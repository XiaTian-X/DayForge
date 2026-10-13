package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Complete retained request ledger, not full business/material closure or an archive ticket. */
internal class ReplicaRequestProof internal constructor(val original: LocalSyncAccess, val storageHash: String,
    states: Map<NextRecoveryKey, NextRecoveryRequestState>) {
    val states: Map<NextRecoveryKey, NextRecoveryRequestState> = Collections.unmodifiableMap(states.toMap())
    val hasUnsettledRequests get() = states.values.any { it !in setOf(NextRecoveryRequestState.ACCEPTED,
        NextRecoveryRequestState.SUPERSEDED, NextRecoveryRequestState.ABANDONED) }
    val activationAuthorized: Boolean get() = false
}

/** SELECT-only ledger audit, including ACKs no longer referenced by any pending request.
 * Reuses actual original/wire/result/round proof readers, never send/prepare/accept.
 * Schema proof and ledger bind the same complete raw contents in separate read transactions.
 */
internal class NextReplicaRequestProofReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val preferences: PreferencesManager,
    private val http: NextSyncHttp) {
    suspend fun read(schema: ReplicaSchemaProof): ReplicaRequestProof = withContext(Dispatchers.IO) {
        sessions.exclusive { database.withTransaction {
            val access = schema.original
            authorize(access)
            require(NextReplicaStorageProof(database).readInTransaction().hash == schema.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            NextProtocolAdmission.requireRoundsOrEmpty(database, access)
            val checkpoint = NextChallengeStore(database).activeInTransaction(access)
            val sql = database.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            val retired = NextRecoveryAuditStore(database).validatedMembers(access)
            suspend fun keys(table: String, kind: String? = null, id: String = "requestId"): Set<NextRecoveryKey> =
                sql.query("SELECT ${if (kind == null) "kind" else "'$kind'"},$id FROM $table ORDER BY 1,2").use { c ->
                    buildSet { while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        require(c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getType(1) == Cursor.FIELD_TYPE_STRING)
                        require(size < NEXT_RECOVERY_PROOF_NODE_LIMIT) { "SYNC_RECOVERY_PROOF_LIMIT" }
                        require(add(NextRecoveryKey(c.getString(0), c.getString(1)))) { "SYNC_RECOVERY_REQUEST_DUPLICATE" }
                    } }
                }
            val origins = keys("next_request_origins")
            val sent = keys("next_transmissions")
            val accepted = keys("next_acceptances")
            val rejected = keys("next_rejections")
            val operations = keys("sync_outbox", NEXT_OPERATION, "operationId")
            val commands = keys("timer_command_outbox", NEXT_TIMER, "commandId")
            val pending = operations + commands
            require(listOf(sent, accepted, rejected, pending, retired).all { origins.containsAll(it) }) { "SYNC_RECOVERY_REQUEST_ORPHAN" }
            require(sent.containsAll(accepted + rejected) && accepted.intersect(rejected + pending + retired).isEmpty() &&
                retired.intersect(pending).isEmpty()) { "SYNC_RECOVERY_CONTRADICTORY_RESULT" }
            val originRows = origins.associateWith { key ->
                requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(key.kind, key.requestId)))
                requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
            }
            for (row in originRows.values) {
                require(row.accountId == access.session.authentication.userId && row.serverInstanceId == access.session.serverInstanceId &&
                    row.syncEpoch == access.session.syncEpoch && row.protocol == 5 && row.queueId > 0 &&
                    row.sourceHash.matches(Regex("[0-9a-f]{64}"))) { "SYNC_RECOVERY_REQUEST_SCOPE_INVALID" }
            }
            // Audit metadata attached to any request, even when no current sender references it.
            val dependencies = keys("next_structural_dependencies", id = "operationId")
            val superseded = keys("next_structural_supersessions", id = "originalId")
            val replacements = keys("next_structural_supersessions", id = "replacementId")
            val onceIds = keys("one_time_transmissions", NEXT_OPERATION, "operationId")
            val restarts = keys("next_restart_materializations", id = "operationId")
            val restartPlans = keys("next_restart_plan_proofs", NEXT_OPERATION, "operationId")
            require(listOf(dependencies, superseded, replacements, onceIds, restarts, restartPlans).all { origins.containsAll(it) }) {
                "SYNC_RECOVERY_REQUEST_ORPHAN"
            }
            for ((key, row) in originRows) {
                val proposal = if (key.kind == NEXT_OPERATION) restartProposal(row.intentJson) else null
                val operation = if (key.kind == NEXT_OPERATION && proposal == null)
                    requireNotNull(roundOperationIntent(row.intentJson)).operation else null
                val structure = operation?.entityType in setOf("plan_node", "metric", "activity_metric_link")
                val task = operation?.payload?.get("one_time") is JsonObject
                require((key !in dependencies + superseded + replacements || structure) &&
                    (key !in onceIds || task) && (key !in restarts + restartPlans || proposal != null)) {
                    "SYNC_RECOVERY_REQUEST_METADATA_INVALID"
                }
            }
            val core = NextCoreRequestStore(database, tokens, sessions, http)
            val timers = NextTimerRequestStore(database, tokens, sessions, core)
            val facts = OneTimeAcceptedEventStore(database, tokens, sessions,
                OneTimeLocalIntentStore(database, tokens, sessions, preferences), timerRequests = timers)
            val once = NextOneTimeRequestStore(database, tokens, sessions, http, facts, core)
            val restart = NextChallengeRestartRepository(database, tokens, sessions, http)
            if (pending.isNotEmpty()) {
                // This existing reader validates ALL pending nodes and their complete joined
                // frontiers, not only the root's descendants; canConfirm is intentionally ignored.
                NextRecoveryDependencyReader(database, tokens, sessions, core, timers, once, restart)
                    .readInTransaction(access, pending.minBy { it.sortKey })
            }
            val structural = NextStructuralCausalStore(database)
            val captured = NextRecoveryCapturedFrontierStore(database)
            val states = linkedMapOf<NextRecoveryKey, NextRecoveryRequestState>()
            // A logical original is not itself retired when its actual replacement is
            // retired. Only the WHOLE sealed audit (including unchanged original and
            // supersession rows) may prove that alias; the live lineage reader correctly
            // rejects a retired replacement and must not be relaxed for this consumer.
            sql.query("SELECT DISTINCT m.recoveryId FROM next_structural_supersessions s " +
                "JOIN next_abandoned_requests m ON m.kind=s.kind AND m.requestId=s.replacementId " +
                "ORDER BY m.recoveryId").use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val manifest = NextRecoveryAuditStore(database).read(access, cursor.getString(0)).manifest
                    val members = manifest.requests.map { it.key }.toSet()
                    val rows = manifest.rows.map { it.table to it.key }.toSet()
                    for (alias in manifest.graph.aliases.filter { it.actual in members }) {
                        require(alias.actual in retired && alias.logical in origins && alias.actual in origins &&
                            alias.logical !in retired + pending + sent + accepted + rejected &&
                            alias.logical in superseded && alias.actual in replacements &&
                            manifest.graph.nodes.single { it.key == alias.logical }.state == NextRecoveryRequestState.SUPERSEDED &&
                            "next_request_origins" to alias.logical in rows && "next_request_origins" to alias.actual in rows &&
                            "next_structural_supersessions" to alias.logical in rows) { "SYNC_RECOVERY_CONTRADICTORY_RESULT" }
                        require(states.putIfAbsent(alias.logical, NextRecoveryRequestState.SUPERSEDED).let {
                            it == null || it == NextRecoveryRequestState.SUPERSEDED
                        })
                    }
                }
            }
            // Latest structure first: a validated lineage also covers its old ancestors.
            val ordered = origins.sortedWith(compareByDescending<NextRecoveryKey> {
                originRows.getValue(it).queueId
            }.thenBy { it.sortKey })
            for (key in ordered) {
                currentCoroutineContext().ensureActive()
                if (key in retired) {
                    states[key] = NextRecoveryRequestState.ABANDONED
                    continue
                }
                if (key in states) continue
                val row = originRows.getValue(key)
                val state = if (key.kind == NEXT_TIMER) {
                    require(key !in dependencies + superseded + replacements + onceIds + restarts + restartPlans)
                    if (key in accepted) { timers.recoveryAcceptedProof(access, key.requestId); NextRecoveryRequestState.ACCEPTED }
                    else { require(key in pending); captured.pending(access, key).state }
                } else if (restartProposal(row.intentJson) != null) {
                    require(key !in dependencies + superseded + replacements + onceIds)
                    restart.recoveryProof(access, key.requestId).state
                } else {
                    val operation = requireNotNull(roundOperationIntent(row.intentJson)).operation
                    require(key !in restarts + restartPlans)
                    when {
                        operation.entityType in setOf("plan_node", "metric", "activity_metric_link") -> {
                            require(key !in onceIds)
                            val chain = structural.recoveryLineage(key.requestId, access)
                            for (link in chain.links) {
                                require(link.logicalKey in origins && link.actualKey in origins)
                                val actual = states.putIfAbsent(link.actualKey, link.state)
                                require(actual == null || actual == link.state)
                                if (link.logicalKey != link.actualKey) {
                                    val prior = states.putIfAbsent(link.logicalKey, NextRecoveryRequestState.SUPERSEDED)
                                    require(prior == null || prior == NextRecoveryRequestState.SUPERSEDED)
                                }
                            }
                            states.getValue(key)
                        }
                        operation.payload["one_time"] is JsonObject -> {
                            require(key !in dependencies + superseded + replacements)
                            once.recoveryProof(access, key.requestId).state
                        }
                        else -> {
                            require(key !in dependencies + superseded + replacements + onceIds)
                            if (key in accepted) { core.recoveryAcceptedFactProof(access, key.requestId); NextRecoveryRequestState.ACCEPTED }
                            else { require(key in pending); captured.pending(access, key).state }
                        }
                    }
                }
                require(states.putIfAbsent(key, state).let { it == null || it == state })
            }
            require(states.keys == origins && superseded.all { it in retired || states[it] == NextRecoveryRequestState.SUPERSEDED } &&
                states.filterValues { it == NextRecoveryRequestState.ACCEPTED }.keys == accepted) { "SYNC_RECOVERY_CONTRADICTORY_RESULT" }
            require(NextChallengeStore(database).activeInTransaction(access) == checkpoint &&
                NextReplicaStorageProof(database).readInTransaction().hash == schema.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            authorize(access)
            ReplicaRequestProof(access, schema.storageHash, states)
        } }
    }

    private suspend fun authorize(access: LocalSyncAccess) {
        check(tokens.isCurrentSyncAuthority(access)) { "SYNC_ACCOUNT_CHANGED" }
    }
}
