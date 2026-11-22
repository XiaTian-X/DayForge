package com.dayforge.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.model.NextSyncProblem
import com.dayforge.data.model.SyncProblemFrame
import com.dayforge.data.model.SyncProblems
import com.dayforge.domain.service.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Memory publication/workflow gates only. The original SyncManager is mocked, not spied.
 * These do NOT pretend to be physical Host/native recovery tests or authorize a receipt. */
@RunWith(AndroidJUnit4::class)
class SettingsRecoverySourceWorkflowTest {
    private val graph = recoveryUiGraph()
    private val ticket = recoveryUiTicket()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private class MemorySource {
        private val monitor = Any()
        private var live = true
        fun revoke() = synchronized(monitor) { live = false }
        private fun publication() = DatabaseEpochPublication(Any(),
            { synchronized(monitor) { live } },
            { assign -> synchronized(monitor) { if (!live) false else { assign(); true } } },
            { assign -> synchronized(monitor) { if (!live) false else assign() } })
        fun frame(value: SyncProblems) = SyncProblemFrame(value, publication(), publication())
    }
    private fun source(gate: MemorySource) = gate.frame(SyncProblems.Next(listOf(NextSyncProblem(
        graph.root.kind, graph.root.requestId, graph.affected.first().entityType, graph.affected.first().entityUuid, "blocked"))))
    private fun sync(frame: SyncProblemFrame) = mockk<SyncManager>(relaxed = true).also { sync ->
        coEvery { sync.readRecoveryPreview(frame, graph.root) } returns graph
        coEvery { sync.prepareRecoveryConfirmation(frame, graph, graph.affected.map { it.key }.toSet()) } returns ticket
        coEvery { sync.confirmRecovery(frame, ticket) } returns recoveryUiProof()
    }
    private suspend fun fixture(sync: SyncManager, test: suspend (SettingsRecoveryWorkflow, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { withTimeout(10_000) { test(SettingsRecoveryWorkflow(sync, scope, requireOriginalSource = true), scope) } }
        finally { releases.forEach { it.complete(Unit) }; scope.cancel(); withTimeout(5_000) { scope.coroutineContext.job.join() } }
    }
    private fun gate() = CompletableDeferred<Unit>().also { releases += it }
    private suspend fun settle(scope: CoroutineScope) { scope.coroutineContext.job.children.toList().joinAll() }
    private suspend fun review(workflow: SettingsRecoveryWorkflow, frame: SyncProblemFrame): RecoveryUiFrame {
        workflow.previewFrom(frame, graph.root)
        return workflow.frames.first { it.value is RecoveryUiState.Review }
    }
    private fun approve(workflow: SettingsRecoveryWorkflow, frame: RecoveryUiFrame): RecoveryUiFrame {
        workflow.approveFrom(frame, true)
        return workflow.frames.value.also { assertTrue((it.value as RecoveryUiState.Review).approved) }
    }

    @Test fun hostModeRefusesEveryLegacyNoFrameActionAndUsesOriginalOverloads() = runBlocking {
        val original = source(MemorySource()); val sync = sync(original)
        fixture(sync) { workflow, scope ->
            val actions = listOf<() -> Unit>({ workflow.preview(graph.root) }, { workflow.refresh() },
                { workflow.approve(true) }, { workflow.confirm() }, { workflow.dismiss() })
            actions.forEach { call ->
                val error = runCatching(call).exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertEquals("SYNC_RECOVERY_ORIGINAL_INPUT_REQUIRED", error?.message)
            }
            assertEquals(RecoveryUiState.Hidden, workflow.state.value)
            val ready = approve(workflow, review(workflow, original))
            workflow.confirmFrom(ready); settle(scope)
            assertTrue(workflow.frames.value.value is RecoveryUiState.Completed)
            coVerify(exactly = 1) { sync.readRecoveryPreview(original, graph.root) }
            coVerify(exactly = 1) { sync.prepareRecoveryConfirmation(original, graph, any()) }
            coVerify(exactly = 1) { sync.confirmRecovery(original, ticket) }
            coVerify(exactly = 0) { sync.readRecoveryPreview(any()) }
            coVerify(exactly = 0) { sync.prepareRecoveryConfirmation(any(), any()) }
            coVerify(exactly = 0) { sync.confirmRecovery(any()) }
        }
    }

    @Test fun oldApprovedDialogCannotRefreshApproveConfirmOrDismissNewSameSourceRequest() = runBlocking {
        val original = source(MemorySource()); val sync = sync(original)
        fixture(sync) { workflow, scope ->
            val a = approve(workflow, review(workflow, original))
            workflow.dismissFrom(a)
            val b = approve(workflow, review(workflow, original)); assertNotSame(a, b)
            workflow.refreshFrom(a); workflow.approveFrom(a, false); workflow.confirmFrom(a); workflow.dismissFrom(a)
            settle(scope); assertSame(b, workflow.frames.value)
            assertTrue((b.value as RecoveryUiState.Review).approved)
            coVerify(exactly = 2) { sync.readRecoveryPreview(original, graph.root) }
            coVerify(exactly = 0) { sync.confirmRecovery(original, ticket) }
            workflow.confirmFrom(b); settle(scope)
            assertTrue(workflow.frames.value.value is RecoveryUiState.Completed)
            coVerify(exactly = 1) { sync.confirmRecovery(original, ticket) }
        }
    }

    @Test fun revokedSourceCannotPrepareOrPublishItsLatePreviewOrBeRecapturedByRefresh() = runBlocking {
        val sourceGate = MemorySource(); val original = source(sourceGate); val sync = sync(original)
        val entered = CompletableDeferred<Unit>(); val release = gate()
        coEvery { sync.readRecoveryPreview(original, graph.root) } coAnswers {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; graph
        }
        fixture(sync) { workflow, scope ->
            workflow.previewFrom(original, graph.root); entered.await()
            val oldLoading = workflow.frames.value
            sourceGate.revoke(); workflow.sourceTransition(false)
            assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            release.complete(Unit); settle(scope)
            workflow.refreshFrom(oldLoading); workflow.previewFrom(original, graph.root); settle(scope)
            assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            coVerify(exactly = 1) { sync.readRecoveryPreview(original, graph.root) }
            coVerify(exactly = 0) { sync.prepareRecoveryConfirmation(original, any(), any()) }
            coVerify(exactly = 0) { sync.confirmRecovery(original, any()) }
        }
    }

    @Test fun sourceRevocationDuringAcceptedCommitClearsPrivateStateWithoutCancellingTheCommit() = runBlocking {
        val sourceGate = MemorySource(); val original = source(sourceGate); val sync = sync(original)
        val entered = CompletableDeferred<Unit>(); val release = gate(); var completed = false
        coEvery { sync.confirmRecovery(original, ticket) } coAnswers {
            entered.complete(Unit); release.await(); completed = true; recoveryUiProof()
        }
        fixture(sync) { workflow, scope ->
            workflow.confirmFrom(approve(workflow, review(workflow, original))); entered.await()
            val oldCommitting = workflow.frames.value
            sourceGate.revoke(); workflow.sourceTransition(false)
            assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            assertTrue(scope.coroutineContext.job.children.any { it.isActive })
            workflow.dismissFrom(oldCommitting)
            release.complete(Unit); settle(scope)
            assertTrue(completed); assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            coVerify(exactly = 1) { sync.confirmRecovery(original, ticket) }
        }
    }

    @Test fun unknownResultRetainsTheSameReceiptAndOriginalSourceAcrossOwnInvalidation() = runBlocking {
        val original = source(MemorySource()); val sync = sync(original)
        fixture(sync) { workflow, scope ->
            val ready = approve(workflow, review(workflow, original))
            coEvery { sync.confirmRecovery(original, ticket) } throws IllegalStateException("lost result")
            workflow.confirmFrom(ready); settle(scope)
            val retry = workflow.frames.value
            assertTrue(retry.value is RecoveryUiState.RetryConfirmation); assertSame(original, retry.source)
            workflow.databaseChanged(); assertSame(retry, workflow.frames.value)
            coEvery { sync.confirmRecovery(original, ticket) } returns recoveryUiProof()
            workflow.confirmFrom(retry); settle(scope)
            assertTrue(workflow.frames.value.value is RecoveryUiState.Completed)
            coVerify(exactly = 1) { sync.prepareRecoveryConfirmation(original, graph, any()) }
            coVerify(exactly = 2) { sync.confirmRecovery(original, ticket) }
        }
    }

    @Test fun cancellationKeepsSameSourceReceiptWhileClosingRejectsLateCompletion() = runBlocking {
        val original = source(MemorySource()); val sync = sync(original)
        fixture(sync) { workflow, scope ->
            coEvery { sync.confirmRecovery(original, ticket) } throws CancellationException("after possible END")
            workflow.confirmFrom(approve(workflow, review(workflow, original))); settle(scope)
            val retry = workflow.frames.value
            assertTrue(retry.value is RecoveryUiState.RetryConfirmation); assertSame(original, retry.source)
            val entered = CompletableDeferred<Unit>(); val release = gate(); var completed = false
            coEvery { sync.confirmRecovery(original, ticket) } coAnswers {
                entered.complete(Unit); release.await(); completed = true; recoveryUiProof()
            }
            workflow.confirmFrom(retry); entered.await()
            workflow.dismissFrom(workflow.frames.value)
            assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            release.complete(Unit); settle(scope)
            assertTrue(completed); assertEquals(RecoveryUiState.Hidden, workflow.frames.value.value)
            coVerify(exactly = 2) { sync.confirmRecovery(original, ticket) }
            coVerify(exactly = 1) { sync.prepareRecoveryConfirmation(original, graph, any()) }
        }
    }
}
