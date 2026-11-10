package app.gureuneun.guide.sdk

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener

/** 문장 하나를 소리 내어 읽는 쪽. StoryPlayer 가 씀 (시험에서는 가짜로 바꿔 끼움). 메인 스레드에서만 부름 */
interface Speaker {
    /** 발화가 끝난 까닭. DONE 끝까지 읽음, STOPPED 멈춤(stop, 다음 발화, 바깥에서 멈춤), ERROR 엔진 오류, TIMEOUT 감시 시간 초과 */
    enum class End { DONE, STOPPED, ERROR, TIMEOUT }

    /** 문장 하나를 읽음. 끝나면 onEnd 를 메인 스레드에서 한 번만 부름 (바로 부를 때도 있음). 발화 번호를 돌려줌 */
    fun utter(text: String, lang: String, onEnd: (End) -> Unit): Long

    /** 지금 읽는 발화를 멈춤. 그 발화의 onEnd 는 STOPPED */
    fun stop()

    companion object {
        /** 감시 시간: 글자 수 × (한국어 125ms, 영어 70ms) × 2 + 4초, 최대 30초 */
        fun watchMs(text: String, lang: String): Long =
            (text.length * (if (lang == "en") 70L else 125L) * 2 + 4000L).coerceAtMost(30_000L)
    }
}

/**
 * TextToSpeech SDK 연결부 (ON_DEVICE 모드라 앱 등록 없이 동작).
 * Pleos 음성 서비스가 없는 환경에서는 안드로이드 기본 TTS 로 대신 읽음.
 *
 * Pleos 콜백(onStart, onDone, onStop, onError)에는 어느 문장의 것인지 알려 주는 값이 없어서 발화에 번호를 붙여 관리함.
 * - 한 번에 한 문장만 읽고, 앞 문장을 멈췄으면 멈춤이 끝났다는 콜백(onStop 등)을 받은 뒤에 다음 문장을 시작함
 *   (늦게 온 앞 문장 콜백이 새 문장을 끝내 버리지 않게). 1.5초 안에 콜백이 없으면 그냥 시작함.
 * - 문장 길이로 감시 시간을 정해, 그 안에 끝나지 않으면 멈추고 TIMEOUT 으로 알림.
 * - 엔진을 준비하는 동안 온 문장은 준비가 끝나면 읽음 (소리 없이 넘어가지 않게).
 * - 안드로이드 기본 TTS 는 utteranceId 로 지난 발화 콜백을 걸러 냄.
 * AudioAttributes 는 따로 주지 않음 (Pleos 기본값 USAGE_MEDIA, CONTENT_TYPE_SPEECH). 바꾸면 AudioWatch.navSpeaking 판정도 바꿔야 함.
 * 공개 함수는 모두 메인 스레드에서 부름.
 */
class SpeechBridge(private val context: Context) : Speaker {

    enum class Engine { NONE, PLEOS, ANDROID }

    val engine = MutableStateFlow(Engine.NONE)
    val speaking = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var pleos: TextToSpeech? = null
    private var android: AndroidTts? = null
    private var pleosLocale: Locale? = null
    private var androidLocale: Locale? = null
    /** 엔진을 준비하는 중 (이때 온 문장은 준비가 끝나면 읽음) */
    private var initializing = false

    private class Utterance(val id: Long, val text: String, val lang: String, val onEnd: (Speaker.End) -> Unit) {
        var via = Engine.NONE
    }

    private var seq = 0L
    /** 지금 읽는 발화 */
    private var current: Utterance? = null
    private var currentStartedAt = 0L
    private var currentHeardStart = false
    /** 앞 발화를 멈추고, 엔진이 멈췄다고 알려 오기를 기다리는 중 */
    private var stopping = false
    /** 멈춤이나 준비가 끝나면 읽을 발화 */
    private var queued: Utterance? = null

