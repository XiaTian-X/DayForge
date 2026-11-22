package com.dayforge.data.repository

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule

abstract class G0CountJourneyScreenFixture:G0CountJourneyFixture() {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    internal fun install(flow:G0CountJourney,seen:G0ScreenObservation) {
        activity.scenario.onActivity { it.setContent { G0CountJourneyScreen(flow,seen) } }
    }
    internal suspend fun phase(flow:G0CountJourney,value:G0CountJourney.Phase)=withTimeout(20_000) {
        flow.state.first { it.phase==value }
    }
}
