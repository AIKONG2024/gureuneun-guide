package app.gureuneun.guide.sdk

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SpeechToText SDK 연결부. ON_DEVICE 모드라 앱 등록 없이 차 안에서 인식함.
 * - listen(): 차 마이크로 듣기 (request)
 * - feed(): 앱이 가진 PCM(16kHz, 16비트, 모노)을 실시간 속도로 넣어 인식 (sendAudio, 시연과 시험용)
 * 한 문장을 받으면 문서대로 stop() 을 불러 인식을 끝냄. 그 뒤에 오는 콜백은 무시함.
 * 마이크를 쓸 수 없거나(MIC_ERROR) 권한이 없으면(PERMISSION_DENIED) problem 으로 알림 (화면에서 안내).
 * 초기화에 실패하면 3초, 10초, 30초 뒤에 다시 시도함.
 */
class ListenBridge(private val context: Context) {

    enum class Status { OFF, READY, LISTENING, ERROR }

    val status = MutableStateFlow(Status.OFF)
    /** 인식 중인 글자 (중간 결과) */
    val partial = MutableStateFlow("")
    /** 입력 소리 크기. 듣는 중 표시에 씀 */
    val level = MutableStateFlow(0.0)
    val lastError = MutableStateFlow<String?>(null)
    private val _problem = MutableStateFlow<String?>(null)
    /** 듣기를 막는 문제: "MIC_ERROR"(마이크, 센서 개인정보 보호 설정 확인), "PERMISSION_DENIED"(STT_SERVICE 권한). 한 번 알아들으면 null */
    val problem: StateFlow<String?> = _problem

    private var stt: SpeechToText? = null
    private val main = Handler(Looper.getMainLooper())
    private var onFinal: ((String) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null
    private var done = true
    private var session = 0
    private var startedLogged = 0
    private var initTries = 0
    /** 결과를 받고 stop() 을 부른 때. 그 직후에 오는 요청 상태 오류는 stop 때문으로 봄 */
    private var stoppedAt = 0L
    // 인식기가 소리를 받을 준비가 되면 열림 (feed 가 그때부터 음성을 흘려 넣음)
    @Volatile private var readyLatch: CountDownLatch? = null

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private val listener = object : ResultListener {
        override fun onReady() {
            readyLatch?.countDown()
        }

        override fun onStartedRecognition() = post {
            status.value = Status.LISTENING
            // 엔진이 말소리 구간마다 여러 번 알려 주므로 한 번만 기록
            if (startedLogged != session) {
                startedLogged = session
                GLog.i("STT", "말소리 들림")
            }
        }

        override fun onUpdated(stt: String, completed: Boolean) = post {
            if (done) return@post
            partial.value = stt
            if (completed && stt.isNotBlank()) deliver(stt)
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {}

        override fun onUpdatedRms(rms: Double) = post { level.value = rms }

        override fun onEndedRecognition() = post {
            GLog.i("STT", "듣기 끝")
            if (done) return@post
            // 엔진이 '듣기 끝'을 먼저 알리고 완성 문장을 뒤이어 보내므로 잠깐 기다림
            val mine = session
            main.postDelayed({
                if (session != mine || done) return@postDelayed
                val t = partial.value
                if (t.isNotBlank()) deliver(t) else fail("NO_SPEECH")
            }, 1200)
        }

        override fun onError(errorType: ErrorType) = post {
            GLog.w("STT", "오류 $errorType")
            lastError.value = errorType.name
            when (errorType.name) {
                "MIC_ERROR", "PERMISSION_DENIED" -> _problem.value = errorType.name
                // 결과를 받고 stop() 을 부른 직후의 요청 상태 오류는 새로 시작한 듣기와 관계없음
                "INVALID_REQUEST_ERROR" -> if (SystemClock.uptimeMillis() - stoppedAt < STOP_ECHO_MS) {
                    GLog.i("STT", "stop 뒤에 온 오류라 무시")
                    return@post
                }
            }
            if (!done) fail(errorType.name)
        }
    }

    private fun deliver(text: String) {
        val cb = onFinal
        GLog.i("STT", "인식: $text")
        finish()
        _problem.value = null
        // 문서: 인식이 끝나면 stop() 을 호출. 다음 듣기를 시작하기 전에 부름
        stoppedAt = SystemClock.uptimeMillis()
        runCatching { stt?.stop() }
        cb?.invoke(text.trim())
    }

    private fun fail(reason: String) {
        val cb = onFail
        finish()
        cb?.invoke(reason)
    }

    private fun finish() {
        done = true
        onFinal = null
        onFail = null
        level.value = 0.0
        if (status.value == Status.LISTENING) status.value = Status.READY
    }

    fun start() {
        if (stt != null) return
        val s = runCatching { SpeechToText(context.applicationContext, Mode.ON_DEVICE) }.getOrElse {
            status.value = Status.ERROR
            lastError.value = it.message
            GLog.w("STT", "음성 인식을 만들지 못함", it)
            retryInit()
            return
        }
        stt = s
        runCatching {
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() = post {
                    s.addListener(listener)
                    status.value = Status.READY
                    initTries = 0
                    GLog.i("STT", "Pleos 음성 인식 준비됨 (ON_DEVICE)")
                }

                override fun onFailure(errorType: ErrorType) = post {
                    status.value = Status.ERROR
                    lastError.value = errorType.name
                    if (errorType.name == "PERMISSION_DENIED") _problem.value = errorType.name
                    GLog.w("STT", "음성 인식 초기화 실패: $errorType")
                    retryInit()
                }
            })
        }.onFailure {
            status.value = Status.ERROR
            lastError.value = it.message
            GLog.w("STT", "음성 인식 초기화 예외", it)
            retryInit()
        }
    }

