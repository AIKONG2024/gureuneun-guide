package app.gureuneun.guide.sdk

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Place
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FusedLocation SDK 연결부.
 * 여행지와 명소마다 근접 알림(Proximity Alert) 영역을 등록하고, 차가 들어서고 나가는 순간을 받음.
 */
class LocationBridge(private val context: Context) {

    data class Fix(val pos: LatLng, val heading: Float, val time: Long)
    data class Transition(val placeId: String, val enter: Boolean)

    val supported = MutableStateFlow<Boolean?>(null)
    val lastFix = MutableStateFlow<Fix?>(null)
    val registered = MutableStateFlow(0)
    val transitions = MutableSharedFlow<Transition>(extraBufferCapacity = 32)

    private val main = Handler(Looper.getMainLooper())
    private var fused: FusedLocation? = null
    private val alertToPlace = mutableMapOf<String, String>()

    private val fixListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null) return
            main.post {
                lastFix.value = Fix(LatLng(location.latitude, location.longitude), location.heading, location.timestampMillis)
            }
        }

        override fun onError(e: Exception) {
            GLog.w("Location", "위치 수신 오류", e)
        }
    }

    /** 위치 권한을 받은 뒤 호출 */
    fun start() {
        if (fused != null) return
        val f = FusedLocation(context.applicationContext)
        fused = f
        runCatching {
            f.initialize()
            main.postDelayed({
                val ok = runCatching { f.isSupported() }.getOrDefault(false)
                supported.value = ok
                GLog.i("Location", "FusedLocation 지원: $ok")
                runCatching { f.registerFusedLocationCallback(fixListener) }.onFailure { GLog.w("Location", "위치 구독 실패", it) }
            }, 800)
        }.onFailure {
            GLog.w("Location", "initialize 실패", it)
            supported.value = false
        }
    }

    /** 장소마다 근접 알림을 등록 */
    fun watch(places: List<Place>) {
        val f = fused ?: return
        clear()
        places.forEach { p ->
            val req = ProximityAlertRequest(p.pos.lat, p.pos.lon, p.radiusM, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT))
            runCatching {
                f.addProximityAlert(req, object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) {
                        main.post {
                            alertToPlace[alertId] = p.id
                            registered.value = alertToPlace.size
                        }
                    }

                    override fun onTransition(event: ProximityAlertEvent) {
                        main.post {
                            val id = alertToPlace[event.alertId] ?: return@post
                            GLog.i("Location", "근접 알림 ${event.transition} $id")
                            transitions.tryEmit(Transition(id, event.transition == ProximityTransition.ENTER))
                        }
                    }

                    override fun onError(e: Exception) {
                        GLog.w("Location", "근접 알림 등록 실패 ${p.id}", e)
                    }
                })
            }.onFailure { GLog.w("Location", "근접 알림 등록 예외 ${p.id}", it) }
        }
    }

    fun clear() {
        val f = fused ?: return
        alertToPlace.keys.toList().forEach { id -> runCatching { f.removeProximityAlert(id) } }
        alertToPlace.clear()
        registered.value = 0
    }

    fun release() {
        clear()
        runCatching {
            fused?.unregisterFusedLocationCallback(fixListener)
            fused?.release()
        }
        fused = null
    }
}
