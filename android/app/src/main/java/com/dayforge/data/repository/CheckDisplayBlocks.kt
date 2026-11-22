package com.dayforge.data.repository

import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.domain.model.ChallengeRoundHead
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import kotlinx.serialization.json.*

/** Existing directory leaves only. Complete original proofs must precede issuance/advance.
 * read() returns data under the caller's authenticated CURRENT index, never a permission.
 */
internal object CheckDisplayBlocks {
    const val COVERAGE_KEY = "formal:check-display-coverage"
    const val COVERAGE = "check-presence-calendar/v1"
    private fun headJson(head: ChallengeRoundHead?): JsonElement = head?.let(CountTrustRoundEvidence::encode) ?: JsonNull
    data class Scope(val habitUuid: String, val head: ChallengeRoundHead?) {
        init { require(head == null || head.activityUuid == habitUuid) }
        fun globalKey() = CountTrustMembership.key("formal.check-display.global", habitUuid, headJson(head).toString())
        fun yearKey(year: Int) = CountTrustMembership.key("formal.check-display.year", habitUuid,
            headJson(head).toString(), year.toString())
    }
    class Change(val habit: HabitEntity, val head: ChallengeRoundHead?, val month: YearMonth, val body: JsonObject)
    class YearEntry(val hash: String, val summary: CountDisplayRollup.YearSummary)
    class Global(val scope: Scope, val zone: ZoneId, entries: List<YearEntry>, val fold: CountDisplayRollup.Complete) {
        val entries: List<YearEntry> = Collections.unmodifiableList(entries.toList())
    }
    private fun hash(value: JsonElement) = CountTrustIndex.hash(value.toString().toByteArray(Charsets.UTF_8))
    private fun basis(habit: HabitEntity) = buildJsonObject {
        require(habit.habitType == com.dayforge.data.model.HabitType.CHECK_IN) { "CHECK_ACTIVITY_CHANGED" }
        put("schedule", Json.encodeToJsonElement(HabitSchedule.serializer(), habit.schedule))
        put("created_at", habit.createdAt); put("habit_type", habit.habitType.name)
    }
    private fun header(scope: Scope, kind: String) = buildJsonObject {
        put("version", 1); put("kind", kind); put("habit_uuid", scope.habitUuid)
        put("head", headJson(scope.head))
    }
    private fun checkHeader(value: JsonObject, scope: Scope, kind: String) {
        require(value["version"] == JsonPrimitive(1) && value["kind"] == JsonPrimitive(kind) &&
            value["habit_uuid"] == JsonPrimitive(scope.habitUuid) && value["head"] == headJson(scope.head)) {
            "CHECK_DISPLAY_SCOPE_CHANGED"
        }
    }
    private fun number(value: JsonElement): Int {
        val p = value.jsonPrimitive
        require(!p.isString)
        return p.int
    }
    private fun date(value: JsonElement): LocalDate? = if (value == JsonNull) null
        else value.jsonPrimitive.also { require(it.isString) }.content.let { text ->
            LocalDate.parse(text).also { require(it.toString() == text) }
        }
    private fun JsonObject.date(name: String) = date(getValue(name))
    private fun dateJson(value: LocalDate?) = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    private fun summary(value: CountDisplayRollup.YearSummary) = buildJsonObject {
        put("year", value.year); put("count", value.count); put("prefix", value.prefix); put("suffix", value.suffix)
        put("best", value.best); put("first_quantity", dateJson(value.firstQuantity))
        put("first_miss", dateJson(value.firstMiss)); put("started_miss", dateJson(value.startedMiss))
    }
    private fun summary(value: JsonObject): CountDisplayRollup.YearSummary {
        require(value.keys == setOf("year", "count", "prefix", "suffix", "best", "first_quantity", "first_miss", "started_miss"))
        return CountDisplayRollup.YearSummary(number(value.getValue("year")), number(value.getValue("count")),
            number(value.getValue("prefix")), number(value.getValue("suffix")), number(value.getValue("best")),
            value.date("first_quantity"), value.date("first_miss"), value.date("started_miss")).checked()
    }
    private fun year(scope: Scope, value: CountDisplayRollup.YearBlock): JsonObject = JsonObject(header(scope, "year") + buildJsonObject {
        put("year", value.year)
        put("months", JsonArray(value.months.values.map { m -> JsonArray(listOf(m.month.monthValue,
            m.qualified, m.quantity, m.participation, m.unknown).map(::JsonPrimitive)) }))
    })
    private fun year(value: JsonObject, scope: Scope, expectedYear: Int,
        summary: CountDisplayRollup.YearSummary): CountDisplayRollup.YearBlock {
        require(value.keys == setOf("version", "kind", "habit_uuid", "head", "year", "months"))
        checkHeader(value, scope, "year")
        require(number(value.getValue("year")) == expectedYear)
        val rows = value.getValue("months").jsonArray
        require(rows.size in 1..12)
        val months = rows.map { row ->
            val cells = row.jsonArray.also { require(it.size == 5) }.map(::number)
            CountDisplayRollup.Month.decode(YearMonth.of(expectedYear, cells[0]), cells[1], cells[2], cells[3], cells[4])
        }
        require(months.map { it.month } == months.map { it.month }.distinct().sorted())
        return CountDisplayRollup.YearBlock.authenticated(expectedYear, months, summary)
    }
    private fun global(scope: Scope, habit: HabitEntity, zone: ZoneId, entries: List<YearEntry>): JsonObject {
        val fold = CountDisplayRollup.Complete.create(entries.map { it.summary }, habit, zone)
        return JsonObject(header(scope, "global") + buildJsonObject {
            put("basis", basis(habit)); put("zone", zone.id)
            put("years", JsonArray(entries.map { e -> buildJsonObject {
                put("hash", e.hash); put("summary", summary(e.summary))
            } }))
            put("qualified_count", fold.qualifiedCount); put("best_streak", fold.bestStreak)
            put("first_quantity", dateJson(fold.firstQuantity)); put("first_violation", dateJson(fold.firstViolation))
        })
    }
    fun global(value: JsonObject, scope: Scope, habit: HabitEntity): Global {
        require(value.keys == setOf("version", "kind", "habit_uuid", "head", "basis", "zone", "years",
            "qualified_count", "best_streak", "first_quantity", "first_violation"))
        checkHeader(value, scope, "global")
        // A live plan/calendar mismatch under the same clean root is not a cache miss.
        require(value["basis"] == basis(habit)) { "CHECK_DISPLAY_PLAN_CHANGED" }
        val zone = ZoneId.of(value.getValue("zone").jsonPrimitive.also { require(it.isString) }.content)
        val entries = value.getValue("years").jsonArray.map { item ->
            val body = item.jsonObject
            require(body.keys == setOf("hash", "summary"))
            val hash = body.getValue("hash").jsonPrimitive.also { require(it.isString && CountTrustIndex.validHash(it.content)) }.content
            YearEntry(hash, summary(body.getValue("summary").jsonObject))
        }
        require(entries.map { it.summary.year } == entries.map { it.summary.year }.distinct().sorted())
        val fold = CountDisplayRollup.Complete.create(entries.map { it.summary }, habit, zone)
        require(number(value.getValue("qualified_count")) == fold.qualifiedCount &&
            number(value.getValue("best_streak")) == fold.bestStreak && value.date("first_quantity") == fold.firstQuantity &&
            value.date("first_violation") == fold.firstViolation) { "CHECK_DISPLAY_FOLD_CHANGED" }
        return Global(scope, zone, entries, fold)
    }
    fun year(global: Global, expectedYear: Int, habit: HabitEntity, read: (String) -> JsonElement?): CountDisplayRollup.YearBlock? {
        val entry = global.entries.singleOrNull { it.summary.year == expectedYear } ?: return null
        val value = requireNotNull(read(global.scope.yearKey(expectedYear))) { "CHECK_DISPLAY_YEAR_MISSING" }.jsonObject
        require(hash(value) == entry.hash) { "CHECK_DISPLAY_YEAR_CHANGED" }
        require(global.scope.habitUuid == habit.uuid)
        return year(value, global.scope, expectedYear, entry.summary)
    }
    fun issue(habit: HabitEntity, head: ChallengeRoundHead?, months: Map<YearMonth, JsonObject>, zone: ZoneId): Map<String, JsonElement> {
        val scope = Scope(habit.uuid, head)
        val years = months.map { (month, body) -> monthBlock(habit.uuid, month, body) }.groupBy { it.month.year }
            .toSortedMap().mapValues { (year, blocks) -> CountDisplayRollup.YearBlock.create(year, blocks, habit, zone) }
        val result = linkedMapOf<String, JsonElement>()
        val entries = years.map { (number, block) ->
            val value = year(scope, block); result[scope.yearKey(number)] = value
            YearEntry(hash(value), block.summary)
        }
        result[scope.globalKey()] = global(scope, habit, zone, entries)
        return result
    }
    private fun monthBlock(uuid: String, month: YearMonth, body: JsonObject): CountDisplayRollup.Month {
        require(body["habit_uuid"] == JsonPrimitive(uuid) && body["month"] == JsonPrimitive(month.toString())) {
            "CHECK_DISPLAY_MONTH_CHANGED"
        }
        return month(month, CountTrustMembership.values(body))
    }
    internal fun month(month: YearMonth, values: com.dayforge.domain.model.CountSummary.Values): CountDisplayRollup.Month {
        val presence = values.quantities.keys.fold(0) { mask, date ->
            require(YearMonth.from(date) == month) { "CHECK_DISPLAY_MONTH_RANGE" }
            mask or (1 shl (date.dayOfMonth - 1))
        }
        // CHECK strict failure treats every absent scheduled day as absent, including dates
        // that retained COUNT sources described as policy-unknown. Original source audits stay.
        return CountDisplayRollup.Month.decode(month, presence, presence, presence, 0)
    }

