package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.util.Collections

/** Complete insert-only local plan: exactly log+source+origin for
 * every entry. No structural dependency row is legitimate for a new metric_observation.
 * Plans are constructed independently. Journal values are used ONLY by verifyActual.
 */
internal class MetricObservationLocalEffects private constructor(
    private val groups: List<List<CountTrustAck.Effect>>,
    sources: List<SyncOutboxEntity>
) {
    // DTO fields are immutable scalar/String values. Own the list and each row independently
    // of Bound and the caller; these expected sources never come from native/journal decoding.
    private val preparedSources: List<SyncOutboxEntity> = Collections.unmodifiableList(sources.map { it.copy() })

    /** Expected NEW source rows only. Not authority, complete old queue membership or a
     * cross-transaction cache. The owner proves the complete queue transition before reuse;
     * the original complete 3N/domain journal still runs at the final producer boundary.
     */
    internal fun sources(): List<SyncOutboxEntity> = preparedSources

    /** SQLite does not promise a portable order between two AFTER triggers. The log INSERT and
     * its trigger's queue INSERT may be journaled in either order; both must be present exactly
     * once before the next log. Origin writes follow, in original ascending source-ID order.
     * This is a declared partial order, not adapting expected effects to the observed journal.
     */
    fun verifyActual(actual: List<CountTrustJournal.Change>, native: CountTrustRows) {
        check(actual.size == groups.sumOf { it.size }) { "METRIC_LOCAL_EXTRA_EFFECT" }
        var offset = 0
        for (group in groups) {
            val remaining = group.toMutableList()
            for (change in actual.subList(offset, offset + group.size)) {
                check(change.operation == "INSERT" && change.old == null && change.new != null) { "METRIC_LOCAL_NON_INSERT" }
                val layout = native.table(change.table)
                val columns = CountTrustJournal.COLUMNS.getValue(change.table)
                val cells = requireNotNull(change.new)
                val value = CountTrustRows.Row(layout, layout.columns.map { name -> cells[columns.indexOf(name)] }).value
                val at = remaining.indexOfFirst { it.table == change.table && it.before == null && it.after?.value == value }
                check(at >= 0) { "METRIC_LOCAL_UNEXPECTED_EFFECT" }
                remaining.removeAt(at)
            }
            check(remaining.isEmpty())
            offset += group.size
        }
    }

    /** Final disk contents must agree after ALL original producer callbacks and triggers finish.
     * Control generation/dirty and owner boundary checks are still the outer kernel's job.
     */
    fun verifyPreparedContents(native: CountTrustRows) = verifyContents(native, groups.takeWhile { it.size == 2 })

    fun verifyFinalContents(native: CountTrustRows) = verifyContents(native, groups)

    private fun verifyContents(native: CountTrustRows, selected: List<List<CountTrustAck.Effect>>) {
        for (effect in selected.flatten()) {
            val after = requireNotNull(effect.after)
            check(native.byIdentity(effect.table, after.identity)?.value == after.value) { "METRIC_LOCAL_FINAL_CONTENT_CHANGED" }
        }
    }

    companion object {
        fun from(bound: MetricObservationLocalEnvelopePlan.Bound, native: CountTrustRows): MetricObservationLocalEffects {
            val items = bound.items.toList()
            fun row(table: String, values: Map<String, Any?>): CountTrustRows.Row {
                val layout = native.table(table)
                require(values.keys == layout.columns.toSet())
                return CountTrustRows.Row(layout, layout.columns.map { cell(values.getValue(it)) })
            }
            val groups = arrayListOf<List<CountTrustAck.Effect>>()
            for (item in items) {
                val log = item.log; val source = item.source
                val fact = row("metric_logs", mapOf(
                    "id" to log.id, "metricId" to log.metricId, "date" to log.date, "value" to log.value,
                    "unit" to log.unit, "note" to log.note, "uuid" to log.uuid, "createdAt" to log.createdAt,
                    "updatedAt" to log.updatedAt, "recordedTimezone" to log.recordedTimezone,
                    "recordedLocalDate" to log.recordedLocalDate, "timeMetadataSource" to log.timeMetadataSource))
                val queue = row("sync_outbox", mapOf(
                    "id" to source.id, "operationId" to source.operationId, "recordType" to source.recordType,
                    "entityUuid" to source.entityUuid, "wireEntityUuid" to source.wireEntityUuid, "action" to source.action,
                    "referenceUuid" to source.referenceUuid, "payloadJson" to source.payloadJson,
                    "baseRevision" to source.baseRevision, "basePayloadJson" to source.basePayloadJson,
                    "attemptedAt" to source.attemptedAt, "attemptCount" to source.attemptCount,
                    "lastError" to source.lastError, "errorCode" to source.errorCode,
                    "deadLetteredAt" to source.deadLetteredAt, "createdAt" to source.createdAt))
                groups += listOf(CountTrustAck.Effect("metric_logs", null, fact), CountTrustAck.Effect("sync_outbox", null, queue))
            }
            for (item in items.sortedBy { it.source.id }) {
                val origin = item.origin
                val value = row("next_request_origins", mapOf(
                    "kind" to origin.kind, "requestId" to origin.requestId, "queueId" to origin.queueId,
                    "protocol" to origin.protocol, "accountId" to origin.accountId,
                    "serverInstanceId" to origin.serverInstanceId, "syncEpoch" to origin.syncEpoch,
                    "sourceHash" to origin.sourceHash, "intentJson" to origin.intentJson))
                groups += listOf(CountTrustAck.Effect("next_request_origins", null, value))
            }
            return MetricObservationLocalEffects(
                Collections.unmodifiableList(groups.map { Collections.unmodifiableList(it) }),
                items.map { it.source })
        }
        private fun cell(value: Any?): CountTrustJournal.Cell = when (value) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
            is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
            is Double -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_FLOAT, value.toRawBits())
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            else -> error("METRIC_LOCAL_UNSUPPORTED_CELL")
        }
    }
}
