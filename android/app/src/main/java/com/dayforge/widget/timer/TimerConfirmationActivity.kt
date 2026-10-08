package com.dayforge.widget.timer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dayforge.R
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.domain.service.TimerServiceController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Transparent activity to show timer confirmation dialog from widget.
 *
 * Shows when user tries to start a timer from widget while another timer is running.
 * User can choose to:
 * - Stop current timer and start new one
 * - Cancel and keep current timer
 */
@AndroidEntryPoint
class TimerConfirmationActivity : ComponentActivity() {

    @Inject lateinit var writer: NextTimerWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val claim = try { WidgetTimerAction.read(intent) } catch (error: Exception) { finish(); return }
        val habitId = claim?.habitId ?: intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val targetMinutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, 0)

        if (habitId == -1L) {
            finish()
            return
        }
        if (claim != null) lifecycleScope.launch {
            writer.accessChanges.collect { access ->
                if (access == null || access.session != claim.authority.session() || access.capturedDeviceId != claim.authority.deviceId) finish()
            }
        }

        setContent {
            MaterialTheme {
                var currentHabitName by remember { mutableStateOf<String?>(null) }
                var newHabitName by remember { mutableStateOf<String?>(null) }
                var ready by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    try {
                        val display = if (claim != null) writer.requireWidgetStart(habitId, claim.authority, requireNotNull(claim.startGuard))
                            else writer.legacyWidgetSwitchDisplay(habitId)
                        currentHabitName = display.incumbentName
                        newHabitName = display.requestedName
                        ready = true
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        android.util.Log.w("TimerConfirmation", "Stale switch confirmation", error)
                        finish()
                    }
                }

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
                                    text = stringResource(R.string.dialog_multi_timer_title),
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                val current = currentHabitName
                                val new = newHabitName
                                Text(
                                    text = if (current != null && new != null) {
                                        stringResource(R.string.dialog_timer_switch_message, current, new)
                                    } else {
                                        stringResource(R.string.dialog_timer_switch_fallback)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                                ) {
                                    TextButton(onClick = { finish() }) {
                                        Text(stringResource(R.string.action_cancel))
                                    }

                                    Button(
                                        enabled = ready,
                                        onClick = {
                                            lifecycleScope.launch {
                                                try {
                                                    if (claim != null) writer.requireWidgetStart(habitId, claim.authority, requireNotNull(claim.startGuard))
                                                    TimerServiceController.startTimer(
                                                        this@TimerConfirmationActivity, habitId, targetMinutes,
                                                        authority = claim?.authority, startGuard = claim?.startGuard
                                                    )
                                                } catch (error: Exception) {
                                                    if (error is CancellationException) throw error
                                                    android.util.Log.w("TimerConfirmation", "Switch rejected", error)
                                                    android.widget.Toast.makeText(this@TimerConfirmationActivity,
                                                        getString(R.string.data_read_failed), android.widget.Toast.LENGTH_LONG).show()
                                                } finally { finish() }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.action_confirm))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_TARGET_MINUTES = "targetMinutes"
    }
}
