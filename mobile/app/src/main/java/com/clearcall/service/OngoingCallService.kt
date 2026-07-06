package com.clearcall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.clearcall.MainActivity
import com.clearcall.R
import com.clearcall.call.CallState

/** Foreground service that keeps an active call alive in the background (screen off, app backgrounded). */
class OngoingCallService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startForegroundCompat() {
        createChannel()
        val peerName = CallState.current.value?.peerName ?: "ClearCall"
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(peerName)
            .setContentText("Call in progress")
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
        startForeground(
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ongoing call", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ongoing_call"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OngoingCallService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OngoingCallService::class.java))
        }
    }
}
