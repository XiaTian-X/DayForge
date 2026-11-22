package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room lifecycle/END tests. A captured input is not a full business certificate. */
@RunWith(AndroidJUnit4::class)
class G0RoomColdOwnerBoundaryTest : G0HouseholdColdOwnerFixture() {
    private fun external(readOnly: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path, null,
        if (readOnly) SQLiteDatabase.OPEN_READONLY
        else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
    )
    private fun nativeState(): Map<String, List<List<Pair<Int, Any?>>>> = external(true).use { database ->
        listOf("metrics", "sync_control", "room_master_table").associateWith { table ->
            database.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                        val type = cursor.getType(column)
                        type to when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> java.lang.Double.doubleToRawLongBits(cursor.getDouble(column))
                            else -> cursor.getBlob(column).toList()
                        }
                    })
                }
            }
        }
    }
    private fun reopen(
        reference: Reference,
        beforeRoom: () -> Unit = {},
        beforeEnd: () -> Unit = {},
        afterEnd: () -> Unit = {}
    ): G0RoomColdOwnerFactory {
        val owner = G0RoomColdOwnerFactory(reference.main, reference.identity,
            delegateFactory = G0TimingFactory(endFaults),
            onFailure = { domainGate.invalidateConnection() },
            beforeRoomOpen = beforeRoom, beforeActualEnd = beforeEnd, afterActualEnd = afterEnd)
        freshGateForColdOpen(recurringReads = true, coldLifecycle = owner)
        storage.reopen()
        return owner
    }
    private suspend fun capture(reference: Reference, owner: G0RoomColdOwnerFactory) =
        domainGate.ownedColdTransaction(db, reference.schema) {
            val input = G0FrozenDomainInputs.capture(db, domainGate, reference.schema)
            owner.validated(input, input)
            input
        }
    private fun restoreControlRows(reference: Reference) {
        db.close()
        external().use { raw ->
            raw.execSQL("DROP TRIGGER IF EXISTS g0_cold_owner_poison")
            raw.execSQL("DELETE FROM room_master_table")
            reference.identity.forEach { (id, hash) ->
                raw.execSQL("INSERT INTO room_master_table(id,identity_hash) VALUES(?,?)", arrayOf<Any>(id, hash))
            }
            raw.execSQL("DELETE FROM sync_control")
            raw.execSQL("INSERT INTO sync_control(id,suppressOutbox) VALUES(1,0)")
        }
    }

    @Test fun roomAdoptsOneReservationAndOnlyKnownEndCanInstallItsExactSnapshot() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        val worker = Executors.newSingleThreadExecutor()
        var blocked = 0
        fun challengeWriter() {
            assertTrue(worker.submit<Boolean> {
                external().use { outside ->
                    outside.rawQuery("PRAGMA busy_timeout=50", null).use { assertTrue(it.moveToFirst()) }
                    try {
                        outside.execSQL("UPDATE metrics SET name=name")
                        false
                    } catch (failure: SQLiteException) {
                        check(failure.message.orEmpty().contains("locked", true) ||
                            failure.message.orEmpty().contains("busy", true))
                        true
                    }
                }
            }.get(5, TimeUnit.SECONDS))
            blocked++
        }
        try {
            val owner = reopen(reference, beforeRoom = ::challengeWriter)
            val completed = domainGate.ownedColdTransaction(db, reference.schema) {
                challengeWriter()
                val handle = db.openHelper.writableDatabase
                yield()
                assertSame(handle, db.openHelper.writableDatabase)
                assertTrue(handle.inTransaction())
                val input = G0FrozenDomainInputs.capture(db, domainGate, reference.schema)
                owner.validated(input, input)
                input
            }
            assertEquals(2, blocked)
            assertEquals(1, owner.callbacks); assertEquals(1, owner.adoptedBegins)
            assertEquals(1, owner.actualEnds); assertTrue(owner.hasEnded)
            assertTrue(owner.reservationRequestedNs > 0 &&
                owner.reservationStartedNs >= owner.reservationRequestedNs &&
                owner.endedNs > owner.reservationStartedNs)
            assertFalse(owner.knownRollback)
            assertEquals(G0RoomColdOwnerFactory.Phase.KNOWN_END, owner.phase)
            completed.end.requireCandidate(completed.value)
            completed.end.requireSnapshot(completed.value)
            assertTrue(runCatching { completed.end.requireCandidate(Any()) }.isFailure)
            val forged = G0RoomColdOwnerFactory.KnownEnd.issue(owner, completed.value, completed.value)
            assertEquals("G0_COLD_OWNER_NO_KNOWN_END",
                runCatching { forged.claimSnapshot(completed.value) }.exceptionOrNull()?.message)
            completed.end.claimSnapshot(completed.value)
            assertEquals("G0_COLD_END_SNAPSHOT_ALREADY_CLAIMED",
                runCatching { completed.end.claimSnapshot(completed.value) }.exceptionOrNull()?.message)
            domainGate.sealCold(completed.end)
            assertEquals(G0RoomColdOwnerFactory.Phase.READY, owner.phase)
            assertTrue(runCatching { completed.end.claimSnapshot(completed.value) }.isFailure)
            domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
                assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
            }
            assertEquals(before, nativeState())
        } finally { worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun roomIdentityAndControlRowsAreRejectedBeforeCallbacksCanRepairThem() = runBlocking<Unit> {
        val reference = coldReference()
        for (kind in listOf("missing_identity", "wrong_identity", "blob_identity", "missing_control", "suppressed_control")) {
            restoreControlRows(reference)
            external().use { raw ->
                when (kind) {
                    "missing_identity" -> raw.execSQL("DELETE FROM room_master_table")
                    "wrong_identity" -> raw.execSQL("UPDATE room_master_table SET identity_hash='wrong'")
                    "blob_identity" -> raw.execSQL("UPDATE room_master_table SET identity_hash=X'010203'")
                    "missing_control" -> raw.execSQL("DELETE FROM sync_control")
                    else -> raw.execSQL("UPDATE sync_control SET suppressOutbox=1")
                }
            }
            val bad = nativeState()
            val owner = reopen(reference)
            val failure = rejected { capture(reference, owner) }
            assertTrue("$kind: $failure", failure.message.orEmpty().startsWith("G0_COLD_OWNER_"))
            assertEquals(0, owner.callbacks)
            assertTrue(owner.hasFailed)
            assertFalse(owner.knownRollback)
            assertEquals(kind, bad, nativeState())
        }
        restoreControlRows(reference)
    }

    @Test fun unexpectedTriggerIsRejectedBeforeMissingControlCanExecuteItsPayload() = runBlocking<Unit> {
        val reference = coldReference()
        db.close()
        external().use {
            it.execSQL("DELETE FROM sync_control")
            it.execSQL("CREATE TRIGGER g0_cold_owner_poison BEFORE INSERT ON sync_control BEGIN DELETE FROM metrics; END")
        }
        val before = nativeState()
        val owner = reopen(reference)
        assertEquals("G0_COLD_OWNER_SCHEMA_REJECTED", rejected { capture(reference, owner) }.message)
        assertEquals(0, owner.callbacks)
        assertEquals(before, nativeState())
        restoreControlRows(reference)
    }

    @Test fun pureBudgetMissRollsBackActualReservationBeforeAnyFreshOwnerCanContinue() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        val owner = reopen(reference)
        val failure = rejected {
            domainGate.ownedColdTransaction(db, reference.schema) {
                G0FrozenDomainInputs.capture(db, domainGate, reference.schema, 1L)
            }
        }
        assertTrue(failure is G0FrozenDomainInputs.BudgetExceeded)
        assertTrue(owner.knownRollback)
        assertTrue(owner.hasEnded && owner.hasFailed)
        assertEquals(1, owner.actualEnds)
        assertEquals(before, nativeState())
        db.close()
        external().use { it.execSQL("UPDATE metrics SET name=name") }
        val next = reopen(reference)
        val completed = capture(reference, next)
        domainGate.sealCold(completed.end)
        assertEquals(G0RoomColdOwnerFactory.Phase.READY, next.phase)
    }

    @Test fun swallowedControlViolationCannotMarkSuccessOrReturnAnotherCandidate() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        var owner = reopen(reference)
        val denied = rejected {
            domainGate.ownedColdTransaction(db, reference.schema) {
                val input = G0FrozenDomainInputs.capture(db, domainGate, reference.schema)
                val handle = db.openHelper.writableDatabase
                assertTrue(runCatching { handle.setTransactionSuccessful() }.isFailure)
                // A sticky failure is not evidence that the native transaction has drained.
                assertTrue(runCatching { handle.close() }.isFailure)
                assertTrue(handle.isOpen)
                owner.validated(input, input)
                input
            }
        }
        assertTrue(denied is G0DomainSqlGate.Denied || denied.message.orEmpty().startsWith("G0_COLD_OWNER_"))
        assertTrue(owner.hasFailed && owner.hasEnded)
        assertEquals(before, nativeState())
        owner = reopen(reference)
        assertEquals("G0_COLD_OWNER_RETURNED_UNVALIDATED_CANDIDATE", rejected {
            domainGate.ownedColdTransaction(db, reference.schema) {
                val input = G0FrozenDomainInputs.capture(db, domainGate, reference.schema)
                owner.validated(Any(), input)
                Any()
            }
        }.message)
        assertTrue(owner.knownRollback)
        assertEquals(before, nativeState())
    }

    @Test fun lateWriteAfterColdScopeAndUnknownActualEndNeverIssueAReadyReceipt() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        var owner = reopen(reference, beforeEnd = {
            assertTrue(runCatching {
                db.openHelper.writableDatabase.execSQL("UPDATE metrics SET name='late'")
            }.isFailure)
        })
        assertTrue(rejected { capture(reference, owner) } is G0DomainSqlGate.Denied)
        assertTrue(owner.hasFailed)
        assertFalse(owner.knownRollback)
        assertEquals(before, nativeState())
        owner = reopen(reference)
        val failure = rejected {
            domainGate.ownedColdTransaction(db, reference.schema) {
                val input = G0FrozenDomainInputs.capture(db, domainGate, reference.schema)
                owner.validated(input, input)
                endFaults.failAfterNextEnd.set(true)
                input
            }
        }
        assertEquals("G0_COMMIT_RESULT_LOST", failure.message)
        assertFalse(owner.knownRollback)
        assertTrue(owner.hasFailed)
        assertEquals(before, nativeState())
    }

    @Test fun foreignGetterAndPrematureCloseCannotBorrowOrDestroyTheActiveOwner() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        for (close in listOf(false, true)) {
            val owner = reopen(reference)
            val failure = rejected {
                domainGate.ownedColdTransaction(db, reference.schema) {
                    val handle = db.openHelper.writableDatabase
                    val observed = AtomicReference<Throwable?>()
                    val thread = Thread {
                        observed.set(runCatching {
                            if (close) db.openHelper.close() else db.openHelper.readableDatabase
                        }.exceptionOrNull())
                    }
                    thread.start(); thread.join(5_000)
                    assertFalse(thread.isAlive)
                    assertNotNull(observed.get())
                    assertTrue("Denied close must not terminate the active engine", handle.isOpen)
                    val value = Any()
                    owner.validated(value)
                    value
                }
            }
            assertNotNull(failure)
            assertTrue(owner.hasEnded && owner.hasFailed)
            assertEquals(before, nativeState())
        }
    }

    @Test fun openingCallbackCannotDeadlockWithARejectedForeignGetterOrClose() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        for (close in listOf(false, true)) {
            val observed = AtomicReference<Throwable?>()
            var callbackReached = false
            val owner = reopen(reference, beforeRoom = {
                callbackReached = true
                val thread = Thread {
                    observed.set(runCatching {
                        if (close) db.openHelper.close() else db.openHelper.readableDatabase
                    }.exceptionOrNull())
                }.apply { isDaemon = true }
                thread.start()
                thread.join(2_000)
                assertFalse("OPENING rejection waited for the callback lifecycle monitor", thread.isAlive)
                assertNotNull(observed.get())
            })
            rejected { capture(reference, owner) }
            assertTrue(callbackReached)
            assertTrue(owner.hasFailed && owner.hasEnded)
            assertEquals(0, owner.adoptedBegins)
            assertEquals(0, owner.callbacks)
            assertEquals(1, owner.actualEnds)
            assertFalse(owner.knownRollback)
            assertEquals(before, nativeState())
            db.close()
            external().use { it.execSQL("UPDATE metrics SET name=name") }
        }
    }

    @Test fun cancellationDrainsActualEndAndCannotPublishOrRetainAColdOwner() = runBlocking<Unit> {
        val reference = coldReference()
        val before = nativeState()
        val owner = reopen(reference)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val task = async {
            domainGate.ownedColdTransaction(db, reference.schema) {
                entered.complete(Unit)
                release.await()
                val value = Any()
                owner.validated(value)
                value
            }
            published = true
        }
        withTimeout(5_000) { entered.await() }
        task.cancelAndJoin()
        assertFalse(published)
        assertTrue(owner.hasEnded && owner.hasFailed)
        assertEquals(1, owner.actualEnds)
        assertEquals(before, nativeState())
        db.close()
        external().use { it.execSQL("UPDATE metrics SET name=name") }
    }
}
