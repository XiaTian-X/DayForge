package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountNodeGcRecoveryTest : CountNodeGcFixture() {
    @Test fun frozenFenceAndActiveStageLeaveEveryRootNodeAndCommandByteUntouched() = runBlocking<Unit> {
        ready(); orphan(300)
        retainedCommandResult()
        val access = access()
        val fence = NextReplicaFenceEntity(id(701), id(1), id(2), id(3), id(704), access.session.authentication.generation,
            requireNotNull(access.deviceId), requireNotNull(access.capabilityRevision).toLong(), "a".repeat(64))
        NextReplicaWriteFence(db).acquire(fence)
        var before = nativeAssertions().capture()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, nextSlice(collector()).status)
        nativeAssertions().assertUnchanged(before)
        NextReplicaWriteFence(db).closeBeforeCommit(fence)
        val stage = tokens.replicaStageFixture()
        NextReplicaStageStore(db, tokens, sessions).begin(stage)
        before = nativeAssertions().capture()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, nextSlice(collector()).status)
        nativeAssertions().assertUnchanged(before); assertEquals(stage, tokens.activeReplicaStage())
    }

    @Test fun explicitPreviewRetirementDoesNotBecomePermissionToSweepDuringNetworkGap() = runBlocking<Unit> {
        ready(); orphan(301)
        retainedCommandResult()
        assertTrue(CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(access()))
        val before = nativeAssertions().capture()
        assertTrue(before.getValue("count_trust_nodes").isEmpty()); assertFalse(before.getValue("count_command_results").isEmpty())
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, nextSlice(collector()).status)
        nativeAssertions().assertUnchanged(before)
    }

    @Test fun sameAccountReauthenticationInvalidatesOldCycleInsteadOfReusingAuthorization() = runBlocking<Unit> {
        ready(); orphan(302)
        val gc = collector(); assertEquals(CountTrustNodeCollector.Status.PROGRESS, nextSlice(gc).status)
        tokens.saveLoginSession("new-access", "new-refresh", "member", id(1), false); register()
        val before = nativeAssertions().capture()
        assertEquals(CountTrustNodeCollector.Status.RESTARTED, nextSlice(gc).status)
        nativeAssertions().assertUnchanged(before)
        finish(gc)
    }

    @Test fun reopeningOwnerDiscardsPartialMarkAndKeepsEveryLiveNativeValue() = runBlocking<Unit> {
        ready(); orphan(303)
        val before = nativeAssertions().capture(); val live = reachable(); val old = collector()
        repeat(3) { assertEquals(CountTrustNodeCollector.Status.PROGRESS, nextSlice(old).status) }
        storage.reopen()
        val coldOwner = owner; val fresh = collector()
        assertEquals(0L, coldOwner.generation); assertFalse(coldOwner.isRetired)
        assertFalse(fresh.requested)
        val idle = fresh.collectSlice()
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, idle.status)
        assertEquals("NO_OPEN_WORK", idle.reason); assertEquals(0, idle.deletedNodes)
        // No native wrap/open occurred just to run optional GC; the new factory is still cold.
        assertEquals(0L, coldOwner.generation); assertFalse(fresh.requested)
        // An explicit actual Room read opens it and proves every durable native value survived.
        nativeAssertions().assertUnchanged(before)
        assertSame(coldOwner, owner); assertTrue(coldOwner.generation > 0L); assertTrue(fresh.requested)
        // A new owner starts from durable roots; no cursor/bitset is serialized or transferred.
        finish(fresh); assertOnlyUnreachableRemoved(before, live)
        val after = nativeAssertions().capture()
        val stale = nextSlice(old)
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, stale.status)
        assertEquals("NO_OPEN_WORK", stale.reason); assertEquals(0, stale.deletedNodes)
        nativeAssertions().assertUnchanged(after)
        // This is connection reopen, deliberately NOT advertised as process death evidence.
    }
}
