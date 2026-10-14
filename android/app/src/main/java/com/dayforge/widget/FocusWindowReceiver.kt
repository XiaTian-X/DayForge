package com.dayforge.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for FocusWidget time window refresh.
 * Receives ACTION_FOCUS_WINDOW_UPDATE and triggers FocusWidget update.
 *
 * Per SYS-02: Triggered by AlarmManager when scheduled refresh time arrives.
 *
 * Enqueues durable current-data loading/rendering/alarm reconciliation. The broadcast never
 * owns a potentially long retained-history audit or detached coroutine.
 */
class FocusWindowReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FocusWindowReceiver"
        const val ACTION_FOCUS_WINDOW_UPDATE = "com.dayforge.FOCUS_WINDOW_UPDATE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FOCUS_WINDOW_UPDATE) {
            Log.d(TAG, "Received FOCUS_WINDOW_UPDATE broadcast, refreshing FocusWidget")

            WidgetRefreshScheduler.request(context)
        }
    }
}
