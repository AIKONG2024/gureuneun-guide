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
 *
 * 알아듣는 말은 여행 안내에 필요한 것만 둠: 창밖에 보이는 곳, 지금 어디인지, 남은 길, 이야기 다시 듣기와 넘기기와 멈춤,
 * 여행 끝내기, 그리고 차가 물은 것에 대한 짧은 대답. 온도나 창문 같은 차 조작은 차의 음성 비서가 할 일이라 받지 않음.
 */
object Ask {

    enum class Intent { WHAT, WHERE, REPLAY, NEXT, PAUSE, RESUME, REMAIN, END_TRIP, UNKNOWN }

    /** 차가 먼저 물어본 것(고른 장소, 여행 끝)에 대한 짧은 대답 */
    enum class Reply { YES, NO, LATER, DESTINATION, WAYPOINT }

    /** 지금 떠 있는 물음의 종류: 고른 장소, 여행 끝, 아이 모드로 갈지, 창밖 보물찾기 */
    enum class Prompt { PICK, END, KID, HUNT }

    enum class Side { LEFT, RIGHT, AHEAD, BEHIND }

    data class Parsed(val intent: Intent, val side: Side?, val text: String)

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    private fun squeeze(raw: String) = raw.lowercase().replace(Regex("[\\s.,!?~'\"]"), "")

