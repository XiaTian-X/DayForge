package com.dayforge.data.repository

import kotlinx.serialization.json.jsonObject

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
    private val countHistoryReader: CountHistoryReader? = null,
    private val widgetFactReader: WidgetFactReader? = null,
    private val checkHistoryReader: CheckHistoryReader? = null,
    private val timerHistoryReader: TimerHistoryReader? = null,
    private val recurringHabitReader: RecurringHabitReader? = null,
    private val restartEntry: HabitRestartEntry? = null,
    private val widgetRestartEntry: WidgetRestartEntry? = null,
    private val timerWriter: NextTimerWriter? = null,
    private val countActions: CountActionReader? = null
) {
    val allHabits: Flow<List<HabitEntity>> = habitDao.getAllHabits()

    val oneTimeChanges: Flow<Unit> = oneTimeRepository?.changes ?: kotlinx.coroutines.flow.flowOf(Unit)

    val countChanges: Flow<Unit> = kotlinx.coroutines.flow.combine(
        countHistoryReader?.changes ?: kotlinx.coroutines.flow.flowOf(Unit),
        checkHistoryReader?.changes ?: kotlinx.coroutines.flow.flowOf(Unit),
        timerHistoryReader?.changes ?: kotlinx.coroutines.flow.flowOf(Unit)) { _, _, _ -> Unit }

    suspend fun getCountHistory(habit: HabitEntity): com.dayforge.domain.model.CountHistory =
        requireNotNull(countHistoryReader) { "COUNT_READER_REQUIRED" }.read(habit)

    suspend fun getCountSummary(habit: HabitEntity): com.dayforge.domain.model.CountSummary =
        requireNotNull(countHistoryReader) { "COUNT_READER_REQUIRED" }.readSummary(habit)

    internal suspend fun legacyCountIdentity(habit: HabitEntity) = countActions?.legacy(habit)

    suspend fun getCheckHistory(habit: HabitEntity): com.dayforge.domain.model.CheckHistory =
        requireNotNull(checkHistoryReader) { "CHECK_READER_REQUIRED" }.read(habit)

    /** Finite ordinary CHECK action/display input; detailed history retains its original API. */
    suspend fun getCheckProjection(habit: HabitEntity): com.dayforge.domain.model.CheckDisplayProjection =
        requireNotNull(checkHistoryReader) { "CHECK_READER_REQUIRED" }.readProjection(habit)

    suspend fun getTimerHistory(habit: HabitEntity): com.dayforge.domain.model.TimerHistory =
        requireNotNull(timerHistoryReader) { "TIMER_READER_REQUIRED" }.read(habit)

    suspend fun getRecurringSnapshot(habit: HabitEntity): RecurringHabitSnapshot =
        requireNotNull(recurringHabitReader) { "RECURRING_READER_REQUIRED" }.read(habit)

    /** Capture BEFORE confirmation; typed plans never fall back to destructive history clearing. */
    suspend fun prepareReactivation(habit: HabitEntity): HabitReactivationRequest {
        if (habit.appearance == null) return HabitReactivationRequest(habit, null)
        require(habit.completionPolicy == "recurring" && habit.habitType != HabitType.GOAL &&
            (habit.targetCycles ?: 0) > 0) { "SYNC_RESTART_ACTIVITY_REQUIRED" }
        val displayed = getRecurringSnapshot(habit)
        require(displayed.authority.rounds != null) { "SYNC_RESTART_PROFILE_REQUIRED" }
        return HabitReactivationRequest(displayed.habit, displayed.authority)
    }

    suspend fun reactivate(request: HabitReactivationRequest, context: Context) {
        val committed = request.commit {
            if (request.preservesHistory) requireNotNull(restartEntry) { "SYNC_RESTART_ENTRY_REQUIRED" }
                .commit(request.habit, requireNotNull(request.authority))
            else if (request.legacyAccess != null) requireNotNull(widgetRestartEntry)
                .commitLegacy(request.habit, request.legacyAccess, requireNotNull(request.legacyIdentity))
            else {
                check(request.habit.appearance == null) { "SYNC_RESTART_PROFILE_REQUIRED" }
                clearHabitHistory(request.habit, context)
            }
        }
        if (committed && (request.preservesHistory || request.legacyAccess != null)) notifyWidgetUpdate(context)
    }

    suspend fun prepareWidgetReactivation(id: Long, fact: WidgetFactClaim?,
        timer: com.dayforge.domain.model.TimerActionAuthority?, legacy: LegacyWidgetRestartClaim?): HabitReactivationRequest =
        requireNotNull(widgetRestartEntry).prepare(id, fact, timer, legacy)

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

    /** Uncontended action-lock path only; legacy/once and increment retain their original preparation. */
    suspend fun prepareCompletion(id: Long, includeHistory: Boolean = true): CompletionActionSnapshot {
        if (!includeHistory) return CompletionActionSnapshot(getHabitForEditing(id))
        val row = habitDao.getVisibleHabitById(id)
        if (row?.appearance != null && row.completionPolicy == "recurring" &&
            row.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING)) {
            return requireNotNull(recurringHabitReader) { "RECURRING_READER_REQUIRED" }.prepareCompletion(id)
        }
        return CompletionActionSnapshot(getHabitForEditing(id))
    }

    /** Explicit CHECK consumer path; old complete-history callers retain prepareCompletion(). */
    suspend fun prepareCheckCompletionProjected(id: Long, includeHistory: Boolean = true): CompletionActionSnapshot {
        if (!includeHistory) return prepareCompletion(id, false)
        val row = habitDao.getVisibleHabitById(id)
        if (row?.habitType == HabitType.CHECK_IN && row.appearance != null && row.completionPolicy == "recurring")
            return requireNotNull(recurringHabitReader) { "RECURRING_READER_REQUIRED" }.prepareCompletion(id, preferCompact = true)
        return prepareCompletion(id, true)
    }

    suspend fun readCompletionAfter(captured: CompletionActionSnapshot,
        allowActivation: Boolean, recordedCompletionId: Long? = null): RecurringCompletionSnapshot =
        requireNotNull(recurringHabitReader) { "RECURRING_READER_REQUIRED" }
            .readCompletionAfter(captured, allowActivation, recordedCompletionId)

    suspend fun readCompletionAfterProjected(captured: CompletionActionSnapshot,
        allowActivation: Boolean, recordedCompletionId: Long? = null): RecurringCompletionSnapshot =
        requireNotNull(recurringHabitReader) { "RECURRING_READER_REQUIRED" }
            .readCompletionAfter(captured, allowActivation, recordedCompletionId, preferCompact = true)

    internal fun registerCompletionConsumer(listener: com.dayforge.data.local.PersonalDataDisplayMemory.Listener) =
        widgetFactReader?.registerCompletionConsumer(listener)

    internal suspend fun publishCompletion(snapshot: WidgetFactSnapshot, requireDay: Boolean = false,
        requireUnchangedProgress: Boolean = false, block: suspend () -> Unit) =
        requireNotNull(widgetFactReader) { "FACT_READER_REQUIRED" }
            .publishCompletion(snapshot, requireDay, requireUnchangedProgress, block)

    /** Goal edits retain their source fact/round and derive only the accepted plan successor. */
    suspend fun applyCompletionGoal(context: Context, snapshot: WidgetFactSnapshot, complete: Boolean): WidgetFactSnapshot {
        val reader = requireNotNull(widgetFactReader) { "FACT_READER_REQUIRED" }
        val before = reader.authorizeCompletion(snapshot.claim)
        val expected = requireNotNull(before.value)
        val successor = mutate(expected, requireNotNull(before.authority)) { habit ->
            val view = reader.requireCompletionInTransaction(snapshot.claim)
            check(habit.completionPolicy == "recurring" && habit.targetCycles != null &&
                view.targetProgress >= habit.targetCycles) { "COMPLETION_GOAL_CHANGED" }
            if (complete) habitDao.updateIsActive(habit.id, false)
            else if (habit.failMode == FailMode.STRICT) habitDao.updateFailMode(habit.id, FailMode.LOOSE)
            val current = requireNotNull(habitDao.getVisibleHabitById(habit.id))
            val allowed = if (complete) habit.copy(isActive = false) else habit.copy(failMode = FailMode.LOOSE)
            check(current.uuid == habit.uuid && NextStructureMapper.writePlan(current) == NextStructureMapper.writePlan(allowed)) {
                "COMPLETION_GOAL_SUCCESSOR_CHANGED"
            }
            view.copy(habit = current, claim = view.claim.copy(plan = NextStructureMapper.writePlan(current)),
                hasFailed = view.hasFailed && com.dayforge.domain.service.FailureCheckerUtils.shouldCheckFailure(current) &&
                    com.dayforge.domain.service.FailureCheckerUtils.isStrictMode(current))
        }
        // These are post-COMMIT side effects, matching the existing goal mutation behavior.
        com.dayforge.domain.service.afterCommittedAction {
            if (complete && expected.bestTime != null) HabitReminderScheduler.cancelReminder(context, expected.id)
            notifyWidgetUpdate(context)
        }
        return successor
    }

    /** Explicit display-time operation; a stale click must never become a fresh toggle. */
    internal suspend fun performWidgetFact(context: Context, claim: WidgetFactClaim, action: String): WidgetFactSnapshot =
        performWidgetFactCore(context, claim, action, commandId = null).value.current

    /** Top-level COUNT only. The original renderer/OS entry owns stable ID and interaction
     * admission; all authority, day, exact target and schedule checks remain in the original
     * writer. Ordinary internal/widget CHECK callers retain their existing participant path.
     */
    internal suspend fun performWidgetCountWithReceipt(context: Context, claim: WidgetFactClaim,
        action: String, commandId: String): CountMutationResult<WidgetCountReadResult> {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(com.dayforge.domain.model.isContractUuid(commandId) && claim.countPolicy != null &&
            action in setOf("increment", "undo")) { "COMMAND_WIDGET_COUNT_REQUIRED" }
        return performWidgetFactCore(context, claim, action, commandId)
    }

    internal suspend fun resumeWidgetCountCommand(context: Context, claim: WidgetFactClaim, action: String,
        commandId: String): WidgetCountCommandRecovery {
        CountCommandEntryBoundary.requireTopLevel(database)
        val reference = requireNotNull(nextObjectEditor).locateWidgetCommand(commandId, claim, action)
            ?: return WidgetCountCommandRecovery.Unresolved(commandId, "NO_COMPLETE_PERSISTENT_INTENT")
        val inspected = resumeCountCommand(context, reference)
        val resolved = if (inspected is CountCommandRecovery.Unresolved &&
            inspected.reason == "ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF")
            resumeCountCommandAfterCompleteProof(context, reference) else inspected
        return when (val result = resolved) {
            is CountCommandRecovery.HistoricalCommitted -> WidgetCountCommandRecovery.Committed(result.receipt)
            is CountCommandRecovery.Unresolved -> WidgetCountCommandRecovery.Unresolved(commandId, result.reason)
        }
    }

    private suspend fun performWidgetFactCore(context: Context, claim: WidgetFactClaim, action: String,
        commandId: String?): CountMutationResult<WidgetCountReadResult> {
        var command: CountCommandReceipt? = null
        val reader = requireNotNull(widgetFactReader)
        val before = reader.authorize(claim, exactFact = action != "increment")
        val habit = requireNotNull(before.value)
        if (commandId != null) require(habit.habitType == HabitType.COUNTING && habit.appearance != null &&
            habit.completionPolicy == "recurring") { "COMMAND_WIDGET_COUNT_REQUIRED" }
        if (habit.completionPolicy == "one_and_done") {
            require(action == "toggle")
            val complete = requireNotNull(claim.oneTimeState).completionEventUuid == null
            requireNotNull(oneTimeRepository).change(habit.id, complete, expectedUuid = habit.uuid,
                widgetClaim = claim, widgetReader = reader)
            notifyWidgetUpdate(context)
            return CountMutationResult(WidgetCountReadResult(reader.afterWrite(claim), null), null)
        }
        when (action) {
            "toggle" -> {
                require(habit.habitType == HabitType.CHECK_IN)
                if (claim.completionUuid == null) logCompletion(context, habit.id, authority = before.authority,
                    expectedHabitUuid = habit.uuid, widgetClaim = claim)
                else undoCompletion(context, requireNotNull(completionDao.getCompletionByUuid(claim.completionUuid)).id,
                    authority = before.authority, widgetClaim = claim)
            }
            "increment" -> {
                require(habit.habitType == HabitType.COUNTING)
                if (commandId == null) logCompletion(context, habit.id, authority = before.authority,
                    expectedHabitUuid = habit.uuid, widgetClaim = claim)
                else command = logCompletionWithReceipt(context, habit.id, authority = before.authority,
                    expectedHabitUuid = habit.uuid, widgetClaim = claim, commandId = commandId).command
            }
            "undo" -> {
                require(habit.habitType == HabitType.COUNTING && claim.completionUuid != null)
                val target = requireNotNull(completionDao.getCompletionByUuid(claim.completionUuid))
                if (commandId == null) undoCompletion(context, target.id, authority = before.authority, widgetClaim = claim)
                else command = undoCompletionWithReceipt(context, target.id, authority = before.authority,
                    widgetClaim = claim, commandId = commandId).command
            }
            else -> error("FACT_WIDGET_UNKNOWN_ACTION")
        }
        val current = if (commandId == null) WidgetCountReadResult(reader.afterWrite(claim), null) else {
            val committed = requireNotNull(command) { "COMMAND_END_NOT_CONFIRMED" }
            reader.afterCountCommand(claim, committed.factUuid.takeIf { action == "increment" && committed.recorded })
        }
        return CountMutationResult(current, command)
    }

    /** Goal confirmation uses the post-write display claim, not a newly selected owner or plan. */
    suspend fun applyWidgetGoal(context: Context, claim: WidgetFactClaim, complete: Boolean) =
        applyWidgetGoalInternal(context, claim, complete, null)

    internal suspend fun applyWidgetGoalWithCommand(context: Context, claim: WidgetFactClaim, complete: Boolean,
        command: CountCommandReceipt) = applyWidgetGoalInternal(context, claim, complete, command)

    private suspend fun applyWidgetGoalInternal(context: Context, claim: WidgetFactClaim, complete: Boolean,
        command: CountCommandReceipt?) {
        val reader = requireNotNull(widgetFactReader)
        val before = reader.authorize(claim)
        val expected = requireNotNull(before.value)
        mutate(expected, before.authority) { habit ->
            command?.let { requireNotNull(nextObjectEditor).requirePendingGoalInTransaction(it, habit.uuid) }
            val view = reader.requireInTransaction(claim)
            val progress = view.targetProgress
            check(habit.completionPolicy == "recurring" && habit.targetCycles != null && progress >= habit.targetCycles) {
                "FACT_WIDGET_GOAL_CHANGED"
            }
            if (complete) habitDao.updateIsActive(habit.id, false)
            else if (habit.failMode == FailMode.STRICT) habitDao.updateFailMode(habit.id, FailMode.LOOSE)
        }
        // Preserve updateIsActive's post-COMMIT reminder side effect. The facade re-reads the
        // current owner/row, so a late callback cannot cancel another account's reused local ID.
        if (complete && expected.bestTime != null) HabitReminderScheduler.cancelReminder(context, claim.habitId)
        notifyWidgetUpdate(context)
    }

    /** A completed timer's goal edits the displayed plan, never the current numeric ID's new plan. */
    suspend fun applyTimerGoal(context: Context, claim: TimerCompletionFollowUpClaim, complete: Boolean) {
        val writer = requireNotNull(timerWriter)
        val captured = writer.authorizeGoal(claim)
        val expected = requireNotNull(captured.value)
        mutate(expected, requireNotNull(captured.authority)) { habit ->
            val view = writer.completionInTransaction(habit.id, claim.source, claim.displayedPlan)
            check(view.goalReached) { "TIMER_GOAL_CHANGED" }
            val access = writer.requireCompletionAccessInTransaction(claim.source, "structure.write")
            if (complete) habitDao.updateIsActive(habit.id, false)
            else if (habit.failMode == FailMode.STRICT) habitDao.updateFailMode(habit.id, FailMode.LOOSE)
            check(writer.requireCompletionAccessInTransaction(claim.source, "structure.write") == access)
        }
        if (complete && expected.bestTime != null) HabitReminderScheduler.cancelReminder(context, expected.id)
        notifyWidgetUpdate(context)
    }

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
        creationAuthority: ObjectCreationAuthority? = null,
        expectedMetricUuids: Map<Long, String>? = null
    ): Long {
        val selected = selectedMetricIds.toSet()
        val metricIdentities = expectedMetricUuids?.toMap()
        require(metricIdentities == null || metricIdentities.keys == selected) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        val draft = HabitDraft(id = predefinedUuid ?: java.util.UUID.randomUUID().toString(),
            name = name, description = description, habitType = habitType, iconResId = iconResId,
            colorHex = colorHex, schedule = schedule, targetValue = targetValue, isCountdown = isCountdown,
            targetCycles = targetCycles, failMode = failMode, bestTime = bestTime,
            selectedMetricIds = selected, appearance = appearance, completionPolicy = completionPolicy)
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
            insertHabit(habit, selected, metricIdentities)
        } else database.withTransaction {
            if (nextObjectCreator != null) NextProtocolAdmission.requireLegacyCreation(database)
            insertHabit(habit, selected, metricIdentities)
        }
        // Side effects only after the complete business/original intent transaction commits.
        context?.let { notifyWidgetUpdate(it) }
        if (habit.bestTime != null && habit.habitType != HabitType.GOAL) {
            context?.let { HabitReminderScheduler.scheduleReminder(it, id) }
        }
        return id
    }

    private suspend fun insertHabit(habit: HabitEntity, selectedMetricIds: Set<Long>, expectedMetricUuids: Map<Long, String>? = null): Long {
        require(expectedMetricUuids == null || expectedMetricUuids.keys == selectedMetricIds) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        requireValidHierarchy(habit)
        require(habitDao.getHabitByUuid(habit.uuid) == null) { "OBJECT_CREATE_ID_REUSED" }
        val habitId = habitDao.insert(habit)
        selectedMetricIds.forEach { metricId ->
                val metric = requireNotNull(database.metricDao().getVisibleMetricById(metricId)) {
                    "Selected metric no longer exists: $metricId"
                }
                if (expectedMetricUuids != null) check(metric.uuid == expectedMetricUuids.getValue(metricId)) { "OBJECT_WRITE_LINK_CHANGED" }
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
        creationAuthority: ObjectCreationAuthority? = null, expectedMetricUuids: Map<Long, String>? = null): Long {
        val frozenChildren = children.map { it.copy(selectedMetricIds = it.selectedMetricIds.toSet()) }
        val selected = frozenChildren.flatMap { it.selectedMetricIds }.toSet()
        val metricIdentities = expectedMetricUuids?.toMap()
        require(metricIdentities == null || metricIdentities.keys == selected) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        require(goal.habitType == HabitType.GOAL && goal.selectedMetricIds.isEmpty())
        require(frozenChildren.all { it.habitType != HabitType.GOAL })
        require((listOf(goal.id) + frozenChildren.map { it.id }).distinct().size == frozenChildren.size + 1)
        fun row(draft: HabitDraft, parent: String?): HabitEntity = if (creationAuthority != null)
            requireNotNull(nextObjectCreator).habit(draft, parent, creationAuthority) else {
                require(draft.appearance == null && draft.completionPolicy == null && draft.schedule !is HabitSchedule.Once)
                HabitEntity(uuid = draft.id, name = draft.name, description = draft.description, habitType = draft.habitType,
                    iconResId = draft.iconResId, colorHex = draft.colorHex, schedule = draft.schedule,
                    targetValue = draft.targetValue, isCountdown = draft.isCountdown, parentHabitId = parent,
                    targetCycles = draft.targetCycles, failMode = draft.failMode, bestTime = draft.bestTime)
            }
        val rows = listOf(row(goal, null)) + frozenChildren.map { row(it, goal.id) }
        suspend fun commit(): List<HabitEntity> {
            // Also applies to the original idempotent existing-goal return. Same numeric
            // IDs in this storage are not permission to attach a replacement metric.
            metricIdentities?.forEach { (id, uuid) ->
                check(database.metricDao().getVisibleMetricById(id)?.uuid == uuid) { "OBJECT_WRITE_LINK_CHANGED" }
            }
            // A restored draft may have committed immediately before process death.
            // Stable UUIDs make retrying the complete save safe without replacing rows.
            val existing = habitDao.getHabitByUuid(goal.id)
            if (existing != null) {
                require(existing.habitType == HabitType.GOAL && existing.parentHabitId == null)
                require(frozenChildren.all { habitDao.getHabitByUuid(it.id)?.parentHabitId == goal.id })
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
                        require(links.toSet() == (listOf(goal) + frozenChildren).single { it.id == old.uuid }.selectedMetricIds) {
                            "OBJECT_CREATE_ID_REUSED"
                        }
                    }
                    return actual
                }
                return listOf(existing) + habitDao.getChildrenByParentUuidOnce(goal.id)
            }
            suspend fun insert(draft: HabitDraft, parent: String?): HabitEntity {
                val id = insertHabit(rows.single { it.uuid == draft.id && it.parentHabitId == parent }, draft.selectedMetricIds,
                    metricIdentities?.filterKeys { it in draft.selectedMetricIds })
                return requireNotNull(habitDao.getHabitById(id))
            }
            return listOf(insert(goal, null)) + frozenChildren.map { insert(it, goal.id) }
        }
        val saved = if (creationAuthority != null) requireNotNull(nextObjectCreator).habits(rows, creationAuthority, ::commit)
            else database.withTransaction {
                if (nextObjectCreator != null) NextProtocolAdmission.requireLegacyCreation(database)
                commit()
            }
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
        editAuthority: ObjectEditAuthority? = null,
        expectedMetricUuids: Map<Long, String>? = null
    ) {
        val selected = selectedMetricIds?.toSet()
        val metricIdentities = expectedMetricUuids?.toMap()
        require(metricIdentities == null || selected != null && metricIdentities.keys == selected) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        if (habit.appearance == null) structuralEditGuard?.requireAllowed()
        var persistedHabit = habit.copy(updatedAt = System.currentTimeMillis())
        suspend fun commit(saved: HabitEntity): HabitEntity {
            val previous = requireNotNull(habitDao.getHabitById(habit.id)) {
                "Habit no longer exists: ${habit.id}"
            }
            check(previous.uuid == habit.uuid && saved.uuid == habit.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            check((previous.appearance == null) == (saved.appearance == null)) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
            requireValidHierarchy(saved)
            habitDao.update(saved)
            selected?.let { reconcileMetricLinks(saved, it, metricIdentities) }
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

    private suspend fun reconcileMetricLinks(habit: HabitEntity, selectedMetricIds: Set<Long>, expectedMetricUuids: Map<Long, String>?) {
        // The full caller selection is rechecked inside the same actual transaction as all
        // removals/inserts, including already-existing links. A local ID is not identity.
        expectedMetricUuids?.forEach { (id, uuid) ->
            check(database.metricDao().getVisibleMetricById(id)?.uuid == uuid) { "OBJECT_WRITE_LINK_CHANGED" }
        }
        val linkDao = database.habitMetricLinkDao()
        val existingLinks = linkDao.getAllLinksForHabit(habit.id)
        existingLinks
            .filter { it.metricId !in selectedMetricIds }
            .forEach {
                if (habit.appearance != null) NextMetricDeletionStore(database).stageLink(it)
                else linkDao.delete(it)
            }

        existingLinks.filter { it.metricId in selectedMetricIds }.forEach {
            NextMetricDeletionStore(database).requireLinkWritable(it)
        }

        val existingMetricIds = existingLinks.mapTo(mutableSetOf()) { it.metricId }
        selectedMetricIds
            .filterNot { it in existingMetricIds }
            .forEach { metricId ->
                val metric = requireNotNull(database.metricDao().getVisibleMetricById(metricId)) {
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
        database.withTransaction {
            requireLegacyHabitIdentity(habit)
            habitDao.delete(habit)
        }
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
            requireLegacyHabitIdentity(habit)
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
            requireLegacyHabitIdentity(habit)
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

    /** Missing rows retain the old DELETE/no-op contract; a replacement row does not. */
    private suspend fun requireLegacyHabitIdentity(expected: HabitEntity) {
        check(database.inTransaction())
        habitDao.getHabitById(expected.id)?.let { current ->
            check(current.uuid == expected.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            check(current.appearance == null) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
        }
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
        context: Context,
        expectedHabitUuid: String? = null
    ) {
        val habit = habitDao.getHabitById(habitId)
        if (expectedHabitUuid != null) check(habit?.uuid == expectedHabitUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (habit?.appearance != null && nextObjectEditor != null) {
            val captured = nextObjectEditor.habit(habitId)
            val row = requireNotNull(captured.value) { "OBJECT_EDIT_NOT_FOUND" }
            check(row.uuid == habit.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            nextObjectEditor.editHabit(row.copy(isActive = isActive), requireNotNull(captured.authority)) {
                habitDao.update(it)
            }
        } else {
            structuralEditGuard?.requireAllowed()
            database.withTransaction {
                val current = habitDao.getHabitById(habitId)
                check(current?.uuid == habit?.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
                check(current?.appearance == null) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
                habitDao.updateIsActive(habitId, isActive)
            }
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
        context: Context,
        expectedHabitUuid: String? = null
    ) {
        val habit = requireNotNull(habitDao.getHabitById(habitId))
        if (expectedHabitUuid != null) check(habit.uuid == expectedHabitUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
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
     * @return The ID of the inserted completion, or 0 when a countdown is already complete.
     */
    /** Historic ordinary participant: may join a caller's Room transaction. Its value is
     * not a durable command receipt; top-level human COUNT actions use the receipt entry.
     */
    suspend fun logCompletion(context: Context, habitId: Long, value: Int = 1,
        authority: ObjectEditAuthority? = null, expectedHabitUuid: String? = null,
        widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null): Long = logCompletionCore(context, habitId, value, authority, expectedHabitUuid,
        widgetClaim, countIdentity, checkIdentity, preparedCommand = null, durableCommand = false).value


    internal suspend fun logCompletionWithReceipt(context: Context, habitId: Long, value: Int = 1,
        authority: ObjectEditAuthority? = null, expectedHabitUuid: String? = null,
        widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
        preparedCommand: NextObjectEditor.CountAttempt? = null, commandId: String? = null): CountMutationResult<Long> {
        try {
            CountCommandEntryBoundary.requireTopLevel(database)
            return logCompletionCore(context, habitId, value, authority, expectedHabitUuid,
        widgetClaim, countIdentity, checkIdentity, preparedCommand, durableCommand = true, commandId = commandId)
        } finally { preparedCommand?.let { requireNotNull(nextObjectEditor).releaseUnexecuted(it) } }
    }

    private suspend fun logCompletionCore(context: Context, habitId: Long, value: Int = 1,
        authority: ObjectEditAuthority? = null, expectedHabitUuid: String? = null,
        widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
        preparedCommand: NextObjectEditor.CountAttempt?, durableCommand: Boolean,
        preparedLegacy: CountCommandLegacyExecutor.Attempt? = null, commandId: String? = null): CountMutationResult<Long> {
        var acquiredCommand = preparedCommand
        var acquiredLegacy = preparedLegacy
        try {
        check(preparedCommand == null || preparedLegacy == null)
        require(commandId == null || durableCommand && preparedCommand == null && preparedLegacy == null &&
            widgetClaim != null && com.dayforge.domain.model.isContractUuid(commandId)) { "COMMAND_WIDGET_ID_REQUIRED" }
        check(durableCommand || preparedCommand == null && preparedLegacy == null) { "COMMAND_ORDINARY_PARTICIPANT_CANNOT_OWN_TICKET" }
        val capturedHabit = habitDao.getHabitById(habitId)
        val legacyFrozen = preparedLegacy?.input?.body()?.getValue("input")?.jsonObject?.also {
            require(CountCommandRecords.integer(it, "habit_id") == habitId && CountCommandRecords.integer(it, "quantity") == value.toLong() &&
                capturedHabit?.uuid == CountCommandRecords.uuid(it, "habit_uuid") && capturedHabit.appearance == null && widgetClaim == null) {
                "COMMAND_ORIGINAL_INPUT_CHANGED"
            }
        }
        val frozen = preparedCommand?.input?.let { intent ->
            require(intent.action == CountCommandRecords.Action.COUNT_ADD && intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT)
            intent.body().getValue("input").jsonObject.also { input ->
                require(CountCommandRecords.integer(input, "habit_id") == habitId && CountCommandRecords.integer(input, "quantity") == value.toLong() &&
                    capturedHabit?.uuid == CountCommandRecords.uuid(input, "habit_uuid") && capturedHabit.habitType == HabitType.COUNTING &&
                    capturedHabit.appearance != null && capturedHabit.completionPolicy == "recurring") { "COMMAND_ORIGINAL_INPUT_CHANGED" }
                require(CountCommandInputs.restoredWidget(input) == widgetClaim) { "COMMAND_ORIGINAL_WIDGET_CLAIM_CHANGED" }
            }
        }
        if (expectedHabitUuid != null) check(capturedHabit?.uuid == expectedHabitUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        check(checkIdentity == null || countIdentity == null) { "CHECK_ACTION_EXPIRED" }
        val checkAuthority = checkIdentity?.let { identity ->
            check(capturedHabit?.habitType == HabitType.CHECK_IN && capturedHabit.appearance != null &&
                capturedHabit.completionPolicy == "recurring" && identity.habitId == habitId &&
                identity.habitUuid == capturedHabit.uuid) { "CHECK_ACTION_EXPIRED" }
            (authority ?: requireNotNull(getHabitForEditing(habitId).authority)).also(identity::requireAuthority)
        }
        val countAuthority = if (countIdentity != null) {
            check(capturedHabit?.habitType == HabitType.COUNTING && countIdentity.habitId == habitId &&
                countIdentity.habitUuid == capturedHabit.uuid) { "COUNT_ACTION_EXPIRED" }
            if (capturedHabit.appearance != null) (authority ?: requireNotNull(getHabitForEditing(habitId).authority))
                .also(countIdentity::requireAuthority) else null
        } else checkAuthority ?: authority
        if (capturedHabit?.completionPolicy == "one_and_done") {
            require(value == 1) { "ONE_TIME_VALUE_MUST_BE_ONE" }
            val id = requireNotNull(oneTimeRepository).change(habitId, complete = true, expectedUuid = capturedHabit.uuid,
                widgetClaim = widgetClaim, widgetReader = widgetFactReader)
            notifyWidgetUpdate(context)
            return CountMutationResult(id, null)
        }
        val occurredAt = (frozen ?: legacyFrozen)?.let { java.time.Instant.parse(CountCommandRecords.text(it, "occurred_at")) } ?: java.time.Instant.now()
        val capturedZone = (frozen ?: legacyFrozen)?.let { java.time.ZoneId.of(CountCommandRecords.text(it, "timezone")) } ?: java.time.ZoneId.systemDefault()
        val countCommand = preparedCommand ?: if (durableCommand && capturedHabit?.habitType == HabitType.COUNTING && capturedHabit.appearance != null && nextObjectEditor != null)
            nextObjectEditor.beginCountAdd(capturedHabit, countAuthority, occurredAt, capturedZone, value, widgetClaim = widgetClaim, commandId = commandId) else null
        acquiredCommand = countCommand
        if (durableCommand && capturedHabit?.habitType == HabitType.COUNTING && capturedHabit.appearance == null && nextObjectEditor != null) {
            check(countAuthority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            if (!capturedHabit.isActive) structuralEditGuard?.requireAllowed()
        }
        val legacyCommand = preparedLegacy ?: if (durableCommand && capturedHabit?.habitType == HabitType.COUNTING &&
            capturedHabit.appearance == null && nextObjectEditor != null) {
            check(widgetClaim == null) { "COMMAND_LEGACY_WIDGET_REQUIRES_ORIGINAL_HANDLER" }
            nextObjectEditor.beginLegacyCountAdd(capturedHabit, countIdentity, occurredAt, capturedZone, value)
        } else null
        acquiredLegacy = legacyCommand
        // Historic ordinary participants keep their original optional A/B/C applicability,
        // including inherited Room transactions. They never fabricate a durable receipt.
        var ordinaryConstructedFact: CompletionEntity? = null
        if (!durableCommand && value == 1 && widgetClaim == null && checkIdentity == null && countAuthority?.rounds != null &&
            capturedHabit?.habitType == HabitType.COUNTING && nextObjectEditor != null) {
            when (val selected = nextObjectEditor.addCountPrepared(capturedHabit, countAuthority, countIdentity, occurredAt, capturedZone)) {
                is CountAddExecutor.Result.Committed -> {
                    if (selected.id != 0L) notifyWidgetUpdate(context)
                    return CountMutationResult(selected.id, null)
                }
                is CountAddExecutor.Result.Original -> ordinaryConstructedFact = selected.constructedFact
            }
        }
        val original: suspend (HabitEntity) -> Long = mutation@ { habit ->
            if (widgetClaim != null) {
                val view = requireNotNull(widgetFactReader).requireInTransaction(widgetClaim,
                    exactFact = habit.habitType != HabitType.COUNTING)
                check(com.dayforge.domain.service.ScheduleValidator.isCheckInAllowedToday(habit.schedule, habit.createdAt)) {
                    "FACT_WIDGET_NOT_SCHEDULED"
                }
                check(occurredAt.atZone(capturedZone).toLocalDate().toString() == widgetClaim.date &&
                    capturedZone.id == widgetClaim.timezone) { "FACT_WIDGET_DAY_CHANGED" }
                view.count?.let { history ->
                    val policy = requireNotNull(history.todayPolicy)
                    check(!policy.isCountdown || history.todayQuantity < policy.targetValue) { "COUNT_DAY_ALREADY_COMPLETED" }
                }
            }
            // Enforce the effective day's rule in the same owner/Room transaction as the fact.
            // UI queues and other entry points must not overrun a completed countdown.
            if (habit.habitType == HabitType.COUNTING) {
                val date = occurredAt.atZone(capturedZone).toLocalDate()
                val policy = (if (habit.appearance != null)
                    NextCountDayStore(database).read(habit, date.toString())?.policy else null)
                    ?: com.dayforge.domain.model.CountDayPolicy(habit.targetValue, habit.isCountdown)
                if (policy.isCountdown) {
                    val history = if (habit.appearance != null)
                        requireNotNull(countHistoryReader).readSummaryInTransaction(habit, date) else null
                    if (habit.appearance != null) requireNotNull(history?.todayPolicy) { "COUNT_DAY_RULE_UNKNOWN" }
                    val quantity = history?.todayQuantity ?: completionDao.getCompletionsInRange(habit.id, date, date.plusDays(1))
                        .sumOf { it.value.toLong() }
                    if (quantity >= policy.targetValue) {
                        NextCoreLocalIntentStore.prepareCommandNoEffect(database, CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE)
                        CountCommandLegacyExecutor.allocation(database)?.noEffect(CountCommandRecords.NoEffectReason.COUNTDOWN_COMPLETE)
                        return@mutation 0L
                    }
                }
            }
            insertCompletionInTransaction(habit, occurredAt, capturedZone, value, ordinaryConstructedFact)
        }

        val id = if (countCommand != null) requireNotNull(nextObjectEditor).mutateCountAddCommand(
            requireNotNull(capturedHabit), countIdentity, countCommand, occurredAt, capturedZone,
            allowPrepared = countAuthority?.rounds != null, commit = original)
        else if (legacyCommand != null) requireNotNull(nextObjectEditor).mutateLegacyCount(legacyCommand, requireNotNull(capturedHabit), original)
        else mutate(requireNotNull(capturedHabit), countAuthority, structural = !capturedHabit.isActive,
            countIdentity = countIdentity, checkIdentity = checkIdentity, commit = original)

        // Notify widgets to update
        if (id != 0L) notifyWidgetUpdate(context)

        // Completion and any reactivation are both captured by the v2 outbox.

        return CountMutationResult(id, countCommand?.committedReceipt() ?: legacyCommand?.receipt())
        } finally {
            acquiredCommand?.let { requireNotNull(nextObjectEditor).releaseUnexecuted(it) }
            acquiredLegacy?.let { requireNotNull(nextObjectEditor).releaseLegacyCount(it) }
        }
    }

    /** Called only for the Service's existing no-target branch after its original summary
     * and identity checks. It cannot turn a later new count into an undo target.
     */
    internal suspend fun recordNoCurrentCount(captured: CompletionActionSnapshot,
        observed: com.dayforge.domain.model.CountSummary): CountCommandReceipt {
        val habit = requireNotNull(captured.edit.value)
        return requireNotNull(nextObjectEditor).recordNoCurrentCount(habit, requireNotNull(captured.edit.authority), observed)
    }

    /**
     * Undoes a completion by deleting it.
     * @param context Context for scheduling widget refresh
     * @param completionId The ID of the completion to delete
     */
    suspend fun undoCompletion(context: Context, completionId: Long,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        authority: ObjectEditAuthority? = null, widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null) {
        undoCompletionCore(context, completionId, oneTimeAuthority, authority, widgetClaim, countIdentity, checkIdentity,
            preparedCommand = null, durableCommand = false)
    }


    internal suspend fun undoCompletionWithReceipt(context: Context, completionId: Long,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        authority: ObjectEditAuthority? = null, widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
        preparedCommand: NextObjectEditor.CountAttempt? = null, commandId: String? = null): CountMutationResult<Unit> {
        try {
            CountCommandEntryBoundary.requireTopLevel(database)
            return undoCompletionCore(context, completionId, oneTimeAuthority, authority, widgetClaim,
        countIdentity, checkIdentity, preparedCommand, durableCommand = true, commandId = commandId)
        } finally { preparedCommand?.let { requireNotNull(nextObjectEditor).releaseUnexecuted(it) } }
    }

    private suspend fun undoCompletionCore(context: Context, completionId: Long,
        oneTimeAuthority: com.dayforge.domain.model.OneTimeActionAuthority? = null,
        authority: ObjectEditAuthority? = null, widgetClaim: WidgetFactClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null,
        preparedCommand: NextObjectEditor.CountAttempt?, durableCommand: Boolean,
        preparedLegacy: CountCommandLegacyExecutor.Attempt? = null, commandId: String? = null): CountMutationResult<Unit> {
        var acquiredCommand = preparedCommand
        var acquiredLegacy = preparedLegacy
        try {
        check(preparedCommand == null || preparedLegacy == null)
        require(commandId == null || durableCommand && preparedCommand == null && preparedLegacy == null &&
            widgetClaim != null && com.dayforge.domain.model.isContractUuid(commandId)) { "COMMAND_WIDGET_ID_REQUIRED" }
        check(durableCommand || preparedCommand == null && preparedLegacy == null) { "COMMAND_ORDINARY_PARTICIPANT_CANNOT_OWN_TICKET" }
        check(checkIdentity == null || countIdentity == null && oneTimeAuthority == null) { "CHECK_ACTION_EXPIRED" }
        val initial = preparedCommand?.input?.let { intent ->
            require(intent.action == CountCommandRecords.Action.COUNT_UNDO && intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT)
            val input = intent.body().getValue("input").jsonObject
            require(CountCommandInputs.restoredWidget(input) == widgetClaim) { "COMMAND_ORIGINAL_WIDGET_CLAIM_CHANGED" }
            CountCommandProducerPlan.restoredCompletion(input.getValue("target_native").jsonObject).also { target ->
                require(target.id == completionId && target.uuid == CountCommandRecords.uuid(input, "target_fact_uuid") &&
                    target.habitId == CountCommandRecords.integer(input, "habit_id") && target.habitUuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                    target.oneTimeAction == null) { "COMMAND_ORIGINAL_UNDO_TARGET_CHANGED" }
            }
        } ?: preparedLegacy?.input?.body()?.getValue("input")?.jsonObject?.let {
            CountCommandProducerPlan.restoredCompletion(it.getValue("target_native").jsonObject).also { target ->
                require(target.id == completionId && target.uuid == CountCommandRecords.uuid(it, "target_fact_uuid") &&
                    target.habitId == CountCommandRecords.integer(it, "habit_id") && widgetClaim == null) { "COMMAND_ORIGINAL_UNDO_TARGET_CHANGED" }
            }
        } ?: completionDao.getCompletionById(completionId)
        if (oneTimeAuthority != null) check(initial?.oneTimeAction == "complete") { "ONE_TIME_ACTION_EXPIRED" }
        if (initial?.oneTimeAction != null) {
            check(checkIdentity == null) { "CHECK_ACTION_EXPIRED" }
            require(initial.oneTimeAction == "complete") { "ONE_TIME_ONLY_COMPLETION_CAN_BE_UNDONE" }
            requireNotNull(oneTimeRepository).change(initial.habitId, complete = false,
                expectedCompletionId = completionId, expectedUuid = initial.habitUuid, authority = oneTimeAuthority,
                widgetClaim = widgetClaim, widgetReader = widgetFactReader)
            notifyWidgetUpdate(context)
            return CountMutationResult(Unit, null)
        }
        if (initial == null) {
            check(widgetClaim == null) { "FACT_WIDGET_FACT_CHANGED" }
            // Preserve the original lookup's completed no-effect decision. A later same-ID
            // row cannot become this command's target. Non-COUNT legacy APIs remain unchanged.
            val receipt = if (durableCommand && countIdentity != null && nextObjectEditor != null)
                nextObjectEditor.recordExplicitMissingCount(completionId, countIdentity) else null
            return CountMutationResult(Unit, receipt)
        }
        val expected = requireNotNull(habitDao.getHabitById(initial.habitId))
        val checkAuthority = checkIdentity?.let { identity ->
            check(expected.habitType == HabitType.CHECK_IN && expected.appearance != null &&
                expected.completionPolicy == "recurring" && identity.habitId == expected.id &&
                identity.habitUuid == expected.uuid) { "CHECK_ACTION_EXPIRED" }
            (authority ?: requireNotNull(getHabitForEditing(expected.id).authority)).also(identity::requireAuthority)
        }
        val countAuthority = if (countIdentity != null) {
            check(expected.habitType == HabitType.COUNTING && countIdentity.habitId == initial.habitId &&
                countIdentity.habitUuid == expected.uuid) { "COUNT_ACTION_EXPIRED" }
            if (expected.appearance != null) (authority ?: requireNotNull(getHabitForEditing(expected.id).authority))
                .also(countIdentity::requireAuthority) else null
        } else checkAuthority ?: authority
        if (expected.appearance != null) check(initial.habitUuid == expected.uuid) { "OBJECT_WRITE_FACT_CHANGED" }
        val countCommand = preparedCommand ?: if (durableCommand && expected.habitType == HabitType.COUNTING && expected.appearance != null && nextObjectEditor != null)
            nextObjectEditor.beginCountUndo(expected, countAuthority, initial, widgetClaim = widgetClaim, commandId = commandId) else null
        acquiredCommand = countCommand
        if (durableCommand && expected.habitType == HabitType.COUNTING && expected.appearance == null && nextObjectEditor != null)
            check(countAuthority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        val legacyCommand = preparedLegacy ?: if (durableCommand && expected.habitType == HabitType.COUNTING &&
            expected.appearance == null && nextObjectEditor != null) {
            check(widgetClaim == null) { "COMMAND_LEGACY_WIDGET_REQUIRES_ORIGINAL_HANDLER" }
            nextObjectEditor.beginLegacyCountUndo(expected, countIdentity, initial)
        } else null
        acquiredLegacy = legacyCommand
        val original: suspend (HabitEntity) -> Unit = mutation@ {
            if (widgetClaim != null) {
                requireNotNull(widgetFactReader).requireInTransaction(widgetClaim)
                check(initial.uuid == widgetClaim.completionUuid) { "FACT_WIDGET_FACT_CHANGED" }
            }
            val completion = completionDao.getCompletionById(completionId)
            // Ordinary undo has always been a no-op when another click already removed it.
            if (completion == null) {
                NextCoreLocalIntentStore.prepareCommandNoEffect(database, CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION)
                CountCommandLegacyExecutor.allocation(database)?.noEffect(CountCommandRecords.NoEffectReason.TARGET_REMOVED_BEFORE_MUTATION)
                return@mutation
            }
            check(completion == initial) { "OBJECT_WRITE_FACT_CHANGED" }
            NextCoreLocalIntentStore.requireCommandUndoTarget(database, completion)
            CountCommandLegacyExecutor.allocation(database)?.selected(completion)

            // A fact cannot be mutated remotely; the outbox converts this delete to a
            // new immutable revert event when the original event was already synced.
            completionDao.delete(initial)
            updateActivityRate(initial.habitId)
        }

        if (legacyCommand != null) requireNotNull(nextObjectEditor).mutateLegacyCount(legacyCommand, expected, original)
        else mutate(expected, countAuthority, structural = false, countIdentity = countIdentity, checkIdentity = checkIdentity,
            command = countCommand, commit = original)

        // Notify widgets
        notifyWidgetUpdate(context)
        return CountMutationResult(Unit, countCommand?.committedReceipt() ?: legacyCommand?.receipt())
        } finally {
            acquiredCommand?.let { requireNotNull(nextObjectEditor).releaseUnexecuted(it) }
            acquiredLegacy?.let { requireNotNull(nextObjectEditor).releaseLegacyCount(it) }
        }
    }

    /** Closed replay of an already durable original COUNT Intent. A recovery miss never
     * creates a new ID, time, target, input or command; original business validation and the
     * private producer still decide whether this exact operation can execute now.
     */
    suspend fun resumeCountCommand(context: Context, reference: CountCommandReference): CountCommandRecovery =
        resumeCountCommandInternal(context, reference, completeProof = false)

    internal suspend fun resumeCountCommandAfterCompleteProof(context: Context, reference: CountCommandReference): CountCommandRecovery =
        resumeCountCommandInternal(context, reference, completeProof = true)

    internal suspend fun resumeCountCommandAfterReauthentication(context: Context, reference: CountCommandReference): CountCommandRecovery =
        resumeCountCommandInternal(context, reference, completeProof = true, reauthenticate = true)

    private suspend fun resumeCountCommandInternal(context: Context, reference: CountCommandReference,
        completeProof: Boolean, reauthenticate: Boolean = false): CountCommandRecovery {
        val editor = requireNotNull(nextObjectEditor)
        val actions = setOf(CountCommandRecords.Action.COUNT_ADD, CountCommandRecords.Action.COUNT_UNDO)
        val inspected = if (reauthenticate) editor.recoverCountCommandAfterReauthentication(reference)
            else if (completeProof) editor.recoverCommandAfterCompleteProof(reference, actions)
            else editor.recoverCommand(reference, actions)
        return when (val recovered = inspected) {
            is NextObjectEditor.RecoveryAttempt.Historical -> CountCommandRecovery.HistoricalCommitted(recovered.receipt)
            is NextObjectEditor.RecoveryAttempt.LegacyMetricExecute -> error("COMMAND_REQUIRES_METRIC_HANDLER")
            is NextObjectEditor.RecoveryAttempt.Unresolved -> CountCommandRecovery.Unresolved(reference, recovered.reason)
            is NextObjectEditor.RecoveryAttempt.LegacyExecute -> {
                val command = recovered.command; val input = command.input.body().getValue("input").jsonObject
                val receipt = when (command.input.action) {
                    CountCommandRecords.Action.COUNT_ADD -> logCompletionCore(context, CountCommandRecords.integer(input, "habit_id"),
                        CountCommandRecords.integer(input, "quantity").toInt(), countIdentity = command.identity,
                        preparedCommand = null, durableCommand = true, preparedLegacy = command).command
                    CountCommandRecords.Action.COUNT_UNDO -> undoCompletionCore(context,
                        CountCommandRecords.integer(input.getValue("target_native").jsonObject, "id"), countIdentity = command.identity,
                        preparedCommand = null, durableCommand = true, preparedLegacy = command).command
                    else -> error("COMMAND_REQUIRES_ORIGINAL_DOMAIN_HANDLER")
                }
                CountCommandRecovery.HistoricalCommitted(requireNotNull(receipt))
            }
            is NextObjectEditor.RecoveryAttempt.Execute -> {
                val command = recovered.command; val input = command.input.body().getValue("input").jsonObject
                try {
                    val receipt = when (command.input.action) {
                        CountCommandRecords.Action.COUNT_ADD -> logCompletionWithReceipt(context, CountCommandRecords.integer(input, "habit_id"),
                            CountCommandRecords.integer(input, "quantity").toInt(), authority = command.authority,
                            expectedHabitUuid = CountCommandRecords.uuid(input, "habit_uuid"), widgetClaim = CountCommandInputs.restoredWidget(input),
                            preparedCommand = command).command
                        CountCommandRecords.Action.COUNT_UNDO -> undoCompletionWithReceipt(context,
                            CountCommandRecords.integer(input.getValue("target_native").jsonObject, "id"), authority = command.authority,
                            widgetClaim = CountCommandInputs.restoredWidget(input), preparedCommand = command).command
                        else -> error("COMMAND_REQUIRES_ORIGINAL_DOMAIN_HANDLER")
                    }
                    CountCommandRecovery.HistoricalCommitted(requireNotNull(receipt))
                } catch (error: Throwable) {
                    editor.releaseUnexecuted(command)
                    throw error
                }
            }
        }
    }

    /** The widget workflow already owns the original-account lock and actual Room transaction. */
    internal suspend fun insertLegacyWidgetCompletionInTransaction(habit: HabitEntity,
        occurredAt: java.time.Instant, zone: java.time.ZoneId): Long {
        check(database.inTransaction() && habit.appearance == null)
        check(habitDao.getVisibleHabitById(habit.id) == habit) { "LEGACY_WIDGET_HABIT_CHANGED" }
        return insertCompletionInTransaction(habit, occurredAt, zone, 1)
    }

    internal suspend fun undoLegacyWidgetCompletionInTransaction(completion: CompletionEntity) {
        check(database.inTransaction() && completion.oneTimeAction == null)
        val habit = requireNotNull(habitDao.getVisibleHabitById(completion.habitId))
        check(habit.appearance == null && (completion.habitUuid == null || habit.uuid == completion.habitUuid)) { "LEGACY_WIDGET_HABIT_CHANGED" }
        check(completionDao.getCompletionById(completion.id) == completion) { "LEGACY_WIDGET_FACT_CHANGED" }
        CountCommandLegacyExecutor.allocation(database)?.selected(completion)
        completionDao.delete(completion)
        updateActivityRate(habit.id)
    }

    private suspend fun insertCompletionInTransaction(habit: HabitEntity, occurredAt: java.time.Instant,
        zone: java.time.ZoneId, value: Int, preparedCountFact: CompletionEntity? = null): Long {
        check(database.inTransaction())
        val legacy = CountCommandLegacyExecutor.allocation(database)
        if (!habit.isActive) {
            if (legacy == null) habitDao.updateIsActive(habit.id, true)
            else {
                val at = System.currentTimeMillis(); legacy.activation(at)
                habitDao.updateIsActive(habit.id, true, at)
            }
        }
        val commandInput = NextCoreLocalIntentStore.commandInput(database)
        check(preparedCountFact == null || commandInput == null) { "COMMAND_FOREIGN_PREPARED_FACT" }
        val constructedCountFact = NextCoreLocalIntentStore.commandConstructedFact(database) ?: preparedCountFact
        val completion = constructedCountFact ?: CompletionEntity(
            habitId = habit.id,
            date = occurredAt.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
            value = value,
            actualCompletedAt = occurredAt.toEpochMilli(),
            habitUuid = habit.uuid,
            recordedTimezone = zone.id,
            uuid = commandInput?.let {
                require(it.action == CountCommandRecords.Action.COUNT_ADD)
                CountCommandRecords.uuid(it.body().getValue("input").jsonObject, "reserved_fact_uuid")
            } ?: legacy?.reservedFactUuid ?: java.util.UUID.randomUUID().toString()
        )
        legacy?.constructed(completion)
        if (constructedCountFact != null) check(completion == CompletionEntity(habitId = habit.id,
            date = occurredAt.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), value = value,
            actualCompletedAt = occurredAt.toEpochMilli(), habitUuid = habit.uuid, recordedTimezone = zone.id,
            uuid = completion.uuid, createdAt = completion.createdAt)) { "COUNT_ADD_FALLBACK_INPUT_CHANGED" }
        // Optional source proof is frozen before the day and completion writes. It never
        // changes the original timestamp, UUID allocation, business validation or fallback.
        val sourceTicket = CountTrustKernel.beginCountSource(database, habit, completion)
        val dayPolicy = if (habit.appearance != null && habit.habitType == HabitType.COUNTING)
            NextCountDayStore(database).capture(habit, completion) else null
        val reactivationBase = if (commandInput != null && !habit.isActive)
            database.syncOutboxDao().getState("plan_node", habit.uuid)?.revision else null
        val commandAllocation = NextCoreLocalIntentStore.prepareCommandCount(database, habit, completion, dayPolicy, reactivationBase)
        val id = completionDao.insert(completion)
        legacy?.inserted(id)
        NextCoreLocalIntentStore.bindCommandIds(database, commandAllocation, listOf(id))
        CountTrustKernel.bindCountSource(database, sourceTicket, id)
        updateActivityRate(habit.id)
        return id
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
            // A profile cache may already contain another round. Physical history deletion is
            // never a challenge restart; leave all facts/requests intact until its new entry is ready.
            clearLegacyHabitHistoryInTransaction(database, habit)
        }

        // Notify widgets
        notifyWidgetUpdate(context)
    }

    private suspend fun <T> mutate(expected: HabitEntity, authority: ObjectEditAuthority? = null,
        structural: Boolean = true, countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        checkIdentity: com.dayforge.domain.model.CheckActionIdentity? = null, command: NextObjectEditor.CountAttempt? = null,
        commit: suspend (HabitEntity) -> T): T {
        if (expected.appearance != null && nextObjectEditor != null) {
            return nextObjectEditor.mutateHabit(expected, authority, countIdentity, checkIdentity, command, commit)
        }
        check(checkIdentity == null) { "CHECK_ACTION_EXPIRED" }
        check(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (structural) structuralEditGuard?.requireAllowed()
        if (countIdentity != null) return requireNotNull(countActions).writeLegacy(countIdentity) {
            val current = requireNotNull(habitDao.getHabitById(expected.id))
            check(current.uuid == expected.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            commit(current)
        }
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
        val habit = habitDao.getHabitById(habitId)
        if (habit?.habitType == HabitType.COUNTING && habit.appearance != null)
            return getCountSummary(habit).todayQuantity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (habit?.habitType == HabitType.CHECK_IN && habit.appearance != null && habit.completionPolicy == "recurring")
            return getCheckHistory(habit).todayQuantity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
                    val summary = getCountSummary(habit)
                    return@map StreakStats(StreakCalculator.currentFromBusinessDates(summary.qualifiedDates, summary.today),
                        StreakCalculator.bestFromBusinessDates(summary.qualifiedDates), summary.qualifiedDates.maxOrNull()?.toDisplayMillis())
                }
                if (habit?.habitType == HabitType.CHECK_IN && habit.appearance != null) {
                    val history = getCheckHistory(habit)
                    return@map StreakStats(StreakCalculator.currentFromBusinessDates(history.qualifiedDates, history.today),
                        StreakCalculator.bestFromBusinessDates(history.qualifiedDates), history.qualifiedDates.maxOrNull()?.toDisplayMillis())
                }
                if (habit?.habitType == HabitType.TIMER && habit.appearance != null) {
                    val history = getTimerHistory(habit)
                    return@map StreakStats(StreakCalculator.currentFromBusinessDates(history.qualifiedDates, history.today),
                        StreakCalculator.bestFromBusinessDates(history.qualifiedDates), history.qualifiedDates.maxOrNull()?.toDisplayMillis())
                }
                val currentStreak = StreakCalculator.calculateCurrentStreak(completions)
                val bestStreak = StreakCalculator.calculateBestStreak(completions)
                val lastCompletionDate = completions.maxOfOrNull { it.businessDate }?.toDisplayMillis()
                StreakStats(currentStreak, bestStreak, lastCompletionDate)
            }
    }

    // Constructor routing only, never an authentication/capability grant. The complete
    // production graph supplies this owner; old internal participant fixtures stay ordinary.
    /** Stable locator resolution only; it cannot execute a pending command or authorize a
     * current goal/prompt. Activities separately revalidate their original ordinary claim. */
    internal suspend fun historicalCountResult(reference: CountCommandReference, habitUuid: String): CountCommandReceipt =
        requireNotNull(nextObjectEditor).historicalCountResult(reference, habitUuid)

    /** Call only after the original current source accepted this exact local result into
     * its model. No-required-follow-up is frozen in the historical outcome; this helper
     * cannot close a required metric form and does not claim that pixels were displayed.
     */
    internal suspend fun acknowledgeCountResultDelivery(receipt: CountCommandReceipt) =
        requireNotNull(nextObjectEditor).acknowledgeCountResultDelivery(receipt)

    internal val hasCountCommandRecovery: Boolean get() = nextObjectEditor != null

    internal suspend fun captureLegacyCountQuantity(expected: HabitEntity): LegacyCountQuantitySelection {
        val current = requireNotNull(habitDao.getHabitById(expected.id))
        check(current.uuid == expected.uuid && current.habitType == HabitType.COUNTING && current.appearance == null) { "COUNT_ACTIVITY_CHANGED" }
        val today = DateTimeUtils.today()
        val quantity = completionDao.getCompletionsInRange(expected.id, today, today.plusDays(1)).sumOf { it.value }
        return LegacyCountQuantitySelection(expected.id, today, quantity)
    }
    internal suspend fun captureLegacyCountLatest(expected: HabitEntity): LegacyCountLatestSelection {
        val current = requireNotNull(habitDao.getHabitById(expected.id))
        check(current.uuid == expected.uuid && current.habitType == HabitType.COUNTING && current.appearance == null) { "COUNT_ACTIVITY_CHANGED" }
        val today = DateTimeUtils.today()
        return LegacyCountLatestSelection(expected.id, today, completionDao.getTodayCompletionId(expected.id, today, today.plusDays(1)))
    }
    internal suspend fun recordLegacyNoCurrentCount(expected: HabitEntity,
        identity: com.dayforge.domain.model.CountActionIdentity, selection: LegacyCountNoTarget): CountCommandReceipt =
        requireNotNull(nextObjectEditor).recordLegacyNoCurrentCount(expected, identity, selection)

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
        val habit = habitDao.getHabitById(habitId)
        if (habit?.habitType == HabitType.COUNTING && habit.appearance != null) {
            return getCountSummary(habit).todayLatestCompletionId
        }
        if (habit?.habitType == HabitType.CHECK_IN && habit.appearance != null && habit.completionPolicy == "recurring")
            return getCheckHistory(habit).todayCompletions.maxByOrNull { it.id }?.id
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
        val prepared = CountTrustKernel.preparedActivityRate(database, habit)
        val legacy = CountCommandLegacyExecutor.allocation(database)
        if (prepared != null) {
            legacy?.rate(prepared.rate, prepared.instant)
            habitDao.updateActivityRate(habitId, prepared.rate, prepared.instant)
            return
        }
        val completions = completionDao.getCompletionsByHabit(habitId).first()
        val newRate = ActivityRateCalculator.calculate(
            schedule = habit.schedule,
            createdAt = habit.createdAt,
            completions = completions.map { it.businessDate }
        )
        if (legacy == null) habitDao.updateActivityRate(habitId, newRate)
        else {
            val at = System.currentTimeMillis(); legacy.rate(newRate, at)
            habitDao.updateActivityRate(habitId, newRate, at)
        }
    }
}
