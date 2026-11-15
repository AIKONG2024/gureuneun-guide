package app.gureuneun.guide.engine

import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 주행 경로: 누적 거리로 위치와 진행 방향을 구함 */
class DrivePath(val pts: List<LatLng>) {
    val cum: DoubleArray = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + Geo.dist(pts[i - 1], pts[i])
    }
    val total: Double get() = cum.lastOrNull() ?: 0.0

    fun index(s: Double): Int {
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    fun at(s: Double): LatLng {
        if (pts.isEmpty()) return LatLng(0.0, 0.0)
        val c = s.coerceIn(0.0, total)
        val i = index(c)
        if (i >= pts.size - 1) return pts.last()
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 0) 0.0 else (c - cum[i]) / seg
        return Geo.lerp(pts[i], pts[i + 1], t)
    }

    /** s 지점의 진행 방향. 앞뒤를 조금 넓게 봐서 꺾이는 곳에서도 부드럽게 */
    fun heading(s: Double): Double {
        val a = at(max(0.0, s - 6))
        val b = at(min(total, s + 22))
        return if (Geo.dist(a, b) < 1) 0.0 else Geo.bearing(a, b)
    }

    /** p 에서 가장 가까운 경로 위 지점: (누적 거리, 떨어진 거리 m, 왼쪽이면 -1 오른쪽이면 1) */
    fun project(p: LatLng): Triple<Double, Double, Int> {
        var best = Double.MAX_VALUE
        var bestS = 0.0
        var side = 0
        val k = cos(Math.toRadians(p.lat))
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = 0.0
            val ay = 0.0
            val bx = (b.lon - a.lon) * k * 111320.0
            val by = (b.lat - a.lat) * 110540.0
            val px = (p.lon - a.lon) * k * 111320.0
            val py = (p.lat - a.lat) * 110540.0
            val l2 = bx * bx + by * by
            val t = if (l2 <= 0) 0.0 else ((px - ax) * bx + (py - ay) * by) / l2
            val tc = t.coerceIn(0.0, 1.0)
            val qx = bx * tc
            val qy = by * tc
            val d = hypot(px - qx, py - qy)
            if (d < best) {
                best = d
                bestS = cum[i] + (cum[i + 1] - cum[i]) * tc
                // 진행 방향(bx,by) 기준 오른쪽이면 외적이 음수
                val cross = bx * py - by * px
                side = if (abs(cross) < 1e-9) 0 else if (cross < 0) 1 else -1
            }
        }
        return Triple(bestS, best, side)
    }
}

/**
 * 시연 주행. 실제 차는 FusedLocation 위치를 쓰고, 에뮬레이터에서는 이 주행으로 위치를 만듦.
 * 화면 속도는 실제와 같은 값으로 보여 주고 시간만 timeScale 배로 빠르게 흘림.
 */
class DemoDrive(private val path: DrivePath, private val gateS: Double, private val turnS: List<Double>) {
    var s = 0.0
        private set
    var speedKmh = 0.0
        private set
    var running = false
    var holdMs = 0L
    var timeScale = 5.0

    fun reset(startS: Double = 0.0) {
        s = startS
        speedKmh = 0.0
        holdMs = 0
    }

    fun jump(to: Double) {
        s = to.coerceIn(0.0, path.total)
    }

    private fun target(): Double {
        if (s < gateS - 320) return 92.0
        if (s < gateS + 70) return 32.0
        val nextTurn = turnS.firstOrNull { it > s - 8 }
        if (nextTurn != null && nextTurn - s < 55) return 24.0
        if (path.total - s < 140) return 16.0
        return 47.0
    }

    /** dt: 실제 흐른 시간(초) */
    fun step(dt: Double) {
        if (!running) return
        if (holdMs > 0) {
            holdMs -= (dt * 1000).toLong()
            speedKmh = max(0.0, speedKmh - 60 * dt * timeScale)
            return
        }
        val tgt = target()
        val rate = if (tgt > speedKmh) 14.0 else 22.0 // km/h per sim second
        val d = rate * dt * timeScale
        speedKmh = if (tgt > speedKmh) min(tgt, speedKmh + d) else max(tgt, speedKmh - d)
        s = min(path.total, s + speedKmh / 3.6 * dt * timeScale)
        if (s >= path.total) {
            speedKmh = 0.0
        }
    }
}
