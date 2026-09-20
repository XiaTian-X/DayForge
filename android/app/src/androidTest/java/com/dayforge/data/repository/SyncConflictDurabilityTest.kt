package com.dayforge.data.repository

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Conflict expectations come from the sync contract, with actual merger and disk state. */
@RunWith(AndroidJUnit4::class)
class SyncConflictDurabilityTest : SyncPersistenceFixture() {
    private val unrelatedUuid = UUID.randomUUID().toString()
    private val canonical = buildJsonObject { put("node_kind", "goal"); put("title", "Server title") }

    private fun result(request: JsonObject, status: String, fields: JsonObject = buildJsonObject {}): String =
        buildJsonObject {
            put("results", buildJsonArray {
                request.getValue("operations").jsonArray.forEach { value ->
                    val op = value.jsonObject
                    add(JsonObject(mapOf("operation_id" to op.getValue("operation_id"),
                        "entity_type" to op.getValue("entity_type"), "entity_uuid" to op.getValue("entity_uuid"),
                        "status" to JsonPrimitive(status)) + fields))
                }
            })
        }.toString()

    private suspend fun prepareConflict(tombstone: Boolean = false): HabitEntity {
        val uuid = UUID.randomUUID().toString()
        tokens.requireSyncBootstrap()
        onBootstrap = { snapshot(listOf(change(0, uuid, "Base title")), 0) }
        repository.sync()
        val habit = database.habitDao().getHabitByUuid(uuid)!!
        database.habitDao().update(habit.copy(name = "Sent snapshot"))
        onPush = { request ->
            // A genuine local write while Retrofit is waiting for its response, including its real outbox trigger.
            runBlocking { database.habitDao().update(habit.copy(name = "Newest local edit")) }
            result(request, "conflict", buildJsonObject {
                put("revision", 2)
                put("entity", if (tombstone) JsonObject(canonical + ("deleted_at" to JsonPrimitive("2026-09-20T00:00:00Z"))) else canonical)
                put("error_code", "REVISION_CONFLICT")
                put("message", "title changed")
                put("conflict_kind", "overlapping_fields")
                put("conflicting_fields", buildJsonArray { add("title") })
            })
        }
        onPull = { cursor -> if (cursor == 0L) page(listOf(change(7, unrelatedUuid, "Unrelated server goal")), 7) else page(emptyList(), cursor) }
        return habit
    }

    private suspend fun requireAttention() {
        val failure = runCatching { repository.sync() }.exceptionOrNull()
        assertTrue("Expected an actionable sync result, got $failure", failure is SyncRequiresAttentionException)
    }

    private suspend fun preserveConflict(tombstone: Boolean = false): HabitEntity {
        val habit = prepareConflict(tombstone)
        requireAttention()
        reopen()
        onPush = { acknowledge(it) }
        return habit
    }

    private fun assertSqlFailure(error: Throwable?) {
        val causes = generateSequence(error) { it.cause }.toList()
        assertTrue("Expected injected SQLite failure, got $error",
            causes.any { it is SQLiteException && it.message.orEmpty().contains("injected failure") })
    }

