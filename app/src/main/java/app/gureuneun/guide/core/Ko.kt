package app.gureuneun.guide.core

import kotlin.math.roundToInt

/** 소리로 읽을 한국어 문장을 다듬는 도구. 조사, 수 읽기, 거리 읽기 */
object Ko {

    /** 소리로 읽을 이름. 끝에 붙은 "(예시)" 같은 괄호 표기는 뺌 */
    fun spokenName(name: String): String = name.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").trim()

    /** 마지막 글자에 받침이 있으면 true, 없으면 false, 판단할 수 없으면 null. 숫자는 읽는 소리로 판단 */
    fun hasJong(word: String): Boolean? {
        val c = spokenName(word).trimEnd { !it.isLetterOrDigit() }.lastOrNull() ?: return null
        return when (c) {
            in '가'..'힣' -> (c - '가') % 28 != 0
            in '0'..'9' -> c in "013678"
            else -> null
        }
    }

    /** 받침이 ㄹ 이면 true. "으로/로" 고를 때 씀 (ㄹ 받침은 "로") */
    private fun rieul(word: String): Boolean {
        val c = spokenName(word).trimEnd { !it.isLetterOrDigit() }.lastOrNull() ?: return false
        return c in '가'..'힣' && (c - '가') % 28 == 8
    }

    fun josa(word: String, withJong: String, withoutJong: String): String = when (hasJong(word)) {
        true -> withJong
        false -> withoutJong
        null -> "$withJong($withoutJong)"
    }

    fun eulReul(word: String) = josa(word, "을", "를")
    fun iGa(word: String) = josa(word, "이", "가")
    fun eunNeun(word: String) = josa(word, "은", "는")
    fun ieyo(word: String) = josa(word, "이에요", "예요")
    fun euro(word: String) = if (hasJong(word) == true && !rieul(word)) "으로" else "로"

    /** 문구의 {key} 자리에 값을 넣고, 바로 뒤의 "예요"는 받침에 맞게 "이에요"로 바꿈 */
    fun fillKo(template: String, key: String, value: String): String {
        val tag = "{$key}"
        return template.replace(tag + "예요", value + ieyo(value)).replace(tag, value)
    }

    /** 열까지는 "세 곳"처럼 우리말 수로 읽음 */
    fun koCount(n: Int): String =
        listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열").getOrNull(n - 1) ?: n.toString()

    /** 소리로 읽을 거리. 1km 아래는 50m 단위, 위는 0.1km 단위 */
    fun distance(m: Double, lang: String): String = when {
        lang == "en" && m < 1000 -> "about ${((m / 50).roundToInt() * 50).coerceAtLeast(50)} meters"
        lang == "en" -> {
            val km = (m / 100).roundToInt() / 10.0
            if (km % 1.0 == 0.0) "about ${km.toInt()} kilometer" + (if (km.toInt() == 1) "" else "s") else "about %.1f kilometers".format(km)
        }
        m < 1000 -> "약 ${((m / 50).roundToInt() * 50).coerceAtLeast(50)}미터"
        else -> "약 %.1f킬로미터".format(m / 1000).replace(".0킬로", "킬로")
    }

    /** 소리로 읽을 시간. 1시간 넘으면 "1시간 20분" */
    fun minutes(min: Int, lang: String): String {
        val h = min / 60
        val m = min % 60
        return if (lang == "en") when {
            h == 0 -> "$m minute" + if (m == 1) "" else "s"
            m == 0 -> "$h hour" + if (h == 1) "" else "s"
            else -> "$h hour" + (if (h == 1) "" else "s") + " $m minutes"
        } else when {
            h == 0 -> "${m}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }
}
