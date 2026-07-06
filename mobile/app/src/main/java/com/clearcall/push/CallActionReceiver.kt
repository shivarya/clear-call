package com.clearcall.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clearcall.call.CallManager

/** Handles the CallStyle notification's Answer/Decline actions without opening any UI. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answerCurrentCall()
            ACTION_DECLINE -> CallManager.declineCurrentCall()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.clearcall.action.ANSWER"
        const val ACTION_DECLINE = "com.clearcall.action.DECLINE"
    }
}
