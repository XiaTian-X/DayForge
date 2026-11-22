package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandLegacyNative
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandRetainedEnrollmentTest : CountCommandRetainedFixture() {
    @Test(timeout = 150_000) fun enrollmentPreservesRawBusinessStorageAndCountStateThenColdRetryIsReadOnly() = runBlocking<Unit> {
        seed(); val database = openCurrent(); val before = root(database)
        val control = CountCommandRootAnchor.read(database.openHelper.writableDatabase)
        val raw = CountCommandLegacyNative.fingerprint(database.openHelper.writableDatabase)
        store(database).ensureForwardEnrollment()
        val after = root(database)
        assertEquals("ACCOUNT_BOUND_RETAINED", after.mode); assertEquals(3, after.format); assertEquals(2L, after.revision)
        assertEquals(before.storageId, after.storageId); assertEquals(before.datasetId, after.datasetId)
        assertEquals(before.schemaHash, after.schemaHash); assertEquals(before.initializationSource, after.initializationSource)
        assertEquals(before.legacyCount, after.legacyCount); assertEquals(before.legacyHash, after.legacyHash)
        assertEquals(control.copy(hash = CountCommandRootAnchor.digest(after)), CountCommandRootAnchor.read(database.openHelper.writableDatabase))
        assertEquals(raw, CountCommandLegacyNative.fingerprint(database.openHelper.writableDatabase))
        assertEquals(0L, count(database, "count_command_intents")); assertEquals(0L, count(database, "count_command_status"))
        assertEquals(0L, count(database, "metric_logs")); assertEquals(0L, count(database, "sync_outbox"))
        val committed = image(database); val cold = openCurrent(); store(cold).ensureForwardEnrollment()
        assertEquals(committed, image(cold)); assertLegacy(cold)
    }
    @Test(timeout = 150_000) fun unrelatedPreT0ProcessLeaseDoesNotBlockEnrollmentOrBecomeExecution() = runBlocking<Unit> {
        seed(); val database = openCurrent()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        val lease = requireNotNull(CountCommandExecutionLeases.acquire(owner, id(90), requireNotNull(currentCoroutineContext()[Job])))
        try {
            store(database).ensureForwardEnrollment()
            assertEquals("ACCOUNT_BOUND_RETAINED", root(database).mode)
            assertEquals(0L, count(database, "count_command_intents")); assertLegacy(database)
        } finally { lease.close() }
    }
    @Test(timeout = 150_000) fun localUnregisteredPendingReportsOldObligationsWithoutImplicitEnrollmentOrExecution() = runBlocking<Unit> {
        seed(); tokens.clearTokens()
        tokens.saveLoginSession("local-only", "refresh", "member", id(1), false)
        assertNotNull(tokens.localCoreWriteAccess()); assertNull(tokens.localSyncAccess())
        val database = openCurrent(); val original = image(database)
        val old = store(database).discover(1)
        assertTrue(old.complete); assertTrue(old.commands.isEmpty()); assertEquals(1L, old.retainedCount)
        assertEquals(original, image(database)); assertNull(tokens.localSyncAccess())
        store(database).ensureForwardEnrollment()
        val enrolled = image(database); val fresh = store(database).discover(1)
        assertEquals(1L, fresh.retainedCount); assertTrue(fresh.complete); assertTrue(fresh.commands.isEmpty())
        assertEquals(enrolled, image(database)); assertLegacy(database)
        val pending = CountCommandPendingRepository(database, tokens, sessions, io.mockk.mockk(), io.mockk.mockk(),
            io.mockk.mockk(), commandMigrationPreferences, repository(database)).page()
        assertEquals(1L, pending.retainedCount); assertTrue(pending.complete); assertTrue(pending.entries.isEmpty())
        assertNull(tokens.localSyncAccess()); assertEquals(enrolled, image(database))
    }
    @Test(timeout = 150_000) fun retainedObligationBlocksOldViewAndCountsAlongsideNewPendingWithoutNetwork() = runBlocking<Unit> {
        seed(); val database = openCurrent(); val value = metric(database); val repo = repository(database)
        val attempt = repo.beginIndependentValue(value.id, 2.0, "value", expectedMetricUuid = value.uuid)
        val saved = repo.executeIndependentValue(attempt)
        val page = store(database).discover(32)
        assertEquals(1L, page.retainedCount); assertEquals(listOf(saved.reference?.commandId), page.commands.map { it.reference.commandId })
        val access = requireNotNull(tokens.localSyncAccess())
        val total = database.withTransaction { CountCommandRecoveryInventory(database, tokens).pendingInTransaction(access) }
        assertEquals(2L, total)
        val raw = database.withTransaction { NextReplicaStorageProof(database).readInTransaction() }
        assertNull(NextReplicaV19StorageView.project(raw))
        repo.acknowledgeIndependentValue(saved) { true }
        assertEquals(1L, database.withTransaction { CountCommandRecoveryInventory(database, tokens).pendingInTransaction(access) })
        assertLegacy(database)
    }
    @Test(timeout = 150_000) fun authorizedClearCreatesNewEmptyDatasetAndRejectsEveryOldReference() = runBlocking<Unit> {
        seed(); val database = openCurrent(); val value = metric(database); val repo = repository(database)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(value.id, 2.0, "value", expectedMetricUuid = value.uuid))
        val old = root(database); database.clearAllData(); val fresh = root(database)
        assertEquals("EMPTY", fresh.mode); assertNotEquals(old.datasetId, fresh.datasetId)
        assertEquals(0L, count(database, "count_command_results")); assertEquals(0L, count(database, "count_command_intents"))
        assertNotNull(runCatching { repo.resolveIndependentValue(requireNotNull(saved.reference)) }.exceptionOrNull())
        assertTrue(CountCommandNativeRows.sameRoot(fresh, root(database)))
    }
    @Test(timeout = 150_000) fun finalAuthorityChangeCannotEnrollWrongNamespaceAndOldMetadataStaysExact() = runBlocking<Unit> {
        seed(); val database = openCurrent(); val value = metric(database)
        val attempt = repository(database).beginIndependentValue(value.id, 2.0, "old", expectedMetricUuid = value.uuid)
        tokens.saveLoginSession("different-access", "refresh", "member", id(9), false)
        val before = image(database)
        assertNotNull(runCatching { store(database).ensureForwardEnrollment(requireNotNull(attempt.intent)) }.exceptionOrNull())
        assertEquals(before, image(database)); assertEquals("LEGACY_UNENROLLED", root(database).mode)
        CountCommandStorageInitialization.verifyMandatory(database.openHelper.writableDatabase)
    }
}
