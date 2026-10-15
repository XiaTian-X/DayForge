package com.dayforge.data.repository

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
        creationAuthority: ObjectCreationAuthority? = null
    ): Long {
        if (creationAuthority == null) structuralEditGuard?.requireAllowed()
        suspend fun commit(saved: MetricEntity): Long {
            validateTargets(saved)
            ensureNameAvailable(saved.name)
            require(metricDao.getMetricByUuid(saved.uuid) == null) { "OBJECT_CREATE_ID_REUSED" }
            val metricId = metricDao.insert(saved)
            selectedHabitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getVisibleHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
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

    suspend fun updateAggregationType(metricId: Long, aggregationType: String) {
        val metric = requireNotNull(metricDao.getMetricById(metricId))
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

    suspend fun linkHabits(metric: MetricEntity, habitIds: Set<Long>, authority: ObjectEditAuthority? = null) {
        mutate(listOf(metric), authority) {
            habitIds.forEach { habitId ->
                val habit = requireNotNull(habitDao.getVisibleHabitById(habitId)) {
                    "Selected habit no longer exists: $habitId"
                }
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
    ): List<Long> {
        val capturedZone = java.time.ZoneId.systemDefault().id
        if (values.isEmpty()) return emptyList()
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
        return mutate(targets, authority, structural = false, readCurrentMetrics = true) { currentMetrics ->
            val timerAccess = promptTimer?.let { source ->
                check(authority?.session == source.session()) { "TIMER_PROMPT_STALE_ACCOUNT" }
                requireNotNull(timerWriter).completionInTransaction(requireNotNull(promptHabit).id, source)
                timerWriter.requireCompletionAccessInTransaction(source, "facts.append")
            }
            if (promptFact != null) {
                check(authority?.session == promptFact.session()) { "FACT_PROMPT_STALE_ACCOUNT" }
                if (promptCompletionSource) requireNotNull(widgetFactReader).requireCompletionInTransaction(promptFact)
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
            val logs = values.map { input ->
                require(input.value.isFinite()) { "Metric value must be finite" }
                val metric = currentMetrics.getValue(input.metricId)
                MetricLogEntity(
                    metricId = input.metricId,
                    date = recordedAt,
                    value = input.value,
                    unit = metric.unit,
                    note = input.note,
                    recordedTimezone = capturedZone
                )
            }
            val saved = if (logs.isEmpty()) emptyList() else metricLogDao.insertAll(logs)
            promptTimer?.let {
                check(requireNotNull(timerWriter).requireCompletionAccessInTransaction(it, "facts.append") == timerAccess)
            }
            saved
        }
    }

    private suspend fun <T> mutate(metrics: List<MetricEntity>, authority: ObjectEditAuthority? = null,
        structural: Boolean = true, deleting: Boolean = false, readCurrentMetrics: Boolean = false,
        commit: suspend (Map<Long, MetricEntity>) -> T): T {
        if (metrics.any { it.appearance != null } && nextObjectEditor != null) {
            return nextObjectEditor.mutateMetrics(metrics, authority, deleting, commit)
        }
        check(authority == null) { "OBJECT_WRITE_CHANGED_RELOAD_REQUIRED" }
        if (structural) structuralEditGuard?.requireAllowed()
        return database.withTransaction {
            if (!deleting) metrics.forEach { NextMetricDeletionStore(database).requireWritable(it.uuid) }
            // Read current units in the transaction, never reuse a pre-transaction projection.
            val current = if (!readCurrentMetrics) emptyMap() else metrics.associate { row -> row.id to requireNotNull(metricDao.getMetricById(row.id)) {
                "Metric no longer exists: ${row.id}"
            } }
            commit(current)
        }
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
