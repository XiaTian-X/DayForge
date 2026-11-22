package com.dayforge.data.local

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.history.CountTrustIndex
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** v20 primary binds stable owned/business DDL, retaining every byte except exact optional
 * SQLite statistics tables already accepted by ApprovedBusinessSchema. This is neither
 * a schema repair nor a source-content permission. COUNT's existing envelope still binds
 * its original complete schema; only the separate derived recovery path interprets it.
 */
internal object CountCommandPrimarySchema {
    private val names get() = ApprovedBusinessSchema.optionalStatistics
    private data class Platform(val raw: List<List<String?>>, val objects: Map<String, ReplicaSqlSchema.SchemaObject>)

    /** No Room/primary recursion: the same Android SQLite implementation creates its own
     * optional statistics in a throwaway in-memory database. Only immutable metadata is
     * published, after actual END and close. No source handle, key or account survives.
     */
    private val platform: Platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val result = SQLiteDatabase.create(null).use { native ->
            native.beginTransaction()
            try {
                native.execSQL("ANALYZE")
                val rows = native.rawQuery("SELECT type,name,tbl_name,sql FROM main.sqlite_master " +
                    "WHERE lower(name) IN ('sqlite_stat1','sqlite_stat4') OR lower(tbl_name) IN ('sqlite_stat1','sqlite_stat4') ORDER BY type,name", null).use { c ->
                    buildList<List<String?>> { while (c.moveToNext()) {
                        poll(); require((0..3).all { c.getType(it) == Cursor.FIELD_TYPE_STRING })
                        add(Collections.unmodifiableList((0..3).map { c.getString(it) }))
                    } }
                }
                require(rows.size in 1..2 && rows.any { it[1] == "sqlite_stat1" }) { "COMMAND_STATISTICS_REFERENCE_INVALID" }
                val frozen = Collections.unmodifiableList(rows)
                val objects = Collections.unmodifiableMap(objects(frozen))
                native.setTransactionSuccessful()
                Platform(frozen, objects)
            } finally { native.endTransaction() }
        }
        poll(); result
    }

    /** Must precede source schema text materialization. Metadata only, no statistics rows. */
    fun requireStatisticsPreflight(sql: SupportSQLiteDatabase) {
        sql.query("SELECT type,typeof(name),length(CAST(name AS BLOB)),typeof(tbl_name),length(CAST(tbl_name AS BLOB))," +
            "typeof(sql),length(CAST(sql AS BLOB)) FROM main.sqlite_master " +
            "WHERE lower(name) IN ('sqlite_stat1','sqlite_stat4') OR lower(tbl_name) IN ('sqlite_stat1','sqlite_stat4') LIMIT 3").use { c ->
            var count = 0
            while (c.moveToNext()) {
                poll(); require(++count <= 2 && c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "table" &&
                    listOf(1, 3, 5).all { c.getType(it) == Cursor.FIELD_TYPE_STRING && c.getString(it) == "text" } &&
                    listOf(2, 4, 6).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER } &&
                    c.getLong(2) in 1..128L && c.getLong(4) in 1..128L && c.getLong(6) in 1..262_144L) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
            }
        }
    }

    /** Shared journal's exact v20 table inventory uses this same present-object contract.
     * It does not grant schema repair, a root, a key or a business write capability.
     */
    fun requireStatisticsSchema(sql: SupportSQLiteDatabase) {
        requireStatisticsPreflight(sql)
        val rows = sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master " +
            "WHERE lower(name) IN ('sqlite_stat1','sqlite_stat4') OR lower(tbl_name) IN ('sqlite_stat1','sqlite_stat4') ORDER BY type,name").use { c ->
            buildList<List<String?>> { while (c.moveToNext()) {
                poll(); require((0..3).all { c.getType(it) == Cursor.FIELD_TYPE_STRING }) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
                add((0..3).map { c.getString(it) })
            } }
        }
        require(ApprovedBusinessSchema.statisticsMatch(objects(rows), platform.objects)) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
    }

    fun hash(rows: List<List<String?>>): String = fullHash(stableRows(rows))

    fun stableRows(rows: List<List<String?>>): List<List<String?>> {
        poll()
        val statistics = rows.filter { row ->
            require(row.size == 4 && row.take(3).all { it != null }) { "COMMAND_SCHEMA_ROW" }
            require(row[3] == null || requireNotNull(row[3]).toByteArray(Charsets.UTF_8).size <= 262_144) { "SYNC_RECOVERY_SCHEMA_INVALID" }
            requireNotNull(row[1]).lowercase(Locale.ROOT) in names || requireNotNull(row[2]).lowercase(Locale.ROOT) in names
        }
        require(ApprovedBusinessSchema.statisticsMatch(objects(statistics), platform.objects)) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
        return Collections.unmodifiableList(rows.filterNot { it in statistics }.map { Collections.unmodifiableList(it.toList()) })
    }

    /** Finite known DDL candidates, not a MAC grant. Used only to classify a dirty, fully
     * authenticated old derived envelope after tracked ANALYZE/statistics removal. Original
     * retained rows are exact bytes; unknown sqlite_* or arbitrary CREATE variants remain.
     */
    fun completeSchemaVariants(rows: List<List<String?>>): Set<String> {
        val stable = stableRows(rows)
        val actual = rows.filter { requireNotNull(it[1]).lowercase(Locale.ROOT) in names }
        var variants = listOf(stable)
        for (name in names) {
            val known = (platform.raw + actual).filter { it[1] == name }.distinct()
            variants = variants.flatMap { old -> listOf(old) + known.map { old + listOf(it) } }
        }
        return variants.map { fullHash(it.sortedWith(compareBy({ row -> row[0] }, { row -> row[1] }))) }.toSet()
    }

    fun fullHash(rows: List<List<String?>>): String = CountTrustIndex.hash(JsonArray(rows.map { row ->
        JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }).toString().toByteArray(Charsets.UTF_8))

    private fun objects(rows: List<List<String?>>): Map<String, ReplicaSqlSchema.SchemaObject> = buildMap {
        for (row in rows) {
            poll(); require(row.size == 4 && row.all { it != null }) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
            val type = requireNotNull(row[0]); val name = requireNotNull(row[1]); val owner = requireNotNull(row[2])
            val canonical = name.lowercase(Locale.ROOT)
            require(type == "table" && canonical in names && owner.lowercase(Locale.ROOT) == canonical) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
            val sql = requireNotNull(row[3])
            require(sql.toByteArray(Charsets.UTF_8).size <= 262_144) { "SYNC_RECOVERY_SCHEMA_INVALID" }
            val definition = ReplicaSchemaSql.table(ReplicaSchemaSql.tokens(sql), name)
            require(put("table.$canonical", ReplicaSqlSchema.SchemaObject(type, canonical, canonical, definition)) == null) { "COMMAND_STATISTICS_SCHEMA_INVALID" }
        }
    }

    private fun poll() { if (Thread.currentThread().isInterrupted) throw CancellationException("Command schema interrupted") }
}
