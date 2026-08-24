package com.glasscast.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class LocalVideoKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Defense in depth: v1 does not declare this service and must never serve LAN video.
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        ensureChannel()
        startForeground(NOTIFICATION_ID, notification())
        return START_STICKY
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local video serving",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("GlassCast")
            .setContentText("GlassCast is serving local video.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.glasscast.sender.action.STOP_LOCAL_VIDEO_SERVICE"
        private const val CHANNEL_ID = "local_video_serving"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) return
            val intent = Intent(context, LocalVideoKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) return
            val intent = Intent(context, LocalVideoKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
