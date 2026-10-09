package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.TimerCommandResult
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NextSyncAttention(val blockedRequests: Set<String>) : IllegalStateException("SYNC_REQUIRES_ATTENTION")
internal class NextSyncRetryRequired : IllegalStateException("SYNC_RETRY_REQUIRED")

/**
 * Injectable complete v5 synchronization entry. No automatic protocol activation: the v4
 * scheduler stays untouched until all writers/consumers and the server switch together.
 * Only this runtime's mutex spans HTTP, never the account mutex or a Room transaction.
 */
@Singleton
internal class NextSyncRuntime @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val http: NextSyncHttp,
    private val preferences: PreferencesManager
) {
    private val mutex = Mutex()
    private val core = NextCoreRequestStore(database, tokens, sessions, http)
    private val timers = NextTimerRequestStore(database, tokens, sessions, core)
    private val localOnce = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
    private val onceFacts = OneTimeAcceptedEventStore(database, tokens, sessions, localOnce, timerRequests = timers)
    private val once = NextOneTimeRequestStore(database, tokens, sessions, http, onceFacts, core)
    private val merge = NextSyncMergeStore(database, tokens, sessions, onceFacts, timers)
    private val restart = NextChallengeRestartRepository(database, tokens, sessions, http)

    /** Captures authority once. Failure, unsupported v5, pending/conflicting work are never success. */
    suspend fun sync(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null) = syncProfile(false, progress, afterSync)

    /** Explicit complete profile coordinator; not the formal UI/Worker activation switch. */
    suspend fun syncRounds(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null) = syncProfile(true, progress, afterSync)

    private suspend fun syncProfile(challengeProfile: Boolean, progress: (SyncProgress) -> Unit,
        afterSync: (suspend () -> Unit)?) = mutex.withLock {
        val access = sessions.exclusive {
            val captured = requireNotNull(tokens.localSyncAccess())
            captured.copy(capabilities = captured.capabilities.toSet())
        }
        // A legacy/unknown queue cannot be converted to profile sources during bootstrap.
        if (challengeProfile && merge.state(access, true) == null) {
            requireNoChallengeUpload(access)
            progress(SyncProgress.Recovering)
            val expected = merge.challengeBootstrapExpectation(access)
            val snapshot = http.session(access) { it.roundBootstrap() } ?: unsupported()
            merge.bootstrap(access, expected, snapshot, cacheOnly = true)
        } else merge.state(access, challengeProfile)
        var pages = 0
        suspend fun catchUpRounds() {
            var state = requireNotNull(merge.state(access, true))
            do {
                require(++pages <= 100) { "SYNC_DOWNLOAD_LIMIT_REACHED" }
                val page = http.session(access) { it.roundPull(state.cursor) } ?: unsupported()
                state = merge.page(access, state, page)
            } while (page.hasMore)
        }
        // A prior call can COMMIT the real restart ACK then lose its Plan catch-up response.
        // Recover that actual log before uploading dependents, within the same page budget.
        if (challengeProfile && sessions.exclusive {
                authorize(access)
                database.withTransaction {
                    NextChallengeStore(database).activeInTransaction(access)
                    val ids = database.openHelper.writableDatabase.query("SELECT m.operationId FROM next_restart_materializations m " +
                        "INNER JOIN next_acceptances a ON a.kind='sync_operation' AND a.requestId=m.operationId " +
                        "LEFT JOIN next_restart_plan_proofs p ON p.operationId=m.operationId WHERE p.operationId IS NULL LIMIT 10001").use { raw ->
                        buildList { while (raw.moveToNext()) {
                            require(raw.getType(0) == android.database.Cursor.FIELD_TYPE_STRING); add(raw.getString(0))
                        } }
                    }
                    require(ids.size <= 10_000)
                    for (id in ids) {
                        val store = NextRestartStore(database)
                        store.accepted(access, store.original(access, id).reference)
                    }
                    ids.isNotEmpty()
                }
            }) catchUpRounds()
        val blocked = linkedSetOf<String>()
        val waiting = linkedSetOf<String>()
        var permanentBlock = false
        var uploaded = 0
        var total = queues(access, challengeProfile).let { it.operations.size + it.commands.size }
        progress(SyncProgress.UploadingChanges(0, total))
        while (true) {
            currentCoroutineContext().ensureActive()
            val queues = queues(access, challengeProfile)
            blocked += queues.rejectedIds
            if (queues.rejectedIds.isNotEmpty()) permanentBlock = true
            val operations = queues.operations.filter { it.operationId !in blocked }
            val commands = queues.commands.filter { it.commandId !in blocked }
            // Create/update endpoints first; timers produce facts before ordinary undo;
            // parent tombstones last. Per-entity causal heads are still proved by each store.
            val structural = operations.firstOrNull { it.recordType in setOf("habit", "metric", "link") && it.action == "upsert" }
            val command = if (structural == null) commands.firstOrNull() else null
            val operation = structural ?: if (command == null) operations.firstOrNull {
                it.recordType == "one_time_completion"
            } ?: operations.firstOrNull { it.recordType != "habit" || it.action != "delete" }
                ?: operations.firstOrNull() else null
            if (operation == null && command == null) break
            require(uploaded < 10_000) { "SYNC_UPLOAD_LIMIT_REACHED" }
            val requestId = operation?.operationId ?: requireNotNull(command).commandId
            try {
                if (command != null) {
                    val delivery = timers.send(access, command.commandId) ?: unsupported()
                    val result = delivery.result.results.single()
                    if (result.status in setOf("conflict", "rejected")) {
                        if (result.errorCode !in TRANSIENT_ERRORS) {
                            core.recordRejection(access, NEXT_TIMER, delivery.requestId, delivery.transmissionProof,
                                encodeSyncRequest(TimerCommandResult.serializer(), result).toString(Charsets.UTF_8),
                                delivery.challengeMetadata, delivery.result.serverTime)
                            permanentBlock = true
                        }
                        blocked += requestId
                        continue
                    }
                    timers.accept(delivery)
                } else if (requireNotNull(operation).recordType == RESTART_RECORD) {
                    require(challengeProfile)
                    when (restart.sendAndAccept(access, requestId) ?: unsupported()) {
                        NextRestartOutcome.COMMITTED, NextRestartOutcome.REPLAYED -> catchUpRounds()
                        NextRestartOutcome.REJECTED -> { permanentBlock = true; blocked += requestId; continue }
                        NextRestartOutcome.RETRY_REQUIRED -> { blocked += requestId; continue }
                    }
                } else if (operation.recordType == "one_time_completion") {
                    when (once.sendAndAccept(access, requestId) ?: unsupported()) {
                        is NextOneTimeOutcome.Accepted -> Unit
                        is NextOneTimeOutcome.Rejected -> { permanentBlock = true; blocked += requestId; continue }
                    }
                } else {
                    val delivery = core.sendOperation(access, requestId) ?: unsupported()
                    val result = delivery.result.results.single()
                    if (result.status in setOf("conflict", "rejected")) {
                        if (result.errorCode !in TRANSIENT_ERRORS) {
                            core.recordRejection(access, NEXT_OPERATION, delivery.requestId, delivery.transmissionProof,
                                encodeSyncRequest(NextSyncOperationResult.serializer(), result).toString(Charsets.UTF_8), delivery.challengeMetadata)
                            permanentBlock = true
                        }
                        blocked += delivery.requestId
                        blocked += requestId
                        continue
                    }
                    core.acceptOperation(delivery)
                }
                uploaded++
                // A cross-queue start barrier can become ready after its predecessor's ACK in this pass.
                // Saved permanent rejections are restored by queues(); immutable requests are never rewritten.
                blocked.removeAll(waiting)
                waiting.clear()
                total = maxOf(total, uploaded + queues.operations.size + queues.commands.size - 1)
                progress(SyncProgress.UploadingChanges(uploaded, total))
            } catch (error: NextRequestException) {
                if (error.reason in setOf(NextRequestException.Reason.TIMER_START_CONFIG_CHANGED,
                        NextRequestException.Reason.COUNT_START_CONFIG_CHANGED)) {
                    permanentBlock = true
                    blocked += requestId
                    continue
                }
                if (error.reason != NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING) throw error
                blocked += requestId
                waiting += requestId
            } catch (error: OneTimeLocalException) {
                if (error.reason != OneTimeLocalException.Reason.PENDING_REJECTED) throw error
                blocked += requestId
            } catch (_: NextStructuralCausalConflict) {
                permanentBlock = true
                blocked += requestId
            }
        }
        var state = merge.state(access, challengeProfile)
        if (state == null) {
            require(!challengeProfile) { "SYNC_CHALLENGE_CHECKPOINT_MISSING" }
            progress(SyncProgress.Recovering)
            val snapshot = http.session(access) { it.bootstrap() } ?: unsupported()
            state = merge.bootstrap(access, null, snapshot)
        }
        progress(SyncProgress.Downloading)
        do {
            require(++pages <= 100) { "SYNC_DOWNLOAD_LIMIT_REACHED" }
            val expected = requireNotNull(state)
            val hasMore = if (challengeProfile) {
                val page = http.session(access) { it.roundPull(expected.cursor) } ?: unsupported()
                state = merge.page(access, expected, page); page.hasMore
            } else {
                val page = http.session(access) { it.pull(expected.cursor) } ?: unsupported()
                state = merge.page(access, expected, page); page.hasMore
            }
        } while (hasMore)
        sessions.exclusive {
            authorize(access)
            database.withTransaction {
                NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
                if (challengeProfile) NextChallengeStore(database).activeInTransaction(access)
                else NextChallengeStore(database).requirePlainInTransaction()
                val outstanding = database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()
                blocked += outstanding.map { it.operationId }
                blocked += (database.timeLogDao().getPendingTimerCommands(Int.MAX_VALUE) +
                    database.timeLogDao().getRejectedTimerCommands()).map { it.commandId }
                blocked += database.syncConflictDao().getUnresolved().map { it.operationId }
                blocked += database.nextSyncStateDao().rejections().map { it.requestId }
                if (blocked.isNotEmpty()) {
                    if (permanentBlock || database.syncOutboxDao().countDeadLetters() > 0 ||
                        database.timeLogDao().countRejectedTimerCommands() > 0 ||
                        database.syncConflictDao().getUnresolved().isNotEmpty() || database.nextSyncStateDao().rejections().isNotEmpty())
                        throw NextSyncAttention(blocked.toSet())
                    throw NextSyncRetryRequired()
                }
            }
            // Last-sync is display metadata, not a second authoritative cursor.
            preferences.setLastSyncTimestamp(System.currentTimeMillis())
            authorize(access)
            afterSync?.invoke()
        }
    }

    /** Actual profile HTTP→Room refresh, not full sync: cannot consume/rewrap an old queue. */
    suspend fun refreshChallengeCache(progress: (SyncProgress) -> Unit = {}): com.dayforge.data.local.entity.NextSyncStateEntity = mutex.withLock {
        val access = sessions.exclusive {
            requireNotNull(tokens.localSyncAccess()).let { it.copy(capabilities = it.capabilities.toSet()) }
        }
        requireNoChallengeUpload(access)
        var state = merge.state(access, challengeProfile = true)
        if (state == null) {
            progress(SyncProgress.Recovering)
            // Keep the old plain cursor as the compare-and-swap expectation, not as profile admission.
            val previous = merge.challengeBootstrapExpectation(access)
            val snapshot = http.session(access) { it.roundBootstrap() } ?: unsupported()
            state = merge.bootstrap(access, previous, snapshot, cacheOnly = true)
        }
        progress(SyncProgress.Downloading)
        var pages = 0
        do {
            require(++pages <= 100) { "SYNC_DOWNLOAD_LIMIT_REACHED" }
            val expected = requireNotNull(state)
            val page = http.session(access) { it.roundPull(expected.cursor) } ?: unsupported()
            state = merge.page(access, expected, page, cacheOnly = true)
        } while (page.hasMore)
        requireNoChallengeUpload(access)
        requireNotNull(state)
    }

    private suspend fun requireNoChallengeUpload(access: LocalSyncAccess) = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            NextChallengeStore(database).requireQuiescentInTransaction()
        }
    }

    private data class Queues(val operations: List<SyncOutboxEntity>, val commands: List<TimerCommandEntity>, val rejectedIds: Set<String>)

    private suspend fun queues(access: LocalSyncAccess, challengeProfile: Boolean): Queues = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            if (challengeProfile) NextChallengeStore(database).activeInTransaction(access)
            else NextChallengeStore(database).requirePlainInTransaction()
            NextRequestSql.sources(sql, "sync_outbox"); NextRequestSql.sources(sql, "timer_command_outbox")
            val causal = NextStructuralCausalStore(database)
            if (challengeProfile) {
                val metadata = NextChallengeStore(database).activeInTransaction(access).second
                NextRestartStore(database).pending(access, metadata, NextRoundPendingInitialStore(database).read(access, metadata))
            }
            val ordered = database.syncOutboxDao().getAll().map {
                it to if (it.recordType == RESTART_RECORD) it.id else causal.logicalOrder(it)
            }.sortedBy { it.second }.map { it.first }
            val rejected = sql.query("SELECT kind,requestId FROM next_rejections ORDER BY kind,requestId").use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        require(cursor.getType(0) == android.database.Cursor.FIELD_TYPE_STRING &&
                            cursor.getType(1) == android.database.Cursor.FIELD_TYPE_STRING)
                        val kind = cursor.getString(0); val id = cursor.getString(1)
                        require(kind in setOf(NEXT_OPERATION, NEXT_TIMER) && com.dayforge.domain.model.isContractUuid(id))
                        requireNotNull(NextRequestSql.rowHash(sql, "next_rejections", "kind=? AND requestId=?", arrayOf(kind, id)))
                        add(id)
                    }
                }
            }
            val commands = database.timeLogDao().getPendingTimerCommands(10_001)
            if (challengeProfile) {
                val originals = (ordered + database.syncOutboxDao().getDeadLetters()).map { NEXT_OPERATION to it.operationId } +
                    (commands + database.timeLogDao().getRejectedTimerCommands()).map { NEXT_TIMER to it.commandId } +
                    database.nextSyncStateDao().rejections().map { it.kind to it.requestId }
                for ((kind, id) in originals.distinct()) {
                    requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(kind, id)))
                    val origin = requireNotNull(database.nextRequestDao().origin(kind, id))
                    require(origin.protocol == 5 && origin.accountId == access.session.authentication.userId &&
                        origin.serverInstanceId == access.session.serverInstanceId && origin.syncEpoch == access.session.syncEpoch)
                    val device = if (kind == NEXT_OPERATION) restartProposal(origin.intentJson)?.let {
                        NextRestartStore(database).original(access, id); it.deviceId
                    } ?: roundOperationIntent(origin.intentJson)?.capturedDeviceId
                        else roundTimerIntent(origin.intentJson)?.capturedDeviceId
                    require(device == access.deviceId) { "SYNC_CHALLENGE_SOURCE_REQUIRED" }
                }
            }
            Queues(ordered, commands, rejected)
        }
    }

    private suspend fun authorize(access: LocalSyncAccess) {
        if (tokens.syncAuthenticationSnapshot(access) == null) rejectNextRequest(NextRequestException.Reason.STALE_ACCESS)
    }
    private fun unsupported(): Nothing = rejectNextRequest(NextRequestException.Reason.UNSUPPORTED_ACCEPTANCE)

    private companion object {
        val TRANSIENT_ERRORS = setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS")
    }
}
