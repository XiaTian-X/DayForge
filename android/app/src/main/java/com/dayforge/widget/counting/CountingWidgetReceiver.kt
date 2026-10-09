package com.dayforge.widget.counting

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.dayforge.widget.WidgetRefreshScheduler

/** Platform lifecycle invalidates data through durable work, never detached receiver IO. */
class CountingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: CountingWidget = CountingWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isNotEmpty()) WidgetRefreshScheduler.request(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}
