package com.cleo.cleos.ui.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder

/**
 * MediaProjection 的前台宿主。
 *
 * Android 14（API 34）起：**只有 mediaProjection 类型的前台服务在运行时**，
 * 投屏 token 才能用来创建虚拟屏（否则 SecurityException: Requires CAPTURE_VIDEO_OUTPUT…）。
 * 这个服务只做这一件事：让探针合法地拿着 token 建屏；UI 仍由探针 Activity 负责。
 */
class MediaProjectionService : Service() {
    companion object {
        const val CHANNEL = "cleos_virtual_screen"
        @Volatile var projection: MediaProjection? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "虚拟屏探针", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("虚拟屏探针")
            .setContentText("投屏会话运行中（只用于虚拟屏验证）")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }
}
