package com.dayforge.widget.counting

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.datastore.preferences.core.*
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dayforge.R
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetFailureChecker
import com.dayforge.widget.base.StatusLabels
import com.dayforge.widget.base.WidgetColorResolver
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.widget.checkin.CheckInActionCallback
import com.dayforge.widget.checkin.ReactivationActivity
import com.dayforge.di.ThemeManagerEntryPoint
import kotlinx.coroutines.CancellationException

import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable

/**
 * 2x2 Counting Widget for COUNTING habits.
 *
 * Layout:
 * - Top: Habit name (adaptive size, bold, center)
 * - Middle: Check In button + progress count display
 * - Bottom: Completed indicator when target reached
 * - Countdown mode: button action is "decrement" (reduces remaining)
 * - Countup mode: button action is "increment" (adds to completed)
 */
class CountingWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "CountingWidget"

        const val PREFS_NAME = "counting_widget_prefs"
        const val PREF_HABIT_ID_PREFIX = "habit_id_"

        // Glance DataStore keys
        val HABIT_ID_KEY = longPreferencesKey("habitId")
        val HABIT_NAME_KEY = stringPreferencesKey("habitName")
        val COLOR_HEX_KEY = stringPreferencesKey("colorHex")
        val TARGET_VALUE_KEY = intPreferencesKey("targetValue")
        val COMPLETED_TODAY_KEY = intPreferencesKey("completedToday")
        val IS_COMPLETED_KEY = booleanPreferencesKey("isCompleted")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")
        val IS_DELETED_KEY = booleanPreferencesKey("isDeleted")
        val APP_WIDGET_ID_KEY = intPreferencesKey("appWidgetId")
        val IS_ACTIVE_KEY = booleanPreferencesKey("isActive")
        val IS_COUNTDOWN_KEY = booleanPreferencesKey("isCountdown")
        // Status fields
        val IS_CHECKIN_ALLOWED_KEY = booleanPreferencesKey("isCheckInAllowed")
        val NEXT_CHECKIN_DATE_KEY = stringPreferencesKey("nextCheckInDate")
        val HAS_FAILED_KEY = booleanPreferencesKey("hasFailed")
        val IS_GOAL_REACHED_KEY = booleanPreferencesKey("isGoalReached")
        val TARGET_PROGRESS_KEY = intPreferencesKey("targetProgress")
        val TARGET_CYCLES_KEY = intPreferencesKey("targetCycles")
        // Pre-computed colors for widget rendering (per WIDGET-COLOR-01, WIDGET-COLOR-06)
        val BACKGROUND_COLOR_KEY = intPreferencesKey("backgroundColor")
        val TEXT_COLOR_KEY = intPreferencesKey("textColor")

        suspend fun refreshWidgetData(context: Context, glanceId: GlanceId, habitId: Long) {
            val appContext = context.applicationContext
            val database = HabitDatabaseProvider.getInstance(appContext)
            val habit = database.habitDao().getHabitById(habitId)

            if (habit == null) {
                Log.w(TAG, "refreshWidgetData: habit $habitId not found")
                updateAppWidgetState(appContext, glanceId) { prefs ->
                    prefs[HABIT_ID_KEY] = habitId
                    prefs[IS_DELETED_KEY] = true
                    prefs[DATA_LOADED_KEY] = true
                }
                return
            }

            val today = DateTimeUtils.today()
            val tomorrow = today.plusDays(1)
            val completions = database.completionDao().getCompletionsInRange(habitId, today, tomorrow)
            val completedToday = completions.sumOf { it.value }
            val isCompleted = completedToday >= habit.targetValue

            // Status calculation
            val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
            val nextCheckInDate = if (!isCheckInAllowed) {
                ScheduleValidator.getNextCheckInDate(habit.schedule, habit.createdAt).toString()
            } else ""
            // Target progress - count days where target was met (COUNTING habits only)
            val targetProgress = if (habit.targetCycles != null) {
                database.completionDao().getTargetMetDayCount(habitId, habit.targetValue)
            } else 0
            // Goal reached: progress met and habit deactivated (user clicked "confirm complete")
            val isGoalReached = habit.targetCycles != null && targetProgress >= habit.targetCycles && !habit.isActive

            // Failed status check
            val hasFailed = WidgetFailureChecker.checkFailure(habit, database)

            // Pre-compute widget colors using WidgetColorResolver
            // Per WIDGET-COLOR-01, WIDGET-COLOR-06: Colors must be pre-calculated before rendering
            val themeManager = ThemeManagerEntryPoint.from(appContext).themeManager()
            val dataStore = DataStoreProvider.get(appContext)
            val preferencesManager = PreferencesManager(dataStore)
            val widgetColorResolver = WidgetColorResolver(appContext, themeManager, preferencesManager)
            val resolvedColors = widgetColorResolver.resolveWidgetColors(habit.colorHex)

            updateAppWidgetState(appContext, glanceId) { prefs ->
                prefs[HABIT_ID_KEY] = habitId
                prefs[HABIT_NAME_KEY] = habit.name
                prefs[COLOR_HEX_KEY] = habit.colorHex
                prefs[TARGET_VALUE_KEY] = habit.targetValue
                prefs[COMPLETED_TODAY_KEY] = completedToday
                prefs[IS_COMPLETED_KEY] = isCompleted
                prefs[IS_ACTIVE_KEY] = habit.isActive
                prefs[IS_COUNTDOWN_KEY] = habit.isCountdown
                prefs[IS_DELETED_KEY] = false
                prefs[DATA_LOADED_KEY] = true
                // Write status fields
                prefs[IS_CHECKIN_ALLOWED_KEY] = isCheckInAllowed
                prefs[NEXT_CHECKIN_DATE_KEY] = nextCheckInDate
                prefs[HAS_FAILED_KEY] = hasFailed
                prefs[IS_GOAL_REACHED_KEY] = isGoalReached
                prefs[TARGET_PROGRESS_KEY] = targetProgress
                prefs[TARGET_CYCLES_KEY] = habit.targetCycles ?: 0
                // Pre-computed colors (Int ARGB for Glance state)
                prefs[BACKGROUND_COLOR_KEY] = resolvedColors.backgroundColorArgb
                prefs[TEXT_COLOR_KEY] = resolvedColors.textColorArgb
            }
            Log.d(TAG, "refreshWidgetData: wrote state for habit '${habit.name}', count=$completedToday, bgColor=${resolvedColors.backgroundColorArgb}")
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance called for id=$id")

        try {
            val appWidgetId = androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(id)
            val state = getAppWidgetState<Preferences>(context, id)
            val habitId = state[HABIT_ID_KEY] ?: -1L

            updateAppWidgetState(context, id) { prefs ->
                prefs[APP_WIDGET_ID_KEY] = appWidgetId
            }

            if (habitId != -1L) {
                refreshWidgetData(context, id, habitId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial data", e)
        }

        provideContent {
            GlanceTheme {
                val context = LocalContext.current
                val state = currentState<Preferences>()
                val habitId = state[HABIT_ID_KEY] ?: -1L
                val dataLoaded = state[DATA_LOADED_KEY] ?: false
                val isDeleted = state[IS_DELETED_KEY] ?: false
                val appWidgetId = state[APP_WIDGET_ID_KEY] ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

                if (habitId == -1L) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.widget_configure_first))
                } else if (isDeleted) {
                    val intent = android.content.Intent(context, CountingWidgetConfigActivity::class.java).apply {
                        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    WidgetEmptyStates.EmptyConfigState(
                        message = context.getString(R.string.widget_habit_deleted),
                        modifier = GlanceModifier.clickable(androidx.glance.appwidget.action.actionStartActivity(intent))
                    )
                } else if (!dataLoaded) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.common_loading))
                } else {
                    val isActive = state[IS_ACTIVE_KEY] ?: true
                    val isCheckInAllowed = state[IS_CHECKIN_ALLOWED_KEY] ?: true
                    val nextCheckInDate = state[NEXT_CHECKIN_DATE_KEY] ?: ""
                    val hasFailed = state[HAS_FAILED_KEY] ?: false
                    val isGoalReached = state[IS_GOAL_REACHED_KEY] ?: false
                    // Read pre-computed colors from state (Int ARGB)
                    val backgroundColorArgb = state[BACKGROUND_COLOR_KEY] ?: "#2196F3".toColorInt()
                    val textColorArgb = state[TEXT_COLOR_KEY] ?: android.graphics.Color.WHITE
                    CountingWidgetContent(
                        habitName = state[HABIT_NAME_KEY] ?: "",
                        backgroundColorArgb = backgroundColorArgb,
                        textColorArgb = textColorArgb,
                        isActive = isActive,
                        targetValue = state[TARGET_VALUE_KEY] ?: 1,
                        completedToday = state[COMPLETED_TODAY_KEY] ?: 0,
                        isCompleted = state[IS_COMPLETED_KEY] ?: false,
                        habitId = habitId,
                        isCountdown = state[IS_COUNTDOWN_KEY] ?: false,
                        isCheckInAllowed = isCheckInAllowed,
                        nextCheckInDate = nextCheckInDate,
                        hasFailed = hasFailed,
                        isGoalReached = isGoalReached
                    )
                }
            }
        }
    }

    @Composable
    private fun CountingWidgetContent(
        habitName: String,
        backgroundColorArgb: Int,
        textColorArgb: Int,
        isActive: Boolean,
        targetValue: Int,
        completedToday: Int,
        isCompleted: Boolean,
        habitId: Long,
        isCountdown: Boolean,
        isCheckInAllowed: Boolean = true,
        nextCheckInDate: String = "",
        hasFailed: Boolean = false,
        isGoalReached: Boolean = false
    ) {
        val context = LocalContext.current
        // Use pre-computed colors from WidgetColorResolver
        // Apply 0.5f opacity for inactive habits
        val bgColor = if (isActive) Color(backgroundColorArgb) else Color(backgroundColorArgb).copy(alpha = 0.5f)
        val textColor = Color(textColorArgb)

        // Calculate display values based on mode
        val remaining = if (isCountdown) {
            (targetValue - completedToday).coerceAtLeast(0)
        } else {
            0 // Not used for countup
        }

        // Display text
        val displayText = if (isCountdown) {
            context.getString(R.string.timer_countdown_remaining, remaining)
        } else {
            context.getString(R.string.timer_countup_progress, completedToday, targetValue)
        }

        // Completed check
        val showCompleted = if (isCountdown) {
            remaining <= 0
        } else {
            completedToday >= targetValue
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top: Habit name (prominent with adaptive font size + Bold)
                Text(
                    text = habitName,
                    style = TextStyle(
                        fontSize = StatusLabels.getAdaptiveFontSize(habitName),
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(textColor)
                    ),
                    maxLines = 1
                )

                // Status labels row below habit name
                StatusLabels.UnifiedLabelsRow(
                    hasFailed = hasFailed,
                    isGoalReached = isGoalReached,
                    isCheckInAllowed = isCheckInAllowed,
                    modeText = if (isCountdown) context.getString(R.string.widget_status_countdown_counting_mode) else null
                )

                Spacer(modifier = GlanceModifier.height(16.dp))

                // Middle: check-in button + display layout (same as app)
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isCountdown && showCompleted) {
                        // Countdown complete: show "✓ Completed"
                        Text(
                            text = "✓",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(textColor)
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = context.getString(R.string.habit_card_status_completed),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = ColorProvider(textColor)
                            )
                        )
                    } else {
                        // Countup or countdown not complete
                        val shouldHideButton = !isCheckInAllowed || hasFailed || isGoalReached

                        // Failed/Completed/Non-checkin: show status at button position, click to reactivate
                        if (shouldHideButton) {
                            if (hasFailed) {
                                val intent = ReactivationActivity.createIntent(
                                    context,
                                    habitId,
                                    habitName,
                                    isFailed = true
                                )
                                Box(
                                    modifier = GlanceModifier
                                        .cornerRadius(16.dp)
                                        .background(ColorProvider(Color.White.copy(alpha = 0.95f)))
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .clickable(actionStartActivity(intent)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = context.getString(R.string.status_already_failed),
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ColorProvider(Color.Red)
                                        )
                                    )
                                }
                            } else if (isGoalReached) {
                                val intent = ReactivationActivity.createIntent(
                                    context,
                                    habitId,
                                    habitName,
                                    isFailed = false
                                )
                                Box(
                                    modifier = GlanceModifier
                                        .cornerRadius(16.dp)
                                        .background(ColorProvider(Color.White.copy(alpha = 0.95f)))
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .clickable(actionStartActivity(intent)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = context.getString(R.string.status_goal_completed),
                                        style = TextStyle(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ColorProvider(Color(0xFF4CAF50))
                                        )
                                    )
                                }
                            }
                            // Non-checkin day: no clickable content
                        } else {
                            // Complete marker (shown when countup target reached)
                            if (showCompleted) {
                                Text(
                                    text = "✓",
                                    style = TextStyle(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ColorProvider(textColor)
                                    )
                                )
                                Spacer(modifier = GlanceModifier.width(6.dp))
                            }

                            // Check-in button
                            Button(
                                text = context.getString(R.string.action_check_in),
                                onClick = actionRunCallback<CheckInActionCallback>(
                                    actionParametersOf(
                                        ActionParameters.Key<Long>("habitId") to habitId,
                                        ActionParameters.Key<String>("action") to if (isCountdown) "decrement" else "increment"
                                    )
                                ),
                                modifier = GlanceModifier.height(36.dp)
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Progress display
                            Text(
                                text = displayText,
                                style = TextStyle(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ColorProvider(textColor)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
