package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual physical Room, original TCP ACK and cold reopen; never switches/publishes authority. */
@RunWith(AndroidJUnit4::class)
class NextReplicaDifferenceReaderTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer
    private var recovering = false
    private var recoveryWire = ByteArray(0)
    private fun reader() = NextReplicaDifferenceReader(db, tokens, sessions, preferences)
    private fun canonical(kind: String, identity: String, body: JsonObject) = SyncV2Change(0, kind, identity, "upsert", 1,
        JsonObject(body + mapOf("public_id" to JsonPrimitive(identity), "revision" to JsonPrimitive(1),
            "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
    private fun snapshot(): RoundSyncBootstrapResponse = RoundSyncBootstrapResponse(listOf(
        canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
        canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
        canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric))), 20, time, emptyList(), 1,
        listOf(habit.uuid, timerHabit.uuid).map { activity ->
            val head = initialChallengeRoundHead(activity)
            ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
        }, emptyList())
    private suspend fun initialize() {
        tokens.getOrCreateInstallationId() // Installation preparation is outside readonly assertions.
        register()
        val (channel, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input).let {
                if (!recovering) it else it.copy(bytes = it.bytes.toString(Charsets.UTF_8).replace(id(3), id(99)).toByteArray())
            } else if (input.path == "/api/v2/sync/rounds/recovery-preview") {
                check(recovering && recoveryWire.isNotEmpty())
                MaterialSocketServer.Reply(recoveryWire)
            } else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                val body = Json.parseToJsonElement(successReply(input).bytes.toString(Charsets.UTF_8)).jsonObject
                MaterialSocketServer.Reply(JsonObject(body + mapOf("challenge_contract" to JsonPrimitive(1),
                    "checkpoints" to json.encodeToJsonElement(snapshot().checkpoints), "births" to JsonArray(emptyList()))).toString().toByteArray())
            }
        }
        http = channel; peer = server
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, local),
            NextTimerRequestStore(db, tokens, sessions, sender(http))).bootstrap(access(), null, snapshot())
    }
    private suspend fun candidate(value: RoundSyncBootstrapResponse = snapshot(), timer: TimerSessionResponse? = null,
        capabilityRevision: Int = 1) =
        NextReplicaCandidateValidator(app, tokens, sessions, preferences).validate(access(), tokens.getOrCreateInstallationId(),
            ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
                RecoveryDeviceProof(id(4), tokens.getOrCreateInstallationId(), "android", "interactive", null, null,
                    false, false, capabilityRevision, listOf("sync.read", "facts.append"), time), value, timer))
    private suspend fun unchanged(block: suspend () -> Unit) {
        val image = storage.snapshotClosedDatabase(); val prefs = dataStore.data.first(); val original = access()
        block()
        assertArrayEquals(image, storage.snapshotClosedDatabase()); assertEquals(prefs, dataStore.data.first())
        assertEquals(original, access())
    }
    private suspend fun queuedObservation(): SyncOutboxEntity {
        val writer = producer()
        writer.writeRounds(writer.captureRounds()) { metrics().recordValue(metric.id, 21.125, "ACKed original note", millis) }
        return db.syncOutboxDao().getAll().single()
    }

    @Test fun equalCompleteContentsStayReadOnlyAndColdReopenKeepsExactProof() = runBlocking<Unit> {
        initialize(); val remote = candidate()
        unchanged {
            val preview = reader().capture(remote)
            assertFalse(preview.activationAuthorized); assertTrue(preview.differences.isEmpty())
            assertTrue(preview.unconfirmedLocalRecords.isEmpty()); assertEquals(5, preview.unchangedRecords)
            val tables = preview.oldStorage.contents.getValue("tables").jsonObject
            assertTrue(tables.keys.containsAll(NextReplicaRecoveryReader.TABLES))
            assertFalse(preview.oldStorage.contents.toString().contains("synthetic-first"))
            val fresh = candidate(snapshot().copy(serverTime = "2026-10-06T00:01:00Z"))
            assertNotEquals(remote.wireHash, fresh.wireHash)
            assertEquals(preview.oldStorage.hash, reader().revalidate(preview, fresh).oldStorage.hash)
            storage.reopen()
            assertEquals(preview.oldStorage.hash, reader().revalidate(preview, remote).oldStorage.hash)
            assertTrue(runCatching { (preview.differences as MutableList<*>).clear() }.isFailure)
        }
    }

    @Test fun actualAckedObservationMissingFromBackupIsExplicitEvenWithEmptyQueues() = runBlocking<Unit> {
        initialize(); val row = queuedObservation(); val sender = sender(http)
        sender.acceptOperation(requireNotNull(sender.sendOperation(access(), row.operationId)))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val original = access(); val installation = tokens.getOrCreateInstallationId()
        recoveryWire = json.encodeToString(ReplicaRecoveryPreviewResponse(1, 5, id(1), id(2), id(99),
            RecoveryDeviceProof(id(4), installation, "android", "interactive", null, null,
                false, false, 1, listOf("sync.read", "facts.append"), time), snapshot(), null)).toByteArray()
        recovering = true; val calls = peer.requests.size
        unchanged {
            val received = http.recoveryPreview(original, id(99), installation)
            val validated = NextReplicaCandidateValidator(app, tokens, sessions, preferences).validate(original, installation, received)
            val preview = reader().capture(validated)
            assertEquals(listOf("/api/v2/system/identity", "/api/v2/sync/rounds/recovery-preview"),
                peer.requests.drop(calls).map { it.path })
            assertTrue(preview.assessment.reviewCounts.isEmpty())
            val missing = preview.differences.single()
            assertEquals(ReplicaDifferenceKind.MISSING, missing.kind)
            assertEquals(ReplicaRecordKey("metric_observation", row.entityUuid), missing.key)
            assertEquals("ACKed original note", missing.before!!.jsonObject.getValue("note").jsonPrimitive.content)
            assertNull(missing.after); assertFalse(preview.activationAuthorized)
            assertEquals(1, preview.oldStorage.contents.getValue("tables").jsonObject.getValue("next_acceptances").jsonArray.size)
        }
    }

    @Test fun equalRowCountsCannotHideChangedValuesOrReplacedIdentities() = runBlocking<Unit> {
        initialize()
        val base = snapshot()
        val changed = base.changes.map { change -> when (change.entityUuid) {
            habit.uuid -> change.copy(payload = JsonObject(change.payload + ("activity" to JsonObject(
                change.payload.getValue("activity").jsonObject + ("target_value" to JsonPrimitive(12))))))
            metric.uuid -> canonical("metric", id(70), NextStructureMapper.writeMetric(metric.copy(uuid = id(70), name = "Other metric")))
            else -> change
        } }
        unchanged {
            val preview = reader().capture(candidate(base.copy(changes = changed)))
            assertEquals(setOf(ReplicaDifferenceKind.ADDED, ReplicaDifferenceKind.CHANGED, ReplicaDifferenceKind.MISSING),
                preview.differences.map { it.kind }.toSet())
            assertEquals(3, preview.differences.size)
            assertEquals(12, preview.differences.single { it.kind == ReplicaDifferenceKind.CHANGED }.after!!
                .jsonObject.getValue("activity").jsonObject.getValue("target_value").jsonPrimitive.int)
        }
    }

    @Test fun sameCountLocalValueAndSchemaChangesInvalidatePreviewAfterColdReopen() = runBlocking<Unit> {
        initialize(); val remote = candidate(); val preview = reader().capture(remote)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET name='Changed locally' WHERE uuid=?", arrayOf(metric.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        storage.reopen()
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(preview, remote) }.message) }
        val second = reader().capture(remote)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER recovery_test_marker AFTER UPDATE ON metrics BEGIN SELECT 1; END")
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(second, remote) }.message) }
    }

    @Test fun unknownOriginalBytesAreRetainedAndSameCountByteMutationInvalidatesPreview() = runBlocking<Unit> {
        initialize(); val row = queuedObservation()
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val remote = candidate(); val preview = reader().capture(remote)
        assertEquals(1L, preview.assessment.reviewCounts[ReplicaRecoveryReview.UNKNOWN_RESULT])
        assertEquals(1, preview.unconfirmedLocalRecords.size)
        val raw = preview.oldStorage.contents.getValue("tables").jsonObject.getValue("next_transmissions").jsonArray.single().jsonObject
        assertEquals(4, raw.getValue("wireBytes").jsonObject.getValue("sqlite_type").jsonPrimitive.int)
        assertEquals(transmission(NEXT_OPERATION, row.operationId).wireBytes.joinToString("") { "%02x".format(it) },
            raw.getValue("wireBytes").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(id(3), delivery.access.session.syncEpoch)
        db.openHelper.writableDatabase.execSQL("UPDATE next_transmissions SET wireBytes=? WHERE kind=? AND requestId=?",
            arrayOf(byteArrayOf(1, 2, 3), NEXT_OPERATION, row.operationId))
        unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(preview, remote) }.message) }
    }

    @Test fun malformedRawAuthorityCannotBeCoercedIntoTrustedDifferences() = runBlocking<Unit> {
        initialize(); val remote = candidate()
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET deleted=2 WHERE entityType='metric'")
        unchanged { rejected { reader().capture(remote) } }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET deleted=0,payloadJson=CAST(payloadJson AS BLOB) WHERE entityType='metric'")
        unchanged { rejected { reader().capture(remote) } }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET payloadJson=zeroblob(?) WHERE entityType='metric'",
            arrayOf(com.dayforge.data.api.SYNC_REQUEST_LIMIT + 8192))
        unchanged { rejected { reader().capture(remote) } }
    }

    @Test fun normalMultiPageLocalCollectionIsCompleteAndNeverBecomesServerAuthority() = runBlocking<Unit> {
        initialize(); val remote = candidate()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            repeat(130) { index -> db.metricDao().insert(metric.copy(id = 0, uuid = id(100 + index), name = "Local $index")) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        unchanged {
            val preview = reader().capture(remote)
            assertEquals(131, preview.oldStorage.contents.getValue("tables").jsonObject.getValue("metrics").jsonArray.size)
            assertEquals(130, preview.unconfirmedLocalRecords.size)
            assertTrue(preview.differences.isEmpty()); assertFalse(preview.activationAuthorized)
            assertEquals(130, preview.unconfirmedLocalRecords.map { it.key.identity }.distinct().size)
        }
    }

    @Test fun freshServerContentCursorOrPermissionsInvalidateOldPreviewWithoutPublishingAnything() = runBlocking<Unit> {
        initialize(); val preview = reader().capture(candidate()); val base = snapshot()
        val changed = base.changes.map { if (it.entityType == "metric")
            it.copy(payload = JsonObject(it.payload + ("name" to JsonPrimitive("Server edit")))) else it }
        for (fresh in listOf(candidate(base.copy(changes = changed)), candidate(base.copy(nextCursor = 21)),
            candidate(capabilityRevision = 2))) {
            unchanged { assertEquals("SYNC_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(preview, fresh) }.message) }
        }
    }

    @Test fun unfinishedTimersAndUnconfirmedLocalRowsRemainVisibleNotCompleted() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timerHabit.id, startTime = millis, endTime = null,
                durationSeconds = 0, date = millis, uuid = id(90), timerTimezone = "Asia/Shanghai",
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = millis),
                TimerCommandEntity(commandId = id(93), sessionUuid = id(90), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(90), sequence = 1, startedAt = millis))
        }
        val session = TimerSessionResponse(id(91), timerHabit.uuid, "paused", id(92), 1, 2, 3,
            time, time, null, "Asia/Shanghai", false, 1, 180, 500, time, null)
        val base = snapshot()
        val remote = candidate(base.copy(births = listOf(ChallengeBirth("timer_session", session.sessionId,
            initialChallengeRoundHead(timerHabit.uuid)))), session)
        unchanged {
            val preview = reader().capture(remote)
            assertTrue(preview.hasUnfinishedTimer); assertFalse(preview.activationAuthorized)
            assertEquals(1L, preview.assessment.reviewCounts[ReplicaRecoveryReview.UNFINISHED_TIMER])
            assertEquals(id(90), preview.unconfirmedLocalRecords.single().key.identity)
            assertEquals(1, db.timeLogDao().getAllTimeLogsForHabit(timerHabit.id).size)
        }
    }

    @Test fun changedRegistrationOrCancelledReadCannotReuseOrMutatePreview() = runBlocking<Unit> {
        initialize(); val remote = candidate(); val preview = reader().capture(remote)
        unchanged {
            val cancelled = launch(start = CoroutineStart.LAZY) { reader().capture(remote) }
            cancelled.cancelAndJoin(); assertTrue(cancelled.isCancelled)
        }
        register(revision = 2)
        unchanged { assertEquals("SYNC_ACCOUNT_CHANGED", rejected { reader().revalidate(preview, remote) }.message) }
    }
}
