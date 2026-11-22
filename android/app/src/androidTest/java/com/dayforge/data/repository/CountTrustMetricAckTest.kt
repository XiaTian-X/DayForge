package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Plain fixture is used ONLY as a business-merger differential oracle.
 * It cannot establish formal production wrapper, real prompt submission, retained qualification,
 * fresh-auth counts or latency. The required formal journey is specified in README.md.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustMetricAckTest : NextCoreRequestFixture() {
    private data class Request(val queue: SyncOutboxEntity, val operation: SyncV2Operation,
        val change: SyncV2Change, val device: String)
    private suspend fun request(): Request {
        val row = observation()
        val (_, delivery) = success(row)
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val device = requireNotNull(delivery.access.deviceId)
        return Request(row, operation, NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), device), device)
    }
    private suspend fun adapter(): CountTrustMetricAck {
        check(db.inTransaction())
        val native = CountTrustRows(db.openHelper.writableDatabase)
        val members = linkedMapOf<String, MutableList<CountTrustRows.Row>>()
        for (row in CountTrustSchema.BUSINESS_TABLES.flatMap { native.read(it) })
            for (key in CountTrustMembership.memberships(row).keys) members.getOrPut(key) { arrayListOf() }.add(row)
        return CountTrustMetricAck(db) { key, table, where, args ->
            val expected = members[key].orEmpty()
            val actual = if (where.isEmpty()) expected.map { requireNotNull(native.byIdentity(table, it.identity)) }
                else native.read(table, where, args)
            assertEquals(expected.associate { it.name to it.value }, actual.associate { it.name to it.value })
            actual
        }
    }
    private fun snapshot(id: String): Map<String, List<JsonObject>> {
        val native = CountTrustRows(db.openHelper.writableDatabase)
        return CountTrustSchema.BUSINESS_TABLES.associateWith { table -> native.read(table).map { row ->
            if (table == "sync_entity_state" && row.text("entityType") == "metric_observation" && row.text("entityUuid") == id)
                JsonObject(row.value - "updatedAt") else row.value
        } }
    }
    private class Rollback(val result: Map<String, List<JsonObject>>) : RuntimeException()
    private suspend fun compare(request: Request) {
        val before = db.withTransaction { snapshot(request.change.entityUuid) }
        val fast = try {
            db.withTransaction {
                assertTrue(CountTrustAck.globalCommonHealth(db))
                val adapter = adapter()
                val plan = requireNotNull(adapter.prepare(request.change, request.operation, request.device, request.queue.id, 123456))
                val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
                val boundary = runtime.maintenance(db) { runtime.captureChangeBoundary() }
                val effects = adapter.apply(plan)
                val actual = runtime.maintenance(db) { runtime.pendingChanges(boundary) }
                assertEquals(effects.size, actual.size)
                val native = CountTrustRows(db.openHelper.writableDatabase)
                effects.zip(actual).forEach { (effect, change) ->
                    val layout = native.table(change.table)
                    val columns = CountTrustJournal.COLUMNS.getValue(change.table)
                    fun values(cells: List<CountTrustJournal.Cell>?) = cells?.let {
                        CountTrustRows.Row(layout, layout.columns.map { name -> it[columns.indexOf(name)] }).value
                    }
                    assertEquals(effect.table, change.table)
                    assertEquals(effect.before?.value, values(change.old))
                    assertEquals(effect.after?.value, values(change.new))
                }
                throw Rollback(snapshot(request.change.entityUuid))
            }
        } catch (rollback: Rollback) { rollback.result }
        assertEquals(before, db.withTransaction { snapshot(request.change.entityUuid) })
        db.withTransaction {
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id, request.operation)
            assertEquals(fast, snapshot(request.change.entityUuid))
        }
    }
    private suspend fun compareRejected(request: Request) {
        val before = db.withTransaction { snapshot(request.change.entityUuid) }
        val fast = rejected { db.withTransaction {
            adapter().prepare(request.change, request.operation, request.device, request.queue.id, 123456)
        } }
        val original = rejected { db.withTransaction {
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id, request.operation)
        } }
        assertEquals(original.javaClass, fast.javaClass)
        if (original is NextFactMergeException) assertEquals(original.reason, (fast as NextFactMergeException).reason)
        assertEquals(before, db.withTransaction { snapshot(request.change.entityUuid) })
    }

    @Test fun firstAndKnownObservationMatchAllBusinessRowsAndOrderedNativeEffects() = runBlocking<Unit> {
        val request = request(); compare(request); compare(request)
    }
    @Test fun originalUnitSurvivesLaterDefinitionUnitChange() = runBlocking<Unit> {
        val request = request()
        db.withTransaction { db.metricDao().update(metric.copy(unit = "lb")) }
        compare(request)
        assertEquals("kg", requireNotNull(db.metricLogDao().getLogByUuid(request.change.entityUuid)).unit)
    }
    @Test fun lateAckAfterDeleteDoesNotResurrectObservation() = runBlocking<Unit> {
        val request = request()
        producer().write(local()) {
            db.metricLogDao().delete(requireNotNull(db.metricLogDao().getLogByUuid(request.change.entityUuid)))
        }
        compare(request)
        assertNull(db.metricLogDao().getLogByUuid(request.change.entityUuid))
        assertNotNull(db.syncOutboxDao().getState("metric_observation", request.change.entityUuid))
    }
    @Test fun alteredOriginalSourceAndMismatchedLocalValueHaveOriginalRejections() = runBlocking<Unit> {
        val request = request()
        val altered = JsonObject(request.change.payload + ("unit" to JsonPrimitive("lb")))
        compareRejected(request.copy(change = request.change.copy(payload = altered)))
        db.withTransaction {
            val log = requireNotNull(db.metricLogDao().getLogByUuid(request.change.entityUuid))
            db.metricLogDao().update(log.copy(value = log.value + 1))
        }
        compareRejected(request)
    }
    @Test fun missingParentUsesOriginalShadowOnlyProofWithoutAdapterMutation() = runBlocking<Unit> {
        val request = request()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("DELETE FROM metrics WHERE id=?", arrayOf(metric.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            val before = snapshot(request.change.entityUuid)
            assertNull(adapter().prepare(request.change, request.operation, request.device, request.queue.id, 123456))
            assertEquals(before, snapshot(request.change.entityUuid))
            NextCommonFactStore(db).restoreInTransaction(listOf(request.change), request.device, request.queue.id,
                request.operation, provenParentDeletion = { type, uuid -> type == "metric" && uuid == metric.uuid })
            assertNull(db.metricLogDao().getLogByUuid(request.change.entityUuid))
            assertNotNull(db.syncOutboxDao().getState("metric_observation", request.change.entityUuid))
        }
    }
}
