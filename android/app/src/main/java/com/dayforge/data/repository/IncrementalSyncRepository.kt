package com.dayforge.data.repository

import android.os.Build
import com.dayforge.BuildConfig
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.api.dto.DeviceEditingUpdate
import com.dayforge.data.api.dto.DeviceResponse
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.SyncV2PullResponse
import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Android v2 synchronization engine.
 *
 * Outbox rows are never removed merely because an HTTP call returned. Each row
 * remains durable until the matching operation result explicitly acknowledges it.
 */
class IncrementalSyncRepository(
    private val api: SyncV2Api,
    private val endpointResolver: EndpointResolver,
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val preferencesManager: PreferencesManager,
    private val habitDao: HabitDao,
    private val completionDao: CompletionDao,
    private val timeLogDao: TimeLogDao,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val linkDao: HabitMetricLinkDao,
    private val outboxDao: SyncOutboxDao,
    private val conflictDao: SyncConflictDao,
    private val timerSyncRepository: TimerSyncRepository,
    private val merger: SyncV2Merger,
    private val json: Json,
    private val accountSessionCoordinator: AccountSessionCoordinator
) {
    suspend fun sync(progressCallback: (SyncProgress) -> Unit = {}) =
        accountSessionCoordinator.exclusive { syncInternal(progressCallback) }

    suspend fun <T> syncAndThen(
        progressCallback: (SyncProgress) -> Unit = {},
        afterSync: suspend () -> T
    ): T = accountSessionCoordinator.exclusive {
        syncInternal(progressCallback)
        afterSync()
    }

    private suspend fun syncInternal(progressCallback: (SyncProgress) -> Unit) {
        tokenManager.migrateLegacyTokenStorage()
        val accountId = ensureAccountId()
        tokenManager.prepareSyncAccount(accountId)
        val resolvedIdentity = endpointResolver.resolve()
        val epochChanged = verifyServerIdentity(resolvedIdentity)
        val deviceId = ensureDevice()

        // Upload first so offline-created data is part of the bootstrap snapshot.
        val uploadTotal = outboxDao.count()
        var uploaded = 0
        progressCallback(SyncProgress.UploadingChanges(uploaded, uploadTotal))
        var rounds = 0
        while (outboxDao.count() > 0) {
            if (++rounds > MAX_PUSH_ROUNDS) {
                throw IllegalStateException("本地待同步队列超过单次处理上限")
            }
            val hasMoreToUpload = pushOneBatch(deviceId)
            uploaded = uploadTotal - outboxDao.count()
            progressCallback(SyncProgress.UploadingChanges(uploaded, uploadTotal))
            if (!hasMoreToUpload) break
        }
        if (outboxDao.count() > 0) {
            throw IllegalStateException("仍有本地修改未被服务器确认")
        }

        // Plan nodes must exist before a queued timer start is accepted. Timer
        // completion then becomes a normal server change pulled below.
        timerSyncRepository.pushPending(deviceId)

        if (epochChanged) {
            progressCallback(SyncProgress.Recovering)
            val snapshot = api.bootstrap(deviceId)
            merger.replaceWithBootstrap(snapshot.changes)
            tokenManager.saveSyncCursor(snapshot.nextCursor)
        } else if (!tokenManager.isSyncBootstrapped.first()) {
            val rejected = outboxDao.countDeadLetters() + conflictDao.countUnresolved() +
                timeLogDao.countRejectedTimerCommands()
            if (rejected > 0) {
                throw SyncRequiresAttentionException(
                    "$rejected 条本地修改仍待处理，处理完成后才能重建同步数据"
                )
            }
            progressCallback(SyncProgress.Downloading)
            val snapshot = api.bootstrap(deviceId)
            merger.apply(snapshot.changes)
            tokenManager.saveSyncCursor(snapshot.nextCursor)
        } else {
            pullUntilCaughtUp(deviceId, progressCallback)
        }
        preferencesManager.setLastSyncTimestamp(System.currentTimeMillis())
        val deadLetters = outboxDao.countDeadLetters() + conflictDao.countUnresolved() +
            timeLogDao.countRejectedTimerCommands()
        if (deadLetters > 0) {
            throw SyncRequiresAttentionException(
                "$deadLetters 条同步问题需要处理，其他数据已继续同步"
            )
        }
    }

    suspend fun hasPendingChanges(): Boolean =
        outboxDao.count() > 0 || outboxDao.countDeadLetters() > 0 ||
            conflictDao.countUnresolved() > 0 ||
            timeLogDao.countPendingTimerCommands() > 0 ||
            timeLogDao.countRejectedTimerCommands() > 0

    suspend fun getDeadLetters(): List<SyncOutboxEntity> = outboxDao.getDeadLetters()

    fun observeDeadLetters(): Flow<List<SyncOutboxEntity>> = outboxDao.observeDeadLetters()

    fun observeConflicts(): Flow<List<SyncConflictEntity>> = conflictDao.observeUnresolved()

    suspend fun resolveConflictUseServer(id: Long) = accountSessionCoordinator.exclusive {
        check(merger.resolveConflictUseServer(id)) { "同步冲突已被处理" }
    }

    suspend fun resolveConflictUseLocal(id: Long) = accountSessionCoordinator.exclusive {
        check(merger.resolveConflictUseLocal(id)) {
            "无法恢复已被服务器删除的数据，请保留服务器版本"
        }
    }

    fun observeRejectedTimerCommands(): Flow<List<com.dayforge.data.local.entity.TimerCommandEntity>> =
        timerSyncRepository.observeRejectedCommands()

    suspend fun retryDeadLetter(id: Long) = accountSessionCoordinator.exclusive {
        outboxDao.retryDeadLetter(id, UUID.randomUUID().toString())
    }

    suspend fun discardDeadLetter(id: Long) = accountSessionCoordinator.exclusive {
        // Persist recovery intent first. A crash between these calls may cause
        // an unnecessary bootstrap, but can never leave silent divergence.
        tokenManager.requireSyncBootstrap()
        outboxDao.deleteDeadLetter(id)
    }

    suspend fun retryAllDeadLetters() = accountSessionCoordinator.exclusive {
        outboxDao.getDeadLetters().forEach {
            outboxDao.retryDeadLetter(it.id, UUID.randomUUID().toString())
        }
        timerSyncRepository.retryAllRejectedCommands()
    }

    suspend fun retryRejectedTimerCommand(id: Long) = accountSessionCoordinator.exclusive {
        timerSyncRepository.retryRejectedCommand(id)
    }

    suspend fun cancelRejectedTimerCommandAndUseServer(id: Long) =
        accountSessionCoordinator.exclusive {
            timerSyncRepository.cancelRejectedCommandAndUseServer(id, ensureDevice())
        }

    fun getLastSyncTime(): Flow<Long?> = preferencesManager.lastSyncTimestamp

    fun canEditStructure(): Flow<Boolean> = tokenManager.canEditStructure

    fun isPrimaryEditor(): Flow<Boolean> = tokenManager.isPrimaryEditor

    suspend fun makeCurrentDevicePrimary(): DeviceResponse = accountSessionCoordinator.exclusive {
        val deviceId = ensureDevice()
        api.makePrimaryDevice(deviceId).also { saveDeviceRegistration(it) }
    }

    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean): DeviceResponse =
        accountSessionCoordinator.exclusive {
            val deviceId = ensureDevice()
            api.updateDeviceEditing(deviceId, DeviceEditingUpdate(enabled))
                .also { saveDeviceRegistration(it) }
        }

    /** Backfill the public account UUID for sessions created before Sync V2. */
    private suspend fun ensureAccountId(): String {
        tokenManager.userId.first()?.let { return it }
        val refreshToken = tokenManager.refreshToken.first()
            ?: throw IllegalStateException("当前登录会话缺少账户标识，请重新登录")
        val refreshed = authApi.refreshToken(RefreshRequest(refreshToken))
        val accountId = refreshed.userId
        tokenManager.saveTokens(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken,
            email = refreshed.username,
            userId = accountId,
            isAdmin = refreshed.isAdmin
        )
        return accountId
    }

    private suspend fun ensureDevice(): String {
        val response = api.registerDevice(
            DeviceRegisterRequest(
                installationId = tokenManager.getOrCreateInstallationId(),
                protocolVersion = MIN_PROTOCOL_VERSION,
                platform = "android",
                deviceClass = "interactive",
                appVersion = BuildConfig.VERSION_NAME,
                displayName = Build.MODEL
            )
        )
        saveDeviceRegistration(response)
        return response.deviceId
    }

    private suspend fun saveDeviceRegistration(response: DeviceResponse) {
        tokenManager.saveDeviceRegistration(
            deviceId = response.deviceId,
            capabilities = response.capabilities.toSet(),
            isPrimaryEditor = response.isPrimaryEditor,
            capabilityRevision = response.capabilityRevision
        )
    }

    /**
     * Make endpoint failover safe before sending account data. A server instance
     * mismatch usually means a mistyped URL or a proxy pointing at the wrong NAS.
     * A changed epoch means the same server intentionally rebuilt its database.
     */
    private suspend fun verifyServerIdentity(
        resolvedIdentity: com.dayforge.data.api.dto.ServerIdentityResponse?
    ): Boolean {
        val identity = resolvedIdentity ?: api.identity()
        if (identity.protocolVersion < MIN_PROTOCOL_VERSION ||
            !identity.capabilities.containsAll(REQUIRED_CAPABILITIES)
        ) {
            throw SyncProtocolException(
                "服务器同步协议不兼容（需要 v$MIN_PROTOCOL_VERSION）"
            )
        }

        val knownInstance = tokenManager.serverInstanceId.first()
        val knownEpoch = tokenManager.syncEpoch.first()
        if (knownInstance == null || knownEpoch == null) {
            tokenManager.saveServerIdentity(identity.serverInstanceId, identity.syncEpoch)
            return false
        }
        if (knownInstance != identity.serverInstanceId) {
            throw ServerIdentityMismatchException(
                "当前地址连接到了另一台服务器，已停止同步以保护本地数据"
            )
        }
        if (knownEpoch == identity.syncEpoch) return false

        if (outboxDao.count() > 0 || outboxDao.countDeadLetters() > 0 ||
            conflictDao.countUnresolved() > 0 ||
            timeLogDao.countPendingTimerCommands() > 0 ||
            timeLogDao.countRejectedTimerCommands() > 0 ||
            timeLogDao.getActiveTimeLog() != null
        ) {
            throw SyncEpochChangedException(
                "服务器数据库已重建，但本地仍有未同步修改；请先确认数据处理方式"
            )
        }
        tokenManager.resetReplicaForEpoch(identity.serverInstanceId, identity.syncEpoch)
        return true
    }

    /** @return true when another batch may be needed. */
    private suspend fun pushOneBatch(deviceId: String): Boolean {
        val rows = outboxDao.getAll()
        if (rows.isEmpty()) return false
        val prepared = prepare(rows).sortedWith(operationOrder).take(MAX_PUSH_OPERATIONS)
        if (prepared.isEmpty()) return outboxDao.count() > 0

        val response = api.push(
            SyncV2PushRequest(
                deviceId = deviceId,
                operations = prepared.map { it.operation }
            )
        )
        val byOperationId = prepared.associateBy { it.row.operationId }
        val acknowledged = mutableSetOf<String>()
        response.results.forEach { result ->
            val item = byOperationId[result.operationId] ?: return@forEach
            acknowledged += result.operationId
            when (result.status) {
                "applied", "already_applied" -> {
                    val revision = result.revision
                        ?: throw IllegalStateException("服务器确认缺少 revision: ${result.operationId}")
                    outboxDao.upsertState(
                        SyncEntityStateEntity(
                            entityType = item.operation.entityType,
                            entityUuid = item.operation.entityUuid,
                            revision = revision,
                            deleted = item.logicalDelete,
                            payloadJson = (result.entity ?: item.operation.payload).toString(),
                            payloadHash = syncPayloadHash(
                                (result.entity ?: item.operation.payload).toString()
                            )
                        )
                    )
                    outboxDao.deleteById(item.row.id)
                }
                "conflict" -> {
                    val revision = result.revision
                    val entity = result.entity
                    if (revision != null && entity != null) {
                        val latestIntent = latestLocalIntentPayload(item)
                        val basePayload = result.baseEntity ?: item.row.basePayloadJson?.let {
                            json.decodeFromString(JsonObject.serializer(), it)
                        }
                        merger.preserveConflictAtomically(
                            recordType = item.row.recordType,
                            localEntityUuid = item.row.entityUuid,
                            operationId = item.row.operationId,
                            action = latestIntent.first,
                            referenceUuid = latestIntent.second,
                            baseRevision = item.row.baseRevision,
                            entityType = result.entityType,
                            wireEntityUuid = result.entityUuid,
                            revision = revision,
                            basePayload = basePayload,
                            localPayload = latestIntent.third,
                            serverPayload = entity,
                            conflictingFields = result.conflictingFields,
                            conflictKind = result.conflictKind,
                            errorCode = result.errorCode,
                            message = result.message
                        )
                    } else {
                        val error = listOfNotNull(result.errorCode, result.message).joinToString(": ")
                            .ifBlank { "同步冲突缺少服务器实体" }
                        // A conflict without the canonical server snapshot violates the
                        // protocol and cannot succeed on retry. Quarantine it so unrelated
                        // changes can still be pulled and surfaced for user attention.
                        outboxDao.markDeadLetter(
                            id = item.row.id,
                            errorCode = "MALFORMED_CONFLICT",
                            error = error,
                            deadLetteredAt = System.currentTimeMillis()
                        )
                    }
                }
                else -> {
                    if (result.errorCode == "DEVICE_CAPABILITY_DENIED") {
                        tokenManager.markStructuralEditingDenied()
                    }
                    val error = listOfNotNull(result.errorCode, result.message).joinToString(": ")
                        .ifBlank { "服务器拒绝了同步操作" }
                    // A protocol-level rejected result is deterministic. Quarantine it
                    // instead of retrying forever and blocking unrelated pull changes.
                    outboxDao.markDeadLetter(
                        id = item.row.id,
                        errorCode = result.errorCode,
                        error = error,
                        deadLetteredAt = System.currentTimeMillis()
                    )
                }
            }
        }
        val missing = prepared.firstOrNull { it.row.operationId !in acknowledged }
        if (missing != null) {
            throw IllegalStateException("服务器未确认操作 ${missing.row.operationId}")
        }
        return outboxDao.count() > 0
    }

    private suspend fun prepare(rows: List<SyncOutboxEntity>): List<PreparedOperation> {
        val result = mutableListOf<PreparedOperation>()
        val byEntity = rows.groupBy { it.recordType to it.entityUuid }
        val deletingHabitUuids = byEntity.values.mapNotNull { group ->
            group.maxByOrNull { it.id }
                ?.takeIf { it.recordType == "habit" && it.action == "delete" }
                ?.entityUuid
        }.toSet()
        for ((_, group) in byEntity) {
            val cascadedFactDelete = group.any {
                it.recordType in FACT_EVENT_RECORDS &&
                    (it.action == "delete" || it.wireEntityUuid != it.entityUuid) &&
                    it.referenceUuid in deletingHabitUuids
            }
            if (cascadedFactDelete) {
                outboxDao.deleteByIds(group.map { it.id })
                continue
            }
            val attempted = group.filter { it.attemptedAt != null }.minByOrNull { it.id }
            if (attempted != null) {
                result += attempted.toPrepared()
                continue
            }

            val ordered = group.sortedBy { it.id }
            val keeper = ordered.first()
            val latest = ordered.last()
            val wireType = wireType(latest.recordType)
            val existingState = outboxDao.getState(wireType, latest.entityUuid)
            if (latest.action == "delete" && existingState == null) {
                // Created and removed locally before the server ever saw it.
                outboxDao.deleteByIds(ordered.map { it.id })
                continue
            }

            val isRevert = latest.action == "delete" && latest.recordType in FACT_EVENT_RECORDS
            if (isRevert && (latest.referenceUuid == null || latest.referenceUuid in deletingHabitUuids)) {
                // The fact was removed by an activity cascade. Deleting the plan node
                // already hides its retained audit facts on the server.
                outboxDao.deleteByIds(ordered.map { it.id })
                continue
            }
            val wireUuid = if (isRevert) UUID.randomUUID().toString() else latest.entityUuid
            val wireAction = if (isRevert) "upsert" else latest.action
            val payload = if (isRevert) {
                val activityUuid = latest.referenceUuid
                    ?: throw IllegalStateException("撤销记录缺少所属活动 UUID")
                SyncV2Mapper.revert(activityUuid, latest.entityUuid)
            } else if (latest.action == "delete") {
                SyncV2Mapper.deletePayload(latest.recordType, latest.referenceUuid)
            } else {
                currentPayload(latest)
                    ?: throw IllegalStateException("待同步记录已不存在: ${latest.recordType}/${latest.entityUuid}")
            }
            val baseRevision = if (isRevert) null else existingState?.revision
            val basePayloadJson = if (isRevert) null else existingState?.payloadJson
            val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
            outboxDao.markPrepared(
                id = keeper.id,
                wireEntityUuid = wireUuid,
                action = wireAction,
                payloadJson = payloadJson,
                baseRevision = baseRevision,
                basePayloadJson = basePayloadJson,
                attemptedAt = System.currentTimeMillis()
            )
            if (ordered.size > 1) outboxDao.deleteByIds(ordered.drop(1).map { it.id })
            result += keeper.copy(
                wireEntityUuid = wireUuid,
                action = wireAction,
                payloadJson = payloadJson,
                baseRevision = baseRevision,
                basePayloadJson = basePayloadJson,
                attemptedAt = System.currentTimeMillis()
            ).toPrepared(logicalDelete = latest.action == "delete")
        }
        return result
    }

    private suspend fun currentPayload(row: SyncOutboxEntity): JsonObject? = when (row.recordType) {
        "habit" -> habitDao.getHabitByUuid(row.entityUuid)?.let(SyncV2Mapper::planNode)
        "completion" -> completionDao.getCompletionByUuid(row.entityUuid)?.let { completion ->
            habitDao.getHabitById(completion.habitId)?.let { SyncV2Mapper.completion(completion, it) }
        }
        "metric" -> metricDao.getMetricByUuid(row.entityUuid)?.let(SyncV2Mapper::metric)
        "metric_log" -> metricLogDao.getLogByUuid(row.entityUuid)?.let { log ->
            val metric = metricDao.getMetricById(log.metricId) ?: return@let null
            SyncV2Mapper.metricObservation(log, metric.uuid)
        }
        "link" -> linkDao.getLinkByUuid(row.entityUuid)?.let(SyncV2Mapper::link)
        else -> null
    }

    /** Return the newest local action and payload so edits made during an in-flight request survive. */
    private suspend fun latestLocalIntentPayload(
        prepared: PreparedOperation
    ): Triple<String, String?, JsonObject> {
        val latest = outboxDao.getAll()
            .filter {
                it.recordType == prepared.row.recordType &&
                    it.entityUuid == prepared.row.entityUuid
            }
            .maxByOrNull { it.id }
            ?: prepared.row
        val payload = when {
            latest.action == "delete" ->
                SyncV2Mapper.deletePayload(latest.recordType, latest.referenceUuid)
            else -> currentPayload(latest) ?: prepared.operation.payload
        }
        return Triple(latest.action, latest.referenceUuid, payload)
    }

    private suspend fun pullUntilCaughtUp(
        deviceId: String,
        progressCallback: (SyncProgress) -> Unit
    ) {
        var cursor = tokenManager.syncCursor.first()
        var pages = 0
        try {
            progressCallback(SyncProgress.Downloading)
            do {
                if (++pages > MAX_PULL_PAGES) {
                    throw IllegalStateException("服务端变更分页超过单次同步上限")
                }
                val page = api.pull(deviceId, cursor)
                validatePullPage(cursor, page)
                merger.apply(page.changes)
                cursor = page.nextCursor
                tokenManager.saveSyncCursor(cursor)
            } while (page.hasMore)
        } catch (error: SyncMergeException) {
            if (outboxDao.count() > 0 || outboxDao.countDeadLetters() > 0 ||
                conflictDao.countUnresolved() > 0
            ) {
                throw IllegalStateException(
                    "服务端变更无法合并，且本地仍有未处理修改，已保留游标以避免数据丢失",
                    error
                )
            }
            progressCallback(SyncProgress.Recovering)
            val snapshot = api.bootstrap(deviceId)
            merger.replaceWithBootstrap(snapshot.changes)
            tokenManager.saveSyncCursor(snapshot.nextCursor)
        }
    }

    /** Reject malformed pagination before applying data or advancing the durable cursor. */
    private fun validatePullPage(cursor: Long, page: SyncV2PullResponse) {
        if (page.changes.isEmpty()) {
            check(!page.hasMore) { "服务端返回空分页但仍声明存在后续数据" }
            check(page.nextCursor == cursor) { "服务端空分页错误推进了同步游标" }
            return
        }

        var previousSequence = cursor
        page.changes.forEach { change ->
            check(change.sequence > previousSequence) { "服务端变更序号未严格递增" }
            previousSequence = change.sequence
        }
        check(page.nextCursor == previousSequence) { "服务端分页游标与最后一条变更不一致" }
    }

    private fun SyncOutboxEntity.toPrepared(logicalDelete: Boolean = action == "delete"): PreparedOperation {
        val payload = payloadJson?.let { json.decodeFromString(JsonObject.serializer(), it) }
            ?: throw IllegalStateException("已发送的 outbox 操作缺少固定 payload")
        return PreparedOperation(
            row = this,
            operation = SyncV2Operation(
                operationId = operationId,
                entityType = wireType(recordType),
                entityUuid = wireEntityUuid,
                action = action,
                baseRevision = baseRevision,
                payload = payload
            ),
            logicalDelete = logicalDelete
        )
    }

    private fun wireType(recordType: String): String = when (recordType) {
        "habit" -> "plan_node"
        "completion" -> "activity_event"
        "metric" -> "metric"
        "metric_log" -> "metric_observation"
        "link" -> "activity_metric_link"
        else -> error("Unknown sync record type: $recordType")
    }

    private data class PreparedOperation(
        val row: SyncOutboxEntity,
        val operation: SyncV2Operation,
        val logicalDelete: Boolean
    )

    private val operationOrder = compareBy<PreparedOperation> {
        when (it.row.recordType) {
            "habit" -> when {
                it.operation.action != "delete" &&
                    it.operation.payload["node_kind"]?.jsonPrimitive?.contentOrNull == "goal" -> 0
                it.operation.action != "delete" -> 1
                it.row.referenceUuid == HabitType.GOAL.name -> 7
                else -> 5
            }
            "metric" -> if (it.operation.action == "delete") 6 else 2
            "link" -> 3
            "completion", "metric_log" -> 4
            else -> 5
        }
    }.thenBy { it.row.id }

    companion object {
        private const val MIN_PROTOCOL_VERSION = 4
        private val REQUIRED_CAPABILITIES = setOf("sync_v2", "device_capabilities")
        private const val MAX_PUSH_OPERATIONS = 100
        private const val MAX_PUSH_ROUNDS = 100
        private const val MAX_PULL_PAGES = 10_000
        private val FACT_EVENT_RECORDS = setOf("completion")
    }
}

class SyncRequiresAttentionException(message: String) : IllegalStateException(message)
class SyncProtocolException(message: String) : IllegalStateException(message)
class ServerIdentityMismatchException(message: String) : IllegalStateException(message)
class SyncEpochChangedException(message: String) : IllegalStateException(message)
