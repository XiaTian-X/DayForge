package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandRetainedNativeBoundaryTest : CountCommandRetainedFixture() {
    private suspend fun rollback(mode: CountCommandRetainedNativeFault.Mode, deferred: Boolean = false) {
        seed(); val fault = CountCommandRetainedNativeFault(); val database = faultRoom(fault)
        val before = image(database); fault.arm(mode)
        val failure = runCatching { store(database).ensureForwardEnrollment() }.exceptionOrNull()
        assertNotNull(failure); assertEquals(1, fault.hits)
        assertTrue(fault.actualRollback); assertFalse(fault.actualCommit)
        assertEquals(deferred, fault.actualDeferredFailure)
        val cold = openCurrent(); assertEquals(before, image(cold)); assertEquals("LEGACY_UNENROLLED", root(cold).mode)
        assertLegacy(cold)
        store(cold).ensureForwardEnrollment()
        assertEquals("ACCOUNT_BOUND_RETAINED", root(cold).mode); assertLegacy(cold)
        assertEquals(0L, count(cold, "count_command_intents")); assertEquals(0L, count(cold, "metric_logs"))
    }
    @Test(timeout = 150_000) fun actualNodeWriteFailureRollsBackAndColdRetryRetainsOldRows() = runBlocking<Unit> {
        rollback(CountCommandRetainedNativeFault.Mode.BEFORE_NODE)
    }
    @Test(timeout = 150_000) fun actualRootWriteFailureRollsBackTheNewNodeAndColdRetryIsComplete() = runBlocking<Unit> {
        rollback(CountCommandRetainedNativeFault.Mode.BEFORE_ROOT)
    }
    @Test(timeout = 150_000) fun actualAnchorWriteFailureCannotLeaveMixedNewRootAndOldAnchor() = runBlocking<Unit> {
        rollback(CountCommandRetainedNativeFault.Mode.BEFORE_ANCHOR)
    }
    @Test(timeout = 150_000) fun extraOldRawMutationAfterNewRootIsRejectedBeforeCommit() = runBlocking<Unit> {
        rollback(CountCommandRetainedNativeFault.Mode.EXTRA_LEGACY)
    }
    @Test(timeout = 150_000) fun realDeferredForeignKeyFailsOuterEndThenColdStateIsExactlyOld() = runBlocking<Unit> {
        rollback(CountCommandRetainedNativeFault.Mode.DEFERRED_END, deferred = true)
    }
    @Test(timeout = 150_000) fun actualCommitWithLostReturnReopensAsCompleteRetainedRootAndNeverRunsT0Automatically() = runBlocking<Unit> {
        seed(); val fault = CountCommandRetainedNativeFault(); val database = faultRoom(fault)
        val old = root(database); fault.arm(CountCommandRetainedNativeFault.Mode.AFTER_END)
        assertNotNull(runCatching { store(database).ensureForwardEnrollment() }.exceptionOrNull())
        assertEquals(1, fault.hits); assertTrue(fault.actualCommit); assertFalse(fault.actualRollback)
        val cold = openCurrent(); val fresh = root(cold)
        assertEquals("ACCOUNT_BOUND_RETAINED", fresh.mode); assertEquals(old.datasetId, fresh.datasetId)
        assertEquals(old.legacyHash, fresh.legacyHash); assertEquals(old.legacyCount, fresh.legacyCount)
        assertEquals(0L, count(cold, "count_command_intents")); assertEquals(0L, count(cold, "count_command_status"))
        assertEquals(0L, count(cold, "metric_logs")); assertEquals(0L, count(cold, "sync_outbox")); assertLegacy(cold)
        val committed = image(cold); store(cold).ensureForwardEnrollment(); assertEquals(committed, image(cold))
        val metric = metric(cold); val repo = repository(cold)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(metric.id, 8.0, "explicit", expectedMetricUuid = metric.uuid))
        repo.acknowledgeIndependentValue(saved) { true }; assertEquals(1L, count(cold, "metric_logs"))
        assertTrue(repo.resolveIndependentValue(requireNotNull(saved.reference)) is IndependentMetricResolution.Saved)
        assertEquals(1L, count(cold, "metric_logs")); assertLegacy(cold)
    }
    @Test(timeout = 150_000) fun finalT2NativeRetainedMutationRollsBackOnlyDeliveryAndColdRetryKeepsOneFact() = runBlocking<Unit> {
        seed(); val fault = CountCommandRetainedNativeFault(); val database = faultRoom(fault)
        val metric = metric(database); val repo = repository(database)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(metric.id, 8.0, "value", expectedMetricUuid = metric.uuid))
        val before = image(database)
        fault.arm(CountCommandRetainedNativeFault.Mode.EXTRA_LEGACY)
        assertNotNull(runCatching { repo.acknowledgeIndependentValue(saved) { true } }.exceptionOrNull())
        assertEquals(1, fault.hits); assertTrue(fault.actualRollback)
        val cold = openCurrent(); assertEquals(before, image(cold)); assertEquals(1L, count(cold, "metric_logs")); assertLegacy(cold)
        val reopened = repository(cold)
        val resolved = reopened.resolveIndependentValue(requireNotNull(saved.reference))
        assertTrue(resolved is IndependentMetricResolution.Saved)
        reopened.acknowledgeIndependentValue((resolved as IndependentMetricResolution.Saved).value) { true }
        assertEquals(1L, count(cold, "metric_logs")); assertTrue(store(cold).discover(32).commands.isEmpty()); assertLegacy(cold)
    }
}
