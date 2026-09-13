package com.dayforge.domain.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.dto.TimerHeartbeatRequest
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetRefreshScheduler
import com.dayforge.widget.checkin.GoalCompletionActivity
import com.dayforge.widget.timer.CountdownDiscardActivity
import com.dayforge.sync.AutoSyncCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service for reliable background timer execution.
 *
 * Uses Android's foreground service API to keep the timer running when the app
 * is backgrounded or the screen is off. Displays a persistent notification with
 * pause/resume/stop controls.
 *
 * All time calculations use System.currentTimeMillis() for actual timestamps.
 */
@AndroidEntryPoint
class TimerService : Service() {

    companion object {
        private const val TAG = "TimerService"

        // Actions for service commands
        const val ACTION_START = "com.dayforge.timer.START"
        const val ACTION_PAUSE = "com.dayforge.timer.PAUSE"
        const val ACTION_RESUME = "com.dayforge.timer.RESUME"
        const val ACTION_STOP = "com.dayforge.timer.STOP"
        const val ACTION_DISCARD = "com.dayforge.timer.DISCARD"

        // Intent extras
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_TARGET_MINUTES = "targetMinutes"
        const val EXTRA_IS_COUNTDOWN = "isCountdown"

        // Widget update broadcast
        const val ACTION_WIDGET_UPDATE = "com.dayforge.TIMER_WIDGET_UPDATE"

        // Notification
        const val NOTIFICATION_ID = 1001
        const val TARGET_NOTIFICATION_ID = 1002
        const val THRESHOLD_NOTIFICATION_ID = 1003
        const val COUNTDOWN_COMPLETE_NOTIFICATION_ID = 1004
        const val CHANNEL_ID = "timer_channel"

        // Threshold settings
        const val THRESHOLD_MULTIPLIER = 3
        private const val HEARTBEAT_INTERVAL_MILLIS = 120_000L

    }

    // Dependency injection
    @Inject
    lateinit var timeLogDao: TimeLogDao

    @Inject
    lateinit var habitDao: HabitDao

    @Inject
    lateinit var habitMetricLinkDao: HabitMetricLinkDao

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var syncApi: SyncV2Api

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var autoSyncCoordinator: AutoSyncCoordinator

    // Coroutine scope for database operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private var tickerJob: Job? = null  // Periodic check for target notification

