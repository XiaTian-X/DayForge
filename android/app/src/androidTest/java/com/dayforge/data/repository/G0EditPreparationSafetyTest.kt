package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Full finite preparation gate; existing Interaction/BackgroundPublication classes remain adjacent. */
@RunWith(AndroidJUnit4::class)
class G0EditPreparationSafetyTest : G0EditPreparationFixture() {
    @Test fun consecutiveRestoredEditorsQualifyFreshNodesAndDispatchOneTextActionEach()=runBlocking<Unit> {
        val seed=seedEdits(0)
        val frameCodes=mutableSetOf<Long>()
        val oldHarnesses=mutableListOf<Harness>()
        repeat(3) {
            val h=restoredHarness(seed);val native=pageNativeState()
            val (_,seen)=h.openEditable()
            assertTrue("Each restored editor owns a distinct actual marker",frameCodes.add(h.observation.encodedVersion(seen.version)))
            for(old in oldHarnesses) {
                assertEquals(G0InteractionSession.Phase.CLOSED,old.session.state.value.phase)
                assertFalse(old.owner.coroutineContext.job.isActive)
                assertTrue(old.port.calls.isEmpty())
            }
            val before=h.session.state.value
            val completed=h.typeName()
            val typed=h.session.state.value
            assertEquals(before.session,typed.session);assertEquals(before.inputVersion+1,typed.inputVersion)
            assertEquals(editedName,typed.name);assertEquals(baseDescription,typed.description)
            val diagnostic=h.inputDriverEvents.single()
            assertTrue(diagnostic.refreshChecked && diagnostic.refreshed && diagnostic.setTextAdvertised)
            assertTrue(diagnostic.enabled && diagnostic.editable && diagnostic.visible)
            assertEquals(true,diagnostic.accepted)
            assertEquals(completed,requireNotNull(diagnostic.callbackObservedAt))
            assertTrue(requireNotNull(diagnostic.qualifiedAt)>=diagnostic.lookupStarted)
            assertTrue(requireNotNull(diagnostic.dispatchStarted)>=requireNotNull(diagnostic.qualifiedAt))
            assertTrue(requireNotNull(diagnostic.dispatchReturned)>=requireNotNull(diagnostic.dispatchStarted))
            assertTrue(completed>=requireNotNull(diagnostic.dispatchReturned))
            assertEquals(before.inputVersion,diagnostic.before.inputVersion)
            assertEquals(typed.inputVersion,diagnostic.after.inputVersion)
            h.observation.tap("cancel");h.phase(G0InteractionSession.Phase.CANCELLED);h.session.awaitSettled()
            assertTrue(h.port.calls.isEmpty());assertFalse(h.session.events.any { it.kind=="confirm" || it.kind=="committed" })
            original(h);assertEquals(native,pageNativeState());oldHarnesses+=h
        }
        assertEquals(3,frameCodes.size)
    }

    @Test fun coldMissCannotQueueConfirmAndCancelCannotPublishLate()=runBlocking<Unit> {
        val seed=seedEdits(0);val h=restoredHarness(seed);val native=pageNativeState()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.beforeInitialization={ entered.complete(Unit);release.await() }
        try {
            h.observation.tap("open");withTimeout(5_000) { entered.await() }
            h.pixels(G0InteractionSession.Phase.LOADING)
            h.observation.tap("save")
            withContext(Dispatchers.Main) { assertFalse(h.session.confirm()) }
            assertTrue(h.decisions.isEmpty());assertTrue(h.port.calls.isEmpty())
            h.observation.tap("cancel");h.phase(G0InteractionSession.Phase.CANCELLED)
        } finally { release.complete(Unit) }
        h.session.awaitSettled()
        assertEquals(G0InteractionSession.Phase.CANCELLED,h.session.state.value.phase)
        assertNull(h.session.state.value.publication);assertTrue(h.port.calls.isEmpty())
        assertFalse(h.session.events.any { it.kind=="state_EDITING" || it.kind=="committed" })
        assertEquals(0,h.builds.get());original(h);assertEquals(native,pageNativeState())
    }

