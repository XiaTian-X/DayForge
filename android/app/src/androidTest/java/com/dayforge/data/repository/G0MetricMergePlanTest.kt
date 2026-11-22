package com.dayforge.data.repository

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent pure decisions versus full original structure merge on the same physical image.
 * The test applier is NOT a production/runtime capability and does NOT bypass ACK validation.
 */
@RunWith(AndroidJUnit4::class)
class G0MetricMergePlanTest : G0DomainFixture() {
    @Test(timeout = 180_000) fun independentMetricDecisionsMatchOriginalPersistedEffects() = runBlocking<Unit> {
        register(); val row = editMetric("Sent metric")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val originalChange = NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), delivery.access.deviceId!!)
        val seed = storage.snapshotClosedDatabase()
        val cases = listOf("first", "remote-rename", "successor", "dead-successor", "same", "older", "newer", "diverged", "deleted", "missing",
            "inconsistent-shadow-created",
            "invalid-old-payload", "created-changed", "uninitialized", "name-conflict")
        for (case in cases) {
            storage.restoreClosedDatabase(seed); storage.reopen()
            var change = originalChange
            suspend fun shadow(revision: Long = 1, deleted: Boolean = false, alignCreated: Boolean = true) {
                val body = JsonObject(originalChange.payload + ("revision" to JsonPrimitive(revision)))
                if (alignCreated) db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    val current = requireNotNull(db.metricDao().getMetricById(metric.id))
                    db.metricDao().update(current.copy(createdAt = java.time.Instant.parse(body.getValue("created_at").jsonPrimitive.content).toEpochMilli()))
                    db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                }
                db.syncOutboxDao().upsertState(SyncEntityStateEntity("metric", metric.uuid, revision, deleted,
                    body.toString(), syncPayloadHash(body.toString()), millis))
            }
            when (case) {
                "remote-rename" -> change = change.copy(payload = JsonObject(change.payload + ("name" to JsonPrimitive("Remote rename"))))
                "successor" -> editMetric("Later local edit")
                "dead-successor" -> {
                    val later = editMetric("Later dead-letter edit")
                    db.syncOutboxDao().markDeadLetter(later.id, "INVALID_PAYLOAD", "synthetic rejection", millis)
                }
                "inconsistent-shadow-created" -> shadow(alignCreated = false)
                "same" -> shadow()
                "older" -> shadow(2)
                "newer" -> { shadow(); change = change.copy(revision = 2, payload = JsonObject(change.payload + ("revision" to JsonPrimitive(2)))) }
                "diverged" -> { shadow(); change = change.copy(payload = JsonObject(change.payload + ("name" to JsonPrimitive("Other reply")))) }
                "deleted" -> shadow(deleted = true)
                "missing" -> { shadow(); db.openHelper.writableDatabase.execSQL("DELETE FROM metrics WHERE id=?", arrayOf<Any>(metric.id)) }
                "invalid-old-payload" -> { shadow(2); change = change.copy(payload = JsonObject(change.payload - "unit")) }
                "created-changed" -> { shadow(); change = change.copy(payload = JsonObject(change.payload + ("created_at" to JsonPrimitive("2026-10-06T00:00:01Z")))) }
                "uninitialized" -> db.metricDao().update(db.metricDao().getMetricById(metric.id)!!.copy(appearance = null))
                "name-conflict" -> {
                    db.metricDao().insert(metric.copy(id = 0, uuid = id(881), name = "Occupied"))
                    change = change.copy(payload = JsonObject(change.payload + ("name" to JsonPrimitive("Occupied"))))
                }
            }
            val input = capture(change, row.id)
            val image = storage.snapshotClosedDatabase(); storage.reopen()
            val before = state(null)
            val started = System.currentTimeMillis()
            val reference = outcome { db.withTransaction { NextStructureStore(db).restoreInTransaction(listOf(change), row.id) } }
            assertEquals("$case expected reference acceptance", case in setOf("first", "remote-rename", "successor", "dead-successor", "same", "older", "newer"), reference == null)
            val expectedReason = when (case) {
                "diverged" -> "structure:STRUCTURE_DIVERGED"
                "deleted" -> "structure:ENTITY_DELETED"
                "missing" -> "structure:INVALID_LOCAL_STATE"
                "uninitialized" -> "structure:UNINITIALIZED"
                else -> null
            }
            if (expectedReason != null) assertEquals("$case exact rejection", expectedReason, reference)
            val expected = state(if (reference == null) started..System.currentTimeMillis() else null)
            if (reference != null) assertEquals("$case original rollback", before, expected)
            storage.restoreClosedDatabase(image)
            // No live Room connection exists while the pure plan is computed.
            var plan: G0MetricMergePlan.Output? = null
            val candidateStarted = System.currentTimeMillis()
            val candidate = outcome {
                plan = G0MetricMergePlan.prepare(change, input, candidateStarted)
                assertEquals("Frozen input and clock must be deterministic", plan, G0MetricMergePlan.prepare(change, input, candidateStarted))
            }
            storage.reopen()
            assertEquals("$case acceptance/rejection", reference, candidate)
            if (candidate == null) db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                requireNotNull(plan).metric?.let { db.metricDao().upsert(it) }
                requireNotNull(plan).shadow?.let { db.syncOutboxDao().upsertState(it) }
                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            }
            assertEquals("$case all persistent tables", expected,
                state(if (candidate == null) candidateStarted..System.currentTimeMillis() else null))
            Log.i("G0MetricPlan", "case=$case accepted=${candidate == null} full_state_equal=true")
        }
    }

    @Test fun preparedDependenciesRejectNamePhantomsAndSuccessorsButAllowUnrelatedChanges() = runBlocking<Unit> {
        register(); val row = editMetric("Prepared metric")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val base = NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), delivery.access.deviceId!!)
        val change = base.copy(payload = JsonObject(base.payload + ("name" to JsonPrimitive("Proposed name"))))
        val input = capture(change, row.id)
        val image = storage.snapshotClosedDatabase()
        val prepared = G0MetricMergePlan.freeze(change, input, System.currentTimeMillis())
        for (case in listOf("unrelated", "name-owner", "successor")) {
            storage.restoreClosedDatabase(image); storage.reopen()
            when (case) {
                "unrelated" -> db.metricDao().insert(metric.copy(id = 0, uuid = id(882), name = "Unrelated"))
                "name-owner" -> db.metricDao().insert(metric.copy(id = 0, uuid = id(883), name = "Proposed name"))
                "successor" -> editMetric("Changed after prepare")
            }
            val before = state(null)
            val failure = outcome { prepared.revalidate(capture(change, row.id)) }
            assertEquals("$case dependency predicate", case == "unrelated", failure == null)
            assertEquals("Revalidation never writes", before, state(null))
            Log.i("G0MetricPlan", "dependency=$case accepted=${failure == null} no_writes=true")
        }
    }

    @Test fun unrelatedDuplicateNamesCannotBecomeAColdBaseline() = runBlocking<Unit> {
        register(); val row = editMetric("Target")
        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val change = NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), delivery.access.deviceId!!)
        db.metricDao().insert(metric.copy(id = 0, uuid = id(884), name = "Duplicate elsewhere"))
        db.metricDao().insert(metric.copy(id = 0, uuid = id(885), name = "Duplicate elsewhere"))
        val before = state(null)
        assertNotNull(outcome { db.withTransaction { NextStructureStore(db).restoreInTransaction(listOf(change), row.id) } })
        assertNotNull(outcome { capture(change, row.id) })
        assertEquals(before, state(null))
    }

    private suspend fun capture(change: SyncV2Change, queueId: Long): G0MetricMergePlan.Input = db.withTransaction {
        val plans = db.habitDao().getAllHabitsOnce()
        val metrics = db.metricDao().getAllMetricsOnce()
        check(plans.map { it.uuid }.distinct().size == plans.size)
        check(metrics.map { it.uuid }.distinct().size == metrics.size)
        check(plans.map { it.name }.distinct().size == plans.size)
        check(metrics.map { it.name }.distinct().size == metrics.size)
        val byId = plans.associateBy { it.uuid }
        for (child in plans) child.parentHabitId?.let { id ->
            val parent = byId[id]
            check(child.habitType != HabitType.GOAL && parent != null && parent.uuid != child.uuid &&
                parent.habitType == HabitType.GOAL && parent.parentHabitId == null &&
                db.syncOutboxDao().getState("plan_node", id)?.deleted != true)
        }
        val pending = db.syncOutboxDao().getAll() + db.syncOutboxDao().getDeadLetters()
        val conflicts = db.syncConflictDao().getUnresolved()
        G0MetricMergePlan.Input(metrics.singleOrNull { it.uuid == change.entityUuid },
            db.syncOutboxDao().getState("metric", change.entityUuid),
            pending.any { it.id != queueId && it.recordType == "metric" &&
                (it.entityUuid == change.entityUuid || it.wireEntityUuid == change.entityUuid) },
            conflicts.any { it.recordType == "metric" && (it.localEntityUuid == change.entityUuid || it.wireEntityUuid == change.entityUuid) },
            metrics.groupBy { it.name }.mapValues { (_, values) -> values.map { it.uuid }.toSet() })
    }

    private suspend fun outcome(block: suspend () -> Unit): String? = try { block(); null } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (error is NextStructureMergeException) "structure:${error.reason}" else error.javaClass.name
    }

    private fun state(window: LongRange?): Map<String, String> {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            val rows = sql.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\"").use { c -> buildList {
                add(c.columnNames.toList().toString())
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i ->
                    if (table == "sync_entity_state" && c.columnNames[i] == "updatedAt" && window != null &&
                        c.getString(c.getColumnIndexOrThrow("entityUuid")) == metric.uuid && c.getLong(i) != millis) {
                        assertTrue(c.getLong(i) in window); "GENERATED_CLOCK"
                    } else when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> "N"
                        Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                        Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                        Cursor.FIELD_TYPE_BLOB -> "B${digest(c.getBlob(i).toList().toString())}"
                        else -> "S${digest(c.getString(i))}"
                    }
                })
            } }
            digest(rows.sorted().toString())
        }
    }
}
