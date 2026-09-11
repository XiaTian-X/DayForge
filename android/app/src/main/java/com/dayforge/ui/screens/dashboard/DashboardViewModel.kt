package com.dayforge.ui.screens.dashboard

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitWithStats
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.domain.model.CardColorStyle
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.model.MetricWithLatestValue
import com.dayforge.domain.service.ActiveTimerStateProvider
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.domain.service.MetricOverviewProvider
import com.dayforge.domain.service.TimerManager
import com.dayforge.domain.service.TimerService
import com.dayforge.domain.service.HabitPriorityCalculator
import com.dayforge.domain.service.CountingSlotCalculator
import com.dayforge.domain.service.TimeMatchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.metrics.LinkedMetricPromptState
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitRepository: HabitRepository,
    private val checkInService: CheckInService,
    private val habitStatusCalculator: HabitStatusCalculator,
    private val timeLogDao: TimeLogDao,
    private val habitDao: com.dayforge.data.local.dao.HabitDao,
    private val preferencesManager: PreferencesManager,
    private val completionDao: CompletionDao,
    private val metricRepository: MetricRepository,
    private val linkedMetricCoordinator: LinkedMetricCoordinator,
    private val metricOverviewProvider: MetricOverviewProvider
) : ViewModel() {

    // Shared timer management
    private val timerManager = TimerManager(context, habitDao, timeLogDao)
    private val activeTimerStateProvider = ActiveTimerStateProvider(
        timeLogDao, habitRepository, viewModelScope, context
    )

    companion object {
        private const val TAG = "DashboardViewModel"
        private const val MIN_REFRESH_DELAY_MS = 10_000L // 最小10秒刷新间隔
        private const val MAX_REFRESH_DELAY_MS = 3600_000L // 最大1小时刷新间隔（无窗口时）
    }

    // Track if data has been loaded at least once
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // 智能时间tick：只在TIME_WINDOW模式启用，动态计算到下一个窗口边界的delay
    // 使用filterMode作为触发源，当模式切换时立即响应
    // 非TIME_WINDOW模式下emit一个初始值确保combine能正常工作
    private val smartTimeTickFlow: Flow<Long> = preferencesManager.filterMode
        .onEach { Log.d(TAG, "filterMode changed: $it") }
        .flatMapLatest { filterMode ->
            if (filterMode != FilterMode.TIME_WINDOW.value) {
                // 非TIME_WINDOW模式：emit一个初始值，然后停止（不触发后续时间刷新）
                flow { emit(System.currentTimeMillis()) }
            } else {
                // TIME_WINDOW模式：动态计算delay的时间tick
                flow {
                    while (true) {
                        val habits = habitRepository.allHabits.first()
                        val currentTime = ZonedDateTime.now(ZoneId.systemDefault())

                        val nextRefreshDelay = calculateNextWindowBoundaryDelay(habits, currentTime)

                        Log.d(TAG, "smartTimeTick: next refresh in ${nextRefreshDelay}ms")
                        emit(System.currentTimeMillis())
                        delay(nextRefreshDelay.coerceIn(MIN_REFRESH_DELAY_MS, MAX_REFRESH_DELAY_MS))
                    }
                }
            }
        }

    init {
        // Process recovery: Restart TimerService if there was an active running timer
        // Per D-05: recovery is automatic without user confirmation
        viewModelScope.launch {
            val activeLog = timeLogDao.getActiveTimeLog()
            if (activeLog != null && !activeLog.isPaused) {
                // Timer was running, restart the foreground service
                // Get the habit to retrieve targetMinutes
                val habits = habitRepository.allHabits.first()
                val habit = habits.find { it.id == activeLog.habitId }
                if (habit != null) {
                    val intent = Intent(context, TimerService::class.java).apply {
                        action = TimerService.ACTION_START
                        putExtra(TimerService.EXTRA_HABIT_ID, activeLog.habitId)
                        putExtra(TimerService.EXTRA_TARGET_MINUTES, habit.targetValue)
                    }
                    // Service will check for existing log and not create new one
                    ContextCompat.startForegroundService(context, intent)
                }
            }
        }
    }

    // Listen to all completions to trigger UI updates when打卡 happens
    private val allCompletions = habitRepository.getAllCompletions()

    /**
     * Habits that need metric recording (completed timer habits with unrecorded prompt metrics).
     * Shows a "Record" button on the habit card.
     *
     * This is now read from DataStore where TimerService stores the pending habits.
     * This approach is more reliable than Flow calculation which could miss updates
     * when the app is in the background or when multiple habits share the same metric.
     */
    val pendingMetricHabits: StateFlow<Set<Long>> = linkedMetricCoordinator.pendingMetricHabits
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptySet()
        )

    // Combine habits, completions, time logs, date change trigger, filter mode, and smart time tick
    // For TIMER habits, todayCount and streaks come from TimeLogEntity, not CompletionEntity
    // Use getActiveTimeLogFlow() as trigger - it changes when timer starts (INSERT) or stops (UPDATE sets endTime)
    // Use dateChangeTrigger to refresh when date changes (user opens app on new day)
    // Use filterMode to switch between different display modes (all, time_window, checkable, terminated)
    // Use smartTimeTickFlow for time-based refresh in TIME_WINDOW mode (triggers at window boundaries)
    // Note: combine最多支持5个参数，使用两次combine串联实现6参数
    private val baseCombineFlow = combine(
        habitRepository.allHabits,
        allCompletions,
        timeLogDao.getActiveTimeLogFlow(),  // Triggers when timer state changes (start/stop)
        preferencesManager.dateChangeTrigger,  // Triggers when date changes
        preferencesManager.filterMode  // Triggers when filter mode changes
    ) { habits, completions, activeTimeLog, _, filterModeValue ->
        // 返回tuple供第二次combine使用
        Tuple5(habits, completions, activeTimeLog, filterModeValue)
    }

    val habitsWithStats: StateFlow<List<HabitWithStats>> = combine(
        baseCombineFlow,
        smartTimeTickFlow  // Triggers at window boundaries in TIME_WINDOW mode
    ) { baseData, _ ->
        val (habits, completions, _, filterModeValue) = baseData
        Log.d(TAG, "habitsWithStats combine triggered: habits=${habits.size}, completions=${completions.size}, filterMode=$filterModeValue")

        val currentTime = ZonedDateTime.now(ZoneId.systemDefault())
        val currentFilterMode = FilterMode.fromValue(filterModeValue)

        val habitsWithStatsList = habits.map { habit ->
            // Use centralized status calculator
            val stats = habitStatusCalculator.calculate(habit, completions, null)

            // Calculate slot progress if time_window mode and COUNTING with bestTime
            val slotProgress = if (currentFilterMode == FilterMode.TIME_WINDOW && habit.habitType == HabitType.COUNTING && habit.bestTime != null) {
                val progress = CountingSlotCalculator.getSlotProgress(
                    habit.bestTime,
                    habit.targetValue,
                    currentTime
                )
                if (progress != null) {
                    "第 ${progress.first} 个/共 ${progress.second} 个"
                } else null
            } else null

            // Return HabitWithStats with slot progress
            stats.copy(slotProgress = slotProgress)
        }

        // Apply filtering and sorting based on filter mode
        when (currentFilterMode) {
            FilterMode.ALL -> {
                // All mode: show all active habits
                // Sorted by: active + checkInAllowed + !completedToday > active + checkInAllowed + completedToday > others
                habitsWithStatsList.sortedByDescending {
                    it.habit.isActive && it.isCheckInAllowed && !it.completedToday
                }
            }
            FilterMode.TIME_WINDOW -> {
                // Time window mode: only show habits with bestTime set, sorted by time-based priority
                // Exclude failed/goal-completed habits (they belong to Terminated mode)
                // Non-check-in day habits are placed at the end

                // First filter to keep only habits that have bestTime configured and are not terminated
                val habitsWithBestTime = habitsWithStatsList.filter {
                    it.habit.bestTime != null && !it.hasFailed && !it.isGoalCompleted
                }

                // Split into check-in allowed and non-check-in day groups
                val checkInAllowedHabits = habitsWithBestTime.filter { it.isCheckInAllowed }
                val nonCheckInDayHabits = habitsWithBestTime.filter { !it.isCheckInAllowed }

                // Calculate priorities for check-in allowed habits
                // 使用todayCount而非completedToday布尔值（支持COUNTING的Slot级别完成判定）
                val completedCountMap = checkInAllowedHabits.associate { it.habit.id to it.todayCount }
                val pendingMetrics = pendingMetricHabits.value
                val habitsEntitiesAllowed = habits.filter { habit ->
                    habit.bestTime != null && checkInAllowedHabits.any { it.habit.id == habit.id }
                }

                val prioritiesAllowed = HabitPriorityCalculator.calculatePriorities(
                    habitsEntitiesAllowed,
                    currentTime,
                    completedCountMap,
                    pendingMetrics
                )

                // Calculate priorities for non-check-in day habits
                val habitsEntitiesNonCheckIn = habits.filter { habit ->
                    habit.bestTime != null && nonCheckInDayHabits.any { it.habit.id == habit.id }
                }
                val prioritiesNonCheckIn = HabitPriorityCalculator.calculatePriorities(
                    habitsEntitiesNonCheckIn,
                    currentTime,
                    emptyMap(),  // No completion info needed for non-check-in days
                    emptySet()   // No pending metrics for non-check-in days
                )

                // Combine: check-in allowed habits first (sorted by priority), then non-check-in day habits
                val allowedSorted = prioritiesAllowed.mapNotNull { priority ->
                    checkInAllowedHabits.find { it.habit.id == priority.habit.id }
                }
                val nonCheckInSorted = prioritiesNonCheckIn.mapNotNull { priority ->
                    nonCheckInDayHabits.find { it.habit.id == priority.habit.id }
                }

                allowedSorted + nonCheckInSorted
            }
            FilterMode.CHECKABLE -> {
                // Checkable mode: show only habits that can be checked in and are not completed
                // Exclude GOAL type habits (they are containers for child habits, not checkable themselves)
                // Exclude failed habits and goal-completed habits (they cannot be checked in)
                val pendingMetrics = pendingMetricHabits.value
                habitsWithStatsList.filter { habitWithStats ->
                    val habit = habitWithStats.habit

                    // Exclude GOAL type habits - they are parent containers, not directly checkable
                    val isGoalType = habit.habitType == HabitType.GOAL

                    // Exclude failed and goal-completed habits - they cannot be checked in
                    val isTerminated = habitWithStats.hasFailed || habitWithStats.isGoalCompleted

                    // Positive counting habits (isCountdown=false) always show when not terminated
                    // because they can continue checking in after reaching target
                    val isPositiveCounting = habit.habitType == HabitType.COUNTING && !habit.isCountdown && !isTerminated

                    // TIMER habits with pending metric show (has "Record" button to process)
                    // Only show if not terminated (failed or goal-completed)
                    val hasPendingMetric = pendingMetrics.contains(habit.id) && habit.habitType == HabitType.TIMER && !isTerminated

                    // Checkable conditions for other habits
                    val isCheckable = !habitWithStats.completedToday &&
                            habitWithStats.isCheckInAllowed &&
                            habit.isActive &&
                            !isTerminated

                    // Show if: not GOAL type, and (positive counting OR has pending metric OR is checkable)
                    !isGoalType && !isTerminated && (isPositiveCounting || hasPendingMetric || isCheckable)
                }
            }
            FilterMode.TERMINATED -> {
                // Terminated mode: show only failed or goal completed habits
                habitsWithStatsList.filter { it.hasFailed || it.isGoalCompleted }
            }
        }
    }
        .onEach { _isInitialized.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
            initialValue = emptyList()
        )

    // Today's progress: Pair of (completed count, total count)
    // Derives from habitsWithStats to ensure TIMER habits use TimeLogEntity data
    // Per D-10: Only counts habits, NOT metrics
    // Filters out non-check-in days, failed habits, and goal-completed habits
    val todayProgress: StateFlow<Pair<Int, Int>> = habitsWithStats.map { habits ->
        val eligibleHabits = habits.filter { it.shouldCountToday }
        val completed = eligibleHabits.count { it.completedToday }
        Pair(completed, eligibleHabits.size)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = Pair(0, 0)
    )

    /**
     * Metrics with their latest values for main screen display.
     * Per METRIC-07: Shows active metrics independently on main screen.
     * Per D-09: Appears below habits section with title separator.
     *
     * Combines metrics with latest log changes to ensure UI refreshes when new logs are recorded.
     */
    val metricsWithLatest: StateFlow<List<MetricWithLatestValue>> = metricOverviewProvider.observe()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
            initialValue = emptyList()
        )

    /**
     * Linked metrics for all habits, keyed by habit ID.
     * Per METRIC-08: Users can see linked metrics under habit cards.
     *
     * Uses habitsWithStats, metric logs, and link changes as triggers to recalculate
     * when habits, logs, or links change. This ensures the UI refreshes when
     * habit-metric links are added/updated during sync.
     */
    val linkedMetricsByHabit: StateFlow<Map<Long, List<LinkedMetricInfo>>> =
        linkedMetricCoordinator.observeLinkedMetrics(
            habitIds = habitsWithStats.map { habits -> habits.map { it.habit.id }.toSet() },
            onlyShownInHabitDetail = true
        ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,  // Changed from WhileSubscribed to ensure flow stays active
        initialValue = emptyMap()
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
     * Filter mode state for reactive UI updates.
     * Controls which habits are displayed and how they are sorted.
     */
    val filterMode: StateFlow<String> = preferencesManager.filterMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = FilterMode.ALL.value
        )

    /**
     * Set filter mode for habit list display.
     * @param mode The filter mode value ("all", "time_window", "checkable", "terminated")
     */
    fun setFilterMode(mode: String) {
        viewModelScope.launch {
            preferencesManager.setFilterMode(mode)
        }
    }

    /**
     * Active timer state for real-time UI updates.
     * Delegates to ActiveTimerStateProvider for shared implementation.
     */
    val activeTimerState: StateFlow<ActiveTimerState?> = activeTimerStateProvider.activeTimerState

    /**
     * Start a timer for the given habit.
     * Delegates to TimerManager for shared implementation.
     *
     * @param habitId The ID of the habit
     * @param targetMinutes Target duration in minutes
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
     * Stop the currently running timer and save the duration.
     * Triggers post-check-in dialog for linked metrics.
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
     *
     * @param habitId The habit ID to check
     * @return true if this habit has the active timer
     */
    fun isHabitTimerActive(habitId: Long): Boolean {
        return activeTimerState.value?.habitId == habitId
    }

    /**
     * Check if battery optimization guidance should be shown.
     * Returns true if guidance has never been shown before.
     */
    suspend fun shouldShowBatteryGuidance(): Boolean {
        return !preferencesManager.hasShownBatteryGuidance.first()
    }

    /**
     * Mark battery optimization guidance as shown.
     * Called after the guidance dialog is dismissed.
     */
    suspend fun markBatteryGuidanceShown() {
        preferencesManager.setBatteryGuidanceShown()
    }

    // ========== Post-Check-In Dialog State ==========

    /**
     * State for the post-check-in dialog that prompts users to record linked metrics.
     * Per METRIC-09: Users should be prompted to record linked metrics after checking in.
     * Per D-15 to D-19: Dialog shows linked metrics, supports inline recording, and "never ask again".
     */
    val postCheckInState: StateFlow<LinkedMetricPromptState?> =
        linkedMetricCoordinator.postCheckInState

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
     * Called after a habit check-in to potentially show the metric recording dialog.
     * Per D-15: Shows dialog if habit has linked metrics with promptOnComplete=true
     * Per D-18: Respects "never ask again" preference
     *
     * @param habitId The ID of the habit that was checked in
     * @param habitName The name of the habit (for display in dialog)
     */
    suspend fun checkAndShowPostCheckInDialog(habitId: Long, habitName: String) {
        val habit = habitDao.getHabitById(habitId)
        val isTempTask = habit?.let { h ->
            h.targetCycles == 1 && h.failMode == com.dayforge.data.model.FailMode.LOOSE && h.habitType == HabitType.CHECK_IN && h.iconResId == 53
        } ?: false
        linkedMetricCoordinator.showPromptIfNeeded(habitId, habitName, isTempTask)
    }

    /**
     * Record a metric value from the post-check-in dialog.
     *
     * @param metricId The ID of the metric
     * @param value The value to record
     * @param note Optional note for this record
     */
    suspend fun recordMetricValues(
        habitId: Long,
        values: List<MetricValueInput>
    ): Boolean = linkedMetricCoordinator.recordMetricValues(habitId, values)

    /**
     * Set "never ask again" preference for a habit's metric prompt.
     * Per D-18: User can suppress future prompts for this habit.
     *
     * @param habitId The ID of the habit
     * @param value True to suppress future prompts
     */
    suspend fun setNeverAskAgain(habitId: Long, value: Boolean) {
        linkedMetricCoordinator.setNeverAskAgain(habitId, value)
    }

    /**
     * Dismiss the post-check-in dialog.
     */
    fun dismissPostCheckInDialog() {
        linkedMetricCoordinator.dismissPrompt()
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
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
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
            Toast.makeText(context, "习惯已完成", Toast.LENGTH_SHORT).show()
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
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
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
        val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
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
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
            if (habit != null) {
                try {
                    habitRepository.clearHabitHistory(habit, context)
                    Toast.makeText(context, "习惯已重新激活，历史记录已清空", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "清空历史失败，请检查网络连接", Toast.LENGTH_SHORT).show()
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

    // ========== Habit Operations ==========

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

    /**
     * Delete a temporary task after completion.
     * Used when temp task (targetCycles=1 + failMode=LOOSE + CHECK_IN) is checked in.
     *
     * @param habitId The ID of the temporary task to delete
     */
    fun deleteTempTask(habitId: Long) {
        viewModelScope.launch {
            val habit = habitRepository.getHabitById(habitId)
            if (habit != null) {
                habitRepository.deleteHabit(
                    habit,
                    context,
                    factDerivedTaskFinalization = true
                )
                Toast.makeText(context, context.getString(R.string.toast_temp_task_completed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Check-in for a CHECK_IN habit.
     * Uses CheckInService for consistent behavior with widgets.
     * Per TARGET-15: Triggers goal completion dialog if targetCycles reached.
     * Triggers post-check-in dialog for linked metrics.
     * For temporary tasks: deletes after completion if no linked metrics.
     */
    fun checkIn(habitId: Long) {
        viewModelScope.launch {
            val result = checkInService.toggleCheckIn(context, habitId)
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit

            // 检测临时任务：targetCycles=1 + failMode=LOOSE + habitType=CHECK_IN + iconResId=53(TaskAlt)
            val isTempTask = habit?.let { h ->
                h.targetCycles == 1 && h.failMode == com.dayforge.data.model.FailMode.LOOSE && h.habitType == HabitType.CHECK_IN && h.iconResId == 53
            } ?: false

            // Check for goal reached (TARGET-15)
            if (result is CheckInResult.Success && result.goalReached) {
                if (isTempTask) {
                    // 临时任务完成：跳过目标完成对话框
                    // 检查是否有关联指标需要提示
                    val hasPromptMetrics = linkedMetricCoordinator.hasPromptMetrics(habitId)

                    if (!hasPromptMetrics) {
                        // 无关联指标 → 直接删除
                        deleteTempTask(habitId)
                    }
                    // 有关联指标 → 等指标对话框关闭后删除（不调用 onGoalReached，避免两个对话框）
                } else {
                    onGoalReached(habitId, result.progress)
                }
            }

            // Trigger metric dialog for CHECK_IN habits
            // 临时任务：只有当有指标且未删除时才显示指标对话框
            // 普通习惯：正常显示指标对话框
            if (habit != null && result is CheckInResult.Success && result.completed) {
                if (isTempTask) {
                    // 临时任务：检查是否有关联指标（已在上面判断是否删除）
                    if (linkedMetricCoordinator.hasPromptMetrics(habitId)) {
                        checkAndShowPostCheckInDialog(habitId, habit.name)
                    }
                } else {
                    checkAndShowPostCheckInDialog(habitId, habit.name)
                }
            }
        }
    }

    fun logCompletion(habitId: Long, value: Int = 1) {
        viewModelScope.launch {
            habitRepository.logCompletion(context, habitId, value)
        }
    }

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
            // Trigger metric dialog for COUNTING habits
            val habit = habitsWithStats.value.find { it.habit.id == habitId }?.habit
            if (habit != null) {
                checkAndShowPostCheckInDialog(habitId, habit.name)
            }
        }
    }

    /**
     * Decrement count for a COUNTING habit.
     * Uses CheckInService for consistent behavior with widgets.
     * Triggers goal completion dialog if targetCycles reached.
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

    /**
     * Update the aggregation type for a metric.
     * Called when user changes aggregation type in TrendChart.
     */
    fun updateAggregationType(metricId: Long, aggregationType: String) {
        viewModelScope.launch {
            runCatching {
                metricRepository.updateAggregationType(metricId, aggregationType)
            }.onFailure {
                Toast.makeText(context, it.message ?: "当前设备不能修改指标配置", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isToday(dateMillis: Long): Boolean {
        val today = DateTimeUtils.startOfDayMillis()
        return dateMillis == today
    }

    /**
     * 计算到下一个窗口边界的延迟时间。
     * 用于智能时间tick，只在TIME_WINDOW模式启用。
     *
     * 遍历所有习惯的窗口边界（开始和结束），找到最近的一个。
     * COUNTING习惯考虑所有slot窗口。
     *
     * @param habits 所有习惯列表
     * @param currentTime 当前时间
     * @return 到下一个窗口边界的毫秒数
     */
    private fun calculateNextWindowBoundaryDelay(
        habits: List<HabitEntity>,
        currentTime: ZonedDateTime
    ): Long {
        val currentMinutes = currentTime.hour * 60 + currentTime.minute

        // 收集所有窗口边界时间（分钟）
        val windowBoundaries = mutableListOf<Int>()

        for (habit in habits) {
            if (!habit.isActive || habit.bestTime == null) continue
            if (habit.habitType == HabitType.GOAL) continue

            val bestTime = habit.bestTime.toInt()

            if (habit.habitType == HabitType.COUNTING) {
                // COUNTING习惯：计算所有slot窗口边界
                val slots = CountingSlotCalculator.calculateSlots(habit.bestTime, habit.targetValue, currentTime)
                for (slot in slots) {
                    if (!slot.isPast) {
                        // 收集窗口开始和结束时间
                        windowBoundaries.add(slot.windowStart)
                        windowBoundaries.add(slot.windowEnd)
                    }
                }
            } else {
                // CHECK_IN/TIMER习惯：单个窗口
                val halfWidth = when (habit.habitType) {
                    HabitType.TIMER -> habit.targetValue
                    else -> 15
                }
                val windowStart = bestTime - halfWidth
                val windowEnd = bestTime + halfWidth

                // 只收集未来的边界
                if (windowEnd > currentMinutes) {
                    windowBoundaries.add(windowStart)
                    windowBoundaries.add(windowEnd)
                }
            }
        }

        // 找到最近的边界时间
        val nearestBoundary = windowBoundaries
            .filter { it > currentMinutes }
            .minByOrNull { it - currentMinutes }

        if (nearestBoundary == null) {
            // 无未来窗口边界：返回最大延迟（将在明天重新计算）
            return MAX_REFRESH_DELAY_MS
        }

        // 计算到边界的毫秒数
        val minutesUntilBoundary = nearestBoundary - currentMinutes
        val secondsUntilBoundary = minutesUntilBoundary * 60 - currentTime.second

        return (secondsUntilBoundary * 1000L).coerceAtLeast(MIN_REFRESH_DELAY_MS)
    }
}

/**
 * Represents the state of an active timer.
 * Used to display real-time timer progress in the UI.
 *
 * @param habitId The ID of the habit with the active timer
 * @param elapsedSeconds Seconds elapsed (excluding pause time)
 * @param isPaused Whether the timer is currently paused
 * @param targetMinutes Target duration in minutes for display
 */
data class ActiveTimerState(
    val habitId: Long,
    val elapsedSeconds: Int,
    val isPaused: Boolean,
    val targetMinutes: Int
)

/**
 * Helper tuple for combining multiple Flow values.
 */
private data class Tuple5<T1, T2, T3, T4>(
    val first: T1,
    val second: T2,
    val third: T3,
    val fourth: T4
)
