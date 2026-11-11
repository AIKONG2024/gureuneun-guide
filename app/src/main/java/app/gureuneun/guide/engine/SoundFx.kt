package app.gureuneun.guide.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.gureuneun.guide.sdk.GLog
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 효과음은 파일 없이 코드로 만들어 재생함.
 * 시작 전에 180ms 무음을 두어(선행 묵음 가이드) 첫소리가 잘리지 않게 함.
 * 소리 포커스를 받았을 때만 냄 (통화 중이거나 다른 앱이 포커스를 쥐고 있으면 건너뜀).
 * 신호음은 USAGE_ASSISTANCE_SONIFICATION 으로 냄.
 */
class SoundFx(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    /** 짧은 신호음 (듣기 시작, 별, 차임) */
    private val fxAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val rate = 44_100
    private val focusHandler = Handler(Looper.getMainLooper())

    /** 포커스를 받았으면 그 요청을, 못 받았으면 null */
    private fun focus(attrs: AudioAttributes, listener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest? = runCatching {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener, focusHandler)
            .build()
        val r = audioManager.requestAudioFocus(req)
        if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req else {
            GLog.i("Fx", "소리 포커스를 받지 못해 건너뜀 ($r)")
            null
        }
    }.getOrNull()

    private fun track(attrs: AudioAttributes, samples: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attrs)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(samples.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()

    /** 신호음 하나. 포커스를 못 받으면 소리 없이 넘어가되 after 는 늘 부름 */
    private fun play(samples: ShortArray, after: () -> Unit = {}) {
        thread(name = "fx") {
            var playing: AudioTrack? = null
            // 통화 같은 더 중요한 소리가 오면 바로 멈춤
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) runCatching { playing?.stop() }
            }
            val req = focus(fxAttrs, listener)
            if (req != null) {
                runCatching {
                    val t = track(fxAttrs, samples)
                    playing = t
                    t.write(samples, 0, samples.size)
                    t.play()
                    Thread.sleep(samples.size * 1000L / rate + 150)
                    playing = null
                    t.release()
                }.onFailure { GLog.w("Fx", "효과음 재생 실패", it) }
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            after()
        }
    }

    /** 도시에 들어설 때와 출발할 때 쓰는 짧은 차임 */
    fun chime(rising: Boolean = true) {
        val notes = if (rising) doubleArrayOf(659.25, 830.61, 987.77, 1318.51) else doubleArrayOf(1318.51, 987.77, 830.61, 659.25)
        val pre = (rate * 0.18).toInt()
        val total = pre + (rate * 2.2).toInt()
        val buf = DoubleArray(total)
        notes.forEachIndexed { n, f ->
            val start = pre + (n * rate * 0.14).toInt()
            for (i in start until total) {
                val t = (i - start).toDouble() / rate
                val env = exp(-t * 2.6) * min(1.0, t * 200)
                buf[i] += env * (sin(2 * PI * f * t) * 0.8 + sin(4 * PI * f * t) * 0.15)
            }
        }
        play(normalize(buf, 0.45))
    }

    /** 음 여러 개를 차례로 울리는 짧은 소리. gap 은 음 사이 간격(초) */
    private fun notes(freqs: DoubleArray, gap: Double, decay: Double, length: Double, peak: Double) {
        val pre = (rate * 0.18).toInt()
        val total = pre + (rate * length).toInt()
        val buf = DoubleArray(total)
        freqs.forEachIndexed { n, f ->
            val start = pre + (n * rate * gap).toInt()
            for (i in start until total) {
                val t = (i - start).toDouble() / rate
                val env = exp(-t * decay) * min(1.0, t * 300)
                buf[i] += env * (sin(2 * PI * f * t) * 0.85 + sin(6 * PI * f * t) * 0.08)
            }
        }
        play(normalize(buf, peak))
    }

    /** 듣기 시작 신호 (음성 비서처럼 짧게 두 번 올라가는 소리) */
    fun listen() = notes(doubleArrayOf(880.0, 1174.66), 0.09, 9.0, 0.45, 0.35)

    /** 다 들었다는 신호 (내려가는 소리) */
    fun heard() = notes(doubleArrayOf(1174.66, 880.0), 0.08, 10.0, 0.4, 0.3)

    /** 퀴즈 정답, 별 하나 */
    fun star() = notes(doubleArrayOf(1318.51, 1567.98, 2093.0), 0.07, 5.0, 0.9, 0.38)

    /** 오늘 퀴즈를 다 맞혔을 때 */
    fun fanfare() = notes(doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 783.99, 1046.5, 1318.51), 0.12, 2.2, 2.4, 0.45)

    private fun normalize(buf: DoubleArray, peak: Double): ShortArray {
        var max = 1e-9
        buf.forEach { if (kotlin.math.abs(it) > max) max = kotlin.math.abs(it) }
        val k = peak / max * Short.MAX_VALUE
        return ShortArray(buf.size) { (buf[it] * k).toInt().coerceIn(-32767, 32767).toShort() }
    }
}
