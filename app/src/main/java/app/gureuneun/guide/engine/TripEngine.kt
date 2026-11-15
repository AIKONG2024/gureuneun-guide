package app.gureuneun.guide.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.gureuneun.guide.BuildConfig
import app.gureuneun.guide.MainActivity
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Record
import app.gureuneun.guide.core.Region
import app.gureuneun.guide.core.Sight
import app.gureuneun.guide.core.Trip
import app.gureuneun.guide.core.TripPlace
import app.gureuneun.guide.net.Sync
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.sdk.ListenBridge
import app.gureuneun.guide.sdk.LocationBridge
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.Speaker
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class Phase { PREP, DRIVE, ARRIVED, RECORD }

sealed interface Card {
    /** 여행지 접근: 조명을 낮추고 짧은 전주 */
    data class Approach(val distM: Int, val at: Long) : Card

    /** 1 요금소 통과(10초 연출), 2 안내 시작 문장 */
    data class Entry(val step: Int, val at: Long) : Card

    data class SightCard(
        val sight: Sight,
        val line: String,
        val body: String,
        val side: Int,
        val distM: Int,
        val short: Boolean,
        val budgetM: Int,
        val asked: String?,
        val at: Long,
        val estMs: Long,
        val paused: Boolean = false,
        val done: Boolean = false,
        val pausedAt: Long = 0L,
        /** 다음 길 안내 ("우회전", "좌회전", "도착") */
        val turn: String = "",
    ) : Card

    data class Offer(val place: TripPlace, val minutes: Int, val line: String, val at: Long, val reply: String? = null, val accepted: Boolean? = null) : Card

    data class Listen(val at: Long, val partial: String = "") : Card

    data class Arrive(val place: TripPlace?, val at: Long) : Card
}

data class Pose(val pos: LatLng, val heading: Double, val speedKmh: Double, val s: Double)

data class NavHint(val turn: String, val distM: Int, val toward: String, val dest: String, val eta: String, val remainKm: Double)

data class Upcoming(val sight: Sight, val aheadM: Int, val side: Int)

data class Ui(
    val phase: Phase = Phase.PREP,
    val trip: Trip,
    val pose: Pose? = null,
    val card: Card? = null,
    val mode: Boolean = false,
    val ambientColor: Int = TripEngine.BLUE,
    val ambientLevel: Float = 0.42f,
    val toast: String? = null,
    val toastAt: Long = 0L,
    val nav: NavHint? = null,
    val upcoming: List<Upcoming> = emptyList(),
    val heard: List<String> = emptyList(),
    val visited: List<String> = emptyList(),
    val qrUrl: String = "",
    val online: Boolean = false,
    val showQr: Boolean = false,
    val showDemo: Boolean = false,
    val turnSignal: String = "OFF",
    val signalDemo: Boolean = false,
    val timeScale: Double = 5.0,
    val dest: String = "",
    val waypoints: List<String> = emptyList(),
    val navLinked: Boolean = false,
    val vehicleLinked: Boolean = false,
    val lightAreas: Int = 0,
    val enteredAt: String? = null,
    val newPlaceId: String? = null,
    val record: Record? = null,
    val speaking: Boolean = false,
    val clock: Long = 0L,
    val gear: String = "-",
    /** 여행 링크를 열어 둔 휴대폰 수 (동반 서버 기준) */
    val phones: Int = 0,
    /** 여행 영상 다시 보기 횟수 (바뀌면 영상을 처음부터 다시 틂) */
    val reelSeq: Int = 0,
)

/**
 * 여행 한 번의 흐름을 맡음: 담아 둔 장소를 일정으로 연결 → 여행지 진입 시 여행 모드 → 지나는 명소 안내 → 여행 영상과 티켓.
 * 판단(무엇을 언제 안내할지)은 차량 데이터(경로, 위치, 진행 방향, 다음 길 안내까지 거리, 방향지시등)로 하고
 * 생성형 AI 는 명소 설명 문장을 다듬는 데만 씀(시제품은 미리 다듬어 둔 문장을 씀).
 * 공개 함수는 메인 스레드에서 부름.
 */
