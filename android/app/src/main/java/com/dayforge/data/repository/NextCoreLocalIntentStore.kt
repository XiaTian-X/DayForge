package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.TimerCommandRequest
import com.dayforge.data.api.dto.NextSyncPushRequest
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.RoundSyncPushRequest
import com.dayforge.data.api.dto.RoundTimerCommandBatchRequest
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.CompletionActionTiming
import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Explicit NEW v5 producer boundary; not injected into the active v4 writers.
 * The callback is a repository's local business transaction, never network/file/UI work.
 * It must not acquire the non-reentrant account coordinator. Old rows cannot be relabelled,
 * rewritten or consumed here. A proof/permission/cancellation failure rolls back business too.
 */
internal class NextCoreLocalIntentStore(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    suspend fun captureRounds(): NextRoundWriteScope = sessions.exclusive {
        val access = requireNotNull(tokens.localSyncAccess())
        require(tokens.isCurrentSyncAuthorityForValidation(access) && access.deviceId != null)
        database.withTransaction {
            captureRoundsInTransaction(access)
        }
    }

    /** The caller already owns the account lock and the same displayed Room snapshot. */
    internal suspend fun captureRoundsInTransaction(access: com.dayforge.data.local.LocalSyncAccess,
        preferTrusted: Boolean = false, countRead: CountTrustKernel.DisplayRead? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null): NextRoundWriteScope {
        check(database.inTransaction())
        require(tokens.localSyncAccess() == access && tokens.isCurrentSyncAuthorityForValidation(access) && access.deviceId != null)
        check(countRead == null || metricSourceRead == null)
        if (metricSourceRead != null) return metricSourceRead.rounds(database, tokens, access)
        if (preferTrusted) {
            val trusted = if (countRead == null) CountTrustKernel(database, tokens, sessions).displayedRounds(access)
                else countRead.displayedRounds(database, tokens, access)
            trusted?.let { return it }
        }
        val (_, metadata, initials) = NextRoundPendingInitialStore(database).capture(access)
        return NextRoundWriteScope(access.copy(capabilities = access.capabilities.toSet()),
            Json.encodeToString(ChallengeMetadata.serializer(), metadata), initials,
            NextRestartStore(database).pending(access, metadata, initials))
    }

    /** Same displayed snapshot chooses an already accepted profile; never upgrade an old ticket. */
    internal suspend fun captureDisplayedRoundsInTransaction(preferTrusted: Boolean = false,
        countRead: CountTrustKernel.DisplayRead? = null,
        metricSourceRead: CountTrustKernel.MetricSourceRead? = null): NextRoundWriteScope? {
        check(database.inTransaction())
        val contracts = database.openHelper.writableDatabase.query("SELECT challengeContract FROM next_sync_state").use { raw ->
            buildList { while (raw.moveToNext()) {
                require(raw.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER && raw.getLong(0) in 0L..1L)
                add(raw.getLong(0))
            } }
        }
        require(contracts.size <= 1)
        if (contracts.singleOrNull() == 1L) return captureRoundsInTransaction(requireNotNull(tokens.localSyncAccess()), preferTrusted, countRead, metricSourceRead)
        NextChallengeStore(database).requirePlainInTransaction()
        return null
    }

    suspend fun <T> writeRounds(scope: NextRoundWriteScope, writeInTransaction: suspend () -> T): T =
        write(scope.access.session, scope, writeInTransaction).result

    /** Optional proof reuse requested only by the real linked-metric COUNT source path. */
    internal suspend fun <T> writeRoundsWithMetricSource(scope: NextRoundWriteScope, writeInTransaction: suspend () -> T): T =
        write(scope.access.session, scope, writeInTransaction, prepareMetricSourceRead = true).result

    suspend fun <T> write(session: LocalDataSession, writeInTransaction: suspend () -> T): T =
        write(session, null, writeInTransaction).result

    /** Only an actual-END T0 ticket can enter this path. The supplied owner is the same
     * private store that issued that ticket; no Participant reaches writeInTransaction.
     */
    internal suspend fun <T> writeCommand(session: LocalDataSession, scope: NextRoundWriteScope?,
        commands: CountCommandStore, ticket: CountCommandStore.ExecutionTicket,
        prepareMetricSourceRead: Boolean = false, writeInTransaction: suspend () -> T): CountCommandStore.CommittedWrite<T> =
        write(session, scope, writeInTransaction, prepareMetricSourceRead, commands, ticket)

    private suspend fun <T> write(session: LocalDataSession, scope: NextRoundWriteScope?,
        writeInTransaction: suspend () -> T, prepareMetricSourceRead: Boolean = false,
        commands: CountCommandStore? = null, ticket: CountCommandStore.ExecutionTicket? = null): CountCommandStore.CommittedWrite<T> {
        // Reject before acquiring the non-reentrant account lock. A display/read participant
        // must not wrap a writer and accidentally reuse its phase's proof across mutations.
        NextEvidenceReadSession.requireWritePhase()
        require((commands == null) == (ticket == null))
        return writeWhileUnscoped(session, scope, writeInTransaction, prepareMetricSourceRead, commands, ticket)
    }

    private suspend fun <T> writeWhileUnscoped(session: LocalDataSession, scope: NextRoundWriteScope?,
        writeInTransaction: suspend () -> T, prepareMetricSourceRead: Boolean, commands: CountCommandStore?,
        ticket: CountCommandStore.ExecutionTicket?): CountCommandStore.CommittedWrite<T> = sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
        val timing = WriteTiming(CompletionActionTiming.currentOperationId())
        val access = tokens.localCoreWriteAccess()
        if (access == null || access.session != session) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
        timing.mark(WriteStage.ENTRY_AUTH)
        val produce: suspend (CountCommandStore.Participant?) -> CountCommandStore.ProducerValue<T> = { participant ->
            produceInTransaction(access, scope, writeInTransaction, prepareMetricSourceRead, timing, participant)
        }
        val completed = if (commands == null) {
            val value = database.withTransaction { produce(null) }
            CountCommandStore.CommittedWrite(value.result, null)
        } else commands.producerTransaction(requireNotNull(ticket), access) { produce(it) }
        completed.also { timing.mark(WriteStage.TRANSACTION_COMMITTED) }
    }

    private suspend fun <T> produceInTransaction(access: LocalCoreWriteAccess, scope: NextRoundWriteScope?,
        writeInTransaction: suspend () -> T, prepareMetricSourceRead: Boolean, timing: WriteTiming,
        participant: CountCommandStore.Participant?, constructedFact: com.dayforge.data.local.entity.CompletionEntity? = null): CountCommandStore.ProducerValue<T> {
        check(database.inTransaction())
        return run {
            // Primary native journal and PREPARED membership were captured by the private
            // outer bridge before withCapture; the metric source-read anchor remains intact.
            val rounds = NextChallengeStore(database)
            val sql = database.openHelper.writableDatabase
            val trust = CountTrustKernel(database, tokens, sessions)
            trust.withCapture(access, prepareMetricSourceRead) { candidate ->
            val trusted = candidate?.takeIf { it.context.formal == (scope != null) }
            val tables = listOf("sync_outbox", "timer_command_outbox")
            // Reuse raw evidence only within this SELECT-only stage. The session is closed
            // before business writes; the returned values remain comparison inputs, not a cache.
            val prepared = NextEvidenceReadSession.read(database) {
                val roundSnapshot = scope?.let { NextChallengeReadSnapshot.capture(database, it.access) }
                val roundState = scope?.let { requireNotNull(roundSnapshot).borrow(database, it.access) }
                val roundCapture = if (scope == null) {
                    rounds.requirePlainInTransaction(); null
                } else {
                    require(tokens.localSyncAccess() == scope.access && access.capturedDeviceId == scope.access.deviceId &&
                        tokens.isCurrentSyncAuthorityForValidation(scope.access)) { "SYNC_CHALLENGE_CONTEXT_CHANGED" }
                    val initials = NextRoundPendingInitialStore(database).read(scope.access, requireNotNull(roundSnapshot))
                    val metadata = requireNotNull(roundState).second
                    val existing = database.habitDao().getAllHabitsOnce()
                    val restarts = NextRestartStore(database).pending(scope.access, metadata, initials)
                    if (prepareMetricSourceRead) trust.captureMetricSourceSeed(trusted, scope.access, roundSnapshot, initials, restarts)
                    NextRoundOperationCapture(database, scope, metadata, existing, initials, restarts)
                }
                NextRequestSql.requireOutboxEnabled(sql)
                PreparedWrite(roundState?.first, roundCapture,
                    tables.associateWith { NextRequestSql.sources(sql, it) },
                    tables.associateWith { NextRequestSql.watermark(sql, it) })
            }
            val roundCapture = prepared.roundCapture
            val before = prepared.before
            val watermarks = prepared.watermarks
            val command = participant?.let {
                val intent = it.frozenIntent
                // The independent observation's complete business journal starts while the
                // original owner is still PREPARING. Its later pre-insert plan only borrows
                // this same transaction/caller/epoch capability; it cannot issue one in BUSINESS.
                val metricBoundary = if (intent.action == CountCommandRecords.Action.METRIC_APPEND) {
                    val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
                    owner.maintenance(database) { owner.capturePrimaryBusinessBoundary() }
                } else null
                CommandCapture(intent, constructedFact, metricBoundary, currentCoroutineContext()[Job])
            }
            timing.mark(WriteStage.PREPARE)
            participant?.beginBusiness()
            val result = trust.withLocalPreparation(trusted) {
                if (command == null) writeInTransaction() else withCommandPreparation(command, writeInTransaction)
            }
            timing.mark(WriteStage.BUSINESS)
            // Expected local observation semantics were frozen before insertAll. Bind only the
            // existing trigger's allocation outputs before any origin producer can run.
            val metricEffects = trust.bindPreparedMetricSources(trusted)
            NextRequestSql.requireOutboxEnabled(sql)
            val sourceDelta = trust.prepareSourceDelta(trusted, before, watermarks, metricEffects)
            val expectedSources = if (sourceDelta == null) tables.associateWith { NextRequestSql.sources(sql, it) } else null
            val createdOrigins = mutableMapOf<Pair<String, String>, String>()
            val createdDependencies = mutableMapOf<String, String>()
            val countDays = mutableMapOf<Pair<Long, String>, String>()
            val causal = NextStructuralCausalStore(database)
            for ((index, table) in tables.withIndex()) {
                val created = if (sourceDelta != null) sourceDelta.newSources(table) else {
                    val after = NextRequestSql.sources(sql, table)
                    if (before.getValue(table).any { (id, hash) -> after[id] != hash })
                        rejectNextRequest(NextRequestException.Reason.SOURCE_CHANGED)
                    after.filterKeys { it !in before.getValue(table) }
                }
                for ((id, hash) in created) {
                    if (id <= watermarks.getValue(table)) rejectNextRequest(NextRequestException.Reason.OLD_INTENT)
                    val kind = if (index == 0) NEXT_OPERATION else NEXT_TIMER
                    val requestId: String
                    val permission: String
                    val bytes: ByteArray
                    val decodedOperation: SyncV2Operation?
                    val commandSource: SyncOutboxEntity?
                    if (kind == NEXT_OPERATION) {
                        val row = database.syncOutboxDao().getById(id)
                            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (row.attemptedAt != null || row.attemptCount != 0 || row.deadLetteredAt != null ||
                            row.payloadJson != null || row.baseRevision != null || row.basePayloadJson != null ||
                            row.entityUuid != row.wireEntityUuid || row.lastError != null || row.errorCode != null)
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        commandSource = row
                        requestId = row.operationId
                        permission = if (row.recordType in setOf("completion", "metric_log")) "facts.append" else "structure.write"
                        val operation = operationInternal(row, command)
                        if (operation.action == "upsert") {
                            val deleted = NextPlanDeletionStore(database)
                            val metrics = NextMetricDeletionStore(database)
                            if (operation.entityType == "plan_node") {
                                deleted.requireWritable(operation.entityUuid)
                                operation.payload["parent_uuid"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                                    ?.let { deleted.requireWritable(it) }
                            } else if (operation.entityType in setOf("activity_event", "activity_metric_link")) {
                                deleted.requireWritable(operation.payload.getValue("activity_uuid").jsonPrimitive.content)
                            }
                            if (operation.entityType == "metric") metrics.requireWritable(operation.entityUuid)
                            if (operation.entityType in setOf("metric_observation", "activity_metric_link"))
                                metrics.requireWritable(operation.payload.getValue("metric_uuid").jsonPrimitive.content)
                            if (operation.entityType == "activity_metric_link") metrics.requireLinkWritable(
                                requireNotNull(database.habitMetricLinkDao().getLinkByUuid(operation.entityUuid)))
                        }
                        val countFrontier = NextCountDayStore(database).capturePlanFrontier(operation, id, access,
                            tokens::localSyncAccess)
                        val round = roundCapture?.capture(operation)?.copy(countPlanFrontier = countFrontier)
                        timing.mark(WriteStage.MAP_OPERATION)
                        // Pure work on owned immutable inputs: one dispatcher round trip and one
                        // strict decode of these exact bytes. No SQL or authority proof is reused.
                        val encoded = withContext(Dispatchers.Default) {
                            val body = if (round == null) countFrontier?.let {
                                encodeSyncRequest(NextCountOperationIntent.serializer(), NextCountOperationIntent(operation, it))
                            } ?: encodeSyncRequest(SyncV2Operation.serializer(), operation)
                                else encodeSyncRequest(NextRoundOperationIntent.serializer(), round)
                            val verified = decodeNextOperationIntent(body.toString(Charsets.UTF_8))
                            // Prove full-envelope limits while still able to roll back the local write.
                            // This placeholder is only a size/type oracle, never persisted or transmitted.
                            if (round == null) {
                                val envelope = encodeSyncRequest(NextSyncPushRequest.serializer(),
                                    NextSyncPushRequest(SIZE_DEVICE, listOf(operation)))
                                decodeFrozenSyncRequest(envelope, NextSyncPushRequest.serializer())
                            } else {
                                val envelope = encodeSyncRequest(RoundSyncPushRequest.serializer(),
                                    RoundSyncPushRequest(1, round.capturedDeviceId, listOf(operation), listOf(round.context)))
                                decodeFrozenSyncRequest(envelope, RoundSyncPushRequest.serializer())
                            }
                            body to verified
                        }
                        bytes = encoded.first
                        decodedOperation = encoded.second
                        timing.mark(WriteStage.ENCODE_OPERATION)
                    } else {
                        commandSource = null
                        decodedOperation = null
                        val row = database.timeLogDao().getTimerCommand(id)
                            ?: rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        if (row.attemptCount != 0 || row.deadLetteredAt != null || row.lastError != null || row.errorCode != null)
                            rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                        requestId = row.commandId; permission = "timer.control"
                        val originalCommand = timerRequest(row)
                        val intent = if (row.commandType == "start")
                            NextTimerPolicyStore(database).capture(originalCommand, access, scope?.pendingRestarts?.get(originalCommand.activityUuid))
                            else NextTimerIntent(originalCommand)
                        val command = intent.command
                        val activity = row.activityUuid ?: database.timeLogDao().getTimeLogByUuid(row.sessionUuid)?.let {
                            database.habitDao().getHabitById(it.habitId)?.uuid
                        }
                        activity?.let { NextPlanDeletionStore(database).requireWritable(it) }
                        val round = roundCapture?.captureTimer(intent)
                        bytes = if (round != null) encodeSyncRequest(NextRoundTimerIntent.serializer(), round)
                            else if (command.startPolicy != null)
                            encodeSyncRequest(NextTimerIntent.serializer(), intent)
                            else encodeSyncRequest(TimerCommandRequest.serializer(), command)
                        decodeNextTimerIntent(bytes.toString(Charsets.UTF_8))
                        if (round == null) {
                            val envelope = encodeSyncRequest(TimerCommandBatchRequest.serializer(),
                                TimerCommandBatchRequest(SIZE_DEVICE, listOf(command)))
                            decodeFrozenSyncRequest(envelope, TimerCommandBatchRequest.serializer())
                        } else {
                            val envelope = encodeSyncRequest(RoundTimerCommandBatchRequest.serializer(),
                                RoundTimerCommandBatchRequest(1, round.capturedDeviceId, listOf(command), listOf(round.context)))
                            decodeFrozenSyncRequest(envelope, RoundTimerCommandBatchRequest.serializer())
                        }
                    }
                    if (!isContractUuid(requestId)) rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
                    if (access.capabilities != null && permission !in access.capabilities)
                        rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
                    val dao = database.nextRequestDao()
                    if (dao.origin(kind, requestId) != null || dao.transmission(kind, requestId) != null || dao.acceptance(kind, requestId) != null)
                        rejectNextRequest(NextRequestException.Reason.REQUEST_ID_REUSED)
                    val origin = NextRequestOriginEntity(kind, requestId, id, 5, access.session.authentication.userId,
                        access.session.serverInstanceId, access.session.syncEpoch, hash, bytes.toString(Charsets.UTF_8))
                    if (kind == NEXT_TIMER) trust.freezeTimerOrigin(trusted, origin)
                    dao.insertOrigin(origin)
                    check(NextRequestSql.rowHash(database, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, requestId)) != null)
                    check(dao.origin(kind, requestId) == origin)
                    if (kind == NEXT_OPERATION) {
                        val operation = requireNotNull(decodedOperation)
                        causal.capture(origin, operation, access)
                        NextCountDayStore(database).bind(origin, operation, access)
                        if ("count_policy" in operation.payload) {
                            val habit = requireNotNull(database.habitDao().getHabitByUuid(operation.payload.getValue("activity_uuid").jsonPrimitive.content))
                            val date = operation.payload.getValue("local_date").jsonPrimitive.content
                            countDays[habit.id to date] = requireNotNull(NextRequestSql.rowHash(database, "count_days", "habitId=? AND localDate=?", arrayOf(habit.id, date)))
                        }
                    }
                    createdOrigins[kind to requestId] = requireNotNull(NextRequestSql.rowHash(database,
                        "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, requestId)))
                    if (kind == NEXT_OPERATION) NextRequestSql.rowHash(database, "next_structural_dependencies", "operationId=?", arrayOf(requestId))
                        ?.let { createdDependencies[requestId] = it }
                    // Private complete accumulator: exact source, strict-decoded operation,
                    // inserted origin and native hash from this original producer iteration.
                    if (command != null && kind == NEXT_OPERATION) command.produced += CountCommandProducerPlan.Produced(
                        requireNotNull(commandSource), requireNotNull(decodedOperation), origin, createdOrigins.getValue(kind to requestId))
                }
            }
            timing.mark(WriteStage.CAPTURE_ORIGINS)
            if (command != null) {
                val proof = requireNotNull(command.plan) { "COMMAND_BUSINESS_PLAN_NOT_FROZEN" }
                    .finish(command.produced.toList(), createdOrigins.keys.toSet())
                requireNotNull(participant).stage(proof)
                command.plan?.verifyIndependentAfterStage()
            }
            // Command DML is now included in every original final business/source/round
            // proof below. Never put a write after that barrier merely because its table is
            // local: its triggers/cascades must not escape the original complete journal.
            // Start empty after ALL writes. In particular, a missing receipt or an old origin
            // from preparation cannot hide changes made by a later origin/day/queue trigger.
            // The queue proof must run outside the SELECT-only evidence session: it owns
            // the original transaction journal boundary, never a delegated read capability.
            val deltaVerified = sourceDelta?.let { trust.verifySourceDelta(requireNotNull(trusted), it) } == true
            trust.withFinalMetricChallengeRead(trusted, metricEffects, scope?.access) { revalidatedChallenge ->
                if (!deltaVerified) check(tables.associateWith { NextRequestSql.sources(sql, it) } ==
                    (sourceDelta?.completeExpected() ?: requireNotNull(expectedSources)))
                for ((key, hash) in createdOrigins) check(NextRequestSql.rowHash(database, "next_request_origins",
                    "kind=? AND requestId=?", arrayOf(key.first, key.second)) == hash)
                for ((id, hash) in createdDependencies) {
                    check(NextRequestSql.rowHash(database, "next_structural_dependencies", "operationId=?", arrayOf(id)) == hash)
                    causal.auditCaptured(id, access)
                }
                for ((key, hash) in countDays) {
                    check(NextRequestSql.rowHash(database, "count_days", "habitId=? AND localDate=?", arrayOf(key.first, key.second)) == hash)
                    requireNotNull(NextCountDayStore(database).read(requireNotNull(database.habitDao().getHabitById(key.first)), key.second))
                }
                NextRequestSql.requireOutboxEnabled(sql)
                if (scope != null) {
                    val finalSnapshot = revalidatedChallenge() ?: NextChallengeReadSnapshot.capture(database, scope.access)
                    val actual = finalSnapshot.borrow(database, scope.access)
                    require(actual.first == requireNotNull(prepared.roundState)) { "SYNC_CHALLENGE_CURSOR_CHANGED" }
                    roundCapture!!.verify(finalSnapshot)
                }
            }
            if (scope != null) {
                require(tokens.localSyncAccess() == scope.access && tokens.isCurrentSyncAuthorityForValidation(scope.access))
            }
            timing.mark(WriteStage.FINAL_AUDIT)
            CountCommandCostTiming.measure(Step.TRUST_FINISH) {
                if (trusted?.timerPreparationAttempted == true) trust.finishTimer(trusted, trusted.timerPreparation)
                else trust.finish(trusted, if (metricEffects == null) CountTrustKernel.Mutation.LOCAL_COUNT
                    else CountTrustKernel.Mutation.LOCAL_OBSERVATIONS, localObservations = metricEffects)
            }
            // Read-only primary footer follows the original final barriers. Actual command
            // writes were staged before them; fresh authority and actual END remain last.
            command?.plan?.verifyIndependentAfterStage()
            val commandCandidate = participant?.let {
                CountCommandCostTiming.measure(Step.COMMAND_FOOTER) { it.verifyBeforeEnd() }
            }
            CountCommandCostTiming.measure(Step.FINAL_AUTHORITY) {
                if (tokens.localCoreWriteAccess() != access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            }
            timing.mark(WriteStage.EXIT_AUTH)
            CountCommandStore.ProducerValue(result, commandCandidate)
            }
        }
    }

    internal class PreparedCount(val executor: CountAddExecutor, val preparation: CountAddExecutor.Preparation)

    /** One admitted command T1. A/B contains values only; original fallback never exits this
     * transaction, reacquires a used ticket, or inserts an already-created origin twice.
     */
    internal suspend fun writePreparedCountCommand(session: LocalDataSession, scope: NextRoundWriteScope,
        commands: CountCommandStore, ticket: CountCommandStore.ExecutionTicket, prepared: PreparedCount,
        original: suspend () -> Long): CountCommandStore.CommittedWrite<Long> {
        NextEvidenceReadSession.requireWritePhase()
        return sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) {
            val timing = WriteTiming(CompletionActionTiming.currentOperationId())
            val access = prepared.executor.commandEntry(prepared.preparation)
            if (access.session != session) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
            timing.mark(WriteStage.ENTRY_AUTH)
            commands.producerTransaction(ticket, access) { participant ->
                when (val selected = prepared.executor.commitInPrimaryTransaction(prepared.preparation, access, participant)) {
                    is CountAddExecutor.CommandSelection.Original -> produceInTransaction(access, scope, original,
                        false, timing, participant, selected.constructedFact)
                    is CountAddExecutor.CommandSelection.Applied -> {
                        val candidate = participant.verifyBeforeEnd()
                        if (tokens.localCoreWriteAccess() != access) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
                        timing.mark(WriteStage.EXIT_AUTH)
                        CountCommandStore.ProducerValue(selected.id, candidate)
                    }
                }
            }.also { timing.mark(WriteStage.TRANSACTION_COMMITTED) }
        }
    }

    private data class PreparedWrite(
        val roundState: NextSyncStateEntity?,
        val roundCapture: NextRoundOperationCapture?,
        val before: Map<String, Map<Long, String>>,
        val watermarks: Map<String, Long>
    )

    private class CommandCapture(val intent: CountCommandRecords.Intent,
        val constructedFact: com.dayforge.data.local.entity.CompletionEntity?,
        val metricBoundary: CountTrustRuntime.PrimaryBusinessBoundary?, val verificationJob: Job?) {
        var plan: CountCommandProducerPlan? = null
        val produced = mutableListOf<CountCommandProducerPlan.Produced>()
    }
    private class CommandPreparation(val capture: CommandCapture, val caller: Job?) {
        var active = true
        var allocation: CommandAllocation? = null
    }
    internal sealed interface CommandAllocation
    private class IssuedAllocation(val scope: CommandPreparation, val plan: CountCommandProducerPlan) : CommandAllocation {
        var bound = false
    }
    private suspend fun <T> withCommandPreparation(capture: CommandCapture, block: suspend () -> T): T {
        val scope = CommandPreparation(capture, currentCoroutineContext()[Job])
        synchronized(commandPreparations) { require(commandPreparations[database] == null); commandPreparations[database] = scope }
        return try { block() } finally {
            scope.active = false
            synchronized(commandPreparations) { if (commandPreparations[database] === scope) commandPreparations.remove(database) }
        }
    }
    companion object {
        private const val SIZE_DEVICE = "00000000-0000-4000-8000-000000000000"
        private val commandPreparations = WeakHashMap<HabitDatabase, CommandPreparation>()
        private suspend fun commandPreparation(database: HabitDatabase): CommandPreparation? {
            currentCoroutineContext().ensureActive()
            val scope = synchronized(commandPreparations) { commandPreparations[database] } ?: return null
            require(scope.active && scope.caller === currentCoroutineContext()[Job] && scope.caller?.isActive != false && database.inTransaction()) {
                "COMMAND_PREPARATION_CALLER_EXPIRED"
            }
            return scope
        }
        /** Immutable requested input only; never a Participant, signer, admission or SQL handle. */
        internal suspend fun commandInput(database: HabitDatabase): CountCommandRecords.Intent? = commandPreparation(database)?.capture?.intent
        internal suspend fun commandConstructedFact(database: HabitDatabase): com.dayforge.data.local.entity.CompletionEntity? =
            commandPreparation(database)?.capture?.constructedFact
        private fun registerPlan(scope: CommandPreparation, plan: CountCommandProducerPlan): CommandAllocation {
            require(scope.capture.plan == null && scope.allocation == null) { "COMMAND_PLAN_ALREADY_FROZEN" }
            scope.capture.plan = plan
            return IssuedAllocation(scope, plan).also { scope.allocation = it }
        }
        internal suspend fun prepareCommandCount(database: HabitDatabase, habit: com.dayforge.data.local.entity.HabitEntity,
            fact: com.dayforge.data.local.entity.CompletionEntity, day: com.dayforge.domain.model.CountDayPolicy?,
            reactivationBase: Long?): CommandAllocation? {
            val scope = commandPreparation(database) ?: return null
            return registerPlan(scope, CountCommandProducerPlan.countAdd(database, scope.capture.intent, habit, fact, day, reactivationBase))
        }
        internal suspend fun prepareCommandMetrics(database: HabitDatabase, logs: List<com.dayforge.data.local.entity.MetricLogEntity>,
            metrics: Map<Long, com.dayforge.data.local.entity.MetricEntity>): CommandAllocation? {
            val scope = commandPreparation(database) ?: return null
            return registerPlan(scope, if (scope.capture.intent.action == CountCommandRecords.Action.METRIC_APPEND)
                CountCommandProducerPlan.independentMetric(database, scope.capture.intent, logs, metrics,
                    requireNotNull(scope.capture.metricBoundary) { "COMMAND_METRIC_PREPARING_BOUNDARY_REQUIRED" },
                    scope.capture.verificationJob)
            else CountCommandProducerPlan.metrics(database, scope.capture.intent, logs, metrics))
        }
        internal suspend fun requireCommandUndoTarget(database: HabitDatabase, fact: com.dayforge.data.local.entity.CompletionEntity) {
            val scope = commandPreparation(database) ?: return
            val intent = scope.capture.intent
            require(intent.action == CountCommandRecords.Action.COUNT_UNDO && intent.capture == CountCommandRecords.Capture.EXECUTION_INPUT)
            val input = intent.body().getValue("input").jsonObject
            require(CountCommandRecords.uuid(input, "target_fact_uuid") == fact.uuid &&
                CountCommandRecords.uuid(input, "habit_uuid") == fact.habitUuid &&
                CountCommandRecords.canonical(input.getValue("target_native")) ==
                    CountCommandRecords.canonical(CountCommandProducerPlan.frozenCompletion(fact))) { "COMMAND_UNDO_TARGET_CHANGED" }
            CountCommandProducerPlan.requireSelectedNative(database, fact)
        }
        internal suspend fun prepareCommandNoEffect(database: HabitDatabase, reason: CountCommandRecords.NoEffectReason) {
            val scope = commandPreparation(database) ?: return
            registerPlan(scope, CountCommandProducerPlan.noEffect(database, scope.capture.intent, reason))
        }
        internal suspend fun bindCommandIds(database: HabitDatabase, allocation: CommandAllocation?, ids: List<Long>) {
            if (allocation == null) { require(commandPreparation(database) == null) { "COMMAND_ALLOCATION_REQUIRED" }; return }
            val scope = requireNotNull(commandPreparation(database)) { "COMMAND_ALLOCATION_SCOPE_ENDED" }
            val issued = allocation as? IssuedAllocation ?: error("COMMAND_FOREIGN_ALLOCATION")
            require(issued.scope === scope && scope.allocation === issued && scope.capture.plan === issued.plan && !issued.bound) {
                "COMMAND_ALLOCATION_CHANGED"
            }
            issued.plan.bindInsertedIds(ids); issued.bound = true
        }
    }

    internal suspend fun operation(row: SyncOutboxEntity): SyncV2Operation = operationInternal(row, null)
    private suspend fun operationInternal(row: SyncOutboxEntity, command: CommandCapture?): SyncV2Operation {
        require(isContractUuid(row.entityUuid) && row.action in setOf("upsert", "delete"))
        val type = when (row.recordType) {
            "habit" -> "plan_node"; "metric" -> "metric"; "completion" -> "activity_event"
            "metric_log" -> "metric_observation"; "link" -> "activity_metric_link"
            else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE) // once has its own durable producer
        }
        val revert = row.recordType == "completion" && row.action == "delete"
        var wireId = row.entityUuid
        val payload = if (revert) {
            // Capture the NEW undo's time and stable identity now, never on a later send.
            val activity = requireNotNull(row.referenceUuid).also { require(isContractUuid(it)) }
            val zone = ZoneId.systemDefault()
            val instant = Instant.ofEpochMilli(row.createdAt)
            wireId = if (command == null) UUID.randomUUID().toString() else {
                require(command.plan == null) { "COMMAND_MULTIPLE_UNDO_EFFECTS" }
                command.plan = CountCommandProducerPlan.undo(database, command.intent, row, zone)
                CountCommandRecords.uuid(command.intent.body().getValue("input").jsonObject, "reserved_revert_uuid")
            }
            buildJsonObject {
                put("activity_uuid", activity); put("event_type", "revert"); put("reverts_event_uuid", row.entityUuid)
                put("occurred_at", instant.toString()); put("timezone", zone.id)
                put("local_date", instant.atZone(zone).toLocalDate().toString()); put("source_type", "app")
            }
        } else if (row.action == "delete") {
            require(row.recordType in setOf("habit", "metric", "metric_log", "link"))
            NextPlanDeletionStore.payload(row) ?: NextMetricDeletionStore.payload(row) ?:
                SyncV2Mapper.deletePayload(row.recordType, row.referenceUuid)
        } else when (row.recordType) {
            "habit" -> NextStructureMapper.writePlan(requireNotNull(database.habitDao().getHabitByUuid(row.entityUuid)))
            "metric" -> NextStructureMapper.writeMetric(requireNotNull(database.metricDao().getMetricByUuid(row.entityUuid)))
            "completion" -> {
                val fact = requireNotNull(database.completionDao().getCompletionByUuid(row.entityUuid))
                val habit = requireNotNull(database.habitDao().getHabitById(fact.habitId))
                require(habit.habitType in setOf(HabitType.CHECK_IN, HabitType.COUNTING) && habit.completionPolicy == "recurring" &&
                    fact.oneTimeAction == null && fact.oneTimeExpectedVersion == null &&
                    fact.oneTimeExpectedHeadEventUuid == null && fact.oneTimeRevertsEventUuid == null)
                val body = SyncV2Mapper.completion(fact, habit)
                if (habit.habitType == HabitType.COUNTING && habit.appearance != null) {
                    val day = requireNotNull(NextCountDayStore(database).read(habit, fact.recordedLocalDate, allowUnbound = true))
                    JsonObject(body + ("count_policy" to day.policy.toJson()))
                } else body
            }
            "metric_log" -> {
                val fact = requireNotNull(database.metricLogDao().getLogByUuid(row.entityUuid))
                val metric = requireNotNull(database.metricDao().getMetricById(fact.metricId))
                SyncV2Mapper.metricObservation(fact, metric.uuid)
            }
            "link" -> SyncV2Mapper.link(requireNotNull(database.habitMetricLinkDao().getLinkByUuid(row.entityUuid)))
            else -> rejectNextRequest(NextRequestException.Reason.INVALID_LOCAL_STATE)
        }
        if (!revert) NextRequestSql.rowHash(database, "sync_entity_state",
            "entityType=? AND entityUuid=?", arrayOf(type, row.entityUuid))
        val shadow = if (revert) null else database.syncOutboxDao().getState(type, row.entityUuid)
        require(shadow == null || (!shadow.deleted && shadow.revision > 0))
        val base = shadow?.revision
        return SyncV2Operation(row.operationId, type, wireId, if (revert) "upsert" else row.action, base, payload)
            .also(::validateNextSyncOperation)
    }
}

