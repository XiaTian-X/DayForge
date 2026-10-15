package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.NextRequestOriginEntity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite rows and query counts. These scoped hashes are not permissions or business proofs. */
@RunWith(AndroidJUnit4::class)
class NextEvidenceReadSessionTest : NextCoreRequestFixture() {
    private val layouts = AtomicInteger()
    private val originReads = AtomicInteger()
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (sqlQuery.startsWith("PRAGMA table_info(next_request_origins)", ignoreCase = true))
                    layouts.incrementAndGet()
                if (sqlQuery.startsWith("SELECT", ignoreCase = true) &&
                    sqlQuery.contains("FROM next_request_origins", ignoreCase = true)) originReads.incrementAndGet()
            }
        }
    }

    private fun rawOrigin(number: Int, kind: String = NEXT_OPERATION) = NextRequestOriginEntity(
        kind, id(number), number.toLong(), 5, id(1), id(2), id(3), "a".repeat(64), "{\"fixture\":$number}")

    private suspend fun insert(row: NextRequestOriginEntity) = db.withTransaction {
        db.nextRequestDao().insertOrigin(row)
    }

    private suspend fun hash(request: String, kind: String = NEXT_OPERATION): String? =
        NextRequestSql.rowHash(db, "next_request_origins",
            "kind=? AND requestId=?", arrayOf(kind, request))

    @Test fun repeatedAuditedRowsAndNestedReadsReuseOneLayoutButOutsideReadsRemainFresh() = runBlocking {
        val queued = observation() // Actual business + origin/outbox transaction, not a mocked digest.
        val origin = originalIntent(queued)
        val second = rawOrigin(600)
        insert(second)
        db.withTransaction {
            val expected = requireNotNull(hash(origin.requestId))
            originReads.set(0); layouts.set(0)
            NextEvidenceReadSession.read(db) {
                assertEquals(expected, hash(origin.requestId))
                val once = originReads.get()
                assertTrue("First access must audit real rows", once > 0)
                repeat(3) { assertEquals(expected, hash(origin.requestId)) }
                NextEvidenceReadSession.read(db) { assertEquals(expected, hash(origin.requestId)) }
                assertEquals("Same read stage must not requery an audited row", once, originReads.get())
                assertNotNull(hash(second.requestId))
                assertEquals("One immutable table layout per read stage", 1, layouts.get())
                val sql = db.openHelper.writableDatabase
                repeat(2) {
                    val beforeRaw = originReads.get()
                    val beforeRawLayout = layouts.get()
                    assertEquals(expected, NextRequestSql.rowHash(sql, "next_request_origins",
                        "kind=? AND requestId=?", arrayOf(origin.kind, origin.requestId)))
                    assertTrue("Raw SQL must fully audit even inside an owning database session",
                        originReads.get() > beforeRaw)
                    assertTrue("Raw SQL must not borrow the session's table layout",
                        layouts.get() > beforeRawLayout)
                }
                val afterRaw = originReads.get()
                assertEquals(expected, hash(origin.requestId))
                assertEquals("Raw audits do not replace the explicit owner's cache", afterRaw, originReads.get())
            }
            val afterScope = originReads.get()
            assertEquals(expected, hash(origin.requestId))
            assertTrue("No scope means the original full read path", originReads.get() > afterScope)
            val firstOutside = originReads.get()
            assertEquals(expected, hash(origin.requestId))
            assertTrue(originReads.get() > firstOutside)
        }
        assertEquals(queued, db.syncOutboxDao().getById(queued.id))
        assertEquals(origin, originalIntent(queued))
    }

    @Test fun missingAndPresentRowsAreReauditedAfterEveryWriteBoundary() = runBlocking {
        val row = rawOrigin(601)
        db.withTransaction {
            NextEvidenceReadSession.read(db) {
                assertNull(hash(row.requestId))
                val first = originReads.get()
                assertNull(hash(row.requestId))
                assertEquals(first, originReads.get())
            }
            db.nextRequestDao().insertOrigin(row)
            val initial = NextEvidenceReadSession.read(db) { requireNotNull(hash(row.requestId)) }
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                arrayOf("{\"fixture\":\"edited\"}", row.kind, row.requestId))
            val edited = NextEvidenceReadSession.read(db) { requireNotNull(hash(row.requestId)) }
            assertNotEquals(initial, edited)
            db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol=? WHERE kind=? AND requestId=?",
                arrayOf("not-an-integer", row.kind, row.requestId))
            val error = rejected { NextEvidenceReadSession.read(db) { hash(row.requestId) } }
            assertTrue(error is NextRequestException)
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (error as NextRequestException).reason)
        }
    }

    @Test fun queryNamespacePredicateAndArgumentTypesAreNotConflated() = runBlocking {
        val first = rawOrigin(602)
        val otherKind = rawOrigin(602, NEXT_TIMER).copy(intentJson = "{\"fixture\":\"timer\"}")
        insert(first); insert(otherKind)
        db.withTransaction {
            val operation = requireNotNull(hash(first.requestId))
            val timer = requireNotNull(hash(first.requestId, NEXT_TIMER))
            assertNotEquals(operation, timer)
            NextEvidenceReadSession.read(db) {
                assertEquals(operation, hash(first.requestId))
                assertEquals(timer, hash(first.requestId, NEXT_TIMER))
                val predicate = "kind=? AND queueId=?"
                assertEquals(operation, NextRequestSql.rowHash(db, "next_request_origins", predicate,
                    arrayOf(NEXT_OPERATION, first.queueId)))
                val beforeText = originReads.get()
                assertEquals(operation, NextRequestSql.rowHash(db, "next_request_origins", predicate,
                    arrayOf(NEXT_OPERATION, first.queueId.toString())))
                assertTrue("TEXT and INTEGER bindings need separate cache keys", originReads.get() > beforeText)
                assertNull(NextRequestSql.rowHash(db, "next_request_origins", "kind=? AND queueId>?",
                    arrayOf(NEXT_OPERATION, first.queueId)))
                assertEquals(operation, NextRequestSql.rowHash(db, "next_request_origins", "kind=? AND queueId>=?",
                    arrayOf(NEXT_OPERATION, first.queueId)))
                assertNull(NextRequestSql.rowHash(db, "next_acceptances", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, first.requestId)))
            }
        }
    }

    @Test fun stageDmlAndMainOrTempDdlFailClosedAndRollBackWithTheirTransaction() = runBlocking {
        val row = rawOrigin(603); insert(row)
        val baseline = db.withTransaction { hash(row.requestId) }
        val changes: List<suspend () -> Unit> = listOf(
            { db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                arrayOf("{\"changed\":true}", row.kind, row.requestId)) },
            { db.openHelper.writableDatabase.execSQL("CREATE TABLE evidence_stage_main(value TEXT)") },
            { db.openHelper.writableDatabase.execSQL("CREATE TEMP TABLE evidence_stage_temp(value TEXT)") }
        )
        for (change in changes) {
            var changed = false
            val error = rejected { db.withTransaction {
                NextEvidenceReadSession.read(db) {
                    assertEquals(baseline, hash(row.requestId))
                    change()
                    changed = true
                    // Returning through the session boundary must reject even if no later read occurs.
                }
            } }
            assertTrue("The actual DML/DDL must execute before the exit guard rejects it", changed)
            assertEquals("EVIDENCE_READ_SESSION_WROTE_DATA", error.message)
            db.withTransaction {
                assertEquals(baseline, NextEvidenceReadSession.read(db) { hash(row.requestId) })
                val sql = db.openHelper.writableDatabase
                sql.query("SELECT name FROM sqlite_master WHERE name='evidence_stage_main'").use { assertFalse(it.moveToFirst()) }
                sql.query("SELECT name FROM sqlite_temp_master WHERE name='evidence_stage_temp'").use { assertFalse(it.moveToFirst()) }
            }
        }
        assertEquals(row, db.nextRequestDao().origin(row.kind, row.requestId))
    }

    @Test fun transactionChildJobAndDifferentDatabaseInstancesCannotBorrowTheSession() = runBlocking {
        assertEquals("EVIDENCE_READ_TRANSACTION_REQUIRED", rejected {
            NextEvidenceReadSession.read(db) { fail("A real transaction is required") }
        }.message)
        val row = rawOrigin(604); insert(row)
        val name = "evidence-foreign-${UUID.randomUUID()}"
        val other = Room.databaseBuilder(app, HabitDatabase::class.java, name).build()
        val samePath = Room.databaseBuilder(app, HabitDatabase::class.java,
            requireNotNull(db.openHelper.databaseName)).build()
        try {
            val foreignSql = withContext(Dispatchers.IO) { other.openHelper.writableDatabase }
            val samePathSql = withContext(Dispatchers.IO) { samePath.openHelper.writableDatabase }
            assertNotSame(db, samePath)
            assertEquals(db.openHelper.writableDatabase.path, samePathSql.path)
            db.withTransaction {
                NextEvidenceReadSession.read(db) {
                    assertNotNull(hash(row.requestId))
                    coroutineScope {
                        assertEquals("EVIDENCE_READ_SESSION_EXPIRED", async { rejected { hash(row.requestId) } }.await().message)
                        assertEquals("EVIDENCE_READ_SESSION_EXPIRED", async {
                            rejected { NextEvidenceReadSession.read(db) { hash(row.requestId) } }
                        }.await().message)
                    }
                    assertEquals("EVIDENCE_READ_SESSION_EXPIRED", rejected {
                        NextRequestSql.rowHash(other, "next_request_origins",
                            "kind=? AND requestId=?", arrayOf(row.kind, row.requestId))
                    }.message)
                    assertEquals("EVIDENCE_READ_SESSION_EXPIRED", rejected {
                        NextRequestSql.rowHash(samePath, "next_request_origins",
                            "kind=? AND requestId=?", arrayOf(row.kind, row.requestId))
                    }.message)
                    // Raw SQL retains its old full-audit semantics, never a cached current-DB proof.
                    assertNull(NextRequestSql.rowHash(foreignSql, "next_request_origins",
                        "kind=? AND requestId=?", arrayOf(row.kind, row.requestId)))
                    rejected { NextEvidenceReadSession.read(other) { fail("Another database cannot borrow") } }
                    rejected { NextEvidenceReadSession.read(samePath) { fail("The same file is not the same owner") } }
                    assertNotNull(hash(row.requestId))
                }
            }
        } finally {
            samePath.close() // Do not delete the shared path owned by the primary fixture.
            other.close()
            assertTrue(app.deleteDatabase(name) || !app.getDatabasePath(name).exists())
        }
    }

    @Test fun cancelledStageIsDiscardedAndTheNextTransactionReaudits() = runBlocking {
        val row = rawOrigin(605); insert(row)
        val entered = CompletableDeferred<Unit>()
        val reader = launch {
            db.withTransaction {
                NextEvidenceReadSession.read(db) {
                    assertNotNull(hash(row.requestId))
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        withTimeout(5_000) { entered.await() }
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
        originReads.set(0)
        withTimeout(5_000) { db.withTransaction {
            NextEvidenceReadSession.read(db) { assertNotNull(hash(row.requestId)) }
        } }
        assertTrue("Cancellation cannot retain a cached proof for the next stage", originReads.get() > 0)
        assertEquals(row, db.nextRequestDao().origin(row.kind, row.requestId))
    }

    @Test fun finiteKeyBudgetFallsBackWithoutTreatingUnreadRowsAsProvenMissing() = runBlocking {
        // A small boundary fixture, not a capacity/time threshold: no business commands or long histories.
        val ids = (1000..1256).map(::id)
        val last = rawOrigin(1256); insert(last)
        db.withTransaction {
            val expected = requireNotNull(hash(last.requestId))
            NextEvidenceReadSession.read(db) {
                for (request in ids) assertEquals(if (request == last.requestId) expected else null, hash(request))
                val firstPass = originReads.get()
                for (request in ids) assertEquals(if (request == last.requestId) expected else null, hash(request))
                assertTrue("257 distinct queries cannot all remain cached in a 256-key session", originReads.get() > firstPass)
            }
            NextEvidenceReadSession.read(db) {
                // Query-key storage counts UTF-16 bytes; an oversized key must use the ordinary path.
                val oversized = "x".repeat(1_048_577)
                assertNull(hash(oversized))
                val firstRead = originReads.get()
                assertNull(hash(oversized))
                assertTrue("The byte-budget fallback must not retain an oversized absence key", originReads.get() > firstRead)
                assertEquals(expected, hash(last.requestId))
                val smallRead = originReads.get()
                assertEquals(expected, hash(last.requestId))
                assertEquals("Oversized fallback must not poison a later small proof", smallRead, originReads.get())
            }
        }
    }
}
