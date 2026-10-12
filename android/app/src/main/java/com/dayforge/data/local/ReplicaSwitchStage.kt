@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class, com.dayforge.domain.model.ContractLongSerializer::class,
    com.dayforge.domain.model.ContractBooleanSerializer::class)

package com.dayforge.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class ReplicaSwitchPhase { FREEZING, FROZEN, ARCHIVE_VERIFIED, COMMITTING, SQL_COMMITTED,
    DESTINATION_BOUND, CATCHUP_COMMITTING, CATCHUP_BOUND, ABORTED }

@Serializable
internal data class ReplicaStageTarget(val projectionHash: String, val schemaHash: String, val deviceId: String,
    val capabilityRevision: Int, val capabilities: List<String>, val isPrimaryEditor: Boolean,
    val structuralEditEnabled: Boolean) {
    init {
        require(listOf(projectionHash, schemaHash).all { it.matches(Regex("[0-9a-f]{64}")) } && isContractUuid(deviceId) && capabilityRevision > 0)
        require(capabilities.size <= 256 && capabilities == capabilities.distinct().sorted() &&
            "sync.read" in capabilities && capabilities.all { it.isNotBlank() && it.length <= 256 })
        require(("structure.write" in capabilities) == (isPrimaryEditor || structuralEditEnabled))
    }
}

/** An actual validated destination snapshot, separate from the originally confirmed target. */
@Serializable
internal data class ReplicaStageSnapshot(val contentHash: String, val wireHash: String,
    val cursor: Long, val target: ReplicaStageTarget) {
    init { require(cursor >= 0 && listOf(contentHash, wireHash).all { it.matches(Regex("[0-9a-f]{64}")) }) }
}

/** The previous published checkpoint is retained until SQL fate proves the next one. */
@Serializable
internal data class ReplicaStageCatchup(val previous: ReplicaStageSnapshot, val next: ReplicaStageSnapshot,
    val authenticationGeneration: String) {
    init {
        require(isContractUuid(authenticationGeneration) && next.cursor >= previous.cursor)
        require(next.target.deviceId == previous.target.deviceId && next.target.schemaHash == previous.target.schemaHash &&
            next.target.capabilityRevision >= previous.target.capabilityRevision &&
            (next.target.capabilityRevision > previous.target.capabilityRevision ||
                next.target.copy(projectionHash = previous.target.projectionHash) == previous.target)) { "SYNC_RECOVERY_PERMISSION_CHANGED" }
    }
}

@Serializable
internal data class ReplicaStageArchiveEntry(val name: String, val byteLength: Long, val contentHash: String)