    @Test fun cancelledCompletedReadCannotPublishPrivateDraftOrWrite()=runBlocking<Unit> {
        val seed=seedEdits(0);val h=restoredHarness(seed);val native=pageNativeState()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.adapter.afterReadForTest={ withContext(NonCancellable) { entered.complete(Unit);release.await() } }
        try {
            h.observation.tap("open");withTimeout(20_000) { entered.await() }
            assertEquals(1,h.adapter.spans.count { it.stage=="read" && it.completed })
            h.pixels(G0InteractionSession.Phase.LOADING)
            h.observation.tap("cancel");h.phase(G0InteractionSession.Phase.CANCELLED)
        } finally { release.complete(Unit) }
        h.session.awaitSettled()
        assertEquals(G0InteractionSession.Phase.CANCELLED,h.session.state.value.phase)
        assertNull(h.session.state.value.publication)
        assertFalse(h.session.events.any { it.kind=="state_EDITING" || it.kind=="committed" })
        assertTrue(h.port.calls.isEmpty());assertEquals(1,h.builds.get())
        original(h);assertEquals(native,pageNativeState())
    }

    @Test fun realDraftAndDwellDoNotPrepareAWriteAndConfirmKeepsItsInput()=runBlocking<Unit> {
        val seed=seedEdits(0);val h=restoredHarness(seed)
        h.openEditable();h.session.awaitSettled()
        val native=pageNativeState();val reads=h.adapter.spans.count { it.stage=="read" }
        val inputAt=h.typeName();delay(300)
        assertTrue(now()-inputAt>=300_000_000L)
        assertEquals(native,pageNativeState());assertEquals(reads,h.adapter.spans.count { it.stage=="read" })
        assertEquals(0,h.trace.count("warm_edit_compute"));assertTrue(h.port.calls.isEmpty())
        h.observation.tap("save");h.phase(G0InteractionSession.Phase.SAVED);h.session.awaitSettled()
        assertEquals(listOf(true),h.decisions.map { it.accepted })
        assertEquals(editedName,h.port.calls.single().name)
        assertEquals(baseDescription,h.port.calls.single().description)
        assertTrue(h.trace.count("warm_edit_compute")>=1)
        assertTrue(h.trace.count("decrypt_access")>=2)
        syncAndVerify(h,ackBeforeConfirm=false)
    }

    @Test fun realAckInvalidatesDraftWithoutQueueThenExplicitFreshConfirmSucceeds()=runBlocking<Unit> {
        val seed=seedEdits(0);val h=restoredHarness(seed)
        h.openEditable();h.typeName()
        val held=h.session.state.value
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.adapter.beforeReadForTest={ entered.complete(Unit);release.await() }
        try {
            h.acceptOld();assertFalse(held.renderable());withTimeout(5_000) { entered.await() }
            h.pixels(G0InteractionSession.Phase.REFRESHING)
            h.observation.tap("save")
            withContext(Dispatchers.Main) { assertFalse(h.session.confirm()) }
            assertTrue("Disabled touch is not a handler refusal",h.decisions.isEmpty())
            assertTrue(h.port.calls.isEmpty());assertFalse(h.session.events.any { it.kind=="confirm" })
        } finally { release.complete(Unit) }
        val fresh=withTimeout(20_000) { h.session.state.first {
            it.phase==G0InteractionSession.Phase.EDITING && it.renderable() && it.publication?.value?.pending==0
        } }
        assertEquals(held.name,fresh.name);assertEquals(held.description,fresh.description)
        assertEquals(held.inputVersion,fresh.inputVersion);assertEquals(1,h.builds.get())
        h.pixels(G0InteractionSession.Phase.EDITING);assertTrue(h.port.calls.isEmpty())
        h.observation.tap("save");h.phase(G0InteractionSession.Phase.SAVED);h.session.awaitSettled()
        assertEquals(listOf(true),h.decisions.map { it.accepted })
        syncAndVerify(h,ackBeforeConfirm=true)
    }
}
