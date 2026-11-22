package com.dayforge.data.repository

import android.database.Cursor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Actual registered local ACK, real Keystore and production controlled Room.
 * The three-access assertion is restricted to the explicitly absent trusted-root fixture.
 */
@RunWith(AndroidJUnit4::class)
class NextCoreAckAuthorityTest : NextCoreRequestFixture() {
    private val alias = "dayforge.test.ack.authority.${UUID.randomUUID()}"
    private val accessCalls = AtomicInteger()
    private val refreshCalls = AtomicInteger()
    private val otherCalls = AtomicInteger()
    private val reads = AtomicInteger()
    private val native = AckAuthorityNativeObservation()
    @Volatile private var accessCiphertext: String? = null
    @Volatile private var refreshCiphertext: String? = null
    @Volatile private var deleteKeyAtAccess = 0
    @Volatile private var changeGenerationAtRead = 0
    @get:Rule(order = -3) val keyCleanup = object : ExternalResource() {
        override fun after() { deleteKey() }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, nativeFactory = native) } }

    private fun deleteKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String): String = actual.encrypt(value).also {
                when (value) {
                    "synthetic-first" -> accessCiphertext = it
                    "synthetic-refresh" -> refreshCiphertext = it
                }
            }
            override fun decrypt(value: String): String? {
                when (value) {
                    accessCiphertext -> if (accessCalls.incrementAndGet() == deleteKeyAtAccess) deleteKey()
                    refreshCiphertext -> refreshCalls.incrementAndGet()
                    else -> otherCalls.incrementAndGet()
                }
                return actual.decrypt(value)
            }
        }
    }
    override fun isolatedDataStore(data: DataStore<Preferences>): DataStore<Preferences> {
        val delegate = data
        return object : DataStore<Preferences> {
            // Keep the original continuous stream; only the real delegate actor changes preferences.
            override val data: Flow<Preferences> = delegate.data.onStart {
                if (reads.incrementAndGet() == changeGenerationAtRead)
                    delegate.edit { it[stringPreferencesKey("auth_session_generation")] = id(991) }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData(transform)
        }
    }
    private fun resetCounts() {
        reads.set(0); accessCalls.set(0); refreshCalls.set(0); otherCalls.set(0)
    }
    private fun counts(expectedReads: Int, access: Int, refresh: Int) {
        assertEquals(expectedReads, reads.get()); assertEquals(access, accessCalls.get())
        assertEquals(refresh, refreshCalls.get()); assertEquals(0, otherCalls.get())
    }

    @Test fun registeredCommitAndReplayKeepFreshAccessWithoutRefreshDecryption() = runBlocking<Unit> {
        val row = editMetric("Credential split")
        val (store, delivery) = success(row)
        assertTrue("The actual pre-ACK HTTP credential path still reads refresh", refreshCalls.get() > 0)
        native.arm(row.id, row.operationId, metric.uuid)
        resetCounts()
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        counts(3, 3, 0); native.requireCommitted()
        val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(db.syncOutboxDao().getById(row.id))
        resetCounts()
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery))
        counts(3, 3, 0)
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        for (damaged in listOf(true, false)) {
            dataStore.edit {
                if (damaged) it[stringPreferencesKey("refresh_token")] = "enc:v1:broken"
                else it.remove(stringPreferencesKey("refresh_token"))
            }
            resetCounts()
            assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery))
            counts(3, 3, 0)
            assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        }
        assertEquals(1, count("next_acceptances")); assertNull(db.syncOutboxDao().getById(row.id))
    }

    @Test fun currentMetadataChangeAndAccessKeyLossRejectBeforeAckEffects() = runBlocking<Unit> {
        val row = editMetric("Entry key failure")
        val (store, delivery) = success(row)
        native.arm(row.id, row.operationId, metric.uuid)
        val original = dataStore.data.first()
        dataStore.edit { it[stringPreferencesKey("sync_device_id")] = id(992) }
        resetCounts()
        val changed = rejected { store.acceptOperation(delivery) }
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (changed as NextRequestException).reason)
        counts(1, 0, 0); native.requireNoEffects()
        dataStore.updateData { original }
        resetCounts(); deleteKeyAtAccess = 1
        val error = rejected { store.acceptOperation(delivery) }
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (error as NextRequestException).reason)
        counts(1, 1, 0); native.requireNoEffects()
        assertUnaccepted(row)
    }

    @Test fun currentAccessKeyLossAtOriginalFinalGuardRollsBackActualAckEffects() = runBlocking<Unit> {
        val row = editMetric("Final key failure")
        val (store, delivery) = success(row)
        native.arm(row.id, row.operationId, metric.uuid)
        resetCounts(); deleteKeyAtAccess = 3
        val error = rejected { store.acceptOperation(delivery) }
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (error as NextRequestException).reason)
        counts(3, 3, 0); native.requireRolledBack()
        assertUnaccepted(row)
    }

    @Test fun freshGenerationChangeAtOriginalFinalGuardRollsBackActualAckEffects() = runBlocking<Unit> {
        val row = editMetric("Final generation failure")
        val (store, delivery) = success(row)
        native.arm(row.id, row.operationId, metric.uuid)
        resetCounts(); changeGenerationAtRead = 3
        val error = rejected { store.acceptOperation(delivery) }
        assertEquals(NextRequestException.Reason.STALE_ACCESS, (error as NextRequestException).reason)
        counts(3, 2, 0); native.requireRolledBack()
        assertUnaccepted(row)
    }

    @Test fun nullDeviceKeepsOriginalSnapshotBeforeOriginalCausalPermissionRejection() = runBlocking<Unit> {
        val row = editMetric("Registration compatibility")
        val (store, delivery) = success(row)
        assertNull(requireNotNull(db.nextStructuralCausalDao().dependency(row.operationId)).capturedDeviceId)
        val registration = LocalSyncAccess(delivery.access.session)
        native.arm(row.id, row.operationId, metric.uuid)
        resetCounts()
        val error = rejected { store.acceptOperation(delivery.copy(access = registration)) }
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED, (error as NextRequestException).reason)
        counts(2, 2, 1); native.requireNoEffects()
        assertUnaccepted(row)
    }
}

