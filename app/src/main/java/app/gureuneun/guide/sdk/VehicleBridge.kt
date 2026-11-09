package app.gureuneun.guide.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Vehicle SDK 연결부.
 * 읽기: 기어, 방향지시등, 와이퍼, 전조등(하향등), 좌석 착석, 안전벨트, 창문 위치, 실내 조명
 * 쓰기: 실내 조명 색과 밝기, 창문 위치(정차 중 사용자 확인 뒤에만)
 */
class VehicleBridge(private val context: Context, private val scope: CoroutineScope) {

    data class State(
        val connected: Boolean = false,
        val gear: String = "-",
        val turnSignal: String = "OFF",
        val wiperOn: Boolean = false,
        val lowBeamOn: Boolean = false,
        val belts: Map<String, Boolean> = emptyMap(),
        val occupied: Map<String, Boolean> = emptyMap(),
        val windows: Map<String, Int> = emptyMap(),
        val ambientColor: Int? = null,
        val ambientBrightness: Int? = null,
        val ambientAreas: Int = 0,
        /** 좌석별 '벨트 안 맴' 경고. 좌석별 착용 신호가 없는 차에서는 이걸로 판단 */
        val beltWarnings: Map<String, Boolean> = emptyMap(),
        val lastError: String? = null,
    ) {
        /** 기어 정보를 못 받으면(차량 서비스 없음) 정차로 봄 */
        val parked: Boolean get() = gear == "P" || gear == "-"

        /** 사람이 앉은 자리. 착석 정보가 없으면 운전석만 있다고 봄 */
        val seatedKeys: List<String>
            get() = SEAT_KEYS.filter { occupied[it] == true }.ifEmpty { listOf("ROW_1_LEFT") }

        private val perSeatBelts: Boolean get() = belts.values.any { it }

        fun buckled(key: String): Boolean = belts[key] == true || (!perSeatBelts && beltWarnings[key] == false)

        val buckledCount: Int get() = seatedKeys.count { buckled(it) }
        val allBuckled: Boolean
            get() = when {
                perSeatBelts -> seatedKeys.all { belts[it] == true }
                beltWarnings.isNotEmpty() -> seatedKeys.all { beltWarnings[it] == false }
                else -> false
            }
    }

    companion object {
        val SEATS = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
        val SEAT_KEYS = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")
        val SIDE_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
    }

    val state = MutableStateFlow(State())
    private var vehicle: Vehicle? = null
    private val main = Handler(Looper.getMainLooper())
    private var ambientAreas: List<SeatArea> = emptyList()
    private var started = false

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun fail(where: String, e: Throwable) {
        GLog.w("Vehicle", "$where 실패", e)
        post { state.update { it.copy(lastError = "$where: ${e.message ?: e.javaClass.simpleName}") } }
    }

    fun start() {
        if (started) return
        started = true
        val v = Vehicle(context.applicationContext)
        vehicle = v
        runCatching { v.initialize() }.onFailure { fail("initialize", it) }
        scope.launch {
            // 차량 서비스 연결에 시간이 걸릴 수 있어 잠시 기다린 뒤 구독함
            delay(700)
            registerAll(v)
            delay(1500)
            readAll(v)
        }
    }

