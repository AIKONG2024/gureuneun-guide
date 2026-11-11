package app.gureuneun.guide.engine

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
import app.gureuneun.guide.core.Lang
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * 여행 모드의 중심.
 * [여행 시작] 한 번으로 내비게이션, 음성, 실내 조명, 화면, 동승자 휴대폰을 한꺼번에 움직임.
 * 달리는 동안에는 말로 묻고(SpeechToText) 말로 답하며(TextToSpeech), 차 상태(Vehicle)와 길 안내(NaviHelper)를 보고
 * 이야기를 언제, 얼마나 길게 들려줄지 스스로 고름.
 *
 * 하는 일은 여행 안내에 필요한 것만: 지나는 곳 이야기, 창밖 묻기, 휴대폰에서 고른 장소를 내비로, 아이 퀴즈, 하루 기록.
 * 앱이 차에서 바꾸는 것은 실내 조명 하나뿐이고 여행이 끝나면 원래대로 돌려 둠.
 */
class TripEngine(private val app: GuideApp) {

    enum class Phase { HOME, DRIVING, ENDED }

    /** 동승자가 휴대폰에서 고른 곳 */
    data class Pick(val id: String, val name: String, val pos: LatLng, val kind: String, val by: String, val placeId: String = "") {
        /** 휴대폰에 이름을 적지 않고 보낸 사람 */
        val anonymous: Boolean get() = by.isBlank() || by == "동승자" || by == "Passenger"
    }

    data class Ui(
        val phase: Phase = Phase.HOME,
        val scenario: Scenario? = null,
        val lang: String = "ko",
        val demoMode: Boolean = true,
        val carName: String = "1호차",
        val groupCode: String? = null,
        /** 여행을 시작한 뒤 차가 처음 움직였는지. 그 전에는 QR 을 띄워 두고 기다림 */
        val departed: Boolean = false,
        val nowPlace: Place? = null,
        val nowKind: StoryPlayer.Kind? = null,
        val nowTitle: String? = null,
        val nowSentence: String? = null,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val holds: Set<String> = emptySet(),
        val entered: List<String> = emptyList(),
        val nextPlace: Place? = null,
        val nextDistanceM: Double? = null,
        val guideDistanceM: Double? = null,
        val carPos: LatLng? = null,
        val heading: Double = 0.0,
        val progressM: Double = 0.0,
        val routeLengthM: Double = 0.0,
        val rain: Boolean = false,
        val night: Boolean = false,
        val pick: Pick? = null,
        val toast: String? = null,
        val toastSeq: Int = 0,
        val demoRunning: Boolean = false,
        val demoFast: Int = 6,
        val followGear: Boolean = true,
        val startedAt: Long = 0,
        val resumable: Boolean = false,
        // 말로 묻기
        val listening: Boolean = false,
        /** 방금 알아들은 질문. 화면에 3초만 보여 주고 지움 */
        val heardText: String? = null,
        /** 물어본 것에 답한 곳 (코스 밖 볼거리일 수 있음). 화면에 그곳 사진을 띄움 */
        val answerPlaceId: String? = null,
        /** 가족 코스에서 뒷자리에 사람이 앉아 있으면 명소 이야기 끝에 아이 이야기를 덧붙임 */
        val kidMode: Boolean = false,
        /** 아이 퀴즈를 맞힌 수 */
        val stars: Int = 0,
        /** 시연 도구의 [질문 음성 넣기]가 다음에 넣을 질문 번호 */
        val demoQuestionIdx: Int = 0,
        /** 지금 이야기하는 곳이 차의 어느 쪽에 보이는지 ("left", "right", "ahead", "behind"). 달리는 동안 화면에 크게 띄움 */
        val nowSide: String? = null,
        /** 차가 물어본 것(고른 장소, 여행 끝)에 말로 답하기를 기다리는 중 */
        val asking: Boolean = false,
        /** [여행 끝]을 누르면 바로 끝내지 않고 한 번 더 물음 */
        val confirmEnd: Boolean = false,
        /** 달리는 동안 바로 고르지 않은 장소. 차가 서면 다시 보여 줌 */
        val laterPicks: List<Pick> = emptyList(),
        /** 하루 기록을 그룹 휴대폰으로 보냈는지. null 이면 아직 묻는 중 */
        val recordShared: Boolean? = null,
    )

