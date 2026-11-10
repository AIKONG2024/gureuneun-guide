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
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * FusedLocation SDK 연결부.
 * 여행지와 명소마다 근접 알림(Proximity Alert) 영역을 등록하고, 차가 들어서고 나가는 순간을 받음.
 * 위치 서비스 호출은 전용 스레드에서 차례로 함.
 * 문서 순서대로 isSupported() 로 지원 여부를 먼저 보고, 지원할 때만 initialize() 함.
 * 준비되기 전에 들어온 근접 알림 요청은 기억했다가 준비되면 등록함. 오류는 lastError 로 알림.
 * 차 개인정보 보호 설정으로 위치가 막혔으면 showBlockedPopup() 으로 차 시스템 안내 팝업을 띄움.
 */
class LocationBridge(private val context: Context) {

    data class Fix(val pos: LatLng, val heading: Float, val time: Long)
    data class Transition(val placeId: String, val enter: Boolean)

    val supported = MutableStateFlow<Boolean?>(null)
    val lastFix = MutableStateFlow<Fix?>(null)
    val registered = MutableStateFlow(0)
    val transitions = MutableSharedFlow<Transition>(extraBufferCapacity = 32)
    private val _lastError = MutableStateFlow<String?>(null)
    /** 마지막 위치 오류 (지원 확인, 초기화, 위치 수신, 근접 알림 등록). 없으면 null */
    val lastError: StateFlow<String?> = _lastError

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fused: FusedLocation? = null
    @Volatile private var starting = false
    /** 위치 서비스가 준비되기 전에 들어온 근접 알림 요청 */
    @Volatile private var pendingWatch: List<Place>? = null
    private val alertToPlace = ConcurrentHashMap<String, String>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "location-sdk").apply { isDaemon = true } }

    private fun report(where: String, e: Throwable?) {
        GLog.w("Location", where, e)
        val msg = where + (e?.let { ": " + (it.message ?: it.javaClass.simpleName) } ?: "")
        main.post { _lastError.value = msg }
    }

    private val fixListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null) return
            main.post {
                lastFix.value = Fix(LatLng(location.latitude, location.longitude), location.heading, location.timestampMillis)
                if (_lastError.value != null) _lastError.value = null
            }
        }

        override fun onError(e: Exception) {
            report("위치 수신 오류", e)
        }
    }

    /** 위치 권한을 받은 뒤 호출. 여러 번 불러도 됨 */
    fun start() {
        if (fused != null || starting) return
        starting = true
        io.execute {
            val f = runCatching { FusedLocation(context.applicationContext) }.getOrElse {
                report("FusedLocation 을 만들지 못함", it)
                main.post { supported.value = false }
                starting = false
                return@execute
            }
            val ok = runCatching { f.isSupported() }.getOrElse {
                report("지원 여부 확인 실패", it)
                false
            }
            GLog.i("Location", "FusedLocation 지원: $ok")
            if (!ok) {
                main.post { supported.value = false }
                starting = false
                return@execute
            }
            val inited = runCatching { f.initialize() }.onFailure { report("initialize 실패", it) }.isSuccess
            if (!inited) {
                main.post { supported.value = false }
                starting = false
                return@execute
            }
            fused = f
            starting = false
            main.post { supported.value = true }
            // 서비스 연결에 시간이 걸릴 수 있어 잠시 뒤 구독 (전용 스레드라 화면은 멈추지 않음)
            runCatching { Thread.sleep(800) }
            runCatching { f.registerFusedLocationCallback(fixListener) }.onFailure { report("위치 구독 실패", it) }
            pendingWatch?.let { places ->
                pendingWatch = null
                register(f, places)
            }
        }
    }

    /** 차 개인정보 보호 설정으로 위치 접근이 막혔을 때 차 시스템의 안내 팝업을 띄움 */
    fun showBlockedPopup() {
        val f = fused ?: return
        io.execute { runCatching { f.showLocationAccessBlockedPopup() }.onFailure { report("위치 차단 안내 팝업 실패", it) } }
    }

    /** 장소마다 근접 알림을 등록. 위치 서비스가 준비되기 전이면 준비되는 대로 등록 */
    fun watch(places: List<Place>) {
        val f = fused
        if (f == null) {
            pendingWatch = places
            GLog.i("Location", "위치 서비스 준비 전이라 근접 알림 ${places.size}곳은 준비되면 등록")
            return
        }
        clear()
        register(f, places)
    }

    private fun register(f: FusedLocation, places: List<Place>) {
        io.execute { places.forEach { p ->
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
                        report("근접 알림 등록 실패 ${p.id}", e)
                    }
                })
            }.onFailure { report("근접 알림 등록 예외 ${p.id}", it) }
        } }
    }

    fun clear() {
        pendingWatch = null
        val f = fused ?: return
        val ids = alertToPlace.keys.toList()
        alertToPlace.clear()
        registered.value = 0
        io.execute { ids.forEach { id -> runCatching { f.removeProximityAlert(id) } } }
    }

    /** 문서 순서: 위치 리스너를 먼저 풀고 release. 같은 인스턴스는 다시 쓰지 않음 */
    fun release() {
        clear()
        val f = fused
        fused = null
        if (f != null) io.execute {
            runCatching { f.unregisterFusedLocationCallback(fixListener) }
            runCatching { f.release() }
        }
    }
}
