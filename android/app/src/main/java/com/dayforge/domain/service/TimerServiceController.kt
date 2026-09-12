package com.dayforge.domain.service

import android.content.Context
import android.content.Intent
import android.os.Build

/** Sends explicit commands to [TimerService] without owning timer state. */
object TimerServiceController {
    fun startTimer(
        context: Context,
        habitId: Long,
        targetMinutes: Int,
        isCountdown: Boolean = false
    ) {
        val intent = commandIntent(
            context = context,
            action = TimerService.ACTION_START,
            habitId = habitId,
            targetMinutes = targetMinutes,
            isCountdown = isCountdown
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun pauseTimer(context: Context, habitId: Long, targetMinutes: Int) {
        context.startService(
            commandIntent(context, TimerService.ACTION_PAUSE, habitId, targetMinutes)
        )
    }

    fun resumeTimer(context: Context, habitId: Long, targetMinutes: Int) {
        context.startService(
            commandIntent(context, TimerService.ACTION_RESUME, habitId, targetMinutes)
        )
    }

    fun stopTimer(context: Context, habitId: Long, targetMinutes: Int) {
        context.startService(
            commandIntent(context, TimerService.ACTION_STOP, habitId, targetMinutes)
        )
    }

    fun discardTimer(context: Context, habitId: Long, targetMinutes: Int) {
        context.startService(
            commandIntent(context, TimerService.ACTION_DISCARD, habitId, targetMinutes)
        )
    }

    internal fun commandIntent(
        context: Context,
        action: String,
        habitId: Long,
        targetMinutes: Int,
        isCountdown: Boolean? = null
    ): Intent = Intent(context, TimerService::class.java).apply {
        this.action = action
        putExtra(TimerService.EXTRA_HABIT_ID, habitId)
        putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        if (isCountdown != null) {
            putExtra(TimerService.EXTRA_IS_COUNTDOWN, isCountdown)
        }
    }
}
