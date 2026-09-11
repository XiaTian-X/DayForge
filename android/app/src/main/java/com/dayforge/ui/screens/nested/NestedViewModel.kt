package com.dayforge.ui.screens.nested

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.MetricValueDraft
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.ScheduleValidator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.domain.service.TimerManager
import com.dayforge.domain.service.TimerService
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.screens.dashboard.ActiveTimerState
import com.dayforge.util.DateTimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Data class representing a child habit with its completion stats.
 * Used for displaying child habits in ParentHabitCard.
 *
 * Per NEST-04: Child habits have check-in support.
 */
data class ChildHabitWithStats(
    val habit: HabitEntity,
    val completedToday: Boolean,
    val todayCount: Int,
    val lastCompletionId: Long?,
    val currentStreak: Int,
    val bestStreak: Int,
    val activityRate: Int = 100,  // 活跃度 0-100
    val isCheckInAllowed: Boolean = true,
    val nextCheckInDate: LocalDate? = null,
    val targetProgress: Int = 0,  // Distinct days completed for habits with targetCycles
    val hasFailed: Boolean = false  // Failure status for target-based habits
) {
    /**
     * Whether the goal has been completed (reached targetCycles and deactivated).
     * Used for CompletionButton to show "目标已完成" state.
     */
    val isGoalCompleted: Boolean
        get() = habit.targetCycles != null && targetProgress >= habit.targetCycles && !habit.isActive
}

/**
 * Data class representing a parent habit with its children and completion stats.
 * Used for displaying top-level habits in NestedScreen.
 *
 * Per NEST-02: Parent habits displayed with child lists.
 * Per NEST-05: Aggregated completion progress.
 */
data class ParentHabitWithChildren(
    val habit: HabitEntity,
    val children: List<ChildHabitWithStats>,
    val completedChildren: Int,
    val totalChildren: Int,
    val totalChildrenIncludingNonCheckInDays: Int,  // All children count (for UI display)
    val isCheckInAllowed: Boolean = true,   // True for Daily schedule, calculated for Weekly/Monthly/Custom
    val nextCheckInDate: LocalDate? = null, // Null if check-in allowed today, otherwise next valid check-in date
    val dayProgress: Int = 0  // Days since creation (for GOAL type display "第 X 天 / 共 Y 天")
)

/**
 * Result of attempting to start a timer.
 * Used to communicate to UI whether to show confirmation dialogs.
 */
sealed class StartTimerResult {
    object Started : StartTimerResult()
    object AlreadyCompleted : StartTimerResult()
    data class HasActiveTimer(val currentHabitId: Long) : StartTimerResult()
}

/**
 * State for the post-check-in dialog that prompts users to record linked metrics.
 */
data class PostCheckInState(
    val habitId: Long,
    val habitName: String,
    val linkedMetrics: List<LinkedMetricInfo>,
    val show: Boolean = true
)

