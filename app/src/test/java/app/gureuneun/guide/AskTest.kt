package app.gureuneun.guide

import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.engine.Ask
import app.gureuneun.guide.engine.Ask.Intent
import app.gureuneun.guide.engine.Ask.Side
import org.junit.Assert.assertEquals
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

    @Test fun tripAndCarQuestions() {
        check("얼마나 남았어?", Intent.REMAIN)
        check("언제 도착해", Intent.REMAIN)
        // "얼마나 남았어"가 들어 있어도 배터리 이야기면 충전으로
        check("배터리 얼마나 남았어", Intent.CHARGE)
        check("충전소 어디 있어", Intent.CHARGE)
        check("화장실 가고 싶어", Intent.REST)
        check("졸려", Intent.REST)
        check("너무 더워", Intent.HOT)
        check("좀 추운데", Intent.COLD)
        check("퀴즈 내 줘", Intent.QUIZ)
        check("창문 열어 줘", Intent.WINDOW_OPEN)
        check("창문 닫아", Intent.WINDOW_CLOSE)
        check("여기 어디야", Intent.WHERE)
        check("아이 모드 켜 줘", Intent.KID_ON)
        check("아이 모드 꺼", Intent.KID_OFF)
        check("다른 차에 주차장 보내 줘", Intent.SHARE_PARKING)
        check("뒤차한테 카페 공유해", Intent.SHARE_CAFE)
        check("Send the parking lot to the other car", Intent.SHARE_PARKING)
        check("How long until we arrive?", Intent.REMAIN)
        // 영어 left 가 들어 있어도 남은 시간 질문
        check("How much time left", Intent.REMAIN)
        check("Is there a restroom nearby", Intent.REST)
        check("It's too hot", Intent.HOT)
        check("음", Intent.UNKNOWN)
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
}
