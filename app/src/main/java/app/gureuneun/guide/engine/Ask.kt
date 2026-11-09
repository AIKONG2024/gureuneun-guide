package app.gureuneun.guide.engine

import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.Ko
import app.gureuneun.guide.core.L10n
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.pick
import kotlin.math.abs

/**
 * "저거 뭐야?" 같은 말을 알아듣고 답을 만드는 곳.
 * 차 안 음성 인식은 끝이 잘리거나("오른쪽이 보이") 조사가 바뀌어 들어올 때가 많아서
 * 문장을 통째로 맞추지 않고 낱말 조각으로 판단함. 화면이나 서버 없이도 돌아가는 순수 코드라 단위 시험을 붙여 둠.
 */
object Ask {

    enum class Intent { WHAT, WHERE, REPLAY, NEXT, PAUSE, RESUME, REMAIN, QUIZ, REST, CHARGE, HOT, COLD, WINDOW_OPEN, WINDOW_CLOSE, KID_ON, KID_OFF, SHARE_PARKING, SHARE_CAFE, UNKNOWN }

    enum class Side { LEFT, RIGHT, AHEAD, BEHIND }

    data class Parsed(val intent: Intent, val side: Side?, val text: String)

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    /** 말한 문장을 의도와 방향으로 나눔 */
    fun parse(raw: String): Parsed {
        val t = raw.lowercase().replace(Regex("[\\s.,!?~'\"]"), "")
        val side = when {
            has(t, "오른", "우측", "right") -> Side.RIGHT
            has(t, "왼", "좌측", "left") -> Side.LEFT
            has(t, "뒤에", "뒤쪽", "지나온", "behind") -> Side.BEHIND
            has(t, "앞에", "앞쪽", "정면", "ahead", "infront") -> Side.AHEAD
            else -> null
        }
        val intent = when {
            // 그룹 여행: "다른 차에 주차장 보내 줘"
            has(t, "보내", "공유", "알려줘다른", "share", "send") && has(t, "주차", "parking") -> Intent.SHARE_PARKING
            has(t, "보내", "공유", "share", "send") && has(t, "카페", "cafe", "coffee") -> Intent.SHARE_CAFE
            has(t, "창문", "window") && has(t, "열", "내려", "open", "down") -> Intent.WINDOW_OPEN
            has(t, "창문", "window") && has(t, "닫", "올려", "close", "up") -> Intent.WINDOW_CLOSE
            has(t, "아이모드", "어린이모드", "아이버전", "kidmode", "kidsmode", "kidversion") && has(t, "꺼", "끄", "그만", "off") -> Intent.KID_OFF
            has(t, "아이모드", "어린이모드", "아이버전", "kidmode", "kidsmode", "kidversion") -> Intent.KID_ON
            has(t, "충전", "배터리", "주유", "기름", "charg", "battery", "fuel", "gasstation") -> Intent.CHARGE
            has(t, "화장실", "휴게소", "쉬었다", "쉬어가", "쉬어갈", "쉬자", "졸려", "피곤", "restroom", "bathroom", "toilet", "restarea", "takeabreak", "tired", "sleepy") -> Intent.REST
            has(t, "더워", "덥다", "덥네", "더운데", "덥지", "toohot", "itshot", "sohot", "warm") -> Intent.HOT
            has(t, "추워", "춥다", "춥네", "추운데", "춥지", "toocold", "itscold", "socold", "chilly") -> Intent.COLD
            has(t, "퀴즈", "문제내", "문제줘", "quiz") -> Intent.QUIZ
            has(t, "계속", "이어서", "이어줘", "다시시작", "continue", "resume", "goon", "keepgoing") -> Intent.RESUME
            has(t, "다시", "한번더", "못들었", "뭐라고", "again", "repeat", "replay", "saythat") -> Intent.REPLAY
            has(t, "다음이야기", "다음거", "다음으로", "넘겨", "건너", "스킵", "nextstory", "skip") || t == "다음" || t == "next" -> Intent.NEXT
            has(t, "멈춰", "그만", "조용", "잠깐", "쉿", "정지", "stop", "pause", "quiet", "shush", "holdon") -> Intent.PAUSE
            has(t, "얼마나", "남았", "언제도착", "몇분", "도착해", "도착까지", "howlong", "howfar", "howmuchlonger", "arewethere", "eta", "timeleft") -> Intent.REMAIN
            side == null && has(t, "여기어디", "지금어디", "여기가어디", "어디쯤", "wherearewe", "whereami") -> Intent.WHERE
            side != null -> Intent.WHAT
            has(t, "뭐", "무슨", "무엇", "저거", "저건", "저기", "이거", "이건", "보이", "건물", "what", "that", "building", "island", "bridge") -> Intent.WHAT
            else -> Intent.UNKNOWN
        }
        return Parsed(intent, side, raw.trim())
    }

