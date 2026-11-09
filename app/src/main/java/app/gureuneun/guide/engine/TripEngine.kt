package app.gureuneun.guide.engine

import android.content.Context
import android.content.Intent
import android.graphics.Color
import app.gureuneun.guide.GuideApp
import app.gureuneun.guide.MainActivity
import app.gureuneun.guide.core.Geo
import app.gureuneun.guide.core.Ko
import app.gureuneun.guide.core.Ko.eulReul
import app.gureuneun.guide.core.Ko.fillKo
import app.gureuneun.guide.core.Ko.koCount
import app.gureuneun.guide.core.Ko.spokenName
import app.gureuneun.guide.core.LatLng
import app.gureuneun.guide.core.Place
import app.gureuneun.guide.core.PlaceKind
import app.gureuneun.guide.core.Polyline
import app.gureuneun.guide.core.Scenario
import app.gureuneun.guide.core.ScenarioParser
import app.gureuneun.guide.core.StoryLength
import app.gureuneun.guide.core.pick
import app.gureuneun.guide.net.CompanionClient
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.sdk.MediaKeys
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * 여행 모드의 중심.
 * [여행 시작] 한 번으로 내비게이션, 음성, 실내 조명, 화면, 동승자 휴대폰을 한꺼번에 움직임.
 * 달리는 동안에는 말로 묻고(SpeechToText) 말로 답하며(TextToSpeech), 차 상태(Vehicle)와 길 안내(NaviHelper)를 보고
 * 이야기 길이, 쉬어 갈 때, 바깥 공기, 보물 상자 같은 순간을 스스로 고름.
 */
class TripEngine(private val app: GuideApp) {

    enum class Phase { HOME, ROLLCALL, DRIVING, ENDED }

    data class Pick(val id: String, val name: String, val pos: LatLng, val kind: String, val by: String, val fromCar: Boolean)

    /** 차가 먼저 권하는 것. 운전자가 화면에서 [예]를 눌러야만 움직임 */
    enum class OfferKind { REST, CHARGE, TREASURE, SUNROOF }

    data class Offer(val kind: OfferKind, val title: String, val body: String, val yes: String, val no: String, val placeId: String? = null)

    /** 보물 상자: 별을 모으는 중, 다 모아 차를 세우기를 기다리는 중, 열림 */
    enum class Treasure { NONE, COLLECTING, READY, OPEN }

    data class Ui(
        val phase: Phase = Phase.HOME,
        val scenario: Scenario? = null,
        val lang: String = "ko",
        val demoMode: Boolean = true,
        val carName: String = "1호차",
        val groupCode: String? = null,
        val nowPlace: Place? = null,
        val nowKind: StoryPlayer.Kind? = null,
        val nowTitle: String? = null,
        val nowText: String? = null,
        val nowSentence: String? = null,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val nowLength: StoryLength? = null,
        val speaking: Boolean = false,
        val holds: Set<String> = emptySet(),
        val entered: List<String> = emptyList(),
        val heard: Map<String, StoryLength> = emptyMap(),
        val nextPlace: Place? = null,
        val nextDistanceM: Double? = null,
        val guideDistanceM: Double? = null,
        val guideFromNavi: Boolean = false,
        val carPos: LatLng? = null,
        val heading: Double = 0.0,
        val progressM: Double = 0.0,
        val routeLengthM: Double = 0.0,
        val rain: Boolean = false,
        val night: Boolean = false,
        val pick: Pick? = null,
        val windowAsk: Place? = null,
        val windowOpened: Boolean = false,
        val toast: String? = null,
        val toastSeq: Int = 0,
        val demoRunning: Boolean = false,
        val demoFast: Int = 6,
        val followGear: Boolean = true,
        val accent: Int = 0xFF7FE3FF.toInt(),
        val startedAt: Long = 0,
        val resumable: Boolean = false,
        val beltsConfirmed: Boolean = false,
        // 말로 묻기
        val listening: Boolean = false,
        val heardText: String? = null,
        val heardSeq: Int = 0,
        val answerText: String? = null,
        val answerTitle: String? = null,
        val asked: Int = 0,
        // 뒷자리 아이 (kidManual 이 null 이면 뒷좌석 착석으로 자동)
        val kidMode: Boolean = false,
        val kidManual: Boolean? = null,
        // 퀴즈와 보물 상자
        val quizSent: Int = 0,
        val stars: Int = 0,
        val starsNeeded: Int = 0,
        val treasure: Treasure = Treasure.NONE,
        val lastStarBy: String? = null,
        // 차가 먼저 권하는 것
        val offer: Offer? = null,
        // 차 안 연출과 길 상황
        val freshAir: Boolean = false,
        val sunroofOpen: Boolean = false,
        val jam: Boolean = false,
        val demoJam: Boolean = false,
        /** 이번 여행에서 달린 시간(분). 차가 주행 시간 신호를 주면 그 값, 아니면 앱이 잰 값 */
        val drivingMin: Int? = null,
        /** 시연 패널의 [녹음한 질문으로 묻기]가 다음에 넣을 질문 번호 */
        val demoQuestionIdx: Int = 0,
    )

    /** 시연용으로 앱에 넣어 둔 녹음 질문 (assets/ask, 16kHz 모노 16비트 PCM) */
    data class DemoQuestion(val file: String, val text: String)

    companion object {
        const val HOLD_TURN = "turn"
        const val HOLD_GUIDE = "guide"
        const val HOLD_USER = "user"
        const val HOLD_LISTEN = "listen"
        const val HOLD_CAUTION = "caution"
        const val WINDOW_OPEN = 3 // 0~10 중 3, 한 뼘쯤
        // 다음 길 안내가 이만큼 가까우면 이야기를 쉼. 시연 경로에는 실제 내비 음성이 없어 짧게 둠
        private const val GUIDE_HOLD_M = 150.0
        private const val GUIDE_HOLD_DEMO_M = 40.0
        private const val CRUISE_BRIGHTNESS = 40
        /** 이만큼 달리면 쉬어 가자고 권함 (졸음운전 예방, 2시간마다 쉬기) */
        const val REST_AFTER_MIN = 120
        /** 배터리가 이만큼 아래로 내려가면 충전소를 권함 */
        const val LOW_SOC = 20
        /** 남은 경로에 이만큼 여유가 없으면 충전소를 권함 (km) */
        const val RANGE_MARGIN_KM = 20.0
        /** 시속 이만큼 아래로 20초 넘게 달리면 막히는 길로 봄 */
        private const val JAM_KMH = 15.0
        /** 단속 카메라, 어린이 보호구역이 이만큼 가까우면 이야기를 쉼 */
        private const val CAUTION_HOLD_M = 250
        private const val DEMO_KMH = 40.0
        private const val DEMO_JAM_KMH = 9.0
    }

    val ui = MutableStateFlow(Ui())
    val sound = SoundFx(app)
    val recorder = TripRecorder(app)
    private val prefs = app.getSharedPreferences("guide", Context.MODE_PRIVATE)
    val companion = CompanionClient(app.scope, prefs)
    val player = StoryPlayer(app.speech) { syncPlayer() }
    val demo = DemoDriver(app.scope) { onDemoTick(it) }
    private var media: MediaKeys? = null

    private var scenario: Scenario? = null
    private var polyline: Polyline? = null
    private var maneuverAlong: List<Double> = emptyList()
    private val inside = HashSet<String>()
    private val entered = LinkedHashSet<String>()
    // 시연 중 건너뛴 명소. 지나간 것으로 치지만 "함께 지나온 명소" 수에는 넣지 않음
    private val skippedInDemo = HashSet<String>()
    private var savedAmbient: Pair<Int, Int>? = null
    private var parkedSince: Long? = null
    private var turnJob: Job? = null
    private var guideJob: Job? = null
    private var lastFix: Pair<Long, LatLng>? = null
    private var speedMs = 11.0
    private var rainAnnounced = false
    private var nightAnnounced = false
    private var windowAskedFor = HashSet<String>()
    private var regionColor = 0xFF7FE3FF.toInt()
    private var playerWasBusy = false
    // 퀴즈와 보물 상자
    private val quizSentFor = LinkedHashSet<String>()
    private val quizCorrect = LinkedHashSet<String>()
    private var gloveWasLocked: Boolean? = null
    // 바깥 공기를 들인 명소와 그 전 공조 상태 (지나가면 되돌림)
    private var freshAirFor: String? = null
    // 쉬어 가기, 충전 제안은 같은 내용을 자꾸 하지 않게 시각을 기억함
    private var lastRestOfferAt = 0L
    private var lastChargeOfferAt = 0L
    private var jamAnnounced = false
    private var slowSince: Long? = null
    private var fastSince: Long? = null
    private val sunroofAskedFor = HashSet<String>()
    private var cautionJob: Job? = null
    // 차가 주행 시간 신호를 주지 않을 때 앱이 직접 재는 주행 시간 (D단이거나 시연 주행 중인 시간만 셈)
    private var driveAccumMs = 0L
    private var driveOffsetMin = 0

    val demoQuestions: Map<String, List<DemoQuestion>> = mapOf(
        "ko" to listOf(
            DemoQuestion("q_right", "오른쪽에 보이는 거 뭐야?"),
            DemoQuestion("q_remain", "얼마나 남았어?"),
            DemoQuestion("q_where", "여기 어디야?"),
            DemoQuestion("q_battery", "배터리 얼마나 남았어?"),
            DemoQuestion("q_quiz", "퀴즈 내 줘"),
            DemoQuestion("q_hot", "좀 더운데"),
            DemoQuestion("q_rest", "화장실 가고 싶어"),
            DemoQuestion("q_what", "저거 뭐야?"),
        ),
        "en" to listOf(
            DemoQuestion("q_en_right", "What's that on the right?"),
            DemoQuestion("q_en_remain", "How long until we get there?"),
        ),
    )

    private val lang get() = ui.value.lang
    private val guideHoldM get() = if (ui.value.demoMode) GUIDE_HOLD_DEMO_M else GUIDE_HOLD_M

    init {
        // 시연 주행: 들려줄 이야기가 남아 있으면 실제 속도, 아무것도 없을 때만 빨리 감기
        // 질문을 듣는 동안에도 실제 속도로 (빨리 감기 중에 물으면 답할 때쯤 이미 멀리 지나 있음)
        demo.storyPlaying = { player.busy || ui.value.listening }
        player.onSentence = { item, index ->
            val text = item.sentences.getOrNull(index) ?: ""
            GLog.i("Speak", "[" + item.lang + "] " + text)
            companion.send("say", JSONObject().put("text", text).put("lang", item.lang).put("kind", item.kind.name).put("placeId", item.placeId ?: ""))
            if (item.placeId != null) {
                companion.send("line", JSONObject().put("placeId", item.placeId).put("index", index).put("text", text))
            }
        }
        observeVehicle()
        observeNavi()
        observeLocation()
        observeCompanion()
        ui.update { it.copy(resumable = prefs.getString("trip_scenario", null) != null) }
        // 5초마다 주행 시간을 재고 쉬어 갈 때가 됐는지 봄
        app.scope.launch {
            while (true) {
                delay(5000)
                tickDrivingTime(5000)
            }
        }
    }

