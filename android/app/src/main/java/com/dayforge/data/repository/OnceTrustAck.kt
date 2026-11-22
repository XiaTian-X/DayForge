package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import java.util.Collections
import kotlinx.serialization.json.*

/** Exact original ONCE effects only. The stores still own every business/receipt validator;
 * this adapter neither writes SQL nor issues a root. Nested fact mergers share the outer
 * owner's adapter and only the outer transaction may freeze it for finishOnce.
 */
internal class OnceTrustAck private constructor(
    private val database: HabitDatabase,
    val context: CountTrustKernel.Context
) {
    private val effects = arrayListOf<CountTrustAck.Effect>()
    private val activities = linkedSetOf<String>()
    private var supported = true
    private var metadata: ChallengeMetadata? = null
    private var frozen = false

    fun decline() { check(!frozen); supported = false }

    suspend fun affect(activity: String) {
        check(!frozen && database.inTransaction())
        val habit = database.habitDao().getHabitByUuid(activity)
        if (habit == null || !context.onceEligible(habit)) decline() else activities += activity
    }

    fun before(table: String, where: String, args: Array<out Any?>): CountTrustRows.Row? {
        check(!frozen && database.inTransaction())
        return context.rows.read(table, where, args).singleOrNull()
    }

    fun row(table: String, fields: Map<String, Any?>): CountTrustRows.Row =
        OnceTrustEvidence.native(context.rows, table, fields)

    fun changed(before: CountTrustRows.Row, fields: Map<String, Any?>): CountTrustRows.Row =
        OnceTrustEvidence.replace(before, fields)

    fun record(table: String, before: CountTrustRows.Row?, after: CountTrustRows.Row?) {
        check(!frozen && database.inTransaction())
        check(before != null || after != null)
        check(before?.table?.name.let { it == null || it == table } &&
            after?.table?.name.let { it == null || it == table })
        effects += CountTrustAck.Effect(table, before, after)
    }

    /** Keep the original whole-table guarded sidecar merger. The supported ONCE shape is
     * unchanged metadata, null operation head and no birth for the one-time fact. Legitimate
     * additional metadata is a maintenance miss, never an extra protocol rejection.
     */
    fun unchangedSidecar(checkpoint: Pair<NextSyncStateEntity, ChallengeMetadata>,
        incoming: ChallengeMetadata, eventUuid: String) {
        check(!frozen && database.inTransaction())
        if (!context.formal) { decline(); return }
        val retained = Json.decodeFromJsonElement(ChallengeMetadata.serializer(),
            requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)))
        check(retained == checkpoint.second) { "ONCE_TRUST_METADATA_CHANGED" }
        val checkpoints = retained.checkpoints.associateBy { it.head.activityUuid }
        val births = retained.births.associateBy { it.entityType to it.entityUuid }
        if (incoming.checkpoints.any { next -> checkpoints[next.head.activityUuid]?.let { old ->
                old.copy(records = old.records.sortedBy { it.head.generation }) !=
                    next.copy(records = next.records.sortedBy { it.head.generation }) } != false } ||
            incoming.births.any { births[it.entityType to it.entityUuid] != it } ||
            incoming.births.any { it.entityType == "activity_event" && it.entityUuid == eventUuid }) {
            decline(); return
        }
        val marker = requireNotNull(before("next_challenge_state", "id=?", arrayOf(1)))
        val state = checkpoint.first
        check(marker.text("accountId") == state.accountId && marker.text("serverInstanceId") == state.serverInstanceId &&
            marker.text("syncEpoch") == state.syncEpoch && marker.text("deviceId") == state.deviceId &&
            marker.long("generation") == state.generation && marker.long("cursor") == state.cursor &&
            marker.text("batchHash") == state.batchHash) { "ONCE_TRUST_METADATA_CHANGED" }
        // mergeAndCountWrites deliberately UPDATEs even an identical marker on saved replay.
        record("next_challenge_state", marker, marker)
        metadata = retained
    }

    fun binding(value: OneTimeTransmissionEntity) = row("one_time_transmissions", mapOf(
        "operationId" to value.operationId, "accountId" to value.accountId,
        "serverInstanceId" to value.serverInstanceId, "syncEpoch" to value.syncEpoch,
        "deviceId" to value.deviceId, "operationJson" to value.operationJson, "rejectionJson" to value.rejectionJson))

    fun transmission(value: NextTransmissionEntity) = row("next_transmissions", mapOf(
        "kind" to value.kind, "requestId" to value.requestId, "queueId" to value.queueId,
        "protocol" to value.protocol, "accountId" to value.accountId, "serverInstanceId" to value.serverInstanceId,
        "syncEpoch" to value.syncEpoch, "deviceId" to value.deviceId, "wireHash" to value.wireHash,
        "wireBytes" to value.wireBytes))

    fun acceptance(value: NextAcceptanceEntity) = row("next_acceptances", mapOf(
        "kind" to value.kind, "requestId" to value.requestId, "originHash" to value.originHash,
        "transmissionHash" to value.transmissionHash, "resultHash" to value.resultHash, "resultJson" to value.resultJson))

    fun completion(value: CompletionEntity) = OnceTrustEvidence.completionRow(context.rows, value)

    fun shadow(value: SyncEntityStateEntity) = row("sync_entity_state", mapOf(
        "entityType" to value.entityType, "entityUuid" to value.entityUuid, "revision" to value.revision,
        "deleted" to if (value.deleted) 1 else 0, "payloadJson" to value.payloadJson,
        "payloadHash" to value.payloadHash, "updatedAt" to value.updatedAt))

    fun freeze(): OnceTrustUpdate? {
        check(!frozen && database.inTransaction()); frozen = true
        if (!supported) return null
        return Prepared(database, context, effects, metadata, activities)
    }

    private class Prepared(private val owner: HabitDatabase, override val context: CountTrustKernel.Context,
        source: List<CountTrustAck.Effect>, override val metadata: ChallengeMetadata?, affected: Set<String>) : OnceTrustUpdate {
        override val effects = Collections.unmodifiableList(source.toList())
        override val affectedHabitUuids = Collections.unmodifiableSet(LinkedHashSet(affected))
        override suspend fun verifyFinal(database: HabitDatabase) {
            check(database === owner && database.inTransaction()) { "ONCE_TRUST_OWNER_CHANGED" }
        }
    }

    companion object {
        fun create(database: HabitDatabase, capture: CountTrustKernel.Capture?): OnceTrustAck? =
            capture?.context?.takeIf { it.once }?.let { OnceTrustAck(database, it) }
    }
}
