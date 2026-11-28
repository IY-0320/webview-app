package com.dreamland.webview

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 앱을 나가 있어도 연락 받기 (설정에서 켜고 끈다).
 *
 * 드림랜드는 앱 안의 웹 화면에서 돌아가서, 앱을 나가거나 화면이 꺼지면 안드로이드가 앱을 재우고
 * 캐릭터가 먼저 보내는 연락 · 걸려 오는 전화도 멈춘다. 작은 고정 알림을 띄워 둔 채 앱을 깨워 두면
 * 드림랜드가 계속 돌아서 연락이 온다. 드림랜드에 들어가 있는 동안만 켜지고, 일어나기를 누르면 꺼진다.
 */
class AwakeService : Service() {
    companion object {
        private const val ACTION_STOP = "com.dreamland.webview.AWAKE_STOP"

        /**
         * 지금 깨어 있는지. 다른 앱 위 팝업은 이때만 쓴다:
         * 앱이 뒤에 있으면 안드로이드(삼성)가 앱의 화면 그리기를 멈춰서 팝업 창이 비어 보였다 (S22 에서 확인)
         */
        @Volatile var running = false
            private set

        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, AwakeService::class.java)) }
        }

        fun stop(c: Context) {
            runCatching { c.stopService(Intent(c, AwakeService::class.java)) }
        }
    }

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Alerts.ensureChannels(this)
        val open = PendingIntent.getActivity(this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, AwakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Alerts.CH_AWAKE)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setColor(Alerts.PINK)
            .setContentTitle("드림랜드가 깨어 있어요")
            .setContentText("앱을 나가 있어도 연락과 전화를 받아요")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "그만 깨우기", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, Alerts.ID_AWAKE, n, type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        running = true
        if (lock == null) {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dreamland:awake").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        lock?.runCatching { if (isHeld) release() }
        lock = null
        super.onDestroy()
    }
}
