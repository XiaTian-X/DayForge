package com.dayforge.widget.progress

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for 2x2 progress widget.
 * Handles widget lifecycle (add, update, delete).
 */
class ProgressWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = ProgressWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                ProgressWidget.refreshWidgetData(context)
                ProgressWidget().updateAll(context)
            } catch (e: Exception) {
                Log.e("ProgressWidget", "Refresh failed", e)
            }
        }
    }
}