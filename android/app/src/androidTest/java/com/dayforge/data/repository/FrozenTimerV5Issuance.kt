package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact pre-TIMER v5 issuance values, frozen from candidate/before/CountTrustKernel.kt.
 * Never manufactures a legacy proof by relabeling or filtering a new TIMER certificate.
 */
internal object FrozenTimerV5Issuance {
    const val VALIDATOR = "count-trust/round-recurring-v5/check-presence-and-native-rules/2026-11-02"
    private const val OBSERVATION_COVERAGE_KEY = "coverage:metric-observation"
    private const val OBSERVATION_COVERAGE = "metric-observation/native-members-v1/2026-10-24"
    private const val CHECK_COVERAGE_KEY = "check:source-coverage"
    private const val CHECK_COVERAGE = "all-retained-recurring-check/v1"
    val TABLES = FrozenCountV4Issuance.TABLES
    private fun eligibleKey(uuid: String) = "eligible:$uuid"
    private fun checkEligibleKey(uuid: String) = "check:eligible:$uuid"
    private fun monthsKey(uuid: String, head: ChallengeRoundHead? = null) = CountTrustRoundEvidence.monthsKey(uuid, head)
    private fun summaryKey(uuid: String, month: YearMonth, head: ChallengeRoundHead? = null) = CountTrustRoundEvidence.summaryKey(uuid, month, head)
    suspend fun values(all: List<CountTrustRows.Row>, habits: List<HabitEntity>, metadata: ChallengeMetadata?,
        evidence: Map<String, RecurringCompletionEvidence>, environment: CountTrustValidationEnvironment.Snapshot,
        commonHealthy: Boolean): Map<String, JsonElement> {
        val values = linkedMapOf<String, JsonElement>()
        val members = linkedMapOf<String, MutableSet<String>>()
        for (row in all) {
            currentCoroutineContext().ensureActive()
            values[row.name] = row.value
            CountTrustMembership.memberships(row).forEach { (key, member) -> members.getOrPut(key) { sortedSetOf() }.add(member) }
        }
        members.forEach { (key, set) -> values[key] = JsonArray(set.map(::JsonPrimitive)) }
        val countIds = habits.mapTo(hashSetOf()) { it.id }
        val factHeads = if (metadata == null) emptyMap() else CountTrustRoundEvidence.bootstrap(metadata,
            all.filter { it.table.name == "completions" && it.long("habitId") in countIds },
            all.filter { it.table.name == "next_request_origins" })
        if (metadata != null) {
            values[CountTrustRoundEvidence.COVERAGE_KEY] = JsonPrimitive(CountTrustRoundEvidence.COVERAGE)
            values[CountTrustRoundEvidence.METADATA] = Json.encodeToJsonElement(ChallengeMetadata.serializer(), metadata)
            factHeads.forEach { (uuid, head) -> values[CountTrustRoundEvidence.factKey(uuid)] = CountTrustRoundEvidence.encode(head) }
        }
        for (habit in habits) {
            val source = evidence.getValue(habit.uuid)
            val factRows = all.filter { it.table.name == "completions" && it.long("habitId") == habit.id }
            val dayRows = all.filter { it.table.name == "count_days" && it.long("habitId") == habit.id }
            val months = (source.completions.map { YearMonth.from(it.businessDate) } +
                source.policies.keys.map(YearMonth::from) + source.unknownDates.map(YearMonth::from)).toSortedSet()
            if (habit.habitType == HabitType.CHECK_IN)
                values[checkEligibleKey(habit.uuid)] = JsonPrimitive(CHECK_COVERAGE)
            else values[eligibleKey(habit.uuid)] = JsonPrimitive(true)
            val heads: List<ChallengeRoundHead?> = if (metadata == null) listOf(null) else {
                val checkpoint = metadata.checkpoints.single { it.head.activityUuid == habit.uuid }
                check(source.roundHead == checkpoint.head) { "COUNT_ROUND_INVALID" }
                values[CountTrustRoundEvidence.headKey(habit.uuid)] = CountTrustRoundEvidence.encode(checkpoint.head)
                checkpoint.records.map { it.head }.also { known ->
                    values[CountTrustRoundEvidence.knownKey(habit.uuid)] = JsonArray(known.map(CountTrustRoundEvidence::encode))
                }
            }
            // Every retained round has its own quantity/latest result. Day policy stays shared.
            for (head in heads) {
                val partitionFacts = factRows.filter { head == null || factHeads[it.text("uuid")] == head }
                val partitionMonths = (months + partitionFacts.map { YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) }).toSortedSet()
                values[monthsKey(habit.uuid, head)] = JsonArray(partitionMonths.map { JsonPrimitive(it.toString()) })
                for (month in partitionMonths) values[summaryKey(habit.uuid, month, head)] = CountTrustMembership.summary(habit.uuid, month,
                    partitionFacts.filter { YearMonth.from(LocalDate.parse(it.text("recordedLocalDate"))) == month },
                    dayRows.filter { YearMonth.from(LocalDate.parse(it.text("localDate"))) == month },
                    if (head == null || head == source.roundHead) source.unknownDates.filterTo(linkedSetOf()) { YearMonth.from(it) == month }
                        else null)
                if (head != null || habit.habitType == HabitType.CHECK_IN) {
                    val completeMonths = partitionMonths.associateWith { month ->
                        requireNotNull(values[summaryKey(habit.uuid, month, head)]).jsonObject }
                    values.putAll(if (habit.habitType == HabitType.CHECK_IN)
                        CheckDisplayBlocks.issue(habit, head, completeMonths, ZoneId.systemDefault())
                        else CountDisplayBlocks.issue(habit, requireNotNull(head), completeMonths, ZoneId.systemDefault()))
                }
            }
        }
        values[CHECK_COVERAGE_KEY] = JsonPrimitive(CHECK_COVERAGE)
        values[CheckDisplayBlocks.COVERAGE_KEY] = JsonPrimitive(CheckDisplayBlocks.COVERAGE)
        if (metadata != null) values[CountDisplayBlocks.COVERAGE_KEY] = JsonPrimitive(CountDisplayBlocks.COVERAGE)
        // Empty tables and no-history habits still have a root with authenticated absence.
        values["format"] = JsonPrimitive(VALIDATOR)
        values[CountTrustValidationEnvironment.KEY] = environment.toJson()
        values["global_common"] = JsonPrimitive(commonHealthy)
        values[OBSERVATION_COVERAGE_KEY] = JsonPrimitive(OBSERVATION_COVERAGE)
        values["activity_shadow_count"] = JsonPrimitive(all.count { it.table.name == "sync_entity_state" && it.text("entityType") == "activity_event" })
        return values
    }
}
