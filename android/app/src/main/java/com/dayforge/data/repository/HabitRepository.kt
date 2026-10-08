package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.StreakStats
import com.dayforge.domain.model.OneTimeStatus
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.StreakCalculator
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.reminder.HabitReminderScheduler
import com.dayforge.util.DateTimeUtils
import com.dayforge.widget.WidgetRefreshScheduler
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
    private val structuralEditGuard: StructuralEditGuard? = null,
    private val nextObjectEditor: NextObjectEditor? = null,
    private val nextObjectCreator: NextObjectCreator? = null,
    private val oneTimeRepository: OneTimeRepository? = null,
    private val countHistoryReader: CountHistoryReader? = null
) {
    val allHabits: Flow<List<HabitEntity>> = habitDao.getAllHabits()

    val oneTimeChanges: Flow<Unit> = oneTimeRepository?.changes ?: kotlinx.coroutines.flow.flowOf(Unit)

    val countChanges: Flow<Unit> = countHistoryReader?.changes ?: kotlinx.coroutines.flow.flowOf(Unit)

    suspend fun getCountHistory(habit: HabitEntity): com.dayforge.domain.model.CountHistory =
        requireNotNull(countHistoryReader) { "COUNT_READER_REQUIRED" }.read(habit)

    suspend fun getOneTimeStatus(id: Long, expectedUuid: String? = null): OneTimeStatus = requireNotNull(oneTimeRepository) {
        "ONE_TIME_REPOSITORY_REQUIRED"
    }.read(id, expectedUuid)

    suspend fun toggleOneTime(context: Context, id: Long, expectedUuid: String? = null,
        authority: com.dayforge.domain.model.OneTimeActionAuthority? = null): Boolean {
        val completed = requireNotNull(oneTimeRepository).toggle(id, expectedUuid, authority)
        notifyWidgetUpdate(context)
        return completed
    }

    // Flow of all completions for reactive UI updates
    fun getAllCompletions(): Flow<List<CompletionEntity>> = completionDao.getAllCompletions()

    fun getHabit(id: Long): Flow<HabitEntity?> = habitDao.getHabitByIdFlow(id)

    suspend fun getHabitById(id: Long): HabitEntity? = habitDao.getVisibleHabitById(id)

    suspend fun getHabitForEditing(id: Long): ObjectEditSnapshot<HabitEntity> =
        nextObjectEditor?.habit(id) ?: ObjectEditSnapshot(habitDao.getHabitById(id), null)

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
        selectedMetricIds: Set<Long> = emptySet(),
        appearance: com.dayforge.domain.model.ObjectAppearance? = null,
        completionPolicy: String? = null,
        creationAuthority: ObjectCreationAuthority? = null
    ): Long {
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        val draft = HabitDraft(id = predefinedUuid ?: java.util.UUID.randomUUID().toString(),
            name = name, description = description, habitType = habitType, iconResId = iconResId,
            colorHex = colorHex, schedule = schedule, targetValue = targetValue, isCountdown = isCountdown,
            targetCycles = targetCycles, failMode = failMode, bestTime = bestTime,
            selectedMetricIds = selectedMetricIds, appearance = appearance, completionPolicy = completionPolicy)
        val habit = if (creationAuthority != null) requireNotNull(nextObjectCreator)
            .habit(draft, parentHabitId, creationAuthority) else HabitEntity(
            uuid = draft.id,
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
        if (creationAuthority == null) require(appearance == null && completionPolicy == null && schedule !is HabitSchedule.Once) {
            "OBJECT_CREATE_REQUIRES_COORDINATED_SWITCH"
        }
        val id = if (creationAuthority != null) requireNotNull(nextObjectCreator).habits(listOf(habit), creationAuthority) {
            insertHabit(habit, selectedMetricIds)
        } else database.withTransaction { insertHabit(habit, selectedMetricIds) }
        // Side effects only after the complete business/original intent transaction commits.
        context?.let { notifyWidgetUpdate(it) }
        if (habit.bestTime != null && habit.habitType != HabitType.GOAL) {
            context?.let { HabitReminderScheduler.scheduleReminder(it, id) }
        }
        return id
    }

    private suspend fun insertHabit(habit: HabitEntity, selectedMetricIds: Set<Long>): Long {
        requireValidHierarchy(habit)
        require(habitDao.getHabitByUuid(habit.uuid) == null) { "OBJECT_CREATE_ID_REUSED" }
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
        return habitId
    }

    /** Parent, children, metric links and their outbox entries commit together. */
    suspend fun createGoal(goal: HabitDraft, children: List<HabitDraft>, context: Context? = null,
        creationAuthority: ObjectCreationAuthority? = null): Long {
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        require(goal.habitType == HabitType.GOAL && goal.selectedMetricIds.isEmpty())
        require(children.all { it.habitType != HabitType.GOAL })
        require((listOf(goal.id) + children.map { it.id }).distinct().size == children.size + 1)
        fun row(draft: HabitDraft, parent: String?): HabitEntity = if (creationAuthority != null)
            requireNotNull(nextObjectCreator).habit(draft, parent, creationAuthority) else {
                require(draft.appearance == null && draft.completionPolicy == null && draft.schedule !is HabitSchedule.Once)
                HabitEntity(uuid = draft.id, name = draft.name, description = draft.description, habitType = draft.habitType,
                    iconResId = draft.iconResId, colorHex = draft.colorHex, schedule = draft.schedule,
                    targetValue = draft.targetValue, isCountdown = draft.isCountdown, parentHabitId = parent,
                    targetCycles = draft.targetCycles, failMode = draft.failMode, bestTime = draft.bestTime)
            }
        val rows = listOf(row(goal, null)) + children.map { row(it, goal.id) }
        suspend fun commit(): List<HabitEntity> {
            // A restored draft may have committed immediately before process death.
            // Stable UUIDs make retrying the complete save safe without replacing rows.
            val existing = habitDao.getHabitByUuid(goal.id)
            if (existing != null) {
                require(existing.habitType == HabitType.GOAL && existing.parentHabitId == null)
                require(children.all { habitDao.getHabitByUuid(it.id)?.parentHabitId == goal.id })
                if (creationAuthority != null) {
                    val actual = listOf(existing) + habitDao.getChildrenByParentUuidOnce(goal.id)
                    require(actual.map { it.uuid }.toSet() == rows.map { it.uuid }.toSet()) { "OBJECT_CREATE_ID_REUSED" }
                    for (expected in rows) {
                        val old = actual.single { it.uuid == expected.uuid }
                        require(old.copy(id = 0, createdAt = expected.createdAt, updatedAt = expected.updatedAt,
                            activityRateUpdatedAt = expected.activityRateUpdatedAt,
                            planMetadata = old.planMetadata?.copy(creationTimestamp = expected.planMetadata!!.creationTimestamp)) == expected) {
                            "OBJECT_CREATE_ID_REUSED"
                        }
                        val links = database.habitMetricLinkDao().getAllLinksForHabit(old.id).map { it.metricId }
                        require(links.toSet() == (listOf(goal) + children).single { it.id == old.uuid }.selectedMetricIds) {
                            "OBJECT_CREATE_ID_REUSED"
                        }
                    }
                    return actual
                }
                return listOf(existing) + habitDao.getChildrenByParentUuidOnce(goal.id)
            }
            suspend fun insert(draft: HabitDraft, parent: String?): HabitEntity {
                val id = insertHabit(rows.single { it.uuid == draft.id && it.parentHabitId == parent }, draft.selectedMetricIds)
                return requireNotNull(habitDao.getHabitById(id))
            }
            return listOf(insert(goal, null)) + children.map { insert(it, goal.id) }
        }
        val saved = if (creationAuthority != null) requireNotNull(nextObjectCreator).habits(rows, creationAuthority, ::commit)
            else database.withTransaction { commit() }
        // No externally visible side effects until the outer transaction commits.
        context?.let { appContext ->
            notifyWidgetUpdate(appContext)
            saved.forEach { habit ->
                if (habit.bestTime != null && habit.habitType != HabitType.GOAL) {
                    HabitReminderScheduler.scheduleReminder(appContext, habit.id)
                }
            }
        }
        return saved.first().id
    }

    suspend fun updateHabit(
        habit: HabitEntity,
        context: Context? = null,
        selectedMetricIds: Set<Long>? = null,
        editAuthority: ObjectEditAuthority? = null
    ) {
        if (habit.appearance == null) structuralEditGuard?.requireAllowed()
        var persistedHabit = habit.copy(updatedAt = System.currentTimeMillis())
        suspend fun commit(saved: HabitEntity): HabitEntity {
            val previous = requireNotNull(habitDao.getHabitById(habit.id)) {
                "Habit no longer exists: ${habit.id}"
            }
            check((previous.appearance == null) == (saved.appearance == null)) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
            requireValidHierarchy(saved)
            habitDao.update(saved)
            selectedMetricIds?.let { reconcileMetricLinks(saved, it) }
            persistedHabit = saved
            return previous
        }
        val previousHabit = if (habit.appearance != null && nextObjectEditor != null) {
            nextObjectEditor.editHabit(habit,
                requireNotNull(editAuthority) { "OBJECT_EDIT_TICKET_REQUIRED" }, ::commit)
        } else database.withTransaction { commit(persistedHabit) }
        // Notify widgets to update
        context?.let { notifyWidgetUpdate(it) }
        // Handle reminder scheduling changes (NOTIFY-01)
        context?.let {
            val previousBestTime = previousHabit.bestTime
            val previousHabitType = previousHabit.habitType
            val previousTargetValue = previousHabit.targetValue
            val newBestTime = persistedHabit.bestTime

            // Check if reminder needs to be rescheduled
            val needsCancel = previousBestTime != null &&
                (newBestTime == null ||
                 newBestTime != previousBestTime ||
                 previousHabitType != persistedHabit.habitType ||
                 previousTargetValue != persistedHabit.targetValue)

            val needsSchedule = newBestTime != null && persistedHabit.habitType != HabitType.GOAL

            // Cancel existing reminder if needed
            if (needsCancel) {
                HabitReminderScheduler.cancelReminder(it, habit.id)
            }

            // Schedule new reminder if needed
            if (needsSchedule) {
                HabitReminderScheduler.scheduleReminder(
                    it,
                    persistedHabit.id
                )
            }
        }
    }

    /** D-003 must hold at the write boundary, including callers outside the editors. */
    private suspend fun requireValidHierarchy(habit: HabitEntity) {
        require(habit.parentHabitId != habit.uuid) { "A habit cannot be its own parent" }
        if (habit.habitType == HabitType.GOAL) {
            require(habit.parentHabitId == null) { "Goals must be top-level" }
        } else {
            require(habitDao.getChildrenByParentUuidOnce(habit.uuid).isEmpty()) {
                "Only goals can have children"
            }
        }
        habit.parentHabitId?.let { parentUuid ->
            val parent = requireNotNull(habitDao.getHabitByUuid(parentUuid)) { "Missing parent goal" }
            require(parent.habitType == HabitType.GOAL && parent.parentHabitId == null) {
                "Parent must be a top-level goal"
            }
        }
    }

    private suspend fun reconcileMetricLinks(habit: HabitEntity, selectedMetricIds: Set<Long>) {
        val linkDao = database.habitMetricLinkDao()
        val existingLinks = linkDao.getAllLinksForHabit(habit.id)
        existingLinks
            .filter { it.metricId !in selectedMetricIds }
            .forEach { linkDao.delete(it) }

        val existingMetricIds = existingLinks.mapTo(mutableSetOf()) { it.metricId }
        selectedMetricIds
            .filterNot { it in existingMetricIds }
            .forEach { metricId ->
                val metric = requireNotNull(database.metricDao().getMetricById(metricId)) {
                    "Selected metric no longer exists: $metricId"
                }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habit.id,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = metric.uuid,
                        coefficient = 1.0,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
    }

    suspend fun deleteHabit(
        habit: HabitEntity,
        context: Context? = null,
        factDerivedTaskFinalization: Boolean = false,
        authority: ObjectEditAuthority? = null
    ) {
        if (habit.appearance != null) {
            require(!factDerivedTaskFinalization) { "ONE_TIME_COMPLETION_MUST_BE_RETAINED" }
            val deleted = requireNotNull(nextObjectEditor).deleteHabit(habit,
                if (habit.habitType == HabitType.GOAL) "detach_children" else null, authority)
            afterHabitDeletion(deleted, context)
            return
        }
        if (!factDerivedTaskFinalization) structuralEditGuard?.requireAllowed()
        // The local delete and its cascades are captured atomically by the v2 outbox.
        habitDao.delete(habit)
        afterHabitDeletion(listOf(habit), context)
    }

    /**
     * Delete a habit and all its child habits (and their descendants recursively).
     * Remote deletion is handled by the durable v2 outbox.
     * @param habit The parent habit to delete with all children
     * @param context Context for widget notification
     */
    suspend fun deleteHabitWithChildren(habit: HabitEntity, context: Context? = null, authority: ObjectEditAuthority? = null) {
        if (habit.appearance != null) {
            afterHabitDeletion(requireNotNull(nextObjectEditor).deleteHabit(habit, "cascade_children", authority), context)
            return
        }
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
                        deleted.id
                    )
                }
            }
            // One subtree deletion is one presentation invalidation, not one per child.
            notifyWidgetUpdate(appContext)
        }
    }

    /**
     * Delete a habit but keep its child habits by orphaning them (set parentHabitId = null).
     * Children remain locally and on server with parentHabitId = null.
     * @param habit The parent habit to delete
     * @param context Context for widget notification
     */
    suspend fun deleteHabitOrphanChildren(habit: HabitEntity, context: Context? = null, authority: ObjectEditAuthority? = null) {
        if (habit.appearance != null) {
            afterHabitDeletion(requireNotNull(nextObjectEditor).deleteHabit(habit, "detach_children", authority), context)
            return
        }
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
                    habit.id
                )
            }
            notifyWidgetUpdate(appContext)
        }
    }

    /**
     * Get all direct children of a habit.
     * @param habitUuid The UUID of the parent habit
     * @return List of child habits
     */
    suspend fun getHabitChildren(habitUuid: String): List<HabitEntity> {
        return habitDao.getVisibleChildrenByParentUuidOnce(habitUuid)
    }

    private suspend fun afterHabitDeletion(deleted: List<HabitEntity>, context: Context?) {
        if (deleted.isEmpty()) return
        context?.let { appContext ->
            deleted.forEach { habit -> if (habit.bestTime != null) {
                HabitReminderScheduler.cancelReminder(appContext, habit.id)
            } }
            notifyWidgetUpdate(appContext)
        }
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
        val habit = habitDao.getHabitById(habitId)
        if (habit?.appearance != null && nextObjectEditor != null) {
            val captured = nextObjectEditor.habit(habitId)
            val row = requireNotNull(captured.value) { "OBJECT_EDIT_NOT_FOUND" }
            nextObjectEditor.editHabit(row.copy(isActive = isActive), requireNotNull(captured.authority)) {
                habitDao.update(it)
            }
        } else {
            structuralEditGuard?.requireAllowed()
            habitDao.updateIsActive(habitId, isActive)
        }

        // Handle reminder scheduling based on active status
        if (habit != null && habit.bestTime != null && habit.habitType != HabitType.GOAL) {
            if (!isActive) {
                // Habit deactivated: cancel reminder
                HabitReminderScheduler.cancelReminder(context, habitId)
            } else {
                // Habit reactivated: schedule reminder
                HabitReminderScheduler.scheduleReminder(context, habitId)
            }
        }

        // Notify widgets to update
        notifyWidgetUpdate(context)

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
        val habit = requireNotNull(habitDao.getHabitById(habitId))
        mutate(habit) { habitDao.updateFailMode(it.id, failMode) }

        // Notify widgets to update
        notifyWidgetUpdate(context)
    }

    /**
     * Logs a completion for a habit and notifies widgets.
     * Automatically reactivates inactive habits when checked in.
     * @param context Context for scheduling widget refresh
     * @param habitId The ID of the habit to log completion for
     * @param value The completion value (default 1 for check-in habits)
     * @return The ID of the inserted completion
     */
    suspend fun logCompletion(context: Context, habitId: Long, value: Int = 1,
        authority: ObjectEditAuthority? = null, expectedHabitUuid: String? = null): Long {
        val capturedHabit = habitDao.getHabitById(habitId)
        if (expectedHabitUuid != null) check(capturedHabit?.uuid == expectedHabitUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (capturedHabit?.completionPolicy == "one_and_done") {
            require(value == 1) { "ONE_TIME_VALUE_MUST_BE_ONE" }
            val id = requireNotNull(oneTimeRepository).change(habitId, complete = true, expectedUuid = capturedHabit.uuid)
            notifyWidgetUpdate(context)
            return id
        }
        val occurredAt = java.time.Instant.now()
        val capturedZone = java.time.ZoneId.systemDefault()
        val id = mutate(requireNotNull(capturedHabit), authority, structural = !capturedHabit.isActive) { habit ->
            // Auto-reactivate if habit is currently inactive
            if (!habit.isActive) {
                habitDao.updateIsActive(habitId, true)
            }

            val completion = CompletionEntity(
                habitId = habitId,
                date = occurredAt.atZone(capturedZone).toLocalDate().atStartOfDay(capturedZone)
                    .toInstant().toEpochMilli(),
                value = value,
                actualCompletedAt = occurredAt.toEpochMilli(),
                habitUuid = habit.uuid,
                recordedTimezone = capturedZone.id
            )
            if (habit.appearance != null && habit.habitType == HabitType.COUNTING)
                NextCountDayStore(database).capture(habit, completion)
            val completionId = completionDao.insert(completion)
            updateActivityRate(habitId)
            completionId
        }

        // Notify widgets to update
        notifyWidgetUpdate(context)

        // Completion and any reactivation are both captured by the v2 outbox.

        return id
    }

    /**
     * Undoes a completion by deleting it.
     * @param context Context for scheduling widget refresh
     * @param completionId The ID of the completion to delete
     */
    suspend fun undoCompletion(context: Context, completionId: Long,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        authority: ObjectEditAuthority? = null) {
        val initial = completionDao.getCompletionById(completionId)
        if (oneTimeAuthority != null) check(initial?.oneTimeAction == "complete") { "ONE_TIME_ACTION_EXPIRED" }
        if (initial?.oneTimeAction != null) {
            require(initial.oneTimeAction == "complete") { "ONE_TIME_ONLY_COMPLETION_CAN_BE_UNDONE" }
            requireNotNull(oneTimeRepository).change(initial.habitId, complete = false,
                expectedCompletionId = completionId, expectedUuid = initial.habitUuid, authority = oneTimeAuthority)
            notifyWidgetUpdate(context)
            return
        }
        if (initial == null) return
        val expected = requireNotNull(habitDao.getHabitById(initial.habitId))
        if (expected.appearance != null) check(initial.habitUuid == expected.uuid) { "OBJECT_WRITE_FACT_CHANGED" }
        mutate(expected, authority, structural = false) {
            val completion = completionDao.getCompletionById(completionId)
            // Ordinary undo has always been a no-op when another click already removed it.
            if (completion == null) return@mutate
            check(completion == initial) { "OBJECT_WRITE_FACT_CHANGED" }

            // A fact cannot be mutated remotely; the outbox converts this delete to a
            // new immutable revert event when the original event was already synced.
            completionDao.delete(initial)
            updateActivityRate(initial.habitId)
        }

        // Notify widgets
        notifyWidgetUpdate(context)
    }

    /**
     * Clears all completion and timelog history for a habit and reactivates it.
     * The v2 outbox records the local transaction for later synchronization.
     * @param habit The habit entity to clear history for
     * @param context Context for widget notification
     */
    suspend fun clearHabitHistory(habit: HabitEntity, context: Context) {
        require(habit.completionPolicy != "one_and_done") { "ONE_TIME_HISTORY_IS_IMMUTABLE" }
        structuralEditGuard?.requireAllowed()
        database.withTransaction {
            val current = requireNotNull(habitDao.getHabitById(habit.id))
            check(current.uuid == habit.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            require(current.completionPolicy != "one_and_done") { "ONE_TIME_HISTORY_IS_IMMUTABLE" }
            // Local deletes become fact tombstones/revert events through the outbox.
            completionDao.deleteByHabitId(current.id)
            timeLogDao.deleteByHabitId(current.id)
            habitDao.updateIsActive(current.id, true)
        }

        // Notify widgets
        notifyWidgetUpdate(context)
    }

    private suspend fun <T> mutate(expected: HabitEntity, authority: ObjectEditAuthority? = null,
        structural: Boolean = true, commit: suspend (HabitEntity) -> T): T {
        if (expected.appearance != null && nextObjectEditor != null) {
            return nextObjectEditor.mutateHabit(expected, authority, commit)
        }
        check(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (structural) structuralEditGuard?.requireAllowed()
        return database.withTransaction {
            val current = requireNotNull(habitDao.getHabitById(expected.id)) { "OBJECT_WRITE_NOT_FOUND" }
            check(current.uuid == expected.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            commit(current)
        }
    }

    /**
     * Gets the total completion count for today.
     * @param habitId The ID of the habit
     * @return Sum of completion values for today
     */
    suspend fun getTodayCompletionCount(habitId: Long): Int {
        val today = DateTimeUtils.today()
        val tomorrow = today.plusDays(1)
        val completions = completionDao.getCompletionsInRange(habitId, today, tomorrow)
        return completions.sumOf { it.value }
    }

    /**
     * Gets streak statistics for a habit.
     * @param habitId The ID of the habit
     * @return Flow of StreakStats with current and best streak
     */
    fun getStreakStats(habitId: Long): Flow<StreakStats> {
        return kotlinx.coroutines.flow.combine(completionDao.getCompletionsByHabit(habitId), countChanges,
            habitDao.getHabitByIdFlow(habitId)) { rows, _, _ -> rows }
            .map { completions ->
                val habit = habitDao.getHabitById(habitId)
                if (habit?.completionPolicy == "one_and_done") {
                    return@map StreakStats(0, 0, null)
                }
                if (habit?.habitType == HabitType.COUNTING && habit.appearance != null) {
                    val history = getCountHistory(habit)
                    return@map StreakStats(StreakCalculator.currentFromBusinessDates(history.qualifiedDates, history.today),
                        StreakCalculator.bestFromBusinessDates(history.qualifiedDates), history.qualifiedDates.maxOrNull()?.toDisplayMillis())
                }
                val currentStreak = StreakCalculator.calculateCurrentStreak(completions)
                val bestStreak = StreakCalculator.calculateBestStreak(completions)
                val lastCompletionDate = completions.maxOfOrNull { it.businessDate }?.toDisplayMillis()
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
        val today = DateTimeUtils.today()
        val tomorrow = today.plusDays(1)
        return completionDao.getTodayCompletionId(habitId, today, tomorrow)
    }

    /**
     * Clears all local data.
     * Called when user logs out or a new user logs in.
     */
    suspend fun clearAllData(context: Context? = null) {
        database.clearAllData()
        context?.let {
            // Login/logout owns the account lock here. Cancel only after the clear commits;
            // never call a scheduler that attempts to reacquire the same non-reentrant lock.
            HabitReminderScheduler.cancelAllReminders(it)
            notifyWidgetUpdate(it)
        }
    }

    /**
     * Invalidates all widget presentations after a committed data change.
     */
    private fun notifyWidgetUpdate(context: Context) {
        WidgetRefreshScheduler.request(context)
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
            completions = completions.map { it.businessDate }
        )
        habitDao.updateActivityRate(habitId, newRate)
    }
}
