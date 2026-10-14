package com.dayforge.domain.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DeviceCalendarTest {
    @Test fun sameDateZoneChangeAndSameZoneClockChangeAlwaysInvalidatePresentation() {
        var now = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneId.of("UTC"))
        val calendar = DeviceCalendar { now }
        val first = calendar.changes.value
        now = now.withZoneSameInstant(ZoneId.of("Pacific/Honolulu")); calendar.refresh()
        assertEquals(first.date, calendar.changes.value.date)
        assertEquals(first.time.toInstant(), calendar.changes.value.time.toInstant())
        assertNotEquals(first.zone, calendar.changes.value.zone)
        now = now.plusMinutes(2); calendar.refresh()
        assertEquals(2L, calendar.changes.value.revision)
        assertEquals(now, calendar.changes.value.time)
    }

    @Test fun midnightPublishesNewCivilDateAndCancellationStopsFurtherWork() = runTest {
        var now = ZonedDateTime.of(2026, 10, 8, 23, 59, 59, 500_000_000, ZoneId.of("Asia/Shanghai"))
        val calendar = DeviceCalendar { now }
        val job = launch { calendar.runMidnights() }; runCurrent()
        calendar.changes.test {
            val first = awaitItem()
            now = now.plusNanos(500_000_000); advanceTimeBy(500); runCurrent()
            assertEquals(first.date.plusDays(1), awaitItem().date)
            job.cancelAndJoin(); val revision = calendar.changes.value.revision
            now = now.plusDays(3); advanceTimeBy(4 * 86_400_000L); runCurrent()
            assertEquals(revision, calendar.changes.value.revision); expectNoEvents()
        }
    }

    @Test fun zoneChangeCancelsOldMidnightInsteadOfRefreshingAtTheWrongBoundary() = runTest {
        var now = ZonedDateTime.of(2026, 10, 8, 23, 59, 59, 0, ZoneId.of("Asia/Shanghai"))
        val calendar = DeviceCalendar { now }
        val job = launch { calendar.runMidnights() }; runCurrent()
        try {
            advanceTimeBy(500)
            now = now.plusNanos(500_000_000).withZoneSameInstant(ZoneId.of("Pacific/Honolulu"))
            calendar.refresh(); runCurrent()
            val revision = calendar.changes.value.revision
            advanceTimeBy(1500); runCurrent()
            assertEquals(revision, calendar.changes.value.revision)
            assertEquals(ZoneId.of("Pacific/Honolulu"), calendar.changes.value.zone)
        } finally { job.cancelAndJoin() }
    }

    @Test fun nextMidnightRespectsShortLongAndSkippedCivilDates() {
        val ny = ZoneId.of("America/New_York")
        assertEquals(23 * 3_600_000L, DeviceCalendar.untilMidnight(ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny)))
        assertEquals(25 * 3_600_000L, DeviceCalendar.untilMidnight(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny)))
        assertEquals(12 * 3_600_000L, DeviceCalendar.untilMidnight(
            ZonedDateTime.of(2011, 12, 29, 12, 0, 0, 0, ZoneId.of("Pacific/Apia"))))
    }
}
