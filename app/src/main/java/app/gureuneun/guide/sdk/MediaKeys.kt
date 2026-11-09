package app.gureuneun.guide.sdk

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * 운전대의 다음, 이전 버튼을 받기 위한 미디어 세션.
 * 이야기 하나를 '곡' 하나처럼 다룸.
 */
class MediaKeys(context: Context, private val onCommand: (Command) -> Unit) {

    enum class Command { NEXT, PREVIOUS, PLAY, PAUSE }

    private val session = MediaSession(context, "GureuneunGuide").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() = onCommand(Command.NEXT)
            override fun onSkipToPrevious() = onCommand(Command.PREVIOUS)
            override fun onPlay() = onCommand(Command.PLAY)
            override fun onPause() = onCommand(Command.PAUSE)
        })
    }

    fun activate() {
        session.isActive = true
        publish(null, null, false)
    }

    /** 지금 들려주는 이야기를 미디어 정보로 알림 (차량 미디어 화면에도 보임) */
    fun publish(title: String?, subtitle: String?, playing: Boolean) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "구르는 가이드")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle ?: "여행 이야기")
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0L, 1f)
                .build()
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }
}
