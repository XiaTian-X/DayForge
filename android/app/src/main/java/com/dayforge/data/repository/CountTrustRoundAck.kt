package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.ChallengeBirth
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.NextSyncStateEntity
import java.util.Collections
import kotlinx.serialization.json.*

/** The accepted-round sidecar of a quantity ACK. All protocol binding and current authority
 * remain with NextCoreRequestStore. Extra legitimate metadata takes the original merge path;
 * it is never silently dropped. The outer kernel verifies these ordered effects together with
 * the fact, receipt and outbox effects before signing anything.
 */
internal class CountTrustRoundAck(private val database: HabitDatabase) {
    internal class Prepared internal constructor(internal val context: CountTrustKernel.Context,
        internal val metadata: ChallengeMetadata, internal val effects: List<CountTrustAck.Effect>)
    private val json = Json { encodeDefaults = true }

    suspend fun prepare(context: CountTrustKernel.Context, access: LocalSyncAccess, source: NextRoundOperationIntent,
        checkpoint: Pair<NextSyncStateEntity, ChallengeMetadata>, incoming: ChallengeMetadata): Prepared? =
        context.index.withReadScope { prepareInsideScope(context, access, source, checkpoint, incoming) }

    private suspend fun prepareInsideScope(context: CountTrustKernel.Context, access: LocalSyncAccess, source: NextRoundOperationIntent,
        checkpoint: Pair<NextSyncStateEntity, ChallengeMetadata>, incoming: ChallengeMetadata): Prepared? {
        check(database.inTransaction())
        val observation = source.operation.entityType == "metric_observation" && source.operation.action == "upsert"
        val quantity = source.operation.entityType == "activity_event" && source.operation.action == "upsert" &&
            source.operation.payload["event_type"] in quantityTypes
        if (!context.formal || source.initialCreation || source.restartFrontier != null || source.goalChildFrontier != null ||
            !(quantity || observation) || observation && (!context.observations || source.context.head != null)) return null
        check(context.access.session == access.session && context.access.capturedDeviceId == access.deviceId)
        val retained = Json.decodeFromJsonElement(ChallengeMetadata.serializer(),
            requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)))
        check(retained == checkpoint.second) { "COUNT_ROUND_METADATA_CHANGED" }
        val additions: List<ChallengeBirth>
        val merged: ChallengeMetadata
        if (observation) {
            // A formal observation has no activity birth. Preserve the original same-value
            // marker write; additional legitimate metadata uses the original protocol merger.
            merged = MetricRoundMetadataRules.unchanged(source, retained, incoming) ?: return null
            additions = emptyList()
        } else {
            val head = requireNotNull(source.context.head)
            val habitRow = context.checked(CountTrustMembership.key("habit.uuid", head.activityUuid), "habits", "uuid=?",
                arrayOf(head.activityUuid)).singleOrNull() ?: return null
            val habit = requireNotNull(database.habitDao().getHabitByUuid(head.activityUuid))
            check(habit.id == habitRow.long("id")) { "COUNT_ACTIVITY_CHANGED" }
            if (!context.eligible(habit) || head !in context.known(habit.uuid)) return null
            val previous = retained.checkpoints.associateBy { it.head.activityUuid }
            if (incoming.checkpoints.any { previous[it.head.activityUuid] != it }) return null
            val births = retained.births.associateBy { it.entityType to it.entityUuid }.toMutableMap()
            additions = incoming.births.filter { birth ->
                val old = births[birth.entityType to birth.entityUuid]
                require(old == null || old == birth) { "SYNC_CHALLENGE_BIRTH_CHANGED" }
                old == null
            }
            if (additions.any { it.entityType != "activity_event" || it.entityUuid != source.operation.entityUuid || it.head != head }) return null
            additions.forEach { births[it.entityType to it.entityUuid] = it }
            check(births["activity_event" to source.operation.entityUuid]?.head == head) { "COUNT_ROUND_BIRTH_REQUIRED" }
            merged = ChallengeMetadata(1, retained.checkpoints.sortedBy { it.head.activityUuid }.map {
                it.copy(records = it.records.sortedBy { record -> record.head.generation })
            }, births.values.sortedWith(compareBy({ it.entityType }, { it.entityUuid })))
        }
        val cursor = context.checked(CountTrustMembership.key("cursor.id", "1"), "next_sync_state", "id=?", arrayOf(1)).single()
        val marker = context.checked(CountTrustMembership.key("challenge.id", "1"), "next_challenge_state", "id=?", arrayOf(1)).single()
        val state = checkpoint.first
        check(cursor.long("challengeContract") == 1L && marker.text("accountId") == state.accountId &&
            marker.text("serverInstanceId") == state.serverInstanceId && marker.text("syncEpoch") == state.syncEpoch &&
            marker.text("deviceId") == state.deviceId && marker.long("generation") == state.generation &&
            marker.long("cursor") == state.cursor && marker.text("batchHash") == state.batchHash &&
            marker.text("metadataHash") == syncPayloadHash(json.encodeToString(ChallengeMetadata.serializer(), retained))) {
            "COUNT_ROUND_METADATA_CHANGED"
        }
        val effects = arrayListOf<CountTrustAck.Effect>()
        for (birth in additions) {
            check(context.checked(CountTrustMembership.key("birth.entity", birth.entityType, birth.entityUuid),
                "next_challenge_births", "entityType=? AND entityUuid=?", arrayOf(birth.entityType, birth.entityUuid)).isEmpty())
            val fields = mapOf("entityType" to birth.entityType, "entityUuid" to birth.entityUuid, "roundUuid" to birth.head.roundUuid)
            val layout = context.rows.table("next_challenge_births")
            val row = CountTrustRows.Row(layout, layout.columns.map { CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, fields.getValue(it)) })
            effects += CountTrustAck.Effect("next_challenge_births", null, row)
        }
        val after = CountTrustRows.Row(marker.table, marker.table.columns.map { field ->
            if (field == "metadataHash") CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING,
                syncPayloadHash(json.encodeToString(ChallengeMetadata.serializer(), merged))) else marker.cell(field)
        })
        // Preserve the original same-value marker UPDATE on replay and its exact control delta.
        effects += CountTrustAck.Effect("next_challenge_state", marker, after)
        val frozen = merged.copy(checkpoints = Collections.unmodifiableList(merged.checkpoints.map {
            it.copy(records = Collections.unmodifiableList(it.records.toList()))
        }), births = Collections.unmodifiableList(merged.births.toList()))
        return Prepared(context, frozen, Collections.unmodifiableList(effects))
    }

    fun apply(prepared: Prepared) {
        check(database.inTransaction())
        val sql = database.openHelper.writableDatabase
        for (effect in prepared.effects) {
            val after = requireNotNull(effect.after)
            if (effect.table == "next_challenge_births") {
                sql.execSQL("INSERT INTO next_challenge_births(entityType,entityUuid,roundUuid) VALUES(?,?,?)",
                    arrayOf(after.text("entityType"), after.text("entityUuid"), after.text("roundUuid")))
            } else {
                check(effect.table == "next_challenge_state" && effect.before != null)
                sql.execSQL("UPDATE next_challenge_state SET metadataHash=? WHERE id=1", arrayOf(after.text("metadataHash")))
            }
            check(prepared.context.rows.byIdentity(after.table.name, after.identity)?.value == after.value) { "COUNT_ROUND_ACK_EFFECT_CHANGED" }
        }
    }

    private companion object {
        val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
    }
}
