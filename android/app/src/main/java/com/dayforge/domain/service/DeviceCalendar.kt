package com.dayforge.domain.service

import java.time.Duration
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/** Presentation clock only: it never changes captured fact dates, timestamps or timer state. */
@Singleton
class DeviceCalendar internal constructor(private val now: () -> ZonedDateTime) {
    @Inject constructor() : this({ ZonedDateTime.now() })

    data class Reading(val time: ZonedDateTime, val revision: Long) {
        val date get() = time.toLocalDate()
        val zone get() = time.zone
    }
    private val state = MutableStateFlow(Reading(now(), 0))
    val changes: StateFlow<Reading> = state.asStateFlow()

    /** Also invalidates time-window calculations when the date/zone are unchanged. */
    fun refresh() { val time = now(); state.update { Reading(time, it.revision + 1) } }

    /** Owned by a STARTED activity. Clock/zone events cancel and recompute the pending midnight. */
    suspend fun runMidnights() {
        changes.collectLatest {
            delay(untilMidnight(now()))
            refresh()
        }
    }

    companion object {
        internal fun untilMidnight(now: ZonedDateTime): Long = Duration.between(now.toInstant(),
            now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()).toMillis().coerceAtLeast(1)
    }
}