    // Timer state (persisted to TimeLogEntity for process recovery)
    private var currentLogId: Long = 0L  // ID of the active TimeLogEntity
    private var habitId: Long = 0
    private var targetMinutes: Int = 0
    private var isCountdown: Boolean = false  // false = countup mode, true = countdown mode
    private var startTime: Long = 0L  // Actual start time (epoch millis)
    private var isPaused: Boolean = false
    private var pausedAt: Long? = null  // Actual pause time (epoch millis)
    private var accumulatedPauseDuration: Long = 0L  // Total time spent paused
    private var timerSessionUuid: String? = null
    private var nextCommandSequence: Int = 1
    private var controlGeneration: Int = 0
    private var lastCommandAt: Long? = null
    private var timerTimezone: String? = null
    private var activeElapsedAtAnchor: Long = 0L
    private var elapsedRealtimeAnchor: Long? = null
    private var timerBootCount: Int? = null
    private var targetReachedNotified: Boolean = false  // Track if target notification was shown
    private var lastHeartbeatAttemptAt: Long = 0L

    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationFactory: TimerNotificationFactory

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationFactory = TimerNotificationFactory(this)
        notificationFactory.createChannel(notificationManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service promoted with startForegroundService must enter the foreground
        // before Room recovery can run. The placeholder is replaced as soon as
        // persisted state has been restored on the IO dispatcher.
        startForegroundWithNotification()
        val action = intent?.action
        val expectedHabitId = intent?.getLongExtra(EXTRA_HABIT_ID, 0L)?.takeIf { it > 0 }
        serviceScope.launch {
            commandMutex.withLock {
                runCatching {
                    when (action) {
                        ACTION_START -> handleStart(intent)
                        ACTION_PAUSE -> handlePause(expectedHabitId)
                        ACTION_RESUME -> handleResume(expectedHabitId)
                        ACTION_STOP -> handleStop(expectedHabitId)
                        ACTION_DISCARD -> handleDiscard(expectedHabitId)
                        null -> recoverPersistedTimer()
                        else -> stopIfNoPersistedTimer()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Timer command failed: $action", error)
                    stopIfNoPersistedTimer()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        serviceScope.cancel()
    }

    /**
     * Starts a periodic ticker to check for target duration and update notification.
     * Checks every second when timer is running (not paused).
     */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)  // Check every second
                if (!isPaused) {
                    // Update notification with current elapsed time
                    updateNotification()

                    // Broadcast widget update for real-time display (D-14)
                    notifyWidgetUpdate()

                    // Countdown mode: check for auto-complete at zero
                    // Per TIMER-06: countdown stops at zero, cannot exceed target
                    if (isCountdown) {
                        val elapsedSeconds = calculateElapsedSeconds()
                        val targetSeconds = targetMinutes * 60
                        val remainingSeconds = targetSeconds - elapsedSeconds

                        if (remainingSeconds <= 0) {
                            // Per TIMER-05: countdown auto-completes at zero and records TimeLogEntity
                            showCountdownCompleteNotification()
                            launchTerminalStop()
                            return@launch  // Exit ticker loop
                        }
                    } else {
                        // Countup mode: check for target completion notification
                        // Per TIMER-07: countup mode continues past target
                        if (targetMinutes > 0 && !targetReachedNotified) {
                            val elapsedSeconds = calculateElapsedSeconds()
                            val targetSeconds = targetMinutes * 60

                            if (elapsedSeconds >= targetSeconds) {
                                showTargetReachedNotification()
                                targetReachedNotified = true
                            }
                        }

                        // Check for threshold (D-07: target × 3, D-08: auto-stop)
                        val thresholdMinutes = targetMinutes * THRESHOLD_MULTIPLIER
                        if (thresholdMinutes > 0 && targetMinutes > 0) {
                            val elapsedSeconds = calculateElapsedSeconds()
                            val elapsedMinutes = elapsedSeconds / 60

                            if (elapsedMinutes >= thresholdMinutes) {
                                // Auto-stop: show notification and stop timer
                                showThresholdReachedNotification()
                                launchTerminalStop()
                                return@launch  // Exit ticker loop
                            }
                        }
                    }
                }
                maybeHeartbeat()
            }
        }
    }

    private fun launchTerminalStop() {
        serviceScope.launch {
            commandMutex.withLock {
                runCatching { handleStop(habitId.takeIf { it > 0 }) }
                    .onFailure { Log.e(TAG, "Automatic timer stop failed", it) }
            }
        }
    }

