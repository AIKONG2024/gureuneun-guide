package app.gureuneun.guide.engine

import app.gureuneun.guide.core.StoryLength
import app.gureuneun.guide.sdk.SpeechBridge

/**
 * 이야기를 문장 단위로 읽어 주는 재생기.
 * 방향지시등, 길 안내, 사용자 일시정지 같은 '멈춤 사유'가 하나라도 있으면 읽지 않고,
 * 사유가 모두 풀리면 멈췄던 문장부터 다시 읽음.
 */
class StoryPlayer(private val speech: SpeechBridge, private val changed: () -> Unit) {

    /** 문장을 읽기 시작할 때마다 불림 (휴대폰 자막을 맞추는 데 씀) */
    var onSentence: ((Item, Int) -> Unit)? = null

    enum class Kind { SYSTEM, ANNOUNCE, STORY }

    data class Item(
        val key: String,
        val placeId: String?,
        val title: String,
        val lang: String,
        val sentences: List<String>,
        val kind: Kind,
        val length: StoryLength? = null,
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

    val holdReasons: Set<String> get() = holds
    val speaking: Boolean get() = speakingNow
    val busy: Boolean get() = current != null || queue.isNotEmpty()
    val pending: List<Item> get() = queue.toList()

    fun enqueue(item: Item) {
        queue.addLast(item)
        pump()
    }

    fun enqueueFront(item: Item) {
        queue.addFirst(item)
        pump()
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
        val cur = current ?: return
        interruptSentence()
        current = null
        index = 0
        lastFinished = cur
        cur.onDone?.invoke(false)
        changed()
        pump()
    }

    /** 지금(또는 방금 끝난) 이야기를 처음부터 다시 */
    fun replay(): Boolean {
        val target = current ?: lastFinished ?: return false
        interruptSentence()
        if (current == null) target.onStart?.invoke()
        current = target
        index = 0
        changed()
        pump()
        return true
    }

    fun stopAll() {
        interruptSentence()
        queue.clear()
        current = null
        index = 0
        changed()
    }

    private fun interruptSentence() {
        generation++
        if (speakingNow) {
            speakingNow = false
            speech.stop()
        }
    }

    private fun pump() {
        if (speakingNow || holds.isNotEmpty()) return
        var item = current
        if (item == null) {
            item = queue.removeFirstOrNull() ?: run { changed(); return }
            current = item
            index = 0
            item.onStart?.invoke()
        }
        if (index >= item.sentences.size) {
            finishCurrent()
            return
        }
        val gen = ++generation
        speakingNow = true
        changed()
        onSentence?.invoke(item, index)
        speech.speak(item.sentences[index], item.lang) { completed ->
            if (gen != generation) return@speak
            speakingNow = false
            // 멈춤 사유 없이 실패한 문장은 건너뛰어 같은 문장에서 맴돌지 않게 함
            if (completed || holds.isEmpty()) index++
            if (index >= item.sentences.size) finishCurrent() else {
                changed()
                pump()
            }
        }
    }

    private fun finishCurrent() {
        val cur = current
        current = null
        index = 0
        lastFinished = cur
        cur?.onDone?.invoke(true)
        changed()
        pump()
    }

    companion object {
        private val SPLIT = Regex("(?<=[.!?。])\\s+")

        fun sentences(text: String): List<String> =
            text.trim().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
