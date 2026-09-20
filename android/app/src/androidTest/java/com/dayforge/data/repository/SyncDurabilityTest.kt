package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.SyncProgress
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real client persistence and wire encoding. Scripted transport is not server idempotency evidence. */
@RunWith(AndroidJUnit4::class)
class SyncDurabilityTest : SyncPersistenceFixture() {
    @Test fun bootstrap_persists_rows_and_cursor_without_echoing_server_data() = runBlocking {
        tokens.requireSyncBootstrap()
        val uuid = UUID.randomUUID().toString()
        onBootstrap = { snapshot(listOf(change(0, uuid, "Server goal")), 12) }
        repository.sync()
        reopen()
        assertEquals(12L, tokens.syncCursor.first())
        assertTrue(tokens.isSyncBootstrapped.first())
        assertEquals("Server goal", database.habitDao().getHabitByUuid(uuid)!!.name)
        assertEquals(1L, database.syncOutboxDao().getState("plan_node", uuid)!!.revision)
        assertEquals(0, database.syncOutboxDao().count())
        repository.sync()
        assertEquals(1, paths.count { it == "/api/v2/sync/bootstrap" })
        assertEquals(listOf(12L), cursors.toList())
    }

    @Test fun lost_response_replays_persisted_snapshot_before_uploading_a_newer_edit() = runBlocking {
        val habit = insertGoal("Original")
        val originalId = database.syncOutboxDao().getAll().single().operationId
        onPush = { throw IOException("Response lost after server commit") }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        val prepared = database.syncOutboxDao().getAll().single()
        assertEquals(originalId, prepared.operationId)
        assertNotNull(prepared.attemptedAt)
        assertNotNull(prepared.payloadJson)
        reopen()
        assertEquals(prepared, database.syncOutboxDao().getAll().single())
        database.habitDao().update(habit.copy(name = "Newer edit"))
        onPush = { acknowledge(it, if (pushes.size == 2) "already_applied" else "applied") }
        repository.sync()
        assertEquals(3, pushes.size)
        assertEquals(pushes[0], pushes[1])
        val first = operation(pushes[0])
        val latest = operation(pushes[2])
        assertEquals(originalId, first.getValue("operation_id").jsonPrimitive.content)
        assertEquals("Original", first.getValue("payload").jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("Newer edit", latest.getValue("payload").jsonObject["title"]!!.jsonPrimitive.content)
        assertNotEquals(first["operation_id"], latest["operation_id"])
        reopen()
        assertEquals("Newer edit", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun interrupted_second_push_batch_reopens_with_only_unacknowledged_operations() = runBlocking {
        repeat(101) { insertGoal("Offline $it") }
        val calls = AtomicInteger()
        onPush = {
            if (calls.incrementAndGet() == 2) throw IOException("Second batch lost")
            acknowledge(it)
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        val remaining = database.syncOutboxDao().getAll().single()
        assertEquals(operation(pushes[1])["operation_id"]!!.jsonPrimitive.content, remaining.operationId)
        repository.sync()
        assertEquals(listOf(100, 1, 1), pushes.map { it["operations"]!!.jsonArray.size })
        assertEquals(pushes[1], pushes[2])
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun partial_acknowledgement_removes_only_the_matching_operation() = runBlocking {
        insertGoal("First")
        insertGoal("Second")
        onPush = { request ->
            val firstOnly = JsonObject(request + ("operations" to JsonArray(listOf(request["operations"]!!.jsonArray.first()))))
            acknowledge(firstOnly)
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        val remaining = database.syncOutboxDao().getAll().single()
        val unacknowledged = pushes.single()["operations"]!!.jsonArray[1].jsonObject
        assertEquals(unacknowledged["operation_id"]!!.jsonPrimitive.content, remaining.operationId)
        assertNull(database.syncOutboxDao().getState("plan_node", remaining.entityUuid))
        onPush = { acknowledge(it) }
        repository.sync()
        assertEquals(unacknowledged, operation(pushes.last()))
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun acknowledgement_without_revision_keeps_durable_request_for_retry() = runBlocking {
        insertGoal("Keep me")
        onPush = { request ->
            val response = json.parseToJsonElement(acknowledge(request)).jsonObject
            buildJsonObject {
                put("results", JsonArray(response.getValue("results").jsonArray.map {
                    JsonObject(it.jsonObject - "revision")
                }))
            }.toString()
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        assertEquals(1, database.syncOutboxDao().count())
        onPush = { acknowledge(it) }
        repository.sync()
        assertEquals(pushes[0], pushes[1])
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun interrupted_pull_resumes_after_the_last_persisted_page() = runBlocking {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val pageTwo = AtomicInteger()
        onPull = { cursor ->
            when (cursor) {
                0L -> page(listOf(change(5, first, "Page one")), 5, true)
                5L -> {
                    if (pageTwo.incrementAndGet() == 1) throw IOException("Page two lost")
                    page(listOf(change(9, second, "Page two")), 9)
                }
                else -> throw IOException("Unexpected cursor $cursor")
            }
        }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        assertEquals(5L, tokens.syncCursor.first())
        assertEquals("Page one", database.habitDao().getHabitByUuid(first)!!.name)
        assertNull(database.habitDao().getHabitByUuid(second))
        repository.sync()
        reopen()
        assertEquals(listOf(0L, 5L, 5L), cursors.toList())
        assertEquals(9L, tokens.syncCursor.first())
        assertEquals("Page two", database.habitDao().getHabitByUuid(second)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun failed_merge_rolls_back_the_whole_page_and_does_not_advance_cursor() = runBlocking {
        val first = UUID.randomUUID().toString()
        val broken = change(2, UUID.randomUUID().toString(), "Broken").let {
            JsonObject(it + ("entity_type" to JsonPrimitive("unknown_entity")))
        }
        onPull = { page(listOf(change(1, first, "Must roll back"), broken), 2) }
        onBootstrap = { throw IOException("Recovery unavailable") }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IOException)
        reopen()
        assertEquals(0L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(first))
        assertNull(database.syncOutboxDao().getState("plan_node", first))
        assertEquals(0, database.syncOutboxDao().count())
        // Suppression must have been restored even when the merge threw.
        insertGoal("New offline change")
        assertEquals(1, database.syncOutboxDao().count())
        onPull = { page(listOf(change(1, first, "Recovered")), 1) }
        repository.sync()
        reopen()
        assertEquals(1L, tokens.syncCursor.first())
        assertEquals("Recovered", database.habitDao().getHabitByUuid(first)!!.name)
    }

    @Test fun invalid_pagination_cannot_change_the_durable_cursor() = runBlocking {
        tokens.saveSyncCursor(7)
        onPull = { page(emptyList(), 7, true) }
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is IllegalStateException)
        reopen()
        assertEquals(7L, tokens.syncCursor.first())
        assertEquals(listOf(7L), cursors.toList())
        assertFalse(paths.contains("/api/v2/sync/bootstrap"))
    }

    @Test fun replaying_a_committed_page_keeps_one_entity_and_no_outgoing_echo() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        onPull = { page(listOf(change(5, uuid, "Replay safely")), 5) }
        repository.sync()
        val saved = database.habitDao().getHabitByUuid(uuid)!!
        // Model the Room-commit/DataStore-cursor gap. This is page replay, not a process-kill test.
        tokens.saveSyncCursor(0)
        reopen()
        repository.sync()
        reopen()
        val rows = database.habitDao().getAllHabitsOnce()
        assertEquals(1, rows.size)
        assertEquals(saved.id, rows.single().id)
        assertEquals(uuid, rows.single().uuid)
        assertEquals("Replay safely", rows.single().name)
        assertEquals(5L, tokens.syncCursor.first())
        assertEquals(listOf(0L, 0L), cursors.toList())
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun epoch_change_with_pending_intent_preserves_database_and_replica_metadata() = runBlocking {
        val habit = insertGoal("Unsynced")
        val pending = database.syncOutboxDao().getAll()
        tokens.saveSyncCursor(7)
        epoch = "epoch-b"
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is SyncEpochChangedException)
        reopen()
        assertEquals("epoch-a", tokens.syncEpoch.first())
        assertEquals(7L, tokens.syncCursor.first())
        assertEquals(pending, database.syncOutboxDao().getAll())
        assertEquals("Unsynced", database.habitDao().getHabitByUuid(habit.uuid)!!.name)
        assertEquals(listOf("/api/v2/system/identity"), paths.toList())
    }

    @Test fun clean_epoch_replaces_old_replica_and_persists_new_high_water_mark() = runBlocking {
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        onPull = { page(listOf(change(7, old, "Old replica")), 7) }
        repository.sync()
        epoch = "epoch-b"
        onBootstrap = { snapshot(listOf(change(0, fresh, "New replica")), 12) }
        val progress = mutableListOf<SyncProgress>()
        repository.sync(progress::add)
        reopen()
        assertTrue(progress.contains(SyncProgress.Recovering))
        assertEquals("epoch-b", tokens.syncEpoch.first())
        assertEquals(12L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(old))
        assertEquals("New replica", database.habitDao().getHabitByUuid(fresh)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test fun another_server_is_rejected_before_registration_or_upload() = runBlocking {
        insertGoal("Private local change")
        val pending = database.syncOutboxDao().getAll()
        server = "server-b"
        assertTrue(runCatching { repository.sync() }.exceptionOrNull() is ServerIdentityMismatchException)
        reopen()
        assertEquals("server-a", tokens.serverInstanceId.first())
        assertEquals(pending, database.syncOutboxDao().getAll())
        assertEquals(listOf("/api/v2/system/identity"), paths.toList())
    }


    @Test fun upload_progress_is_reported_after_durable_acknowledgement_before_pull() = runBlocking {
        insertGoal("Offline goal")
        val progress = mutableListOf<SyncProgress>()
        val persistedCounts = mutableListOf<Int>()
        repository.sync { event ->
            progress += event
            if (event is SyncProgress.UploadingChanges) {
                persistedCounts += runBlocking { database.syncOutboxDao().count() }
            }
        }
        assertEquals(listOf(SyncProgress.UploadingChanges(0, 1), SyncProgress.UploadingChanges(1, 1),
            SyncProgress.Downloading), progress)
        assertEquals(listOf(1, 0), persistedCounts)
        assertTrue(paths.indexOf("/api/v2/sync/push") < paths.indexOf("/api/v2/sync/changes"))
        reopen()
        assertEquals(0, database.syncOutboxDao().count())
        assertEquals("Offline goal", database.habitDao().getAllHabitsOnce().single().name)
    }

    @Test fun persisted_parent_first_deletions_are_transmitted_child_first() = runBlocking {
        val parent = UUID.randomUUID().toString()
        val child = UUID.randomUUID().toString()
        // Persist prepared operations in the opposite order, so bypassing sorting cannot pass.
        for ((uuid, type, payload) in listOf(Triple(parent, "GOAL", "{\"child_policy\":\"detach_children\"}"),
            Triple(child, "CHECK_IN", "{}"))) {
            database.syncOutboxDao().insert(SyncOutboxEntity(operationId = UUID.randomUUID().toString(),
                recordType = "habit", entityUuid = uuid, wireEntityUuid = uuid, action = "delete",
                referenceUuid = type, payloadJson = payload, baseRevision = 1, attemptedAt = 1, attemptCount = 1))
        }
        reopen()
        assertEquals(listOf(parent, child), database.syncOutboxDao().getAll().map { it.entityUuid })
        repository.sync()
        val operations = pushes.single().getValue("operations").jsonArray.map { it.jsonObject }
        assertEquals(listOf(child, parent), operations.map { it.getValue("entity_uuid").jsonPrimitive.content })
        assertTrue(operations.all { it.getValue("action").jsonPrimitive.content == "delete" })
        assertEquals(buildJsonObject { put("child_policy", "detach_children") }, operations[1].getValue("payload"))
        reopen()
        assertEquals(0, database.syncOutboxDao().count())
        assertTrue(database.syncOutboxDao().getState("plan_node", parent)!!.deleted)
        assertTrue(database.syncOutboxDao().getState("plan_node", child)!!.deleted)
    }

    @Test fun malformed_incremental_page_on_clean_cache_recovers_from_authoritative_snapshot() = runBlocking {
        val old = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()
        onPull = { page(listOf(change(3, old, "Old replica")), 3) }
        repository.sync()
        val malformed = JsonObject(change(7, fresh, "Invalid") + ("payload" to buildJsonObject { put("node_kind", "goal") }))
        onPull = { page(listOf(malformed), 7) }
        onBootstrap = { snapshot(listOf(change(0, fresh, "Recovered replica")), 9) }
        val progress = mutableListOf<SyncProgress>()
        repository.sync(progress::add)
        reopen()
        assertTrue(progress.contains(SyncProgress.Recovering))
        assertEquals(9L, tokens.syncCursor.first())
        assertNull(database.habitDao().getHabitByUuid(old))
        assertEquals("Recovered replica", database.habitDao().getHabitByUuid(fresh)!!.name)
        assertEquals(0, database.syncOutboxDao().count())
        assertEquals(1, paths.count { it == "/api/v2/sync/bootstrap" })
    }

    @Test fun legacy_session_without_public_account_id_refreshes_before_any_sync_request() = runBlocking {
        tokens.clearSyncState()
        tokenStore.edit { it.remove(stringPreferencesKey("user_public_id")) }
        reopen()
        assertNull(tokens.userId.first())
        var refreshBody: JsonObject? = null
        onRefresh = {
            refreshBody = it
            """{"access_token":"refreshed-access","refresh_token":"refreshed-refresh","user_id":"account-a","username":"member","is_admin":false}"""
        }
        onBootstrap = { snapshot(emptyList(), 9) }
        repository.sync()
        assertEquals(buildJsonObject { put("refresh_token", "synthetic-refresh") }, refreshBody)
        assertEquals("/api/auth/refresh", paths.first())
        assertTrue(paths.indexOf("/api/auth/refresh") < paths.indexOf("/api/v2/devices/register"))
        reopen()
        assertEquals("refreshed-access", tokens.accessToken.first())
        assertEquals("refreshed-refresh", tokens.refreshToken.first())
        assertEquals("member", tokens.userEmail.first())
        assertEquals(false, tokens.isAdmin.first())
        assertEquals("account-a", tokens.userId.first())
        assertEquals("account-a", tokens.syncAccountId.first())
        assertEquals(9L, tokens.syncCursor.first())
        repository.sync()
        assertEquals(1, paths.count { it == "/api/auth/refresh" })
    }
}