    fun attachMedia(keys: MediaKeys) {
        media = keys
        keys.activate()
    }

    // ---------------------------------------------------------------- 시나리오

    fun loadScenario(id: String): Scenario =
        ScenarioParser.parse(app.assets.open("scenarios/$id.json").bufferedReader().use { it.readText() })

    fun scenarioList(): List<Scenario> = listOf("gyeongju", "jeju", "seoul").mapNotNull { runCatching { loadScenario(it) }.getOrNull() }

    // ---------------------------------------------------------------- 여행 시작과 끝

    fun startTrip(scenarioId: String, lang: String, joinCode: String?, demoMode: Boolean, resume: Boolean = false) {
        val sc = loadScenario(scenarioId)
        scenario = sc
        val line = Polyline(sc.route)
        polyline = line
        // 시연 경로의 회전 지점: 살짝 꺾이는 곳은 빼고, 120m 안에 붙은 회전은 한 번의 안내로 침
        // (실제 내비도 "우회전 뒤 바로 좌회전"처럼 묶어 말하고, 시연 중 이야기가 너무 자주 끊기지 않게)
        maneuverAlong = sc.maneuvers.filter { !it.modifier.startsWith("slight") }.map { line.project(it.pos) }.sorted()
            .fold(mutableListOf<Double>()) { acc, d -> if (acc.isEmpty() || d - acc.last() > 120) acc.add(d); acc }
        inside.clear()
        entered.clear()
        skippedInDemo.clear()
        windowAskedFor.clear()
        rainAnnounced = false
        nightAnnounced = false
        restoreCabin()
        quizSentFor.clear()
        quizCorrect.clear()
        lastRestOfferAt = 0
        lastChargeOfferAt = 0
        jamAnnounced = false
        slowSince = null
        fastSince = null
        sunroofAskedFor.clear()
        demo.cityKmh = DEMO_KMH
        driveAccumMs = 0
        driveOffsetMin = 0
        // 앞 여행에서 세워 둔 시각이 남아 있으면 첫 출발을 "다시 출발"로 잘못 알아들음
        parkedSince = null
        player.stopAll()
        recorder.begin(sc.id, lang)
        regionColor = parseColor(sc.places.firstOrNull { it.kind == PlaceKind.CITY }?.color ?: "#7FE3FF")

        val v = app.vehicle.state.value
        savedAmbient = if (v.ambientColor != null) v.ambientColor to (v.ambientBrightness ?: 70) else null
        val carName = if (joinCode != null) "2호차" else "1호차"
        val resumeEntered = if (resume) prefs.getString("trip_entered", "")!!.split(",").filter { it.isNotBlank() } else emptyList()
        entered.addAll(resumeEntered)
        ui.value = Ui(
            phase = Phase.ROLLCALL,
            scenario = sc,
            lang = lang,
            demoMode = demoMode,
            carName = carName,
            entered = entered.toList(),
            routeLengthM = line.length,
            startedAt = System.currentTimeMillis(),
            accent = 0xFFFFE2B0.toInt(),
            followGear = ui.value.followGear,
            kidManual = ui.value.kidManual,
            // 뒷좌석 착석으로 아이 모드를 켜는 건 가족 코스만 (택시 뒷좌석 손님, 친구들은 아이가 아니니까)
            kidMode = ui.value.kidManual ?: (sc.kidAuto && rearOccupied()),
            starsNeeded = sc.treasureStars,
            treasure = if (sc.treasureStars > 0) Treasure.COLLECTING else Treasure.NONE,
        )
        saveTrip(joinCode)
        GLog.i("Trip", "여행 시작: ${sc.title.pick(lang)} (${if (demoMode) "시연 모드" else "실제 위치"})")
        recorder.event("start", null, sc.title.pick(lang))

        // 1. 실내 조명: 출발 전 따뜻한 흰빛으로 맞이함
        setAmbient(0xFFFFE2B0.toInt(), 85)

        // 2. 여행 그룹: 네 자리 번호를 만들거나 다른 차 그룹에 들어감
        app.scope.launch {
            val code = if (joinCode != null) {
                if (companion.joinGroup(joinCode, carName) != null) joinCode else null
            } else companion.createGroup(sc.id, lang, carName)
            ui.update { it.copy(groupCode = code ?: joinCode ?: localCode()) }
            if (code == null) toast(if (lang == "en") "Group server offline. The car guide still works." else "그룹 서버에 연결하지 못했어요. 차 안 안내는 그대로 돼요.")
            else companion.send("trip", JSONObject().put("scenarioId", sc.id).put("lang", lang).put("entered", entered.joinToString(",")))
        }

        // 3. 내비게이션: 목적지와 경유지(최대 두 곳)를 순정 내비에 넣음
        sc.destination?.let { dest ->
            app.navi.routeTo(dest.name.pick(lang), dest.pos, sc.waypoints.map { it.name.pick(lang) to it.pos })
        }

        // 4. 위치: 여행지와 명소마다 근접 알림을 등록 (실차), 시연 모드는 같은 판정을 앱 안에서 함
        app.location.watch(sc.places.filter { it.triggers })
        val startM = if (resume) prefs.getFloat("trip_progress", 0f).toDouble() else 0.0
        demo.load(sc.route, startM)
        demo.fastForward = ui.value.demoFast

        // 5. 좌석 점호와 인사
        if (resume) {
            val next = nextPlaceAfter()
            say(PlaceKindSystem.CHAPTER, fillKo(sc.chapter.pick(lang), "next", next?.name?.pick(lang) ?: ""))
            completeRollcall(silent = true)
        } else {
            val names = sc.landmarks.filter { it.triggers }.joinToString(if (lang == "en") ", " else ", ") { it.name.pick(lang) }
            say(PlaceKindSystem.GREETING, fillKo(sc.greeting.pick(lang), "places", names))
            if (app.vehicle.state.value.connected && app.vehicle.state.value.allBuckled) completeRollcall()
        }
        bringToFrontSoon()
    }

    private enum class PlaceKindSystem { GREETING, DEPARTURE, CHAPTER, FINALE, NOTICE }

    /** title 을 주면 화면에 "알림" 대신 그 제목을 띄움 (예: 보물 상자, 쉬어 갈 시간) */
    private fun say(kind: PlaceKindSystem, text: String, front: Boolean = false, interject: Boolean = false, stale: (() -> Boolean)? = null, title: String? = null) {
        if (text.isBlank()) return
        val item = StoryPlayer.Item(
            key = "sys-${kind.name}-${System.nanoTime()}",
            placeId = null,
            title = title ?: when (kind) {
                PlaceKindSystem.GREETING -> if (lang == "en") "Today's course" else "오늘의 코스"
                PlaceKindSystem.DEPARTURE -> if (lang == "en") "Let's go" else "출발"
                PlaceKindSystem.CHAPTER -> if (lang == "en") "Next chapter" else "다음 장"
                PlaceKindSystem.FINALE -> if (lang == "en") "See you again" else "오늘 여행을 마치며"
                PlaceKindSystem.NOTICE -> if (lang == "en") "Notice" else "알림"
            },
            lang = lang,
            sentences = StoryPlayer.sentences(text),
            kind = StoryPlayer.Kind.SYSTEM,
            stale = stale,
        )
        when {
            interject -> player.interject(item)
            front -> player.enqueueFront(item)
            else -> player.enqueue(item)
        }
    }

    /** 모두 벨트를 매면(또는 직접 확인하면) 출발 */
    fun completeRollcall(silent: Boolean = false) {
        val sc = scenario ?: return
        if (ui.value.phase != Phase.ROLLCALL) return
        ui.update { it.copy(phase = Phase.DRIVING, beltsConfirmed = true) }
        recorder.event("depart", null, sc.title.pick(lang))
        if (!silent) {
            sound.chime(rising = true)
            say(PlaceKindSystem.DEPARTURE, sc.departure.pick(lang))
        }
        setAmbient(regionColor, 70)
        app.scope.launch {
            delay(2500)
            setAmbient(regionColor, CRUISE_BRIGHTNESS)
        }
        // 출발지가 이미 여행지 안이면 도시 오프닝을 바로 들려줌
        ui.value.carPos?.let { checkPlaces(it, ui.value.heading) }
        maybeRunDemo()
    }

    fun endTrip() {
        val sc = scenario ?: return
        demo.stop()
        // 하던 이야기는 휴대폰에서 이어 들을 수 있게 넘기고, 앱이 바꾼 차 안 설정은 되돌림
        handOffToPhones()
        player.stopAll()
        sound.stopWaves()
        restoreCabin()
        val count = entered.count { id -> id !in skippedInDemo && sc.findPlace(id)?.kind != PlaceKind.CITY }
        say(PlaceKindSystem.FINALE, sc.finale.pick(lang).replace("{count}곳", koCount(count) + " 곳").replace("{count}", count.toString()))
        recorder.event("end", null, sc.title.pick(lang))
        recorder.end()
        app.location.clear()
        restoreAmbient()
        clearTrip()
        ui.update { it.copy(phase = Phase.ENDED, pick = null, windowAsk = null, offer = null, listening = false) }
        companion.send("trip_end")
        app.scope.launch { companion.uploadRecord(recorder.toJson()) }
        media?.publish(null, null, false)
    }

    fun goHome() {
        player.stopAll()
        demo.stop()
        restoreCabin()
        companion.leave()
        scenario = null
        ui.update { Ui(lang = it.lang, demoMode = it.demoMode, followGear = it.followGear, demoFast = it.demoFast, resumable = prefs.getString("trip_scenario", null) != null) }
    }

    fun setLang(l: String) = ui.update { it.copy(lang = l) }
    fun setDemoMode(on: Boolean) = ui.update { it.copy(demoMode = on) }

    // ---------------------------------------------------------------- 시연 주행

    fun demoPlay() {
        if (ui.value.phase == Phase.ROLLCALL) completeRollcall()
        demo.play()
        ui.update { it.copy(demoRunning = true) }
    }

    fun demoPause() {
        demo.stop()
        ui.update { it.copy(demoRunning = false) }
    }

    fun demoFaster() {
        val next = when (ui.value.demoFast) { 2 -> 4; 4 -> 6; 6 -> 10; else -> 2 }
        demo.fastForward = next
        ui.update { it.copy(demoFast = next) }
    }

    fun toggleFollowGear() = ui.update { it.copy(followGear = !it.followGear) }

    fun setFollowGear(on: Boolean) = ui.update { it.copy(followGear = on) }

    fun setDemoFast(n: Int) {
        demo.fastForward = n.coerceIn(1, 20)
        ui.update { it.copy(demoFast = demo.fastForward) }
    }