    /** 말한 문장을 의도와 방향으로 나눔 */
    fun parse(raw: String): Parsed {
        val t = squeeze(raw)
        val side = when {
            has(t, "오른", "우측", "right") -> Side.RIGHT
            has(t, "왼", "좌측", "left") -> Side.LEFT
            has(t, "뒤에", "뒤쪽", "지나온", "behind") -> Side.BEHIND
            has(t, "앞에", "앞쪽", "정면", "ahead", "infront") -> Side.AHEAD
            else -> null
        }
        val intent = when {
            // "그만"이 들어 있어도 여행 전체를 끝내자는 말이면 멈춤이 아니라 여행 끝
            has(t, "여행끝", "여행마치", "여행그만", "여행종료", "오늘은여기까지", "endtrip", "endthetrip", "finishthetrip", "finishtrip", "endourtrip") -> Intent.END_TRIP
            // 창문을 움직여 달라는 말은 받지 않음 (차 조작은 이 앱이 하지 않음). "창문 그만 올려"의 '그만', "창문 다시 닫아"의 '다시'를
            // 이야기 조작으로 잘못 듣지 않게 먼저 거름. "창문 밖에 저거 뭐야"처럼 움직이는 말이 없으면 그대로 질문
            has(t, "창문", "window") && has(t, "열", "닫", "올려", "내려", "open", "close", "shut", "roll", "lower") -> Intent.UNKNOWN
            // "배터리 얼마나 남았어"는 남은 길을 묻는 말이 아님 (차 상태는 이 앱이 답하지 않음)
            has(t, "배터리", "기름", "연료", "충전", "주유", "battery", "fuel", "charg", "gasstation") -> Intent.UNKNOWN
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

    // 한 글자 대답은 "어디야", "예쁘다"처럼 다른 말의 첫 글자와 겹치므로 그 말만 했을 때만 대답으로 봄
    // "해 줘"도 마찬가지 ("시원하게 해 줘"를 장소 물음의 대답으로 듣지 않게)
    private val YES_KO = setOf("응", "응응", "네", "네네", "예", "넹", "웅", "어어", "해줘", "해주세요", "부탁해", "부탁해요")
    private val YES_EN = listOf("yes", "yeah", "yep", "ok", "okay", "sure", "please")

    /**
     * 차가 물어본 직후의 짧은 대답을 알아들음. 물음과 상관없는 말이면 null (그때는 parse 로 넘김).
     * "괜찮아요"는 우리말에서 거절이라 NO 로 봄
     */
    fun reply(raw: String): Reply? {
        val t = squeeze(raw)
        if (t.isEmpty()) return null
        return when {
            has(t, "목적지", "바로가", "거기로가", "그리로가", "destination", "gothere", "takeusthere", "navigatethere") -> Reply.DESTINATION
            has(t, "경유", "들렀다", "들러", "들르", "들렀가", "가는길에", "waypoint", "addastop", "asastop", "ontheway") -> Reply.WAYPOINT
            // "다음에 보이는 거"처럼 질문에도 쓰이는 '다음에'는 빼고 미루는 말만
            has(t, "나중에", "다음에할", "다음에하", "이따", "later", "notnow", "maybelater") -> Reply.LATER
            // "넣지 마", "하지 말고"처럼 하지 말라는 말은 '넣어' 같은 낱말이 들어 있어도 거절
            has(t, "아니", "아뇨", "싫", "괜찮아", "됐어", "필요없", "안할래", "안해", "안좋", "지마", "지말", "그대로둬", "nothank", "nope", "dont", "donot") || t == "no" -> Reply.NO
            has(t, "그래", "좋아", "알았어", "알겠어", "넣어", "오케이", "그렇게", "doit", "goahead", "letsgo") -> Reply.YES
            // "응 가자", "응 끝내 줘"처럼 응으로 시작하는 짧은 말도 대답
            t in YES_KO || (t.startsWith("응") && t.length <= 8) -> Reply.YES
            YES_EN.any { w -> t == w || (t.startsWith(w) && t.length <= w.length + 6) } -> Reply.YES
            else -> null
        }
    }

    /**
     * "응", "괜찮아요"처럼 대답만 한 짧은 말인지 (물은 게 없을 때 들어온 대답을 알아보는 데 씀).
     * 한글은 여덟 자, 영어는 낱말이 길어 열네 자까지를 짧은 말로 봄
     */
    fun isBareReply(raw: String): Boolean {
        val t = squeeze(raw)
        val limit = if (t.all { it.code < 128 }) 14 else 8
        return t.length <= limit && reply(raw) != null
    }

    /**
     * 물음이 떠 있을 때 들은 말을 그 물음의 대답으로 볼지 정함. null 이면 보통 질문으로 처리.
     * "응", "좋아"처럼 짧은 대답은 다른 뜻이 없을 때만 대답으로 보고,
     * "들렀다 가자", "끝내자"처럼 그 물음에만 맞는 말은 그 물음이 떠 있을 때만 대답으로 봄.
     * "창문 열어 줘"처럼 차를 조작해 달라는 말은 대답으로 보지 않음 (장소를 묻는 중에 들었다고 장소를 넣으면 안 되니까)
     */
    fun answerFor(raw: String, prompt: Prompt): Reply? {
        val t = squeeze(raw)
        val r = reply(raw)
        val q = parse(raw).intent
        // 창밖 보물찾기는 "찾았다"만 답으로 봄 ("응" 한마디로 스티커를 주지 않음). 그 밖의 말은 보통 질문으로 처리
        if (prompt == Prompt.HUNT) {
            return when {
                has(t, "찾았", "저기있", "저기다", "보인다", "보여요", "found", "iseeit", "seeit", "thereitis") -> Reply.YES
                has(t, "넘어가", "모르겠", "못찾", "skip") -> Reply.NO
                else -> null
            }
        }
        return when {
            has(t, "창문", "window") -> null
            r == Reply.LATER -> r
            r == Reply.DESTINATION || r == Reply.WAYPOINT -> if (prompt == Prompt.PICK) r else null
            r == Reply.NO && (q == Intent.UNKNOWN || q == Intent.PAUSE) -> Reply.NO
            r == Reply.YES && q == Intent.UNKNOWN -> Reply.YES
            prompt == Prompt.END && (q == Intent.END_TRIP || has(t, "끝내", "마치자")) -> Reply.YES
            prompt == Prompt.PICK && q == Intent.UNKNOWN && has(t, "가자", "갑시다", "가보자") -> Reply.YES
            else -> null
        }
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
                // "on your right right now"처럼 겹쳐 들리지 않게
                (if (side != null) "Nothing to point out ${sideWordEn(side)} at the moment." else "Nothing to point out right now.") + nextEn
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
