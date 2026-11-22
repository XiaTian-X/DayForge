package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryNodeSession

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only SQLite source contract. Supplied roots are fixture commitments, not credentials.
 * Explicit transaction tokens model the required owner callback. Actual Gate/Room capability,
 * authenticated persistent roots, business recovery and process death need separate integration. */
@RunWith(AndroidJUnit4::class)
class G0CountBlockNodeSourceTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val unit = DirectoryTestData.unit
    private val sample = directory.build(unit, DirectoryTestData.records(2))

    @Test fun pointReadUsesFixedPrimaryKeyQueryAndTheCompleteOriginalCodec() = runBlocking<Unit> {
        store { sql ->
            val records = DirectoryTestData.records(400)
            val built = directory.build(unit, records)
            sql.install(built.blocks)
            val measured = sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    directory.readPoint(built.root, records[255].key, loader(source))
                }
            }
            DirectoryTestData.assertRecords(listOf(records[255]), measured.value.records)
            assertTrue(measured.stats.queries in 1 until built.blocks.size)
            assertEquals(measured.stats.queries, measured.stats.blobReads)
            assertEquals(measured.value.stats.loadedBytes, measured.stats.loadedBytes)
            assertEquals(measured.stats.queries, sql.queries)
            assertEquals(measured.stats.blobReads, sql.blobReads)
            val plan = sql.raw.query("EXPLAIN QUERY PLAN ${G0CountBlockSql.readNode}", arrayOf<Any?>(262144, built.root.digest)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString(" ").uppercase()
            }
            assertTrue(plan.contains("SEARCH")); assertFalse(plan.contains("SCAN"))
        }
    }

    @Test fun repeatedLoadsAreUncachedAndFreezeBothCursorAndCallerBuffers() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            val original = sample.blocks.getValue(sample.root.digest)
            val measured = sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    val first = requireNotNull(source.load(sample.root.digest, 262144))
                    requireNotNull(sql.lastBlob)[0] = 0
                    val caller = first.toByteArray(); caller[0] = 0
                    assertEquals(original, first)
                    val second = requireNotNull(source.load(sample.root.digest, 262144))
                    assertEquals(first, second)
                    HistoryBlockCodec.decode(sample.root, first.toByteArray())
                    HistoryBlockCodec.decode(sample.root, second.toByteArray())
                }
            }
            assertEquals(2, measured.stats.queries); assertEquals(2, measured.stats.blobReads)
            assertEquals(original.size * 2L, measured.stats.loadedBytes)
        }
    }

    @Test fun everyNonBlobStorageClassIsRejectedBeforeGetBlobIncludingNulAndMultibyte() = runBlocking<Unit> {
        val values: List<Any?> = listOf(null, "short", "é".repeat(32), "a\u0000" + "b".repeat(32), 7L, 2.5)
        for (value in values) store(nullable = true) { sql ->
            sql.put(sample.root.digest, value)
            rejected(Code.MALFORMED) {
                sql.transaction { boundary -> G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    source.load(sample.root.digest, 4)
                } }
            }
            assertEquals(1, sql.queries); assertEquals(0, sql.blobReads)
        }
    }

    @Test fun blobAndCumulativeByteLimitsApplyInSqlBeforeMaterialization() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            val size = sample.blocks.getValue(sample.root.digest).size
            rejected(Code.BUDGET) {
                sql.transaction { boundary -> G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    source.load(sample.root.digest, size - 1)
                } }
            }
            assertEquals(1, sql.queries); assertEquals(0, sql.blobReads)
            sql.resetCounts()
            rejected(Code.BUDGET) {
                sql.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(sql.db, boundary,
                        G0CountBlockNodeSource.Budget(maxLoadedBytes = size * 2L - 1L)) { source ->
                        assertNotNull(source.load(sample.root.digest, size))
                        source.load(sample.root.digest, size)
                    }
                }
            }
            assertEquals(2, sql.queries); assertEquals(1, sql.blobReads)
        }
    }

    @Test fun queryLimitsAndMalformedDigestsCannotTriggerUnboundedOrExtraReads() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            rejected(Code.BUDGET) {
                sql.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(sql.db, boundary,
                        G0CountBlockNodeSource.Budget(maxQueries = 1)) { source ->
                        source.load(sample.root.digest, 262144)
                        source.load(sample.root.digest, 262144)
                    }
                }
            }
            assertEquals(1, sql.queries); assertEquals(1, sql.blobReads)
            for (limit in listOf(0, -1)) {
                sql.resetCounts()
                rejected(Code.BUDGET) { sql.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(sql.db, boundary) { it.load(sample.root.digest, limit) }
                } }
                assertEquals(0, sql.queries)
            }
            for (digest in listOf("", "f".repeat(63), "F".repeat(64), "g".repeat(64))) {
                sql.resetCounts()
                rejected(Code.MALFORMED) { sql.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(sql.db, boundary) { it.load(digest, 262144) }
                } }
                assertEquals(0, sql.queries)
            }
            rejected(Code.BUDGET) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary,
                    G0CountBlockNodeSource.Budget(maxQueries = 4097)) { error("must not enter") }
            } }
            rejected(Code.BUDGET) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary,
                    G0CountBlockNodeSource.Budget(maxLoadedBytes = 32L * 1024 * 1024 + 1)) { error("must not enter") }
            } }
        }
    }

    @Test fun missingRowIsNullOnlyAtByteBoundaryAndOriginalDirectoryRejectsIt() = runBlocking<Unit> {
        store { sql ->
            val absent = sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { it.load(sample.root.digest, 262144) }
            }
            assertNull(absent.value); assertEquals(1, absent.stats.queries); assertEquals(0, absent.stats.blobReads)
            rejected(Code.MALFORMED) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    directory.readPoint(sample.root, DirectoryTestData.key(0), loader(source))
                }
            } }
        }
    }

    @Test fun changedBytesAndMismatchedCompleteRefNeverProduceVerifiedResults() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            val changed = sample.blocks.getValue(sample.root.digest).toByteArray()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            sql.raw.execSQL("UPDATE ${G0CountBlockSql.nodes} SET body=? WHERE digest=?", arrayOf(changed, sample.root.digest))
            rejected(Code.HASH_MISMATCH) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    directory.readPoint(sample.root, DirectoryTestData.key(0), loader(source))
                }
            } }
            sql.raw.execSQL("UPDATE ${G0CountBlockSql.nodes} SET body=? WHERE digest=?",
                arrayOf(sample.blocks.getValue(sample.root.digest).toByteArray(), sample.root.digest))
            rejected { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    val raw = requireNotNull(source.load(sample.root.digest, 262144)).toByteArray()
                    HistoryBlockCodec.decode(sample.root.copy(count = sample.root.count + 1), raw)
                }
            } }
        }
    }

    @Test fun successfulOrFailedScopeCannotLeakAUsableSourceAndSwallowingDoesNotHealFailure() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            lateinit var escaped: G0CountBlockNodeSource.Source
            sql.transaction { boundary -> G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                escaped = source; source.load(sample.root.digest, 262144)
            } }
            rejected(Code.CONTEXT) { escaped.load(sample.root.digest, 262144) }
            val first = AtomicReference<Throwable?>()
            val outer = failure { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    escaped = source
                    try { source.load(sample.root.digest, 0) } catch (caught: Throwable) { first.set(caught) }
                    assertSame(first.get(), failure { source.load(sample.root.digest, 262144) })
                }
            } }
            assertSame(first.get(), outer)
            assertSame(outer, failure { escaped.load(sample.root.digest, 262144) })
        }
    }

    @Test fun closedAbsentAndReplacedTransactionsCannotReuseTheCapturedOwner() = runBlocking<Unit> {
        store { sql ->
            // Only tests the additional native-transaction check; this empty callback is not a Gate permit.
            rejected(Code.CONTEXT) { G0CountBlockNodeSource.withSource(sql.db, {}) { error("not entered") } }
            val replaced = failure { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    sql.replaceTransactionForRejection()
                    source.load(sample.root.digest, 262144)
                }
            } }
            assertTrue(replaced is IllegalStateException)
            assertEquals("SOURCE_TEST_TRANSACTION_REPLACED", replaced.message)
            assertEquals(0, sql.queries)
        }
        val sql = NativeStore()
        try {
            sql.close()
            rejected(Code.CONTEXT) { G0CountBlockNodeSource.withSource(sql.db, {}) { error("closed") } }
        } finally { sql.close() }
    }

    @Test fun otherThreadsAndReentrantPollingCannotBorrowASource() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            rejected(Code.CONTEXT) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                    val foreign = AtomicReference<Throwable?>()
                    val thread = Thread { try { source.load(sample.root.digest, 262144) }
                        catch (caught: Throwable) { foreign.set(caught) } }
                    thread.start(); thread.join(2000)
                    assertFalse(thread.isAlive); assertTrue(foreign.get() is Rejected)
                }
            } }
            assertEquals(0, sql.queries)
            lateinit var borrowed: G0CountBlockNodeSource.Source
            var reenter = false
            rejected(Code.CONTEXT) { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary, checkCancelled = {
                    if (reenter) { reenter = false; borrowed.load(sample.root.digest, 262144) }
                }) { source ->
                    borrowed = source; reenter = true; source.load(sample.root.digest, 262144)
                }
            } }
            assertEquals(0, sql.queries)
        }
    }

    @Test fun cancellationPropagatesBeforeMaterializationAndAfterAnIntermediateLoad() = runBlocking<Unit> {
        store { sql ->
            val built = directory.build(unit, DirectoryTestData.records(400))
            sql.install(built.blocks)
            var polls = 0
            val early = CancellationException("source pre-blob cancellation")
            assertSame(early, failure { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary, checkCancelled = {
                    polls++; if (polls == 3) throw early
                }) { it.load(built.root.digest, 262144) }
            } })
            assertEquals(1, sql.queries); assertEquals(0, sql.blobReads)
            sql.resetCounts()
            val late = CancellationException("source after first blob")
            assertSame(late, failure { sql.transaction { boundary ->
                G0CountBlockNodeSource.withSource(sql.db, boundary, checkCancelled = {
                    if (sql.blobReads == 1) throw late
                }) { source -> directory.readRange(built.root, null, null, loader(source)) }
            } })
            assertEquals(1, sql.queries); assertEquals(1, sql.blobReads)
        }
    }

    @Test fun fileReopenRequiresANewSourceAndStillUsesTheSuppliedRootPathOnly() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val file = File(app.cacheDir, "g0-block-source-${UUID.randomUUID()}.db")
        val built = directory.build(unit, DirectoryTestData.records(400))
        lateinit var old: G0CountBlockNodeSource.Source
        try {
            val first = NativeStore(file = file)
            try {
                first.install(built.blocks)
                first.transaction { boundary -> G0CountBlockNodeSource.withSource(first.db, boundary) { source ->
                    old = source
                    directory.readPoint(built.root, DirectoryTestData.key(17), loader(source))
                } }
            } finally { first.close() }
            val second = NativeStore(file = file)
            try {
                val result = second.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(second.db, boundary) { source ->
                        directory.readPoint(built.root, DirectoryTestData.key(255), loader(source))
                    }
                }
                DirectoryTestData.assertRecords(listOf(DirectoryTestData.records(400)[255]), result.value.records)
                assertTrue(result.stats.queries in 1 until built.blocks.size)
                rejected(Code.CONTEXT) { old.load(built.root.digest, 262144) }
            } finally { second.close() }
        } finally {
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
        }
    }

    @Test fun onlyOperationSessionCanReuseDecodedNodesAndFreshOperationsReadAgain() = runBlocking<Unit> {
        store { sql ->
            sql.install(sample.blocks)
            val identity = Any()
            repeat(2) { operation ->
                val result = sql.transaction { boundary ->
                    G0CountBlockNodeSource.withSource(sql.db, boundary) { source ->
                        HistoryNodeSession.use(identity, sample.root, source) { session ->
                            val tree = session.tree(identity, sample.root)
                            repeat(2) {
                                val point = directory.readPoint(sample.root, DirectoryTestData.key(0), tree)
                                DirectoryTestData.assertRecords(listOf(DirectoryTestData.records(2)[0]), point.records)
                            }
                            assertEquals(1, session.stats().decodedNodes)
                            assertEquals(1, session.stats().hits)
                        }
                    }
                }
                assertEquals(1, result.stats.queries); assertEquals(1, result.stats.blobReads)
                assertEquals(operation + 1, sql.queries)
            }
        }
    }

    private fun loader(source: G0CountBlockNodeSource.Source) =
        AuthenticatedHistoryDirectory.Loader { digest, bytes -> source.load(digest, bytes)?.toByteArray() }

    private suspend fun rejected(code: Code? = null, block: suspend () -> Unit) {
        val caught = failure(block)
        assertTrue("expected Rejected, got $caught", caught is Rejected)
        if (code != null) assertEquals(code, (caught as Rejected).code)
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (caught: Throwable) { return caught }
        throw AssertionError("expected failure")
    }
    private suspend fun store(nullable: Boolean = false, block: suspend (NativeStore) -> Unit) {
        val sql = NativeStore(nullable = nullable)
        try { block(sql) } finally { sql.close() }
    }

    private class NativeStore(nullable: Boolean = false, file: File? = null) : AutoCloseable {
        private val app = ApplicationProvider.getApplicationContext<Context>()
        private val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name(file?.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Nullable body exists only to prove the loader rejects this malformed storage.
                        db.execSQL(if (nullable) "CREATE TABLE ${G0CountBlockSql.nodes} (digest TEXT PRIMARY KEY NOT NULL,body BLOB)"
                            else G0CountBlockSql.createNodes)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("no fixture migration")
                    }
                }).build())
        val raw = helper.writableDatabase
        var queries = 0
            private set
        var blobReads = 0
            private set
        var lastBlob: ByteArray? = null
            private set
        private var transactionToken: Any? = null
        val db: SupportSQLiteDatabase = object : SupportSQLiteDatabase by raw {
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                assertEquals(G0CountBlockSql.readNode, query)
                queries++
                return object : CursorWrapper(raw.query(query, bindArgs)) {
                    override fun getBlob(columnIndex: Int): ByteArray {
                        assertEquals(2, columnIndex); blobReads++
                        return super.getBlob(columnIndex).also { lastBlob = it }
                    }
                }
            }
        }
        fun resetCounts() { queries = 0; blobReads = 0; lastBlob = null }
        fun put(digest: String, body: Any?) = raw.execSQL(G0CountBlockSql.insertNode, arrayOf(digest, body))
        fun install(blocks: Map<String, Bytes>) { blocks.forEach { (digest, bytes) -> put(digest, bytes.toByteArray()) } }
        suspend fun <T> transaction(block: suspend (() -> Unit) -> T): T {
            check(transactionToken == null)
            raw.beginTransaction()
            val captured = Any(); transactionToken = captured
            val thread = Thread.currentThread()
            val boundary = {
                check(transactionToken === captured && Thread.currentThread() === thread) { "SOURCE_TEST_TRANSACTION_REPLACED" }
            }
            return try { block(boundary) }
            finally { transactionToken = null; if (raw.isOpen && raw.inTransaction()) raw.endTransaction() }
        }
        fun replaceTransactionForRejection() {
            check(transactionToken != null); raw.endTransaction(); raw.beginTransaction(); transactionToken = Any()
        }
        override fun close() { transactionToken = null; helper.close() }
    }
}