    /** Existing global is mandatory once coverage was issued. Update only the independently
     * verified changed months; preserve all other authenticated year entries exactly. The
     * calendar basis stays the issued zone, avoiding arbitrary default-zone revalidation work.
     */
    fun advance(changes: List<Change>, read: (String) -> JsonElement?): Map<String, JsonElement> {
        val result = linkedMapOf<String, JsonElement>()
        for ((scope, group) in changes.groupBy { Scope(it.habit.uuid, it.head) }) {
            val habit = group.first().habit
            require(group.all { basis(it.habit) == basis(habit) } && group.map { it.month }.distinct().size == group.size)
            val before = global(requireNotNull(read(scope.globalKey())) { "CHECK_DISPLAY_GLOBAL_MISSING" }.jsonObject, scope, habit)
            val entries = before.entries.associateBy { it.summary.year }.toSortedMap()
            var anyChanged = false
            for ((number, changed) in group.groupBy { it.month.year }) {
                val previous = year(before, number, habit, read)
                val months = previous?.months.orEmpty().toMutableMap()
                var changedMask = false
                for (change in changed) {
                    val next = monthBlock(habit.uuid, change.month, change.body)
                    val old = months[change.month.monthValue]
                    if (old == null || old.qualified != next.qualified || old.quantity != next.quantity ||
                        old.participation != next.participation || old.unknown != next.unknown) changedMask = true
                    months[change.month.monthValue] = next
                }
                // Value/latest-ID or ACK metadata changes can leave the complete display
                // masks identical. Their ordinary monthly/source proofs still advance, while
                // this issued yearly calculation stays reusable without366 calendar checks.
                if (!changedMask) continue
                anyChanged = true
                val next = CountDisplayRollup.YearBlock.create(number, months.values, habit, before.zone)
                val body = year(scope, next)
                val entry = YearEntry(hash(body), next.summary)
                if (entries[number]?.hash != entry.hash) result[scope.yearKey(number)] = body
                entries[number] = entry
            }
            if (anyChanged) {
                val body = global(scope, habit, before.zone, entries.values.toList())
                if (body != read(scope.globalKey())) result[scope.globalKey()] = body
            }
        }
        return result
    }
}
