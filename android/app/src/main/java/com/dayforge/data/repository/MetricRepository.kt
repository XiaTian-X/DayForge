package com.dayforge.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

import kotlinx.serialization.json.jsonObject

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.LinkedMetricSnapshot
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.service.StructuralEditGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

class DuplicateMetricNameException(name: String) :
    IllegalArgumentException("Metric name already exists: $name")

data class MetricValueDraft(
    val metricId: Long,
    val value: Double,
    val note: String = ""
)

/** Immutable preparation from one read transaction, not permission to skip writer validation. */
internal class CompletionMetricRead internal constructor(
    val source: WidgetFactSnapshot,
    val metrics: List<ObjectEditSnapshot<MetricEntity>>,
    val links: List<LinkedMetricSnapshot>
)

/**
 * Owns local metric mutations.
 *
 * Room triggers append sync operations for every syncable table mutation. Multi-row user
 * actions are wrapped here so their business rows and every generated outbox row either all
 * commit or all roll back.
 */
@Singleton
class MetricRepository @Inject constructor(
    private val database: HabitDatabase,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val habitDao: HabitDao,
    private val linkDao: HabitMetricLinkDao,
    private val structuralEditGuard: StructuralEditGuard? = null,
    private val nextObjectEditor: NextObjectEditor? = null,
    private val nextObjectCreator: NextObjectCreator? = null,
    private val widgetFactReader: WidgetFactReader? = null,
    private val timerWriter: NextTimerWriter? = null
) {
    fun observeActiveMetrics(): Flow<List<MetricEntity>> = metricDao.getAllActiveMetrics()

    fun observeLatestMetricLog(): Flow<MetricLogEntity?> = metricLogDao.getLatestLogFlow()

    suspend fun getLatestLog(metricId: Long): MetricLogEntity? =
        metricLogDao.getLatestLog(metricId)

    suspend fun getMetricForEditing(id: Long): ObjectEditSnapshot<MetricEntity> =
        nextObjectEditor?.metric(id) ?: ObjectEditSnapshot(metricDao.getVisibleMetricById(id), null)

    suspend fun getMetricsForEditing(ids: List<Long>): List<ObjectEditSnapshot<MetricEntity>> =
        if (ids.isEmpty()) emptyList() else nextObjectEditor?.metrics(ids) ?: database.withTransaction {
            val rows = ids.distinct().associateWith { ObjectEditSnapshot(metricDao.getVisibleMetricById(it), null) }
            ids.map { rows.getValue(it) }
        }

    suspend fun getLogsInRange(
        metricId: Long,
        startInclusive: Long,
        endExclusive: Long
    ): List<MetricLogEntity> =
        metricLogDao.getLogsInRange(metricId, startInclusive, endExclusive)

    fun observeLinkedMetricSnapshots(): Flow<List<LinkedMetricSnapshot>> =
        linkDao.observeLinkedMetricSnapshots()

    suspend fun getLinkedMetricSnapshots(habitId: Long): List<LinkedMetricSnapshot> =
        linkDao.getLinkedMetricSnapshots(habitId)

    /** A preflight link query may skip this work, but no preflight row is used for publication. */
    internal suspend fun publishCompletionPrompt(snapshot: WidgetFactSnapshot,
        publish: (CompletionMetricRead) -> Unit) {
        requireNotNull(widgetFactReader).readCompletionPlanAndPublish(snapshot, read = { current, scope ->
            val infos = linkDao.getLinkedMetricSnapshots(current.habit.id).filter { it.promptOnComplete }
            if (infos.isEmpty()) null else {
                val links = linkDao.getVisibleLinksForHabit(current.habit.id).filter { it.isActive && it.promptOnComplete }
                val metrics = requireNotNull(nextObjectEditor).metricsInTransaction(infos.map { it.metricId }, scope)
                metrics.forEach { captured ->
                    val metric = requireNotNull(captured.value) { "FACT_PROMPT_STALE_METRIC" }
                    check(captured.authority?.session == snapshot.claim.session() && metric.appearance != null) {
                        "FACT_PROMPT_STALE_METRIC"
                    }
                    val link = links.single { it.metricId == metric.id }
                    check(link.habitUuid == current.habit.uuid && link.metricUuid == metric.uuid) { "METRIC_PROMPT_LINK_CHANGED" }
                }
                CompletionMetricRead(snapshot, metrics, infos)
            }
        }, publish = { prepared -> if (prepared != null) publish(prepared) })
    }

    suspend fun createMetric(
        metric: MetricEntity,
        selectedHabitIds: Set<Long> = emptySet(),
        creationAuthority: ObjectCreationAuthority? = null,
        expectedHabitUuids: Map<Long, String>? = null
    ): Long {
        val selected = selectedHabitIds.toSet()
        val habitIdentities = expectedHabitUuids?.toMap()
        require(habitIdentities == null || habitIdentities.keys == selected) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        suspend fun commit(saved: MetricEntity): Long {
            validateTargets(saved)
            ensureNameAvailable(saved.name)
            require(metricDao.getMetricByUuid(saved.uuid) == null) { "OBJECT_CREATE_ID_REUSED" }
            val metricId = metricDao.insert(saved)
            selected.forEach { habitId ->
                val habit = requireNotNull(habitDao.getVisibleHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                if (habitIdentities != null) check(habit.uuid == habitIdentities.getValue(habitId)) { "OBJECT_WRITE_LINK_CHANGED" }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = saved.uuid,
                        coefficient = 1.0,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
            return metricId
        }
        return if (creationAuthority != null) requireNotNull(nextObjectCreator).metric(metric, creationAuthority, ::commit)
            else {
                require(metric.appearance == null || nextObjectCreator == null) { "OBJECT_CREATE_TICKET_REQUIRED" }
                database.withTransaction {
                    if (nextObjectCreator != null) NextProtocolAdmission.requireLegacyCreation(database)
                    commit(metric)
                }
            }
    }

    suspend fun updateMetric(metric: MetricEntity, editAuthority: ObjectEditAuthority? = null) {
        structuralEditGuard?.requireAllowed()
        suspend fun commit(saved: MetricEntity) {
            validateTargets(saved)
            val previous = requireNotNull(metricDao.getMetricById(metric.id)) {
                "Metric no longer exists: ${metric.id}"
            }
            check(previous.uuid == metric.uuid && saved.uuid == metric.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
            check((previous.appearance == null) == (saved.appearance == null)) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
            val duplicate = metricDao.getMetricByName(saved.name)
            if (duplicate != null && duplicate.id != metric.id) {
                throw DuplicateMetricNameException(saved.name)
            }
            metricDao.update(saved)
        }
        if (metric.appearance != null && nextObjectEditor != null) nextObjectEditor
            .editMetric(metric, requireNotNull(editAuthority) { "OBJECT_EDIT_TICKET_REQUIRED" }, ::commit)
        else database.withTransaction { commit(metric.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun updateAggregationType(metricId: Long, aggregationType: String, expectedMetricUuid: String? = null) {
        val metric = requireNotNull(metricDao.getMetricById(metricId))
        if (expectedMetricUuid != null) check(metric.uuid == expectedMetricUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        mutate(listOf(metric)) { metricDao.updateAggregationType(metricId, aggregationType) }
    }

    suspend fun deleteMetric(metric: MetricEntity, authority: ObjectEditAuthority? = null) {
        mutate(listOf(metric), authority, deleting = true) {
            if (metric.appearance != null) NextMetricDeletionStore(database).stageMetric(metric)
            else metricDao.delete(metric)
        }
    }

    suspend fun unlinkHabit(linkId: Long, expectedLinkUuid: String? = null, authority: ObjectEditAuthority? = null) {
        val link = linkDao.getById(linkId) ?: return
        if (expectedLinkUuid != null) check(link.uuid == expectedLinkUuid) { "OBJECT_WRITE_LINK_CHANGED" }
        val metric = requireNotNull(metricDao.getMetricById(link.metricId))
        mutate(listOf(metric), authority) {
            check(linkDao.getById(linkId) == link) { "OBJECT_WRITE_LINK_CHANGED" }
            if (metric.appearance != null) NextMetricDeletionStore(database).stageLink(link)
            else linkDao.delete(link)
        }
    }

    suspend fun linkHabits(metric: MetricEntity, habitIds: Set<Long>, authority: ObjectEditAuthority? = null,
        expectedHabitUuids: Map<Long, String>? = null) {
        val ids = habitIds.toSet()
        val identities = expectedHabitUuids?.toMap()
        require(identities == null || identities.keys == ids) { "OBJECT_WRITE_LINK_SELECTION_CHANGED" }
        mutate(listOf(metric), authority) {
            ids.forEach { habitId ->
                val habit = requireNotNull(habitDao.getVisibleHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
                if (identities != null) check(habit.uuid == identities.getValue(habitId)) { "OBJECT_WRITE_LINK_CHANGED" }
                check((habit.appearance == null) == (metric.appearance == null)) { "OBJECT_WRITE_MIXED_PROTOCOL" }
                linkDao.getLink(habitId, metric.id)?.let { NextMetricDeletionStore(database).requireLinkWritable(it) }
                linkDao.insertOrIgnore(
                    HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metric.id,
                        metricUuid = metric.uuid,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                )
            }
        }
    }

    /** Explicit independent UI command; ordinary batch/prompt APIs below retain their original semantics.
     * Capture first and retain its reference before execute, including an unknown T0 outcome. */
    internal suspend fun beginIndependentValue(metricId: Long, value: Double, note: String,
        recordedAt: Long = System.currentTimeMillis(), authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null): IndependentMetricAttempt {
        val metric = requireNotNull(metricDao.getMetricById(metricId)) { "Metric no longer exists: " + metricId }
        if (expectedMetricUuid != null) require(metric.uuid == expectedMetricUuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        return requireNotNull(nextObjectEditor).beginIndependentValue(metric, authority, value, note, recordedAt)
    }
    internal suspend fun executeIndependentValue(attempt: IndependentMetricAttempt): IndependentMetricSaved =
        requireNotNull(nextObjectEditor).executeIndependentValue(attempt)
    internal suspend fun resolveIndependentValue(reference: CountCommandReference,
        continueIfProvenUnexecuted: Boolean = false): IndependentMetricResolution =
        requireNotNull(nextObjectEditor).resolveIndependentValue(reference, continueIfProvenUnexecuted)
    internal suspend fun resolveIndependentAttempt(attempt: IndependentMetricAttempt,
        continueIfProvenUnexecuted: Boolean = false): IndependentMetricResolution =
        requireNotNull(nextObjectEditor).resolveIndependentAttempt(attempt, continueIfProvenUnexecuted)
    internal suspend fun acknowledgeIndependentValue(saved: IndependentMetricSaved, isConsumerCurrent: () -> Boolean) =
        requireNotNull(nextObjectEditor).acknowledgeIndependentValue(saved, isConsumerCurrent)

    internal suspend fun prepareIndependentEnd(reference: CountCommandReference): IndependentMetricEnd? =
        requireNotNull(nextObjectEditor).prepareIndependentEnd(reference)
    internal suspend fun endIndependentValue(plan: IndependentMetricEnd, isConsumerCurrent: () -> Boolean): IndependentMetricEnded =
        requireNotNull(nextObjectEditor).endIndependentValue(plan, isConsumerCurrent)
    internal suspend fun acknowledgeIndependentEnd(ended: IndependentMetricEnded, isConsumerCurrent: () -> Boolean) =
        requireNotNull(nextObjectEditor).acknowledgeIndependentEnd(ended, isConsumerCurrent)

    suspend fun recordValue(
        metricId: Long,
        value: Double,
        note: String,
        recordedAt: Long = System.currentTimeMillis(),
        authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null
    ): Long = recordValues(
        values = listOf(MetricValueDraft(metricId, value, note)),
        recordedAt = recordedAt, authority = authority, expectedMetricUuid = expectedMetricUuid
    ).single()

    /** Legacy prompts have no v5 edit ticket. Hold their original account/UUID claim through
     * the Room commit; never enter the typed writer's non-reentrant account lock from here.
     */
    internal suspend fun recordLegacyPromptValues(
        values: List<MetricValueDraft>, recordedAt: Long,
        claim: HabitPreferenceClaim, preferences: HabitPreferenceRepository,
        habit: com.dayforge.data.local.entity.HabitEntity, metrics: List<MetricEntity>
    ): List<Long> {
        require(habit.appearance == null && metrics.isNotEmpty() && metrics.all { it.appearance == null }) {
            "METRIC_PROMPT_MIXED_PROTOCOL"
        }
        require(claim.habitId == habit.id && claim.habitUuid == habit.uuid)
        require(metrics.map { it.id }.toSet() == values.map { it.metricId }.toSet() &&
            metrics.size == metrics.map { it.id }.toSet().size)
        return preferences.publish(claim) {
            database.withTransaction {
                preferences.requireWidgetWriteInTransaction(claim)
                check(habitDao.getVisibleHabitById(habit.id) == habit) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
                metrics.forEach { original ->
                    check(metricDao.getVisibleMetricById(original.id) == original) { "METRIC_PROMPT_METADATA_CHANGED" }
                    val link = requireNotNull(linkDao.getLink(habit.id, original.id)) { "METRIC_PROMPT_LINK_CHANGED" }
                    NextMetricDeletionStore(database).requireLinkWritable(link)
                    check(link.isActive && link.promptOnComplete && link.habitUuid == habit.uuid &&
                        link.metricUuid == original.uuid) { "METRIC_PROMPT_LINK_CHANGED" }
                }
                recordValues(values, recordedAt).also {
                    // A rejected refresh can invalidate credentials without the account mutex.
                    // Keep the actual rows and their outbox entries inside this final fence.
                    preferences.requireWidgetWriteInTransaction(claim)
                }
            }
        }
    }

    /** Durable COUNT child; original non-command legacy prompt entry above remains intact. */
    internal suspend fun recordLegacyPromptValuesWithReceipt(values: List<MetricValueDraft>, recordedAt: Long,
        claim: HabitPreferenceClaim, preferences: HabitPreferenceRepository,
        habit: com.dayforge.data.local.entity.HabitEntity, metrics: List<MetricEntity>, parent: CountCommandReceipt,
        prepared: CountCommandLegacyMetricExecutor.Attempt? = null): CountMutationResult<List<Long>> {
        var attempt = prepared
        try {
            CountCommandEntryBoundary.requireTopLevel(database)
            val editor = requireNotNull(nextObjectEditor)
            val selected = prepared ?: editor.beginLegacyMetricChild(parent, claim, preferences, habit, metrics, values, recordedAt)
            attempt = selected
            val input = selected.input.body().getValue("input").jsonObject
            require(selected.parent.reference == parent.reference && selected.claim === claim && selected.habit == habit &&
                selected.metrics == metrics && CountCommandLegacyMetricInput.values(input) == values &&
                CountCommandRecords.integer(input, "recorded_at") == recordedAt) { "COMMAND_ORIGINAL_METRIC_INPUT_CHANGED" }
            val ids = editor.mutateLegacyMetrics(selected, preferences) { recordValues(values, recordedAt) }
            return CountMutationResult(ids, selected.receipt())
        } finally { attempt?.let { requireNotNull(nextObjectEditor).releaseLegacyMetrics(it) } }
    }

    suspend fun recordValues(
        values: List<MetricValueDraft>,
        recordedAt: Long = System.currentTimeMillis(),
        authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null,
        expectedMetrics: List<MetricEntity>? = null,
        promptHabit: com.dayforge.data.local.entity.HabitEntity? = null,
        promptFact: WidgetFactClaim? = null,
        promptTimer: com.dayforge.domain.model.TimerActionAuthority? = null,
        promptCompletionSource: Boolean = false
    ): List<Long> = recordValuesWithReceipt(values, recordedAt, authority, expectedMetricUuid,
        expectedMetrics, promptHabit, promptFact, promptTimer, promptCompletionSource).value

    internal suspend fun recordValuesWithReceipt(
        values: List<MetricValueDraft>,
        recordedAt: Long = System.currentTimeMillis(),
        authority: ObjectEditAuthority? = null,
        expectedMetricUuid: String? = null,
        expectedMetrics: List<MetricEntity>? = null,
        promptHabit: com.dayforge.data.local.entity.HabitEntity? = null,
        promptFact: WidgetFactClaim? = null,
        promptTimer: com.dayforge.domain.model.TimerActionAuthority? = null,
        promptCompletionSource: Boolean = false,
        parentCommand: CountCommandReceipt? = null,
        preparedCommand: NextObjectEditor.CountAttempt? = null
    ): CountMutationResult<List<Long>> {
        var acquiredCommand = preparedCommand
        try {
        if (parentCommand != null || preparedCommand != null) CountCommandEntryBoundary.requireTopLevel(database)
        val frozen = preparedCommand?.input?.let { intent ->
            require(intent.action == CountCommandRecords.Action.LINKED_METRICS && parentCommand != null)
            intent.body().getValue("input").jsonObject.also { input ->
                require(CountCommandRecords.uuid(input, "parent_command_id") == parentCommand.commandId &&
                    CountCommandRecords.integer(input, "recorded_at") == recordedAt &&
                    CountCommandInputs.restoredWidget(input, "source_claim") == promptFact) { "COMMAND_ORIGINAL_METRIC_INPUT_CHANGED" }
            }
        }
        val capturedZone = frozen?.let { CountCommandRecords.text(it, "timezone") } ?: java.time.ZoneId.systemDefault().id
        if (values.isEmpty()) return CountMutationResult(emptyList(), null)
        require(!promptCompletionSource || promptFact != null && promptHabit?.appearance != null &&
            promptHabit.completionPolicy == "recurring") { "COMPLETION_PROMPT_SOURCE_REQUIRED" }
        if (promptFact != null) require(authority != null && expectedMetrics != null && promptHabit != null &&
            promptHabit.id == promptFact.habitId && promptHabit.uuid == promptFact.habitUuid) { "FACT_PROMPT_TICKET_REQUIRED" }
        if (promptTimer != null) require(promptFact == null && authority != null && expectedMetrics != null &&
            promptHabit?.uuid == promptTimer.habitUuid && promptTimer.sessionUuid != null) { "TIMER_PROMPT_TICKET_REQUIRED" }
        val targets = expectedMetrics ?: values.map { it.metricId }.distinct().map { id ->
            requireNotNull(metricDao.getMetricById(id)) { "Metric no longer exists: $id" }
        }
        if (expectedMetrics != null) {
            require(authority != null && targets.all { it.appearance != null })
            require(targets.map { it.id }.toSet() == values.map { it.metricId }.toSet() &&
                targets.size == targets.map { it.id }.toSet().size)
        }
        if (expectedMetricUuid != null) check(targets.size == 1 && targets.single().uuid == expectedMetricUuid) {
            "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED"
        }
        val command = preparedCommand ?: parentCommand?.let { parent ->
            require(promptCompletionSource && promptHabit?.habitType == com.dayforge.data.model.HabitType.COUNTING)
            val actualHabit = requireNotNull(promptHabit)
            val links = targets.map { metric -> requireNotNull(linkDao.getLink(actualHabit.id, metric.id)) { "METRIC_PROMPT_LINK_CHANGED" } }
            requireNotNull(nextObjectEditor).beginMetricChild(parent, requireNotNull(authority), actualHabit,
                requireNotNull(promptFact), values, targets, links, recordedAt, capturedZone)
        }
        acquiredCommand = command
        val ids = mutate(targets, authority, structural = false, readCurrentMetrics = true,
            prepareMetricSourceRead = promptCompletionSource && promptHabit?.habitType == com.dayforge.data.model.HabitType.COUNTING,
            command = command) { currentMetrics ->
            val timerAccess = promptTimer?.let { source ->
                check(authority?.session == source.session()) { "TIMER_PROMPT_STALE_ACCOUNT" }
                requireNotNull(timerWriter).completionInTransaction(requireNotNull(promptHabit).id, source)
                timerWriter.requireCompletionAccessInTransaction(source, "facts.append")
            }
            if (promptFact != null) {
                check(authority?.session == promptFact.session()) { "FACT_PROMPT_STALE_ACCOUNT" }
                if (promptCompletionSource) CountTrustKernel.requireMetricCompletion(database,
                    requireNotNull(widgetFactReader), promptFact)
                else requireNotNull(widgetFactReader).requireInTransaction(promptFact, requireDay = false)
                check(promptFact.completionUuid != null) { "FACT_PROMPT_COMPLETION_CHANGED" }
            }
            if (promptHabit != null) {
                check(habitDao.getVisibleHabitById(promptHabit.id)?.uuid == promptHabit.uuid) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
                targets.forEach { metric ->
                    val link = requireNotNull(linkDao.getLink(promptHabit.id, metric.id)) { "METRIC_PROMPT_LINK_CHANGED" }
                    NextMetricDeletionStore(database).requireLinkWritable(link)
                    check(link.isActive && link.promptOnComplete && link.habitUuid == promptHabit.uuid && link.metricUuid == metric.uuid) {
                        "METRIC_PROMPT_LINK_CHANGED"
                    }
                }
            }
            val commandInput = NextCoreLocalIntentStore.commandInput(database)
            val commandValues = commandInput?.let { frozen ->
                require(frozen.action == CountCommandRecords.Action.LINKED_METRICS && promptCompletionSource)
                val input = CountTrustKernel.metricCommandInput(database, frozen)
                require(CountCommandRecords.uuid(input, "habit_uuid") == promptHabit?.uuid &&
                    CountCommandRecords.uuid(input, "source_fact_uuid") == promptFact?.completionUuid &&
                    CountCommandInputs.restoredWidget(input, "source_claim") == promptFact) { "COMMAND_METRIC_SOURCE_CHANGED" }
                input.getValue("metrics").jsonArray.map { it.jsonObject }.associateBy { CountCommandRecords.integer(it, "metric_id") }
            }
            commandValues?.values?.forEach { frozen ->
                val id = CountCommandRecords.integer(frozen, "metric_id")
                require(CountCommandRecords.canonical(frozen.getValue("definition")) ==
                    CountCommandRecords.canonical(CountTrustKernel.metricDefinitionForT1(database, currentMetrics.getValue(id)))) { "COMMAND_METRIC_DEFINITION_CHANGED" }
                val link = requireNotNull(linkDao.getLink(requireNotNull(promptHabit).id, id)) { "METRIC_PROMPT_LINK_CHANGED" }
                require(CountCommandRecords.canonical(frozen.getValue("link")) ==
                    CountCommandRecords.canonical(CountCommandInputs.metricLink(link))) { "COMMAND_METRIC_LINK_CHANGED" }
            }
            val legacyAllocation = CountCommandLegacyMetricExecutor.allocation(database)
            val logs = values.map { input ->
                require(input.value.isFinite()) { "Metric value must be finite" }
                val metric = currentMetrics.getValue(input.metricId)
                MetricLogEntity(
                    metricId = input.metricId,
                    date = recordedAt,
                    value = input.value,
                    unit = metric.unit,
                    note = input.note,
                    recordedTimezone = capturedZone,
                    uuid = commandValues?.let { CountCommandRecords.uuid(it.getValue(input.metricId), "reserved_fact_uuid") }
                        ?: legacyAllocation?.uuid(input.metricId) ?: java.util.UUID.randomUUID().toString()
                )
            }
            legacyAllocation?.constructed(logs)
            val metricTicket = if (promptCompletionSource) CountTrustKernel.beginMetricBatch(database,
                logs, currentMetrics, requireNotNull(promptHabit), requireNotNull(promptFact)) else null
            val commandAllocation = NextCoreLocalIntentStore.prepareCommandMetrics(database, logs, currentMetrics)
            val saved = if (logs.isEmpty()) emptyList() else metricLogDao.insertAll(logs)
            legacyAllocation?.inserted(saved)
            NextCoreLocalIntentStore.bindCommandIds(database, commandAllocation, saved)
            CountTrustKernel.bindMetricBatch(database, metricTicket, saved)
            promptTimer?.let {
                check(requireNotNull(timerWriter).requireCompletionAccessInTransaction(it, "facts.append") == timerAccess)
            }
            saved
        }
        return CountMutationResult(ids, command?.committedReceipt())
        } finally { acquiredCommand?.let { requireNotNull(nextObjectEditor).releaseUnexecuted(it) } }
    }

    /** Keep the original source/account publication fence around the explicit preference
     * action, then persist T2 outside that account lock. A historical receipt alone can never
     * close the form. The caller still checks exact currently displayed prompt identity.
     */
    internal suspend fun skipCompletionCommandFollowUp(snapshot: WidgetFactSnapshot, parent: CountCommandReceipt,
        skip: suspend () -> Unit) {
        require(snapshot.habit.habitType == com.dayforge.data.model.HabitType.COUNTING &&
            parent.recorded && parent.factUuid == snapshot.claim.completionUuid) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
        requireNotNull(widgetFactReader).publishCompletion(snapshot, requireDay = false, block = skip)
        requireNotNull(nextObjectEditor).acknowledgeExplicitFollowUpSkip(parent,
            requireNotNull(snapshot.claim.completionUuid), snapshot.habit.uuid)
    }

    /** A completed original policy decision, not a receipt-only acknowledgement. Re-read
     * the exact source and the no-prompt predicates together under the original account/read
     * plan. A declined current producer or read failure leaves FOLLOW_UP_PENDING. T2 starts
     * only after that scope has returned and released its non-reentrant account lock.
     */
    internal suspend fun finishCompletionCommandWhenNoPrompt(snapshot: WidgetFactSnapshot,
        parent: CountCommandReceipt, preferences: com.dayforge.data.local.PreferencesManager,
        isCurrent: () -> Boolean): Boolean {
        require(snapshot.habit.habitType == com.dayforge.data.model.HabitType.COUNTING && parent.recorded &&
            parent.factUuid == snapshot.claim.completionUuid) { "COMMAND_FOLLOWUP_SOURCE_CHANGED" }
        var decided = false
        requireNotNull(widgetFactReader).readCompletionPlanAndPublish(snapshot, read = { current, _ ->
            val noLinks = linkDao.getLinkedMetricSnapshots(current.habit.id).none { it.promptOnComplete }
            val neverAsk = preferences.getNeverAskAgain(current.claim.session(), current.habit.uuid).first()
            noLinks || neverAsk
        }, publish = { absent -> decided = absent && isCurrent() })
        if (!decided) return false
        requireNotNull(nextObjectEditor).acknowledgeNoRequiredFollowUp(parent,
            requireNotNull(snapshot.claim.completionUuid), snapshot.habit.uuid)
        return true
    }

    /** Original regular/legacy prompt close, after its original preference publication.
     * This does not turn an unresolved child or a bare window dismissal into a close grant.
     */
    internal suspend fun acknowledgeLegacyPromptSkip(claim: HabitPreferenceClaim, parent: CountCommandReceipt) {
        require(parent.recorded && parent.requiresMetricFollowUp && parent.sourceHabitUuid == claim.habitUuid)
        requireNotNull(nextObjectEditor).acknowledgeExplicitFollowUpSkip(parent, requireNotNull(parent.factUuid), claim.habitUuid)
    }

    internal suspend fun finishLegacyCommandWhenNoPrompt(claim: HabitPreferenceClaim, parent: CountCommandReceipt,
        owner: HabitPreferenceRepository, preferences: com.dayforge.data.local.PreferencesManager,
        isCurrent: () -> Boolean): Boolean {
        require(parent.recorded && parent.requiresMetricFollowUp && parent.sourceHabitUuid == claim.habitUuid)
        var decided = false
        owner.publish(claim) {
            val noLinks = database.withTransaction { linkDao.getLinkedMetricSnapshots(claim.habitId).none { it.promptOnComplete } }
            val neverAsk = preferences.getNeverAskAgain(claim.session, claim.habitUuid).first()
            decided = (noLinks || neverAsk) && isCurrent()
        }
        if (!decided) return false
        requireNotNull(nextObjectEditor).acknowledgeNoRequiredFollowUp(parent, requireNotNull(parent.factUuid), claim.habitUuid)
        return true
    }

    internal suspend fun acknowledgeMetricCommandDelivery(parent: CountCommandReceipt, child: CountCommandReceipt) =
        requireNotNull(nextObjectEditor).acknowledgeMetricDelivery(parent, child)

    internal suspend fun deliverFormalMetricResult(parent: CountCommandReceipt, child: CountCommandReceipt,
        snapshot: WidgetFactSnapshot, isConsumerCurrent: () -> Boolean) =
        requireNotNull(nextObjectEditor).deliverFormalMetricResult(parent, child, snapshot,
            requireNotNull(widgetFactReader), isConsumerCurrent)

    /** Original immutable child inputs, used only to restore an explicitly opened form.
     * This is not execution/definition authority and does not set submittedCommand. */
    internal class RestoredMetricInput private constructor(val reference: CountCommandReference,
        private val saved: List<JsonObject>, private val legacy: Boolean) {
        fun inputs(metrics: List<MetricEntity>): Map<Long, Pair<String, String>> {
            require(saved.isNotEmpty() && saved.map { CountCommandRecords.integer(it, "metric_id") }.distinct().size == saved.size)
            return java.util.Collections.unmodifiableMap(saved.associate { item ->
                val id = CountCommandRecords.integer(item, "metric_id")
                val current = requireNotNull(metrics.singleOrNull { it.id == id }) { "COMMAND_METRIC_DEFINITION_CHANGED" }
                require(current.uuid == CountCommandRecords.uuid(item, "metric_uuid") &&
                    (if (legacy) CountCommandLegacyMetricInput.matchesDefinition(item.getValue("definition_native").jsonObject, current)
                    else CountCommandRecords.canonical(item.getValue("definition")) == CountCommandRecords.canonical(NextStructureMapper.writeMetric(current)))) {
                    "COMMAND_METRIC_DEFINITION_CHANGED"
                }
                val value = Double.fromBits(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(item, "value_bits"), 16))
                require(value.isFinite())
                id to (value.toString() to CountCommandRecords.text(item, "note"))
            })
        }
        companion object {
            fun from(reference: CountCommandReference, row: CountCommandRowPlan.Aggregate): RestoredMetricInput = RestoredMetricInput(reference,
                row.intent.body().getValue("input").jsonObject.getValue("metrics").jsonArray.map { it.jsonObject },
                CountCommandLegacyInput.isLegacy(row.intent))
        }
    }
    internal suspend fun restoredMetricInput(parent: CountCommandReceipt): RestoredMetricInput? {
        val child = requireNotNull(nextObjectEditor).locateMetricChild(parent) ?: return null
        return RestoredMetricInput.from(CountCommandReference.from(child.reference), child.historical)
    }

    /** Explicit Save retry for a known parent edge. Exact old values retain the original
     * child ID/time. It does not infer absence from a miss or synthesize a second submission.
     * Current form/source publication and its T2 remain the caller's separate original work.
     */
    internal suspend fun resumeExistingMetricSubmission(parent: CountCommandReceipt, values: List<MetricValueDraft>,
        metrics: List<MetricEntity>, legacyPreferences: HabitPreferenceRepository? = null,
        expectedChild: CountCommandReference? = null): CountCommandReceipt? {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(values.isNotEmpty() && values.map { it.metricId }.distinct().size == values.size &&
            metrics.size == values.size && metrics.map { it.id }.toSet() == values.map { it.metricId }.toSet())
        val located = requireNotNull(nextObjectEditor).locateMetricChild(parent)
        if (expectedChild != null) require(located != null && CountCommandReference.from(located.reference) == expectedChild) {
            "COMMAND_METRIC_RESTORED_CHILD_CHANGED"
        }
        if (located == null) return null
        val historical = located.historical
        val frozen = historical.intent.body().getValue("input").jsonObject.getValue("metrics").jsonArray.map { it.jsonObject }
        val same = frozen.size == values.size && frozen.all { saved ->
            val id = CountCommandRecords.integer(saved, "metric_id")
            val value = values.singleOrNull { it.metricId == id }
            val metric = metrics.singleOrNull { it.id == id }
            value != null && metric != null && metric.uuid == CountCommandRecords.uuid(saved, "metric_uuid") &&
                value.value.toRawBits() == java.lang.Long.parseUnsignedLong(CountCommandRecords.text(saved, "value_bits"), 16) &&
                value.note == CountCommandRecords.text(saved, "note") &&
                (if (CountCommandLegacyInput.isLegacy(historical.intent))
                    CountCommandLegacyMetricInput.matchesDefinition(saved.getValue("definition_native").jsonObject, metric)
                else metric.appearance != null && NextStructureMapper.writeMetric(metric) == saved.getValue("definition"))
        }
        if (!same) {
            require(historical.outcome == null && historical.status.execution == CountCommandRecords.Execution.PREPARED) {
                "COMMAND_METRIC_GROUP_ALREADY_COMMITTED"
            }
            // This is a new explicit corrected input. Only registerChild's original complete
            // no-effects/active-invocation guards may supersede the old PREPARED child.
            return null
        }
        return when (val result = resumeLinkedMetricCommand(CountCommandReference.from(located.reference), legacyPreferences)) {
            is CountCommandRecovery.HistoricalCommitted -> result.receipt
            is CountCommandRecovery.Unresolved -> error("COMMAND_METRIC_RECOVERY_UNRESOLVED:${result.reason}")
        }
    }

    /** Replays only a durable, fully frozen child after native recovery proved eligibility.
     * Successful return is local historical commit, not current form publication or T2.
     */
    suspend fun resumeLinkedMetricCommand(reference: CountCommandReference,
        legacyPreferences: HabitPreferenceRepository? = null): CountCommandRecovery {
        val editor = requireNotNull(nextObjectEditor)
        val fast = editor.recoverCommand(reference, setOf(CountCommandRecords.Action.LINKED_METRICS), legacyPreferences)
        val inspected = if (fast is NextObjectEditor.RecoveryAttempt.Unresolved &&
            fast.reason == "ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF")
            editor.recoverCommandAfterCompleteProof(reference, setOf(CountCommandRecords.Action.LINKED_METRICS), legacyPreferences) else fast
        return when (val recovered = inspected) {
            is NextObjectEditor.RecoveryAttempt.Historical -> CountCommandRecovery.HistoricalCommitted(recovered.receipt)
            is NextObjectEditor.RecoveryAttempt.Unresolved -> CountCommandRecovery.Unresolved(reference, recovered.reason)
            is NextObjectEditor.RecoveryAttempt.LegacyExecute -> error("COMMAND_LEGACY_METRIC_INPUT_REQUIRED")
            is NextObjectEditor.RecoveryAttempt.LegacyMetricExecute -> {
                val command = recovered.command
                try {
                    val input = command.input.body().getValue("input").jsonObject
                    val result = recordLegacyPromptValuesWithReceipt(CountCommandLegacyMetricInput.values(input),
                        CountCommandRecords.integer(input, "recorded_at"), command.claim, requireNotNull(legacyPreferences),
                        command.habit, command.metrics, command.parent, command)
                    CountCommandRecovery.HistoricalCommitted(requireNotNull(result.command))
                } finally { editor.releaseLegacyMetrics(command) }
            }
            is NextObjectEditor.RecoveryAttempt.Execute -> {
                val command = recovered.command
                try {
                    val input = command.input.body().getValue("input").jsonObject
                    val source = requireNotNull(CountCommandInputs.restoredWidget(input, "source_claim"))
                    val habit = requireNotNull(habitDao.getHabitById(source.habitId)) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
                    require(habit.uuid == CountCommandRecords.uuid(input, "habit_uuid")) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
                    val selected = input.getValue("metrics").jsonArray.map { it.jsonObject }
                    val targets = selected.map { frozen ->
                        requireNotNull(metricDao.getMetricById(CountCommandRecords.integer(frozen, "metric_id"))).also { metric ->
                            require(metric.uuid == CountCommandRecords.uuid(frozen, "metric_uuid") &&
                                CountCommandRecords.canonical(NextStructureMapper.writeMetric(metric)) ==
                                CountCommandRecords.canonical(frozen.getValue("definition"))) { "COMMAND_METRIC_DEFINITION_CHANGED" }
                        }
                    }
                    val drafts = selected.map { frozen -> MetricValueDraft(CountCommandRecords.integer(frozen, "metric_id"),
                        Double.fromBits(java.lang.Long.parseUnsignedLong(CountCommandRecords.text(frozen, "value_bits"), 16)),
                        CountCommandRecords.text(frozen, "note")) }
                    val parent = editor.recoverMetricParent(command)
                    val result = recordValuesWithReceipt(drafts, CountCommandRecords.integer(input, "recorded_at"),
                        authority = command.authority, expectedMetrics = targets, promptHabit = habit, promptFact = source,
                        promptCompletionSource = true, parentCommand = parent, preparedCommand = command)
                    CountCommandRecovery.HistoricalCommitted(requireNotNull(result.command))
                } catch (error: Throwable) {
                    editor.releaseUnexecuted(command)
                    throw error
                }
            }
        }
    }

    private suspend fun <T> mutate(metrics: List<MetricEntity>, authority: ObjectEditAuthority? = null,
        structural: Boolean = true, deleting: Boolean = false, readCurrentMetrics: Boolean = false,
        prepareMetricSourceRead: Boolean = false, command: NextObjectEditor.CountAttempt? = null,
        commit: suspend (Map<Long, MetricEntity>) -> T): T {
        if (metrics.any { it.appearance != null } && nextObjectEditor != null) {
            return nextObjectEditor.mutateMetrics(metrics, authority, deleting, prepareMetricSourceRead, command, commit)
        }
        check(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (structural) structuralEditGuard?.requireAllowed()
        suspend fun legacyCommit(): T {
            // Bind every captured numeric ID to its original UUID in the actual writer,
            // even callbacks which do not need current units (delete/link/aggregation).
            val current = metrics.mapNotNull { row ->
                val observed = metricDao.getMetricById(row.id)
                if (observed == null && deleting) return@mapNotNull null // Preserve original missing-row DELETE no-op.
                val stored = requireNotNull(observed) { "Metric no longer exists: ${row.id}" }
                check(stored.uuid == row.uuid) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
                check(stored.appearance == null) { "OBJECT_EDIT_REQUIRES_COORDINATED_SWITCH" }
                row.id to stored
            }.toMap()
            if (!deleting) metrics.forEach { NextMetricDeletionStore(database).requireWritable(it.uuid) }
            return commit(if (readCurrentMetrics) current else emptyMap())
        }
        val legacyFrame = CountCommandLegacyMetricExecutor.allocation(database)
        return if (legacyFrame != null) {
            check(!structural && !deleting && readCurrentMetrics) { "COMMAND_LEGACY_METRIC_WRITE_SHAPE" }
            legacyCommit()
        } else database.withTransaction { legacyCommit() }
    }

    private fun validateTargets(metric: MetricEntity) {
        require(metric.targetValue?.isFinite() != false && metric.targetValueUpper?.isFinite() != false) {
            "Metric targets must be finite"
        }
        if (metric.targetDirection == "range") {
            val lower = requireNotNull(metric.targetValue) { "Range targets require a lower value" }
            val upper = requireNotNull(metric.targetValueUpper) { "Range targets require an upper value" }
            require(upper >= lower) { "Range upper target must not be less than lower target" }
        }
    }

    private suspend fun ensureNameAvailable(name: String) {
        if (metricDao.getMetricByName(name) != null) {
            throw DuplicateMetricNameException(name)
        }
    }
}
