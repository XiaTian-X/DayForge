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

    /** Captures authority once. Failure, unsupported v5, pending/conflicting work are never success. */
    suspend fun sync(progress: (SyncProgress) -> Unit = {}, afterSync: (suspend () -> Unit)? = null) = mutex.withLock {
        val access = sessions.exclusive {
            val captured = requireNotNull(tokens.localSyncAccess())
            captured.copy(capabilities = captured.capabilities.toSet())
        }
        val blocked = linkedSetOf<String>()
        var permanentBlock = false
        var uploaded = 0
        var total = queues(access).let { it.operations.size + it.commands.size }
        progress(SyncProgress.UploadingChanges(0, total))
        while (true) {
            currentCoroutineContext().ensureActive()
            val queues = queues(access)
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
            } ?: operations.firstOrNull() else null
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
                                encodeSyncRequest(TimerCommandResult.serializer(), result).toString(Charsets.UTF_8))
                            permanentBlock = true
                        }
                        blocked += requestId
                        continue
                    }
                    timers.accept(delivery)
                } else if (requireNotNull(operation).recordType == "one_time_completion") {
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
                                encodeSyncRequest(NextSyncOperationResult.serializer(), result).toString(Charsets.UTF_8))
                            permanentBlock = true
                        }
                        blocked += delivery.requestId
                        blocked += requestId
                        continue
                    }
                    core.acceptOperation(delivery)
                }
                uploaded++
                total = maxOf(total, uploaded + queues.operations.size + queues.commands.size - 1)
                progress(SyncProgress.UploadingChanges(uploaded, total))
            } catch (error: NextRequestException) {
                if (error.reason != NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING) throw error
                blocked += requestId
            } catch (error: OneTimeLocalException) {
                if (error.reason != OneTimeLocalException.Reason.PENDING_REJECTED) throw error
                blocked += requestId
            } catch (_: NextStructuralCausalConflict) {
                permanentBlock = true
                blocked += requestId
            }
        }
        var state = merge.state(access)
        if (state == null) {
            progress(SyncProgress.Recovering)
            val snapshot = http.session(access) { it.bootstrap() } ?: unsupported()
            state = merge.bootstrap(access, null, snapshot)
        }
        progress(SyncProgress.Downloading)
        var pages = 0
        do {
            require(++pages <= 100) { "SYNC_DOWNLOAD_LIMIT_REACHED" }
            val expected = requireNotNull(state)
            val page = http.session(access) { it.pull(expected.cursor) } ?: unsupported()
            state = merge.page(access, expected, page)
        } while (page.hasMore)
        sessions.exclusive {
            authorize(access)
            database.withTransaction {
                NextRequestSql.requireOutboxEnabled(database.openHelper.writableDatabase)
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

    private data class Queues(val operations: List<SyncOutboxEntity>, val commands: List<TimerCommandEntity>, val rejectedIds: Set<String>)

    private suspend fun queues(access: LocalSyncAccess): Queues = sessions.exclusive {
        authorize(access)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            NextRequestSql.sources(sql, "sync_outbox"); NextRequestSql.sources(sql, "timer_command_outbox")
            val causal = NextStructuralCausalStore(database)
            val ordered = database.syncOutboxDao().getAll().map { it to causal.logicalOrder(it) }.sortedBy { it.second }.map { it.first }
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
            Queues(ordered, database.timeLogDao().getPendingTimerCommands(10_001), rejected)
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
