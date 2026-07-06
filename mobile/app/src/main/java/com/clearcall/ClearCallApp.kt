package com.clearcall

import android.app.Application
import com.clearcall.call.CallManager

class ClearCallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before anything else — an FCM ring push can cold-start this process with
        // no Activity involved, and CallMessagingService needs CallManager ready immediately.
        CallManager.init(this)
    }
}
