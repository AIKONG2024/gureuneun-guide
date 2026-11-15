package app.gureuneun.guide.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import app.gureuneun.guide.sdk.GLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 지역 연주곡과 전주. 재생하는 동안 다른 소리는 잠시 줄이고(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
 * 끝나면 원래 음량으로 돌려줌.
 */
class Sound(private val ctx: Context, private val scope: CoroutineScope) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null
    private var focus: AudioFocusRequest? = null
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** assets 경로의 소리를 재생. maxMs 가 있으면 그 시간에 맞춰 줄이며 끝냄 */
    fun play(asset: String, volume: Float = 1f, maxMs: Long? = null, fadeMs: Long = 1800, onEnd: (() -> Unit)? = null) {
        stop()
        val p = runCatching {
            val fd = ctx.assets.openFd(asset)
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                prepare()
                setVolume(volume, volume)
            }
        }.getOrElse {
            GLog.w("Sound", "재생 실패 $asset", it)
            onEnd?.invoke()
            return
        }
        requestFocus()
        player = p
        GLog.i("DEMO_EVT", "music:$asset")
        p.setOnCompletionListener {
            if (player === p) {
                release()
                onEnd?.invoke()
            }
        }
        p.start()
        if (maxMs != null) {
            fadeJob = scope.launch {
                delay((maxMs - fadeMs).coerceAtLeast(0))
                fade(p, volume, fadeMs)
                if (player === p) {
                    release()
                    onEnd?.invoke()
                }
            }
        }
    }

    private suspend fun fade(p: MediaPlayer, from: Float, ms: Long) {
        val steps = 18
        for (i in 1..steps) {
            val v = from * (1f - i / steps.toFloat())
            runCatching { p.setVolume(v, v) }
            delay(ms / steps)
        }
    }

    private fun requestFocus() {
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = r
        runCatching { am.requestAudioFocus(r) }
    }

    private fun release() {
        fadeJob?.cancel()
        fadeJob = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    fun stop() = release()
}