    /** 초기화 실패 뒤 다시 시도 (3초, 10초, 30초) */
    private fun retryInit() {
        val wait = INIT_RETRY_MS.getOrNull(initTries) ?: return
        initTries++
        GLog.i("STT", "${wait / 1000}초 뒤 음성 인식 다시 준비")
        main.postDelayed({
            if (status.value != Status.ERROR) return@postDelayed
            runCatching { stt?.release() }
            stt = null
            start()
        }, wait)
    }

    val ready: Boolean get() = status.value == Status.READY || status.value == Status.LISTENING

    private fun begin(lang: String, ok: (String) -> Unit, no: (String) -> Unit, timeoutMs: Long): SpeechToText? {
        val s = stt
        if (s == null || !ready) {
            no("NOT_READY")
            return null
        }
        if (!done) runCatching { s.stop() }
        done = false
        onFinal = ok
        onFail = no
        partial.value = ""
        status.value = Status.LISTENING
        runCatching { s.setLocale(if (lang == "en") Locale.US else Locale.KOREA) }
        val mine = ++session
        GLog.i("STT", "듣기 요청 (${if (lang == "en") "영어" else "한국어"})")
        // 엔진이 끝을 알려 주지 않을 때를 대비한 안전장치
        main.postDelayed({ if (session == mine && !done) { runCatching { s.stop() }; val t = partial.value; if (t.isNotBlank()) deliver(t) else fail("TIMEOUT") } }, timeoutMs)
        return s
    }

    /** 차 마이크로 한 문장을 들음 */
    fun listen(lang: String, ok: (String) -> Unit, no: (String) -> Unit) {
        val s = begin(lang, ok, no, 20000) ?: return
        runCatching { s.request() }.onFailure {
            GLog.w("STT", "request 실패", it)
            fail("REQUEST_FAILED")
        }
    }

    /** PCM(16kHz, 16비트, 모노)을 실시간 속도로 넣어 인식시킴 */
    fun feed(pcm: ByteArray, lang: String, ok: (String) -> Unit, no: (String) -> Unit) {
        // 에뮬레이터의 차 안 엔진은 결과가 늦게 나올 때가 있어 넉넉히 기다림
        val latch = CountDownLatch(1)
        readyLatch = latch
        val s = begin(lang, ok, no, 18000L + pcm.size / 32L) ?: return
        val mine = session
        Thread {
            runCatching {
                // 인식기가 준비됐다고 알려 줄 때까지 기다렸다가 넣음 (앞부분이 잘리지 않게).
                // 에뮬레이터는 파일을 넣을 때 준비 신호를 주지 않아 1.5초만 기다림 (질문 파일 앞에 무음이 1초쯤 있음)
                val ready = latch.await(1500, TimeUnit.MILLISECONDS)
                GLog.i("STT", if (ready) "인식기 준비됨, 음성 넣기 시작" else "준비 신호 없이 음성 넣기 시작")
                var i = 0
                while (i < pcm.size && session == mine && !done) {
                    val end = minOf(pcm.size, i + 3200)
                    s.sendAudio(pcm.copyOfRange(i, end))
                    i = end
                    Thread.sleep(100)
                }
                s.completeAudioSend()
            }.onFailure {
                GLog.w("STT", "오디오 넣기 실패", it)
                post { if (session == mine && !done) fail("FEED_FAILED") }
            }
        }.start()
    }

    fun cancel() {
        if (done) return
        runCatching { stt?.stop() }
        fail("CANCELLED")
    }

    fun release() {
        runCatching {
            stt?.removeListener(listener)
            stt?.release()
        }
        stt = null
        status.value = Status.OFF
    }

    private companion object {
        val INIT_RETRY_MS = listOf(3_000L, 10_000L, 30_000L)
        const val STOP_ECHO_MS = 1_000L
    }
}
