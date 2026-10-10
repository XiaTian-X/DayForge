package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.domain.service.SyncManager
import com.dayforge.sync.AutoSyncWorker
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real discovery, registration, Room cursor and runtime. Only LAN observation and unused v4 engine are mocked. */
@RunWith(AndroidJUnit4::class)
class BusinessSyncRepositoryTest : NextObjectEditorFixture() {
    private val json = Json { encodeDefaults = true }
    private val legacy = mockk<IncrementalSyncRepository>(relaxed = true)
    private fun driver(http: NextSyncHttp): BusinessSyncRepository {
        val monitor = mockk<NetworkMonitor>()
        coEvery { monitor.localNetworks() } returns emptyList()
        val resolver = EndpointResolver(monitor, preferences, tokens, json, SelectedNetworkTransport())
        return BusinessSyncRepository(legacy, resolver, db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
    }

    private fun response(input: MaterialSocketServer.Input, revision: Int = 1): MaterialSocketServer.Reply {
        if (input.path.endsWith("/identity")) return reply(input)
        assertEquals("5", input.headers["x-dayforge-protocol"])
        assertEquals(id(2), input.headers["x-dayforge-server-instance"])
        assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
        return when (input.path) {
            "/api/v2/devices/register" -> {
                val request = json.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                assertEquals(5, request.protocolVersion)
                MaterialSocketServer.Reply(json.encodeToString(DeviceResponse(id(4), request.installationId, "android",
                    isPrimaryEditor = true, structuralEditEnabled = true, capabilityRevision = revision,
                    capabilities = caps.toList(), lastSeenAt = time)).toByteArray())
            }
            "/api/v2/sync/rounds/bootstrap" -> {
                assertTrue(input.target.contains("challenge_contract=1"))
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncBootstrapResponse(emptyList(), 20, time,
                    emptyList(), 1, emptyList(), emptyList())).toByteArray())
            }
            "/api/v2/sync/rounds/changes" -> {
                assertTrue(input.target.contains("challenge_contract=1"))
                MaterialSocketServer.Reply(json.encodeToString(RoundSyncPullResponse(emptyList(), 20, false, time,
                    1, emptyList(), emptyList())).toByteArray())
            }
            "/api/v2/sync/rounds/push" -> {
                val plain = Json.parseToJsonElement(successReply(input).bytes.toString(Charsets.UTF_8)).jsonObject
                MaterialSocketServer.Reply(JsonObject(plain + mapOf("challenge_contract" to JsonPrimitive(1),
                    "checkpoints" to JsonArray(emptyList()), "births" to JsonArray(emptyList())))
                    .toString().toByteArray())
            }
            else -> error("Unexpected production route: ${input.target}")
        }
    }

    @Test fun manualAndWorkerUseSameRealRegistrationAndRoundsChainAcrossColdReopen() = runBlocking<Unit> {
        db.clearAllData()
        db.openHelper.writableDatabase.execSQL("ANALYZE") // SQLite statistics are metadata, not business rows.
        val (http, server) = channel { response(it) }
        val service = driver(http)
        assertTrue(SyncManager(legacy, service).sync().isSuccess)
        val installation = tokens.getOrCreateInstallationId()
        assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
        assertEquals(id(4), tokens.localSyncAccess()!!.deviceId)
        assertEquals(0L, tokens.syncCursor.first())
        assertNotNull(preferences.lastSyncTimestamp.first())
        storage.reopen()
        val entry = mockk<AutoSyncWorker.AutoSyncWorkerEntryPoint>()
        every { entry.tokenManager() } returns tokens
        every { entry.businessSyncRepository() } returns driver(http)
        assertEquals(Result.success(), AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)) { entry }.doWork())
        val registrations = server.requests.filter { it.path.endsWith("/register") }
        assertEquals(2, registrations.size)
        assertTrue(registrations.all {
            json.decodeFromString<DeviceRegisterRequest>(it.body.toString(Charsets.UTF_8)).installationId == installation
        })
        assertEquals(1, server.requests.count { it.path.endsWith("/bootstrap") })
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
    }

    @Test fun formalNavigationTicketCreatesAndUploadsMetricWithoutLegacyRowOrPlainV5Fallback() = runBlocking<Unit> {
        db.clearAllData(); val (http, server) = channel { response(it) }; val service = driver(http)
        service.sync()
        val ticket = requireNotNull(creator.captureForNavigation())
        assertNotNull(ticket.rounds)
        val saved = creatingMetrics().createMetric(metric.copy(id = 0), creationAuthority = ticket)
        val source = db.syncOutboxDao().getAll().single()
        assertNotNull(roundOperationIntent(originalIntent(source).intentJson))
        service.sync()
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertEquals(metric.appearance, db.metricDao().getMetricById(saved)!!.appearance)
        assertEquals(1, server.requests.count { it.path == "/api/v2/sync/rounds/push" })
        storage.reopen(); assertEquals(metric.appearance, db.metricDao().getMetricById(saved)!!.appearance)
    }

    @Test fun nonemptyOrOrphanOrPlainProfileNeverRegistersConvertsOrClears() = runBlocking<Unit> {
        val (http, server) = channel { response(it) }
        val error = rejected { driver(http).sync() }
        assertEquals("SYNC_CONTROLLED_REBUILD_REQUIRED", error.message)
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
        db.clearAllData(); register()
        db.nextSyncStateDao().insert(NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0, "a".repeat(64), "b".repeat(64)))
        rejected { driver(http).sync() }
        assertEquals(0, db.nextSyncStateDao().rows().single().challengeContract)
        db.clearAllData()
        db.syncOutboxDao().upsertState(com.dayforge.data.local.entity.SyncEntityStateEntity("metric", id(101), 1, payloadJson = "{}"))
        rejected { driver(http).sync() }
        assertEquals(0, server.requests.count { it.path.endsWith("/register") })
        assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun reauthenticationDuringRegisterDoesNotPublishResponseOrRunCleanup() = runBlocking<Unit> {
        db.clearAllData()
        val (http, server) = channel { input ->
            if (input.path.endsWith("/register")) runBlocking {
                tokens.saveLoginSession("new", "new-refresh", "next", id(1), false)
            }
            response(input)
        }
        var cleaned = false
        rejected { driver(http).sync(afterSync = { cleaned = true }) }
        assertFalse(cleaned); assertNull(tokens.syncDeviceId.first()); assertNull(tokens.serverInstanceId.first())
        assertTrue(db.nextSyncStateDao().rows().isEmpty())
        assertFalse(server.requests.any { it.path.endsWith("/bootstrap") })
        assertEquals("new", tokens.accessToken.first())
    }

    @Test fun concurrentLocalCreationBeforeRegisterResponseIsPreservedAndRequiresRebuild() = runBlocking<Unit> {
        db.clearAllData()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/register")) runBlocking { db.habitDao().insert(habit.copy(id = 0, appearance = null, completionPolicy = null, planMetadata = null)) }
            response(input)
        }
        assertEquals("SYNC_CONTROLLED_REBUILD_REQUIRED", rejected { driver(http).sync() }.message)
        assertNotNull(db.habitDao().getHabitByUuid(habit.uuid))
        assertTrue(db.syncOutboxDao().getAll().isNotEmpty())
        assertNull(tokens.syncDeviceId.first()); assertTrue(db.nextSyncStateDao().rows().isEmpty())
    }

    @Test fun firstBootstrapCommitRechecksEmptyCacheEvenForAnUnqueuedConcurrentLegacyRow() = runBlocking<Unit> {
        db.clearAllData()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/bootstrap")) runBlocking {
                db.withTransaction {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    db.habitDao().insert(habit.copy(id = 0, appearance = null, completionPolicy = null, planMetadata = null))
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                }
            }
            response(input)
        }
        assertEquals("SYNC_CONTROLLED_REBUILD_REQUIRED", rejected { driver(http).sync() }.message)
        assertNotNull(db.habitDao().getHabitByUuid(habit.uuid)); assertEquals(0, db.syncOutboxDao().count())
        assertTrue(db.nextSyncStateDao().rows().isEmpty()); assertNull(preferences.lastSyncTimestamp.first())
        assertNotNull(tokens.localSyncAccess()) // Registration is not bootstrap success.
    }

    @Test fun successfulV5CleanupKeepsOriginalAuthenticationUntilAllRequestsComplete() = runBlocking<Unit> {
        db.clearAllData(); val original = tokens.authenticationSnapshot()!!.session
        val (http, server) = channel { response(it) }
        var cleaned = false
        assertTrue(SyncManager(legacy, driver(http)).syncAndThen {
            assertEquals(original, tokens.authenticationSnapshot()!!.session)
            assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
            assertTrue(server.requests.any { it.path.endsWith("/changes") })
            tokens.clearAuthenticationTokens(); cleaned = true
        }.isSuccess)
        assertTrue(cleaned); assertNull(tokens.authenticationSnapshot())
    }

    @Test fun actualPermissionAndInvalidRegistrationRepliesAreNotReportedAsConnectivityLoss() = runBlocking<Unit> {
        db.clearAllData()
        for (status in listOf(403, 422, 200)) {
            val (http, _) = channel { input ->
                if (input.path.endsWith("/register")) MaterialSocketServer.Reply(
                    if (status == 200) "{}".toByteArray() else """{"detail":{"code":"INVALID_PAYLOAD"}}""".toByteArray(), status)
                else response(input)
            }
            val manager = SyncManager(legacy, driver(http))
            assertTrue(manager.sync().isFailure)
            assertFalse((manager.syncProgress.first() as com.dayforge.data.model.SyncProgress.Error).isNetworkFailure)
            assertNull(tokens.syncDeviceId.first()); assertTrue(db.nextSyncStateDao().rows().isEmpty())
        }
    }

    @Test fun changedDeviceCapabilityProofCannotBeOverwrittenByLateRegistration() = runBlocking<Unit> {
        db.clearAllData(); val (http, _) = channel { response(it) }; driver(http).sync()
        val before = db.nextSyncStateDao().rows().single()
        val (second, _) = channel { input ->
            if (input.path.endsWith("/register")) runBlocking { tokens.saveDeviceRegistration(id(4), caps - "structure.write", false, 2) }
            response(input)
        }
        rejected { driver(second).sync() }
        assertEquals(2, tokens.localSyncAccess()!!.capabilityRevision)
        assertFalse("structure.write" in tokens.localSyncAccess()!!.capabilities)
        assertEquals(before, db.nextSyncStateDao().rows().single())
    }

    @Test fun exactIdentityReproofRejectsVersionFlipBeforeRegisterAndNoPlainFallback() = runBlocking<Unit> {
        db.clearAllData(); var identities = 0
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input, if (++identities == 1) 5 else 4) else response(input)
        }
        assertTrue(rejected { driver(http).sync() } is SyncProtocolException)
        assertFalse(server.requests.any { it.path.endsWith("/register") })
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
        assertNull(tokens.syncDeviceId.first())
    }

    @Test fun originalReplicaEpochAndDeviceIdentityAreNeverAutomaticallyReplaced() = runBlocking<Unit> {
        db.clearAllData(); register()
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) MaterialSocketServer.Reply(
                reply(input).bytes.toString(Charsets.UTF_8).replace(id(3), id(99)).toByteArray()) else response(input)
        }
        rejected { driver(http).sync() }
        assertEquals(id(3), tokens.syncEpoch.first()); assertEquals(id(4), tokens.syncDeviceId.first())
        assertFalse(server.requests.any { it.path.endsWith("/register") })
        val (second, _) = channel { input ->
            val r = response(input)
            if (input.path.endsWith("/register")) r.copy(bytes = r.bytes.toString(Charsets.UTF_8).replace(id(4), id(99)).toByteArray()) else r
        }
        rejected { driver(second).sync() }
        assertEquals(id(4), tokens.syncDeviceId.first()); assertTrue(db.nextSyncStateDao().rows().isEmpty())
    }

    @Test fun cancellationOfRegisterCannotPublishLateResponseAndColdRetryKeepsInstallation() = runBlocking<Unit> {
        db.clearAllData(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val (http, _) = channel { input ->
            if (input.path.endsWith("/register")) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                response(input).copy(allowClientClose = true)
            } else response(input)
        }
        try {
            val attempt = async { driver(http).sync() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val installation = tokens.getOrCreateInstallationId()
            attempt.cancelAndJoin(); assertNull(tokens.syncDeviceId.first())
            release.countDown(); storage.reopen()
            val (nextHttp, _) = channel { response(it) }; driver(nextHttp).sync()
            assertEquals(installation, tokens.getOrCreateInstallationId())
            assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
        } finally { release.countDown() }
    }

    @Test fun v4DelegatesWithCapturedOwnerAndFutureVersionsFailClosed() = runBlocking<Unit> {
        val (http, _) = channel { reply(it, 4) }
        val original = tokens.authenticationSnapshot()!!.session
        driver(http).sync()
        coVerify(exactly = 1) { legacy.syncForAuthentication(original, any(), null) }
        val (future, _) = channel { reply(it, 6) }
        assertTrue(rejected { driver(future).sync() } is SyncProtocolException)
        coVerify(exactly = 1) { legacy.syncForAuthentication(any(), any(), any()) }
    }

    @Test fun retryOfEmptyV5RegistersAndBootstrapsWithoutLegacyQueueReactivation() = runBlocking<Unit> {
        db.clearAllData(); val (http, server) = channel { response(it) }
        assertTrue(SyncManager(legacy, driver(http)).retrySync().isSuccess)
        assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
        assertNotNull(preferences.lastSyncTimestamp.first())
        assertEquals(1, server.requests.count { it.path.endsWith("/bootstrap") })
        coVerify(exactly = 0) { legacy.retryAllDeadLetters() }
        coVerify(exactly = 0) { legacy.retrySyncForAuthentication(any(), any()) }
    }

    @Test fun settingsRetryReplaysExactV5UnknownResultAcrossColdReopen() = runBlocking<Unit> {
        db.clearAllData(); var loseResponse = true
        val (http, server) = channel { input ->
            val value = response(input)
            if (input.path.endsWith("/push") && loseResponse) value.copy(length = value.bytes.size + 10) else value
        }
        val service = driver(http); service.sync()
        val ticket = requireNotNull(creator.captureForNavigation())
        val saved = creatingMetrics().createMetric(metric.copy(id = 0), creationAuthority = ticket)
        val source = db.syncOutboxDao().getAll().single()
        assertTrue(SyncManager(legacy, service).sync().exceptionOrNull() is java.io.IOException)
        val original = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        val frozen = requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        val failedTimestamp = preferences.lastSyncTimestamp.first()
        storage.reopen(); loseResponse = false
        assertTrue(SyncManager(legacy, driver(http)).retrySync().isSuccess)
        val transmissions = server.requests.filter { it.path.endsWith("/push") }
        assertEquals(2, transmissions.size)
        assertTrue(transmissions.all { it.body.contentEquals(frozen.wireBytes) })
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertTrue(frozen.wireBytes.contentEquals(db.nextRequestDao().transmission(NEXT_OPERATION, source.operationId)!!.wireBytes))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertEquals(metric.appearance, db.metricDao().getMetricById(saved)!!.appearance)
        assertTrue(requireNotNull(preferences.lastSyncTimestamp.first()) >= requireNotNull(failedTimestamp))
        coVerify(exactly = 0) { legacy.retrySyncForAuthentication(any(), any()) }
    }

    @Test fun retryKeepsV5PermanentRejectionAndFrozenSourceWhileIndependentWorkContinues() = runBlocking<Unit> {
        db.clearAllData(); var denied: String? = null
        val (http, server) = channel { input ->
            if (input.path.endsWith("/push") && denied != null) {
                val request = json.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single()
                if (operation.operationId == denied) MaterialSocketServer.Reply(json.encodeToString(RoundSyncPushResponse(
                    listOf(NextSyncOperationResult(operation.operationId, operation.entityType, operation.entityUuid,
                        "rejected", errorCode = "INVALID_PAYLOAD", message = "Needs correction")),
                    1, emptyList(), emptyList())).toByteArray()) else response(input)
            } else response(input)
        }
        val service = driver(http); service.sync()
        val ticket = requireNotNull(creator.captureForNavigation())
        creatingMetrics().createMetric(metric.copy(id = 0), creationAuthority = ticket)
        val row = db.syncOutboxDao().getAll().single(); denied = row.operationId
        assertTrue(rejected { service.sync() } is NextSyncAttention)
        val original = db.nextRequestDao().origin(NEXT_OPERATION, row.operationId)
        val frozen = db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId)!!.wireBytes
        val rejection = db.nextSyncStateDao().rejections().single()
        val lastSync = preferences.lastSyncTimestamp.first()
        storage.reopen()
        // Existing creator points to the prior Room instance; reconstruct the real creator after cold reopen.
        creator = NextObjectCreator(db, tokens, sessions, icons)
        val nextTicket = requireNotNull(creator.captureForNavigation())
        creatingMetrics().createMetric(metric.copy(id = 0, uuid = id(190), name = "Independent"), creationAuthority = nextTicket)
        val independent = db.syncOutboxDao().getAll().single { it.operationId != row.operationId }
        assertTrue(SyncManager(legacy, driver(http)).retrySync().exceptionOrNull() is NextSyncAttention)
        assertEquals(listOf(row), db.syncOutboxDao().getAll())
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
        assertTrue(frozen.contentEquals(db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId)!!.wireBytes))
        assertEquals(listOf(rejection), db.nextSyncStateDao().rejections())
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, independent.operationId))
        assertEquals(lastSync, preferences.lastSyncTimestamp.first())
        assertEquals(2, server.requests.count { it.path.endsWith("/push") })
        assertTrue(server.requests.count { it.path.endsWith("/changes") } >= 3)
        coVerify(exactly = 0) { legacy.retrySyncForAuthentication(any(), any()) }
    }

    @Test fun userRetryUsesCapturedV4EntryButFutureVersionCannotReactivateAnyLegacyWork() = runBlocking<Unit> {
        val (http, _) = channel { reply(it, 4) }
        val original = tokens.authenticationSnapshot()!!.session
        driver(http).retrySync()
        coVerify(exactly = 1) { legacy.retrySyncForAuthentication(original, any()) }
        coVerify(exactly = 0) { legacy.syncForAuthentication(any(), any(), any()) }
        val (future, _) = channel { reply(it, 6) }
        assertTrue(rejected { driver(future).retrySync() } is SyncProtocolException)
        coVerify(exactly = 1) { legacy.retrySyncForAuthentication(any(), any()) }
        coVerify(exactly = 0) { legacy.retryAllDeadLetters() }
    }
}