    private fun gearName(g: VehicleGear?): String = when (g) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_UNKNOWN, null -> "-"
        else -> "D"
    }

    private fun registerAll(v: Vehicle) {
        runCatching {
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) {
                    post { state.update { it.copy(connected = true, gear = gearName(gear)) } }
                }

                override fun onFailed(e: Exception) = fail("기어 구독", e)
            })
        }.onFailure { fail("기어 구독", it) }

        runCatching {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                    val s = when (value) {
                        TurnSignalValue.LEFT -> "LEFT"
                        TurnSignalValue.RIGHT -> "RIGHT"
                        else -> "OFF"
                    }
                    post { state.update { it.copy(connected = true, turnSignal = s) } }
                }

                override fun onFailed(e: Exception) = fail("방향지시등 구독", e)
            })
        }.onFailure { fail("방향지시등 구독", it) }

        runCatching {
            v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
                override fun onWiperStateUpdated(value: WiperState?) {
                    post { state.update { it.copy(connected = true, wiperOn = value == WiperState.ON) } }
                }

                override fun onFailed(e: Exception) = fail("와이퍼 구독", e)
            })
        }.onFailure { fail("와이퍼 구독", it) }

        runCatching {
            v.getLight().registerLowBeamLightState(object : LightStateListener {
                override fun onLightStateUpdated(value: LightState?) {
                    post { state.update { it.copy(connected = true, lowBeamOn = value == LightState.ON) } }
                }

                override fun onFailed(e: Exception) = fail("전조등 구독", e)
            })
        }.onFailure { fail("전조등 구독", it) }

        SEATS.forEachIndexed { i, seat ->
            val key = SEAT_KEYS[i]
            runCatching {
                v.getSeat().registerSeatBeltBuckledState(SeatArea(seat), object : SeatBeltBuckledStateListener {
                    override fun onSeatBeltBuckledStateUpdated(value: Boolean?) {
                        post { state.update { it.copy(connected = true, belts = it.belts + (key to (value == true))) } }
                    }

                    override fun onFailed(e: Exception) = fail("벨트 구독 $key", e)
                })
            }.onFailure { fail("벨트 구독 $key", it) }
            runCatching {
                v.getSeat().registerSeatOccupancy(SeatArea(seat), object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(value: Boolean?) {
                        post { state.update { it.copy(connected = true, occupied = it.occupied + (key to (value == true))) } }
                    }

                    override fun onFailed(e: Exception) = fail("착석 구독 $key", e)
                })
            }.onFailure { fail("착석 구독 $key", it) }
        }

        SIDE_WINDOWS.forEach { w ->
            runCatching {
                v.getWindow().registerWindowPosition(WindowArea(w), object : WindowPositionListener {
                    override fun onWindowPositionUpdated(value: Int?) {
                        if (value != null) post { state.update { it.copy(windows = it.windows + (w.name to value)) } }
                    }

                    override fun onFailed(e: Exception) = fail("창문 구독 ${w.name}", e)
                })
            }.onFailure { fail("창문 구독 ${w.name}", it) }
        }

        SEATS.forEachIndexed { i, seat ->
            val key = SEAT_KEYS[i]
            runCatching {
                v.getSafety().registerSeatBeltNotBuckledWarning(SeatArea(seat), object : SeatBeltNotBuckledWarningListener {
                    override fun onSeatBeltNotBuckledWarningUpdated(value: Boolean?) {
                        if (value != null) post { state.update { it.copy(beltWarnings = it.beltWarnings + (key to value)) } }
                    }

                    override fun onFailed(e: Exception) = fail("벨트 경고 구독 $key", e)
                })
                v.getSafety().hasSeatBeltNotBuckledWarning(SeatArea(seat), { w ->
                    if (w != null) post { state.update { it.copy(beltWarnings = it.beltWarnings + (key to w)) } }
                }, { e -> fail("벨트 경고 읽기 $key", e) })
            }.onFailure { fail("벨트 경고 구독 $key", it) }
        }

        runCatching {
            v.getLight().checkAmbientLightBrightnessColorCapabilities({ _, areas ->
                ambientAreas = areas
                GLog.i("Vehicle", "실내 조명 색 영역 ${areas.size}곳")
                post { state.update { it.copy(ambientAreas = areas.size) } }
                areas.firstOrNull()?.let { area -> watchAmbient(v, area) }
            }, { e -> fail("조명 색 영역 확인", e) })
        }.onFailure { fail("조명 색 영역 확인", it) }
    }

    private fun watchAmbient(v: Vehicle, area: SeatArea) {
        runCatching {
            v.getLight().registerAmbientLightBrightnessColorListener(area, object : AmbientLightBrightnessColorListener {
                override fun onBrightnessColorUpdated(value: Int?) {
                    post { state.update { it.copy(ambientColor = value) } }
                }

                override fun onFailed(e: Exception) = fail("조명 색 구독", e)
            })
            v.getLight().registerAmbientLightBrightnessListener(area, object : AmbientLightBrightnessListener {
                override fun onBrightnessUpdated(value: Int?) {
                    post { state.update { it.copy(ambientBrightness = value) } }
                }

                override fun onFailed(e: Exception) = fail("조명 밝기 구독", e)
            })
        }.onFailure { fail("조명 구독", it) }
    }

    private fun readAll(v: Vehicle) {
        runCatching {
            v.getGear().getCurrentGearSelectionState({ g ->
                post { state.update { it.copy(connected = true, gear = gearName(g)) } }
            }, { e -> fail("기어 읽기", e) })
        }
        SEATS.forEachIndexed { i, seat ->
            val key = SEAT_KEYS[i]
            runCatching {
                v.getSeat().getCurrentSeatOccupancy(SeatArea(seat), { b ->
                    post { state.update { it.copy(occupied = it.occupied + (key to (b == true))) } }
                }, { e -> fail("착석 읽기 $key", e) })
                v.getSeat().getCurrentSeatBeltBuckledState(SeatArea(seat), { b ->
                    post { state.update { it.copy(belts = it.belts + (key to (b == true))) } }
                }, { e -> fail("벨트 읽기 $key", e) })
            }
        }
        runCatching {
            v.getLight().getCurrentLowBeamLightState({ s ->
                post { state.update { it.copy(lowBeamOn = s == LightState.ON) } }
            }, { e -> fail("전조등 읽기", e) })
        }
        runCatching {
            v.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), { s ->
                post { state.update { it.copy(wiperOn = s == WiperState.ON) } }
            }, { e -> fail("와이퍼 읽기", e) })
        }
        SIDE_WINDOWS.forEach { w ->
            runCatching {
                v.getWindow().getCurrentWindowPosition(WindowArea(w), { p ->
                    if (p != null) post { state.update { it.copy(windows = it.windows + (w.name to p)) } }
                }, { e -> fail("창문 읽기 ${w.name}", e) })
            }
        }
        ambientAreas.firstOrNull()?.let { area ->
            runCatching {
                v.getLight().getAmbientLightBrightnessColor(area, { c -> post { state.update { it.copy(ambientColor = c) } } }, { e -> fail("조명 색 읽기", e) })
                v.getLight().getAmbientLightBrightness(area, { b -> post { state.update { it.copy(ambientBrightness = b) } } }, { e -> fail("조명 밝기 읽기", e) })
            }
        }
    }

    /** 실내 조명 색(RGB)과 밝기(0~100)를 바꿈 */
    fun setAmbient(color: Int, brightness: Int) {
        val v = vehicle ?: return
        val areas = ambientAreas.ifEmpty { listOf(SeatArea(SeatValue.ROW_1_LEFT)) }
        areas.forEach { area ->
            runCatching {
                v.getLight().setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> }, { e -> fail("조명 켜기", e) })
            }
            runCatching {
                v.getLight().setAmbientLightBrightnessColor(area, color, { _, value ->
                    post { state.update { it.copy(ambientColor = value) } }
                }, { e -> fail("조명 색 바꾸기", e) })
            }.onFailure { fail("조명 색 바꾸기", it) }
            runCatching {
                v.getLight().setAmbientLightBrightness(area, brightness.coerceIn(0, 100), { _, value ->
                    post { state.update { it.copy(ambientBrightness = value) } }
                }, { e -> fail("조명 밝기 바꾸기", e) })
            }.onFailure { fail("조명 밝기 바꾸기", it) }
        }
        GLog.i("Vehicle", "실내 조명 색 ${"%06X".format(color and 0xFFFFFF)} 밝기 $brightness")
    }

    /** 창문 위치를 바꿈. 정차 여부와 사용자 확인은 부르는 쪽에서 먼저 확인함 */
    fun setWindows(position: Int, windows: List<WindowValue> = SIDE_WINDOWS) {
        val v = vehicle ?: return
        windows.forEach { w ->
            runCatching {
                v.getWindow().setWindowPosition(WindowArea(w), position, { _, value ->
                    if (value != null) post { state.update { it.copy(windows = it.windows + (w.name to value)) } }
                }, { e -> fail("창문 움직이기 ${w.name}", e) })
            }.onFailure { fail("창문 움직이기", it) }
        }
        GLog.i("Vehicle", "창문 위치 $position")
    }

    fun release() {
        runCatching { vehicle?.release() }
        vehicle = null
        started = false
    }
}
