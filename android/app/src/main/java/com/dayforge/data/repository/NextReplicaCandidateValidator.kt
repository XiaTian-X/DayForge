package com.dayforge.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal data class ReplicaRecordKey(val kind: String, val identity: String)

/** Complete validated server contents, not a staged database or permission to replace one. */
internal class ValidatedReplicaCandidate internal constructor(
    val original: LocalSyncAccess,
    val newEpoch: String,
    val cursor: Long,
    val wireHash: String,
    val deviceProof: RecoveryDeviceProof,
    records: Map<ReplicaRecordKey, JsonElement>,
    val hasUnfinishedTimer: Boolean
) {
    val records: Map<ReplicaRecordKey, JsonElement> = Collections.unmodifiableMap(records.toMap())
    val activationAuthorized: Boolean get() = false
}

/**
 * Authenticated dry run in an exclusively owned in-memory Room instance. Never replaces the
 * process-wide provider, copies credentials, publishes a cursor, or acknowledges old requests.
 * Internal merge participants are reused; their public authenticated write entries are not called.
 */
internal class NextReplicaCandidateValidator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager
) {
    private val json = Json { encodeDefaults = true }

    suspend fun validate(original: LocalSyncAccess, installation: String,
        response: ReplicaRecoveryPreviewResponse): ValidatedReplicaCandidate = withContext(Dispatchers.IO) {
        sessions.exclusive {
            authorize(original, installation)
            // Re-decode caller-owned collections before using them across suspensions.
            val wire = json.encodeToString(response)
            require(wire.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "SYNC_RECOVERY_SNAPSHOT_TOO_LARGE" }
            val frozen = json.decodeFromString<ReplicaRecoveryPreviewResponse>(wire)
            val proof = frozen.device
            require(frozen.accountId == original.session.authentication.userId &&
                frozen.serverInstanceId == original.session.serverInstanceId && frozen.syncEpoch != original.session.syncEpoch)
            require(proof.deviceId == original.deviceId && proof.installationId == installation &&
                proof.platform == "android" && proof.deviceClass == "interactive" && proof.capabilityRevision > 0)
            require(proof.capabilities.distinct().size == proof.capabilities.size &&
                proof.capabilities.all(String::isNotBlank) && "sync.read" in proof.capabilities)
            require(("structure.write" in proof.capabilities) == (proof.isPrimaryEditor || proof.structuralEditEnabled))
            Instant.parse(requireNotNull(proof.lastSeenAt))
            val snapshot = frozen.snapshot
            require(snapshot.nextCursor >= 0)
            Instant.parse(snapshot.serverTime)
            for (change in snapshot.changes) {
                currentCoroutineContext().ensureActive()
                require(change.sequence == 0L && change.operation == "upsert" && change.revision > 0 && isContractUuid(change.entityUuid))
                change.originDeviceId?.let { require(isContractUuid(it)) }
                Instant.parse(change.changedAt)
                if (change.entityType != "challenge_round") {
                    val updated = requireNotNull(change.payload["updated_at"] as? JsonPrimitive).also { require(it.isString) }
                    require(Instant.parse(change.changedAt) == Instant.parse(updated.content))
                }
            }
            // This is a private projection scope, not authority given to HTTP or active storage.
            val candidateScope = LocalSyncAccess(original.session.copy(syncEpoch = frozen.syncEpoch), proof.deviceId,
                proof.capabilityRevision, proof.capabilities.toSet())
            val candidate = Room.inMemoryDatabaseBuilder(context.applicationContext, HabitDatabase::class.java)
                .addCallback(SyncSchemaCallback).build()
            var failure: Throwable? = null
            try {
                candidate.withTransaction {
                    val local = OneTimeLocalIntentStore(candidate, tokens, sessions, preferences)
                    val accepted = OneTimeAcceptedEventStore(candidate, tokens, sessions, local)
                    accepted.restoreAcceptedDataInTransaction(OneTimeSyncContext(candidateScope.session, proof.deviceId),
                        NextSyncBootstrapResponse(snapshot.changes.filter { it.entityType != "challenge_round" },
                            snapshot.nextCursor, snapshot.serverTime, snapshot.oneTimeCheckpoints))
                    val hash = syncPayloadHash(wire)
                    val state = NextSyncStateEntity(frozen.accountId, frozen.serverInstanceId, frozen.syncEpoch, proof.deviceId,
                        1, snapshot.nextCursor, hash, hash, challengeContract = 1)
                    val rounds = NextChallengeStore(candidate)
                    rounds.mergeInTransaction(candidateScope, null, state, snapshot.metadata())
                    check(candidate.nextSyncStateDao().insert(state) == 1L)
                    check(rounds.activeInTransaction(candidateScope).second == rounds.readInTransaction(candidateScope, state))
                    check(candidate.syncOutboxDao().count() == 0 && candidate.timeLogDao().getPendingTimerCommands(1).isEmpty())
                    check(!candidate.nextRequestDao().hasAny())
                    frozen.activeTimer?.let { timer ->
                        require(timer.state in setOf("running", "paused") && timer.endedAt == null && timer.completedEventId == null)
                        require(listOf(timer.sessionId, timer.activityUuid, timer.controllerDeviceId).all(::isContractUuid))
                        require(timer.controlGeneration > 0 && timer.revision > 0 && timer.nextCommandSequence > 0 &&
                            timer.targetSeconds >= 0 && timer.maxDurationSeconds in 1..86_400 &&
                            timer.activeElapsedMs in 0..timer.maxDurationSeconds.toLong() * 1000)
                        require(timer.timezone in java.time.ZoneId.getAvailableZoneIds())
                        val started = Instant.parse(timer.startedAt)
                        require(Instant.parse(timer.stateChangedAt) >= started)
                        timer.lastHeartbeatAt?.let { require(Instant.parse(it) >= started) }
                        require(candidate.habitDao().getHabitByUuid(timer.activityUuid)?.habitType == com.dayforge.data.model.HabitType.TIMER)
                        RoundActiveTimerResponse(timer, snapshot.serverTime, 1, snapshot.checkpoints, snapshot.births)
                    }
                }
                currentCoroutineContext().ensureActive()
                authorize(original, installation)
                val records = linkedMapOf<ReplicaRecordKey, JsonElement>()
                fun add(kind: String, identity: String, value: JsonElement) {
                    check(records.put(ReplicaRecordKey(kind, identity), value) == null)
                }
                snapshot.changes.forEach { add(it.entityType, it.entityUuid, it.payload) }
                snapshot.oneTimeCheckpoints.forEach { add("one_time_checkpoint", it.activityUuid, json.encodeToJsonElement(it)) }
                snapshot.checkpoints.forEach { checkpoint ->
                    add("challenge_checkpoint", checkpoint.head.activityUuid,
                        json.encodeToJsonElement(checkpoint.copy(records = checkpoint.records.sortedBy { it.head.generation })))
                    checkpoint.records.filter { it.head.generation > 0 }.forEach { record ->
                        val key = ReplicaRecordKey("challenge_round", record.head.roundUuid)
                        val value = json.encodeToJsonElement(record)
                        require(records[key]?.let { it == value } != false)
                        records[key] = value
                    }
                }
                snapshot.births.forEach { add("challenge_birth.${it.entityType}", it.entityUuid, json.encodeToJsonElement(it)) }
                frozen.activeTimer?.let { add("active_timer", it.sessionId, json.encodeToJsonElement(it)) }
                ValidatedReplicaCandidate(original.copy(capabilities = original.capabilities.toSet()), frozen.syncEpoch,
                    snapshot.nextCursor, syncPayloadHash(wire),
                    proof.copy(capabilities = Collections.unmodifiableList(proof.capabilities.toList())),
                    records, frozen.activeTimer != null)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { candidate.close() } catch (close: Throwable) {
                    if (failure == null) throw close else failure.addSuppressed(close)
                }
            }
        }
    }

    private suspend fun authorize(original: LocalSyncAccess, installation: String) {
        require(isContractUuid(installation) && original.deviceId != null)
        check(tokens.localSyncAccess() == original && tokens.syncAuthenticationSnapshot(original) != null &&
            tokens.registeredInstallationId() == installation) { "SYNC_ACCOUNT_CHANGED" }
    }
}
