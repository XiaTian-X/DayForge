package com.dayforge.data.repository

import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.dto.DeviceResponse
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.api.dto.SyncV2OperationResult
import com.dayforge.data.api.dto.SyncV2BootstrapResponse
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2PullResponse
import com.dayforge.data.api.dto.SyncV2PushResponse
import com.dayforge.data.api.dto.TokenResponse
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSyncRepositoryTest {

    @Test
    fun `fresh client bootstraps once and persists the high water cursor`() = runTest {
        val fixture = fixture(bootstrapped = false)
        val change = SyncV2Change(
            sequence = 0,
            entityType = "plan_node",
            entityUuid = UUID.randomUUID().toString(),
            operation = "upsert",
            revision = 1,
            payload = buildJsonObject {
                put("node_kind", "goal")
                put("title", "Server goal")
            },
            changedAt = "2026-08-10T00:00:00Z"
        )
        coEvery { fixture.api.bootstrap("device-id") } returns SyncV2BootstrapResponse(
            changes = listOf(change),
            nextCursor = 12,
            serverTime = "2026-08-10T00:00:01Z"
        )

        fixture.repository.sync()

        coVerify(exactly = 1) { fixture.merger.apply(listOf(change)) }
        coVerify(exactly = 1) { fixture.tokenManager.saveSyncCursor(12) }
        coVerify(exactly = 0) { fixture.api.pull(any(), any(), any()) }
    }

    @Test
    fun `offline queue upload reports durable progress before pulling`() = runTest {
        val fixture = fixture()
        val entityUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedRow(1, entityUuid, "Offline goal")
        coEvery { fixture.api.push(any()) } answers {
            val operation = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>().operations.single()
            SyncV2PushResponse(
                listOf(
                    SyncV2OperationResult(
                        operationId = operation.operationId,
                        entityType = operation.entityType,
                        entityUuid = operation.entityUuid,
                        status = "applied",
                        revision = 2,
                        entity = operation.payload
                    )
                )
            )
        }
        val progress = mutableListOf<SyncProgress>()

        fixture.repository.sync(progress::add)

        assertEquals(SyncProgress.UploadingChanges(0, 1), progress[0])
        assertEquals(SyncProgress.UploadingChanges(1, 1), progress[1])
        assertEquals(SyncProgress.Downloading, progress[2])
        assertEquals(0, fixture.outbox.count())
        coVerify(exactly = 1) { fixture.api.pull("device-id", 0, any()) }
    }

    @Test
    fun `lost push response replays the exact durable operation`() = runTest {
        val fixture = fixture()
        val entityUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedRow(1, entityUuid, "Durable goal")
        val pushed = mutableListOf<com.dayforge.data.api.dto.SyncV2Operation>()
        coEvery { fixture.api.push(any()) } answers {
            val operation = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>().operations.single()
            pushed += operation
            if (pushed.size == 1) throw IOException("response lost")
            SyncV2PushResponse(
                listOf(
                    SyncV2OperationResult(
                        operationId = operation.operationId,
                        entityType = operation.entityType,
                        entityUuid = operation.entityUuid,
                        status = "already_applied",
                        revision = 1,
                        entity = operation.payload
                    )
                )
            )
        }

        val interrupted = runCatching { fixture.repository.sync() }
        assertTrue(interrupted.isFailure)
        assertEquals(1, fixture.outbox.count())

        fixture.repository.sync()

        assertEquals(2, pushed.size)
        assertEquals(pushed[0], pushed[1])
        assertEquals(0, fixture.outbox.count())
    }

    @Test
    fun `second push batch interruption retains only the unacknowledged batch`() = runTest {
        val fixture = fixture()
        repeat(101) { index ->
            fixture.outbox.rows += preparedRow(
                id = (index + 1).toLong(),
                entityUuid = UUID.randomUUID().toString(),
                title = "Queued goal $index"
            )
        }
        val batchSizes = mutableListOf<Int>()
        val interruptedOperationIds = mutableListOf<String>()
        var pushCount = 0
        coEvery { fixture.api.push(any()) } answers {
            val request = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>()
            batchSizes += request.operations.size
            pushCount += 1
            if (pushCount == 2) {
                interruptedOperationIds += request.operations.single().operationId
                throw IOException("second batch interrupted")
            }
            if (pushCount == 3) {
                interruptedOperationIds += request.operations.single().operationId
            }
            SyncV2PushResponse(
                request.operations.map { operation ->
                    SyncV2OperationResult(
                        operationId = operation.operationId,
                        entityType = operation.entityType,
                        entityUuid = operation.entityUuid,
                        status = if (pushCount == 3) "already_applied" else "applied",
                        revision = 1,
                        entity = operation.payload
                    )
                }
            )
        }

        val interrupted = runCatching { fixture.repository.sync() }
        assertTrue(interrupted.isFailure)
        assertEquals(1, fixture.outbox.count())

        fixture.repository.sync()

        assertEquals(listOf(100, 1, 1), batchSizes)
        assertEquals(2, interruptedOperationIds.size)
        assertEquals(interruptedOperationIds[0], interruptedOperationIds[1])
        assertEquals(0, fixture.outbox.count())
    }

    @Test
    fun `second pull page interruption resumes from the persisted page cursor`() = runTest {
        val fixture = fixture()
        val first = change(sequence = 5, title = "First page")
        val second = change(sequence = 6, title = "Second page")
        coEvery { fixture.api.pull("device-id", 0, any()) } returns SyncV2PullResponse(
            changes = listOf(first),
            nextCursor = 5,
            hasMore = true,
            serverTime = "2026-08-05T00:00:00Z"
        )
        var secondPageAttempts = 0
        coEvery { fixture.api.pull("device-id", 5, any()) } answers {
            secondPageAttempts += 1
            if (secondPageAttempts == 1) throw IOException("second page interrupted")
            SyncV2PullResponse(
                changes = listOf(second),
                nextCursor = 6,
                hasMore = false,
                serverTime = "2026-08-05T00:00:01Z"
            )
        }

        val interrupted = runCatching { fixture.repository.sync() }
        assertTrue(interrupted.isFailure)
        coVerify(exactly = 1) { fixture.merger.apply(listOf(first)) }
        coVerify(exactly = 1) { fixture.tokenManager.saveSyncCursor(5) }

        every { fixture.tokenManager.syncCursor } returns flowOf(5L)
        fixture.repository.sync()

        coVerify(exactly = 1) { fixture.api.pull("device-id", 0, any()) }
        coVerify(exactly = 2) { fixture.api.pull("device-id", 5, any()) }
        coVerify(exactly = 1) { fixture.merger.apply(listOf(second)) }
        coVerify(exactly = 1) { fixture.tokenManager.saveSyncCursor(6) }
    }

    @Test
    fun `non advancing pull page is rejected before merge and cursor persistence`() = runTest {
        val fixture = fixture()
        coEvery { fixture.api.pull("device-id", 0, any()) } returns SyncV2PullResponse(
            changes = emptyList(),
            nextCursor = 0,
            hasMore = true,
            serverTime = "2026-08-05T00:00:00Z"
        )

        val result = runCatching { fixture.repository.sync() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("空分页") == true)
        coVerify(exactly = 1) { fixture.api.pull("device-id", 0, any()) }
        coVerify(exactly = 0) { fixture.merger.apply(any()) }
        coVerify(exactly = 0) { fixture.tokenManager.saveSyncCursor(any()) }
    }

    @Test
    fun `child deletion is pushed before parent goal deletion`() = runTest {
        val fixture = fixture()
        val childUuid = UUID.randomUUID().toString()
        val goalUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedDeleteRow(1, childUuid, HabitType.CHECK_IN)
        fixture.outbox.rows += preparedDeleteRow(2, goalUuid, HabitType.GOAL)
        val pushedEntities = mutableListOf<String>()
        coEvery { fixture.api.push(any()) } answers {
            val request = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>()
            pushedEntities += request.operations.map { it.entityUuid }
            SyncV2PushResponse(
                request.operations.map { operation ->
                    SyncV2OperationResult(
                        operationId = operation.operationId,
                        entityType = operation.entityType,
                        entityUuid = operation.entityUuid,
                        status = "applied",
                        revision = 2,
                        entity = operation.payload
                    )
                }
            )
        }

        fixture.repository.sync()

        assertEquals(listOf(childUuid, goalUuid), pushedEntities)
    }

    @Test
    fun `malformed incremental change on clean cache recovers from bootstrap`() = runTest {
        val fixture = fixture()
        val malformed = SyncV2Change(
            sequence = 7,
            entityType = "plan_node",
            entityUuid = UUID.randomUUID().toString(),
            operation = "upsert",
            revision = 1,
            payload = buildJsonObject { put("node_kind", "goal") },
            changedAt = "2026-08-05T00:00:00Z"
        )
        coEvery { fixture.api.pull("device-id", 0, any()) } returns SyncV2PullResponse(
            changes = listOf(malformed),
            nextCursor = 7,
            hasMore = false,
            serverTime = "2026-08-05T00:00:00Z"
        )
        coEvery { fixture.merger.apply(listOf(malformed)) } throws
            SyncMergeException(malformed, "missing title")
        coEvery { fixture.api.bootstrap("device-id") } returns SyncV2BootstrapResponse(
            changes = emptyList(),
            nextCursor = 9,
            serverTime = "2026-08-05T00:00:01Z"
        )

        fixture.repository.sync()

        coVerify(exactly = 1) { fixture.merger.replaceWithBootstrap(emptyList()) }
        coVerify(exactly = 1) { fixture.tokenManager.saveSyncCursor(9) }
        coVerify(exactly = 0) { fixture.tokenManager.saveSyncCursor(7) }
    }

    @Test
    fun `conflict preserves newest local edit and waits for a user decision`() = runTest {
        val fixture = fixture()
        val entityUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedRow(id = 1, entityUuid = entityUuid, title = "Snapshot")
        fixture.outbox.states["plan_node" to entityUuid] = state(entityUuid, 1)
        coEvery { fixture.habitDao.getHabitByUuid(entityUuid) } returns goal(entityUuid, "Newest local edit")
        coEvery {
            fixture.merger.preserveConflictAtomically(
                recordType = any(),
                localEntityUuid = any(),
                operationId = any(),
                action = any(),
                referenceUuid = any(),
                baseRevision = any(),
                entityType = any(),
                wireEntityUuid = any(),
                revision = any(),
                basePayload = any(),
                localPayload = any(),
                serverPayload = any(),
                conflictingFields = any(),
                conflictKind = any(),
                errorCode = any(),
                message = any()
            )
        } coAnswers {
            fixture.conflicts.insert(
                SyncConflictEntity(
                    operationId = fixture.outbox.rows.first().operationId,
                    recordType = "habit",
                    localEntityUuid = entityUuid,
                    wireEntityUuid = entityUuid,
                    entityType = "plan_node",
                    action = "upsert",
                    referenceUuid = null,
                    baseRevision = 1,
                    serverRevision = 2,
                    basePayloadJson = null,
                    localPayloadJson = buildJsonObject {
                        put("node_kind", "goal")
                        put("title", "Newest local edit")
                    }.toString(),
                    serverPayloadJson = buildJsonObject {
                        put("node_kind", "goal")
                        put("title", "Server title")
                    }.toString(),
                    conflictingFieldsJson = "[\"title\"]",
                    conflictKind = "overlapping_fields",
                    errorCode = "REVISION_CONFLICT",
                    message = "title changed"
                )
            )
            fixture.outbox.deleteByEntity("habit", entityUuid)
        }

        coEvery { fixture.api.push(any()) } coAnswers {
            val request = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>()
            fixture.outbox.rows += SyncOutboxEntity(
                id = 2,
                operationId = UUID.randomUUID().toString(),
                recordType = "habit",
                entityUuid = entityUuid,
                wireEntityUuid = entityUuid,
                action = "upsert"
            )
            SyncV2PushResponse(
                listOf(
                    SyncV2OperationResult(
                        operationId = request.operations.single().operationId,
                        entityType = "plan_node",
                        entityUuid = entityUuid,
                        status = "conflict",
                        revision = 2,
                        errorCode = "REVISION_CONFLICT",
                        entity = buildJsonObject {
                            put("node_kind", "goal")
                            put("title", "Server title")
                        },
                        conflictingFields = listOf("title"),
                        conflictKind = "overlapping_fields"
                    )
                )
            )
        }

        val failure = runCatching { fixture.repository.sync() }.exceptionOrNull()

        assertTrue(failure is SyncRequiresAttentionException)
        assertEquals(0, fixture.outbox.count())
        assertEquals(1, fixture.conflicts.countUnresolved())
        assertTrue(fixture.conflicts.rows.single().localPayloadJson.contains("Newest local edit"))
        coVerify(exactly = 1) {
            fixture.merger.preserveConflictAtomically(
                recordType = "habit",
                localEntityUuid = entityUuid,
                operationId = any(),
                action = "upsert",
                referenceUuid = null,
                baseRevision = 1,
                entityType = "plan_node",
                wireEntityUuid = entityUuid,
                revision = 2,
                basePayload = any(),
                localPayload = match { it["title"]?.toString()?.contains("Newest local edit") == true },
                serverPayload = any(),
                conflictingFields = listOf("title"),
                conflictKind = "overlapping_fields",
                errorCode = "REVISION_CONFLICT",
                message = null
            )
        }
    }

    @Test
    fun `malformed conflict is quarantined and does not block pull`() = runTest {
        val fixture = fixture()
        val entityUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedRow(id = 1, entityUuid = entityUuid, title = "Snapshot")
        coEvery { fixture.api.push(any()) } answers {
            val operation = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>().operations.single()
            SyncV2PushResponse(
                listOf(
                    SyncV2OperationResult(
                        operationId = operation.operationId,
                        entityType = operation.entityType,
                        entityUuid = operation.entityUuid,
                        status = "conflict",
                        message = "missing canonical snapshot"
                    )
                )
            )
        }

        var requiresAttention = false
        try {
            fixture.repository.sync()
        } catch (_: SyncRequiresAttentionException) {
            requiresAttention = true
        }

        assertEquals(true, requiresAttention)
        assertEquals(0, fixture.outbox.count())
        assertEquals(1, fixture.outbox.countDeadLetters())
        assertEquals("MALFORMED_CONFLICT", fixture.outbox.getDeadLetters().single().errorCode)
        coVerify(exactly = 1) { fixture.api.pull("device-id", 0, any()) }
    }

    @Test
    fun `permanent rejection is quarantined and pull still completes`() = runTest {
        val fixture = fixture()
        val entityUuid = UUID.randomUUID().toString()
        fixture.outbox.rows += preparedRow(id = 1, entityUuid = entityUuid, title = "Invalid")
        val rejectedOperationId = fixture.outbox.rows.single().operationId
        val pushedOperationIds = mutableListOf<String>()
        coEvery { fixture.api.push(any()) } answers {
            val request = firstArg<com.dayforge.data.api.dto.SyncV2PushRequest>()
            pushedOperationIds += request.operations.single().operationId
            SyncV2PushResponse(
                listOf(
                    if (pushedOperationIds.size == 1) {
                        SyncV2OperationResult(
                            operationId = request.operations.single().operationId,
                            entityType = "plan_node",
                            entityUuid = entityUuid,
                            status = "rejected",
                            errorCode = "INVALID_PAYLOAD",
                            message = "invalid"
                        )
                    } else {
                        SyncV2OperationResult(
                            operationId = request.operations.single().operationId,
                            entityType = "plan_node",
                            entityUuid = entityUuid,
                            status = "applied",
                            revision = 1,
                            entity = request.operations.single().payload
                        )
                    }
                )
            )
        }

        var requiresAttention = false
        try {
            fixture.repository.sync()
        } catch (_: SyncRequiresAttentionException) {
            requiresAttention = true
        }

        assertEquals(true, requiresAttention)
        assertEquals(0, fixture.outbox.count())
        assertEquals(1, fixture.outbox.countDeadLetters())
        coVerify(exactly = 1) { fixture.api.pull("device-id", 0, any()) }

        fixture.repository.retryAllDeadLetters()
        assertEquals(1, fixture.outbox.count())
        assertEquals(0, fixture.outbox.countDeadLetters())
        val retried = fixture.outbox.getAll().single()
        assertEquals(false, retried.operationId == rejectedOperationId)
        assertEquals(null, retried.payloadJson)
        assertEquals(null, retried.attemptedAt)

        coEvery { fixture.habitDao.getHabitByUuid(entityUuid) } returns goal(entityUuid, "Corrected")
        fixture.repository.sync()

        assertEquals(0, fixture.outbox.count())
        assertEquals(0, fixture.outbox.countDeadLetters())
        assertEquals(2, pushedOperationIds.size)
        assertEquals(false, pushedOperationIds[0] == pushedOperationIds[1])
    }

    @Test
    fun `discarding a rejected change requires a canonical bootstrap`() = runTest {
        val fixture = fixture()
        fixture.outbox.rows += preparedRow(1, UUID.randomUUID().toString(), "Rejected").copy(
            deadLetteredAt = 1,
            errorCode = "INVALID_PAYLOAD"
        )

        fixture.repository.discardDeadLetter(1)

        coVerify(exactly = 1) { fixture.tokenManager.requireSyncBootstrap() }
        assertEquals(0, fixture.outbox.countDeadLetters())
    }

    @Test
    fun `legacy session backfills account id through refresh before sync`() = runTest {
        val fixture = fixture(accountId = null)

        fixture.repository.sync()

        coVerify(exactly = 1) { fixture.authApi.refreshToken(match { it.refreshToken == "legacy-refresh" }) }
        coVerify(exactly = 1) {
            fixture.tokenManager.saveTokens(
                accessToken = "new-access",
                refreshToken = "new-refresh",
                email = "member",
                userId = "account-id",
                isAdmin = false
            )
        }
        coVerify(exactly = 1) { fixture.tokenManager.prepareSyncAccount("account-id") }
    }

    @Test
    fun `different server identity stops before device registration or upload`() = runTest {
        val fixture = fixture(serverInstanceId = "trusted-server")
        coEvery { fixture.api.identity() } returns identity(instanceId = "other-server")

        val result = runCatching { fixture.repository.sync() }

        assertTrue(result.exceptionOrNull() is ServerIdentityMismatchException)
        coVerify(exactly = 0) { fixture.api.registerDevice(any()) }
        coVerify(exactly = 0) { fixture.api.push(any()) }
    }

    @Test
    fun `clean epoch change replaces local replica from new bootstrap`() = runTest {
        val before = contractIdentity("server/identity-before-response.json")
        val after = contractIdentity("server/identity-after-epoch-reset-response.json")
        val fixture = fixture(
            serverInstanceId = before.serverInstanceId,
            syncEpoch = before.syncEpoch
        )
        coEvery { fixture.api.identity() } returns after
        coEvery { fixture.api.bootstrap("device-id") } returns SyncV2BootstrapResponse(
            changes = emptyList(),
            nextCursor = 0,
            serverTime = "2026-08-14T00:00:00Z"
        )

        fixture.repository.sync()

        coVerify(exactly = 1) {
            fixture.tokenManager.resetReplicaForEpoch(after.serverInstanceId, after.syncEpoch)
        }
        coVerify(exactly = 1) { fixture.merger.replaceWithBootstrap(emptyList()) }
        coVerify(exactly = 0) { fixture.api.pull(any(), any(), any()) }
    }

    private fun fixture(
        accountId: String? = "account-id",
        bootstrapped: Boolean = true,
        serverInstanceId: String? = "server",
        syncEpoch: String? = "epoch"
    ): Fixture {
        val api = mockk<SyncV2Api>()
        val endpointResolver = mockk<EndpointResolver>(relaxed = true)
        val authApi = mockk<AuthApi>(relaxed = true)
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val preferences = mockk<PreferencesManager>(relaxed = true)
        val habitDao = mockk<HabitDao>(relaxed = true)
        val completionDao = mockk<CompletionDao>(relaxed = true)
        val timeLogDao = mockk<TimeLogDao>(relaxed = true)
        val metricDao = mockk<MetricDao>(relaxed = true)
        val metricLogDao = mockk<MetricLogDao>(relaxed = true)
        val linkDao = mockk<HabitMetricLinkDao>(relaxed = true)
        val outbox = FakeOutboxDao()
        val conflicts = FakeConflictDao()
        val merger = mockk<SyncV2Merger>(relaxed = true)
        val timerSyncRepository = mockk<TimerSyncRepository>(relaxed = true)
        val json = Json { ignoreUnknownKeys = true }

        every { tokenManager.userId } returns flowOf(accountId)
        every { tokenManager.refreshToken } returns flowOf("legacy-refresh")
        every { tokenManager.isSyncBootstrapped } returns flowOf(bootstrapped)
        every { tokenManager.syncCursor } returns flowOf(0L)
        every { tokenManager.syncDeviceId } returns flowOf(null)
        every { tokenManager.serverInstanceId } returns flowOf(serverInstanceId)
        every { tokenManager.syncEpoch } returns flowOf(syncEpoch)
        coEvery { timeLogDao.getActiveTimeLog() } returns null
        coEvery { tokenManager.getOrCreateInstallationId() } returns "installation-id"
        coEvery { authApi.refreshToken(any()) } returns TokenResponse(
            accessToken = "new-access",
            refreshToken = "new-refresh",
            userId = "account-id",
            username = "member",
            isAdmin = false
        )
        coEvery { api.registerDevice(any()) } returns DeviceResponse(
            deviceId = "device-id",
            installationId = "installation-id",
            platform = "android"
        )
        coEvery { api.identity() } returns identity()
        coEvery { endpointResolver.resolve() } returns null
        coEvery { api.pull(any(), any(), any()) } returns SyncV2PullResponse(
            changes = emptyList(),
            nextCursor = 0,
            hasMore = false,
            serverTime = "2026-08-05T00:00:00Z"
        )

        return Fixture(
            api,
            authApi,
            tokenManager,
            habitDao,
            outbox,
            merger,
            conflicts,
            IncrementalSyncRepository(
                api,
                endpointResolver,
                authApi,
                tokenManager,
                preferences,
                habitDao,
                completionDao,
                timeLogDao,
                metricDao,
                metricLogDao,
                linkDao,
                outbox,
                conflicts,
                timerSyncRepository,
                merger,
                json,
                AccountSessionCoordinator()
            )
        )
    }

    private fun identity(
        instanceId: String = "server",
        syncEpoch: String = "epoch"
    ) = ServerIdentityResponse(
        serverInstanceId = instanceId,
        syncEpoch = syncEpoch,
        protocolVersion = 4,
        capabilities = listOf("sync_v2", "timer_commands", "device_capabilities"),
        serverTime = "2026-08-14T00:00:00Z"
    )

    private fun contractIdentity(path: String): ServerIdentityResponse = Json.decodeFromString(
        requireNotNull(javaClass.classLoader?.getResource("sync-v2/$path")) {
            "Missing shared contract fixture: $path"
        }.readText()
    )

    private fun preparedRow(id: Long, entityUuid: String, title: String): SyncOutboxEntity {
        val payload = """{"node_kind":"goal","title":"$title","goal":{"evaluation_policy":{"schema_version":1,"type":"manual"}}}"""
        return SyncOutboxEntity(
            id = id,
            operationId = UUID.randomUUID().toString(),
            recordType = "habit",
            entityUuid = entityUuid,
            wireEntityUuid = entityUuid,
            action = "upsert",
            payloadJson = payload,
            baseRevision = 1,
            attemptedAt = 1,
            attemptCount = 1
        )
    }

    private fun change(sequence: Long, title: String) = SyncV2Change(
        sequence = sequence,
        entityType = "plan_node",
        entityUuid = UUID.randomUUID().toString(),
        operation = "upsert",
        revision = 1,
        payload = buildJsonObject {
            put("node_kind", "goal")
            put("title", title)
        },
        changedAt = "2026-08-05T00:00:00Z"
    )

    private fun preparedDeleteRow(
        id: Long,
        entityUuid: String,
        habitType: HabitType
    ) = SyncOutboxEntity(
        id = id,
        operationId = UUID.randomUUID().toString(),
        recordType = "habit",
        entityUuid = entityUuid,
        wireEntityUuid = entityUuid,
        action = "delete",
        referenceUuid = habitType.name,
        payloadJson = if (habitType == HabitType.GOAL) {
            """{"child_policy":"detach_children"}"""
        } else {
            "{}"
        },
        baseRevision = 1,
        attemptedAt = 1,
        attemptCount = 1
    )

    private fun goal(uuid: String, name: String) = HabitEntity(
        id = 1,
        name = name,
        habitType = HabitType.GOAL,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        uuid = uuid
    )

    private fun state(entityUuid: String, revision: Long) = SyncEntityStateEntity(
        entityType = "plan_node",
        entityUuid = entityUuid,
        revision = revision,
        deleted = false
    )

    private data class Fixture(
        val api: SyncV2Api,
        val authApi: AuthApi,
        val tokenManager: TokenManager,
        val habitDao: HabitDao,
        val outbox: FakeOutboxDao,
        val merger: SyncV2Merger,
        val conflicts: FakeConflictDao,
        val repository: IncrementalSyncRepository
    )

    private class FakeOutboxDao : SyncOutboxDao {
        val rows = mutableListOf<SyncOutboxEntity>()
        val states = mutableMapOf<Pair<String, String>, SyncEntityStateEntity>()

        override suspend fun insert(row: SyncOutboxEntity): Long {
            val id = if (row.id == 0L) (rows.maxOfOrNull { it.id } ?: 0L) + 1 else row.id
            rows += row.copy(id = id)
            return id
        }

        override suspend fun getAll() = rows.filter { it.deadLetteredAt == null }.sortedBy { it.id }
        override suspend fun count() = rows.count { it.deadLetteredAt == null }
        override fun observePendingCount() = flowOf(rows.count { it.deadLetteredAt == null })
        override suspend fun countDeadLetters() = rows.count { it.deadLetteredAt != null }
        override suspend fun getDeadLetters() = rows.filter { it.deadLetteredAt != null }
        override fun observeDeadLetters() = flowOf(rows.filter { it.deadLetteredAt != null })
        override suspend fun deleteDeadLetter(id: Long) {
            rows.removeAll { it.id == id && it.deadLetteredAt != null }
        }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteByIds(ids: List<Long>) { rows.removeAll { it.id in ids } }
        override suspend fun deleteByEntity(recordType: String, entityUuid: String) {
            rows.removeAll { it.recordType == recordType && it.entityUuid == entityUuid }
        }

        override suspend fun hasNewerPending(recordType: String, entityUuid: String, afterId: Long) =
            rows.any { it.recordType == recordType && it.entityUuid == entityUuid && it.id > afterId }

        override suspend fun markPrepared(id: Long, wireEntityUuid: String, action: String, payloadJson: String, baseRevision: Long?, basePayloadJson: String?, attemptedAt: Long) {
            replace(id) { it.copy(wireEntityUuid = wireEntityUuid, action = action, payloadJson = payloadJson, baseRevision = baseRevision, basePayloadJson = basePayloadJson, attemptedAt = attemptedAt, attemptCount = it.attemptCount + 1, lastError = null, errorCode = null) }
        }

        override suspend fun markError(id: Long, error: String) { replace(id) { it.copy(lastError = error) } }
        override suspend fun markDeadLetter(id: Long, errorCode: String?, error: String, deadLetteredAt: Long) {
            replace(id) { it.copy(errorCode = errorCode, lastError = error, deadLetteredAt = deadLetteredAt) }
        }

        override suspend fun retryDeadLetter(id: Long, newOperationId: String) {
            replace(id) {
                it.copy(
                    operationId = newOperationId,
                    wireEntityUuid = it.entityUuid,
                    payloadJson = null,
                    baseRevision = null,
                    basePayloadJson = null,
                    errorCode = null,
                    lastError = null,
                    deadLetteredAt = null,
                    attemptedAt = null
                )
            }
        }

        override suspend fun getState(entityType: String, entityUuid: String) = states[entityType to entityUuid]
        override suspend fun upsertState(state: SyncEntityStateEntity) { states[state.entityType to state.entityUuid] = state }
        override suspend fun deleteState(entityType: String, entityUuid: String) { states.remove(entityType to entityUuid) }

        private fun replace(id: Long, update: (SyncOutboxEntity) -> SyncOutboxEntity) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = update(rows[index])
        }
    }

    private class FakeConflictDao : SyncConflictDao {
        val rows = mutableListOf<SyncConflictEntity>()

        override suspend fun insert(conflict: SyncConflictEntity): Long {
            val id = if (conflict.id == 0L) (rows.maxOfOrNull { it.id } ?: 0L) + 1 else conflict.id
            rows += conflict.copy(id = id)
            return id
        }

        override suspend fun getUnresolved() = rows.filter { it.status == "unresolved" }
        override fun observeUnresolved() = flowOf(rows.filter { it.status == "unresolved" })
        override suspend fun countUnresolved() = rows.count { it.status == "unresolved" }
        override suspend fun getUnresolvedById(id: Long) =
            rows.firstOrNull { it.id == id && it.status == "unresolved" }

        override suspend fun markResolved(id: Long, resolution: String, resolvedAt: Long): Int {
            val index = rows.indexOfFirst { it.id == id && it.status == "unresolved" }
            if (index < 0) return 0
            rows[index] = rows[index].copy(
                status = "resolved",
                resolution = resolution,
                resolvedAt = resolvedAt
            )
            return 1
        }
    }
}