    private fun trigger(name: String, event: String, table: String) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $name BEFORE $event ON $table BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
    }

    private fun resolution(id: Long): Pair<String, String?> = database.openHelper.readableDatabase
        .query("SELECT status, resolution, resolvedAt FROM sync_conflicts WHERE id = ?", arrayOf(id)).use {
            assertTrue(it.moveToFirst())
            if (it.getString(0) == "resolved") assertTrue(it.getLong(2) > 0)
            it.getString(0) to if (it.isNull(1)) null else it.getString(1)
        }

    private suspend fun rejectGoal(): HabitEntity {
        val habit = insertGoal("Rejected local goal")
        onPush = { result(it, "rejected", buildJsonObject {
            put("error_code", "INVALID_PAYLOAD"); put("message", "invalid goal")
        }) }
        onPull = { cursor -> if (cursor == 0L) page(listOf(change(7, unrelatedUuid, "Unrelated server goal")), 7) else page(emptyList(), cursor) }
        requireAttention()
        reopen()
        return habit
    }

    @Test fun conflict_persists_newest_intent_all_versions_and_continues_unrelated_pull() = runBlocking {
        val habit = preserveConflict()
        val conflict = database.syncConflictDao().getUnresolved().single()
        val sent = operation(pushes.single())
        assertEquals("Sent snapshot", sent.getValue("payload").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("Newest local edit", json.parseToJsonElement(conflict.localPayloadJson).jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("Base title", json.parseToJsonElement(conflict.basePayloadJson!!).jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(canonical, json.parseToJsonElement(conflict.serverPayloadJson))
        assertEquals(sent.getValue("operation_id").jsonPrimitive.content, conflict.operationId)
        assertEquals(habit.uuid, conflict.localEntityUuid)
        assertEquals(habit.uuid, conflict.wireEntityUuid)
        assertEquals("habit", conflict.recordType)
        assertEquals("plan_node", conflict.entityType)
        assertEquals("upsert", conflict.action)
        // The actual habits trigger carries its type for later delete ordering.
        assertEquals("GOAL", conflict.referenceUuid)
        assertEquals(1L, conflict.baseRevision)
        assertEquals(2L, conflict.serverRevision)
        assertEquals("[\"title\"]", conflict.conflictingFieldsJson)
        assertEquals("overlapping_fields", conflict.conflictKind)
        assertEquals("REVISION_CONFLICT", conflict.errorCode)
        assertEquals("title changed", conflict.message)
        assertEquals("Server title", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals("Unrelated server goal", database.habitDao().getHabitByUuid(unrelatedUuid)!!.name)
        assertEquals(7L, tokens.syncCursor.first())
        assertEquals(0, database.syncOutboxDao().count())
        assertTrue(repository.hasPendingChanges())
        requireAttention()
        reopen()
        assertEquals(1, pushes.size)
        assertEquals(conflict, database.syncConflictDao().getUnresolved().single())
    }

    @Test fun choosing_local_rebases_latest_intent_with_new_operation_and_rejects_double_resolution() = runBlocking {
        val habit = preserveConflict()
        val conflict = database.syncConflictDao().getUnresolved().single()
        repository.resolveConflictUseLocal(conflict.id)
        reopen()
        val pending = database.syncOutboxDao().getAll().single()
        assertNotEquals(conflict.operationId, pending.operationId)
        assertEquals(2L, pending.baseRevision)
        assertEquals(conflict.serverPayloadJson, pending.basePayloadJson)
        assertEquals(conflict.localPayloadJson, pending.payloadJson)
        assertNotNull(pending.attemptedAt)
        assertEquals("Newest local edit", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(2L, database.syncOutboxDao().getState("plan_node", habit.uuid)!!.revision)
        assertEquals("resolved" to "local", resolution(conflict.id))
        assertTrue(runCatching { repository.resolveConflictUseLocal(conflict.id) }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(pending), database.syncOutboxDao().getAll())
        repository.sync()
        val sent = operation(pushes.last())
        assertEquals(pending.operationId, sent.getValue("operation_id").jsonPrimitive.content)
        assertEquals(2L, sent.getValue("base_revision").jsonPrimitive.long)
        // v4 transmits base_revision; the base payload is retained locally, not a wire field.
        assertEquals(setOf("operation_id", "entity_type", "entity_uuid", "action", "base_revision", "payload"), sent.keys)
        assertEquals(json.parseToJsonElement(conflict.localPayloadJson), sent.getValue("payload"))
        reopen()
        assertFalse(repository.hasPendingChanges())
        assertEquals("Newest local edit", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
    }

    @Test fun choosing_server_is_durable_and_does_not_reupload_local_intent() = runBlocking {
        val habit = preserveConflict()
        val conflict = database.syncConflictDao().getUnresolved().single()
        repository.resolveConflictUseServer(conflict.id)
        reopen()
        assertEquals("resolved" to "server", resolution(conflict.id))
        assertEquals("Server title", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
        assertFalse(repository.hasPendingChanges())
        assertTrue(runCatching { repository.resolveConflictUseServer(conflict.id) }.exceptionOrNull() is IllegalStateException)
        repository.sync()
        assertEquals(1, pushes.size)
    }

    @Test fun choosing_local_cannot_resurrect_a_server_tombstone() = runBlocking {
        val habit = preserveConflict(tombstone = true)
        val conflict = database.syncConflictDao().getUnresolved().single()
        assertNull(database.habitDao().getHabitByUuid(habit.uuid))
        assertTrue(runCatching { repository.resolveConflictUseLocal(conflict.id) }.exceptionOrNull() is IllegalStateException)
        reopen()
        assertEquals(conflict, database.syncConflictDao().getUnresolved().single())
        assertNull(database.habitDao().getHabitByUuid(habit.uuid))
        assertEquals(0, database.syncOutboxDao().count())
        repository.resolveConflictUseServer(conflict.id)
        reopen()
        assertFalse(repository.hasPendingChanges())
    }

    @Test fun failed_conflict_insert_rolls_back_server_merge_shadow_and_queue_removal() = runBlocking {
        val habit = prepareConflict()
        val originalState = database.syncOutboxDao().getState("plan_node", habit.uuid)
        trigger("reject_conflict", "INSERT", "sync_conflicts")
        assertSqlFailure(runCatching { repository.sync() }.exceptionOrNull())
        reopen()
        assertEquals("Newest local edit", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(originalState, database.syncOutboxDao().getState("plan_node", habit.uuid))
        assertEquals(0, database.syncConflictDao().countUnresolved())
        val pending = database.syncOutboxDao().getAll()
        assertEquals(2, pending.size)
        assertNotNull(pending.first().attemptedAt)
        assertNull(pending.last().attemptedAt)
        assertEquals(0L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(unrelatedUuid))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_conflict")
        requireAttention()
        reopen()
        assertEquals(pushes[0], pushes[1])
        assertEquals(1, database.syncConflictDao().countUnresolved())
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun failed_local_resolution_outbox_insert_rolls_back_fact_shadow_and_resolution() = runBlocking {
        val habit = preserveConflict()
        val conflict = database.syncConflictDao().getUnresolved().single()
        val shadow = database.syncOutboxDao().getState("plan_node", habit.uuid)
        trigger("reject_resolution", "INSERT", "sync_outbox")
        assertSqlFailure(runCatching { repository.resolveConflictUseLocal(conflict.id) }.exceptionOrNull())
        reopen()
        assertEquals(conflict, database.syncConflictDao().getUnresolved().single())
        assertEquals(shadow, database.syncOutboxDao().getState("plan_node", habit.uuid))
        assertEquals("Server title", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resolution")
        repository.resolveConflictUseLocal(conflict.id)
        reopen()
        assertEquals(1, database.syncOutboxDao().count())
        assertEquals("resolved" to "local", resolution(conflict.id))
    }

    @Test fun failed_resolution_status_update_keeps_the_conflict_actionable() = runBlocking {
        preserveConflict()
        val conflict = database.syncConflictDao().getUnresolved().single()
        trigger("reject_resolution_status", "UPDATE", "sync_conflicts")
        assertSqlFailure(runCatching { repository.resolveConflictUseServer(conflict.id) }.exceptionOrNull())
        reopen()
        assertEquals(conflict, database.syncConflictDao().getUnresolved().single())
        assertTrue(repository.hasPendingChanges())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_resolution_status")
        repository.resolveConflictUseServer(conflict.id)
        reopen()
        assertFalse(repository.hasPendingChanges())
    }

    @Test fun malformed_conflicts_are_quarantined_without_blocking_unrelated_pull() = runBlocking {
        val first = insertGoal("Missing snapshot")
        val second = insertGoal("Missing revision")
        onPush = { request ->
            val results = request.getValue("operations").jsonArray.map { element ->
                val op = element.jsonObject
                val fields = if (op.getValue("entity_uuid").jsonPrimitive.content == first.uuid)
                    buildJsonObject { put("revision", 2) } else buildJsonObject { put("entity", canonical) }
                json.parseToJsonElement(result(buildJsonObject { put("operations", buildJsonArray { add(op) }) }, "conflict", fields))
                    .jsonObject.getValue("results").jsonArray.single()
            }
            buildJsonObject { put("results", JsonArray(results)) }.toString()
        }
        onPull = { page(listOf(change(9, unrelatedUuid, "Pulled despite invalid conflicts")), 9) }
        requireAttention()
        reopen()
        val dead = repository.getDeadLetters()
        assertEquals(setOf(first.uuid, second.uuid), dead.map { it.entityUuid }.toSet())
        assertTrue(dead.all { it.errorCode == "MALFORMED_CONFLICT" && it.deadLetteredAt != null && it.payloadJson != null })
        assertEquals(0, database.syncOutboxDao().count())
        assertEquals(0, database.syncConflictDao().countUnresolved())
        assertEquals(9L, tokens.syncCursor.first())
        assertEquals("Pulled despite invalid conflicts", database.habitDao().getHabitByUuid(unrelatedUuid)!!.name)
    }

    @Test fun rejection_survives_reopen_without_automatic_retry_and_manual_retry_uses_new_id() = runBlocking {
        val habit = rejectGoal()
        val dead = repository.getDeadLetters().single()
        assertEquals("INVALID_PAYLOAD", dead.errorCode)
        assertEquals("INVALID_PAYLOAD: invalid goal", dead.lastError)
        assertNotNull(dead.deadLetteredAt)
        assertNotNull(dead.attemptedAt)
        assertEquals(7L, tokens.syncCursor.first())
        requireAttention()
        assertEquals(1, pushes.size)
        assertEquals(dead, repository.getDeadLetters().single())
        repository.retryDeadLetter(dead.id)
        reopen()
        val retried = database.syncOutboxDao().getAll().single()
        assertNotEquals(dead.operationId, retried.operationId)
        assertNull(retried.payloadJson)
        assertNull(retried.attemptedAt)
        assertNull(retried.baseRevision)
        assertNull(retried.basePayloadJson)
        assertNull(retried.deadLetteredAt)
        assertNull(retried.errorCode)
        assertNull(retried.lastError)
        assertEquals(0, repository.getDeadLetters().size)
        database.habitDao().update(habit.copy(name = "Corrected goal"))
        onPush = { acknowledge(it) }
        repository.sync()
        val sent = operation(pushes.last())
        assertEquals(retried.operationId, sent.getValue("operation_id").jsonPrimitive.content)
        assertEquals("Corrected goal", sent.getValue("payload").jsonObject.getValue("title").jsonPrimitive.content)
        reopen()
        assertFalse(repository.hasPendingChanges())
        assertEquals("Corrected goal", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
    }

    @Test fun retry_all_rejections_changes_each_operation_id_and_preserves_each_local_goal() = runBlocking {
        val habits = listOf(insertGoal("First"), insertGoal("Second"))
        onPush = { result(it, "rejected", buildJsonObject { put("error_code", "INVALID_PAYLOAD") }) }
        requireAttention()
        reopen()
        val dead = repository.getDeadLetters().associateBy { it.entityUuid }
        assertEquals(2, dead.size)
        repository.retryAllDeadLetters()
        reopen()
        val retried = database.syncOutboxDao().getAll()
        assertEquals(2, retried.size)
        retried.forEach { assertNotEquals(dead.getValue(it.entityUuid).operationId, it.operationId); assertNull(it.payloadJson); assertNull(it.attemptedAt) }
        assertEquals(0, repository.getDeadLetters().size)
        onPush = { acknowledge(it) }
        repository.sync()
        reopen()
        assertFalse(repository.hasPendingChanges())
        habits.forEach { assertEquals(it.name, database.habitDao().getHabitByUuid(it.uuid)!!.name) }
    }

    @Test fun discard_failed_delete_persists_recovery_intent_and_keeps_rejection() = runBlocking {
        rejectGoal()
        val dead = repository.getDeadLetters().single()
        trigger("reject_discard", "DELETE", "sync_outbox")
        assertSqlFailure(runCatching { repository.discardDeadLetter(dead.id) }.exceptionOrNull())
        reopen()
        assertFalse(tokens.isSyncBootstrapped.first())
        assertEquals(listOf(dead), repository.getDeadLetters())
        val bootstrapCalls = paths.count { it.endsWith("/bootstrap") }
        requireAttention()
        assertEquals(bootstrapCalls, paths.count { it.endsWith("/bootstrap") })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_discard")
        repository.discardDeadLetter(dead.id)
        reopen()
        assertEquals(0, repository.getDeadLetters().size)
        assertFalse(tokens.isSyncBootstrapped.first())
    }

    @Test fun discard_then_failed_bootstrap_retries_and_preserves_local_only_data() = runBlocking {
        val habit = rejectGoal()
        repository.discardDeadLetter(repository.getDeadLetters().single().id)
        reopen()
        assertFalse(tokens.isSyncBootstrapped.first())
        onBootstrap = { throw IOException("Bootstrap unavailable") }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        assertFalse(tokens.isSyncBootstrapped.first())
        assertNotNull(database.habitDao().getHabitByUuid(habit.uuid))
        assertEquals(0, repository.getDeadLetters().size)
        onBootstrap = { snapshot(listOf(change(0, unrelatedUuid, "Canonical goal")), 11) }
        repository.sync()
        reopen()
        // Both localized discard dialogs explicitly promise to retain current local data.
        assertEquals("Rejected local goal", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals("Canonical goal", database.habitDao().getHabitByUuid(unrelatedUuid)!!.name)
        assertEquals(11L, tokens.syncCursor.first())
        assertTrue(tokens.isSyncBootstrapped.first())
        assertFalse(repository.hasPendingChanges())
        assertEquals(1, pushes.size)
    }

    @Test fun discard_reloads_the_authoritative_version_when_the_entity_exists_on_server() = runBlocking {
        val habit = rejectGoal()
        repository.discardDeadLetter(repository.getDeadLetters().single().id)
        reopen()
        assertFalse(tokens.isSyncBootstrapped.first())
        assertEquals("Rejected local goal", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        onBootstrap = { snapshot(listOf(change(0, habit.uuid, "Canonical server title")), 13) }
        repository.sync()
        reopen()
        assertEquals("Canonical server title", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(13L, tokens.syncCursor.first())
        assertFalse(repository.hasPendingChanges())
        assertEquals(1, pushes.size)
    }
}
