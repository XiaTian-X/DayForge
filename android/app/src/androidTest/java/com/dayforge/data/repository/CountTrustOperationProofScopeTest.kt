package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockDiagnostics
import com.dayforge.data.history.HistoryNodeSession
import com.dayforge.data.local.CountTrustRuntime
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete operation scopes on real controlled Room; diagnostics observe actual codec calls.
 * Pure index fixtures grant no business authority. The last tests use a real qualified kernel.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustOperationProofScopeTest : NextCoreRequestFixture() {
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private val sql get() = db.openHelper.writableDatabase
    private suspend fun <T> owning(block: suspend () -> T): T = db.withTransaction { owner.maintenance(db) { block() } }
    private class DecodeCount : HistoryBlockDiagnostics.Observer {
        var count = 0
        override fun begin(stage: HistoryBlockDiagnostics.Stage): HistoryBlockDiagnostics.Observation {
            if (stage == HistoryBlockDiagnostics.Stage.NODE_DECODE) count++
            return HistoryBlockDiagnostics.Observation { }
        }
    }
    private suspend fun <T> measured(block: suspend (DecodeCount) -> T): T {
        val previous = HistoryBlockDiagnostics.current.get(); val counter = DecodeCount()
        HistoryBlockDiagnostics.current.set(counter)
        try { return block(counter) } finally { HistoryBlockDiagnostics.current.set(previous) }
    }
    private suspend fun qualified() {
        register()
        CountHistoryReader(db, tokens, sessions).read(habit, LocalDate.of(2026, 10, 6))
        db.withTransaction { owner.maintenance(db) {
            assertNotNull(CountTrustSeal.read(sql)); assertFalse(owner.isRetired)
            sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        } }
    }
    private suspend fun contents() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test fun openingFlagsAndNestedProofsReuseActualDecodeButNextOperationStartsFresh() = runBlocking<Unit> { owning {
        val binding = "51".repeat(32)
        val values = (0 until 12).associate { "key:$it" to JsonPrimitive(it) }
        val root = CountTrustIndex.build(sql, binding, values) {}
        val baseline = CountTrustIndex(sql, binding, root) {}
        var baselineCalls = 0
        measured { counter ->
            assertEquals(values.getValue("key:0"), baseline.get("key:0"))
            assertEquals(values.getValue("key:1"), baseline.get("key:1"))
            baseline.withReadScope { values.forEach { (key, value) -> assertEquals(value, baseline.get(key)) } }
            baselineCalls = counter.count
        }
        val candidate = CountTrustIndex(sql, binding, root) {}
        var candidateCalls = 0
        measured { counter -> candidate.withOperationReadScope {
            assertEquals(values.getValue("key:0"), candidate.get("key:0"))
            assertEquals(values.getValue("key:1"), candidate.get("key:1"))
            candidate.withReadScope { values.forEach { (key, value) -> assertEquals(value, candidate.get(key)) } }
            assertNull(candidate.get("absent"))
            candidateCalls = counter.count
        } }
        assertTrue("Same proofs must invoke strictly fewer real decodes", candidateCalls in 1 until baselineCalls)
        measured { counter -> candidate.withOperationReadScope {
            assertEquals(values.getValue("key:0"), candidate.get("key:0"))
            assertTrue("A closed operation cannot retain decoded proof", counter.count > 0)
        } }
    } }

    @Test fun rootChangeDisablesOldOperationTreeAndPreservesImmutableOldRoot() = runBlocking<Unit> { owning {
        val binding = "52".repeat(32)
        val values = (0 until 40).associate { "key:$it" to JsonPrimitive(it) }
        val old = CountTrustIndex.build(sql, binding, values) {}
        val index = CountTrustIndex(sql, binding, old) {}
        index.withOperationReadScope {
            assertEquals(JsonPrimitive(0), index.get("key:0"))
            index.withReadScope {
                index.putAll(mapOf("key:0" to JsonPrimitive(90), "key:1" to null, "key:41" to JsonPrimitive(41)))
                assertNotEquals(old, index.root)
                assertEquals(JsonPrimitive(90), index.get("key:0")); assertNull(index.get("key:1"))
            }
            assertEquals(JsonPrimitive(41), index.get("key:41"))
        }
        val retained = CountTrustIndex(sql, binding, old) {}
        retained.withOperationReadScope { values.forEach { (key, value) -> assertEquals(value, retained.get(key)) } }
        val fresh = CountTrustIndex(sql, binding, index.root) {}
        fresh.withOperationReadScope { assertEquals(JsonPrimitive(90), fresh.get("key:0")); assertNull(fresh.get("key:1")) }
    } }

    @Test fun distinctBudgetRecursiveEntryAndCancellationKeepOriginalBoundaries() = runBlocking<Unit> { owning {
        val binding = "53".repeat(32)
        val values = (0 until 12).associate { "key:$it" to JsonPrimitive(it) }
        val root = CountTrustIndex.build(sql, binding, values) {}
        val index = CountTrustIndex(sql, binding, root) {}
        val defaultIndex = CountTrustIndex(sql, binding, root) {}
        var defaultDecodes = 0
        measured { counter ->
            defaultIndex.withOperationReadScope {
                defaultIndex.withReadScope {
                    repeat(3) { values.forEach { (key, value) -> assertEquals(value, defaultIndex.get(key)) } }
                    assertNull(defaultIndex.get("absent"))
                }
            }
            defaultDecodes = counter.count
        }
        index.withOperationReadScope {
            var restrictedDecodes = 0
            measured { counter ->
                index.withReadScope(HistoryNodeSession.Budget(maxVisits = 1, maxDecodedNodes = 1)) {
                    repeat(3) { values.forEach { (key, value) -> assertEquals(value, index.get(key)) } }
                    assertNull(index.get("absent"))
                }
                restrictedDecodes = counter.count
            }
            // At most the first point can borrow the one-visit restricted tree. Every later
            // point still executes its original proof; accidentally borrowing the outer wide
            // budget would collapse these same 36 point reads into one decoded leaf.
            assertTrue("The explicit small budget must stop reuse and retain original proofs", restrictedDecodes >= values.size * 3)
            assertTrue("Default reuse must be independently observed on the same complete queries", defaultDecodes in 1 until restrictedDecodes)
            index.withReadScope {
                assertTrue(rejected { index.withReadScope { index.get("key:0") } } is IllegalStateException)
                assertEquals(JsonPrimitive(1), index.get("key:1"))
            }
        }
        val failure = try {
            index.withOperationReadScope { assertEquals(JsonPrimitive(0), index.get("key:0")); throw CancellationException("PROOF_SCOPE_CANCELLED") }
        } catch (cancelled: CancellationException) { cancelled }
        assertEquals("PROOF_SCOPE_CANCELLED", failure.message)
        measured { counter -> index.withOperationReadScope {
            assertEquals(JsonPrimitive(0), index.get("key:0")); assertTrue(counter.count > 0)
        } }
    } }

    @Test fun realKernelContextCannotReadAfterActualEndOrInAnotherTransaction() = runBlocking<Unit> {
        qualified(); val before = contents()
        val kernel = CountTrustKernel(db, tokens, sessions)
        val context = sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            kernel.withOpen(access) { opened -> requireNotNull(opened).also {
                assertTrue(it.eligible(habit)); assertEquals(habit, db.habitDao().getHabitById(habit.id))
                assertEquals(JsonPrimitive(true), it.index.get("global_common"))
            } }
        } }
        val outside = rejected { context.index.get("global_common") }
        assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", outside.message)
        db.withTransaction {
            assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", rejected { context.index.get("global_common") }.message)
        }
        class Rollback : RuntimeException()
        var rolledBack: CountTrustKernel.Context? = null
        try { sessions.exclusive { db.withTransaction<Unit> {
            rolledBack = kernel.withOpen(requireNotNull(tokens.localCoreWriteAccess())) { requireNotNull(it) }
            throw Rollback()
        } } } catch (_: Rollback) { }
        db.withTransaction {
            assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", rejected { requireNotNull(rolledBack).index.get("global_common") }.message)
        }
        assertEquals(before, contents())
        sessions.exclusive { db.withTransaction { kernel.withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            assertTrue(requireNotNull(it).eligible(habit))
        } } }
    }

    // This is foreign-thread/connection lifetime evidence, not a same-thread child-Job
    // concurrency claim. The unchanged journal/private-ticket suites cover strict Job ownership.
    @Test fun foreignThreadCannotBorrowActualTransactionAndLegalRoomChildStillReads() = runBlocking<Unit> {
        qualified(); val before = contents()
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { opened ->
                val context = requireNotNull(opened)
                assertEquals(habit, db.habitDao().getHabitById(habit.id))
                val failure = withContext(Dispatchers.Default) { rejected { context.index.get("global_common") } }
                assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", failure.message)
                assertTrue(context.eligible(habit)); assertFalse(owner.isRetired)
            }
        } }
        assertEquals(before, contents())
    }

    @Test fun newOperationDoesNotHideChangedNodeAndRetiredScopeCannotReadCachedProof() = runBlocking<Unit> {
        qualified()
        val stable = contents()
        class Rollback : RuntimeException()
        try { sessions.exclusive { db.withTransaction<Unit> {
            val kernel = CountTrustKernel(db, tokens, sessions); val access = requireNotNull(tokens.localCoreWriteAccess())
            var root: String? = null
            kernel.withOpen(access) { context ->
                root = requireNotNull(context).header.root.digest
                assertTrue(context.eligible(habit))
            }
            owner.maintenance(db) {
                val bytes = sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(requireNotNull(root))).use {
                    assertTrue(it.moveToFirst()); it.getBlob(0)
                }
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
                sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(bytes, requireNotNull(root)))
            }
            assertNotNull(rejected { kernel.withOpen(access) { requireNotNull(it).eligible(habit) } })
            throw Rollback()
        } } } catch (_: Rollback) { }
        assertEquals(stable, contents())
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { context ->
                val known = requireNotNull(context); assertTrue(known.eligible(habit))
                owner.maintenance(db) { owner.retireFastPathForRecovery() }
                assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", rejected { known.eligible(habit) }.message)
            }
        } }
        // Closing a now-unused optional proof scope must not manufacture a new failure for
        // the original legal retirement fallback; only consuming cached proof is rejected.
        assertTrue(owner.isRetired)
    }

    @Test fun reopenedExternalCorruptionCannotBorrowFormerOperationBytesOrProof() = runBlocking<Unit> {
        qualified()
        val old = sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
                requireNotNull(it).also { value -> assertTrue(value.eligible(habit)) }
            }
        } }
        val digest = old.header.root.digest
        val actualPath = requireNotNull(sql.path)
        assertEquals("com.dayforge.testbed", app.packageName)
        assertEquals(app.getDatabasePath("habit_database").canonicalPath, java.io.File(actualPath).canonicalPath)
        db.close()
        SQLiteDatabase.openDatabase(actualPath, null, SQLiteDatabase.OPEN_READWRITE).use { external ->
            val bytes = external.rawQuery("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                assertTrue(it.moveToFirst()); it.getBlob(0)
            }
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            external.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(bytes, digest))
        }
        storage.reopen().openHelper.writableDatabase
        val damaged = contents()
        assertEquals("COUNT_TRUST_READ_TOKEN_EXPIRED", rejected { old.index.get("global_common") }.message)
        sessions.exclusive { db.withTransaction {
            assertNotNull(rejected { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
                requireNotNull(it).eligible(habit)
            } })
        } }
        assertEquals("External corruption must remain rejected without repair", damaged, contents())
    }
}
