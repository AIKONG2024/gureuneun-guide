package app.gureuneun.guide.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371000.0

    fun dist(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** a 에서 bearing 방향으로 m 미터 간 곳 */
    fun dest(a: LatLng, bearingDeg: Double, m: Double): LatLng {
        val d = m / R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(a.lat)
        val l1 = Math.toRadians(a.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLng(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    /** a 에서 b 로 돌 때의 각도 차이 (-180..180, 오른쪽이 +) */
    fun turn(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    fun worldX(lon: Double, worldSize: Double) = (lon + 180.0) / 360.0 * worldSize

    fun worldY(lat: Double, worldSize: Double): Double {
        val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize
    }

    /** 한글 받침이 있으면 true (이/가, 을/를 고르기) */
    fun batchim(word: String): Boolean {
        val c = word.trim().lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        return (c.code - 0xAC00) % 28 != 0
    }

    fun iGa(word: String) = word + if (batchim(word)) "이" else "가"
    fun eunNeun(word: String) = word + if (batchim(word)) "은" else "는"
    fun eulReul(word: String) = word + if (batchim(word)) "을" else "를"
    fun ida(word: String) = word + if (batchim(word)) "이에요" else "예요"

    /** ~로/~으로 (받침이 ㄹ 이면 '로') */
    fun ro(word: String): String {
        val c = word.trim().lastOrNull() ?: return word
        if (c !in '가'..'힣') return word + "로"
        val jong = (c.code - 0xAC00) % 28
        return word + if (jong == 0 || jong == 8) "로" else "으로"
    }
}
