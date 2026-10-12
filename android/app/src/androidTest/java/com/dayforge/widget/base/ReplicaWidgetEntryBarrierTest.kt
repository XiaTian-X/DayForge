package com.dayforge.widget.base

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.ReplicaSwitchGuard
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.data.local.replicaStageFixture
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual primary Room/DataStore + the standalone production Compose gate; not a Hilt APK journey. */
@RunWith(AndroidJUnit4::class)
class ReplicaWidgetEntryBarrierTest : NextCoreRequestFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var controller: ReplicaPresentationController? = null
    private var created = 0
    private var cleared = 0
    private var closes = 0
    private suspend fun start(): ReplicaPresentationController =
        ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions)).also {
            controller = it
        }
    private fun show(gate: ReplicaPresentationController) = compose.setContent {
        WidgetReplicaGate(gate, { closes++ }) {
            DisposableEffect(Unit) { created++; onDispose { cleared++ } }
            Text("Original widget confirmation")
        }
    }
    @After fun closeController() = runBlocking<Unit> {
        controller?.close(); controller?.awaitClosed()
    }

    @Test fun realFreezeClosesOriginalPageAndVerifiedCancelCannotReviveItsOldContent() = runBlocking<Unit> {
        register(); val gate = start()
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        show(gate); compose.onNodeWithText("Original widget confirmation").assertIsDisplayed()
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        compose.waitUntil(5_000) { closes == 1 && cleared == 1 }
        compose.onNodeWithText("Original widget confirmation").assertDoesNotExist()
        stages.cancel(stage)
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        compose.waitForIdle()
        compose.onNodeWithText("Original widget confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(1, created); assertEquals(1, cleared) }
    }

    @Test fun coldOrphanJournalAndDamageCloseWithoutComposingThemeOrBusiness() = runBlocking<Unit> {
        register(); val stage = tokens.replicaStageFixture(); tokens.persistReplicaStage(null, stage)
        dataStore.edit { it.remove(ReplicaSwitchGuard.POINTER) }
        val gate = start(); show(gate)
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        compose.waitUntil(5_000) { closes == 1 }
        dataStore.edit { it[ReplicaSwitchStage.key(stage.recoveryId)] = "synthetic-damaged-journal" }
        gate.retry(); compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, created); assertEquals(0, cleared); assertEquals(1, closes) }
        compose.onNodeWithText("Original widget confirmation").assertDoesNotExist()
        assertNull(db.nextReplicaFenceDao().state())
    }

    @Test fun conflatedFreezeCancelCannotAdoptNewPermitWhenUiNeverObservesPending() = runBlocking<Unit> {
        register(); val gate = start()
        val original = withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        val lifetime = WidgetReplicaLifetime()
        assertTrue(lifetime.admit(ReplicaPresentationState.Checking)); assertTrue(lifetime.admit(original))
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        stages.cancel(stage)
        val fresh = withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        assertNotSame((original as ReplicaPresentationState.Open).permit, (fresh as ReplicaPresentationState.Open).permit)
        // The Activity lifetime receives ONLY the new Open, like a conflated UI collector.
        assertFalse(lifetime.admit(fresh)); assertFalse(lifetime.admit(original))
        assertFalse(lifetime.admit(ReplicaPresentationState.Checking))
        assertTrue(WidgetReplicaLifetime().admit(fresh)) // A genuinely new Activity may enter.
    }

    @Test fun sqlOnlyFreezeClosesLivePageWithoutPointerAndOrdinaryCursorChangesDoNotCloseIt() = runBlocking<Unit> {
        register(); val gate = start()
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        val original = (gate.state.value as ReplicaPresentationState.Open).permit
        show(gate); compose.onNodeWithText("Original widget confirmation").assertIsDisplayed()
        tokens.saveSyncCursor(4); gate.retry(); compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, closes); assertEquals(1, created) }
        assertSame(original, (gate.state.value as ReplicaPresentationState.Open).permit)
        NextReplicaWriteFence(db).acquire(tokens.replicaStageFixture().fence)
        assertFalse(tokens.replicaSwitchPending())
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        compose.waitUntil(5_000) { closes == 1 && cleared == 1 }
        compose.onNodeWithText("Original widget confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, created); assertEquals(1, closes) }
    }
}