    /** 다음 명소(to 를 주면 그 명소) 바로 앞으로 건너뜀. 사이에 있는 명소는 지나간 것으로 침 */
    fun demoJumpToNext(to: String? = null) {
        val line = polyline ?: return
        val sc = scenario ?: return
        val target = to?.let { id -> sc.places.firstOrNull { it.id == id } } ?: nextPlaceAfter() ?: return
        val along = line.project(target.pos)
        val skipped = sc.places.filter {
            it.triggers && it.kind != PlaceKind.CITY && it.id != target.id && it.id !in entered && line.project(it.pos) < along
        }
        if (skipped.isNotEmpty()) {
            entered += skipped.map { it.id }
            skippedInDemo += skipped.map { it.id }
            saveEntered()
            ui.update { it.copy(entered = entered.toList()) }
        }
        demo.seek(max(0.0, along - target.radiusM - 120))
    }

    private fun maybeRunDemo() {
        val u = ui.value
        if (!u.demoMode || u.phase != Phase.DRIVING) return
        val v = app.vehicle.state.value
        val gearKnown = v.connected && v.gear != "-"
        if (!u.followGear || !gearKnown || v.gear == "D") demoPlay()
    }

    /** 시연: 경로의 m 미터 지점으로 옮김 (사이의 명소는 지나간 것으로 침) */
    fun demoSeek(m: Double) {
        val line = polyline ?: return
        val sc = scenario ?: return
        val skipped = sc.places.filter { it.triggers && it.kind != PlaceKind.CITY && it.id !in entered && line.project(it.pos) + it.radiusM < m }
        if (skipped.isNotEmpty()) {
            entered += skipped.map { it.id }
            skippedInDemo += skipped.map { it.id }
            saveEntered()
            ui.update { it.copy(entered = entered.toList()) }
        }
        demo.seek(m)
    }

    private fun onDemoTick(t: DemoDriver.Tick) {
        ui.update { it.copy(demoRunning = demo.running.value, progressM = t.progressM) }
        if (!ui.value.demoMode) return
        speedMs = if (t.speedKmh > 0) t.speedKmh / 3.6 else speedMs
        trackSpeed(t.speedKmh)
        onPosition(t.pos, t.heading)
        // 시연 경로의 회전 지점까지 남은 거리로 길 안내 시점을 흉내 냄
        val nextIdx = maneuverAlong.indexOfFirst { it > t.progressM + 5 }
        val dist = if (nextIdx >= 0) maneuverAlong[nextIdx] - t.progressM else null
        ui.update { it.copy(guideDistanceM = dist, guideFromNavi = false) }
        updateGuidanceHold(dist)
        if (ui.value.phase == Phase.DRIVING) saveProgress(t.progressM)
        if (t.progressM >= t.lengthM && t.lengthM > 0) demoPause()
    }

    // ---------------------------------------------------------------- 위치와 명소

    private fun onPosition(p: LatLng, heading: Double) {
        ui.update { it.copy(carPos = p, heading = heading) }
        if (ui.value.phase == Phase.DRIVING) {
            recorder.point(p)
            checkPlaces(p, heading)
        }
        val sc = scenario ?: return
        val next = nextPlaceAfter()
        val line = polyline
        val dist = if (next != null && line != null) max(0.0, line.project(next.pos) - line.project(p)) else null
        ui.update { it.copy(nextPlace = next, nextDistanceM = dist) }
        if (sc.places.isEmpty()) return
    }

    private fun checkPlaces(p: LatLng, heading: Double) {
        val sc = scenario ?: return
        sc.places.filter { it.triggers }.forEach { place ->
            val d = Geo.distanceM(p, place.pos)
            val isIn = place.id in inside
            if (!isIn && d <= place.radiusM) {
                inside += place.id
                onEnter(place, p, heading)
            } else if (isIn && d > place.radiusM * 1.25) {
                inside -= place.id
                if (place.id == freshAirFor) breatheOut()
            }
        }
    }

    private fun nextPlaceAfter(): Place? {
        val sc = scenario ?: return null
        return sc.places.firstOrNull { it.triggers && it.kind != PlaceKind.CITY && it.id !in entered }
    }

    /** 경로를 따라 이 명소를 marginM 미터 넘게 지나쳤는지. 늦게 차례가 온 안내를 걸러 낼 때 씀 */
    private fun passed(place: Place, marginM: Double): Boolean {
        val line = polyline ?: return false
        val car = ui.value.carPos ?: return false
        return line.project(car) > line.project(place.pos) + marginM
    }

    /** 다음 길 안내까지(없으면 다음 명소까지) 조용히 들을 수 있는 시간으로 이야기 길이를 고름 */
    private fun chooseLength(): StoryLength {
        // 막히는 길에서는 시간이 넉넉하니 긴 이야기
        if (ui.value.jam) return StoryLength.LONG
        val guide = ui.value.guideDistanceM
        val quietM = when {
            guide != null -> guide - guideHoldM
            else -> (ui.value.nextDistanceM ?: 2000.0) - 250.0
        }
        val quietS = quietM / max(speedMs, 3.0)
        return when {
            quietS > 70 -> StoryLength.LONG
            quietS > 30 -> StoryLength.MEDIUM
            else -> StoryLength.SHORT
        }
    }

    private fun sideWord(side: String): String = when (lang) {
        "en" -> when (side) { "left" -> "on your left"; "right" -> "on your right"; else -> "straight ahead" }
        else -> when (side) { "left" -> "왼쪽"; "right" -> "오른쪽"; else -> "앞쪽" }
    }

    private fun onEnter(place: Place, p: LatLng, heading: Double) {
        if (place.id in entered) return
        if (ui.value.phase != Phase.DRIVING) return
        entered += place.id
        saveEntered()
        val name = place.name.pick(lang)
        GLog.i("Trip", "영역 진입: $name")
        recorder.event("pass", place.id, name)
        companion.send("pass", JSONObject().put("placeId", place.id).put("name", name).put("at", System.currentTimeMillis()))
        ui.update { it.copy(entered = entered.toList()) }

        val color = parseColor(place.color)
        val len = chooseLength()
        val story = place.story(lang)?.of(len).orEmpty()
        val mood = when {
            ui.value.rain && place.rain.isNotEmpty() -> place.rain.pick(lang)
            ui.value.night && place.night.isNotEmpty() -> place.night.pick(lang)
            else -> ""
        }
        if (place.kind == PlaceKind.CITY) {
            regionColor = color
            sound.chime(rising = true)
            setAmbient(color, 90)
        } else {
            // 새 명소가 나타나면 하던 이야기는 이 문장까지만 하고 명소 안내로 넘어감
            player.yieldCurrent()
            // 지나갈 때 가장 가까워지는 지점에서 어느 쪽에 보이는지로 판단 ("잠시 후 오른쪽에")
            val side = polyline?.let { line ->
                val (pt, hd) = line.at(line.project(place.pos))
                if (Geo.distanceM(pt, place.pos) < 40) "ahead" else Geo.sideOf(pt, hd, place.pos)
            } ?: Geo.sideOf(p, heading, place.pos)
            val word = sideWord(side)
            val announce = place.announce.pick(lang)
                .replace("{Side}", word.replaceFirstChar { it.uppercase() })
                .replace("{side}", word)
            if (announce.isNotBlank()) {
                player.enqueue(
                    StoryPlayer.Item(
                        key = "ann-${place.id}", placeId = place.id, title = name, lang = lang,
                        sentences = StoryPlayer.sentences(announce), kind = StoryPlayer.Kind.ANNOUNCE,
                        // "잠시 후"라고 말하기엔 이미 지나쳤으면 건너뜀
                        stale = { passed(place, 30.0) },
                        onStart = {
                            setAmbient(color, 80)
                            showStoryOnPhones(place, announce, null, "announce")
                        },
                    )
                )
            }
        }
        // 바다, 숲에서는 공조를 바깥 공기로 돌려 그곳 공기를 들임. 지나가면 되돌림
        if (place.kind == PlaceKind.SEA || place.kind == PlaceKind.FOREST) breatheIn(place)
        val text = listOf(mood, story).filter { it.isNotBlank() }.joinToString(" ")
        player.enqueue(
            StoryPlayer.Item(
                key = "story-${place.id}", placeId = place.id, title = name, lang = lang,
                sentences = StoryPlayer.sentences(text), kind = StoryPlayer.Kind.STORY, length = len,
                yieldable = true,
                stale = if (place.kind == PlaceKind.CITY) null else ({ passed(place, place.radiusM * 1.5) }),
                onStart = {
                    setAmbient(color, if (place.kind == PlaceKind.CITY) 90 else 80)
                    recorder.event("story", place.id, name, len.name)
                    showStoryOnPhones(place, text, len, "story")
                    media?.publish(name, if (lang == "en") "Rolling Guide" else "구르는 가이드", true)
                },
                onDone = { completed ->
                    ui.update { it.copy(heard = it.heard + (place.id to len)) }
                    companion.send("story_end", JSONObject().put("placeId", place.id))
                    if (!player.busy) setAmbient(regionColor, CRUISE_BRIGHTNESS)
                    media?.publish(name, null, false)
                    if (!completed) GLog.i("Trip", "이야기 건너뜀: $name")
                    if (place.kind != PlaceKind.CITY) afterStory(place, completed)
                },
            )
        )
        // 바닷가, 숲길처럼 차를 세우는 곳이면 시연 주행을 멈춰 정차 장면을 보여 줌
        if (place.stop && ui.value.demoMode) {
            app.scope.launch {
                while (player.busy && demo.running.value) delay(500)
                if (demo.running.value) {
                    demoPause()
                    toast(if (lang == "en") "Demo: shift to P in the Virtual Console to park here." else "시연: Virtual Console 에서 P 를 눌러 여기 세워 보세요.")
                }
            }
        }
    }

    private fun showStoryOnPhones(place: Place, text: String, len: StoryLength?, kind: String) {
        companion.send(
            "story",
            JSONObject()
                .put("placeId", place.id)
                .put("name", place.name.pick(lang))
                .put("model", place.model ?: "")
                .put("kind", kind)
                .put("length", len?.name ?: "")
                .put("lang", lang)
                .put("text", text)
                .put("rain", ui.value.rain)
                .put("night", ui.value.night)
                .put("color", place.color),
        )
    }

    // ---------------------------------------------------------------- 차 상태

    private fun observeVehicle() {
        var prev = app.vehicle.state.value
        app.scope.launch {
            app.vehicle.state.collect { v ->
                val p = prev
                prev = v
                val phase = ui.value.phase
                // 좌석 점호: 앉은 자리 모두 벨트를 매면 출발
                if (phase == Phase.ROLLCALL && v.connected && v.allBuckled && !(p.connected && p.allBuckled)) completeRollcall()
                // 방향지시등: 켜면 멈추고 꺼지면 이어서
                if (v.turnSignal != p.turnSignal) onTurnSignal(v.turnSignal != "OFF")
                // 와이퍼와 전조등: 비 오는 날, 밤 이야기
                if (v.wiperOn != p.wiperOn || v.lowBeamOn != p.lowBeamOn) onMood(v.wiperOn, v.lowBeamOn)
                // 기어
                if (v.gear != p.gear) onGear(p.gear, v.gear)
                // 가족 여행 코스에서 뒷자리에 누가 앉으면 아이 모드 (직접 켜고 끈 적이 없을 때만)
                if (v.occupied != p.occupied) {
                    GLog.i("Vehicle", "착석 ${v.occupied.filterValues { it }.keys.joinToString().ifBlank { "없음" }}")
                    if (ui.value.kidManual == null && scenario?.kidAuto == true) {
                        val kid = rearOccupied(v)
                        if (kid != ui.value.kidMode) ui.update { it.copy(kidMode = kid) }
                    }
                }
                // 오래 달렸거나 배터리가 모자라면 쉬어 가기, 충전을 권함
                if (v.drivingMin != p.drivingMin || v.socPct != p.socPct || v.rangeKm != p.rangeKm) checkCare(v.copy(drivingMin = v.drivingMin ?: appDrivingMin()))
            }
        }
    }

