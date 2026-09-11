package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.repository.HabitRepository
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Transparent activity to show reactivation dialog for failed/completed habits.
 *
 * Shows when user taps on "失败" or "完成" status label in widget.
 * Allows user to:
 * - Confirm reactivation (clears history and reactivates the habit)
 * - Cancel (dismisses dialog)
 *
 * Following GoalCompletionActivity pattern for widget-to-Compose dialog bridge.
 */
@AndroidEntryPoint
class ReactivationActivity : ComponentActivity() {

    @Inject
    lateinit var habitDatabase: HabitDatabase

    @Inject
    lateinit var habitRepository: HabitRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: getString(R.string.default_habit_name)
        val isFailed = intent.getBooleanExtra(EXTRA_IS_FAILED, false)

        if (habitId == -1L) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
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
                                    text = if (isFailed) stringResource(R.string.dialog_reactivate_failed_title) else stringResource(R.string.dialog_reactivate_completed_title),
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = if (isFailed) {
                                        stringResource(R.string.dialog_reactivate_failed_message, habitName)
                                    } else {
                                        stringResource(R.string.dialog_reactivate_completed_message, habitName)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
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
                                        onClick = {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                try {
                                                    val habitDao = habitDatabase.habitDao()
                                                    val habit = habitDao.getHabitById(habitId)
                                                    if (habit != null) {
                                                        habitRepository.clearHabitHistory(habit, this@ReactivationActivity)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(applicationContext, getString(R.string.toast_reactivate_success), Toast.LENGTH_SHORT).show()
                                                        finish()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(applicationContext, getString(R.string.toast_reactivate_error), Toast.LENGTH_SHORT).show()
                                                        finish()
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.dialog_reactivate_confirm))
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
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_IS_FAILED = "isFailed"

        /**
         * Create intent to start this activity.
         *
         * @param context Context for starting activity
         * @param habitId The ID of the habit to reactivate
         * @param habitName The name of the habit (for dialog display)
         * @param isFailed Whether the habit is in failed state (vs completed)
         * @return Intent to start ReactivationActivity
         */
        fun createIntent(
            context: Context,
            habitId: Long,
            habitName: String,
            isFailed: Boolean
        ): Intent {
            return Intent(context, ReactivationActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_IS_FAILED, isFailed)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
