package com.dayforge.data.repository

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same SQLite image + exact immutable command, reference versus scheduled reference bridge.
 * Compares outcome, original rejection location, attempted main-table writes and every persisted
 * cell including types/absence. This is scheduling regression evidence, NOT independent proof
 * that an incremental replacement equals the original validators (none is installed here).
 */
@RunWith(AndroidJUnit4::class)
class G0OriginalValidatorDifferentialTest : G0DomainFixture() {
    private data class Outcome(val value: String?, val failure: String?, val writes: List<String>, val decrypts: Long, val state: Map<String, String>)
    private var clockWindow: LongRange? = null

    private fun state(): Map<String, String> {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            val escaped = table.replace("\"", "\"\"")
            val rows = sql.query("SELECT * FROM \"$escaped\"").use { c -> buildList {
                add(c.columnNames.toList().toString())
                val values = mutableListOf<String>()
                while (c.moveToNext()) values.add((0 until c.columnCount).joinToString("|") { i ->
                    val clockCell = table == "sync_entity_state" && c.columnNames[i] == "updatedAt" &&
                        shadowClockKey == (c.getString(c.getColumnIndexOrThrow("entityType")) to c.getString(c.getColumnIndexOrThrow("entityUuid"))) && clockWindow != null
                    if (clockCell) {
                        assertEquals(Cursor.FIELD_TYPE_INTEGER, c.getType(i))
                        assertTrue(c.getLong(i) in requireNotNull(clockWindow))
                        assertTrue("Persisted clock must equal the actually bound clock", c.getLong(i) in shadowClockValues)
                        "GENERATED_SHADOW_CLOCK"
                    } else when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> "N"
                        Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                        Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                        Cursor.FIELD_TYPE_BLOB -> "B${digest(c.getBlob(i).toList().toString())}"
                        else -> "S${digest(c.getString(i))}"
                    }
                })
                addAll(values.sorted())
            } }
            digest(rows.toString())
        }
    }

    private suspend fun compare(label: String, accepted: Boolean, action: suspend (scheduled: Boolean) -> Any?): Outcome {
        val image = storage.snapshotClosedDatabase()
        storage.reopen() // Use the instrumented builder on BOTH executions, after the same image.
        val before = state()
        suspend fun execute(scheduled: Boolean): Outcome {
            writes.clear(); shadowClockValues.clear()
            val beforeDecrypts = decrypts.get()
            val started = System.currentTimeMillis()
            var value: String? = null; var failure: String? = null
            try { value = action(scheduled).toString() } catch (error: Exception) {
                val site = error.stackTrace.firstOrNull { it.className.startsWith("com.dayforge.data.repository.") && !it.className.contains("G0") }
                failure = "${error.javaClass.name}:${error.message}:$site"
            }
            clockWindow = started..System.currentTimeMillis()
            shadowClockValues.forEach { assertTrue("Generated clock outside actual execution", it in requireNotNull(clockWindow)) }
            val attempts = writes.toList()
            Log.i("G0Differential", "case=$label scheduled=$scheduled clock_start=$started clock_end=${clockWindow!!.last} shadow_clocks=${shadowClockValues.toList()}")
            return Outcome(value, failure, attempts, decrypts.get() - beforeDecrypts, state())
        }
        val reference = execute(false)
        assertEquals("$label reference acceptance", accepted, reference.failure == null)
        if (accepted) assertTrue("$label accepted operation must perform actual credential decryption", reference.decrypts > 0)
        if (!accepted) assertEquals("$label reference rollback", before, reference.state)
        storage.restoreClosedDatabase(image); storage.reopen(); clockWindow = null
        assertEquals("$label identical starting image", before, state())
        val candidate = execute(true)
        assertEquals("$label outcome / rejection point / attempted writes / entire persisted state", reference, candidate)
        Log.i("G0Differential", "case=$label accepted=$accepted tables=${candidate.state.size} attempted_writes=${candidate.writes.size} state=${digest(candidate.state.toString())}")
        clockWindow = null
        return candidate
    }

    @Test fun deterministicWarmWritePreservesOriginalOriginAndOutboxAtomically() = runBlocking<Unit> {
        register(); val session = local()
        compare("warm-write", true) { scheduled ->
            val body: suspend () -> Unit = {
                db.metricDao().update(metric.copy(name = "Immutable input", updatedAt = millis))
                // Stabilize only fixture-generated UUID/time BEFORE original source capture.
                val row = db.syncOutboxDao().getAll().single()
                db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET operationId=?,createdAt=? WHERE id=?",
                    arrayOf<Any>(id(901), millis, row.id))
            }
            if (scheduled) bridge().write(session, body) else producer().write(session, body)
        }
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_request_origins"))
    }

    @Test fun originalWriteRejectsMutationOfExistingSourceWithIdenticalRollback() = runBlocking<Unit> {
        register(); val old = editMetric("Earlier"); val session = local()
        compare("warm-source-rewrite", false) { scheduled ->
            val body: suspend () -> Unit = {
                db.metricDao().update(metric.copy(name = "Must roll back", updatedAt = millis))
                db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET referenceUuid=? WHERE id=?", arrayOf<Any>(id(902), old.id))
            }
            if (scheduled) bridge().write(session, body) else producer().write(session, body)
        }
    }

    @Test fun actualAckAndExactReplayHaveIdenticalReceiptRetirementAndNoExtraHttp() = runBlocking<Unit> {
        register(); val row = observation(); val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId)); val requests = server.requests.size
        suspend fun accept(scheduled: Boolean) = if (scheduled) bridge().acknowledge(http, delivery) else sender(http).acceptOperation(delivery)
        shadowClockKey = "metric_observation" to row.entityUuid
        compare("ack-commit", true, ::accept)
        assertNull(db.syncOutboxDao().getById(row.id)); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        shadowClockKey = null
        compare("ack-replay", true, ::accept)
        assertEquals(requests, server.requests.size)
    }

    @Test fun missingParentIsNotTreatedAsAuthorizedDeletion() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().delete(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        compare("ack-missing-parent", false) { if (it) bridge().acknowledge(http, delivery) else sender(http).acceptOperation(delivery) }
        assertUnaccepted(row)
    }

    @Test fun coherentlyChangedResponseCannotChangeImmutableFact() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val result = delivery.result.results.single()
        val changed = delivery.copy(result = delivery.result.copy(results = listOf(result.copy(
            entity = JsonObject(requireNotNull(result.entity) + ("value" to JsonPrimitive(99)))))))
        compare("ack-changed-value", false) { if (it) bridge().acknowledge(http, changed) else sender(http).acceptOperation(changed) }
        assertUnaccepted(row)
    }

    @Test fun lateReceiptTriggerCannotRewriteOriginalEvidence() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER g0_late_origin_fault AFTER INSERT ON next_acceptances BEGIN UPDATE next_request_origins SET intentJson='{}' WHERE requestId='${row.operationId}'; END")
        shadowClockKey = "metric_observation" to row.entityUuid
        compare("ack-late-origin", false) { if (it) bridge().acknowledge(http, delivery) else sender(http).acceptOperation(delivery) }
        assertUnaccepted(row)
    }

    @Test fun recreatedOutboxAfterReceiptIsRejectedAsNegativeDependencyViolation() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        sender(http).acceptOperation(delivery)
        db.syncOutboxDao().insert(row)
        compare("ack-recreated-outbox", false) { if (it) bridge().acknowledge(http, delivery) else sender(http).acceptOperation(delivery) }
        assertNotNull(db.syncOutboxDao().getById(row.id))
    }

    @Test fun oldAccountDeliveryIsRejectedBeforeAnyMainTableWrite() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        tokens.saveLoginSession("synthetic-new", "synthetic-new-refresh", "member", id(999), false)
        val outcome = compare("ack-old-account", false) { if (it) bridge().acknowledge(http, delivery) else sender(http).acceptOperation(delivery) }
        assertTrue(outcome.writes.isEmpty()); assertUnaccepted(row)
    }
}
