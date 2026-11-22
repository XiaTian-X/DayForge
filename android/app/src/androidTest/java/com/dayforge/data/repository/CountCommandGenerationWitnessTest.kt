package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** New complete physical-Room class candidate. Tests the conditional generation witness,
 * NOT a recovery execution issuer, unknown COMMIT or real process death. No test is run yet.
 * Ordinary per-method timeout remains 150 seconds; fixture has no long historical seed.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandGenerationWitnessTest : NextCoreRequestFixture() {
    private class DeliberateRollback : RuntimeException()
    private fun commands() = CountCommandStore(db, tokens, sessions)
    private val sql get() = db.openHelper.writableDatabase
    private fun generation() = sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    private suspend fun prepared(): Pair<CountCommandStore, CountCommandStore.Reference> {
        val store = commands(); store.bindEmptyNamespace()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val root = db.withTransaction { sql.query("SELECT storageId,datasetId FROM count_command_recovery_roots WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
        } }
        val namespace = CountCommandRecords.Namespace(access.session.authentication.userId, access.session.serverInstanceId,
            access.session.syncEpoch, access.capturedDeviceId, root.first, root.second)
        val body = buildJsonObject {
            put("format", 1); put("command_id", id(1001)); put("namespace", namespace.body())
            put("original_auth_generation", access.session.authentication.generation)
            put("action", "COUNT_ADD"); put("capture", "EXECUTION_INPUT")
            put("input", buildJsonObject {
                put("habit_uuid", habit.uuid); put("habit_id", habit.id); put("widget_claim", JsonNull); put("quantity", 1); put("occurred_at", time)
                put("timezone", "UTC"); put("local_date", "2026-10-06"); put("plan", NextStructureMapper.writePlan(habit))
                put("round", JsonNull); put("reserved_fact_uuid", id(1002))
            })
        }
        val bytes = CountCommandRecords.canonical(body).toByteArray(Charsets.UTF_8)
        val registered = store.register(CountCommandRecords.Intent.decode(bytes, bytes.size, {}))
        assertFalse(registered.alreadyRegistered); assertNotNull(registered.ticket)
        return store to registered.reference
    }

    @Test(timeout = 150_000) fun t0BaselineSurvivesARealDiskReopenWithoutGrantingExecution() = runBlocking<Unit> {
        val (_, reference) = prepared()
        val before = imageWithTemporarySequenceWitness()
        storage.reopen()
        val observed = commands().inspectPrepared(reference)
        assertTrue(observed.unchangedBusinessSinceAdmission)
        val after = image()
        assertEquals("Complete main image changed tables=" + (before.keys + after.keys).filter { before[it] != after[it] },
            before, after)
        assertEquals(0L, sql.query("SELECT count(*) FROM completions").use { assertTrue(it.moveToFirst()); it.getLong(0) })
    }

    @Test(timeout = 150_000) fun committedSameValueBusinessUpdateInvalidatesTheWitness() = runBlocking<Unit> {
        val (store, reference) = prepared(); val before = generation()
        val originalMetric = db.metricDao().getMetricById(metric.id)
        db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        assertEquals(before + 1, generation())
        assertEquals(originalMetric, db.metricDao().getMetricById(metric.id))
        assertFalse(store.inspectPrepared(reference).unchangedBusinessSinceAdmission)
        assertEquals(0L, sql.query("SELECT count(*) FROM count_command_results").use { assertTrue(it.moveToFirst()); it.getLong(0) })
    }

    @Test(timeout = 150_000) fun nativeRollbackPreservesTheExactAdmissionSnapshot() = runBlocking<Unit> {
        val (store, reference) = prepared(); val before = image()
        try {
            db.withTransaction<Unit> {
                sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
                throw DeliberateRollback()
            }
            fail("Expected the deliberate native rollback")
        } catch (_: DeliberateRollback) { }
        assertEquals(before, image())
        assertTrue(store.inspectPrepared(reference).unchangedBusinessSinceAdmission)
    }

    @Test(timeout = 150_000) fun baselineColumnTamperCannotTurnAdvancedStateIntoNoEffects() = runBlocking<Unit> {
        val (store, reference) = prepared()
        db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        val value = generation()
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            sql.execSQL("UPDATE count_command_status SET businessGenerationAtAdmission=? WHERE commandId=?", arrayOf<Any>(value, reference.commandId))
        } }
        val damaged = image(); var rejected = false
        try { store.inspectPrepared(reference) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { rejected = true }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue("Authenticated row membership must reject the changed baseline", rejected)
        assertEquals(damaged, image())
    }

    @Test(timeout = 150_000) fun clearRotatesDatasetAndNeverReportsOldCommandAsUncommitted() = runBlocking<Unit> {
        val (_, reference) = prepared()
        db.clearAllData()
        var reason: String? = null
        try { commands().resolve(reference) } catch (error: IllegalArgumentException) { reason = error.message }
        assertEquals("COMMAND_DATASET_REPLACED", reason)
        assertEquals(0L, sql.query("SELECT count(*) FROM count_command_intents").use { assertTrue(it.moveToFirst()); it.getLong(0) })
    }

    private suspend fun imageWithTemporarySequenceWitness(): Map<String, List<List<JsonElement>>> = db.withTransaction {
        val main = image()
        val temporary = rows("SELECT * FROM temp.sqlite_sequence ORDER BY rowid")
        assertTrue("Actual primary T0 must create its own TEMP journal sequence",
            temporary.any { it.first().jsonArray[1].jsonPrimitive.content == "count_command_journal_events" })
        assertEquals("Unqualified sqlite_sequence really resolves to the TEMP namesake", temporary,
            rows("SELECT * FROM sqlite_sequence ORDER BY rowid"))
        assertNotEquals("The retained image must bind main.sqlite_sequence, not the live TEMP journal", temporary,
            main.getValue("sqlite_sequence"))
        main
    }

    private suspend fun image(): Map<String, List<List<JsonElement>>> = db.withTransaction {
        (CountTrustSchema.BUSINESS_TABLES + CountCommandV20Schema.currentLocalTables + "sqlite_sequence").sorted().associateWith { table ->
            rows("SELECT * FROM main.`$table` ORDER BY rowid")
        }
    }

    private fun rows(query: String): List<List<JsonElement>> = sql.query(query).use { c -> buildList {
        while (c.moveToNext()) add((0 until c.columnCount).map { index -> buildJsonArray {
            add(c.getType(index)); add(when (c.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(index).toRawBits())
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(index))
                Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(android.util.Base64.encodeToString(c.getBlob(index), android.util.Base64.NO_WRAP))
                else -> error("Native type")
            })
        } })
    } }
}
