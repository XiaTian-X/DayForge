package com.dayforge.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VisibleScreenSharingTest {
    @Test fun leavingScreenStopsProjectionAndReopeningReadsCurrentData() = runTest {
        val data = MutableStateFlow(1)
        var projections = 0
        var active = 0
        val state = flow {
            active++
            try { data.collect { projections++; emit(it) } }
            finally { active-- }
        }.stateIn(backgroundScope, VisibleScreenSharing, 0)
        val first = backgroundScope.launch { state.collect() }
        runCurrent()
        assertEquals(1, state.value)
        first.cancelAndJoin()
        advanceTimeBy(1_001); runCurrent()
        assertEquals(0, active)
        assertEquals(0, state.value)
        val before = projections
        data.value = 2; runCurrent()
        assertEquals(before, projections)
        val reopened = backgroundScope.launch { state.collect() }
        runCurrent()
        assertEquals(2, state.value)
        assertEquals(1, active)
        reopened.cancelAndJoin()
    }
}
