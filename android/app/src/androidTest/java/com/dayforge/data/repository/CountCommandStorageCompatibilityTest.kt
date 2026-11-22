package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Full raw archive/source retains primary obligations. Diagnostic counters never issue
 * execution or permit old snapshots to erase an enrolled catalog.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandStorageCompatibilityTest : NextObjectEditorFixture() {
    private suspend fun prepared(): CountCommandReference {
        tokens.getOrCreateInstallationId(); register()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val attempt = editor.beginCountAdd(habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val value = attempt.input
        editor.releaseUnexecuted(attempt)
        return CountCommandReference(value.commandId, value.inputHash, value.namespace.hash, value.namespace.dataset)
    }
    private suspend fun proof() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    @Test(timeout = 150_000) fun enrolledPreparedIsRetainedInRawSourceAndNeverProjectsTo19Or18() = runBlocking<Unit> {
        val reference = prepared(); val before = proof()
        assertEquals(45, NextReplicaRecoveryReader.TABLES.size)
        assertTrue(before.contents.getValue("tables").jsonObject.keys.containsAll(CountCommandV20Schema.tables))
        assertNull(NextReplicaV19StorageView.project(before)); assertTrue(NextReplicaCompatibilityViews.projected(before).isEmpty())
        val count = db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) }
        assertEquals(1L, count); assertEquals(before.contents, proof().contents)
        val store = CountCommandStore(db, tokens, sessions)
        assertEquals(CountCommandRecords.Execution.PREPARED, store.resolve(reference.native()).historical.status.execution)
        assertEquals(before.contents, proof().contents)
    }
    @Test(timeout = 150_000) fun headerOrNativeStatusDamageCannotMasqueradeAsACompleteRecoveryProjection() = runBlocking<Unit> {
        val reference = prepared()
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_command_status SET stateJson=stateJson||' ' WHERE commandId=?", arrayOf(reference.commandId))
        } }
        val damaged = proof()
        assertNotNull(runCatching { CountCommandStore(db, tokens, sessions).resolve(reference.native()) }.exceptionOrNull())
        assertNull(NextReplicaV19StorageView.project(damaged)) // Still enrolled, never strip it to satisfy an old hash.
        assertEquals(damaged.contents, proof().contents)
        db.withTransaction { CountTrustRuntime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32)")
        } }
        val badMac = proof()
        assertNotNull(runCatching { NextReplicaV19StorageView.project(badMac) }.exceptionOrNull())
        assertNotNull(runCatching { db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) } }.exceptionOrNull())
        assertEquals(badMac.contents, proof().contents)
    }
    @Test(timeout = 150_000) fun clearRotatesDatasetAndOldReferenceCannotBecomeAnAbsentExecutableCommand() = runBlocking<Unit> {
        val reference = prepared(); val before = CountCommandStore(db, tokens, sessions).resolve(reference.native())
        db.clearAllData()
        val old = runCatching { CountCommandStore(db, tokens, sessions).recover(reference.native()) }.exceptionOrNull()
        assertNotNull(old)
        val raw = proof(); val tables = raw.contents.getValue("tables").jsonObject
        assertTrue(tables.getValue("count_command_intents").jsonArray.isEmpty())
        assertTrue(tables.getValue("count_command_status").jsonArray.isEmpty())
        assertTrue(tables.getValue("count_command_results").jsonArray.isEmpty())
        assertTrue(tables.getValue("count_command_nodes").jsonArray.isEmpty())
        val root = CountCommandNativeRows(db.openHelper.writableDatabase) {}.root()
        assertNotEquals(before.historical.intent.namespace.dataset, root.datasetId)
        assertNotEquals(before.historical.intent.namespace.storage, root.storageId)
        assertEquals("EMPTY", root.mode)
        assertEquals(raw.contents, proof().contents)
    }
    @Test(timeout = 150_000) fun actualAnalyzeColdReopenAllowsExplicitSharedJournalGateThenNoEffectT1() = runBlocking<Unit> {
        prepared()
        db.openHelper.writableDatabase.execSQL("ANALYZE")
        storage.reopen()
        // Separate real capture gate: the later metadata-only NO_EFFECT producer is not
        // claimed to install the business journal itself. This exact call does so under
        // the new native owner containing real ANALYZE-created tables.
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val beforeGate = proof()
        val captured = owner.primaryTransaction(db) { owner.maintenance(db) {
            val command = owner.captureCommandBoundary()
            db.openHelper.writableDatabase.query("SELECT count(*) FROM temp.sqlite_master WHERE type='table' AND name='count_trust_journal_identity'").use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            val business = owner.capturePrimaryBusinessBoundary()
            db.openHelper.writableDatabase.query("SELECT count(*) FROM temp.sqlite_master WHERE type='table' AND name='count_trust_journal_identity'").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
            }
            assertTrue(owner.primaryBusinessChanges(business).isEmpty())
            owner.verifyCommandBoundary(command, emptyList()); owner.sealCommandOperation()
            assertEquals(access, tokens.localCoreWriteAccess())
        } }
        captured.end.requireCandidate(captured.value)
        assertEquals(beforeGate.contents, proof().contents)
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(habit, com.dayforge.util.DateTimeUtils.today())
        val identity = requireNotNull(summary.actionIdentity)
        assertNull(db.completionDao().getCompletionById(Long.MAX_VALUE))
        val input = CountCommandInputs.explicitTargetAbsent(db, java.util.UUID.randomUUID().toString(), Long.MAX_VALUE, identity)
        val store = CountCommandStore(db, tokens, sessions); val registered = store.register(input)
        val committed = store.completeCapturedNoEffect(requireNotNull(registered.ticket))
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, committed.outcome.kind)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val stable = proof()
        assertEquals(committed.outcome.text(), store.resolve(registered.reference).historical.outcome?.text())
        assertEquals(stable.contents, proof().contents)
    }

    @Test(timeout = 150_000) fun sameAccountOldNamespaceObligationIsReviewedButCannotExecuteAcrossEpoch() = runBlocking<Unit> {
        val reference = prepared(); val store = CountCommandStore(db, tokens, sessions)
        val original = store.resolve(reference.native())
        assertEquals(CountCommandStore.LocationScope.CURRENT, original.scope)
        assertEquals(CountCommandRecords.Execution.PREPARED, original.historical.status.execution)
        assertTrue(original.historical.status.pending); assertNull(original.historical.outcome)
        tokens.saveServerIdentity(id(2), id(9901))
        tokens.saveDeviceRegistration(id(4), caps, true, 1)
        val before = proof()
        // Diagnostic discovery preserves the complete old obligation but issues no ticket.
        // Exact-ID resolution/execution deliberately refuse this isolated current epoch.
        val page = store.discover(1)
        assertTrue(page.complete); assertNull(page.continuation)
        val located = page.commands.single()
        assertEquals(reference.native(), located.reference)
        assertEquals(CountCommandStore.LocationScope.ISOLATED, located.scope)
        assertEquals(original.historical.intent.text(), located.historical.intent.text())
        assertEquals(original.historical.member(), located.historical.member())
        assertEquals(original.historical.status, located.historical.status)
        assertTrue(located.historical.status.pending); assertNull(located.historical.outcome)
        assertEquals(1L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
        assertEquals(before.contents, proof().contents)
        val resolution = runCatching { store.resolve(reference.native()) }.exceptionOrNull()
        assertTrue(resolution is IllegalArgumentException)
        assertEquals("COMMAND_CURRENT_NAMESPACE_ISOLATED", resolution?.message)
        assertEquals(before.contents, proof().contents)
        repeat(2) {
            // Rejection releases the attempted execution lease; a second call must not
            // hide behind IN_PROGRESS or issue a current-epoch execution capability.
            val recovery = runCatching { store.recover(reference.native()) }.exceptionOrNull()
            assertTrue(recovery is IllegalArgumentException)
            assertEquals("COMMAND_CURRENT_NAMESPACE_ISOLATED", recovery?.message)
            assertEquals(before.contents, proof().contents)
        }
        val repeated = store.discover(1)
        assertTrue(repeated.complete); assertNull(repeated.continuation)
        assertEquals(listOf(located.reference), repeated.commands.map { it.reference })
        assertEquals(located.historical.member(), repeated.commands.single().historical.member())
        assertEquals(CountCommandStore.LocationScope.ISOLATED, repeated.commands.single().scope)
        assertEquals(before.contents, proof().contents)
    }

}