private enum class WriteStage { ENTRY_AUTH, PREPARE, BUSINESS, MAP_OPERATION, ENCODE_OPERATION, CAPTURE_ORIGINS, FINAL_AUDIT, EXIT_AUTH, TRANSACTION_COMMITTED }

/** Opt-in durations; no business data, identity, credentials, or change to transaction order. */
private class WriteTiming(private val operationId: Long?) {
    private var previous = if (CompletionActionTiming.enabled) android.os.SystemClock.elapsedRealtime() else 0L
    private var previousNanos = if (CompletionActionTiming.enabled) android.os.SystemClock.elapsedRealtimeNanos() else 0L
    fun mark(stage: WriteStage) {
        if (!CompletionActionTiming.enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        val nowNanos = android.os.SystemClock.elapsedRealtimeNanos()
        val operation = operationId?.let { " operation_id=$it" }.orEmpty()
        android.util.Log.d("LocalWriteTiming", "stage=$stage elapsed_ms=${now - previous}$operation " +
            "stage_start_ns=$previousNanos stage_end_ns=$nowNanos")
        previous = now
        previousNanos = nowNanos
    }
}

internal fun timerRequest(row: TimerCommandEntity): TimerCommandRequest {
    require(isContractUuid(row.commandId) && isContractUuid(row.sessionUuid) && row.sequence > 0 &&
        row.commandType in setOf("start", "pause", "resume", "stop", "cancel", "takeover") &&
        row.expectedControlGeneration >= 0 && (row.expectedRevision == null || row.expectedRevision > 0) &&
        (row.activeElapsedMillis == null || row.activeElapsedMillis in 0..86_400_000))
    row.activityUuid?.let { require(isContractUuid(it)) }
    row.timezone?.let { require(it in ZoneId.getAvailableZoneIds()) }
    val instant = Instant.ofEpochMilli(row.occurredAt)
    require(instant.atZone(ZoneId.of("UTC")).year in 1..9999)
    return TimerCommandRequest(row.commandId, row.sessionUuid, row.sequence, row.commandType,
        instant.toString(), row.expectedControlGeneration, row.expectedRevision, row.activityUuid, row.timezone, row.activeElapsedMillis)
}
