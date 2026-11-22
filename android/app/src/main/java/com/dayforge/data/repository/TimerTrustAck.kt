package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import java.util.Collections
import kotlinx.serialization.json.*

/** Predicts the original TIMER send/ACK writes from authenticated old rows and frozen input.
 * It neither performs the business writes nor replaces their original validators. In particular,
 * the original challenge merger still checks every other table and its exact generation delta.
 */
internal class TimerTrustAck(private val database: HabitDatabase) {
    private val json = Json { encodeDefaults = true }

    private class Dependency(val table: String, val where: String, args: Array<out Any?>,
        rows: List<CountTrustRows.Row>) {
        val args = args.copyOf()
        val values = Collections.unmodifiableList(rows.map { it.name to it.value })
        fun verify(native: CountTrustRows) {
            check(native.read(table, where, args).map { it.name to it.value } == values) {
                "TIMER_ACK_DEPENDENCY_CHANGED"
            }
        }
    }

    private class Prepared(override val context: CountTrustKernel.Context,
        effects: List<CountTrustAck.Effect>, override val metadata: ChallengeMetadata?,
        dependencies: List<Dependency>, private val owner: HabitDatabase) : TimerTrustUpdate {
        override val effects = Collections.unmodifiableList(effects.toList())
        override val displayEdits: Map<String, JsonElement?> = emptyMap()
        private val dependencies = Collections.unmodifiableList(dependencies.toList())
        override suspend fun verifyFinal(database: HabitDatabase) {
            check(database === owner && database.inTransaction()) { "TIMER_ACK_SCOPE_CHANGED" }
            dependencies.forEach { it.verify(context.rows) }
        }
    }

    private class Source(val session: String,
        val dependencies: MutableList<Dependency>)

