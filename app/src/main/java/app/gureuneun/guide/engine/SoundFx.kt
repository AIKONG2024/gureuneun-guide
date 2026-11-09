package app.gureuneun.guide.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 효과음은 파일 없이 코드로 만들어 재생함.
 * 시작 전에 180ms 무음을 두어(선행 묵음 가이드) 첫소리가 잘리지 않게 함.
 */
class SoundFx(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val rate = 44_100
    @Volatile private var wavesTrack: AudioTrack? = null

    private fun focus(): AudioFocusRequest? = runCatching {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        audioManager.requestAudioFocus(req)
        req
    }.getOrNull()

    private fun play(samples: ShortArray, after: () -> Unit = {}) {
        thread(name = "fx") {
            val req = focus()
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(samples.size * 1000L / rate + 150)
                track.release()
            }
            req?.let { r -> runCatching { audioManager.abandonAudioFocusRequest(r) } }
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

    /** 시연용 파도 소리 (실차에서는 창문을 내리면 진짜 파도 소리가 들어옴) */
    fun waves(seconds: Int = 18) {
        stopWaves()
        val total = rate * seconds
        val buf = DoubleArray(total)
        var brown = 0.0
        var lp = 0.0
        val rnd = java.util.Random(7)
        for (i in 0 until total) {
            brown = (brown + (rnd.nextDouble() * 2 - 1) * 0.02) * 0.998
            lp += (brown - lp) * 0.08
            val t = i.toDouble() / rate
            val swell = 0.35 + 0.65 * (0.5 + 0.5 * sin(2 * PI * t / 6.5 - PI / 2))
            val fade = min(1.0, t / 2.5) * min(1.0, (seconds - t) / 3.0)
            buf[i] = lp * swell * fade
        }
        val samples = normalize(buf, 0.5)
        thread(name = "waves") {
            val req = focus()
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                wavesTrack = track
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(seconds * 1000L + 200)
                runCatching { track.release() }
                if (wavesTrack === track) wavesTrack = null
            }
            req?.let { r -> runCatching { audioManager.abandonAudioFocusRequest(r) } }
        }
    }

    fun stopWaves() {
        wavesTrack?.let { runCatching { it.stop(); it.release() } }
        wavesTrack = null
    }

    private fun normalize(buf: DoubleArray, peak: Double): ShortArray {
        var max = 1e-9
        buf.forEach { if (kotlin.math.abs(it) > max) max = kotlin.math.abs(it) }
        val k = peak / max * Short.MAX_VALUE
        return ShortArray(buf.size) { (buf[it] * k).toInt().coerceIn(-32767, 32767).toShort() }
    }
}
