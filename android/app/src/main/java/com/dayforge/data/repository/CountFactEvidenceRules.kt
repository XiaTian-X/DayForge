package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Change
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** The original per-fact rules, independent of how a complete evidence set is obtained.
 * These ordinary arguments grant no trust: the caller must separately prove raw SQL types,
 * complete membership, origin uniqueness, accepted/reverted membership and current ownership.
 * No SQL, retained state, authority, signing or recovery is performed here.
 */
internal object CountFactEvidenceRules {
    fun verify(habit: HabitEntity, fact: CompletionEntity, policy: CountDayPolicy?,
        original: JsonObject?, accepted: SyncV2Change?, reverted: Boolean) {
        check(!reverted) { "COUNT_FACT_INVALID" }
        if (accepted != null) {
            check(accepted.payload["event_type"] in quantityTypes) { "COUNT_FACT_INVALID" }
            val wasCheckIn = accepted.payload["event_type"] == JsonPrimitive("check_in")
            check(fact == NextCommonFactMapper.completion(accepted, habit, fact) &&
                NextCommonFactMapper.countPolicy(accepted.payload) ==
                    (if (wasCheckIn) null else policy)) { "COUNT_FACT_INVALID" }
            original?.let { NextCommonFactProof.requireOriginalPayload(it, accepted,
                accepted.payload.getValue("source_device_id").jsonPrimitive.content) }
        } else if (original != null) {
            verifyPendingFields(habit.uuid, fact.value, fact.actualCompletedAt, fact.date,
                fact.recordedLocalDate, fact.recordedTimezone, policy, original)
        } else {
            check(policy == null) { "COUNT_FACT_INVALID" }
        }
    }

    /** The original pending-source rule, on only the fields it actually reads. No synthetic
     * UUID/row ID/createdAt is needed to validate this before the original T1 allocation point.
     * Exact native identity, complete source membership and absence remain the caller's proof.
     */
    internal fun verifyPendingFields(activityUuid: String, value: Int, actualCompletedAt: Long?,
        legacyDateMillis: Long, recordedLocalDate: String, recordedTimezone: String,
        policy: CountDayPolicy?, original: JsonObject) {
        val captured = NextCommonFactMapper.countPolicy(original)
        val wasCheckIn = original["event_type"] == JsonPrimitive("check_in")
        val expected = SyncV2Mapper.completionFields(activityUuid,
            if (wasCheckIn) HabitType.CHECK_IN else HabitType.COUNTING, value,
            actualCompletedAt ?: legacyDateMillis, recordedLocalDate, recordedTimezone)
        check(JsonObject(original.filterKeys { it != "count_policy" }) == expected &&
            captured == (if (wasCheckIn) null else policy) && (!wasCheckIn || value == 1)) {
            "COUNT_FACT_INVALID"
        }
        val instant = Instant.ofEpochMilli(requireNotNull(actualCompletedAt) { "COUNT_FACT_INVALID" })
        val zone = ZoneId.of(recordedTimezone)
        val date = java.time.LocalDate.parse(recordedLocalDate)
        check(instant.atZone(zone).toLocalDate() == date &&
            legacyDateMillis == date.atStartOfDay(zone).toInstant().toEpochMilli()) { "COUNT_FACT_INVALID" }
    }

    private val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
}
