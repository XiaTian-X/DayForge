package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary controlled query-observer regressions, not native injection below its gate.
 * The denied observer cancels only its bound public operation so that no real user COUNT
 * can obscure the complete zero-write assertion. Room dispatches callbacks in a coroutine;
 * the exact guard exception is caught here, never treated as if SQLite propagated it.
 */
@RunWith(AndroidJUnit4::class)
class CountAddObserverReadBoundaryTest : CountTrustFormalProfileFixture() {
    private val spy = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = spy
    private val targetTransaction = AtomicLong(0L)
    private enum class Point { FIRST_READ_BOUNDARY, BEFORE_ACTUAL_END }
    private val armed = AtomicBoolean()
    private val attempted = AtomicInteger()
    private val executed = AtomicBoolean()
    private val inActualTransaction = AtomicBoolean()
    private val successfulBeforeAttempt = AtomicBoolean()
    private val transactionThread = AtomicReference<Thread?>()
    private val denied = AtomicReference<IllegalStateException?>()
    @Volatile private var point = Point.FIRST_READ_BOUNDARY
    @Volatile private var target = 0L
    @Volatile private var operation: Job? = null

    override fun countAddObservedQuery(query: String) {
        if (!armed.get()) return
        val nativeTransaction = spy.currentCountAddTransactionId() ?: return
        val exact = query.trim().uppercase()
        if (exact == "SELECT TOTAL_CHANGES()") {
            // Bind the real A worker before any injected work. Room invalidation callbacks
            // on another transaction thread must not consume the intended END window.
            transactionThread.compareAndSet(null, Thread.currentThread())
        }
        if (transactionThread.get() !== Thread.currentThread()) return
        if (exact == "TRANSACTION SUCCESSFUL") successfulBeforeAttempt.set(true)
        val atPoint = when (point) {
            Point.FIRST_READ_BOUNDARY -> exact == "SELECT TOTAL_CHANGES()"
            Point.BEFORE_ACTUAL_END -> exact == "END TRANSACTION" && successfulBeforeAttempt.get()
        }
        if (!atPoint || !armed.compareAndSet(true, false)) return
        targetTransaction.set(nativeTransaction)
        attempted.incrementAndGet()
        inActualTransaction.set(db.inTransaction())
        try {
            db.openHelper.writableDatabase.compileStatement("UPDATE habits SET name=? WHERE id=?").use {
                it.bindString(1, "COUNT A observer boundary probe"); it.bindLong(2, target)
                check(it.executeUpdateDelete() == 1) { "COUNT_ADD_OBSERVER_UPDATE_NOT_REACHED" }
            }
            // Retain the unsafe path: an old gate that really permits the DML is NOT
            // cancelled. Its later stale-ticket refusal must expose any durable A drift.
            executed.set(true)
        } catch (error: IllegalStateException) {
            if (error.message != "COUNT_TRUST_OBSERVER_WRITE_DENIED") throw error
            denied.set(error)
            requireNotNull(operation).cancel(CancellationException("count-add-observer-guard-confirmed"))
        }
    }

    @Test fun initialReadBoundaryObserverCannotPersistAWriteFromARejectedPublicCount() = runBlocking<Unit> {
        verifyBoundary(Point.FIRST_READ_BOUNDARY)
    }

    @Test fun successfulAEndObserverCannotPersistAWriteBeforeNativeCommit() = runBlocking<Unit> {
        verifyBoundary(Point.BEFORE_ACTUAL_END)
    }

    private suspend fun verifyBoundary(selected: Point) {
        val habit = openCountAddPlanInputs().first()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = images.capture()
        target = habit.id; point = selected
        val observation = spy.startCountAddTransactionObservation()
        var saved: Long? = null
        val failure = supervisorScope {
            val pending = async(Dispatchers.IO + observation, start = CoroutineStart.LAZY) {
                creatingHabits().logCompletion(app, habit.id, authority = authority)
            }
            operation = pending
            armed.set(true)
            pending.start()
            try {
                saved = pending.await()
                null
            } catch (error: Exception) {
                error
            } finally {
                armed.set(false)
                // await above has observed the public operation's actual coroutine terminal;
                // no delayed cleanup or test-side write can race the native image below.
                operation = null
            }
        }
        val transactions = spy.finishCountAddTransactionObservation()
        val targetId = requireNotNull(targetTransaction.get().takeIf { it > 0L }) { "COUNT_ADD_TEST_TARGET_TRANSACTION_NOT_OBSERVED" }
        val targetFrame = transactions.single { it.id == targetId }
        val after = images.capture()
        val changedTables = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
        android.util.Log.i("CountAddABoundary", buildJsonObject {
            put("scenario", selected.name)
            put("attempts", attempted.get()); put("actual_dml_completed", executed.get())
            put("in_actual_transaction", inActualTransaction.get())
            put("successful_before_attempt", successfulBeforeAttempt.get())
            put("precise_observer_denial", denied.get()?.message == "COUNT_TRUST_OBSERVER_WRITE_DENIED")
            put("public_returned_saved_id", saved != null)
            put("target_native_end_observed", targetFrame.knownEnd)
            put("target_explicit_business_writes", targetFrame.explicitBusinessWrites)
            put("public_failed", failure != null)
            put("failure_class", failure?.javaClass?.simpleName.orEmpty())
            put("failure_message", failure?.message.orEmpty().take(120))
            put("changed_main_tables", JsonArray(changedTables.map(::JsonPrimitive)))
            put("performance_measured", false)
        }.toString())
        assertEquals(1, attempted.get())
        assertNotNull(transactionThread.get())
        assertTrue("Fault must run inside the actual A transaction", inActualTransaction.get())
        if (selected == Point.BEFORE_ACTUAL_END) assertTrue(successfulBeforeAttempt.get())
        // ALL main tables, native cell types, control, node pool, roots and sqlite_sequence.
        // On the unsafe source the log first preserves actual DML/refusal/drift evidence.
        assertEquals("A observer changed durable content despite the rejected/cancelled command", before, after)
        assertFalse("Ordinary observer must never reach native business UPDATE", executed.get())
        assertEquals("COUNT_TRUST_OBSERVER_WRITE_DENIED", requireNotNull(denied.get()).message)
        assertTrue("Only the precisely denied observer cancels this bound operation", failure is CancellationException)
        assertEquals("count-add-observer-guard-confirmed", requireNotNull(failure).message)
        assertNull("Cancelled A must not report a saved public result", saved)
        assertTrue("The targeted public-operation transaction must actually finish", targetFrame.knownEnd)
        assertEquals(0, targetFrame.explicitBusinessWrites)
        assertEquals(0, targetFrame.completionInserts); assertEquals(0, targetFrame.originInserts)
        assertTrue("No later original writer may follow this cancelled A",
            transactions.all { it.explicitBusinessWrites == 0 && it.knownEnd })
        assertEquals(0, spy.countAddClosedEffectReads())
    }
}
