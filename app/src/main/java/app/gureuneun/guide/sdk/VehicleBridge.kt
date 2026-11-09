package app.gureuneun.guide.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.GloveBoxLockStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
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
import java.util.concurrent.Executors

/**
 * Vehicle SDK 연결부.
 * 읽기: 기어, 방향지시등, 와이퍼, 전조등(하향등), 좌석 착석, 안전벨트, 창문 위치, 실내 조명,
 *       이번 주행 시간, 배터리 잔량, 갈 수 있는 거리, 공조(내기 순환, 바람 세기, 온도), 글로브 박스 잠금, 선루프 지원 여부
 * 쓰기: 실내 조명 색과 밝기, 창문 위치(정차 중 사용자 확인 뒤에만), 공조(바깥 공기, 온도),
 *       글로브 박스 잠금(정차 중 운전자 확인 뒤에만), 선루프(지원하는 차, 정차 중에만)
 * 차마다 지원하는 기능이 달라서 쓰기 전에 지원 여부(capability)를 먼저 확인하고, 없으면 조용히 건너뜀.
 * 차량 서비스 호출은 모두 전용 스레드 하나에서 차례로 함 (화면 스레드가 막혀 앱이 멈춘 것처럼 보이지 않게)
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
        /** 이번 주행 시간(분). 쉬어 갈 때를 알려 줄 때 씀 */
        val drivingMin: Int? = null,
        /** 배터리 잔량(%) */
        val socPct: Int? = null,
        /** 갈 수 있는 거리(km) */
        val rangeKm: Double? = null,
        /** 내기 순환이 켜져 있으면 true, 바깥 공기면 false */
        val recirc: Boolean? = null,
        val fanSpeed: Int? = null,
        val fanMax: Int? = null,
        /** 운전석 설정 온도(섭씨) */
        val tempC: Float? = null,
        val tempMin: Float? = null,
        val tempMax: Float? = null,
        val gloveSupported: Boolean? = null,
        val gloveLocked: Boolean? = null,
        /** 선루프 위치를 움직일 수 있는 차인지 */
        val sunroof: Boolean? = null,
        val sunroofPos: Int? = null,
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
    private var recircAreas: List<SeatArea> = emptyList()
    private var fanAreas: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var tempAreas: Map<SeatArea, AreaBounds<Float>> = emptyMap()
    private var roofArea: WindowArea? = null
    private var roofBounds: AreaBounds<Int>? = null
    /** 차량 서비스가 준비되기 전에 들어온 조명 요청 (준비되면 바로 적용) */
    private var pendingAmbient: Pair<Int, Int>? = null
    private var started = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-sdk").apply { isDaemon = true } }

    /** 차량 서비스 호출을 전용 스레드에서 차례로 실행 */
    private fun bg(block: () -> Unit) = io.execute { runCatching(block).onFailure { fail("차량 호출", it) } }

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
            bg {
                registerAll(v)
                registerCare(v)
                registerClimate(v)
                registerBody(v)
            }
            delay(1500)
            bg { readAll(v) }
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
                pendingAmbient?.let { (c, b) ->
                    pendingAmbient = null
                    post { setAmbient(c, b) }
                }
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

    // ------------------------------------------------------------ 주행 시간, 배터리, 갈 수 있는 거리

    private fun registerCare(v: Vehicle) {
        registerDrivingTime(v, 0)
        runCatching {
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(socPct = value) } }
                }

                override fun onFailed(e: Exception) = fail("배터리 구독", e)
            })
            v.getEvBattery().getEvBatteryStateOfCharge({ s -> if (s != null) post { state.update { it.copy(socPct = s) } } }, { e -> fail("배터리 읽기", e) })
        }.onFailure { fail("배터리 구독", it) }
        runCatching {
            v.getOdometer().registerRangeRemainingListener(object : RangeRemainingListener {
                override fun onRangeRemainingChanged(value: Float?) {
                    if (value != null) post { state.update { it.copy(rangeKm = rangeToKm(value)) } }
                }

                override fun onFailed(e: Exception) = fail("주행 가능 거리 구독", e)
            })
            v.getOdometer().getRangeRemainingDistance({ r -> if (r != null) post { state.update { it.copy(rangeKm = rangeToKm(r)) } } }, { e -> fail("주행 가능 거리 읽기", e) })
        }.onFailure { fail("주행 가능 거리 구독", it) }
    }

    /**
     * 주행 시간은 차마다 주는 신호가 달라서 이번 주행, 누적, 충전 뒤 순서로 지원하는 것을 찾아 구독함.
     * 지원 여부를 먼저 물어보고(check...Capabilities) 되는 것만 구독하므로 안 되는 차에서 오류가 쌓이지 않음
     */
    private fun registerDrivingTime(v: Vehicle, step: Int) {
        val o = v.getOdometer()
        val name = listOf("이번 주행", "누적", "충전 뒤").getOrNull(step) ?: run {
            GLog.i("Vehicle", "주행 시간 신호를 주지 않는 차 (쉬어 가기 알림은 앱이 잰 시간으로)")
            return
        }
        val listener = object : DrivingTimeListener {
            override fun onDrivingTimeUpdated(value: Int?) {
                if (value != null) post { state.update { it.copy(drivingMin = value) } }
            }

            override fun onFailed(e: Exception) = fail("주행 시간 구독", e)
        }
        val onValue: (Int?) -> Unit = { m -> if (m != null) post { state.update { it.copy(drivingMin = m) } } }
        val ok: (Any?) -> Unit = {
            GLog.i("Vehicle", "주행 시간 신호: $name")
            runCatching {
                when (step) {
                    0 -> { o.registerTripCurrentDrivingTime(listener); o.getTripCurrentDrivingTime(onValue) { e -> fail("주행 시간 읽기", e) } }
                    1 -> { o.registerTripCumulativeDrivingTime(listener); o.getTripCumulativeDrivingTime(onValue) { e -> fail("주행 시간 읽기", e) } }
                    else -> { o.registerTripSinceRefillDrivingTime(listener); o.getTripSinceRefillDrivingTime(onValue) { e -> fail("주행 시간 읽기", e) } }
                }
            }.onFailure { fail("주행 시간 구독", it) }
        }
        val next: (Exception) -> Unit = { registerDrivingTime(v, step + 1) }
        runCatching {
            when (step) {
                0 -> o.checkTripCurrentDrivingTimeCapabilities(ok, next)
                1 -> o.checkTripCumulativeDrivingTimeCapabilities(ok, next)
                else -> o.checkTripSinceRefillDrivingTimeCapabilities(ok, next)
            }
        }.onFailure { registerDrivingTime(v, step + 1) }
    }

    /** 차량 신호 RANGE_REMAINING 은 미터 단위 */
    private fun rangeToKm(v: Float): Double = v / 1000.0

    // ------------------------------------------------------------ 공조

    private fun registerClimate(v: Vehicle) {
        runCatching {
            v.getHVAC().checkHvacRecirculationCapabilities({ areas: List<SeatArea> ->
                recircAreas = areas
                GLog.i("Vehicle", "내기 순환 지원 ${areas.size}구역")
                areas.firstOrNull()?.let { a ->
                    runCatching {
                        v.getHVAC().registerHvacRecirculationState(a, object : HvacRecirculationStateListener {
                            override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) {
                                if (value != null) post { state.update { it.copy(recirc = value) } }
                            }

                            override fun onFailed(e: Exception) = fail("내기 순환 구독", e)
                        })
                        v.getHVAC().getHvacRecirculationState(a, { b -> if (b != null) post { state.update { it.copy(recirc = b) } } }, { e -> fail("내기 순환 읽기", e) })
                    }.onFailure { fail("내기 순환 구독", it) }
                }
            }, { e -> fail("내기 순환 지원 확인", e) })
        }.onFailure { fail("내기 순환 지원 확인", it) }
        runCatching {
            v.getHVAC().checkHvacFanSpeedCapabilities({ m: Map<SeatArea, AreaBounds<Int>> ->
                fanAreas = m
                GLog.i("Vehicle", "바람 세기 지원 ${m.size}구역 ${m.values.firstOrNull()?.let { "${it.minValue}~${it.maxValue}" } ?: ""}")
                m.entries.firstOrNull()?.let { (a, b) ->
                    post { state.update { it.copy(fanMax = b.maxValue) } }
                    runCatching {
                        v.getHVAC().registerHvacFanSpeed(a, object : HvacFanSpeedListener {
                            override fun onHvacFanSpeedUpdated(value: Int?) {
                                if (value != null) post { state.update { it.copy(fanSpeed = value) } }
                            }

                            override fun onFailed(e: Exception) = fail("바람 세기 구독", e)
                        })
                        v.getHVAC().getHvacFanSpeed(a, { s -> if (s != null) post { state.update { it.copy(fanSpeed = s) } } }, { e -> fail("바람 세기 읽기", e) })
                    }.onFailure { fail("바람 세기 구독", it) }
                }
            }, { e -> fail("바람 세기 지원 확인", e) })
        }.onFailure { fail("바람 세기 지원 확인", it) }
        runCatching {
            v.getHVAC().checkHvacTemperatureCapabilities({ m: Map<SeatArea, AreaBounds<Float>> ->
                tempAreas = m
                GLog.i("Vehicle", "온도 지원 ${m.size}구역 ${m.values.firstOrNull()?.let { "${it.minValue}~${it.maxValue}" } ?: ""}")
                val driver = driverFirst(m.keys)
                val b = driver?.let { m[it] }
                post { state.update { it.copy(tempMin = b?.minValue, tempMax = b?.maxValue) } }
                driver?.let { a ->
                    runCatching {
                        v.getHVAC().getHvacTemperature(a, TemperatureUnit.CELSIUS, { t -> if (t != null) post { state.update { it.copy(tempC = t) } } }, { e -> fail("온도 읽기", e) })
                    }
                }
            }, { e -> fail("온도 지원 확인", e) })
        }.onFailure { fail("온도 지원 확인", it) }
    }

    private fun driverFirst(areas: Collection<SeatArea>): SeatArea? = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()

    /** 내기 순환(true) 또는 바깥 공기(false). 지원하지 않는 차면 false 를 돌려줌 */
    fun setRecirculation(on: Boolean): Boolean {
        val v = vehicle ?: return false
        val a = recircAreas.firstOrNull() ?: return false
        bg {
            v.getHVAC().setHvacRecirculation(a, on, { _, value -> if (value != null) post { state.update { it.copy(recirc = value) } } }, { e -> fail("내기 순환 바꾸기", e) })
        }
        GLog.i("Vehicle", if (on) "내기 순환으로 돌림" else "바깥 공기 들이기")
        return true
    }

    fun setFanSpeed(level: Int): Boolean {
        val v = vehicle ?: return false
        val (a, b) = fanAreas.entries.firstOrNull() ?: return false
        val lv = level.coerceIn(b.minValue, b.maxValue)
        bg {
            v.getHVAC().setHvacFanSpeed(a, lv, { _, value -> if (value != null) post { state.update { it.copy(fanSpeed = value) } } }, { e -> fail("바람 세기 바꾸기", e) })
        }
        return true
    }

    /** 모든 공조 구역 온도를 운전석 기준으로 delta 만큼 바꿈. 바뀐 온도를 돌려줌 (지원하지 않으면 null) */
    fun nudgeTemperature(delta: Float): Float? {
        val v = vehicle ?: return null
        if (tempAreas.isEmpty()) return null
        val driver = driverFirst(tempAreas.keys) ?: return null
        val cur = state.value.tempC ?: 22f
        val bounds = tempAreas[driver]
        val target = if (bounds != null) (cur + delta).coerceIn(bounds.minValue, bounds.maxValue) else cur + delta
        val areas = tempAreas
        bg {
            areas.forEach { (a, b) ->
                v.getHVAC().setHvacTemperature(a, target.coerceIn(b.minValue, b.maxValue), TemperatureUnit.CELSIUS, { _, _ -> }, { e -> fail("온도 바꾸기", e) })
            }
        }
        post { state.update { it.copy(tempC = target) } }
        GLog.i("Vehicle", "설정 온도 $cur → $target")
        return target
    }

    // ------------------------------------------------------------ 글로브 박스, 선루프

    private fun registerBody(v: Vehicle) {
        runCatching {
            v.getDoor().checkGloveBoxLockCapabilities({ _ ->
                GLog.i("Vehicle", "글로브 박스 잠금 지원")
                post { state.update { it.copy(gloveSupported = true) } }
                runCatching {
                    v.getDoor().registerGloveBoxLockState(object : GloveBoxLockStateListener {
                        override fun onGloveBoxLockStateUpdated(value: Boolean?) {
                            if (value != null) post { state.update { it.copy(gloveLocked = value) } }
                        }

                        override fun onFailed(e: Exception) = fail("글로브 박스 구독", e)
                    })
                    v.getDoor().getCurrentGloveBoxLockState({ b -> if (b != null) post { state.update { it.copy(gloveLocked = b) } } }, { e -> fail("글로브 박스 읽기", e) })
                }.onFailure { fail("글로브 박스 구독", it) }
            }, { e ->
                GLog.i("Vehicle", "글로브 박스 잠금 신호를 주지 않는 차: ${e.message}")
                post { state.update { it.copy(gloveSupported = false) } }
            })
        }.onFailure { post { state.update { it.copy(gloveSupported = false) } } }
        runCatching {
            v.getWindow().checkWindowPositionCapabilities({ m: Map<WindowArea, AreaBounds<Int>> ->
                val roof = m.entries.firstOrNull { (a, _) -> WindowValue.ROOF_TOP_1 in a.values || WindowValue.ROOF_TOP_2 in a.values }
                roofArea = roof?.key
                roofBounds = roof?.value
                GLog.i("Vehicle", "위치를 움직일 수 있는 창: " + m.keys.joinToString { a -> a.values.joinToString("+") { it.name } } + if (roof != null) " (선루프 있음)" else " (선루프 없음)")
                post { state.update { it.copy(sunroof = roof != null) } }
            }, { e ->
                fail("창문 지원 확인", e)
                post { state.update { it.copy(sunroof = false) } }
            })
        }.onFailure { post { state.update { it.copy(sunroof = false) } } }
    }

    /** 글로브 박스 잠금. 정차 여부와 운전자 확인은 부르는 쪽에서 먼저 확인함. 지원하지 않는 차면 false */
    fun setGloveBoxLock(locked: Boolean): Boolean {
        val v = vehicle ?: return false
        if (state.value.gloveSupported != true) return false
        bg {
            v.getDoor().setGloveBoxLock(locked, { value -> if (value != null) post { state.update { it.copy(gloveLocked = value) } } }, { e -> fail("글로브 박스 잠금", e) })
        }
        GLog.i("Vehicle", if (locked) "글로브 박스 잠금" else "글로브 박스 잠금 풀기")
        return true
    }

    /** 선루프 위치(0 닫힘, 100 끝까지). 지원하는 차에서만 움직임 */
    fun setSunroof(percent: Int): Boolean {
        val v = vehicle ?: return false
        val a = roofArea ?: return false
        val b = roofBounds
        val pos = if (b != null) b.minValue + (b.maxValue - b.minValue) * percent.coerceIn(0, 100) / 100 else percent
        bg {
            v.getWindow().setWindowPosition(a, pos, { _, value -> if (value != null) post { state.update { it.copy(sunroofPos = value) } } }, { e -> fail("선루프", e) })
        }
        GLog.i("Vehicle", "선루프 위치 $pos")
        return true
    }

    /** 실내 조명 색(RGB)과 밝기(0~100)를 바꿈 */
    fun setAmbient(color: Int, brightness: Int) {
        val v = vehicle ?: return
        if (ambientAreas.isEmpty()) {
            pendingAmbient = color to brightness
            return
        }
        val areas = ambientAreas
        bg {
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
        }
        GLog.i("Vehicle", "실내 조명 색 ${"%06X".format(color and 0xFFFFFF)} 밝기 $brightness")
    }

    /** 창문 위치를 바꿈. 정차 여부와 사용자 확인은 부르는 쪽에서 먼저 확인함 */
    fun setWindows(position: Int, windows: List<WindowValue> = SIDE_WINDOWS) {
        val v = vehicle ?: return
        bg {
            windows.forEach { w ->
                runCatching {
                    v.getWindow().setWindowPosition(WindowArea(w), position, { _, value ->
                        if (value != null) post { state.update { it.copy(windows = it.windows + (w.name to value)) } }
                    }, { e -> fail("창문 움직이기 ${w.name}", e) })
                }.onFailure { fail("창문 움직이기", it) }
            }
        }
        GLog.i("Vehicle", "창문 위치 $position")
    }

    fun release() {
        runCatching { vehicle?.release() }
        vehicle = null
        started = false
    }
}
