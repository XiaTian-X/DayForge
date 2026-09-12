package com.dayforge.widget.timer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dayforge.R
import com.dayforge.domain.service.TimerServiceController
import dagger.hilt.android.AndroidEntryPoint

/**
 * Transparent activity to show discard confirmation dialog.
 *
 * Shown when user tries to stop a timer before reaching target.
 * - Countdown: remaining time > 0
 * - Countup: elapsed time < target
 *
 * Per TIMER-08: Shows confirmation dialog with message "计时未达标，是否放弃本次计时？"
 * Per TIMER-09: If user confirms, deletes the TimeLogEntity instead of saving.
 *
 * User can choose to:
 * - "放弃" (Discard): Delete the incomplete session
 * - "继续计时" (Continue): Return to timer, keep timing
 */
@AndroidEntryPoint
class CountdownDiscardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val targetMinutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, 0)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 0)
        val isCountdown = intent.getBooleanExtra(EXTRA_IS_COUNTDOWN, true)

        if (habitId == -1L) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                // Transparent background with centered dialog
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Dialog(onDismissRequest = { finish() }) {
                        Card(
                            modifier = Modifier.padding(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.dialog_discard_title),
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = buildDiscardMessage(context, seconds, targetMinutes, isCountdown),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                                ) {
                                    // "继续计时" button - just dismiss, timer continues
                                    TextButton(onClick = { finish() }) {
                                        Text(stringResource(R.string.action_continue_timer))
                                    }

                                    // "放弃" button - discard the session
                                    Button(
                                        onClick = {
                                            // Per TIMER-09: discard deletes TimeLogEntity
                                            TimerServiceController.discardTimer(
                                                this@CountdownDiscardActivity,
                                                habitId,
                                                targetMinutes
                                            )
                                            finish()
                                        }
                                    ) {
                                        Text(stringResource(R.string.action_discard))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds the discard confirmation message.
     * - Countdown: "还剩 MM:SS，是否放弃本次计时？"
     * - Countup: "已计时 X分钟，未达目标 Y分钟，是否放弃本次计时？"
     */
    private fun buildDiscardMessage(context: Context, seconds: Int, targetMinutes: Int, isCountdown: Boolean): String {
        return if (isCountdown) {
            // Countdown mode: show remaining time
            if (seconds > 0) {
                val minutes = seconds / 60
                val secs = seconds % 60
                context.getString(R.string.dialog_discard_countdown_remaining, minutes, secs)
            } else {
                context.getString(R.string.dialog_discard_countdown_fallback)
            }
        } else {
            // Countup mode: show elapsed time and target
            val elapsedMinutes = seconds / 60
            val elapsedSeconds = seconds % 60
            context.getString(R.string.dialog_discard_countup_elapsed, elapsedMinutes, elapsedSeconds, targetMinutes)
        }
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_TARGET_MINUTES = "targetMinutes"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_IS_COUNTDOWN = "isCountdown"

        /**
         * Create intent to launch this activity.
         * @param seconds For countdown: remaining seconds. For countup: elapsed seconds.
         */
        fun createIntent(
            context: android.content.Context,
            habitId: Long,
            targetMinutes: Int,
            seconds: Int,
            isCountdown: Boolean = true
        ): Intent {
            return Intent(context, CountdownDiscardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_TARGET_MINUTES, targetMinutes)
                putExtra(EXTRA_SECONDS, seconds)
                putExtra(EXTRA_IS_COUNTDOWN, isCountdown)
            }
        }
    }
}
