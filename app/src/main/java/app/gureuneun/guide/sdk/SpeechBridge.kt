package app.gureuneun.guide.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener

/**
 * TextToSpeech SDK 연결부 (ON_DEVICE 모드라 앱 등록 없이 동작).
 * Pleos 음성 서비스가 없는 환경에서는 안드로이드 기본 TTS 로 대신 읽음.
 */
class SpeechBridge(private val context: Context) {

    enum class Engine { NONE, PLEOS, ANDROID }

    val engine = MutableStateFlow(Engine.NONE)
    val speaking = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var pleos: TextToSpeech? = null
    private var android: AndroidTts? = null
    private var locale: Locale? = null
    private var onFinished: ((Boolean) -> Unit)? = null
    private var utteranceSeq = 0

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun finish(completed: Boolean) {
        speaking.value = false
        val cb = onFinished
        onFinished = null
        cb?.invoke(completed)
    }

    private val pleosListener = object : EventListener {
        override fun onStart() = post { speaking.value = true }
        override fun onReady() {}
        override fun onDone() = post { finish(true) }
        override fun onStop() = post { finish(false) }
        override fun onError(errorType: ErrorType) = post {
            GLog.w("TTS", "오류 $errorType")
            lastError.value = errorType.toString()
            finish(false)
        }

        override fun onUpdatedRms(rms: Double) {}
    }

    fun start() {
        if (engine.value != Engine.NONE || pleos != null) return
        val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        pleos = t
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = post {
                    t.addEventListener(pleosListener)
                    engine.value = Engine.PLEOS
                    GLog.i("TTS", "Pleos 음성 준비됨 (ON_DEVICE)")
                }

                override fun onFailure(errorType: ErrorType) = post {
                    GLog.w("TTS", "Pleos 음성 초기화 실패: $errorType, 기본 TTS 로 대신함")
                    lastError.value = errorType.toString()
                    startAndroidFallback()
                }
            })
        }.onFailure {
            GLog.w("TTS", "Pleos 음성 초기화 예외", it)
            startAndroidFallback()
        }
    }

    private fun startAndroidFallback() {
        if (android != null) return
        android = AndroidTts(context.applicationContext) { status ->
            if (status == AndroidTts.SUCCESS) {
                engine.value = Engine.ANDROID
                android?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = post { speaking.value = true }
                    override fun onDone(utteranceId: String?) = post { finish(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = post { finish(false) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = post { finish(false) }
                })
                GLog.i("TTS", "안드로이드 기본 TTS 준비됨")
            } else {
                GLog.w("TTS", "기본 TTS 도 사용할 수 없음")
            }
        }
    }

    val ready: Boolean get() = engine.value != Engine.NONE

    /** 문장 하나를 읽음. 끝까지 읽으면 true, 중간에 멈추면 false 로 콜백 */
    fun speak(text: String, lang: String, done: (Boolean) -> Unit) {
        val loc = if (lang == "en") Locale.US else Locale.KOREA
        onFinished = done
        when (engine.value) {
            Engine.PLEOS -> {
                val t = pleos ?: return finish(false)
                if (locale != loc) {
                    runCatching { t.setLocale(loc) }
                    locale = loc
                }
                speaking.value = true
                runCatching { t.speak(text, true) }.onFailure {
                    GLog.w("TTS", "speak 실패", it)
                    finish(false)
                }
            }
            Engine.ANDROID -> {
                val t = android ?: return finish(false)
                if (locale != loc) {
                    t.language = loc
                    locale = loc
                }
                speaking.value = true
                t.speak(text, AndroidTts.QUEUE_FLUSH, null, "u${utteranceSeq++}")
            }
            Engine.NONE -> {
                // 음성이 없는 환경: 글자 수만큼 기다린 것처럼 처리해 흐름은 이어지게 함
                speaking.value = true
                main.postDelayed({ finish(true) }, (text.length * 90L).coerceIn(1200L, 9000L))
            }
        }
    }

    fun stop() {
        when (engine.value) {
            Engine.PLEOS -> runCatching { pleos?.stop() }
            Engine.ANDROID -> runCatching { android?.stop() }
            Engine.NONE -> main.removeCallbacksAndMessages(null)
        }
        if (engine.value != Engine.PLEOS) finish(false)
    }

    fun release() {
        runCatching {
            pleos?.removeEventListener(pleosListener)
            pleos?.release()
        }
        runCatching { android?.shutdown() }
        pleos = null
        android = null
        engine.value = Engine.NONE
    }
}
