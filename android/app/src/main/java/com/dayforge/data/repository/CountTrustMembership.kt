package com.dayforge.data.repository

import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.api.decodeSyncReply
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountSummary
import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact routing and pure arithmetic only. A Row/List is NOT an authenticated capability.
 * The kernel must prove complete indexed membership, raw values, business rules and ownership
 * before these results can replace an original scan. No SQL, signing or authority lives here.
 */
internal object CountTrustMembership {
    /** JSON framing avoids delimiter/Unicode collisions. Keys are exact, never LIKE probes. */
    fun key(kind: String, vararg parts: String): String = "members:" + JsonArray((listOf(kind) + parts).map(::JsonPrimitive))

    /** Each value is one member; callers maintain the authenticated sorted sets. Deliberately
     * no whole-table set: a normal write must not rewrite an account's complete history array.
     */
    suspend fun memberships(row: CountTrustRows.Row): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun add(kind: String, vararg parts: String) { result[key(kind, *parts)] = row.name }
        fun dated(prefix: String, owner: String, text: String) {
            val date = date(text)
            add("$prefix.month", owner, YearMonth.from(date).toString())
            add("$prefix.date", owner, text)
        }
        suspend fun body(text: String): JsonObject {
            val caller = currentCoroutineContext()
            return decodeSyncReply(text.toByteArray(Charsets.UTF_8), SYNC_REQUEST_LIMIT,
                JsonObject.serializer(), { caller.ensureActive() })
        }
        fun event(prefix: String, payload: JsonObject) {
            val activity = payload.string("activity_uuid")
            // ONCE history is addressed by immutable event/version, not recurring day folds.
            // Keeping a month/date JSON member array here would rewrite H on each undo.
            if (payload["one_time"].let { it == null || it == JsonNull })
                dated(prefix, activity, payload.string("local_date"))
            if (payload["event_type"] == JsonPrimitive("revert") && payload["one_time"].let { it == null || it == JsonNull })
                add("$prefix.revertTarget", payload.string("reverts_event_uuid"))
        }
        when (row.table.name) {
            "completions" -> {
                add("completion.uuid", row.text("uuid"))
                if (row.nullableText("oneTimeAction") == null)
                    row.nullableText("habitUuid")?.let { dated("completion", it, row.text("recordedLocalDate")) }
            }
            "count_days" -> {
                dated("day", row.text("habitUuid"), row.text("localDate"))
                add("day.firstEvent", row.text("firstEventUuid"))
                row.nullableText("originRequestId")?.let { add("day.origin", row.text("originKind"), it) }
                row.nullableText("planPredecessorId")?.let { add("day.predecessor", it) }
            }
            "sync_entity_state" -> {
                val type = row.text("entityType")
                add("shadow.entity", type, row.text("entityUuid"))
                if (type == "activity_event") {
                    val raw = requireNotNull(row.nullableText("payloadJson")) { "COUNT_FACT_INVALID" }
                    require(row.nullableText("payloadHash") == syncPayloadHash(raw)) { "COUNT_FACT_INVALID" }
                    event("shadow", body(raw))
                }
            }
            "next_request_origins" -> {
                val kind = row.text("kind"); val request = row.text("requestId")
                add("origin.request", kind, request)
                if (kind == NEXT_OPERATION) {
                    val operation = decodeNextOperationIntent(row.text("intentJson"))
                    validateNextSyncOperation(operation)
                    require(operation.operationId == request) { "COUNT_FACT_INVALID" }
                    add("origin.entity", operation.entityType, operation.entityUuid)
                    if (operation.entityType == "activity_event" && operation.action == "upsert") event("origin", operation.payload)
                }
            }
            "sync_outbox" -> {
                val recordType = row.text("recordType")
                add("outbox.record", recordType, row.text("entityUuid"))
                entityType(recordType)?.let { type ->
                    add("outbox.entity", type, row.text("entityUuid"))
                    add("outbox.wire", type, row.text("wireEntityUuid"))
                }
                add("outbox.request", row.text("operationId"))
            }
            "sync_conflicts" -> {
                add("conflict.entity", row.text("entityType"), row.text("localEntityUuid"))
                add("conflict.wire", row.text("entityType"), row.text("wireEntityUuid"))
                add("conflict.record", row.text("recordType"), row.text("localEntityUuid"))
                add("conflict.request", row.text("operationId"))
            }
            "timer_command_outbox" -> { add("timer.session", row.text("sessionUuid")); add("timer.request", row.text("commandId")) }
            "sync_control" -> add("control.id", row.long("id").toString())
            "habits" -> { add("habit.uuid", row.text("uuid")); add("habit.id", row.long("id").toString()) }
            "metrics" -> { add("metric.uuid", row.text("uuid")); add("metric.id", row.long("id").toString()) }
            "metric_logs" -> add("metricLog.uuid", row.text("uuid"))
            "habit_metric_links" -> {
                add("link.uuid", row.text("uuid"))
                add("link.pair", row.long("habitId").toString(), row.long("metricId").toString())
            }
            "timelogs" -> add("timer.uuid", row.text("uuid"))
            "next_challenge_rounds" -> {
                add("round.uuid", row.text("roundUuid"))
                add("round.activity", row.text("activityUuid"))
            }
            "next_challenge_births" -> add("birth.entity", row.text("entityType"), row.text("entityUuid"))
            "next_challenge_state" -> add("challenge.id", row.long("id").toString())
            "next_sync_state" -> add("cursor.id", row.long("id").toString())
        }
        return Collections.unmodifiableMap(result)
    }

    /** Complete one-month bootstrap/rebuild, including retained day rules after all facts undo.
     * unknownDates may additionally contain an unbound-history day with no remaining fact.
     * Current-round filtering, if any, must already be complete before supplying the facts.
     */
    fun summary(habitUuid: String, month: YearMonth, completions: List<CountTrustRows.Row>,
        days: List<CountTrustRows.Row>, unknownDates: Set<LocalDate>? = null): JsonObject {
        val facts = facts(habitUuid, month, completions)
        val policies = policies(habitUuid, month, days)
        val quantities = sortedMapOf<LocalDate, Long>()
        val latest = sortedMapOf<LocalDate, Long>()
        for (fact in facts) {
            quantities[fact.date] = Math.addExact(quantities[fact.date] ?: 0, fact.quantity)
            latest[fact.date] = maxOf(latest[fact.date] ?: Long.MIN_VALUE, fact.id)
        }
        val unknown = unknownDates ?: (quantities.keys - policies.keys)
        return encode(habitUuid, month, quantities, policies, unknown, latest, facts.size)
    }

    /** Increment from the prior COMMITTED arithmetic result. Removed/added rows must be exact
     * authenticated old/new changes. For each removed date the caller supplies ALL effective
     * rows remaining AFTER the delta, including additions, to prove the new latest-ID result.
     * This primitive does not infer that the provided lists or the previous JSON are trusted.
     */
    fun update(previous: JsonObject, removed: List<CountTrustRows.Row>, added: List<CountTrustRows.Row>,
        days: List<CountTrustRows.Row>, remainingOnRemovedDates: List<CountTrustRows.Row>,
        unknownDates: Set<LocalDate>): JsonObject {
        val owner = previous.string("habit_uuid"); val month = month(previous.string("month"))
        val before = values(previous)
        val old = facts(owner, month, removed); val fresh = facts(owner, month, added)
        val remaining = facts(owner, month, remainingOnRemovedDates)
        val touched = old.mapTo(hashSetOf()) { it.date }
        require(remaining.all { it.date in touched }) { "COUNT_CALCULATION_MEMBER_RANGE" }
        val quantities = before.quantities.toMutableMap(); val latest = before.latestIds.toMutableMap()
        for (fact in old) {
            val next = Math.subtractExact(quantities.getValue(fact.date), fact.quantity)
            require(next >= 0) { "COUNT_CALCULATION_NEGATIVE" }
            if (next == 0L) quantities.remove(fact.date) else quantities[fact.date] = next
        }
        for (fact in fresh) quantities[fact.date] = Math.addExact(quantities[fact.date] ?: 0L, fact.quantity)
        for (date in touched) {
            val retained = remaining.filter { it.date == date }
            require(retained.fold(0L) { total, fact -> Math.addExact(total, fact.quantity) } == (quantities[date] ?: 0L)) {
                "COUNT_CALCULATION_REMAINING_MISMATCH"
            }
            if (retained.isEmpty()) latest.remove(date) else latest[date] = retained.maxOf { it.id }
        }
        for (fact in fresh) if (fact.date !in touched) latest[fact.date] = maxOf(latest[fact.date] ?: Long.MIN_VALUE, fact.id)
        val count = Math.addExact(Math.subtractExact(previous.getValue("fact_count").jsonPrimitive.int, old.size), fresh.size)
        require(count >= 0)
        return encode(owner, month, quantities, policies(owner, month, days), unknownDates, latest, count)
    }

    /** Reconstruct only arithmetic; fresh identity/today policy/round/display proof stays outside. */
    fun values(summary: JsonObject): CountSummary.Values {
        require(summary.keys == setOf("habit_uuid", "month", "quantities", "policies", "unknown_dates", "latest_ids", "fact_count"))
        val month = month(summary.string("month")); require(summary.string("habit_uuid").isNotBlank())
        fun longs(key: String) = summary.getValue(key).jsonObject.map { (text, value) ->
            val date = date(text); require(YearMonth.from(date) == month && value is JsonPrimitive && !value.isString)
            date to value.long.also { require(it > 0L) }
        }.toMap()
        val quantities = longs("quantities"); val latest = longs("latest_ids")
        val policies = summary.getValue("policies").jsonObject.map { (text, value) ->
            val date = date(text); require(YearMonth.from(date) == month); date to CountDayPolicy.fromJson(value)
        }.toMap()
        val unknown = summary.getValue("unknown_dates").jsonArray.map { value ->
            require(value is JsonPrimitive && value.isString); date(value.content).also { require(YearMonth.from(it) == month) }
        }
        val count = summary.getValue("fact_count").jsonPrimitive
        require(!count.isString && count.int >= 0 && count.int >= latest.size &&
            (count.int == 0) == quantities.isEmpty() && unknown.distinct().size == unknown.size &&
            unknown.none(policies::containsKey) && quantities.keys == latest.keys)
        return CountSummary.Values.create(policies, quantities, unknown.toSet(), latest).also { it.totalQuantity }
    }

    private data class Fact(val uuid: String, val id: Long, val date: LocalDate, val quantity: Long)
    private fun facts(owner: String, month: YearMonth, rows: List<CountTrustRows.Row>): List<Fact> {
        val ids = hashSetOf<Long>(); val uuids = hashSetOf<String>()
        return rows.map { row ->
            val fact = row.completion(); val date = date(fact.recordedLocalDate)
            require(fact.habitUuid == owner && YearMonth.from(date) == month && fact.id > 0 && fact.value > 0 &&
                fact.oneTimeAction == null && fact.oneTimeExpectedVersion == null &&
                fact.oneTimeExpectedHeadEventUuid == null && fact.oneTimeRevertsEventUuid == null &&
                ids.add(fact.id) && uuids.add(fact.uuid)) { "COUNT_CALCULATION_MEMBER_RANGE" }
            Fact(fact.uuid, fact.id, date, fact.value.toLong())
        }
    }
    private fun policies(owner: String, month: YearMonth, rows: List<CountTrustRows.Row>): Map<LocalDate, CountDayPolicy> = buildMap {
        for (row in rows) {
            val day = row.day(); val date = date(day.localDate)
            require(day.habitUuid == owner && YearMonth.from(date) == month && put(date, day.policy) == null) {
                "COUNT_CALCULATION_MEMBER_RANGE"
            }
        }
    }
    private fun encode(owner: String, month: YearMonth, quantities: Map<LocalDate, Long>, policies: Map<LocalDate, CountDayPolicy>,
        unknown: Set<LocalDate>, latest: Map<LocalDate, Long>, count: Int) = buildJsonObject {
        put("habit_uuid", owner); put("month", month.toString()); put("fact_count", count)
        put("quantities", buildJsonObject { quantities.toSortedMap().forEach { (date, value) -> put(date.toString(), value) } })
        put("policies", buildJsonObject { policies.toSortedMap().forEach { (date, policy) -> put(date.toString(), policy.toJson()) } })
        put("unknown_dates", JsonArray(unknown.sorted().map { JsonPrimitive(it.toString()) }))
        put("latest_ids", buildJsonObject { latest.toSortedMap().forEach { (date, value) -> put(date.toString(), value) } })
    }.also { values(it) }
    private fun JsonObject.string(key: String): String = getValue(key).let { require(it is JsonPrimitive && it.isString); it.content }
    private fun date(text: String) = LocalDate.parse(text).also { require(it.toString() == text) }
    private fun month(text: String) = YearMonth.parse(text).also { require(it.toString() == text) }
    private fun entityType(record: String): String? = when (record) {
        "habit" -> "plan_node"; "metric" -> "metric"; "completion" -> "activity_event"
        "metric_log" -> "metric_observation"; "link" -> "activity_metric_link"
        else -> null
    }
}