    private fun onTurnSignal(on: Boolean) {
        turnJob?.cancel()
        if (on) {
            GLog.i("Trip", "방향지시등 켜짐, 이야기 잠시 멈춤")
            player.hold(HOLD_TURN)
        } else {
            turnJob = app.scope.launch {
                delay(1200)
                GLog.i("Trip", "방향지시등 꺼짐, 이야기 이어 감")
                player.release(HOLD_TURN)
            }
        }
    }

    private fun onMood(rain: Boolean, night: Boolean) {
        ui.update { it.copy(rain = rain, night = night) }
        if (ui.value.phase != Phase.DRIVING) return
        if (rain && !rainAnnounced) {
            rainAnnounced = true
            recorder.event("rain", null, if (lang == "en") "Rain" else "비")
            say(PlaceKindSystem.NOTICE, if (lang == "en") "Looks like rain. I'll tell the next stories with the sound of rain." else "비가 오네요. 다음 이야기는 빗소리와 어울리게 들려 드릴게요.", interject = true, title = if (lang == "en") "Rainy day" else "비 오는 날")
        }
        if (night && !nightAnnounced) {
            nightAnnounced = true
            recorder.event("night", null, if (lang == "en") "Night" else "밤")
            say(PlaceKindSystem.NOTICE, if (lang == "en") "The headlights are on. From now on, night stories." else "전조등이 켜졌네요. 이제부터는 밤 풍경 이야기로 바꿔 드릴게요.", interject = true, title = if (lang == "en") "Night drive" else "밤 이야기")
        }
    }

    private fun onGear(old: String, new: String) {
        val u = ui.value
        if (u.phase != Phase.DRIVING) return
        if (new == "P") {
            parkedSince = System.currentTimeMillis()
            if (u.demoMode && u.followGear) demoPause()
            // 하던 이야기는 휴대폰에서 이어 들을 수 있게 넘김
            handOffToPhones()
            // 보물 상자가 준비됐으면 먼저, 아니면 바닷가 창문, 그다음 하늘 열기
            if (!maybeOfferTreasure()) {
                maybeAskWindow()
                if (ui.value.windowAsk == null) maybeAskSunroof()
            }
        } else if (new == "D") {
            val since = parkedSince
            parkedSince = null
            ui.update { it.copy(windowAsk = null, offer = it.offer?.takeIf { o -> o.kind == OfferKind.REST || o.kind == OfferKind.CHARGE }) }
            sound.stopWaves()
            // 달리기 시작하면 열어 둔 글로브 박스는 다시 잠그고, 열어 둔 선루프는 닫음
            restoreGlove()
            closeSunroof()
            // 다시 타면 다음 장
            if (since != null && System.currentTimeMillis() - since > 15_000) {
                val next = nextPlaceAfter()
                if (next != null) say(PlaceKindSystem.CHAPTER, fillKo(scenario?.chapter?.pick(lang).orEmpty(), "next", next.name.pick(lang)))
            }
            if (u.demoMode && u.followGear) demoPlay()
        }
    }

    /** 바닷가, 숲길 명소에 차를 세우면 창문을 조금 열지 먼저 물어봄 */
    private fun maybeAskWindow() {
        val sc = scenario ?: return
        val pos = ui.value.carPos ?: return
        val spot = sc.places.firstOrNull {
            (it.kind == PlaceKind.SEA || it.kind == PlaceKind.FOREST) && it.ambience != null &&
                Geo.distanceM(pos, it.pos) <= it.radiusM * 1.3 && it.id !in windowAskedFor
        } ?: return
        windowAskedFor += spot.id
        ui.update { it.copy(windowAsk = spot) }
        say(
            PlaceKindSystem.NOTICE,
            if (lang == "en") "We've stopped by the sea. Shall I lower the windows a little so you can hear the waves?" else "바닷가에 세웠네요. 창문을 한 뼘쯤 내려 파도 소리를 들여 볼까요?",
            interject = true,
            stale = { ui.value.windowAsk == null },
            title = if (lang == "en") "Windows" else "창문",
        )
    }

    fun answerWindow(open: Boolean) {
        val spot = ui.value.windowAsk ?: return
        ui.update { it.copy(windowAsk = null) }
        if (!open) return
        if (!app.vehicle.state.value.parked) {
            toast(if (lang == "en") "Windows move only while parked." else "창문은 차가 서 있을 때만 움직여요.")
            return
        }
        app.vehicle.setWindows(WINDOW_OPEN)
        ui.update { it.copy(windowOpened = true) }
        recorder.event("window", spot.id, spot.name.pick(lang))
        if (ui.value.demoMode && spot.ambience == "waves") sound.waves()
        companion.send("window", JSONObject().put("placeId", spot.id).put("open", true))
    }

    fun closeWindows() {
        app.vehicle.setWindows(0)
        sound.stopWaves()
        ui.update { it.copy(windowOpened = false) }
    }

    // ---------------------------------------------------------------- 내비게이션

    private fun observeNavi() {
        app.scope.launch {
            app.navi.state.collect { n ->
                if (ui.value.demoMode || ui.value.phase != Phase.DRIVING) return@collect
                val d = if (n.status == "ROUTE_DRIVE") n.tbtDistanceM else null
                ui.update { it.copy(guideDistanceM = d, guideFromNavi = d != null) }
                updateGuidanceHold(d)
            }
        }
        // 단속 카메라, 어린이 보호구역 앞에서는 쉬고, 남은 길이 많이 막히면 긴 이야기로
        app.scope.launch {
            app.navi.state.collect { n ->
                if (ui.value.phase != Phase.DRIVING) return@collect
                val camera = n.cameraAheadM?.let { it in 0..CAUTION_HOLD_M } == true
                val school = n.cautionAheadM?.let { it in 0..CAUTION_HOLD_M } == true && n.cautionType?.contains("SCHOOL") == true
                updateCautionHold(camera || school)
                if (!ui.value.demoMode && (n.jamTimeS ?: 0L) >= 300) setJam(true, "내비 교통 정보")
            }
        }
        app.scope.launch {
            app.navi.events.collect { e ->
                when (e) {
                    is NaviBridge.Event.Arrived -> if (ui.value.phase == Phase.DRIVING) {
                        recorder.event("arrive", null, e.name ?: "")
                        toast(if (lang == "en") "Arrived. Tap 'End trip' to see today's record." else "도착했어요. [여행 끝]을 누르면 오늘 기록을 보여 드려요.")
                    }
                    else -> {}
                }
            }
        }
    }

    /** 다음 길 안내가 가까우면 잠시 멈추고, 지나가면 이어서 */
    private fun updateGuidanceHold(dist: Double?) {
        val near = dist != null && dist in 0.0..guideHoldM
        if (near && !player.isHeld(HOLD_GUIDE)) {
            guideJob?.cancel()
            GLog.i("Trip", "길 안내 " + dist?.toInt() + "m 앞, 이야기 잠시 멈춤")
            player.hold(HOLD_GUIDE)
        } else if (!near && player.isHeld(HOLD_GUIDE) && guideJob?.isActive != true) {
            guideJob = app.scope.launch {
                delay(1500)
                GLog.i("Trip", "길 안내 지남, 이야기 이어 감")
                player.release(HOLD_GUIDE)
            }
        }
    }

    // ---------------------------------------------------------------- 실제 위치 (실차)

    private fun observeLocation() {
        app.scope.launch {
            app.location.lastFix.collect { fix ->
                if (fix == null || ui.value.demoMode) return@collect
                lastFix?.let { (t0, p0) ->
                    val dt = (fix.time - t0) / 1000.0
                    if (dt > 0.5) speedMs = Geo.distanceM(p0, fix.pos) / dt
                }
                lastFix = fix.time to fix.pos
                trackSpeed(speedMs * 3.6)
                onPosition(fix.pos, fix.heading.toDouble())
            }
        }
        app.scope.launch {
            app.location.transitions.collect { t ->
                if (ui.value.demoMode || !t.enter) return@collect
                val place = scenario?.findPlace(t.placeId) ?: return@collect
                val pos = ui.value.carPos ?: place.pos
                onEnter(place, pos, ui.value.heading)
            }
        }
    }

    // ---------------------------------------------------------------- 여행 그룹

    private fun observeCompanion() {
        app.scope.launch {
            companion.incoming.collect { msg ->
                when (msg.optString("type")) {
                    "pick" -> onPickMessage(msg)
                    "phone_joined" -> toast(if (lang == "en") "A phone joined the trip group." else "휴대폰 한 대가 여행 그룹에 들어왔어요.")
                    "car_joined" -> toast(if (lang == "en") "${msg.optString("name")} joined the group." else "${msg.optString("name")}가 그룹에 들어왔어요.")
                    "report" -> {
                        recorder.event("report", msg.optString("placeId"), msg.optString("name"), msg.optString("note"))
                        toast(if (lang == "en") "A passenger reported a wrong fact. Thanks!" else "틀린 내용 신고가 들어왔어요. 다음 여행 전에 고칠게요.")
                    }
                    "quiz_answer" -> onQuizAnswer(msg)
                }
            }
        }
    }

    private fun onPickMessage(msg: JSONObject) {
        if (ui.value.phase != Phase.DRIVING && ui.value.phase != Phase.ROLLCALL) return
        val fromCar = msg.optBoolean("fromCar")
        if (fromCar && msg.optString("from") == ui.value.carName) return
        val pick = Pick(
            id = msg.optString("id"),
            name = msg.optString("name"),
            pos = LatLng(msg.optDouble("lat"), msg.optDouble("lon")),
            kind = msg.optString("kind"),
            by = msg.optString("by").ifBlank { msg.optString("from") },
            fromCar = fromCar,
        )
        ui.update { it.copy(pick = pick) }
        say(
            PlaceKindSystem.NOTICE,
            when {
                lang == "en" && pick.fromCar -> "${pick.by} sent ${spokenName(pick.name)}. Tap confirm to add it to navigation."
                lang == "en" -> "${pick.by} picked ${spokenName(pick.name)}. Tap confirm to add it to navigation."
                pick.fromCar -> "${pick.by}에서 ${spokenName(pick.name)}${eulReul(pick.name)} 보냈어요. 화면에서 확인을 누르면 내비게이션에 넣을게요."
                else -> "${pick.by}님이 ${spokenName(pick.name)}${eulReul(pick.name)} 골랐어요. 화면에서 확인을 누르면 내비게이션에 넣을게요."
            },
            front = true,
            title = if (lang == "en") "Picked place" else "고른 장소",
        )
    }

