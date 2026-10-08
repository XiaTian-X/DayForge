package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Room, original journals and real profile HTTP; no synthetic cursor-only success. */
@RunWith(AndroidJUnit4::class)
class NextChallengeRestoreTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private fun initial(activity: String) = ChallengeRoundRecord(initialChallengeRoundHead(activity), null, null, null)
    private fun restarted(activity: String = habit.uuid) = ChallengeRoundRecord(
        ChallengeRoundHead(activity, id(301), 1), id(4), id(302),
        ChallengeRestartIntent(activity, id(301), initialChallengeRoundUuid(activity), 0, 1))
    private fun metadata(restart: Boolean = false, births: List<ChallengeBirth> = emptyList()) = ChallengeMetadata(1,
        listOf(ChallengeCheckpoint(if (restart) restarted().head else initial(habit.uuid).head,
            if (restart) listOf(initial(habit.uuid), restarted()) else listOf(initial(habit.uuid))),
            ChallengeCheckpoint(initial(timerHabit.uuid).head, listOf(initial(timerHabit.uuid)))), births)
    private fun canonical(type: String, id: String, body: JsonObject, revision: Long = 1, sequence: Long = 0) =
        SyncV2Change(sequence, type, id, "upsert", revision, JsonObject(body + mapOf(
            "public_id" to JsonPrimitive(id), "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time),
            "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun fact(identity: String = id(210), sequence: Long = 0, value: Int = 3) = canonical("activity_event", identity,
        buildJsonObject {
            put("activity_uuid", habit.uuid); put("event_type", "count_delta"); put("value", value)
            put("occurred_at", time); put("local_date", "2026-10-06"); put("timezone", "Etc/UTC")
            put("note", "original fact"); put("source_type", "app"); put("source_device_id", id(4))
            put("external_event_id", JsonNull); put("metadata", buildJsonObject {}); put("received_at", time)
            for (key in listOf("duration_seconds", "duration_milliseconds", "started_at", "ended_at", "reverts_event_uuid")) put(key, JsonNull)
            put("count_policy", CountDayPolicy(10, false).toJson())
        }, sequence = sequence)
    private fun snapshot(cursor: Long = 20, meta: ChallengeMetadata = metadata(), facts: List<SyncV2Change> = emptyList()) =
        RoundSyncBootstrapResponse(listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))) + facts, cursor, time, emptyList(),
            1, meta.checkpoints, meta.births)
    private fun page(cursor: Long, meta: ChallengeMetadata = metadata(true), changes: List<SyncV2Change> = emptyList(), more: Boolean = false) =
        RoundSyncPullResponse(changes, cursor, more, time, 1, meta.checkpoints, meta.births)
    private fun roundChange(sequence: Long = 21) = SyncV2Change(sequence, "challenge_round", restarted().head.roundUuid,
        "upsert", 1, json.encodeToJsonElement(restarted()).jsonObject, time, id(4))
    private fun renamed(sequence: Long = 22) = canonical("metric", metric.uuid,
        JsonObject(NextStructureMapper.writeMetric(metric) + ("name" to JsonPrimitive("Remote metric"))), 2, sequence)
    private fun merger(http: NextSyncHttp): NextSyncMergeStore {
        val timers = NextTimerRequestStore(db, tokens, sessions, sender(http))
        return NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timers), timers)
    }
    private fun runtime(http: NextSyncHttp) = NextSyncRuntime(db, tokens, sessions, http, preferences)
    private fun bytes(value: RoundSyncBootstrapResponse) = MaterialSocketServer.Reply(json.encodeToString(value).toByteArray())
    private fun bytes(value: RoundSyncPullResponse) = MaterialSocketServer.Reply(json.encodeToString(value).toByteArray())
    private suspend fun initialized(http: NextSyncHttp): NextSyncStateEntity = merger(http).bootstrap(access(), null, snapshot())
    private suspend fun plainInitialized(http: NextSyncHttp): NextSyncStateEntity =
        merger(http).bootstrap(access(), null, NextSyncBootstrapResponse(snapshot().changes, 20, time, emptyList()))

    @Test fun actualProfileBootstrapPagesKeepOriginalBirthAndColdResumeWithoutPlainRoutes() = runBlocking {
        register()
        val old = ChallengeBirth("activity_event", id(210), initial(habit.uuid).head)
        val newer = ChallengeBirth("activity_event", id(211), restarted().head)
        val requests = mutableListOf<String>()
        val (http, _) = channel { input ->
            requests += input.target
            when {
                input.path.endsWith("/identity") -> reply(input)
                input.path == "/api/v2/sync/rounds/bootstrap" -> bytes(snapshot(meta = metadata(births = listOf(old)), facts = listOf(fact())))
                input.path == "/api/v2/sync/rounds/changes" -> {
                    assertTrue(input.target.contains("challenge_contract=1"))
                    val cursor = input.target.substringAfter("cursor=").substringBefore('&').toLong()
                    if (cursor == 20L) bytes(page(23, metadata(true, listOf(old, newer)),
                        listOf(roundChange(), renamed(), fact(id(211), 23, 4)), more = true))
                    else bytes(page(cursor, metadata(true, listOf(old, newer))))
                }
                else -> error("Unexpected plain route ${input.path}")
            }
        }
        assertEquals(23L, runtime(http).refreshChallengeCache().cursor)
        assertEquals("Remote metric", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(setOf(3, 4), db.completionDao().getAllCompletionsOnce().map { it.value }.toSet())
        assertEquals(10, db.countDayDao().get(habit.id, "2026-10-06")!!.targetValue)
        val first = merger(http).challengeMetadata(access())!!
        assertEquals(initial(habit.uuid).head, first.requireBirth("activity_event", id(210), habit.uuid).head)
        assertEquals(restarted().head, first.requireBirth("activity_event", id(211), habit.uuid).head)
        assertEquals(0L, tokens.syncCursor.first()); assertNull(preferences.lastSyncTimestamp.first())
        storage.reopen()
        assertEquals(first, merger(http).challengeMetadata(access()))
        assertEquals(23L, runtime(http).refreshChallengeCache().cursor)
        assertEquals(1, requests.count { it.startsWith("/api/v2/sync/rounds/bootstrap") })
        assertEquals(3, count("next_challenge_rounds")); assertEquals(2, count("next_challenge_births"))
    }

    @Test fun plainCursorCannotSkipProfileBootstrapAndOriginalCursorIsComparedAtomically() = runBlocking {
        register()
        val requests = mutableListOf<String>()
        val (http, _) = channel { input ->
            requests += input.path
            if (input.path.endsWith("/identity")) reply(input)
            else if (input.path.endsWith("/bootstrap")) bytes(snapshot(30, metadata(true)))
            else bytes(page(30))
        }
        val store = merger(http)
        store.bootstrap(access(), null, NextSyncBootstrapResponse(snapshot().changes, 20, time, emptyList()))
        assertNull(store.state(access(), challengeProfile = true))
        assertEquals(30L, runtime(http).refreshChallengeCache().cursor)
        assertTrue(requests.contains("/api/v2/sync/rounds/bootstrap"))
        rejected { store.state(access()) }
        rejected { runtime(http).sync() }
        val before = count("sync_outbox")
        rejected { editMetric("Cannot use plain producer") }
        assertEquals(before, count("sync_outbox")); assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
    }

    @Test fun originalFrozenQueueBlocksReadOnlyRefreshAndIsNeverConsumedOrRewrapped() = runBlocking {
        register()
        val row = editMetric("Keep frozen")
        val (http, _) = channel()
        val delivery = sender(http).sendOperation(access(), row.operationId)!!
        val original = transmission(NEXT_OPERATION, row.operationId)
        rejected { runtime(http).refreshChallengeCache() }
        assertEquals(row, db.syncOutboxDao().getById(row.id)); assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertArrayEquals(original.wireBytes, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(delivery.transmissionProof, db.withTransaction { NextRequestSql.rowHash(db.openHelper.writableDatabase,
            "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, row.operationId)) })
        assertNull(db.nextSyncStateDao().rows().singleOrNull()); assertFalse(db.nextChallengeDao().hasAny())
    }

    @Test fun profileMergeKeepsPendingSourcesButCannotSendThemThroughPlainProducerOrSender() = runBlocking {
        register()
        val (http, _) = channel()
        val previous = plainInitialized(http)
        val row = editMetric("Local pending")
        sender(http).sendOperation(access(), row.operationId)
        val original = transmission(NEXT_OPERATION, row.operationId)
        val store = merger(http)
        store.bootstrap(access(), previous, snapshot())
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        rejected { sender(http).sendOperation(access(), row.operationId) }
        assertArrayEquals(original.wireBytes, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals("Local pending", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertNotNull(store.challengeMetadata(access()))
    }

    @Test fun laterInvalidCacheEntryRollsBackAllBusinessHistoryBirthsAndCursor() = runBlocking {
        register()
        val (http, _) = channel()
        val store = merger(http); val before = initialized(http)
        val invalid = renamed().let { it.copy(payload = JsonObject(it.payload - "name")) }
        rejected { store.page(access(), before, page(22, changes = listOf(roundChange(), invalid))) }
        assertEquals(before, store.state(access(), true)); assertEquals(metadata(), store.challengeMetadata(access()))
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(2, count("next_challenge_rounds")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun lateCursorTriggerCannotChangeRoundHistoryOrBirthProofAndRetrySurvivesReopen() = runBlocking {
        register()
        val (http, _) = channel()
        val store = merger(http); val before = initialized(http)
        val changes = page(22, changes = listOf(roundChange(), renamed()))
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER damage_round_proof AFTER UPDATE ON next_sync_state BEGIN
            UPDATE next_challenge_state SET metadataHash='${"0".repeat(64)}'; END""")
        rejected { store.page(access(), before, changes) }
        assertEquals(before, store.state(access(), true)); assertEquals(metadata(), store.challengeMetadata(access()))
        assertEquals(metric.name, db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_round_proof")
        storage.reopen()
        val next = merger(http).page(access(), before, changes)
        assertEquals(22L, next.cursor); assertEquals(metadata(true), merger(http).challengeMetadata(access()))
        assertEquals(next, merger(http).page(access(), before, changes)) // Exact retry cannot add another round.
        assertEquals(3, count("next_challenge_rounds"))
    }

    @Test fun alreadyCommittedPageStillAuditsProfileInsteadOfTrustingCursorOnly() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); val before = initialized(http)
        val response = page(21, changes = listOf(roundChange()))
        store.page(access(), before, response)
        db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=?", arrayOf("0".repeat(64)))
        rejected { store.page(access(), before, response) }
        assertEquals(21L, db.nextSyncStateDao().rows().single().cursor)
    }

    @Test fun roundInsertionTriggerCannotMutateOriginalJournalOrOtherBusinessRows() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); val previous = plainInitialized(http)
        val row = editMetric("Keep exact work")
        val original = originalIntent(row)
        val before = store.bootstrap(access(), previous, snapshot())
        for (action in listOf("UPDATE next_request_origins SET intentJson='{}'",
            "UPDATE metrics SET name='trigger edit' WHERE uuid='${metric.uuid}'")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER damage_round_insert AFTER INSERT ON next_challenge_rounds BEGIN $action; END")
            rejected { store.page(access(), before, page(21, changes = listOf(roundChange()))) }
            assertEquals(before, store.state(access(), true)); assertEquals(original, originalIntent(row))
            assertEquals(row, db.syncOutboxDao().getById(row.id))
            assertEquals("Keep exact work", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_round_insert")
        }
    }

    @Test fun headRollbackChangedOriginalIntentAndBirthReassignmentNeverOverwriteAuthority() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http)
        val old = ChallengeBirth("activity_event", id(210), initial(habit.uuid).head)
        val before = store.bootstrap(access(), null, snapshot(meta = metadata(true, listOf(old)), facts = listOf(fact())))
        rejected { store.page(access(), before, page(20, metadata(births = listOf(old)))) }
        val changedRecord = restarted().copy(restartIntent = restarted().restartIntent!!.copy(expectedPlanRevision = 2))
        val changedMeta = metadata(true, listOf(old)).let { it.copy(checkpoints = listOf(
            ChallengeCheckpoint(changedRecord.head, listOf(initial(habit.uuid), changedRecord)), it.checkpoints.last())) }
        rejected { store.page(access(), before, page(20, changedMeta)) }
        rejected { store.page(access(), before, page(20, metadata(true, listOf(old.copy(head = restarted().head))))) }
        assertEquals(before, store.state(access(), true)); assertEquals(metadata(true, listOf(old)), store.challengeMetadata(access()))
    }

    @Test fun fullSnapshotPrunesOnlyCacheNotOriginalHistoryOrBirthsAndDoesNotResurrectDeletedPlans() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http)
        val old = ChallengeBirth("activity_event", id(210), initial(habit.uuid).head)
        val before = store.bootstrap(access(), null, snapshot(meta = metadata(true, listOf(old)), facts = listOf(fact())))
        store.bootstrap(access(), before, RoundSyncBootstrapResponse(emptyList(), 30, time, emptyList(), 1, emptyList(), emptyList()))
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty()); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        assertEquals(metadata(true, listOf(old)), store.challengeMetadata(access()))
        storage.reopen()
        assertEquals(metadata(true, listOf(old)), merger(http).challengeMetadata(access()))
        assertTrue(db.habitDao().getAllHabitsOnce().isEmpty())
    }

    @Test fun rawBlobFractionOverflowMissingChainAndOrphanMarkerFailBeforeRoomCoercion() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); initialized(http)
        val sql = db.openHelper.writableDatabase
        val row = db.nextChallengeDao().rounds().first()
        for (statement in listOf("UPDATE next_challenge_rounds SET recordJson=CAST(recordJson AS BLOB)",
            "UPDATE next_challenge_rounds SET generation=0.5", "UPDATE next_challenge_rounds SET generation=4294967296",
            "DELETE FROM next_challenge_rounds", "DELETE FROM next_challenge_state")) {
            // Deliberate corruption is rolled back after proving refusal; never 'repair' authority.
            val rollback = IllegalStateException("test-only rollback")
            assertSame(rollback, rejected {
                db.withTransaction {
                    sql.execSQL(statement)
                    rejected { NextChallengeStore(db).readInTransaction(access(), db.nextSyncStateDao().rows().single()) }
                    throw rollback
                }
            })
        }
        assertEquals(row, db.nextChallengeDao().rounds().first())
        assertEquals(metadata(), store.challengeMetadata(access()))
    }

    @Test fun changedAccountDeviceOrEpochCannotReadPreviousRoundAuthority() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); initialized(http)
        val captured = access()
        tokens.saveDeviceRegistration(id(40), caps, true, 2)
        rejected { store.state(captured, true) }; rejected { store.state(access(), true) }
        tokens.saveServerIdentity(id(2), id(43)); tokens.saveDeviceRegistration(id(40), caps, true, 2)
        rejected { store.state(access(), true) }
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "member", id(41), false)
        register()
        rejected { store.challengeMetadata(access()) }
        assertEquals(2, count("next_challenge_rounds"))
    }

    @Test fun emptyPageCanAdvanceCheckpointWithoutDroppingExistingCacheOrFacts() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); val before = initialized(http)
        val next = store.page(access(), before, page(20))
        assertEquals(20L, next.cursor); assertEquals(before.generation + 1, next.generation)
        assertEquals(metadata(true), store.challengeMetadata(access()))
        assertEquals(2, db.habitDao().getAllHabitsOnce().size); assertEquals(1, db.metricDao().getAllMetricsOnce().size)
    }

    @Test fun localWorkCreatedDuringHttpPreventsReadOnlyProfileCommit() = runBlocking {
        register()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                assertEquals("/api/v2/sync/rounds/bootstrap", input.path)
                runBlocking { editMetric("Written while downloading") }
                bytes(snapshot())
            }
        }
        rejected { runtime(http).refreshChallengeCache() }
        assertEquals("Written while downloading", db.metricDao().getMetricByUuid(metric.uuid)!!.name)
        assertEquals(1, db.syncOutboxDao().count()); assertEquals(1, count("next_request_origins"))
        assertFalse(db.nextChallengeDao().hasAny()); assertTrue(db.nextSyncStateDao().rows().isEmpty())
    }

    @Test fun deletingAllSidecarRowsCannotTurnAProfileCursorIntoAnOldPlainCursor() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); val before = initialized(http)
        db.withTransaction {
            for (table in listOf("next_challenge_births", "next_challenge_rounds", "next_challenge_state"))
                db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
        }
        assertEquals(1, before.challengeContract)
        rejected { store.state(access(), true) }; rejected { store.state(access()) }
        rejected { runtime(http).sync() }; rejected { editMetric("Cannot guess a profile") }
        assertEquals(before, db.nextSyncStateDao().rows().single()); assertEquals(0, count("sync_outbox"))
    }

    @Test fun sourceUniquenessUsesActualDeviceAndNeverOverwritesAnotherActivityRound() = runBlocking {
        register()
        val (http, _) = channel(); val store = merger(http); val before = initialized(http)
        fun incoming(device: String): ChallengeMetadata {
            val timerRecord = ChallengeRoundRecord(ChallengeRoundHead(timerHabit.uuid, id(401), 1), device, id(302),
                ChallengeRestartIntent(timerHabit.uuid, id(401), initialChallengeRoundUuid(timerHabit.uuid), 0, 1))
            return metadata(true).copy(checkpoints = listOf(metadata(true).checkpoints.first(),
                ChallengeCheckpoint(timerRecord.head, listOf(initial(timerHabit.uuid), timerRecord))))
        }
        rejected { store.page(access(), before, page(20, incoming(id(4)))) }
        assertEquals(before, store.state(access(), true)); assertEquals(2, count("next_challenge_rounds"))
        store.page(access(), before, page(20, incoming(id(40))))
        assertEquals(4, count("next_challenge_rounds"))
    }

    @Test fun explicitTestbedClearRemovesCursorAndAuthorityTogetherWithoutForeignKeyLeaks() = runBlocking {
        register()
        val (http, _) = channel(); initialized(http)
        db.clearAllData()
        assertTrue(db.nextSyncStateDao().rows().isEmpty()); assertFalse(db.nextChallengeDao().hasAny())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        storage.reopen()
        assertNull(merger(http).state(access(), true))
        db.withTransaction { NextRequestSql.requireOutboxEnabled(db.openHelper.writableDatabase) }
    }
}
