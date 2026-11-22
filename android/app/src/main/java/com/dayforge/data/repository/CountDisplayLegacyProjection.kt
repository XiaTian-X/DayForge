package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.CountDisplayProjection
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import java.time.ZoneId

/** The unchanged full-data algorithms are the legal fallback and independent differential
 * oracle. No unneeded all-history quantity sum is evaluated by this finite display adapter. */
internal fun projectCountDisplay(habit: HabitEntity, summary: CountSummary, zone: ZoneId,
    cardAtMillis: Long? = null): CountDisplayProjection = CountDisplayProjection(summary.today, zone.id,
    summary.todayQuantity, summary.todayPolicy, summary.todayLatestCompletionId, summary.qualifiedDates.size,
    FailureCheckerUtils.countHasFailed(habit, summary, zone), summary.roundHead, summary.actionIdentity,
    cardAtMillis?.let { CountDisplayProjection.Card(StreakCalculator.currentFromBusinessDates(summary.qualifiedDates, summary.today),
        StreakCalculator.bestFromBusinessDates(summary.qualifiedDates), ActivityRateCalculator.calculate(habit.schedule,
            habit.createdAt, summary.participatingDates.toList(), it, zone)) })
