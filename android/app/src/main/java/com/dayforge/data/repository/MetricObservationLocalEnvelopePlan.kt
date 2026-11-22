package com.dayforge.data.repository

import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.ChallengeSourceContext
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.domain.model.isContractUuid
import java.util.Collections

/** Expected semantics from BEFORE-write input; only generated ID,
 * operationId and SQLite trigger timestamp are bound afterwards. Does not accept journal data.
 * Caller proves complete native old absence, parent/prompt inputs and same transaction scope.
 */
internal class MetricObservationLocalEnvelopePlan private constructor(
    private val intent: MetricObservationLocalPlan,
    private val parents: Map<Long, Parent>,
    private val accountId: String,
    private val serverInstanceId: String?,
    private val syncEpoch: String?,
    private val deviceId: String?,
    private val formal: Boolean,
    private val oldQueueWatermark: Long,
    private val input: MetricT1Input? = null
) {
    private data class Parent(val id: Long, val uuid: String, val unit: String)
    /** Called only by the original kernel after its complete same-T1 native/absence gates.
     * A value attachment does not replace any source, strict-envelope or post-write proof.
     */
    internal fun withInput(value: MetricT1Input): MetricObservationLocalEnvelopePlan =
        MetricObservationLocalEnvelopePlan(
            intent = intent,
            parents = parents,
            accountId = accountId,
            serverInstanceId = serverInstanceId,
            syncEpoch = syncEpoch,
            deviceId = deviceId,
            formal = formal,
            oldQueueWatermark = oldQueueWatermark,
            input = value
        )

    /** Allocation input has exactly the three values that the existing schema generates.
     * Do not supply a previously observed whole source as an expected row.
     */
    data class Allocation(val id: Long, val operationId: String, val createdAt: Long)
    data class Item(val log: MetricLogEntity, val source: SyncOutboxEntity,
        val operation: SyncV2Operation, val origin: NextRequestOriginEntity)
    class Bound internal constructor(val items: List<Item>)

    /** Run immediately after insertAll and before the original origin loop. Sources are selected
     * independently by the preplanned log UUIDs, never reconstructed from journal NEW cells.
     * Original source/watermark/reuse audits still run; a validated schema provides generation.
     */
    suspend fun bind(actualLogIds: List<Long>, selectedSources: List<SyncOutboxEntity>): Bound {
        val logs = intent.bindInserted(actualLogIds)
        require(selectedSources.size == logs.size && selectedSources.map { it.entityUuid }.distinct().size == logs.size)
        val allocated = selectedSources.associateBy { it.entityUuid }
        val items = logs.map { log ->
            val observed = requireNotNull(allocated[log.uuid])
            val allocation = Allocation(observed.id, observed.operationId, observed.createdAt)
            require(allocation.id > oldQueueWatermark && isContractUuid(allocation.operationId))
            // Existing SQLite trigger truncates to seconds. This is an allocation timestamp,
            // never the observation instant/day. Do not add a wall-clock validity policy.
            require(allocation.createdAt % 1000L == 0L)
            val parent = input?.metric(log.metricId)?.let { Parent(it.id,it.uuid,it.unit) }
                ?: parents.getValue(log.metricId)
            val source = SyncOutboxEntity(
                id = allocation.id, operationId = allocation.operationId, recordType = "metric_log",
                entityUuid = log.uuid, wireEntityUuid = log.uuid, action = "upsert",
                referenceUuid = parent.uuid, payloadJson = null, baseRevision = null, basePayloadJson = null,
                attemptedAt = null, attemptCount = 0, lastError = null, errorCode = null,
                deadLetteredAt = null, createdAt = allocation.createdAt
            )
            check(observed == source) { "METRIC_LOCAL_SOURCE_CHANGED" }
            val operation = SyncV2Operation(source.operationId, "metric_observation", log.uuid, "upsert", null,
                input?.observation(log, parent.uuid) ?: SyncV2Mapper.metricObservation(log, parent.uuid))
            val round = if (formal) NextRoundOperationIntent(1, operation,
                ChallengeSourceContext(source.operationId, head = null), requireNotNull(deviceId)) else null
            val bytes = if (round == null) encodeSyncRequest(SyncV2Operation.serializer(), operation)
                else encodeSyncRequest(NextRoundOperationIntent.serializer(), round)
            val text = bytes.toString(Charsets.UTF_8)
            // Full envelope identity remains protocol-specific, including head=null in formal.
            check(roundOperationIntent(text) == round && decodeNextOperationIntent(text) == operation)
            val origin = NextRequestOriginEntity(NEXT_OPERATION, source.operationId, source.id, 5,
                accountId, serverInstanceId, syncEpoch, NextRequestSql.sourceHash(source), text)
            Item(log, source, operation, origin)
        }
        require(items.map { it.source.id }.distinct().size == items.size &&
            items.map { it.source.operationId }.distinct().size == items.size)
        // Room insertAll executes each planned INSERT sequentially. Each trigger allocates
        // a monotonically increasing source ID; no assumption that IDs are gapless is needed.
        require(items.zipWithNext().all { (left, right) -> left.source.id < right.source.id })
        return Bound(Collections.unmodifiableList(items))
    }

    companion object {
        fun freezeBeforeInsert(logs: List<MetricLogEntity>, currentMetrics: Map<Long, MetricEntity>,
            access: LocalCoreWriteAccess, oldQueueWatermark: Long, formal: Boolean = true): MetricObservationLocalEnvelopePlan? {
            // Native signed-zero storage/journal behavior is not yet differentially verified.
            // This is optional-profile fallback, never a new product rejection or value edit.
            if (logs.any { it.value.toRawBits() == (-0.0).toRawBits() }) return null
            val device = access.capturedDeviceId
            require(oldQueueWatermark >= 0 && (device == null || isContractUuid(device)))
            if (formal) requireNotNull(device)
            val frozen = currentMetrics.mapValues { (_, value) -> Parent(value.id, value.uuid, value.unit) }
            require(logs.all { log -> frozen[log.metricId]?.let {
                it.id == log.metricId && isContractUuid(it.uuid) && it.unit == log.unit
            } == true })
            return MetricObservationLocalEnvelopePlan(MetricObservationLocalPlan.freezeBeforeInsert(logs),
                Collections.unmodifiableMap(frozen), access.session.authentication.userId,
                access.session.serverInstanceId, access.session.syncEpoch, device, formal, oldQueueWatermark)
        }
    }
}
