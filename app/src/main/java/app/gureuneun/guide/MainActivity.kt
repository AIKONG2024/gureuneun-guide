package app.gureuneun.guide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.sdk.ListenBridge
import app.gureuneun.guide.sdk.MediaKeys
import app.gureuneun.guide.ui.DiagScreen
import app.gureuneun.guide.ui.HomeScreen
import app.gureuneun.guide.ui.RecordScreen
import app.gureuneun.guide.ui.TripScreen

class MainActivity : ComponentActivity() {
    private val app get() = application as GuideApp
    /** 점검 화면을 띄웠는지. 뒤로 키가 먼저 닫음 */
    private var diag by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.vehicle.start()
        app.navi.start()
        app.speech.start()
        app.listen.start()
        app.ux.start()
        app.audio
        requestNeededPermissions()

        val engine = app.engine
        app.attachMedia()
        val scenarios = engine.scenarioList()
        engine.companion.watchReachability()

        // 뒤로 키: 점검 화면이면 닫고, 떠 있는 물음이 있으면 닫고, 아니면 앱을 끝내지 않고 뒤로 보냄 (Pleos 규칙)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    diag -> diag = false
                    engine.onBack() -> Unit
                    else -> moveTaskToBack(true)
                }
            }
        })

        setContent {
            val ui by engine.ui.collectAsState()
            val vehicle by app.vehicle.state.collectAsState()
            val navi by app.navi.state.collectAsState()
            val ux by app.ux.state.collectAsState()
            val speech by app.speech.engine.collectAsState()
            val locOk by app.location.supported.collectAsState()
            val group by engine.companion.state.collectAsState()
            val partial by app.listen.partial.collectAsState()
            val level by app.listen.level.collectAsState()

            when {
                diag -> DiagScreen(app) { diag = false }
                ui.phase == TripEngine.Phase.HOME -> HomeScreen(
                    ui = ui, scenarios = scenarios, vehicle = vehicle, navi = navi, speech = speech, locationOk = locOk,
                    onStart = { id, code -> engine.startTrip(id, ui.lang, code, ui.demoMode) },
                    onResume = { engine.resumeTrip() },
                    onLang = { engine.setLang(it) },
                    onDiag = { diag = true },
                )
                ui.phase == TripEngine.Phase.ENDED -> RecordScreen(
                    ui, engine.recorder, group,
                    parked = vehicle.parked && !ux.restricted,
                    payload = { engine.reelPayload() },
                    onShare = { engine.shareRecord() },
                ) { engine.goHome() }
                else -> TripScreen(ui, vehicle, ux, group, engine, partial, level)
            }
        }
        // 시스템이 활동을 되살릴 때는 처음 받은 intent 가 다시 오므로 시연 명령을 또 처리하지 않음
        if (savedInstanceState == null) handleDemoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoIntent(intent)
    }

    private fun requestNeededPermissions() {
        val want = buildList {
            if (checkSelfPermission(LOCATION_PERM) != PackageManager.PERMISSION_GRANTED) add(LOCATION_PERM)
            // 여행 중 알림(포그라운드 서비스). 거절해도 서비스는 돌고 알림만 안 보임
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (LOCATION_PERM !in want) app.location.start()
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), PERM_REQUEST)
    }

    /**
     * 시연 영상 녹화용 명령. 화면 버튼과 같은 동작만 함
     * 디버그 빌드이거나 점검 화면에서 '시연 명령 받기'를 켰을 때만 받음 (다른 앱이 보낸 intent 로 차가 움직이지 않게)
     * 예: adb shell am start -n app.gureuneun.guide/.MainActivity --es demo start --es scenario jeju --es lang ko
     */
    private fun handleDemoIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("demo") ?: return
        intent.removeExtra("demo")
        if (!app.demoCommandsAllowed) {
            GLog.w("Demo", "시연 명령 꺼짐, 무시: $cmd (점검 화면에서 켤 수 있음)")
            return
        }
        val engine = app.engine
        GLog.i("Demo", "명령 $cmd")
        when (cmd) {
            "start" -> {
                val lang = intent.getStringExtra("lang") ?: engine.ui.value.lang
                engine.setLang(lang)
                engine.startTrip(intent.getStringExtra("scenario") ?: "gyeongju", lang, intent.getStringExtra("join"), true)
            }
            "go" -> engine.depart()
            "play" -> engine.demoPlay()
            "pause" -> engine.demoPause()
            "jump" -> engine.demoJumpToNext(intent.getStringExtra("to"))
            "seek" -> engine.demoSeek(intent.getIntExtra("m", 0).toDouble())
            "fast" -> engine.setDemoFast(intent.getIntExtra("n", 6))
            "follow" -> engine.setFollowGear(intent.getBooleanExtra("on", true))
            "next" -> engine.onMediaCommand(MediaKeys.Command.NEXT)
            "prev" -> engine.onMediaCommand(MediaKeys.Command.PREVIOUS)
            "pick" -> engine.answerPick(intent.getStringExtra("mode") ?: "destination")
            "end" -> engine.endTrip()
            "clear" -> engine.forgetSavedTrip()
            "home" -> engine.goHome()
            // 음성 인식 시험: 앱 폴더(files/ask)의 PCM(16kHz, 16비트, 모노)을 넣어 인식시킴
            "stt" -> {
                val name = intent.getStringExtra("file") ?: "q.pcm"
                val f = listOfNotNull(java.io.File(filesDir, "ask/$name"), getExternalFilesDir("ask")?.let { java.io.File(it, name) }).firstOrNull { it.exists() }
                val lang = intent.getStringExtra("lang") ?: engine.ui.value.lang
                if (f == null) GLog.w("STT", "시험 파일 없음: $name")
                else app.listen.feed(f.readBytes(), lang, { GLog.i("STT", "시험 결과: $it") }, { GLog.w("STT", "시험 실패: $it") })
            }
            // 말로 묻기: [물어보기] 버튼과 같음 (차 마이크)
            "listen" -> engine.ask()
            // 말로 묻기 시연: 질문 음성 파일(files/ask 또는 앱에 넣어 둔 assets/ask 의 PCM)을 음성 인식 SDK 에 넣어 같은 흐름으로 처리
            "ask" -> engine.askDemo(intent.getStringExtra("file"))
            // 음성 인식 없이 문장만 넣어 답 확인 (시험용)
            "asktext" -> engine.onHeardTyped(intent.getStringExtra("q") ?: "")
            // 말로 답하기 시험: 차가 물은 뒤 "응", "나중에" 같은 답을 넣음 (음성 인식 결과와 같은 길로 감)
            "answer" -> engine.onHeardTyped(intent.getStringExtra("q") ?: "응")
            // 여행 끝내기 확인 창을 띄움 ([여행 끝] 단추와 같음)
            "endask" -> engine.requestEndTrip()
            // 기록 화면의 [휴대폰으로 보내기]
            "share" -> engine.shareRecord()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            GLog.i("Perm", "$p ${if (ok) "허용" else "거부"}")
            if (p == LOCATION_PERM && ok) app.location.start()
        }
    }

    private fun onTrip(): Boolean = app.engine.ui.value.phase == TripEngine.Phase.DRIVING

    // 화면이 보이는 동안 (다른 앱이 덮으면 onStop). 같은 화면으로 intent 가 올 때 잠깐 거치는 onPause 는 치지 않음
    override fun onStart() {
        super.onStart()
        app.inFront = true
    }

    override fun onStop() {
        app.inFront = false
        super.onStop()
    }

    private fun mediaCommand(keyCode: Int): MediaKeys.Command? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKeys.Command.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaKeys.Command.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaKeys.Command.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> MediaKeys.Command.PAUSE
        else -> null
    }

    private fun isMediaKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || mediaCommand(keyCode) != null

    /**
     * 미디어 세션으로 오지 않는 경우를 대비해 화면이 받은 운전대 키도 같이 처리.
     * 여행 중일 때만 받음. 여행이 아닐 때는 원래 미디어 앱(라디오, 음악)으로 넘김
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isMediaKey(keyCode) || !onTrip()) return super.onKeyDown(keyCode, event)
        // 길게 누르면 같은 키가 되풀이해 옴. 처음 한 번만 처리
        if ((event?.repeatCount ?: 0) > 0) return true
        val cmd = mediaCommand(keyCode)
        if (cmd != null) app.engine.onMediaCommand(cmd) else app.engine.togglePause()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isMediaKey(keyCode) && onTrip()) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        private const val LOCATION_PERM = "pleos.car.permission.FUSED_LOCATION"
        private const val PERM_REQUEST = 1
    }
}
