package com.dayforge.data.repository

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.NextSyncPushResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0BackgroundPublicationTest : G0InteractionFixture() {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    override fun installScreen() { compose.setContent { G0InteractionScreen(interaction,observation) } }
    private suspend fun openPending():NextCoreDelivery<NextSyncPushResponse> {
        register();val pending=editMetric("Pending history")
        val (http,_)=channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),pending.operationId))
        setupInteraction();openReady()
        return delivery
    }
    @Test fun idleBackgroundAckRefreshesPendingWithoutLosingDraftOrRebuilding()=runBlocking<Unit> {
        val delivery=openPending();draft("Uncommitted user draft")
        val old=interaction.state.value
        assertEquals(1,old.publication!!.value.pending)
        assertEquals(NextOperationAcceptance.COMMITTED,withContext(Dispatchers.IO) { activeStore.acknowledge(delivery) })
        assertFalse(old.renderable())
        val fresh=withTimeout(2_000) { interaction.state.first {
            it.version>old.version && it.phase==G0InteractionSession.Phase.EDITING && it.renderable() && it.publication?.value?.pending==0
        } }
        interaction.awaitSettled()
        assertEquals(old.name,fresh.name);assertEquals(old.description,fresh.description)
        assertEquals(old.inputVersion,fresh.inputVersion);assertEquals(1,builds)
        assertEquals("Pending history",fresh.publication!!.value.name)
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        assertEquals("Uncommitted user draft",interaction.state.value.publication!!.value.name)
        originalDatabase();assertEquals(2,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
    }
    @Test fun changedBusinessContentCannotSilentlyReplaceDraftBaseline()=runBlocking<Unit> {
        openPending();draft("User draft")
        val old=interaction.state.value
        withContext(Dispatchers.IO) { requireNotNull(activeStore.edit("Other committed content","",id(2401))) }
        assertFalse(old.renderable())
        withTimeout(2_000) { interaction.state.first { it.phase==G0InteractionSession.Phase.RECOVERY } }
        interaction.awaitSettled()
        assertFalse(interaction.confirm());assertNull(interaction.state.value.publication)
        originalDatabase();assertEquals("Other committed content",db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(2,count("next_request_origins"));assertEquals(2,count("sync_outbox"))
    }
    @Test fun cancelBackgroundRefreshCannotPublishLateDraft()=runBlocking<Unit> {
        val delivery=openPending();draft("Cancelled background draft")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertEquals(NextOperationAcceptance.COMMITTED,withContext(Dispatchers.IO) { activeStore.acknowledge(delivery) })
        withTimeout(2_000) { entered.await() }
        assertEquals(G0InteractionSession.Phase.REFRESHING,interaction.state.value.phase)
        try { assertTrue(interaction.cancel()) } finally { release.complete(Unit) }
        interaction.awaitSettled()
        assertEquals(G0InteractionSession.Phase.CANCELLED,interaction.state.value.phase)
        assertNull(interaction.state.value.publication)
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun authorityChangeDuringBackgroundRefreshCannotRepublishOldAccount()=runBlocking<Unit> {
        val delivery=openPending();draft("Revoked background draft")
        val held=interaction.state.value
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertEquals(NextOperationAcceptance.COMMITTED,withContext(Dispatchers.IO) { activeStore.acknowledge(delivery) })
        withTimeout(2_000) { entered.await() }
        try { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        finally { release.complete(Unit) }
        phase(G0InteractionSession.Phase.RECOVERY);interaction.awaitSettled()
        assertFalse(held.renderable());assertFalse(interaction.confirm());assertNull(interaction.state.value.publication)
        originalDatabase();assertEquals(1,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
    }

}