    /** Called before insertTransmission, including no-write replay, in the original send scope. */
    suspend fun prepareSend(capture: CountTrustKernel.Capture?, access: LocalSyncAccess,
        origin: NextRequestOriginEntity, transmission: NextTransmissionEntity): TimerTrustUpdate? {
        val context = capture?.context ?: return null
        return context.index.withReadScope {
            val source = source(context, access, origin) ?: return@withReadScope null
            check(transmission.kind == NEXT_TIMER && transmission.requestId == origin.requestId &&
                transmission.queueId == origin.queueId && transmission.protocol == 5 &&
                transmission.accountId == origin.accountId && transmission.serverInstanceId == access.session.serverInstanceId &&
                transmission.syncEpoch == access.session.syncEpoch && transmission.deviceId == access.deviceId &&
                transmission.wireHash == nextRequestHash(transmission.wireBytes)) { "TIMER_SEND_INPUT_CHANGED" }
            validateNextTimerEnvelope(origin.intentJson, transmission.wireBytes, requireNotNull(access.deviceId))
            val old = context.checked(CountTrustMembership.key("timer.transmission", origin.requestId),
                "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, origin.requestId)).singleOrNull()
            val expected = transmissionRow(context.rows, transmission)
            val effects = if (old == null) listOf(CountTrustAck.Effect("next_transmissions", null, expected)) else {
                check(old.value == expected.value) { "TIMER_SEND_INPUT_CHANGED" }
                source.dependencies += Dependency("next_transmissions", "kind=? AND requestId=?",
                    arrayOf(NEXT_TIMER, origin.requestId), listOf(old))
                emptyList()
            }
            Prepared(context, effects, null, source.dependencies, database)
        }
    }

    /** Must run before acknowledgeInTransaction: even REPLAY writes its same-value marker. */
    suspend fun prepareAck(capture: CountTrustKernel.Capture?, access: LocalSyncAccess,
        origin: NextRequestOriginEntity, originHash: String, transmissionHash: String,
        result: TimerCommandResult, checkpoint: Pair<NextSyncStateEntity, ChallengeMetadata>?,
        incoming: ChallengeMetadata?): TimerTrustUpdate? {
        val context = capture?.context ?: return null
        return context.index.withReadScope {
            val source = source(context, access, origin) ?: return@withReadScope null
            check(result.status == "applied" && result.commandId == origin.requestId &&
                result.sessionId == source.session) { "TIMER_ACK_INPUT_CHANGED" }
            val transmission = context.checked(CountTrustMembership.key("timer.transmission", origin.requestId),
                "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, origin.requestId)).single()
            source.dependencies += Dependency("next_transmissions", "kind=? AND requestId=?",
                arrayOf(NEXT_TIMER, origin.requestId), listOf(transmission))
            val effects = arrayListOf<CountTrustAck.Effect>()
            val metadata = if (checkpoint == null) {
                check(!context.formal && incoming == null && roundTimerIntent(origin.intentJson) == null)
                null
            } else {
                check(context.formal && roundTimerIntent(origin.intentJson) != null)
                sidecar(context, access, checkpoint, requireNotNull(incoming), effects, source.dependencies)
                    ?: return@withReadScope null
            }
            val oldReceipt = context.checked(CountTrustMembership.key("timer.acceptance", origin.requestId),
                "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, origin.requestId)).singleOrNull()
            val queue = context.checked(CountTrustMembership.key("timer.request", origin.requestId),
                "timer_command_outbox", "commandId=?", arrayOf(origin.requestId)).singleOrNull()
            val bytes = encodeSyncRequest(TimerCommandResult.serializer(), result)
            decodeFrozenSyncRequest(bytes, TimerCommandResult.serializer())
            val receipt = native(context.rows, "next_acceptances", mapOf<String, Any?>("kind" to NEXT_TIMER,
                "requestId" to origin.requestId, "originHash" to originHash, "transmissionHash" to transmissionHash,
                "resultHash" to nextRequestHash(bytes), "resultJson" to bytes.toString(Charsets.UTF_8)))
            if (oldReceipt == null) {
                if (queue == null || queue.long("id") != origin.queueId || queue.text("sessionUuid") != source.session)
                    return@withReadScope null
                effects += CountTrustAck.Effect("next_acceptances", null, receipt)
                effects += CountTrustAck.Effect("timer_command_outbox", queue, null)
            } else {
                if (queue != null || oldReceipt.value != receipt.value) return@withReadScope null
                source.dependencies += Dependency("next_acceptances", "kind=? AND requestId=?",
                    arrayOf(NEXT_TIMER, origin.requestId), listOf(oldReceipt))
            }
            Prepared(context, effects, metadata, source.dependencies, database)
        }
    }

    private suspend fun source(context: CountTrustKernel.Context, access: LocalSyncAccess,
        origin: NextRequestOriginEntity): Source? {
        check(database.inTransaction())
        if (!context.timers || origin.kind != NEXT_TIMER) return null
        check(context.access.session == access.session && context.access.capturedDeviceId == access.deviceId)
        val command = decodeNextTimerIntent(origin.intentJson).command
        check(command.commandId == origin.requestId)
        val logs = context.checked(CountTrustMembership.key("timer.uuid", command.sessionId),
            "timelogs", "uuid=?", arrayOf(command.sessionId))
        // Cancelled/remote-only sessions keep the original complete path and stay dirty.
        val log = logs.singleOrNull() ?: return null
        val habit = requireNotNull(database.habitDao().getHabitById(log.long("habitId")))
        if (!context.timerEligible(habit)) return null
        val dependencies = arrayListOf<Dependency>()
        fun retain(key: String, table: String, where: String, args: Array<out Any?>): List<CountTrustRows.Row> =
            context.checked(key, table, where, args).also { dependencies += Dependency(table, where, args, it) }
        val rawOrigin = retain(CountTrustMembership.key("origin.request", NEXT_TIMER, origin.requestId),
            "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_TIMER, origin.requestId)).single()
        check(rawOrigin.text("intentJson") == origin.intentJson && rawOrigin.long("queueId") == origin.queueId &&
            rawOrigin.text("accountId") == access.session.authentication.userId)
        val rawHabit = retain(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", "uuid=?", arrayOf(habit.uuid)).single()
        check(rawHabit.long("id") == habit.id)
        dependencies += Dependency("timelogs", "uuid=?", arrayOf(command.sessionId), logs)
        retain(CountTrustMembership.key("timer.segments", command.sessionId), "timer_segments", "sessionUuid=?", arrayOf(command.sessionId))
        retain(CountTrustMembership.key("timer.allocations", command.sessionId), "timelog_day_allocations", "sessionUuid=?", arrayOf(command.sessionId))
        return Source(command.sessionId, dependencies)
    }

    private suspend fun sidecar(context: CountTrustKernel.Context, access: LocalSyncAccess,
        checkpoint: Pair<NextSyncStateEntity, ChallengeMetadata>, incoming: ChallengeMetadata,
        effects: MutableList<CountTrustAck.Effect>, dependencies: MutableList<Dependency>): ChallengeMetadata? {
        val retained = Json.decodeFromJsonElement(ChallengeMetadata.serializer(),
            requireNotNull(context.index.get(CountTrustRoundEvidence.METADATA)))
        check(retained == checkpoint.second) { "TIMER_ACK_METADATA_CHANGED" }
        val histories = retained.checkpoints.associateBy { it.head.activityUuid }.toMutableMap()
        for (next in incoming.checkpoints) {
            val old = histories[next.head.activityUuid]
            if (old != null) {
                require(next.head.generation >= old.head.generation) { "SYNC_CHALLENGE_HEAD_BEHIND" }
                val records = next.records.associateBy { it.head.generation }
                require(old.records.all { records[it.head.generation] == it }) { "SYNC_CHALLENGE_HISTORY_CHANGED" }
            }
            if (next.copy(records = next.records.sortedBy { it.head.generation }) != old) {
                val habit = database.habitDao().getHabitByUuid(next.head.activityUuid)
                // A newly selected partition needs a separately audited display rebuild. Do not
                // discard other legitimate metadata or partially seal the rest of this ACK.
                if (habit != null && (context.eligible(habit) || context.timerEligible(habit))) return null
            }
            histories[next.head.activityUuid] = next
        }
        val births = retained.births.associateBy { it.entityType to it.entityUuid }.toMutableMap()
        for (birth in incoming.births) {
            val previous = births[birth.entityType to birth.entityUuid]
            require(previous == null || previous == birth) { "SYNC_CHALLENGE_BIRTH_CHANGED" }
            if (birth.entityType != "timer_session") {
                context.index.get(CountTrustRoundEvidence.factKey(birth.entityUuid))?.let {
                    if (CountTrustRoundEvidence.decode(it) != birth.head) return null
                }
            }
            val timerBirth = context.index.get(TimerTrustEvidence.birthKey(birth.entityUuid))
            if (timerBirth != null) {
                if (TimerTrustEvidence.decodeBirth(timerBirth) != birth.head) return null
            } else {
                // Unrelated server births need no local TIMER leaf. A retained qualified
                // session cannot lose its leaf and be treated as an unrelated/plain birth.
                val local = context.checked(CountTrustMembership.key("timer.uuid", birth.entityUuid),
                    "timelogs", "uuid=?", arrayOf(birth.entityUuid)).singleOrNull()
                if (local != null) {
                    val habit = database.habitDao().getHabitById(local.long("habitId"))
                    if (habit != null) check(!context.timerEligible(habit)) { "TIMER_TRUST_BIRTH_MISSING" }
                }
            }
            births[birth.entityType to birth.entityUuid] = birth
        }
        val merged = ChallengeMetadata(1, histories.values.sortedBy { it.head.activityUuid }.map {
            it.copy(records = Collections.unmodifiableList(it.records.sortedBy { record -> record.head.generation }))
        }, Collections.unmodifiableList(births.values.sortedWith(compareBy({ it.entityType }, { it.entityUuid }))))
        val state = checkpoint.first
        val cursor = context.checked(CountTrustMembership.key("cursor.id", "1"), "next_sync_state", "id=?", arrayOf(1)).single()
        dependencies += Dependency("next_sync_state", "id=?", arrayOf(1), listOf(cursor))
        val marker = context.checked(CountTrustMembership.key("challenge.id", "1"), "next_challenge_state", "id=?", arrayOf(1)).single()
        check(cursor.long("challengeContract") == 1L && marker.text("accountId") == state.accountId &&
            marker.text("serverInstanceId") == state.serverInstanceId && marker.text("syncEpoch") == state.syncEpoch &&
            marker.text("deviceId") == state.deviceId && marker.long("generation") == state.generation &&
            marker.long("cursor") == state.cursor && marker.text("batchHash") == state.batchHash &&
            marker.text("metadataHash") == syncPayloadHash(json.encodeToString(ChallengeMetadata.serializer(), retained))) {
            "TIMER_ACK_METADATA_CHANGED"
        }
        check(state.accountId == access.session.authentication.userId && state.deviceId == access.deviceId)
        val oldRecords = retained.checkpoints.flatMap { it.records }.associateBy { it.head.roundUuid }
        for (record in merged.checkpoints.flatMap { it.records }) if (record.head.roundUuid !in oldRecords) {
            check(context.checked(CountTrustMembership.key("round.uuid", record.head.roundUuid),
                "next_challenge_rounds", "roundUuid=?", arrayOf(record.head.roundUuid)).isEmpty())
            val body = json.encodeToString(com.dayforge.domain.model.ChallengeRoundRecord.serializer(), record)
            effects += CountTrustAck.Effect("next_challenge_rounds", null, native(context.rows, "next_challenge_rounds",
                mapOf<String, Any?>("roundUuid" to record.head.roundUuid, "activityUuid" to record.head.activityUuid,
                    "generation" to record.head.generation.toLong(), "accountId" to state.accountId,
                    "serverInstanceId" to state.serverInstanceId, "syncEpoch" to state.syncEpoch,
                    "sourceDeviceUuid" to record.sourceDeviceUuid, "operationUuid" to record.restartOperationUuid,
                    "recordJson" to body, "recordHash" to syncPayloadHash(body))))
        }
        val oldBirths = retained.births.map { it.entityType to it.entityUuid }.toSet()
        for (birth in merged.births) if (birth.entityType to birth.entityUuid !in oldBirths) {
            check(context.checked(CountTrustMembership.key("birth.entity", birth.entityType, birth.entityUuid),
                "next_challenge_births", "entityType=? AND entityUuid=?", arrayOf(birth.entityType, birth.entityUuid)).isEmpty())
            effects += CountTrustAck.Effect("next_challenge_births", null, native(context.rows, "next_challenge_births",
                mapOf<String, Any?>("entityType" to birth.entityType, "entityUuid" to birth.entityUuid, "roundUuid" to birth.head.roundUuid)))
        }
        // The original DAO updates every marker column, even when all values are unchanged.
        effects += CountTrustAck.Effect("next_challenge_state", marker, native(context.rows, "next_challenge_state",
            mapOf<String, Any?>("accountId" to state.accountId, "serverInstanceId" to state.serverInstanceId,
                "syncEpoch" to state.syncEpoch, "deviceId" to state.deviceId, "generation" to state.generation,
                "cursor" to state.cursor, "batchHash" to state.batchHash, "id" to 1,
                "metadataHash" to syncPayloadHash(json.encodeToString(ChallengeMetadata.serializer(), merged)))))
        return merged.copy(checkpoints = Collections.unmodifiableList(merged.checkpoints))
    }

    private fun transmissionRow(rows: CountTrustRows, value: NextTransmissionEntity) = native(rows, "next_transmissions",
        mapOf<String, Any?>("kind" to value.kind, "requestId" to value.requestId, "queueId" to value.queueId,
            "protocol" to value.protocol, "accountId" to value.accountId, "serverInstanceId" to value.serverInstanceId,
            "syncEpoch" to value.syncEpoch, "deviceId" to value.deviceId, "wireHash" to value.wireHash,
            "wireBytes" to value.wireBytes))

    private fun native(rows: CountTrustRows, table: String, fields: Map<String, Any?>): CountTrustRows.Row {
        val layout = rows.table(table)
        check(layout.columns.toSet() == fields.keys) { "TIMER_ACK_LAYOUT_CHANGED" }
        return CountTrustRows.Row(layout, layout.columns.map { name -> when (val value = fields.getValue(name)) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            is Int -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value.toLong())
            is Long -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
            is ByteArray -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_BLOB, value.toList())
            else -> error("TIMER_ACK_CELL_TYPE")
        } })
    }
}