    fun answerPick(mode: String) {
        val p = ui.value.pick ?: return
        ui.update { it.copy(pick = null) }
        if (mode == "later") return
        if (mode == "waypoint") app.navi.addWaypoint(p.name, p.pos) else app.navi.routeTo(p.name, p.pos)
        recorder.event("pick", null, p.name, p.by)
        companion.send("pick_ack", JSONObject().put("id", p.id).put("name", p.name).put("mode", mode).put("accepted", true))
        toast(if (lang == "en") "${p.name} is now in navigation." else "${p.name}${eulReul(p.name)} 내비게이션에 넣었어요.")
        bringToFrontSoon()
    }

    /** 이 차가 고른 곳(예: 먼저 도착한 차의 주차장)을 그룹의 다른 차에 보냄 */
    fun sharePlace(place: Place) {
        companion.send(
            "pick",
            JSONObject().put("id", place.id).put("name", place.name.pick(lang)).put("lat", place.pos.lat).put("lon", place.pos.lon)
                .put("kind", place.kind.name.lowercase()).put("by", ui.value.carName).put("fromCar", true),
        )
        recorder.event("share", place.id, place.name.pick(lang))
        toast(if (lang == "en") "Sent ${place.name.pick(lang)} to the other cars." else "${place.name.pick(lang)}${eulReul(place.name.pick(lang))} 그룹의 다른 차에 보냈어요.")
    }

    // ---------------------------------------------------------------- 말로 묻기 ("저거 뭐야?")

    /** [물어보기] 버튼: 차 마이크로 한 문장을 듣고 답함. 듣는 중에 다시 누르면 그만 들음 */
    fun ask() {
        val u = ui.value
        if (u.phase != Phase.DRIVING && u.phase != Phase.ROLLCALL) return
        if (u.listening) {
            app.listen.cancel()
            return
        }
        beginListening()
        app.listen.listen(lang, { onHeard(it) }, { onNotHeard(it) })
    }

    /** 녹음해 둔 질문(PCM 16kHz 모노)을 같은 음성 인식 SDK 에 넣어 똑같이 처리함. 마이크가 없는 에뮬레이터 시연용 */
    fun askWithAudio(pcm: ByteArray) {
        val u = ui.value
        if (u.phase != Phase.DRIVING && u.phase != Phase.ROLLCALL) return
        beginListening()
        app.listen.feed(pcm, lang, { onHeard(it) }, { onNotHeard(it) })
    }

    /**
     * 시연: 녹음한 질문으로 묻기. name 을 주면 그 질문, 없으면 목록의 다음 질문.
     * 앱 폴더(files/ask)에 같은 이름 파일이 있으면 그걸, 없으면 앱에 넣어 둔 녹음(assets/ask)을 씀
     */
    fun askDemo(name: String? = null) {
        val list = demoQuestions[lang] ?: demoQuestions.getValue("ko")
        val file = name?.removeSuffix(".pcm") ?: list[ui.value.demoQuestionIdx % list.size].file
        val bytes = runCatching { java.io.File(app.filesDir, "ask/$file.pcm").takeIf { it.exists() }?.readBytes() }.getOrNull()
            ?: runCatching { app.assets.open("ask/$file.pcm").use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            GLog.w("STT", "질문 파일 없음: $file")
            return
        }
        if (name == null) ui.update { it.copy(demoQuestionIdx = it.demoQuestionIdx + 1) }
        GLog.i("Ask", "녹음한 질문 넣기: $file")
        askWithAudio(bytes)
    }

    private fun beginListening() {
        if (ui.value.listening) app.listen.cancel()
        // 듣는 동안에는 이야기를 멈춰 차 소리가 질문에 섞이지 않게 함. 끊긴 문장은 답이 끝난 뒤 다시 읽음
        player.hold(HOLD_LISTEN)
        sound.listen()
        ui.update { it.copy(listening = true, heardText = null) }
    }

    private fun onNotHeard(reason: String) {
        ui.update { it.copy(listening = false) }
        GLog.i("Ask", "못 알아들음: $reason")
        if (reason != "CANCELLED") {
            reply(
                if (lang == "en") "Sorry, I didn't catch that. Try asking, what's that on the right?" else "잘 못 들었어요. 오른쪽에 보이는 거 뭐야, 하고 물어봐 주세요.",
                if (lang == "en") "Ask me" else "물어보기",
                share = false,
            )
        }
        player.release(HOLD_LISTEN)
    }

    /** 알아들은 말을 의도별로 처리함 (마이크, 녹음 파일, 시험 명령 모두 여기로 옴) */
    fun onHeard(text: String) {
        sound.heard()
        ui.update { it.copy(listening = false, heardText = text, heardSeq = it.heardSeq + 1, asked = it.asked + 1) }
        val q = Ask.parse(text)
        GLog.i("Ask", "질문 [$text] 의도 ${q.intent} ${q.side?.name ?: ""}")
        val en = lang == "en"
        when (q.intent) {
            Ask.Intent.WHAT -> answerWhat(text, q.side)
            Ask.Intent.WHERE -> reply(whereAreWe(), if (en) "Where we are" else "지금 여기는")
            Ask.Intent.REPLAY -> if (!player.replay()) reply(if (en) "Nothing to replay yet." else "아직 다시 들려 드릴 이야기가 없어요.", if (en) "Replay" else "다시 듣기")
            Ask.Intent.NEXT -> player.skip()
            Ask.Intent.PAUSE -> {
                player.hold(HOLD_USER)
                toast(if (en) "Paused. Say continue, or press play on the wheel." else "멈췄어요. 계속, 이라고 말하거나 운전대 버튼을 누르면 이어 가요.")
            }
            Ask.Intent.RESUME -> player.release(HOLD_USER)
            Ask.Intent.REMAIN -> reply(remainingText(), if (en) "Time to go" else "남은 길")
            Ask.Intent.QUIZ -> askQuiz()
            Ask.Intent.REST -> findRest(byVoice = true, toilet = Regex("화장실|restroom|bathroom|toilet", RegexOption.IGNORE_CASE).containsMatchIn(text))
            Ask.Intent.CHARGE -> chargeStatus()
            Ask.Intent.HOT -> changeTemp(-1f)
            Ask.Intent.COLD -> changeTemp(1f)
            Ask.Intent.WINDOW_OPEN -> voiceWindow(true)
            Ask.Intent.WINDOW_CLOSE -> voiceWindow(false)
            Ask.Intent.KID_ON -> setKidMode(true, speak = true)
            Ask.Intent.KID_OFF -> setKidMode(false, speak = true)
            Ask.Intent.SHARE_PARKING -> voiceShare(PlaceKind.PARKING)
            Ask.Intent.SHARE_CAFE -> voiceShare(PlaceKind.CAFE)
            Ask.Intent.UNKNOWN -> reply(
                if (en) "I'm not sure what you mean. Try, what's that on the right?" else "무슨 말인지 잘 모르겠어요. 오른쪽에 보이는 거 뭐야, 하고 물어봐 주세요.",
                if (en) "Ask me" else "물어보기",
            )
        }
        recorder.event("ask", null, text, q.intent.name)
        player.release(HOLD_LISTEN)
    }

    /** 답을 지금 문장 다음에 끼워 말하고, 끝나면 하던 이야기로 돌아감. 휴대폰에도 질문과 답을 같이 띄움 */
    private fun reply(text: String, title: String, placeId: String? = null, share: Boolean = true) {
        if (text.isBlank()) return
        player.interject(
            StoryPlayer.Item(
                key = "ans-${System.nanoTime()}", placeId = null, title = title, lang = lang,
                sentences = StoryPlayer.sentences(text), kind = StoryPlayer.Kind.ANSWER,
            )
        )
        ui.update { it.copy(answerText = text, answerTitle = title) }
        if (share) {
            companion.send(
                "qa",
                JSONObject().put("q", ui.value.heardText ?: "").put("a", text).put("title", title).put("placeId", placeId ?: "").put("at", System.currentTimeMillis()),
            )
        }
    }

    private fun answerWhat(question: String, side: Ask.Side?) {
        val sc = scenario ?: return
        val car = ui.value.carPos
        if (car == null) {
            reply(if (lang == "en") "I don't know where we are yet." else "아직 차 위치를 몰라서 답하기 어려워요.", if (lang == "en") "Out the window" else "창밖")
            return
        }
        val next = ui.value.nextPlace?.let { p -> ui.value.nextDistanceM?.let { d -> p.name.pick(lang) to d } }
        // 지금 들려주고 있는 명소를 물어보면 같은 이야기를 되풀이하지 않음
        val telling = player.current?.takeIf { it.kind != StoryPlayer.Kind.ANSWER && it.kind != StoryPlayer.Kind.SYSTEM }?.placeId
        val a = Ask.whatIsThere(sc, car, ui.value.heading, side, lang, next, telling)
        GLog.i("Ask", "답: ${a.targetName ?: "보이는 곳 없음"} ${a.side ?: ""} ${a.distanceM?.toInt()?.let { "${it}m" } ?: ""}")
        reply(a.text, a.targetName ?: (if (lang == "en") "Out the window" else "창밖"), a.targetId)
        if (a.targetId != null) recorder.event("qa", a.targetId, a.targetName ?: "", question)
    }

    private fun whereAreWe(): String {
        val sc = scenario ?: return ""
        val car = ui.value.carPos
        val city = sc.places.firstOrNull { it.kind == PlaceKind.CITY && car != null && Geo.distanceM(car, it.pos) <= it.radiusM }
        val last = entered.lastOrNull { id -> id !in skippedInDemo && sc.findPlace(id)?.kind != PlaceKind.CITY }?.let { sc.findPlace(it) }
        val next = ui.value.nextPlace
        val dist = ui.value.nextDistanceM
        if (lang == "en") {
            val first = if (city != null) "We're in ${city.name.pick("en")}." else "We're on the way to ${sc.destination?.name?.pick("en") ?: "our destination"}."
            val passed = last?.let { " We just passed ${it.name.pick("en")}." } ?: ""
            val ahead = if (next != null && dist != null) " Next is ${next.name.pick("en")}, ${Ko.distance(dist, "en")} ahead." else ""
            return first + passed + ahead
        }
        val dest = sc.destination?.name?.pick("ko") ?: "목적지"
        val first = if (city != null) "지금 ${city.name.pick("ko")}에 와 있어요." else "$dest${Ko.euro(dest)} 가는 길이에요."
        val lastName = last?.name?.pick("ko")
        val nextName = next?.name?.pick("ko")
        val rest = when {
            lastName != null && nextName != null && dist != null ->
                " 방금 $lastName${eulReul(lastName)} 지났고, 다음은 $nextName${Ko.ieyo(nextName)}. ${Ko.distance(dist, "ko")} 남았어요."
            lastName != null -> " 방금 $lastName${eulReul(lastName)} 지났어요."
            nextName != null && dist != null -> " 다음은 $nextName${Ko.ieyo(nextName)}. ${Ko.distance(dist, "ko")} 남았어요."
            else -> ""
        }
        return first + rest
    }

