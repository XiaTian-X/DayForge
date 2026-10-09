package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Direct sender/HTTP-return/ACK boundary, not merely a scheduler hint. No network or file I/O. */
internal class NextConfigImportBarrier(private val database: HabitDatabase) {
    private val journal get() = NextConfigImportStore(database)

    suspend fun requireReady(operation: SyncV2Operation, access: LocalSyncAccess, expectedProof: String? = null,
        replay: Boolean = false): String? {
        check(database.inTransaction())
        if (expectedProof != null) {
            // A non-secret exact group binding, not authority. Do not scan every historical import
            // or confuse an old callback with a newly started replacement of the same definitions.
            val parts = expectedProof.split(':')
            require(parts.size == 2 && isContractUuid(parts[0]) && parts[1].matches(Regex("[0-9a-f]{64}")))
            val entry = journal.network(parts[0])
            requireTerminalIfMarked(entry, access)
            return ready(entry, operation, access).also { check(it == expectedProof) { "CONFIG_IMPORT_ACCEPTANCES_CHANGED" } }
        }
        val active = journal.activeNetwork() ?: return null
        // The core still proves this exact earlier receipt. A replay installs no business state
        // and must not acquire a different replacement's dependency merely by sharing an UUID.
        if (replay && active.plan.steps.none { it.operationId == operation.operationId }) return null
        return ready(active, operation, access)
    }

    private suspend fun ready(entry: NextConfigImportStore.NetworkEntry, operation: SyncV2Operation,
        access: LocalSyncAccess): String? {
        requireTarget(entry, access)
        val own = entry.plan.steps.singleOrNull { it.operationId == operation.operationId }
        own?.requireOperation(operation)
        val references = buildSet {
            add(operation.entityUuid)
            for (key in listOf("parent_uuid", "activity_uuid", "metric_uuid")) {
                val value = operation.payload[key]
                if (value != null && value != JsonNull) {
                    require(value is JsonPrimitive && value.isString); add(value.content)
                }
            }
        }
        val parents = if (own == null) entry.plan.steps.filter { it.entityUuid in references } else emptyList()
        if (own == null && parents.isEmpty()) return null
        val phases = if (own != null) listOf(own.phase) else parents.map { it.phase }
        val required = entry.plan.steps.filter { step ->
            phases.any { step.phase < it } || step in parents
        }
        val causal = NextStructuralCausalStore(database)
        val hashes = required.map { step ->
            currentCoroutineContext().ensureActive()
            val (original, hash) = causal.acceptedOperationProof(step.operationId, access)
            step.requireOperation(original); step.operationId + ":" + hash
        }
        val sources = Json.encodeToString(entry.receipt.copy(acceptanceHash = null))
        return entry.plan.importId + ":" + nextRequestHash((entry.sourceProof + sources + hashes.joinToString(";")).toByteArray(Charsets.UTF_8))
    }

    suspend fun requireTerminalIfMarked(entry: NextConfigImportStore.NetworkEntry, access: LocalSyncAccess) {
        requireTarget(entry, access)
        if (entry.accepted != null) require(proofs(entry, access) == entry.accepted) { "CONFIG_IMPORT_ACCEPTANCES_CHANGED" }
    }

    /** Last real ACK and the group completion receipt share the SAME original ACK transaction. */
    suspend fun finishIfAccepted(access: LocalSyncAccess) {
        check(database.inTransaction())
        val entry = journal.activeNetwork() ?: return
        requireTarget(entry, access)
        val sql = database.openHelper.writableDatabase
        for (step in entry.plan.steps) {
            currentCoroutineContext().ensureActive()
            // Missing is only a reason NOT to complete. Presence is never itself acceptance proof.
            if (NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, step.operationId)) == null) return
        }
        val accepted = proofs(entry, access)
        journal.accepted(entry, accepted)
        val saved = journal.network(entry.plan.importId)
        requireTerminalIfMarked(saved, access)
    }

    private suspend fun proofs(entry: NextConfigImportStore.NetworkEntry, access: LocalSyncAccess): Map<String, String> {
        val causal = NextStructuralCausalStore(database)
        return entry.plan.steps.associate { step ->
            currentCoroutineContext().ensureActive()
            val (operation, hash) = causal.acceptedOperationProof(step.operationId, access)
            step.requireOperation(operation); step.operationId to hash
        }
    }

    private fun requireTarget(entry: NextConfigImportStore.NetworkEntry, access: LocalSyncAccess) {
        val target = entry.plan.target
        check(target.accountId == access.session.authentication.userId && target.serverInstanceId == access.session.serverInstanceId &&
            target.syncEpoch == access.session.syncEpoch && target.deviceId == access.deviceId) { "CONFIG_IMPORT_TARGET_CHANGED" }
        if ("structure.write" !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
    }
}
