package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountCommandMigrationFixture
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two distinct inputs: a legal old plain cursor with naturally empty allocators, and
 * an actually qualified ONCE subject whose allocators are already nonempty. No sequence
 * deletion, synthetic Context, hand-signed seal or dirty reset joins those two cases.
 * This is a storage/page test using the original plaintext authentication fixture, not
 * an HTTP/server, AES, Host replacement or dual-physical-device acceptance claim. */
@RunWith(AndroidJUnit4::class)
class CountTrustAllocatorPageTest : CountCommandMigrationFixture() {
    private val at = "2026-10-06T00:00:00Z"
    private val json = Json { encodeDefaults = true }

    private suspend fun <T> pageOwner(database: HabitDatabase,
        block: suspend (NextSyncMergeStore, OneTimeLocalIntentStore) -> T): T {
        // Construction satisfies the original merger's timer participant. These pages
        // perform no network request; fail before any socket if that scope ever changes.
        val client = OkHttpClient.Builder().addInterceptor { error("ALLOCATOR_PAGE_UNEXPECTED_HTTP") }.build()
        var failure: Throwable? = null
        try {
            val http = NextSyncHttp(client, tokens, "http://127.0.0.1:1/".toHttpUrl())
            val local = OneTimeLocalIntentStore(database, tokens, sessions, commandMigrationPreferences)
            val once = OneTimeAcceptedEventStore(database, tokens, sessions, local)
            val sender = NextCoreRequestStore(database, tokens, sessions, http)
            return block(NextSyncMergeStore(database, tokens, sessions, once,
                NextTimerRequestStore(database, tokens, sessions, sender)), local)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun image(database: HabitDatabase): JsonObject = database.withTransaction {
        // Unlike nextRestartDatabaseProof, this includes MAIN sqlite_sequence and rowids.
        CountCommandMigrationFixture.snapshot(database.openHelper.writableDatabase).contents
    }

    private fun tables(image: JsonObject) = image.getValue("tables").jsonObject

    @Test(timeout = 150_000) fun migratedEmptyAllocatorPlainNoopIsReadOnlyAndAdvancingPagePreparesOnce() = runBlocking<Unit> {
        val access = requireNotNull(tokens.localSyncAccess())
        val bootstrap = NextSyncBootstrapResponse(emptyList(), 20, at, emptyList())
        val hash = syncPayloadHash(json.encodeToString(bootstrap))
        val state = NextSyncStateEntity(access.session.authentication.userId,
            requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
            requireNotNull(access.deviceId), 1, 20, hash, hash)
        // Existing KSP19 input: a valid ACTIVE plain cursor with no business allocation.
        // Remove only source()'s unrelated canned old request/rejection. No allocator or
        // COUNT/command authority is written; the real 19->20 edge owns current roots.
        val old = source(19) { sql ->
            sql.execSQL("DELETE FROM next_transmissions")
            sql.execSQL("DELETE FROM next_rejections")
            sql.execSQL("INSERT INTO next_sync_state(accountId,serverInstanceId,syncEpoch,deviceId,generation,cursor,bootstrapHash,batchHash,id,challengeContract) VALUES(?,?,?,?,?,?,?,?,1,0)",
                arrayOf<Any>(state.accountId, state.serverInstanceId, state.syncEpoch, state.deviceId,
                    state.generation, state.cursor, state.bootstrapHash, state.batchHash))
            sql.query("SELECT 1 FROM main.sqlite_sequence LIMIT 1").use { assertFalse(it.moveToFirst()) }
        }
        val database = openCurrent()
        assertEquals(JsonArray(emptyList()), tables(old.contents).getValue("sqlite_sequence"))
        val before = image(database)
        assertEquals(tables(old.contents).getValue("sqlite_sequence"), tables(before).getValue("sqlite_sequence"))
        assertEquals(state, database.nextSyncStateDao().rows().single())
        assertTrue(database.habitDao().getAllHabitsOnce().isEmpty())
        assertTrue(database.metricDao().getAllMetricsOnce().isEmpty())
        pageOwner(database) { merger, _ ->
            val empty = NextSyncPullResponse(emptyList(), state.cursor, false, at)
            assertEquals(state, merger.page(access, state, empty))
            assertEquals("Plain same-cursor page must not initialize allocator metadata", before, image(database))
            assertEquals(access, tokens.localSyncAccess())
            val advanced = merger.page(access, state, empty.copy(nextCursor = state.cursor + 1))
            assertEquals(state.generation + 1, advanced.generation)
            assertEquals(state.cursor + 1, advanced.cursor)
            assertEquals(state.bootstrapHash, advanced.bootstrapHash)
            database.withTransaction {
                val sql = database.openHelper.writableDatabase
                sql.query("SELECT rowid,name,seq FROM main.sqlite_sequence ORDER BY rowid").use { cursor ->
                    for ((index, name) in listOf("sync_outbox", "timer_command_outbox").withIndex()) {
                        assertTrue(cursor.moveToNext())
                        assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(0)); assertEquals(index + 1L, cursor.getLong(0))
                        assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(1)); assertEquals(name, cursor.getString(1))
                        assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(2)); assertEquals(0L, cursor.getLong(2))
                        assertEquals(0L, NextRequestSql.watermark(sql, name))
                        assertTrue(NextRequestSql.sources(sql, name).isEmpty())
                    }
                    assertFalse(cursor.moveToNext())
                }
            }
            val committed = image(database)
            val allowed = setOf("sqlite_sequence", "next_sync_state", "count_trust_control")
            assertEquals(tables(before).filterKeys { it !in allowed }, tables(committed).filterKeys { it !in allowed })
            assertEquals(before.getValue("schema"), committed.getValue("schema"))
            assertEquals(advanced, merger.page(access, state, empty.copy(nextCursor = state.cursor + 1)))
            assertEquals("Saved page replay remains exactly read-only", committed, image(database))
            assertEquals(advanced, merger.state(access))
        }
    }

    @Test(timeout = 150_000) fun realOnceDeepLeafStillRejectsAfterNonemptyPreparationAndExactPageRetries() = runBlocking<Unit> {
        val database = openCurrent()
        val access = requireNotNull(tokens.localSyncAccess())
        val millis = Instant.parse(at).toEpochMilli()
        val habit = HabitEntity(uuid = id(710), name = "Allocator ONCE", habitType = HabitType.CHECK_IN,
            iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Once(), targetValue = 1,
            failMode = FailMode.LOOSE, createdAt = millis, updatedAt = millis,
            completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            appearance = ObjectAppearance(IconReference.Role("task.custom"), "#123456", "object"),
            planMetadata = PlanStructureMetadata(at, 0, null, null, "Asia/Shanghai", null, null, null))
        val parent = SyncV2Change(0, "plan_node", habit.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writePlan(habit) + mapOf("public_id" to JsonPrimitive(habit.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(at), "updated_at" to JsonPrimitive(at),
                "deleted_at" to JsonNull)), at)
        pageOwner(database) { merger, local ->
            val state = merger.bootstrap(access, null, NextSyncBootstrapResponse(listOf(parent), 20, at,
                listOf(OneTimeProjection(habit.uuid, OneTimeState(0, null, null)))))
            sessions.exclusive { database.withTransaction {
                CountTrustKernel(database, tokens, sessions).withOnceCapture(tokens.localCoreWriteAccess(), prepare = true) {
                    assertNotNull("Only the original complete validator may issue ONCE coverage", it)
                }
            } }
            assertEquals(0, local.read(habit.uuid).confirmed.version)
            val healthy = image(database)
            assertTrue(tables(healthy).getValue("sqlite_sequence").jsonArray.isNotEmpty())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
            val header = requireNotNull(CountTrustSeal.read(database.openHelper.writableDatabase))
            val bytes = sessions.exclusive { database.withTransaction {
                val current = requireNotNull(CountTrustKernel(database, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
                assertTrue(current.once)
                requireNotNull(current.index.get(OnceTrustEvidence.projectionKey(habit.uuid))).toString().toByteArray(Charsets.UTF_8)
            } }
            assertTrue(bytes.size in 1..CountTrustIndex.CHUNK_BYTES)
            val digest = CountTrustIndex.hash(bytes)
            database.withTransaction { owner.maintenance(database) {
                val sql = database.openHelper.writableDatabase
                sql.query("SELECT body FROM count_trust_nodes WHERE digest=?", arrayOf(digest)).use {
                    assertTrue(it.moveToFirst()); assertArrayEquals(bytes, it.getBlob(0)); assertFalse(it.moveToNext())
                }
                val broken = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(broken, digest))
            } }
            val damaged = image(database)
            database.withTransaction { owner.prepareOutboxAllocatorsForWrite(database) }
            assertEquals("Nonempty preparation never rewrites a damaged old proof", damaged, image(database))
            assertEquals(header, CountTrustSeal.read(database.openHelper.writableDatabase))
            sessions.exclusive { database.withTransaction {
                val current = requireNotNull(CountTrustKernel(database, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
                assertTrue("Root/coverage authentication must pass before the deep fault", current.once)
                val failure = rejection { current.index.get(OnceTrustEvidence.projectionKey(habit.uuid)) }
                assertTrue(failure is IllegalArgumentException)
                assertEquals("COUNT_TRUST_NODE_CHANGED", failure.message)
            } }
            val event = id(711)
            val page = NextSyncPullResponse(listOf(complete(habit.uuid, event, state.cursor + 1)), state.cursor + 1, false, at)
            val failure = rejection { merger.page(access, state, page) }
            assertTrue(failure is OneTimeLocalException)
            assertEquals(OneTimeLocalException.Reason.INVALID_LOCAL_STATE, (failure as OneTimeLocalException).reason)
            assertEquals(damaged, image(database))
            assertEquals(state, database.nextSyncStateDao().rows().single())
            assertNull(database.completionDao().getCompletionByUuid(event))
            database.withTransaction { owner.maintenance(database) {
                database.openHelper.writableDatabase.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(bytes, digest))
            } }
            assertEquals("Restore only the original corrupted chunk, never re-sign or clear dirty", healthy, image(database))
            val next = merger.page(access, state, page)
            assertEquals(state.cursor + 1, next.cursor)
            assertEquals(1, local.read(habit.uuid).confirmed.version)
            assertEquals(event, database.completionDao().getAllCompletionsOnce().single().uuid)
            assertTrue(database.syncOutboxDao().getAll().isEmpty())
            val committed = image(database)
            assertEquals(next, merger.page(access, state, page))
            assertEquals(committed, image(database))
            assertEquals(access, tokens.localSyncAccess())
        }
    }

    private suspend fun rejection(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            return error
        }
        throw AssertionError("Original page must reject")
    }

    /** Independent valid complete event, as in the original OnceTrustPullTest wire fixture. */
    private fun complete(activity: String, event: String, sequence: Long): SyncV2Change {
        val body = buildJsonObject {
            put("public_id", event); put("revision", 1); put("deleted_at", JsonNull)
            listOf("created_at", "updated_at", "received_at", "occurred_at").forEach { put(it, at) }
            put("activity_uuid", activity); put("event_type", "check_in"); put("value", 1)
            listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "external_event_id", "reverts_event_uuid").forEach { put(it, JsonNull) }
            put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("note", ""); put("metadata", buildJsonObject {})
            put("one_time", buildJsonObject {
                put("event_uuid", event); put("action", "complete"); put("expected_version", 0)
                put("expected_head_event_uuid", JsonNull); put("reverts_event_uuid", JsonNull)
            })
            put("one_time_state_after", buildJsonObject {
                put("version", 1); put("head_event_uuid", event); put("completion_event_uuid", event)
            })
        }
        return SyncV2Change(sequence, "activity_event", event, "upsert", 1, body, at)
    }
}
