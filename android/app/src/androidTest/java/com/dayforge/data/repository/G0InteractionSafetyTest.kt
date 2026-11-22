package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0InteractionSafetyTest : G0InteractionFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    override fun installScreen() { compose.setContent { G0InteractionScreen(interaction,observation) } }
    @Test fun coldScreenDoesNotExposeOriginalValuesOrAcceptBlindSave()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        beforeInitialization={ entered.complete(Unit);release.await() }
        setupInteraction()
        withContext(Dispatchers.Main) { interaction.open() }
        entered.await()
        compose.onNodeWithText(metric.name).assertDoesNotExist()
        compose.onNodeWithText("正在验证，尚不可编辑").assertIsDisplayed()
        assertFalse(interaction.confirm())
        assertTrue(interaction.cancel());release.complete(Unit);interaction.awaitSettled()
        assertEquals(G0InteractionSession.Phase.CANCELLED,interaction.state.value.phase)
        assertEquals(0,builds);assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"))
    }
    @Test fun lateReadAfterCancelCannotPublishIntoReopenedSession()=runBlocking<Unit> {
        setupInteraction();openReady();val first=interaction.state.value
        assertTrue(interaction.cancel())
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.afterReadForTest={ withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        interaction.open();entered.await()
        assertTrue(interaction.cancel());val cancelled=interaction.state.value
        adapter.afterReadForTest={};interaction.open();val reopened=interaction.state.value.session
        release.complete(Unit);phase(G0InteractionSession.Phase.EDITING);interaction.awaitSettled()
        assertTrue(reopened>cancelled.session && cancelled.session>first.session)
        assertEquals(reopened,interaction.state.value.session);assertEquals(1,builds)
        assertEquals(0,interaction.state.value.inputVersion);assertEquals(metric.name,interaction.state.value.name)
        assertEquals(0,countAfterReopen("sync_outbox"))
    }
    @Test fun handoffRejectsDuplicateAndCancelButFinishesOriginalFrozenInput()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Frozen input")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        activeStore.beforeFinalForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.confirm());entered.await()
        assertFalse(interaction.confirm());assertFalse(interaction.cancel())
        assertEquals(G0InteractionSession.Phase.SAVING,interaction.state.value.phase)
        assertTrue(interaction.events.none { it.kind=="state_SAVED_REFRESHING" })
        compose.onNodeWithText("已保存，正在更新显示").assertDoesNotExist()
        draft("Must not replace")
        assertTrue(interaction.state.value.name.isEmpty());assertNull(interaction.state.value.publication)
        assertEquals(1L,interaction.state.value.inputVersion)
        release.complete(Unit);phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        assertEquals("Frozen input",interaction.state.value.publication!!.value.name)
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
        assertEquals("Frozen input",db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun pageDetachAfterHandoffDoesNotPublishOrReplayCommittedWork()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Detached result")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        activeStore.beforeFinalForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.confirm());entered.await();interaction.detach();release.complete(Unit)
        interaction.awaitSettled()
        assertEquals(G0InteractionSession.Phase.CLOSED,interaction.state.value.phase)
        assertNull(interaction.state.value.publication)
        assertFalse(interaction.confirm())
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun authorityChangeRevokesQueuedAndAlreadyIssuedPublication()=runBlocking<Unit> {
        setupInteraction();openReady();val old=interaction.state.value.publication!!
        assertTrue(old.valid());interaction.cancel()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.afterReadForTest={ withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        interaction.open();entered.await()
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        release.complete(Unit);interaction.awaitSettled()
        assertFalse(old.valid());assertEquals(G0InteractionSession.Phase.RECOVERY,interaction.state.value.phase)
        assertNull(interaction.state.value.publication);assertTrue(interaction.state.value.name.isEmpty())
        assertFalse(interaction.confirm());compose.onNodeWithText(metric.name).assertDoesNotExist()
        originalDatabase();assertEquals(0,count("sync_outbox"))
    }
    @Test fun unknownCommitCannotBePresentedAsFailureAndRetried()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Unknown persisted")
        endFaults.failAfterNextEnd.set(true)
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.RECOVERY);interaction.awaitSettled()
        assertNull(interaction.state.value.publication);assertFalse(interaction.confirm())
        assertTrue(interaction.events.none { it.kind=="committed" || it.kind=="state_SAVED_REFRESHING" })
        compose.onNodeWithText("已保存，正在更新显示").assertDoesNotExist()
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
        assertEquals("Unknown persisted",db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun committedWriteWithRefreshFailureCannotBeSavedAgain()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Saved despite refresh")
        adapter.beforeReadForTest={ error("P1_REFRESH_FAILURE") }
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED_REFRESH_FAILED);interaction.awaitSettled()
        assertEquals(1,interaction.events.count { it.kind=="committed" });assertFalse(interaction.confirm())
        compose.onNodeWithText("已保存，显示更新失败，请勿重复保存").assertIsDisplayed()
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun committedWriteKeepsSafeBusyFeedbackWhileRefreshIsPending()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Saved before refresh")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.confirm());entered.await()
        try {
            assertEquals(1,interaction.events.count { it.kind=="committed" })
            assertEquals(G0InteractionSession.Phase.SAVED_REFRESHING,interaction.state.value.phase)
            assertTrue(interaction.state.value.renderable());assertNull(interaction.state.value.publication)
            compose.onNodeWithText("已保存，正在更新显示").assertIsDisplayed()
            compose.onNodeWithText("Saved before refresh").assertDoesNotExist()
            assertFalse(interaction.confirm());assertFalse(interaction.cancel())
        } finally { release.complete(Unit) }
        phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        assertEquals("Saved before refresh",interaction.state.value.publication!!.value.name)
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun ackCommittedThenRefreshCancelledCannotReplayLocalSave()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Saved before ack")
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        adapter.afterReadForTest={ throw CancellationException("P1_ACK_REFRESH_CANCEL") }
        assertTrue(interaction.sync());phase(G0InteractionSession.Phase.SYNC_RECOVERY);interaction.awaitSettled()
        assertFalse(interaction.confirm());assertFalse(interaction.sync())
        assertEquals(1,interaction.events.count { it.kind=="committed" })
        assertNull(interaction.state.value.publication)
        compose.onNodeWithText("已保存，同步结果待恢复，请勿重复保存").assertIsDisplayed()
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"))
        assertEquals(0,count("sync_outbox"));assertEquals("Saved before ack",db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun unchangedSaveSyncsOlderPendingOnlyAfterActualAck()=runBlocking<Unit> {
        register();val original=editMetric("Earlier pending change")
        setupInteraction();openReady()
        assertEquals(1,interaction.state.value.publication!!.value.pending)
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        val captured=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.afterSyncPlanForTest={ captured.complete(Unit);release.await() }
        assertTrue(interaction.sync());captured.await()
        try {
            assertEquals(G0InteractionSession.Phase.SYNCING,interaction.state.value.phase)
            assertFalse(interaction.confirm());assertFalse(interaction.sync())
            assertTrue(interaction.events.none { it.kind=="state_SYNCED" })
            assertTrue(adapter.spans.none { it.stage=="send" || it.stage=="ack" })
        } finally { release.complete(Unit) }
        phase(G0InteractionSession.Phase.SYNCED);interaction.awaitSettled()
        assertEquals(1,adapter.spans.count { it.stage=="ack" && it.completed })
        originalDatabase();assertEquals(0,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
        assertEquals(1,count("next_acceptances"));assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION,original.operationId))
    }
    @Test fun authorityChangeWhileCommittedRefreshIsPendingRevokesReceiptAndLateResult()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Committed before revoke")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.confirm());entered.await()
        assertEquals(G0InteractionSession.Phase.SAVED_REFRESHING,interaction.state.value.phase)
        try {
            tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
            phase(G0InteractionSession.Phase.RECOVERY)
            compose.onNodeWithText("已保存，正在更新显示").assertDoesNotExist()
            compose.onNodeWithText("Committed before revoke").assertDoesNotExist()
        } finally { release.complete(Unit) }
        interaction.awaitSettled()
        assertEquals(G0InteractionSession.Phase.RECOVERY,interaction.state.value.phase)
        assertNull(interaction.state.value.publication);assertFalse(interaction.confirm())
        assertTrue(interaction.events.none { it.kind=="state_SAVED" })
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
        assertEquals("Committed before revoke",db.metricDao().getMetricById(metric.id)!!.name)
    }
    private suspend fun countAfterReopen(table:String):Int { originalDatabase();return count(table) }
}
