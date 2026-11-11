package app.gureuneun.guide.engine

import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
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
import app.gureuneun.guide.sdk.ListenBridge
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
 * 이야기를 언제, 얼마나 길게 들려줄지 스스로 고름.
 *
 * 하는 일은 여행 안내에 필요한 것만: 지나는 곳 이야기, 창밖 묻기, 휴대폰에서 고른 장소를 내비로, 아이 퀴즈와 보물 상자, 하루 기록.
 * 차를 움직이는 것은 실내 조명(저절로)과 앞 창문, 글로브 박스 잠금(P단에서 운전자가 답했을 때만)뿐.
 */
class TripEngine(private val app: GuideApp) {

    enum class Phase { HOME, ROLLCALL, DRIVING, ENDED }

    /** 동승자가 휴대폰에서 고른 곳 */
    data class Pick(val id: String, val name: String, val pos: LatLng, val kind: String, val by: String) {
        /** 휴대폰에 이름을 적지 않고 보낸 사람 */
        val anonymous: Boolean get() = by.isBlank() || by == "동승자" || by == "Passenger"
    }

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
        val startedAt: Long = 0,
        val resumable: Boolean = false,
        val beltsConfirmed: Boolean = false,
        // 말로 묻기
        val listening: Boolean = false,
        val heardText: String? = null,
        val heardSeq: Int = 0,
        /** 가족 코스에서 뒷자리에 사람이 앉아 있으면 명소 이야기 끝에 아이 이야기를 덧붙임 */
        val kidMode: Boolean = false,
        // 퀴즈와 보물 상자
        val quizSent: Int = 0,
        val stars: Int = 0,
        val starsNeeded: Int = 0,
        val treasure: Treasure = Treasure.NONE,
        val lastStarBy: String? = null,
        /** 별을 다 모으고 차를 세워 "보물 상자를 열어 볼까요?" 하고 묻는 중 */
        val treasureAsk: Boolean = false,
        /** 시연 패널의 [녹음한 질문으로 묻기]가 다음에 넣을 질문 번호 */
        val demoQuestionIdx: Int = 0,
        /** 지금 이야기하는 곳이 차의 어느 쪽에 보이는지 ("left", "right", "ahead", "behind"). 달리는 동안 화면에 크게 띄움 */
        val nowSide: String? = null,
        /** 차가 물어본 것(고른 장소, 창문, 보물 상자, 여행 끝)에 말로 답하기를 기다리는 중 */
        val asking: Boolean = false,
        /** [여행 끝]을 누르면 바로 끝내지 않고 한 번 더 물음 */
        val confirmEnd: Boolean = false,
        /** 달리는 동안 바로 고르지 않은 장소. 차가 서면 다시 보여 줌 */
        val laterPicks: List<Pick> = emptyList(),
        /** 하루 기록을 그룹 휴대폰으로 보냈는지. null 이면 아직 묻는 중 */
        val recordShared: Boolean? = null,
    )

    /** 시연용으로 앱에 넣어 둔 녹음 질문 (assets/ask, 16kHz 모노 16비트 PCM) */
    data class DemoQuestion(val file: String, val text: String)

    companion object {
        const val HOLD_TURN = "turn"
        const val HOLD_GUIDE = "guide"
        const val HOLD_USER = "user"
        const val HOLD_LISTEN = "listen"
        /** 전화가 울리거나 통화 중 */
        const val HOLD_CALL = "call"
        /** 내비게이션 길 안내 음성이 나오는 중 (실차) */
        const val HOLD_NAVVOICE = "navvoice"
        /** 0~10 가운데 2. 앞 창문만, 바람과 소리가 들어올 만큼만 내림 (뒷자리 아이가 몸을 내밀 수 없게) */
        const val WINDOW_OPEN = 2
        /** 앱이 움직이는 창문은 앞 창문 두 짝뿐. 뒷창문은 아이가 앉는 자리라 건드리지 않음 */
        val FRONT_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT)
        /** 차가 물어본 것에 이만큼 답이 없으면 달리는 중에는 접어 둠 (차가 서면 다시 보여 줌) */
        private const val PROMPT_TIMEOUT_MS = 30_000L
        // 다음 길 안내가 이만큼 가까우면 이야기를 쉼. 시연 경로에는 실제 내비 음성이 없어 짧게 둠
        private const val GUIDE_HOLD_M = 150.0
        private const val GUIDE_HOLD_DEMO_M = 40.0
        private const val CRUISE_BRIGHTNESS = 40
        /** 멈췄던 여행을 이어서 갈 수 있는 시간. 이보다 오래된 기록은 처음 화면에 띄우지 않음 */
        private const val RESUME_WINDOW_MS = 12 * 60 * 60 * 1000L
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
    // 앱이 앞 창문을 내린 시각. 창문이 움직이는 몇 초 동안 들어오는 위치 값으로 '닫힘'을 잘못 읽지 않게 함
    private var windowOpenedAt = 0L
    private var regionColor = 0xFF7FE3FF.toInt()
    private var playerWasBusy = false
    // 퀴즈와 보물 상자
    private val quizSentFor = LinkedHashSet<String>()
    private val quizCorrect = LinkedHashSet<String>()
    private var gloveWasLocked: Boolean? = null
    // 보물 상자 때 앱이 글로브 박스 잠금을 풀었는지 (풀었을 때만 다시 잠금)
    private var gloveOpenedByApp = false
    // 명소마다 지나갈 때 차의 어느 쪽에 보이는지 ("잠시 후 오른쪽에")
    private val placeSide = HashMap<String, String>()
    // 답을 기다리는 물음을 달리는 동안 오래 띄워 두지 않음
    private var promptJob: Job? = null

    val demoQuestions: Map<String, List<DemoQuestion>> = mapOf(
        "ko" to listOf(
            DemoQuestion("q_right", "오른쪽에 보이는 거 뭐야?"),
            DemoQuestion("q_remain", "얼마나 남았어?"),
            DemoQuestion("q_where", "여기 어디야?"),
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
        observeAudio()
        // 앞서 앱이 바꿔 둔 차 상태(조명, 글로브 박스 잠금)가 남아 있으면 원래대로 (앱이 갑자기 꺼졌던 경우)
        app.vehicle.restoreAll { ok -> if (!ok) GLog.w("Trip", "앞 여행에서 바꾼 차 상태를 다 되돌리지 못함 (다음에 다시 해 봄)") }
        // 에뮬레이터는 GPS가 움직이지 않아 시연 경로가 기본, 실차는 실제 위치가 기본
        ui.update { it.copy(resumable = hasSavedTrip(), demoMode = isEmulator()) }
    }

    fun attachMedia(keys: MediaKeys) {
        // 세션은 여행 중에만 켬. 여행이 아닐 때 운전대 키는 음악 앱이 받게 둠
        media = keys
    }

    /** 전화와 내비 안내 음성이 나오는 동안은 이야기를 쉼. 끝나면 끊긴 문장부터 이어 감 */
    private fun observeAudio() {
        app.scope.launch {
            app.audio.inCall.collect { holdWhile(HOLD_CALL, it || app.audio.ringing.value) }
        }
        app.scope.launch {
            app.audio.ringing.collect { holdWhile(HOLD_CALL, it || app.audio.inCall.value) }
        }
        app.scope.launch {
            app.audio.navSpeaking.collect { holdWhile(HOLD_NAVVOICE, it) }
        }
    }

    private fun holdWhile(reason: String, on: Boolean) {
        val p = ui.value.phase
        if (on && (p == Phase.DRIVING || p == Phase.ROLLCALL)) {
            if (!player.isHeld(reason)) GLog.i("Trip", "소리 겹침($reason), 이야기 잠시 멈춤")
            player.hold(reason)
        } else if (!on && player.isHeld(reason)) {
            app.scope.launch {
                // 안내 음성이 끝나고 바로 말을 시작하면 겹쳐 들리므로 잠깐 쉼
                delay(if (reason == HOLD_NAVVOICE) 800 else 1500)
                player.release(reason)
            }
        }
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
        restoreGlove()
        quizSentFor.clear()
        quizCorrect.clear()
        placeSide.clear()
        promptJob?.cancel()
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
            followGear = ui.value.followGear,
            // 아이 이야기는 가족 코스에서 뒷자리에 누가 앉았을 때만 (택시 뒷좌석 손님, 친구들은 아이가 아니니까)
            kidMode = kidsInBack(sc),
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

        // 5. 좌석 점호와 인사. 점호 창은 차가 서 있을 때만 띄움: 이미 달리는 중에 눌렀으면 바로 안내를 시작함
        if (resume) {
            val next = nextPlaceAfter()
            say(PlaceKindSystem.CHAPTER, fillKo(sc.chapter.pick(lang), "next", next?.name?.pick(lang) ?: ""))
            completeRollcall(silent = true)
        } else {
            val names = sc.landmarks.filter { it.triggers }.joinToString(if (lang == "en") ", " else ", ") { it.name.pick(lang) }
            // 인사를 다 듣기 전에 출발하면(벨트를 다 맸거나 [출발]을 눌렀으면) 남은 인사는 접고 출발 안내로 넘어감
            say(PlaceKindSystem.GREETING, fillKo(sc.greeting.pick(lang), "places", names), stale = { ui.value.phase != Phase.ROLLCALL })
            val car = app.vehicle.state.value
            if (car.connected && (car.allBuckled || rolling(car))) completeRollcall()
        }
        // 여행 동안 운전대 키를 받고, 내비 화면이 앞에 와도 이야기가 끊기지 않게 포그라운드 서비스를 켬
        media?.activate()
        app.tripActive(true, sc.title.pick(lang))
        bringToFrontSoon()
    }

    private enum class PlaceKindSystem { GREETING, DEPARTURE, CHAPTER, FINALE, NOTICE }

    /** title 을 주면 화면에 "알림" 대신 그 제목을 띄움 (예: 보물 상자, 쉬어 갈 시간) */
    private fun say(
        kind: PlaceKindSystem,
        text: String,
        front: Boolean = false,
        interject: Boolean = false,
        stale: (() -> Boolean)? = null,
        title: String? = null,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
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
            onDone = onDone,
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
        GLog.i("Trip", "점호 끝, 출발")
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
        promptJob?.cancel()
        if (ui.value.listening) app.listen.cancel()
        demo.stop()
        player.stopAll()
        sound.stopWaves()
        // 앱이 풀어 둔 글로브 박스 잠금과 바꾼 실내 조명은 되돌림
        restoreGlove()
        val count = entered.count { id -> id !in skippedInDemo && sc.findPlace(id)?.kind != PlaceKind.CITY }
        say(PlaceKindSystem.FINALE, sc.finale.pick(lang).replace("{count}곳", koCount(count) + " 곳").replace("{count}", count.toString()))
        recorder.event("end", null, sc.title.pick(lang))
        recorder.end()
        app.location.clear()
        restoreAmbient()
        // 엔진이 되돌린 뒤 마지막으로: 처음 바꾸기 전 기록과 다른 것이 남아 있으면 원래 값으로
        app.vehicle.restoreAll()
        clearTrip()
        // 기록은 바로 올리지 않고 기록 카드에서 한 번 물어본 뒤 보냄 (지나온 길은 위치 정보라서)
        ui.update {
            it.copy(
                phase = Phase.ENDED, pick = null, windowAsk = null, treasureAsk = false, listening = false, asking = false, confirmEnd = false,
                recordShared = if (companion.state.value.online) null else false,
            )
        }
        companion.send("trip_end")
        media?.publish(null, null, false)
        media?.deactivate()
        app.tripActive(false)
    }

    /** [여행 끝]: 바로 끝내지 않고 한 번 더 물음. 말로 "여행 끝내 줘" 했을 때는 차도 소리로 물음 */
    fun requestEndTrip(byVoice: Boolean = false) {
        val p = ui.value.phase
        if (p != Phase.DRIVING && p != Phase.ROLLCALL) return
        ui.update { it.copy(confirmEnd = true) }
        if (byVoice) {
            say(
                PlaceKindSystem.NOTICE,
                if (lang == "en") "Shall we end today's trip? I'll show today's record." else "오늘 여행을 끝낼까요? 끝내면 하루 기록을 보여 드려요.",
                interject = true, stale = { !ui.value.confirmEnd }, title = if (lang == "en") "End trip" else "여행 끝",
                onDone = { afterQuestion(it) },
            )
        }
    }

    fun cancelEndTrip() {
        closePromptMic()
        ui.update { it.copy(confirmEnd = false) }
    }

    /** 기록 카드에서 [보내기]를 눌렀을 때만 지나온 길과 기록을 그룹 페이지로 올림 */
    fun shareRecord() {
        app.scope.launch {
            val ok = companion.uploadRecord(recorder.toJson(forUpload = true))
            ui.update { it.copy(recordShared = if (ok) true else null) }
            if (!ok) toast(if (lang == "en") "Couldn't send the record. Please try again." else "기록을 보내지 못했어요. 다시 눌러 주세요.")
        }
    }

    fun keepRecordLocal() = ui.update { it.copy(recordShared = false) }

    /** 뒤로 버튼: 떠 있는 물음이 있으면 닫고 true. 없으면 false (앱은 끝내지 않고 뒤로 숨김) */
    fun onBack(): Boolean {
        val u = ui.value
        when {
            u.confirmEnd -> cancelEndTrip()
            u.windowAsk != null -> answerWindow(false)
            u.treasureAsk -> answerTreasure(false)
            u.pick != null -> answerPick("later")
            else -> return false
        }
        return true
    }

    fun goHome() {
        val midTrip = ui.value.phase == Phase.ROLLCALL || ui.value.phase == Phase.DRIVING
        player.stopAll()
        demo.stop()
        restoreGlove()
        if (midTrip) restoreAmbient()
        app.vehicle.restoreAll()
        companion.leave()
        media?.deactivate()
        app.tripActive(false)
        scenario = null
        ui.update { Ui(lang = it.lang, demoMode = it.demoMode, followGear = it.followGear, demoFast = it.demoFast, resumable = hasSavedTrip()) }
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
            placeSide[place.id] = side
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
                // 좌석 점호: 앉은 자리 모두 벨트를 매면 출발. 점호 중에 차가 움직이기 시작해도 창을 닫고 안내를 시작함 (달리는 동안 창을 띄워 두지 않게)
                if (phase == Phase.ROLLCALL && v.connected) {
                    val buckled = v.allBuckled && !(p.connected && p.allBuckled)
                    val setOff = rolling(v) && !rolling(p)
                    if (buckled || setOff) completeRollcall()
                }
                // 방향지시등: 켜면 멈추고 꺼지면 이어서
                if (v.turnSignal != p.turnSignal) onTurnSignal(v.turnSignal != "OFF")
                // 와이퍼와 전조등: 비 오는 날, 밤 이야기
                if (v.wiperOn != p.wiperOn || v.lowBeamOn != p.lowBeamOn) onMood(v.wiperOn, v.lowBeamOn)
                // 기어
                if (v.gear != p.gear) onGear(p.gear, v.gear)
                // 앱이 내려 둔 앞 창문을 운전자가 스위치로 닫았으면 '열려 있음' 표시와 닫기 단추를 거둠
                if (ui.value.windowOpened && v.windows != p.windows && System.currentTimeMillis() - windowOpenedAt > 5000 &&
                    app.vehicle.frontWindowsClosed(v) == true
                ) {
                    GLog.i("Trip", "앞 창문이 닫힘")
                    sound.stopWaves()
                    ui.update { it.copy(windowOpened = false) }
                }
                // 가족 여행 코스에서 뒷자리에 누가 앉아 있으면 아이 이야기를 덧붙임
                if (v.occupied != p.occupied) {
                    GLog.i("Vehicle", "착석 ${v.occupied.filterValues { it }.keys.joinToString().ifBlank { "없음" }}")
                    val kid = kidsInBack(scenario, v)
                    if (phase != Phase.HOME && kid != ui.value.kidMode) ui.update { it.copy(kidMode = kid) }
                }
            }
        }
    }

    /** 기어가 P 가 아닌 것으로 확인됨 (달리거나 곧 달림). 기어를 모르면 false */
    private fun rolling(v: VehicleBridge.State) = v.connected && (v.gear == "D" || v.gear == "R" || v.gear == "N")

    /** 가족 코스에서 뒷자리에 누가 앉아 있는지. 뒷좌석 착석 신호를 주지 않는 차는 앉아 있다고 봄 (가족 코스를 골랐으니까) */
    private fun kidsInBack(sc: Scenario?, v: VehicleBridge.State = app.vehicle.state.value): Boolean {
        if (sc?.kidAuto != true) return false
        val rear = listOf("ROW_2_LEFT", "ROW_2_RIGHT").mapNotNull { v.occupied[it] }
        return rear.isEmpty() || rear.any { it }
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
            // 달리는 동안 '나중에'로 담아 둔 장소가 있으면 지금 고르라고 알림
            val saved = ui.value.laterPicks.size
            if (saved > 0) {
                toast(
                    when {
                        lang != "en" -> "담아 둔 장소가 ${koCount(saved)} 곳 있어요. 화면에서 골라 주세요."
                        saved == 1 -> "1 saved place. Pick it on the screen."
                        else -> "$saved saved places. Pick one on the screen."
                    },
                )
            }
            // 보물 상자가 준비됐으면 먼저, 아니면 바닷가 창문
            if (!maybeOfferTreasure()) maybeAskWindow()
            return
        }
        if (old == "P") {
            // P 에서 벗어나면 서 있을 때만 하는 물음(창문, 보물 상자)과 파도 소리를 거두고, 열어 둔 글로브 박스는 다시 잠금
            ui.update { it.copy(windowAsk = null, treasureAsk = false) }
            sound.stopWaves()
            restoreGlove()
        }
        if (new == "D") {
            val since = parkedSince
            parkedSince = null
            // 창문은 달리는 동안 앱이 움직이지 않음 (끼임 위험, P단에서만 움직임). 열려 있으면 소리로 알리고 닫는 건 운전자가 스위치로
            if (ui.value.windowOpened) {
                say(
                    PlaceKindSystem.NOTICE,
                    if (lang == "en") "The front windows are still a little open. Use the window switch to close them." else "앞 창문이 조금 열려 있어요. 닫으려면 창문 스위치를 올려 주세요.",
                    interject = true, title = if (lang == "en") "Windows" else "창문",
                )
            }
            // 다시 타면 다음 장
            if (since != null && System.currentTimeMillis() - since > 15_000) {
                val next = nextPlaceAfter()
                if (next != null) say(PlaceKindSystem.CHAPTER, fillKo(scenario?.chapter?.pick(lang).orEmpty(), "next", next.name.pick(lang)))
            }
            if (u.demoMode && u.followGear) demoPlay()
        }
    }

    /** 파도 소리가 들리는 바닷가 명소에 차를 세우면 앞 창문을 조금 열지 먼저 물어봄 (명소마다 한 번) */
    private fun maybeAskWindow() {
        val sc = scenario ?: return
        val pos = ui.value.carPos ?: return
        val spot = sc.places.firstOrNull {
            it.kind == PlaceKind.SEA && it.ambience != null && Geo.distanceM(pos, it.pos) <= it.radiusM * 1.3 && it.id !in windowAskedFor
        } ?: return
        windowAskedFor += spot.id
        ui.update { it.copy(windowAsk = spot) }
        say(
            PlaceKindSystem.NOTICE,
            if (lang == "en") "We've stopped by the sea. Shall I lower the front windows a little so you can hear the waves?" else "바닷가에 세웠네요. 앞 창문을 조금 내려 파도 소리를 들여 볼까요?",
            interject = true,
            stale = { ui.value.windowAsk == null },
            title = if (lang == "en") "Windows" else "창문",
            onDone = { afterQuestion(it) },
        )
    }

    fun answerWindow(open: Boolean) {
        val spot = ui.value.windowAsk ?: return
        closePromptMic()
        ui.update { it.copy(windowAsk = null) }
        if (!open) return
        if (!app.vehicle.state.value.parked) {
            toast(if (lang == "en") "Windows move only while parked." else "창문은 차가 서 있을 때만 움직여요.")
            return
        }
        // 앞 창문만 조금. 뒷자리 아이가 손이나 머리를 내밀 수 있는 만큼은 열지 않음
        lowerFrontWindows { ok ->
            if (!ok) return@lowerFrontWindows
            recorder.event("window", spot.id, spot.name.pick(lang))
            if (ui.value.demoMode && spot.ambience == "waves") sound.waves()
            companion.send("window", JSONObject().put("placeId", spot.id).put("open", true))
        }
    }

    /** 앞 창문 두 짝을 조금 내림. 차가 움직였다고 알려 준 뒤에만 열린 것으로 봄 (못 움직이면 알림) */
    private fun lowerFrontWindows(then: (Boolean) -> Unit = {}) {
        val en = lang == "en"
        val requested = app.vehicle.setWindows(WINDOW_OPEN, FRONT_WINDOWS) { ok ->
            if (ok) {
                windowOpenedAt = System.currentTimeMillis()
                ui.update { it.copy(windowOpened = true) }
            }
            else toast(if (en) "Couldn't move the windows. Check that the car is in P." else "창문을 움직이지 못했어요. 차가 P단에 서 있는지 확인해 주세요.")
            then(ok)
        }
        if (requested) toast(if (en) "Lowering the front windows a little." else "앞 창문을 조금 내려요.")
    }

    /** 창문을 올리기 전에 먼저 알리고 잠깐 기다림 (손이 창밖에 있을 수 있어서) */
    fun closeWindows(warn: Boolean = true) {
        val en = lang == "en"
        // 달리는 동안에는 앱이 창문을 올리지 않음. 운전자가 직접 스위치로
        if (!app.vehicle.state.value.parked) {
            toast(if (en) "I don't move the windows while driving. Please use the window switch." else "달리는 동안에는 창문을 움직이지 않아요. 창문 스위치로 닫아 주세요.")
            return
        }
        sound.stopWaves()
        if (warn) toast(if (en) "Closing the front windows. Hands inside, please." else "앞 창문을 닫아요. 손을 안으로 넣어 주세요.")
        app.scope.launch {
            delay(1500)
            app.vehicle.setWindows(0, FRONT_WINDOWS) { ok ->
                if (ok) ui.update { it.copy(windowOpened = false) }
                else toast(if (en) "Couldn't close the windows. Please use the window switch." else "창문을 닫지 못했어요. 창문 스위치로 닫아 주세요.")
            }
        }
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
        // 빨리 달릴수록 일찍 쉼: 안내 지점까지 9초 안이면 (시속 100km면 약 250m 앞)
        val holdM = if (ui.value.demoMode) guideHoldM else max(guideHoldM, speedMs * 9.0)
        val near = dist != null && dist in 0.0..holdM
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
        // 그룹 서버가 여행 도중에 켜지면 통신부가 그룹을 늦게 만듦. 그 번호를 화면과 QR에 반영
        app.scope.launch {
            var lastCode: String? = null
            companion.state.collect { s ->
                // 차 이름은 서버가 들어온 순서대로 "N호차"로 붙임. 세 번째 차부터 앱이 짐작한 이름과 어긋나지 않게 서버 값을 따름
                val serverName = s.carName
                if (serverName != null && serverName != ui.value.carName && ui.value.phase != Phase.HOME) {
                    ui.update { it.copy(carName = serverName) }
                }
                val code = s.code
                if (code != null && code != lastCode) {
                    lastCode = code
                    val p = ui.value.phase
                    if ((p == Phase.ROLLCALL || p == Phase.DRIVING) && ui.value.groupCode != code) {
                        ui.update { it.copy(groupCode = code) }
                        scenario?.let { sc -> companion.send("trip", JSONObject().put("scenarioId", sc.id).put("lang", lang).put("entered", entered.joinToString(","))) }
                    }
                } else if (code == null) lastCode = null
            }
        }
        app.scope.launch {
            companion.incoming.collect { msg ->
                when (msg.optString("type")) {
                    "pick" -> onPickMessage(msg)
                    // 출발 전 점호 창은 QR 옆 휴대폰 수가 바로 늘어나므로 알림을 겹쳐 띄우지 않음 (QR과 그룹 번호를 가리지 않게)
                    "phone_joined" -> if (ui.value.phase != Phase.ROLLCALL) toast(if (lang == "en") "A phone joined the trip group." else "휴대폰 한 대가 여행 그룹에 들어왔어요.")
                    "car_joined" -> toast(if (lang == "en") "${msg.optString("name")} joined the group." else "${msg.optString("name")}가 그룹에 들어왔어요.")
                    "quiz_answer" -> onQuizAnswer(msg)
                }
            }
        }
    }

    private fun onPickMessage(msg: JSONObject) {
        if (ui.value.phase != Phase.DRIVING && ui.value.phase != Phase.ROLLCALL) return
        val pick = Pick(
            id = msg.optString("id"),
            name = msg.optString("name"),
            pos = LatLng(msg.optDouble("lat"), msg.optDouble("lon")),
            kind = msg.optString("kind"),
            by = msg.optString("by").trim(),
        )
        ui.update { it.copy(pick = pick) }
        // 앞 물음의 30초 시계가 새 물음을 접지 않게 멈춤. 새 시계는 이 물음을 다 말한 뒤에 켬
        promptJob?.cancel()
        val stop = defaultPickMode(pick) == "waypoint"
        val who = when {
            lang == "en" -> if (pick.anonymous) "A passenger" else pick.by
            pick.anonymous -> "함께 탄 분이"
            else -> "${pick.by} 님이"
        }
        say(
            PlaceKindSystem.NOTICE,
            if (lang == "en") "$who picked ${spokenName(pick.name)}. " + (if (stop) "Shall we stop there on the way?" else "Shall I take us there?")
            else "$who ${spokenName(pick.name)}${eulReul(pick.name)} 골랐어요. " + (if (stop) "가는 길에 들를까요?" else "그리로 안내할까요?"),
            front = true,
            // 묻기도 전에 화면 단추로 답했으면 묻지 않음
            stale = { ui.value.pick?.id != pick.id },
            title = if (lang == "en") "Picked place" else "고른 장소",
            onDone = { afterQuestion(it) },
        )
    }

    /** 휴대폰에서 고른 곳을 "응" 한마디로 넣을 때의 기본: 주차장은 목적지로, 카페나 볼거리는 가는 길에 들르기 */
    private fun defaultPickMode(p: Pick) = if (p.kind.equals("parking", ignoreCase = true)) "destination" else "waypoint"

    fun answerPick(mode: String) {
        val p = ui.value.pick ?: return
        closePromptMic()
        ui.update { it.copy(pick = null) }
        if (mode == "later") {
            // 달리는 동안 고르지 않은 곳은 버리지 않고 모아 두었다가 차가 서면 다시 보여 줌
            ui.update { it.copy(laterPicks = (it.laterPicks.filter { l -> l.id != p.id } + p).takeLast(5)) }
            companion.send("pick_ack", JSONObject().put("id", p.id).put("name", p.name).put("mode", "later").put("accepted", false))
            // 이미 서 있으면 '담아 둔 장소' 카드에 바로 보이므로 그곳을 가리킴
            toast(
                if (app.vehicle.state.value.parked) {
                    if (lang == "en") "Saved ${p.name}. Find it under Saved for later." else "${p.name}${eulReul(p.name)} 담아 뒀어요. 담아 둔 장소에서 다시 고를 수 있어요."
                } else {
                    if (lang == "en") "Saved ${p.name} for later. It'll show up when we stop." else "${p.name}${eulReul(p.name)} 담아 뒀어요. 차를 세우면 다시 보여 드릴게요."
                },
            )
            return
        }
        applyPick(p, mode)
    }

    /** 담아 둔 장소를 차가 선 뒤에 내비게이션에 넣음 */
    fun routeLaterPick(id: String, mode: String) {
        val p = ui.value.laterPicks.firstOrNull { it.id == id } ?: return
        ui.update { it.copy(laterPicks = it.laterPicks.filter { l -> l.id != id }) }
        if (mode != "drop") applyPick(p, mode)
    }

    private fun applyPick(p: Pick, mode: String) {
        recorder.event("pick", null, p.name, if (p.anonymous) "" else p.by)
        val ok = if (mode == "waypoint") routeWithStop(p) else app.navi.routeTo(p.name, p.pos)
        companion.send("pick_ack", JSONObject().put("id", p.id).put("name", p.name).put("mode", mode).put("accepted", ok))
        if (!ok) {
            // 내비에 넣지 못했으면 버리지 않고 담아 둠. 차가 서면 다시 고를 수 있음
            ui.update { it.copy(laterPicks = (it.laterPicks.filter { l -> l.id != p.id } + p).takeLast(5)) }
            toast(if (lang == "en") "Couldn't reach the navigation. I saved ${p.name} for later." else "내비게이션에 넣지 못했어요. ${p.name}${eulReul(p.name)} 담아 둘게요.")
            return
        }
        toast(
            when {
                lang == "en" && mode == "waypoint" -> "Added ${p.name} as a stop on the way."
                lang == "en" -> "${p.name} is now the destination."
                mode == "waypoint" -> "${p.name}${eulReul(p.name)} 가는 길에 들르도록 넣었어요."
                else -> "${p.name}${Ko.euro(p.name)} 안내를 시작해요."
            }
        )
        bringToFrontSoon()
    }

    /**
     * 고른 곳을 가는 길에 들르도록 넣음. 시나리오가 출발할 때 경유지 두 곳(명소)을 넣어 두어 내비의 빈자리가 없는 일이 많음.
     * 그래서 아직 지나지 않은 명소 경유지와 고른 곳을 길 순서대로 묶어 같은 목적지로 경로를 다시 요청함 (경유지는 두 곳까지).
     * 셋이 되면 고른 곳과 바로 다음 명소만 남김. 빠진 명소도 근처를 지나면 이야기는 그대로 나옴 (위치로 판정)
     */
    private fun routeWithStop(p: Pick): Boolean {
        val sc = scenario
        val dest = sc?.destination
        val line = polyline
        if (sc == null || dest == null || line == null) return app.navi.addWaypoint(p.name, p.pos, replaceLast = true)
        val carAt = ui.value.carPos?.let { line.project(it) } ?: ui.value.progressM
        val ahead = sc.waypoints.filter { it.id !in entered && line.project(it.pos) > carAt + 30 }
            .map { Triple(it.name.pick(lang), it.pos, line.project(it.pos)) }
        val stop = Triple(p.name, p.pos, line.project(p.pos))
        val stops = if (ahead.size < 2) ahead + stop else listOf(ahead.minBy { it.third }, stop)
        val ordered = stops.sortedBy { it.third }
        GLog.i("Trip", "경유지 다시 묶음: " + ordered.joinToString(" → ") { it.first } + " → " + dest.name.pick(lang))
        return app.navi.routeTo(dest.name.pick(lang), dest.pos, ordered.map { it.first to it.second })
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
        val wasAsking = ui.value.asking
        ui.update { it.copy(listening = false, asking = false) }
        GLog.i("Ask", "못 알아들음: $reason")
        // 차가 되물은 뒤 아무 말이 없으면 다시 재촉하지 않음 (화면 단추는 그대로 남아 있음)
        if (reason != "CANCELLED" && !wasAsking) {
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
        ui.update { it.copy(listening = false, asking = false, heardText = text, heardSeq = it.heardSeq + 1) }
        // 차가 물어본 것이 떠 있으면 "응", "나중에" 같은 짧은 대답부터 봄
        val prompt = currentPrompt()
        if (prompt != null) {
            val r = Ask.answerFor(text, prompt)
            if (r != null) {
                GLog.i("Ask", "대답 [$text] $r")
                recorder.event("ask", null, text, "REPLY_" + r.name)
                answerPrompt(r)
                player.release(HOLD_LISTEN)
                return
            }
        }
        val q = Ask.parse(text)
        GLog.i("Ask", "질문 [$text] 의도 ${q.intent} ${q.side?.name ?: ""}")
        val en = lang == "en"
        // 물음이 없는데 "응", "괜찮아"만 들어오면(물음이 접힌 뒤 늦게 답한 경우) 길게 되묻지 않고 짧게 알림
        if (prompt == null && q.intent == Ask.Intent.UNKNOWN && Ask.isBareReply(text)) {
            reply(if (en) "There's nothing waiting for an answer right now." else "지금은 따로 여쭤본 게 없어요.", if (en) "Ask me" else "물어보기", share = false)
            recorder.event("ask", null, text, "REPLY_NONE")
            player.release(HOLD_LISTEN)
            return
        }
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
            Ask.Intent.END_TRIP -> requestEndTrip(byVoice = true)
            // 여행 안내 밖의 부탁(온도, 창문, 음악 등)과 못 알아들은 말: 할 수 있는 것을 짧게 알려 줌
            Ask.Intent.UNKNOWN -> reply(
                if (en) "Sorry, I didn't get that. Ask me what's outside, or how long until we arrive." else "잘 못 알아들었어요. 창밖에 보이는 곳이나 남은 길을 물어봐 주세요.",
                if (en) "Ask me" else "물어보기",
                share = false,
            )
        }
        recorder.event("ask", null, text, q.intent.name)
        player.release(HOLD_LISTEN)
    }

    // ---------------------------------------------------------------- 차가 물어본 것에 말로 답하기

    private fun hasPrompt(): Boolean {
        val u = ui.value
        return u.confirmEnd || u.windowAsk != null || u.treasureAsk || u.pick != null
    }

    /** 지금 떠 있는 물음의 종류 (화면과 같은 우선순위: 여행 끝, 창문, 보물 상자, 고른 장소) */
    private fun currentPrompt(): Ask.Prompt? {
        val u = ui.value
        return when {
            u.confirmEnd -> Ask.Prompt.END
            u.windowAsk != null -> Ask.Prompt.WINDOW
            u.treasureAsk -> Ask.Prompt.TREASURE
            u.pick != null -> Ask.Prompt.PICK
            else -> null
        }
    }

    /**
     * 물음을 다 읽었으면 마이크를 잠깐 열어 "응", "나중에"를 들음 (운전자가 화면을 보지 않고도 답할 수 있게).
     * 다른 안내에 밀려 끝까지 못 읽었거나 듣지 못하면 조용히 닫고 화면 단추만 남김 (나중에 까닭을 알 수 있게 로그).
     * 답을 기다리는 30초는 물음이 들린 뒤부터 셈. 앞 이야기가 길어 묻기가 늦어져도 묻기 전에 접히지 않게
     */
    private fun afterQuestion(completed: Boolean) {
        if (hasPrompt()) armPromptTimeout()
        if (completed) listenForAnswer() else if (hasPrompt()) GLog.i("Ask", "물음을 끝까지 읽지 못해 답 듣기를 열지 않음. 화면 단추로 답할 수 있음")
    }

    private fun listenForAnswer(attempt: Int = 0) {
        val u = ui.value
        if (!hasPrompt() || u.listening || u.phase != Phase.DRIVING) {
            GLog.i("Ask", "답 듣기 안 엶 (물음 ${hasPrompt()}, 듣는 중 ${u.listening}, 단계 ${u.phase})")
            return
        }
        val status = app.listen.status.value
        if (status != ListenBridge.Status.READY) {
            // 음성 인식이 앞의 듣기를 막 끝내는 중이면 잠깐 기다렸다가 다시 엶 (물음이 떠 있는 동안 2초까지)
            if (attempt < 4) app.scope.launch { delay(500); listenForAnswer(attempt + 1) }
            else GLog.w("Ask", "음성 인식이 준비되지 않아 답 듣기를 건너뜀 ($status). 화면 단추로 답할 수 있음")
            return
        }
        ui.update { it.copy(asking = true) }
        beginListening()
        app.listen.listen(lang, { onHeard(it) }, { onNotHeard(it) })
    }

    /** 물음에 화면 단추로 답했으면 답을 듣던 마이크도 바로 닫음 (듣는 표시가 남거나 늦게 "잘 못 들었어요"가 나오지 않게) */
    private fun closePromptMic() {
        if (ui.value.asking && ui.value.listening) app.listen.cancel()
    }

    /** 시연 명령이나 시험으로 넣은 말. 열려 있던 마이크는 닫고 음성 인식 결과와 같은 길로 처리 */
    fun onHeardTyped(text: String) {
        if (ui.value.listening) app.listen.cancel()
        onHeard(text)
    }

    private fun answerPrompt(r: Ask.Reply) {
        val u = ui.value
        val yes = r == Ask.Reply.YES || r == Ask.Reply.DESTINATION || r == Ask.Reply.WAYPOINT
        val pick = u.pick
        when {
            u.confirmEnd -> if (yes) endTrip() else cancelEndTrip()
            u.windowAsk != null -> answerWindow(yes)
            u.treasureAsk -> answerTreasure(yes)
            pick != null -> answerPick(
                when (r) {
                    Ask.Reply.DESTINATION -> "destination"
                    Ask.Reply.WAYPOINT -> "waypoint"
                    Ask.Reply.YES -> defaultPickMode(pick)
                    else -> "later"
                }
            )
        }
    }

    /**
     * 달리는 동안 답이 없는 물음은 30초 뒤 접어 둠 (이야기 카드가 물음에 오래 묶이지 않게).
     * 여행을 끝낼지 물은 것은 그대로 계속 가고, 고른 장소는 담아 두었다가 차가 서면 다시 보여 줌
     */
    private fun armPromptTimeout() {
        promptJob?.cancel()
        promptJob = app.scope.launch {
            delay(PROMPT_TIMEOUT_MS)
            while (ui.value.listening) delay(1000)
            val u = ui.value
            if (u.phase != Phase.DRIVING || app.vehicle.state.value.parked) return@launch
            if (u.confirmEnd) {
                GLog.i("Ask", "여행을 끝낼지 물었지만 답이 없어 계속 감")
                cancelEndTrip()
                // 그 밑에 기다리던 고른 장소 물음에는 다시 30초를 줌
                if (ui.value.pick != null) armPromptTimeout()
            } else if (u.pick != null) answerPick("later")
        }
    }

    /** 답을 지금 문장 다음에 끼워 말하고, 끝나면 하던 이야기로 돌아감. 휴대폰에도 질문과 답을 같이 띄움 */
    private fun reply(text: String, title: String, placeId: String? = null, share: Boolean = true, side: Ask.Side? = null) {
        if (text.isBlank()) return
        val sideKey = side?.name?.lowercase()
        player.interject(
            StoryPlayer.Item(
                key = "ans-${System.nanoTime()}", placeId = null, title = title, lang = lang,
                sentences = StoryPlayer.sentences(text), kind = StoryPlayer.Kind.ANSWER,
                // 답을 말하기 시작할 때 화면의 방향 표시도 그쪽으로 바꿈
                onStart = { ui.update { it.copy(nowSide = sideKey) } },
            )
        )
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
        reply(a.text, a.targetName ?: (if (lang == "en") "Out the window" else "창밖"), a.targetId, side = if (a.targetId != null) a.side else null)
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

    // ---------------------------------------------------------------- 뒷자리 아이, 퀴즈, 보물 상자

    /**
     * 명소 이야기가 끝나면: 가족 코스에서 뒷자리에 누가 앉아 있으면 뒷자리 친구들 이야기를 바로 이어서, 그리고 휴대폰으로 퀴즈.
     * 퀴즈는 아이 이야기를 다 들려준 뒤에 보냄 ("휴대폰으로 퀴즈도 보냈어요"라고 말한 때에 맞춰, 정답 알림이 이야기를 끊지 않게)
     */
    private fun afterStory(place: Place, completed: Boolean) {
        val sc = scenario ?: return
        if (ui.value.phase != Phase.DRIVING) return
        val hasQuiz = place.id in sc.quiz && place.id !in quizSentFor
        val phones = companion.state.value.phones > 0
        val kidLine = if (ui.value.kidMode && completed) sc.kid[place.id]?.pick(lang).orEmpty() else ""
        if (kidLine.isBlank()) {
            if (hasQuiz) sendQuiz(place.id)
            return
        }
        val tail = if (hasQuiz && phones) (if (lang == "en") " I'm sending a quiz to your phones, too." else " 휴대폰으로 퀴즈도 보낼게요.") else ""
        player.enqueueFront(
            StoryPlayer.Item(
                key = "kid-${place.id}", placeId = place.id, title = if (lang == "en") "For the back seat" else "뒷자리 친구들에게", lang = lang,
                sentences = StoryPlayer.sentences(kidLine + tail), kind = StoryPlayer.Kind.KID,
                stale = { passed(place, place.radiusM * 3.0) },
                onStart = {
                    recorder.event("kid", place.id, place.name.pick(lang))
                    companion.send("kid", JSONObject().put("placeId", place.id).put("text", kidLine))
                },
                onDone = { if (hasQuiz) sendQuiz(place.id) },
            )
        )
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
        val parked = ui.value.phase == Phase.DRIVING && app.vehicle.state.value.parked
        val en = lang == "en"
        val who = if (en) (by?.let { "$it got it right!" } ?: "Correct!") else (by?.let { "$it 님 정답이에요!" } ?: "정답이에요!")
        companion.send("stars", JSONObject().put("stars", stars).put("need", need).put("ready", ready).put("by", by ?: ""))
        // 서 있을 때 별을 다 모았으면 정답 알림과 보물 상자 물음을 한 번에 말함
        if (ready && parked && maybeOfferTreasure(lead = who)) return
        val line = when {
            ready && parked -> if (en) "$who That's all $stars stars." else "$who 별을 ${koCount(stars)} 개 다 모았어요."
            ready -> if (en) "$who That's all $stars stars. When we park, I'll open the treasure box." else "$who 별을 다 모았어요. 차를 세우면 보물 상자를 열어 드릴게요."
            else -> if (en) "$who That's star number $stars." else "$who 별 ${koCount(stars)} 개째예요."
        }
        say(PlaceKindSystem.NOTICE, line, interject = true, title = if (en) "Quiz" else "퀴즈 정답")
    }

    /** 별을 다 모았고 차가 서 있으면 보물 상자(글로브 박스)를 열지 물어봄. lead 는 물음 앞에 붙여 말할 한마디 (정답 알림) */
    private fun maybeOfferTreasure(lead: String = ""): Boolean {
        val u = ui.value
        if (u.treasure != Treasure.READY || u.treasureAsk) return false
        if (!app.vehicle.state.value.parked) return false
        val en = lang == "en"
        ui.update { it.copy(treasureAsk = true) }
        // 방금 정답을 맞혀 다 모았으면 그 말에 이어서, 달리는 동안 다 모으고 이제 세운 것이면 세웠다는 말부터
        val ask = when {
            lead.isNotBlank() -> if (en) "$lead That's all the stars. Shall I open the treasure box?" else "$lead 별을 다 모았어요. 보물 상자를 열어 볼까요?"
            en -> "We've stopped, and you have all the stars. Shall I open the treasure box?"
            else -> "차를 세웠네요. 별을 다 모았으니 보물 상자를 열어 볼까요?"
        }
        say(
            PlaceKindSystem.NOTICE, ask,
            interject = true, stale = { !ui.value.treasureAsk }, title = if (en) "Treasure box" else "보물 상자",
            onDone = { afterQuestion(it) },
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
        ui.update { it.copy(treasure = Treasure.OPEN) }
        sound.fanfare()
        recorder.event("treasure", null, if (en) "Treasure box" else "보물 상자", ui.value.stars.toString())
        // 상자가 열리는 동안 실내를 금빛으로
        setAmbient(0xFFFFC94D.toInt(), 95)
        app.scope.launch {
            delay(7000)
            if (ui.value.phase == Phase.DRIVING) setAmbient(regionColor, CRUISE_BRIGHTNESS)
        }
        // 잠금이 실제로 풀렸는지 차가 알려 준 뒤에 말함 (P단을 한 번 더 확인하고 풂)
        val requested = app.vehicle.setGloveBoxLock(false) { unlocked ->
            companion.send("treasure", JSONObject().put("stars", ui.value.stars).put("unlocked", unlocked))
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
        // 요청이 들어갔으면 달리기 시작할 때 다시 잠금 (풀리지 않았어도 원래 잠겨 있었으면 다시 잠가도 그대로라 괜찮음)
        if (requested) gloveOpenedByApp = true
    }

    /** 보물 상자를 열 때 잠겨 있었으면 달리기 시작할 때 다시 잠금 */
    private fun restoreGlove() {
        // 앱이 연 적이 없으면 그대로. 열기 전 잠금 상태를 몰랐으면(null) 안전하게 다시 잠금
        if (!gloveOpenedByApp) return
        gloveOpenedByApp = false
        val was = gloveWasLocked ?: true
        gloveWasLocked = null
        if (was) app.vehicle.setGloveBoxLock(true)
    }

    /** 보물 상자를 열지 묻는 것에 대한 답. 차가 P단에 서 있고 운전자가 그러자고 했을 때만 글로브 박스 잠금을 풂 */
    fun answerTreasure(yes: Boolean) {
        if (!ui.value.treasureAsk) return
        closePromptMic()
        ui.update { it.copy(treasureAsk = false) }
        GLog.i("Trip", "보물 상자: ${if (yes) "예" else "나중에"}")
        if (yes) openTreasure()
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
                // 답은 말하기 시작할 때 방향을 직접 넣음. 안내 문구(장소 없음)에는 방향을 띄우지 않음
                nowSide = when {
                    cur == null -> it.nowSide
                    cur.kind == StoryPlayer.Kind.ANSWER -> it.nowSide
                    cur.placeId != null -> placeSide[cur.placeId]
                    else -> null
                },
            )
        }
    }

    // ---------------------------------------------------------------- 조명

    private fun setAmbient(color: Int, brightness: Int) {
        app.vehicle.setAmbient(color, brightness)
    }

    private fun restoreAmbient() {
        val (c, b) = savedAmbient ?: (0xFFFFFFFF.toInt() to 70)
        app.vehicle.setAmbient(c, b)
    }

    // ---------------------------------------------------------------- 기타

    fun toast(msg: String) = ui.update { it.copy(toast = msg, toastSeq = it.toastSeq + 1) }

    private fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk")

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

    // 다시 타면 다음 장: 앱이 다시 켜져도(갑자기 꺼졌어도) 이어서 갈 수 있게 저장
    private fun saveTrip(joinCode: String?) {
        prefs.edit()
            .putString("trip_scenario", scenario?.id)
            .putString("trip_lang", lang)
            .putString("trip_join", joinCode)
            .putString("trip_entered", "")
            .putFloat("trip_progress", 0f)
            .putLong("trip_saved_at", System.currentTimeMillis())
            .apply()
    }

    private fun saveEntered() = prefs.edit().putString("trip_entered", entered.joinToString(",")).putLong("trip_saved_at", System.currentTimeMillis()).apply()

    private var lastSavedProgress = 0.0
    private fun saveProgress(m: Double) {
        if (kotlin.math.abs(m - lastSavedProgress) < 100) return
        lastSavedProgress = m
        prefs.edit().putFloat("trip_progress", m.toFloat()).putLong("trip_saved_at", System.currentTimeMillis()).apply()
    }

    private fun clearTrip() = prefs.edit().remove("trip_scenario").remove("trip_entered").remove("trip_progress").remove("trip_join").remove("trip_saved_at").apply()

    /**
     * 이어서 갈 수 있는 여행이 남아 있는지. 반나절(12시간)이 지난 기록은 지난 여행으로 보고 지움
     * (며칠 전에 멈춘 여행을 처음 화면에 계속 띄워 두지 않게)
     */
    private fun hasSavedTrip(): Boolean {
        if (prefs.getString("trip_scenario", null) == null) return false
        val age = System.currentTimeMillis() - prefs.getLong("trip_saved_at", 0L)
        if (age in 0..RESUME_WINDOW_MS) return true
        clearTrip()
        return false
    }

    fun resumeTrip() {
        if (!hasSavedTrip()) {
            ui.update { it.copy(resumable = false) }
            return
        }
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
