package app.gureuneun.guide

import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.engine.Ask
import app.gureuneun.guide.engine.Ask.Intent
import app.gureuneun.guide.engine.Ask.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차 안에서 들어오는 말(끝이 잘리거나 조사가 바뀐 문장 포함)을 제대로 알아듣는지 */
class AskTest {

    private fun check(text: String, intent: Intent, side: Side? = null) {
        val p = Ask.parse(text)
        assertEquals("의도: \"$text\"", intent, p.intent)
        if (side != null) assertEquals("방향: \"$text\"", side, p.side)
    }

    @Test fun whatQuestions() {
        check("오른쪽에 보이는 거 뭐야?", Intent.WHAT, Side.RIGHT)
        check("오른쪽에 보이는 건 뭐야?", Intent.WHAT, Side.RIGHT)
        // 에뮬레이터 인식기가 실제로 돌려준 잘린 결과
        check("오른쪽이 보이", Intent.WHAT, Side.RIGHT)
        check("오른쪽이 보이는 건", Intent.WHAT, Side.RIGHT)
        check("왼쪽에 있는 건물 뭐야", Intent.WHAT, Side.LEFT)
        check("저 앞에 다리 이름이 뭐야", Intent.WHAT, Side.AHEAD)
        check("방금 지나온 거 뭐였어", Intent.WHAT, Side.BEHIND)
        check("저거 뭐야", Intent.WHAT)
        assertNull(Ask.parse("저거 뭐야").side)
        check("What's that on the right?", Intent.WHAT, Side.RIGHT)
        check("what is that building on the left", Intent.WHAT, Side.LEFT)
    }

    @Test fun controlCommands() {
        check("여행 끝내 줘", Intent.END_TRIP)
        check("오늘은 여기까지 하자", Intent.END_TRIP)
        // "그만"이 들어 있어도 여행을 끝내자는 말은 멈춤이 아님
        check("여행 그만할래", Intent.END_TRIP)
        check("end the trip", Intent.END_TRIP)
        check("다시 말해 줘", Intent.REPLAY)
        check("한 번 더", Intent.REPLAY)
        check("다시 시작해", Intent.RESUME)
        check("계속해 줘", Intent.RESUME)
        check("다음 이야기", Intent.NEXT)
        check("넘겨", Intent.NEXT)
        check("잠깐 멈춰", Intent.PAUSE)
        check("조용히 해 줘", Intent.PAUSE)
        check("Say that again", Intent.REPLAY)
        check("skip", Intent.NEXT)
        check("pause", Intent.PAUSE)
    }

    @Test fun tripQuestions() {
        check("얼마나 남았어?", Intent.REMAIN)
        check("언제 도착해", Intent.REMAIN)
        check("여기 어디야", Intent.WHERE)
        check("How long until we arrive?", Intent.REMAIN)
        // 영어 left 가 들어 있어도 남은 시간 질문
        check("How much time left", Intent.REMAIN)
        check("Where are we?", Intent.WHERE)
        check("음", Intent.UNKNOWN)
    }

    /** 여행 안내 밖의 부탁(차 상태, 온도, 창문)은 받지 않음. 비슷한 낱말 때문에 엉뚱한 일을 하지 않는지 */
    @Test fun requestsOutsideTheGuideAreNotActedOn() {
        // "얼마나 남았어"가 들어 있어도 배터리 이야기면 남은 길을 답하지 않음
        check("배터리 얼마나 남았어", Intent.UNKNOWN)
        check("충전소 어디 있어", Intent.UNKNOWN)
        check("화장실 가고 싶어", Intent.UNKNOWN)
        check("너무 더워", Intent.UNKNOWN)
        check("It's too hot", Intent.UNKNOWN)
        // 창문을 움직여 달라는 말: '그만', '다시'가 들어 있어도 이야기 멈춤, 다시 듣기로 듣지 않음
        check("창문 열어 줘", Intent.UNKNOWN)
        check("창문 그만 올려", Intent.UNKNOWN)
        check("창문 다시 닫아 줘", Intent.UNKNOWN)
        check("Close the windows", Intent.UNKNOWN)
        // 창밖을 묻는 말은 그대로 질문
        check("창문 밖에 저거 뭐야", Intent.WHAT)
    }

