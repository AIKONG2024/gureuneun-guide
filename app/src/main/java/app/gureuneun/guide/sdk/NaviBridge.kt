package app.gureuneun.guide.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.gureuneun.guide.core.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * NaviHelper SDK 연결부.
 * 순정 내비게이션에 목적지와 경유지를 넣고, 다음 길 안내(TBT)까지 남은 거리를 받아
 * 이야기가 길 안내와 겹치지 않게 하는 데 씀.
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
        val lastEvent: String? = null,
        val lastError: String? = null,
    )

    sealed interface Event {
        data class Arrived(val name: String?) : Event
        data class WaypointArrived(val name: String?) : Event
        data object RouteStarted : Event
    }

    val state = MutableStateFlow(State())
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var pollJob: Job? = null

    private fun post(block: () -> Unit) {
        main.post(block)
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
            post { state.update { it.copy(status = navigationStatus.toString()) } }
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
            events.tryEmit(Event.Arrived(state.value.destinationName))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            GLog.i("Navi", "경유지 도착")
            events.tryEmit(Event.WaypointArrived(null))
        }

        override fun onRouteCancelled() {
            post { state.update { it.copy(lastEvent = "경로 취소") } }
        }

        override fun onError(errorCode: NaviErrorCode) {
            GLog.w("Navi", "오류 $errorCode")
            post { state.update { it.copy(lastError = errorCode.toString()) } }
        }
    }

    fun start() {
        if (helper != null) return
        val h = NaviHelper(context.applicationContext)
        helper = h
        runCatching {
            h.initialize(
                onConnected = {
                    GLog.i("Navi", "내비게이션 서비스 연결됨")
                    h.addListener(listener)
                    post { state.update { it.copy(connected = true) } }
                    startPolling()
                },
                onFailed = { t ->
                    GLog.w("Navi", "내비게이션 서비스 연결 실패", t)
                    post { state.update { it.copy(connected = false, lastError = t.message) } }
                },
            )
        }.onFailure { GLog.w("Navi", "initialize 실패", it) }
    }

    /** 다음 길 안내까지 남은 거리를 주기적으로 물어봄 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    helper?.getTBTInfo()
                    helper?.getCurrentLocationInfo()
                }
                delay(1500)
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

    /** 목적지(와 경유지 최대 두 곳)를 순정 내비게이션에 넣음 */
    fun routeTo(name: String, pos: LatLng, waypoints: List<Pair<String, LatLng>> = emptyList()) {
        val h = helper ?: return
        runCatching {
            if (waypoints.isEmpty()) {
                h.requestRoute(routeInfo(name, pos))
            } else {
                val wps = waypoints.take(2).mapIndexed { i, (wName, wPos) ->
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
                h.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination = routeInfo(name, pos), waypoints = wps))
            }
            GLog.i("Navi", "목적지 요청: $name (경유지 ${waypoints.size}곳)")
            post { state.update { it.copy(destinationName = name, lastEvent = "목적지 요청 $name") } }
        }.onFailure { GLog.w("Navi", "목적지 요청 실패", it) }
    }

    /** 지금 경로를 유지한 채 경유지 하나를 넣음 */
    fun addWaypoint(name: String, pos: LatLng) {
        val h = helper ?: return
        runCatching {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = pos.lat,
                    longitude = pos.lon,
                    poiName = name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = name,
                    poiSubId = "0",
                )
            )
            GLog.i("Navi", "경유지 추가: $name")
        }.onFailure { GLog.w("Navi", "경유지 추가 실패", it) }
    }

    fun cancelRoute() {
        runCatching { helper?.cancelRoute() }
    }

    fun release() {
        pollJob?.cancel()
        runCatching {
            helper?.removeListener(listener)
            helper?.release()
        }
        helper = null
    }
}
