package com.dayforge.widget.motivation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.dayforge.R
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneOffset

class MotivationWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "MotivationWidget"

        val MESSAGE_KEY = stringPreferencesKey("message")
        val BEST_STREAK_KEY = intPreferencesKey("bestStreak")
        val COMPLETED_TODAY_KEY = intPreferencesKey("completedToday")
        val TOTAL_HABITS_KEY = intPreferencesKey("totalHabits")

        suspend fun refreshWidgetData(context: Context) {
            val database = HabitDatabase.getInstance(context.applicationContext)
            val habitDao = database.habitDao()
            val completionDao = database.completionDao()
            val timeLogDao = database.timeLogDao()

            // Create services manually (widgets don't use Hilt)
            val failureChecker = FailureChecker(completionDao, timeLogDao)
            val habitStatusCalculator = HabitStatusCalculator(failureChecker, completionDao, timeLogDao)

            val habits = habitDao.getAllHabits().first()

            // Calculate best streak across all habits
            var bestStreak = 0
            for (habit in habits) {
                val streak = when (habit.habitType) {
                    HabitType.TIMER -> {
                        // For TIMER habits, calculate streak from TimeLogEntity
                        val timeLogs = timeLogDao.getAllTimeLogsForHabit(habit.id)
                        val targetSeconds = habit.targetValue * 60
                        val completedDates = timeLogs
                            .groupBy { it.date }
                            .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                            .keys
                            .toList()
                        StreakCalculator.calculateBestStreakFromDates(completedDates)
                    }
                    HabitType.CHECK_IN,
                    HabitType.COUNTING -> {
                        val completions = completionDao.getCompletionsByHabit(habit.id).first()
                        StreakCalculator.calculateBestStreak(completions)
                    }
                    HabitType.GOAL -> 0  // GOAL type doesn't have streaks
                }
                if (streak > bestStreak) {
                    bestStreak = streak
                }
            }

            // Calculate status for each habit
            val stats = habits.map { habit ->
                habitStatusCalculator.calculate(habit)
            }

            // Filter eligible habits (check-in day + not failed + not goal-completed)
            val eligibleStats = stats.filter { it.shouldCountToday }

            val completedCount = eligibleStats.count { it.completedToday }
            val totalCount = eligibleStats.size
            val message = MotivationMessages.getMessage(context, bestStreak)

            Log.d(TAG, "Motivation refresh: $completedCount / $totalCount (eligible from ${habits.size} total), bestStreak: $bestStreak")

            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(MotivationWidget::class.java)
            for (id in glanceIds) {
                updateAppWidgetState(context, id) { prefs ->
                    prefs[MESSAGE_KEY] = message
                    prefs[BEST_STREAK_KEY] = bestStreak
                    prefs[COMPLETED_TODAY_KEY] = completedCount
                    prefs[TOTAL_HABITS_KEY] = totalCount
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        try {
            refreshWidgetData(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in provideGlance", e)
        }

        provideContent {
            val context = androidx.glance.LocalContext.current
            val prefs = currentState<Preferences>()
            val message = prefs[MESSAGE_KEY] ?: context.getString(R.string.motivation_default)
            val bestStreak = prefs[BEST_STREAK_KEY] ?: 0
            val completedToday = prefs[COMPLETED_TODAY_KEY] ?: 0
            val totalHabits = prefs[TOTAL_HABITS_KEY] ?: 0

            MotivationWidgetContent(
                message = message,
                bestStreak = bestStreak,
                completedToday = completedToday,
                totalHabits = totalHabits
            )
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