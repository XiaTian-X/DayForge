package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CountNodeGcLifecycleTest : CountNodeGcFixture() {
    @Test fun accountWorkWinsWithoutQueueingTheCollector() = runBlocking<Unit> {
        ready(); orphan(100); val before = nativeAssertions().capture()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val work = async(Dispatchers.IO) { sessions.exclusive { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            assertEquals(CountTrustNodeCollector.Status.BUSY, collector().collectSlice().status)
        } finally { release.complete(Unit); work.await() }
        nativeAssertions().assertUnchanged(before)
        finish()
    }

    @Test fun lexicalReadEndDoesNotReleaseItsActualTransactionRoot() = runBlocking<Unit> {
        ready(); orphan(101)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) { db.withTransaction {
            val retained = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
            entered.complete(Unit); release.await()
            assertTrue(retained.eligible(habit))
        } }
        try {
            entered.await()
            val report = withTimeout(5000) { collector().collectSlice() }
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
            assertEquals("READER_OR_OWNER_PRESENT", report.reason)
        } finally { release.complete(Unit); reader.await() }
        finish()
    }

    @Test fun pendingNativeBeginIsVisibleBeforeItCanEstablishASnapshot() = runBlocking<Unit> {
        ready(); orphan(102)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        spy.pauseBegin = reached to release
        val reader = async(Dispatchers.IO) {
            val native = db.openHelper.writableDatabase
            native.beginTransactionNonExclusive()
            try { native.query("SELECT count(*) FROM main.count_trust_nodes").use { assertTrue(it.moveToFirst()) } }
            finally { native.endTransaction() }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { reached.await(5, TimeUnit.SECONDS) })
            val report = withTimeout(5000) { collector().collectSlice() }
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
        } finally { release.countDown(); reader.await() }
        finish()
    }

    @Test fun secondOpenOwnerDefersUntilTheOtherConnectionIsClosed() = runBlocking<Unit> {
        ready(); orphan(103)
        awaitRoomRefreshEndBeforeSecondOwner()
        val second = HabitDatabaseProvider.build(app)
        try {
            second.openHelper.writableDatabase.query("SELECT count(*) FROM main.count_trust_root").use { assertTrue(it.moveToFirst()) }
            assertTrue(second.isOpen)
            val secondOwner = requireNotNull(com.dayforge.data.local.CountTrustRuntime.forDatabase(second))
            assertNotSame(owner, secondOwner)
            val report = collector().collectSlice()
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
            assertEquals("READER_OR_OWNER_PRESENT", report.reason)
        } finally { second.close() }
        finish()
    }

    /** Observe an actual Room refresh END, then current registered native quiescence.
     * This is not an open/BUSY retry or proof that every not-yet-started Room job is gone.
     * Uses the same BEGIN/END gate as the existing lifecycle case; any later open failure
     * still propagates unchanged. No business write or GC is inserted before owner2. */
    private suspend fun awaitRoomRefreshEndBeforeSecondOwner() {
        val flowReady = CompletableDeferred<Unit>()
        val observer = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            db.invalidationTracker.createFlow("count_trust_nodes").onEach { flowReady.complete(Unit) }.collect()
        }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val ended = CountDownLatch(1)
        try {
            withTimeout(10_000) { flowReady.await() }
            spy.pauseBeginEnded = ended; spy.pauseBegin = entered to release
            withTimeout(5_000) {
                while (entered.count != 0L) {
                    db.invalidationTracker.refreshAsync()
                    withContext(Dispatchers.IO) { entered.await(25, TimeUnit.MILLISECONDS) }
                }
            }
            release.countDown()
            assertTrue("Room's real refresh must reach native outer END", withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) })
        } finally {
            release.countDown(); spy.pauseBegin = null; spy.pauseBeginEnded = null
            withTimeout(5_000) { observer.cancelAndJoin() }
        }
        // END's native signal precedes the factory's final bookkeeping by a few statements.
        // Only a known transient registered transaction may defer this precondition.
        withTimeout(5_000) {
            while (true) when (owner.nodeCollectionAdmission(db)) {
                com.dayforge.data.local.CountTrustRuntime.NodeGcAdmission.READY -> break
                com.dayforge.data.local.CountTrustRuntime.NodeGcAdmission.BUSY -> yield()
                com.dayforge.data.local.CountTrustRuntime.NodeGcAdmission.UNAVAILABLE -> error("GC_FIRST_OWNER_NOT_AVAILABLE")
            }
        }
    }

    @Test fun cancellationAtActualNativeReadDoesNotPublishProgressAndNextCollectorRebuilds() = runBlocking<Unit> {
        ready(); orphan(104); val before = nativeAssertions().capture(); var returned = false
        val child = launch {
            spy.cancelRead = requireNotNull(currentCoroutineContext()[Job])
            nextSlice(collector()); returned = true
        }
        child.join(); assertTrue(child.isCancelled); assertTrue(spy.faultTouched); assertFalse(returned)
        nativeAssertions().assertUnchanged(before)
        finish()
    }

    @Test fun retiredRecoveryOwnerNeverUsesGcAsPreviewCacheDisposal() = runBlocking<Unit> {
        ready(); orphan(105)
        val before = nativeAssertions().capture()
        db.withTransaction { owner.maintenance(db) { owner.retireFastPathForRecovery() } }
        val report = collector().collectSlice()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
        assertEquals("NO_OPEN_WORK", report.reason)
        nativeAssertions().assertUnchanged(before)
    }
    @Test fun externalNativeCommitRetiresTheJournalOwnerBeforeAnySweepCanContinue() = runBlocking<Unit> {
        ready(); orphan(106)
        db.withTransaction { owner.maintenance(db) { owner.captureChangeBoundary() } }
        val gc = collector(); untilSweep(gc)
        val digest = db.withTransaction { requireNotNull(CountTrustSeal.read(sql)).root.digest }
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf<Any>("external corruption".toByteArray(), digest))
        }
        val previousOwner = owner
        val result = nextSlice(gc)
        // Either this BEGIN or an already queued invalidation BEGIN can first observe the
        // external commit. Both must retire before sweep; no arbitrary miss is accepted.
        assertTrue(result.status == CountTrustNodeCollector.Status.FAILED ||
            result.status == CountTrustNodeCollector.Status.DEFERRED && result.reason == "NO_OPEN_WORK")
        assertEquals(0, result.deletedNodes); assertTrue(previousOwner.isRetired)
        assertEquals(0, spy.deletes)
        storage.reopen(); val before = nativeAssertions().capture(); val fresh = collector(); var failed = false
        for (step in 0 until 10000) {
            val next = nextSlice(fresh); assertEquals(0, next.deletedNodes)
            if (next.status == CountTrustNodeCollector.Status.FAILED) { failed = true; break }
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, next.status)
        }
        assertTrue(failed); nativeAssertions().assertUnchanged(before)
    }

    @Test fun actualRoomInvalidationBetweenConfirmedSlicesPreservesProgressAndFinishes() = runBlocking<Unit> {
        ready(); orphan(107)
        val flowReady = CompletableDeferred<Unit>()
        val observer = launch(Dispatchers.IO) {
            db.invalidationTracker.createFlow("count_trust_nodes").onEach { flowReady.complete(Unit) }.collect()
        }
        val release = CountDownLatch(1)
        try {
            withTimeout(10_000) { flowReady.await() }
            val before = nativeAssertions().capture(); val live = reachable()
            val gc = collector(); val marked = untilSweep(gc)
            val entered = CountDownLatch(1); val ended = CountDownLatch(1)
            spy.pauseBeginEnded = ended; spy.pauseBegin = entered to release
            // Room may coalesce the request with a prior refresh. Wait for the actual
            // native event and request again after that prior job drains; no timing guess.
            withTimeout(5_000) {
                while (entered.count != 0L) {
                    db.invalidationTracker.refreshAsync()
                    withContext(Dispatchers.IO) { entered.await(25, TimeUnit.MILLISECONDS) }
                }
            }
            // No test-owned native BEGIN occurs after arming: this is Room's real refresh.
            val deferred = gc.collectSlice()
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, deferred.status)
            assertEquals("READER_OR_OWNER_PRESENT", deferred.reason)
            assertEquals(0, deferred.deletedNodes); assertEquals(0, spy.deletes)
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) })
            val resumed = nextSlice(gc)
            assertEquals("A completed mark must not be thrown away by pre-work Room refresh", "SWEEP", resumed.executedPhase)
            assertEquals(marked.cohort, resumed.cohort)
            assertTrue(resumed.status in setOf(CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.FINISHED))
            if (resumed.status != CountTrustNodeCollector.Status.FINISHED) finish(gc)
            assertOnlyUnreachableRemoved(before, live)
        } finally {
            release.countDown(); spy.pauseBegin = null; spy.pauseBeginEnded = null
            observer.cancelAndJoin()
        }
    }

}