    /** 경로를 따라 남은 거리(미터). 실차는 내비가 알려 준 값을 먼저 씀 */
    private fun remainingRouteM(): Double? {
        val n = app.navi.state.value
        val navRemain = n.remainDistanceM
        if (!ui.value.demoMode && navRemain != null) return navRemain.toDouble()
        val line = polyline ?: return null
        val car = ui.value.carPos ?: return line.length
        return max(0.0, line.length - line.project(car))
    }

    private fun remainingRouteKm(): Double? = remainingRouteM()?.div(1000.0)

    private fun remainingText(): String {
        val sc = scenario ?: return ""
        val n = app.navi.state.value
        val m = remainingRouteM() ?: 0.0
        // 시연 주행은 빨리 감기를 하므로 시내 평균 속도로 어림함
        val navSec = n.remainSeconds
        val min = if (!ui.value.demoMode && navSec != null) navSec / 60 else (m / max(speedMs, 8.3) / 60).toInt()
        val dest = sc.destination?.name?.pick(lang) ?: ""
        val via = sc.places.filter { it.triggers && it.kind != PlaceKind.CITY && it.id !in entered && it.id != sc.destinationId }.take(2).map { it.name.pick(lang) }
        return if (lang == "en") {
            "About ${Ko.minutes(max(1, min), "en")} to $dest, ${Ko.distance(m, "en")} to go." + if (via.isNotEmpty()) " On the way we'll pass ${via.joinToString(" and ")}." else ""
        } else {
            "${dest}까지 ${Ko.distance(m, "ko")}, ${Ko.minutes(max(1, min), "ko")}쯤 남았어요." +
                if (via.isNotEmpty()) " 가는 길에 ${via.joinToString(", ")}${eulReul(via.last())} 지나요." else ""
        }
    }

    private fun changeTemp(delta: Float) {
        val en = lang == "en"
        val title = if (en) "Climate" else "온도"
        val t = app.vehicle.nudgeTemperature(delta)
        if (t == null) {
            reply(if (en) "I can't change the temperature in this car." else "이 차는 앱에서 온도를 바꿀 수 없어요.", title)
            return
        }
        val deg = if (t % 1f == 0f) t.toInt().toString() else "%.1f".format(t)
        reply(
            if (delta < 0) (if (en) "I turned it down to $deg degrees." else "온도를 1도 내려 ${deg}도로 맞췄어요.")
            else (if (en) "I turned it up to $deg degrees." else "온도를 1도 올려 ${deg}도로 맞췄어요."),
            title,
        )
        recorder.event("climate", null, title, deg)
    }

    /** "다른 차에 주차장 보내 줘": 오늘 코스의 주차장(카페)을 그룹의 다른 차 내비로 보냄 */
    private fun voiceShare(kind: PlaceKind) {
        val sc = scenario ?: return
        val en = lang == "en"
        val title = if (en) "Share with the group" else "그룹에 보내기"
        val place = sc.pickables.firstOrNull { it.kind == kind }
        val others = companion.state.value.cars.count { it != ui.value.carName }
        when {
            place == null -> reply(if (en) "There's no saved spot like that on today's course." else "오늘 코스에는 그런 곳이 따로 없어요.", title)
            others == 0 && sc.cars <= 1 -> reply(if (en) "There's no other car in this group to send it to." else "같이 가는 차가 없어서 보낼 곳이 없어요.", title)
            else -> {
                sharePlace(place)
                val name = place.name.pick(lang)
                reply(if (en) "I sent $name to the other cars. Their navigation will ask to go there." else "다른 차에 $name${eulReul(name)} 보냈어요. 그 차 내비게이션에서 확인만 누르면 돼요.", title, place.id)
            }
        }
    }

    private fun voiceWindow(open: Boolean) {
        val en = lang == "en"
        val title = if (en) "Windows" else "창문"
        if (!open) {
            closeWindows()
            reply(if (en) "Closing the windows." else "창문을 닫을게요.", title)
            return
        }
        if (!app.vehicle.state.value.parked) {
            reply(if (en) "I'll open the windows once we've stopped." else "창문은 차가 서 있을 때 열어 드릴게요.", title)
            return
        }
        app.vehicle.setWindows(WINDOW_OPEN)
        ui.update { it.copy(windowOpened = true) }
        reply(if (en) "I lowered the windows a little." else "창문을 한 뼘 내렸어요.", title)
    }

    // ---------------------------------------------------------------- 뒷자리 아이, 퀴즈, 보물 상자

    private fun rearOccupied(v: VehicleBridge.State = app.vehicle.state.value) = v.occupied["ROW_2_LEFT"] == true || v.occupied["ROW_2_RIGHT"] == true

    fun setKidMode(on: Boolean, speak: Boolean = false) {
        ui.update { it.copy(kidMode = on, kidManual = on) }
        GLog.i("Trip", "아이 모드 ${if (on) "켬" else "끔"}")
        if (speak) {
            reply(
                if (lang == "en") (if (on) "Kid mode on. After each story, there's a short one for the back seat." else "Kid mode off.")
                else (if (on) "아이 모드를 켰어요. 명소 이야기가 끝나면 뒷자리 친구들 이야기를 짧게 해 줄게요." else "아이 모드를 껐어요."),
                if (lang == "en") "Kid mode" else "아이 모드",
            )
        }
    }

    fun toggleKidMode() = setKidMode(!ui.value.kidMode)

    /** 명소 이야기가 끝나면: 아이 모드면 뒷자리 친구들 이야기를 바로 이어서, 그리고 휴대폰으로 퀴즈 */
    private fun afterStory(place: Place, completed: Boolean) {
        val sc = scenario ?: return
        if (ui.value.phase != Phase.DRIVING) return
        val hasQuiz = place.id in sc.quiz && place.id !in quizSentFor
        val phones = companion.state.value.phones > 0
        val kidLine = if (ui.value.kidMode && completed) sc.kid[place.id]?.pick(lang).orEmpty() else ""
        if (kidLine.isNotBlank()) {
            val tail = if (hasQuiz && phones) (if (lang == "en") " I sent a quiz to your phones, too." else " 휴대폰으로 퀴즈도 보냈어요.") else ""
            player.enqueueFront(
                StoryPlayer.Item(
                    key = "kid-${place.id}", placeId = place.id, title = if (lang == "en") "For the back seat" else "뒷자리 친구들에게", lang = lang,
                    sentences = StoryPlayer.sentences(kidLine + tail), kind = StoryPlayer.Kind.KID,
                    stale = { passed(place, place.radiusM * 3.0) },
                    onStart = {
                        recorder.event("kid", place.id, place.name.pick(lang))
                        companion.send("kid", JSONObject().put("placeId", place.id).put("text", kidLine))
                    },
                )
            )
        }
        if (hasQuiz) sendQuiz(place.id)
    }

    private fun sendQuiz(placeId: String): Boolean {
        val sc = scenario ?: return false
        val q = sc.quiz[placeId] ?: return false
        if (!quizSentFor.add(placeId)) return false
        val name = sc.findPlace(placeId)?.name?.pick(lang) ?: ""
        // 보기 순서를 섞어 "늘 1번이 정답"이 되지 않게 함
        val base = q.choices(lang)
        val order = base.indices.shuffled()
        val answer = order.indexOf(q.answer)
        companion.send(
            "quiz",
            JSONObject().put("id", placeId).put("name", name).put("q", q.question.pick(lang)).put("choices", JSONArray(order.map { base[it] }))
                .put("answer", answer).put("lang", lang).put("stars", ui.value.stars).put("need", ui.value.starsNeeded),
        )
        recorder.event("quiz", placeId, name)
        ui.update { it.copy(quizSent = quizSentFor.size) }
        GLog.i("Quiz", "퀴즈 보냄: $name")
        return true
    }

    private fun askQuiz() {
        val sc = scenario ?: return
        val en = lang == "en"
        val title = if (en) "Quiz" else "퀴즈"
        if (companion.state.value.phones == 0) {
            reply(if (en) "Connect a phone first. When we stop, scan the QR code on the screen." else "휴대폰을 먼저 연결해 주세요. 차가 서 있을 때 화면의 QR을 찍으면 돼요.", title)
            return
        }
        // 이야기를 들은 명소의 퀴즈부터, 없으면 남은 아무 퀴즈
        val id = (entered.filter { it in sc.quiz } + sc.quiz.keys).firstOrNull { it !in quizSentFor }
        if (id != null && sendQuiz(id)) {
            val name = sc.findPlace(id)?.name?.pick(lang) ?: ""
            reply(if (en) "I sent a quiz about $name to your phones. Get it right for a star!" else "휴대폰으로 $name 퀴즈를 보냈어요. 맞히면 별을 하나 드려요.", title)
        } else {
            val n = ui.value.stars
            reply(if (en) "That's all the quizzes for this trip. You have $n stars." else if (n == 0) "이번 여행 퀴즈는 다 냈어요." else "이번 여행 퀴즈는 다 냈어요. 지금까지 별을 ${koCount(n)} 개 모았어요.", title)
        }
    }

    /** 휴대폰에서 퀴즈를 맞히면 그룹 서버가 알려 줌. 같은 퀴즈는 별 하나만 */
    private fun onQuizAnswer(msg: JSONObject) {
        if (ui.value.phase == Phase.HOME) return
        val id = msg.optString("id")
        if (!msg.optBoolean("correct") || !quizCorrect.add(id)) return
        val by = msg.optString("by").trim().takeIf { it.isNotBlank() && it != "동승자" && it != "Passenger" }
        val stars = quizCorrect.size
        val name = scenario?.findPlace(id)?.name?.pick(lang) ?: ""
        recorder.event("quiz_ok", id, name, by ?: "")
        sound.star()
        val need = ui.value.starsNeeded
        val ready = need > 0 && stars >= need && ui.value.treasure == Treasure.COLLECTING
        ui.update { it.copy(stars = stars, lastStarBy = by, treasure = if (ready) Treasure.READY else it.treasure) }
        val parked = ui.value.phase == Phase.DRIVING && parkedSince != null
        val en = lang == "en"
        val who = if (en) (by?.let { "$it got it right!" } ?: "Correct!") else (by?.let { "$it 님 정답이에요!" } ?: "정답이에요!")
        val line = when {
            ready && parked -> if (en) "$who That's all $stars stars." else "$who 별을 ${koCount(stars)} 개 다 모았어요."
            ready -> if (en) "$who That's all $stars stars. When we park, I'll open the treasure box." else "$who 별을 다 모았어요. 차를 세우면 보물 상자를 열어 드릴게요."
            else -> if (en) "$who That's star number $stars." else "$who 별 ${koCount(stars)} 개째예요."
        }
        say(PlaceKindSystem.NOTICE, line, interject = true, title = if (en) "Quiz" else "퀴즈 정답")
        companion.send("stars", JSONObject().put("stars", stars).put("need", need).put("ready", ready).put("by", by ?: ""))
        if (ready && parked) maybeOfferTreasure()
    }

