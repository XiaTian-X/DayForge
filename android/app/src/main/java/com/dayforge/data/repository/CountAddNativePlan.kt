package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.ChallengeSourceContext
import com.dayforge.data.api.dto.RoundSyncPushRequest
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CountDayEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.ChallengeRoundHead
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.isContractUuid
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import kotlinx.serialization.json.*

/** Pure successor for one already-active formal COUNT +1, including an unstarted day.
 * It grants no authority and contains no SQL, caller callback or live proof. The private
 * executor must authenticate complete inputs/absence and revalidate its read set at T1.
 * Allocations below are the original SQLite/trigger/clock outputs, not journal row copies.
 */
internal class CountAddNativePlan private constructor(
    private val access: LocalCoreWriteAccess,
    private val habit: HabitEntity,
    private val nativeHabit: CountTrustRows.Row,
    private val occurredAt: Instant,
    private val zone: ZoneId,
    private val head: ChallengeRoundHead,
    private val dayBefore: CountTrustRows.Row?,
    private val days: List<CountTrustRows.Row>,
    private val previous: Map<ChallengeRoundHead, JsonObject?>,
    private val months: Map<ChallengeRoundHead, List<String>>,
    private val layouts: Map<String, CountTrustRows.Table>,
    private val watermark: Long
) {
    private val date = occurredAt.atZone(zone).toLocalDate()
    private val month = YearMonth.from(date)
    private val policy = dayBefore?.day()?.policy ?: CountDayPolicy(habit.targetValue, habit.isCountdown)
    private val payload = JsonObject(SyncV2Mapper.completionFields(habit.uuid, HabitType.COUNTING, 1,
        occurredAt.toEpochMilli(), date.toString(), zone.id) + ("count_policy" to policy.toJson())).also {
        CountFactEvidenceRules.verifyPendingFields(habit.uuid, 1, occurredAt.toEpochMilli(),
            date.atStartOfDay(zone).toInstant().toEpochMilli(), date.toString(), zone.id, policy, it)
    }
    val noEffect: Boolean = policy.isCountdown &&
        (previous.getValue(head)?.let(CountTrustMembership::values)?.quantities?.get(date) ?: 0L) >= policy.targetValue
    private class MonthSuccessor(val quantities: Map<LocalDate, Long>, val policies: Map<LocalDate, CountDayPolicy>,
        val unknown: Set<LocalDate>, val latest: Map<LocalDate, Long>, val count: Int, val added: Boolean)
    // The full old-block decode, checked arithmetic and policy propagation happen in pure B.
    // Only the real allocated latest ID and small canonical encoding remain at T1.
    private val successors: Map<ChallengeRoundHead, MonthSuccessor> = if (noEffect) emptyMap() else previous.mapValues { (partition, json) ->
        val values = json?.let(CountTrustMembership::values)
        val quantities = values?.quantities.orEmpty().toMutableMap()
        val policies = days.associate { LocalDate.parse(it.day().localDate) to it.day().policy }.toMutableMap()
        if (dayBefore == null) policies[date] = policy
        val added = partition == head
        if (added) quantities[date] = Math.addExact(quantities[date] ?: 0L, 1L)
        val count = Math.addExact(json?.getValue("fact_count")?.jsonPrimitive?.int ?: 0, if (added) 1 else 0)
        MonthSuccessor(Collections.unmodifiableMap(quantities), Collections.unmodifiableMap(policies),
            Collections.unmodifiableSet(values?.unknownDates.orEmpty() - date),
            Collections.unmodifiableMap(values?.latestIds.orEmpty().toMap()), count, added)
    }

    /** Only immutable input is used here; native createdAt/UUID retain the original T1 point.
     * Calling this does not persist or reserve the resulting identity.
     */
    fun beginAtOriginalConstructionPoint(uuid: String, createdAt: Long): Fact {
        check(!noEffect) { "COUNT_ADD_NO_EFFECT" }
        require(isContractUuid(uuid))
        val completion = CompletionEntity(habitId = habit.id,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), value = 1,
            actualCompletedAt = occurredAt.toEpochMilli(), uuid = uuid, habitUuid = habit.uuid,
            createdAt = createdAt, recordedTimezone = zone.id, recordedLocalDate = date.toString())
        return Fact(this, completion, if (dayBefore == null)
            CountDayEntity(habit.id, habit.uuid, date.toString(), policy.targetValue, policy.isCountdown, uuid) else null)
    }

    class Fact internal constructor(private val owner: CountAddNativePlan,
        val completion: CompletionEntity, val initialDay: CountDayEntity?) {
        /** Rate is produced by the private original post-insert calculation, at its original
         * now/ZoneId point. No generic callback may choose these scalar outputs.
         */
        suspend fun bind(completionId: Long, queueId: Long, operationId: String, queueCreatedAt: Long,
            rate: CountTrustKernel.RateCalculation): Bound = owner.bind(this, completionId, queueId,
                operationId, queueCreatedAt, rate)
        internal fun belongsTo(plan: CountAddNativePlan) = owner === plan
    }

    class Bound internal constructor(
        val completion: CompletionEntity,
        val source: SyncOutboxEntity,
        val origin: NextRequestOriginEntity,
        val finalDay: CountDayEntity?,
        val rate: CountTrustKernel.RateCalculation,
        effects: List<CountTrustAck.Effect>,
        calculations: Map<String, JsonElement?>,
        displayChanges: List<CountDisplayBlocks.Change>,
        private val pairOffset: Int
    ) {
        val effects: List<CountTrustAck.Effect> = Collections.unmodifiableList(effects.toList())
        val calculations: Map<String, JsonElement?> = Collections.unmodifiableMap(LinkedHashMap(calculations))
        val displayChanges: List<CountDisplayBlocks.Change> = Collections.unmodifiableList(displayChanges.toList())

        fun journalExpectation(): List<CountTrustJournal.CountAddEffect> = effects.map { effect ->
            val columns = CountTrustJournal.COLUMNS.getValue(effect.table)
            CountTrustJournal.CountAddEffect(effect.table, effect.before?.let { columns.map(it::cell) },
                columns.map(requireNotNull(effect.after)::cell))
        }

        /** Compare actual ordered native effects to a wholly independently built plan.
         * TEMP journal and permanent AFTER-trigger ordering permit exactly the fact/queue
         * pair permutations. No other reorder, no-op, extra mutation or missing effect passes.
         */
        fun verifyActual(changes: List<CountTrustJournal.Change>) {
            check(changes.size == effects.size) { "COUNT_ADD_EXTRA_EFFECT" }
            fun matches(change: CountTrustJournal.Change, effect: CountTrustAck.Effect): Boolean {
                if (change.table != effect.table) return false
                if (change.operation != if (effect.before == null) "INSERT" else "UPDATE") return false
                val fields = CountTrustJournal.COLUMNS.getValue(change.table)
                fun cells(row: CountTrustRows.Row?) = row?.let { fields.map(it::cell) }
                return change.old == cells(effect.before) && change.new == cells(effect.after)
            }
            val ordinary = changes.indices.all { matches(changes[it], effects[it]) }
            val swapped = changes.indices.all { index ->
                val expected = when (index) { pairOffset -> pairOffset + 1; pairOffset + 1 -> pairOffset; else -> index }
                matches(changes[index], effects[expected])
            }
            check(ordinary || swapped) { "COUNT_ADD_UNEXPECTED_EFFECT" }
        }

        /** Intermediate first-day/control values do not survive; final rows are still checked
         * individually after ALL producers/triggers, before any root can be signed.
         */
        fun verifyFinalContents(sql: SupportSQLiteDatabase) {
            val final = linkedMapOf<String, CountTrustRows.Row>()
            for (effect in effects) {
                val row = requireNotNull(effect.after)
                final[row.name] = row
            }
            for (row in final.values) {
                val args = arrayListOf<Any?>()
                val expectedLength = row.cells.joinToString("+") { cell ->
                    args += cell.value
                    "COALESCE(length(CAST(? AS BLOB)),0)"
                }
                val actualLength = row.table.columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
                val equal = row.table.columns.map { name ->
                    val cell = row.cell(name)
                    when (cell.type) {
                        Cursor.FIELD_TYPE_NULL -> "`$name` IS NULL"
                        Cursor.FIELD_TYPE_INTEGER -> { args += cell.value as Long; "typeof(`$name`)='integer' AND `$name` IS ?" }
                        Cursor.FIELD_TYPE_STRING -> { args += cell.value as String; "typeof(`$name`)='text' AND CAST(`$name` AS BLOB)=CAST(? AS BLOB)" }
                        else -> error("COUNT_ADD_EXPECTED_CELL_TYPE")
                    }
                }.joinToString(" AND ")
                val where = row.table.primary.joinToString(" AND ") { name -> args += row.cell(name).value; "`$name` IS ?" }
                sql.query("SELECT COUNT(*),COALESCE(MIN(CASE WHEN $actualLength=$expectedLength THEN " +
                    "CASE WHEN $equal THEN 1 ELSE 0 END ELSE 0 END),0) FROM main.`${row.table.name}` WHERE $where",
                    args.toTypedArray()).use { c ->
                    check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                        c.getLong(0) == 1L && c.getLong(1) == 1L && !c.moveToNext()) { "COUNT_ADD_FINAL_CONTENT_CHANGED" }
                }
            }
        }

        /** Dynamic request/SQLite identities affect only a bounded set of membership paths.
         * Values must be loaded from the freshly authenticated CURRENT root at T1; no patch
         * calculated against an old root may overwrite an intervening unrelated ACK.
         */
        suspend fun requiredKeys(): Set<String> = buildSet {
            for (effect in effects) for (row in listOfNotNull(effect.before, effect.after)) {
                add(row.name); addAll(CountTrustMembership.memberships(row).keys)
            }
        }

        suspend fun edits(currentValues: Map<String, JsonElement?>): Map<String, JsonElement?> {
            check(currentValues.keys == requiredKeys()) { "COUNT_ADD_INCOMPLETE_PATCH_INPUT" }
            val changed = linkedMapOf<String, Pair<CountTrustRows.Row?, CountTrustRows.Row?>>()
            for (effect in effects) {
                val row = effect.after ?: requireNotNull(effect.before)
                val expectedOld = changed[row.name]?.second?.value ?: if (row.name in changed) null else currentValues[row.name]
                check(expectedOld == effect.before?.value) { "COUNT_ADD_OLD_CONTENT_CHANGED" }
                changed[row.name] = (if (row.name in changed) changed.getValue(row.name).first else effect.before) to effect.after
            }
            val members = linkedMapOf<String, MutableSet<String>>()
            suspend fun update(row: CountTrustRows.Row, add: Boolean) {
                for ((key, identity) in CountTrustMembership.memberships(row)) {
                    val set = members.getOrPut(key) {
                        currentValues[key]?.jsonArray?.map { it.jsonPrimitive.content }?.let { values ->
                            check(values.size == values.toSet().size) { "COUNT_ADD_DUPLICATE_MEMBER" }
                            values.toSortedSet()
                        } ?: sortedSetOf()
                    }
                    check(if (add) set.add(identity) else set.remove(identity)) { "COUNT_ADD_MEMBER_CHANGED" }
                }
            }
            val result = linkedMapOf<String, JsonElement?>()
            for ((name, pair) in changed) {
                pair.first?.let { update(it, false) }; pair.second?.let { update(it, true) }
                result[name] = pair.second?.value
            }
            members.forEach { (key, values) -> result[key] = JsonArray(values.map(::JsonPrimitive)) }
            check(result.keys.intersect(calculations.keys).isEmpty()) { "COUNT_ADD_PATCH_KEY_COLLISION" }
            result.putAll(calculations)
            return Collections.unmodifiableMap(result)
        }
    }

    private suspend fun bind(stage: Fact, completionId: Long, queueId: Long, operationId: String,
        queueCreatedAt: Long, rate: CountTrustKernel.RateCalculation): Bound {
        check(stage.belongsTo(this))
        check(stage.completion == CompletionEntity(habitId = habit.id,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), value = 1,
            actualCompletedAt = occurredAt.toEpochMilli(), uuid = stage.completion.uuid,
            habitUuid = habit.uuid, createdAt = stage.completion.createdAt,
            recordedTimezone = zone.id, recordedLocalDate = date.toString())) { "COUNT_ADD_FACT_INPUT_CHANGED" }
        check(stage.initialDay == if (dayBefore == null) CountDayEntity(habit.id, habit.uuid,
            date.toString(), policy.targetValue, policy.isCountdown, stage.completion.uuid) else null) {
            "COUNT_ADD_DAY_INPUT_CHANGED"
        }
        require(isContractUuid(stage.completion.uuid) && !noEffect)
        require(completionId > 0 && queueId > watermark && isContractUuid(operationId) && queueCreatedAt % 1000L == 0L)
        val fact = stage.completion.copy(id = completionId)
        val source = SyncOutboxEntity(id = queueId, operationId = operationId, recordType = "completion",
            entityUuid = fact.uuid, wireEntityUuid = fact.uuid, action = "upsert", referenceUuid = habit.uuid,
            payloadJson = null, baseRevision = null, basePayloadJson = null, attemptedAt = null,
            attemptCount = 0, lastError = null, errorCode = null, deadLetteredAt = null, createdAt = queueCreatedAt)
        val operation = SyncV2Operation(operationId, "activity_event", fact.uuid, "upsert", null, payload)
        validateNextSyncOperation(operation)
        val device = requireNotNull(access.capturedDeviceId)
        val round = NextRoundOperationIntent(1, operation, ChallengeSourceContext(operationId, head), device)
        val bytes = encodeSyncRequest(NextRoundOperationIntent.serializer(), round)
        check(decodeNextOperationIntent(bytes.toString(Charsets.UTF_8)) == operation)
        val envelope = encodeSyncRequest(RoundSyncPushRequest.serializer(),
            RoundSyncPushRequest(1, device, listOf(operation), listOf(round.context)))
        decodeFrozenSyncRequest(envelope, RoundSyncPushRequest.serializer())
        val origin = NextRequestOriginEntity(NEXT_OPERATION, operationId, queueId, 5,
            access.session.authentication.userId, access.session.serverInstanceId, access.session.syncEpoch,
            NextRequestSql.sourceHash(source), bytes.toString(Charsets.UTF_8))
        val finalDay = stage.initialDay?.copy(originRequestId = operationId,
            originHash = CountAddOriginHash.hash(origin), planQueueWatermark = queueId - 1,
            capturedDeviceId = device)
        val expected = arrayListOf<CountTrustAck.Effect>()
        stage.initialDay?.let { expected += CountTrustAck.Effect("count_days", null, dayRow(it)) }
        val pairOffset = expected.size
        val factRow = completionRow(fact)
        expected += CountTrustAck.Effect("completions", null, factRow)
        expected += CountTrustAck.Effect("sync_outbox", null, sourceRow(source))
        require(rate.rate in 0..100)
        val habitAfter = CountTrustRows.Row(nativeHabit.table, nativeHabit.table.columns.map { field -> when (field) {
            "activityRate" -> integer(rate.rate.toLong())
            "activityRateUpdatedAt" -> integer(rate.instant)
            else -> nativeHabit.cell(field)
        } })
        expected += CountTrustAck.Effect("habits", nativeHabit, habitAfter)
        expected += CountTrustAck.Effect("next_request_origins", null, originRow(origin))
        if (finalDay != null) expected += CountTrustAck.Effect("count_days", dayRow(requireNotNull(stage.initialDay)), dayRow(finalDay))
        val calculations = linkedMapOf<String, JsonElement?>()
        for ((partition, successor) in successors) {
            val latest = successor.latest.toMutableMap()
            if (successor.added) latest[date] = maxOf(latest[date] ?: Long.MIN_VALUE, fact.id)
            calculations[CountTrustRoundEvidence.summaryKey(habit.uuid, month, partition)] = buildJsonObject {
                put("habit_uuid", habit.uuid); put("month", month.toString()); put("fact_count", successor.count)
                put("quantities", buildJsonObject { successor.quantities.toSortedMap().forEach { (key, value) -> put(key.toString(), value) } })
                put("policies", buildJsonObject { successor.policies.toSortedMap().forEach { (key, value) -> put(key.toString(), value.toJson()) } })
                put("unknown_dates", JsonArray(successor.unknown.sorted().map { JsonPrimitive(it.toString()) }))
                put("latest_ids", buildJsonObject { latest.toSortedMap().forEach { (key, value) -> put(key.toString(), value) } })
            }.also { CountTrustMembership.values(it) }
            calculations[CountTrustRoundEvidence.monthsKey(habit.uuid, partition)] =
                JsonArray((months.getValue(partition) + month.toString()).toSortedSet().map(::JsonPrimitive))
        }
        calculations[CountTrustRoundEvidence.factKey(fact.uuid)] = CountTrustRoundEvidence.encode(head)
        val displayChanges = successors.keys.map { partition -> CountDisplayBlocks.Change(habit, partition,
            month, requireNotNull(calculations[CountTrustRoundEvidence.summaryKey(habit.uuid, month, partition)]).jsonObject) }
        return Bound(fact, source, origin, finalDay, rate, expected, calculations, displayChanges, pairOffset)
    }

    private fun row(table: String, values: Map<String, Any?>): CountTrustRows.Row {
        val layout = layouts.getValue(table)
        check(layout.columns.toSet() == values.keys) { "COUNT_ADD_LAYOUT_CHANGED" }
        return CountTrustRows.Row(layout, layout.columns.map { field -> when (val value = values.getValue(field)) {
            null -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_NULL, null)
            is String -> CountTrustJournal.Cell(Cursor.FIELD_TYPE_STRING, value)
            is Long -> integer(value)
            is Int -> integer(value.toLong())
            is Boolean -> integer(if (value) 1L else 0L)
            else -> error("COUNT_ADD_UNEXPECTED_NATIVE_TYPE")
        } })
    }

    private fun completionRow(v: CompletionEntity) = row("completions", mapOf("id" to v.id, "habitId" to v.habitId,
        "date" to v.date, "value" to v.value, "actualCompletedAt" to v.actualCompletedAt, "uuid" to v.uuid,
        "habitUuid" to v.habitUuid, "createdAt" to v.createdAt, "recordedTimezone" to v.recordedTimezone,
        "recordedLocalDate" to v.recordedLocalDate, "timeMetadataSource" to v.timeMetadataSource,
        "oneTimeAction" to v.oneTimeAction, "oneTimeExpectedVersion" to v.oneTimeExpectedVersion,
        "oneTimeExpectedHeadEventUuid" to v.oneTimeExpectedHeadEventUuid, "oneTimeRevertsEventUuid" to v.oneTimeRevertsEventUuid))
    private fun dayRow(v: CountDayEntity) = row("count_days", mapOf("habitId" to v.habitId, "habitUuid" to v.habitUuid,
        "localDate" to v.localDate, "targetValue" to v.targetValue, "isCountdown" to v.isCountdown,
        "firstEventUuid" to v.firstEventUuid, "originRequestId" to v.originRequestId, "originHash" to v.originHash,
        "planPredecessorId" to v.planPredecessorId, "planPredecessorOriginHash" to v.planPredecessorOriginHash,
        "planPredecessorDependencyHash" to v.planPredecessorDependencyHash, "planQueueWatermark" to v.planQueueWatermark,
        "capturedDeviceId" to v.capturedDeviceId, "originKind" to v.originKind))
    private fun sourceRow(v: SyncOutboxEntity) = row("sync_outbox", mapOf("id" to v.id, "operationId" to v.operationId,
        "recordType" to v.recordType, "entityUuid" to v.entityUuid, "wireEntityUuid" to v.wireEntityUuid,
        "action" to v.action, "referenceUuid" to v.referenceUuid, "payloadJson" to v.payloadJson,
        "baseRevision" to v.baseRevision, "basePayloadJson" to v.basePayloadJson, "attemptedAt" to v.attemptedAt,
        "attemptCount" to v.attemptCount, "lastError" to v.lastError, "errorCode" to v.errorCode,
        "deadLetteredAt" to v.deadLetteredAt, "createdAt" to v.createdAt))
    private fun originRow(v: NextRequestOriginEntity) = row("next_request_origins", mapOf("kind" to v.kind,
        "requestId" to v.requestId, "queueId" to v.queueId, "protocol" to v.protocol, "accountId" to v.accountId,
        "serverInstanceId" to v.serverInstanceId, "syncEpoch" to v.syncEpoch, "sourceHash" to v.sourceHash,
        "intentJson" to v.intentJson))

    companion object {
        private fun integer(value: Long) = CountTrustJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)

        private fun freeze(row: CountTrustRows.Row): CountTrustRows.Row = CountTrustRows.Row(row.table.copy(
            columns = Collections.unmodifiableList(row.table.columns.toList()),
            primary = Collections.unmodifiableList(row.table.primary.toList())), row.cells)

        private fun decodeHabit(row: CountTrustRows.Row): HabitEntity = with(row) {
            val codec = com.dayforge.data.local.entity.HabitTypeConverter()
            fun nullableInt(name: String) = nullableLong(name)?.also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }?.toInt()
            HabitEntity(id = long("id"), name = text("name"), description = text("description"),
                habitType = codec.toHabitType(text("habitType")), iconResId = int("iconResId"), colorHex = text("colorHex"),
                schedule = codec.toSchedule(text("schedule")), targetValue = int("targetValue"), isCountdown = bool("isCountdown"),
                isActive = bool("isActive"), uuid = text("uuid"), parentHabitId = nullableText("parentHabitId"),
                targetCycles = nullableInt("targetCycles"), failMode = codec.toFailMode(text("failMode")),
                goalSuccess = if (cell("goalSuccess").type == Cursor.FIELD_TYPE_NULL) null else bool("goalSuccess"),
                activityRate = int("activityRate"), activityRateUpdatedAt = long("activityRateUpdatedAt"),
                bestTime = nullableLong("bestTime"), createdAt = long("createdAt"), updatedAt = long("updatedAt"),
                completionPolicy = nullableText("completionPolicy"), oneTimeConfirmedVersion = nullableInt("oneTimeConfirmedVersion"),
                oneTimeConfirmedHeadEventUuid = nullableText("oneTimeConfirmedHeadEventUuid"),
                oneTimeConfirmedCompletionEventUuid = nullableText("oneTimeConfirmedCompletionEventUuid"),
                appearance = codec.toAppearance(nullableText("appearance")), planMetadata = codec.toPlanMetadata(nullableText("planMetadata")))
        }

        /** The argument lists are complete inputs supplied by the separate authenticated
         * freeze owner. This pure function never treats supplied values as certificates.
         */
        fun prepare(access: LocalCoreWriteAccess, habit: HabitEntity, nativeHabit: CountTrustRows.Row,
            occurredAt: Instant, zone: ZoneId, head: ChallengeRoundHead, day: CountTrustRows.Row?,
            monthDays: List<CountTrustRows.Row>, summaries: Map<ChallengeRoundHead, JsonObject?>,
            knownHeads: List<ChallengeRoundHead>,
            months: Map<ChallengeRoundHead, List<String>>,
            layouts: Map<String, CountTrustRows.Table>, watermark: Long): CountAddNativePlan? {
            if (!habit.isActive || habit.habitType != HabitType.COUNTING || habit.appearance == null ||
                habit.completionPolicy != "recurring" || access.capturedDeviceId == null) return null
            require(watermark >= 0 && head.activityUuid == habit.uuid && summaries.containsKey(head))
            require(knownHeads.isNotEmpty() && knownHeads.distinct().size == knownHeads.size &&
                head in knownHeads && knownHeads.all { it.activityUuid == habit.uuid })
            check(summaries.keys == if (day == null) knownHeads.toSet() else setOf(head)) {
                "COUNT_ADD_INCOMPLETE_POLICY_PARTITIONS"
            }
            check(nativeHabit.table.name == "habits" && nativeHabit.long("id") == habit.id && nativeHabit.text("uuid") == habit.uuid)
            val frozenHabit = freeze(nativeHabit)
            val decodedHabit = decodeHabit(frozenHabit)
            check(decodedHabit == habit) { "COUNT_ADD_HABIT_INPUT_CHANGED" }
            val date = occurredAt.atZone(zone).toLocalDate(); val month = YearMonth.from(date)
            check(months.keys == summaries.keys)
            for ((partition, entries) in months) {
                require(entries.distinct().size == entries.size)
                entries.forEach { require(YearMonth.parse(it).toString() == it) }
                check((month.toString() in entries) == (summaries.getValue(partition) != null)) {
                    "COUNT_ADD_CALCULATION_DIRECTORY_CHANGED"
                }
            }
            day?.day()?.let { require(it.habitId == habit.id && it.habitUuid == habit.uuid && it.localDate == date.toString()) }
            require(monthDays.map { it.day().localDate }.distinct().size == monthDays.size)
            require(monthDays.all { it.day().let { d -> d.habitId == habit.id && d.habitUuid == habit.uuid &&
                YearMonth.from(LocalDate.parse(d.localDate)) == month } })
            check(monthDays.singleOrNull { it.day().localDate == date.toString() }?.value == day?.value)
            val policyRows = monthDays.associate { LocalDate.parse(it.day().localDate) to it.day().policy }
            for ((partition, previous) in summaries) {
                require(partition.activityUuid == habit.uuid)
                previous?.let {
                    require(it.getValue("habit_uuid") == JsonPrimitive(habit.uuid) && it.getValue("month") == JsonPrimitive(month.toString()))
                    check(CountTrustMembership.values(it).policies == policyRows) { "COUNT_ADD_POLICY_BLOCK_CHANGED" }
                }
            }
            val immutableSummaries = summaries.mapValues { (_, value) -> value?.let {
                Json.parseToJsonElement(it.toString()).jsonObject
            } }
            return CountAddNativePlan(access.copy(capabilities = access.capabilities?.let { Collections.unmodifiableSet(it.toSet()) }),
                decodedHabit, frozenHabit, occurredAt, zone, head, day?.let(::freeze),
                Collections.unmodifiableList(monthDays.map(::freeze)), Collections.unmodifiableMap(immutableSummaries),
                Collections.unmodifiableMap(months.mapValues { Collections.unmodifiableList(it.value.toList()) }),
                Collections.unmodifiableMap(layouts.mapValues { (_, t) -> t.copy(columns = Collections.unmodifiableList(t.columns.toList()),
                    primary = Collections.unmodifiableList(t.primary.toList())) }), watermark)
        }
    }
}