    // ------------------------------------------------------------ "저거 뭐야?"

    data class Candidate(val id: String, val name: L10n, val pos: LatLng, val fact: L10n, val visibleM: Double, val isPlace: Boolean)

    data class Answer(val text: String, val targetId: String?, val targetName: String?, val side: Side?, val distanceM: Double?)

    /** 창밖으로 보일 수 있는 곳: 이야기 정류장이 아닌 볼거리(sights)와 명소 */
    fun candidates(sc: Scenario): List<Candidate> {
        val sights = sc.sights.map { Candidate(it.id, it.name, it.pos, it.fact, it.visibleM, false) }
        val places = (sc.places.filter { it.kind != PlaceKind.CITY } + sc.pickables.filter { it.kind == PlaceKind.LANDMARK || it.kind == PlaceKind.SEA })
            .distinctBy { it.id }
            .filter { p -> sights.none { it.id == p.id } }
            .map { p -> Candidate(p.id, p.name, p.pos, p.stories.mapValues { it.value.short }, maxOf(600.0, p.radiusM * 2.5), true) }
        return sights + places
    }

    /** 진행 방향 기준 상대 각도. 오른쪽이 +, 왼쪽이 - (−180~180) */
    fun relative(car: LatLng, heading: Double, target: LatLng): Double =
        ((Geo.bearing(car, target) - heading) + 540.0) % 360.0 - 180.0

    fun sideOf(rel: Double): Side = when {
        abs(rel) >= 135 -> Side.BEHIND
        rel > 25 -> Side.RIGHT
        rel < -25 -> Side.LEFT
        else -> Side.AHEAD
    }

    private fun matches(side: Side, rel: Double): Boolean = when (side) {
        Side.RIGHT -> rel in 15.0..165.0
        Side.LEFT -> rel in -165.0..-15.0
        Side.AHEAD -> abs(rel) <= 40
        Side.BEHIND -> abs(rel) >= 130
    }

    /** 물어본 쪽에 보이는 곳 가운데 가장 그럴듯한 곳. 가까울수록, 물어본 방향 한가운데일수록 점수가 좋음 */
    fun pick(cands: List<Candidate>, car: LatLng, heading: Double, side: Side?): Pair<Candidate, Double>? {
        return cands.mapNotNull { c ->
            val d = Geo.distanceM(car, c.pos)
            if (d > c.visibleM) return@mapNotNull null
            val rel = relative(car, heading, c.pos)
            if (side != null && !matches(side, rel)) return@mapNotNull null
            val ideal = when (side ?: sideOf(rel)) {
                Side.RIGHT, Side.LEFT -> 90.0
                Side.AHEAD -> 0.0
                Side.BEHIND -> 180.0
            }
            val score = d / c.visibleM + 0.35 * abs(abs(rel) - ideal) / 90.0 + if (side == null && sideOf(rel) == Side.BEHIND) 0.6 else 0.0
            Triple(c, d, score)
        }.minByOrNull { it.third }?.let { it.first to it.second }
    }

    private fun sideKo(s: Side) = when (s) {
        Side.RIGHT -> "오른쪽으로"
        Side.LEFT -> "왼쪽으로"
        Side.AHEAD -> "앞에"
        Side.BEHIND -> "뒤로"
    }

    private fun sideEn(s: Side) = when (s) {
        Side.RIGHT -> "Look to your right."
        Side.LEFT -> "Look to your left."
        Side.AHEAD -> "Straight ahead."
        Side.BEHIND -> "Just behind us."
    }

