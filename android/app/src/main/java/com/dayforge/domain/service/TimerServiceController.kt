package com.dayforge.domain.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.model.TimerStartGuard
import com.dayforge.data.repository.LegacyWidgetTimerClaim

/** Sends explicit commands to [TimerService] without owning timer state. */
object TimerServiceController {
    fun startTimer(
        context: Context,
        habitId: Long,
        targetMinutes: Int,
        isCountdown: Boolean = false,
        authority: TimerActionAuthority? = null,
        startGuard: TimerStartGuard? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null
    ) {
        val intent = commandIntent(
            context = context,
            action = TimerService.ACTION_START,
            habitId = habitId,
            targetMinutes = targetMinutes,
            isCountdown = isCountdown,
            authority = authority,
            legacyWidget = legacyWidget
        )
        startGuard?.attach(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun pauseTimer(context: Context, habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null) {
        context.startService(
            commandIntent(context, TimerService.ACTION_PAUSE, habitId, targetMinutes, authority = authority, legacyWidget = legacyWidget)
        )
    }

    fun resumeTimer(context: Context, habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null) {
        context.startService(
            commandIntent(context, TimerService.ACTION_RESUME, habitId, targetMinutes, authority = authority, legacyWidget = legacyWidget)
        )
    }

    fun stopTimer(context: Context, habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null) {
        context.startService(
            commandIntent(context, TimerService.ACTION_STOP, habitId, targetMinutes, authority = authority, legacyWidget = legacyWidget)
        )
    }

    fun discardTimer(context: Context, habitId: Long, targetMinutes: Int, authority: TimerActionAuthority? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null) {
        context.startService(
            commandIntent(context, TimerService.ACTION_DISCARD, habitId, targetMinutes, authority = authority, legacyWidget = legacyWidget)
        )
    }

    internal fun commandIntent(
        context: Context,
        action: String,
        habitId: Long,
        targetMinutes: Int,
        isCountdown: Boolean? = null,
        authority: TimerActionAuthority? = null,
        legacyWidget: LegacyWidgetTimerClaim? = null
    ): Intent = Intent(context, TimerService::class.java).apply {
        this.action = action
        putExtra(TimerService.EXTRA_HABIT_ID, habitId)
        putExtra(TimerService.EXTRA_TARGET_MINUTES, targetMinutes)
        if (isCountdown != null) {
            putExtra(TimerService.EXTRA_IS_COUNTDOWN, isCountdown)
        }
        require(authority == null || legacyWidget == null)
        authority?.attach(this)
        legacyWidget?.attach(this)
    }
}
