package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real DataStore, Keystore and SQLite. Ordering gates only; not a mixed UI benchmark. */
@RunWith(AndroidJUnit4::class)
class G0AuthorityOwnerTest : NextCoreRequestFixture() {
    private val owner = G0AuthorityOwner()
    private val alias = "dayforge.test.g0.owner.${UUID.randomUUID()}"
    override fun isolatedDataStore(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) = owner.wrap(data)
    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher(alias)
    @After fun removeOwnerKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    @Test fun permitFirstKeepsActorFreeForFreshFinalAndDefersRevocationUntilRealCommit() = runBlocking<Unit> {
        register()
        val access = tokens.localCoreWriteAccess()
        assertNotNull(access)
        val lease = owner.lease()
        val entered = CompletableDeferred<Unit>()
        owner.onTransitionForTest = { entered.complete(Unit) }
        coroutineScope {
            lateinit var mutation: Deferred<Unit>
            var permit: AutoCloseable? = null
            try {
                db.withTransaction {
                    db.metricDao().update(metric.copy(name = "committed-before-revocation"))
                    permit = owner.finalPermit(lease)
                    mutation = async(Dispatchers.IO) { tokens.clearAuthenticationTokens() }
                    withTimeout(5000) { entered.await() }
                    assertFalse("Revocation must not persist across an acquired final permit", mutation.isCompleted)
                    assertTrue(runCatching { owner.lease() }.exceptionOrNull() is G0AuthorityOwner.Denied)
                    // This actual persisted read/decryption would deadlock if the revoker
                    // waited for our permit from INSIDE the DataStore actor.
                    assertEquals(access, withTimeout(5000) { tokens.localCoreWriteAccess() })
                }
                assertEquals("committed-before-revocation", db.metricDao().getMetricById(metric.id)!!.name)
                assertFalse(mutation.isCompleted)
            } finally { permit?.close() }
            withTimeout(5000) { mutation.await() }
        }
        assertNull(tokens.localCoreWriteAccess())
        assertTrue(runCatching { owner.checkLease(lease) }.exceptionOrNull() is G0AuthorityOwner.Denied)
    }

    @Test fun transitionFirstRejectsOldFinalAndRollsBackActualSql() = runBlocking<Unit> {
        register()
        val lease = owner.lease()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        owner.onTransitionForTest = { entered.complete(Unit); resume.await() }
        coroutineScope {
            val mutation = async(Dispatchers.IO) { tokens.clearAuthenticationTokens() }
            try {
                withTimeout(5000) { entered.await() }
                val failure = runCatching { db.withTransaction {
                    db.metricDao().update(metric.copy(name = "must-rollback"))
                    owner.finalPermit(lease).close()
                } }.exceptionOrNull()
                assertTrue(failure is G0AuthorityOwner.Denied)
                assertEquals(metric, db.metricDao().getMetricById(metric.id))
            } finally { resume.complete(Unit) }
            withTimeout(5000) { mutation.await() }
        }
        assertNull(tokens.localCoreWriteAccess())
    }

    @Test fun realPersistenceFailureQuarantinesOwnerWithoutChangingStoredCredentials() = runBlocking<Unit> {
        register()
        val before = tokens.localCoreWriteAccess()
        val lease = owner.lease()
        val failure = IllegalStateException("G0_PERSISTENCE_FAILURE")
        assertSame(failure, runCatching { dataStore.updateData { throw failure } }.exceptionOrNull())
        assertEquals(before, tokens.localCoreWriteAccess())
        assertTrue(runCatching { owner.lease() }.exceptionOrNull() is G0AuthorityOwner.Denied)
        assertTrue(runCatching { owner.finalPermit(lease) }.exceptionOrNull() is G0AuthorityOwner.Denied)
    }

