package app.gureuneun.guide.sdk

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차 안 소리 상황 살피기. GuideApp.audio 로 하나만 씀.
 *
 * inCall: 전화 통화 중 (오디오 모드 MODE_IN_CALL, MODE_IN_COMMUNICATION). 엔진이 이야기를 쉬게 함.
 * ringing: 전화가 울리는 중 (MODE_RINGTONE). 통화로 넘어가기 전 단계.
 * navSpeaking: 다른 앱이 길 안내 음성(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)을 내는 중.
 *   Pleos TextToSpeech 3.3.1 은 AudioAttributes 를 따로 주지 않으면 USAGE_MEDIA, CONTENT_TYPE_SPEECH 로 말함 (AAR 의 Tts 기본값).
 *   이 앱은 setAudioAttributes 를 부르지 않으므로 우리 이야기 음성은 navSpeaking 에 잡히지 않음.
 *   TTS 의 usage 를 바꾸면 이 판정도 같이 바꿔야 함.
 *
 * 모든 값은 메인 스레드에서 바뀜.
 */
class AudioWatch(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private val _inCall = MutableStateFlow(false)
    val inCall: StateFlow<Boolean> = _inCall.asStateFlow()
    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()
    private val _navSpeaking = MutableStateFlow(false)
    val navSpeaking: StateFlow<Boolean> = _navSpeaking.asStateFlow()

    private var started = false
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            checkNav(configs)
            // 통화가 시작되면 통화 음성 재생도 바뀌므로, 모드 리스너가 없는 기기에서는 여기서도 모드를 봄
            checkMode()
        }
    }

    // 모드 리스너가 없는 API 30 이하에서만 씀
    private val poll = object : Runnable {
        override fun run() {
            checkMode()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { am.registerAudioPlaybackCallback(playback, main) }
            .onFailure { GLog.w("Audio", "재생 감시 등록 실패", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { setMode(it) }
            runCatching { am.addOnModeChangedListener(ctx.mainExecutor, l) }
                .onSuccess { modeListener = l }
                .onFailure { GLog.w("Audio", "통화 감시 등록 실패, 2초마다 확인", it) }
        }
        if (modeListener == null) main.postDelayed(poll, POLL_MS)
        checkMode()
        runCatching { checkNav(am.activePlaybackConfigurations) }
    }

    fun release() {
        if (!started) return
        started = false
        main.removeCallbacks(poll)
        runCatching { am.unregisterAudioPlaybackCallback(playback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modeListener?.let { l -> runCatching { am.removeOnModeChangedListener(l) } }
        modeListener = null
    }

    private fun checkMode() = setMode(runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL))

    private fun setMode(mode: Int) {
        val call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        val ring = mode == AudioManager.MODE_RINGTONE
        if (_inCall.value != call) {
            _inCall.value = call
            GLog.i("Audio", if (call) "통화 시작 (모드 $mode)" else "통화 끝")
        }
        if (_ringing.value != ring) _ringing.value = ring
    }

    // 앱에 오는 목록에는 지금 소리를 내는 재생기만 들어 있음
    private fun checkNav(configs: List<AudioPlaybackConfiguration>) {
        val nav = configs.any { runCatching { it.audioAttributes.usage }.getOrNull() == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        if (_navSpeaking.value != nav) {
            _navSpeaking.value = nav
            GLog.i("Audio", if (nav) "길 안내 음성 시작" else "길 안내 음성 끝")
        }
    }

    private companion object {
        const val POLL_MS = 2000L
    }
}
