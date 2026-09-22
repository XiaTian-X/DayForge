package com.dayforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dayforge.R

/**
 * TargetProgressIndicator displays progress toward a target cycle count.
 *
 * Display format:
 * - Row with "已完成 {progress}" left, "目标：{target}" right
 * - LinearProgressIndicator showing progress proportion below
 *
 * Used for habits with targetCycles set (targetCycles != null).
 * Shows distinct days completed, NOT value sum.
 *
 * @param progress Current distinct days completed
 * @param target Target cycle count (targetCycles)
 * @param textColor Optional text color override
 * @param modifier Modifier for custom styling
 */
@Composable
fun TargetProgressIndicator(
    progress: Int,
    target: Int,
    textColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val resolvedColor = textColor ?: MaterialTheme.colorScheme.onSurface
    val progressFraction = if (target > 0) {
        (progress.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Top row: 已完成 X | 目标：Y
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.target_completed_format, progress),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 14.sp,
                color = resolvedColor
            )
            Text(
                text = stringResource(R.string.target_label_format, target),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 14.sp,
                color = resolvedColor.copy(alpha = 0.7f)
            )
        }

        // Progress bar
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}