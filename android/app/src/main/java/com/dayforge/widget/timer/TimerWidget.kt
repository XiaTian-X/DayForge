package com.dayforge.widget.timer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.action.clickable
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.domain.service.TimerElapsedCalculator
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetFailureChecker
import com.dayforge.widget.base.StatusLabels
import com.dayforge.widget.base.WidgetColorResolver
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.widget.checkin.ReactivationActivity
import com.dayforge.di.ThemeManagerEntryPoint
import com.dayforge.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 2x2 Timer Widget for TIMER habits with state-based display.
 *
 * Layout per UI-SPEC.md:
 * - Top: Habit name (12sp, white, center)
 * - Middle: Timer display based on state (RUNNING/PAUSED/NOT_RUNNING)
 * - Bottom: "已完成" text or CheckCircle indicator when isCompleted
 *
 * Display formats (match CompletionButton in app):
 * - RUNNING: "MM:SS / X分钟"
 * - PAUSED: "MM:SS / X分钟 (已暂停)"
 * - NOT_RUNNING: "X/Y 分钟" (accumulatedMinutes/targetMinutes)
 */
class TimerWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "TimerWidget"

        const val PREFS_NAME = "timer_widget_prefs"
        const val PREF_HABIT_ID_PREFIX = "habit_id_"

        // Glance DataStore keys
        val HABIT_ID_KEY = longPreferencesKey("habitId")
        val HABIT_NAME_KEY = stringPreferencesKey("habitName")
        val COLOR_HEX_KEY = stringPreferencesKey("colorHex")
        val TARGET_MINUTES_KEY = intPreferencesKey("targetMinutes")
        val ACCUMULATED_SECONDS_KEY = intPreferencesKey("accumulatedSeconds")
        val TIMER_STATE_KEY = stringPreferencesKey("timerState")  // "RUNNING", "PAUSED", "NOT_RUNNING"
        val ELAPSED_SECONDS_KEY = intPreferencesKey("elapsedSeconds")
        val IS_COMPLETED_KEY = booleanPreferencesKey("isCompleted")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")
        val IS_DELETED_KEY = booleanPreferencesKey("isDeleted")
        val APP_WIDGET_ID_KEY = intPreferencesKey("appWidgetId")
        val IS_ACTIVE_KEY = booleanPreferencesKey("isActive")
        val IS_COUNTDOWN_KEY = booleanPreferencesKey("isCountdown")
        val REMAINING_SECONDS_KEY = intPreferencesKey("remainingSeconds")
        val SHOW_METRIC_PROMPT_KEY = booleanPreferencesKey("showMetricPrompt")  // Whether to show record button
        // 新增状态字段
        val IS_CHECKIN_ALLOWED_KEY = booleanPreferencesKey("isCheckInAllowed")
        val NEXT_CHECKIN_DATE_KEY = stringPreferencesKey("nextCheckInDate")
        val HAS_FAILED_KEY = booleanPreferencesKey("hasFailed")
        val IS_GOAL_REACHED_KEY = booleanPreferencesKey("isGoalReached")
        val TARGET_PROGRESS_KEY = intPreferencesKey("targetProgress")
        val TARGET_CYCLES_KEY = intPreferencesKey("targetCycles")
        // Pre-computed colors for widget rendering (per WIDGET-COLOR-01, WIDGET-COLOR-06)
        val BACKGROUND_COLOR_KEY = intPreferencesKey("backgroundColor")
        val TEXT_COLOR_KEY = intPreferencesKey("textColor")

        /**
         * Refreshes widget data from the database.
         *
         * Steps:
         * 1. Query habit by ID from HabitDatabase.habitDao().getHabitById(habitId)
         * 2. If habit is null, set IS_DELETED_KEY = true and return
         * 3. Query active timer: timeLogDao.getActiveTimeLogForHabit(habitId)
         * 4. Calculate timer state:
         *    - If activeTimer != null and !activeTimer.isPaused -> RUNNING
         *    - If activeTimer != null and activeTimer.isPaused -> PAUSED
         *    - Else -> NOT_RUNNING
         * 5. Calculate elapsed seconds if timer active (use System.currentTimeMillis() - startTime, accounting for pausedAt)
         * 6. Calculate today's accumulated seconds: query timeLogDao.getTimeLogsInRange for today's completed sessions (endTime != null), sum durationSeconds
         * 7. Check completion: accumulatedSeconds >= targetMinutes * 60
         * 8. Write all values to Glance state via updateAppWidgetState
         */
        suspend fun refreshWidgetData(context: Context, glanceId: GlanceId, habitId: Long) {
            val appContext = context.applicationContext
            val database = HabitDatabase.getInstance(appContext)
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

            val timeLogDao = database.timeLogDao()

            // Query active timer for this habit
            val activeTimer = timeLogDao.getActiveTimeLogForHabit(habitId)

            // Get habit timer settings for validation
            val targetMinutes = habit.targetValue
            val isCountdown = habit.isCountdown
            val targetSeconds = targetMinutes * 60

            // Calculate safe duration limit (same logic as TimerService)
            // Absolute maximum: 24 hours (prevent runaway values from system date changes)
            val absoluteMaxSeconds = 24 * 60 * 60
            val safeDurationLimit = if (isCountdown) {
                targetSeconds  // Countdown: cannot exceed target
            } else {
                if (targetMinutes > 0) targetSeconds * 3 else absoluteMaxSeconds  // Countup: threshold or absolute max
            }

            // Determine timer state
            val timerState: String
            var elapsedSeconds = 0

            when {
                activeTimer != null && !activeTimer.isPaused -> {
                    timerState = "RUNNING"
                    val rawElapsed = TimerElapsedCalculator.elapsedSeconds(activeTimer, context)
                    // Clamp to safe limit to prevent abnormal display from system date changes
                    elapsedSeconds = rawElapsed.coerceAtMost(safeDurationLimit)
                }
                activeTimer != null && activeTimer.isPaused -> {
                    timerState = "PAUSED"
                    val rawElapsed = TimerElapsedCalculator.elapsedSeconds(activeTimer, context)
                    // Clamp to safe limit
                    elapsedSeconds = rawElapsed.coerceAtMost(safeDurationLimit)
                }
                else -> {
                    timerState = "NOT_RUNNING"
                    elapsedSeconds = 0
                }
            }

            // Calculate today's accumulated seconds (completed sessions only)
            // Use local timezone to match how TimeLogEntity.date is stored
            val today = DateTimeUtils.startOfDayMillis()
            val tomorrow = today + DateTimeUtils.MILLIS_PER_DAY

            // Add current session elapsed time to accumulated if timer is running/paused
            val accumulatedFromCompleted = timeLogDao.getCompletedDurationSecondsForDate(
                habitId, java.time.LocalDate.now().toString(), today, tomorrow
            )
            val accumulatedSeconds = if (timerState != "NOT_RUNNING") {
                accumulatedFromCompleted + elapsedSeconds
            } else {
                accumulatedFromCompleted
            }

            // Check completion
            val isCompleted = accumulatedSeconds >= targetSeconds

            // Check if this habit is in the pending metric set (stored in DataStore)
            // This is more reliable than calculating from database, especially when
            // app is in background or when multiple habits share the same metric.
            val dataStore = DataStoreProvider.get(appContext)
            val preferencesManager = PreferencesManager(dataStore)
            val pendingHabits = preferencesManager.pendingMetricHabits.first()
            val showMetricPrompt = isCompleted && habitId in pendingHabits

            // Calculate remaining seconds for countdown mode
            val remainingSeconds = if (isCountdown) {
                (targetSeconds - accumulatedSeconds).coerceAtLeast(0)
            } else {
                0  // Not used for countup mode
            }

            // 新增状态计算
            val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
            val nextCheckInDate = if (!isCheckInAllowed) {
                ScheduleValidator.getNextCheckInDate(habit.schedule, habit.createdAt).toString()
            } else ""

            // Target progress - count days where timer target was met
            val targetProgress = if (habit.targetCycles != null) {
                timeLogDao.getTargetMetDayCount(habit.id, targetSeconds)
            } else 0
            // 目标完成：进度达标且习惯已被停用（用户点击了"确认完成"）
            val isGoalReached = habit.targetCycles != null && targetProgress >= habit.targetCycles && !habit.isActive

            // 失败状态判定
            val hasFailed = WidgetFailureChecker.checkFailure(habit, database)

            // Pre-compute widget colors using WidgetColorResolver
            // Per WIDGET-COLOR-01, WIDGET-COLOR-06: Colors must be pre-calculated before rendering
            // Reuse existing dataStore and preferencesManager from line 191-192
            val themeManager = ThemeManagerEntryPoint.from(appContext).themeManager()
            val widgetColorResolver = WidgetColorResolver(appContext, themeManager, preferencesManager)
            val resolvedColors = widgetColorResolver.resolveWidgetColors(habit.colorHex)

            // Write all values to Glance state
            updateAppWidgetState(appContext, glanceId) { prefs ->
                prefs[HABIT_ID_KEY] = habitId
                // 倒计时习惯添加标签
                prefs[HABIT_NAME_KEY] = habit.name
                prefs[COLOR_HEX_KEY] = habit.colorHex
                prefs[TARGET_MINUTES_KEY] = targetMinutes
                prefs[ACCUMULATED_SECONDS_KEY] = accumulatedSeconds
                prefs[TIMER_STATE_KEY] = timerState
                prefs[ELAPSED_SECONDS_KEY] = elapsedSeconds
                prefs[IS_COMPLETED_KEY] = isCompleted
                prefs[IS_ACTIVE_KEY] = habit.isActive
                prefs[IS_COUNTDOWN_KEY] = isCountdown
                prefs[REMAINING_SECONDS_KEY] = remainingSeconds
                prefs[SHOW_METRIC_PROMPT_KEY] = showMetricPrompt
                prefs[IS_DELETED_KEY] = false
                prefs[DATA_LOADED_KEY] = true
                // 写入新增状态
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

            Log.d(
                TAG, "refreshWidgetData: wrote state for habit '${habit.name}', " +
                "timerState=$timerState, elapsed=$elapsedSeconds, accumulated=$accumulatedSeconds, " +
                "isCompleted=$isCompleted, showMetricPrompt=$showMetricPrompt, bgColor=${resolvedColors.backgroundColorArgb}"
            )
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
                val context = androidx.glance.LocalContext.current
                val state = currentState<Preferences>()
                val habitId = state[HABIT_ID_KEY] ?: -1L
                val dataLoaded = state[DATA_LOADED_KEY] ?: false
                val isDeleted = state[IS_DELETED_KEY] ?: false
                val appWidgetId = state[APP_WIDGET_ID_KEY] ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

                if (habitId == -1L) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.widget_configure_first))
                } else if (isDeleted) {
                    val intent = android.content.Intent(
                        context,
                        TimerWidgetConfigActivity::class.java
                    ).apply {
                        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    WidgetEmptyStates.EmptyConfigState(
                        message = context.getString(R.string.widget_habit_deleted),
                        modifier = GlanceModifier.clickable(
                            androidx.glance.appwidget.action.actionStartActivity(intent)
                        )
                    )
                } else if (!dataLoaded) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.common_loading))
                } else {
                    val isActive = state[IS_ACTIVE_KEY] ?: true
                    val isCountdown = state[IS_COUNTDOWN_KEY] ?: false
                    val remainingSeconds = state[REMAINING_SECONDS_KEY] ?: 0
                    val isCheckInAllowed = state[IS_CHECKIN_ALLOWED_KEY] ?: true
                    val nextCheckInDate = state[NEXT_CHECKIN_DATE_KEY] ?: ""
                    val hasFailed = state[HAS_FAILED_KEY] ?: false
                    val isGoalReached = state[IS_GOAL_REACHED_KEY] ?: false
                    // Read pre-computed colors from state (Int ARGB)
                    val backgroundColorArgb = state[BACKGROUND_COLOR_KEY] ?: android.graphics.Color.parseColor("#2196F3")
                    val textColorArgb = state[TEXT_COLOR_KEY] ?: android.graphics.Color.WHITE
                    TimerWidgetContent(
                        habitName = state[HABIT_NAME_KEY] ?: "",
                        backgroundColorArgb = backgroundColorArgb,
                        textColorArgb = textColorArgb,
                        isActive = isActive,
                        targetMinutes = state[TARGET_MINUTES_KEY] ?: 1,
                        accumulatedSeconds = state[ACCUMULATED_SECONDS_KEY] ?: 0,
                        timerState = state[TIMER_STATE_KEY] ?: "NOT_RUNNING",
                        elapsedSeconds = state[ELAPSED_SECONDS_KEY] ?: 0,
                        isCompleted = state[IS_COMPLETED_KEY] ?: false,
                        isCountdown = isCountdown,
                        remainingSeconds = remainingSeconds,
                        showMetricPrompt = state[SHOW_METRIC_PROMPT_KEY] ?: false,
                        habitId = habitId,
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
    private fun TimerWidgetContent(
        habitName: String,
        backgroundColorArgb: Int,
        textColorArgb: Int,
        isActive: Boolean,
        targetMinutes: Int,
        accumulatedSeconds: Int,
        timerState: String,
        elapsedSeconds: Int,
        isCompleted: Boolean,
        isCountdown: Boolean,
        remainingSeconds: Int,
        showMetricPrompt: Boolean,
        habitId: Long,
        isCheckInAllowed: Boolean = true,
        nextCheckInDate: String = "",
        hasFailed: Boolean = false,
        isGoalReached: Boolean = false
    ) {
        val context = androidx.glance.LocalContext.current
        // Use pre-computed colors from WidgetColorResolver
        // Apply 0.5f opacity for inactive habits
        val bgColor = if (isActive) Color(backgroundColorArgb) else Color(backgroundColorArgb).copy(alpha = 0.5f)
        val textColor = Color(textColorArgb)

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
                    modeText = if (isCountdown) context.getString(R.string.widget_status_countdown_mode) else null
                )

                Spacer(modifier = GlanceModifier.height(12.dp))

                // Middle: Timer display and buttons based on state
                when (timerState) {
                    "RUNNING" -> {
                        RunningTimerContent(
                            elapsedSeconds = elapsedSeconds,
                            targetMinutes = targetMinutes,
                            isCountdown = isCountdown,
                            remainingSeconds = remainingSeconds,
                            habitId = habitId,
                            isCheckInAllowed = isCheckInAllowed,
                            hasFailed = hasFailed,
                            isGoalReached = isGoalReached,
                            textColorArgb = textColorArgb
                        )
                    }
                    "PAUSED" -> {
                        PausedTimerContent(
                            elapsedSeconds = elapsedSeconds,
                            targetMinutes = targetMinutes,
                            isCountdown = isCountdown,
                            remainingSeconds = remainingSeconds,
                            habitId = habitId,
                            isCheckInAllowed = isCheckInAllowed,
                            hasFailed = hasFailed,
                            isGoalReached = isGoalReached,
                            textColorArgb = textColorArgb
                        )
                    }
                    else -> {
                        NotRunningTimerContent(
                            accumulatedSeconds = accumulatedSeconds,
                            targetMinutes = targetMinutes,
                            isCountdown = isCountdown,
                            isCompleted = isCompleted,
                            showMetricPrompt = showMetricPrompt,
                            habitId = habitId,
                            habitName = habitName,
                            isCheckInAllowed = isCheckInAllowed,
                            hasFailed = hasFailed,
                            isGoalReached = isGoalReached,
                            textColorArgb = textColorArgb
                        )
                    }
                }

                // Bottom: "已完成" indicator when target reached
                if (isCompleted) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Text(
                        text = context.getString(R.string.widget_status_completed),
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = ColorProvider(textColor)
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun RunningTimerContent(
        elapsedSeconds: Int,
        targetMinutes: Int,
        isCountdown: Boolean,
        remainingSeconds: Int,
        habitId: Long,
        isCheckInAllowed: Boolean = true,
        hasFailed: Boolean = false,
        isGoalReached: Boolean = false,
        textColorArgb: Int = android.graphics.Color.WHITE
    ) {
        val context = androidx.glance.LocalContext.current
        val textColor = Color(textColorArgb)
        val displayText = if (isCountdown) {
            // Countdown mode: display remaining time
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            context.getString(R.string.widget_remaining_time_format, minutes, seconds)
        } else {
            // Countup mode: display elapsed time
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            context.getString(R.string.widget_elapsed_time_format, minutes, seconds, targetMinutes)
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pause button
            Button(
                text = context.getString(R.string.action_pause),
                onClick = actionRunCallback<TimerActionCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<String>("action") to "pause",
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = GlanceModifier.height(36.dp).width(50.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Gray),
                    contentColor = ColorProvider(Color.White)
                )
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Timer display - use textColorArgb for proper theme contrast
            Text(
                text = displayText,
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(textColor)
                ),
                maxLines = 1
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Stop button
            Button(
                text = context.getString(R.string.action_stop),
                onClick = actionRunCallback<TimerActionCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<String>("action") to "stop",
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = GlanceModifier.height(36.dp).width(50.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Gray),
                    contentColor = ColorProvider(Color.White)
                )
            )
        }
    }

    @Composable
    private fun PausedTimerContent(
        elapsedSeconds: Int,
        targetMinutes: Int,
        isCountdown: Boolean,
        remainingSeconds: Int,
        habitId: Long,
        isCheckInAllowed: Boolean = true,
        hasFailed: Boolean = false,
        isGoalReached: Boolean = false,
        textColorArgb: Int = android.graphics.Color.WHITE
    ) {
        val context = androidx.glance.LocalContext.current
        val textColor = Color(textColorArgb)
        val displayText = if (isCountdown) {
            // Countdown mode: display remaining time
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            context.getString(R.string.widget_remaining_time_format, minutes, seconds)
        } else {
            // Countup mode: display elapsed time
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            context.getString(R.string.widget_elapsed_time_format, minutes, seconds, targetMinutes)
        }

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resume button
            Button(
                text = context.getString(R.string.action_resume),
                onClick = actionRunCallback<TimerActionCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<String>("action") to "resume",
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = GlanceModifier.height(36.dp).width(50.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Gray),
                    contentColor = ColorProvider(Color.White)
                )
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Timer display (paused) - use textColorArgb for proper theme contrast
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = displayText,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(textColor)
                    ),
                    maxLines = 1
                )
                Text(
                    text = context.getString(R.string.widget_paused),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        color = ColorProvider(textColor.copy(alpha = 0.8f))
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Stop button
            Button(
                text = context.getString(R.string.action_stop),
                onClick = actionRunCallback<TimerActionCallback>(
                    actionParametersOf(
                        ActionParameters.Key<Long>("habitId") to habitId,
                        ActionParameters.Key<String>("action") to "stop",
                        ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                    )
                ),
                modifier = GlanceModifier.height(36.dp).width(50.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = ColorProvider(Color.Gray),
                    contentColor = ColorProvider(Color.White)
                )
            )
        }
    }

    @Composable
    private fun NotRunningTimerContent(
        accumulatedSeconds: Int,
        targetMinutes: Int,
        isCountdown: Boolean,
        isCompleted: Boolean,
        showMetricPrompt: Boolean,
        habitId: Long,
        habitName: String,
        isCheckInAllowed: Boolean = true,
        hasFailed: Boolean = false,
        isGoalReached: Boolean = false,
        textColorArgb: Int = android.graphics.Color.WHITE
    ) {
        val context = androidx.glance.LocalContext.current
        val textColor = Color(textColorArgb)
        // Unified display: always show target minutes
        val progressText = context.getString(R.string.widget_target_minutes_format, targetMinutes)

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start button - only show if not completed and allowed to check in
            // 按钮是否隐藏（失败/目标完成/非打卡日）
            val shouldHideStartButton = !isCheckInAllowed || hasFailed || isGoalReached

            // 失败/完成/非打卡日状态：在原来开始按钮位置显示状态，点击可重新激活
            if (shouldHideStartButton) {
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
                            text = context.getString(R.string.widget_status_failed),
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
                // 非打卡日不显示任何可点击内容
            } else {
                if (!isCompleted) {
                    Button(
                        text = context.getString(R.string.widget_start),
                        onClick = actionRunCallback<TimerActionCallback>(
                            actionParametersOf(
                                ActionParameters.Key<Long>("habitId") to habitId,
                                ActionParameters.Key<String>("action") to "start",
                                ActionParameters.Key<Int>("targetMinutes") to targetMinutes
                            )
                        ),
                        modifier = GlanceModifier.height(36.dp)
                    )

                    Spacer(modifier = GlanceModifier.width(12.dp))
                }

                // Progress display - use textColorArgb for proper theme contrast
                Text(
                    text = progressText,
                    style = TextStyle(
                        fontSize = if (isCompleted) 20.sp else 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(textColor)
                    ),
                    maxLines = 1
                )

                // Record button - show when completed with pending metrics
                if (showMetricPrompt) {
                    Spacer(modifier = GlanceModifier.width(12.dp))

                    Button(
                        text = context.getString(R.string.widget_record),
                        onClick = actionRunCallback<RecordMetricCallback>(
                            actionParametersOf(
                                ActionParameters.Key<Long>("habitId") to habitId,
                                ActionParameters.Key<String>("habitName") to habitName
                            )
                        ),
                        modifier = GlanceModifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = ColorProvider(Color.White.copy(alpha = 0.3f)),
                            contentColor = ColorProvider(Color.White)
                        )
                    )
                }
            }
        }
    }
}
