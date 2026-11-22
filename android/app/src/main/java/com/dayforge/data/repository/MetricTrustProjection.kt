package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import java.util.Collections
import kotlinx.serialization.json.*

/** Immutable current read values; no account authority, SQL token or execution ticket escapes. */
internal class MetricWindowSnapshot(val metric: MetricEntity, val latest: MetricLogEntity?,
    logs: List<MetricLogEntity>, val startInclusive: Long, val endExclusive: Long,
    val directoryRevision: Long?) {
    val logs: List<MetricLogEntity> = Collections.unmodifiableList(logs.toList())
}

/** Same-snapshot native range verification. Original date ordering and original latest DAO
 * selection remain authoritative for ties; UUID radix order is used only for membership. */
internal class MetricTrustProjection(private val database: HabitDatabase) {
    suspend fun read(context: CountTrustKernel.Context, metric: MetricEntity,
        start: Long, end: Long): MetricWindowSnapshot? {
        check(database.inTransaction()); require(start <= end)
        return context.index.withReadScope {
            val reader = MetricTrustDirectory.Reader(context.index::get)
            if (!reader.declared(metric.uuid)) return@withReadScope null
            val parent = context.checked(CountTrustMembership.key("metric.id", metric.id.toString()),
                "metrics", "id=?", arrayOf(metric.id)).single()
            check(parent.text("uuid") == metric.uuid && database.metricDao().getMetricById(metric.id) == metric) {
                "METRIC_DISPLAY_DEFINITION_CHANGED"
            }
            val manifest = requireNotNull(reader.manifest(parent))
            val selected = database.metricLogDao().getLatestLog(metric.id)
            val latest = selected?.let { dto -> context.rows.read("metric_logs", "id=?", arrayOf(dto.id)).single().also {
                check(decode(it) == dto) { "METRIC_DISPLAY_NATIVE_CHANGED" }
            } }
            reader.latest(manifest, latest)
            val rows = rangeRows(context.rows.table("metric_logs"), metric.id, start, end)
            reader.range(manifest, start, end, rows)
            MetricWindowSnapshot(metric, selected, rows.map(::decode), start, end, manifest.revision)
        }
    }

    suspend fun history(context: CountTrustKernel.Context, metric: MetricEntity): List<MetricLogEntity>? {
        check(database.inTransaction())
        return context.index.withReadScope {
            val reader = MetricTrustDirectory.Reader(context.index::get)
            if (!reader.declared(metric.uuid)) return@withReadScope null
            val parent = context.checked(CountTrustMembership.key("metric.id", metric.id.toString()),
                "metrics", "id=?", arrayOf(metric.id)).single()
            check(parent.text("uuid") == metric.uuid && database.metricDao().getMetricById(metric.id) == metric) {
                "METRIC_DISPLAY_DEFINITION_CHANGED"
            }
            val manifest = requireNotNull(reader.manifest(parent))
            val rows = orderedRows(context.rows.table("metric_logs"),
                "SELECT * FROM metric_logs WHERE metricId=? ORDER BY date DESC", arrayOf(metric.id))
            reader.interval(manifest, null, null, rows)
            Collections.unmodifiableList(rows.map(::decode))
        }
    }

    class Page(logs: List<MetricLogEntity>, val totalCount: Long, val nextExclusiveDate: Long?,
        val complete: Boolean, val directoryRevision: Long?) {
        val logs = Collections.unmodifiableList(logs.toList())
    }

