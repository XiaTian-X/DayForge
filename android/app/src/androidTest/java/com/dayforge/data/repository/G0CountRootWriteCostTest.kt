package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0CountRootWriteCostTest:G0CountRootWriteCostFixture() {
    @Test fun eightPastFactsColdFirstWriteAndTwoConcurrentAckWindows()=runBlocking<Unit> {rootWriteJourney(8)}
    @Test fun eightyPastFactsColdFirstWriteAndTwoConcurrentAckWindows()=runBlocking<Unit> {rootWriteJourney(80)}
}
