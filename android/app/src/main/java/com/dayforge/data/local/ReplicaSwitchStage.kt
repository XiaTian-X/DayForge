@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractIntegerSerializer::class, com.dayforge.domain.model.ContractLongSerializer::class)

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

internal enum class ReplicaSwitchPhase { FREEZING, FROZEN, ARCHIVE_VERIFIED, ABORTED }

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

/** Pre-COMMIT journal participant only. No consent, COMMIT or rollback authority.
 * Hashes bind the complete inputs supplied by the owning workflow, not a revalidation claim.
 * A new authenticated generation invalidates continuation; explicit same-owner cancellation
 * can still close a matching pre-COMMIT fence without restoring credentials or source data.
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
    val previousFenceHash: String? = null, val archive: ReplicaStageArchive? = null
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
            ReplicaSwitchPhase.ABORTED -> generation in 2L..4L
        })
        require(when (phase) {
            ReplicaSwitchPhase.FREEZING, ReplicaSwitchPhase.FROZEN -> archive == null
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> archive != null
            ReplicaSwitchPhase.ABORTED -> (archive != null) == (generation == 4L)
        })
    }
    val original: LocalSyncAccess get() = LocalSyncAccess(LocalDataSession(
        AuthenticationSession(accountId, authenticationGeneration), serverInstanceId, oldEpoch),
        deviceId, capabilityRevision, capabilities.toSet())
    val fence: NextReplicaFenceEntity get() = NextReplicaFenceEntity(recoveryId, accountId,
        serverInstanceId, oldEpoch, newEpoch, authenticationGeneration, deviceId, capabilityRevision.toLong(), sourceHash)

    fun transition(next: ReplicaSwitchPhase, receipt: ReplicaStageArchive? = archive): ReplicaSwitchStage {
        require(generation < Long.MAX_VALUE && when (phase) {
            ReplicaSwitchPhase.FREEZING -> next == ReplicaSwitchPhase.FROZEN || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.FROZEN -> next == ReplicaSwitchPhase.ARCHIVE_VERIFIED || next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.ARCHIVE_VERIFIED -> next == ReplicaSwitchPhase.ABORTED
            ReplicaSwitchPhase.ABORTED -> false
        }) { "SYNC_REPLICA_STAGE_TRANSITION_INVALID" }
        require(if (next == ReplicaSwitchPhase.ARCHIVE_VERIFIED) receipt != null else receipt == archive)
        return copy(phase = next, generation = generation + 1, archive = receipt)
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        // Full 4610-entry archive receipts fit without silently dropping material witnesses.
        const val ROW_LIMIT = 2 * 1024 * 1024
        const val HISTORY_LIMIT = 16 * 1024 * 1024
        const val KEY_PREFIX = "replica_switch_stage_"
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
                    archive = it.archive?.copy(entries = java.util.Collections.unmodifiableList(it.archive.entries.toList())))
            }.also { caller.ensureActive() }
        }
    }
}
