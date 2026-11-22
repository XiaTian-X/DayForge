package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed eight facts/eight dates; span AND distribution change, not a year of daily use.
 * Same original budgets, six taps/seven syncs per arm, native terminal/oracle assertions.
 */
@RunWith(AndroidJUnit4::class)
class G0ShortCountCalendarCostTest:G0ShortCountBacklogCostFixture() {
    @Test(timeout=900_000) fun eightDatesWithinOneWeek()=runBlocking<Unit> {
        measure(8,listOf(7L,6L,5L,4L,3L,2L,1L,0L))
    }
    @Test(timeout=900_000) fun eightDatesSpreadAcrossOneYear()=runBlocking<Unit> {
        measure(8,listOf(365L,270L,180L,90L,30L,7L,1L,0L))
    }
}
