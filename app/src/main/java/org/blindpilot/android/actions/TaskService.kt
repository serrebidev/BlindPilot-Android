package org.blindpilot.android.actions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import org.blindpilot.android.MainActivity
import org.blindpilot.android.core.Cancellation

/** Keeps a running task alive in the background and offers Stop from the notification shade. */
class TaskService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must enter the foreground, even one that only finishes the task.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Running tasks", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, TaskService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("BlindPilot task running").setContentText("Choose Stop to end it. Completed actions are not undone.")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        when (intent?.action) {
            ACTION_STOP -> cancelActive()
            ACTION_FINISH -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "tasks"
        private const val ACTION_STOP = "org.blindpilot.android.STOP"
        private const val ACTION_FINISH = "org.blindpilot.android.FINISH"
        @Volatile private var active: Cancellation? = null

        fun start(context: Context, cancellation: Cancellation) {
            active = cancellation
            // The in-app Stop control still works if Android refuses a background start.
            runCatching { context.startForegroundService(Intent(context, TaskService::class.java)) }
        }
        fun stop(context: Context) {
            active = null
            runCatching { context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_FINISH)) }
        }
        fun cancelActive() { active?.cancel() }
    }
}