    private suspend fun maybeHeartbeat() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatAttemptAt < HEARTBEAT_INTERVAL_MILLIS) return
        lastHeartbeatAttemptAt = now
        val sessionUuid = timerSessionUuid ?: return
        val deviceId = tokenManager.syncDeviceId.first() ?: run {
            autoSyncCoordinator.enqueueNow()
            return
        }
        runCatching {
            syncApi.heartbeatTimer(
                sessionUuid,
                TimerHeartbeatRequest(deviceId, controlGeneration)
            )
        }
    }

    /**
     * Broadcasts widget update for real-time timer display.
     * Called every second by the ticker while timer is running.
     * Per D-14: widget refreshes every second while timer is running.
     * Per D-16: broadcasts stop when timer stops (ticker is cancelled).
     */
    private fun notifyWidgetUpdate() {
        notifyWidgetUpdate(habitId)
    }

    private fun notifyWidgetUpdate(updatedHabitId: Long) {
        if (updatedHabitId == 0L) return

        val intent = Intent(ACTION_WIDGET_UPDATE).apply {
            putExtra(EXTRA_HABIT_ID, updatedHabitId)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Shows a notification when the target duration is reached.
     * Per D-14, timer continues running after target is reached.
     */
    private fun showTargetReachedNotification() {
        notificationManager.notify(
            TARGET_NOTIFICATION_ID,
            notificationFactory.createTargetReachedNotification()
        )
    }

    /**
     * Shows a notification when the threshold duration is reached.
     * Per D-08, timer auto-stops when reaching threshold.
     */
    private fun showThresholdReachedNotification() {
        notificationManager.notify(
            THRESHOLD_NOTIFICATION_ID,
            notificationFactory.createThresholdReachedNotification()
        )
    }

    /**
     * Shows a notification when countdown reaches zero.
     * Per TIMER-05: countdown auto-completes at zero and records TimeLogEntity.
     */
    private fun showCountdownCompleteNotification() {
        notificationManager.notify(
            COUNTDOWN_COMPLETE_NOTIFICATION_ID,
            notificationFactory.createCountdownCompleteNotification()
        )
    }

    private suspend fun handleStart(intent: Intent) {
        val requestedHabitId = intent.getLongExtra(EXTRA_HABIT_ID, 0L)
        if (requestedHabitId <= 0L) {
            stopIfNoPersistedTimer()
            return
        }

        // Recovery must win over the daily-completion guard. Otherwise a
        // process-recreated timer can be orphaned merely because an earlier
        // session already met today's target.
        val existingLog = timeLogDao.getActiveTimeLog()
        if (existingLog?.habitId == requestedHabitId) {
            if (!restoreState(existingLog)) {
                stopServiceAndClearNotification()
                return
            }
            updateNotification()
            autoSyncCoordinator.enqueueNow()
            startTicker()
            return
        }

        val requestedHabit = habitDao.getHabitByIdSync(requestedHabitId)
        if (requestedHabit == null) {
            stopIfNoPersistedTimer()
            return
        }

        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
        val completedSecondsToday = timeLogDao.getCompletedDurationSecondsForDate(
            requestedHabitId,
            java.time.LocalDate.now().toString(),
            todayStart,
            todayEnd
        )
        val targetSeconds = requestedHabit.targetValue * 60
        if (targetSeconds > 0 && completedSecondsToday >= targetSeconds) {
            Log.d(TAG, "handleStart: habit $requestedHabitId already completed today")
            if (existingLog != null && restoreState(existingLog)) {
                updateNotification()
                startTicker()
            } else {
                clearState()
                stopServiceAndClearNotification()
            }
            return
        }

        if (existingLog != null) {
            interruptTimer(existingLog)
        }

        habitId = requestedHabitId
        targetMinutes = requestedHabit.targetValue
        isCountdown = requestedHabit.isCountdown

        startTime = System.currentTimeMillis()
        isPaused = false
        pausedAt = null
        accumulatedPauseDuration = 0L
        nextCommandSequence = 2
        controlGeneration = 1
        lastCommandAt = startTime
        timerTimezone = ZoneId.systemDefault().id
        activeElapsedAtAnchor = 0L
        elapsedRealtimeAnchor = SystemClock.elapsedRealtime()
        timerBootCount = currentBootCount()

        val sessionUuid = UUID.randomUUID().toString()
        timerSessionUuid = sessionUuid
        val capturedTimezone = requireNotNull(timerTimezone)
        currentLogId = timeLogDao.insertSyncedTimer(
            TimeLogEntity(
                habitId = habitId,
                startTime = startTime,
                endTime = null,
                durationSeconds = 0,
                isPaused = false,
                pausedAt = null,
                accumulatedPauseMillis = 0,
                timerNextCommandSequence = nextCommandSequence,
                timerControlGeneration = controlGeneration,
                timerLastCommandAt = startTime,
                timerTimezone = capturedTimezone,
                timerActiveElapsedMillis = 0,
                timerElapsedRealtimeAnchor = elapsedRealtimeAnchor,
                timerBootCount = timerBootCount,
                date = DateTimeUtils.startOfDayMillis(),
                uuid = sessionUuid
            ),
            timerCommand(
                sessionUuid = sessionUuid,
                sequence = 1,
                type = "start",
                occurredAt = startTime,
                generation = 0,
                activityUuid = requestedHabit.uuid,
                timezone = capturedTimezone
            ),
            TimerSegmentEntity(
                sessionUuid = sessionUuid,
                sequence = 1,
                startedAt = startTime
            )
        )
        updateNotification()
        autoSyncCoordinator.enqueueNow()
        startTicker()
    }

    private suspend fun recoverPersistedTimer() {
        val existingLog = timeLogDao.getActiveTimeLog()
        if (existingLog == null || !restoreState(existingLog)) {
            stopServiceAndClearNotification()
            return
        }
        updateNotification()
        startTicker()
    }

    private suspend fun restoreState(log: TimeLogEntity): Boolean {
        val habit = habitDao.getHabitByIdSync(log.habitId) ?: return false
        currentLogId = log.id
        habitId = log.habitId
        targetMinutes = habit.targetValue
        isCountdown = habit.isCountdown
        startTime = log.startTime
        isPaused = log.isPaused
        pausedAt = log.pausedAt
        accumulatedPauseDuration = log.accumulatedPauseMillis
        timerSessionUuid = log.uuid
        nextCommandSequence = log.timerNextCommandSequence
        controlGeneration = log.timerControlGeneration
        lastCommandAt = log.timerLastCommandAt
        timerTimezone = log.timerTimezone
        activeElapsedAtAnchor = log.timerActiveElapsedMillis
        elapsedRealtimeAnchor = log.timerElapsedRealtimeAnchor
        timerBootCount = log.timerBootCount
        targetReachedNotified = targetMinutes > 0 &&
            calculateElapsedSeconds() >= targetMinutes * 60
        return true
    }

    private suspend fun ensureRestoredState(expectedHabitId: Long?): Boolean {
        val inMemoryMatches = currentLogId > 0L && timerSessionUuid != null &&
            (expectedHabitId == null || habitId == expectedHabitId)
        if (inMemoryMatches) return true

        val active = timeLogDao.getActiveTimeLog() ?: return false
        if (expectedHabitId != null && active.habitId != expectedHabitId) {
            Log.w(TAG, "Ignoring stale timer command for habit $expectedHabitId")
            return false
        }
        return restoreState(active)
    }

    private suspend fun interruptTimer(log: TimeLogEntity) {
        val interruptedHabit = habitDao.getHabitByIdSync(log.habitId)
        val elapsedSeconds = calculateElapsedSecondsForLog(log)
        val targetSeconds = (interruptedHabit?.targetValue ?: 0) * 60
        val shouldSave = interruptedHabit?.isCountdown != true &&
            targetSeconds > 0 && elapsedSeconds >= targetSeconds

        if (shouldSave) {
            val safeElapsed = elapsedSeconds.coerceAtMost(
                calculateSafeDurationLimit(false, interruptedHabit?.targetValue ?: 0)
            )
            finishInterruptedTimer(log, safeElapsed)
        } else {
            cancelInterruptedTimer(log)
        }
        notifyWidgetUpdate(log.habitId)
        tickerJob?.cancel()
        tickerJob = null
        clearState()
    }

    /**
     * Calculates elapsed seconds for a given TimeLogEntity.
     * Used when stopping an existing timer for a different habit.
     */
    private fun calculateElapsedSecondsForLog(log: TimeLogEntity): Int {
        return TimerElapsedCalculator.elapsedSeconds(log, this)
    }

    private suspend fun finishInterruptedTimer(log: TimeLogEntity, durationSeconds: Int) {
        val endTime = if (!log.isPaused) {
            val segments = timeLogDao.getTimerSegments(log.uuid)
            val closedDuration = segments.sumOf { segment ->
                segment.endedAt?.let { (it - segment.startedAt).coerceAtLeast(0) } ?: 0L
            }
            val open = segments.lastOrNull { it.endedAt == null }
            if (open != null) {
                open.startedAt + (durationSeconds * 1_000L - closedDuration).coerceAtLeast(1)
            } else {
                maxOf(System.currentTimeMillis(), (log.timerLastCommandAt ?: log.startTime) + 1)
            }
        } else {
            maxOf(System.currentTimeMillis(), (log.timerLastCommandAt ?: log.startTime) + 1)
        }
        timeLogDao.finishTimerAndQueue(
            id = log.id,
            endTime = endTime,
            durationSeconds = durationSeconds,
            accumulatedPauseMillis = log.accumulatedPauseMillis,
            nextSequence = log.timerNextCommandSequence + 1,
            activeElapsedMillis = durationSeconds * 1_000L,
            command = timerCommand(
                log.uuid,
                log.timerNextCommandSequence,
                "stop",
                endTime,
                log.timerControlGeneration,
                activeElapsedMillis = durationSeconds * 1_000L
            ),
            wasPaused = log.isPaused
        )
        persistDayAllocations(log.id)
    }

    private suspend fun persistDayAllocations(logId: Long) {
        val completed = timeLogDao.getById(logId) ?: return
        val timezone = completed.timerTimezone ?: ZoneId.systemDefault().id
        val allocations = DurationDayAllocator.allocate(
            sessionUuid = completed.uuid,
            habitId = completed.habitId,
            timezone = timezone,
            segments = timeLogDao.getTimerSegments(completed.uuid),
            maximumDurationMillis = completed.timerActiveElapsedMillis
        )
        timeLogDao.replaceDayAllocations(completed.uuid, allocations)
    }

    private suspend fun cancelInterruptedTimer(log: TimeLogEntity) {
        val commandAt = maxOf(
            System.currentTimeMillis(),
            (log.timerLastCommandAt ?: log.startTime) + 1
        )
        timeLogDao.deleteTimerAndQueue(
            log,
            timerCommand(
                log.uuid,
                log.timerNextCommandSequence,
                "cancel",
                commandAt,
                log.timerControlGeneration
            )
        )
    }

    private fun nextCommandTime(): Long {
        val value = maxOf(System.currentTimeMillis(), (lastCommandAt ?: 0L) + 1)
        lastCommandAt = value
        return value
    }

    private fun timerCommand(
        sessionUuid: String,
        sequence: Int,
        type: String,
        occurredAt: Long,
        generation: Int = controlGeneration,
        activityUuid: String? = null,
        timezone: String? = null,
        activeElapsedMillis: Long? = null
    ) = TimerCommandEntity(
        sessionUuid = sessionUuid,
        sequence = sequence,
        commandType = type,
        occurredAt = occurredAt,
        expectedControlGeneration = generation,
        activityUuid = activityUuid,
        timezone = timezone,
        activeElapsedMillis = activeElapsedMillis
    )

    private suspend fun handlePause(expectedHabitId: Long?) {
        if (!ensureRestoredState(expectedHabitId)) {
            stopIfNoPersistedTimer()
            return
        }
        if (isPaused) return

        val activeElapsed = calculateElapsedMillis()
        val commandAt = nextRunningBoundary(activeElapsed)
        val sessionUuid = requireNotNull(timerSessionUuid)
        val sequence = nextCommandSequence
        timeLogDao.updatePauseAndQueue(
            id = currentLogId,
            isPaused = true,
            pausedAt = commandAt,
            accumulatedPauseMillis = accumulatedPauseDuration,
            nextSequence = sequence + 1,
            commandAt = commandAt,
            activeElapsedMillis = activeElapsed,
            elapsedRealtimeAnchor = null,
            bootCount = timerBootCount,
            command = timerCommand(
                sessionUuid, sequence, "pause", commandAt,
                activeElapsedMillis = activeElapsed
            ),
            resumedSegment = null
        )

        activeElapsedAtAnchor = activeElapsed
        elapsedRealtimeAnchor = null
        isPaused = true
        pausedAt = commandAt
        nextCommandSequence = sequence + 1
        notifyWidgetUpdate()
        autoSyncCoordinator.enqueueNow()
        updateNotification()
    }

    private suspend fun handleResume(expectedHabitId: Long?) {
        if (!ensureRestoredState(expectedHabitId)) {
            stopIfNoPersistedTimer()
            return
        }
        val previousPausedAt = pausedAt
        if (!isPaused || previousPausedAt == null) return

        val resumedAt = nextCommandTime()
        val resumedAccumulatedPause = accumulatedPauseDuration +
            (resumedAt - previousPausedAt).coerceAtLeast(0L)
        val resumedElapsedAnchor = SystemClock.elapsedRealtime()
        val resumedBootCount = currentBootCount()
        val sessionUuid = requireNotNull(timerSessionUuid)
        val sequence = nextCommandSequence
        timeLogDao.updatePauseAndQueue(
            id = currentLogId,
            isPaused = false,
            pausedAt = null,
            accumulatedPauseMillis = resumedAccumulatedPause,
            nextSequence = sequence + 1,
            commandAt = resumedAt,
            activeElapsedMillis = activeElapsedAtAnchor,
            elapsedRealtimeAnchor = resumedElapsedAnchor,
            bootCount = resumedBootCount,
            command = timerCommand(sessionUuid, sequence, "resume", resumedAt),
            resumedSegment = TimerSegmentEntity(
                sessionUuid = sessionUuid,
                sequence = sequence,
                startedAt = resumedAt
            )
        )

        accumulatedPauseDuration = resumedAccumulatedPause
        isPaused = false
        pausedAt = null
        elapsedRealtimeAnchor = resumedElapsedAnchor
        timerBootCount = resumedBootCount
        nextCommandSequence = sequence + 1
        notifyWidgetUpdate()
        autoSyncCoordinator.enqueueNow()
        updateNotification()
    }

    private suspend fun handleStop(expectedHabitId: Long?) {
        if (!ensureRestoredState(expectedHabitId)) {
            stopIfNoPersistedTimer()
            return
        }
        // Per TIMER-08: Check if timer is incomplete before stopping
        // Countdown: incomplete if remaining > 0
        // Countup: incomplete if elapsed < target
        val targetSeconds = targetMinutes * 60
        val elapsedSeconds = calculateElapsedSeconds()
        val isIncomplete = if (isCountdown) {
            (targetSeconds - elapsedSeconds) > 0
        } else {
            targetMinutes > 0 && elapsedSeconds < targetSeconds
        }

        if (isIncomplete) {
            // Show discard confirmation dialog for incomplete sessions
            Log.d(TAG, "handleStop: incomplete session (isCountdown=$isCountdown, elapsed=$elapsedSeconds, target=$targetSeconds)")
            val remainingOrElapsedSeconds = if (isCountdown) {
                targetSeconds - elapsedSeconds  // remaining for countdown
            } else {
                elapsedSeconds  // elapsed for countup
            }
            val intent = CountdownDiscardActivity.createIntent(
                this,
                habitId,
                targetMinutes,
                remainingOrElapsedSeconds,
                isCountdown
            )
            startActivity(intent)
            return  // Don't stop the timer, let user decide
        }

        // Stop the ticker
        tickerJob?.cancel()
        tickerJob = null

        val stoppedHabitId = habitId
        val logIdToStop = currentLogId
        if (logIdToStop > 0L) {
            val rawActiveElapsedMillis = calculateElapsedMillis()
            val rawDurationSeconds = (rawActiveElapsedMillis / 1_000L)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

            // Clamp duration to safe limit to prevent abnormal values from system date changes
            val safeDurationLimit = calculateSafeDurationLimit(isCountdown, targetMinutes)
            val durationSeconds = rawDurationSeconds.coerceAtMost(safeDurationLimit)
            val activeElapsedMillis = rawActiveElapsedMillis.coerceAtMost(
                safeDurationLimit * 1_000L
            )
            val endTime = if (isPaused) {
                nextCommandTime()
            } else {
                nextRunningBoundary(activeElapsedMillis)
            }

            if (rawDurationSeconds > safeDurationLimit) {
                Log.w(TAG, "Duration clamped: raw=$rawDurationSeconds, limit=$safeDurationLimit, isCountdown=$isCountdown")
            }

            val sessionUuid = requireNotNull(timerSessionUuid)
            val sequence = nextCommandSequence
            timeLogDao.finishTimerAndQueue(
                id = logIdToStop,
                endTime = endTime,
                durationSeconds = durationSeconds,
                accumulatedPauseMillis = accumulatedPauseDuration,
                nextSequence = sequence + 1,
                activeElapsedMillis = activeElapsedMillis,
                command = timerCommand(
                    sessionUuid, sequence, "stop", endTime,
                    activeElapsedMillis = activeElapsedMillis
                ),
                wasPaused = isPaused
            )
            nextCommandSequence = sequence + 1
            persistDayAllocations(logIdToStop)
            autoSyncCoordinator.enqueueNow()

            // Check if this habit has linked metrics with promptOnComplete=true
            // If so, add to pending metric habits set
            if (stoppedHabitId != 0L) {
                val links = habitMetricLinkDao.getLinksByHabitSync(stoppedHabitId)
                if (links.any { it.promptOnComplete }) {
                    Log.d(TAG, "Habit $stoppedHabitId has prompt metrics, adding to pending set")
                    preferencesManager.addPendingMetricHabit(stoppedHabitId)
                }
            }

            // Check for goal reached (TARGET-08, TARGET-13)
            // TIMER habits use timelogs for progress calculation
            // Only count days where duration target was met
            if (stoppedHabitId != 0L) {
                val habit = habitDao.getHabitById(stoppedHabitId)
                if (habit?.targetCycles != null) {
                    val dailyTargetSeconds = habit.targetValue * 60
                    val progress = timeLogDao.getTargetMetDayCount(
                        stoppedHabitId,
                        dailyTargetSeconds
                    )
                    if (progress >= habit.targetCycles) {
                        startActivity(
                            GoalCompletionActivity.createIntent(
                                this@TimerService,
                                stoppedHabitId,
                                habit.name,
                                progress,
                                habit.targetCycles
                            )
                        )
                    }
                }
            }

            // Broadcast widget update after database is updated
            if (stoppedHabitId != 0L) {
                val intent = Intent(ACTION_WIDGET_UPDATE).apply {
                    putExtra(EXTRA_HABIT_ID, stoppedHabitId)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            // Notify all widgets to update (Progress, Motivation, etc.)
            WidgetRefreshScheduler.request(this@TimerService)
        }

        clearState()
        stopServiceAndClearNotification()
    }

    /**
     * Discards the timer session without saving.
     * Per TIMER-09: Deletes TimeLogEntity instead of saving duration.
     * Called when user confirms to abandon an incomplete countdown session.
     */
    private suspend fun handleDiscard(expectedHabitId: Long?) {
        if (!ensureRestoredState(expectedHabitId)) {
            stopIfNoPersistedTimer()
            return
        }
        Log.d(TAG, "handleDiscard: discarding timer for habitId=$habitId")

        // Stop the ticker first
        tickerJob?.cancel()
        tickerJob = null

        // Save habitId for widget broadcast before clearing state
        val stoppedHabitId = habitId
        val logIdToDelete = currentLogId

        if (logIdToDelete > 0) {
            val logToDelete = timeLogDao.getById(logIdToDelete)
            if (logToDelete != null && logToDelete.endTime == null) {
                val commandAt = maxOf(
                    System.currentTimeMillis(),
                    (logToDelete.timerLastCommandAt ?: logToDelete.startTime) + 1
                )
                timeLogDao.deleteTimerAndQueue(
                    logToDelete,
                    timerCommand(
                        logToDelete.uuid,
                        logToDelete.timerNextCommandSequence,
                        "cancel",
                        commandAt,
                        logToDelete.timerControlGeneration
                    )
                )
            }
            autoSyncCoordinator.enqueueNow()
        }

        // Broadcast widget update
        if (stoppedHabitId != 0L) {
            val intent = Intent(ACTION_WIDGET_UPDATE).apply {
                putExtra(EXTRA_HABIT_ID, stoppedHabitId)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        clearState()
        stopServiceAndClearNotification()
    }

    private suspend fun stopIfNoPersistedTimer() {
        val persisted = timeLogDao.getActiveTimeLog()
        if (persisted != null && restoreState(persisted)) {
            updateNotification()
            startTicker()
        } else {
            stopServiceAndClearNotification()
        }
    }

    private fun stopServiceAndClearNotification() {
        tickerJob?.cancel()
        tickerJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun clearState() {
        currentLogId = 0L
        habitId = 0L
        targetMinutes = 0
        isCountdown = false
        startTime = 0L
        isPaused = false
        pausedAt = null
        accumulatedPauseDuration = 0L
        timerSessionUuid = null
        nextCommandSequence = 1
        controlGeneration = 0
        lastCommandAt = null
        timerTimezone = null
        activeElapsedAtAnchor = 0L
        elapsedRealtimeAnchor = null
        timerBootCount = null
        targetReachedNotified = false
        lastHeartbeatAttemptAt = 0L
    }

    /**
     * Starts the foreground service with notification.
     */
    private fun startForegroundWithNotification() {
        val elapsedSeconds = calculateElapsedSeconds()
        val notification = notificationFactory.createTimerNotification(
            elapsedSeconds = elapsedSeconds,
            isPaused = isPaused,
            targetMinutes = targetMinutes,
            isCountdown = isCountdown,
            habitId = habitId
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Updates the notification with current timer state.
     */
    private fun updateNotification() {
        val elapsedSeconds = calculateElapsedSeconds()
        val notification = notificationFactory.createTimerNotification(
            elapsedSeconds = elapsedSeconds,
            isPaused = isPaused,
            targetMinutes = targetMinutes,
            isCountdown = isCountdown,
            habitId = habitId
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun currentBootCount(): Int? = runCatching {
        android.provider.Settings.Global.getInt(
            contentResolver,
            android.provider.Settings.Global.BOOT_COUNT
        )
    }.getOrNull()

    private fun calculateElapsedMillis(): Long {
        if (startTime == 0L) return 0L
        if (isPaused) return activeElapsedAtAnchor.coerceAtLeast(0)
        val anchor = elapsedRealtimeAnchor
        if (anchor != null && timerBootCount == currentBootCount()) {
            return (activeElapsedAtAnchor + SystemClock.elapsedRealtime() - anchor).coerceAtLeast(0)
        }
        // elapsedRealtime cannot survive a reboot. Fall back to persisted wall
        // history without ever reducing the known active duration.
        val recovered = System.currentTimeMillis() - startTime - accumulatedPauseDuration
        return maxOf(activeElapsedAtAnchor, recovered).coerceAtLeast(0)
    }

    /** Produce a stable UTC boundary from monotonic active time while running. */
    private fun nextRunningBoundary(activeElapsedMillis: Long): Long {
        val activeDelta = (activeElapsedMillis - activeElapsedAtAnchor).coerceAtLeast(1)
        val value = (lastCommandAt ?: startTime) + activeDelta
        lastCommandAt = value
        return value
    }

    private fun calculateElapsedSeconds(): Int =
        (calculateElapsedMillis() / 1_000L)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * Calculates safe duration limit to prevent abnormal values from system date changes.
     * Countdown mode: maximum is target value (cannot exceed target)
     * Countup mode: maximum is 3x target (threshold limit per D-07)
     * When target is 0 (no target): maximum is 24 hours (absolute limit for sanity)
     */
    private fun calculateSafeDurationLimit(isCountdown: Boolean, targetMinutes: Int): Int {
        // Absolute maximum: 24 hours (prevent runaway values from system date changes)
        val absoluteMaxSeconds = 24 * 60 * 60

        return if (isCountdown) {
            // Countdown: cannot exceed target (countdown habits always have a target)
            targetMinutes * 60
        } else {
            // Countup with target: threshold limit (target × 3)
            // Countup without target: absolute maximum
            if (targetMinutes > 0) {
                targetMinutes * THRESHOLD_MULTIPLIER * 60
            } else {
                absoluteMaxSeconds
            }
        }
    }

}
