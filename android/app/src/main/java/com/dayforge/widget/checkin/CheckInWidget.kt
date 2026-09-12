package com.dayforge.widget.checkin

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetFailureChecker
import com.dayforge.widget.base.StatusLabels
import com.dayforge.widget.base.WidgetColorResolver
import com.dayforge.widget.base.WidgetEmptyStates
import com.dayforge.di.ThemeManagerEntryPoint
import kotlinx.coroutines.CancellationException

import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import com.dayforge.R
import androidx.compose.ui.platform.LocalContext

/**
 * 1x1 Check-in Widget for quick habit completion.
 *
 * Architecture:
 * - provideGlance (suspend): loads data from Room DB and writes to Glance state
 * - provideContent (@Composable): reads Glance state and renders UI
 *
 * This separation is critical because:
 * - provideGlance runs in a background coroutine (can access Room)
 * - provideContent runs on the main thread (cannot access Room)
 * - update() only re-runs provideContent, not provideGlance
 * - So we must push fresh data into Glance state before calling update()
 */
class CheckInWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "CheckInWidget"

        const val PREFS_NAME = "widget_prefs"
        const val PREF_HABIT_ID_PREFIX = "habit_id_"

        // Glance DataStore keys
        val HABIT_ID_KEY = longPreferencesKey("habitId")
        val HABIT_NAME_KEY = stringPreferencesKey("habitName")
        val COLOR_HEX_KEY = stringPreferencesKey("colorHex")
        val IS_COMPLETED_KEY = booleanPreferencesKey("isCompleted")
        val DATA_LOADED_KEY = booleanPreferencesKey("dataLoaded")
        val IS_DELETED_KEY = booleanPreferencesKey("isDeleted")
        val APP_WIDGET_ID_KEY = intPreferencesKey("appWidgetId")
        val IS_ACTIVE_KEY = booleanPreferencesKey("isActive")
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

        /**
         * Load habit data from Room and write into Glance state.
         * Call this from any suspend context (config activity, action callback, etc.)
         * then call update() to trigger recomposition.
         */
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

            val today = DateTimeUtils.startOfDayMillis()
            val tomorrow = today + DateTimeUtils.MILLIS_PER_DAY
            val completions = database.completionDao().getCompletionsInRange(habitId, today, tomorrow)
            val completedToday = completions.sumOf { it.value }
            val isCompleted = completedToday >= habit.targetValue

            // 新增状态计算
            val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)
            val nextCheckInDate = if (!isCheckInAllowed) {
                ScheduleValidator.getNextCheckInDate(habit.schedule, habit.createdAt).toString()
            } else ""

            // Target progress - any completion counts (CHECK_IN only)
            val targetProgress = if (habit.targetCycles != null) {
                database.completionDao().getDistinctDayCount(habitId)
            } else 0
            // 目标完成：进度达标且习惯已被停用（用户点击了"确认完成"）
            val isGoalReached = habit.targetCycles != null && targetProgress >= habit.targetCycles && !habit.isActive

            // 失败状态判定
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
                prefs[IS_COMPLETED_KEY] = isCompleted
                prefs[IS_ACTIVE_KEY] = habit.isActive
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
            Log.d(TAG, "refreshWidgetData: wrote state for habit '${habit.name}', completed=$completedToday, bgColor=${resolvedColors.backgroundColorArgb}")
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance called for id=$id")

        // On first run, load data from Room into Glance state
        // (provideGlance is suspend, safe to access Room here)
        try {
            val appWidgetId = androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(id)
            val state = getAppWidgetState<androidx.datastore.preferences.core.Preferences>(context, id)
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
                // Read all data from Glance state – runs on every recomposition
                val context = androidx.glance.LocalContext.current
                val state = currentState<androidx.datastore.preferences.core.Preferences>()
                val habitId = state[HABIT_ID_KEY] ?: -1L
                val dataLoaded = state[DATA_LOADED_KEY] ?: false
                val isDeleted = state[IS_DELETED_KEY] ?: false
                val appWidgetId = state[APP_WIDGET_ID_KEY] ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID

                Log.d(TAG, "provideContent: habitId=$habitId, dataLoaded=$dataLoaded")

                if (habitId == -1L) {
                    WidgetEmptyStates.EmptyConfigState(context.getString(R.string.widget_configure_first))
                } else if (isDeleted) {
                    val intent = android.content.Intent(context, CheckInWidgetConfigActivity::class.java).apply {
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
                    val backgroundColorArgb = state[BACKGROUND_COLOR_KEY] ?: android.graphics.Color.parseColor("#4CAF50")
                    val textColorArgb = state[TEXT_COLOR_KEY] ?: android.graphics.Color.WHITE
                    CheckInWidgetContent(
                        habitName = state[HABIT_NAME_KEY] ?: "",
                        backgroundColorArgb = backgroundColorArgb,
                        textColorArgb = textColorArgb,
                        isActive = isActive,
                        isCompleted = state[IS_COMPLETED_KEY] ?: false,
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
    private fun CheckInWidgetContent(
        habitName: String,
        backgroundColorArgb: Int,
        textColorArgb: Int,
        isActive: Boolean,
        isCompleted: Boolean,
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
                    modeText = null
                )

                Spacer(modifier = GlanceModifier.height(8.dp))

                // 按钮是否隐藏（失败/目标完成/非打卡日）
                val shouldHideButton = !isCheckInAllowed || hasFailed || isGoalReached

                if (shouldHideButton) {
                    // 在原来打卡按钮位置显示失败/完成状态，点击可重新激活
                    if (hasFailed) {
                        val intent = ReactivationActivity.createIntent(
                            androidx.glance.LocalContext.current,
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
                            androidx.glance.LocalContext.current,
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
                    // 非打卡日不显示任何可点击内容
                } else {
                    if (isCompleted) {
                        Button(
                            text = context.getString(R.string.widget_status_completed),
                            onClick = actionRunCallback<CheckInActionCallback>(
                                actionParametersOf(
                                    ActionParameters.Key<Long>("habitId") to habitId,
                                    ActionParameters.Key<String>("action") to "toggle"
                                )
                            ),
                            modifier = GlanceModifier.height(40.dp)
                        )
                    } else {
                        Button(
                            text = context.getString(R.string.action_check_in),
                            onClick = actionRunCallback<CheckInActionCallback>(
                                actionParametersOf(
                                    ActionParameters.Key<Long>("habitId") to habitId,
                                    ActionParameters.Key<String>("action") to "toggle"
                                )
                            ),
                            modifier = GlanceModifier.height(40.dp)
                        )
                    }
                }
            }
        }
    }
}