@HiltViewModel
class NestedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val habitRepository: HabitRepository,
    private val checkInService: CheckInService,
    private val preferencesManager: PreferencesManager,
    private val habitMetricLinkDao: HabitMetricLinkDao,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val metricRepository: MetricRepository,
    private val failureChecker: FailureChecker,
) : ViewModel() {

    // Shared timer management
    private val timerManager = TimerManager(context, habitDao, timeLogDao)
    private val activeTimerStateProvider = ActiveTimerStateProvider(
        timeLogDao, habitRepository, viewModelScope, context
    )

    companion object {
        private const val TAG = "NestedViewModel"
    }

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Listen to all completions to trigger UI updates
    private val allCompletions = habitRepository.getAllCompletions()

    /**
     * Top-level habits (parentHabitId = null) with their children and stats.
     */
    val topLevelHabitsWithChildren: StateFlow<List<ParentHabitWithChildren>> = combine(
        habitDao.getTopLevelHabits(),
        allCompletions,
        timeLogDao.getActiveTimeLogFlow(),
        preferencesManager.dateChangeTrigger  // Triggers when date changes
    ) { topLevelHabits, completions, activeTimeLog, _ ->
        Log.d(TAG, "topLevelHabitsWithChildren combine triggered: topLevelHabits=${topLevelHabits.size}")

        topLevelHabits.map { parentHabit ->
            val children = withContext(Dispatchers.IO) {
                habitDao.getChildrenByParentUuid(parentHabit.uuid).first()
            }

            val childrenWithStats = children.map { child ->
                calculateChildStats(child, completions)
            }

            // Sort children by isActive && isCheckInAllowed
            val sortedChildren = childrenWithStats.sortedByDescending { it.habit.isActive && it.isCheckInAllowed }

            // Only count children where today is a check-in day (isCheckInAllowed = true)
            // Per NEST-05: Progress should exclude non-check-in-day habits
            // Also exclude completed goals and failed habits from progress calculation
            val checkInAllowedChildren = sortedChildren.filter {
                it.isCheckInAllowed && !it.isGoalCompleted && !it.hasFailed
            }
            val completedChildren = checkInAllowedChildren.count { it.completedToday }
            val totalChildren = checkInAllowedChildren.size

            // Calculate isCheckInAllowed for parent
            val parentIsCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(parentHabit.schedule, parentHabit.createdAt)
            val parentNextCheckInDate = if (!parentIsCheckInAllowed) {
                ScheduleValidator.getNextCheckInDate(parentHabit.schedule, parentHabit.createdAt)
            } else null

            // Calculate day progress for GOAL type
            val dayProgress = if (parentHabit.habitType == HabitType.GOAL) {
                val creationDate = Instant.ofEpochMilli(parentHabit.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                ChronoUnit.DAYS.between(creationDate, LocalDate.now()).toInt() + 1
            } else 0

            ParentHabitWithChildren(
                habit = parentHabit,
                children = sortedChildren,
                completedChildren = completedChildren,
                totalChildren = totalChildren,
                totalChildrenIncludingNonCheckInDays = sortedChildren.size,
                isCheckInAllowed = parentIsCheckInAllowed,
                nextCheckInDate = parentNextCheckInDate,
                dayProgress = dayProgress
            )
        }
            // Show GOAL type habits (even without children) and parent habits with children
            .filter { it.habit.habitType == HabitType.GOAL || it.totalChildren > 0 }
            .sortedByDescending { it.habit.isActive && it.isCheckInAllowed }
    }
        .onEach { _isInitialized.value = true }
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    /**
     * Active timer state for real-time UI updates.
     * Delegates to ActiveTimerStateProvider for shared implementation.
     */
    val activeTimerState: StateFlow<ActiveTimerState?> = activeTimerStateProvider.activeTimerState

    /**
     * Habits that have pending metric recording.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = preferencesManager.pendingMetricHabits
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    /**
     * Expanded parent habit UUIDs for persisting expand/collapse state.
     */
    val expandedParentUuids: StateFlow<Set<String>> = preferencesManager.expandedParentUuids
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    /**
     * Card color style preference for reactive card rendering.
     * Per CARD-09: Triggers instant recomposition when style changes.
     */
    val cardColorStyle: StateFlow<CardColorStyle> = preferencesManager.cardColorStyle
        .map { CardColorStyle.fromStringOrDefault(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = CardColorStyle.DEFAULT
        )

    /**
     * Linked metrics by habit ID.
     */
    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> = habitRepository.allHabits
        .map { habits ->
            val result = mutableMapOf<Long, List<LinkedMetricInfo>>()
            habits.forEach { habit ->
                try {
                    val links = habitMetricLinkDao.getLinksByHabit(habit.id).first()
                    val infos = links.mapNotNull { link ->
                        val metric = metricDao.getMetricById(link.metricId) ?: return@mapNotNull null
                        val latestLog = metricLogDao.getLatestLog(link.metricId)
                        LinkedMetricInfo(
                            metricName = metric.name,
                            metricId = metric.id,
                            latestValue = latestLog?.value,
                            unit = metric.unit,
                            decimalPlaces = metric.decimalPlaces
                        )
                    }
                    if (infos.isNotEmpty()) {
                        result[habit.id] = infos
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
            result
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyMap()
        )

    /**
     * State for the post-check-in dialog.
     */
    private val _postCheckInState = MutableStateFlow<PostCheckInState?>(null)
    val postCheckInState: StateFlow<PostCheckInState?> = _postCheckInState

    // ========== Goal Completion Dialog State ==========

    /**
     * State for the goal completion dialog shown when targetCycles is reached.
     * Per TARGET-08: Dialog appears when check-in reaches target cycles.
     */
    private val _showGoalDialog = MutableStateFlow(false)
    val showGoalDialog: StateFlow<Boolean> = _showGoalDialog.asStateFlow()

    private val _goalHabitId = MutableStateFlow<Long?>(null)
    val goalHabitId: StateFlow<Long?> = _goalHabitId.asStateFlow()

    private val _goalProgress = MutableStateFlow(0)
    val goalProgress: StateFlow<Int> = _goalProgress.asStateFlow()

    private val _goalTarget = MutableStateFlow(0)
    val goalTarget: StateFlow<Int> = _goalTarget.asStateFlow()

    // ========== Reactivation Dialog State ==========

    private val _showReactivationDialog = MutableStateFlow(false)
    val showReactivationDialog: StateFlow<Boolean> = _showReactivationDialog.asStateFlow()

    private val _reactivationHabitId = MutableStateFlow<Long?>(null)
    val reactivationHabitId: StateFlow<Long?> = _reactivationHabitId.asStateFlow()

    private val _reactivationHabitName = MutableStateFlow("")
    val reactivationHabitName: StateFlow<String> = _reactivationHabitName.asStateFlow()

    // Children deletion dialog state
    data class PendingDeleteInfo(val habit: HabitEntity, val childCount: Int)
    private val _showChildrenDialog = MutableStateFlow<PendingDeleteInfo?>(null)
    val showChildrenDialog: StateFlow<PendingDeleteInfo?> = _showChildrenDialog.asStateFlow()

    /**
     * Calculate stats for a child habit.
     */
    private suspend fun calculateChildStats(
        child: HabitEntity,
        completions: List<com.dayforge.data.local.entity.CompletionEntity>
    ): ChildHabitWithStats {
        val habitCompletions = completions.filter { it.habitId == child.id }
        val todayCompletions = habitCompletions.filter {
            isToday(it.date)
        }

        val (todayCount, currentStreak, bestStreak) = if (child.habitType == HabitType.TIMER) {
            val allTimeLogs = withContext(Dispatchers.IO) {
                timeLogDao.getAllTimeLogsForHabit(child.id)
            }

            val todayStart = DateTimeUtils.startOfDayMillis()
            val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY
            val todayLogs = allTimeLogs.filter { it.date in todayStart until todayEnd }
            val count = todayLogs.sumOf { it.durationSeconds }

            val targetSeconds = child.targetValue * 60
            val completedDates = allTimeLogs
                .groupBy { it.date }
                .filter { (_, logs) -> logs.sumOf { it.durationSeconds } >= targetSeconds }
                .keys
                .toList()

            Triple(
                count,
                StreakCalculator.calculateCurrentStreakFromDates(completedDates),
                StreakCalculator.calculateBestStreakFromDates(completedDates)
            )
        } else {
            Triple(
                todayCompletions.sumOf { it.value },
                StreakCalculator.calculateCurrentStreak(habitCompletions),
                StreakCalculator.calculateBestStreak(habitCompletions)
            )
        }

        val todayCompletionId = todayCompletions.maxByOrNull { it.id }?.id

        val completedToday = when (child.habitType) {
            HabitType.CHECK_IN -> todayCount > 0
            HabitType.COUNTING -> todayCount >= child.targetValue
            HabitType.TIMER -> todayCount >= child.targetValue * 60
            HabitType.GOAL -> false  // GOAL type doesn't have check-ins
        }

        // Check-in day validation for Weekly/Monthly/Custom schedules
        val isCheckInAllowed = ScheduleValidator.isCheckInAllowedToday(child.schedule, child.createdAt)
        val nextCheckInDate = if (!isCheckInAllowed) {
            ScheduleValidator.getNextCheckInDate(child.schedule, child.createdAt)
        } else null

        // Calculate targetProgress for habits with targetCycles
        // Per TARGET-06: TIMER habits use timelogs, other types use completions
        val targetProgress = if (child.targetCycles != null) {
            withContext(Dispatchers.IO) {
                if (child.habitType == HabitType.TIMER) {
                    timeLogDao.getDistinctDayCount(child.id)
                } else {
                    completionDao.getDistinctDayCount(child.id)
                }
            }
        } else {
            0
        }

        // Check failure status for target-based habits
        val hasFailed = if (child.targetCycles != null) {
            withContext(Dispatchers.IO) {
                val firstCompletionDateMillis = if (child.habitType == HabitType.TIMER) {
                    timeLogDao.getFirstTimeLogDate(child.id)
                } else {
                    completionDao.getFirstCompletionDate(child.id)
                }
                val firstCompletionDate = firstCompletionDateMillis?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                }
                failureChecker.hasFailed(child, firstCompletionDate)
            }
        } else {
            false
        }

        return ChildHabitWithStats(
            habit = child,
            completedToday = completedToday,
            todayCount = todayCount,
            lastCompletionId = todayCompletionId,
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            activityRate = child.activityRate,
            isCheckInAllowed = isCheckInAllowed,
            nextCheckInDate = nextCheckInDate,
            targetProgress = targetProgress,
            hasFailed = hasFailed
        )
    }

    // ========== Check-in Operations ==========

    /**
     * Log a completion for a child habit.
     * Uses CheckInService for consistent behavior with DashboardViewModel.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun logCompletion(habitId: Long, value: Int = 1) {
        viewModelScope.launch {
            val result = checkInService.toggleCheckIn(context, habitId)
            // Check for goal reached (TARGET-15)
            if (result is CheckInResult.Success && result.goalReached) {
                onGoalReached(habitId, result.progress)
            }
            // Trigger metric dialog only on successful check-in (not undo)
            val habit = findHabitById(habitId)
            if (habit != null && result is CheckInResult.Success && result.completed) {
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    /**
     * Undo a completion for a child habit.
     */
    fun undoCompletion(completionId: Long) {
        viewModelScope.launch {
            habitRepository.undoCompletion(context, completionId)
        }
    }

    /**
     * Increment count for a COUNTING habit.
     * Uses CheckInService for consistent behavior with widgets.
     * Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     */
    fun incrementCount(habitId: Long) {
        viewModelScope.launch {
            val result = checkInService.incrementCount(context, habitId)
            // Check for goal reached (TARGET-08)
            if (result is CheckInResult.Success && result.goalReached) {
                onGoalReached(habitId, result.progress)
            }
            val habit = findHabitById(habitId)
            if (habit != null) {
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses CheckInService for consistent behavior with widgets.
     */
    fun decrementCount(habitId: Long) {
        viewModelScope.launch {
            val result = checkInService.decrementCount(context, habitId)
            // Check for goal reached (TARGET-08)
            if (result is CheckInResult.Success && result.goalReached) {
                onGoalReached(habitId, result.progress)
            }
        }
    }

    // ========== Timer Operations ==========

    /**
     * Check if can start a timer. Returns result for UI to handle.
     *
     * @return StartTimerResult indicating whether to start, already completed, or has active timer
     */
    fun checkStartTimer(habitId: Long, targetMinutes: Int): StartTimerResult {
        val currentTimer = activeTimerState.value

        // Check if there's an active timer for a different habit
        if (currentTimer != null && currentTimer.habitId != habitId) {
            return StartTimerResult.HasActiveTimer(currentTimer.habitId)
        }

        // Check if already completed today
        viewModelScope.launch {
            val todayStart = DateTimeUtils.startOfDayMillis()
            val todayEnd = todayStart + DateTimeUtils.MILLIS_PER_DAY
            val completedSeconds = timeLogDao.getCompletedDurationSecondsForDate(
                habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
            )
            val targetSeconds = targetMinutes * 60

            if (completedSeconds >= targetSeconds) {
                // Already completed - UI should handle this
            }
        }

        return StartTimerResult.Started
    }

    /**
     * Start a timer for a child habit.
     * Delegates to TimerManager for shared implementation.
     */
    fun startTimer(habitId: Long, targetMinutes: Int) {
        viewModelScope.launch {
            timerManager.startTimer(habitId, targetMinutes)
        }
    }

    /**
     * Pause the currently running timer.
     * Delegates to TimerManager for shared implementation.
     */
    fun pauseTimer() {
        val currentState = activeTimerState.value ?: return
        timerManager.pauseTimer(currentState.habitId, currentState.targetMinutes)
    }

    /**
     * Resume a paused timer.
     * Delegates to TimerManager for shared implementation.
     */
    fun resumeTimer() {
        val currentState = activeTimerState.value ?: return
        timerManager.resumeTimer(currentState.habitId, currentState.targetMinutes)
    }

    /**
     * Stop the currently running timer.
     * Delegates to TimerManager for shared implementation.
     */
    fun stopTimer() {
        val currentState = activeTimerState.value ?: return

        viewModelScope.launch {
            val stoppedHabitId = timerManager.stopTimer(
                habitId = currentState.habitId,
                targetMinutes = currentState.targetMinutes
            )

            // Trigger metric dialog for TIMER habits after stopping
            if (stoppedHabitId != null) {
                delay(100)
                val habitForDialog = habitDao.getHabitById(stoppedHabitId)
                if (habitForDialog != null) {
                    checkAndShowPostCheckInDialog(stoppedHabitId, habitForDialog.name)
                }
            }
        }
    }

    /**
     * Check if a specific habit has the active timer.
     */
    fun isHabitTimerActive(habitId: Long): Boolean {
        return activeTimerState.value?.habitId == habitId
    }

    // ========== Battery Optimization ==========

    /**
     * Check if battery optimization guidance should be shown.
     */
    suspend fun shouldShowBatteryGuidance(): Boolean {
        return !preferencesManager.hasShownBatteryGuidance.first()
    }

    /**
     * Mark battery optimization guidance as shown.
     */
    suspend fun markBatteryGuidanceShown() {
        preferencesManager.setBatteryGuidanceShown()
    }

    // ========== Post-Check-In Dialog ==========

    /**
     * Called after a habit check-in to potentially show the metric recording dialog.
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String) {
        val neverAsk = preferencesManager.getNeverAskAgain(habitId).first()
        if (neverAsk) return

        val links = try {
            habitMetricLinkDao.getLinksByHabit(habitId).first()
        } catch (e: Exception) {
            emptyList()
        }
        val promptLinks = links.filter { it.promptOnComplete }

        if (promptLinks.isEmpty()) return

        val metricInfos = promptLinks.mapNotNull { link ->
            val metric = metricDao.getMetricById(link.metricId) ?: return@mapNotNull null
            val latestLog = metricLogDao.getLatestLog(link.metricId)
            LinkedMetricInfo(
                metricName = metric.name,
                metricId = metric.id,
                latestValue = latestLog?.value,
                unit = metric.unit,
                decimalPlaces = metric.decimalPlaces
            )
        }

        if (metricInfos.isNotEmpty()) {
            _postCheckInState.value = PostCheckInState(
                habitId = habitId,
                habitName = habitName,
                linkedMetrics = metricInfos,
                show = true
            )
        }
    }

    /**
     * Record a metric value from the post-check-in dialog.
     */
    suspend fun recordMetricValues(
        habitId: Long,
        values: List<com.dayforge.ui.components.MetricValueInput>
    ): Boolean {
        return try {
            val recordedAt = System.currentTimeMillis()
            metricRepository.recordValues(
                values.map { input -> MetricValueDraft(input.metricId, input.value, input.note) },
                recordedAt
            )

            preferencesManager.removePendingMetricHabit(habitId)
            val updateIntent = Intent(TimerService.ACTION_WIDGET_UPDATE).apply {
                putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                setPackage(context.packageName)
            }
            context.sendBroadcast(updateIntent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to record linked metrics", error)
            Toast.makeText(
                context,
                context.getString(R.string.metric_error_record_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        preferencesManager.setNeverAskAgain(habitId, value)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    fun dismissPostCheckInDialog() {
        _postCheckInState.value = null
    }

    // ========== Goal Completion Dialog Methods ==========

    /**
     * Called when a habit reaches its targetCycles.
     * Per TARGET-08: Shows goal completion dialog.
     *
     * @param habitId The ID of the habit that reached its target
     * @param progress The current progress (distinct days count)
     */
    private fun onGoalReached(habitId: Long, progress: Int) {
        val habit = findHabitById(habitId)
        if (habit != null && habit.targetCycles != null) {
            _showGoalDialog.value = true
            _goalHabitId.value = habitId
            _goalProgress.value = progress
            _goalTarget.value = habit.targetCycles
        }
    }

    /**
     * Confirms goal completion, setting habit isActive = false.
     * Per TARGET-08: User can confirm to mark habit as complete.
     */
    fun confirmGoalCompletion() {
        viewModelScope.launch {
            val habitId = _goalHabitId.value ?: return@launch
            habitRepository.updateIsActive(habitId, false, context)
            _showGoalDialog.value = false
            // Show toast notification
            Toast.makeText(context, context.getString(R.string.toast_habit_completed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dismisses the goal completion dialog without completing the habit.
     * Per TARGET-08: User can continue tracking if they dismiss.
     * Switches to LOOSE mode to prevent failure detection for the continued cycle.
     * Dialog will reappear on next goal-reaching check-in.
     */
    fun dismissGoalDialog() {
        viewModelScope.launch {
            val habitId = _goalHabitId.value ?: return@launch
            val habit = habitDao.getHabitById(habitId)
            // Only switch to LOOSE if currently STRICT (preserve user's choice if already LOOSE)
            if (habit != null && habit.failMode == com.dayforge.data.model.FailMode.STRICT) {
                habitRepository.updateFailMode(habitId, com.dayforge.data.model.FailMode.LOOSE, context)
            }
            _showGoalDialog.value = false
        }
    }

    // ========== Reactivation Dialog Methods ==========

    /**
     * Shows reactivation dialog for a failed habit.
     * Called when user taps on "已失败" status.
     */
    fun showReactivationDialog(habitId: Long) {
        val habit = findHabitById(habitId)
        if (habit != null) {
            _showReactivationDialog.value = true
            _reactivationHabitId.value = habitId
            _reactivationHabitName.value = habit.name
        }
    }

    /**
     * Confirms reactivation: clears history and reactivates the habit.
     */
    fun confirmReactivation() {
        viewModelScope.launch {
            val habitId = _reactivationHabitId.value ?: return@launch
            val habit = findHabitById(habitId)
            if (habit != null) {
                try {
                    habitRepository.clearHabitHistory(habit, context)
                    Toast.makeText(context, context.getString(R.string.toast_habit_reactivated), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.toast_clear_history_failed), Toast.LENGTH_SHORT).show()
                }
            }
            _showReactivationDialog.value = false
            _reactivationHabitId.value = null
            _reactivationHabitName.value = ""
        }
    }

    /**
     * Dismisses the reactivation dialog.
     */
    fun dismissReactivationDialog() {
        _showReactivationDialog.value = false
        _reactivationHabitId.value = null
        _reactivationHabitName.value = ""
    }

    // ========== Habit Delete ==========

    /**
     * Delete a child habit.
     */
    fun deleteHabit(habit: HabitEntity) {
        viewModelScope.launch {
            val children = habitRepository.getHabitChildren(habit.uuid)
            if (children.isNotEmpty()) {
                _showChildrenDialog.value = PendingDeleteInfo(habit, children.size)
            } else {
                habitRepository.deleteHabit(habit, context)
            }
        }
    }

    fun deleteHabitWithChildren() {
        viewModelScope.launch {
            val pending = _showChildrenDialog.value ?: return@launch
            habitRepository.deleteHabitWithChildren(pending.habit, context)
            _showChildrenDialog.value = null
        }
    }

    fun deleteHabitKeepChildren() {
        viewModelScope.launch {
            val pending = _showChildrenDialog.value ?: return@launch
            habitRepository.deleteHabitOrphanChildren(pending.habit, context)
            _showChildrenDialog.value = null
        }
    }

    fun dismissChildrenDialog() {
        _showChildrenDialog.value = null
    }

    // ========== Expand State Management ==========

    /**
     * Toggle expand state for a parent habit.
     * Persists the change to DataStore.
     */
    fun toggleParentExpand(uuid: String) {
        viewModelScope.launch {
            val current = expandedParentUuids.value
            val newSet = if (uuid in current) {
                current - uuid
            } else {
                current + uuid
            }
            preferencesManager.setExpandedParentUuids(newSet)
        }
    }

    // ========== Helper Methods ==========

    private fun findHabitById(habitId: Long): HabitEntity? {
        return topLevelHabitsWithChildren.value
            .flatMap { it.children }
            .find { it.habit.id == habitId }
            ?.habit
    }

    private fun isToday(dateMillis: Long): Boolean {
        val today = DateTimeUtils.startOfDayMillis()
        return dateMillis == today
    }
}
