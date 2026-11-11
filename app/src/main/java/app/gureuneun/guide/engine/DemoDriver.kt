package app.gureuneun.guide.engine

import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Polyline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 시연 모드 주행기.
 * 에뮬레이터는 GPS 위치가 움직이지 않으므로 실제 도로 경로(OSRM)를 따라 차 위치를 흘려 넣음.
 * 이야기가 나오는 동안에는 실제 속도로, 그 사이 구간은 빠르게 감아 시연 시간을 줄임.
 */
class DemoDriver(private val scope: CoroutineScope, private val onTick: (Tick) -> Unit) {

    data class Tick(val pos: LatLng, val heading: Double, val progressM: Double, val lengthM: Double, val speedKmh: Double)

    val running = MutableStateFlow(false)
    var fastForward = 6

    /** 이야기가 나오는 중이면 true 를 돌려줌 (그동안은 실제 속도) */
    var storyPlaying: () -> Boolean = { false }

    private var line: Polyline? = null
    private var job: Job? = null

    var progressM: Double = 0.0
        private set
    val lengthM: Double get() = line?.length ?: 0.0

    fun load(points: List<LatLng>, startM: Double = 0.0) {
        stop()
        line = Polyline(points)
        progressM = startM
        emit()
    }

    fun play() {
        if (job?.isActive == true || line == null) return
        running.value = true
        job = scope.launch {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(200)
                val now = System.currentTimeMillis()
                val dt = (now - last) / 1000.0
                last = now
                val factor = if (storyPlaying()) 1.0 else fastForward.toDouble()
                progressM += CITY_KMH / 3.6 * factor * dt
                if (progressM >= lengthM) {
                    progressM = lengthM
                    emit()
                    break
                }
                emit()
            }
            running.value = false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running.value = false
    }

    fun seek(m: Double) {
        progressM = m.coerceIn(0.0, lengthM)
        emit()
    }

    private fun emit() {
        val l = line ?: return
        val (p, h) = l.at(progressM)
        onTick(Tick(p, h, progressM, l.length, if (running.value) CITY_KMH else 0.0))
    }

    private companion object {
        /** 시연 주행 속도 (시내 평균) */
        const val CITY_KMH = 40.0
    }
}
