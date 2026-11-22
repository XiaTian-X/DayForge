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
internal object TimerDisplayBlocks {
    const val COVERAGE_KEY = "formal:timer-display-coverage"
    const val COVERAGE = "timer-completed-millis-calendar/v1"
    private fun headJson(head: ChallengeRoundHead?): JsonElement = head?.let(CountTrustRoundEvidence::encode) ?: JsonNull
    data class Scope(val habitUuid: String, val head: ChallengeRoundHead?) {
        init { require(head == null || head.activityUuid == habitUuid) }
        fun globalKey() = CountTrustMembership.key("formal.timer-display.global", habitUuid, headJson(head).toString())
        fun monthKey(month: YearMonth) = CountTrustMembership.key("formal.timer-display.month", habitUuid,
            headJson(head).toString(), month.toString())
        fun yearKey(year: Int) = CountTrustMembership.key("formal.timer-display.year", habitUuid,
            headJson(head).toString(), year.toString())
    }
    class Change(val habit: HabitEntity, val head: ChallengeRoundHead?, val month: YearMonth, val body: JsonObject)
    class YearEntry(val hash: String, val summary: CountDisplayRollup.YearSummary)
    class Global(val scope: Scope, val zone: ZoneId, entries: List<YearEntry>, val fold: CountDisplayRollup.Complete) {
        val entries: List<YearEntry> = Collections.unmodifiableList(entries.toList())
    }
    private fun hash(value: JsonElement) = CountTrustIndex.hash(value.toString().toByteArray(Charsets.UTF_8))
    private fun basis(habit: HabitEntity) = buildJsonObject {
        require(habit.habitType == com.dayforge.data.model.HabitType.TIMER) { "TIMER_ACTIVITY_CHANGED" }
        put("schedule", Json.encodeToJsonElement(HabitSchedule.serializer(), habit.schedule))
        put("created_at", habit.createdAt); put("habit_type", habit.habitType.name)
        put("target_seconds", Math.multiplyExact(habit.targetValue.toLong(), 60L))
    }
    private fun header(scope: Scope, kind: String) = buildJsonObject {
        put("version", 1); put("kind", kind); put("habit_uuid", scope.habitUuid)
        put("head", headJson(scope.head))
    }
    private fun checkHeader(value: JsonObject, scope: Scope, kind: String) {
        require(value["version"] == JsonPrimitive(1) && value["kind"] == JsonPrimitive(kind) &&
            value["habit_uuid"] == JsonPrimitive(scope.habitUuid) && value["head"] == headJson(scope.head)) {
            "TIMER_DISPLAY_SCOPE_CHANGED"
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
        require(value["basis"] == basis(habit)) { "TIMER_DISPLAY_PLAN_CHANGED" }
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
            value.date("first_violation") == fold.firstViolation) { "TIMER_DISPLAY_FOLD_CHANGED" }
        return Global(scope, zone, entries, fold)
    }
    fun year(global: Global, expectedYear: Int, habit: HabitEntity, read: (String) -> JsonElement?): CountDisplayRollup.YearBlock? {
        val entry = global.entries.singleOrNull { it.summary.year == expectedYear } ?: return null
        val value = requireNotNull(read(global.scope.yearKey(expectedYear))) { "TIMER_DISPLAY_YEAR_MISSING" }.jsonObject
        require(hash(value) == entry.hash) { "TIMER_DISPLAY_YEAR_CHANGED" }
        require(global.scope.habitUuid == habit.uuid)
        return year(value, global.scope, expectedYear, entry.summary)
    }
    fun issue(habit: HabitEntity, head: ChallengeRoundHead?, milliseconds: Map<LocalDate, Long>, zone: ZoneId): Map<String, JsonElement> {
        val scope = Scope(habit.uuid, head)
        val months = milliseconds.entries.groupBy { YearMonth.from(it.key) }.toSortedMap().mapValues { (month, entries) ->
            monthBody(scope, month, entries.associate { it.key to it.value })
        }
        val years = months.map { (month, body) -> monthBlock(scope, habit, month, body) }.groupBy { it.month.year }
            .toSortedMap().mapValues { (year, blocks) -> CountDisplayRollup.YearBlock.create(year, blocks, habit, zone) }
        val result = linkedMapOf<String, JsonElement>()
        months.forEach { (month, body) -> result[scope.monthKey(month)] = body }
        val entries = years.map { (number, block) ->
            val value = year(scope, block); result[scope.yearKey(number)] = value
            YearEntry(hash(value), block.summary)
        }
        result[scope.globalKey()] = global(scope, habit, zone, entries)
        return result
    }
    internal fun monthBody(scope: Scope, month: YearMonth, values: Map<LocalDate, Long>): JsonObject =
        JsonObject(header(scope, "month") + buildJsonObject {
            put("month", month.toString())
            put("milliseconds", JsonObject(values.toSortedMap().mapKeys { it.key.toString() }.mapValues { (_, value) ->
                require(value >= 0L); JsonPrimitive(value)
            }))
        })

