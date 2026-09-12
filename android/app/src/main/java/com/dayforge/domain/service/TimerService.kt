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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import com.dayforge.widget.WidgetUpdateReceiver
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            ACTION_DISCARD -> handleDiscard()
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
                            handleStop()
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
                                handleStop()
                                return@launch  // Exit ticker loop
                            }
                        }
                    }
                }
                maybeHeartbeat()
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
        if (habitId == 0L) return  // No active timer

        val intent = Intent(ACTION_WIDGET_UPDATE).apply {
            putExtra(EXTRA_HABIT_ID, habitId)
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

    private fun handleStart(intent: Intent) {
        habitId = intent.getLongExtra(EXTRA_HABIT_ID, 0L)
        targetMinutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, 0)

        // CRITICAL: Start foreground immediately to avoid ForegroundServiceDidNotStartInTimeException
        // Android requires startForeground() to be called within 5 seconds of startForegroundService()
        startForegroundWithNotification()

        // Read isCountdown from intent, or query habit from database
        isCountdown = if (intent.hasExtra(EXTRA_IS_COUNTDOWN)) {
            intent.getBooleanExtra(EXTRA_IS_COUNTDOWN, false)
        } else {
            // Query habit from database if isCountdown not provided in intent
            runBlocking {
                withContext(Dispatchers.IO) {
                    habitDao.getHabitByIdSync(habitId)?.isCountdown ?: false
                }
            }
        }

        // Check if already completed today (one completion per day)
        // Use local timezone to match how TimeLogEntity.date is stored
        val todayStart = DateTimeUtils.startOfDayMillis()
        val todayEnd = DateTimeUtils.startOfNextDayMillis(todayStart)
        val completedSecondsToday = runBlocking {
            withContext(Dispatchers.IO) {
                timeLogDao.getCompletedDurationSecondsForDate(
                    habitId, java.time.LocalDate.now().toString(), todayStart, todayEnd
                )
            }
        }
        val targetSeconds = targetMinutes * 60
        val alreadyCompletedToday = completedSecondsToday >= targetSeconds

        if (alreadyCompletedToday) {
            // Already completed today, don't start a new timer
            Log.d(TAG, "handleStart: habit $habitId already completed today, not starting timer")
            stopSelf()
            return
        }

        // Check for existing active TimeLogEntity (recovery case)
        // Per D-04: restore state from existing entity
        val existingLog = runBlocking {
            withContext(Dispatchers.IO) {
                timeLogDao.getActiveTimeLog()
            }
        }

        if (existingLog != null && existingLog.habitId == habitId) {
            // Recovery: restore state from existing TimeLogEntity for same habit
            currentLogId = existingLog.id
            startTime = existingLog.startTime
            isPaused = existingLog.isPaused
            pausedAt = existingLog.pausedAt
            accumulatedPauseDuration = existingLog.accumulatedPauseMillis
            timerSessionUuid = existingLog.uuid
            nextCommandSequence = existingLog.timerNextCommandSequence
            controlGeneration = existingLog.timerControlGeneration
            lastCommandAt = existingLog.timerLastCommandAt
            timerTimezone = existingLog.timerTimezone
            activeElapsedAtAnchor = existingLog.timerActiveElapsedMillis
            elapsedRealtimeAnchor = existingLog.timerElapsedRealtimeAnchor
            timerBootCount = existingLog.timerBootCount

            // If timer was paused, we restore paused state
            // If timer was running, it continues from where it left off
        } else {
            // Stop any existing active timer for a different habit
            // Only one timer can be active at a time
            if (existingLog != null && existingLog.habitId != habitId) {
                val stoppedHabitId = existingLog.habitId
                val existingElapsed = calculateElapsedSecondsForLog(existingLog)

                // Get the habit being interrupted to check isCountdown and targetValue
                val interruptedHabit = runBlocking {
                    withContext(Dispatchers.IO) {
                        habitDao.getHabitByIdSync(stoppedHabitId)
                    }
                }

                // Determine whether to save or discard the interrupted timer
                // Rule: Countdown -> always discard
                // Rule: Countup -> save if target reached, discard otherwise
                val shouldSave = if (interruptedHabit?.isCountdown == true) {
                    // Countdown mode: always discard when interrupted
                    Log.d(TAG, "Interrupted countdown timer for habit $stoppedHabitId, discarding")
                    false
                } else {
                    // Countup mode: save only if target reached
                    val targetSeconds = (interruptedHabit?.targetValue ?: 0) * 60
                    val targetReached = targetSeconds > 0 && existingElapsed >= targetSeconds
                    Log.d(TAG, "Interrupted countup timer for habit $stoppedHabitId, elapsed=$existingElapsed, target=$targetSeconds, save=$targetReached")
                    targetReached
                }

                runBlocking {
                    withContext(Dispatchers.IO) {
                        if (shouldSave) {
                            // Clamp duration to safe limit to prevent abnormal values
                            val interruptedTargetMinutes = interruptedHabit?.targetValue ?: 0
                            val safeLimit = calculateSafeDurationLimit(
                                interruptedHabit?.isCountdown ?: false,
                                interruptedTargetMinutes
                            )
                            val safeElapsed = existingElapsed.coerceAtMost(safeLimit)

                            if (existingElapsed > safeLimit) {
                                Log.w(TAG, "Interrupted timer duration clamped: raw=$existingElapsed, limit=$safeLimit")
                            }

                            // Save the timer with clamped duration
                            finishInterruptedTimer(existingLog, safeElapsed)
                        } else {
                            cancelInterruptedTimer(existingLog)
                        }
                    }
                }

                // Broadcast widget update for stopped habit
                val updateIntent = Intent(ACTION_WIDGET_UPDATE).apply {
                    putExtra(EXTRA_HABIT_ID, stoppedHabitId)
                    setPackage(packageName)
                }
                sendBroadcast(updateIntent)

                // Cancel old ticker if running
                tickerJob?.cancel()
                tickerJob = null
            }

            // New timer: create fresh state
            startTime = System.currentTimeMillis()  // Actual start time
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

            // Create and persist TimeLogEntity for process recovery
            // Use runBlocking to ensure currentLogId is set before continuing
            // Use local timezone for date field to match other habit types
            val dateMidnight = DateTimeUtils.startOfDayMillis()

            currentLogId = runBlocking {
                withContext(Dispatchers.IO) {
                    val habit = habitDao.getHabitByIdSync(habitId)
                        ?: error("Timer habit no longer exists")
                    val sessionUuid = UUID.randomUUID().toString()
                    val capturedTimezone = requireNotNull(timerTimezone)
                    timerSessionUuid = sessionUuid
                    val timeLog = TimeLogEntity(
                        habitId = habitId,
                        startTime = startTime,  // Actual start time
                        endTime = null,  // null means timer is active
                        durationSeconds = 0,  // Will be calculated on stop
                        isPaused = false,
                        pausedAt = null,
                        accumulatedPauseMillis = 0,
                        timerNextCommandSequence = 2,
                        timerControlGeneration = 1,
                        timerLastCommandAt = startTime,
                        timerTimezone = capturedTimezone,
                        timerActiveElapsedMillis = 0,
                        timerElapsedRealtimeAnchor = elapsedRealtimeAnchor,
                        timerBootCount = timerBootCount,
                        date = dateMidnight,
                        uuid = sessionUuid
                    )
                    timeLogDao.insertSyncedTimer(
                        timeLog,
                        timerCommand(
                            sessionUuid = sessionUuid,
                            sequence = 1,
                            type = "start",
                            occurredAt = startTime,
                            generation = 0,
                            activityUuid = habit.uuid,
                            timezone = capturedTimezone
                        ),
                        TimerSegmentEntity(
                            sessionUuid = sessionUuid,
                            sequence = 1,
                            startedAt = startTime
                        )
                    )
                }
            }
        }

        // Foreground already started at the beginning of handleStart
        autoSyncCoordinator.enqueueNow()
        startTicker()
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

    private fun handlePause() {
        if (!isPaused) {
            val activeElapsed = calculateElapsedMillis()
            val commandAt = nextRunningBoundary(activeElapsed)
            activeElapsedAtAnchor = activeElapsed
            elapsedRealtimeAnchor = null
            isPaused = true
            pausedAt = commandAt

            // Commit state and command before accepting another service action.
            runBlocking {
                withContext(Dispatchers.IO) {
                    if (currentLogId > 0) {
                        val sessionUuid = requireNotNull(timerSessionUuid)
                        val sequence = nextCommandSequence++
                        timeLogDao.updatePauseAndQueue(
                            id = currentLogId,
                            isPaused = true,
                            pausedAt = pausedAt,
                            accumulatedPauseMillis = accumulatedPauseDuration,
                            nextSequence = nextCommandSequence,
                            commandAt = requireNotNull(pausedAt),
                            activeElapsedMillis = activeElapsedAtAnchor,
                            elapsedRealtimeAnchor = null,
                            bootCount = timerBootCount,
                            command = timerCommand(
                                sessionUuid, sequence, "pause", requireNotNull(pausedAt),
                                activeElapsedMillis = activeElapsedAtAnchor
                            ),
                            resumedSegment = null
                        )
                        // Broadcast widget update after database is updated
                        notifyWidgetUpdate()
                    }
                }
            }

            autoSyncCoordinator.enqueueNow()

            updateNotification()
        }
    }

    private fun handleResume() {
        if (isPaused && pausedAt != null) {
            // Calculate how long we were paused and add to accumulated duration
            val resumedAt = nextCommandTime()
            val pauseDuration = resumedAt - (pausedAt ?: 0L)
            accumulatedPauseDuration += pauseDuration

            isPaused = false
            pausedAt = null
            elapsedRealtimeAnchor = SystemClock.elapsedRealtime()
            timerBootCount = currentBootCount()

            runBlocking {
                withContext(Dispatchers.IO) {
                    if (currentLogId > 0) {
                        val sessionUuid = requireNotNull(timerSessionUuid)
                        val sequence = nextCommandSequence++
                        timeLogDao.updatePauseAndQueue(
                            id = currentLogId,
                            isPaused = false,
                            pausedAt = null,
                            accumulatedPauseMillis = accumulatedPauseDuration,
                            nextSequence = nextCommandSequence,
                            commandAt = resumedAt,
                            activeElapsedMillis = activeElapsedAtAnchor,
                            elapsedRealtimeAnchor = elapsedRealtimeAnchor,
                            bootCount = timerBootCount,
                            command = timerCommand(
                                sessionUuid, sequence, "resume", resumedAt
                            ),
                            resumedSegment = TimerSegmentEntity(
                                sessionUuid = sessionUuid,
                                sequence = sequence,
                                startedAt = resumedAt
                            )
                        )
                        // Broadcast widget update after database is updated
                        notifyWidgetUpdate()
                    }
                }
            }

            autoSyncCoordinator.enqueueNow()

            updateNotification()
        }
    }

    private fun handleStop() {
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

        // Save habitId for widget broadcast before clearing state
        val stoppedHabitId = habitId

        // If currentLogId is 0, try to get the active timer from database
        // This can happen if the service was restarted or insert wasn't complete
        val logIdToStop = if (currentLogId > 0) {
            currentLogId
        } else {
            runBlocking {
                withContext(Dispatchers.IO) {
                    timeLogDao.getActiveTimeLog()?.id ?: 0L
                }
            }
        }

        // Calculate final duration if we have a valid log
        if (logIdToStop > 0) {
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

            // Persist final state to database synchronously to ensure completion before stopSelf()
            // Using runBlocking instead of serviceScope.launch to avoid race condition with onDestroy()
            runBlocking {
                withContext(Dispatchers.IO) {
                    val sessionUuid = requireNotNull(timerSessionUuid)
                    val sequence = nextCommandSequence++
                    timeLogDao.finishTimerAndQueue(
                        id = logIdToStop,
                        endTime = endTime,
                        durationSeconds = durationSeconds,
                        accumulatedPauseMillis = accumulatedPauseDuration,
                        nextSequence = nextCommandSequence,
                        activeElapsedMillis = activeElapsedMillis,
                        command = timerCommand(
                            sessionUuid, sequence, "stop", endTime,
                            activeElapsedMillis = activeElapsedMillis
                        ),
                        wasPaused = isPaused
                    )
                    persistDayAllocations(logIdToStop)
                }
            }
            autoSyncCoordinator.enqueueNow()

            // Check if this habit has linked metrics with promptOnComplete=true
            // If so, add to pending metric habits set
            if (stoppedHabitId != 0L) {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val links = habitMetricLinkDao.getLinksByHabitSync(stoppedHabitId)
                        val hasPromptMetrics = links.any { it.promptOnComplete }
                        if (hasPromptMetrics) {
                            Log.d(TAG, "Habit $stoppedHabitId has prompt metrics, adding to pending set")
                            preferencesManager.addPendingMetricHabit(stoppedHabitId)
                        }
                    }
                }
            }

            // Check for goal reached (TARGET-08, TARGET-13)
            // TIMER habits use timelogs for progress calculation
            // Only count days where duration target was met
            if (stoppedHabitId != 0L) {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val habit = habitDao.getHabitById(stoppedHabitId)
                        if (habit?.targetCycles != null) {
                            // Use target-met day count (days where duration >= targetSeconds)
                            val targetSeconds = habit.targetValue * 60
                            val progress = timeLogDao.getTargetMetDayCount(stoppedHabitId, targetSeconds)
                            val goalReached = progress >= habit.targetCycles
                            Log.d(TAG, "Goal check for habit $stoppedHabitId: progress=$progress, target=${habit.targetCycles}, goalReached=$goalReached")
                            if (goalReached) {
                                // Launch GoalCompletionActivity to show dialog
                                val intent = GoalCompletionActivity.createIntent(
                                    this@TimerService,
                                    stoppedHabitId,
                                    habit.name,
                                    progress,
                                    habit.targetCycles
                                )
                                startActivity(intent)
                            }
                        }
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
            val dataChangedIntent = Intent(WidgetUpdateReceiver.ACTION_DATA_CHANGED).apply {
                putExtra(WidgetUpdateReceiver.EXTRA_HABIT_ID, stoppedHabitId)
            }
            LocalBroadcastManager.getInstance(this@TimerService).sendBroadcast(dataChangedIntent)
        }

        // Clear state
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

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Discards the timer session without saving.
     * Per TIMER-09: Deletes TimeLogEntity instead of saving duration.
     * Called when user confirms to abandon an incomplete countdown session.
     */
    private fun handleDiscard() {
        Log.d(TAG, "handleDiscard: discarding timer for habitId=$habitId")

        // Stop the ticker first
        tickerJob?.cancel()
        tickerJob = null

        // Save habitId for widget broadcast before clearing state
        val stoppedHabitId = habitId
        val logIdToDelete = currentLogId

        // Delete the TimeLogEntity synchronously BEFORE stopping service
        // This is critical because stopSelf() triggers onDestroy() which cancels serviceScope
        if (logIdToDelete > 0) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    val logToDelete = timeLogDao.getById(logIdToDelete)
                    if (logToDelete != null) {
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
                        Log.d(TAG, "handleDiscard: deleted TimeLogEntity id=$logIdToDelete")
                    }
                }
            }
            autoSyncCoordinator.enqueueNow()
        }

        // Clear state
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

        // Cancel the notification
        notificationManager.cancel(NOTIFICATION_ID)

        // Broadcast widget update
        if (stoppedHabitId != 0L) {
            val intent = Intent(ACTION_WIDGET_UPDATE).apply {
                putExtra(EXTRA_HABIT_ID, stoppedHabitId)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        // Stop foreground and service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "handleDiscard: service stopped")
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
