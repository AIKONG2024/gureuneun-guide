package app.gureuneun.guide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.gureuneun.guide.engine.TripEngine
import app.gureuneun.guide.sdk.GLog

/**
 * 여행 중에 프로세스를 지키는 포그라운드 서비스 (종류 mediaPlayback).
 * 내비 화면이 앞에 와서 이 앱이 뒤로 가도 이야기, 근접 알림, 차 상태 반응이 이어지게 함.
 * 시작, 알림 갱신, 중지는 GuideApp.tripActive(on, title) 로만 함.
 *
 * P 에서 화면이 꺼지면(시동을 끈 것으로 봄) 이야기를 멈춰 두고, 다시 D 로 출발하면 다음 장부터 이어 감.
 * 프로세스가 죽으면 다시 띄우지 않음(START_NOT_STICKY). 이어 가기는 홈 화면의 [이어 가기]로.
 */
class TripService : Service() {

    private var foreground = false
    private var screenWatch: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        watchScreen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            // 뒤에 시작 요청이 또 와 있으면(바로 다음 여행) 멈추지 않음
            if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            running = false
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, build(this, intent.getStringExtra(EXTRA_TITLE)), type)
            foreground = true
            running = true
            GLog.i("Trip", "포그라운드 서비스 켬")
        } catch (e: Exception) {
            // 서비스 없이도 여행은 이어 감. 다만 뒤로 가 있는 동안 프로세스가 정리될 수 있음
            GLog.w("Trip", "포그라운드 서비스 시작 실패", e)
            foreground = false
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        screenWatch?.let { runCatching { unregisterReceiver(it) } }
        screenWatch = null
        foreground = false
        running = false
        GLog.i("Trip", "포그라운드 서비스 끔")
        super.onDestroy()
    }

    private fun watchScreen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != Intent.ACTION_SCREEN_OFF) return
                val app = application as GuideApp
                // 달리는 중에 화면만 끈 것은 그대로 둠. P 로 확인됐을 때만 시동을 끈 것으로 봄
                if (!app.vehicle.state.value.parked) return
                GLog.i("Trip", "정차 중 화면 꺼짐: 이야기를 멈춤으로 둠")
                app.engine.pauseForBreak()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenWatch = r
        }.onFailure { GLog.w("Trip", "화면 꺼짐 감시 등록 실패", it) }
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTIFY_ID = 1107
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "app.gureuneun.guide.action.TRIP_STOP"

        /** 포그라운드로 돌고 있는지 */
        @Volatile var running = false
            private set

        /** 여행 시작. 앱이 앞에 있을 때 불러야 함 (뒤에서는 안드로이드 12부터 막힘) */
        fun start(ctx: Context, title: String?): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java).putExtra(EXTRA_TITLE, title))
            true
        } catch (e: Exception) {
            GLog.w("Trip", "포그라운드 서비스를 시작하지 못함", e)
            false
        }

        /** 알림 글만 바꿈 (서비스를 다시 부르지 않음) */
        fun update(ctx: Context, title: String?) {
            runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, build(ctx, title)) }
                .onFailure { GLog.w("Trip", "알림 갱신 실패", it) }
        }

        /** 서비스에 멈춤 명령을 보냄. 시작 처리 전에 바로 stopService 하면 '포그라운드 시작 안 함'으로 앱이 죽을 수 있어서 */
        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, TripService::class.java).setAction(ACTION_STOP)) }
                .onFailure { runCatching { ctx.stopService(Intent(ctx, TripService::class.java)) } }
        }

        private fun build(ctx: Context, title: String?): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "여행 중", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "여행하는 동안 이야기와 길 알림을 이어 가는 중이라는 표시"
                        setShowBadge(false)
                    }
                )
            }
            val en = title != null && title.isNotBlank() && title.none { it in '가'..'힣' }
            val text = when {
                en -> "Rolling Guide · ${title} trip"
                title.isNullOrBlank() -> "구르는 가이드 · 여행 중"
                else -> "구르는 가이드 · ${title} 여행 중"
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_trip)
                .setColor(ContextCompat.getColor(ctx, R.color.accent))
                .setContentTitle(text)
                .setContentText(if (en) "Stories and route alerts keep going on other screens." else "다른 화면을 보는 동안에도 이야기와 길 알림이 이어져요.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
