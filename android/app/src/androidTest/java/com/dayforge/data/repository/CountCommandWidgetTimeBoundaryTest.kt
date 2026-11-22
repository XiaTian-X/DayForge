package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Real saved fact, original reader oracle and deterministic date/default-zone changes.
 * No production clock hook, wall-clock mutation, fixed sleep or time-policy change.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandWidgetTimeBoundaryTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun postWriteCurrentDateDoesNotBecomeTheOriginalFactDate() = runBlocking<Unit> {
        widgetCommandCurrentClockAndSourceClockRemainSeparate(crossDate = true)
    }
    @Test(timeout = 150_000) fun changedDefaultZoneKeepsOriginalCurrentMathAndSeparateFactSource() = runBlocking<Unit> {
        widgetCommandCurrentClockAndSourceClockRemainSeparate(crossDate = false)
    }
}
