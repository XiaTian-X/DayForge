package com.dayforge.data.repository

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Held immutable UI states must fail the same draw-time fence as current personal content. */
@RunWith(AndroidJUnit4::class)
class G0ReceiptPresentationTest : G0InteractionFixture() {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    override fun installScreen() { compose.setContent { G0InteractionScreen(interaction,observation) } }
    @Test fun heldReceiptIsNotRenderableAfterPageDetach()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Detached receipt")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.confirm());entered.await()
        val held=interaction.state.value
        assertEquals(G0InteractionSession.Phase.SAVED_REFRESHING,held.phase)
        assertTrue(held.renderable())
        try {
            interaction.detach()
            assertFalse("G0_HELD_RECEIPT_AFTER_DETACH",held.renderable())
        } finally { release.complete(Unit);interaction.awaitSettled() }
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun heldReceiptIsRevokedAtAuthorityReservationBeforePersistence()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Revoked receipt")
        val entered=CompletableDeferred<Unit>();val releaseRead=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);releaseRead.await() }
        assertTrue(interaction.confirm());entered.await()
        val held=interaction.state.value
        assertEquals(G0InteractionSession.Phase.SAVED_REFRESHING,held.phase)
        assertTrue(held.renderable())
        val reserved=CompletableDeferred<Unit>();val releaseAuthority=CompletableDeferred<Unit>()
        authority.onTransitionForTest={ reserved.complete(Unit);releaseAuthority.await() }
        val mutation=async(Dispatchers.IO) { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try {
            reserved.await()
            assertFalse("G0_HELD_RECEIPT_DURING_AUTHORITY_TRANSITION",held.renderable())
        } finally {
            releaseAuthority.complete(Unit);mutation.await();releaseRead.complete(Unit)
            interaction.awaitSettled()
        }
        assertTrue(interaction.events.none { it.kind=="state_SAVED" })
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun failedRefreshRetainsReceiptRevocationFence()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Failed refresh receipt")
        adapter.beforeReadForTest={ error("G0_RECEIPT_REFRESH_FAILURE") }
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED_REFRESH_FAILED)
        interaction.awaitSettled()
        val held=interaction.state.value
        assertTrue(held.renderable())
        assertRevokedDuringReservation(held)
        originalDatabase();assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
    }
    @Test fun syncingReceiptRetainsFenceAfterAckCommit()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Syncing receipt")
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        assertTrue(interaction.sync());entered.await()
        val held=interaction.state.value
        assertEquals(G0InteractionSession.Phase.SYNCING,held.phase)
        assertTrue(held.renderable())
        try { assertRevokedDuringReservation(held) }
        finally { release.complete(Unit);interaction.awaitSettled() }
        assertTrue(interaction.events.none { it.kind=="state_SYNCED" })
        originalDatabase();assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun failedSyncRefreshRetainsReceiptRevocationFence()=runBlocking<Unit> {
        setupInteraction();openReady();draft("Failed sync refresh")
        assertTrue(interaction.confirm());phase(G0InteractionSession.Phase.SAVED);interaction.awaitSettled()
        adapter.beforeReadForTest={ error("G0_RECEIPT_SYNC_REFRESH_FAILURE") }
        assertTrue(interaction.sync());phase(G0InteractionSession.Phase.SYNC_RECOVERY);interaction.awaitSettled()
        val held=interaction.state.value
        assertTrue(held.renderable())
        assertRevokedDuringReservation(held)
        originalDatabase();assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    private suspend fun assertRevokedDuringReservation(held:G0InteractionSession.State)=coroutineScope {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        authority.onTransitionForTest={ entered.complete(Unit);release.await() }
        val mutation=async(Dispatchers.IO) { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try {
            entered.await()
            assertFalse("G0_RECEIPT_FENCE_LOST_ON_PHASE_CHANGE",held.renderable())
        } finally { release.complete(Unit);mutation.await() }
    }

}
