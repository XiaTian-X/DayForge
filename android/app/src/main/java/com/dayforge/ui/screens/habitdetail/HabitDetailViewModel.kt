package com.dayforge.ui.screens.habitdetail

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.util.DateTimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import javax.inject.Inject

/**
 * Display information for a metric linked to a GOAL habit.
 * Aggregates data from the goal habit and its children.
 */
data class MetricDisplayInfo(
    val metric: MetricEntity,
    val currentValue: String,      // Formatted value or "--" if no records
    val currentRawValue: Double?,  // Raw numeric value for distance calculation
    val trackedDays: Int,          // Days with at least one log
    val targetDays: Int?,          // GOAL habit's targetCycles (for day progress)
    val achievementRate: Int?,     // Day progress percentage
    val distanceToTarget: String?  // Distance to metric target, null if no target
)

data class HabitDetailUiState(
    val habitId: Long = 0,
    val habit: HabitEntity? = null,
    val streakStats: StreakStats? = null,
    val completions: List<CompletionEntity> = emptyList(),
    val timeLogs: List<TimeLogEntity> = emptyList(),
    val lastCompletionId: Long? = null,
    val targetProgress: Int = 0,  // Distinct days completed for habits with targetCycles
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    // Per TARGET-02: Reactivation confirmation dialog state
    val showReactivationDialog: Boolean = false,
    val reactivationHabitName: String = "",
    // Metrics for GOAL habits: aggregated from self and children
    val metrics: List<MetricDisplayInfo> = emptyList(),
    // Per NOTIFY-04: Notification enabled state for this habit
    val notificationEnabled: Boolean = true
)