    private val watchdog = Runnable {
        val u = current ?: return@Runnable
        GLog.w("TTS", "감시 시간 안에 끝나지 않아 멈춤: \u0022" + u.text.take(24) + "\u0022")
        halt(Speaker.End.TIMEOUT)
    }
    private val stopWait = Runnable {
        if (stopping) GLog.w("TTS", "멈춤 확인이 오지 않아 다음 문장을 시작함")
        settle()
    }
    private val initGiveUp = Runnable {
        if (!initializing) return@Runnable
        GLog.w("TTS", "음성 엔진을 준비하지 못함. 소리 없이 글자 수만큼 기다리며 이어 감")
        engineReady()
    }
    private val initWait = Runnable {
        if (!initializing) return@Runnable
        GLog.w("TTS", "Pleos 음성 준비가 늦어 기본 TTS 로 대신함")
        startAndroidFallback()
        main.postDelayed(initGiveUp, INIT_WAIT_MS)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private val pleosListener = object : EventListener {
        override fun onStart() = post { onPleosStart() }
        override fun onReady() {}
        override fun onDone() = post { onPleosEnd(Speaker.End.DONE) }
        override fun onStop() = post { onPleosEnd(Speaker.End.STOPPED) }
        override fun onError(errorType: ErrorType) = post {
            GLog.w("TTS", "오류 $errorType")
            lastError.value = errorType.toString()
            onPleosEnd(Speaker.End.ERROR)
        }

        override fun onUpdatedRms(rms: Double) {}
    }

    private val androidListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = post {
            val u = current
            if (u != null && idOf(utteranceId) == u.id) {
                currentHeardStart = true
                speaking.value = true
            }
        }

        override fun onDone(utteranceId: String?) = post { idOf(utteranceId)?.let { end(it, Speaker.End.DONE) } }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = post { idOf(utteranceId)?.let { end(it, Speaker.End.ERROR) } }

        override fun onError(utteranceId: String?, errorCode: Int) = post {
            lastError.value = "android tts $errorCode"
            idOf(utteranceId)?.let { end(it, Speaker.End.ERROR) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) = post { idOf(utteranceId)?.let { end(it, Speaker.End.STOPPED) } }
    }

    private fun idOf(utteranceId: String?): Long? = utteranceId?.removePrefix("u")?.toLongOrNull()

    fun start() {
        if (engine.value != Engine.NONE || pleos != null || initializing) return
        initializing = true
        main.postDelayed(initWait, INIT_WAIT_MS)
        val t = runCatching { TextToSpeech(context.applicationContext, Mode.ON_DEVICE) }.getOrElse {
            GLog.w("TTS", "Pleos 음성을 만들지 못함, 기본 TTS 로 대신함", it)
            startAndroidFallback()
            return
        }
        pleos = t
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = post {
                    t.addEventListener(pleosListener)
                    engine.value = Engine.PLEOS
                    GLog.i("TTS", "Pleos 음성 준비됨 (ON_DEVICE)")
                    engineReady()
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
        android = runCatching {
            AndroidTts(context.applicationContext) { status ->
                post {
                    if (status == AndroidTts.SUCCESS) {
                        android?.setOnUtteranceProgressListener(androidListener)
                        if (engine.value == Engine.NONE) engine.value = Engine.ANDROID
                        GLog.i("TTS", "안드로이드 기본 TTS 준비됨")
                    } else {
                        GLog.w("TTS", "기본 TTS 도 사용할 수 없음")
                    }
                    engineReady()
                }
            }
        }.onFailure {
            GLog.w("TTS", "기본 TTS 를 만들지 못함", it)
            post { engineReady() }
        }.getOrNull()
    }

    /** 엔진 준비가 끝남 (실패도 포함). 기다리던 문장을 읽음 */
    private fun engineReady() {
        initializing = false
        main.removeCallbacks(initWait)
        main.removeCallbacks(initGiveUp)
        flush()
    }

    val ready: Boolean get() = engine.value != Engine.NONE

    /** 문장 하나를 읽음. 끝까지 읽으면 true, 멈추거나 실패하면 false 로 콜백 (예전 방식, 점검 화면에서 씀) */
    fun speak(text: String, lang: String, done: (Boolean) -> Unit) {
        utter(text, lang) { done(it == Speaker.End.DONE) }
    }

    override fun utter(text: String, lang: String, onEnd: (Speaker.End) -> Unit): Long {
        val u = Utterance(++seq, text, lang, onEnd)
        // 앞 발화가 남아 있으면 멈춤 (그 발화는 STOPPED)
        if (current != null) halt(Speaker.End.STOPPED)
        // 앞 발화의 콜백 안에서 더 새 문장이 들어왔으면 이 문장은 읽지 않음
        if (u.id != seq) {
            onEnd(Speaker.End.STOPPED)
            return u.id
        }
        queued?.let { old ->
            queued = null
            old.onEnd(Speaker.End.STOPPED)
        }
        if (stopping || initializing) queued = u else begin(u)
        return u.id
    }

    override fun stop() {
        queued?.let { q ->
            queued = null
            q.onEnd(Speaker.End.STOPPED)
        }
        halt(Speaker.End.STOPPED)
    }

    private fun begin(u: Utterance) {
        current = u
        currentStartedAt = SystemClock.uptimeMillis()
        currentHeardStart = false
        speaking.value = true
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, Speaker.watchMs(u.text, u.lang))
        val loc = if (u.lang == "en") Locale.US else Locale.KOREA
        u.via = engine.value
        when (u.via) {
            Engine.PLEOS -> {
                val t = pleos ?: return end(u.id, Speaker.End.ERROR)
                if (pleosLocale != loc) {
                    runCatching { t.setLocale(loc) }
                    pleosLocale = loc
                }
                runCatching { t.speak(u.text, true) }.onFailure {
                    GLog.w("TTS", "speak 실패", it)
                    end(u.id, Speaker.End.ERROR)
                }
            }
            Engine.ANDROID -> {
                val t = android ?: return end(u.id, Speaker.End.ERROR)
                if (androidLocale != loc) {
                    runCatching { t.language = loc }
                    androidLocale = loc
                }
                val r = runCatching { t.speak(u.text, AndroidTts.QUEUE_FLUSH, null, "u" + u.id) }.getOrDefault(AndroidTts.ERROR)
                if (r != AndroidTts.SUCCESS) end(u.id, Speaker.End.ERROR)
            }
            Engine.NONE -> {
                // 음성이 없는 환경: 글자 수만큼 기다린 것처럼 처리해 흐름은 이어지게 함
                val id = u.id
                main.postDelayed({ end(id, Speaker.End.DONE) }, (u.text.length * 90L).coerceIn(1200L, 9000L))
            }
        }
    }

    /** 이 번호의 발화가 지금 읽는 발화일 때만 끝냄 (지난 발화의 늦은 콜백은 버림) */
    private fun end(id: Long, how: Speaker.End) {
        val u = current ?: return
        if (u.id != id) return
        current = null
        main.removeCallbacks(watchdog)
        speaking.value = false
        u.onEnd(how)
    }

    /** 지금 발화를 멈추고 how 로 알림. Pleos 는 멈춤 확인이 올 때까지 다음 발화를 미룸 */
    private fun halt(how: Speaker.End) {
        val u = current ?: return
        current = null
        main.removeCallbacks(watchdog)
        speaking.value = false
        when (u.via) {
            Engine.PLEOS -> {
                stopping = true
                main.removeCallbacks(stopWait)
                main.postDelayed(stopWait, STOP_WAIT_MS)
                runCatching { pleos?.stop() }.onFailure {
                    GLog.w("TTS", "stop 실패", it)
                    settle()
                }
            }
            Engine.ANDROID -> runCatching { android?.stop() }
            Engine.NONE -> Unit
        }
        u.onEnd(how)
    }

    private fun onPleosStart() {
        val u = current ?: return
        if (u.via != Engine.PLEOS) return
        currentHeardStart = true
        speaking.value = true
    }

    private fun onPleosEnd(how: Speaker.End) {
        // 앞 발화를 멈추고 기다리던 확인
        if (stopping) {
            settle()
            return
        }
        // 읽는 것이 없을 때 온 콜백은 버림
        val u = current ?: return
        if (u.via != Engine.PLEOS) return
        // 새 문장을 시작한 직후, 시작 알림(onStart)도 오기 전에 온 끝, 멈춤 콜백은 앞 문장의 늦은 콜백으로 보고 버림
        if (how != Speaker.End.ERROR && !currentHeardStart && SystemClock.uptimeMillis() - currentStartedAt < STALE_MS) {
            GLog.i("TTS", "지난 발화의 늦은 콜백 무시 ($how)")
            return
        }
        end(u.id, how)
    }

    /** 앞 발화의 멈춤이 끝남: 기다리던 발화를 시작 */
    private fun settle() {
        if (!stopping) return
        stopping = false
        main.removeCallbacks(stopWait)
        flush()
    }

    private fun flush() {
        if (stopping || initializing || current != null) return
        val q = queued ?: return
        queued = null
        begin(q)
    }

    fun release() {
        stop()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(stopWait)
        main.removeCallbacks(initWait)
        main.removeCallbacks(initGiveUp)
        runCatching {
            pleos?.removeEventListener(pleosListener)
            pleos?.release()
        }
        runCatching { android?.shutdown() }
        pleos = null
        android = null
        stopping = false
        initializing = false
        engine.value = Engine.NONE
    }

    private companion object {
        /** stop 뒤 멈춤 확인을 기다리는 시간 */
        const val STOP_WAIT_MS = 1500L
        /** 새 문장 시작 직후 이 안에 온 끝 콜백은 앞 문장 것으로 봄 */
        const val STALE_MS = 300L
        /** 엔진 준비를 기다리는 시간 */
        const val INIT_WAIT_MS = 8000L
    }
}
