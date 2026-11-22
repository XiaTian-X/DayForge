package com.dayforge.data.repository

import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step
import java.util.Collections
import kotlinx.serialization.json.*

/** Immutable values shared by the original same-T1 producers after their original checks.
 * No native or DAO read is elided; this input never asserts a new no-write interval.
 * Not a credential, native handle, command participant or transferable read capability.
 * Original source reads/mandatory predicates and every post-write verifier remain independent.
 */
internal class MetricT1Input private constructor(
    private val commandText: String,
    private val commandInput: JsonObject,
    private val metrics: Map<Long, MetricEntity>,
    private val logs: List<MetricLogEntity>
) {
    // Actual metric/log/appearance variants consist only of immutable scalars and strings.
    // Container ownership is explicit. A new mutable entity field requires revisiting this contract.
    private val payloads = HashMap<String, JsonObject>()
    private val logsByUuid = logs.associateBy { it.uuid }
    fun matches(intent: CountCommandRecords.Intent, actualLogs: List<MetricLogEntity>,
        actualMetrics: Map<Long, MetricEntity>): Boolean =
        intent.text() == commandText && actualLogs == logs && actualMetrics == metrics

    fun input(intent: CountCommandRecords.Intent): JsonObject {
        check(intent.text() == commandText) { "METRIC_T1_COMMAND_CHANGED" }
        return commandInput
    }
    fun metric(id: Long): MetricEntity = metrics.getValue(id)

    /** First evaluation occurs at the original ProducerPlan mapping position. The envelope
     * consumes the exact same immutable payload after binding only SQLite-allocated IDs.
     * Full envelope strict decode/equality and actual source checks are not replaced.
     */
    fun observation(log: MetricLogEntity, metricUuid: String): JsonObject {
        val expected = logsByUuid.getValue(log.uuid)
        check(log.copy(id = 0) == expected && metrics.getValue(log.metricId).uuid == metricUuid) {
            "METRIC_T1_OBSERVATION_CHANGED"
        }
        payloads[log.uuid]?.let { found ->
            return CountCommandCostTiming.measure(Step.METRIC_OBSERVATION_REUSED) { found }
        }
        return CountCommandCostTiming.measure(Step.METRIC_OBSERVATION_BUILT) {
            immutableJson(SyncV2Mapper.metricObservation(expected, metricUuid)).jsonObject.also { payloads[log.uuid] = it }
        }
    }

    /** Only the private LocalPreparation retains a builder. Observation has no public flag
     * and cannot make a caller-provided entity into an independently read DAO value.
     */
    class Builder(val intent: CountCommandRecords.Intent) {
        private class MetricValue(val actual: MetricEntity) { var definition: JsonObject? = null }
        private val observed = LinkedHashMap<Long, MetricValue>()
        private var input: JsonObject? = null
        private var source: WidgetFactClaim? = null
        private var sourceSeen = false
        private var valid = true
        private var retained = 0L

        // Optional representation budget only, never a smaller product N/value limit.
        private fun retain(amount: Long): Boolean {
            if (!valid || amount < 0 || amount > RETAINED_UNITS - retained) { valid = false; return false }
            retained += amount
            return true
        }
        fun observeActualDao(row: MetricEntity) {
            if (!valid) return
            // No mapping/validation before the Editor's original definition check.
            val strings = listOf(row.name,row.description,row.unit,row.aggregationType,row.targetDirection,
                row.colorHex,row.uuid,row.appearance?.accentColor,row.appearance?.iconTint,
                when (val icon = row.appearance?.icon) {
                    null -> null
                    is com.dayforge.domain.model.IconReference.Role -> icon.role
                    is com.dayforge.domain.model.IconReference.Asset -> icon.assetId
                })
            if (row.id in observed || !retain(256L + strings.sumOf { 2L * (it?.length ?: 0) })) {
                valid = false; return
            }
            observed[row.id] = MetricValue(row.copy())
        }
        fun definition(row: MetricEntity): JsonObject {
            val value = observed[row.id]?.takeIf { valid && it.actual == row }
                ?: return NextStructureMapper.writeMetric(row)
            value.definition?.let { found ->
                return CountCommandCostTiming.measure(Step.METRIC_DEFINITION_REUSED) { found }
            }
            // Same original JsonObject equality; never substitute canonical-string equality.
            val result = NextStructureMapper.writeMetric(row)
            return immutableJson(result).jsonObject.also { value.definition = it }
        }
        fun commandInput(actual: CountCommandRecords.Intent): JsonObject {
            if (!valid || actual !== intent) return actual.body().getValue("input").jsonObject
            input?.let { return it }
            val value = actual.body().getValue("input").jsonObject
            if (!retain(estimate(value))) return value
            return immutableJson(value).jsonObject.also { input = it }
        }
        fun sourceCompleted(claim: WidgetFactClaim) {
            if (!valid || sourceSeen) { valid = false; return }
            sourceSeen = true
            // Original structural equality: canonical command key order and fresh writer
            // insertion order are equivalent. Freeze the only collection-bearing fields.
            val bytes = estimate(claim.plan) + (claim.countPolicy?.let(::estimate) ?: 0L)
            if (retain(bytes)) source = claim.copy(plan = immutableJson(claim.plan).jsonObject,
                countPolicy = claim.countPolicy?.let { immutableJson(it).jsonObject })
        }
        fun hasCompletedSource(claim: WidgetFactClaim): Boolean =
            valid && source != null && source == claim

        fun seal(actualLogs: List<MetricLogEntity>, actualMetrics: Map<Long, MetricEntity>,
            claim: WidgetFactClaim, absentRanges: Set<String>, absentOrigins: Set<String>): MetricT1Input? {
            if (!hasCompletedSource(claim) || observed.keys != actualMetrics.keys ||
                observed.any { (id,value) -> value.actual != actualMetrics[id] || value.definition == null }) return null
            val body = input ?: return null
            if (actualLogs.any { it.value.toRawBits() == Long.MIN_VALUE }) return null
            if (!retain(actualLogs.sumOf { 256L + 2L * (it.note.length + it.unit.length + it.uuid.length +
                    it.recordedTimezone.length + it.recordedLocalDate.length) })) return null
            // Use the original complete range/absence results, never add another SQL audit.
            if (actualLogs.any { log ->
                listOf(CountTrustMembership.key("metricLog.uuid",log.uuid),
                    CountTrustMembership.key("shadow.entity","metric_observation",log.uuid),
                    CountTrustMembership.key("outbox.entity","metric_observation",log.uuid),
                    CountTrustMembership.key("outbox.wire","metric_observation",log.uuid)).any { key ->
                    key !in absentRanges
                } || log.uuid !in absentOrigins
            }) return null
            return CountCommandCostTiming.measure(Step.METRIC_INPUT_ISSUED) {
                MetricT1Input(intent.text(),body, Collections.unmodifiableMap(LinkedHashMap(actualMetrics)),
                    Collections.unmodifiableList(actualLogs.map { it.copy() }))
            }
        }
        fun close() { valid = false; observed.clear(); input = null; source = null }
    }

    companion object {
        private const val RETAINED_UNITS = 2_097_152L // Estimate, not total heap or product limit.
        private fun estimate(value: JsonElement): Long = when (value) {
            is JsonObject -> 64L + value.entries.sumOf { 32L + 2L * it.key.length + estimate(it.value) }
            is JsonArray -> 32L + value.sumOf(::estimate)
            is JsonPrimitive -> 32L + 2L * value.content.length
        }
        private fun immutableJson(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { immutableJson(it.value) }))
            is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::immutableJson)))
            else -> value // JsonPrimitive owns an immutable scalar/text spelling, including -0.0.
        }
    }
}
