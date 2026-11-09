package app.gureuneun.guide.engine

import android.os.SystemClock
import app.gureuneun.guide.core.StoryLength
import app.gureuneun.guide.sdk.SpeechBridge

/**
 * 이야기를 문장 단위로 읽어 주는 재생기.
 * 방향지시등, 길 안내, 사용자 일시정지 같은 '멈춤 사유'가 하나라도 있으면 읽지 않고,
 * 사유가 모두 풀리면 멈췄던 문장부터 다시 읽음. 다만 거의 다 읽은 문장이거나 같은 문장에서
 * 두 번째로 끊긴 거면 다음 문장으로 넘어감.
 *
 * 사람 가이드처럼 움직이도록 두 가지를 더 함
 * - 새 명소가 나타나면 지금 하던 이야기는 이 문장까지만 하고 차례를 넘김 (yieldCurrent)
 * - 비, 전조등 같은 짧은 알림은 문장 사이에 끼워 말하고 하던 이야기로 돌아감 (interject)
 */
class StoryPlayer(private val speech: SpeechBridge, private val changed: () -> Unit) {

    /** 문장을 읽기 시작할 때마다 불림 (휴대폰 자막을 맞추는 데 씀) */
    var onSentence: ((Item, Int) -> Unit)? = null

    /** SYSTEM 안내, ANNOUNCE "잠시 후 오른쪽에", STORY 명소 이야기, ANSWER 질문에 대한 답, KID 뒷자리 아이 이야기 */
    enum class Kind { SYSTEM, ANNOUNCE, STORY, ANSWER, KID }

    data class Item(
        val key: String,
        val placeId: String?,
        val title: String,
        val lang: String,
        val sentences: List<String>,
        val kind: Kind,
        val length: StoryLength? = null,
        /** 새 명소가 나타나면 지금 문장까지만 읽고 넘겨도 되는 이야기 */
        val yieldable: Boolean = false,
        /** 차례가 왔을 때 true 면 읽지 않고 건너뜀 (이미 지나친 명소) */
        val stale: (() -> Boolean)? = null,
        val onStart: (() -> Unit)? = null,
        val onDone: ((Boolean) -> Unit)? = null,
    ) {
        val text: String get() = sentences.joinToString(" ")
    }

    var current: Item? = null
        private set
    var index: Int = 0
        private set
    var lastFinished: Item? = null
        private set

    private val queue = ArrayDeque<Item>()
    private val holds = LinkedHashSet<String>()
    private var speakingNow = false
    private var generation = 0
    private var sentenceStartedAt = 0L
    private var cutsAtIndex = 0
    private var cutAfterSentence = false
    // 끼워 말할 짧은 알림. 여러 개가 겹치면(퀴즈 정답과 질문의 답이 같이 오면) 차례로 모두 말함
    private val interjections = ArrayDeque<Item>()
    private var interjecting = false
    private var resumeTo: Pair<Item, Int>? = null

    val holdReasons: Set<String> get() = holds
    val speaking: Boolean get() = speakingNow
    val busy: Boolean get() = current != null || queue.isNotEmpty() || interjections.isNotEmpty()
    val pending: List<Item> get() = queue.toList()

    fun enqueue(item: Item) {
        queue.addLast(item)
        pump()
    }

    fun enqueueFront(item: Item) {
        queue.addFirst(item)
        pump()
    }

    /** 짧은 알림을 지금 문장이 끝나는 대로 끼워 말하고, 끝나면 하던 이야기를 이어 감 */
    fun interject(item: Item) {
        if (current == null) {
            enqueueFront(item)
            return
        }
        interjections.addLast(item)
        pump()
    }

    /** 지금 이야기가 양보할 수 있는 이야기면 이 문장까지만 읽고 다음 차례로 넘김 */
    fun yieldCurrent() {
        val back = resumeTo
        if (interjecting && back != null && back.first.yieldable) {
            resumeTo = null
            back.first.onDone?.invoke(false)
            return
        }
        val cur = current ?: return
        if (!cur.yieldable || interjecting) return
        if (speakingNow) cutAfterSentence = true else finishCurrent(false)
    }

    fun hold(reason: String) {
        if (holds.add(reason)) {
            interruptSentence()
            changed()
        }
    }

    fun release(reason: String) {
        if (holds.remove(reason)) {
            changed()
            pump()
        }
    }

