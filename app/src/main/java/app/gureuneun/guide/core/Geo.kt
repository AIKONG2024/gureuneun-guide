package app.gureuneun.guide.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_000.0

    private fun rad(d: Double) = d * PI / 180.0

    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 북쪽 기준 시계 방향 0~360도 */
    fun bearing(a: LatLng, b: LatLng): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
    }

    fun lerp(a: LatLng, b: LatLng, t: Double) = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** 진행 방향 기준으로 장소가 어느 쪽에 있는지: "left", "right", "ahead" */
    fun sideOf(car: LatLng, headingDeg: Double, target: LatLng): String {
        val rel = ((bearing(car, target) - headingDeg) + 540.0) % 360.0 - 180.0
        return when {
            rel > 20 -> "right"
            rel < -20 -> "left"
            else -> "ahead"
        }
    }
}

/** 시연 주행과 남은 거리 계산에 쓰는 경로 */
class Polyline(val points: List<LatLng>) {
    private val cumulative: DoubleArray = DoubleArray(points.size)
    val length: Double

    init {
        var acc = 0.0
        for (i in points.indices) {
            if (i > 0) acc += Geo.distanceM(points[i - 1], points[i])
            cumulative[i] = acc
        }
        length = acc
    }

    /** 경로 시작점에서 d 미터 지난 위치와 진행 방향 */
    fun at(d: Double): Pair<LatLng, Double> {
        if (points.size < 2) return (points.firstOrNull() ?: LatLng(0.0, 0.0)) to 0.0
        val dist = d.coerceIn(0.0, length)
        var i = cumulative.indexOfFirst { it >= dist }
        if (i <= 0) i = 1
        val segLen = cumulative[i] - cumulative[i - 1]
        val t = if (segLen <= 0.0) 0.0 else (dist - cumulative[i - 1]) / segLen
        val p = Geo.lerp(points[i - 1], points[i], t)
        return p to Geo.bearing(points[i - 1], points[i])
    }

    /** p 에 가장 가까운 경로상의 거리(미터) */
    fun project(p: LatLng): Double {
        var best = Double.MAX_VALUE
        var bestD = 0.0
        for (i in points.indices) {
            val dd = Geo.distanceM(points[i], p)
            if (dd < best) {
                best = dd
                bestD = cumulative[i]
            }
        }
        return bestD
    }
}
