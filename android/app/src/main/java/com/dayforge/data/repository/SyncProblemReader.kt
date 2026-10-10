package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.NextSyncProblem
import com.dayforge.data.model.SyncProblems
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

internal fun requirePermanentProblem(status: String, code: String?) {
    require(status in setOf("conflict", "rejected") && !code.isNullOrBlank() &&
        code !in setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS"))
}

/** Offline SELECT-only snapshot. HTTP, sender prepare/accept and preference edits are prohibited. */
internal class SyncProblemReader(
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val core: NextCoreRequestStore,
    private val once: NextOneTimeRequestStore, private val restart: NextChallengeRestartRepository
) {
    fun changes() = combine(tokens.iconAccessChanges, database.invalidationTracker.createFlow(
        "sync_outbox", "sync_conflicts", "timer_command_outbox", "next_sync_state", "next_request_origins",
        "next_transmissions", "next_acceptances", "next_rejections", "one_time_transmissions",
        "local_fact_submissions", "next_structural_dependencies", "next_structural_supersessions",
        "next_challenge_state", "next_challenge_rounds", "next_challenge_births", "next_restart_materializations",
        "next_restart_plan_proofs", "next_config_imports", "count_days", "habits", "metrics", "sync_entity_state")) {
        _, _ -> Unit
    }.map { Unit }

    suspend fun read(): SyncProblems = sessions.exclusive {
        val authority = tokens.localCoreWriteAccess()
        val access = tokens.localSyncAccess()
        database.withTransaction {
            if (!NextProtocolAdmission.hasNextState(database)) {
                val result = if (authority == null) SyncProblems.Legacy(null, emptyList(), emptyList(), emptyList())
                    else SyncProblems.Legacy(authority.session, database.syncOutboxDao().getDeadLetters(),
                        database.syncConflictDao().getUnresolved(), database.timeLogDao().getRejectedTimerCommands())
                check(tokens.localCoreWriteAccess() == authority && tokens.localSyncAccess() == access)
                return@withTransaction result
            }
            val captured = requireNotNull(access)
            check(authority?.session == captured.session && tokens.syncAuthenticationSnapshot(captured) != null)
            NextProtocolAdmission.requireRoundsOrEmpty(database, captured)
            val rounds = NextChallengeStore(database).activeInTransaction(captured)
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            NextRequestSql.sources(sql, "sync_outbox")
            NextRequestSql.sources(sql, "timer_command_outbox")
            val rejectedKeys = sql.query("SELECT kind,requestId FROM next_rejections ORDER BY kind,requestId LIMIT 10001").use { c ->
                buildList { while (c.moveToNext()) {
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                    add(c.getString(0) to c.getString(1))
                } }
            }
            require(rejectedKeys.size <= 10_000)
            for ((kind, id) in rejectedKeys) requireNotNull(NextRequestSql.rowHash(sql, "next_rejections",
                "kind=? AND requestId=?", arrayOf(kind, id)))
            val rejected = database.nextSyncStateDao().rejections()
            require(rejected.map { it.kind to it.requestId } == rejectedKeys)
            val operations = (database.syncOutboxDao().getAll() + database.syncOutboxDao().getDeadLetters()).sortedBy { it.id }
            val commands = (database.timeLogDao().getPendingTimerCommands(10_001) +
                database.timeLogDao().getRejectedTimerCommands()).sortedBy { it.id }
            require(operations.size + commands.size <= 10_000 && database.syncConflictDao().getUnresolved().isEmpty())
            val operationById = operations.associateBy { it.operationId }
            val commandById = commands.associateBy { it.commandId }
            require(operationById.size == operations.size && commandById.size == commands.size)
            val items = mutableListOf<NextSyncProblem>()
            for (row in rejected) {
                currentCoroutineContext().ensureActive()
                require(row.kind in setOf(NEXT_OPERATION, NEXT_TIMER))
                items += if (row.kind == NEXT_OPERATION) {
                    val queue = requireNotNull(operationById[row.requestId])
                    require(queue.recordType != "one_time_completion") // Once has its own bound journal, never two warnings.
                    if (queue.recordType == RESTART_RECORD) restart.inspectRejection(captured, row)
                    else core.inspectRejection(captured, row)
                } else {
                    requireNotNull(commandById[row.requestId]); core.inspectRejection(captured, row)
                }
            }
            suspend fun inspect(kind: String, id: String, type: String, uuid: String, block: suspend () -> Unit) {
                if (kind to id in rejectedKeys) return
                currentCoroutineContext().ensureActive()
                try { block() }
                catch (error: NextRequestException) {
                    if (error.reason !in setOf(NextRequestException.Reason.PERMISSION_DENIED,
                            NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
                            NextRequestException.Reason.TIMER_START_CONFIG_CHANGED,
                            NextRequestException.Reason.COUNT_START_CONFIG_CHANGED)) throw error
                    items += NextSyncProblem(kind, id, type, uuid, error.reason.name)
                } catch (error: NextStructuralCausalConflict) {
                    items += NextSyncProblem(kind, id, type, uuid, "STRUCTURAL_CAUSAL_CONFLICT", error.fields)
                } catch (error: OneTimeLocalException) {
                    if (error.reason != OneTimeLocalException.Reason.PENDING_REJECTED) throw error
                    items += NextSyncProblem(kind, id, type, uuid, "ONE_TIME_PREDECESSOR_REJECTED")
                }
            }
            for (queue in operations) {
                if (queue.recordType == "one_time_completion" && queue.deadLetteredAt != null) {
                    items += once.inspectRejection(captured, queue.operationId)
                    continue
                }
                require(queue.deadLetteredAt == null)
                inspect(NEXT_OPERATION, queue.operationId, queue.recordType, queue.entityUuid) {
                    when (queue.recordType) {
                        "one_time_completion" -> once.inspectPending(captured, queue.operationId)
                        RESTART_RECORD -> restart.inspectPending(captured, queue.operationId)
                        else -> core.inspectPending(captured, NEXT_OPERATION, queue.operationId)
                    }
                }
            }
            for (queue in commands) {
                require(queue.deadLetteredAt == null)
                inspect(NEXT_TIMER, queue.commandId, "timer_session", queue.sessionUuid) {
                    core.inspectPending(captured, NEXT_TIMER, queue.commandId)
                }
            }
            check(NextChallengeStore(database).activeInTransaction(captured) == rounds &&
                tokens.localCoreWriteAccess() == authority && tokens.localSyncAccess() == captured &&
                tokens.syncAuthenticationSnapshot(captured) != null)
            SyncProblems.Next(items.toList())
        }
    }
}