    /** 별을 다 모았고 차가 서 있으면 보물 상자(글로브 박스)를 열지 물어봄 */
    private fun maybeOfferTreasure(): Boolean {
        val u = ui.value
        if (u.treasure != Treasure.READY || u.offer != null) return false
        if (!app.vehicle.state.value.parked) return false
        val en = lang == "en"
        ui.update {
            it.copy(
                offer = Offer(
                    OfferKind.TREASURE,
                    if (en) "Treasure box" else "보물 상자",
                    if (en) "You collected ${u.stars} stars! Shall I open the glove box in front of the passenger seat?" else "퀴즈 별을 ${u.stars}개 모았어요! 조수석 앞 글로브 박스를 열어 볼까요?",
                    if (en) "Open it" else "열어 주세요",
                    if (en) "Later" else "나중에",
                )
            )
        }
        say(
            PlaceKindSystem.NOTICE, if (en) "You collected all the stars. Shall I open the treasure box?" else "별을 다 모았네요. 보물 상자를 열어 볼까요?",
            interject = true, stale = { ui.value.offer?.kind != OfferKind.TREASURE }, title = if (en) "Treasure box" else "보물 상자",
        )
        return true
    }

    private fun openTreasure() {
        val v = app.vehicle.state.value
        val en = lang == "en"
        if (!v.parked) {
            toast(if (en) "The glove box opens only while parked." else "글로브 박스는 차가 서 있을 때만 열어요.")
            return
        }
        if (gloveWasLocked == null) gloveWasLocked = v.gloveLocked
        val unlocked = app.vehicle.setGloveBoxLock(false)
        ui.update { it.copy(treasure = Treasure.OPEN) }
        sound.fanfare()
        recorder.event("treasure", null, if (en) "Treasure box" else "보물 상자", ui.value.stars.toString())
        companion.send("treasure", JSONObject().put("stars", ui.value.stars).put("unlocked", unlocked))
        // 상자가 열리는 동안 실내를 금빛으로
        setAmbient(0xFFFFC94D.toInt(), 95)
        app.scope.launch {
            delay(7000)
            if (ui.value.phase == Phase.DRIVING) setAmbient(regionColor, CRUISE_BRIGHTNESS)
        }
        say(
            PlaceKindSystem.NOTICE,
            when {
                unlocked && en -> "The treasure box is open! Look in the glove box in front of the passenger seat."
                unlocked -> "보물 상자가 열렸어요! 조수석 앞 글로브 박스를 열어 보세요."
                en -> "Treasure time! This car doesn't let apps unlock the glove box, so open it by hand."
                else -> "보물 상자 시간이에요! 이 차는 앱으로 잠금을 풀 수 없어서, 글로브 박스를 직접 열어 보세요."
            },
            interject = true,
            title = if (en) "Treasure box" else "보물 상자",
        )
    }

    /** 보물 상자를 열 때 잠겨 있었으면 달리기 시작할 때 다시 잠금 */
    private fun restoreGlove() {
        val was = gloveWasLocked ?: return
        gloveWasLocked = null
        if (was) app.vehicle.setGloveBoxLock(true)
    }

    // ---------------------------------------------------------------- 차가 먼저 권하는 것 (쉬어 가기, 충전, 보물 상자, 하늘 열기)

    fun answerOffer(yes: Boolean) {
        val o = ui.value.offer ?: return
        ui.update { it.copy(offer = null) }
        GLog.i("Trip", "제안 ${o.kind}: ${if (yes) "예" else "아니요"}")
        if (!yes) return
        when (o.kind) {
            OfferKind.REST -> findRest(byVoice = false, toilet = false)
            OfferKind.CHARGE -> openChargers()
            OfferKind.TREASURE -> openTreasure()
            OfferKind.SUNROOF -> openSunroof(o.placeId)
        }
    }

    private fun checkCare(v: VehicleBridge.State) {
        if (ui.value.phase != Phase.DRIVING || ui.value.offer != null) return
        val now = System.currentTimeMillis()
        val remainKm = remainingRouteKm()
        val soc = v.socPct
        val range = v.rangeKm
        val short = range != null && remainKm != null && range < remainKm + RANGE_MARGIN_KM
        if (((soc != null && soc <= LOW_SOC) || short) && now - lastChargeOfferAt > 30 * 60_000) {
            lastChargeOfferAt = now
            offerCharge(v, remainKm)
            return
        }
        val min = v.drivingMin ?: return
        if (min >= REST_AFTER_MIN && now - lastRestOfferAt > 60 * 60_000) {
            lastRestOfferAt = now
            offerRest(min)
        }
    }

    private fun appDrivingMin(): Int = (driveAccumMs / 60_000).toInt() + driveOffsetMin

    private fun drivingNow(): Boolean {
        val v = app.vehicle.state.value
        return v.gear == "D" || v.gear == "R" || (ui.value.demoMode && demo.running.value)
    }

    private fun tickDrivingTime(stepMs: Long) {
        if (ui.value.phase != Phase.DRIVING) return
        if (drivingNow()) driveAccumMs += stepMs
        val v = app.vehicle.state.value
        val min = v.drivingMin ?: appDrivingMin()
        if (ui.value.drivingMin != min) ui.update { it.copy(drivingMin = min) }
        checkCare(v.copy(drivingMin = min))
    }

    /** 시연: 오래 달린 상황을 만듦 (앱이 재는 주행 시간을 min 분으로 맞춤) */
    fun setDemoDrivingTime(min: Int) {
        driveOffsetMin = min - (driveAccumMs / 60_000).toInt()
        GLog.i("Trip", "시연: 주행 시간 ${min}분으로 맞춤")
        tickDrivingTime(0)
    }

    private fun offerRest(min: Int) {
        val en = lang == "en"
        val body = if (en) "We've been driving for ${Ko.minutes(min, "en")}. Shall we take a break at a rest area?" else "${Ko.minutes(min, "ko")}째 달리고 있어요. 휴게소에서 잠깐 쉬어 갈까요?"
        ui.update { it.copy(offer = Offer(OfferKind.REST, if (en) "Time for a break" else "쉬어 갈 시간", body, if (en) "Find a rest area" else "휴게소 찾기", if (en) "Not now" else "괜찮아요")) }
        say(PlaceKindSystem.NOTICE, body, interject = true, stale = { ui.value.offer?.kind != OfferKind.REST }, title = if (en) "Time for a break" else "쉬어 갈 시간")
        recorder.event("rest", null, if (en) "Break suggested" else "쉬어 가자고 함", min.toString())
    }

    private fun chargeLine(v: VehicleBridge.State, remainKm: Double?): String {
        val en = lang == "en"
        val range = v.rangeKm
        return when {
            range != null && remainKm != null && range < remainKm ->
                if (en) "It's ${remainKm.toInt()} km to go, but the battery is good for only about ${range.toInt()} km. We need to charge on the way."
                else "목적지까지 ${remainKm.toInt()}킬로미터 남았는데, 지금 배터리로는 ${range.toInt()}킬로미터밖에 못 가요. 가는 길에 충전해야 해요."
            range != null && remainKm != null && range < remainKm + RANGE_MARGIN_KM ->
                if (en) "When we arrive, only about ${(range - remainKm).toInt()} km of range will be left. Shall we charge on the way?"
                else "목적지에 닿으면 갈 수 있는 거리가 ${(range - remainKm).toInt()}킬로미터쯤만 남아요. 가는 길에 충전하고 갈까요?"
            v.socPct != null -> if (en) "The battery is at ${v.socPct}%. Shall I show the chargers nearby?" else "배터리가 ${v.socPct}% 남았어요. 가까운 충전소를 띄워 드릴까요?"
            else -> if (en) "Shall I show the chargers nearby?" else "가까운 충전소를 띄워 드릴까요?"
        }
    }

    private fun offerCharge(v: VehicleBridge.State, remainKm: Double?) {
        val en = lang == "en"
        val body = chargeLine(v, remainKm)
        ui.update { it.copy(offer = Offer(OfferKind.CHARGE, if (en) "Charging" else "충전", body, if (en) "Show chargers" else "충전소 보기", if (en) "Not now" else "괜찮아요")) }
        say(PlaceKindSystem.NOTICE, body, interject = true, stale = { ui.value.offer?.kind != OfferKind.CHARGE }, title = if (en) "Charging" else "충전")
        recorder.event("charge", null, if (en) "Charging suggested" else "충전하자고 함", "${v.socPct ?: ""}")
    }

    /** "배터리 얼마나 남았어?" */
    private fun chargeStatus() {
        val v = app.vehicle.state.value
        val en = lang == "en"
        val title = if (en) "Battery" else "배터리"
        val soc = v.socPct
        val range = v.rangeKm
        if (soc == null && range == null) {
            reply(if (en) "I can't read the battery in this car." else "이 차에서는 배터리 정보를 읽을 수 없어요.", title)
            return
        }
        val remainKm = remainingRouteKm()
        val enough = range == null || remainKm == null || range >= remainKm + RANGE_MARGIN_KM
        val head = when {
            en -> listOfNotNull(soc?.let { "The battery is at $it%" }, range?.let { "good for about ${it.toInt()} km" }).joinToString(", ") + "."
            soc != null && range != null -> "배터리는 ${soc}% 남았고, 약 ${range.toInt()}킬로미터 갈 수 있어요."
            soc != null -> "배터리는 ${soc}% 남았어요."
            else -> "약 ${range!!.toInt()}킬로미터 갈 수 있어요."
        }
        if (enough) {
            reply(head + if (remainKm != null) (if (en) " That's plenty to reach our destination." else " 목적지까지 넉넉해요.") else "", title)
        } else {
            lastChargeOfferAt = System.currentTimeMillis()
            val body = chargeLine(v, remainKm)
            reply("$head $body", title)
            ui.update { it.copy(offer = Offer(OfferKind.CHARGE, if (en) "Charging" else "충전", body, if (en) "Show chargers" else "충전소 보기", if (en) "Not now" else "괜찮아요")) }
        }
    }

    private fun openChargers() {
        val ok = app.navi.openChargers()
        recorder.event("charge_nav", null, if (lang == "en") "Chargers nearby" else "가까운 충전소")
        toast(if (ok) (if (lang == "en") "Showing chargers nearby in the navigation." else "내비게이션에 가까운 충전소를 띄웠어요.") else (if (lang == "en") "Couldn't open the navigation." else "내비게이션을 열지 못했어요."))
    }

    private fun findRest(byVoice: Boolean, toilet: Boolean) {
        val en = lang == "en"
        val kw = when {
            toilet -> if (en) "restroom" else "화장실"
            else -> if (en) "rest area" else "휴게소"
        }
        val ok = app.navi.searchNearby(kw)
        lastRestOfferAt = System.currentTimeMillis()
        recorder.event("rest_nav", null, kw)
        val line = when {
            !ok -> if (en) "I couldn't open the navigation." else "내비게이션을 열지 못했어요."
            en -> "I opened nearby ${kw}s in the navigation. Pick one and we'll stop there."
            else -> "내비게이션에 가까운 $kw${eulReul(kw)} 띄웠어요. 하나 고르면 들렀다 가요."
        }
        if (byVoice) reply(line, if (en) "Break" else "쉬어 가기") else toast(line)
    }

