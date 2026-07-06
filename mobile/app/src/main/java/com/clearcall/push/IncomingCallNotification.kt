package com.clearcall.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import com.clearcall.IncomingCallActivity
import com.clearcall.R
import com.clearcall.call.CallInfo

/**
 * The lock-screen / full-screen incoming-call surface, via [Notification.CallStyle]
 * (API 31+, matches minSdk). The notification channel is silent — [com.clearcall.call.RingtonePlayer]
 * owns the actual ringtone so it can be started/stopped precisely with the call state.
 */
object IncomingCallNotification {
    private const val CHANNEL_ID = "incoming_calls"
    private const val NOTIF_ID = 1001

    fun show(context: Context, info: CallInfo) {
        createChannel(context)

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answerIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declineIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(info.peerName).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(info.peerName)
            .setContentText("Incoming ClearCall")
            .setSmallIcon(R.drawable.ic_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setStyle(Notification.CallStyle.forIncomingCall(person, declineIntent, answerIntent))
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }

    fun dismiss(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            description = "Full-screen incoming call alerts"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}
