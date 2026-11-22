package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryPreviewBoundaryTest : CountNodeGcFixture() {
    private suspend fun held(): CountTrustRecoveryPreview {
        val hold = owner.newRecoveryPreview(); var delivered = false
        try {
            CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(access(), hold)
            delivered = true; return hold
        } finally { hold.finishPreparation(delivered) }
    }
    private suspend fun suppressed(): Boolean = db.withTransaction { owner.maintenance(db) {
        val storageId = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        owner.recoveryPreviewSuppressed(storageId)
    } }
    @Test fun twoReturnedPreviewsOwnIndependentHoldsAndFreshOwnerCannotRequalifyUntilBothClose() = runBlocking<Unit> {
        ready(); val first = held(); val second = held()
        try {
            first.close(); assertFalse(first.isValid); assertFalse(first.isRetained)
            assertTrue(second.isValid); assertTrue(second.isRetained)
            val before = nativeAssertions().capture()
            storage.reopen()
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            assertTrue(owner.isRetired); assertTrue(CountTrustRetirementReason.RECOVERY_PREVIEW in owner.retirementReasons)
            nativeAssertions().assertUnchanged(before)
            second.close(); assertFalse(second.isRetained); assertTrue(owner.isRetired)
            assertFalse(suppressed())
            storage.reopen()
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            assertFalse(owner.isRetired)
            sql.query("SELECT count(*) FROM count_trust_root").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        } finally { first.close(); second.close() }
    }
    @Test fun closeInvalidatesImmediatelyButCannotReleaseAnAlreadyRunningUse() = runBlocking<Unit> {
        ready(); val hold = held(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val current = owner
        val operation = async { hold.use(current) { entered.complete(Unit); release.await() } }
        try {
            withTimeout(5_000) { entered.await() }
            hold.close(); assertFalse(hold.isValid); assertTrue(hold.isRetained)
            assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", runCatching { hold.use(current) { fail("Closed hold executed") } }.exceptionOrNull()?.message)
            assertTrue(suppressed())
        } finally { release.complete(Unit); operation.await(); hold.close() }
        assertFalse(hold.isRetained); assertFalse(suppressed()); assertTrue(current.isRetired)
    }
    @Test fun anOldStorageHoldCannotReleaseAReplacementStoragesIndependentPreview() = runBlocking<Unit> {
        ready(); val first = held()
        val oldStorage = nativeAssertions().capture().getValue("count_trust_control").single().getValue("storageId")
        var replacement: CountTrustRecoveryPreview? = null
        try {
            db.clearAllData(); storage.reopen()
            val image = nativeAssertions().capture()
            assertNotEquals(oldStorage, image.getValue("count_trust_control").single().getValue("storageId"))
            assertFalse(suppressed()); assertFalse(owner.isRetired)
            val fresh = held(); replacement = fresh
            first.close(); first.close()
            assertTrue(fresh.isValid); assertTrue(fresh.isRetained); assertTrue(suppressed())
            fresh.close(); assertFalse(fresh.isRetained); assertFalse(suppressed())
        } finally { first.close(); replacement?.close() }
    }
    @Test fun aFreshCollectorCannotDeleteUnderAnotherOwnersLivePreview() = runBlocking<Unit> {
        ready(); val hold = held()
        try {
            orphan(711)
            val before = nativeAssertions().capture()
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            val report = nextSlice(collector())
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
            assertEquals("COUNT_GC_RECOVERY_PENDING", report.reason); assertEquals(0, report.deletedNodes)
            assertTrue(owner.isRetired); nativeAssertions().assertUnchanged(before)
            hold.close(); assertFalse(hold.isRetained)
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            finish(); assertOnlyUnreachableRemoved(before, emptySet())
        } finally { hold.close() }
    }
    @Test fun twoActualOwnersOfOneUnknownPathFileCannotMissTheLivePreviewAndRequalify() = runBlocking<Unit> {
        ready()
        storage.isolatedBuilder = ::unknownPathDatabase; storage.reopen()
        nativeAssertions().capture() // Intentional actual open, not an admission-time query.
        val firstOwner = owner; val hold = held()
        try {
            val before = nativeAssertions().capture()
            assertTrue(hold.isValid); assertTrue(hold.isRetained)
            assertTrue(before.getValue("count_trust_root").isEmpty())
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            assertNotSame(firstOwner, owner)
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            // R1 used distinct factory objects as keys and could issue a new root here.
            assertTrue(owner.isRetired)
            assertTrue(owner.retirementReasons.any { it.name == "CONNECTION_IDENTITY_UNKNOWN" })
            nativeAssertions().assertUnchanged(before)
            hold.close(); assertFalse(hold.isRetained)
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            assertTrue(owner.isRetired); nativeAssertions().assertUnchanged(before)
        } finally { hold.close() }
    }
    private suspend fun mixedIdentityOwners(oldUnknown: Boolean, newUnknown: Boolean) {
        ready()
        storage.isolatedBuilder = if (oldUnknown) ::unknownPathDatabase else { context -> HabitDatabaseProvider.build(context) }
        storage.reopen(); nativeAssertions().capture()
        val old = owner; val first = held(); var second: CountTrustRecoveryPreview? = null
        try {
            if (oldUnknown) second = held() // Unknown registrations also remain per-preview.
            val before = nativeAssertions().capture()
            storage.isolatedBuilder = if (newUnknown) ::unknownPathDatabase else { context -> HabitDatabaseProvider.build(context) }
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            assertNotSame(old, owner)
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            assertTrue(owner.isRetired); nativeAssertions().assertUnchanged(before)
            first.close(); first.close()
            if (second != null) {
                assertTrue(requireNotNull(second).isRetained)
                storage.reopen(); nativeAssertions().assertUnchanged(before)
                CountHistoryReader(db, tokens, sessions).readSummary(habit)
                assertTrue(owner.isRetired); nativeAssertions().assertUnchanged(before)
                requireNotNull(second).close()
            }
            assertFalse(first.isRetained); assertTrue(second?.isRetained != true)
            // No global block: after the exact holds finish, a known-path owner can qualify.
            storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context) }
            storage.reopen(); nativeAssertions().assertUnchanged(before)
            CountHistoryReader(db, tokens, sessions).readSummary(habit)
            assertFalse(owner.isRetired)
            sql.query("SELECT count(*) FROM count_trust_root").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        } finally { first.close(); second?.close() }
    }
    @Test fun unknownOldOwnerCannotLoseItsPreviewWhenTheSameFileGetsAKnownPathOwner() = runBlocking<Unit> {
        mixedIdentityOwners(oldUnknown = true, newUnknown = false)
    }
    @Test fun knownOldOwnerCannotLoseItsPreviewWhenTheSameFileGetsAnUnknownPathOwner() = runBlocking<Unit> {
        mixedIdentityOwners(oldUnknown = false, newUnknown = true)
    }
}