class TripEngine(private val ctx: Context) {
    companion object {
        const val BLUE = 0xFF00AAD2.toInt()
        const val FINAL = "donggung"
        const val FIRST = "daereungwon"
        /** 시나리오 시각 (기획서 4장: 08:00 출발, 11:24 경주 요금소) */
        const val SCENE_START = "08:00"
        const val SCENE_ENTRY = "11:24"
        private val CLOCK = SimpleDateFormat("HH:mm", Locale.KOREA)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val region: Region = Region.load(ctx)
    private val seed: Trip = Trip.load(ctx)
    private var trip: Trip = seed
    val path = DrivePath(region.approach.dropLast(1) + region.route)
    val gateS: Double = path.project(region.gate).first
    private val turns: List<Pair<String, Double>> = region.maneuvers.map { it.modifier to path.project(it.pos).first }.sortedBy { it.second }
    private val drive = DemoDrive(path, gateS, turns.map { it.second })

    private data class SightAt(val sight: Sight, val s: Double, val off: Double, val side: Int)

    private val sightAts = region.sights.map { val (s, off, side) = path.project(it.pos); SightAt(it, s, off, side) }.sortedBy { it.s }

    val vehicle = VehicleBridge(ctx, scope)
    val navi = NaviBridge(ctx, scope)
    val tts = SpeechBridge(ctx)
    val stt = ListenBridge(ctx)
    val ux = UxBridge(ctx)
    val location = LocationBridge(ctx)
    private val sound = Sound(ctx, scope)
    private val sync = Sync(this)

    val ui = MutableStateFlow(Ui(trip = trip, qrUrl = BuildConfig.COMPANION_URL + "/t/" + seed.code))

    private var started = false
    private var approached = false
    private var entered = false
    private val offered = mutableSetOf<String>()
    private val heard = linkedSetOf<String>()
    private val visited = linkedSetOf<String>()
    private val photos = mutableListOf<String>()
    private var lastSpeechEnd = 0L
    private var currentDest: TripPlace? = null
    private var waypoint: TripPlace? = null
    private var demoJobs = mutableListOf<Job>()
    private var lastSignal = "OFF"
    private var lanUrl: String? = null
    private var userScale = 5.0
    private var narrSeq = 0

    val tripCode: String get() = trip.code

    fun start() {
        if (started) return
        started = true
        vehicle.start()
        navi.start()
        tts.start()
        stt.start()
        ux.start()
        sync.start()
        scope.launch { vehicle.state.collect { v -> onVehicle(v) } }
        scope.launch { navi.state.collect { n -> update { it.copy(navLinked = n.connected) } } }
        scope.launch { tts.speaking.collect { sp -> update { it.copy(speaking = sp) } } }
        scope.launch { stt.partial.collect { p -> (ui.value.card as? Card.Listen)?.let { c -> setCard(c.copy(partial = p)) } } }
        scope.launch { loop() }
        GLog.i("Trip", "경로 ${path.total.roundToInt()}m, 요금소 ${gateS.roundToInt()}m 지점, 명소 ${sightAts.size}곳")
    }

    fun onLocationPermission() = location.start()

    // ------------------------------------------------------------------ 상태 갱신

    private inline fun update(f: (Ui) -> Ui) = ui.update(f)

    private fun setCard(c: Card?) = update { it.copy(card = c) }

    fun toast(msg: String) {
        GLog.i("DEMO_EVT", "toast:$msg")
        update { it.copy(toast = msg, toastAt = SystemClock.elapsedRealtime()) }
    }

    private fun setTrip(t: Trip) {
        trip = t
        update { it.copy(trip = t) }
        sync.dirty()
    }

    private fun setPlace(id: String, f: (TripPlace) -> TripPlace) {
        setTrip(trip.copy(places = trip.places.map { if (it.id == id) f(it) else it }))
    }

    private fun speak(text: String, onEnd: (Speaker.End) -> Unit = {}) {
        GLog.i("DEMO_EVT", "speak:$text")
        tts.utter(text, "ko", onEnd)
    }

    private fun now() = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------------ 내비 화면과 오가기

    /** 우리 화면이 보이는지 (내비게이션에 길을 넣으면 내비 화면이 잠시 앞으로 나옴) */
    private val front = MutableStateFlow(true)

    fun setFront(v: Boolean) {
        front.value = v
    }

    private suspend fun awaitFront() {
        front.first { it }
    }

    /** 차량 내비게이션에 목적지와 경유지를 넣음. 내비가 경로 안내를 시작하면 우리 화면으로 돌아옴 */
    private fun route(name: String, pos: LatLng, waypoints: List<Pair<String, LatLng>>) {
        if (!naviEnabled) {
            // 녹화용: 순정 내비게이션을 띄우지 않고 앱 화면만 진행 (내비 연동은 따로 확인)
            GLog.i("DEMO_EVT", "navi:$name:${waypoints.joinToString { it.first }}")
            return
        }
        val wasFront = front.value
        if (!wasFront || !ui.value.navLinked) {
            navi.routeTo(name, pos, waypoints)
            GLog.i("DEMO_EVT", "navi:$name:${waypoints.joinToString { it.first }}")
            return
        }
        // 경로 안내 시작 신호를 먼저 기다리기 시작한 뒤 목적지를 넣음 (신호를 놓치지 않게)
        val started = scope.async { withTimeoutOrNull(15_000) { navi.events.first { it is NaviBridge.Event.RouteStarted } } }
        navi.routeTo(name, pos, waypoints)
        GLog.i("DEMO_EVT", "navi:$name:${waypoints.joinToString { it.first }}")
        scope.launch {
            withTimeoutOrNull(5_000) { front.first { !it } }
            started.await()
            delay(300)
            if (!front.value) {
                GLog.i("DEMO_EVT", "front")
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 주행 고리

    private suspend fun loop() {
        var last = now()
        while (true) {
            delay(33)
            val t = now()
            val dt = ((t - last) / 1000.0).coerceIn(0.0, 0.2)
            last = t
            tick(dt, t)
        }
    }

    private fun scaleNow(c: Card?): Double = when (c) {
        is Card.SightCard, is Card.Offer, is Card.Entry, is Card.Listen -> 1.6
        is Card.Approach -> 3.0
        else -> userScale
    }

    private fun tick(dt: Double, t: Long) {
        val u = ui.value
        if (u.toast != null && t - u.toastAt > 4200) update { it.copy(toast = null) }
        if (u.phase == Phase.DRIVE) {
            // 시연 주행은 우리 화면이 보일 때만 진행 (내비 화면이 앞에 있는 동안 연출을 놓치지 않게)
            if (front.value) {
                drive.timeScale = scaleNow(u.card)
                drive.step(dt)
            }
            val pose = Pose(path.at(drive.s), path.heading(drive.s), drive.speedKmh, drive.s)
            events(pose, t)
            update { it.copy(pose = pose, nav = navHint(pose), upcoming = upcoming(pose), clock = t, timeScale = drive.timeScale) }
        } else if (u.card != null || u.toast != null) {
            update { it.copy(clock = t) }
        }
    }

    private fun cardIdle(): Boolean = ui.value.card == null

    private fun events(pose: Pose, t: Long) {
        if (!approached && pose.s >= gateS - 800) {
            approached = true
            onApproach(pose)
        }
        if (!entered && pose.s >= gateS) {
            entered = true
            onEnter()
            return
        }
        if (!entered) return
        if (pose.s >= path.total - 4) {
            arrive()
            return
        }
        // 일정에 넣은 곳과 경유지로 받은 곳 들르기
        for (p in trip.places) {
            if (p.id == FINAL || p.id in visited) continue
            if (p.status != "planned" && p.status != "next") continue
            val near = Geo.dist(pose.pos, p.pos) < 170
            val passed = p.status == "next" && path.project(p.pos).let { (ps, off, _) -> off < 450 && pose.s >= ps - 6 }
            if ((near || passed) && cardIdle()) {
                visit(p)
                return
            }
        }
        if (!cardIdle()) return
        // 담아 둔 곳 제안 (시간을 정하지 않은 곳만, 가까워지면. 그 앞에 들를 곳이 남아 있으면 거기부터)
        for (p in trip.places) {
            if (p.status != "open" || p.id in offered) continue
            val (ps, off, _) = path.project(p.pos)
            val ahead = ps - pose.s
            val stopBefore = trip.places.any { q ->
                q.id != p.id && (q.status == "planned" || q.status == "next") && q.id !in visited &&
                    path.project(q.pos).first.let { it > pose.s && it < ps }
            }
            if (off < 450 && ahead in 40.0..650.0 && !stopBefore) {
                offer(p, ahead, off)
                return
            }
        }
        // 지나는 명소 먼저 안내
        if (t - lastSpeechEnd < 2200) return
        for (sa in sightAts) {
            val id = sa.sight.id
            if (id in heard || id == "gyerim" || nearTripPlace(sa.sight)) continue
            val ahead = sa.s - pose.s
            if (ahead in -30.0..290.0 && sa.off <= sa.sight.radiusM + 80) {
                narrate(sa, pose, null)
                return
            }
        }
    }

    /** 일정에 넣은 곳(또는 그 안)에 있는 명소는 따로 안내하지 않음. 가족이 직접 들르는 곳이라서 */
    private fun nearTripPlace(s: Sight): Boolean =
        trip.places.any { p -> p.status != "open" && p.status != "skipped" && Geo.dist(p.pos, s.pos) < 220 }

    // ------------------------------------------------------------------ 출발

    fun startTrip() {
        if (ui.value.phase != Phase.PREP) return
        // 동반 서버 없이 실행해도 '담아 둔 곳 제안'을 볼 수 있게, 담아 둔 곳이 없으면 엄마가 담은 카페를 넣어 둠
        if (trip.places.none { it.status == "open" }) injectDemoPlace(silent = true)
        val first = trip.places.first { it.id == FIRST }
        val lunch = trip.places.firstOrNull { it.id == "lunch" }
        currentDest = first
        route(first.name, first.pos, listOfNotNull(lunch?.let { it.name to it.pos }))
        approached = false
        entered = false
        drive.reset(0.0)
        drive.running = true
        update { it.copy(phase = Phase.DRIVE, showQr = false, dest = first.name, waypoints = listOfNotNull(lunch?.name), card = null, newPlaceId = null) }
        speak("${Geo.ro(first.name)} 안내를 시작할게요. 점심 식당을 경유지로 넣었어요.")
        scope.launch {
            delay(700)
            awaitFront()
            delay(400)
            toast("시연 주행은 경주 요금소 1.5km 앞에서 시작해요")
        }
        sync.dirty()
    }

    // ------------------------------------------------------------------ 여행 모드

    private fun onApproach(pose: Pose) {
        val dist = (gateS - pose.s).roundToInt()
        vehicle.setAmbient(BLUE, 18)
        update { it.copy(card = Card.Approach(dist, now()), ambientLevel = 0.14f) }
        sound.play(region.theme.prelude, volume = 0.9f)
        GLog.i("DEMO_EVT", "approach")
    }

    private fun onEnter() {
        val theme = region.theme
        val clock = SCENE_ENTRY
        val at = now()
        update { it.copy(mode = true, card = Card.Entry(1, at), ambientColor = theme.color, ambientLevel = 1f, enteredAt = clock) }
        vehicle.setAmbient(theme.color, 100)
        sound.play(theme.music, volume = 1f, maxMs = 10_800, fadeMs = 2200)
        GLog.i("DEMO_EVT", "enter:${region.id}")
        sync.dirty()
        scope.launch {
            delay(10_000)
            vehicle.setAmbient(theme.color, 55)
            update { it.copy(card = Card.Entry(2, now()), ambientLevel = 0.55f) }
            speak(theme.line) {
                scope.launch {
                    delay(1400)
                    if (ui.value.card is Card.Entry) setCard(null)
                    lastSpeechEnd = now()
                }
            }
        }
    }

    fun exitMode() {
        if (!ui.value.mode) return
        vehicle.restoreAll()
        sound.play(region.theme.prelude, volume = 0.6f)
        update { it.copy(mode = false, ambientColor = BLUE, ambientLevel = 0.42f) }
        toast("${Geo.eulReul(region.name)} 벗어나 여행 모드를 마쳤어요")
        speak("${region.name} 여행 모드를 마쳤어요. 조명과 화면을 평소대로 돌려 놓았어요.")
        sync.dirty()
    }

    // ------------------------------------------------------------------ 들르기와 제안

    private fun visit(p: TripPlace) {
        visited += p.id
        setPlace(p.id) { it.copy(status = "visited") }
        drive.holdMs = 2400
        update { it.copy(visited = visited.toList()) }
        toast("${p.name}에 들렀어요")
        GLog.i("DEMO_EVT", "visit:${p.id}")
        if (p.id == FIRST) {
            val fin = trip.places.firstOrNull { it.id == FINAL }
            if (fin != null) {
                currentDest = fin
                route(fin.name, fin.pos, emptyList())
                update { it.copy(dest = fin.name, waypoints = emptyList()) }
                scope.launch {
                    delay(600)
                    awaitFront()
                    delay(1200)
                    toast("다음 목적지 ${Geo.ro(fin.name)} 안내를 이어요")
                }
            }
        }
        if (waypoint?.id == p.id) {
            waypoint = null
            update { it.copy(waypoints = emptyList()) }
        }
        sync.dirty()
    }

    private fun offer(p: TripPlace, ahead: Double, off: Double) {
        offered += p.id
        // 경로에서 벗어났다 돌아오는 거리와 시내 주행(시속 13km 정도)을 감안한 시간
        val minutes = max(1, ((ahead + off * 2) / 3.6 / 60.0).roundToInt())
        val who = trip.who(p.by)
        val line = "${Geo.iGa(who)} 담은 ${Geo.iGa(p.name)} ${minutes}분 거리예요. 들를까요?"
        val at = now()
        setCard(Card.Offer(p, minutes, line, at))
        GLog.i("DEMO_EVT", "offer:${p.id}")
        sync.dirty()
        speak(line) { end ->
            if (end != Speaker.End.DONE) return@speak
            if ((ui.value.card as? Card.Offer)?.at != at) return@speak
            // 운전자가 말로 답할 수 있게 짧게 들음 (에뮬레이터에서 마이크가 없으면 화면 버튼으로)
            stt.listen("ko", ok = { said -> replyOfferBySpeech(said) }, no = { })
        }
        if (isDemoAuto) {
            demoJobs += scope.launch {
                delay(7200)
                if ((ui.value.card as? Card.Offer)?.at == at) demoSay("응")
            }
        }
    }

    private fun replyOfferBySpeech(said: String) {
        val yes = listOf("응", "네", "좋아", "들러", "그래", "가자", "예").any { said.contains(it) }
        val no = listOf("아니", "다음", "괜찮", "됐어").any { said.contains(it) }
        if (yes || no) replyOffer(yes && !no, said)
    }

    fun replyOffer(yes: Boolean, said: String? = null) {
        val c = ui.value.card as? Card.Offer ?: return
        stt.cancel()
        val p = c.place
        GLog.i("DEMO_EVT", "reply:${if (yes) "yes" else "no"}:${said ?: ""}")
        setCard(c.copy(reply = said ?: if (yes) "응, 들를게요" else "다음에", accepted = yes))
        if (yes) {
            setPlace(p.id) { it.copy(status = "next") }
            waypoint = p
            val d = currentDest
            if (d != null) route(d.name, d.pos, listOf(p.name to p.pos))
            update { it.copy(waypoints = listOf(p.name)) }
            speak("${Geo.eulReul(p.name)} 경유지로 넣었어요.")
            toast("${p.name} 경유지로 추가")
        } else {
            setPlace(p.id) { it.copy(status = "skipped") }
            speak("네, 다음에 들러요.")
        }
        scope.launch {
            delay(600)
            awaitFront()
            delay(2400)
            if ((ui.value.card as? Card.Offer)?.at == c.at) setCard(null)
            lastSpeechEnd = now()
        }
        sync.dirty()
    }

    // ------------------------------------------------------------------ 지나는 명소

    private fun nextTurn(s: Double): Pair<String, Double>? = turns.firstOrNull { it.second > s + 4 }

    private fun narrate(sa: SightAt, pose: Pose, asked: String?) {
        val sight = sa.sight
        val sideWord = when (sa.side) {
            1 -> "오른쪽"
            -1 -> "왼쪽"
            else -> "앞쪽"
        }
        // 설명 길이: 다음 길 안내까지 남은 거리와 속도로 정함 (길 안내 음성과 겹치지 않게)
        val turnS = nextTurn(pose.s)?.second ?: path.total
        val budgetM = (turnS - pose.s).coerceAtLeast(0.0)
        val mps = max(6.0, pose.speedKmh / 3.6)
        val short = asked == null && budgetM / mps < 18
        val line = if (asked != null) {
            val noun = listOf("숲", "다리", "탑", "연못", "언덕", "무덤", "마을", "궁궐").firstOrNull { asked.contains(it) } ?: "곳"
            val prev = heard.lastOrNull()?.let { region.sight(it) }
            if (prev != null && Geo.dist(prev.pos, sight.pos) < 450) "${prev.name} 뒤쪽 ${Geo.eunNeun(noun)} ${Geo.ida(sight.name)}"
            else "${sideWord}에 보이는 ${Geo.eunNeun(noun)} ${Geo.ida(sight.name)}"
        } else {
            "곧 ${sideWord}에 ${Geo.iGa(sight.name)} 보여요"
        }
        val body = if (asked != null) dropRepeat(sight.text, sight.name) else if (short) sight.short else sight.text
        val ahead = (sa.s - pose.s).roundToInt()
        val at = now()
        val seq = ++narrSeq
        val est = ((line.length + body.length) * 120L).coerceAtLeast(3500L)
        heard += sight.id
        update { it.copy(heard = heard.toList()) }
        val turnWord = nextTurn(pose.s)?.first?.let { m ->
            when {
                m.contains("left") -> "좌회전"
                m.contains("right") -> "우회전"
                else -> "직진"
            }
        } ?: "도착"
        setCard(Card.SightCard(sight, line, body, sa.side, ahead, short, budgetM.roundToInt(), asked, at, est, turn = turnWord))
        GLog.i("DEMO_EVT", "sight:${sight.id}:${if (short) "short" else "long"}")
        sync.dirty()
        speak("$line. $body") { end -> onSightSpeechEnd(seq, end) }
        if (isDemoAuto && asked == null && sight.id == "cheomseongdae") {
            // 시연: 설명 중에 차로를 바꾸면 설명을 멈췄다가 이어서 안내함
            demoJobs += scope.launch {
                delay(3200)
                if (narrSeq == seq) demoSignal()
            }
        }
    }

    private fun onSightSpeechEnd(seq: Int, end: Speaker.End) {
        if (seq != narrSeq) return
        val c = ui.value.card as? Card.SightCard ?: return
        if (c.paused) return
        if (end == Speaker.End.STOPPED && c.done) return
        setCard(c.copy(done = true))
        lastSpeechEnd = now()
        val sightId = c.sight.id
        val asked = c.asked
        scope.launch {
            delay(1600)
            val cur = ui.value.card as? Card.SightCard
            if (cur != null && cur.at == c.at && narrSeq == seq) setCard(null)
            lastSpeechEnd = now()
            if (isDemoAuto && asked == null && sightId == "cheomseongdae") {
                delay(900)
                demoSay("저 숲은 뭐야?")
            }
        }
    }

    private fun onSignal(sig: String, demo: Boolean) {
        signalImpl(sig, demo)
    }

    /** 차량 화면 [다시] */
    fun replaySight() {
        val c = ui.value.card as? Card.SightCard ?: return
        tts.stop()
        val seq = ++narrSeq
        setCard(c.copy(paused = false, done = false, at = now(), estMs = ((c.line.length + c.body.length) * 120L).coerceAtLeast(3500L)))
        GLog.i("DEMO_EVT", "replay:${c.sight.id}")
        speak("${c.line}. ${c.body}") { end -> onSightSpeechEnd(seq, end) }
    }

    /** 차량 화면 [그만] */
    fun stopSight() {
        if (ui.value.card !is Card.SightCard) return
        narrSeq++
        tts.stop()
        setCard(null)
        lastSpeechEnd = now()
    }

    /** 차량 화면 [들르기]: 지금 설명 중인 명소를 경유지로 넣음 */
    fun visitSight() {
        val c = ui.value.card as? Card.SightCard ?: return
        addStop(c.sight.id, trip.members.firstOrNull { it.driver }?.id ?: "dad", fromPhone = false)
        speak("${Geo.eulReul(c.sight.name)} 경유지로 넣었어요.")
    }

    /** 명소나 담아 둔 곳을 경유지로 넣음 (차량 [들르기], 휴대폰 [들르기]) */
    private fun addStop(id: String, by: String, fromPhone: Boolean) {
        val sight = region.sight(id)
        val existing = trip.places.firstOrNull { it.id == id }
        val name = sight?.name ?: existing?.name ?: return
        val pos = sight?.pos ?: existing?.pos ?: return
        if (existing == null) {
            val p = TripPlace(
                id = id, name = name, kind = "sight", area = region.name, pos = pos, by = by, source = "지나는 명소",
                photo = sight?.photo ?: "", time = null, status = "next", note = sight?.short ?: "",
            )
            setTrip(trip.copy(places = trip.places + p))
        } else if (existing.status != "visited") {
            setPlace(id) { it.copy(status = "next") }
        }
        waypoint = trip.places.firstOrNull { it.id == id }
        val d = currentDest
        if (d != null && ui.value.phase == Phase.DRIVE) route(d.name, d.pos, listOf(name to pos))
        if (ui.value.phase == Phase.DRIVE) update { it.copy(waypoints = listOf(name)) }
        val who = trip.who(by)
        toast(if (fromPhone) "${Geo.iGa(who)} 휴대폰에서 '$name 들르기'를 눌렀어요" else "${Geo.eulReul(name)} 경유지로 넣었어요")
        GLog.i("DEMO_EVT", "${if (fromPhone) "phone_visit" else "car_visit"}:$id")
        sync.dirty()
    }

    private fun signalImpl(sig: String, demo: Boolean) {
        update { it.copy(turnSignal = sig, signalDemo = demo) }
        val c = ui.value.card as? Card.SightCard ?: return
        if (c.done) return
        if (sig != "OFF" && !c.paused) {
            GLog.i("DEMO_EVT", "pause:signal")
            setCard(c.copy(paused = true, pausedAt = now()))
            tts.stop()
        } else if (sig == "OFF" && c.paused) {
            GLog.i("DEMO_EVT", "resume")
            val seq = ++narrSeq
            setCard(c.copy(paused = false, at = now(), estMs = (c.body.length * 120L).coerceAtLeast(2500L)))
            speak(c.body) { end -> onSightSpeechEnd(seq, end) }
        }
    }

    // ------------------------------------------------------------------ 말로 묻기

    fun listenAsk() {
        if (ui.value.phase != Phase.DRIVE) return
        tts.stop()
        setCard(Card.Listen(now()))
        GLog.i("DEMO_EVT", "listen")
        stt.listen("ko", ok = { said -> ask(said) }, no = { reason ->
            if (ui.value.card is Card.Listen) setCard(null)
            if (reason != "NOT_READY") toast("잘 듣지 못했어요. 다시 말씀해 주세요")
            else toast("음성 인식을 준비하고 있어요. 시연 메뉴의 말하기 예시를 눌러 보세요")
        })
    }

    fun ask(said: String) {
        val pose = ui.value.pose ?: return
        GLog.i("DEMO_EVT", "ask:$said")
        val key = mapOf(
            "숲" to "gyerim", "다리" to "woljeonggyo", "연못" to "donggung", "탑" to "cheomseongdae", "돌" to "cheomseongdae",
            "언덕" to "daereungwon", "무덤" to "daereungwon", "궁궐" to "wolseong", "마을" to "gyochon", "한옥" to "gyochon",
        )
        val byName = sightAts.firstOrNull { said.contains(it.sight.name) }
        val byKey = key.entries.firstOrNull { said.contains(it.key) }?.value?.let { id -> sightAts.firstOrNull { it.sight.id == id } }
        val nearest = sightAts.filter { it.sight.id != FINAL }.minByOrNull { Geo.dist(pose.pos, it.sight.pos) }
        val target = byName ?: byKey?.takeIf { Geo.dist(pose.pos, it.sight.pos) < 1500 } ?: nearest
        if (target == null) {
            setCard(null)
            speak("주변에서 알려 드릴 곳을 찾지 못했어요.")
            return
        }
        tts.stop()
        narrate(target, pose, said)
    }

    // ------------------------------------------------------------------ 도착과 기록

    private fun arrive() {
        if (ui.value.phase != Phase.DRIVE) return
        drive.running = false
        val fin = trip.places.firstOrNull { it.id == FINAL }
        visited += FINAL
        setPlace(FINAL) { it.copy(status = "visited") }
        update { it.copy(phase = Phase.ARRIVED, card = Card.Arrive(fin, now()), visited = visited.toList(), gear = "P", pose = it.pose?.copy(speedKmh = 0.0)) }
        GLog.i("DEMO_EVT", "arrive")
        speak("${fin?.name ?: "목적지"}에 도착했어요. 오늘 ${region.name} 여행은 여기까지예요.")
        scope.launch {
            delay(5200)
            makeRecord()
        }
    }

    private fun makeRecord() {
        val rec = Record(
            ready = true, km = 384, visited = visited.toList(), stories = heard.toList(), photos = photos.toList(),
            entryAt = ui.value.enteredAt ?: SCENE_ENTRY, startAt = SCENE_START, sentTo = trip.members.size,
        )
        update { it.copy(phase = Phase.RECORD, card = null, record = rec) }
        GLog.i("DEMO_EVT", "record")
        speak("오늘 여행을 1분 영상으로 만들었어요. 가족 휴대폰 ${trip.members.size}대에도 보냈어요.")
        sync.dirty()
    }

    fun replayRecord() {
        if (ui.value.record == null) return
        GLog.i("DEMO_EVT", "reel_replay")
        update { it.copy(reelSeq = it.reelSeq + 1) }
    }

    /** 차량 화면에서 재생하는 여행 영상에 넘길 자료 (여행 상태 + 동반 서버 주소) */
    fun reelJson(): String = stateJson().apply { put("base", BuildConfig.COMPANION_URL) }.toString()

    fun onReelEnded() {
        GLog.i("DEMO_EVT", "reel_end")
    }

    /** QR 화면 [문자로 링크 받기]: 운전자 휴대폰으로 여행 링크를 보냄 (시제품은 알림만) */
    fun sendLink() {
        val driver = trip.members.firstOrNull { it.driver }?.name ?: "운전자"
        toast("$driver 휴대폰으로 여행 링크를 문자로 보냈어요")
    }

    fun onPresence(n: Int) {
        if (ui.value.phones != n) update { it.copy(phones = n) }
    }

    // ------------------------------------------------------------------ 휴대폰(여행 링크)에서 온 것

    fun onInfo(url: String?) {
        if (url.isNullOrBlank()) return
        lanUrl = url
        update { it.copy(qrUrl = url.trimEnd('/') + "/t/" + trip.code) }
    }

    fun onOnline(on: Boolean) {
        if (ui.value.online != on) update { it.copy(online = on) }
    }

    fun onInbox(items: JSONArray) {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val by = o.optString("by", "mom")
            val who = trip.who(by)
            when (o.optString("type")) {
                "add" -> {
                    val po = o.optJSONObject("place") ?: continue
                    val p = TripPlace.from(po, by = by, status = "open").copy(time = null)
                    if (trip.places.any { it.id == p.id }) continue
                    setTrip(trip.copy(places = trip.places + p))
                    update { it.copy(newPlaceId = p.id) }
                    toast("${Geo.iGa(who)} 휴대폰에서 ${Geo.eulReul(p.name)} 담았어요")
                    GLog.i("DEMO_EVT", "phone_add:${p.id}")
                }
                "visit" -> {
                    val id = o.optString("id")
                    addStop(id, by, fromPhone = true)
                }
                "photo" -> {
                    val url = o.optString("url")
                    if (url.isNotBlank()) photos += url
                    toast("${Geo.iGa(who)} 여행 사진을 올렸어요")
                }
                "share" -> {
                    val on = o.optBoolean("on")
                    setTrip(trip.copy(members = trip.members.map { if (it.id == by) it.copy(sharing = on) else it }))
                    toast("$who 위치 공유 ${if (on) "켬" else "끔"}")
                }
            }
        }
    }

    /** 동반 서버로 보내는 여행 상태 (휴대폰 '지금' 화면이 이 값을 보여 줌) */
    fun stateJson(): JSONObject {
        val u = ui.value
        val o = JSONObject()
        o.put("code", trip.code); o.put("title", trip.title); o.put("region", trip.region); o.put("date", trip.date)
        o.put("from", trip.from); o.put("to", trip.to)
        o.put("members", JSONArray().apply {
            trip.members.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id); put("name", m.name); put("color", String.format("#%06X", m.color and 0xFFFFFF)); put("driver", m.driver); put("sharing", m.sharing)
                })
            }
        })
        o.put("places", JSONArray().apply { trip.places.forEach { put(it.toJson()) } })
        o.put("heard", JSONArray(heard.toList()))
        o.put("now", nowJson(u.card) ?: JSONObject.NULL)
        o.put("mode", JSONObject().apply { put("active", u.mode); put("region", region.id); put("enteredAt", u.enteredAt ?: JSONObject.NULL) })
        val p = u.pose
        if (p != null) {
            o.put("car", JSONObject().apply {
                put("lat", p.pos.lat); put("lon", p.pos.lon); put("heading", p.heading); put("speedKmh", p.speedKmh.roundToInt())
                put("driving", u.phase == Phase.DRIVE); put("eta", u.nav?.eta ?: ""); put("dest", u.dest); put("remainKm", u.nav?.remainKm ?: 0.0)
            })
        } else o.put("car", JSONObject.NULL)
        val r = u.record
        if (r != null) {
            o.put("record", JSONObject().apply {
                put("ready", r.ready); put("title", "오늘의 ${region.name}"); put("duration", "1:02"); put("km", r.km)
                put("visited", JSONArray(r.visited)); put("stories", JSONArray(r.stories)); put("photos", JSONArray(r.photos))
                put("entryAt", r.entryAt); put("startAt", r.startAt); put("sentTo", r.sentTo)
            })
        } else o.put("record", JSONObject.NULL)
        return o
    }

    private fun nowJson(c: Card?): JSONObject? = when (c) {
        is Card.SightCard -> JSONObject().apply {
            put("type", "sight"); put("id", c.sight.id); put("title", c.sight.name); put("tag", c.sight.tag); put("line", c.line); put("text", c.body)
            put("side", when (c.side) { 1 -> "right"; -1 -> "left"; else -> "ahead" }); put("photo", c.sight.photo)
            put("asked", c.asked ?: JSONObject.NULL); put("paused", c.paused)
        }
        is Card.Offer -> JSONObject().apply {
            put("type", "offer"); put("id", c.place.id); put("title", c.place.name); put("line", c.line); put("text", c.place.note); put("photo", c.place.photo)
        }
        is Card.Entry, is Card.Approach -> JSONObject().apply {
            put("type", "mode"); put("id", region.id); put("title", region.name); put("line", region.theme.welcome); put("text", region.theme.line); put("photo", "gyeongju_2.jpg")
        }
        is Card.Arrive -> JSONObject().apply {
            put("type", "arrive"); put("id", c.place?.id ?: ""); put("title", c.place?.name ?: ""); put("line", "도착했어요"); put("text", c.place?.note ?: ""); put("photo", c.place?.photo ?: "")
        }
        else -> null
    }

    // ------------------------------------------------------------------ 길 안내 표시

    private fun navHint(pose: Pose): NavHint {
        val dest = currentDest
        // 지금 목적지까지 남은 거리와 시간 (시내는 시속 30km, 고속도로는 시속 90km 정도로 계산)
        val destS = dest?.let { path.project(it.pos).first } ?: path.total
        val remain = (destS - pose.s).coerceAtLeast(0.0)
        val hwy = (gateS - pose.s).coerceIn(0.0, remain)
        val etaMin = max(1, ((hwy / 25.0 + (remain - hwy) / 8.3) / 60.0).roundToInt())
        val eta = "${etaMin}분"
        if (pose.s < gateS) {
            val d = (gateS - pose.s).roundToInt()
            return NavHint("straight", d, region.gateName, dest?.name ?: "", eta, remain / 1000.0)
        }
        val nt = nextTurn(pose.s)
        val turn = nt?.first ?: "straight"
        val dist = ((nt?.second ?: path.total) - pose.s).roundToInt()
        val toward = when {
            turn.contains("left") -> "좌회전"
            turn.contains("right") -> "우회전"
            else -> "직진"
        }
        return NavHint(turn, dist, toward, dest?.name ?: "", eta, remain / 1000.0)
    }

    private fun upcoming(pose: Pose): List<Upcoming> =
        sightAts.filter { it.sight.id !in heard && it.sight.id != "gyerim" && !nearTripPlace(it.sight) && it.s > pose.s - 20 && it.off <= it.sight.radiusM + 80 }
            .take(3).map { Upcoming(it.sight, (it.s - pose.s).roundToInt(), it.side) }

    /** 물어본 답에서는 첫 문장이 질문의 답과 겹치면 뺌 ("계림은 첨성대 뒤쪽에 있는 숲이에요") */
    private fun dropRepeat(text: String, name: String): String {
        val first = text.substringBefore(". ", text)
        return if (first.startsWith(name) && first.length < text.length) text.substringAfter(". ").trim() else text
    }

    // ------------------------------------------------------------------ 차량 상태

    private fun onVehicle(v: VehicleBridge.State) {
        update { it.copy(vehicleLinked = v.connected, lightAreas = v.ambientAreas, gear = if (it.phase == Phase.DRIVE) "D" else v.gear) }
        if (v.turnSignal != lastSignal) {
            lastSignal = v.turnSignal
            onSignal(v.turnSignal, false)
        }
    }

    // ------------------------------------------------------------------ 화면 조작

    fun toggleQr(show: Boolean) {
        val parked = ui.value.phase != Phase.DRIVE
        if (show && !parked) {
            toast("주행 중에는 QR을 띄우지 않아요. 정차하면 보여 드릴게요")
            return
        }
        update { it.copy(showQr = show) }
    }

    fun toggleDemo(show: Boolean) = update { it.copy(showDemo = show) }

    // ------------------------------------------------------------------ 시연 도구 (화면 '시연' 메뉴와 adb 명령)

    var isDemoAuto = true

    /** 순정 내비게이션에 목적지를 실제로 넣을지 (시연 영상 녹화 때만 끔) */
    var naviEnabled = true

    fun demoSpeed(x: Double) {
        userScale = x
        toast("시연 주행 ${x.roundToInt()}배속")
    }

    fun injectDemoPlace(silent: Boolean = false) {
        if (trip.places.any { it.id == "hwangridan" }) return
        val p = TripPlace(
            id = "hwangridan", name = "황리단길 카페", kind = "cafe", area = "경주 황남동", pos = LatLng(35.8372, 129.2096),
            by = "mom", source = "블로그", photo = "hwangridan_1.jpg", time = null, status = "open", note = "한옥 카페가 모인 골목",
        )
        setTrip(trip.copy(places = trip.places + p))
        update { it.copy(newPlaceId = p.id) }
        if (!silent) toast("엄마가 휴대폰에서 황리단길 카페를 담았어요")
    }

    fun demoSay(text: String) {
        GLog.i("DEMO_EVT", "say:$text")
        when (ui.value.card) {
            is Card.Offer -> replyOffer(listOf("응", "네", "좋아", "들러").any { text.contains(it) }, text)
            else -> ask(text)
        }
    }

    fun demoSignal() {
        onSignal("RIGHT", true)
        demoJobs += scope.launch {
            delay(2600)
            onSignal("OFF", true)
        }
    }

    fun demoArrive() {
        if (ui.value.phase != Phase.DRIVE) return
        drive.jump(path.total - 30)
    }

    fun demoReset() {
        demoJobs.forEach { it.cancel() }
        demoJobs.clear()
        tts.stop()
        stt.cancel()
        sound.stop()
        vehicle.restoreAll()
        drive.running = false
        drive.reset(0.0)
        approached = false
        entered = false
        offered.clear(); heard.clear(); visited.clear(); photos.clear()
        currentDest = null
        waypoint = null
        trip = seed
        navi.cancelRoute()
        update {
            Ui(
                trip = seed, qrUrl = it.qrUrl, online = it.online, navLinked = it.navLinked, vehicleLinked = it.vehicleLinked,
                lightAreas = it.lightAreas, phones = it.phones,
            )
        }
        sync.dirty()
    }

    fun release() {
        sound.stop()
        vehicle.restoreAll()
    }
}
