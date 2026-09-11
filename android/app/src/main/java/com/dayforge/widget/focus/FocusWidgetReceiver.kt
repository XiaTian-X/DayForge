package com.dayforge.widget.focus

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidgetProvider for FocusWidget.
 * Handles widget lifecycle (add, update, delete).
 *
 * Per WIDGET-01: FocusWidget is a Glance AppWidget that displays
 * the user's top-priority habit based on time-based relevance.
 */
class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusWidget()
}