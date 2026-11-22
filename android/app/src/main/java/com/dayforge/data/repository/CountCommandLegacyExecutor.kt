package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.CountActionIdentity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** Original local-v4 COUNT mutation inside one private primary T1. The independent T0 is
 * owned by the top-level command entrance. The original public transaction-participant APIs
 * do not enter this class and keep their historic outer-transaction composition contract.
 */
internal class CountCommandLegacyExecutor(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val commands: CountCommandStore) {
    class Attempt internal constructor(internal val owner: CountCommandLegacyExecutor,
        val input: CountCommandRecords.Intent, internal val ticket: CountCommandStore.ExecutionTicket,
        val identity: CountActionIdentity) {
        internal var committed: CountCommandStore.Committed? = null
        fun receipt() = CountCommandReceipt(requireNotNull(committed) { "COMMAND_END_NOT_CONFIRMED" })
    }

    suspend fun add(habit: HabitEntity, identity: CountActionIdentity?, at: Instant, zone: ZoneId, quantity: Int): Attempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val selected = identity ?: CountActionReader(database, tokens, sessions).legacy(habit)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
        require(access.session == selected.session && access.capturedDeviceId == selected.deviceId) { "COUNT_SESSION_CHANGED" }
        val input = CountCommandLegacyInput.add(database, access, habit, selected, at, zone, quantity)
        return register(input, selected)
    }
    suspend fun undo(habit: HabitEntity, identity: CountActionIdentity?, target: CompletionEntity): Attempt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val selected = identity ?: CountActionReader(database, tokens, sessions).legacy(habit)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
        require(access.session == selected.session && access.capturedDeviceId == selected.deviceId) { "COUNT_SESSION_CHANGED" }
        return register(CountCommandLegacyInput.undo(database, access, habit, selected, target), selected)
    }
    suspend fun noCurrent(habit: HabitEntity, identity: CountActionIdentity, selection: LegacyCountNoTarget): CountCommandReceipt {
        CountCommandEntryBoundary.requireTopLevel(database)
        val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
        require(access.session == identity.session && access.capturedDeviceId == identity.deviceId) { "COUNT_SESSION_CHANGED" }
        val input = CountCommandLegacyInput.noCurrent(database, access, habit, identity, selection)
        val saved = commands.register(input)
        require(!saved.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return CountCommandReceipt(commands.completeCapturedNoEffect(requireNotNull(saved.ticket)))
    }

    private suspend fun register(input: CountCommandRecords.Intent, identity: CountActionIdentity): Attempt {
        val saved = commands.register(input)
        require(!saved.alreadyRegistered) { "COMMAND_REQUIRES_RESOLUTION" }
        return Attempt(this, input, requireNotNull(saved.ticket), identity)
    }

    /** The same Store issued Ready only after authenticated current native recovery and
     * complete NoEffects. Re-read the original legacy display provenance; never deserialize
     * an authority or select a different native undo target from later state.
     */
    suspend fun recovered(ready: CountCommandStore.Recovery.Ready): Attempt {
        try {
            require(CountCommandLegacyInput.isLegacy(ready.intent))
            val input = ready.intent.body().getValue("input").jsonObject
            val habit = requireNotNull(database.habitDao().getHabitById(CountCommandRecords.integer(input, "habit_id")))
            require(habit.uuid == CountCommandRecords.uuid(input, "habit_uuid")) { "COUNT_ACTIVITY_CHANGED" }
            val identity = CountActionReader(database, tokens, sessions).legacy(habit)
            require(input.getValue("identity_plan") != JsonNull && input.getValue("identity_plan") == identity.plan) {
                "COUNT_ACTIVITY_CHANGED"
            }
            return Attempt(this, ready.intent, ready.ticket, identity)
        } catch (failure: Throwable) { commands.releaseUnadmitted(ready.ticket); throw failure }
    }

    suspend fun release(attempt: Attempt) {
        require(attempt.owner === this)
        commands.releaseUnadmitted(attempt.ticket)
    }

    suspend fun <T> run(attempt: Attempt, expected: HabitEntity, original: suspend (HabitEntity) -> T): T = try {
        CountCommandEntryBoundary.requireTopLevel(database)
        require(attempt.owner === this && attempt.committed == null)
        val input = attempt.input.body().getValue("input").jsonObject
        require(expected.id == CountCommandRecords.integer(input, "habit_id") && expected.uuid == CountCommandRecords.uuid(input, "habit_uuid"))
        sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val access = requireNotNull(tokens.localCoreWriteAccess()) { "COUNT_SESSION_CHANGED" }
            val identity = attempt.identity
            check(access.session == identity.session && access.capturedDeviceId == identity.deviceId &&
                (access.capabilities == null || "facts.append" in access.capabilities)) { "COUNT_SESSION_CHANGED" }
            val done = commands.producerTransaction(attempt.ticket, access) { participant ->
                require(participant.frozenIntent.text() == attempt.input.text())
                // Same predicates as CountActionReader.writeLegacy, before the original
                // callback. No UI, current summary publication or extra display read here.
                val current = requireNotNull(database.habitDao().getVisibleHabitById(identity.habitId))
                check(identity.legacy != null && current.uuid == identity.habitUuid && current.appearance == null &&
                    identity.plan == SyncV2Mapper.planNode(current) && input.getValue("identity_plan") == identity.plan) {
                    "COUNT_ACTIVITY_CHANGED"
                }
                NextChallengeStore(database).requirePlainInTransaction()
                val frame = CountCommandLegacyPlan.capture(database, participant.frozenIntent)
                val active = Active(currentCoroutineContext()[Job], frame)
                synchronized(frames) { check(frames[database] == null) { "COMMAND_LEGACY_NESTED_FRAME" }; frames[database] = active }
                try {
                    participant.beginBusiness()
                    val value = original(current)
                    participant.stage(frame.proven())
                    // The full domain/source OLD/NEW journal is checked AGAIN after every
                    // command-domain write; a stage trigger/cascade cannot hide extra DML.
                    frame.verifyAfterStage()
                    val candidate = participant.verifyBeforeEnd()
                    check(tokens.localCoreWriteAccess() == access) { "COUNT_SESSION_CHANGED" }
                    CountCommandStore.ProducerValue(value, candidate)
                } finally {
                    frame.close()
                    synchronized(frames) { check(frames.remove(database) === active) }
                }
            }
            attempt.committed = requireNotNull(done.command)
            done.result
        }
    } finally { release(attempt) }

    private class Active(val caller: Job?, val plan: CountCommandLegacyPlan)
    companion object {
        private val frames = WeakHashMap<HabitDatabase, Active>()
        /** Same private original allocation hooks for the Widget's original T1 body. This
         * cannot broaden an optional COUNT certificate or issue a primary participant.
         */
        internal suspend fun <T> withWidgetAllocation(database: HabitDatabase, plan: CountCommandLegacyPlan,
            block: suspend () -> T): T {
            require(CountCommandLegacyWidgetInput.matches(plan.intent) && database.inTransaction())
            val active = Active(currentCoroutineContext()[Job], plan)
            synchronized(frames) { check(frames[database] == null) { "COMMAND_LEGACY_NESTED_FRAME" }; frames[database] = active }
            return try { block() } finally { synchronized(frames) { check(frames.remove(database) === active) } }
        }

        /** Only original allocation points consult this private lexical map. An unbound
         * ordinary call is unchanged; a child Job cannot bind a parent producer's effects.
         */
        suspend fun allocation(database: HabitDatabase): CountCommandLegacyPlan? {
            currentCoroutineContext().ensureActive()
            val active = synchronized(frames) { frames[database] } ?: return null
            check(currentCoroutineContext()[Job] === active.caller && database.inTransaction()) { "COMMAND_LEGACY_FOREIGN_ALLOCATION" }
            return active.plan
        }
    }
}
