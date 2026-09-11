package com.dayforge.widget.focus

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Configuration activity for FocusWidget.
 * Unlike other widgets, FocusWidget doesn't require habit selection -
 * it automatically selects the top-priority habit based on time window.
 *
 * This activity just confirms widget creation and triggers initial refresh.
 */
class FocusWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get app widget ID from intent
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Set result as OK immediately (no selection needed)
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)

        // Trigger initial widget refresh
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(this@FocusWidgetConfigActivity)
                val glanceId = manager.getGlanceIdBy(appWidgetId)
                FocusWidget.refreshWidgetData(this@FocusWidgetConfigActivity, glanceId)
                FocusWidget().update(this@FocusWidgetConfigActivity, glanceId)
            } catch (e: Exception) {
                // Log error but don't crash
            }
        }

        // Finish immediately - no UI needed
        finish()
    }
}