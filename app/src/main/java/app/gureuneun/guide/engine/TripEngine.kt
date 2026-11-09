package app.gureuneun.guide.engine

import android.content.Context
import android.content.Intent
import android.graphics.Color
import app.gureuneun.guide.GuideApp
import app.gureuneun.guide.MainActivity
import app.gureuneun.guide.core.Geo
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
import org.json.JSONObject
import kotlin.math.max

/**
 * 여행 모드의 중심.
 * [여행 시작] 한 번으로 내비게이션, 음성, 실내 조명, 화면, 동승자 휴대폰을 한꺼번에 움직임.
 */
class TripEngine(private val app: GuideApp) {

    enum class Phase { HOME, ROLLCALL, DRIVING, ENDED }

    data class Pick(val id: String, val name: String, val pos: LatLng, val kind: String, val by: String, val fromCar: Boolean)

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
    )

    companion object {
        const val HOLD_TURN = "turn"
        const val HOLD_GUIDE = "guide"
        const val HOLD_USER = "user"
        const val WINDOW_OPEN = 3 // 0~10 중 3, 한 뼘쯤
        // 다음 길 안내가 이만큼 가까우면 이야기를 쉼. 시연 경로에는 실제 내비 음성이 없어 짧게 둠
        private const val GUIDE_HOLD_M = 150.0
        private const val GUIDE_HOLD_DEMO_M = 40.0
        private const val CRUISE_BRIGHTNESS = 40
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

    private val lang get() = ui.value.lang
    private val guideHoldM get() = if (ui.value.demoMode) GUIDE_HOLD_DEMO_M else GUIDE_HOLD_M

    init {
        // 시연 주행: 들려줄 이야기가 남아 있으면 실제 속도, 아무것도 없을 때만 빨리 감기
        demo.storyPlaying = { player.busy }
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
        maneuverAlong = sc.maneuvers.map { line.project(it.pos) }
        inside.clear()
        entered.clear()
        skippedInDemo.clear()
        windowAskedFor.clear()
        rainAnnounced = false
        nightAnnounced = false
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

    private fun say(kind: PlaceKindSystem, text: String, front: Boolean = false, interject: Boolean = false) {
        if (text.isBlank()) return
        val item = StoryPlayer.Item(
            key = "sys-${kind.name}-${System.nanoTime()}",
            placeId = null,
            title = when (kind) {
                PlaceKindSystem.GREETING -> if (lang == "en") "Today's course" else "오늘의 코스"
                PlaceKindSystem.DEPARTURE -> if (lang == "en") "Let's go" else "출발"
                PlaceKindSystem.CHAPTER -> if (lang == "en") "Next chapter" else "다음 장"
                PlaceKindSystem.FINALE -> if (lang == "en") "See you again" else "오늘 여행을 마치며"
                PlaceKindSystem.NOTICE -> if (lang == "en") "Notice" else "알림"
            },
            lang = lang,
            sentences = StoryPlayer.sentences(text),
            kind = StoryPlayer.Kind.SYSTEM,
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
        player.stopAll()
        sound.stopWaves()
        val count = entered.count { id -> id !in skippedInDemo && sc.findPlace(id)?.kind != PlaceKind.CITY }
        say(PlaceKindSystem.FINALE, sc.finale.pick(lang).replace("{count}곳", koCount(count) + " 곳").replace("{count}", count.toString()))
        recorder.event("end", null, sc.title.pick(lang))
        recorder.end()
        app.location.clear()
        restoreAmbient()
        clearTrip()
        ui.update { it.copy(phase = Phase.ENDED, pick = null, windowAsk = null) }
        companion.send("trip_end")
        app.scope.launch { companion.uploadRecord(recorder.toJson()) }
        media?.publish(null, null, false)
    }

    fun goHome() {
        player.stopAll()
        demo.stop()
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
            say(PlaceKindSystem.NOTICE, if (lang == "en") "Looks like rain. I'll tell the next stories with the sound of rain." else "비가 오네요. 다음 이야기는 빗소리와 어울리게 들려 드릴게요.", interject = true)
        }
        if (night && !nightAnnounced) {
            nightAnnounced = true
            recorder.event("night", null, if (lang == "en") "Night" else "밤")
            say(PlaceKindSystem.NOTICE, if (lang == "en") "The headlights are on. From now on, night stories." else "전조등이 켜졌네요. 이제부터는 밤 풍경 이야기로 바꿔 드릴게요.", interject = true)
        }
    }

    private fun onGear(old: String, new: String) {
        val u = ui.value
        if (u.phase != Phase.DRIVING) return
        if (new == "P") {
            parkedSince = System.currentTimeMillis()
            if (u.demoMode && u.followGear) demoPause()
            maybeAskWindow()
        } else if (new == "D") {
            val since = parkedSince
            parkedSince = null
            ui.update { it.copy(windowAsk = null) }
            sound.stopWaves()
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

    // ---------------------------------------------------------------- 한국어 조사와 수 읽기

    /** 소리로 읽을 이름. 끝에 붙은 "(예시)" 같은 괄호 표기는 뺌 */
    private fun spokenName(name: String): String = name.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "").trim()

    /** 마지막 글자에 받침이 있으면 true, 없으면 false, 판단할 수 없으면 null. 숫자는 읽는 소리로 판단 */
    private fun hasJong(word: String): Boolean? {
        val c = spokenName(word).trimEnd { !it.isLetterOrDigit() }.lastOrNull() ?: return null
        return when (c) {
            in '가'..'힣' -> (c - '가') % 28 != 0
            in '0'..'9' -> c in "013678"
            else -> null
        }
    }

    private fun josa(word: String, withJong: String, withoutJong: String): String = when (hasJong(word)) {
        true -> withJong
        false -> withoutJong
        null -> "$withJong($withoutJong)"
    }

    private fun eulReul(word: String): String = josa(word, "을", "를")

    /** 문구의 {key} 자리에 값을 넣고, 바로 뒤의 "예요"는 받침에 맞게 "이에요"로 바꿈 */
    private fun fillKo(template: String, key: String, value: String): String {
        val tag = "{$key}"
        return template.replace(tag + "예요", value + josa(value, "이에요", "예요")).replace(tag, value)
    }

    /** 열까지는 "세 곳"처럼 우리말 수로 읽음 */
    private fun koCount(n: Int): String =
        listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열").getOrNull(n - 1) ?: n.toString()

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
