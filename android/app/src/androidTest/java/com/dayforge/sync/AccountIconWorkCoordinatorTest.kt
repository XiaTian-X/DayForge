package com.dayforge.sync

import android.content.Context
import android.net.Network
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.LocalIconAccess
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AccountIconWorkCoordinatorTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val manager=mockk<WorkManager>(relaxed=true)
    private val access=MutableStateFlow<LocalIconAccess?>(null)
    private val network=MutableStateFlow(NetworkMonitor.Snapshot())
    private val imports=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    private val immediate=mutableListOf<OneTimeWorkRequest>()
    private val periodic=mutableListOf<PeriodicWorkRequest>()
    private val cancelled=mutableListOf<String>()
    private fun id(n: Int)="a9600000-0000-4000-8000-${n.toString(16).padStart(12,'0')}"
    private fun authority(owner: Int = 1, revision: Int = 1) = LocalIconAccess(
        LocalDataSession(AuthenticationSession(id(owner),id(5)),id(2),id(3)),id(4),revision,false)
    @Before fun setup() {
        mockkStatic(WorkManager::class); every { WorkManager.getInstance(context) } returns manager
        every { manager.enqueueUniqueWork(AccountIconWorkCoordinator.ONE_TIME,ExistingWorkPolicy.REPLACE,any<OneTimeWorkRequest>()) } answers {
            immediate.add(thirdArg()); mockk(relaxed=true)
        }
        every { manager.enqueueUniquePeriodicWork(AccountIconWorkCoordinator.PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,any()) } answers {
            periodic.add(thirdArg()); mockk(relaxed=true)
        }
        every { manager.cancelUniqueWork(any()) } answers { cancelled.add(firstArg()); mockk(relaxed=true) }
    }
    @After fun cleanup() { unmockkStatic(WorkManager::class) }

    @Test fun invalidAccessInitializesNoWorkAndValidStartupIsIdempotentAndContainsNoAuthorityOrNetworkConstraint() = runTest {
        val coordinator=AccountIconWorkCoordinator(context,access,network,imports,backgroundScope)
        coordinator.start(); coordinator.start(); runCurrent(); assertTrue(immediate.isEmpty()); assertTrue(periodic.isEmpty())
        assertEquals(listOf(AccountIconWorkCoordinator.ONE_TIME,AccountIconWorkCoordinator.PERIODIC),cancelled)
        access.value=authority(); runCurrent(); assertEquals(1,immediate.size); assertEquals(1,periodic.size)
        val work=immediate.single().workSpec; val safety=periodic.single().workSpec
        assertEquals(AccountIconWorker::class.java.name,work.workerClassName); assertEquals(Data.EMPTY,work.input)
        assertEquals(Constraints.NONE,work.constraints); assertEquals(BackoffPolicy.EXPONENTIAL,work.backoffPolicy)
        assertEquals(30_000L,work.backoffDelayDuration); assertFalse(work.expedited)
        assertEquals(Data.EMPTY,safety.input); assertEquals(Constraints.NONE,safety.constraints)
        assertEquals(1_800_000L,safety.intervalDuration); assertEquals(1_800_000L,safety.initialDelay)
        assertEquals(30_000L,safety.backoffDelayDuration); assertFalse(safety.expedited)
        verify(exactly=0) { manager.enqueueUniqueWork("dayforge-auto-sync",any(),any<OneTimeWorkRequest>()) }
    }

    @Test fun importBurstsDebounceAndLogoutCancelsOnlyOwnedSchedulesWithoutLateWakeup() = runTest {
        access.value=authority(); AccountIconWorkCoordinator(context,access,network,imports,backgroundScope).start(); runCurrent()
        imports.tryEmit(Unit); runCurrent(); advanceTimeBy(1_500); imports.tryEmit(Unit); runCurrent()
        advanceTimeBy(1_500); runCurrent(); assertEquals(1,immediate.size)
        advanceTimeBy(500); runCurrent(); assertEquals(2,immediate.size)
        imports.tryEmit(Unit); runCurrent(); advanceTimeBy(1_000); access.value=null; runCurrent()
        advanceTimeBy(3_000); runCurrent(); assertEquals(2,immediate.size)
        assertEquals(listOf(AccountIconWorkCoordinator.ONE_TIME,AccountIconWorkCoordinator.PERIODIC),cancelled)
        access.value=authority(owner=9); runCurrent(); assertEquals(3,immediate.size); assertEquals(2,periodic.size)
        access.value=authority(owner=9); runCurrent(); assertEquals(3,immediate.size)
    }

    @Test fun readonlyCapabilityChangeSchedulesFreshExecutionWithoutCarryingOldSnapshotInWorkData() = runTest {
        access.value=authority(); AccountIconWorkCoordinator(context,access,network,imports,backgroundScope).start(); runCurrent()
        access.value=authority(revision=2); runCurrent(); assertEquals(2,immediate.size)
        assertTrue(immediate.all { it.workSpec.input==Data.EMPTY }); assertEquals(2,periodic.size)
        imports.tryEmit(Unit); runCurrent(); advanceTimeBy(2_000); runCurrent(); assertEquals(3,immediate.size)
    }

    private fun path(id: Int, blocked: Boolean = false): NetworkMonitor.Path {
        val parcel=Parcel.obtain()
        return try {
            parcel.writeInt(id); parcel.setDataPosition(0)
            val value=Network.CREATOR.createFromParcel(parcel); assertEquals(0,parcel.dataAvail())
            NetworkMonitor.Path(value,local=true,blocked=blocked)
        } finally { parcel.recycle() }
    }

    @Test fun localOnlyNetworkBurstsDebounceLossCancelsWakeAndRemainingPathAndUnknownObservationStillWake() = runTest {
        access.value=authority(); AccountIconWorkCoordinator(context,access,network,imports,backgroundScope).start(); runCurrent()
        val lan=path(901); val other=path(902)
        network.value=NetworkMonitor.Snapshot(listOf(lan),revision=1); runCurrent(); advanceTimeBy(1_000)
        network.value=NetworkMonitor.Snapshot(listOf(lan,other),revision=2); runCurrent(); advanceTimeBy(1_000)
        network.value=NetworkMonitor.Snapshot(revision=3); runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertEquals(1,immediate.size)
        network.value=NetworkMonitor.Snapshot(listOf(lan,other),revision=4); runCurrent(); advanceTimeBy(1_000)
        network.value=NetworkMonitor.Snapshot(listOf(other),revision=5); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(2,immediate.size)
        network.value=NetworkMonitor.Snapshot(listOf(path(902,blocked=true)),revision=6); runCurrent()
        advanceTimeBy(2_000); runCurrent(); assertEquals(2,immediate.size)
        network.value=NetworkMonitor.Snapshot(monitoring=false,revision=7); runCurrent()
        advanceTimeBy(2_000); runCurrent(); assertEquals(3,immediate.size)
        assertTrue(immediate.all { it.workSpec.constraints == Constraints.NONE })
    }

    @Test fun failedAuthorityObservationIsFiniteVisibleAndCancelsOnlyOwnedWorkWithoutCrashing() = runTest {
        val broken=flow<LocalIconAccess?> { emit(authority()); throw IllegalStateException("test observation failure") }
        val coordinator=AccountIconWorkCoordinator(context,broken,network,imports,backgroundScope)
        coordinator.start(); runCurrent()
        assertEquals(AccountIconWorkCoordinator.Status.FAILED,coordinator.status.value)
        assertEquals(listOf(AccountIconWorkCoordinator.ONE_TIME,AccountIconWorkCoordinator.PERIODIC),cancelled)
        val count=immediate.size; advanceTimeBy(60_000); runCurrent(); assertEquals(count,immediate.size)
        verify(exactly=0) { manager.cancelUniqueWork("dayforge-auto-sync") }
    }

    @Test fun enqueueFailureIsFiniteVisibleAndExplicitRestartCanResumeWithoutDiscardingAuthority() = runTest {
        access.value=authority()
        every { manager.enqueueUniqueWork(AccountIconWorkCoordinator.ONE_TIME,ExistingWorkPolicy.REPLACE,any<OneTimeWorkRequest>()) } throws IllegalStateException("test scheduler failure")
        val coordinator=AccountIconWorkCoordinator(context,access,network,imports,backgroundScope)
        coordinator.start(); runCurrent(); assertEquals(AccountIconWorkCoordinator.Status.FAILED,coordinator.status.value)
        assertEquals(listOf(AccountIconWorkCoordinator.ONE_TIME,AccountIconWorkCoordinator.PERIODIC),cancelled)
        every { manager.enqueueUniqueWork(AccountIconWorkCoordinator.ONE_TIME,ExistingWorkPolicy.REPLACE,any<OneTimeWorkRequest>()) } answers {
            immediate.add(thirdArg()); mockk(relaxed=true)
        }
        coordinator.start(); runCurrent(); assertEquals(AccountIconWorkCoordinator.Status.OBSERVING,coordinator.status.value)
        assertEquals(1,immediate.size); assertEquals(authority(),access.value)
        imports.tryEmit(Unit); runCurrent(); advanceTimeBy(2_000); runCurrent(); assertEquals(2,immediate.size)
    }
}
