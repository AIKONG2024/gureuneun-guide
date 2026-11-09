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
            var diag by remember { mutableStateOf(false) }

            when {
                diag -> DiagScreen(app) { diag = false }
                ui.phase == TripEngine.Phase.HOME -> HomeScreen(
                    ui = ui, scenarios = scenarios, vehicle = vehicle, navi = navi, speech = speech, locationOk = locOk, group = group,
                    onStart = { id, code -> engine.startTrip(id, ui.lang, code, ui.demoMode) },
                    onResume = { engine.resumeTrip() },
                    onLang = { engine.setLang(it) },
                    onDemo = { engine.setDemoMode(it) },
                    onDiag = { diag = true },
                )
                ui.phase == TripEngine.Phase.ENDED -> RecordScreen(ui, engine.recorder, group) { engine.goHome() }
                else -> TripScreen(ui, vehicle, ux, navi, group, engine)
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
            "jump" -> engine.demoJumpToNext()
            "fast" -> engine.setDemoFast(intent.getIntExtra("n", 6))
            "follow" -> engine.setFollowGear(intent.getBooleanExtra("on", true))
            "next" -> engine.onMediaCommand(MediaKeys.Command.NEXT)
            "prev" -> engine.onMediaCommand(MediaKeys.Command.PREVIOUS)
            "pick" -> engine.answerPick(intent.getStringExtra("mode") ?: "destination")
            "window" -> engine.answerWindow(intent.getBooleanExtra("open", true))
            "end" -> engine.endTrip()
            "clear" -> engine.forgetSavedTrip()
            "home" -> engine.goHome()
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
