package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** One real reopen -> open -> sync journey without an artificial no-op save. */
class G0RecoveredPendingScreenTest : G0InteractionFixture() {
    @get:Rule val activity=ActivityScenarioRule(ComponentActivity::class.java)
    override fun installScreen() { activity.scenario.onActivity { it.setContent { G0InteractionScreen(interaction,observation) } } }
    @Test fun pendingSavedContentCanSyncDirectlyAfterColdReopen()=runBlocking<Unit> {
        register();val original=editMetric("Saved before connection close")
        val setup=SystemClock.elapsedRealtimeNanos();val previous=db
        freshGateForColdOpen();storage.reopen();assertFalse(previous.isOpen);assertFalse(db.isOpen)
        setupInteraction();observation.await(interaction.state.value.version)
        val open=observation.tap("open");val ready=phase(G0InteractionSession.Phase.EDITING)
        val content=observation.await(ready.version);interaction.awaitSettled()
        assertTrue(ready.canSync());assertEquals(1,ready.publication!!.value.pending)
        val sync=observation.tap("sync");val synced=phase(G0InteractionSession.Phase.SYNCED)
        val done=observation.await(synced.version);interaction.awaitSettled()
        assertTrue(synced.renderable());assertEquals(0,synced.publication!!.value.pending)
        assertTrue(interaction.events.none { it.kind=="committed" });assertTrue(adapter.spans.none { it.stage=="edit" })
        assertEquals(1,adapter.spans.count { it.stage=="ack" && it.completed })
        Log.i("G0RecoveredScreen","{\"setup_to_editable_ns\":${content.observed-setup},\"open_to_editable_ns\":${content.observed-open},\"sync_to_observed_ns\":${done.observed-sync},\"new_business_writes\":0,\"exact_first_presentation\":false}")
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,original.operationId))
    }
}
