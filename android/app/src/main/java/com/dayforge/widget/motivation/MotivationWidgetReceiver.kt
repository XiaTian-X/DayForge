package com.dayforge.widget.motivation

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dayforge.widget.WidgetRefreshScheduler

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
        if (appWidgetIds.isNotEmpty()) WidgetRefreshScheduler.request(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
