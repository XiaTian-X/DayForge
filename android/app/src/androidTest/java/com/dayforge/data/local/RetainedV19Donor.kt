package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.repository.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** A new historical test INPUT derived from a complete real protocol donor. This is not a
 * downgrade of a production database. Its constructor is private, requires actual Host
 * shutdown and exact re-read of the closed donor, and never issues a root/signature/profile.
 * The independent target20 root is made only by production19->20 migration/enrollment. */
internal class RetainedV19Donor private constructor(
    private val originalDatabase: HabitDatabase,
    private val originalHost: DatabaseRuntimeHost,
    private val originalImage: JsonObject,
    private val columns: Map<String, List<String>>,
    private val rawDigests: Map<String, String>,
    private val archive: File,
    private val archiveHash: String
) {
    private val rows get() = originalImage.getValue("tables").jsonObject
    private var consumed = false
    private var released = false

    fun requireSourceClosed(epochs: PhysicalDatabaseEpochFixture) {
        check(!released && originalHost !== epochs.host && originalHost.lifecycleEnded && !originalDatabase.isOpen)
        check(requireNotNull(CountTrustRuntime.forDatabase(originalDatabase)).isGraphCloseConfirmed(originalDatabase))
        assertEquals(1, epochs.realRoomCreates)
        check(archive.isFile && hashFile(archive) == archiveHash)
    }

    /** Called once while creating NEW19 tables, BEFORE old dirty/outbox callbacks exist. */
    fun copyOriginalBusinessIntoNew19(sql: SupportSQLiteDatabase) {
        check(!consumed && originalHost.lifecycleEnded && !originalDatabase.isOpen)
        assertEquals(20, originalImage.getValue("user_version").jsonPrimitive.int)
        for (table in BUSINESS) {
            val target = columnNames(sql, table)
            assertEquals("Historical business columns changed: $table", columns.getValue(table), target)
            sql.query("SELECT 1 FROM main.`$table` LIMIT 1").use { assertFalse(it.moveToFirst()) }
            insertNativeRows(sql, table, rows.getValue(table).jsonArray, target)
        }
        // Row IDs and sequence allocation are part of the old source, not guessed maxima.
        sql.execSQL("DELETE FROM main.sqlite_sequence")
        insertNativeRows(sql, "sqlite_sequence", rows.getValue("sqlite_sequence").jsonArray, listOf("name", "seq"))
        sql.execSQL("DELETE FROM main.android_metadata")
        insertNativeRows(sql, "android_metadata", rows.getValue("android_metadata").jsonArray, listOf("locale"))
        consumed = true
        requireBusinessEquals(sql)
    }

    /** Independent native cells AND SQL text/blob bytes; no current proof object is copied. */
    fun requireBusinessEquals(sql: SupportSQLiteDatabase) {
        check(consumed)
        val actual = CountCommandMigrationFixture.snapshot(sql).contents.getValue("tables").jsonObject
        for (table in BUSINESS + AUXILIARY) {
            assertEquals("Every original native cell/rowid: $table", rows.getValue(table), actual.getValue(table))
            assertEquals("Every original native byte stream: $table", rawDigests.getValue(table), rawDigest(sql, table, columns.getValue(table)))
        }
        sql.query("PRAGMA main.foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    /** Only after all actual target/donor Hosts have closed and the preference actor drained. */
    fun releaseAfterHostsClosed(epochs: PhysicalDatabaseEpochFixture) {
        check(!released && originalHost.lifecycleEnded && epochs.host.lifecycleEnded)
        check(!originalDatabase.isOpen && epochs.createdRoomInspection().none { it.isOpen })
        check(archive.isFile && hashFile(archive) == archiveHash)
        check(archive.delete()); released = true
    }

    companion object {
        // Exact old business/protocol closure from KSP19. No wildcard/prefix can silently
        // exempt a new table. The four excluded tables authenticate DERIVED current state.
        private val BUSINESS: Set<String> = java.util.Collections.unmodifiableSet(linkedSetOf("habits", "completions", "count_days", "timelogs", "metrics", "metric_logs",
            "habit_metric_links", "sync_outbox", "sync_entity_state", "sync_conflicts", "sync_control",
            "timer_command_outbox", "timer_segments", "timelog_day_allocations", "local_fact_submissions",
            "completion_metric_prompts", "one_time_transmissions", "next_recovery_state", "next_request_origins",
            "next_transmissions", "next_acceptances", "next_structural_dependencies", "next_structural_supersessions",
            "next_sync_state", "next_rejections", "next_challenge_rounds", "next_challenge_births", "next_challenge_state",
            "next_restart_materializations", "next_restart_plan_proofs", "next_config_imports", "next_config_import_payloads",
            "next_recovery_audits", "next_abandoned_requests", "next_recovery_audit_payloads", "next_replica_fence"))
        private val DERIVED19 = setOf("count_trust_control", "count_trust_root", "count_trust_nodes", "count_trust_routes")
        private val COMMAND20 = setOf("count_command_intents", "count_command_status", "count_command_nodes", "count_command_recovery_roots")
        private val AUXILIARY = setOf("sqlite_sequence", "android_metadata")
        private fun hashFile(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun columnNames(sql: SupportSQLiteDatabase, table: String): List<String> {
            require(table.matches(Regex("[a-z_]+")))
            return sql.query("PRAGMA main.table_info(`$table`)").use { c -> buildList {
                while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name")))
            } }
        }
        private fun nativeValue(cell: JsonObject): Any? = when (cell.getValue("sqlite_type").jsonPrimitive.int) {
            Cursor.FIELD_TYPE_NULL -> { require(cell.getValue("value") == JsonNull); null }
            Cursor.FIELD_TYPE_INTEGER -> cell.getValue("value").jsonPrimitive.long
            Cursor.FIELD_TYPE_FLOAT -> Double.fromBits(cell.getValue("value").jsonPrimitive.content.toLong(16))
            Cursor.FIELD_TYPE_STRING -> cell.getValue("value").jsonPrimitive.content
            Cursor.FIELD_TYPE_BLOB -> cell.getValue("value").jsonPrimitive.content.let { hex ->
                require(hex.length % 2 == 0); ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            else -> error("Unsupported donor native cell")
        }
        private fun insertNativeRows(sql: SupportSQLiteDatabase, table: String, values: JsonArray, names: List<String>) {
            require(names.all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) })
            val fields = listOf("rowid") + names
            val statement = "INSERT INTO main.`$table`(${fields.joinToString(",") { "`$it`" }}) VALUES(${fields.joinToString(",") { "?" }})"
            for (value in values) {
                val row = value.jsonObject
                require(row.keys == (names + "__recovery_rowid").toSet())
                val bound = (listOf(nativeValue(row.getValue("__recovery_rowid").jsonObject)) +
                    names.map { nativeValue(row.getValue(it).jsonObject) }).toTypedArray()
                sql.execSQL(statement, bound)
            }
        }
        private fun rawDigest(sql: SupportSQLiteDatabase, table: String, fields: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun put(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            put(table)
            val selected = listOf("rowid") + fields.flatMap { listOf("typeof(`$it`)", "hex(CAST(`$it` AS BLOB))") }
            // Match snapshot's persistent MAIN namespace. A live command journal creates
            // temp.sqlite_sequence, which otherwise shadows the original sequence table.
            sql.query("SELECT ${selected.joinToString(",")} FROM main.`$table` ORDER BY rowid").use { c ->
                while (c.moveToNext()) for (i in 0 until c.columnCount) put(c.getString(i))
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        suspend fun captureAndClose(context: Context, donor: DatabaseRuntimeHost, tokens: TokenManager): RetainedV19Donor {
            check(context.packageName == "com.dayforge.testbed")
            val expected19 = CountCommandMigrationFixture.asset(19).getValue("entities").jsonArray
                .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }.toSet()
            assertEquals(BUSINESS + DERIVED19 + "count_command_results", expected19)
            data class Captured(val database: HabitDatabase, val image: JsonObject,
                val columns: Map<String, List<String>>, val digests: Map<String, String>)
            val captured = donor.withGraph { graph ->
                val access = requireNotNull(tokens.localSyncAccess())
                graph.database.withTransaction {
                    val sql = graph.database.openHelper.writableDatabase
                    assertEquals(20, sql.version)
                    val state = NextChallengeStore(graph.database).activeInTransaction(access).first
                    assertEquals(1, state.challengeContract)
                    assertEquals(access.session.authentication.userId, state.accountId)
                    val authenticated = CountCommandStorageInitialization.verifyRootAnchor(sql)
                    assertEquals(CountCommandRootCodec.Mode.EMPTY, authenticated.header.mode)
                    val original = CountCommandMigrationFixture.snapshot(sql).contents
                    val tables = original.getValue("tables").jsonObject
                    assertEquals(expected19 + COMMAND20 + AUXILIARY + "room_master_table", tables.keys)
                    for (table in COMMAND20 - "count_command_recovery_roots" + "count_command_results")
                        assertTrue("Donor cannot erase a current command: $table", tables.getValue(table).jsonArray.isEmpty())
                    for (table in listOf("sync_outbox", "timer_command_outbox", "next_request_origins", "next_acceptances",
                        "next_transmissions", "next_rejections", "next_replica_fence")) assertTrue(tables.getValue(table).jsonArray.isEmpty())
                    assertEquals(1, tables.getValue("next_sync_state").jsonArray.size)
                    assertTrue(tables.getValue("next_challenge_state").jsonArray.isNotEmpty())
                    assertTrue(tables.getValue("habits").jsonArray.isNotEmpty())
                    assertTrue(tables.getValue("metrics").jsonArray.isNotEmpty())
                    val sequences = tables.getValue("sqlite_sequence").jsonArray
                    assertTrue(sequences.all { it.jsonObject.getValue("name").jsonObject.getValue("value").jsonPrimitive.content in BUSINESS })
                    val names = java.util.Collections.unmodifiableMap((BUSINESS + AUXILIARY).associateWith {
                        java.util.Collections.unmodifiableList(columnNames(sql, it))
                    })
                    val bytes = java.util.Collections.unmodifiableMap(names.mapValues { (table, fields) -> rawDigest(sql, table, fields) })
                    // Re-parse private immutable bytes, never let external maps mutate a snapshot.
                    val encoded = original.toString(); check(encoded.toByteArray().size < 32 * 1024 * 1024)
                    Captured(graph.database, Json.parseToJsonElement(encoded).jsonObject, names, bytes)
                }.also { check(tokens.isCurrentSyncAuthority(access)) }
            }
            shutdownTestHost(donor)
            check(donor.lifecycleEnded && !captured.database.isOpen)
            check(requireNotNull(CountTrustRuntime.forDatabase(captured.database)).isGraphCloseConfirmed(captured.database))
            currentCoroutineContext().ensureActive()
            return withContext(Dispatchers.IO) {
                val file = context.getDatabasePath("habit_database")
                check(file.isFile && file.length() in 1..67_108_864L)
                // Read after actual owner closure, before moving any file. The helper has no
                // migrations/callback writes and must observe the EXACT previously frozen DB.
                var closedSql: SupportSQLiteDatabase? = null
                FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
                    .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                        override fun onCreate(db: SupportSQLiteDatabase) = error("Closed donor must exist")
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("No donor upgrade")
                    }).build()).use { helper ->
                    val sql = helper.writableDatabase.also { closedSql = it }
                    assertTrue("Closed donor complete schema/native image changed",
                        captured.image == CountCommandMigrationFixture.snapshot(sql).contents)
                    for ((table, fields) in captured.columns) assertEquals(captured.digests.getValue(table), rawDigest(sql, table, fields))
                }
                check(closedSql?.isOpen == false)
                check(!File(file.path + "-wal").exists() || File(file.path + "-wal").length() == 0L)
                val archive = context.getDatabasePath("retained-formal-donor-${UUID.randomUUID()}")
                check(!archive.exists() && file.renameTo(archive))
                context.deleteDatabase("habit_database") // Only closed synthetic sidecars; the donor is archived.
                check(!file.exists())
                RetainedV19Donor(captured.database, donor, captured.image, captured.columns, captured.digests, archive, hashFile(archive))
            }
        }
    }
}
