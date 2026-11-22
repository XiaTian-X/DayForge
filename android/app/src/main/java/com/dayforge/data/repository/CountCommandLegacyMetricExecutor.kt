package com.dayforge.data.repository

import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Private child producer, with the same primary Store as its COUNT parent. No ordinary
 * callback obtains the participant, signer or a generic SQL handle. The original legacy
 * prompt checks execute inside this existing T1, never a nested non-reentrant account lock.
 */
internal class CountCommandLegacyMetricExecutor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val commands: CountCommandStore) {
    class Attempt internal constructor(internal val owner: CountCommandLegacyMetricExecutor,
        val input: CountCommandRecords.Intent, internal val ticket: CountCommandStore.ExecutionTicket,
        val parent: CountCommandReceipt, val claim: HabitPreferenceClaim, val habit: HabitEntity,
        metrics: List<MetricEntity>) {
        val metrics: List<MetricEntity> = java.util.Collections.unmodifiableList(metrics.toList())
        internal var committed: CountCommandStore.Committed? = null
        fun receipt() = CountCommandReceipt(requireNotNull(committed) { "COMMAND_END_NOT_CONFIRMED" })
    }
    suspend fun begin(parent: CountCommandReceipt, claim: HabitPreferenceClaim, preferences: HabitPreferenceRepository,
        habit: HabitEntity, metrics: List<MetricEntity>, values: List<MetricValueDraft>, recordedAt: Long): Attempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
        require(claim.session == access.session) { "HABIT_PREFERENCE_SESSION_CHANGED" }
        val input = CountCommandLegacyMetricInput.create(database, access, parent, claim, preferences, habit, metrics, values, recordedAt)
        check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
        val saved = commands.registerChild(parent.committed.reference, input)
        require(!saved.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return Attempt(this, input, requireNotNull(saved.ticket), parent, claim, habit, metrics)
    }
    suspend fun recovered(ready: CountCommandStore.Recovery.Ready, preferences: HabitPreferenceRepository): Attempt = try {
        require(CountCommandLegacyInput.isLegacy(ready.intent) && ready.intent.action == CountCommandRecords.Action.LINKED_METRICS)
        val input = ready.intent.body().getValue("input").jsonObject
        val habit = requireNotNull(database.habitDao().getVisibleHabitById(CountCommandRecords.integer(input, "habit_id")))
        check(habit.uuid == CountCommandRecords.uuid(input, "habit_uuid") && habit.appearance == null) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
        val metrics = input.getValue("metrics").jsonArray.map { it.jsonObject }.map { frozen ->
            requireNotNull(database.metricDao().getVisibleMetricById(CountCommandRecords.integer(frozen, "metric_id"))).also {
                check(it.uuid == CountCommandRecords.uuid(frozen, "metric_uuid") && it.appearance == null) { "METRIC_PROMPT_METADATA_CHANGED" }
            }
        }
        val widget = input.getValue("widget_claim").takeUnless { it == JsonNull }?.let { LegacyWidgetRestartClaim.decode(CountCommandRecords.canonical(it)) }
        val claim = preferences.read(habit, widgetIdentity = widget).claim
        val parent = CountCommandReceipt(commands.recoverParent(ready.ticket.reference))
        Attempt(this, ready.intent, ready.ticket, parent, claim, habit, metrics)
    } catch (error: Throwable) { commands.releaseUnadmitted(ready.ticket); throw error }

    suspend fun release(attempt: Attempt) { require(attempt.owner === this); commands.releaseUnadmitted(attempt.ticket) }
    suspend fun <T> run(attempt: Attempt, preferences: HabitPreferenceRepository, original: suspend () -> T): T = try {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(attempt.owner === this && attempt.committed == null)
        sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            check(ReplicaPresentationRepository(database, tokens, sessions).isOpenWhileAccountLocked()) { "HABIT_PREFERENCE_RECOVERY_PENDING" }
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
            check(tokens.localFactAccess()?.session == attempt.claim.session && access.session == attempt.claim.session) { "HABIT_PREFERENCE_SESSION_CHANGED" }
            val done = commands.producerTransaction(attempt.ticket, access) { participant ->
                require(participant.frozenIntent.text() == attempt.input.text())
                preferences.requireWidgetWriteInTransaction(attempt.claim)
                check(database.habitDao().getVisibleHabitById(attempt.habit.id) == attempt.habit) { "METRIC_PROMPT_ACTIVITY_CHANGED" }
                for (metric in attempt.metrics) {
                    check(database.metricDao().getVisibleMetricById(metric.id) == metric) { "METRIC_PROMPT_METADATA_CHANGED" }
                    val link = requireNotNull(database.habitMetricLinkDao().getLink(attempt.habit.id, metric.id)) { "METRIC_PROMPT_LINK_CHANGED" }
                    NextMetricDeletionStore(database).requireLinkWritable(link)
                    check(link.isActive && link.promptOnComplete && link.habitUuid == attempt.habit.uuid && link.metricUuid == metric.uuid) { "METRIC_PROMPT_LINK_CHANGED" }
                    val frozen = attempt.input.body().getValue("input").jsonObject.getValue("metrics").jsonArray.map { it.jsonObject }
                        .single { CountCommandRecords.integer(it, "metric_id") == metric.id }
                    check(CountCommandInputs.metricLink(link) == frozen.getValue("link")) { "METRIC_PROMPT_LINK_CHANGED" }
                }
                val plan = CountCommandLegacyMetricPlan.capture(database, participant.frozenIntent)
                val active = Active(currentCoroutineContext()[Job], plan)
                synchronized(frames) { check(frames[database] == null) { "COMMAND_LEGACY_METRIC_NESTED_FRAME" }; frames[database] = active }
                try {
                    participant.beginBusiness()
                    val value = original()
                    participant.stage(CountCommandProducerPlan.Proven.verifiedLegacyMetrics(plan))
                    plan.verifyAfterStage()
                    preferences.requireWidgetWriteInTransaction(attempt.claim)
                    val candidate = participant.verifyBeforeEnd()
                    check(tokens.localCoreWriteAccess() == access && tokens.localFactAccess()?.session == attempt.claim.session) { "COUNT_SESSION_CHANGED" }
                    CountCommandStore.ProducerValue(value, candidate)
                } finally {
                    plan.close(); synchronized(frames) { check(frames.remove(database) === active) }
                }
            }
            attempt.committed = requireNotNull(done.command); done.result
        }
    } finally { release(attempt) }

    private class Active(val caller: Job?, val plan: CountCommandLegacyMetricPlan)
    companion object {
        private val frames = WeakHashMap<HabitDatabase, Active>()
        suspend fun allocation(database: HabitDatabase): CountCommandLegacyMetricPlan? {
            currentCoroutineContext().ensureActive()
            val active = synchronized(frames) { frames[database] } ?: return null
            check(currentCoroutineContext()[Job] === active.caller && database.inTransaction()) { "COMMAND_LEGACY_METRIC_FOREIGN_ALLOCATION" }
            return active.plan
        }
    }
}
