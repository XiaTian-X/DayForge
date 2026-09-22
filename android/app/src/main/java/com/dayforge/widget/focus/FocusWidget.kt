package com.dayforge.widget.focus

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
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitPriorityCalculator
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.TimeMatchResult
import com.dayforge.widget.base.HabitActionButtons
import com.dayforge.widget.base.StatusLabels
import com.dayforge.widget.base.WidgetColorResolver
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.widget.FocusWidgetAlarmScheduler
import com.dayforge.di.ThemeManagerEntryPoint
import com.dayforge.widget.checkin.ReactivationActivity
import com.dayforge.R
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import com.dayforge.domain.service.TimerElapsedCalculator

/**
 * FocusWidget - displays top-priority habit based on time-based relevance.
 *
 * Architecture (per WIDGET-01):
 * - provideGlance (suspend): loads priorities from HabitPriorityCalculator, writes to Glance state
 * - provideContent (@Composable): reads Glance state and renders UI
 * - Only stores 2 candidate habits in state (WIDGET-10 limitation)
 *
 * Display logic:
 * - Primary habit: prominent with large check-in button (WIDGET-02)
 * - Preview habit: subtle, small font, low opacity (WIDGET-03)
 * - COUNTING slot progress: "第X个/共Y个" (WIDGET-04)
 * - Empty state when no habits in window (WIDGET-05)
 */
class FocusWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "FocusWidget"

        // Glance DataStore keys - Primary habit
        val PRIMARY_HABIT_ID_KEY = longPreferencesKey("primaryHabitId")
        val PRIMARY_HABIT_NAME_KEY = stringPreferencesKey("primaryHabitName")
        val PRIMARY_COLOR_HEX_KEY = stringPreferencesKey("primaryColorHex")
        val PRIMARY_HABIT_TYPE_KEY = stringPreferencesKey("primaryHabitType")
        val PRIMARY_TARGET_VALUE_KEY = intPreferencesKey("primaryTargetValue")
        val PRIMARY_COMPLETED_TODAY_KEY = intPreferencesKey("primaryCompletedToday")
        val PRIMARY_IS_COMPLETED_KEY = booleanPreferencesKey("primaryIsCompleted")
        val PRIMARY_IS_ACTIVE_KEY = booleanPreferencesKey("primaryIsActive")
        val PRIMARY_BACKGROUND_COLOR_KEY = intPreferencesKey("primaryBackgroundColor")
        val PRIMARY_TEXT_COLOR_KEY = intPreferencesKey("primaryTextColor")
        val PRIMARY_SLOT_INDEX_KEY = intPreferencesKey("primarySlotIndex")
        val PRIMARY_TOTAL_SLOTS_KEY = intPreferencesKey("primaryTotalSlots")
        val PRIMARY_TIME_SCORE_KEY = floatPreferencesKey("primaryTimeScore")
        // Match result type for showing appropriate UI (InWindow, BeforeWindow, AfterWindow, NoBestTime)
        val PRIMARY_MATCH_TYPE_KEY = stringPreferencesKey("primaryMatchType")
        val PRIMARY_MINUTES_UNTIL_WINDOW_KEY = intPreferencesKey("primaryMinutesUntilWindow")
        val PRIMARY_MINUTES_SINCE_WINDOW_KEY = intPreferencesKey("primaryMinutesSinceWindow")
        // COUNTING习惯下一slot时间（用于显示"下一slot XX:XX开始"）
        val PRIMARY_NEXT_SLOT_TIME_KEY = intPreferencesKey("primaryNextSlotTime")
        val PRIMARY_TODAY_COUNT_KEY = intPreferencesKey("primaryTodayCount")  // 实际完成次数
        // TIMER习惯实时计时状态
        val PRIMARY_IS_TIMER_ACTIVE_KEY = booleanPreferencesKey("primaryIsTimerActive")
        val PRIMARY_TIMER_ELAPSED_KEY = intPreferencesKey("primaryTimerElapsed")  // 正在计时的实时秒数
        // COUNTING/TIMER习惯的isCountdown模式状态
        val PRIMARY_IS_COUNTDOWN_KEY = booleanPreferencesKey("primaryIsCountdown")
        // 习惯状态标签（用于显示StatusLabels）
        val PRIMARY_HAS_FAILED_KEY = booleanPreferencesKey("primaryHasFailed")
        val PRIMARY_IS_GOAL_REACHED_KEY = booleanPreferencesKey("primaryIsGoalReached")
        val PRIMARY_IS_CHECKIN_ALLOWED_KEY = booleanPreferencesKey("primaryIsCheckInAllowed")

        // Glance DataStore keys - Preview habit (WIDGET-03)
        val PREVIEW_HABIT_ID_KEY = longPreferencesKey("previewHabitId")
        val PREVIEW_HABIT_NAME_KEY = stringPreferencesKey("previewHabitName")
        val PREVIEW_COLOR_HEX_KEY = stringPreferencesKey("previewColorHex")
        val PREVIEW_HABIT_TYPE_KEY = stringPreferencesKey("previewHabitType")
        val PREVIEW_TIME_SCORE_KEY = floatPreferencesKey("previewTimeScore")
        val PREVIEW_MATCH_TYPE_KEY = stringPreferencesKey("previewMatchType")
        val PREVIEW_MINUTES_UNTIL_WINDOW_KEY = intPreferencesKey("previewMinutesUntilWindow")

        // State keys
        val HAS_HABITS_KEY = booleanPreferencesKey("hasHabits")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")

        /**
         * Refresh all FocusWidget instances without requiring glanceId.
         * Used by platform refresh paths when all instances need fresh data.
         */
        suspend fun refreshWidgetData(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(FocusWidget::class.java)
            for (glanceId in glanceIds) {
                refreshWidgetData(context, glanceId)
            }
            Log.d(TAG, "refreshWidgetData: refreshed ${glanceIds.size} FocusWidget instances")
        }

        /**
         * Load habit priorities and write into Glance state.
         * Per WIDGET-10: Only store top 2 habits to reduce state size.
         *
         * Reuses HabitStatusCalculator from app logic for consistency:
         * - Calculates isCheckInAllowed, completedToday, hasFailed, isGoalCompleted
         * - Filters out non-check-in day habits automatically
         */
        suspend fun refreshWidgetData(context: Context, glanceId: GlanceId) {
            val appContext = context.applicationContext
            val database = HabitDatabaseProvider.getInstance(appContext)
            val currentTime = ZonedDateTime.now()

            // Create services manually (widgets don't use Hilt injection)
            // Reuse app's centralized status calculation logic
            val failureChecker = FailureChecker(database.completionDao(), database.timeLogDao())
            val habitStatusCalculator = HabitStatusCalculator(
                failureChecker,
                database.completionDao(),
                database.timeLogDao()
            )

            // Get all active habits
            val allActiveHabits = database.habitDao().getAllHabitsOnce().filter { it.isActive }

            // Calculate status for each habit using app logic
            val habitsWithStats = allActiveHabits.map { habit ->
                habitStatusCalculator.calculate(habit)
            }

            // Filter eligible habits:
            // 1. check-in day (shouldCountToday)
            // 2. not failed, not goal completed (calculated in shouldCountToday)
            // 3. not GOAL type (container for child habits)
            // 4. has bestTime configured (FocusWidget只显示有时间偏好的习惯)
            val eligibleHabits = habitsWithStats.filter {
                it.shouldCountToday &&
                it.habit.habitType != HabitType.GOAL &&
                it.habit.bestTime != null  // 必须有bestTime才参与时间窗口匹配
            }

            // Get pending metric habits (completed habits that need metric recording)
            val dataStore = DataStoreProvider.get(appContext)
            val preferencesManager = PreferencesManager(dataStore)
            val pendingMetricHabits = preferencesManager.pendingMetricHabits.first()

            // Get active timer (if any) for real-time TIMER habit progress
            val activeTimeLog = database.timeLogDao().getActiveTimeLog()

            // Calculate priorities using HabitPriorityCalculator
            // 使用todayCount而非completedToday布尔值（支持COUNTING的Slot级别完成判定）
            // 对于有活跃计时器的TIMER习惯，todayCount会加上实时elapsedSeconds
            val completedCountMap = eligibleHabits.associate { habitWithStats ->
                val habit = habitWithStats.habit
                val baseCount = habitWithStats.todayCount
                // 如果是TIMER习惯且有活跃计时器，加上实时elapsedSeconds
                val activeElapsed = if (habit.habitType == HabitType.TIMER && activeTimeLog?.habitId == habit.id) {
                    TimerElapsedCalculator.elapsedSeconds(activeTimeLog, appContext)
                } else 0
                habit.id to (baseCount + activeElapsed)
            }
            val priorities = HabitPriorityCalculator.calculatePriorities(
                eligibleHabits.map { it.habit },
                currentTime,
                completedCountMap,
                pendingMetricHabits
            )

            Log.d(TAG, "refreshWidgetData: ${eligibleHabits.size} eligible habits from ${allActiveHabits.size} total, top score=${priorities.firstOrNull()?.sortScore}")

            // Get top 2 habits (WIDGET-10: limit to 2 candidates)
            val primary = priorities.firstOrNull()
            val preview = priorities.getOrNull(1)

            // Get stats for primary to avoid re-querying
            val primaryStats = primary?.let { habitsWithStats.find { it.habit.id == primary.habit.id } }

            val themeManager = ThemeManagerEntryPoint.from(appContext).themeManager()
            val widgetColorResolver = WidgetColorResolver(appContext, themeManager, preferencesManager)

            updateAppWidgetState(appContext, glanceId) { prefs ->
                prefs[DATA_LOADED_KEY] = true

                if (primary != null) {
                    prefs[HAS_HABITS_KEY] = true

                    // Primary habit state
                    val primaryHabit = primary.habit
                    prefs[PRIMARY_HABIT_ID_KEY] = primaryHabit.id
                    prefs[PRIMARY_HABIT_NAME_KEY] = primaryHabit.name
                    prefs[PRIMARY_COLOR_HEX_KEY] = primaryHabit.colorHex
                    prefs[PRIMARY_HABIT_TYPE_KEY] = primaryHabit.habitType.name
                    prefs[PRIMARY_TARGET_VALUE_KEY] = primaryHabit.targetValue
                    prefs[PRIMARY_IS_ACTIVE_KEY] = primaryHabit.isActive
                    prefs[PRIMARY_SLOT_INDEX_KEY] = primary.slotIndex ?: -1
                    prefs[PRIMARY_TOTAL_SLOTS_KEY] = primary.totalSlots ?: -1
                    prefs[PRIMARY_TIME_SCORE_KEY] = primary.sortScore

                    // Match result type and timing info
                    prefs[PRIMARY_MATCH_TYPE_KEY] = when (primary.matchResult) {
                        is TimeMatchResult.InWindow -> "InWindow"
                        is TimeMatchResult.BeforeWindow -> "BeforeWindow"
                        is TimeMatchResult.AfterWindow -> "AfterWindow"
                        is TimeMatchResult.NoBestTime -> "NoBestTime"
                    }
                    when (primary.matchResult) {
                        is TimeMatchResult.BeforeWindow -> {
                            prefs[PRIMARY_MINUTES_UNTIL_WINDOW_KEY] = (primary.matchResult as TimeMatchResult.BeforeWindow).minutesUntilWindow
                        }
                        is TimeMatchResult.AfterWindow -> {
                            prefs[PRIMARY_MINUTES_SINCE_WINDOW_KEY] = (primary.matchResult as TimeMatchResult.AfterWindow).minutesSinceWindowEnd
                        }
                        else -> { /* No timing info needed for InWindow or NoBestTime */ }
                    }

                    // Today's progress (use stats from HabitStatusCalculator to avoid re-querying)
                    val isCompleted = primary.completedToday
                    prefs[PRIMARY_IS_COMPLETED_KEY] = isCompleted

                    // 计算todayCount：如果有活跃计时器，加上实时elapsedSeconds
                    val baseTodayCount = primaryStats?.todayCount ?: 0
                    val isTimerActive = primaryHabit.habitType == HabitType.TIMER && activeTimeLog?.habitId == primaryHabit.id
                    val activeElapsedSeconds = if (isTimerActive) {
                        TimerElapsedCalculator.elapsedSeconds(activeTimeLog!!, appContext)
                    } else 0
                    val totalTodayCount = baseTodayCount + activeElapsedSeconds

                    prefs[PRIMARY_COMPLETED_TODAY_KEY] = totalTodayCount
                    prefs[PRIMARY_TODAY_COUNT_KEY] = totalTodayCount  // 实际完成次数（含实时计时）
                    prefs[PRIMARY_IS_TIMER_ACTIVE_KEY] = isTimerActive
                    prefs[PRIMARY_TIMER_ELAPSED_KEY] = activeElapsedSeconds  // 正在计时的实时秒数

                    // COUNTING/TIMER习惯的isCountdown模式状态
                    prefs[PRIMARY_IS_COUNTDOWN_KEY] = primaryHabit.isCountdown

                    // 状态标签数据（用于显示StatusLabels）
                    prefs[PRIMARY_HAS_FAILED_KEY] = primaryStats?.hasFailed ?: false
                    prefs[PRIMARY_IS_GOAL_REACHED_KEY] = primaryStats?.isGoalCompleted ?: false
                    prefs[PRIMARY_IS_CHECKIN_ALLOWED_KEY] = primaryStats?.isCheckInAllowed ?: true

                    // COUNTING习惯下一slot时间（方案I：当前slot完成后显示下一slot开始时间）
                    prefs[PRIMARY_NEXT_SLOT_TIME_KEY] = primary.nextSlotTime ?: -1

                    // Pre-computed colors (WIDGET-07)
                    val primaryColors = widgetColorResolver.resolveWidgetColors(primaryHabit.colorHex)
                    prefs[PRIMARY_BACKGROUND_COLOR_KEY] = primaryColors.backgroundColorArgb
                    prefs[PRIMARY_TEXT_COLOR_KEY] = primaryColors.textColorArgb

                    // Preview habit state (WIDGET-03) - next upcoming habit
                    if (preview != null) {
                        prefs[PREVIEW_HABIT_ID_KEY] = preview.habit.id
                        prefs[PREVIEW_HABIT_NAME_KEY] = preview.habit.name
                        prefs[PREVIEW_COLOR_HEX_KEY] = preview.habit.colorHex
                        prefs[PREVIEW_HABIT_TYPE_KEY] = preview.habit.habitType.name
                        prefs[PREVIEW_TIME_SCORE_KEY] = preview.sortScore
                        prefs[PREVIEW_MATCH_TYPE_KEY] = when (preview.matchResult) {
                            is TimeMatchResult.InWindow -> "InWindow"
                            is TimeMatchResult.BeforeWindow -> "BeforeWindow"
                            is TimeMatchResult.AfterWindow -> "AfterWindow"
                            is TimeMatchResult.NoBestTime -> "NoBestTime"
                        }
                        when (preview.matchResult) {
                            is TimeMatchResult.BeforeWindow -> {
                                prefs[PREVIEW_MINUTES_UNTIL_WINDOW_KEY] = (preview.matchResult as TimeMatchResult.BeforeWindow).minutesUntilWindow
                            }
                            else -> { /* No timing info needed for other match types */ }
                        }
                    } else {
                        // No preview habit
                        prefs[PREVIEW_HABIT_ID_KEY] = -1L
                    }

                    Log.d(TAG, "refreshWidgetData: primary='${primaryHabit.name}' match=${primary.matchResult::class.simpleName}, preview='${preview?.habit?.name}'")
                } else {
                    // No habits at all - empty state (WIDGET-05)
                    prefs[HAS_HABITS_KEY] = false
                    Log.d(TAG, "refreshWidgetData: no habits found")
                }
            }

            // Schedule next refresh at window boundary (SYS-01)
            // 如果有活跃计时器，使用更频繁的刷新（每分钟）
            if (activeTimeLog != null) {
                // 有活跃计时器：调度下一分钟刷新以同步实时进度
                FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(appContext)
            } else {
                FocusWidgetAlarmScheduler.scheduleNextRefresh(appContext)
            }
        }

    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance called for id=$id")

        try {
            refreshWidgetData(context, id)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading initial data", e)
        }

        provideContent {
            GlanceTheme {
                val context = LocalContext.current
                val state = currentState<Preferences>()
                val dataLoaded = state[DATA_LOADED_KEY] ?: false
                val hasHabits = state[HAS_HABITS_KEY] ?: false

                if (!dataLoaded) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(com.dayforge.R.string.common_loading))
                } else if (!hasHabits) {
                    // Empty state (WIDGET-05)
                    EmptyFocusState()
                } else {
                    FocusWidgetContent()
                }
            }
        }
    }

    @Composable
    private fun EmptyFocusState() {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color.LightGray))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = context.getString(com.dayforge.R.string.widget_focus_empty_state),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = ColorProvider(Color.DarkGray)
                )
            )
        }
    }

    @Composable
    private fun FocusWidgetContent() {
        val context = LocalContext.current
        val state = currentState<Preferences>()

        // Read primary habit state
        val habitId = state[PRIMARY_HABIT_ID_KEY] ?: -1L
        val habitName = state[PRIMARY_HABIT_NAME_KEY] ?: ""
        val backgroundColorArgb = state[PRIMARY_BACKGROUND_COLOR_KEY] ?: "#4CAF50".toColorInt()
        val textColorArgb = state[PRIMARY_TEXT_COLOR_KEY] ?: android.graphics.Color.WHITE
        val habitType = try {
            HabitType.valueOf(state[PRIMARY_HABIT_TYPE_KEY] ?: "CHECK_IN")
        } catch (e: Exception) {
            HabitType.CHECK_IN
        }
        val targetValue = state[PRIMARY_TARGET_VALUE_KEY] ?: 1
        val completedToday = state[PRIMARY_COMPLETED_TODAY_KEY] ?: 0
        val todayCount = state[PRIMARY_TODAY_COUNT_KEY] ?: 0  // 实际完成次数（含实时计时）
        val isCompleted = state[PRIMARY_IS_COMPLETED_KEY] ?: false
        val isActive = state[PRIMARY_IS_ACTIVE_KEY] ?: true
        val slotIndex = state[PRIMARY_SLOT_INDEX_KEY] ?: -1
        val totalSlots = state[PRIMARY_TOTAL_SLOTS_KEY] ?: -1
        val nextSlotTime = state[PRIMARY_NEXT_SLOT_TIME_KEY] ?: -1  // COUNTING下一slot时间
        // TIMER习惯实时计时状态
        val isTimerActive = state[PRIMARY_IS_TIMER_ACTIVE_KEY] ?: false
        val timerElapsedSeconds = state[PRIMARY_TIMER_ELAPSED_KEY] ?: 0
        // COUNTING/TIMER习惯的isCountdown模式状态
        val isCountdown = state[PRIMARY_IS_COUNTDOWN_KEY] ?: false
        // 状态标签数据
        val hasFailed = state[PRIMARY_HAS_FAILED_KEY] ?: false
        val isGoalReached = state[PRIMARY_IS_GOAL_REACHED_KEY] ?: false
        val isCheckInAllowedFromState = state[PRIMARY_IS_CHECKIN_ALLOWED_KEY] ?: true

        // Read match result type and timing
        val matchType = state[PRIMARY_MATCH_TYPE_KEY] ?: "NoBestTime"
        val minutesUntilWindow = state[PRIMARY_MINUTES_UNTIL_WINDOW_KEY] ?: 0
        val minutesSinceWindow = state[PRIMARY_MINUTES_SINCE_WINDOW_KEY] ?: 0

        // Read preview habit state (WIDGET-03)
        val previewHabitId = state[PREVIEW_HABIT_ID_KEY] ?: -1L
        val previewHabitName = state[PREVIEW_HABIT_NAME_KEY] ?: ""
        val previewMatchType = state[PREVIEW_MATCH_TYPE_KEY] ?: "NoBestTime"
        val previewMinutesUntil = state[PREVIEW_MINUTES_UNTIL_WINDOW_KEY] ?: 0

        // Determine if check-in is allowed based on match type
        // InWindow: 允许打卡
        // NoBestTime: 无时间偏好，始终允许
        // AfterWindow: 允许补打卡（CHECK_IN类型）
        val isCheckInAllowed = matchType == "InWindow" || matchType == "NoBestTime" || matchType == "AfterWindow"

        // Adjust background opacity for out-of-window states
        val bgColor = when {
            !isActive -> Color(backgroundColorArgb).copy(alpha = 0.5f)
            matchType == "BeforeWindow" -> Color(backgroundColorArgb).copy(alpha = 0.6f)
            matchType == "AfterWindow" -> Color(backgroundColorArgb).copy(alpha = 0.4f)
            else -> Color(backgroundColorArgb)
        }
        val textColor = Color(textColorArgb)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(bgColor))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Primary habit: prominent display with adaptive font (WIDGET-02)
                Text(
                    text = habitName,
                    style = TextStyle(
                        fontSize = StatusLabels.getAdaptiveFontSize(habitName),
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(textColor)
                    ),
                    maxLines = 1
                )

                // Status labels row (显示模式标签和状态)
                // COUNTING/TIMER习惯显示倒计时/正向计时模式
                val modeText = when (habitType) {
                    HabitType.COUNTING -> if (isCountdown) {
                        context.getString(com.dayforge.R.string.widget_status_countdown_counting_mode)
                    } else null
                    HabitType.TIMER -> if (isCountdown) {
                        context.getString(com.dayforge.R.string.widget_status_countdown_mode)
                    } else null
                    else -> null
                }
                StatusLabels.UnifiedLabelsRow(
                    hasFailed = hasFailed,
                    isGoalReached = isGoalReached,
                    isCheckInAllowed = isCheckInAllowedFromState,
                    modeText = modeText
                )

                // COUNTING slot progress (方案I：区分当前slot和已完成状态)
                if (habitType == HabitType.COUNTING && totalSlots > 0) {
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    // 判断当前slot是否已完成（todayCount > slotIndex）
                    val isCurrentSlotCompleted = slotIndex >= 0 && todayCount > slotIndex
                    val progressText = if (isCurrentSlotCompleted && nextSlotTime >= 0) {
                        // 当前slot已完成，显示下一slot信息
                        val nextSlotHour = nextSlotTime / 60
                        val nextSlotMinute = nextSlotTime % 60
                        context.getString(
                            com.dayforge.R.string.widget_counting_next_slot,
                            todayCount,
                            nextSlotHour,
                            nextSlotMinute
                        )
                    } else if (slotIndex >= 0) {
                        // 当前slot未完成，显示当前slot进度
                        context.getString(
                            com.dayforge.R.string.widget_counting_current_slot,
                            slotIndex + 1,
                            totalSlots
                        )
                    } else {
                        // 不在任何slot窗口
                        context.getString(
                            com.dayforge.R.string.widget_counting_completed_slots,
                            todayCount,
                            totalSlots
                        )
                    }
                    Text(
                        text = progressText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = ColorProvider(textColor)
                        )
                    )
                }

                // TIMER habit progress display (含实时计时状态)
                if (habitType == HabitType.TIMER) {
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    val targetMinutes = targetValue
                    val completedMinutes = todayCount / 60  // todayCount是秒数（含实时计时），转换为分钟
                    val elapsedMinutes = timerElapsedSeconds / 60

                    // 显示计时状态和进度
                    val statusText = if (isTimerActive) {
                        // 正在计时：显示实时进度
                        "正在计时 ${elapsedMinutes}/${targetMinutes} 分钟"
                    } else if (completedMinutes >= targetMinutes) {
                        "已完成 ${completedMinutes}/${targetMinutes} 分钟"
                    } else {
                        "进度 ${completedMinutes}/${targetMinutes} 分钟"
                    }
                    Text(
                        text = statusText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = if (isTimerActive) FontWeight.Bold else FontWeight.Normal,
                            color = ColorProvider(textColor)
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Time status info (for BeforeWindow/AfterWindow)
                when (matchType) {
                    "BeforeWindow" -> {
                        val timeHint = formatMinutesUntil(minutesUntilWindow)
                        Text(
                            text = context.getString(com.dayforge.R.string.widget_focus_starting_in, timeHint),
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = ColorProvider(textColor.copy(alpha = 0.8f))
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(12.dp))
                    }
                    "AfterWindow" -> {
                        Text(
                            text = context.getString(com.dayforge.R.string.widget_focus_window_passed),
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = ColorProvider(textColor.copy(alpha = 0.6f))
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(12.dp))
                    }
                }

                // Check-in button area (WIDGET-06: use shared HabitActionButtons)
                // Handle failed/goal-reached states first, then normal check-in
                val shouldShowStatusIndicator = hasFailed || isGoalReached

                if (matchType == "BeforeWindow" && !shouldShowStatusIndicator) {
                    // Show waiting indicator instead of button (only if not failed/completed)
                    HabitActionButtons.WaitingButton(habitId = habitId)
                } else if (shouldShowStatusIndicator) {
                    // Failed or goal reached: show status indicator (matches standalone widgets)
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
                                    fontSize = 14.sp,
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
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ColorProvider(Color(0xFF4CAF50))
                                )
                            )
                        }
                    }
                } else if (habitType == HabitType.COUNTING && isCheckInAllowed) {
                    // COUNTING type: single check-in button + progress
                    // Unified default button styling matches CHECK_IN and TIMER types
                    val remaining = if (isCountdown) {
                        (targetValue - completedToday).coerceAtLeast(0)
                    } else 0
                    val showCompleted = if (isCountdown) remaining <= 0 else completedToday >= targetValue
                    val progressText = if (isCountdown) {
                        context.getString(com.dayforge.R.string.timer_countdown_remaining, remaining)
                    } else {
                        context.getString(com.dayforge.R.string.timer_countup_progress, completedToday, targetValue)
                    }
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
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
                        Button(
                            text = context.getString(com.dayforge.R.string.action_check_in),
                            onClick = actionRunCallback<com.dayforge.widget.checkin.CheckInActionCallback>(
                                actionParametersOf(
                                    ActionParameters.Key<Long>("habitId") to habitId,
                                    ActionParameters.Key<String>("action") to if (isCountdown) "decrement" else "increment"
                                )
                            ),
                            modifier = GlanceModifier.height(36.dp)
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Text(
                            text = progressText,
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(textColor)
                            )
                        )
                    }
                } else if (habitType == HabitType.TIMER && isCheckInAllowed) {
                    // TIMER type: start button with active state (unified styling: height=36dp)
                    HabitActionButtons.TimerStartButton(
                        habitId = habitId,
                        targetMinutes = targetValue,
                        isTimerActive = isTimerActive
                    )
                } else if (isCheckInAllowed) {
                    // CHECK_IN type or AfterWindow (allow makeup check-in)
                    HabitActionButtons.CheckInButton(
                        habitId = habitId,
                        isCompleted = isCompleted
                    )
                } else if (matchType == "AfterWindow") {
                    // AfterWindow but not failed/completed and not in check-in window
                    // Show "已过窗口" indicator (non-clickable, matches standalone widgets for non-checkin days)
                    Text(
                        text = context.getString(R.string.widget_focus_window_passed),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = ColorProvider(textColor.copy(alpha = 0.6f))
                        )
                    )
                }

                // Preview habit: subtle display (WIDGET-03)
                if (previewHabitId != -1L) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    val previewText = when (previewMatchType) {
                        "BeforeWindow" -> {
                            val timeHint = formatMinutesUntil(previewMinutesUntil)
                            context.getString(com.dayforge.R.string.widget_focus_next_in, previewHabitName, timeHint)
                        }
                        "InWindow" -> context.getString(com.dayforge.R.string.widget_focus_next_now, previewHabitName)
                        else -> context.getString(com.dayforge.R.string.widget_focus_next, previewHabitName)
                    }
                    Text(
                        text = previewText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = ColorProvider(textColor.copy(alpha = 0.6f))
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }

    /**
     * Format minutes until window into human-readable text.
     */
    private fun formatMinutesUntil(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes}分钟"
            minutes < 120 -> "1小时${minutes - 60}分钟"
            else -> {
                val hours = minutes / 60
                val mins = minutes % 60
                if (mins == 0) "${hours}小时" else "${hours}小时${mins}分钟"
            }
        }
    }
}
