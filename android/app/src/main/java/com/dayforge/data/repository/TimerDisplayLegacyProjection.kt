package com.dayforge.data.repository

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.model.TimerDisplayProjection
import com.dayforge.domain.model.TimerHistory
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import java.time.ZoneId

/** Original complete evidence and algorithms remain the legal fallback and differential oracle. */
internal fun projectTimerDisplay(habit: HabitEntity, history: TimerHistory, zone: ZoneId, now: Long): TimerDisplayProjection {
    val qualified = history.qualifiedDates
    return TimerDisplayProjection(history.today, zone.id, history.roundHead, history.targetSeconds,
        history.completedMillis[history.today] ?: 0L, qualified.size, history.firstDate,
        StreakCalculator.currentFromBusinessDates(qualified, history.today),
        StreakCalculator.bestFromBusinessDates(qualified),
        ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, qualified.toList(), now, zone),
        FailureCheckerUtils.timerFailure(habit, history))
}
