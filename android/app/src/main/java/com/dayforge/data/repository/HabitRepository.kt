package com.dayforge.data.repository

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.domain.service.TimeZone
import com.dayforge.reminder.HabitReminderScheduler
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetUpdateReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HabitRepository @Inject constructor(
    private val habitDao: HabitDao,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val database: HabitDatabase,
    private val structuralEditGuard: StructuralEditGuard? = null
) {
    val allHabits: Flow<List<HabitEntity>> = habitDao.getAllHabits()

    // Flow of all completions for reactive UI updates
    fun getAllCompletions(): Flow<List<CompletionEntity>> = completionDao.getAllCompletions()

    fun getHabit(id: Long): Flow<HabitEntity?> = habitDao.getHabitByIdFlow(id)

    suspend fun getHabitById(id: Long): HabitEntity? = habitDao.getHabitById(id)

    suspend fun createHabit(
        name: String,
        description: String,
        habitType: HabitType,
        iconResId: Int,
        colorHex: String,
        schedule: HabitSchedule,
        targetValue: Int = 1,
        isCountdown: Boolean = false,
        parentHabitId: String? = null,
        targetCycles: Int? = null,  // Nullable: null = infinite tracking (no target)
        failMode: FailMode = FailMode.STRICT,  // Failure mode for target-based habits
        bestTime: Long? = null,  // Best execution time (minutes since midnight)
        predefinedUuid: String? = null,  // Pre-allocated UUID for goal creation flow
        context: Context? = null,
        selectedMetricIds: Set<Long> = emptySet()
    ): Long {
        structuralEditGuard?.requireAllowed()
        val habit = HabitEntity(
            uuid = predefinedUuid ?: java.util.UUID.randomUUID().toString(),  // Use predefined or generate new
            name = name,
            description = description,
            habitType = habitType,
            iconResId = iconResId,
            colorHex = colorHex,
            schedule = schedule,
            targetValue = targetValue,
            isCountdown = isCountdown,
            parentHabitId = parentHabitId,
            targetCycles = targetCycles,
            failMode = failMode,
            bestTime = bestTime
        )
        val id = database.withTransaction {
            val habitId = habitDao.insert(habit)
            selectedMetricIds.forEach { metricId ->
                val metric = requireNotNull(database.metricDao().getMetricById(metricId)) {
                    "Selected metric no longer exists: $metricId"
                }
                database.habitMetricLinkDao().insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = metric.uuid,
                        coefficient = 1.0,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
            habitId
        }
        // Notify widgets to update (Progress/Motivation show total habits count)
        context?.let { notifyWidgetUpdate(it) }
        // Schedule reminder if bestTime is set (NOTIFY-01)
        if (habit.bestTime != null && habit.habitType != HabitType.GOAL) {
            context?.let {
                HabitReminderScheduler.scheduleReminder(it, id, habit.bestTime, habit.habitType, habit.targetValue)
            }
        }
        return id
    }

    suspend fun updateHabit(habit: HabitEntity, context: Context? = null) {
        structuralEditGuard?.requireAllowed()
        val previousHabit = habitDao.getHabitById(habit.id)
        habitDao.update(habit.copy(
            updatedAt = System.currentTimeMillis()
        ))
        // Notify widgets to update
        context?.let { notifyWidgetUpdate(it, habit.id) }
        // Handle reminder scheduling changes (NOTIFY-01)
        context?.let {
            val previousBestTime = previousHabit?.bestTime
            val previousHabitType = previousHabit?.habitType ?: habit.habitType
            val previousTargetValue = previousHabit?.targetValue ?: habit.targetValue
            val newBestTime = habit.bestTime

            // Check if reminder needs to be rescheduled
            val needsCancel = previousBestTime != null &&
                (newBestTime == null ||
                 newBestTime != previousBestTime ||
                 previousHabitType != habit.habitType ||
                 previousTargetValue != habit.targetValue)

            val needsSchedule = newBestTime != null && habit.habitType != HabitType.GOAL

            // Cancel existing reminder if needed
            if (needsCancel) {
                HabitReminderScheduler.cancelReminder(it, habit.id, previousHabitType, previousTargetValue)
            }

            // Schedule new reminder if needed
            if (needsSchedule) {
                HabitReminderScheduler.scheduleReminder(it, habit.id, newBestTime, habit.habitType, habit.targetValue)
            }
        }
    }

    suspend fun deleteHabit(
        habit: HabitEntity,
        context: Context? = null,
        factDerivedTaskFinalization: Boolean = false
    ) {
        if (!factDerivedTaskFinalization) structuralEditGuard?.requireAllowed()
        // Cancel any pending reminder for this habit
        if (habit.bestTime != null) {
            context?.let {
                HabitReminderScheduler.cancelReminder(it, habit.id, habit.habitType, habit.targetValue)
            }
        }
        // The local delete and its cascades are captured atomically by the v2 outbox.
        habitDao.delete(habit)

        // Notify widgets to update progress and motivation, and mark checkin/counting widgets as deleted
        context?.let { notifyWidgetUpdate(it, habit.id) }
    }

    /**
     * Delete a habit and all its child habits (and their descendants recursively).
     * Remote deletion is handled by the durable v2 outbox.
     * @param habit The parent habit to delete with all children
     * @param context Context for widget notification
     */
    suspend fun deleteHabitWithChildren(habit: HabitEntity, context: Context? = null) {
        structuralEditGuard?.requireAllowed()
        val deletedHabits = database.withTransaction {
            val descendants = collectDescendants(habit.uuid, mutableSetOf(habit.uuid))
            descendants.asReversed().forEach { child -> habitDao.delete(child) }
            habitDao.delete(habit)
            listOf(habit) + descendants
        }

        context?.let { appContext ->
            deletedHabits.forEach { deleted ->
                if (deleted.bestTime != null) {
                    HabitReminderScheduler.cancelReminder(
                        appContext,
                        deleted.id,
                        deleted.habitType,
                        deleted.targetValue
                    )
                }
                notifyWidgetUpdate(appContext, deleted.id)
            }
        }
    }

    /**
     * Delete a habit but keep its child habits by orphaning them (set parentHabitId = null).
     * Children remain locally and on server with parentHabitId = null.
     * @param habit The parent habit to delete
     * @param context Context for widget notification
     */
    suspend fun deleteHabitOrphanChildren(habit: HabitEntity, context: Context? = null) {
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            val children = habitDao.getChildrenByParentUuidOnce(habit.uuid)
            children.forEach { child ->
                habitDao.updateParentHabitId(child.id, null)
            }
            habitDao.delete(habit)
        }

        context?.let { appContext ->
            if (habit.bestTime != null) {
                HabitReminderScheduler.cancelReminder(
                    appContext,
                    habit.id,
                    habit.habitType,
                    habit.targetValue
                )
            }
            notifyWidgetUpdate(appContext, habit.id)
        }
    }

    /**
     * Get all direct children of a habit.
     * @param habitUuid The UUID of the parent habit
     * @return List of child habits
     */
    suspend fun getHabitChildren(habitUuid: String): List<HabitEntity> {
        return habitDao.getChildrenByParentUuidOnce(habitUuid)
    }

    /**
     * Recursively delete a habit and all its descendants.
     * Private helper used by deleteHabitWithChildren.
     */
    private suspend fun collectDescendants(
        parentUuid: String,
        visitedUuids: MutableSet<String>
    ): List<HabitEntity> {
        val descendants = mutableListOf<HabitEntity>()
        val children = habitDao.getChildrenByParentUuidOnce(parentUuid)
        children.forEach { child ->
            // Defensive cycle protection for malformed imported/synced hierarchy data.
            if (visitedUuids.add(child.uuid)) {
                descendants += child
                descendants += collectDescendants(child.uuid, visitedUuids)
            }
        }
        return descendants
    }

    /**
     * Updates the active status of a habit.
     * @param habitId The ID of the habit to update
     * @param isActive The new active status
     * @param context Context for widget notification
     */
    suspend fun updateIsActive(
        habitId: Long,
        isActive: Boolean,
        context: Context
    ) {
        structuralEditGuard?.requireAllowed()
        val habit = habitDao.getHabitById(habitId)
        habitDao.updateIsActive(habitId, isActive)

        // Handle reminder scheduling based on active status
        if (habit != null && habit.bestTime != null && habit.habitType != HabitType.GOAL) {
            if (!isActive) {
                // Habit deactivated: cancel reminder
                HabitReminderScheduler.cancelReminder(context, habitId, habit.habitType, habit.targetValue)
            } else {
                // Habit reactivated: schedule reminder
                HabitReminderScheduler.scheduleReminder(context, habitId, habit.bestTime, habit.habitType, habit.targetValue)
            }
        }

        // Notify widgets to update
        notifyWidgetUpdate(context, habitId)

        // The update trigger added it to the durable v2 outbox.
    }

    /**
     * Updates the fail mode of a habit.
     * Used when user chooses to continue tracking after reaching targetCycles.
     * Switching to LOOSE mode prevents failure detection for the continued cycle.
     * @param habitId The ID of the habit to update
     * @param failMode The new fail mode (STRICT or LOOSE)
     * @param context Context for widget notification
     */
    suspend fun updateFailMode(
        habitId: Long,
        failMode: com.dayforge.data.model.FailMode,
        context: Context
    ) {
        structuralEditGuard?.requireAllowed()
        habitDao.updateFailMode(habitId, failMode)

        // Notify widgets to update
        notifyWidgetUpdate(context, habitId)
    }

    /**
     * Logs a completion for a habit and notifies widgets.
     * Automatically reactivates inactive habits when checked in.
     * @param context Context for LocalBroadcastManager
     * @param habitId The ID of the habit to log completion for
     * @param value The completion value (default 1 for check-in habits)
     * @return The ID of the inserted completion
     */
    suspend fun logCompletion(context: Context, habitId: Long, value: Int = 1): Long {
        if (habitDao.getHabitById(habitId)?.isActive == false) {
            structuralEditGuard?.requireAllowed()
        }
        val id = database.withTransaction {
            // Auto-reactivate if habit is currently inactive
            val habit = habitDao.getHabitById(habitId)
            if (habit != null && !habit.isActive) {
                habitDao.updateIsActive(habitId, true)
            }

            val completion = CompletionEntity(
                habitId = habitId,
                date = DateTimeUtils.startOfDayMillis(),
                value = value,
                actualCompletedAt = System.currentTimeMillis(),  // Real completion time
                habitUuid = habit?.uuid
            )
            val completionId = completionDao.insert(completion)
            updateActivityRate(habitId)
            completionId
        }

        // Notify widgets to update
        notifyWidgetUpdate(context, habitId)

        // Completion and any reactivation are both captured by the v2 outbox.

        return id
    }

    /**
     * Undoes a completion by deleting it.
     * @param context Context for LocalBroadcastManager
     * @param completionId The ID of the completion to delete
     */
    suspend fun undoCompletion(context: Context, completionId: Long) {
        val habitId = database.withTransaction {
            val completion = completionDao.getCompletionById(completionId)
                ?: return@withTransaction null

            // A fact cannot be mutated remotely; the outbox converts this delete to a
            // new immutable revert event when the original event was already synced.
            completionDao.delete(completion)
            updateActivityRate(completion.habitId)
            completion.habitId
        } ?: return

        // Notify widgets
        notifyWidgetUpdate(context, habitId)
    }

    /**
     * Clears all completion and timelog history for a habit and reactivates it.
     * The v2 outbox records the local transaction for later synchronization.
     * @param habit The habit entity to clear history for
     * @param context Context for widget notification
     */
    suspend fun clearHabitHistory(habit: HabitEntity, context: Context) {
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            // Local deletes become fact tombstones/revert events through the outbox.
            completionDao.deleteByHabitId(habit.id)
            timeLogDao.deleteByHabitId(habit.id)
            habitDao.updateIsActive(habit.id, true)
        }

        // Notify widgets
        notifyWidgetUpdate(context, habit.id)
    }

    /**
     * Gets the total completion count for today.
     * @param habitId The ID of the habit
     * @return Sum of completion values for today
     */
    suspend fun getTodayCompletionCount(habitId: Long): Int {
        val today = DateTimeUtils.startOfDayMillis()
        val tomorrow = today + DateTimeUtils.MILLIS_PER_DAY
        val completions = completionDao.getCompletionsInRange(habitId, today, tomorrow)
        return completions.sumOf { it.value }
    }

    /**
     * Gets streak statistics for a habit.
     * @param habitId The ID of the habit
     * @return Flow of StreakStats with current and best streak
     */
    fun getStreakStats(habitId: Long): Flow<StreakStats> {
        return completionDao.getCompletionsByHabit(habitId)
            .map { completions ->
                val currentStreak = StreakCalculator.calculateCurrentStreak(completions)
                val bestStreak = StreakCalculator.calculateBestStreak(completions)
                val lastCompletionDate = completions.maxByOrNull { it.date }?.date
                StreakStats(currentStreak, bestStreak, lastCompletionDate)
            }
    }

    /**
     * Gets today's progress across all habits.
     * @return Flow of Pair (completed count, total count)
     */
    fun getTodaysProgress(): Flow<Pair<Int, Int>> {
        return habitDao.getAllHabits().map { habits ->
            val total = habits.size
            var completed = 0
            for (habit in habits) {
                val count = getTodayCompletionCount(habit.id)
                if (count > 0) completed++
            }
            Pair(completed, total)
        }
    }

    /**
     * Gets today's completion ID for a habit (for undo functionality).
     * @param habitId The ID of the habit
     * @return Completion ID if completed today, null otherwise
     */
    suspend fun getTodayCompletionId(habitId: Long): Long? {
        val today = DateTimeUtils.startOfDayMillis()
        val tomorrow = today + DateTimeUtils.MILLIS_PER_DAY
        return completionDao.getTodayCompletionId(habitId, today, tomorrow)
    }

    /**
     * Clears all local data.
     * Called when user logs out or a new user logs in.
     */
    suspend fun clearAllData(context: Context? = null) {
        // Cancel all habit reminders before clearing database
        context?.let {
            val allHabits = habitDao.getAllHabitsOnce()
            for (habit in allHabits) {
                if (habit.bestTime != null) {
                    HabitReminderScheduler.cancelReminder(it, habit.id, habit.habitType, habit.targetValue)
                }
            }
        }
        database.clearAllData()
        context?.let { notifyWidgetUpdate(it) }
    }

    /**
     * Sends LocalBroadcast to notify widgets of data changes.
     */
    private fun notifyWidgetUpdate(context: Context, habitId: Long? = null) {
        val intent = Intent(WidgetUpdateReceiver.ACTION_DATA_CHANGED).apply {
            habitId?.let { putExtra(WidgetUpdateReceiver.EXTRA_HABIT_ID, it) }
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    /**
     * Updates the activity rate for a habit.
     * Called after logging/undoing completions.
     * @param habitId The ID of the habit to update
     */
    private suspend fun updateActivityRate(habitId: Long) {
        val habit = habitDao.getHabitById(habitId) ?: return
        val completions = completionDao.getCompletionsByHabit(habitId).first()
        val newRate = ActivityRateCalculator.calculate(
            schedule = habit.schedule,
            createdAt = habit.createdAt,
            completions = completions.map { it.date }
        )
        habitDao.updateActivityRate(habitId, newRate)
    }
}
