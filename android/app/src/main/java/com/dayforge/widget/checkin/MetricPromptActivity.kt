package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.MetricValueDraft
import com.dayforge.domain.service.TimerService
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.PostCheckInDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Transparent activity to show metric prompt dialog after widget check-in.
 *
 * Shows when user checks in a habit from widget that has linked metrics
 * with promptOnComplete=true.
 */
@AndroidEntryPoint
class MetricPromptActivity : ComponentActivity() {

    @Inject
    lateinit var habitDatabase: HabitDatabase

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var metricRepository: MetricRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"

        if (habitId == -1L) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                var linkedMetrics by remember { mutableStateOf<List<LinkedMetricInfo>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }

                LaunchedEffect(habitId) {
                    val metricInfos = withContext(Dispatchers.IO) {
                        // Get linked metrics with promptOnComplete=true
                        val links = habitDatabase.habitMetricLinkDao().getLinksByHabit(habitId).first()
                        val promptLinks = links.filter { it.promptOnComplete }

                        // Build linked metric info list
                        promptLinks.mapNotNull { link ->
                            val metric = habitDatabase.metricDao().getMetricById(link.metricId) ?: return@mapNotNull null
                            val latestLog = habitDatabase.metricLogDao().getLatestLog(link.metricId)
                            LinkedMetricInfo(
                                metricName = metric.name,
                                metricId = metric.id,
                                latestValue = latestLog?.value,
                                unit = metric.unit,
                                decimalPlaces = metric.decimalPlaces
                            )
                        }
                    }
                    if (metricInfos.isEmpty()) {
                        finish()
                    } else {
                        linkedMetrics = metricInfos
                        isLoading = false
                    }
                }

                if (!isLoading && linkedMetrics.isNotEmpty()) {
                    // PostCheckInDialog is already an AlertDialog, no need to wrap in Dialog
                    PostCheckInDialog(
                        habitName = habitName,
                        linkedMetrics = linkedMetrics,
                        onRecord = { values, neverAskAgain ->
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        metricRepository.recordValues(
                                            values.map { input ->
                                                MetricValueDraft(input.metricId, input.value, input.note)
                                            }
                                        )
                                        if (neverAskAgain) {
                                            preferencesManager.setNeverAskAgain(habitId, true)
                                        }
                                        preferencesManager.removePendingMetricHabit(habitId)
                                    }

                                    val updateIntent = Intent(TimerService.ACTION_WIDGET_UPDATE).apply {
                                        putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                                        setPackage(packageName)
                                    }
                                    sendBroadcast(updateIntent)
                                    finish()
                                } catch (error: Exception) {
                                    Log.e(TAG, "Failed to record linked metrics", error)
                                    Toast.makeText(
                                        this@MetricPromptActivity,
                                        getString(com.dayforge.R.string.metric_error_record_failed, error.message.orEmpty()),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onSkip = { neverAskAgain ->
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    if (neverAskAgain) {
                                        preferencesManager.setNeverAskAgain(habitId, true)
                                    }
                                    preferencesManager.removePendingMetricHabit(habitId)
                                }
                                finish()
                            }
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MetricPromptActivity"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"

        /**
         * Create intent to start this activity.
         */
        fun createIntent(context: android.content.Context, habitId: Long, habitName: String): Intent {
            return Intent(context, MetricPromptActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
