package app.gureuneun.guide.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
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
import ai.pleos.playground.vehicle.listener.FuelPercentListener
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
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Vehicle SDK 연결부.
 * 읽기: 기어, 방향지시등, 와이퍼, 전조등(하향등), 좌석 착석, 안전벨트, 창문 위치, 실내 조명,
 *       이번 주행 시간, 배터리 잔량, 연료 잔량, 갈 수 있는 거리, 공조(내기 순환, 바람 세기, 온도), 글로브 박스 잠금, 선루프 지원 여부
 * 쓰기: 실내 조명 색과 밝기, 창문 위치(정차 중 사용자 확인 뒤에만), 공조(바깥 공기, 온도),
 *       글로브 박스 잠금(정차 중 운전자 확인 뒤에만), 선루프(지원하는 차, 정차 중에만)
 * 차마다 지원하는 기능이 달라서 쓰기 전에 지원 여부(capability)와 쓰기 권한(AccessType WRITE, READ_WRITE)을 먼저 확인하고,
 * 없으면 조용히 건너뜀. 창문, 선루프 값은 차가 알려 준 범위(AreaBounds)로 환산함.
 * 창문, 선루프, 글로브 박스 잠금 풀기는 기어가 P 로 확인된 때만, 그리고 움직이기 직전에 P 를 한 번 더 물어 확인될 때만 함.
 * 앱이 바꾼 조명, 내기 순환, 글로브 박스, 선루프는 바꾸기 직전 값을 기록해 두고 restoreAll() 로 되돌림
 * (창문은 끼임 위험 때문에 앱이 저절로 올리지 않으므로 기록하지 않음).
 * 차량 서비스 호출은 모두 전용 스레드 하나에서 차례로 함 (화면 스레드가 막혀 앱이 멈춘 것처럼 보이지 않게).
 * 연결이나 기어 구독이 안 되면 1초에서 30초까지 간격을 늘려 다시 시도함.
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
        /** 연료 잔량(%). 연료 신호가 없는 차(전기차)는 null */
        val fuelPct: Int? = null,
        /** 동력원 "EV", "HEV", "ICE". 아직 모르거나 판단할 신호가 없으면 "UNKNOWN" (배터리, 연료 신호 지원 여부로 판단) */
        val powertrain: String = "UNKNOWN",
        val lastError: String? = null,
    ) {
        /** 기어가 P 로 확인됐을 때만 정차. 기어 정보를 못 받으면(차량 서비스 없음, "-") 정차로 보지 않음 */
        val parked: Boolean get() = VehicleMath.isParked(gear)

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
        val FRONT_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT)
        /** SDK 답을 기다리는 시간 */
        private const val WAIT_MS = 1500L
        private const val MAX_RETRY_MS = 30_000L
        // 되돌리기 기록 이름
        private const val KEY_GLOVE = "glove"
        private const val KEY_ROOF = "roof"
        private const val UNKNOWN_POS = Int.MIN_VALUE
    }

    val state = MutableStateFlow(State())
    @Volatile private var vehicle: Vehicle? = null
    private val main = Handler(Looper.getMainLooper())
    // 아래 값은 전용 스레드에서 쓰고 여러 스레드에서 읽음
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()
    @Volatile private var ambientWritable = false
    @Volatile private var brightnessBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    /** 조명 스위치 지원 여부를 확인했는지, 확인했다면 쓸 수 있는 구역과 고를 수 있는 값 */
    @Volatile private var switchKnown = false
    @Volatile private var switchAreas: Map<SeatArea, List<LightSwitch>> = emptyMap()
    @Volatile private var recircAreas: List<SeatArea> = emptyList()
    @Volatile private var recircWritable = false
    @Volatile private var fanAreas: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var fanWritable = false
    @Volatile private var tempAreas: Map<SeatArea, AreaBounds<Float>> = emptyMap()
    @Volatile private var tempWritable = false
    @Volatile private var tempStep: Float? = null
    /** 위치를 바꿀 수 있는 창과 범위 (쓰기 권한이 있을 때만) */
    @Volatile private var windowMap: Map<WindowArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var roofArea: WindowArea? = null
    @Volatile private var roofBounds: AreaBounds<Int>? = null
    @Volatile private var gloveWritable = false
    /** 차량 서비스가 준비되기 전에 들어온 조명 요청 (준비되면 바로 적용) */
    @Volatile private var pendingAmbient: Pair<Int, Int>? = null
    /** 연결 확인, 구독, 지원 여부 확인까지 끝났는지 */
    @Volatile private var ready = false
    @Volatile private var started = false
    /** 연결을 다시 시도한 횟수 (전용 스레드에서만) */
    private var attempt = 0
    private val retrying = AtomicBoolean(false)
    /** 구독을 풀 방법 (전용 스레드에서만) */
    private val unregs = ArrayList<() -> Unit>()
    /** 차량 연결 전에 들어온 되돌리기 요청 (메인 스레드에서만) */
    private val waitingRestore = ArrayList<((Boolean) -> Unit)?>()
    /** 앱이 바꾸기 전 차 상태 기록. 프로세스가 죽어도 남아서 다음 restoreAll() 때 되돌림 */
    private val restore: SharedPreferences = context.getSharedPreferences("vehicle_restore", Context.MODE_PRIVATE)
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

    private fun readable(c: CapabilityConfig?) = c?.accessType == AccessType.READ || c?.accessType == AccessType.READ_WRITE
    private fun writable(c: CapabilityConfig?) = c?.accessType == AccessType.WRITE || c?.accessType == AccessType.READ_WRITE

    /** 값 하나가 오는 SDK 콜백의 답 (값이 null 인 답도 있음) */
    private class Got<T>(val value: T?)

    /**
     * SDK 의 비동기 읽기(값 콜백, 오류 콜백)를 전용 스레드에서 기다림. 시간 안에 답이 없거나 오류면 null.
     * SDK 는 콜백을 자기 스레드(코루틴 Default)에서 부르므로 여기서 기다려도 서로 막히지 않음
     */
    private fun <T> await(onError: ((Exception) -> Unit)? = null, call: (ok: (T?) -> Unit, err: (Exception) -> Unit) -> Unit): Got<T>? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Got<T>?>(null)
        runCatching {
            call({ value -> box.compareAndSet(null, Got(value)); latch.countDown() }, { e -> onError?.invoke(e); latch.countDown() })
        }.onFailure { e ->
            if (e is Exception) onError?.invoke(e)
            latch.countDown()
        }
        runCatching { latch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        return box.get()
    }

    /** 지원 여부 확인처럼 값 두 개가 오는 콜백을 기다림. 지원하지 않거나 답이 없으면 null */
    private fun <A, B> await2(what: String, call: (ok: (A, B) -> Unit, err: (Exception) -> Unit) -> Unit): Pair<A, B>? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<Pair<A, B>?>(null)
        runCatching {
            call({ a, b -> box.compareAndSet(null, a to b); latch.countDown() }, { e ->
                GLog.i("Vehicle", "$what: 지원하지 않음 (${e.message ?: e.javaClass.simpleName})")
                latch.countDown()
            })
        }.onFailure { e ->
            GLog.w("Vehicle", "$what 확인 실패", e)
            latch.countDown()
        }
        runCatching { latch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        return box.get()
    }

    /** 바꾸기 요청을 보내고 성공 콜백(또는 오류, 시간 초과)까지 기다림. 성공 콜백이 오면 true */
    private fun setWait(what: String, call: (done: () -> Unit, err: (Exception) -> Unit) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        runCatching {
            call({ ok.set(true); latch.countDown() }, { e -> fail(what, e); latch.countDown() })
        }.onFailure {
            fail(what, it)
            latch.countDown()
        }
        runCatching { latch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        return ok.get()
    }

    /** 구독하고 나중에 풀 방법을 같이 기억함 (전용 스레드에서만) */
    private fun reg(what: String, register: () -> Unit, unregister: () -> Unit) {
        runCatching(register).onSuccess { unregs.add(unregister) }.onFailure { fail(what, it) }
    }

    private fun unregisterAll() {
        unregs.forEach { runCatching(it) }
        unregs.clear()
    }

    fun start() {
        if (started) return
        started = true
        connect(fresh = true)
    }

    /** 차량 서비스에 붙음. fresh 면 Vehicle 을 새로 만들어 처음부터 */
    private fun connect(fresh: Boolean) = bg {
        val cur = vehicle
        if (cur != null && !fresh) {
            subscribe(cur)
            return@bg
        }
        ready = false
        if (cur != null) {
            unregisterAll()
            runCatching { cur.release() }
        }
        val v = Vehicle(context.applicationContext)
        vehicle = v
        runCatching { v.initialize() }.onFailure { fail("initialize", it) }
        // 차량 서비스 연결에 시간이 걸릴 수 있어 잠시 기다린 뒤 확인함
        main.postDelayed({ bg { if (vehicle === v) subscribe(v) } }, 700)
    }

    /** 기어를 한 번 읽어 차량 서비스가 답하는지 보고, 답하면 구독과 지원 여부 확인을 함. 답이 없으면 간격을 늘려 다시 */
    private fun subscribe(v: Vehicle) {
        if (!started || vehicle !== v) return
        val gear = await<VehicleGear> { ok, err -> v.getGear().getCurrentGearSelectionState(ok, err) }
        if (gear == null) {
            post { state.update { it.copy(connected = false) } }
            retryLater("차량 서비스가 답하지 않음")
            return
        }
        post { state.update { it.copy(connected = true, gear = gearName(gear.value)) } }
        unregisterAll()
        ready = false
        attempt = 0
        registerAll(v)
        registerCare(v)
        registerClimate(v)
        registerBody(v)
        ready = true
        GLog.i("Vehicle", "차량 서비스 연결, 구독 끝")
        pendingAmbient?.let { (c, b) ->
            pendingAmbient = null
            applyAmbient(v, c, b)
        }
        main.postDelayed({ bg { if (vehicle === v) readAll(v) } }, 1500)
        post { flushRestore() }
    }

    /** 1, 2, 4, 8, 16, 30초 간격으로 다시 시도. 세 번째마다 Vehicle 을 새로 만듦 */
    private fun retryLater(why: String) {
        if (!started || !retrying.compareAndSet(false, true)) return
        bg {
            attempt++
            val n = attempt
            val wait = (1000L shl (n - 1).coerceIn(0, 5)).coerceAtMost(MAX_RETRY_MS)
            if (n <= 3 || n % 10 == 0) GLog.w("Vehicle", "$why. ${wait / 1000}초 뒤 다시 연결 (${n}번째)")
            main.postDelayed({
                retrying.set(false)
                if (started) connect(fresh = n % 3 == 0)
            }, wait)
        }
    }

    private fun gearName(g: VehicleGear?): String = VehicleMath.gearLetter(g?.name)

    private fun registerAll(v: Vehicle) {
        val gear = v.getGear()
        val gearL = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                post { state.update { it.copy(connected = true, gear = gearName(gear)) } }
            }

            override fun onFailed(e: Exception) {
                fail("기어 구독", e)
                // 기어는 차를 움직여도 되는지 판단하는 데 쓰므로, 끊기면 처음부터 다시 붙음
                retryLater("기어 구독 오류")
            }
        }
        reg("기어 구독", { gear.registerGearSelection(gearL) }, { gear.unregisterGearSelection(gearL) })

        val turn = v.getTurnSignal()
        val turnL = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                val s = when (value) {
                    TurnSignalValue.LEFT -> "LEFT"
                    TurnSignalValue.RIGHT -> "RIGHT"
                    else -> "OFF"
                }
                post { state.update { it.copy(connected = true, turnSignal = s) } }
            }

            override fun onFailed(e: Exception) = fail("방향지시등 구독", e)
        }
        reg("방향지시등 구독", { turn.registerTurnSignalState(turnL) }, { turn.unregisterTurnSignalState(turnL) })

        val wiper = v.getWiper()
        val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiperL = object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) {
                post { state.update { it.copy(connected = true, wiperOn = value == WiperState.ON) } }
            }

            override fun onFailed(e: Exception) = fail("와이퍼 구독", e)
        }
        reg("와이퍼 구독", { wiper.registerWiperState(windshield, wiperL) }, { wiper.unregisterWiperState(windshield, wiperL) })

        val light = v.getLight()
        val beamL = object : LightStateListener {
            override fun onLightStateUpdated(value: LightState?) {
                post { state.update { it.copy(connected = true, lowBeamOn = value == LightState.ON) } }
            }

            override fun onFailed(e: Exception) = fail("전조등 구독", e)
        }
        reg("전조등 구독", { light.registerLowBeamLightState(beamL) }, { light.unregisterLowBeamLightState(beamL) })

        val seatApi = v.getSeat()
        SEATS.forEachIndexed { i, seat ->
            val key = SEAT_KEYS[i]
            val area = SeatArea(seat)
            val beltL = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(value: Boolean?) {
                    post { state.update { it.copy(connected = true, belts = it.belts + (key to (value == true))) } }
                }

                override fun onFailed(e: Exception) = fail("벨트 구독 $key", e)
            }
            reg("벨트 구독 $key", { seatApi.registerSeatBeltBuckledState(area, beltL) }, { seatApi.unregisterSeatBeltBuckledState(area, beltL) })
            val occL = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) {
                    post { state.update { it.copy(connected = true, occupied = it.occupied + (key to (value == true))) } }
                }

                override fun onFailed(e: Exception) = fail("착석 구독 $key", e)
            }
            reg("착석 구독 $key", { seatApi.registerSeatOccupancy(area, occL) }, { seatApi.unregisterSeatOccupancy(area, occL) })
        }

        val window = v.getWindow()
        SIDE_WINDOWS.forEach { w ->
            val area = WindowArea(w)
            val winL = object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(windows = it.windows + (w.name to value)) } }
                }

                override fun onFailed(e: Exception) = fail("창문 구독 ${w.name}", e)
            }
            reg("창문 구독 ${w.name}", { window.registerWindowPosition(area, winL) }, { window.unregisterWindowPosition(area, winL) })
        }

        val safety = v.getSafety()
        SEATS.forEachIndexed { i, seat ->
            val key = SEAT_KEYS[i]
            val area = SeatArea(seat)
            val warnL = object : SeatBeltNotBuckledWarningListener {
                override fun onSeatBeltNotBuckledWarningUpdated(value: Boolean?) {
                    if (value != null) post { state.update { it.copy(beltWarnings = it.beltWarnings + (key to value)) } }
                }

                override fun onFailed(e: Exception) = fail("벨트 경고 구독 $key", e)
            }
            reg("벨트 경고 구독 $key", { safety.registerSeatBeltNotBuckledWarning(area, warnL) }, { safety.unregisterSeatBeltNotBuckledWarning(area, warnL) })
            runCatching {
                safety.hasSeatBeltNotBuckledWarning(area, { w ->
                    if (w != null) post { state.update { it.copy(beltWarnings = it.beltWarnings + (key to w)) } }
                }, { e -> fail("벨트 경고 읽기 $key", e) })
            }.onFailure { fail("벨트 경고 읽기 $key", it) }
        }

        // 실내 조명: 색을 바꿀 수 있는 구역, 밝기 범위, 스위치(켜고 끄기)
        val color = await2<CapabilityConfig?, List<SeatArea>?>("조명 색") { ok, err -> light.checkAmbientLightBrightnessColorCapabilities(ok, err) }
        val areas = color?.second.orEmpty()
        ambientAreas = areas
        ambientWritable = writable(color?.first)
        brightnessBounds = await2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>("조명 밝기") { ok, err ->
            light.checkAmbientLightBrightnessCapabilities(ok, err)
        }?.second.orEmpty()
        val sw = await2<CapabilityConfig?, Map<SeatArea, List<LightSwitch>>?>("조명 스위치") { ok, err -> light.checkAmbientLightSwitchCapabilities(ok, err) }
        switchKnown = sw != null
        switchAreas = if (sw != null && writable(sw.first)) sw.second.orEmpty() else emptyMap()
        GLog.i("Vehicle", "실내 조명 색 영역 ${areas.size}곳 (${color?.first?.accessType}), 스위치 ${switchAreas.size}곳")
        post { state.update { it.copy(ambientAreas = areas.size) } }
        areas.firstOrNull()?.let { area -> watchAmbient(v, area) }
    }

    private fun watchAmbient(v: Vehicle, area: SeatArea) {
        val light = v.getLight()
        val colorL = object : AmbientLightBrightnessColorListener {
            override fun onBrightnessColorUpdated(value: Int?) {
                post { state.update { it.copy(ambientColor = value) } }
            }

            override fun onFailed(e: Exception) = fail("조명 색 구독", e)
        }
        reg("조명 색 구독", { light.registerAmbientLightBrightnessColorListener(area, colorL) }, { light.unregisterAmbientLightBrightnessColorListener(area, colorL) })
        val brightL = object : AmbientLightBrightnessListener {
            override fun onBrightnessUpdated(value: Int?) {
                post { state.update { it.copy(ambientBrightness = value) } }
            }

            override fun onFailed(e: Exception) = fail("조명 밝기 구독", e)
        }
        reg("조명 밝기 구독", { light.registerAmbientLightBrightnessListener(area, brightL) }, { light.unregisterAmbientLightBrightnessListener(area, brightL) })
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

    // ------------------------------------------------------------ 주행 시간, 배터리, 연료, 갈 수 있는 거리

    private fun registerCare(v: Vehicle) {
        val o = v.getOdometer()
        // 이번 주행 시간: 차가 주는 '이번 주행' 값만 씀. 누적이나 충전 뒤 값은 쉬어 가기 판단을 틀리게 하므로 쓰지 않음.
        // 신호가 없는 차는 null 로 두고 엔진이 직접 잰 시간을 씀
        val dt = await<CapabilityConfig> { ok, err -> o.checkTripCurrentDrivingTimeCapabilities(ok, err) }
        if (dt != null && readable(dt.value)) {
            val dtL = object : DrivingTimeListener {
                override fun onDrivingTimeUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(drivingMin = value) } }
                }

                override fun onFailed(e: Exception) = fail("주행 시간 구독", e)
            }
            reg("주행 시간 구독", { o.registerTripCurrentDrivingTime(dtL) }, { o.unregisterTripCurrentDrivingTime(dtL) })
            runCatching {
                o.getTripCurrentDrivingTime({ m -> if (m != null) post { state.update { it.copy(drivingMin = m) } } }, { e -> fail("주행 시간 읽기", e) })
            }.onFailure { fail("주행 시간 읽기", it) }
            GLog.i("Vehicle", "주행 시간 신호: 이번 주행")
        } else {
            GLog.i("Vehicle", "이번 주행 시간 신호를 주지 않는 차 (쉬어 가기 알림은 앱이 잰 시간으로)")
            post { state.update { it.copy(drivingMin = null) } }
        }

        // 배터리 잔량(SOC)
        val ev = v.getEvBattery()
        val evOk = readable(await<CapabilityConfig> { ok, err -> ev.checkEvBatteryStateOfChargeCapabilities(ok, err) }?.value)
        if (evOk) {
            val socL = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(socPct = value) } }
                }

                override fun onFailed(e: Exception) = fail("배터리 구독", e)
            }
            reg("배터리 구독", { ev.registerEvBatteryStateOfCharge(socL) }, { ev.unregisterEvBatteryStateOfCharge(socL) })
            runCatching {
                ev.getEvBatteryStateOfCharge({ s -> if (s != null) post { state.update { it.copy(socPct = s) } } }, { e -> fail("배터리 읽기", e) })
            }.onFailure { fail("배터리 읽기", it) }
        }

        // 연료 잔량(%)
        val fuel = v.getFuel()
        val fuelOk = readable(await<CapabilityConfig> { ok, err -> fuel.checkFuelPercentCapabilities(ok, err) }?.value)
        if (fuelOk) {
            val fuelL = object : FuelPercentListener {
                override fun onFuelPercentUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(fuelPct = value) } }
                }

                override fun onFailed(e: Exception) = fail("연료 구독", e)
            }
            reg("연료 구독", { fuel.registerFuelPercent(fuelL) }, { fuel.unregisterFuelPercent(fuelL) })
            runCatching {
                fuel.getFuelPercent({ p -> if (p != null) post { state.update { it.copy(fuelPct = p) } } }, { e -> fail("연료 읽기", e) })
            }.onFailure { fail("연료 읽기", it) }
        }
        val pt = VehicleMath.powertrain(evOk, fuelOk)
        GLog.i("Vehicle", "동력원 $pt (배터리 신호 $evOk, 연료 신호 $fuelOk)")
        post { state.update { it.copy(powertrain = pt, socPct = if (evOk) it.socPct else null, fuelPct = if (fuelOk) it.fuelPct else null) } }

        val rangeL = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(value: Float?) {
                if (value != null) post { state.update { it.copy(rangeKm = rangeToKm(value)) } }
            }

            override fun onFailed(e: Exception) = fail("주행 가능 거리 구독", e)
        }
        reg("주행 가능 거리 구독", { o.registerRangeRemainingListener(rangeL) }, { o.unregisterRangeRemainingListener(rangeL) })
        runCatching {
            o.getRangeRemainingDistance({ r -> if (r != null) post { state.update { it.copy(rangeKm = rangeToKm(r)) } } }, { e -> fail("주행 가능 거리 읽기", e) })
        }.onFailure { fail("주행 가능 거리 읽기", it) }
    }

    /** 차량 신호 RANGE_REMAINING 은 미터 단위 */
    private fun rangeToKm(v: Float): Double = v / 1000.0

    // ------------------------------------------------------------ 공조

    private fun registerClimate(v: Vehicle) {
        val h = v.getHVAC()
        val rc = await2<CapabilityConfig?, List<SeatArea>?>("내기 순환") { ok, err -> h.checkHvacRecirculationCapabilities(ok, err) }
        recircAreas = rc?.second.orEmpty()
        recircWritable = writable(rc?.first)
        GLog.i("Vehicle", "내기 순환 지원 ${recircAreas.size}구역 (${rc?.first?.accessType})")
        recircAreas.firstOrNull()?.let { a ->
            val recircL = object : HvacRecirculationStateListener {
                override fun onHvacAutoRecirculationStateUpdated(value: Boolean?) {
                    if (value != null) post { state.update { it.copy(recirc = value) } }
                }

                override fun onFailed(e: Exception) = fail("내기 순환 구독", e)
            }
            reg("내기 순환 구독", { h.registerHvacRecirculationState(a, recircL) }, { h.unregisterHvacRecirculationState(a, recircL) })
            runCatching {
                h.getHvacRecirculationState(a, { b -> if (b != null) post { state.update { it.copy(recirc = b) } } }, { e -> fail("내기 순환 읽기", e) })
            }.onFailure { fail("내기 순환 읽기", it) }
        }

        val fc = await2<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>("바람 세기") { ok, err -> h.checkHvacFanSpeedCapabilities(ok, err) }
        val fm = fc?.second.orEmpty()
        fanAreas = fm
        fanWritable = writable(fc?.first)
        GLog.i("Vehicle", "바람 세기 지원 ${fm.size}구역 ${fm.values.firstOrNull()?.let { "${it.minValue}~${it.maxValue}" } ?: ""} (${fc?.first?.accessType})")
        fm.entries.firstOrNull()?.let { (a, b) ->
            post { state.update { it.copy(fanMax = b.maxValue) } }
            val fanL = object : HvacFanSpeedListener {
                override fun onHvacFanSpeedUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(fanSpeed = value) } }
                }

                override fun onFailed(e: Exception) = fail("바람 세기 구독", e)
            }
            reg("바람 세기 구독", { h.registerHvacFanSpeed(a, fanL) }, { h.unregisterHvacFanSpeed(a, fanL) })
            runCatching {
                h.getHvacFanSpeed(a, { s -> if (s != null) post { state.update { it.copy(fanSpeed = s) } } }, { e -> fail("바람 세기 읽기", e) })
            }.onFailure { fail("바람 세기 읽기", it) }
        }

        // 온도: 구역별 범위와 함께 한 번에 바꿀 수 있는 단위(increment)도 받음
        val tLatch = CountDownLatch(1)
        val tBox = AtomicReference<Triple<CapabilityConfig?, Map<SeatArea, AreaBounds<Float>>?, HvacTemperatureConfigArray?>?>(null)
        runCatching {
            h.checkHvacTemperatureCapabilities({ c: CapabilityConfig?, m: Map<SeatArea, AreaBounds<Float>>?, arr: HvacTemperatureConfigArray? ->
                tBox.compareAndSet(null, Triple(c, m, arr))
                tLatch.countDown()
            }, { e ->
                GLog.i("Vehicle", "온도: 지원하지 않음 (${e.message ?: e.javaClass.simpleName})")
                tLatch.countDown()
            })
        }.onFailure {
            GLog.w("Vehicle", "온도 확인 실패", it)
            tLatch.countDown()
        }
        runCatching { tLatch.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        val tc = tBox.get()
        val tm = tc?.second.orEmpty()
        tempAreas = tm
        tempWritable = writable(tc?.first)
        tempStep = tc?.third?.incrementCelsius?.takeIf { it > 0f }
        GLog.i("Vehicle", "온도 지원 ${tm.size}구역 ${tm.values.firstOrNull()?.let { "${it.minValue}~${it.maxValue}" } ?: ""} 단위 ${tempStep ?: "-"} (${tc?.first?.accessType})")
        val driver = driverFirst(tm.keys)
        val tb = driver?.let { tm[it] }
        post { state.update { it.copy(tempMin = tb?.minValue, tempMax = tb?.maxValue) } }
        driver?.let { a ->
            runCatching {
                h.getHvacTemperature(a, TemperatureUnit.CELSIUS, { t -> if (t != null) post { state.update { it.copy(tempC = t) } } }, { e -> fail("온도 읽기", e) })
            }.onFailure { fail("온도 읽기", it) }
        }
    }

    private fun driverFirst(areas: Collection<SeatArea>): SeatArea? = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()

    /**
     * 내기 순환(true) 또는 바깥 공기(false). 지원하지 않거나 쓰기 권한이 없는 차면 false.
     * 처음 바꾸기 직전 값을 기록해 두고 restoreAll() 때 되돌림
     */
    fun setRecirculation(on: Boolean): Boolean {
        val v = vehicle ?: return false
        val a = recircAreas.firstOrNull() ?: return false
        if (!recircWritable) return false
        bg {
            rememberRecirc(v, a)
            runCatching {
                v.getHVAC().setHvacRecirculation(a, on, { _, value -> if (value != null) post { state.update { it.copy(recirc = value) } } }, { e -> fail("내기 순환 바꾸기", e) })
            }.onFailure { fail("내기 순환 바꾸기", it) }
        }
        GLog.i("Vehicle", if (on) "내기 순환으로 돌림" else "바깥 공기 들이기")
        return true
    }

    fun setFanSpeed(level: Int): Boolean {
        val v = vehicle ?: return false
        if (!fanWritable) return false
        val (a, b) = fanAreas.entries.firstOrNull() ?: return false
        val lv = level.coerceIn(b.minValue, b.maxValue)
        bg {
            runCatching {
                v.getHVAC().setHvacFanSpeed(a, lv, { _, value -> if (value != null) post { state.update { it.copy(fanSpeed = value) } } }, { e -> fail("바람 세기 바꾸기", e) })
            }.onFailure { fail("바람 세기 바꾸기", it) }
        }
        return true
    }

    /** 모든 공조 구역 온도를 운전석 기준으로 delta 만큼 바꿈. 바뀐 온도를 돌려줌 (지원하지 않거나 쓰기 권한이 없으면 null) */
    fun nudgeTemperature(delta: Float): Float? {
        val v = vehicle ?: return null
        if (tempAreas.isEmpty() || !tempWritable) return null
        val driver = driverFirst(tempAreas.keys) ?: return null
        val cur = state.value.tempC ?: 22f
        val bounds = tempAreas[driver]
        // 차가 받는 단위(예: 0.5도)에 맞춤
        val step = tempStep
        val raw = if (step != null) Math.round((cur + delta) / step) * step else cur + delta
        val target = if (bounds != null) raw.coerceIn(bounds.minValue, bounds.maxValue) else raw
        val areas = tempAreas
        bg {
            areas.forEach { (a, b) ->
                runCatching {
                    v.getHVAC().setHvacTemperature(a, target.coerceIn(b.minValue, b.maxValue), TemperatureUnit.CELSIUS, { _, _ -> }, { e -> fail("온도 바꾸기", e) })
                }.onFailure { fail("온도 바꾸기", it) }
            }
        }
        post { state.update { it.copy(tempC = target) } }
        GLog.i("Vehicle", "설정 온도 $cur → $target")
        return target
    }

    // ------------------------------------------------------------ 글로브 박스, 선루프, 창문

    private fun registerBody(v: Vehicle) {
        val door = v.getDoor()
        val gc = await<CapabilityConfig> { ok, err -> door.checkGloveBoxLockCapabilities(ok, err) }
        gloveWritable = gc != null && writable(gc.value)
        if (gc != null) {
            GLog.i("Vehicle", "글로브 박스 잠금 지원 (${gc.value?.accessType})")
            val gloveL = object : GloveBoxLockStateListener {
                override fun onGloveBoxLockStateUpdated(value: Boolean?) {
                    if (value != null) post { state.update { it.copy(gloveLocked = value) } }
                }

                override fun onFailed(e: Exception) = fail("글로브 박스 구독", e)
            }
            reg("글로브 박스 구독", { door.registerGloveBoxLockState(gloveL) }, { door.unregisterGloveBoxLockState(gloveL) })
            runCatching {
                door.getCurrentGloveBoxLockState({ b -> if (b != null) post { state.update { it.copy(gloveLocked = b) } } }, { e -> fail("글로브 박스 읽기", e) })
            }.onFailure { fail("글로브 박스 읽기", it) }
        } else {
            GLog.i("Vehicle", "글로브 박스 잠금 신호를 주지 않는 차")
        }
        post { state.update { it.copy(gloveSupported = gloveWritable) } }

        val window = v.getWindow()
        val wc = await2<CapabilityConfig?, Map<WindowArea, AreaBounds<Int>>?>("창문 위치") { ok, err -> window.checkWindowPositionCapabilities(ok, err) }
        val all = wc?.second.orEmpty()
        val canWrite = writable(wc?.first)
        val roof = all.entries.firstOrNull { (a, _) -> WindowValue.ROOF_TOP_1 in a.values || WindowValue.ROOF_TOP_2 in a.values }
        windowMap = if (canWrite) all.filterKeys { it != roof?.key } else emptyMap()
        roofArea = if (canWrite) roof?.key else null
        roofBounds = roof?.value
        GLog.i(
            "Vehicle",
            "위치를 움직일 수 있는 창 (${wc?.first?.accessType}): " +
                all.entries.joinToString { (a, b) -> a.values.joinToString("+") { it.name } + " ${b.minValue}~${b.maxValue}" } +
                if (roof != null) " (선루프 있음)" else " (선루프 없음)",
        )
        post { state.update { it.copy(sunroof = roofArea != null) } }
        roof?.key?.let { a ->
            val roofL = object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) {
                    if (value != null) post { state.update { it.copy(sunroofPos = value) } }
                }

                override fun onFailed(e: Exception) = fail("선루프 구독", e)
            }
            reg("선루프 구독", { window.registerWindowPosition(a, roofL) }, { window.unregisterWindowPosition(a, roofL) })
        }
    }

    private fun refuse(what: String, why: String) {
        GLog.w("Vehicle", "$what 안 함: $why")
        post { state.update { it.copy(lastError = "$what 안 함: $why") } }
    }

    /** 움직이기 직전에 기어를 다시 물어봄. P 로 답이 와야 true (전용 스레드에서만) */
    private fun confirmParked(v: Vehicle): Boolean {
        val got = await<VehicleGear> { ok, err -> v.getGear().getCurrentGearSelectionState(ok, err) } ?: return false
        val g = gearName(got.value)
        post { state.update { it.copy(connected = true, gear = g) } }
        return VehicleMath.isParked(g)
    }

    /**
     * 창문, 선루프, 글로브 박스 잠금 풀기처럼 끼이거나 열릴 수 있는 동작.
     * 마지막으로 받은 기어가 P 가 아니면 바로 false. P 이면 전용 스레드에서 움직이기 직전에 한 번 더 물어보고,
     * P 로 확인되지 않으면 움직이지 않고 로그와 lastError 를 남김.
     * 돌려주는 true 는 '요청을 받았다'는 뜻. 실제로 했는지는 onResult(메인 스레드)로 옴
     */
    private fun guarded(what: String, onResult: ((Boolean) -> Unit)?, action: (Vehicle) -> Boolean): Boolean {
        val v = vehicle
        val why = when {
            v == null -> "차량 서비스 연결 전"
            !state.value.parked -> "기어가 P 로 확인되지 않음 (지금 ${state.value.gear})"
            else -> null
        }
        if (v == null || why != null) {
            refuse(what, why ?: "차량 서비스 연결 전")
            onResult?.let { cb -> post { cb(false) } }
            return false
        }
        bg {
            val ok = runCatching {
                if (confirmParked(v)) {
                    action(v)
                } else {
                    refuse(what, "움직이기 직전 P 확인 실패")
                    false
                }
            }.getOrElse {
                fail(what, it)
                false
            }
            onResult?.let { cb -> post { cb(ok) } }
        }
        return true
    }

    /**
     * 글로브 박스 잠금. 지원하지 않거나 쓰기 권한이 없는 차면 false.
     * 잠금 풀기는 P 를 다시 확인한 뒤에만 함. 잠그기는 열리는 쪽이 아니라서 달리는 중에도 함 (보물 상자를 연 뒤 다시 잠글 때).
     * 처음 바꾸기 직전 잠금 상태를 기록해 두고 restoreAll() 때 되돌림. true 는 요청을 받았다는 뜻, 결과는 onResult
     */
    fun setGloveBoxLock(locked: Boolean, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val what = if (locked) "글로브 박스 잠금" else "글로브 박스 잠금 풀기"
        if (!gloveWritable) {
            onResult?.let { cb -> post { cb(false) } }
            return false
        }
        val act: (Vehicle) -> Boolean = { v ->
            rememberGlove(v)
            setWait(what) { done, err ->
                v.getDoor().setGloveBoxLock(locked, { value ->
                    if (value != null) post { state.update { it.copy(gloveLocked = value) } }
                    done()
                }, err)
            }
        }
        GLog.i("Vehicle", what)
        if (!locked) return guarded(what, onResult, act)
        val v = vehicle
        if (v == null) {
            onResult?.let { cb -> post { cb(false) } }
            return false
        }
        bg {
            val ok = runCatching { act(v) }.getOrElse {
                fail(what, it)
                false
            }
            onResult?.let { cb -> post { cb(ok) } }
        }
        return true
    }

    /**
     * 선루프 위치(0 닫힘, 100 끝까지). 지원하는 차에서만, P 를 다시 확인한 뒤에만 움직임.
     * 값은 차가 알려 준 범위로 환산함 (틸트가 있어 최소값이 음수인 차에서도 0 은 닫힘).
     * 처음 바꾸기 직전 위치를 기록해 두고 restoreAll() 때 되돌림. true 는 요청을 받았다는 뜻, 결과는 onResult
     */
    fun setSunroof(percent: Int, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val a = roofArea
        val b = roofBounds
        if (a == null || b == null) {
            onResult?.let { cb -> post { cb(false) } }
            return false
        }
        val pos = VehicleMath.sunroofValue(percent, b.minValue, b.maxValue)
        GLog.i("Vehicle", "선루프 ${percent}% → 위치 $pos (${b.minValue}~${b.maxValue})")
        return guarded("선루프", onResult) { v ->
            rememberRoof(v, a)
            setWait("선루프") { done, err ->
                v.getWindow().setWindowPosition(a, pos, { _, value ->
                    if (value != null) post { state.update { it.copy(sunroofPos = value) } }
                    done()
                }, err)
            }
        }
    }

    /**
     * 실내 조명 색(RGB)과 밝기(0~100)를 바꿈. 차량 서비스가 준비되기 전이면 준비되는 대로 적용.
     * 처음 바꾸기 직전 스위치, 색, 밝기를 구역별로 기록해 두고 restoreAll() 때 되돌림
     */
    fun setAmbient(color: Int, brightness: Int) {
        bg {
            val v = vehicle
            if (v == null || !ready) pendingAmbient = color to brightness else applyAmbient(v, color, brightness)
        }
        GLog.i("Vehicle", "실내 조명 색 ${"%06X".format(color and 0xFFFFFF)} 밝기 $brightness")
    }

    /** 전용 스레드에서만 */
    private fun applyAmbient(v: Vehicle, color: Int, brightness: Int) {
        val areas = ambientAreas
        if (areas.isEmpty() || !ambientWritable) {
            GLog.i("Vehicle", "실내 조명을 바꿀 수 없는 차 (구역 ${areas.size}, 쓰기 $ambientWritable)")
            return
        }
        val l = v.getLight()
        areas.forEach { area ->
            rememberLight(v, area)
            if (canSwitch(area)) {
                runCatching {
                    l.setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> }, { e -> fail("조명 켜기", e) })
                }.onFailure { fail("조명 켜기", it) }
            }
            runCatching {
                l.setAmbientLightBrightnessColor(area, color, { _, value ->
                    post { state.update { it.copy(ambientColor = value) } }
                }, { e -> fail("조명 색 바꾸기", e) })
            }.onFailure { fail("조명 색 바꾸기", it) }
            // 밝기는 0~100 으로 받아 차의 범위로 환산
            val bb = brightnessBounds[area]
            val pct = brightness.coerceIn(0, 100)
            val lv = if (bb != null && bb.maxValue > bb.minValue) bb.minValue + ((bb.maxValue - bb.minValue) * pct + 50) / 100 else pct
            runCatching {
                l.setAmbientLightBrightness(area, lv, { _, value ->
                    post { state.update { it.copy(ambientBrightness = value) } }
                }, { e -> fail("조명 밝기 바꾸기", e) })
            }.onFailure { fail("조명 밝기 바꾸기", it) }
        }
    }

    /** 스위치 지원을 확인했으면 켤 수 있는 구역만. 확인하지 못했으면 예전처럼 켜 봄 */
    private fun canSwitch(area: SeatArea): Boolean =
        if (switchKnown) switchAreas[area]?.contains(LightSwitch.ON) == true else true

    /**
     * 창문 위치를 0~10 눈금으로 바꿈 (0 닫힘, 10 끝까지). 차가 알려 준 창문별 범위로 환산함 (범위 0..100 이면 2 는 20).
     * P 를 다시 확인한 뒤에만 움직임. 사용자 확인은 부르는 쪽에서 먼저 받음.
     * 창문은 끼임 위험 때문에 앱이 저절로 올리지 않으므로 되돌리기 기록을 하지 않음.
     * true 는 요청을 받았다는 뜻, 결과는 onResult
     */
    fun setWindows(position: Int, windows: List<WindowValue> = SIDE_WINDOWS, onResult: ((Boolean) -> Unit)? = null): Boolean {
        val targets = windowTargets(windows)
        if (targets.isEmpty()) {
            GLog.i("Vehicle", "움직일 수 있는 창문이 없음 (${windows.joinToString { it.name }})")
            onResult?.let { cb -> post { cb(false) } }
            return false
        }
        val step = position.coerceIn(0, VehicleMath.WINDOW_STEPS)
        GLog.i(
            "Vehicle",
            "창문 $step/${VehicleMath.WINDOW_STEPS}: " +
                targets.joinToString { (a, b) -> a.values.joinToString("+") { it.name } + "→" + VehicleMath.windowValue(step, b.minValue, b.maxValue) },
        )
        return guarded("창문", onResult) { v ->
            var all = true
            targets.forEach { (a, b) ->
                val value = VehicleMath.windowValue(step, b.minValue, b.maxValue)
                val names = a.values.map { it.name }
                val ok = setWait("창문 움직이기 ${names.joinToString("+")}") { done, err ->
                    v.getWindow().setWindowPosition(a, value, { _, got ->
                        if (got != null) post { state.update { s -> s.copy(windows = s.windows + names.associateWith { got }) } }
                        done()
                    }, err)
                }
                if (!ok) all = false
            }
            all
        }
    }

    /** 부탁받은 창마다 움직일 영역. 그 창만 있는 영역을 먼저 쓰고, 없으면 부탁받은 창으로만 이뤄진 묶음 영역을 씀 */
    private fun windowTargets(windows: List<WindowValue>): List<Pair<WindowArea, AreaBounds<Int>>> {
        val m = windowMap
        val out = LinkedHashMap<WindowArea, AreaBounds<Int>>()
        windows.forEach { w ->
            val hit = m.entries.firstOrNull { (a, _) -> a.values.toList() == listOf(w) }
                ?: m.entries.firstOrNull { (a, _) -> w in a.values && a.values.all { it in windows } }
            if (hit != null) out[hit.key] = hit.value else GLog.i("Vehicle", "${w.name} 창문은 이 차에서 움직일 수 없음")
        }
        return out.toList()
    }

    // ------------------------------------------------------------ 앱이 바꾼 차 상태 되돌리기

    private fun seatKey(a: SeatArea) = a.values.joinToString("+") { it.name }

    private fun tri(b: Boolean?) = when (b) {
        true -> 1
        false -> 0
        null -> -1
    }

    private fun untri(i: Int): Boolean? = when (i) {
        1 -> true
        0 -> false
        else -> null
    }

    /** 조명을 처음 바꾸기 직전 스위치, 색, 밝기를 기록 (이미 기록이 있으면 그대로 둠). 전용 스레드에서만 */
    private fun rememberLight(v: Vehicle, a: SeatArea) {
        val k = "light.${seatKey(a)}"
        if (restore.contains(k)) return
        val l = v.getLight()
        val e = restore.edit().putBoolean(k, true)
        if (canSwitch(a)) await<LightSwitch> { ok, err -> l.getAmbientLightSwitch(a, ok, err) }?.value?.let { e.putString("$k.sw", it.name) }
        await<Int> { ok, err -> l.getAmbientLightBrightnessColor(a, ok, err) }?.value?.let { e.putInt("$k.color", it) }
        await<Int> { ok, err -> l.getAmbientLightBrightness(a, ok, err) }?.value?.let { e.putInt("$k.bright", it) }
        e.commit()
        GLog.i("Vehicle", "조명 원래 값 기록 ${seatKey(a)}")
    }

    private fun rememberRecirc(v: Vehicle, a: SeatArea) {
        val k = "recirc.${seatKey(a)}"
        if (restore.contains(k)) return
        val got = await<Boolean> { ok, err -> v.getHVAC().getHvacRecirculationState(a, ok, err) }?.value
        restore.edit().putInt(k, tri(got)).commit()
    }

    private fun rememberGlove(v: Vehicle) {
        if (restore.contains(KEY_GLOVE)) return
        val got = await<Boolean> { ok, err -> v.getDoor().getCurrentGloveBoxLockState(ok, err) }?.value
        restore.edit().putInt(KEY_GLOVE, tri(got)).commit()
    }

    private fun rememberRoof(v: Vehicle, a: WindowArea) {
        if (restore.contains(KEY_ROOF)) return
        val got = await<Int> { ok, err -> v.getWindow().getCurrentWindowPosition(a, ok, err) }?.value
        restore.edit().putInt(KEY_ROOF, got ?: UNKNOWN_POS).commit()
    }

    /** 되돌릴 기록이 남아 있는지 */
    val hasChanges: Boolean get() = restore.all.isNotEmpty()

    /**
     * 앱이 바꾼 실내 조명(스위치 포함), 내기 순환, 글로브 박스, 선루프를 바꾸기 전 값으로 되돌림.
     * 창문은 끼임 위험 때문에 되돌리지 않음 (닫기는 사람이 확인하고 직접).
     * 되돌린 뒤 다시 읽어 맞으면 그 기록을 지움. 맞지 않거나 지금 할 수 없는 것(P 가 아닐 때 선루프, 잠금 풀기)은
     * 기록을 남겨 두었다가 다음 restoreAll() 때 다시 함. 원래 잠금 상태를 모르면 잠금, 원래 내기 순환을 모르면 바깥 공기로 둠.
     * 차량 서비스에 붙기 전에 부르면 붙은 뒤에 함. onDone 은 메인 스레드에서, 남은 기록이 없으면 true
     */
    fun restoreAll(onDone: ((Boolean) -> Unit)? = null) {
        if (!hasChanges) {
            onDone?.let { cb -> post { cb(true) } }
            return
        }
        post {
            waitingRestore.add(onDone)
            if (ready) flushRestore() else GLog.i("Vehicle", "차량 서비스에 붙은 뒤 되돌림")
        }
    }

    /** 메인 스레드에서만 */
    private fun flushRestore() {
        if (waitingRestore.isEmpty()) return
        val v = vehicle ?: return
        val cbs = waitingRestore.toList()
        waitingRestore.clear()
        bg {
            val ok = runCatching { restoreNow(v) }.getOrElse {
                fail("되돌리기", it)
                false
            }
            post { cbs.forEach { it?.invoke(ok) } }
        }
    }

    /** 되돌린 값을 다시 읽어 맞는지 봄. 차가 값을 늦게 반영할 수 있어 쉬어 가며 여러 번 */
    private fun <T> check(read: (ok: (T?) -> Unit, err: (Exception) -> Unit) -> Unit, tries: Int = 3, gapMs: Long = 300, good: (T?) -> Boolean): Boolean {
        repeat(tries) { i ->
            if (i > 0) runCatching { Thread.sleep(gapMs) }
            val got = await<T>(call = read)
            if (got != null && good(got.value)) return true
        }
        return false
    }

    /** 전용 스레드에서만. 남은 기록이 없으면 true */
    private fun restoreNow(v: Vehicle): Boolean {
        if (restore.all.isEmpty()) return true
        GLog.i("Vehicle", "앱이 바꾼 차 상태 되돌리기 시작")
        ambientAreas.forEach { a ->
            val k = "light.${seatKey(a)}"
            if (restore.contains(k) && restoreLight(v, a, k)) restore.edit().remove(k).remove("$k.sw").remove("$k.color").remove("$k.bright").commit()
        }
        val h = v.getHVAC()
        recircAreas.forEach { a ->
            val k = "recirc.${seatKey(a)}"
            if (!restore.contains(k) || !recircWritable) return@forEach
            // 원래 값을 모르면 바깥 공기 (오래 내기 순환이면 졸릴 수 있어서)
            val target = untri(restore.getInt(k, -1)) ?: false
            val ok = setWait("내기 순환 되돌리기") { done, err -> h.setHvacRecirculation(a, target, { _, _ -> done() }, err) } &&
                check<Boolean>({ o, err -> h.getHvacRecirculationState(a, o, err) }) { it == target }
            if (ok) restore.edit().remove(k).commit()
        }
        if (restore.contains(KEY_GLOVE) && gloveWritable) {
            val target = VehicleMath.gloveRestoreTarget(untri(restore.getInt(KEY_GLOVE, -1)))
            val d = v.getDoor()
            // 잠그는 것은 언제든, 잠금 풀기로 되돌리는 것은 P 로 확인될 때만
            if (!target && !confirmParked(v)) {
                GLog.i("Vehicle", "글로브 박스 잠금 풀기는 차가 P 일 때 되돌림")
            } else if (
                setWait("글로브 박스 되돌리기") { done, err -> d.setGloveBoxLock(target, { _ -> done() }, err) } &&
                check<Boolean>({ o, err -> d.getCurrentGloveBoxLockState(o, err) }) { it == target }
            ) {
                restore.edit().remove(KEY_GLOVE).commit()
            }
        }
        val roof = roofArea
        val rb = roofBounds
        if (restore.contains(KEY_ROOF) && roof != null && rb != null) {
            val saved = restore.getInt(KEY_ROOF, UNKNOWN_POS)
            val target = if (saved == UNKNOWN_POS) VehicleMath.closedValue(rb.minValue, rb.maxValue) else saved.coerceIn(rb.minValue, rb.maxValue)
            val w = v.getWindow()
            if (!confirmParked(v)) {
                GLog.i("Vehicle", "선루프는 차가 P 일 때 되돌림")
            } else if (
                setWait("선루프 되돌리기") { done, err -> w.setWindowPosition(roof, target, { _, _ -> done() }, err) } &&
                // 선루프는 움직이는 데 몇 초 걸림
                check<Int>({ o, err -> w.getCurrentWindowPosition(roof, o, err) }, tries = 12, gapMs = 1000) { it == target }
            ) {
                restore.edit().remove(KEY_ROOF).commit()
            }
        }
        val left = restore.all.keys.filter { !it.endsWith(".sw") && !it.endsWith(".color") && !it.endsWith(".bright") }
        if (left.isEmpty()) GLog.i("Vehicle", "앱이 바꾼 차 상태를 모두 되돌림") else GLog.w("Vehicle", "아직 못 되돌린 것: ${left.joinToString()}")
        return left.isEmpty()
    }

    /** 조명 한 구역을 되돌리고 다시 읽어 확인. 색, 밝기, 스위치 순서 (원래 꺼져 있었으면 마지막에 끔) */
    private fun restoreLight(v: Vehicle, a: SeatArea, k: String): Boolean {
        if (!ambientWritable) return false
        val l = v.getLight()
        var ok = true
        if (restore.contains("$k.color")) {
            val c = restore.getInt("$k.color", 0)
            val done = setWait("조명 색 되돌리기") { d, err -> l.setAmbientLightBrightnessColor(a, c, { _, _ -> d() }, err) } &&
                check<Int>({ o, err -> l.getAmbientLightBrightnessColor(a, o, err) }) { it != null && VehicleMath.sameColor(it, c) }
            ok = ok && done
        }
        if (restore.contains("$k.bright")) {
            val b = restore.getInt("$k.bright", 0)
            val done = setWait("조명 밝기 되돌리기") { d, err -> l.setAmbientLightBrightness(a, b, { _, _ -> d() }, err) } &&
                check<Int>({ o, err -> l.getAmbientLightBrightness(a, o, err) }) { it != null && kotlin.math.abs(it - b) <= 2 }
            ok = ok && done
        }
        val sw = restore.getString("$k.sw", null)?.let { n -> runCatching { LightSwitch.valueOf(n) }.getOrNull() }
        if (sw != null) {
            val done = setWait("조명 스위치 되돌리기") { d, err -> l.setAmbientLightSwitch(a, sw, { _, _ -> d() }, err) } &&
                check<LightSwitch>({ o, err -> l.getAmbientLightSwitch(a, o, err) }) { it == sw }
            ok = ok && done
        }
        return ok
    }

    fun release() {
        started = false
        ready = false
        bg {
            unregisterAll()
            runCatching { vehicle?.release() }
            vehicle = null
        }
    }
}
