package com.dayforge.widget.motivation

import com.dayforge.widget.base.DeviceWidgetTheme
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.dayforge.R
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.widget.timer.WidgetTimerRefreshCallback
import kotlinx.coroutines.flow.first

class MotivationWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "MotivationWidget"

        val MESSAGE_KEY = stringPreferencesKey("message")
        val BEST_STREAK_KEY = intPreferencesKey("bestStreak")
        val COMPLETED_TODAY_KEY = intPreferencesKey("completedToday")
        val TOTAL_HABITS_KEY = intPreferencesKey("totalHabits")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")
        val READ_FAILED_KEY = booleanPreferencesKey("readFailed")

        suspend fun refreshWidgetData(context: Context, glanceId: GlanceId? = null) {
            val appContext = context.applicationContext
            val ids = glanceId?.let { listOf(it) } ?: GlanceAppWidgetManager(appContext)
                .getGlanceIds(MotivationWidget::class.java)
            for (id in ids) {
                WidgetEntryPoint.from(appContext).displayPublisher().renderPrepared(
                    onReadFailure = { error ->
                        Log.w(TAG, "Motivation widget source unavailable", error)
                        updateAppWidgetState(appContext, id) { prefs ->
                            prefs[DATA_LOADED_KEY] = false
                            prefs[READ_FAILED_KEY] = true
                        }
                    }
                ) { prepareWidgetData(appContext, id) }
            }
        }

        private suspend fun prepareWidgetData(context: Context, id: GlanceId): suspend () -> Unit {
            val database = HabitDatabaseProvider.getInstance(context.applicationContext)
            val habitDao = database.habitDao()
            val completionDao = database.completionDao()
            val timeLogDao = database.timeLogDao()

            val habitStatusCalculator = WidgetEntryPoint.calculator(context, database)

            val habits = habitDao.getVisibleHabitsOnce().filter { it.completionPolicy != "one_and_done" }
            // Reuse typed status within this preparation, not as cached account authority.
            val stats = habits.map { habitStatusCalculator.calculate(it) }

            // Calculate best streak across all habits
            var bestStreak = 0
            for (stat in stats) {
                val habit = stat.habit
                val streak = when (habit.habitType) {
                    HabitType.TIMER -> {
                        if (habit.appearance != null) {
                            stat.bestStreak
                        } else {
                            // Preserve the legacy timer's existing streak algorithm.
                            val timeLogs = timeLogDao.getAllTimeLogsForHabit(habit.id)
                            val targetSeconds = habit.targetValue * 60
                            val completedDates = timeLogs
                                .groupBy { it.date }
                                .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                                .keys
                                .toList()
                            StreakCalculator.calculateBestStreakFromDates(completedDates)
                        }
                    }
                    HabitType.CHECK_IN,
                    HabitType.COUNTING -> {
                        if (habit.appearance != null) {
                            stat.bestStreak
                        } else {
                            // Legacy counting streaks count recorded days, not qualified days.
                            val completions = completionDao.getCompletionsByHabit(habit.id).first()
                            StreakCalculator.calculateBestStreak(completions)
                        }
                    }
                    HabitType.GOAL -> 0  // GOAL type doesn't have streaks
                }
                if (streak > bestStreak) {
                    bestStreak = streak
                }
            }

            // Filter eligible habits (check-in day + not failed + not goal-completed)
            val eligibleStats = stats.filter { it.shouldCountToday }

            val completedCount = eligibleStats.count { it.completedToday }
            val totalCount = eligibleStats.size
            val message = MotivationMessages.getMessage(context, bestStreak)

            Log.d(TAG, "Motivation refresh: $completedCount / $totalCount (eligible from ${habits.size} total), bestStreak: $bestStreak")

            return suspend {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[MESSAGE_KEY] = message
                    prefs[BEST_STREAK_KEY] = bestStreak
                    prefs[COMPLETED_TODAY_KEY] = completedCount
                    prefs[TOTAL_HABITS_KEY] = totalCount
                    prefs[DATA_LOADED_KEY] = true
                    prefs[READ_FAILED_KEY] = false
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            DeviceWidgetTheme(context) {
                val context = androidx.glance.LocalContext.current
                val prefs = currentState<Preferences>()
                if (prefs[READ_FAILED_KEY] == true) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.data_read_failed) + "\n" +
                        context.getString(R.string.action_retry), GlanceModifier.clickable(
                            actionRunCallback<WidgetTimerRefreshCallback>(actionParametersOf(
                                ActionParameters.Key<String>("widget") to "motivation"))))
                } else if (prefs[DATA_LOADED_KEY] != true) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.common_loading))
                } else {
                    MotivationWidgetContent(
                        message = prefs[MESSAGE_KEY] ?: context.getString(R.string.motivation_default),
                        bestStreak = prefs[BEST_STREAK_KEY] ?: 0,
                        completedToday = prefs[COMPLETED_TODAY_KEY] ?: 0,
                        totalHabits = prefs[TOTAL_HABITS_KEY] ?: 0
                    )
                }
            }
        }
    }

    @Composable
    private fun MotivationWidgetContent(
        message: String,
        bestStreak: Int,
        completedToday: Int,
        totalHabits: Int
    ) {
        val context = androidx.glance.LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(16.dp)
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = message,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurface,
                        textAlign = TextAlign.Start
                    ),
                    modifier = GlanceModifier.fillMaxWidth()
                )

                Spacer(modifier = GlanceModifier.height(12.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = context.getString(R.string.widget_best_streak, bestStreak),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = GlanceTheme.colors.tertiary
                        )
                    )

                    Spacer(modifier = GlanceModifier.width(16.dp))

                    Text(
                        text = context.getString(R.string.widget_today_status, completedToday, totalHabits),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = GlanceTheme.colors.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}