    private fun sideWordKo(s: Side) = when (s) {
        Side.RIGHT -> "오른쪽"
        Side.LEFT -> "왼쪽"
        Side.AHEAD -> "앞쪽"
        Side.BEHIND -> "뒤쪽"
    }

    private fun sideWordEn(s: Side) = when (s) {
        Side.RIGHT -> "on your right"
        Side.LEFT -> "on your left"
        Side.AHEAD -> "ahead"
        Side.BEHIND -> "behind us"
    }

    /** 소개 문장의 첫 문장이 "천마총이에요." 처럼 이름만 말하는 문장이면 뺌 (앞에 방향을 붙여 다시 말하므로) */
    internal fun dropNameSentence(fact: String, name: String): String {
        val ss = StoryPlayer.sentences(fact)
        val first = ss.firstOrNull() ?: return fact
        val bare = first.removeSuffix(".").removeSuffix("이에요").removeSuffix("예요").trim()
        return if (bare == name || bare == Ko.spokenName(name)) ss.drop(1).joinToString(" ") else fact
    }

    /**
     * 답 문장을 만듦. next 는 보이는 곳이 없을 때 "조금 더 가면" 하고 알려 줄 다음 명소.
     * telling 은 지금 들려주고 있는 명소 id. 물어본 곳이 바로 그곳이면 같은 이야기를 되풀이하지 않고 짧게 짚어 줌
     */
    fun whatIsThere(sc: Scenario, car: LatLng, heading: Double, side: Side?, lang: String, next: Pair<String, Double>? = null, telling: String? = null): Answer {
        val found = pick(candidates(sc), car, heading, side)
        if (found == null) {
            val nextKo = next?.let { (n, d) -> " 조금 더 가면 ${n}${Ko.iGa(n)} 나와요. ${Ko.distance(d, "ko")} 남았어요." } ?: ""
            val nextEn = next?.let { (n, d) -> " $n is coming up in ${Ko.distance(d, "en")}." } ?: ""
            val text = if (lang == "en") {
                (if (side != null) "Nothing to point out ${sideWordEn(side)} right now." else "Nothing to point out right now.") + nextEn
            } else {
                (if (side != null) "지금 ${sideWordKo(side)}에는 따로 알려 드릴 곳이 안 보여요." else "지금은 창밖으로 알려 드릴 곳이 안 보여요.") + nextKo
            }
            return Answer(text, null, null, side, null)
        }
        val (c, d) = found
        val s = side ?: sideOf(relative(car, heading, c.pos))
        val name = c.name.pick(lang)
        if (c.id == telling) {
            val text = if (lang == "en") {
                sideEn(s) + " That's $name, the one I'm telling you about." + if (d >= 150) " It's ${Ko.distance(d, "en")} away." else ""
            } else {
                "${sideKo(s)} 보이는 곳이 지금 이야기하는 ${Ko.spokenName(name)}${Ko.ieyo(name)}." + if (d >= 150) " 여기서 ${Ko.distance(d, "ko")} 떨어져 있어요." else ""
            }
            return Answer(text, c.id, name, s, d)
        }
        val fact = c.fact.pick(lang)
        val far = d >= 150
        val text = if (lang == "en") {
            // 명소 소개는 "This is ..."로 시작하므로 창밖을 가리키는 말 "That's ..."로 바꿈
            val f = fact.replaceFirst(Regex("^This is "), "That's ").replaceFirst(Regex("^These are "), "Those are ")
            val body = if (f.contains(name.substringBefore(" "))) f else "That's $name. $f"
            sideEn(s) + " " + body + if (far) " It's ${Ko.distance(d, "en")} away." else ""
        } else {
            // 소개 글이 "대릉원이에요."로 시작하면 그 문장은 빼고 방향과 함께 한 번만 말함
            val rest = dropNameSentence(fact, name)
            "${sideKo(s)} 보이는 곳은 ${Ko.spokenName(name)}${Ko.ieyo(name)}. " + rest + if (far) " 여기서 ${Ko.distance(d, "ko")} 떨어져 있어요." else ""
        }
        return Answer(text.replace(Regex("\\s+"), " ").trim(), c.id, name, s, d)
    }
}
