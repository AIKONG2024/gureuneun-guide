package app.gureuneun.guide

import app.gureuneun.guide.engine.StoryPlayer
import app.gureuneun.guide.sdk.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 발화 번호(지난 발화 콜백 무시), 오류 재시도, 끊긴 문장 다시 읽기, 감시 시간 (가짜 음성, 가짜 시계) */
class StoryPlayerTest {

    private class FakeSpeaker : Speaker {
        class Call(val id: Long, val text: String, val onEnd: (Speaker.End) -> Unit)

        val calls = mutableListOf<Call>()
        var stops = 0
        private var seq = 0L

        override fun utter(text: String, lang: String, onEnd: (Speaker.End) -> Unit): Long {
            val c = Call(++seq, text, onEnd)
            calls += c
            return c.id
        }

        override fun stop() {
            stops++
        }

        fun last() = calls.last()
    }

    private val speaker = FakeSpeaker()
    private var now = 0L
    private val later = mutableListOf<() -> Unit>()

    private fun runLater() {
        val tasks = later.toList()
        later.clear()
        tasks.forEach { it() }
    }

    private fun player() = StoryPlayer(speaker, { now }, { _, block -> later += block }) {}

    private fun item(key: String, vararg sentences: String, done: MutableList<Boolean>? = null) = StoryPlayer.Item(
        key = key,
        placeId = key,
        title = key,
        lang = "ko",
        sentences = sentences.toList(),
        kind = StoryPlayer.Kind.STORY,
        yieldable = true,
        onDone = { done?.add(it) },
    )

    @Test
    fun lateCallbackAfterSkipIsIgnored() {
        val p = player()
        val done = mutableListOf<Boolean>()
        p.enqueue(item("a", "첫 문장.", "둘째 문장.", done = done))
        val old = speaker.last()
        p.skip()
        assertEquals(listOf(false), done)
        assertNull(p.current)
        // 건너뛴 뒤에 온 지난 발화의 끝 알림
        old.onEnd(Speaker.End.DONE)
        assertEquals(1, speaker.calls.size)
        assertEquals(listOf(false), done)
        assertNull(p.current)
    }

    @Test
    fun lateCallbackDoesNotEndTheNextStory() {
        val p = player()
        p.enqueue(item("a", "A1."))
        p.enqueue(item("b", "B1.", "B2."))
        val a1 = speaker.last()
        p.skip()
        assertEquals("B1.", speaker.last().text)
        a1.onEnd(Speaker.End.DONE)
        assertEquals("b", p.current?.key)
        assertEquals(0, p.index)
        assertTrue(p.speaking)
        speaker.last().onEnd(Speaker.End.DONE)
        assertEquals("B2.", speaker.last().text)
        assertEquals(1, p.index)
    }

    @Test
    fun errorRetriesSameSentenceTwiceThenMovesOn() {
        val p = player()
        p.enqueue(item("a", "A1.", "A2."))
        speaker.last().onEnd(Speaker.End.ERROR)
        assertFalse(p.speaking)
        assertEquals(1, later.size)
        runLater()
        assertEquals("A1.", speaker.last().text)
        assertEquals(2, speaker.calls.size)
        speaker.last().onEnd(Speaker.End.ERROR)
        runLater()
        assertEquals("A1.", speaker.last().text)
        assertEquals(3, speaker.calls.size)
        // 세 번째도 안 되면 다음 문장으로
        speaker.last().onEnd(Speaker.End.ERROR)
        assertEquals("A2.", speaker.last().text)
        assertEquals(4, speaker.calls.size)
        assertTrue(later.isEmpty())
    }

    @Test
    fun retryIsCancelledByHoldAndResumesOnRelease() {
        val p = player()
        p.enqueue(item("a", "A1."))
        speaker.last().onEnd(Speaker.End.ERROR)
        p.hold("user")
        runLater()
        assertEquals(1, speaker.calls.size)
        p.release("user")
        assertEquals(2, speaker.calls.size)
        assertEquals("A1.", speaker.last().text)
    }

    @Test
    fun timeoutCountsAsHeard() {
        val p = player()
        p.enqueue(item("a", "A1.", "A2."))
        speaker.last().onEnd(Speaker.End.TIMEOUT)
        assertEquals("A2.", speaker.last().text)
        assertTrue(later.isEmpty())
    }

    @Test
    fun holdEarlyInSentenceRereadsIt() {
        val p = player()
        val long = "가".repeat(40) + "."
        p.enqueue(item("a", long, "다음."))
        now = 1000
        p.hold("turn")
        assertEquals(1, speaker.stops)
        p.release("turn")
        assertEquals(long, speaker.last().text)
    }

    @Test
    fun holdNearEndOfSentenceMovesOn() {
        val p = player()
        val long = "가".repeat(40) + "."
        p.enqueue(item("a", long, "다음."))
        // 41자 × 125ms 의 70% 를 넘겨 읽음
        now = 5000
        p.hold("turn")
        p.release("turn")
        assertEquals("다음.", speaker.last().text)
    }

    @Test
    fun watchTimeGrowsWithSentenceLength() {
        assertEquals(6500L, Speaker.watchMs("가".repeat(10), "ko"))
        assertEquals(5400L, Speaker.watchMs("a".repeat(10), "en"))
        assertEquals(30_000L, Speaker.watchMs("가".repeat(1000), "ko"))
    }

    /** 이야기 중에 끼워 말한 물음도 끝나면 onDone 이 와야 엔진이 마이크를 열 수 있음. 듣는 동안 멈췄다가 답 뒤에 이야기를 이어 감 */
    @Test
    fun interjectedPromptReportsDoneThenStoryResumesAfterListening() {
        val p = player()
        val storyDone = mutableListOf<Boolean>()
        val askDone = mutableListOf<Boolean>()
        p.enqueue(item("story", "S1.", "S2.", done = storyDone))
        p.interject(item("ask", "가는 길에 들를까요?", done = askDone).copy(onDone = { askDone.add(it); if (it) p.hold("listen") }))
        // 지금 문장(S1)이 끝나는 대로 물음을 끼워 말함
        speaker.last().onEnd(Speaker.End.DONE)
        assertEquals("가는 길에 들를까요?", speaker.last().text)
        speaker.last().onEnd(Speaker.End.DONE)
        assertEquals(listOf(true), askDone)
        // 듣는 동안은 이야기를 멈춤
        assertEquals("story", p.current?.key)
        assertFalse(p.speaking)
        // 지금까지 읽은 것은 S1 과 물음 두 문장뿐
        assertEquals(2, speaker.calls.size)
        p.release("listen")
        assertEquals("S2.", speaker.last().text)
        speaker.last().onEnd(Speaker.End.DONE)
        assertEquals(listOf(true), storyDone)
    }
}
