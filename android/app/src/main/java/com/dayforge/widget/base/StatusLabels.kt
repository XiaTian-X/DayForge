package com.dayforge.widget.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dayforge.R

/**
 * Shared status label composables for widgets.
 * Provides consistent styling for status indicators across widgets.
 * All labels use high-contrast white background with rounded corners (Android S+).
 */
object StatusLabels {

    /**
     * Adaptive font size for habit name based on name length.
     * Shorter names get larger font for better visibility.
     *
     * @param name The habit name
     * @return TextUnit with appropriate font size
     */
    fun getAdaptiveFontSize(name: String): TextUnit {
        return when {
            name.length <= 4 -> 16.sp
            name.length <= 8 -> 14.sp
            else -> 12.sp
        }
    }

    // ==================== Internal implementations with configurable sizing ====================

    /**
     * Internal failed label with configurable sizing for adaptive layouts.
     */
    @Composable
    internal fun FailedLabelInternal(
        paddingHorizontal: Dp,
        paddingVertical: Dp,
        fontSize: TextUnit,
        text: String
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(16.dp)
                .background(Color.White.copy(alpha = 0.95f))
                .padding(horizontal = paddingHorizontal, vertical = paddingVertical)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color.Red)
                )
            )
        }
    }

    /**
     * Internal goal reached label with configurable sizing for adaptive layouts.
     */
    @Composable
    internal fun GoalReachedLabelInternal(
        paddingHorizontal: Dp,
        paddingVertical: Dp,
        fontSize: TextUnit,
        text: String
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(16.dp)
                .background(Color.White.copy(alpha = 0.95f))
                .padding(horizontal = paddingHorizontal, vertical = paddingVertical)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color(0xFF4CAF50))
                )
            )
        }
    }

    /**
     * Internal non-check-in day label with configurable sizing for adaptive layouts.
     */
    @Composable
    internal fun NonCheckInDayLabelInternal(
        paddingHorizontal: Dp,
        paddingVertical: Dp,
        fontSize: TextUnit,
        text: String
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(16.dp)
                .background(Color.White.copy(alpha = 0.95f))
                .padding(horizontal = paddingHorizontal, vertical = paddingVertical)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color(0xFF424242))
                )
            )
        }
    }

    /**
     * Internal mode label with configurable sizing for adaptive layouts.
     */
    @Composable
    internal fun ModeLabelInternal(
        text: String,
        paddingHorizontal: Dp,
        paddingVertical: Dp,
        fontSize: TextUnit
    ) {
        Box(
            modifier = GlanceModifier
                .cornerRadius(16.dp)
                .background(Color.White.copy(alpha = 0.95f))
                .padding(horizontal = paddingHorizontal, vertical = paddingVertical)
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(Color(0xFF424242))
                )
            )
        }
    }

    // ==================== Unified Labels Row ====================

    /**
     * Unified row of labels for mode and non-check-in day indicators.
     *
     * NOTE: Failed/GoalReached labels are NOT shown here to avoid duplication.
     * The main content area displays "已失败"/"目标已完成" prominently,
     * so top labels only show mode text and non-check-in day indicator.
     *
     * @param hasFailed Whether the habit has failed (used to suppress non-check-in label)
     * @param isGoalReached Whether the goal has been reached (used to suppress non-check-in label)
     * @param isCheckInAllowed Whether check-in is allowed today
     * @param modeText Optional mode text, null for no mode label
     */
    @Composable
    fun UnifiedLabelsRow(
        hasFailed: Boolean,
        isGoalReached: Boolean,
        isCheckInAllowed: Boolean,
        modeText: String? = null
    ) {
        val paddingHorizontal = 8.dp
        val paddingVertical = 4.dp
        val fontSize = 9.sp
        val context = LocalContext.current

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Non-check-in day label (only when not failed/completed - those show in main content area)
            if (!isCheckInAllowed && !hasFailed && !isGoalReached) {
                NonCheckInDayLabelInternal(paddingHorizontal, paddingVertical, fontSize, context.getString(R.string.widget_status_non_checkin))
            }

            // Mode label (shown after non-check-in label with spacing)
            if (modeText != null) {
                if (!isCheckInAllowed && !hasFailed && !isGoalReached) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                }
                ModeLabelInternal(modeText, paddingHorizontal, paddingVertical, fontSize)
            }
        }
    }
}