    /** Soft page: complete the boundary date cohort in one query; never append the LIMIT
     * prefix to it. A large same-date cohort is deliberately not advertised as hard bounded. */
    fun page(context: CountTrustKernel.Context?, metric: MetricEntity, upper: Long? = null,
        target: Int = 10, expectedRevision: Long? = null): Page {
        require(target in 1..128); check(database.inTransaction())
        val reader = context?.let { MetricTrustDirectory.Reader(it.index::get) }
        val parent = context?.takeIf { reader!!.declared(metric.uuid) }?.checked(
            CountTrustMembership.key("metric.id", metric.id.toString()), "metrics", "id=?", arrayOf(metric.id))?.single()
        val manifest = parent?.let { requireNotNull(reader).manifest(it) }
        if (expectedRevision != null) check(manifest?.revision == expectedRevision) { "METRIC_PAGE_CHANGED" }
        val sql = database.openHelper.writableDatabase
        val filter = "metricId=?" + if (upper == null) "" else " AND date<?"
        val parameters: Array<Any> = if (upper == null) arrayOf(metric.id) else arrayOf(metric.id, upper)
        val dates = sql.query("SELECT date FROM metric_logs WHERE $filter ORDER BY date DESC LIMIT ?",
            parameters + target).use { c -> buildList { while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(c.getLong(0)) } } }
        val boundary = dates.lastOrNull()
        val layout = context?.rows?.table("metric_logs") ?: CountTrustRows(sql).table("metric_logs")
        val rows = if (boundary == null) emptyList() else orderedRows(layout,
            "SELECT * FROM metric_logs WHERE $filter AND date>=? ORDER BY date DESC", parameters + boundary)
        if (manifest != null) requireNotNull(reader).interval(manifest, boundary, upper, rows)
        val count = manifest?.root?.count ?: sql.query("SELECT COUNT(*) FROM metric_logs WHERE metricId=?", arrayOf(metric.id)).use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER); c.getLong(0) }
        val remains = if (boundary == null) false else if (manifest != null) manifest.root.min?.let {
            (java.lang.Long.parseUnsignedLong(it.take(16), 16) xor Long.MIN_VALUE) < boundary
        } == true else sql.query(
            "SELECT EXISTS(SELECT 1 FROM metric_logs WHERE metricId=? AND date<?)", arrayOf(metric.id, boundary)).use { c ->
            check(c.moveToFirst()); c.getLong(0) != 0L }
        if (manifest != null && !remains && boundary != null) requireNotNull(reader).interval(manifest, null, boundary, emptyList())
        return Page(rows.map(::decode), count, boundary.takeIf { remains }, !remains, manifest?.revision)
    }

    fun verifyRoster(context: CountTrustKernel.Context) {
        val rows = context.rows.read("metrics")
        val expected = requireNotNull(context.index.get(MetricTrustDirectory.ROSTER_KEY)) { "METRIC_DIRECTORY_ROSTER_MISSING" }
            .jsonArray.map { it.jsonPrimitive.also { p -> check(p.isString) }.content }
        check(expected == rows.map { it.name }.sorted()) { "METRIC_DIRECTORY_ROSTER_CHANGED" }
        rows.forEach { check(context.index.get(it.name) == it.value) { "METRIC_DIRECTORY_DEFINITION_CHANGED" } }
    }

    private fun rangeRows(table: CountTrustRows.Table, id: Long, start: Long, end: Long): List<CountTrustRows.Row> =
        orderedRows(table, "SELECT * FROM metric_logs WHERE metricId=? AND date>=? AND date<? ORDER BY date ASC", arrayOf(id, start, end))

    private fun orderedRows(table: CountTrustRows.Table, query: String, args: Array<out Any?>): List<CountTrustRows.Row> =
        database.openHelper.writableDatabase.query(query, args).use { cursor ->
            check(cursor.columnNames.toList() == table.columns)
            buildList { while (cursor.moveToNext()) add(CountTrustRows.Row(table, table.columns.indices.map { n ->
                CountTrustJournal.Cell(cursor.getType(n), when (cursor.getType(n)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(n)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(n).toRawBits()
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(n)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(n).toList()
                    else -> error("METRIC_DISPLAY_NATIVE_TYPE")
                })
            })) }
        }

    companion object {
        internal fun decode(row: CountTrustRows.Row): MetricLogEntity {
            check(row.table.name == "metric_logs")
            val value = row.cell("value").let { check(it.type == Cursor.FIELD_TYPE_FLOAT); Double.fromBits(it.value as Long) }
            return MetricLogEntity(id = row.long("id"), metricId = row.long("metricId"), date = row.long("date"), value = value,
                unit = row.text("unit"), note = row.text("note"), uuid = row.text("uuid"), createdAt = row.long("createdAt"),
                updatedAt = row.long("updatedAt"), recordedTimezone = row.text("recordedTimezone"),
                recordedLocalDate = row.text("recordedLocalDate"), timeMetadataSource = row.text("timeMetadataSource"))
        }
    }
}
