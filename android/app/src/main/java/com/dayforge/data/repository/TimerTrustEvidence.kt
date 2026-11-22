package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.LocalDate
import java.util.Collections
import kotlinx.serialization.json.*

/** Owned values produced only after the original retained timer audit returns. These are
 * qualification inputs, not an authority or a replacement for fresh native/END checks.
 */
internal class TimerTrustEvidence internal constructor(
    val habit: HabitEntity,
    val displayedHead: ChallengeRoundHead?,
    sessions: List<Session>
) {
    class Session internal constructor(
        val log: TimeLogEntity,
        val head: ChallengeRoundHead?,
        segments: List<TimerSegmentEntity>,
        allocations: List<TimeLogDayAllocationEntity>
    ) {
        val segments: List<TimerSegmentEntity> = Collections.unmodifiableList(segments.toList())
        val allocations: List<TimeLogDayAllocationEntity> = Collections.unmodifiableList(allocations.toList())
    }
    val sessions: List<Session> = Collections.unmodifiableList(sessions.toList())
    init {
        require(this.sessions.map { it.log.uuid }.distinct().size == this.sessions.size)
        require(this.sessions.all { it.log.habitId == habit.id })
        require(this.sessions.count { it.log.endTime == null } <= 1)
    }
    fun completedMillis(head: ChallengeRoundHead?): Map<LocalDate, Long> {
        val result = linkedMapOf<LocalDate, Long>()
        sessions.filter { it.head == head && it.log.endTime != null }.forEach { session ->
            session.allocations.forEach { allocation ->
                val date = LocalDate.parse(allocation.localDate)
                result[date] = Math.addExact(result[date] ?: 0L, allocation.durationMillis)
            }
        }
        return Collections.unmodifiableMap(result)
    }

    companion object {
        const val COVERAGE_KEY = "timer:source-coverage"
        const val COVERAGE = "retained-timer-chain-segments-allocations/v1"
        const val HABITS_KEY = "timer:qualified-habits"
        fun eligibleKey(uuid: String) = "timer:eligible:$uuid"
        fun birthKey(session: String) = CountTrustMembership.key("timer.birth", session)

        /** Top-level JsonNull is the index tombstone, never a retained plain birth. */
        fun encodeBirth(head: ChallengeRoundHead?): JsonObject = buildJsonObject {
            put("kind", if (head == null) "plain" else "round")
            if (head != null) put("head", CountTrustRoundEvidence.encode(head))
        }

        fun decodeBirth(value: JsonElement): ChallengeRoundHead? {
            val body = value as? JsonObject ?: error("TIMER_TRUST_BIRTH_INVALID")
            val kind = body["kind"] as? JsonPrimitive ?: error("TIMER_TRUST_BIRTH_INVALID")
            check(kind.isString) { "TIMER_TRUST_BIRTH_INVALID" }
            return when (kind.content) {
                "plain" -> {
                    check(body.keys == setOf("kind")) { "TIMER_TRUST_BIRTH_INVALID" }
                    null
                }
                "round" -> {
                    check(body.keys == setOf("kind", "head")) { "TIMER_TRUST_BIRTH_INVALID" }
                    CountTrustRoundEvidence.decode(body.getValue("head"))
                }
                else -> error("TIMER_TRUST_BIRTH_INVALID")
            }
        }

        /** Extra routing exists only in a root whose original TIMER audit completed.
         * The ordinary COUNT/CHECK table vocabulary is deliberately not widened.
         */
        suspend fun memberships(row: CountTrustRows.Row): Map<String, String> {
            val result = linkedMapOf<String, String>()
            fun add(kind: String, vararg parts: String) { result[CountTrustMembership.key(kind, *parts)] = row.name }
            when (row.table.name) {
                "timelogs" -> {
                    add("timer.habit", row.long("habitId").toString())
                    if (row.nullableLong("endTime") == null) {
                        add("timer.active", row.long("habitId").toString()); add("timer.active.global")
                    }
                }
                "timer_segments" -> add("timer.segments", row.text("sessionUuid"))
                "timelog_day_allocations" -> add("timer.allocations", row.text("sessionUuid"))
                "next_request_origins" -> if (row.text("kind") == NEXT_TIMER) {
                    val command = decodeNextTimerIntent(row.text("intentJson")).command
                    require(command.commandId == row.text("requestId")) { "TIMER_COMMAND_ID_CHANGED" }
                    add("timer.origin", command.sessionId)
                }
                "next_transmissions" -> if (row.text("kind") == NEXT_TIMER)
                    add("timer.transmission", row.text("requestId"))
                "next_acceptances" -> if (row.text("kind") == NEXT_TIMER)
                    add("timer.acceptance", row.text("requestId"))
            }
            return result
        }

        fun log(row: CountTrustRows.Row): TimeLogEntity {
            require(row.table.name == "timelogs")
            return TimeLogEntity(row.long("id"), row.long("habitId"), row.long("startTime"), row.nullableLong("endTime"),
                row.int("durationSeconds"), row.bool("isPaused"), row.nullableLong("pausedAt"),
                row.long("accumulatedPauseMillis"), row.int("timerNextCommandSequence"), row.int("timerControlGeneration"),
                row.nullableLong("timerLastCommandAt"), row.nullableText("timerTimezone"), row.long("timerActiveElapsedMillis"),
                row.nullableLong("timerElapsedRealtimeAnchor"), row.nullableLong("timerBootCount")?.also {
                    require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                }?.toInt(), row.long("date"), row.text("uuid"), row.long("createdAt"), row.long("updatedAt"))
        }
        fun segment(row: CountTrustRows.Row): TimerSegmentEntity {
            require(row.table.name == "timer_segments")
            return TimerSegmentEntity(row.long("id"), row.text("sessionUuid"), row.int("sequence"),
                row.long("startedAt"), row.nullableLong("endedAt"))
        }
    }
}

/** Only the two original TIMER boundaries can implement this frozen mutation description.
 * It is consumed inside its capture's original transaction, before final auth and actual END.
 */
internal sealed interface TimerTrustUpdate {
    val context: CountTrustKernel.Context
    val effects: List<CountTrustAck.Effect>
    val metadata: com.dayforge.data.api.dto.ChallengeMetadata?
    val displayEdits: Map<String, kotlinx.serialization.json.JsonElement?>
    suspend fun verifyFinal(database: com.dayforge.data.local.HabitDatabase)
}
