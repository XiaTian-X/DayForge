package com.dayforge.data.repository

import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.dayforge.data.api.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountIconController
import io.mockk.coEvery
import io.mockk.mockk
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*

/** Tests current production cleanup, not the unmerged GC. No production injection hook or
 * arbitrary callback is added. Raw setup changes only this isolated testbed database. */
abstract class CountTrustCleanupIncomingForeignKeyFixture : NextReplicaDestinationFixture() {
    private val cipherAlias = "dayforge.test.cleanup-fk.${UUID.randomUUID()}"
    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher(cipherAlias)
    private val controllers = arrayListOf<AccountIconController>()
    internal val runtimeInitializations = AtomicInteger()
    private val trace = CleanupTrace()
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, trace) } }
    @After fun cleanupOwnedResources() {
        controllers.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(cipherAlias) }
    }
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    internal data class Cell(val type: Int, val value: Any?)
    internal data class Image(val version: Int, val schema: List<List<Cell>>,
        val columns: Map<String, List<String>>, val tables: Map<String, List<List<Cell>>>)
    private fun cells(cursor: Cursor): List<Cell> = (0 until cursor.columnCount).map { column ->
        val type = cursor.getType(column)
        Cell(type, when (type) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
            else -> error("Unexpected SQLite storage type")
        })
    }
    private suspend fun image(): Image = db.withTransaction {
        val schema = sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            buildList { while (c.moveToNext()) add(cells(c)) }
        }
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) }) }
        }
        val columns = linkedMapOf<String, List<String>>()
        val values = tables.associateWith { table ->
            sql.query("SELECT rowid AS __cleanup_rowid,* FROM main.`$table` ORDER BY rowid").use { c ->
                columns[table] = c.columnNames.toList()
                buildList { while (c.moveToNext()) add(cells(c)) }
            }
        }
        Image(sql.version, schema, columns, values)
    }
    private class OracleRollback : RuntimeException()

    /** First prove the native FK effect itself, then prove its rollback restores ALL main
     * native values, rowids, definitions and version before testing any production entry. */
    internal suspend fun installAndProveCascade(): Image {
        val body = "cleanup-incoming-fk-node".toByteArray()
        val digest = CountTrustIndex.hash(body)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any>(digest, body))
            sql.execSQL("INSERT INTO count_trust_routes VALUES('completions','fk-case',NULL,NULL,NULL,NULL,'derived','hash')")
            sql.execSQL("INSERT INTO count_command_results VALUES('fk-command','input','fact',NULL,'retained','unknown')")
        } }
        val outsideTransactionFk = sql.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0))
            it.getInt(0).also { value -> assertTrue(value in 0..1) }
        }
        android.util.Log.i("CountCleanupFk", "phase=connection outside_transaction_fk=$outsideTransactionFk")
        // Same actual corruption class as the reviewed GC counterexample. No extra table or
        // trigger is introduced; the previously optional existing definition is what changes.
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE).use { native ->
            native.execSQL("ALTER TABLE android_metadata ADD COLUMN cleanupRef TEXT REFERENCES count_trust_nodes(digest) ON DELETE CASCADE")
            native.execSQL("INSERT INTO android_metadata(locale,cleanupRef) VALUES('cleanup-side-effect',?)", arrayOf<Any>(digest))
        }
        val before = image()
        var cascadeReached = false
        val failure = try {
            db.withTransaction<Unit> {
                owner.maintenance(db) {
                    assertTrue(db.inTransaction()); assertTrue(sql.inTransaction())
                    val writerFk = sql.query("PRAGMA foreign_keys").use {
                        assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0)); it.getInt(0)
                    }
                    android.util.Log.i("CountCleanupFk", "phase=connection oracle_writer_fk=$writerFk actual_write_transaction=true")
                    assertEquals("The actual writer connection must already enforce foreign keys", 1, writerFk)
                    sql.execSQL("DELETE FROM count_trust_nodes WHERE digest=?", arrayOf<Any>(digest))
                    sql.query("SELECT count(*) FROM main.android_metadata WHERE cleanupRef=?", arrayOf<Any>(digest)).use {
                        assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
                    }
                    sql.query("SELECT count(*) FROM main.android_metadata").use {
                        assertTrue(it.moveToFirst()); assertEquals(before.tables.getValue("android_metadata").size - 1L, it.getLong(0))
                    }
                    cascadeReached = true
                }
                throw OracleRollback()
            }
            null
        } catch (error: OracleRollback) { error }
        assertTrue("The real FK effect must precede the sentinel rollback", cascadeReached)
        assertTrue(failure is OracleRollback)
        assertEquals("The oracle must leave the complete main database unchanged", before, image())
        android.util.Log.i("CountCleanupFk", "phase=oracle cascade_reached=true full_rollback=true")
        return before
    }

    /** Keeps failure observation before asserting, so an unfixed run records the actual entry
     * and any undeclared metadata loss rather than stopping at an unrelated late exception. */
    internal suspend fun requireEntryRejectsWithoutEffects(label: String, expected: Image, operation: suspend () -> Unit) {
        val before = expected
        val preferencesBefore = dataStore.data.first()
        trace.begin()
        var returned = false
        val failure = try { operation(); returned = true; null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { error }
        finally { trace.stop() }
        val sample = trace.snapshot()
        val after = image()
        android.util.Log.i("CountCleanupFk", "phase=production entry=$label returned=$returned " +
            "failure_class=${failure?.javaClass?.simpleName ?: "none"} owned_schema_reads=${sample.ownedSchema} " +
            "full_schema_reads=${sample.fullSchema} node_delete_requests=${sample.nodes} control_delete_requests=${sample.control} " +
            "metadata_rows_before=${before.tables.getValue("android_metadata").size} metadata_rows_after=${after.tables.getValue("android_metadata").size} " +
            "full_main_equal=${before == after}")
        assertTrue("The actual cleanup entry/schema boundary must be reached: $sample",
            sample.ownedSchema + sample.fullSchema + sample.nodes + sample.control > 0)
        assertEquals("Cleanup must not cascade into optional Android metadata", before.tables.getValue("android_metadata"), after.tables.getValue("android_metadata"))
        assertEquals("Malformed incoming schema must reject before any durable reset/delete", before, after)
        assertEquals(preferencesBefore, dataStore.data.first())
        assertNotNull("Unsafe incoming FK must be refused by the production entry", failure)
        assertFalse("Refusal cannot return successful cleanup", returned)
        assertEquals(0, sample.nodes); assertEquals(0, sample.control)
    }

    internal fun actualRecoveryRepository(): ReplicaRecoveryRepository {
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val networks = mockk<NetworkMonitor>(); coEvery { networks.localNetworks() } returns emptyList()
        val business = BusinessSyncRepository(mockk(), mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val controller = AccountIconController({ runtimeInitializations.incrementAndGet(); runtime() }, tokens).also(controllers::add)
        val connection = ReplicaRecoveryConnection(EndpointResolver(networks, preferences, tokens, Json, SelectedNetworkTransport()),
            tokens, sessions, client, http, AccountIconHttp(client, tokens, peer.origin))
        return ReplicaRecoveryRepository(context, db, tokens, sessions, preferences, dataStore, business,
            NextReplicaCandidateValidator(context, tokens, sessions, preferences), controller, connection)
    }

    private class CleanupTrace : RoomDatabase.QueryCallback {
        data class Sample(val ownedSchema: Int, val fullSchema: Int, val nodes: Int, val control: Int)
        private val ownedSchema = AtomicInteger(); private val fullSchema = AtomicInteger()
        private val nodes = AtomicInteger(); private val control = AtomicInteger()
        @Volatile private var active = false
        fun begin() { ownedSchema.set(0); fullSchema.set(0); nodes.set(0); control.set(0); active = true }
        fun stop() { active = false }
        fun snapshot() = Sample(ownedSchema.get(), fullSchema.get(), nodes.get(), control.get())
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (!active) return
            val text = sqlQuery.trim().replace("`", "").replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
            if (text.contains("from sqlite_master where name in (")) ownedSchema.incrementAndGet()
            if (text == "select type,name,tbl_name,sql from main.sqlite_master order by type,name") fullSchema.incrementAndGet()
            if (text == "delete from count_trust_nodes" || text == "delete from main.count_trust_nodes") nodes.incrementAndGet()
            if (text == "delete from count_trust_control" || text == "delete from main.count_trust_control") control.incrementAndGet()
        }
    }
}
