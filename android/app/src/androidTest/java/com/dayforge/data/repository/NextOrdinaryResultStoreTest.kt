package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary result closure through actual file Room, DataStore and loopback HTTP. */
@RunWith(AndroidJUnit4::class)
class NextOrdinaryResultStoreTest : NextCoreRequestFixture() {
    private fun tombstone(input: MaterialSocketServer.Input, body: JsonObject, revision: Long = 2): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        val op = wireOperation(input)
        val result = buildJsonObject {
            put("operation_id", op.getValue("operation_id")); put("entity_type", op.getValue("entity_type"))
            put("entity_uuid", op.getValue("entity_uuid")); put("status", "applied"); put("revision", revision)
            put("entity", JsonObject(body + mapOf("revision" to JsonPrimitive(revision),
                "updated_at" to JsonPrimitive("2026-10-06T00:00:02.123456Z"),
                "deleted_at" to JsonPrimitive("2026-10-06T00:00:02.123456Z"))))
        }
        return MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(result))) }.toString().toByteArray())
    }

    private suspend fun createLink(): SyncOutboxEntity {
        producer().write(local()) {
            db.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, uuid = id(600), coefficient = 0.5))
        }
        return db.syncOutboxDao().getAll().last()
    }

    private suspend fun remove(type: String, uuid: String): SyncOutboxEntity {
        producer().write(local()) {
            when (type) {
                "plan_node" -> habits().deleteHabit(requireNotNull(db.habitDao().getHabitByUuid(uuid)))
                "metric" -> metrics().deleteMetric(requireNotNull(db.metricDao().getMetricByUuid(uuid)))
                "metric_observation" -> db.metricLogDao().delete(requireNotNull(db.metricLogDao().getLogByUuid(uuid)))
                "activity_metric_link" -> metrics().unlinkHabit(requireNotNull(db.habitMetricLinkDao().getLinkByUuid(uuid)).id)
                else -> error("Not deletable")
            }
        }
        return db.syncOutboxDao().getAll().last { it.action == "delete" && it.entityUuid == uuid }
    }

    @Test fun allFourDeleteKindsCommitFullTombstoneAndColdReplayWithoutHttpOrResurrection() = runBlocking<Unit> {
        register()
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity") || wireOperation(input)["action"] != JsonPrimitive("delete")) successReply(input)
            else {
                val op = wireOperation(input)
                val state = runBlocking { db.syncOutboxDao().getState(op.getValue("entity_type").jsonPrimitive.content,
                    op.getValue("entity_uuid").jsonPrimitive.content) }!!
                tombstone(input, Json.parseToJsonElement(state.payloadJson!!).jsonObject)
            }
        }
        val store = sender(http)
        producer().write(local()) { habits().updateHabit(habit.copy(description = "Published")) }
        val plan = db.syncOutboxDao().getAll().single()
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), plan.operationId))
        val metricIntent = editMetric("Published metric")
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), metricIntent.operationId))
        val observation = observation(); val link = createLink()
        for (row in listOf(observation, link))
            assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), row.operationId))
        val deletes = mutableListOf<SyncOutboxEntity>()
        for ((type, uuid) in listOf("metric_observation" to observation.entityUuid, "activity_metric_link" to link.entityUuid,
            "plan_node" to habit.uuid, "metric" to metric.uuid)) {
            val row = remove(type, uuid); deletes += row
            val origin = originalIntent(row)
            assertEquals(JsonPrimitive(1), Json.parseToJsonElement(origin.intentJson).jsonObject["base_revision"])
            assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), row.operationId))
            val shadow = db.syncOutboxDao().getState(type, uuid)!!
            assertTrue(shadow.deleted); assertEquals(2L, shadow.revision)
            assertEquals(syncPayloadHash(shadow.payloadJson!!), shadow.payloadHash)
            assertEquals("2026-10-06T00:00:02.123456Z", Json.parseToJsonElement(shadow.payloadJson).jsonObject["deleted_at"]!!.jsonPrimitive.content)
            assertEquals(origin, originalIntent(row)); assertNull(db.syncOutboxDao().getById(row.id))
        }
        val requests = server.requests.size
        val bytes = deletes.associate { it.operationId to transmission(NEXT_OPERATION, it.operationId).wireBytes.copyOf() }
        storage.reopen()
        for (row in deletes) {
            assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
            assertArrayEquals(bytes.getValue(row.operationId), transmission(NEXT_OPERATION, row.operationId).wireBytes)
        }
        assertEquals(requests, server.requests.size); assertEquals(8, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
        assertNull(db.habitDao().getHabitByUuid(habit.uuid)); assertNull(db.metricDao().getMetricByUuid(metric.uuid))
        assertNull(db.metricLogDao().getLogByUuid(observation.entityUuid)); assertNull(db.habitMetricLinkDao().getLinkByUuid(link.entityUuid))
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun pendingParentDeletesPermitShadowOnlyFactAndLinkAcceptanceThenColdReplays() = runBlocking<Unit> {
        val check = completion(); val observation = observation(); val link = createLink()
        register(); val (http, server) = channel { successReply(it) }
        val store = sender(http)
        val deliveries = listOf(check, observation, link).map { requireNotNull(store.sendOperation(access(), it.operationId)) }
        remove("plan_node", habit.uuid); remove("metric", metric.uuid)
        val originalDeletes = db.syncOutboxDao().getAll().filter { it.action == "delete" }
        assertTrue(originalDeletes.isNotEmpty())
        for (delivery in deliveries) assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertNull(db.habitDao().getHabitByUuid(habit.uuid)); assertNull(db.metricDao().getMetricByUuid(metric.uuid))
        assertEquals(0, count("completions")); assertEquals(0, count("metric_logs")); assertEquals(0, count("habit_metric_links"))
        for ((type, uuid) in listOf("activity_event" to check.entityUuid, "metric_observation" to observation.entityUuid,
            "activity_metric_link" to link.entityUuid)) assertFalse(db.syncOutboxDao().getState(type, uuid)!!.deleted)
        originalDeletes.forEach { assertEquals(it, db.syncOutboxDao().getById(it.id)); assertNotNull(originalIntent(it)) }
        storage.reopen(); val requests = server.requests.size
        for (row in listOf(check, observation, link))
            assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(requests, server.requests.size); assertEquals(0L, tokens.syncCursor.first())
        originalDeletes.forEach { assertEquals(it, db.syncOutboxDao().getById(it.id)) }
    }

    @Test fun arbitraryParentAbsenceAndOldUnboundDeleteNeverAuthorizeFactAcceptance() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().delete(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertTrue(rejected { store.acceptOperation(delivery) } is IllegalArgumentException)
        assertUnaccepted(row); assertNull(db.syncOutboxDao().getState("metric_observation", row.entityUuid))
        val old = SyncOutboxEntity(operationId = id(601), recordType = "metric", entityUuid = metric.uuid,
            wireEntityUuid = metric.uuid, action = "delete")
        val oldId = db.syncOutboxDao().insert(old)
        assertEquals(NextRequestException.Reason.OLD_INTENT,
            (rejected { store.acceptOperation(delivery) } as NextRequestException).reason)
        assertUnaccepted(row); assertEquals(old.copy(id = oldId), db.syncOutboxDao().getById(oldId))
        assertEquals(0, count("next_acceptances")); assertEquals(0, count("sync_entity_state"))
    }

    @Test fun acceptedParentDeletionProofSurvivesColdReopenWithoutRecreatingParent() = runBlocking<Unit> {
        val seed = editMetric("Published"); register()
        var parent: JsonObject? = null
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity") || wireOperation(input)["action"] != JsonPrimitive("delete")) successReply(input)
            else tombstone(input, requireNotNull(parent))
        }
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), seed.operationId))
        parent = Json.parseToJsonElement(db.syncOutboxDao().getState("metric", metric.uuid)!!.payloadJson!!).jsonObject
        val fact = observation(); val delivery = store.sendOperation(access(), fact.operationId)!!
        val deleted = remove("metric", metric.uuid)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), deleted.operationId))
        storage.reopen()
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, deleted.operationId)!!
        val good = Json.parseToJsonElement(receipt.resultJson).jsonObject
        val broken = JsonObject(good + ("entity" to JsonObject(good.getValue("entity").jsonObject + ("deleted_at" to JsonNull)))).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(broken, nextRequestHash(broken.toByteArray()), deleted.operationId))
        assertNotNull(rejected { sender(http).acceptOperation(delivery) }); assertUnaccepted(fact)
        assertNull(db.syncOutboxDao().getState("metric_observation", fact.entityUuid))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(receipt.resultJson, receipt.resultHash, deleted.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertNull(db.metricDao().getMetricByUuid(metric.uuid)); assertNull(db.metricLogDao().getLogByUuid(fact.entityUuid))
        assertTrue(db.syncOutboxDao().getState("metric", metric.uuid)!!.deleted)
        assertFalse(db.syncOutboxDao().getState("metric_observation", fact.entityUuid)!!.deleted)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, deleted.operationId))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, fact.operationId)); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun lateParentDeletionProofMutationRollsBackShadowOnlyAcceptance() = runBlocking<Unit> {
        val row = observation(); val (store, delivery) = success(row)
        val deleted = remove("metric", metric.uuid)
        val original = originalIntent(deleted)
        val sql = db.openHelper.writableDatabase
        for (fault in listOf("UPDATE next_request_origins SET intentJson='{}' WHERE requestId='${deleted.operationId}'",
            "UPDATE sync_outbox SET referenceUuid='${id(620)}' WHERE id=${deleted.id}")) {
            sql.execSQL("CREATE TRIGGER parent_proof_fault AFTER INSERT ON next_acceptances BEGIN $fault; END")
            assertNotNull(rejected { store.acceptOperation(delivery) })
            sql.execSQL("DROP TRIGGER parent_proof_fault")
            assertUnaccepted(row); assertEquals(deleted, db.syncOutboxDao().getById(deleted.id))
            assertEquals(original, originalIntent(deleted)); assertEquals(0, count("sync_entity_state"))
            assertEquals(0, count("metric_logs")); assertNull(db.metricDao().getMetricByUuid(metric.uuid))
        }
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertNull(db.metricDao().getMetricByUuid(metric.uuid)); assertEquals(deleted, db.syncOutboxDao().getById(deleted.id))
    }

    @Test fun coldFactReceiptReplaysIgnoreLaterParentEditsButRejectCoherentlyChangedImmutableValues() = runBlocking<Unit> {
        val row = observation(); register(); val (http, server) = channel { successReply(it) }
        val store = sender(http); val delivery = requireNotNull(store.sendOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!
        producer().write(local()) { metrics().updateMetric(db.metricDao().getMetricById(metric.id)!!.copy(unit = "g")) }
        val suffix = db.syncOutboxDao().getAll().single()
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals("kg", db.metricLogDao().getLogByUuid(row.entityUuid)!!.unit)
        val good = Json.parseToJsonElement(receipt.resultJson).jsonObject
        val requests = server.requests.size
        for ((field, value) in mapOf("value" to JsonPrimitive(99), "unit" to JsonPrimitive("g"), "note" to JsonPrimitive("tampered"))) {
            val broken = JsonObject(good + ("entity" to JsonObject(good.getValue("entity").jsonObject + (field to value)))).toString()
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
                arrayOf<Any>(broken, nextRequestHash(broken.toByteArray()), row.operationId))
            storage.reopen()
            assertNotNull(rejected { sender(http).sendAndAcceptOperation(access(), row.operationId) })
            assertEquals(requests, server.requests.size)
            assertEquals(broken, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!.resultJson)
            assertEquals(suffix, db.syncOutboxDao().getById(suffix.id)); assertEquals(21.125, db.metricLogDao().getLogByUuid(row.entityUuid)!!.value, 0.0)
            assertEquals(0L, tokens.syncCursor.first())
        }
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(receipt.resultJson, receipt.resultHash, row.operationId))
        storage.reopen(); assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(requests, server.requests.size)
    }

    @Test fun ordinaryUndoRemainsAnImmutableUpsertAndColdReplayDoesNotRestoreCompletion() = runBlocking<Unit> {
        val row = completion(HabitType.CHECK_IN); register(); val (http, server) = channel { successReply(it) }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        producer().write(local()) { db.completionDao().delete(requireNotNull(db.completionDao().getCompletionByUuid(row.entityUuid))) }
        val undo = db.syncOutboxDao().getAll().single()
        val intent = Json.parseToJsonElement(originalIntent(undo).intentJson).jsonObject
        assertEquals(JsonPrimitive("upsert"), intent["action"])
        assertNotEquals(JsonPrimitive(row.entityUuid), intent["entity_uuid"])
        assertEquals(JsonPrimitive(row.entityUuid), intent.getValue("payload").jsonObject["reverts_event_uuid"])
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), undo.operationId))
        val requests = server.requests.size
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), undo.operationId))
        assertEquals(requests, server.requests.size); assertEquals(0, count("completions")); assertEquals(2, count("next_acceptances"))
        assertFalse(db.syncOutboxDao().getState("activity_event", row.entityUuid)!!.deleted)
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun malformedAndRegressingTombstonesNeverConsumeDeleteOrChangeAuthoritativeShadow() = runBlocking<Unit> {
        val seed = editMetric("Published"); val (store, delivered) = success(seed)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivered))
        val old = db.syncOutboxDao().getState("metric", metric.uuid)!!
        val row = remove("metric", metric.uuid)
        val body = Json.parseToJsonElement(old.payloadJson!!).jsonObject
        val (http, _) = channel { tombstone(it, body) }
        val delivery = sender(http).sendOperation(access(), row.operationId)!!
        val result = delivery.result.results.single()
        val good = result.entity!!
        val invalid = listOf(JsonObject(good - "unit"), JsonObject(good + ("deleted_at" to JsonNull)),
            JsonObject(good + ("deleted_at" to JsonPrimitive("invalid"))), JsonObject(good + ("public_id" to JsonPrimitive(id(602)))),
            JsonObject(good + ("decimal_places" to JsonPrimitive(1.5))), JsonObject(good + ("revision" to JsonPrimitive(1))))
        for (broken in invalid) {
            assertNotNull(rejected { sender(http).acceptOperation(delivery.copy(result = NextSyncPushResponse(listOf(result.copy(entity = broken))))) })
            assertUnaccepted(row); assertEquals(old, db.syncOutboxDao().getState("metric", metric.uuid))
        }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!
        val goodReceipt = Json.parseToJsonElement(receipt.resultJson).jsonObject
        val badReceipt = JsonObject(goodReceipt + ("entity" to JsonObject(goodReceipt.getValue("entity").jsonObject + ("deleted_at" to JsonNull)))).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE requestId=?",
            arrayOf<Any>(badReceipt, nextRequestHash(badReceipt.toByteArray()), row.operationId))
        storage.reopen()
        assertNotNull(rejected { sender(http).sendAndAcceptOperation(access(), row.operationId) })
        assertTrue(db.syncOutboxDao().getState("metric", metric.uuid)!!.deleted); assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun deleteConflictsPreserveFrozenBaseAndOriginalReplayBytes() = runBlocking<Unit> {
        val row = remove("metric", metric.uuid); register()
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val op = wireOperation(input)
                MaterialSocketServer.Reply(buildJsonObject { put("results", JsonArray(listOf(buildJsonObject {
                    put("operation_id", op.getValue("operation_id")); put("entity_type", op.getValue("entity_type"))
                    put("entity_uuid", op.getValue("entity_uuid")); put("status", "conflict"); put("error_code", "REVISION_CONFLICT")
                }))) }.toString().toByteArray())
            }
        }
        val store = sender(http); val origin = originalIntent(row)
        assertTrue(rejected { store.sendAndAcceptOperation(access(), row.operationId) } is IllegalArgumentException)
        val wire = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        storage.reopen()
        assertTrue(rejected { sender(http).sendAndAcceptOperation(access(), row.operationId) } is IllegalArgumentException)
        server.requests.filter { it.path.endsWith("/push") }.also { assertEquals(2, it.size) }.forEach { assertArrayEquals(wire, it.body) }
        assertUnaccepted(row); assertEquals(origin, originalIntent(row)); assertEquals(0, count("next_structural_supersessions"))
    }

    @Test fun freshDeleteCannotJumpSameEntityPredecessorOrInventRebasedDelete() = runBlocking<Unit> {
        val first = editMetric("Before delete"); val row = remove("metric", metric.uuid); register()
        val (http, server) = channel { successReply(it) }
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), row.operationId) } as NextRequestException).reason)
        assertTrue(server.requests.all { it.path.endsWith("/identity") }); assertEquals(0, count("next_transmissions"))
        assertUnaccepted(row); assertUnaccepted(first); assertEquals(0, count("next_structural_supersessions"))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        assertNull(db.metricDao().getMetricByUuid(metric.uuid))
        assertNull(Json.parseToJsonElement(originalIntent(row).intentJson).jsonObject["base_revision"]?.takeUnless { it == JsonNull })
        assertEquals(0, count("next_structural_supersessions")); assertUnaccepted(row)
    }

    @Test fun deleteAckFailuresRollbackShadowReceiptAndQueueIncludingFinalCommit() = runBlocking<Unit> {
        val seed = editMetric("Published"); val (store, delivered) = success(seed)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivered))
        val old = db.syncOutboxDao().getState("metric", metric.uuid)!!
        val row = remove("metric", metric.uuid)
        val (http, _) = channel { tombstone(it, Json.parseToJsonElement(old.payloadJson!!).jsonObject) }
        val delivery = sender(http).sendOperation(access(), row.operationId)!!
        val sql = db.openHelper.writableDatabase
        for (fault in listOf("BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(ABORT,'receipt'); END",
            "BEFORE INSERT ON next_acceptances BEGIN SELECT RAISE(IGNORE); END",
            "AFTER DELETE ON sync_outbox BEGIN UPDATE next_acceptances SET resultHash='broken' WHERE requestId='${row.operationId}'; END")) {
            sql.execSQL("CREATE TRIGGER ordinary_fault $fault")
            assertNotNull(rejected { sender(http).acceptOperation(delivery) })
            sql.execSQL("DROP TRIGGER ordinary_fault")
            assertUnaccepted(row); assertEquals(old, db.syncOutboxDao().getState("metric", metric.uuid))
        }
        sql.execSQL("CREATE TABLE ordinary_commit_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE ordinary_commit_child(id INTEGER REFERENCES ordinary_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER ordinary_commit AFTER INSERT ON next_acceptances BEGIN INSERT INTO ordinary_commit_child VALUES(1); END")
        assertNotNull(rejected { sender(http).acceptOperation(delivery) })
        storage.reopen()
        assertUnaccepted(row); assertEquals(old, db.syncOutboxDao().getState("metric", metric.uuid)); assertEquals(0, count("ordinary_commit_child"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER ordinary_commit")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        storage.reopen(); assertTrue(db.syncOutboxDao().getState("metric", metric.uuid)!!.deleted)
        assertNull(db.metricDao().getMetricByUuid(metric.uuid))
    }

    @Test fun staleAccountAndCancelledDeleteAcceptanceLeaveOriginalWorkIntact() = runBlocking<Unit> {
        val seed = editMetric("Published"); val (store, delivered) = success(seed)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivered))
        val old = db.syncOutboxDao().getState("metric", metric.uuid)!!
        val row = remove("metric", metric.uuid)
        val (http, _) = channel { tombstone(it, Json.parseToJsonElement(old.payloadJson!!).jsonObject) }
        val delivery = sender(http).sendOperation(access(), row.operationId)!!
        sessions.exclusive {
            val job = launch(start = CoroutineStart.UNDISPATCHED) { sender(http).acceptOperation(delivery) }
            job.cancelAndJoin()
        }
        assertUnaccepted(row); assertEquals(old, db.syncOutboxDao().getState("metric", metric.uuid))
        tokens.saveLoginSession("synthetic-second", "synthetic-refresh", "other", id(603), false)
        assertEquals(NextRequestException.Reason.STALE_ACCESS,
            (rejected { sender(http).acceptOperation(delivery) } as NextRequestException).reason)
        assertUnaccepted(row); assertEquals(old, db.syncOutboxDao().getState("metric", metric.uuid))
    }
}