@HiltViewModel
class HabitDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository,
    private val preferencesManager: PreferencesManager,
    private val timeLogDao: TimeLogDao,
    private val completionDao: CompletionDao,
    private val habitDao: HabitDao,
    private val habitMetricLinkDao: HabitMetricLinkDao,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HabitDetailUiState())
    val uiState: StateFlow<HabitDetailUiState> = _uiState.asStateFlow()

    private var currentHabitId: Long? = null

    fun loadHabit(habitId: Long) {
        // Reset if different habit to ensure fresh data load
        if (currentHabitId != null && currentHabitId != habitId) {
            _uiState.value = HabitDetailUiState(habitId = habitId, isLoading = true)
        }
        if (currentHabitId == habitId) return
        currentHabitId = habitId

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, habitId = habitId)

            // Combine habit info, completions, and notification setting for reactive updates
            // Per NOTIFY-04: Include notification preference to avoid nested collect leak
            combine(
                habitRepository.getHabit(habitId),
                habitRepository.getAllCompletions(),
                preferencesManager.getHabitNotificationEnabled(habitId)
            ) { habit, allCompletions, notificationEnabled ->
                val habitCompletions = allCompletions.filter { it.habitId == habitId }

                // Calculate targetProgress for habits with targetCycles
                // Per TARGET-06: TIMER habits use timelogs, other types use completions
                val targetProgress = if (habit?.targetCycles != null) {
                    if (habit.habitType == HabitType.TIMER) {
                        timeLogDao.getDistinctDayCount(habitId)
                    } else {
                        completionDao.getDistinctDayCount(habitId)
                    }
                } else {
                    0
                }

                // For TIMER habits, use TimeLogEntity for streaks and history
                if (habit?.habitType == HabitType.TIMER) {
                    val timeLogs = timeLogDao.getAllTimeLogsForHabit(habitId)
                    val targetSeconds = habit.targetValue * 60

                    // Get dates where target was met
                    val completedDates = timeLogs
                        .groupBy { it.date }
                        .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                        .keys
                        .toList()

                    val currentStreak = StreakCalculator.calculateCurrentStreakFromDates(completedDates)
                    val bestStreak = StreakCalculator.calculateBestStreakFromDates(completedDates)
                    val lastCompletionDate = completedDates.maxOrNull()
                    val streakStats = StreakStats(currentStreak, bestStreak, lastCompletionDate)

                    // Create pseudo-completions from time logs for history display
                    val completions = timeLogs
                        .groupBy { it.date }
                        .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                        .map { (date, _) ->
                            CompletionEntity(habitId = habitId, date = date, value = 1)
                        }

                    LoadResult(habit, streakStats, completions, timeLogs, targetProgress, emptyList(), notificationEnabled)
                } else if (habit?.habitType == HabitType.GOAL) {
                    // GOAL habit: aggregate metrics from self and children
                    val metrics = loadGoalHabitMetrics(habit)
                    LoadResult(habit, null, emptyList(), emptyList(), targetProgress, metrics, notificationEnabled)
                } else {
                    val currentStreak = StreakCalculator.calculateCurrentStreak(habitCompletions)
                    val bestStreak = StreakCalculator.calculateBestStreak(habitCompletions)
                    val lastCompletionDate = habitCompletions.maxByOrNull { it.date }?.date
                    val streakStats = StreakStats(currentStreak, bestStreak, lastCompletionDate)

                    LoadResult(habit, streakStats, habitCompletions, emptyList(), targetProgress, emptyList(), notificationEnabled)
                }
            }.collect { result ->
                _uiState.value = _uiState.value.copy(
                    habit = result.habit,
                    streakStats = result.streakStats,
                    completions = result.completions,
                    timeLogs = result.timeLogs,
                    targetProgress = result.targetProgress,
                    metrics = result.metrics,
                    notificationEnabled = result.notificationEnabled,  // Per NOTIFY-04: From combined flow
                    isLoading = false
                )
            }
        }
    }

    /**
     * Toggles notification enabled state for the current habit.
     * Per NOTIFY-04: User can disable notifications per-habit.
     *
     * @param habitId The ID of the habit
     * @param enabled True to enable notifications for this habit, false to disable
     */
    fun toggleNotificationEnabled(habitId: Long, enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setHabitNotificationEnabled(habitId, enabled)
            _uiState.value = _uiState.value.copy(notificationEnabled = enabled)
        }
    }

    /**
     * Loads and aggregates metrics for a GOAL habit and its children.
     * Deduplicates metrics by metricId.
     * Uses Dispatchers.IO to ensure database operations run off the main thread.
     */
    private suspend fun loadGoalHabitMetrics(habit: HabitEntity): List<MetricDisplayInfo> = withContext(Dispatchers.IO) {
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY

        // Get metric links for the goal habit itself
        val ownLinks = habitMetricLinkDao.getAllLinksForHabit(habit.id)

        // Get children and their metric links
        val children = habit.uuid.let { parentUuid ->
            habitDao.getChildrenByParentUuid(parentUuid).first()
        }
        val childLinks = children.flatMap { child ->
            habitMetricLinkDao.getAllLinksForHabit(child.id)
        }

        // Aggregate all links, deduplicate by metricId
        val allLinks = (ownLinks + childLinks)
            .groupBy { it.metricId }
            .map { it.value.first() }

        // Build MetricDisplayInfo for each unique metric
        allLinks.mapNotNull { link ->
            val metric = metricDao.getMetricById(link.metricId)
            if (metric == null) return@mapNotNull null

            // Get today's logs
            val todayLogs = metricLogDao.getLogsInRangeSync(link.metricId, todayStart, todayEnd)

            // Calculate current value (raw and formatted)
            val (formattedValue, rawValue) = calculateCurrentValueWithRaw(metric, todayLogs)

            // Get all logs to count tracked days
            val allLogs = metricLogDao.getAllLogsForMetric(link.metricId)
            val trackedDays = allLogs.map { DateTimeUtils.startOfDayMillis(it.date) }.distinct().size

            // Calculate targetDays and achievementRate based on GOAL habit's targetCycles
            val targetDays = habit.targetCycles
            val achievementRate = if (targetDays != null && trackedDays > 0) {
                ((trackedDays.toDouble() / targetDays.toDouble()) * 100).toInt().coerceIn(0, 100)
            } else null

            // Calculate distance to metric target
            val distanceToTarget = calculateDistanceToTarget(metric, rawValue)

            MetricDisplayInfo(
                metric = metric,
                currentValue = formattedValue,
                currentRawValue = rawValue,
                trackedDays = trackedDays,
                targetDays = targetDays,
                achievementRate = achievementRate,
                distanceToTarget = distanceToTarget
            )
        }.sortedByDescending { it.trackedDays }
    }

    /**
     * Calculates current value based on metric's aggregation type.
     * Returns pair of (formatted string, raw double value).
     * Returns ("--", null) if no logs.
     */
    private fun calculateCurrentValueWithRaw(metric: MetricEntity, logs: List<MetricLogEntity>): Pair<String, Double?> {
        if (logs.isEmpty()) return Pair("--", null)

        val value = when (metric.aggregationType) {
            "average" -> logs.sumOf { it.value } / logs.size
            "sum" -> logs.sumOf { it.value }
            "by_time" -> logs.maxByOrNull { it.date }?.value ?: return Pair("--", null)
            else -> logs.sumOf { it.value } / logs.size // Default to average
        }

        // Format based on decimalPlaces - use integer format when decimalPlaces is 0
        val pattern = if (metric.decimalPlaces == 0) "#" else "#.${"#".repeat(metric.decimalPlaces)}"
        return Pair(DecimalFormat(pattern).format(value), value)
    }

    /**
     * Calculates distance to metric target.
     * Handles three target directions: increase, decrease, range.
     * Returns null if metric has no target or no current value.
     */
    private fun calculateDistanceToTarget(metric: MetricEntity, currentValue: Double?): String? {
        if (currentValue == null || metric.targetDirection == null) return null

        val unit = metric.unit
        val decimalPlaces = metric.decimalPlaces
        val pattern = if (decimalPlaces == 0) "#" else "#.${"#".repeat(decimalPlaces)}"
        val formatter = DecimalFormat(pattern)

        return when (metric.targetDirection) {
            "increase" -> {
                val target = metric.targetValue ?: return null
                if (currentValue >= target) {
                    // Already reached target
                    null
                } else {
                    val distance = target - currentValue
                    "距离目标还差${formatter.format(distance)}$unit"
                }
            }
            "decrease" -> {
                val target = metric.targetValue ?: return null
                if (currentValue <= target) {
                    // Already reached target
                    null
                } else {
                    val distance = currentValue - target
                    "距离目标还差${formatter.format(distance)}$unit"
                }
            }
            "range" -> {
                val lower = metric.targetValue ?: return null
                val upper = metric.targetValueUpper ?: return null
                if (currentValue >= lower && currentValue <= upper) {
                    // Within range
                    null
                } else if (currentValue < lower) {
                    val distance = lower - currentValue
                    "距离下限还差${formatter.format(distance)}$unit"
                } else {
                    val distance = currentValue - upper
                    "距离上限还差${formatter.format(distance)}$unit"
                }
            }
            else -> null
        }
    }

    private data class LoadResult(
        val habit: HabitEntity?,
        val streakStats: StreakStats?,
        val completions: List<CompletionEntity>,
        val timeLogs: List<TimeLogEntity>,
        val targetProgress: Int,
        val metrics: List<MetricDisplayInfo>,
        val notificationEnabled: Boolean  // Per NOTIFY-04: Included to avoid nested collect
    )

    fun logCompletion(value: Int = 1) {
        viewModelScope.launch {
            currentHabitId?.let { habitId ->
                val completionId = habitRepository.logCompletion(context, habitId, value)
                _uiState.value = _uiState.value.copy(
                    lastCompletionId = completionId
                )
            }
        }
    }

    fun undoCompletion() {
        viewModelScope.launch {
            _uiState.value.lastCompletionId?.let { completionId ->
                habitRepository.undoCompletion(context, completionId)
                _uiState.value = _uiState.value.copy(
                    lastCompletionId = null
                )
            }
        }
    }

    /**
     * Checks if the active status can be toggled for the given habit.
     * Blocks deactivation if the habit has a target and progress > 0.
     * Per TARGET-06: TIMER habits use timelogs table for progress calculation.
     *
     * @param habit The habit to check
     * @return true if toggle is allowed, false otherwise
     */
    private suspend fun canToggleActive(habit: HabitEntity): Boolean {
        // Reactivation is always allowed (will be handled by TARGET-02 for history clearing)
        if (!habit.isActive) return true

        // No target cycles means unlimited tracking - allow deactivation
        if (habit.targetCycles == null) return true

        // Check progress - Per TARGET-06: TIMER uses timelogs, others use completions
        val progress = if (habit.habitType == HabitType.TIMER) {
            timeLogDao.getDistinctDayCount(habit.id)
        } else {
            completionDao.getDistinctDayCount(habit.id)
        }

        // Block deactivation if there's progress
        return progress == 0
    }

    /**
     * Toggles the active status of the current habit.
     * Validates that deactivation is allowed before proceeding.
     * Per TARGET-02: For reactivation of target-based habits, shows confirmation dialog first.
     * Syncs to server if user is logged in.
     */
    fun toggleActiveStatus() {
        viewModelScope.launch {
            currentHabitId?.let { habitId ->
                val habit = _uiState.value.habit ?: return@let
                val newIsActive = !habit.isActive

                // If trying to deactivate, validate first
                if (!newIsActive) {
                    val canToggle = canToggleActive(habit)
                    if (!canToggle) {
                        // Show error message
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_goal_habit_cannot_deactivate),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                }

                // Per TARGET-02: If reactivating a target-based habit, show confirmation dialog first
                if (!habit.isActive && newIsActive && habit.targetCycles != null) {
                    _uiState.value = _uiState.value.copy(
                        showReactivationDialog = true,
                        reactivationHabitName = habit.name
                    )
                    return@launch
                }

                // Direct toggle for non-target habits or deactivation
                performToggleActive(habitId, newIsActive)
            }
        }
    }

    /**
     * Confirms reactivation and clears history before setting active.
     * Per TARGET-02: Called when user confirms the reactivation dialog.
     */
    fun confirmReactivation() {
        viewModelScope.launch {
            currentHabitId?.let { habitId ->
                val habit = _uiState.value.habit ?: return@let
                // Clear history first
                habitRepository.clearHabitHistory(habit, context)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_habit_reactivated),
                    Toast.LENGTH_SHORT
                ).show()
                // Perform toggle
                performToggleActive(habitId, true)
            }
            // Close dialog
            _uiState.value = _uiState.value.copy(
                showReactivationDialog = false,
                reactivationHabitName = ""
            )
        }
    }

    /**
     * Dismisses the reactivation confirmation dialog without making changes.
     * Per TARGET-02: Called when user cancels the reactivation dialog.
     */
    fun dismissReactivationDialog() {
        _uiState.value = _uiState.value.copy(
            showReactivationDialog = false,
            reactivationHabitName = ""
        )
    }

    /**
     * Performs the actual toggle active operation.
     * @param habitId The ID of the habit
     * @param newIsActive The new active status
     */
    private suspend fun performToggleActive(habitId: Long, newIsActive: Boolean) {
        habitRepository.updateIsActive(
            habitId = habitId,
            isActive = newIsActive,
            context = context
        )
    }

    fun getStreakStats(): StreakStats? {
        return _uiState.value.streakStats
    }
}