    /** 시연용으로 앱에 넣어 둔 질문 음성 (assets/ask, 16kHz 모노 16비트 PCM) */
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
    private var regionColor = 0xFF7FE3FF.toInt()
    private var playerWasBusy = false
    // 아이 퀴즈
    private val quizSentFor = LinkedHashSet<String>()
    private val quizCorrect = LinkedHashSet<String>()
    // 목적지에 세웠을 때 여행을 끝낼지 한 번만 물음
    private var endOffered = false
    // 차를 세우고 시동을 꺼서(화면이 꺼져서) 멈춰 둔 것. 다시 출발하면 저절로 이어 감
    private var awayPause = false
    private var flashJob: Job? = null
    // 명소마다 지나갈 때 차의 어느 쪽에 보이는지 ("잠시 후 오른쪽에")
    private val placeSide = HashMap<String, String>()
    // 답을 기다리는 물음을 달리는 동안 오래 띄워 두지 않음
    private var promptJob: Job? = null
    private var heardJob: Job? = null
    // 휴대폰에서 골라 내비에 넣은 곳. 길을 다시 요청할 때 앞서 넣은 곳이 빠지지 않게 기억해 둠
    private val pickedStops = ArrayList<Pick>()
    private var pickedDest: Pick? = null

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

    /** 이야기와 답을 들려주는 안내 언어 (여행을 시작할 때 고름) */
    private val lang get() = ui.value.lang
    /** 운전자가 보는 화면과 운전자에게 묻는 말의 언어 (차의 시스템 언어). 손님에게 영어로 안내해도 기사 화면은 그대로 */
    private val dlang get() = Lang.screen
    private val den get() = dlang == "en"
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
        // 앞서 앱이 바꿔 둔 실내 조명이 남아 있으면 원래대로 (앱이 갑자기 꺼졌던 경우)
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
        if (on && ui.value.phase == Phase.DRIVING) {
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
        quizSentFor.clear()
        quizCorrect.clear()
        placeSide.clear()
        pickedStops.clear()
        pickedDest = null
        promptJob?.cancel()
        endOffered = false
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
            phase = Phase.DRIVING,
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
            if (code == null) toast(if (den) "Phones can't join right now. The guide still works." else "휴대폰 연결이 안 돼요. 차 안 안내는 그대로예요.")
            else companion.send("trip", JSONObject().put("scenarioId", sc.id).put("lang", lang).put("entered", entered.joinToString(",")))
        }

        // 3. 내비게이션: 목적지와 경유지(최대 두 곳)를 순정 내비에 넣음. 내비에 뜨는 이름은 운전자 화면 언어로
        sc.destination?.let { dest ->
            app.navi.routeTo(dest.name.pick(dlang), dest.pos, sc.waypoints.map { it.name.pick(dlang) to it.pos })
        }

        // 4. 위치: 여행지와 명소마다 근접 알림을 등록 (실차), 시연 모드는 같은 판정을 앱 안에서 함
        app.location.watch(sc.places.filter { it.triggers })
        val startM = if (resume) prefs.getFloat("trip_progress", 0f).toDouble() else 0.0
        demo.load(sc.route, startM)
        demo.fastForward = ui.value.demoFast

