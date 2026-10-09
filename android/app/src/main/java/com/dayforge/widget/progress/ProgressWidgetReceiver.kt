package com.dayforge.widget.progress

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dayforge.widget.WidgetRefreshScheduler

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
        if (appWidgetIds.isNotEmpty()) WidgetRefreshScheduler.request(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
