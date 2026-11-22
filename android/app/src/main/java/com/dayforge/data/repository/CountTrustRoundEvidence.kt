package com.dayforge.data.repository

import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.YearMonth
import kotlinx.serialization.json.*

/** Pure partition identities plus exact source binding. These functions do not grant authority:
 * the kernel supplies the complete, authenticated members and owns the transaction/journal.
 * Shared habit/date policy is deliberately NOT partitioned by round.
 */
internal object CountTrustRoundEvidence {
    const val COVERAGE = "formal-round-members/v1"
    const val METADATA = "formal:metadata"
    const val COVERAGE_KEY = "formal:coverage"

    fun encode(head: ChallengeRoundHead): JsonElement = Json.encodeToJsonElement(ChallengeRoundHead.serializer(), head)
    fun decode(value: JsonElement): ChallengeRoundHead = Json.decodeFromJsonElement(ChallengeRoundHead.serializer(), value)
    fun headKey(activity: String) = CountTrustMembership.key("formal.head", activity)
    fun factKey(uuid: String) = CountTrustMembership.key("formal.fact-head", uuid)
    fun knownKey(activity: String) = CountTrustMembership.key("formal.known-heads", activity)
    fun monthsKey(activity: String, head: ChallengeRoundHead?) = if (head == null) "months:$activity"
        else CountTrustMembership.key("formal.months", activity, encode(head).toString())
    fun summaryKey(activity: String, month: YearMonth, head: ChallengeRoundHead?) = if (head == null) "summary:$activity:$month"
        else CountTrustMembership.key("formal.summary", activity, encode(head).toString(), month.toString())

    /** Called only AFTER the independent original reader validated every retained fact, including
     * hidden rounds and pending source/receipt/wire conditions. Do not derive old births from the
     * currently displayed head. This pass selects identities, not a replacement cold validator.
     */
    suspend fun bootstrap(metadata: ChallengeMetadata, facts: List<CountTrustRows.Row>,
        origins: List<CountTrustRows.Row>): Map<String, ChallengeRoundHead> {
        val known = metadata.checkpoints.flatMap { it.records }.mapTo(hashSetOf()) { it.head }
        val accepted = metadata.births.filter { it.entityType == "activity_event" }.associate { it.entityUuid to it.head }
        val byEntity = linkedMapOf<String, MutableList<NextRoundOperationIntent>>()
        for (row in origins) if (row.text("kind") == NEXT_OPERATION) {
            val source = roundOperationIntent(row.text("intentJson")) ?: continue
            if (source.operation.entityType == "activity_event" && source.operation.action == "upsert" &&
                source.operation.payload["event_type"] in quantityTypes)
                byEntity.getOrPut(source.operation.entityUuid) { arrayListOf() }.add(source)
        }
        return facts.associate { fact ->
            val id = fact.text("uuid")
            val sources = byEntity[id].orEmpty()
            check(sources.size <= 1) { "COUNT_ROUND_SOURCE_CHANGED" }
            val source = sources.singleOrNull()
            val head = accepted[id] ?: requireNotNull(source?.context?.head) { "COUNT_ROUND_BIRTH_REQUIRED" }
            check(head in known && head.activityUuid == fact.text("habitUuid") &&
                (source == null || source.context.head == head)) { "COUNT_ROUND_INVALID" }
            id to head
        }
    }

    /** Incremental counterpart of the original round selector. A receipt is never an inferred
     * birth; a pending fact must still retain its exact original queue and optional frozen wire.
     * Callers provide fresh bounded SQL checks for absence/hash and immutable transmission.
     */
    suspend fun bindFact(fact: CountTrustRows.Row, origins: List<CountTrustRows.Row>,
        accepted: ChallengeRoundHead?, known: Set<ChallengeRoundHead>, access: LocalCoreWriteAccess,
        pending: suspend (CountTrustRows.Row, NextRoundOperationIntent) -> Unit): ChallengeRoundHead {
        val matches = origins.mapNotNull { row ->
            val operation = decodeNextOperationIntent(row.text("intentJson"))
            if (operation.entityType != "activity_event" || operation.entityUuid != fact.text("uuid") ||
                operation.action != "upsert" || operation.payload["event_type"] !in quantityTypes) null
            else row to roundOperationIntent(row.text("intentJson"))
        }
        check(matches.size <= 1) { "COUNT_FACT_INVALID" }
        val original = matches.singleOrNull()
        val source = original?.second
        accepted?.let { check(it.activityUuid == fact.text("habitUuid") && it in known) { "COUNT_ROUND_INVALID" } }
        if (source != null) {
            val row = requireNotNull(original).first
            check(row.text("accountId") == access.session.authentication.userId &&
                source.operation.operationId == row.text("requestId") &&
                source.context.head?.activityUuid == fact.text("habitUuid") &&
                source.context.head in known && (accepted == null || accepted == source.context.head)) { "COUNT_ROUND_INVALID" }
        }
        if (accepted != null) return accepted
        check(source != null) { "COUNT_ROUND_BIRTH_REQUIRED" }
        val row = original.first
        check(row.text("serverInstanceId") == access.session.serverInstanceId &&
            row.text("syncEpoch") == access.session.syncEpoch && source.capturedDeviceId == access.capturedDeviceId) {
            "COUNT_ROUND_SOURCE_CHANGED"
        }
        pending(row, source)
        return requireNotNull(source.context.head)
    }

    private val quantityTypes = setOf(JsonPrimitive("check_in"), JsonPrimitive("count_delta"), JsonPrimitive("count_snapshot"))
}