    // ---------------------------------------------------------------- 차 안 연출: 바깥 공기, 하늘 열기

    /** 바다, 숲에서는 내기 순환을 바깥 공기로 바꿔 그곳 공기를 들임. 이미 바깥 공기면 손대지 않음 */
    private fun breatheIn(place: Place) {
        if (freshAirFor != null) return
        if (app.vehicle.state.value.recirc != true) return
        if (!app.vehicle.setRecirculation(false)) return
        freshAirFor = place.id
        val name = place.name.pick(lang)
        ui.update { it.copy(freshAir = true) }
        recorder.event("air", place.id, name)
        toast(if (lang == "en") "Letting in the air of $name. I'll switch back after we pass." else "${name}의 바깥 공기를 들이고 있어요. 지나가면 원래대로 돌려 둘게요.")
    }

    private fun breatheOut() {
        if (freshAirFor == null) return
        freshAirFor = null
        app.vehicle.setRecirculation(true)
        ui.update { it.copy(freshAir = false) }
    }

    /** 하늘과 이어지는 명소(첨성대)에 세우면, 선루프가 있는 차에서만 하늘을 열어 보자고 물어봄 */
    private fun maybeAskSunroof() {
        val sc = scenario ?: return
        if (app.vehicle.state.value.sunroof != true || ui.value.offer != null) return
        val pos = ui.value.carPos ?: return
        val spot = sc.places.firstOrNull { it.sky && Geo.distanceM(pos, it.pos) <= it.radiusM * 1.5 && it.id !in sunroofAskedFor } ?: return
        sunroofAskedFor += spot.id
        val en = lang == "en"
        val name = spot.name.pick(lang)
        val body = if (en) "$name is where people once watched the sky. Shall I open the sunroof so we can look up too?" else "$name${Ko.eunNeun(name)} 옛사람들이 하늘을 보던 곳이에요. 선루프를 열어 우리도 하늘을 볼까요?"
        ui.update { it.copy(offer = Offer(OfferKind.SUNROOF, if (en) "Open the sky" else "하늘 열기", body, if (en) "Open sunroof" else "선루프 열기", if (en) "No thanks" else "괜찮아요", spot.id)) }
        say(PlaceKindSystem.NOTICE, body, interject = true, stale = { ui.value.offer?.kind != OfferKind.SUNROOF }, title = if (en) "Open the sky" else "하늘 열기")
    }

    private fun openSunroof(placeId: String?) {
        if (!app.vehicle.state.value.parked) {
            toast(if (lang == "en") "The sunroof opens only while parked." else "선루프는 차가 서 있을 때만 열어요.")
            return
        }
        if (app.vehicle.setSunroof(100)) {
            ui.update { it.copy(sunroofOpen = true) }
            recorder.event("sunroof", placeId, scenario?.findPlace(placeId)?.name?.pick(lang) ?: "")
            setAmbient(0xFF6C5CE7.toInt(), 35)
        }
    }

    private fun closeSunroof() {
        if (!ui.value.sunroofOpen) return
        app.vehicle.setSunroof(0)
        ui.update { it.copy(sunroofOpen = false) }
    }

    /** 여행이 끝나거나 새로 시작할 때 앱이 바꾼 것을 원래대로: 공조, 글로브 박스, 선루프 */
    private fun restoreCabin() {
        breatheOut()
        restoreGlove()
        closeSunroof()
    }

    // ---------------------------------------------------------------- 길 상황: 막히는 길, 주의 지점

    /** 시연: 길이 막히는 상황을 흉내 냄 (시연 주행 속도를 시속 9km로) */
    fun setDemoJam(on: Boolean) {
        demo.cityKmh = if (on) DEMO_JAM_KMH else DEMO_KMH
        ui.update { it.copy(demoJam = on) }
        if (!on) {
            slowSince = null
            setJam(false, "시연")
        }
    }

    /** 시속 15km 아래로 12초 넘게 달리면 막히는 길, 25km 넘게 10초 달리면 풀린 것으로 봄 (신호 대기 정차는 빼고) */
    private fun trackSpeed(kmh: Double) {
        if (ui.value.phase != Phase.DRIVING) return
        val now = System.currentTimeMillis()
        if (kmh in 0.5..JAM_KMH) {
            fastSince = null
            val since = slowSince ?: now.also { slowSince = it }
            if (now - since > 12_000) setJam(true, "속도")
        } else if (kmh > JAM_KMH + 10) {
            slowSince = null
            val since = fastSince ?: now.also { fastSince = it }
            if (now - since > 10_000) setJam(false, "속도")
        }
    }

    private fun setJam(on: Boolean, why: String) {
        if (ui.value.jam == on) return
        ui.update { it.copy(jam = on) }
        GLog.i("Trip", if (on) "막히는 길($why), 이야기를 길게" else "막힘 풀림($why)")
        if (on && !jamAnnounced) {
            jamAnnounced = true
            recorder.event("jam", null, if (lang == "en") "Traffic" else "막히는 길")
            say(
                PlaceKindSystem.NOTICE,
                if (lang == "en") "Traffic is slow. While it's like this, I'll tell the longer versions of the stories." else "길이 막히네요. 막히는 동안에는 이야기를 길게 들려 드릴게요.",
                interject = true,
                title = if (lang == "en") "Traffic" else "막히는 길",
            )
        }
    }

    /** 단속 카메라와 어린이 보호구역 앞에서는 이야기를 잠시 쉼 */
    private fun updateCautionHold(near: Boolean) {
        if (near && !player.isHeld(HOLD_CAUTION)) {
            cautionJob?.cancel()
            GLog.i("Trip", "단속 카메라 또는 어린이 보호구역 앞, 이야기 잠시 멈춤")
            player.hold(HOLD_CAUTION)
        } else if (!near && player.isHeld(HOLD_CAUTION) && cautionJob?.isActive != true) {
            cautionJob = app.scope.launch {
                delay(1500)
                player.release(HOLD_CAUTION)
            }
        }
    }

    /** 차를 세우면 하던 이야기의 남은 부분을 휴대폰에서 이어 들을 수 있게 보냄 */
    private fun handOffToPhones() {
        val cur = player.current ?: return
        if (cur.kind != StoryPlayer.Kind.STORY || cur.placeId == null) return
        val rest = cur.sentences.drop(player.index).joinToString(" ")
        if (rest.isBlank()) return
        companion.send("handoff", JSONObject().put("placeId", cur.placeId).put("name", cur.title).put("text", rest).put("lang", cur.lang))
        GLog.i("Trip", "휴대폰으로 이어 듣기 넘김: ${cur.title}")
    }

    // ---------------------------------------------------------------- 운전대 버튼과 재생 조작

    fun onMediaCommand(c: MediaKeys.Command) {
        when (c) {
            MediaKeys.Command.NEXT -> player.skip()
            MediaKeys.Command.PREVIOUS -> if (!player.replay()) toast(if (lang == "en") "Nothing to replay yet." else "다시 들을 이야기가 아직 없어요.")
            MediaKeys.Command.PAUSE -> player.hold(HOLD_USER)
            MediaKeys.Command.PLAY -> player.release(HOLD_USER)
        }
    }

    fun togglePause() = if (player.isHeld(HOLD_USER)) player.release(HOLD_USER) else player.hold(HOLD_USER)

    private fun syncPlayer() {
        val cur = player.current
        val busy = player.busy
        if (playerWasBusy && !busy) GLog.i("Player", "이야기 끝, 조용함")
        playerWasBusy = busy
        ui.update {
            it.copy(
                // 안내 문구처럼 장소가 없는 항목이 나와도 마지막 명소 영상은 그대로 둠
                nowPlace = cur?.placeId?.let { id -> scenario?.findPlace(id) } ?: it.nowPlace,
                nowKind = cur?.kind ?: it.nowKind,
                nowTitle = cur?.title ?: it.nowTitle,
                nowText = cur?.text ?: it.nowText,
                nowSentence = cur?.sentences?.getOrNull(player.index),
                sentenceIndex = player.index,
                sentenceCount = cur?.sentences?.size ?: 0,
                nowLength = cur?.length ?: if (cur == null) it.nowLength else null,
                speaking = player.speaking,
                holds = player.holdReasons.toSet(),
            )
        }
    }

    // ---------------------------------------------------------------- 조명

    private fun setAmbient(color: Int, brightness: Int) {
        app.vehicle.setAmbient(color, brightness)
        ui.update { it.copy(accent = color) }
    }

    private fun restoreAmbient() {
        val (c, b) = savedAmbient ?: (0xFFFFFFFF.toInt() to 70)
        app.vehicle.setAmbient(c, b)
        ui.update { it.copy(accent = 0xFF7FE3FF.toInt()) }
    }

    // ---------------------------------------------------------------- 기타

    fun toast(msg: String) = ui.update { it.copy(toast = msg, toastSeq = it.toastSeq + 1) }

    private fun bringToFrontSoon() {
        app.scope.launch {
            delay(5000)
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }
    }

    private fun localCode(): String = (1000..9999).random().toString()

    private fun parseColor(hex: String): Int = runCatching { Color.parseColor(hex) }.getOrDefault(0xFF7FE3FF.toInt())

    // 다시 타면 다음 장: 앱이 다시 켜져도 이어서 갈 수 있게 저장
    private fun saveTrip(joinCode: String?) {
        prefs.edit()
            .putString("trip_scenario", scenario?.id)
            .putString("trip_lang", lang)
            .putString("trip_join", joinCode)
            .putString("trip_entered", "")
            .putFloat("trip_progress", 0f)
            .apply()
    }

    private fun saveEntered() = prefs.edit().putString("trip_entered", entered.joinToString(",")).apply()

    private var lastSavedProgress = 0.0
    private fun saveProgress(m: Double) {
        if (kotlin.math.abs(m - lastSavedProgress) < 100) return
        lastSavedProgress = m
        prefs.edit().putFloat("trip_progress", m.toFloat()).apply()
    }

    private fun clearTrip() = prefs.edit().remove("trip_scenario").remove("trip_entered").remove("trip_progress").remove("trip_join").apply()

    fun resumeTrip() {
        if (prefs.getString("trip_scenario", null) == null) return
        val id = prefs.getString("trip_scenario", null) ?: return
        val l = prefs.getString("trip_lang", "ko") ?: "ko"
        ui.update { it.copy(lang = l) }
        startTrip(id, l, prefs.getString("trip_join", null), ui.value.demoMode, resume = true)
    }

    /** 이어서 가기 기록을 지움 (시연 녹화 전 초기화용) */
    fun forgetSavedTrip() {
        clearTrip()
        ui.update { it.copy(resumable = false) }
    }
}