/** Durable private receipt only; actual file verification remains mandatory after every reopen. */
@Serializable
internal data class ReplicaStageArchive(val archiveId: String, val inputHash: String,
    val byteLength: Long, val contentHash: String, val entries: List<ReplicaStageArchiveEntry>) {
    init {
        require(isContractUuid(archiveId) && byteLength in 1..672L * 1024 * 1024 && entries.size in 3..4610)
        require(listOf(inputHash, contentHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(entries.map { it.name }.distinct().size == entries.size &&
            entries.take(3).map { it.name } == listOf("input.json", "business.json", "icons.json"))
        require(entries.first().contentHash == inputHash && entries.all { it.name.length in 1..160 &&
            it.byteLength in 0..32L * 1024 * 1024 && it.contentHash.matches(Regex("[0-9a-f]{64}")) })
    }
}

/** Durable journal participant only. No consent, COMMIT or rollback authority.
 * Hashes bind the complete inputs supplied by the owning workflow, not a revalidation claim.
 * A new authenticated generation invalidates pre-COMMIT consent. Same-owner cancellation can
 * close a proved old replica; actual committed SQL only permits controlled forward continuation.
 */
@Serializable
internal data class ReplicaSwitchStage(
    val recoveryId: String, val accountId: String, val serverInstanceId: String,
    val oldEpoch: String, val newEpoch: String, val authenticationGeneration: String,
    val deviceId: String, val capabilityRevision: Int, val capabilities: List<String>,
    val installationId: String, val sourceHash: String, val inputHash: String,
    val candidateHash: String, val candidateWireHash: String, val candidateCursor: Long,
    val phase: ReplicaSwitchPhase = ReplicaSwitchPhase.FREEZING,
    val generation: Long = 1, val formatVersion: Int = 1,
    val previousFenceHash: String? = null, val archive: ReplicaStageArchive? = null,
    val target: ReplicaStageTarget? = null, val catchup: ReplicaStageCatchup? = null
) {
    init {
        require(formatVersion == 1 && generation > 0 && capabilityRevision > 0 && candidateCursor >= 0)
        require(oldEpoch != newEpoch && listOf(recoveryId, accountId, serverInstanceId, oldEpoch,
            newEpoch, authenticationGeneration, deviceId, installationId).all(::isContractUuid))
        require(capabilities.size <= 256 && capabilities == capabilities.distinct().sorted() &&
            "sync.read" in capabilities && capabilities.all { it.isNotBlank() && it.length <= 256 })
        require(listOf(sourceHash, inputHash, candidateHash, candidateWireHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(previousFenceHash == null || previousFenceHash.matches(Regex("[0-9a-f]{64}")))
        require(when (phase) {
            ReplicaSwitchPhase.FREEZING -> generation == 1L
            ReplicaSwitchPhase.FROZEN -> generation == 2L
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> generation == 3L
            ReplicaSwitchPhase.COMMITTING -> generation == 4L
            ReplicaSwitchPhase.SQL_COMMITTED -> generation == 5L
            ReplicaSwitchPhase.DESTINATION_BOUND -> generation == 6L
            ReplicaSwitchPhase.CATCHUP_COMMITTING -> generation >= 7L && generation % 2L == 1L
            ReplicaSwitchPhase.CATCHUP_BOUND -> generation >= 8L && generation % 2L == 0L
            ReplicaSwitchPhase.ABORTED -> generation in 2L..5L
        })
        require(when (phase) {
            ReplicaSwitchPhase.FREEZING, ReplicaSwitchPhase.FROZEN -> archive == null
            ReplicaSwitchPhase.ARCHIVE_VERIFIED, ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
                ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_COMMITTING,
                ReplicaSwitchPhase.CATCHUP_BOUND -> archive != null
            ReplicaSwitchPhase.ABORTED -> (archive != null) == (generation >= 4L)
        })
        require((target != null) == (phase in setOf(ReplicaSwitchPhase.COMMITTING, ReplicaSwitchPhase.SQL_COMMITTED,
            ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_COMMITTING, ReplicaSwitchPhase.CATCHUP_BOUND) ||
            phase == ReplicaSwitchPhase.ABORTED && generation == 5L))
        require(target == null || target.deviceId == deviceId)
        require((catchup != null) == (phase in setOf(ReplicaSwitchPhase.CATCHUP_COMMITTING, ReplicaSwitchPhase.CATCHUP_BOUND)))
        catchup?.let {
            val baseline = requireNotNull(target)
            require(it.previous.target.deviceId == baseline.deviceId && it.previous.target.schemaHash == baseline.schemaHash &&
                it.previous.target.capabilityRevision >= baseline.capabilityRevision &&
                (it.previous.target.capabilityRevision > baseline.capabilityRevision ||
                    it.previous.target.copy(projectionHash = baseline.projectionHash) == baseline) &&
                it.previous.cursor >= candidateCursor) { "SYNC_RECOVERY_PERMISSION_CHANGED" }
        }
    }
    val original: LocalSyncAccess get() = LocalSyncAccess(LocalDataSession(
        AuthenticationSession(accountId, authenticationGeneration), serverInstanceId, oldEpoch),
        deviceId, capabilityRevision, capabilities.toSet())
    val fence: NextReplicaFenceEntity get() = NextReplicaFenceEntity(recoveryId, accountId,
        serverInstanceId, oldEpoch, newEpoch, authenticationGeneration, deviceId, capabilityRevision.toLong(), sourceHash)

    val publishedSnapshot: ReplicaStageSnapshot get() = when (phase) {
        ReplicaSwitchPhase.CATCHUP_COMMITTING -> requireNotNull(catchup).previous
        ReplicaSwitchPhase.CATCHUP_BOUND -> requireNotNull(catchup).next
        else -> ReplicaStageSnapshot(candidateHash, candidateWireHash, candidateCursor, requireNotNull(target))
    }

    val destinationPending: Boolean get() = phase in setOf(ReplicaSwitchPhase.DESTINATION_BOUND,
        ReplicaSwitchPhase.CATCHUP_COMMITTING, ReplicaSwitchPhase.CATCHUP_BOUND)

    /** Target scope only; a journal or this value is never material/publication permission. */
    fun destination(authentication: AuthenticationSession): LocalSyncAccess {
        require(authentication.userId == accountId)
        val proof = if (destinationPending) publishedSnapshot.target else requireNotNull(target)
        return LocalSyncAccess(LocalDataSession(authentication, serverInstanceId, newEpoch),
            proof.deviceId, proof.capabilityRevision, proof.capabilities.toSet())
    }

    fun transition(next: ReplicaSwitchPhase, receipt: ReplicaStageArchive? = archive,
        destination: ReplicaStageTarget? = target, pursuit: ReplicaStageCatchup? = catchup): ReplicaSwitchStage {
        require(generation < Long.MAX_VALUE && when (phase) {
            ReplicaSwitchPhase.FREEZING -> next == ReplicaSwitchPhase.FROZEN || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.FROZEN -> next == ReplicaSwitchPhase.ARCHIVE_VERIFIED || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> next == ReplicaSwitchPhase.COMMITTING || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.COMMITTING -> next == ReplicaSwitchPhase.SQL_COMMITTED || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.SQL_COMMITTED -> next == ReplicaSwitchPhase.DESTINATION_BOUND
            ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_BOUND -> next == ReplicaSwitchPhase.CATCHUP_COMMITTING
            ReplicaSwitchPhase.CATCHUP_COMMITTING -> next in setOf(ReplicaSwitchPhase.CATCHUP_COMMITTING, ReplicaSwitchPhase.CATCHUP_BOUND)
            ReplicaSwitchPhase.ABORTED -> false
        }) { "SYNC_REPLICA_STAGE_TRANSITION_INVALID" }
        require(if (next == ReplicaSwitchPhase.ARCHIVE_VERIFIED) receipt != null else receipt == archive)
        require(if (next == ReplicaSwitchPhase.COMMITTING) destination != null else destination == target)
        if (next == ReplicaSwitchPhase.CATCHUP_COMMITTING) {
            require(pursuit != null && pursuit.previous == publishedSnapshot) { "SYNC_REPLICA_STAGE_CHANGED" }
        } else require(pursuit == catchup)
        val increment = if (phase == ReplicaSwitchPhase.CATCHUP_COMMITTING && next == phase) 2L else 1L
        require(generation <= Long.MAX_VALUE - increment)
        return copy(phase = next, generation = generation + increment, archive = receipt, target = destination, catchup = pursuit)
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        // Full 4610-entry archive receipts fit without silently dropping material witnesses.
        const val ROW_LIMIT = 2 * 1024 * 1024
        const val HISTORY_LIMIT = 16 * 1024 * 1024
        const val KEY_PREFIX = "replica_switch_stage_"
        const val CATCHUP_AUDIT_PREFIX = "replica_catchup_intent_"
        fun catchupKey(stage: ReplicaSwitchStage): Preferences.Key<String> {
            require(stage.phase in setOf(ReplicaSwitchPhase.CATCHUP_COMMITTING, ReplicaSwitchPhase.CATCHUP_BOUND))
            val intentGeneration = stage.generation - if (stage.phase == ReplicaSwitchPhase.CATCHUP_BOUND) 1 else 0
            return stringPreferencesKey(CATCHUP_AUDIT_PREFIX + stage.recoveryId + "_" + intentGeneration)
        }
        fun encodeCatchup(stage: ReplicaSwitchStage): String = json.encodeToString(requireNotNull(stage.catchup))
        fun key(id: String): Preferences.Key<String> {
            require(isContractUuid(id)); return stringPreferencesKey(KEY_PREFIX + id)
        }
        fun encode(stage: ReplicaSwitchStage): String = json.encodeToString(stage).also {
            require(it.toByteArray(Charsets.UTF_8).size <= ROW_LIMIT) { "SYNC_REPLICA_STAGE_LIMIT" }
        }
        suspend fun decode(raw: String): ReplicaSwitchStage {
            val caller = currentCoroutineContext()
            require(raw.length <= ROW_LIMIT) { "SYNC_REPLICA_STAGE_LIMIT" }
            val text = strictAppearanceJson(raw.toByteArray(Charsets.UTF_8), ROW_LIMIT, { caller.ensureActive() }) {
                error("SYNC_REPLICA_STAGE_$it")
            }
            return json.decodeFromString<ReplicaSwitchStage>(text).let {
                it.copy(capabilities = java.util.Collections.unmodifiableList(it.capabilities.toList()),
                    archive = it.archive?.copy(entries = java.util.Collections.unmodifiableList(it.archive.entries.toList())),
                    target = it.target?.immutable(), catchup = it.catchup?.let { proof -> proof.copy(
                        previous = proof.previous.copy(target = proof.previous.target.immutable()),
                        next = proof.next.copy(target = proof.next.target.immutable())) })
            }.also { caller.ensureActive() }
        }
    }
}

private fun ReplicaStageTarget.immutable() = copy(capabilities = java.util.Collections.unmodifiableList(capabilities.toList()))
