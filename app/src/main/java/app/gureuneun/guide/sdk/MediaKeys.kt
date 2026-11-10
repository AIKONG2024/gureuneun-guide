package app.gureuneun.guide.sdk

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle

/**
 * 운전대의 다음, 이전, 재생/멈춤 버튼을 받기 위한 미디어 세션.
 * 이야기 하나를 '곡' 하나처럼 다룸.
 *
 * 앱 전체에서 하나만 만들어 씀(GuideApp.media). 활동이 다시 만들어져도 새로 만들지 않음.
 * 여행이 시작될 때 activate()로 켜고, 끝날 때 deactivate()로 꺼서 운전대 키를 원래 미디어 앱(라디오, 음악)에 돌려줌.
 * 콜백은 세션을 만든 스레드(메인)에서 옴.
 */
class MediaKeys(context: Context, private val onCommand: (Command) -> Unit) {

    enum class Command { NEXT, PREVIOUS, PLAY, PAUSE }

    private var released = false
    // 마지막으로 알린 이야기. 세션을 껐다 켜도, 활동이 다시 만들어져도 그대로 다시 알림
    private var title: String? = null
    private var subtitle: String? = null
    private var playing = false

    /** 세션이 켜져 있는지 (여행 중) */
    var active = false
        private set

    private val session = MediaSession(context.applicationContext, "GureuneunGuide").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onSkipToNext() = send(Command.NEXT)
            override fun onSkipToPrevious() = send(Command.PREVIOUS)
            override fun onPlay() = send(Command.PLAY)
            override fun onPause() = send(Command.PAUSE)
            // 차 미디어 화면에서 다른 음원으로 바꾸면 정지가 옴. 이야기는 끝내지 않고 멈춤으로 둠
            override fun onStop() = send(Command.PAUSE)
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = send(Command.PLAY)
            override fun onPlayFromSearch(query: String?, extras: Bundle?) = send(Command.PLAY)
        })
    }

    private fun send(c: Command) {
        if (!active) {
            GLog.i("Media", "여행 중이 아니라 무시: $c")
            return
        }
        GLog.i("Media", "운전대 키 $c")
        onCommand(c)
    }

    /** 여행이 시작될 때 부름. 운전대 키를 이 앱이 받음. 여러 번 불러도 됨 */
    fun activate() {
        if (released) return
        active = true
        apply()
        if (!session.isActive) {
            session.isActive = true
            GLog.i("Media", "미디어 세션 켬")
        }
    }

    /** 여행이 끝날 때 부름. 운전대 키를 원래 미디어 앱에 돌려줌. 여러 번 불러도 됨 */
    fun deactivate() {
        if (released) return
        active = false
        title = null
        subtitle = null
        playing = false
        if (session.isActive) {
            // 차 미디어 화면에 '재생 중'이 남지 않게 멈춘 것으로 알리고 끔
            setState(PlaybackState.STATE_STOPPED)
            session.isActive = false
            GLog.i("Media", "미디어 세션 끔")
        }
    }

    /**
     * 지금 들려주는 이야기를 미디어 정보로 알림 (차량 미디어 화면에도 보임).
     * 세션이 꺼져 있을 때는 값만 기억했다가 activate() 때 알림.
     */
    fun publish(title: String?, subtitle: String?, playing: Boolean) {
        this.title = title
        this.subtitle = subtitle
        this.playing = playing
        if (!released && active) apply()
    }

    private fun apply() {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "구르는 가이드")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle ?: "여행 이야기")
                .build()
        )
        setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
    }

    private fun setState(state: Int) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    /** 프로세스가 끝날 때만. 보통은 deactivate() */
    fun release() {
        if (released) return
        released = true
        active = false
        session.isActive = false
        session.release()
    }
}
