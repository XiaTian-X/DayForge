package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.repository.HabitRepository
import com.dayforge.ui.components.GoalCompletionDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Transparent activity to show goal completion dialog after widget check-in.
 *
 * Per TARGET-08: Shows when user checks in from widget and reaches targetCycles.
 * Allows user to:
 * - Confirm completion (sets habit isActive = false)
 * - Continue tracking (dismisses dialog, habit stays active)
 *
 * Following MetricPromptActivity pattern for widget-to-Compose dialog bridge.
 */
@AndroidEntryPoint
class GoalCompletionActivity : ComponentActivity() {

    @Inject
    lateinit var habitDatabase: HabitDatabase

    @Inject
    lateinit var habitRepository: HabitRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: "Habit"
        val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
        val target = intent.getIntExtra(EXTRA_TARGET, 0)

        if (habitId == -1L) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                GoalCompletionDialog(
                    habitName = habitName,
                    progress = progress,
                    target = target,
                    onConfirm = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                habitRepository.updateIsActive(habitId, false, applicationContext)
                            }.fold(
                                onSuccess = {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(applicationContext, getString(R.string.goal_completion_toast), Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                },
                                onFailure = { error ->
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(applicationContext, error.message ?: getString(R.string.goal_completion_update_error), Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    },
                    onDismiss = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                val habit = habitDatabase.habitDao().getHabitById(habitId)
                                if (habit != null && habit.failMode == com.dayforge.data.model.FailMode.STRICT) {
                                    habitRepository.updateFailMode(habitId, com.dayforge.data.model.FailMode.LOOSE, applicationContext)
                                }
                            }.fold(
                                onSuccess = { withContext(Dispatchers.Main) { finish() } },
                                onFailure = { error ->
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(applicationContext, error.message ?: getString(R.string.goal_completion_update_error), Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_NAME = "habitName"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TARGET = "target"

        /**
         * Create intent to start this activity.
         *
         * @param context Context for starting activity
         * @param habitId The ID of the habit that reached its goal
         * @param habitName The name of the habit (for dialog display)
         * @param progress The current progress (distinct days count)
         * @param target The target cycles (habit.targetCycles)
         * @return Intent to start GoalCompletionActivity
         */
        fun createIntent(
            context: Context,
            habitId: Long,
            habitName: String,
            progress: Int,
            target: Int
        ): Intent {
            return Intent(context, GoalCompletionActivity::class.java).apply {
                putExtra(EXTRA_HABIT_ID, habitId)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TARGET, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
