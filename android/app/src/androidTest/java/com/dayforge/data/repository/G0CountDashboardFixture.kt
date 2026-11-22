package com.dayforge.data.repository

import androidx.activity.compose.setContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After

abstract class G0CountDashboardFixture:G0CountJourneyScreenFixture() {
    override fun domainOpeningCallback():androidx.room.RoomDatabase.Callback=G0SchemaOpeningCallback(true)
    private val homes=mutableListOf<G0CountDashboard>()
    internal open val coalesceDashboardBeforeRead:Boolean get()=false
    internal fun dashboardRepository()=G0DetailedDashboardReadRepository(db,tokens,sessions,
        ReplicaPresentationRepository(db,tokens,sessions),CountHistoryReader(db,tokens,sessions),CheckHistoryReader(db,tokens,sessions))
    internal fun dashboard(coalesce:Boolean=coalesceDashboardBeforeRead)=G0CountDashboard(app,dashboardRepository(),journeyOwner,coalesce).also {homes+=it}
    internal suspend fun homeQuantity(home:G0CountDashboard,quantity:Long)=withTimeout(20_000) {
        home.state.first { it.valid() && it.batch?.counts?.get(habit.id)?.todayQuantity==quantity }
    }
    internal fun installBoth(flow:G0CountJourney,home:G0CountDashboard,a:G0ScreenObservation,b:G0ScreenObservation) {
        activity.scenario.onActivity {it.setContent {G0CountDashboardScreen(flow,home,a,b)}}
    }
    internal fun requireCommandBounds(seen:G0ScreenObservation) {
        val pane=requireNotNull(seen.bounds["command-pane"])
        for(key in listOf("increment","close")) {
            val button=requireNotNull(seen.bounds[key])
            org.junit.Assert.assertTrue("$key $button outside $pane",
                button.left>=pane.left && button.top>=pane.top && button.right<=pane.right && button.bottom<=pane.bottom)
        }
    }
    internal suspend fun both(flow:G0CountJourney,home:G0CountDashboard,a:G0ScreenObservation,b:G0ScreenObservation)=
        G0ScreenObservation.awaitTogether {
            val x=flow.state.value;val y=home.state.value
            if(!G0CountDashboard.compatible(x,y)) null else listOf(
                G0ScreenObservation.Target(a,x.version) {x.renderable()},
                G0ScreenObservation.Target(b,y.version) {G0CountDashboard.compatible(flow.state.value,y)})
        }
    @After fun stopDashboards()=runBlocking {homes.forEach {it.shutdown()}}
}
