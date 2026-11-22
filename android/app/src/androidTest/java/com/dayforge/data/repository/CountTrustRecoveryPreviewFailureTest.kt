package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryPreviewFailureTest : CountTrustRecoveryFixture() {
    private val armed = AtomicBoolean()
    private val reached = AtomicBoolean()
    private val target = AtomicReference<Job?>()
    private val callback = object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            if (sqlQuery.trim().lowercase() == "select * from next_replica_fence order by id" &&
                requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired && armed.compareAndSet(true, false)) {
                check(db.inTransaction()); reached.set(true)
                requireNotNull(target.get()).cancel(CancellationException("RECOVERY_PREVIEW_POST_CLEANUP_CANCEL"))
            }
        }
    }
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, callback) } }
    @Test fun finalFreshFailureRollsBackEveryNativeRowAndReleasesOnlyItsFailedPreview() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db)); val epoch = owner.generation
        val hold = owner.newRecoveryPreview()
        decryptCalls = 0; failDecryptAt = 2
        try {
            assertNotNull(runCatching { preparation().discardCache(access, hold) }.exceptionOrNull())
        } finally { hold.finishPreparation(false); failDecryptAt = Int.MAX_VALUE }
        assertEquals(2, decryptCalls); assertFalse(hold.isValid); assertFalse(hold.isRetained)
        assertTrue(owner.isRetired); assertEquals(epoch, owner.generation)
        assertTrue(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
    }
    @Test fun cancellationAfterRealCleanupWaitsForRollbackBeforeDroppingItsHold() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db)); val epoch = owner.generation
        val hold = owner.newRecoveryPreview(); var returned = false
        supervisorScope {
            val task = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try { preparation().discardCache(access, hold); returned = true }
                finally { hold.finishPreparation(false) }
            }
            target.set(task); armed.set(true)
            assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        }
        assertTrue(reached.get()); assertFalse(returned); assertFalse(hold.isRetained)
        assertTrue(owner.isRetired); assertEquals(epoch, owner.generation)
        assertTrue(CountTrustRetirementReason.RECOVERY_PREPARATION in owner.retirementReasons)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
        target.set(null)
    }
    @Test fun staleEntryCannotBindOrRetireAndItsFailureCannotEraseAnIndependentHold() = runBlocking<Unit> {
        register(); seedCache(); val access = access(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val first = owner.newRecoveryPreview()
        try { preparation().discardCache(access, first) } catch (error: Throwable) { first.finishPreparation(false); throw error }
        first.finishPreparation(true)
        val before = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
        val second = owner.newRecoveryPreview(); val reasons = owner.retirementReasons
        try { assertNotNull(runCatching { preparation().discardCache(access, second) }.exceptionOrNull()) }
        finally { second.finishPreparation(false) }
        assertFalse(second.isRetained); assertTrue(first.isRetained); assertEquals(reasons, owner.retirementReasons)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
        first.close(); assertFalse(first.isRetained)
    }

    @Test fun preflightPreservesEveryNativeRowAndCannotAuthorizeLaterCleanup() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        decryptCalls = 0
        preparation().preflight(access)
        assertEquals(2, decryptCalls); assertFalse(owner.isRetired)
        native.assertUnchanged(before)
        // A successful preflight is not a reusable authority proof across the network gap.
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false)
        assertEquals("SYNC_ACCOUNT_CHANGED", runCatching { preparation().discardCache(access) }.exceptionOrNull()?.message)
        assertFalse(owner.isRetired); native.assertUnchanged(before)
    }

    @Test fun preflightRequiresFreshEntryAndExitWithoutRetiringOnEitherFailure() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        for (failAt in 1..2) {
            decryptCalls = 0; failDecryptAt = failAt
            val failure = try { runCatching { preparation().preflight(access) }.exceptionOrNull() }
                finally { failDecryptAt = Int.MAX_VALUE }
            assertEquals("SYNC_ACCOUNT_CHANGED", failure?.message)
            assertEquals(failAt, decryptCalls); assertFalse(owner.isRetired)
            native.assertUnchanged(before)
        }
    }

    @Test fun preflightRejectsOverflowBeforeAnyCleanupOrQualification() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        db.withTransaction { owner.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_trust_control SET generation=? WHERE id=1", arrayOf(Long.MAX_VALUE))
        } }
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture(); val reasons = owner.retirementReasons
        assertEquals(ConsumerNativeCell(android.database.Cursor.FIELD_TYPE_INTEGER, Long.MAX_VALUE),
            before.getValue("count_trust_control").single().getValue("generation"))
        assertEquals("COUNT_TRUST_CONTROL_INVALID", runCatching { preparation().preflight(access) }.exceptionOrNull()?.message)
        assertEquals(reasons, owner.retirementReasons); native.assertUnchanged(before)
        // Same real MAX input plus a revoked hold must preserve the old direct-discard order.
        val closed = owner.newRecoveryPreview().also { it.close() }
        try {
            assertFalse(closed.isValid); assertFalse(closed.isRetained)
            assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED",
                runCatching { preparation().discardCache(access, closed) }.exceptionOrNull()?.message)
        } finally { closed.finishPreparation(false) }
        assertFalse(closed.isValid); assertFalse(closed.isRetained)
        assertEquals(reasons, owner.retirementReasons); native.assertUnchanged(before)
    }

    @Test fun preflightRejectsNativeMalformedControlWithoutNormalizingIt() = runBlocking<Unit> {
        register(); seedCache(); val access = access()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        db.withTransaction { owner.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_trust_control SET generation='malformed' WHERE id=1")
        } }
        val native = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = native.capture(); val reasons = owner.retirementReasons
        assertEquals(ConsumerNativeCell(android.database.Cursor.FIELD_TYPE_STRING, "malformed"),
            before.getValue("count_trust_control").single().getValue("generation"))
        assertEquals("COUNT_TRUST_CONTROL_INVALID", runCatching { preparation().preflight(access) }.exceptionOrNull()?.message)
        assertEquals(reasons, owner.retirementReasons); native.assertUnchanged(before)
    }
}
