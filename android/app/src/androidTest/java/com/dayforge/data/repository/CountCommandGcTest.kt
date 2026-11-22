package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real v20 primary rows plus the unchanged bounded COUNT collector. These cases do not
 * claim command-node GC, old-file migration coverage or actual process-death recovery.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandGcTest : CountNodeGcFixture() {
    private suspend fun prepared(): CountCommandStore.Reference {
        ready()
        assertEquals(CountCommandV20Schema.VERSION, sql.version)
        val commands = CountCommandStore(db, tokens, sessions)
        commands.bindEmptyNamespace()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val root = db.withTransaction { sql.query("SELECT storageId,datasetId FROM count_command_recovery_roots WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
        } }
        val namespace = CountCommandRecords.Namespace(access.session.authentication.userId, access.session.serverInstanceId,
            access.session.syncEpoch, access.capturedDeviceId, root.first, root.second)
        val body = buildJsonObject {
            put("format", 1); put("command_id", id(2001)); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation)
            put("action", "COUNT_ADD"); put("capture", "EXECUTION_INPUT")
            put("input", buildJsonObject {
                put("habit_uuid", habit.uuid); put("habit_id", habit.id); put("widget_claim", JsonNull)
                put("quantity", 1); put("occurred_at", time); put("timezone", "UTC"); put("local_date", "2026-10-06")
                put("plan", NextStructureMapper.writePlan(habit)); put("round", JsonNull); put("reserved_fact_uuid", id(2002))
            })
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        val value = commands.register(CountCommandRecords.Intent.decode(bytes, bytes.size, {}))
        assertFalse(value.alreadyRegistered)
        commands.releaseUnadmitted(requireNotNull(value.ticket))
        assertTrue(count("count_command_nodes") > 0)
        return value.reference
    }

    @Test(timeout = 150_000) fun absentOptionalCountRootNeverDeletesDurableCommandCatalog() = runBlocking<Unit> {
        val reference = prepared(); orphan(2401)
        db.withTransaction { owner.maintenance(db) { sql.execSQL("DELETE FROM count_trust_root") } }
        val before = compactNativeProof()
        assertTrue(before.getValue("count_command_nodes").isNotEmpty())
        spy.resetCounts()
        val reports = finish()
        assertTrue(reports.sumOf { it.deletedNodes } > 0); assertTrue(spy.deletes > 0)
        val after = compactNativeProof()
        assertEquals(before - "count_trust_nodes", after - "count_trust_nodes")
        assertTrue(after.getValue("count_trust_nodes").isEmpty())
        assertTrue(CountCommandStore(db, tokens, sessions).inspectPrepared(reference).unchangedBusinessSinceAdmission)
        assertEquals(after, compactNativeProof())
    }

    @Test(timeout = 150_000) fun badPrimaryMacStopsBeforeCountDeletionAndOriginalRootCanRetry() = runBlocking<Unit> {
        val reference = prepared(); orphan(2402)
        val original = db.withTransaction { sql.query("SELECT mac FROM count_command_recovery_roots WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getBlob(0)
        } }
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
        } }
        val damaged = compactNativeProof(); spy.resetCounts()
        val failed = nextSlice(collector())
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status)
        assertEquals("COMMAND_PRIMARY_MAC_INVALID", failed.reason)
        assertEquals(0, spy.deletes); assertEquals(damaged, compactNativeProof())
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("UPDATE count_command_recovery_roots SET mac=? WHERE id=1", arrayOf(original))
        } }
        val repaired = compactNativeProof(); val live = reachable()
        finish()
        assertEquals(repaired - "count_trust_nodes", compactNativeProof() - "count_trust_nodes")
        assertEquals(live, reachable())
        assertTrue(CountCommandStore(db, tokens, sessions).inspectPrepared(reference).unchangedBusinessSinceAdmission)
    }

    @Test(timeout = 150_000) fun missingMandatoryPrimaryRootIsNotAnEmptyCollectablePool() = runBlocking<Unit> {
        prepared(); orphan(2403)
        db.withTransaction { owner.maintenance(db) { sql.execSQL("DELETE FROM count_command_recovery_roots") } }
        val damaged = compactNativeProof(); spy.resetCounts()
        val failed = nextSlice(collector())
        assertEquals(CountTrustNodeCollector.Status.FAILED, failed.status)
        assertEquals("COMMAND_PRIMARY_ROOT_MISSING", failed.reason)
        assertEquals(0, spy.deletes); assertEquals(damaged, compactNativeProof())
    }
}
