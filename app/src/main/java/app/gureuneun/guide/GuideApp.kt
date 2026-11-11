package app.gureuneun.guide

import android.app.Application
import android.content.Context
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.AudioWatch
import app.gureuneun.guide.sdk.ListenBridge
import app.gureuneun.guide.sdk.LocationBridge
import app.gureuneun.guide.sdk.MediaKeys
import app.gureuneun.guide.sdk.NaviBridge
import app.gureuneun.guide.sdk.SpeechBridge
import app.gureuneun.guide.sdk.UxBridge
import app.gureuneun.guide.sdk.VehicleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GuideApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val vehicle by lazy { VehicleBridge(this, scope) }
    val navi by lazy { NaviBridge(this, scope) }
    val speech by lazy { SpeechBridge(this) }
    val listen by lazy { ListenBridge(this) }
    val location by lazy { LocationBridge(this) }
    val ux by lazy { UxBridge(this) }
    val engine by lazy { TripEngine(this) }

    /** 통화(inCall), 전화 울림(ringing), 다른 앱의 길 안내 음성(navSpeaking). 처음 쓸 때 살피기 시작 */
    val audio by lazy { AudioWatch(this).also { it.start() } }

    /**
     * 운전대 미디어 키 세션. 앱 전체에서 하나 (활동이 다시 만들어져도 새로 만들지 않음).
     * 엔진이 여행 시작에 media.activate(), 끝에 media.deactivate() 를 부름.
     */
    val media by lazy { MediaKeys(this) { engine.onMediaCommand(it) } }
    private var mediaAttached = false

    /** 엔진에 미디어 세션을 한 번만 붙임 (MainActivity.onCreate 에서 부름) */
    fun attachMedia() {
        if (mediaAttached) return
        mediaAttached = true
        engine.attachMedia(media)
    }

    private val prefs by lazy { getSharedPreferences("guide", Context.MODE_PRIVATE) }

    /** 점검 화면의 '시연 명령 받기'. 기본 꺼짐 */
    var demoCommands: Boolean
        get() = prefs.getBoolean(PREF_DEMO_COMMANDS, false)
        set(v) {
            prefs.edit().putBoolean(PREF_DEMO_COMMANDS, v).apply()
        }

    /** 시연 명령(adb intent)을 받아도 되는지. 디버그 빌드는 늘 받음 */
    val demoCommandsAllowed: Boolean get() = BuildConfig.DEBUG || demoCommands

    @Volatile private var tripOn = false
    private var tripTitle: String? = null

    /** 우리 화면이 지금 앞에 보이는지. 내비게이션에 길을 넣은 뒤 화면을 되찾을지 정할 때 봄 */
    @Volatile var inFront = false

    /** 여행 중 표시가 켜져 있는지 */
    val tripIsActive: Boolean get() = tripOn

    /**
     * 여행 중 표시. 엔진이 여행 시작(on=true, 제목), 제목 바꿈(on=true, 새 제목), 끝(on=false)에 부름.
     * 켜져 있는 동안 포그라운드 서비스(TripService)가 프로세스를 지키고, 내비 상태를 자주 확인함.
     * 시작은 앱이 앞에 있을 때 불러야 함. 제목이 null 이면 앞의 제목을 그대로 씀.
     */
    fun tripActive(on: Boolean, title: String? = null) {
        if (on) {
            val was = tripOn
            tripOn = true
            if (title != null) tripTitle = title
            if (was && TripService.running) TripService.update(this, tripTitle) else TripService.start(this, tripTitle)
        } else {
            if (!tripOn) return
            tripOn = false
            tripTitle = null
            TripService.stop(this)
        }
        navi.setTripActive(on)
    }

    companion object {
        const val PREF_DEMO_COMMANDS = "demo_commands"
    }
}