    @Test fun answersFromTheSideAsked() {
        val sc = Scenarios.load("gyeongju")
        // 천마총 300m 남쪽에서 동쪽으로 달리는 차: 천마총은 왼쪽(북쪽)
        val car = LatLng(35.83589, 129.2105)
        val left = Ask.whatIsThere(sc, car, 90.0, Side.LEFT, "ko")
        assertEquals("cheonmachong", left.targetId)
        assertTrue(left.text, left.text.startsWith("왼쪽으로 보이는 곳은 천마총이에요."))
        // 이름만 말하는 첫 문장("천마총이에요.")은 한 번만 나와야 함
        assertEquals(1, Regex("천마총이에요").findAll(left.text).count())
        assertTrue(left.text, left.text.contains("여기서 약 300미터 떨어져 있어요"))

        // 오른쪽(남쪽)에는 보이는 곳이 없으니 다음 명소를 알려 줌
        val right = Ask.whatIsThere(sc, car, 90.0, Side.RIGHT, "ko", next = "첨성대" to 820.0)
        assertNull(right.targetId)
        assertEquals("지금 오른쪽에는 따로 알려 드릴 곳이 안 보여요. 조금 더 가면 첨성대가 나와요. 약 800미터 남았어요.", right.text)

        val en = Ask.whatIsThere(sc, car, 90.0, Side.LEFT, "en")
        assertTrue(en.text, en.text.startsWith("Look to your left. That's Cheonmachong"))
    }

    @Test fun namesAreNotRepeated() {
        assertEquals("하늘로 날아오르는 말 그림이 나왔어요.", Ask.dropNameSentence("천마총이에요. 하늘로 날아오르는 말 그림이 나왔어요.", "천마총"))
        assertEquals("바다 건너 보이는 섬은 비양도예요.", Ask.dropNameSentence("바다 건너 보이는 섬은 비양도예요.", "비양도"))
        // 명소(이야기 정류장)도 "대릉원이에요."를 두 번 말하지 않음
        val sc = Scenarios.load("gyeongju")
        val nearDaereungwon = LatLng(35.83895, 129.21093)
        val a = Ask.whatIsThere(sc, nearDaereungwon, 0.0, Side.RIGHT, "ko")
        assertEquals("daereungwon", a.targetId)
        assertEquals(a.text, 1, Regex("대릉원이에요").findAll(a.text).count())
        val en = Ask.whatIsThere(sc, nearDaereungwon, 0.0, Side.RIGHT, "en")
        assertTrue(en.text, en.text.startsWith("Look to your right. That's Daereungwon."))
        assertEquals(en.text, 1, Regex("Daereungwon").findAll(en.text).count())
        // 지금 들려주는 명소를 물어보면 짧게 짚어 주기만 함
        val busy = Ask.whatIsThere(sc, nearDaereungwon, 0.0, Side.RIGHT, "ko", telling = "daereungwon")
        assertEquals("오른쪽으로 보이는 곳이 지금 이야기하는 대릉원이에요.", busy.text)
    }

