package com.dayforge.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InFlightActionsTest {
    @Test fun overlappingSubmissionsAreRejectedRatherThanQueued() = runTest {
        val actions = InFlightActions()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<Int>()
        actions.launch(backgroundScope, 1) { events += 1; release.await(); events += 2 }
        actions.launch(backgroundScope, 1) { fail("Duplicate must not queue") }
        actions.launch(backgroundScope, 1) { fail("Delete must not overlap submission") }
        runCurrent()
        assertEquals(listOf(1), events)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(1, 2), events)
        val deleteRelease = CompletableDeferred<Unit>()
        actions.launch(backgroundScope, 1) { deleteRelease.await() }
        actions.launch(backgroundScope, 1) { fail("Submission must not overlap delete") }
        runCurrent(); deleteRelease.complete(Unit); runCurrent()
        assertTrue(actions.active.value.isEmpty())
    }

    @Test fun synchronousAdmissionPreventsDoubleToggleAndReopensAfterCompletion() = runTest {
        val actions = InFlightActions()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        repeat(20) { actions.launch(backgroundScope, 1) { writes++; release.await() } }
        assertEquals(setOf(1L), actions.active.value)
        runCurrent(); assertEquals(1, writes)
        actions.launch(backgroundScope, 2) { writes++ }
        runCurrent(); assertEquals(2, writes)
        release.complete(Unit); runCurrent(); assertTrue(actions.active.value.isEmpty())
        actions.launch(backgroundScope, 1) { writes++ }; runCurrent(); assertEquals(3, writes)
    }

    @Test fun duplicateSubmissionsDoNotQueueAndCancellationReleasesFeedback() = runTest {
        val actions = InFlightActions()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var writes = 0
        repeat(5) { actions.launch(scope, 1) { release.await(); writes++ } }
        runCurrent(); assertEquals(setOf(1L), actions.active.value)
        release.complete(Unit); runCurrent(); assertEquals(1, writes); assertTrue(actions.active.value.isEmpty())
        actions.launch(scope, 1) { writes++ }; runCurrent(); assertEquals(2, writes)
        actions.launch(scope, 2) { awaitCancellation() }; runCurrent()
        scope.cancel(); runCurrent(); assertTrue(actions.active.value.isEmpty())
        actions.launch(scope, 3) { fail("Cancelled scope cannot write") }
        runCurrent(); assertTrue(actions.active.value.isEmpty())
    }
}
