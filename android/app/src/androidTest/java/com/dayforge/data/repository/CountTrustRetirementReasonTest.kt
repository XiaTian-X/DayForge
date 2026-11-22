package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary physical tests; no owner reset, provider replacement or automatic reopen. */
@RunWith(AndroidJUnit4::class)
class CountTrustRetirementReasonTest : CountNodeGcFixture() {
    @Test fun recoveryRetirementIsPermanentAndCloseAddsRatherThanReplacesItsReason() = runBlocking<Unit> {
        ready(); val retained = owner; val epoch = retained.generation
        val earlier = retained.retirementReasons
        assertTrue(earlier.isEmpty())
        CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(access())
        assertTrue(retained.isRetired); assertEquals(epoch, retained.generation)
        assertEquals(setOf(CountTrustRetirementReason.RECOVERY_PREPARATION), retained.retirementReasons)
        assertTrue(earlier.isEmpty())
        // Soft retirement preserves both ordinary DAO access and synchronous maintenance.
        // A Room suspend DAO may use a child Job, which must not inherit maintenance authority.
        val images = nativeAssertions(); val beforeReads = images.capture()
        db.withTransaction {
            assertEquals(habit.uuid, requireNotNull(db.habitDao().getHabitById(habit.id)).uuid)
            retained.maintenance(db) {
                sql.query("SELECT uuid FROM main.habits WHERE id=?", arrayOf(habit.id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(android.database.Cursor.FIELD_TYPE_STRING, it.getType(0))
                    assertEquals(habit.uuid, it.getString(0)); assertFalse(it.moveToNext())
                }
            }
        }
        images.assertUnchanged(beforeReads)
        assertTrue(retained.isRetired); assertEquals(epoch, retained.generation)
        assertEquals(setOf(CountTrustRetirementReason.RECOVERY_PREPARATION), retained.retirementReasons)
        db.close()
        assertTrue(retained.isRetired); assertTrue(retained.generation > epoch)
        assertTrue(retained.retirementReasons.containsAll(setOf(CountTrustRetirementReason.RECOVERY_PREPARATION,
            CountTrustRetirementReason.CONNECTION_CLOSED)))
    }
    @Test fun actualUnknownEndRemainsRecordedAfterCloseAndFreshOwnerDoesNotReviveIt() = runBlocking<Unit> {
        ready(); orphan(710); val gc = collector(); untilSweep(gc)
        val old = owner; spy.endFault = CountNodeGcNativeSpy.EndFault.COMMITTED_THEN_THROW
        val result = nextSlice(gc)
        assertEquals(CountTrustNodeCollector.Status.FAILED, result.status); assertTrue(spy.faultTouched)
        assertEquals(0, result.deletedNodes); assertTrue(old.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in old.retirementReasons)
        storage.reopen()
        assertTrue(old.retirementReasons.containsAll(setOf(CountTrustRetirementReason.UNKNOWN_END,
            CountTrustRetirementReason.CONNECTION_CLOSED)))
        nativeAssertions().capture()
        assertNotSame(old, owner); assertFalse(owner.isRetired); assertTrue(old.isRetired)
        assertEquals("NO_OPEN_WORK", gc.collectSlice().reason)
    }
    @Test fun unownedSchemaRetirementCannotBeOverwrittenByLaterRecoveryOrClose() = runBlocking<Unit> {
        ready(); val old = owner
        sql.execSQL("CREATE TABLE retirement_reason_extra(value TEXT)")
        assertTrue(old.isRetired)
        assertTrue(CountTrustRetirementReason.UNOWNED_SCHEMA_CHANGE in old.retirementReasons)
        db.withTransaction { old.maintenance(db) { old.retireFastPathForRecovery() } }
        val reasons = old.retirementReasons
        db.close()
        assertTrue(old.retirementReasons.containsAll(reasons))
        assertTrue(CountTrustRetirementReason.CONNECTION_CLOSED in old.retirementReasons)
    }
}
