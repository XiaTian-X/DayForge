package com.dayforge.domain.service

import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object StreakCalculator {
    fun calculateCurrentStreak(
        completions: List<CompletionEntity>,
        today: LocalDate = DateTimeUtils.today()
    ): Int = currentStreak(completions.map { it.businessDate }, today)

    fun calculateBestStreak(completions: List<CompletionEntity>): Int =
        bestStreak(completions.map { it.businessDate })

    fun calculateCurrentStreakWithTarget(
        completions: List<CompletionEntity>,
        targetValue: Int,
        today: LocalDate = DateTimeUtils.today()
    ): Int = currentStreak(targetMetDays(completions, targetValue), today)

    fun calculateBestStreakWithTarget(completions: List<CompletionEntity>, targetValue: Int): Int =
        bestStreak(targetMetDays(completions, targetValue))

    private fun targetMetDays(completions: List<CompletionEntity>, targetValue: Int): List<LocalDate> =
        completions.groupBy { it.businessDate }
            .filterValues { day -> day.sumOf { it.value } >= targetValue }
            .keys.toList()

    // Keep the existing timer projection API separate from captured completion dates.
    fun calculateCurrentStreakFromDates(dates: List<Long>, today: LocalDate = DateTimeUtils.today()): Int {
        val days = dates.map { displayDate(it) }
        // Preserve the timer path's existing handling of dates ahead of today.
        return if (days.any { it > today }) 0 else currentStreak(days, today)
    }

    fun calculateBestStreakFromDates(dates: List<Long>): Int = bestStreak(dates.map { displayDate(it) })

    private fun displayDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun currentStreak(dates: List<LocalDate>, today: LocalDate): Int {
        // Westward date-line travel can leave valid captured dates ahead of today.
        // Preserve that history, but do not let it hide today's/yesterday's streak.
        val days = dates.filter { it <= today }.distinct().sortedDescending()
        val latest = days.firstOrNull() ?: return 0
        if (latest != today && latest != today.minusDays(1)) return 0
        var expected = latest
        var streak = 0
        for (day in days) {
            if (day != expected) break
            streak++
            expected = expected.minusDays(1)
        }
        return streak
    }

    private fun bestStreak(dates: List<LocalDate>): Int {
        val days = dates.distinct().sorted()
        if (days.isEmpty()) return 0
        var best = 1
        var current = 1
        for (i in 1 until days.size) {
            current = if (days[i] == days[i - 1].plusDays(1)) current + 1 else 1
            best = maxOf(best, current)
        }
        return best
    }
}
