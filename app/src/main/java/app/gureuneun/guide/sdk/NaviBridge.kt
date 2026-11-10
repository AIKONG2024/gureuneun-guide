package app.gureuneun.guide.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.gureuneun.guide.core.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NaviHelper SDK 연결부.
 * 순정 내비게이션에 목적지와 경유지를 넣고, 다음 길 안내(TBT)까지 남은 거리를 받아
 * 이야기가 길 안내와 겹치지 않게 하는 데 씀.
 * 막히는 구간(교통 정보), 단속 카메라와 어린이 보호구역 같은 주의 지점도 받아서
 * 막히면 긴 이야기를 고르고, 주의 지점 앞에서는 이야기를 잠시 쉼.
 * 쉬어 갈 곳, 충전소, 주유소는 순정 내비 화면(검색, 주변 충전소)을 띄워 운전자가 고르게 함.
 *
 * 연결 전에 들어온 경로 요청은 대기열(최대 8개)에 두었다가 연결되면 보냄. 화면 띄우기는 연결 전이면 false.
 * 연결이 안 되면 1초에서 30초까지 간격을 늘려 다시 시도함. 오류는 errors 로 알림.
 * 길 안내 정보는 여행 중이고 경로 안내 중일 때(또는 경로를 요청한 뒤 1분 동안)만 1.5초마다 물어봄.
 */
class NaviBridge(private val context: Context, private val scope: CoroutineScope) {

    data class State(
        val connected: Boolean = false,
        val status: String = "-",
        val tbtDistanceM: Double? = null,
        val tbtText: String? = null,
        val tbtType: String? = null,
        val remainDistanceM: Int? = null,
        val remainSeconds: Int? = null,
        val destinationName: String? = null,
        val navPos: LatLng? = null,
        /** 지금 도로 제한 속도(km/h) */
        val speedLimit: Int? = null,
        /** 남은 경로의 막히는 구간 합계 */
        val jamDistM: Double? = null,
        val jamTimeS: Long? = null,
        /** 가장 가까운 단속 카메라까지 거리 */
        val cameraAheadM: Int? = null,
        /** 가장 가까운 주의 지점(어린이 보호구역 등)까지 거리와 종류 */
        val cautionAheadM: Int? = null,
        val cautionType: String? = null,
        /** 내비가 마지막으로 알려 준 경유지 수 (모르면 null) */
        val waypointCount: Int? = null,
        val lastEvent: String? = null,
        val lastError: String? = null,
    )

    sealed interface Event {
        data class Arrived(val name: String?) : Event
        data class WaypointArrived(val name: String?) : Event
        data object RouteStarted : Event
    }

    /**
     * 내비 오류. code 는 NaviErrorCode 이름(CAUSE_SEARCH_ROUTE 등)이거나
     * CONNECT_FAILED(연결 실패), NOT_CONNECTED(연결 전이라 못 함), WAYPOINTS_FULL(경유지 두 곳이 다 참), REQUEST_FAILED(요청 중 예외).
     * what 은 무엇을 하다가 났는지 (장소 이름, 요청 이름)
     */
    data class Failure(val code: String, val what: String?)

