package app.gureuneun.guide

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    private var mediaKeys: MediaKeys? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.vehicle.start()
        app.navi.start()
        app.speech.start()
        app.listen.start()
        app.ux.start()
        val perm = "pleos.car.permission.FUSED_LOCATION"
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) app.location.start()
        else requestPermissions(arrayOf(perm), 1)

        val engine = app.engine
        if (mediaKeys == null) {
            mediaKeys = MediaKeys(applicationContext) { engine.onMediaCommand(it) }.also { engine.attachMedia(it) }
        }
        val scenarios = engine.scenarioList()
        engine.companion.watchReachability()

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
            val sttStatus by app.listen.status.collectAsState()
            var diag by remember { mutableStateOf(false) }

            when {
                diag -> DiagScreen(app) { diag = false }
                ui.phase == TripEngine.Phase.HOME -> HomeScreen(
                    ui = ui, scenarios = scenarios, vehicle = vehicle, navi = navi, speech = speech, locationOk = locOk, group = group,
                    listenReady = sttStatus == ListenBridge.Status.READY || sttStatus == ListenBridge.Status.LISTENING,
                    onStart = { id, code -> engine.startTrip(id, ui.lang, code, ui.demoMode) },
                    onResume = { engine.resumeTrip() },
                    onLang = { engine.setLang(it) },
                    onDemo = { engine.setDemoMode(it) },
                    onDiag = { diag = true },
                )
                ui.phase == TripEngine.Phase.ENDED -> RecordScreen(ui, engine.recorder, group) { engine.goHome() }
                else -> TripScreen(ui, vehicle, ux, navi, group, engine, partial, level)
            }
        }
        handleDemoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoIntent(intent)
    }

    /**
     * 시연 영상 녹화용 명령. 화면 버튼과 같은 동작만 함
     * 예: adb shell am start -n app.gureuneun.guide/.MainActivity --es demo start --es scenario jeju --es lang ko
     */
    private fun handleDemoIntent(intent: Intent?) {
        val cmd = intent?.getStringExtra("demo") ?: return
        intent.removeExtra("demo")
        val engine = app.engine
        GLog.i("Demo", "명령 $cmd")
        when (cmd) {
            "start" -> {
                val lang = intent.getStringExtra("lang") ?: engine.ui.value.lang
                engine.setLang(lang)
                engine.startTrip(intent.getStringExtra("scenario") ?: "gyeongju", lang, intent.getStringExtra("join"), true)
            }
            "go" -> engine.completeRollcall()
            "play" -> engine.demoPlay()
            "pause" -> engine.demoPause()
            "jump" -> engine.demoJumpToNext(intent.getStringExtra("to"))
            "seek" -> engine.demoSeek(intent.getIntExtra("m", 0).toDouble())
            "fast" -> engine.setDemoFast(intent.getIntExtra("n", 6))
            "follow" -> engine.setFollowGear(intent.getBooleanExtra("on", true))
            "next" -> engine.onMediaCommand(MediaKeys.Command.NEXT)
            "prev" -> engine.onMediaCommand(MediaKeys.Command.PREVIOUS)
            "pick" -> engine.answerPick(intent.getStringExtra("mode") ?: "destination")
            "window" -> engine.answerWindow(intent.getBooleanExtra("open", true))
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
            // 말로 묻기 시연: 녹음해 둔 질문(files/ask 또는 앱에 넣어 둔 assets/ask 의 PCM)을 음성 인식 SDK 에 넣어 같은 흐름으로 처리
            "ask" -> engine.askDemo(intent.getStringExtra("file"))
            // 음성 인식 없이 문장만 넣어 답 확인 (시험용)
            "asktext" -> engine.onHeard(intent.getStringExtra("q") ?: "")
            "kid" -> engine.setKidMode(intent.getBooleanExtra("on", true))
            "jam" -> engine.setDemoJam(intent.getBooleanExtra("on", true))
            "offer" -> engine.answerOffer(intent.getStringExtra("answer") != "no")
            "drivetime" -> engine.setDemoDrivingTime(intent.getIntExtra("min", 125))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GLog.i("Perm", "위치 권한 ${if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "허용" else "거부"}")
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) app.location.start()
    }

    /** 미디어 세션으로 오지 않는 경우를 대비해 화면이 받은 운전대 키도 같이 처리 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val cmd = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKeys.Command.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaKeys.Command.PREVIOUS
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> null
            else -> return super.onKeyDown(keyCode, event)
        }
        if (cmd != null) app.engine.onMediaCommand(cmd) else app.engine.togglePause()
        return true
    }
}
