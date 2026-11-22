package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original full-date algorithms versus fixed masks/folds. Physical Android execution remains
 * required. No fixture here supplies a root, credential, journal or business acceptance proof. */
@RunWith(AndroidJUnit4::class)
class CountDisplayRollupTest {
    private val utc = ZoneId.of("Etc/UTC")
    private val policy = CountDayPolicy(2, false)
    private fun date(value: String) = LocalDate.parse(value)
    private fun habit(schedule: HabitSchedule = HabitSchedule.Daily, active: Boolean = true,
        mode: FailMode = FailMode.STRICT, cycles: Int? = 9) = HabitEntity(id = 1, name = "projection",
        habitType = HabitType.COUNTING, iconResId = 0, colorHex = "#FFFFFF", schedule = schedule,
        createdAt = Instant.parse("2022-12-31T12:30:00Z").toEpochMilli(), targetValue = 2,
        targetCycles = cycles, failMode = mode, isActive = active)
    private data class Built(val original: CountSummary.Values, val years: Map<Int, CountDisplayRollup.YearBlock>,
        val fold: CountDisplayRollup.Complete)
    private fun build(habit: HabitEntity, zone: ZoneId, quantities: Map<LocalDate, Long>,
        policies: Map<LocalDate, CountDayPolicy> = quantities.keys.associateWith { policy },
        unknown: Set<LocalDate> = emptySet(), retained: Set<YearMonth> = emptySet()): Built {
        val latest = quantities.keys.sorted().mapIndexed { index, day -> day to (index + 1L) }.toMap()
        // Kernel's original effective unknown adjustment precedes the independent old oracle.
        val original = CountSummary.Values.create(policies, quantities, unknown.filterTo(linkedSetOf()) { it in quantities }, latest)
        val months = (quantities.keys + policies.keys + unknown).map(YearMonth::from).toSet() + retained
        val blocks = months.sorted().map { month ->
            CountDisplayRollup.Month.from(month, CountSummary.Values.create(
                policies.filterKeys { YearMonth.from(it) == month }, quantities.filterKeys { YearMonth.from(it) == month },
                unknown.filterTo(linkedSetOf()) { YearMonth.from(it) == month }, latest.filterKeys { YearMonth.from(it) == month }))
        }.groupBy { it.month.year }.mapValues { (year, values) -> CountDisplayRollup.YearBlock.create(year, values, habit, zone) }
        return Built(original, blocks, CountDisplayRollup.Complete.create(blocks.values.map { it.summary }, habit, zone))
    }
    private fun compare(value: Built, habit: HabitEntity, zone: ZoneId, today: LocalDate) {
        val old = CountSummary.fromValues(today, value.original, value.original.policies[today], null, null)
        val selected = setOf(today.year, today.minusDays(1).year).mapNotNull { year -> value.years[year]?.let { year to it } }.toMap()
        assertEquals(old.qualifiedDates.size, value.fold.qualifiedCount)
        assertEquals(StreakCalculator.bestFromBusinessDates(old.qualifiedDates), value.fold.bestStreak)
        assertEquals(StreakCalculator.currentFromBusinessDates(old.qualifiedDates, today), value.fold.currentStreak(today, selected))
        assertEquals(FailureCheckerUtils.countHasFailed(habit, old, zone), value.fold.hasFailed(habit, today, zone))
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val window = ActivityRateCalculator.generateWindowCheckInDays(habit.schedule, habit.createdAt, now, zone)
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val hits = window.filter { day -> value.years[day.year]?.participated(day) == true }
        val compactRate = (100 - (window.size - hits.size) * ActivityRateCalculator.getDeductionPerMiss(habit.schedule)).coerceAtLeast(0)
        assertEquals(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, old.participatingDates.toList(), now, zone), compactRate)
    }
    @Test fun firstPartialMonthAndSparseCalendarGapsKeepTheOriginalFailureStart() {
        val habit = habit()
        val built = build(habit, utc, mapOf(date("2023-03-17") to 1L, date("2023-03-18") to 2L, date("2025-01-02") to 2L),
            retained = setOf(YearMonth.of(2022, 1)))
        listOf("2022-01-01", "2023-03-17", "2023-03-18", "2023-03-19", "2024-06-03", "2025-02-01")
            .forEach { compare(built, habit, utc, date(it)) }
    }
    @Test fun leapMonthAndMultipleYearBoundariesMatchOriginalStreakAlgorithms() {
        val habit = habit(); val first = date("2023-12-28")
        val built = build(habit, utc, (0L..440L).associate { first.plusDays(it) to 2L })
        listOf("2023-12-28", "2024-01-01", "2024-02-29", "2025-01-01", "2025-03-12", "2025-03-13", "2025-03-14")
            .forEach { compare(built, habit, utc, date(it)) }
    }
    @Test fun futureDatesStayInBestAndProgressButNotTheCurrentPrefix() {
        val habit = habit()
        val built = build(habit, utc, mapOf(date("2024-12-30") to 2L, date("2024-12-31") to 2L,
            date("2025-01-01") to 2L, date("2025-01-03") to 2L, date("2027-01-01") to 2L))
        listOf("2024-12-29", "2024-12-31", "2025-01-01", "2025-01-02", "2025-01-04")
            .forEach { compare(built, habit, utc, date(it)) }
        assertEquals(5, built.fold.qualifiedCount)
    }
    @Test fun removingQuantityDoesNotLeaveAnUnknownDateExemptFromStrictFailure() {
        val habit = habit(); val unknown = setOf(date("2024-02-28"), date("2024-02-29"))
        val facts = mapOf(date("2024-02-27") to 2L, date("2024-02-28") to 1L, date("2024-03-01") to 2L)
        val policies = facts.keys.filter { it !in unknown }.associateWith { policy }
        val before = build(habit, utc, facts, policies, unknown)
        val after = build(habit, utc, facts - date("2024-02-28"), policies, unknown)
        listOf("2024-02-28", "2024-02-29", "2024-03-01", "2024-03-02").forEach {
            compare(before, habit, utc, date(it)); compare(after, habit, utc, date(it))
        }
    }
    @Test fun allOriginalSchedulesAndDateLineZonesRetainTheirOwnCalendarBasis() {
        val schedules = listOf(HabitSchedule.Daily, HabitSchedule.Weekly(emptyList()), HabitSchedule.Weekly(listOf(1, 4, 7)),
            HabitSchedule.Monthly(31), HabitSchedule.Custom(11), HabitSchedule.Custom(400), HabitSchedule.Once())
        val zones = listOf(utc, ZoneId.of("Pacific/Kiritimati"), ZoneId.of("Pacific/Honolulu"))
        val facts = (0L..770L step 3).associate { date("2023-01-01").plusDays(it) to if (it % 7L == 0L) 1L else 2L }
        for (schedule in schedules) for (zone in zones) {
            val habit = habit(schedule); val built = build(habit, zone, facts)
            listOf("2023-01-01", "2023-08-09", "2024-02-29", "2025-03-01").forEach { compare(built, habit, zone, date(it)) }
        }
    }
    @Test fun emptyHistoryFailureGatesAndUnusedTotalOverflowRemainIndependent() {
        val days = listOf(date("2024-01-01"), date("2025-06-02"))
        for (habit in listOf(habit(), habit(active = false), habit(mode = FailMode.LOOSE), habit(cycles = null))) {
            val empty = build(habit, utc, emptyMap(), days.associateWith { policy })
            compare(empty, habit, utc, date("2026-01-01")); assertNull(empty.fold.firstQuantity)
            compare(build(habit, utc, mapOf(days.first() to 1L)), habit, utc, date("2026-01-01"))
        }
        val habit = habit()
        val large = build(habit, utc, days.associateWith { Long.MAX_VALUE })
        compare(large, habit, utc, date("2026-01-01")) // Card math must not access totalQuantity.
        assertThrows(ArithmeticException::class.java) { large.original.totalQuantity }
    }
    @Test fun missingOrDifferentRequestedYearCannotImpersonateTheAuthenticatedPrefix() {
        val habit = habit(); val today = date("2024-01-01")
        val original = build(habit, utc, mapOf(today to 2L)); val changed = build(habit, utc, mapOf(today to 1L))
        assertThrows(IllegalArgumentException::class.java) { original.fold.currentStreak(today, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { original.fold.currentStreak(today, changed.years) }
    }
    @Test fun invalidMasksAndMutableCollectionCastsCannotChangePublishedValues() {
        val habit = habit(); val built = build(habit, utc, mapOf(date("2024-01-01") to 2L))
        assertThrows(UnsupportedOperationException::class.java) { (built.fold.years as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (built.years.getValue(2024).months as MutableMap<*, *>).clear() }
        assertThrows(IllegalArgumentException::class.java) { CountDisplayRollup.Month.decode(YearMonth.of(2024, 2), 1 shl 29, 1 shl 29, 0, 0) }
        compare(built, habit, utc, date("2024-01-01"))
    }
}
