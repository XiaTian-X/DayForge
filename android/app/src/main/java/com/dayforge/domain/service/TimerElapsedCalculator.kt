package com.dayforge.domain.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.dayforge.data.local.entity.TimeLogEntity

/** Shared clock-safe elapsed calculation for UI, widgets, and service callers. */
object TimerElapsedCalculator {
    fun elapsedMillis(log: TimeLogEntity, context: Context): Long {
        if (log.isPaused) return log.timerActiveElapsedMillis.coerceAtLeast(0)
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        val anchor = log.timerElapsedRealtimeAnchor
        if (anchor != null && bootCount == log.timerBootCount) {
            return (log.timerActiveElapsedMillis + SystemClock.elapsedRealtime() - anchor)
                .coerceAtLeast(0)
        }
        val wallFallback = System.currentTimeMillis() - log.startTime - log.accumulatedPauseMillis
        return maxOf(log.timerActiveElapsedMillis, wallFallback).coerceAtLeast(0)
    }

    fun elapsedSeconds(log: TimeLogEntity, context: Context): Int =
        (elapsedMillis(log, context) / 1_000L)
            .coerceIn(0, Int.MAX_VALUE.toLong())
            .toInt()
}
