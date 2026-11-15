package app.gureuneun.guide

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import app.gureuneun.guide.sdk.GLog
import app.gureuneun.guide.ui.TripScreen

class MainActivity : ComponentActivity() {
    private val engine get() = (application as GuideApp).engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine.start()
        requestLocation()

        // 뒤로 키: 떠 있는 창을 먼저 닫고, 없으면 앱을 끝내지 않고 뒤로 보냄 (여행 안내는 계속)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val u = engine.ui.value
                when {
                    u.showDemo -> engine.toggleDemo(false)
                    u.showQr -> engine.toggleQr(false)
                    else -> moveTaskToBack(true)
                }
            }
        })

        setContent { TripScreen(engine) }
        if (savedInstanceState == null) demoCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        demoCommand(intent)
    }

    override fun onStart() {
        super.onStart()
        engine.setFront(true)
    }

    override fun onStop() {
        engine.setFront(false)
        super.onStop()
    }

    private fun requestLocation() {
        if (checkSelfPermission(LOCATION) == PackageManager.PERMISSION_GRANTED) engine.onLocationPermission()
        else requestPermissions(arrayOf(LOCATION), PERM_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERM_REQUEST) return
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        GLog.i("Perm", "위치 권한 ${if (ok) "허용" else "거부"}")
        if (ok) engine.onLocationPermission()
    }

    /**
     * 시연 영상 녹화용 명령. 화면의 시연 메뉴와 같은 동작만 함
     * 예: adb shell am start -n app.gureuneun.guide/.MainActivity --es demo start
     *     --es demo say --es text "저 숲은 뭐야?"
     */
    private fun demoCommand(intent: Intent?) {
        val cmd = intent?.getStringExtra("demo") ?: return
        intent.removeExtra("demo")
        GLog.i("Demo", "명령 $cmd")
        val e = engine
        when (cmd) {
            "start" -> e.startTrip()
            "say" -> e.demoSay(intent.getStringExtra("text") ?: "응")
            "listen" -> e.listenAsk()
            "signal" -> e.demoSignal()
            "arrive" -> e.demoArrive()
            "reset" -> e.demoReset()
            "speed" -> e.demoSpeed(intent.getStringExtra("x")?.toDoubleOrNull() ?: 5.0)
            "place" -> e.injectDemoPlace()
            "qr" -> e.toggleQr(intent.getStringExtra("on") != "0")
            "menu" -> e.toggleDemo(intent.getStringExtra("on") != "0")
            "auto" -> e.isDemoAuto = intent.getStringExtra("on") != "0"
            "navi" -> e.naviEnabled = intent.getStringExtra("on") != "0"
            "exit" -> e.exitMode()
            "replay" -> e.replayRecord()
        }
    }

    companion object {
        private const val LOCATION = "pleos.car.permission.FUSED_LOCATION"
        private const val PERM_REQUEST = 7
    }
}
