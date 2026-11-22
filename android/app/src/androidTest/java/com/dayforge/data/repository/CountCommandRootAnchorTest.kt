package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountTrustRuntime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real controlled Room T0/T1/T2 and native rollback. These are local metadata fault gates,
 * not whole-control rollback protection, process death, UI delivery or latency evidence. */
@RunWith(AndroidJUnit4::class)
class CountCommandRootAnchorTest : NextObjectEditorFixture() {
    private val sql get() = db.openHelper.writableDatabase
    private class Rollback : RuntimeException()
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun control() = db.withTransaction { CountCommandRootAnchor.read(sql) }
    private suspend fun root() = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
    private suspend fun verified() = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(sql).header }
    private suspend fun prepared(): Pair<CountCommandStore, CountCommandStore.Reference> {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val store = CountCommandStore(db, tokens, sessions)
        val registered = store.register(input)
        store.releaseUnadmitted(requireNotNull(registered.ticket))
        return store to registered.reference
    }
    private suspend fun fault(sqlText: String, args: Array<Any?> = emptyArray()) = db.withTransaction {
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) { sql.execSQL(sqlText, args) }
    }
    private suspend fun refused(expected: String, action: suspend () -> Unit) {
        val before = image()
        val error: Throwable? = try { action(); null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) { failure }
            catch (failure: IllegalArgumentException) { failure }
        assertTrue("Expected precise refusal: $expected", generateSequence(error) { it.cause }.any { it.message == expected })
        assertTrue("Rejected operation preserves every native main field", before == image())
    }

    @Test(timeout = 150_000) fun t0AdvancesAnchorWithoutChangingOriginalBusinessControlOrCountRoot() = runBlocking<Unit> {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val before = control(); val countBefore = db.withTransaction { CountTrustSeal.read(sql) }
        val store = CountCommandStore(db, tokens, sessions); val registration = store.register(input)
        store.releaseUnadmitted(requireNotNull(registration.ticket))
        val reference = registration.reference; val after = control(); val authenticated = verified()
        assertEquals(before.storage, after.storage); assertEquals(before.generation, after.generation); assertEquals(before.dirty, after.dirty)
        assertNotEquals(before.hash, after.hash); assertEquals(CountCommandRootAnchor.digest(root()), after.hash)
        assertEquals(countBefore, db.withTransaction { CountTrustSeal.read(sql) })
        assertEquals(1L, authenticated.pending.count)
        assertEquals(CountCommandRecords.Execution.PREPARED, CountCommandStore(db, tokens, sessions).resolve(reference).historical.status.execution)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun noEffectT1AndT2AdvanceOnlyTheirOwnAuthenticatedMetadata() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId(); register()
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(habit, com.dayforge.util.DateTimeUtils.today())
        val input = CountCommandInputs.explicitTargetAbsent(db, UUID.randomUUID().toString(), Long.MAX_VALUE, requireNotNull(summary.actionIdentity))
        val store = CountCommandStore(db, tokens, sessions)
        val before = control(); val registered = store.register(input); val t0 = control()
        val result = store.completeCapturedNoEffect(requireNotNull(registered.ticket)); val t1 = control()
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, result.outcome.kind); verified()
        store.markDelivered(registered.reference); val t2 = control(); verified()
        assertEquals(before.copy(hash = t0.hash), t0)
        assertEquals(t0.copy(hash = t1.hash), t1); assertEquals(t1.copy(hash = t2.hash), t2)
        assertNotEquals(before.hash, t0.hash); assertNotEquals(t0.hash, t1.hash); assertNotEquals(t1.hash, t2.hash)
        assertEquals(0L, verified().pending.count)
        val done = image(); store.markDelivered(registered.reference); assertTrue(done == image())
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun missingMalformedAndWrongAnchorNeverBecomeAnEmptyOrExecutableCatalog() = runBlocking<Unit> {
        val (store, ref) = prepared(); val original = control()
        for ((value, error) in listOf(null to "COMMAND_PRIMARY_ANCHOR_MISSING", byteArrayOf(1) to "COMMAND_PRIMARY_ANCHOR_INVALID",
            "0".repeat(64) to "COMMAND_PRIMARY_ANCHOR_MISMATCH")) {
            fault("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf(value))
            refused(error) { store.resolve(ref) }
            assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
            fault("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf(original.hash))
            verified()
        }
    }

    @Test(timeout = 150_000) fun matchingHashOfAnInvalidMacCannotAuthenticateTheRoot() = runBlocking<Unit> {
        val (store, ref) = prepared()
        val damagedRoot = root().copy(mac = ByteArray(32))
        fault("UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
        fault("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf(CountCommandRootAnchor.digest(damagedRoot)))
        refused("COMMAND_PRIMARY_MAC_INVALID") { store.resolve(ref) }
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
    }

    @Test(timeout = 150_000) fun sameValueExtraAnchorUpdateIsInTheActualWriteSetAndRollsBack() = runBlocking<Unit> {
        prepared(); val before = image(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        var touched = false
        val failure: Throwable? = try {
            owner.primaryTransaction(db) { owner.maintenance(db) {
                val boundary = owner.captureCommandBoundary()
                sql.execSQL("UPDATE count_trust_control SET commandRootHash=commandRootHash WHERE id=1")
                touched = sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L }
                owner.verifyCommandBoundary(boundary, emptyList())
                owner.sealCommandOperation()
            } }
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalStateException) { error }
        assertTrue(touched)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "COMMAND_JOURNAL_EXTRA_OR_GAP" })
        assertTrue(before == image())
    }

    @Test(timeout = 150_000) fun nativeRollbackCannotLeaveOnlyOneSideOfTheRootAnchorPair() = runBlocking<Unit> {
        prepared(); val before = image(); val state = control()
        try {
            db.withTransaction<Unit> { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
                sql.execSQL("UPDATE count_command_recovery_roots SET revision=revision+1 WHERE id=1")
                CountCommandRootAnchor.update(sql, state, "1".repeat(64))
                throw Rollback()
            } }
            fail("Rollback required")
        } catch (_: Rollback) { }
        assertTrue(before == image()); verified()
    }
}