/** Fixed read-only native observer below the original controlled factory. No SQL/handle callback API.
 * Observe actual queue DELETE return and the original outer engine END, not Room query callbacks.
 */
private class AckAuthorityNativeObservation : SupportSQLiteOpenHelper.Factory {
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val rows: List<List<Cell>>)
    private data class Image(val version: Int, val cookie: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Attempt(val queue: Long, val request: String, val metric: String,
        val raw: SupportSQLiteDatabase, val before: Image) {
        @Volatile var effects = false
        @Volatile var rollback = false
        @Volatile var endFailure: Throwable? = null
        val ends = AtomicInteger()
    }
    @Volatile private var opened: SupportSQLiteDatabase? = null
    @Volatile private var selected: Attempt? = null
    private val writer = ThreadLocal<Attempt?>()

    fun arm(queue: Long, request: String, metric: String) {
        val raw = requireNotNull(opened)
        check(selected == null && !raw.inTransaction())
        check(scalar(raw, "SELECT count(*) FROM main.count_trust_root") == 0L)
        selected = Attempt(queue, request, metric, raw, image(raw))
    }
    fun requireNoEffects() {
        val a = requireNotNull(selected)
        assertFalse(a.effects); assertEquals(0, a.ends.get()); assertNull(a.endFailure)
        assertFalse(a.raw.inTransaction()); assertEquals(a.before, image(a.raw))
    }
    fun requireRolledBack() {
        val a = requireNotNull(selected)
        assertTrue("Receipt and shadow existed and queue deletion completed before final authority", a.effects)
        assertEquals("Actual outer END returned", 1, a.ends.get()); assertNull(a.endFailure)
        assertTrue("Complete MAIN typed/schema/cookie/rowid image rolled back", a.rollback)
        assertFalse(a.raw.inTransaction()); assertEquals(a.before, image(a.raw))
    }
    fun requireCommitted() {
        val a = requireNotNull(selected)
        assertTrue(a.effects); assertEquals(1, a.ends.get()); assertNull(a.endFailure)
        assertFalse(a.rollback); assertFalse(a.raw.inTransaction())
    }
    private fun scalar(raw: SupportSQLiteDatabase, query: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(query, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun rows(raw: SupportSQLiteDatabase, query: String): Rows = raw.query(query).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList { while (c.moveToNext()) add(columns.indices.map { i ->
            val type = c.getType(i)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Unknown SQLite cell type")
            })
        }) })
    }
    private fun image(raw: SupportSQLiteDatabase): Image {
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = raw.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return Image(raw.version, scalar(raw, "PRAGMA main.schema_version"), schema, tables.associateWith { table ->
            rows(raw, "SELECT rowid AS __ack_authority_rowid,* FROM main.\"${table.replace("\"", "\"\"")}\" ORDER BY rowid")
        })
    }
    private fun deleted(raw: SupportSQLiteDatabase, queue: Long?) {
        val a = selected ?: return
        if (queue != a.queue) return
        check(raw === a.raw && raw.inTransaction() && writer.get() == null && !a.effects)
        check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, a.request)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE entityType='metric' AND entityUuid=? AND revision=1",
            arrayOf(a.metric)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=?", arrayOf(a.queue)) == 0L)
        a.effects = true; writer.set(a)
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var previous: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (previous !== raw) {
                previous = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (sql != DELETE_QUEUE) return statement
                        return object : SupportSQLiteStatement by statement {
                            private var queue: Long? = null
                            override fun clearBindings() { queue = null; statement.clearBindings() }
                            override fun bindLong(index: Int, value: Long) { if (index == 1) queue = value; statement.bindLong(index, value) }
                            override fun execute() { statement.execute(); deleted(raw, queue) }
                            override fun executeInsert(): Long = statement.executeInsert().also { deleted(raw, queue) }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { deleted(raw, queue) }
                        }
                    }
                    override fun endTransaction() {
                        val a = writer.get()
                        if (a == null) { raw.endTransaction(); return }
                        try {
                            raw.endTransaction()
                            if (!raw.inTransaction()) {
                                a.ends.incrementAndGet(); a.rollback = image(raw) == a.before
                            }
                        } catch (failure: Throwable) { a.endFailure = failure; throw failure }
                        finally { if (!raw.inTransaction()) writer.remove() }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    private companion object { const val DELETE_QUEUE = "DELETE FROM sync_outbox WHERE id = ?" }
}
