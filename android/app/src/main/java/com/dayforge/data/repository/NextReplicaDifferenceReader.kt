package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.domain.model.OneTimeProjection
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal enum class ReplicaDifferenceKind { ADDED, CHANGED, MISSING }

/** Full values, not counts or a claim that an absent server receipt was never executed. */
internal data class ReplicaDifference(val key: ReplicaRecordKey, val kind: ReplicaDifferenceKind,
    val before: JsonElement?, val after: JsonElement?, val previouslyDeleted: Boolean)

internal data class ReplicaUnconfirmedLocalRecord(val key: ReplicaRecordKey, val table: String, val rawValues: JsonObject)

internal class ReplicaDifferencePreview internal constructor(
    val assessment: ReplicaRecoveryAssessment,
    val oldStorage: ReplicaStorageProof,
    val candidate: ValidatedReplicaCandidate,
    differences: List<ReplicaDifference>,
    unconfirmedLocalRecords: List<ReplicaUnconfirmedLocalRecord>,
    val unchangedRecords: Int
) {
    val differences: List<ReplicaDifference> = Collections.unmodifiableList(differences.toList())
    val unconfirmedLocalRecords: List<ReplicaUnconfirmedLocalRecord> = Collections.unmodifiableList(unconfirmedLocalRecords.toList())
    val hasUnfinishedTimer: Boolean get() = candidate.hasUnfinishedTimer ||
        ReplicaRecoveryReview.UNFINISHED_TIMER in assessment.reviewCounts
    val activationAuthorized: Boolean get() = false
}

/** Complete authority differences + exact local contents. No HTTP, writes, archive or switch.
 * Local facts/queues/journals without server authority remain visible in oldStorage and assessment;
 * they are never promoted to ACKed records or silently substituted for missing server records.
 */
internal class NextReplicaDifferenceReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val preferences: PreferencesManager) {
    private val json = Json { encodeDefaults = true }

    suspend fun capture(candidate: ValidatedReplicaCandidate): ReplicaDifferencePreview = withContext(Dispatchers.IO) {
        sessions.exclusive { database.withTransaction { captureInTransaction(candidate) } }
    }

    /** Fresh candidate must come from authenticated read+isolated validation, never a cached DTO.
     * Volatile response server_time/wireHash is NOT compared as business state. Any actual content,
     * cursor, permission, old raw value/type, schema or authentication change invalidates the preview.
     */
    suspend fun revalidate(previous: ReplicaDifferencePreview, fresh: ValidatedReplicaCandidate): ReplicaDifferencePreview =
        withContext(Dispatchers.IO) { sessions.exclusive { database.withTransaction {
            val current = captureInTransaction(fresh)
            require(previous.assessment.original == current.assessment.original &&
                previous.assessment.observedEpoch == current.assessment.observedEpoch &&
                previous.oldStorage.hash == current.oldStorage.hash &&
                previous.candidate.contentHash == fresh.contentHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            current
        } } }

    private suspend fun captureInTransaction(candidate: ValidatedReplicaCandidate): ReplicaDifferencePreview {
        check(database.inTransaction())
        val original = candidate.original
        authorize(original, candidate.deviceProof.installationId)
        val observed = ServerIdentityResponse(requireNotNull(original.session.serverInstanceId), candidate.newEpoch, 5,
            candidate.deviceProof.capabilities, Instant.now().toString())
        val report = NextReplicaRecoveryReader(database, tokens, sessions).readInTransaction(original, observed)
        val storage = NextReplicaStorageProof(database).readInTransaction()
        val before = linkedMapOf<ReplicaRecordKey, JsonElement>()
        val deleted = mutableSetOf<ReplicaRecordKey>()
        fun add(kind: String, id: String, value: JsonElement) {
            require(before.put(ReplicaRecordKey(kind, id), value) == null)
        }
        NextAuthorityShadowReader(database).readInTransaction { shadow ->
            add(shadow.entityType, shadow.entityUuid, shadow.payload)
            if (shadow.operation == "delete") deleted += ReplicaRecordKey(shadow.entityType, shadow.entityUuid)
        }
        val unconfirmed = buildList {
            for ((table, kind) in listOf("habits" to "plan_node", "metrics" to "metric",
                "habit_metric_links" to "activity_metric_link", "completions" to "activity_event",
                "timelogs" to "activity_event", "metric_logs" to "metric_observation")) {
                for (raw in storage.contents.getValue("tables").jsonObject.getValue(table).jsonArray) {
                    currentCoroutineContext().ensureActive()
                    val values = raw.jsonObject
                    val uuid = values.getValue("uuid").jsonObject
                    require(uuid.getValue("sqlite_type").jsonPrimitive.int == android.database.Cursor.FIELD_TYPE_STRING)
                    val identity = uuid.getValue("value").jsonPrimitive.content
                    require(com.dayforge.domain.model.isContractUuid(identity))
                    val key = ReplicaRecordKey(kind, identity)
                    if (key !in before || key in deleted) add(ReplicaUnconfirmedLocalRecord(key, table, values))
                }
            }
        }
        val metadata = NextChallengeStore(database).activeInTransaction(original).second
        metadata.checkpoints.forEach { checkpoint ->
            add("challenge_checkpoint", checkpoint.head.activityUuid, json.encodeToJsonElement(checkpoint))
            checkpoint.records.filter { it.head.generation > 0 }.forEach { record ->
                add("challenge_round", record.head.roundUuid, json.encodeToJsonElement(record))
            }
        }
        metadata.births.forEach { add("challenge_birth.${it.entityType}", it.entityUuid, json.encodeToJsonElement(it)) }
        val once = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
        for (habit in database.habitDao().getAllHabitsOnce().filter { it.completionPolicy == "one_and_done" }) {
            currentCoroutineContext().ensureActive()
            val state = once.readInTransaction(habit.uuid, original.session).confirmed
            add("one_time_checkpoint", habit.uuid, json.encodeToJsonElement(OneTimeProjection(habit.uuid, state)))
        }
        val differences = mutableListOf<ReplicaDifference>()
        var unchanged = 0
        for (key in (before.keys + candidate.records.keys).sortedWith(compareBy({ it.kind }, { it.identity }))) {
            currentCoroutineContext().ensureActive()
            val old = before[key]; val new = candidate.records[key]
            val tombstone = key in deleted
            when {
                tombstone && new == null -> Unit // Already deleted locally AND absent in the new complete snapshot.
                old == new && !tombstone -> unchanged++
                else -> differences += ReplicaDifference(key, when {
                    old == null -> ReplicaDifferenceKind.ADDED
                    new == null -> ReplicaDifferenceKind.MISSING
                    else -> ReplicaDifferenceKind.CHANGED
                }, old, new, tombstone)
            }
        }
        authorize(original, candidate.deviceProof.installationId)
        return ReplicaDifferencePreview(report, storage, candidate, differences, unconfirmed, unchanged)
    }

    private suspend fun authorize(original: LocalSyncAccess, installation: String) {
        check(tokens.localSyncAccess() == original && tokens.syncAuthenticationSnapshot(original) != null &&
            tokens.registeredInstallationId() == installation &&
            original.deviceId != null) { "SYNC_ACCOUNT_CHANGED" }
    }
}
