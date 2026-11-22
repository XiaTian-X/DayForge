package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.ScheduleValidator
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections

/** Pure calendar calculation. A caller must separately prove complete inputs, exact calendar
 * basis and the current authenticated root. No fact/source, database handle or authority is kept.
 */
internal object CountDisplayRollup {
    class Month private constructor(val month: YearMonth, val qualified: Int, val quantity: Int,
        val participation: Int, val unknown: Int) {
        fun has(mask: Int, day: Int): Boolean {
            require(day in 1..month.lengthOfMonth())
            return mask and (1 shl (day - 1)) != 0
        }
        val firstQuantity: LocalDate? get() = quantity.takeUnless { it == 0 }
            ?.let { month.atDay(Integer.numberOfTrailingZeros(it) + 1) }
        companion object {
            fun from(month: YearMonth, values: CountSummary.Values): Month {
                fun bits(dates: Collection<LocalDate>): Int = dates.fold(0) { result, date ->
                    require(YearMonth.from(date) == month) { "COUNT_DISPLAY_MONTH_RANGE" }
                    result or (1 shl (date.dayOfMonth - 1))
                }
                return decode(month, bits(values.qualifiedDates), bits(values.quantities.keys),
                    bits(values.participatingDates), bits(values.unknownDates.filter { it in values.quantities }))
            }
            fun decode(month: YearMonth, qualified: Int, quantity: Int, participation: Int, unknown: Int): Month {
                val allowed = ((1L shl month.lengthOfMonth()) - 1L).toInt()
                require(listOf(qualified, quantity, participation, unknown).all { it >= 0 && (it and allowed) == it })
                require((qualified and quantity) == qualified && (unknown and quantity) == unknown &&
                    (qualified and unknown) == 0) { "COUNT_DISPLAY_MASK_CHANGED" }
                return Month(month, qualified, quantity, participation, unknown)
            }
        }
    }
    data class YearSummary(val year: Int, val count: Int, val prefix: Int, val suffix: Int,
        val best: Int, val firstQuantity: LocalDate?, val firstMiss: LocalDate?, val startedMiss: LocalDate?) {
        val length: Int get() = Year.of(year).length()
        val first: LocalDate get() = LocalDate.of(year, 1, 1)
        val last: LocalDate get() = LocalDate.of(year, 12, 31)
        fun checked(): YearSummary {
            require(count in 0..length && prefix in 0..count && suffix in 0..count && best in 0..count)
            require((count == length) == (prefix == length && suffix == length))
            require(listOfNotNull(firstQuantity, firstMiss, startedMiss).all { it.year == year })
            require(startedMiss == null || firstQuantity != null && startedMiss >= firstQuantity)
            require(firstQuantity != null || count == 0)
            return this
        }
    }
    class YearBlock private constructor(val year: Int, months: Map<Int, Month>, val summary: YearSummary) {
        val months: Map<Int, Month> = Collections.unmodifiableMap(LinkedHashMap(months))
        fun qualified(date: LocalDate): Boolean {
            require(date.year == year)
            return months[date.monthValue]?.let { it.has(it.qualified, date.dayOfMonth) } == true
        }
        fun participated(date: LocalDate): Boolean {
            require(date.year == year)
            return months[date.monthValue]?.let { it.has(it.participation, date.dayOfMonth) } == true
        }
        fun suffixThrough(date: LocalDate): Int {
            require(date.year == year)
            var current = date
            var result = 0
            while (qualified(current)) {
                result++
                if (current.dayOfYear == 1) break
                current = current.minusDays(1)
            }
            return result
        }
        companion object {
            /** Only after the caller verified the current year bytes against the authenticated
             * global entry's exact digest. Reuses its issued calculation instead of rerunning
             * the calendar business predicate for all366 days on every consumer read. */
            fun authenticated(year: Int, months: Collection<Month>, summary: YearSummary): YearBlock {
                val owned = months.sortedBy { it.month }.associateBy { it.month.monthValue }
                require(owned.size == months.size && owned.values.all { it.month.year == year })
                require(summary.year == year)
                return YearBlock(year, owned, summary.checked())
            }
            fun create(year: Int, months: Collection<Month>, habit: HabitEntity, zone: ZoneId): YearBlock {
                val owned = months.sortedBy { it.month }.associateBy { it.month.monthValue }
                require(owned.size == months.size && owned.values.all { it.month.year == year })
                val first = LocalDate.of(year, 1, 1)
                val start = owned.values.mapNotNull { it.firstQuantity }.minOrNull()
                var count = 0; var prefix = 0; var suffix = 0; var best = 0
                var firstMiss: LocalDate? = null; var startedMiss: LocalDate? = null
                for (offset in 0 until Year.of(year).length()) {
                    val date = first.plusDays(offset.toLong())
                    val month = owned[date.monthValue]
                    val qualified = month?.let { it.has(it.qualified, date.dayOfMonth) } == true
                    if (qualified) {
                        count++; suffix++
                        if (prefix == offset) prefix++
                        best = maxOf(best, suffix)
                    } else suffix = 0
                    val unknown = month?.let { it.has(it.unknown, date.dayOfMonth) } == true
                    if (!qualified && !unknown && ScheduleValidator.isCheckInAllowedOnDate(habit.schedule, habit.createdAt, date, zone)) {
                        if (firstMiss == null) firstMiss = date
                        if (start != null && date >= start && startedMiss == null) startedMiss = date
                    }
                }
                return YearBlock(year, owned, YearSummary(year, count, prefix, suffix, best, start, firstMiss, startedMiss).checked())
            }
        }
    }
    class Complete private constructor(years: List<YearSummary>, val qualifiedCount: Int,
        val bestStreak: Int, val firstQuantity: LocalDate?, val firstViolation: LocalDate?) {
        val years: List<YearSummary> = Collections.unmodifiableList(years.toList())
        /** Best/count intentionally include future dates. Only this requested-date prefix
         * removes them, preserving the original date-line-travel behavior. */
        fun currentStreak(today: LocalDate, blocks: Map<Int, YearBlock>): Int {
            fun block(date: LocalDate): YearBlock? {
                val expected = years.singleOrNull { it.year == date.year } ?: return null
                return requireNotNull(blocks[date.year]) { "COUNT_DISPLAY_YEAR_REQUIRED" }.also {
                    require(it.summary == expected) { "COUNT_DISPLAY_YEAR_CHANGED" }
                }
            }
            val end = when {
                block(today)?.qualified(today) == true -> today
                today > LocalDate.MIN && block(today.minusDays(1))?.qualified(today.minusDays(1)) == true -> today.minusDays(1)
                else -> return 0
            }
            var result = requireNotNull(block(end)).suffixThrough(end)
            if (result != end.dayOfYear) return result
            var year = end.year.toLong() - 1L
            for (previous in years.asReversed()) {
                if (previous.year >= end.year) continue
                if (previous.year.toLong() != year) break
                result = Math.addExact(result, previous.suffix)
                if (previous.count != previous.length) break
                year--
            }
            return result
        }
        fun hasFailed(habit: HabitEntity, today: LocalDate, zone: ZoneId): Boolean {
            if (!FailureCheckerUtils.shouldCheckFailure(habit) || !FailureCheckerUtils.isStrictMode(habit) || firstQuantity == null) return false
            if (firstViolation?.let { it < today } == true) return true
            val last = years.lastOrNull()?.last ?: return false
            return last < today && firstDue(habit, zone, last.plusDays(1), today) != null
        }
        companion object {
            fun create(years: Collection<YearSummary>, habit: HabitEntity, zone: ZoneId): Complete {
                val ordered = years.sortedBy { it.year }.onEach { it.checked() }
                require(ordered.map { it.year }.distinct().size == ordered.size)
                var count = 0; var best = 0; var suffix = 0
                var start: LocalDate? = null; var violation: LocalDate? = null
                var previous: YearSummary? = null
                for (year in ordered) {
                    count = Math.addExact(count, year.count)
                    val adjacent = previous?.let { it.year.toLong() + 1L == year.year.toLong() } == true
                    if (!adjacent) suffix = 0
                    best = maxOf(best, year.best, Math.addExact(suffix, year.prefix))
                    suffix = if (year.count == year.length) Math.addExact(suffix, year.length) else year.suffix
                    if (start == null) {
                        start = year.firstQuantity
                        if (start != null) violation = year.startedMiss
                    } else if (violation == null) {
                        if (!adjacent) violation = firstDue(habit, zone, requireNotNull(previous).last.plusDays(1), year.first)
                        if (violation == null) violation = year.firstMiss
                    }
                    previous = year
                }
                return Complete(ordered, count, best, start, violation)
            }
        }
    }
    private fun firstDue(habit: HabitEntity, zone: ZoneId, first: LocalDate, after: LocalDate): LocalDate? =
        CountDisplayCalendar.firstDue(habit, zone, first, after)
}
