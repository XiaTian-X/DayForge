package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.*
import com.dayforge.domain.service.SyncManager
import com.dayforge.sync.AutoSyncWorker
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual file Room/preferences and discovery/registration/ACK TCP. No switch/clear authorization. */
@RunWith(AndroidJUnit4::class)
class NextReplicaRecoveryReaderTest : NextCoreRequestFixture() {
    private val json = Json { encodeDefaults = true }
    private val legacy = mockk<IncrementalSyncRepository>(relaxed = true)
    private var observedEpoch = id(3)
    private var rejectMetric = false
    private lateinit var http: NextSyncHttp
    private lateinit var peer: MaterialSocketServer

    private fun driver(): BusinessSyncRepository {
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        return BusinessSyncRepository(legacy, EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport()),
            db, tokens, sessions, http, NextSyncRuntime(db, tokens, sessions, http, preferences))
    }
    private fun observed() = ServerIdentityResponse(id(2), id(99), 5, caps.toList(), time)
    private suspend fun read() = NextReplicaRecoveryReader(db, tokens, sessions).read(access(), observed())
    private suspend fun initialize() {
        db.clearAllData() // Only this physical testbed fixture, never formal data.
        val (channel, server) = channel { input ->
            when (input.path) {
                "/api/v2/system/identity" -> reply(input).let { it.copy(bytes =
                    it.bytes.toString(Charsets.UTF_8).replace(id(3), observedEpoch).toByteArray()) }
                "/api/v2/devices/register" -> {
                    val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                    MaterialSocketServer.Reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android",
                        isPrimaryEditor = true, structuralEditEnabled = true, capabilityRevision = 1,
                        capabilities = caps.toList(), lastSeenAt = time)).toByteArray())
                }
                "/api/v2/sync/rounds/bootstrap" -> MaterialSocketServer.Reply(json.encodeToString(
                    RoundSyncBootstrapResponse(emptyList(), 20, time, emptyList(), 1, emptyList(), emptyList())).toByteArray())
                "/api/v2/sync/rounds/changes" -> MaterialSocketServer.Reply(json.encodeToString(
                    RoundSyncPullResponse(emptyList(), 20, false, time, 1, emptyList(), emptyList())).toByteArray())
                "/api/v2/sync/rounds/push" -> {
                    val body = if (!rejectMetric) Json.parseToJsonElement(successReply(input).bytes.toString(Charsets.UTF_8)).jsonObject
                    else buildJsonObject { put("results", buildJsonArray { add(buildJsonObject {
                        val operation = wireOperation(input)
                        put("operation_id", operation.getValue("operation_id")); put("entity_type", operation.getValue("entity_type"))
                        put("entity_uuid", operation.getValue("entity_uuid")); put("status", "rejected"); put("error_code", "INVALID_PAYLOAD")
                    }) }) }
                    MaterialSocketServer.Reply(JsonObject(body + mapOf("challenge_contract" to JsonPrimitive(1),
                        "checkpoints" to JsonArray(emptyList()), "births" to JsonArray(emptyList()))).toString().toByteArray())
                }
                else -> error("Unexpected recovery fixture request ${input.path}")
            }
        }
        http = channel; peer = server
        driver().sync()
    }
    private suspend fun queuedMetric(): SyncOutboxEntity {
        val writer = producer()
        writer.writeRounds(writer.captureRounds()) { db.metricDao().insert(metric.copy(id = 0)) }
        return db.syncOutboxDao().getAll().single()
    }

    @Test fun completeInventoryIsReadOnlyAndEmptyQueuesNeverAuthorizeTransition() = runBlocking<Unit> {
        initialize()
        db.openHelper.writableDatabase.execSQL("ANALYZE")
        val original = access(); val preferencesBefore = dataStore.data.first()
        val image = storage.snapshotClosedDatabase()
        val calls = peer.requests.size
        val report = read()
        assertEquals(NextReplicaRecoveryReader.TABLES, report.rowCounts.keys)
        assertEquals(36, report.rowCounts.size)
        assertEquals(original, report.original)
        assertTrue(report.reviewCounts.isEmpty()); assertFalse(report.activationAuthorized)
        assertTrue("complete_difference_preview" in report.remainingGates)
        assertTrue("verified_private_archive" in report.remainingGates)
        assertEquals(id(3), report.checkpoint.syncEpoch); assertEquals(id(99), report.observedEpoch)
        assertEquals(calls, peer.requests.size); assertEquals(preferencesBefore, dataStore.data.first())
        assertArrayEquals(image, storage.snapshotClosedDatabase())
        assertEquals(original, access())
    }

    @Test fun actualSharedManualEntryStopsBeforeRegisterUploadOrSuccessAndKeepsColdState() = runBlocking<Unit> {
        initialize(); observedEpoch = id(99)
        val original = access(); val last = preferences.lastSyncTimestamp.first(); val calls = peer.requests.size
        val before = storage.snapshotClosedDatabase()
        val manager = SyncManager(legacy, driver())
        val error = manager.sync().exceptionOrNull()
        assertTrue(error is NextReplicaRecoveryRequired)
        val report = (error as NextReplicaRecoveryRequired).assessment
        assertFalse(report.activationAuthorized); assertEquals(original, report.original)
        assertEquals(listOf("/api/v2/system/identity"), peer.requests.drop(calls).map { it.path })
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
        assertEquals(last, preferences.lastSyncTimestamp.first()); assertEquals(original, access())
        assertArrayEquals(before, storage.snapshotClosedDatabase())
        assertTrue(rejected { driver().retrySync() } is NextReplicaRecoveryRequired)
        assertEquals(listOf("/api/v2/system/identity", "/api/v2/system/identity"),
            peer.requests.drop(calls).map { it.path })
        assertEquals(original, access()); assertEquals(last, preferences.lastSyncTimestamp.first())
    }

    @Test fun actualUnknownTransmissionAndNeverSentSourceArePreservedNotRelabelled() = runBlocking<Unit> {
        initialize(); val source = queuedMetric()
        assertEquals(1L, read().reviewCounts[ReplicaRecoveryReview.BUSINESS_QUEUE])
        val delivery = requireNotNull(sender(http).sendOperation(access(), source.operationId))
        val original = transmission(NEXT_OPERATION, source.operationId)
        val sourceBefore = db.syncOutboxDao().getById(source.id)
        val image = storage.snapshotClosedDatabase(); val calls = peer.requests.size
        val report = read()
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.UNKNOWN_RESULT])
        assertFalse(report.activationAuthorized); assertEquals(calls, peer.requests.size)
        assertEquals(sourceBefore, db.syncOutboxDao().getById(source.id))
        assertArrayEquals(original.wireBytes, transmission(NEXT_OPERATION, source.operationId).wireBytes)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertEquals(id(3), delivery.access.session.syncEpoch)
        assertArrayEquals(image, storage.snapshotClosedDatabase())
    }

    @Test fun acceptedDataStillNeedsDifferencePreviewEvenWithNoLocalWork() = runBlocking<Unit> {
        initialize(); val source = queuedMetric()
        val request = sender(http)
        request.acceptOperation(requireNotNull(request.sendOperation(access(), source.operationId)))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val report = read()
        assertEquals(1L, report.rowCounts.getValue("metrics"))
        assertEquals(1L, report.rowCounts.getValue("next_acceptances"))
        assertTrue(report.reviewCounts.isEmpty()); assertFalse(report.activationAuthorized)
        assertTrue("complete_difference_preview" in report.remainingGates)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
    }

    @Test fun workerStopsChangedEpochWithoutBackgroundRetryOrLegacyFallback() = runBlocking<Unit> {
        initialize(); val source = queuedMetric(); observedEpoch = id(99)
        val original = access(); val last = preferences.lastSyncTimestamp.first(); val calls = peer.requests.size
        val entry = mockk<AutoSyncWorker.AutoSyncWorkerEntryPoint>()
        every { entry.tokenManager() } returns tokens
        every { entry.businessSyncRepository() } returns driver()
        assertEquals(Result.failure(), AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)) { entry }.doWork())
        assertEquals(listOf("/api/v2/system/identity"), peer.requests.drop(calls).map { it.path })
        assertEquals(source, db.syncOutboxDao().getAll().single())
        assertEquals(original, access()); assertEquals(last, preferences.lastSyncTimestamp.first())
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId))
    }

    @Test fun permanentRejectionIsNotAnUnknownResultOrAnAcceptance() = runBlocking<Unit> {
        initialize(); val source = queuedMetric(); rejectMetric = true
        assertTrue(rejected { driver().sync() } is NextSyncAttention)
        val rejection = db.nextSyncStateDao().rejections().single()
        val original = transmission(NEXT_OPERATION, source.operationId)
        val image = storage.snapshotClosedDatabase()
        val report = read()
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.REJECTION])
        assertNull(report.reviewCounts[ReplicaRecoveryReview.UNKNOWN_RESULT])
        // v5 keeps the immutable refusal separately, not in the v4 dead-letter state.
        assertEquals(source.operationId, rejection.requestId)
        val storedResult = json.decodeFromString<NextSyncOperationResult>(rejection.resultJson)
        assertEquals(source.operationId, storedResult.operationId)
        assertEquals("rejected", storedResult.status); assertEquals("INVALID_PAYLOAD", storedResult.errorCode)
        assertEquals(rejection, db.nextSyncStateDao().rejections().single())
        assertEquals(source.operationId, db.syncOutboxDao().getAll().single().operationId)
        assertTrue(db.syncOutboxDao().getDeadLetters().isEmpty())
        assertArrayEquals(original.wireBytes, transmission(NEXT_OPERATION, source.operationId).wireBytes)
        assertArrayEquals(image, storage.snapshotClosedDatabase())
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertFalse(report.activationAuthorized)
    }

    @Test fun importJournalAndUnfinishedSnapshotRemainExplicitReviewNotConfirmation() = runBlocking<Unit> {
        initialize()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""INSERT INTO next_config_imports
            (importId,accountId,serverInstanceId,syncEpoch,deviceId,archiveHash,identitiesHash,mode,state,receiptHash)
            VALUES(?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(id(45), id(1), id(2), id(3),
            id(4), "a".repeat(64), "b".repeat(64), "replacement", "prepared", null))
        sql.execSQL("""INSERT OR REPLACE INTO next_recovery_state
            (accountId,serverInstanceId,syncEpoch,deviceId,generation,phase,minimumCursor,candidateCursor,snapshotHash,id)
            VALUES(?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(id(1), id(2), id(3),
            id(4), 2L, "awaiting_snapshot", 20L, null, null, 1))
        val report = read()
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.CONFIGURATION_JOURNAL])
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.SNAPSHOT_CANDIDATE])
        assertFalse(report.activationAuthorized)
        assertEquals("prepared", sql.query("SELECT state FROM next_config_imports").use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun pausedTimerCommandsAndMetricDraftAreAllBlockingReviewItems() = runBlocking<Unit> {
        initialize()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val timer = timerHabit.copy(id = db.habitDao().insert(timerHabit.copy(id = 0)))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            db.timeLogDao().insertSyncedTimer(TimeLogEntity(habitId = timer.id, startTime = millis, endTime = null,
                durationSeconds = 0, isPaused = true, pausedAt = millis, date = millis, uuid = id(20)),
                TimerCommandEntity(commandId = id(21), sessionUuid = id(20), sequence = 1, commandType = "start",
                    occurredAt = millis, expectedControlGeneration = 0, activityUuid = timer.uuid, timezone = "Asia/Shanghai"),
                TimerSegmentEntity(sessionUuid = id(20), sequence = 1, startedAt = millis))
            db.completionFollowUpDao().insertPrompt(CompletionMetricPromptEntity(id(30), timer.uuid, entriesJson = "[]"))
        }
        val image = storage.snapshotClosedDatabase(); val report = read()
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.TIMER_QUEUE])
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.UNFINISHED_TIMER])
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.METRIC_DRAFT])
        assertFalse(report.activationAuthorized)
        assertArrayEquals(image, storage.snapshotClosedDatabase())
    }

    @Test fun orphanOriginAndUnknownTransmissionCannotLookLikeAnEmptyQueue() = runBlocking<Unit> {
        initialize(); val source = queuedMetric()
        sender(http).sendOperation(access(), source.operationId)
        db.syncOutboxDao().deleteById(source.id)
        val report = read()
        assertEquals(0L, report.rowCounts.getValue("sync_outbox"))
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.UNVERIFIED_SOURCE])
        assertEquals(1L, report.reviewCounts[ReplicaRecoveryReview.UNKNOWN_RESULT])
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertFalse(report.activationAuthorized)
    }

    @Test fun unknownTableAndDamagedChallengeProofAreRejectedWithoutCleanup() = runBlocking<Unit> {
        initialize()
        db.openHelper.writableDatabase.execSQL("CREATE TABLE future_business_collection(id INTEGER)")
        assertEquals("SYNC_REPLICA_RECOVERY_SCHEMA_REVIEW_REQUIRED", rejected { read() }.message)
        assertEquals(0, count("future_business_collection"))
        db.openHelper.writableDatabase.execSQL("DROP TABLE future_business_collection")
        db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=?", arrayOf("0".repeat(64)))
        val image = storage.snapshotClosedDatabase()
        rejected { read() }
        assertArrayEquals(image, storage.snapshotClosedDatabase())
    }

    @Test fun otherAccountServerSameEpochAndUnknownProtocolCannotGainRecoveryAuthority() = runBlocking<Unit> {
        initialize(); val reader = NextReplicaRecoveryReader(db, tokens, sessions); val original = access()
        for (identity in listOf(observed().copy(serverInstanceId = id(100)), observed().copy(syncEpoch = id(3)),
            observed().copy(protocolVersion = 4), observed().copy(protocolVersion = 6),
            observed().copy(syncEpoch = "not-an-epoch"))) rejected { reader.read(original, identity) }
        tokens.saveLoginSession("other-auth", "other-refresh", "other", id(100), false)
        rejected { reader.read(original, observed()) }
        assertEquals(id(1), db.nextSyncStateDao().rows().single().accountId)
        assertEquals(id(3), db.nextSyncStateDao().rows().single().syncEpoch)
    }

    @Test fun sameAccountReloginInvalidatesOldPreviewButDoesNotClearReplicaBinding() = runBlocking<Unit> {
        initialize(); val reader = NextReplicaRecoveryReader(db, tokens, sessions); val original = access()
        tokens.saveLoginSession("new-auth", "new-refresh", "member", id(1), false)
        rejected { reader.read(original, observed()) }
        val report = read()
        assertNotEquals(original.session.authentication.generation, report.original.session.authentication.generation)
        assertEquals(id(3), report.original.session.syncEpoch); assertFalse(report.activationAuthorized)
        assertTrue("new_epoch_authentication_and_snapshot" in report.remainingGates)
    }

    @Test fun cancelledReadDoesNotBecomeAReportOrChangeStoredState() = runBlocking<Unit> {
        initialize(); val original = access(); val image = storage.snapshotClosedDatabase()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lock = launch { sessions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val reading = async { read() }; yield(); reading.cancelAndJoin()
        release.complete(Unit); lock.join()
        assertTrue(reading.isCancelled); assertEquals(original, access())
        assertArrayEquals(image, storage.snapshotClosedDatabase())
    }
}