    fun milliseconds(value: JsonObject, scope: Scope, month: YearMonth): Map<LocalDate, Long> {
        require(value.keys == setOf("version", "kind", "habit_uuid", "head", "month", "milliseconds"))
        checkHeader(value, scope, "month")
        require(value["month"] == JsonPrimitive(month.toString())) { "TIMER_DISPLAY_MONTH_CHANGED" }
        return Collections.unmodifiableMap(value.getValue("milliseconds").jsonObject.map { (date, raw) ->
            val parsed = LocalDate.parse(date)
            require(parsed.toString() == date && YearMonth.from(parsed) == month) { "TIMER_DISPLAY_MONTH_RANGE" }
            val number = raw.jsonPrimitive
            require(!number.isString && number.long >= 0L) { "TIMER_DISPLAY_MILLIS_CHANGED" }
            parsed to number.long
        }.toMap())
    }

    private fun monthBlock(scope: Scope, habit: HabitEntity, month: YearMonth, body: JsonObject): CountDisplayRollup.Month =
        month(month, milliseconds(body, scope, month), Math.multiplyExact(habit.targetValue.toLong(), 60L))

    internal fun month(month: YearMonth, values: Map<LocalDate, Long>, targetSeconds: Long): CountDisplayRollup.Month {
        require(targetSeconds > 0L)
        var quantity = 0
        var qualified = 0
        values.forEach { (date, millis) ->
            require(YearMonth.from(date) == month && millis >= 0L) { "TIMER_DISPLAY_MONTH_RANGE" }
            val bit = 1 shl (date.dayOfMonth - 1)
            quantity = quantity or bit
            if (millis / 1_000L >= targetSeconds) qualified = qualified or bit
        }
        // Every completed allocation date starts strict checking, even if it is below
        // today's display target. Activity rate/streaks count qualified dates only.
        return CountDisplayRollup.Month.decode(month, qualified, quantity, qualified, 0)
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
            val before = global(requireNotNull(read(scope.globalKey())) { "TIMER_DISPLAY_GLOBAL_MISSING" }.jsonObject, scope, habit)
            val entries = before.entries.associateBy { it.summary.year }.toSortedMap()
            var anyChanged = false
            for ((number, changed) in group.groupBy { it.month.year }) {
                val previous = year(before, number, habit, read)
                val months = previous?.months.orEmpty().toMutableMap()
                var changedMask = false
                for (change in changed) {
                    result[scope.monthKey(change.month)] = change.body
                    val next = monthBlock(scope, habit, change.month, change.body)
                    val old = months[change.month.monthValue]
                    if (old == null || old.qualified != next.qualified || old.quantity != next.quantity ||
                        old.participation != next.participation || old.unknown != next.unknown) changedMask = true
                    months[change.month.monthValue] = next
                }
                // Milliseconds may change without crossing a display threshold. The month
                // leaf still changes; unchanged year masks retain their authenticated fold.
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

    /** Only data from this authenticated partition; the independently captured active control
     * can have another birth and must not be rebuilt from this display head.
     */
    fun project(global: Global, habit: HabitEntity, today: LocalDate, zone: ZoneId, now: Long,
        activeLog: com.dayforge.data.local.entity.TimeLogEntity?,
        activeSegments: List<com.dayforge.data.local.entity.TimerSegmentEntity>,
        read: (String) -> JsonElement?): com.dayforge.domain.model.TimerDisplayProjection {
        require(global.zone == zone && java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == today) {
            "TIMER_DISPLAY_DATE_CHANGED"
        }
        val targetSeconds = Math.multiplyExact(habit.targetValue.toLong(), 60L)
        val years = linkedMapOf<Int, CountDisplayRollup.YearBlock?>()
        fun year(number: Int): CountDisplayRollup.YearBlock? = if (number in years) years[number]
            else TimerDisplayBlocks.year(global, number, habit, read).also { years[number] = it }
        val months = linkedMapOf<YearMonth, Map<LocalDate, Long>>()
        fun monthValues(month: YearMonth): Map<LocalDate, Long> = months.getOrPut(month) {
            val expected = year(month.year)?.months?.get(month.monthValue)
            val body = read(global.scope.monthKey(month))?.jsonObject
            check((expected == null) == (body == null)) { "TIMER_DISPLAY_MONTH_CHANGED" }
            if (body == null) emptyMap() else milliseconds(body, global.scope, month).also { values ->
                val actual = TimerDisplayBlocks.month(month, values, targetSeconds)
                check(actual.qualified == requireNotNull(expected).qualified && actual.quantity == expected.quantity &&
                    actual.participation == expected.participation && actual.unknown == expected.unknown) {
                    "TIMER_DISPLAY_MONTH_CHANGED"
                }
            }
        }
        fun qualified(date: LocalDate): Boolean = year(date.year)?.qualified(date) == true
        fun millis(date: LocalDate): Long = monthValues(YearMonth.from(date))[date] ?: 0L
        year(today.year)
        if (today > LocalDate.MIN) year(today.minusDays(1).year)
        val current = global.fold.currentStreak(today, years.filterValues { it != null }.mapValues { requireNotNull(it.value) })
        val days = com.dayforge.domain.service.ActivityRateCalculator.generateWindowCheckInDays(
            habit.schedule, habit.createdAt, now, zone).map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val missed = days.count { !qualified(it) }
        val activity = (100 - missed * com.dayforge.domain.service.ActivityRateCalculator.getDeductionPerMiss(habit.schedule)).coerceAtLeast(0)
        var failure = com.dayforge.domain.service.StrictFailureState.NOT_FAILED
        if (com.dayforge.domain.service.FailureCheckerUtils.shouldCheckFailure(habit) &&
            com.dayforge.domain.service.FailureCheckerUtils.isStrictMode(habit)) {
            // The authenticated fold proves a healthy prefix without reading its years.
            // A missing date after the final stored year is a real gap too; a null
            // firstViolation alone must never suppress that unrepresented tail.
            var date = if (!global.fold.hasFailed(habit, today, zone)) null else
                global.fold.firstViolation?.takeIf { it < today } ?: global.fold.years.lastOrNull()?.last?.let { last ->
                    if (last < today) CountDisplayCalendar.firstDue(habit, zone, last.plusDays(1), today) else null
                }
            var pending = false
            while (date != null && date < today) {
                if (com.dayforge.domain.service.ScheduleValidator.isCheckInAllowedOnDate(habit.schedule, habit.createdAt, date) &&
                    !qualified(date)) {
                    val day = com.dayforge.data.local.dao.TimerFailureDaySnapshot(millis(date), activeLog, activeSegments)
                    if (com.dayforge.domain.service.UnsettledTimerFailure.maySettle(day, date, targetSeconds)) pending = true
                    else { failure = com.dayforge.domain.service.StrictFailureState.FAILED; break }
                }
                date = date.plusDays(1)
            }
            if (failure != com.dayforge.domain.service.StrictFailureState.FAILED && pending)
                failure = com.dayforge.domain.service.StrictFailureState.AWAITING_TIMER_SETTLEMENT
        }
        return com.dayforge.domain.model.TimerDisplayProjection(today, zone.id, global.scope.head,
            targetSeconds, millis(today), global.fold.qualifiedCount, global.fold.firstQuantity,
            current, global.fold.bestStreak, activity, failure)
    }
}
