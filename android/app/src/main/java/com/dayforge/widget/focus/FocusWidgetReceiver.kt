package com.dayforge.widget.focus

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dayforge.widget.WidgetRefreshScheduler

/**
 * AppWidgetProvider for FocusWidget.
 * Handles widget lifecycle (add, update, delete).
 *
 * Per WIDGET-01: FocusWidget is a Glance AppWidget that displays
 * the user's top-priority habit based on time-based relevance.
 */
class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: FocusWidget = FocusWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isNotEmpty()) WidgetRefreshScheduler.request(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
