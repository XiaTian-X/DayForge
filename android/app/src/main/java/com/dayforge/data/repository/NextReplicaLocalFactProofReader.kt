package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class ReplicaMetricPromptEvidence internal constructor(val row: CompletionMetricPromptEntity,
    entries: List<CompletionMetricDraft>, val completionOperationId: String,
    observations: Map<String, NextRecoveryRequestState>) {
    val entries: List<CompletionMetricDraft> = Collections.unmodifiableList(entries.toList())
    val observations: Map<String, NextRecoveryRequestState> = Collections.unmodifiableMap(observations.toMap())
    val pending: Boolean get() = row.state == "pending"
}

/** Local durability receipts and full draft history. Not the entire business/material/archive proof. */
internal class ReplicaLocalFactProof internal constructor(val configuration: ReplicaConfigProof,
    submissions: List<LocalFactSubmissionEntity>, prompts: List<ReplicaMetricPromptEvidence>) {
    val submissions: List<LocalFactSubmissionEntity> = Collections.unmodifiableList(submissions.toList())
    val prompts: List<ReplicaMetricPromptEvidence> = Collections.unmodifiableList(prompts.toList())
    val hasPendingDrafts: Boolean get() = prompts.any { it.pending }
    val activationAuthorized: Boolean get() = false
}

/** SELECT-only cross-check against ALL verified original requests, not just pending send work. */
internal class NextReplicaLocalFactProofReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun read(configuration: ReplicaConfigProof): ReplicaLocalFactProof = withContext(Dispatchers.IO) {
        sessions.exclusive { database.withTransaction {
            val requests = configuration.requests
            fun unchanged(hash: String) = require(hash == requests.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            suspend fun authorize() {
                check(tokens.isCurrentSyncAuthority(requests.original)) {
                    "SYNC_ACCOUNT_CHANGED"
                }
            }
            authorize()
            unchanged(NextReplicaStorageProof(database).readInTransaction().hash)
            val sql = database.openHelper.writableDatabase
            val dao = database.completionFollowUpDao()
            val operations = linkedMapOf<String, SyncV2Operation>()
            for (key in requests.states.keys.filter { it.kind == NEXT_OPERATION }) {
                currentCoroutineContext().ensureActive()
                val source = requireNotNull(database.nextRequestDao().origin(key.kind, key.requestId))
                roundOperationIntent(source.intentJson)?.let {
                    require(it.operation.operationId == key.requestId)
                    operations[key.requestId] = it.operation
                }
            }
            val ids = sql.query("SELECT operationId FROM local_fact_submissions ORDER BY operationId").use { c ->
                buildList { while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    require(c.getType(0) == Cursor.FIELD_TYPE_STRING && size < NEXT_RECOVERY_PROOF_NODE_LIMIT)
                    add(c.getString(0))
                } }
            }
            val submissions = ids.associateWith { id ->
                requireNotNull(NextRequestSql.rowHash(sql, "local_fact_submissions", "operationId=?", arrayOf(id)))
                val row = requireNotNull(dao.submission(id))
                require(listOf(row.operationId, row.entityUuid, row.referenceUuid).all(::isContractUuid))
                val operation = operations[id]
                require(operation != null && operation.action == "upsert" && operation.entityType == row.entityType &&
                    operation.entityUuid == row.entityUuid &&
                    operation.payload.toString() == row.payloadJson) { "SYNC_RECOVERY_FACT_RECEIPT_INVALID" }
                val reference = when (row.entityType) {
                    "metric_observation" -> "metric_uuid"
                    "activity_event" -> {
                        require(operation.payload["one_time"] is JsonObject) { "SYNC_RECOVERY_FACT_RECEIPT_INVALID" }
                        "activity_uuid"
                    }
                    else -> error("SYNC_RECOVERY_FACT_RECEIPT_INVALID")
                }
                require(operation.payload.getValue(reference).jsonPrimitive.content == row.referenceUuid) {
                    "SYNC_RECOVERY_FACT_RECEIPT_INVALID"
                }
                row
            }
            // Every local once producer commits its immutable receipt with the original fact.
            // Ordinary metric operations do NOT universally have such a receipt; never invent it.
            operations.values.filter { it.entityType == "activity_event" && it.payload["one_time"] is JsonObject }
                .forEach { require(it.operationId in submissions) { "SYNC_RECOVERY_FACT_RECEIPT_MISSING" } }
            val prompts = CompletionMetricPromptStore(database, tokens, sessions).recoveryInTransaction(
                requests.original.session, operations, submissions, requests.states)
            val reserved = configuration.journals.flatMap { it.reservedOperationIds }.toSet()
            require(prompts.none { p -> p.entries.any { it.operationId in reserved } }) { "SYNC_RECOVERY_DRAFT_ID_REUSED" }
            unchanged(NextReplicaStorageProof(database).readInTransaction().hash)
            authorize()
            ReplicaLocalFactProof(configuration, submissions.values.toList(), prompts)
        } }
    }
}
