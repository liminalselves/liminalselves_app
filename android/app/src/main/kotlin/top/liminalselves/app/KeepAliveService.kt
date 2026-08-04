package top.liminalselves.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：保持 App 在后台存活，从而维持 WebView 中的 WebSocket 连接
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        // 使用新渠道 ID，避免旧渠道重要性无法降级的问题。
        // 公开供权限向导页（PermissionSetupActivity）检查渠道开关状态。
        const val CHANNEL_ID = "liminalselves_keepalive_v2"
        private const val OLD_CHANNEL_ID = "liminalselves_keepalive"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "top.liminalselves.app.START_KEEPALIVE"
        const val ACTION_STOP = "top.liminalselves.app.STOP_KEEPALIVE"

        /** 确保保活渠道存在（幂等），供服务自身与权限向导页共用。 */
        fun ensureChannel(context: android.content.Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            // 删除旧渠道（可能是 IMPORTANCE_LOW，无法降级）
            manager.getNotificationChannel(OLD_CHANNEL_ID)?.let { manager.deleteNotificationChannel(OLD_CHANNEL_ID) }
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "保活服务",
                        NotificationManager.IMPORTANCE_MIN,
                    ).apply {
                        description = "保持应用在后台运行，维持消息连接"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        Log.d(TAG, "KeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping KeepAliveService")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Starting KeepAliveService")
                startForeground(NOTIFICATION_ID, createNotification())
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14+（API 34）对 dataSync 类型前台服务有累计时长限制（约 6 小时/24h 窗口），
     * 超限后系统会回调此方法并停止服务。这里做收尾清理；
     * 超长后台场景的离线送达需依赖厂商推送通道，而非前台服务保活。
     */
    override fun onTimeout(timeoutType: Int) {
        Log.w(TAG, "KeepAliveService timeout (type=$timeoutType), stopping; long background relies on vendor push")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("阈界人格")
            .setContentText("保持连接中...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
