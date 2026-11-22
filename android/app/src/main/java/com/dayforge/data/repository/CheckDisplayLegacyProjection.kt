package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.CheckDisplayProjection
import com.dayforge.domain.model.CheckHistory
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import java.time.ZoneId

/** Original complete evidence/algorithms remain the legal fallback and independent oracle. */
internal fun projectCheckDisplay(habit: HabitEntity, history: CheckHistory, zone: ZoneId,
    cardAtMillis: Long? = null): CheckDisplayProjection = CheckDisplayProjection(history.today, zone.id,
    history.todayQuantity, history.todayCompletions.maxByOrNull { it.id }?.id, history.qualifiedDates.size,
    FailureCheckerUtils.checkHasFailed(habit, history, zone), history.roundHead, history.actionIdentity,
    cardAtMillis?.let { CheckDisplayProjection.Card(
        StreakCalculator.currentFromBusinessDates(history.qualifiedDates, history.today),
        StreakCalculator.bestFromBusinessDates(history.qualifiedDates),
        ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, history.qualifiedDates.toList(), it, zone)) })
