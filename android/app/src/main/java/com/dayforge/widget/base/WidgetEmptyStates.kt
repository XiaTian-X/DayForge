package com.dayforge.widget.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dayforge.R

/**
 * Shared empty/error state composables for widgets.
 * Eliminates duplicate EmptyConfigState functions across widget classes.
 */
object WidgetEmptyStates {

    /**
     * Standard empty state display for unconfigured or loading widgets.
     *
     * @param message The message to display (e.g., getString(R.string.widget_configure_first))
     * @param modifier Optional GlanceModifier for additional styling (e.g., clickable)
     */
    @Composable
    fun EmptyConfigState(
        message: String,
        modifier: GlanceModifier = GlanceModifier
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color.DarkGray),
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}