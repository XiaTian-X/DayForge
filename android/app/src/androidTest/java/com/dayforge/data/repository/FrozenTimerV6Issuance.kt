package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Frozen v6 issuance additions from the actual 536 kernel. Builds old values from the
 * original full audits/raw rows, never by relabeling a new tagged-birth certificate. */
internal object FrozenTimerV6Issuance {
    const val VALIDATOR = "count-trust/round-recurring-v6/timer-millis-and-native-rules/2026-11-02"
    private const val SOURCE_COVERAGE = "retained-timer-chain-segments-allocations/v1"
    val TABLES = (FrozenTimerV5Issuance.TABLES + listOf("timer_segments", "timelog_day_allocations",
        "next_transmissions", "next_acceptances")).distinct()

    suspend fun values(all: List<CountTrustRows.Row>, habits: List<HabitEntity>, metadata: ChallengeMetadata?,
        evidence: Map<String, RecurringCompletionEvidence>, timers: List<TimerTrustEvidence>,
        environment: CountTrustValidationEnvironment.Snapshot, commonHealthy: Boolean): Map<String, JsonElement> {
        val values = FrozenTimerV5Issuance.values(all, habits, metadata, evidence, environment, commonHealthy).toMutableMap()
        val members = linkedMapOf<String, MutableSet<String>>()
        for (row in all) {
            fun add(kind: String, vararg parts: String) {
                val key = CountTrustMembership.key(kind, *parts)
                members.getOrPut(key) { sortedSetOf() }.add(row.name)
            }
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
                    require(command.commandId == row.text("requestId"))
                    add("timer.origin", command.sessionId)
                }
                "next_transmissions" -> if (row.text("kind") == NEXT_TIMER) add("timer.transmission", row.text("requestId"))
                "next_acceptances" -> if (row.text("kind") == NEXT_TIMER) add("timer.acceptance", row.text("requestId"))
            }
        }
        members.forEach { (key, items) -> values[key] = JsonArray((items + values[key]?.jsonArray.orEmpty()
            .map { it.jsonPrimitive.content }).toSortedSet().map(::JsonPrimitive)) }
        check(all.count { it.table.name == "timelogs" && it.nullableLong("endTime") == null } <= 1)
        for (audited in timers) {
            val habit = audited.habit
            val heads: List<ChallengeRoundHead?> = if (metadata == null) listOf(null) else {
                val checkpoint = metadata.checkpoints.single { it.head.activityUuid == habit.uuid }
                check(audited.displayedHead == checkpoint.head)
                values[CountTrustRoundEvidence.headKey(habit.uuid)] = CountTrustRoundEvidence.encode(checkpoint.head)
                checkpoint.records.map { it.head }.also { known ->
                    values[CountTrustRoundEvidence.knownKey(habit.uuid)] = JsonArray(known.map(CountTrustRoundEvidence::encode))
                }
            }
            for (session in audited.sessions) {
                check(session.head in heads)
                // Exact old representation: plain birth was a top-level tombstone.
                values[CountTrustMembership.key("timer.birth", session.log.uuid)] =
                    session.head?.let(CountTrustRoundEvidence::encode) ?: JsonNull
            }
            heads.forEach { head -> values.putAll(TimerDisplayBlocks.issue(habit, head,
                audited.completedMillis(head), ZoneId.systemDefault())) }
            values["timer:eligible:${habit.uuid}"] = JsonPrimitive(SOURCE_COVERAGE)
        }
        values["timer:qualified-habits"] = JsonArray(timers.map { JsonPrimitive(it.habit.uuid) }.sortedBy { it.content })
        values["timer:source-coverage"] = JsonPrimitive(SOURCE_COVERAGE)
        values["formal:timer-display-coverage"] = JsonPrimitive("timer-completed-millis-calendar/v1")
        values["format"] = JsonPrimitive(VALIDATOR)
        return values
    }
}
