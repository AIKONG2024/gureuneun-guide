package app.gureuneun.guide.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
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
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
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
 * Vehicle SDK 연결부. 여행 안내에 쓰는 신호만 다룸.
 * 읽기: 기어, 방향지시등, 와이퍼, 전조등(하향등), 좌석 착석, 실내 조명
 * 쓰기: 실내 조명 색과 밝기
 * 차마다 지원하는 기능이 달라서 쓰기 전에 지원 여부(capability)와 쓰기 권한(AccessType WRITE, READ_WRITE)을 먼저 확인하고,
 * 없으면 조용히 건너뜀.
 * 앱이 바꾼 조명은 바꾸기 직전 값을 기록해 두고 restoreAll() 로 되돌림.
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
        val occupied: Map<String, Boolean> = emptyMap(),
        val ambientColor: Int? = null,
        val ambientBrightness: Int? = null,
        val ambientAreas: Int = 0,
        val lastError: String? = null,
    ) {
        /** 기어가 P 로 확인됐을 때만 정차. 기어 정보를 못 받으면(차량 서비스 없음, "-") 정차로 보지 않음 */
        val parked: Boolean get() = VehicleMath.isParked(gear)
    }

    companion object {
        val SEATS = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
        val SEAT_KEYS = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")
        /** SDK 답을 기다리는 시간 */
        private const val WAIT_MS = 1500L
        private const val MAX_RETRY_MS = 30_000L
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
        post { state.update { it.copy(lastError = "$where: ${reason(e)}") } }
    }

    /** 점검 화면에 보일 짧은 까닭. 예외 전문은 로그에만 남김 */
    private fun reason(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "not in carPropertyConfig" in m || "not supported" in m.lowercase() -> "이 차가 지원하지 않는 항목"
            e is SecurityException -> "권한이 없음"
            m.isBlank() -> e.javaClass.simpleName
            else -> m.take(80)
        }
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
                // 기어는 차가 서 있는지 판단하는 데 쓰므로, 끊기면 처음부터 다시 붙음
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
            val occL = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) {
                    post { state.update { it.copy(connected = true, occupied = it.occupied + (key to (value == true))) } }
                }

                override fun onFailed(e: Exception) = fail("착석 구독 $key", e)
            }
            reg("착석 구독 $key", { seatApi.registerSeatOccupancy(area, occL) }, { seatApi.unregisterSeatOccupancy(area, occL) })
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
        ambientAreas.firstOrNull()?.let { area ->
            runCatching {
                v.getLight().getAmbientLightBrightnessColor(area, { c -> post { state.update { it.copy(ambientColor = c) } } }, { e -> fail("조명 색 읽기", e) })
                v.getLight().getAmbientLightBrightness(area, { b -> post { state.update { it.copy(ambientBrightness = b) } } }, { e -> fail("조명 밝기 읽기", e) })
            }
        }
    }

    // ------------------------------------------------------------ 실내 조명

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

    // ------------------------------------------------------------ 앱이 바꾼 차 상태 되돌리기

    private fun seatKey(a: SeatArea) = a.values.joinToString("+") { it.name }

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

    /** 되돌릴 기록이 남아 있는지 */
    val hasChanges: Boolean get() = restore.all.isNotEmpty()

    /**
     * 앱이 바꾼 실내 조명(스위치 포함)을 바꾸기 전 값으로 되돌림.
     * 되돌린 뒤 다시 읽어 맞으면 그 기록을 지움. 맞지 않으면 기록을 남겨 두었다가 다음 restoreAll() 때 다시 함.
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
        // 이 앱이 더는 바꾸지 않는 항목의 옛 기록(앞 버전의 공조, 선루프, 글로브 박스)은 되돌릴 수 없으므로 지움
        val stale = restore.all.keys.filter { !it.startsWith("light.") }
        if (stale.isNotEmpty()) restore.edit().apply { stale.forEach { remove(it) } }.commit()
        if (restore.all.isEmpty()) return true
        GLog.i("Vehicle", "앱이 바꾼 차 상태 되돌리기 시작")
        ambientAreas.forEach { a ->
            val k = "light.${seatKey(a)}"
            if (restore.contains(k) && restoreLight(v, a, k)) restore.edit().remove(k).remove("$k.sw").remove("$k.color").remove("$k.bright").commit()
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
