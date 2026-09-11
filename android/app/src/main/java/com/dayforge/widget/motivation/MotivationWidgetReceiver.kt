package com.dayforge.widget.motivation

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
 * AppWidgetProvider for motivation widget.
 * Handles widget lifecycle (add, update, delete).
 */
class MotivationWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = MotivationWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                MotivationWidget.refreshWidgetData(context)
                MotivationWidget().updateAll(context)
            } catch (e: Exception) {
                Log.e("MotivationWidget", "Refresh failed", e)
            }
        }
    }
}