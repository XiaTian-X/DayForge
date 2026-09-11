package com.dayforge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dayforge.R

/**
 * Dialog shown when user reaches target cycles for a habit with goal tracking.
 *
 * Per TARGET-08: Shows when check-in reaches targetCycles, allowing user to:
 * - Confirm completion (sets habit isActive = false)
 * - Continue tracking (dismisses dialog, habit stays active)
 *
 * Dialog structure:
 * - Title: "目标完成！" (celebratory tone)
 * - Body: Progress summary and action explanation
 * - Confirm button: "确认完成" (filled button, primary)
 * - Dismiss button: "继续打卡" (text button)
 *
 * @param habitName The name of the habit
 * @param progress The current progress (distinct days with completions)
 * @param target The target cycles (habit.targetCycles)
 * @param onConfirm Callback when user confirms completion
 * @param onDismiss Callback when user dismisses to continue tracking
 */
@Composable
fun GoalCompletionDialog(
    habitName: String,
    progress: Int,
    target: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // Emoji is visual only, not in contentDescription for accessibility
            val accessibilityTitle = stringResource(R.string.dialog_goal_complete_title_accessibility)
            Text(
                text = stringResource(R.string.dialog_goal_complete_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics {
                    contentDescription = accessibilityTitle
                }
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dialog_goal_complete_message, habitName, progress, target),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.dialog_goal_complete_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text(stringResource(R.string.action_confirm_complete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_continue_checkin))
            }
        }
    )
}