        // 5. 인사. 차가 서 있는 동안은 QR 을 띄워 두고 기다렸다가 움직이기 시작하면 출발 안내로 넘어감
        if (resume) {
            val next = nextPlaceAfter()
            say(PlaceKindSystem.CHAPTER, fillKo(sc.chapter.pick(lang), "next", next?.name?.pick(lang) ?: ""))
            depart(silent = true)
        } else {
            val stops = sc.landmarks.filter { it.triggers }.map { it.name.pick(lang) }
            // 영어는 "A, B and C"
            val names = if (lang == "en" && stops.size > 1) stops.dropLast(1).joinToString(", ") + " and " + stops.last() else stops.joinToString(", ")
            // 인사를 다 듣기 전에 출발하면 남은 인사는 접고 출발 안내로 넘어감
            say(PlaceKindSystem.GREETING, fillKo(sc.greeting.pick(lang), "places", names), stale = { ui.value.departed })
            // 이미 달리는 중에 눌렀으면 바로 안내를 시작함
            if (rolling(app.vehicle.state.value)) depart()
        }
        // 여행 동안 운전대 키를 받고, 내비 화면이 앞에 와도 이야기가 끊기지 않게 포그라운드 서비스를 켬
        media?.activate()
        app.tripActive(true, sc.title.pick(lang))
        bringToFrontSoon()
    }

    private enum class PlaceKindSystem { GREETING, DEPARTURE, CHAPTER, FINALE, NOTICE }

    /** title 을 주면 "알림" 대신 그 제목을 씀 (예: 퀴즈 정답, 고른 장소) */
    private fun say(
        kind: PlaceKindSystem,
        text: String,
        front: Boolean = false,
        interject: Boolean = false,
        stale: (() -> Boolean)? = null,
        title: String? = null,
        /** 읽어 줄 언어. 운전자에게 묻는 말은 dlang 으로 */
        to: String = lang,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        if (text.isBlank()) return
        val item = StoryPlayer.Item(
            key = "sys-${kind.name}-${System.nanoTime()}",
            placeId = null,
            title = title ?: when (kind) {
                PlaceKindSystem.GREETING -> if (to == "en") "Today's course" else "오늘의 코스"
                PlaceKindSystem.DEPARTURE -> if (to == "en") "Let's go" else "출발"
                PlaceKindSystem.CHAPTER -> if (to == "en") "Next chapter" else "다음 장"
                PlaceKindSystem.FINALE -> if (to == "en") "See you again" else "오늘 여행을 마치며"
                PlaceKindSystem.NOTICE -> if (to == "en") "Notice" else "알림"
            },
            lang = to,
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

    /** 차가 처음 움직이기 시작하면 출발 안내를 하고 여행지 색으로 조명을 바꿈 */
    fun depart(silent: Boolean = false) {
        val sc = scenario ?: return
        if (ui.value.phase != Phase.DRIVING || ui.value.departed) return
        ui.update { it.copy(departed = true) }
        recorder.event("depart", null, sc.title.pick(lang))
        GLog.i("Trip", "출발")
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
        flashJob?.cancel()
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
                phase = Phase.ENDED, pick = null, listening = false, asking = false, confirmEnd = false,
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
        if (ui.value.phase != Phase.DRIVING) return
        ui.update { it.copy(confirmEnd = true) }
        if (byVoice) {
            say(
                PlaceKindSystem.NOTICE,
                if (den) "Shall we end today's trip?" else "오늘 여행을 끝낼까요?",
                interject = true, stale = { !ui.value.confirmEnd }, title = if (den) "End trip" else "여행 끝", to = dlang,
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
            if (!ok) toast(if (den) "Couldn't send it. Please try again." else "보내지 못했어요. 다시 눌러 주세요.")
        }
    }

    /** 뒤로 버튼: 떠 있는 물음이 있으면 닫고 true. 없으면 false (앱은 끝내지 않고 뒤로 숨김) */
    fun onBack(): Boolean {
        val u = ui.value
        when {
            u.confirmEnd -> cancelEndTrip()
            u.pick != null -> answerPick("later")
            else -> return false
        }
        return true
    }

    fun goHome() {
        val midTrip = ui.value.phase == Phase.DRIVING
        player.stopAll()
        demo.stop()
        flashJob?.cancel()
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
        // 시연 도구로 주행을 시작해도 출발 안내부터
        if (!ui.value.departed) depart()
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
        ui.update { it.copy(guideDistanceM = dist) }
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
        // 출발하기 전에는 판정하지 않음 (출발지가 이미 여행지 안이어도 인사와 출발 안내가 먼저)
        if (!ui.value.departed) return
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
        if (ui.value.phase != Phase.DRIVING || !ui.value.departed) return
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
                    media?.publish(name, if (den) "Rolling Guide" else "구르는 가이드", true)
                },
                onDone = { completed ->
                    companion.send("story_end", JSONObject().put("placeId", place.id))
                    if (!player.busy) setAmbient(regionColor, CRUISE_BRIGHTNESS)
                    media?.publish(name, null, false)
                    if (!completed) GLog.i("Trip", "이야기 건너뜀: $name")
                    if (place.kind != PlaceKind.CITY) afterStory(place, completed)
                },
            )
        )
        // 마지막 목적지처럼 차를 세우는 곳이면 시연 주행도 이야기가 끝난 뒤 멈춤
        if (place.stop && ui.value.demoMode) {
            app.scope.launch {
                while (player.busy && demo.running.value) delay(500)
                if (demo.running.value) demoPause()
            }
        }
    }

    private fun showStoryOnPhones(place: Place, text: String, len: StoryLength?, kind: String) {
        companion.send(
            "story",
            JSONObject()
                .put("placeId", place.id)
                .put("name", place.name.pick(lang))
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
                // 여행을 시작한 뒤 차가 처음 움직이면 출발
                if (phase == Phase.DRIVING && !ui.value.departed && rolling(v)) depart()
                // 방향지시등: 켜면 멈추고 꺼지면 이어서
                if (v.turnSignal != p.turnSignal) onTurnSignal(v.turnSignal != "OFF")
                // 와이퍼와 전조등: 비 오는 날, 밤 풍경에 맞는 한마디를 이야기 앞에 붙임 (따로 알리지는 않음)
                if (v.wiperOn != p.wiperOn || v.lowBeamOn != p.lowBeamOn) ui.update { it.copy(rain = v.wiperOn, night = v.lowBeamOn) }
                // 기어
                if (v.gear != p.gear) onGear(p.gear, v.gear)
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

    private fun onGear(old: String, new: String) {
        val u = ui.value
        if (u.phase != Phase.DRIVING) return
        if (new == "P") {
            // 출발한 뒤에 세운 것만 쉬어 간 것으로 침 (출발 전부터 서 있던 것을 "다시 출발"로 알아듣지 않게)
            if (u.departed) parkedSince = System.currentTimeMillis()
            if (u.demoMode && u.followGear) demoPause()
            updateGuidanceHold(u.guideDistanceM)
            // 마지막 목적지에 세웠으면 이야기가 끝난 뒤 여행을 끝낼지 한 번 물어봄
            offerEndAtDestination()
            return
        }
        if (new == "D") {
            val since = parkedSince
            parkedSince = null
            // 쉬었다 돌아왔으면 하다 만 이야기는 접고 다음 장부터
            if (awayPause) {
                awayPause = false
                player.stopAll()
                player.release(HOLD_USER)
            }
            // 다시 타면 다음 장
            if (since != null && System.currentTimeMillis() - since > 15_000) {
                val next = nextPlaceAfter()
                if (next != null) say(PlaceKindSystem.CHAPTER, fillKo(scenario?.chapter?.pick(lang).orEmpty(), "next", next.name.pick(lang)))
            }
            if (u.demoMode && u.followGear) demoPlay()
        }
    }

    /**
     * 마지막 목적지 이야기를 듣고 차를 세웠으면 "오늘 여행을 끝낼까요?" 하고 한 번 물어봄.
     * 도착해서 [여행 끝]을 찾아 누르지 않아도 "응" 한마디로 하루 기록까지 가게
     */
    private fun offerEndAtDestination() {
        val sc = scenario ?: return
        val destId = sc.destinationId
        if (endOffered || destId !in entered) return
        endOffered = true
        app.scope.launch {
            // 하던 이야기와 아이 이야기가 끝날 때까지 기다림
            delay(1500)
            while (player.busy || ui.value.listening) delay(500)
            val u = ui.value
            if (u.phase != Phase.DRIVING || !app.vehicle.state.value.parked || u.confirmEnd || u.pick != null) {
                // 그사이 다시 출발했으면 다음에 세울 때 다시 물어봄
                if (u.phase == Phase.DRIVING && !app.vehicle.state.value.parked) endOffered = false
                return@launch
            }
            requestEndTrip(byVoice = true)
        }
    }

    // ---------------------------------------------------------------- 내비게이션

    private fun observeNavi() {
        app.scope.launch {
            app.navi.state.collect { n ->
                if (ui.value.demoMode || ui.value.phase != Phase.DRIVING) return@collect
                val d = if (n.status == "ROUTE_DRIVE") n.tbtDistanceM else null
                ui.update { it.copy(guideDistanceM = d) }
                updateGuidanceHold(d)
            }
        }
        app.scope.launch {
            app.navi.events.collect { e ->
                when (e) {
                    is NaviBridge.Event.Arrived -> if (ui.value.phase == Phase.DRIVING) recorder.event("arrive", null, e.name ?: "")
                    else -> {}
                }
            }
        }
    }

    /** 다음 길 안내가 가까우면 잠시 멈추고, 지나가면 이어서 */
    private fun updateGuidanceHold(dist: Double?) {
        // 빨리 달릴수록 일찍 쉼: 안내 지점까지 9초 안이면 (시속 100km면 약 250m 앞)
        val holdM = if (ui.value.demoMode) guideHoldM else max(guideHoldM, speedMs * 9.0)
        // 세워 둔 차는 회전할 일이 없으니 쉬지 않음 (목적지 바로 앞에 세웠을 때 이야기가 묶이지 않게)
        val near = dist != null && dist in 0.0..holdM && !app.vehicle.state.value.parked
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
                // 기어 신호를 주지 않는 차는 움직이기 시작한 것으로 출발을 앎
                if (ui.value.phase == Phase.DRIVING && !ui.value.departed && speedMs > 2.0 && !app.vehicle.state.value.connected) depart()
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
                    if (ui.value.phase == Phase.DRIVING && ui.value.groupCode != code) {
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
                    "car_joined" -> toast(if (den) "${msg.optString("name")} joined." else "${msg.optString("name")}가 들어왔어요.")
                    "quiz_answer" -> onQuizAnswer(msg)
                }
            }
        }
    }

    private fun onPickMessage(msg: JSONObject) {
        if (ui.value.phase != Phase.DRIVING) return
        val pick = Pick(
            id = msg.optString("id"),
            name = msg.optString("name"),
            pos = LatLng(msg.optDouble("lat"), msg.optDouble("lon")),
            kind = msg.optString("kind"),
            by = msg.optString("by").trim(),
            placeId = msg.optString("placeId"),
        )
        ui.update { it.copy(pick = pick) }
        // 앞 물음의 30초 시계가 새 물음을 접지 않게 멈춤. 새 시계는 이 물음을 다 말한 뒤에 켬
        promptJob?.cancel()
        val stop = defaultPickMode(pick) == "waypoint"
        val who = when {
            den -> if (pick.anonymous) "A passenger" else pick.by
            pick.anonymous -> "함께 탄 분이"
            else -> "${pick.by} 님이"
        }
        val name = pickName(pick)
        say(
            PlaceKindSystem.NOTICE,
            if (den) "$who picked ${spokenName(name)}. " + (if (stop) "Shall we stop there on the way?" else "Shall I take us there?")
            else "$who ${spokenName(name)}${eulReul(name)} 골랐어요. " + (if (stop) "가는 길에 들를까요?" else "그리로 안내할까요?"),
            front = true,
            // 묻기도 전에 화면 단추로 답했으면 묻지 않음
            stale = { ui.value.pick?.id != pick.id },
            title = if (den) "Picked place" else "고른 장소",
            to = dlang,
            onDone = { afterQuestion(it) },
        )
    }

    /** 운전자에게 보여 주고 말할 장소 이름. 손님 휴대폰이 영어여도 기사에게는 화면 언어로 */
    fun pickName(p: Pick): String = scenario?.nameOf(p.placeId)?.pick(dlang) ?: p.name

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
            // 달리는 중이면 언제 다시 볼 수 있는지 알려 줌. 서 있으면 옆 칸에 바로 보이니 띄우지 않음
            if (!app.vehicle.state.value.parked) toast(if (den) "Saved. I'll show it when we stop." else "담아 뒀어요. 차를 세우면 다시 보여 드릴게요.")
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
        if (mode == "waypoint") {
            // 같은 곳을 다시 골라도 경유지는 하나
            pickedStops.removeAll { Geo.distanceM(it.pos, p.pos) < 30 }
            pickedStops += p
        } else pickedDest = p
        val ok = reroute()
        companion.send("pick_ack", JSONObject().put("id", p.id).put("name", p.name).put("mode", mode).put("accepted", ok))
        if (!ok) {
            // 내비에 넣지 못했으면 버리지 않고 담아 둠. 차가 서면 다시 고를 수 있음
            if (mode == "waypoint") pickedStops -= p else pickedDest = null
            ui.update { it.copy(laterPicks = (it.laterPicks.filter { l -> l.id != p.id } + p).takeLast(5)) }
            toast(if (den) "Couldn't reach the navigation. Saved for later." else "내비게이션에 넣지 못했어요. 담아 둘게요.")
            return
        }
        val name = pickName(p)
        toast(
            when {
                den && mode == "waypoint" -> "Added $name as a stop."
                den -> "Heading to $name."
                mode == "waypoint" -> "$name${eulReul(name)} 경유지에 넣었어요."
                else -> "$name${Ko.euro(name)} 안내해요."
            }
        )
        bringToFrontSoon()
    }

    /**
     * 고른 곳을 넣어 순정 내비에 경로를 다시 요청함. 카페나 볼거리는 가는 길에 들르는 곳으로, 주차장은 목적지로.
     * 경유지는 두 곳까지라 고른 곳을 먼저 넣고, 남는 자리에 아직 지나지 않은 명소를 가까운 순으로 채워 길 순서대로 묶음.
     * 앞서 넣은 곳은 지나기 전까지 그대로 둠. 빠진 명소도 근처를 지나면 이야기는 그대로 나옴 (위치로 판정)
     */
    private fun reroute(): Boolean {
        val sc = scenario ?: return false
        val line = polyline ?: return false
        val dest = pickedDest?.let { pickName(it) to it.pos } ?: sc.destination?.let { it.name.pick(dlang) to it.pos } ?: return false
        val carAt = ui.value.carPos?.let { line.project(it) } ?: ui.value.progressM
        val picked = pickedStops.map { Triple(pickName(it), it.pos, line.project(it.pos)) }.filter { it.third > carAt + 30 }.takeLast(2)
        val sights = sc.waypoints.filter { it.id !in entered && line.project(it.pos) > carAt + 30 }
            .map { Triple(it.name.pick(dlang), it.pos, line.project(it.pos)) }.sortedBy { it.third }
        val stops = (picked + sights.take(2 - picked.size)).sortedBy { it.third }
        GLog.i("Trip", "경유지 다시 묶음: " + (stops.map { it.first } + dest.first).joinToString(" → "))
        return app.navi.routeTo(dest.first, dest.second, stops.map { it.first to it.second })
    }

    // ---------------------------------------------------------------- 말로 묻기 ("저거 뭐야?")

    /** [물어보기] 버튼: 차 마이크로 한 문장을 듣고 답함. 듣는 중에 다시 누르면 그만 들음 */
    fun ask() {
        val u = ui.value
        if (u.phase != Phase.DRIVING) return
        if (u.listening) {
            app.listen.cancel()
            return
        }
        beginListening()
        app.listen.listen(lang, { onHeard(it) }, { onNotHeard(it) })
    }

    /** 질문 음성 파일(PCM 16kHz 모노)을 같은 음성 인식 SDK 에 넣어 똑같이 처리함. 마이크가 없는 에뮬레이터 시연용 */
    fun askWithAudio(pcm: ByteArray) {
        if (ui.value.phase != Phase.DRIVING) return
        beginListening()
        app.listen.feed(pcm, lang, { onHeard(it) }, { onNotHeard(it) })
    }

    /**
     * 시연: 질문 음성 넣기. name 을 주면 그 질문, 없으면 목록의 다음 질문.
     * 앱 폴더(files/ask)에 같은 이름 파일이 있으면 그걸, 없으면 앱에 넣어 둔 파일(assets/ask)을 씀
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
        GLog.i("Ask", "질문 음성 넣기: $file")
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

    /** 알아들은 말을 의도별로 처리함 (마이크, 음성 파일, 시험 명령 모두 여기로 옴) */
    fun onHeard(text: String) {
        sound.heard()
        // 차가 물어본 것이 떠 있으면 "응", "나중에" 같은 짧은 대답부터 봄
        val prompt = currentPrompt()
        val answer = prompt?.let { Ask.answerFor(text, it) }
        // 알아들은 말은 물어본 것일 때만 화면에 잠깐 띄움 ("응" 같은 대답은 띄우지 않음)
        ui.update { it.copy(listening = false, asking = false, heardText = if (answer == null) text else null) }
        heardJob?.cancel()
        heardJob = app.scope.launch {
            delay(3000)
            ui.update { it.copy(heardText = null) }
        }
        if (prompt != null) {
            val r = answer
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
            Ask.Intent.PAUSE -> player.hold(HOLD_USER)
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
        return u.confirmEnd || u.pick != null
    }

    /** 지금 떠 있는 물음의 종류 (화면과 같은 우선순위: 여행 끝, 고른 장소) */
    private fun currentPrompt(): Ask.Prompt? {
        val u = ui.value
        return when {
            u.confirmEnd -> Ask.Prompt.END
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
        app.listen.listen(dlang, { onHeard(it) }, { onNotHeard(it) })
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
                // 답을 말하기 시작할 때 화면의 방향 표시와 사진도 그곳으로 바꿈
                onStart = { ui.update { it.copy(nowSide = sideKey, answerPlaceId = placeId) } },
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

    // ---------------------------------------------------------------- 뒷자리 아이와 퀴즈

    /**
     * 명소 이야기를 끝까지 들었으면: 가족 코스에서 뒷자리에 누가 앉아 있을 때 뒷자리 친구들 이야기를 바로 잇고, 휴대폰으로 퀴즈를 보냄.
     * 건너뛴 이야기에는 퀴즈를 내지 않음. 퀴즈는 아이 이야기를 다 들려준 뒤에 보냄 (정답 알림이 이야기를 끊지 않게)
     */
    private fun afterStory(place: Place, completed: Boolean) {
        val sc = scenario ?: return
        if (ui.value.phase != Phase.DRIVING || !completed) return
        val hasQuiz = place.id in sc.quiz && place.id !in quizSentFor
        val phones = companion.state.value.phones > 0
        val kidLine = if (ui.value.kidMode) sc.kid[place.id]?.pick(lang).orEmpty() else ""
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
                .put("answer", answer).put("lang", lang).put("stars", ui.value.stars).put("total", sc.quiz.size),
        )
        recorder.event("quiz", placeId, name)
        GLog.i("Quiz", "퀴즈 보냄: $name")
        return true
    }

    /**
     * 휴대폰에서 퀴즈를 맞히면 그룹 서버가 알려 줌. 같은 퀴즈는 별 하나만.
     * 맞히면 차 안이 같이 축하함: 효과음, 금빛으로 잠깐 바뀌는 실내 조명, 짧은 한마디
     */
    private fun onQuizAnswer(msg: JSONObject) {
        if (ui.value.phase == Phase.HOME) return
        val id = msg.optString("id")
        if (!msg.optBoolean("correct") || !quizCorrect.add(id)) return
        val by = msg.optString("by").trim().takeIf { it.isNotBlank() && it != "동승자" && it != "Passenger" }
        val stars = quizCorrect.size
        val total = scenario?.quiz?.size ?: 0
        val all = total > 0 && stars >= total
        val name = scenario?.findPlace(id)?.name?.pick(lang) ?: ""
        recorder.event("quiz_ok", id, name, by ?: "")
        ui.update { it.copy(stars = stars) }
        companion.send("stars", JSONObject().put("stars", stars).put("total", total).put("by", by ?: ""))
        if (ui.value.phase != Phase.DRIVING) return
        if (all) sound.fanfare() else sound.star()
        flashAmbient(0xFFFFC94D.toInt())
        val en = lang == "en"
        val who = if (en) (by?.let { "$it got it right!" } ?: "Correct!") else (by?.let { "$it 님 정답이에요!" } ?: "정답이에요!")
        val line = if (all) (if (en) "$who That's every quiz today." else "$who 오늘 퀴즈를 다 맞혔어요.") else who
        toast(if (den) (by?.let { "$it got it right!" } ?: "Correct!") else (by?.let { "$it 님 정답이에요!" } ?: "정답이에요!"))
        say(PlaceKindSystem.NOTICE, line, interject = true, title = if (en) "Quiz" else "퀴즈 정답")
    }

    // ---------------------------------------------------------------- 운전대 버튼과 재생 조작

    fun onMediaCommand(c: MediaKeys.Command) {
        when (c) {
            MediaKeys.Command.NEXT -> player.skip()
            MediaKeys.Command.PREVIOUS -> if (!player.replay()) toast(if (den) "Nothing to replay yet." else "다시 들을 이야기가 아직 없어요.")
            MediaKeys.Command.PAUSE -> player.hold(HOLD_USER)
            MediaKeys.Command.PLAY -> player.release(HOLD_USER)
        }
    }

    fun togglePause() = if (player.isHeld(HOLD_USER)) player.release(HOLD_USER) else player.hold(HOLD_USER)

    /** 차를 세우고 화면이 꺼졌을 때(시동을 끈 것으로 봄) 이야기를 멈춰 둠. 다시 D 로 출발하면 이어 감 */
    fun pauseForBreak() {
        if (ui.value.phase != Phase.DRIVING) return
        awayPause = true
        player.hold(HOLD_USER)
    }

    private fun syncPlayer() {
        val cur = player.current
        val busy = player.busy
        if (playerWasBusy && !busy) GLog.i("Player", "이야기 끝, 조용함")
        playerWasBusy = busy
        ui.update {
            it.copy(
                // 안내 문구처럼 장소가 없는 항목이 나와도 마지막 명소 사진은 그대로 둠
                nowPlace = cur?.placeId?.let { id -> scenario?.findPlace(id) } ?: it.nowPlace,
                nowKind = cur?.kind ?: it.nowKind,
                nowTitle = cur?.title ?: it.nowTitle,
                nowSentence = cur?.sentences?.getOrNull(player.index),
                sentenceIndex = player.index,
                sentenceCount = cur?.sentences?.size ?: 0,
                holds = player.holdReasons.toSet(),
                // 답이 끝나고 다른 이야기로 넘어가면 답한 곳 사진은 거둠
                answerPlaceId = if (cur != null && cur.kind != StoryPlayer.Kind.ANSWER) null else it.answerPlaceId,
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

    /** 실내 조명을 잠깐 다른 색으로 밝혔다가 달리던 색으로 돌아옴 (퀴즈 정답). 밤에는 눈부시지 않게 덜 밝힘 */
    private fun flashAmbient(color: Int) {
        flashJob?.cancel()
        setAmbient(color, if (ui.value.night) 60 else 95)
        flashJob = app.scope.launch {
            delay(2500)
            if (ui.value.phase == Phase.DRIVING) setAmbient(regionColor, CRUISE_BRIGHTNESS)
        }
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
        // 내비게이션에 길을 넣으면 내비 화면이 앞으로 나옴. 우리 화면을 보고 있었을 때만 되찾고,
        // 내비를 보며 달리던 중이면(말로 "응" 하고 답한 경우) 그대로 둠
        if (!app.inFront) return
        app.scope.launch {
            // 내비가 찾은 경로를 보여 주고 안내를 시작할 때까지는 그대로 둠 (운전자가 경로를 볼 시간). 길어도 12초
            withTimeoutOrNull(12_000) { app.navi.events.first { it is NaviBridge.Event.RouteStarted } }
            delay(1500)
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