    @Test fun cancellationAfterPersistenceWaitsForHandoffAndDoesNotPretendRollback() = runBlocking<Unit> {
        register()
        val lease = owner.lease()
        val persisted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.afterPersistenceForTest = { persisted.complete(Unit); release.await() }
        coroutineScope {
            val mutation = async(Dispatchers.IO) { tokens.clearAuthenticationTokens() }
            try {
                withTimeout(5000) { persisted.await() }
                mutation.cancel(CancellationException("G0_CALLER_CANCELLED_AFTER_PERSISTENCE"))
                assertNull(tokens.localCoreWriteAccess())
                assertTrue(runCatching { owner.lease() }.exceptionOrNull() is G0AuthorityOwner.Denied)
                assertFalse("Cancelled caller cannot abandon actual owner handoff", mutation.isCompleted)
            } finally { release.complete(Unit) }
            withTimeout(5000) { mutation.join() }
            assertTrue(mutation.isCancelled)
        }
        // A new lease coordinates work; it cannot grant access when actual authentication fails.
        owner.checkLease(owner.lease())
        assertNull(tokens.localCoreWriteAccess())
        assertTrue(runCatching { owner.checkLease(lease) }.exceptionOrNull() is G0AuthorityOwner.Denied)
    }

    @Test fun stableLeaseCannotReplaceFreshKeystoreAuthentication() = runBlocking<Unit> {
        register()
        val lease = owner.lease()
        assertNotNull(tokens.localCoreWriteAccess())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        owner.checkLease(lease)
        owner.finalPermit(lease).use { assertNull(tokens.localCoreWriteAccess()) }
    }

    @Test fun foreignAndRevokedLeasesCannotAcquireFinalPermit() = runBlocking<Unit> {
        val foreign = G0AuthorityOwner().lease()
        assertTrue(runCatching { owner.finalPermit(foreign) }.exceptionOrNull() is G0AuthorityOwner.Denied)
        val old = owner.lease()
        val before = checkNotNull(tokens.authenticationSnapshot())
        assertTrue(tokens.saveRefreshedTokens(before, "synthetic-refreshed", "synthetic-refresh", "member", id(1), false))
        assertEquals(before.session, checkNotNull(tokens.authenticationSnapshot()).session)
        assertTrue(runCatching { owner.finalPermit(old) }.exceptionOrNull() is G0AuthorityOwner.Denied)
        owner.finalPermit(owner.lease()).use { assertNotNull(tokens.localCoreWriteAccess()) }
    }

    @Test fun lostPersistenceResultDoesNotReopenAdmissionOrPretendCredentialsUnchanged() = runBlocking<Unit> {
        register()
        val failure = IllegalStateException("G0_PERSISTED_RESULT_LOST")
        owner.afterPersistenceForTest = { throw failure }
        assertSame(failure, runCatching { tokens.clearAuthenticationTokens() }.exceptionOrNull())
        assertNull(tokens.localCoreWriteAccess())
        assertTrue(runCatching { owner.lease() }.exceptionOrNull() is G0AuthorityOwner.Denied)
    }
    @Test fun reservationClearsBeforePersistenceAndLateObserverAlsoSeesIt()=runBlocking<Unit> {
        register();val prior=tokens.localCoreWriteAccess();val lease=owner.lease()
        val notifications=java.util.concurrent.atomic.AtomicInteger()
        val observer=owner.observeReservations { notifications.incrementAndGet() }
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        owner.onTransitionForTest={ assertEquals(1,notifications.get());entered.complete(Unit);release.await() }
        val mutation=async(Dispatchers.IO) { tokens.clearAuthenticationTokens() }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals(prior,tokens.localCoreWriteAccess())
            rejected { owner.checkLease(lease) }
            var late=0;owner.observeReservations { late++ }.use { assertEquals(1,late) }
        } finally { observer.close();release.complete(Unit);mutation.await() }
        owner.onTransitionForTest={};dataStore.updateData { it }
        assertEquals(1,notifications.get())
    }
    @Test fun failingMemoryNotificationCannotProceedIntoCredentialPersistence()=runBlocking<Unit> {
        register();val prior=tokens.localCoreWriteAccess()
        val failure=IllegalStateException("G0_MEMORY_NOTIFICATION_FAILED")
        val observer=owner.observeReservations { throw failure }
        try { assertSame(failure,runCatching { tokens.clearAuthenticationTokens() }.exceptionOrNull()) }
        finally { observer.close() }
        assertEquals(prior,tokens.localCoreWriteAccess());rejected { owner.lease() }
    }

}