    val state = MutableStateFlow(State())
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private val _errors = MutableSharedFlow<Failure>(extraBufferCapacity = 8)
    val errors: SharedFlow<Failure> = _errors.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var helper: NaviHelper? = null
    @Volatile private var connected = false
    @Volatile private var started = false
    private var attempt = 0
    private val retrying = AtomicBoolean(false)
    private var pollJob: Job? = null
    @Volatile private var tripActive = false
    @Volatile private var routeRequestedAt = 0L
    /** 이 앱이 넣은 경유지 수. 내비가 경유지 정보를 주지 않을 때만 씀 */
    @Volatile private var knownWaypoints = 0
    @Volatile private var waypointWait: CountDownLatch? = null
    @Volatile private var lastWaypoints: List<WaypointInfo>? = null
    /** 연결 전에 들어온 요청 */
    private val pending = ArrayDeque<Pair<String, (NaviHelper) -> Unit>>()
    // 내비 서비스 호출은 전용 스레드에서 (내비 앱이 바쁠 때 화면이 멈추지 않게)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "navi-sdk").apply { isDaemon = true } }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun report(code: String, what: String?) {
        _errors.tryEmit(Failure(code, what))
        post { state.update { it.copy(lastError = if (what != null) "$code ($what)" else code) } }
    }

    /**
     * 내비 호출을 전용 스레드에서 실행. 연결 전이면 queue 일 때 대기열에 넣고 true, 아니면 NOT_CONNECTED 로 알리고 false.
     * true 는 '요청을 받았다'는 뜻
     */
    private fun send(what: String, queue: Boolean = true, block: (NaviHelper) -> Unit): Boolean {
        val h = helper
        if (h != null && connected) {
            dispatch(h, what, block)
            return true
        }
        if (!queue) {
            GLog.w("Navi", "내비 연결 전이라 못 함: $what")
            report("NOT_CONNECTED", what)
            return false
        }
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(what to block)
        }
        GLog.i("Navi", "내비 연결 전이라 연결되면 보냄: $what")
        return true
    }

    private fun dispatch(h: NaviHelper, what: String, block: (NaviHelper) -> Unit) {
        io.execute {
            runCatching { block(h) }.onFailure {
                GLog.w("Navi", "$what 실패", it)
                report("REQUEST_FAILED", what)
            }
        }
    }

    private fun flushPending(h: NaviHelper) {
        val list = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (list.isNotEmpty()) GLog.i("Navi", "기다리던 요청 ${list.size}개 보냄")
        list.forEach { (what, block) -> dispatch(h, what, block) }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val first = tbtInfoList.firstOrNull()
            post {
                state.update {
                    it.copy(
                        tbtDistanceM = first?.distance,
                        tbtText = first?.description,
                        tbtType = first?.type?.toString(),
                    )
                }
            }
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            post {
                state.update { it.copy(status = navigationStatus.toString()) }
                ensurePolling()
            }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val d = drivingInfo.destination
            post { state.update { it.copy(remainDistanceM = d.distance, remainSeconds = d.duration, destinationName = d.name) } }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            post { state.update { it.copy(navPos = LatLng(currentLocationInfo.latitude, currentLocationInfo.longitude)) } }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            GLog.i("Navi", "경로 안내 시작: ${routeStartInfo.name} ${routeStartInfo.distance}m")
            post { state.update { it.copy(lastEvent = "경로 시작 ${routeStartInfo.name}", destinationName = routeStartInfo.name) } }
            events.tryEmit(Event.RouteStarted)
        }

        override fun onRoutingCompleted() {
            post { state.update { it.copy(lastEvent = "경로 탐색 완료") } }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            GLog.i("Navi", "목적지 도착")
            knownWaypoints = 0
            events.tryEmit(Event.Arrived(state.value.destinationName))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            GLog.i("Navi", "경유지 도착")
            knownWaypoints = (knownWaypoints - 1).coerceAtLeast(0)
            events.tryEmit(Event.WaypointArrived(null))
        }

        override fun onWayPointInfo(waypointInfo: List<WaypointInfo>) {
            lastWaypoints = waypointInfo
            waypointWait?.countDown()
            post { state.update { it.copy(waypointCount = waypointInfo.size) } }
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            GLog.i("Navi", "경유지 바뀜: ${waypointChangedInfo.index} ${waypointChangedInfo.cmdType}")
        }

        override fun onRouteCancelled() {
            knownWaypoints = 0
            post { state.update { it.copy(lastEvent = "경로 취소") } }
        }

        override fun onError(errorCode: NaviErrorCode) {
            GLog.w("Navi", "오류 $errorCode")
            report(errorCode.name, null)
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val s = runCatching { routeStateInfo.trafficStatus?.summary }.getOrNull()
            post { state.update { it.copy(jamDistM = s?.totalCongestionDist, jamTimeS = s?.totalCongestionTime) } }
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            val near = cameraAlertList.filter { it.status }.minOfOrNull { it.remainDistance }
            post { state.update { it.copy(cameraAheadM = near) } }
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            val near = cautionPointList.minByOrNull { it.remainDistance }
            post { state.update { it.copy(cautionAheadM = near?.remainDistance, cautionType = near?.type?.toString()) } }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            post { state.update { it.copy(speedLimit = speedLimit) } }
        }
    }

    fun start() {
        if (started) return
        started = true
        connect()
    }

    private fun connect() {
        val old = helper
        if (old != null) runCatching {
            old.removeListener(listener)
            old.release()
        }
        connected = false
        val h = NaviHelper(context.applicationContext)
        helper = h
        runCatching {
            h.initialize(
                onConnected = {
                    if (helper === h && started) {
                        GLog.i("Navi", "내비게이션 서비스 연결됨")
                        runCatching { h.addListener(listener) }.onFailure { GLog.w("Navi", "addListener 실패", it) }
                        connected = true
                        retrying.set(false)
                        post {
                            attempt = 0
                            state.update { it.copy(connected = true) }
                            ensurePolling()
                        }
                        flushPending(h)
                    }
                },
                onFailed = { t ->
                    if (helper === h) {
                        GLog.w("Navi", "내비게이션 서비스 연결 실패", t)
                        connected = false
                        post { state.update { it.copy(connected = false) } }
                        report("CONNECT_FAILED", t.message)
                        retryLater()
                    }
                },
            )
        }.onFailure {
            GLog.w("Navi", "initialize 실패", it)
            report("CONNECT_FAILED", it.message)
            retryLater()
        }
    }

    /** 1, 2, 4, 8, 16, 30초 간격으로 NaviHelper 를 새로 만들어 다시 연결 */
    private fun retryLater() {
        if (!started || !retrying.compareAndSet(false, true)) return
        post {
            attempt++
            val wait = (1000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_RETRY_MS)
            if (attempt <= 3 || attempt % 10 == 0) GLog.i("Navi", "${wait / 1000}초 뒤 내비게이션 다시 연결 (${attempt}번째)")
            main.postDelayed({
                retrying.set(false)
                if (started && !connected) connect()
            }, wait)
        }
    }

    /** 여행 중인지 알려 줌 (GuideApp.tripActive 가 부름). 여행 중일 때만 길 안내 정보를 자주 물어봄 */
    fun setTripActive(on: Boolean) {
        if (tripActive == on) return
        tripActive = on
        GLog.i("Navi", if (on) "여행 중: 길 안내 정보 확인 시작" else "여행 끝: 길 안내 정보 확인 멈춤")
        post { ensurePolling() }
    }

    /** 다음에 물어볼 때까지 기다릴 시간. null 이면 멈춤 */
    private fun pollDelay(): Long? {
        val recent = routeRequestedAt != 0L && SystemClock.elapsedRealtime() - routeRequestedAt < ROUTE_WATCH_MS
        val status = state.value.status
        // 상태를 아직 못 받았으면(-) 경로 안내 중일 수 있으니 자주 물어봄
        val routing = status == NavigationStatus.ROUTE_DRIVE.name || status == "-"
        return when {
            recent -> FAST_POLL_MS
            tripActive && routing -> FAST_POLL_MS
            tripActive -> SLOW_POLL_MS
            else -> null
        }
    }

    /** 다음 길 안내까지 남은 거리를 주기적으로 물어봄 (메인 스레드에서 부름) */
    private fun ensurePolling() {
        if (!connected || pollJob?.isActive == true || pollDelay() == null) return
        pollJob = scope.launch {
            var n = 0
            while (isActive) {
                val wait = pollDelay() ?: break
                val h = helper ?: break
                if (!connected) break
                val every = n++
                io.execute {
                    runCatching {
                        h.getTBTInfo()
                        h.getCurrentLocationInfo()
                        // 교통 정보는 자주 바뀌지 않아 열 번에 한 번
                        if (every % 10 == 0) h.getRouteStateInfo()
                    }
                }
                delay(wait)
            }
        }
    }

    private fun routeInfo(name: String, pos: LatLng) = RouteInfo(
        latitude = pos.lat,
        longitude = pos.lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = "",
        address = name,
        poiSubId = "0",
    )

    private fun markRouteRequested() {
        routeRequestedAt = SystemClock.elapsedRealtime()
        post { ensurePolling() }
    }

    /** 목적지(와 경유지 최대 두 곳)를 순정 내비게이션에 넣음. 연결 전이면 연결되면 보냄 */
    fun routeTo(name: String, pos: LatLng, waypoints: List<Pair<String, LatLng>> = emptyList()): Boolean =
        send("목적지 요청 $name") { h ->
            val wps = waypoints.take(2)
            if (wps.isEmpty()) {
                h.requestRoute(routeInfo(name, pos))
            } else {
                val req = wps.mapIndexed { i, (wName, wPos) ->
                    RequestWaypointInfo(
                        latitude = wPos.lat,
                        longitude = wPos.lon,
                        poiName = wName,
                        waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                        poiId = "",
                        address = wName,
                        poiSubId = "0",
                    )
                }
                h.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination = routeInfo(name, pos), waypoints = req))
            }
            knownWaypoints = wps.size
            markRouteRequested()
            GLog.i("Navi", "목적지 요청: $name (경유지 ${wps.size}곳)")
            post { state.update { it.copy(destinationName = name, lastEvent = "목적지 요청 $name") } }
        }

    /** 지금 경유지 수를 내비에 물어봄 (전용 스레드에서만). 답이 없으면 null */
    private fun askWaypointCount(h: NaviHelper): Int? {
        val latch = CountDownLatch(1)
        lastWaypoints = null
        waypointWait = latch
        try {
            h.getWaypointInfo()
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            GLog.w("Navi", "경유지 정보 요청 실패", e)
        } finally {
            waypointWait = null
        }
        return lastWaypoints?.size
    }

    /**
     * 지금 경로를 유지한 채 경유지 하나를 넣음. 먼저 내비에 경유지 수를 물어 빈 자리(FIRST, SECOND)에 넣음.
     * 두 곳이 다 찼으면 replaceLast 일 때만 두 번째를 바꾸고, 아니면 넣지 않고 WAYPOINTS_FULL 로 알림
     * (시나리오가 처음부터 경유지 두 곳을 넣으므로 여행 중에는 보통 다 차 있음).
     * true 는 요청을 받았다는 뜻. 실제로 넣었는지는 onResult(메인 스레드)로 옴
     */
    fun addWaypoint(name: String, pos: LatLng, replaceLast: Boolean = false, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val accepted = send("경유지 추가 $name") { h ->
            val asked = askWaypointCount(h)
            val count = asked ?: knownWaypoints.also { GLog.w("Navi", "경유지 정보를 받지 못해 앱이 넣은 수(${it}곳)로 판단") }
            val index = when {
                count <= 0 -> WaypointIndex.FIRST
                count == 1 -> WaypointIndex.SECOND
                replaceLast -> {
                    h.removeWaypoint(WaypointIndex.SECOND)
                    WaypointIndex.SECOND
                }
                else -> null
            }
            if (index == null) {
                GLog.w("Navi", "경유지가 이미 두 곳이라 넣지 않음: $name")
                report("WAYPOINTS_FULL", name)
                onResult?.let { cb -> post { cb(false) } }
                return@send
            }
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = pos.lat,
                    longitude = pos.lon,
                    poiName = name,
                    waypointIndex = index,
                    poiId = "",
                    address = name,
                    poiSubId = "0",
                )
            )
            knownWaypoints = (count + 1).coerceAtMost(2)
            markRouteRequested()
            GLog.i("Navi", "경유지 추가: $name (${index.name})")
            onResult?.let { cb -> post { cb(true) } }
        }
        if (!accepted) onResult?.let { cb -> post { cb(false) } }
        return accepted
    }

    fun cancelRoute(): Boolean = send("경로 취소") {
        it.cancelRoute()
        knownWaypoints = 0
    }

    /** 순정 내비의 주변 충전소 화면을 띄움. 연결 전이면 false */
    fun openChargers(): Boolean = send("충전소 화면 요청", queue = false) { h ->
        h.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations)
        GLog.i("Navi", "주변 충전소 화면 요청")
    }

    /**
     * 주유소 찾기. NaviHelper 3.0.5 에는 주유소 전용 화면이 없어서 순정 내비의 장소 검색 화면을 '주유소'로 띄움.
     * 연결 전이면 false
     */
    fun openGasStations(lang: String = "ko"): Boolean = searchNearby(if (lang == "en") "gas station" else "주유소")

    /** 순정 내비의 장소 검색 화면을 검색어와 함께 띄움 (예: 휴게소, 화장실). 연결 전이면 false */
    fun searchNearby(keyword: String): Boolean = send("검색 화면 요청 $keyword", queue = false) { h ->
        h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        GLog.i("Navi", "장소 검색 화면 요청: $keyword")
    }

    fun release() {
        started = false
        connected = false
        pollJob?.cancel()
        runCatching {
            helper?.removeListener(listener)
            helper?.release()
        }
        helper = null
    }

    private companion object {
        const val MAX_PENDING = 8
        const val WAIT_MS = 1500L
        const val MAX_RETRY_MS = 30_000L
        const val FAST_POLL_MS = 1500L
        const val SLOW_POLL_MS = 10_000L
        /** 경로를 요청한 뒤 이만큼은 상태와 관계없이 자주 물어봄 */
        const val ROUTE_WATCH_MS = 60_000L
    }
}
