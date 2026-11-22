package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import java.util.Collections

/** Predictions from the original prompt's intended entities and native allocated IDs.
 * No journal event is treated as permission, and no SQL write/signing capability is exposed.
 * The prompt store retains all original CAS, source, profile and final-row validation.
 */
internal class OncePromptTrust private constructor(
    private val database: HabitDatabase, private val context: CountTrustKernel.Context
) {
    private val effects = arrayListOf<CountTrustAck.Effect>()
    private var frozen = false
    private var supported = true
    private var metricIds: List<Long>? = null
    private var outboxIds: List<Long>? = null
    private val intendedQueues = linkedMapOf<String, SyncOutboxEntity>()
    private fun add(table: String, before: CountTrustRows.Row?, after: CountTrustRows.Row?) {
        check(!frozen && database.inTransaction())
        effects += CountTrustAck.Effect(table, before, after)
    }
    fun prompt(before: CompletionMetricPromptEntity, after: CompletionMetricPromptEntity) =
        add("completion_metric_prompts", OnceTrustEvidence.promptRow(context.rows, before),
            OnceTrustEvidence.promptRow(context.rows, after))

    /** Freeze both persistent allocation sequences before the original first business write.
     * TEMP journal sqlite_sequence and a deleted maximum row must not change the prediction.
     */
    fun prepareMetrics(size: Int) {
        check(!frozen && database.inTransaction() && size > 0 && metricIds == null && outboxIds == null)
        val metric = OnceTrustEvidence.nextId(database, "metric_logs")
        val outbox = OnceTrustEvidence.nextId(database, "sync_outbox")
        if (metric == null || outbox == null) { supported = false; return }
        try {
            metricIds = Collections.unmodifiableList(List(size) { Math.addExact(metric, it.toLong()) })
            outboxIds = Collections.unmodifiableList(List(size) { Math.addExact(outbox, it.toLong()) })
        } catch (_: ArithmeticException) { supported = false }
    }
    fun allocatedMetric(index: Int, actual: Long) {
        if (supported && requireNotNull(metricIds)[index] != actual) supported = false
    }
    fun allocatedOutbox(index: Int, actual: Long) {
        if (supported && requireNotNull(outboxIds)[index] != actual) supported = false
    }
    fun suppression() {
        if (!supported) return
        val before = context.rows.read("sync_control", "id=?", arrayOf(1)).single()
        check(before.long("suppressOutbox") == 0L)
        val suppressed = OnceTrustEvidence.replace(before, mapOf("suppressOutbox" to 1L))
        add("sync_control", before, suppressed)
        add("sync_control", suppressed, before)
    }
    fun metric(index: Int, value: MetricLogEntity) {
        if (!supported) return
        add("metric_logs", null, OnceTrustEvidence.native(context.rows, "metric_logs", mapOf(
            "id" to requireNotNull(metricIds)[index], "metricId" to value.metricId, "date" to value.date,
            "value" to value.value, "unit" to value.unit, "note" to value.note,
            "uuid" to value.uuid, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
            "recordedTimezone" to value.recordedTimezone, "recordedLocalDate" to value.recordedLocalDate,
            "timeMetadataSource" to value.timeMetadataSource)))
    }
    fun outbox(index: Int, value: SyncOutboxEntity) {
        if (!supported) return
        val intended = value.copy(id = requireNotNull(outboxIds)[index])
        check(intendedQueues.put(value.operationId, intended) == null)
        add("sync_outbox", null, OnceTrustEvidence.outboxRow(context.rows, intended))
    }
    fun submission(value: LocalFactSubmissionEntity) =
        add("local_fact_submissions", null, OnceTrustEvidence.submissionRow(context.rows, value))
    fun origin(value: NextRequestOriginEntity) {
        if (!supported) return
        val intended = requireNotNull(intendedQueues[value.requestId])
        add("next_request_origins", null, OnceTrustEvidence.originRow(context.rows,
            value.copy(queueId = intended.id, sourceHash = NextRequestSql.sourceHash(intended))))
    }

    fun freeze(): OnceTrustUpdate? {
        check(!frozen && database.inTransaction()); frozen = true
        return if (supported) Prepared(database, context, effects) else null
    }
    private class Prepared(private val owner: HabitDatabase,
        override val context: CountTrustKernel.Context, source: List<CountTrustAck.Effect>) : OnceTrustUpdate {
        override val effects = Collections.unmodifiableList(source.toList())
        override val metadata: ChallengeMetadata? = null
        // Prompt/metric writes do not change the ONCE confirmed state or pending intent chain.
        override val affectedHabitUuids: Set<String> = emptySet()
        override suspend fun verifyFinal(database: HabitDatabase) {
            check(database === owner && database.inTransaction()) { "ONCE_PROMPT_TRUST_OWNER_CHANGED" }
        }
    }
    companion object {
        fun create(database: HabitDatabase, capture: CountTrustKernel.Capture?): OncePromptTrust? =
            capture?.context?.takeIf { it.once }?.let { OncePromptTrust(database, it) }
    }
}
