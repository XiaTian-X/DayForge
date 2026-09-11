package com.dayforge.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.dayforge.widget.focus.FocusWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for FocusWidget time window refresh.
 * Receives ACTION_FOCUS_WINDOW_UPDATE and triggers FocusWidget update.
 *
 * Per SYS-02: Triggered by AlarmManager when scheduled refresh time arrives.
 *
 * 改进：先刷新数据（重新计算优先级和时间），再更新UI，最后调度下一次刷新。
 */
class FocusWindowReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FocusWindowReceiver"
        const val ACTION_FOCUS_WINDOW_UPDATE = "com.dayforge.FOCUS_WINDOW_UPDATE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FOCUS_WINDOW_UPDATE) {
            Log.d(TAG, "Received FOCUS_WINDOW_UPDATE broadcast, refreshing FocusWidget")

            val pendingResult = goAsync()
            val appContext = context.applicationContext

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 先刷新数据（重新计算优先级和matchResult）
                    FocusWidget.refreshWidgetData(appContext)

                    // 再更新UI
                    FocusWidget().updateAll(appContext)

                    Log.d(TAG, "FocusWidget refresh completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh FocusWidget", e)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.d(TAG, "pendingResult.finish() threw exception (safe to ignore)")
                    }
                }
            }
        }
    }
}