    fun isHeld(reason: String) = holds.contains(reason)

    /** 지금 이야기를 건너뜀 */
    fun skip() {
        if (current == null) return
        interruptSentence()
        finishCurrent(false)
    }

    /** 지금(또는 방금 끝난) 이야기를 처음부터 다시 */
    fun replay(): Boolean {
        val target = current ?: lastFinished ?: return false
        interruptSentence()
        if (current == null) target.onStart?.invoke()
        current = target
        index = 0
        cutsAtIndex = 0
        changed()
        pump()
        return true
    }

    fun stopAll() {
        interruptSentence()
        queue.clear()
        current = null
        index = 0
        cutsAtIndex = 0
        cutAfterSentence = false
        interjections.clear()
        interjecting = false
        resumeTo = null
        changed()
    }

    private fun interruptSentence() {
        generation++
        if (!speakingNow) return
        speakingNow = false
        speech.stop()
        val item = current ?: return
        if (index >= item.sentences.size) return
        cutsAtIndex++
        val spoken = SystemClock.elapsedRealtime() - sentenceStartedAt
        val expected = item.sentences[index].length * (if (item.lang == "en") 70L else 125L)
        if (spoken >= expected * 7 / 10 || cutsAtIndex >= 2) {
            index++
            cutsAtIndex = 0
        }
    }

    private fun nextFromQueue(): Item? {
        while (true) {
            val next = queue.removeFirstOrNull() ?: return null
            if (next.stale?.invoke() == true) continue
            return next
        }
    }

    private fun pump() {
        if (speakingNow || holds.isNotEmpty()) return
        // 끼워 말할 차례가 오기 전에 쓸모없어진 알림(이미 답한 질문 등)은 버림
        while (!interjecting && interjections.isNotEmpty() && interjections.first().stale?.invoke() == true) interjections.removeFirst()
        if (interjections.isNotEmpty() && !interjecting) {
            val inter = interjections.removeFirst()
            val cur = current
            if (cur != null && index < cur.sentences.size) resumeTo = cur to index
            else cur?.let { lastFinished = it; it.onDone?.invoke(true) }
            current = inter
            index = 0
            cutsAtIndex = 0
            interjecting = true
            inter.onStart?.invoke()
        }
        var item = current
        if (item == null) {
            item = nextFromQueue() ?: run { changed(); return }
            current = item
            index = 0
            cutsAtIndex = 0
            item.onStart?.invoke()
        }
        if (index >= item.sentences.size) {
            finishCurrent(true)
            return
        }
        // 멈췄다 이어 가는 사이에 명소를 지나쳤으면 남은 문장은 건너뜀
        if (!interjecting && item.stale?.invoke() == true) {
            finishCurrent(false)
            return
        }
        val gen = ++generation
        speakingNow = true
        sentenceStartedAt = SystemClock.elapsedRealtime()
        changed()
        onSentence?.invoke(item, index)
        speech.speak(item.sentences[index], item.lang) { completed ->
            if (gen != generation) return@speak
            speakingNow = false
            // 멈춤 사유 없이 실패한 문장은 건너뛰어 같은 문장에서 맴돌지 않게 함
            if (completed || holds.isEmpty()) {
                index++
                cutsAtIndex = 0
            }
            if (cutAfterSentence) {
                cutAfterSentence = false
                finishCurrent(false)
            } else if (index >= item.sentences.size) {
                finishCurrent(true)
            } else {
                changed()
                pump()
            }
        }
    }

    private fun finishCurrent(completed: Boolean) {
        val cur = current
        current = null
        index = 0
        cutsAtIndex = 0
        cutAfterSentence = false
        if (interjecting) {
            interjecting = false
            val next = interjections.removeFirstOrNull()
            if (next != null) {
                // 끼워 말할 것이 더 있으면 하던 이야기로 돌아가기 전에 이어서 말함
                current = next
                interjecting = true
                next.onStart?.invoke()
            } else {
                resumeTo?.let { (item, at) ->
                    current = item
                    index = at
                }
                resumeTo = null
            }
        } else if (cur != null) {
            lastFinished = cur
            cur.onDone?.invoke(completed)
        }
        changed()
        pump()
    }

    companion object {
        private val SPLIT = Regex("(?<=[.!?。])\\s+")

        fun sentences(text: String): List<String> =
            text.trim().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