    /** 차가 "가는 길에 들를까요?"처럼 물은 뒤 운전자가 화면을 누르지 않고 말로 답하는 경우 */
    @Test fun shortReplies() {
        fun r(text: String) = Ask.reply(text)
        assertEquals(Ask.Reply.YES, r("응"))
        assertEquals(Ask.Reply.YES, r("네"))
        assertEquals(Ask.Reply.YES, r("응 그래"))
        assertEquals(Ask.Reply.YES, r("응 가자"))
        assertEquals(Ask.Reply.YES, r("좋아"))
        assertEquals(Ask.Reply.YES, r("넣어 줘"))
        assertEquals(Ask.Reply.YES, r("Yes please"))
        assertEquals(Ask.Reply.YES, r("okay"))
        assertEquals(Ask.Reply.NO, r("아니"))
        assertEquals(Ask.Reply.NO, r("괜찮아요"))
        assertEquals(Ask.Reply.NO, r("No thanks"))
        assertEquals(Ask.Reply.NO, r("No thank you"))
        assertEquals(Ask.Reply.NO, r("그대로 둬"))
        assertEquals(Ask.Reply.LATER, r("나중에"))
        assertEquals(Ask.Reply.LATER, r("not now"))
        assertEquals(Ask.Reply.WAYPOINT, r("가는 길에 들렀다 가자"))
        assertEquals(Ask.Reply.WAYPOINT, r("경유지로 넣어 줘"))
        assertEquals(Ask.Reply.DESTINATION, r("목적지로 바꿔 줘"))
        assertEquals(Ask.Reply.DESTINATION, r("take us there"))
        // 대답이 아닌 질문은 그대로 질문으로 처리하게 null
        assertNull(r("어디야"))
        assertNull(r("오른쪽에 보이는 거 뭐야"))
        assertNull(r("예쁘다"))
        // 차를 조작해 달라는 말은 대답이 아님
        assertNull(r("창문 열어 줘"))
        assertNull(r("시원하게 해 줘"))
        assertNull(r(""))
    }

    /** 물은 게 없을 때 들어온 "응", "괜찮아요"는 대답만 한 말로 알아봄 (길게 되묻지 않게) */
    @Test fun bareReplies() {
        assertTrue(Ask.isBareReply("응"))
        assertTrue(Ask.isBareReply("괜찮아요"))
        assertTrue(Ask.isBareReply("나중에"))
        assertTrue(Ask.isBareReply("Yes please"))
        assertFalse(Ask.isBareReply("오른쪽에 보이는 거 뭐야"))
        assertFalse(Ask.isBareReply("가는 길에 들렀다 가자고 했잖아"))
        assertFalse(Ask.isBareReply(""))
    }

    /** 물음이 떠 있을 때: 짧은 대답만 대답으로, 다른 부탁은 그대로 부탁으로 */
    @Test fun answersDependOnThePrompt() {
        fun a(text: String, p: Ask.Prompt) = Ask.answerFor(text, p)
        assertEquals(Ask.Reply.YES, a("응", Ask.Prompt.PICK))
        assertEquals(Ask.Reply.YES, a("그래 가자", Ask.Prompt.PICK))
        assertEquals(Ask.Reply.YES, a("가자", Ask.Prompt.PICK))
        assertEquals(Ask.Reply.WAYPOINT, a("가는 길에 들렀다 가자", Ask.Prompt.PICK))
        assertEquals(Ask.Reply.WAYPOINT, a("stop on the way", Ask.Prompt.PICK))
        assertEquals(Ask.Reply.LATER, a("나중에", Ask.Prompt.PICK))
        // 장소를 묻는 중에 "창문 열어 줘"는 장소 대답이 아님
        assertNull(a("창문 열어 줘", Ask.Prompt.PICK))
        assertNull(a("시원하게 해 줘", Ask.Prompt.PICK))
        // "다음에 보이는 거 뭐야"는 미루는 말이 아니라 질문
        assertNull(a("다음에 보이는 거 뭐야", Ask.Prompt.PICK))
        // 창문 이야기는 어떤 물음에도 대답이 아님 ("열어", "지 마"가 들어 있어도)
        assertNull(a("창문 열어 줘", Ask.Prompt.END))
        assertNull(a("창문 열지 마", Ask.Prompt.PICK))
        assertNull(a("Close the windows please", Ask.Prompt.END))
        // 여행을 끝낼지 묻는 중
        assertEquals(Ask.Reply.YES, a("응 여행 끝내 줘", Ask.Prompt.END))
        assertEquals(Ask.Reply.YES, a("끝내자", Ask.Prompt.END))
        assertNull(a("목적지로 바꿔 줘", Ask.Prompt.END))
        assertEquals(Ask.Reply.LATER, a("나중에", Ask.Prompt.END))
        assertEquals(Ask.Reply.NO, a("아니 그만해", Ask.Prompt.END))
        // 질문은 질문으로
        assertNull(a("얼마나 남았어", Ask.Prompt.END))
        assertNull(a("오른쪽에 보이는 거 뭐야", Ask.Prompt.END))
    }
}
