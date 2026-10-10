package com.dayforge.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.service.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRecoveryWorkflowTest {
    private val graph = recoveryUiGraph()
    private val ticket = recoveryUiTicket()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private val sync = mockk<SyncManager>(relaxed = true).also {
        coEvery { it.readRecoveryPreview(graph.root) } returns graph
        coEvery { it.prepareRecoveryConfirmation(graph, graph.affected.map { node -> node.key }.toSet()) } returns ticket
        coEvery { it.confirmRecovery(ticket) } returns recoveryUiProof()
    }
    private suspend fun fixture(test: suspend (SettingsRecoveryWorkflow, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { withTimeout(10_000) { test(SettingsRecoveryWorkflow(sync, scope), scope) } }
        finally { gates.forEach { it.complete(Unit) }; scope.cancel(); withTimeout(5_000) { scope.coroutineContext.job.join() } }
    }
    private suspend fun settle(scope: CoroutineScope) { scope.coroutineContext.job.children.toList().forEach { it.join() } }
    private suspend fun review(workflow: SettingsRecoveryWorkflow): RecoveryUiState.Review {
        workflow.preview(graph.root)
        return workflow.state.first { it is RecoveryUiState.Review } as RecoveryUiState.Review
    }
    @Test fun previewDescribesExactChangesAndWholeSelectionWithoutAutomaticallyConfirming() = runBlocking {
        fixture { workflow, scope ->
            val view = review(workflow)
            assertFalse(view.approved); assertFalse(view.data.preparationFailed)
            assertEquals(listOf(RecoveryFieldChange("name", "Current name", "Accepted name")), view.data.changes.single().fields)
            assertEquals(1, view.data.contractVersion); assertEquals("a".repeat(12), view.data.checkpoint)
            workflow.confirm(); settle(scope)
            coVerify(exactly = 0) { sync.confirmRecovery(any()) }
            workflow.approve(true); workflow.confirm()
            val done = workflow.state.first { it is RecoveryUiState.Completed } as RecoveryUiState.Completed
            assertEquals(2, done.count)
            coVerify(exactly = 1) { sync.confirmRecovery(ticket) }
            coVerify(exactly = 0) { sync.sync(any()) }
        }
    }
    @Test fun protectedGroupCannotPrepareApproveOrConfirm() = runBlocking {
        fixture { workflow, _ ->
            val protected = recoveryUiGraph(protected = true)
            coEvery { sync.readRecoveryPreview(graph.root) } returns protected
            val view = review(workflow)
            assertFalse(view.data.graph.canConfirm); assertTrue(view.data.changes.isEmpty())
            workflow.approve(true); workflow.confirm()
            assertFalse((workflow.state.value as RecoveryUiState.Review).approved)
            coVerify(exactly = 0) { sync.prepareRecoveryConfirmation(any(), any()) }
            coVerify(exactly = 0) { sync.confirmRecovery(any()) }
        }
    }
    @Test fun failedProjectionPreparationIsNotAConfirmableGraphOrRawExceptionLeak() = runBlocking {
        fixture { workflow, _ ->
            coEvery { sync.prepareRecoveryConfirmation(any(), any()) } throws IllegalStateException("private row contents")
            val view = review(workflow)
            assertTrue(view.data.preparationFailed); assertTrue(view.data.changes.isEmpty())
            workflow.approve(true); workflow.confirm()
            assertFalse((workflow.state.value as RecoveryUiState.Review).approved)
            coVerify(exactly = 0) { sync.confirmRecovery(any()) }
        }
    }
    @Test fun databaseChangeInvalidatesApprovalAndCannotUseOldReceipt() = runBlocking {
        fixture { workflow, scope ->
            review(workflow); workflow.approve(true); workflow.databaseChanged()
            assertEquals(RecoveryUiState.Stale, workflow.state.value)
            workflow.confirm(); settle(scope)
            coVerify(exactly = 0) { sync.confirmRecovery(any()) }
            workflow.refresh(); workflow.state.first { it is RecoveryUiState.Review }
            assertFalse((workflow.state.value as RecoveryUiState.Review).approved)
        }
    }
    @Test fun latePreviewAfterAccountTransitionCannotPrepareOrPublishPriorData() = runBlocking {
        fixture { workflow, scope ->
            val started = CompletableDeferred<Unit>(); val release = gate()
            coEvery { sync.readRecoveryPreview(any()) } coAnswers {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; graph
            }
            workflow.preview(graph.root); started.await()
            workflow.authenticationTransition(true)
            assertEquals(RecoveryUiState.Hidden, workflow.state.value)
            workflow.authenticationTransition(false); release.complete(Unit); settle(scope)
            assertEquals(RecoveryUiState.Hidden, workflow.state.value)
            coVerify(exactly = 0) { sync.prepareRecoveryConfirmation(any(), any()) }
        }
    }
    @Test fun latePreviewAfterDatabaseChangeCannotBecomeReady() = runBlocking {
        fixture { workflow, scope ->
            val started = CompletableDeferred<Unit>(); val release = gate()
            coEvery { sync.readRecoveryPreview(any()) } coAnswers { started.complete(Unit); release.await(); graph }
            workflow.preview(graph.root); started.await(); workflow.databaseChanged()
            release.complete(Unit); settle(scope)
            assertEquals(RecoveryUiState.Stale, workflow.state.value)
            coVerify(exactly = 0) { sync.prepareRecoveryConfirmation(any(), any()) }
        }
    }
    @Test fun ownCommitInvalidationAndDoubleClickDoNotLoseTheOriginalReceipt() = runBlocking {
        fixture { workflow, scope ->
            val started = CompletableDeferred<Unit>(); val release = gate()
            coEvery { sync.confirmRecovery(ticket) } coAnswers { started.complete(Unit); release.await(); recoveryUiProof() }
            review(workflow); workflow.approve(true); workflow.confirm(); started.await()
            workflow.databaseChanged(); workflow.confirm()
            assertTrue(workflow.state.value is RecoveryUiState.Committing)
            release.complete(Unit); settle(scope)
            assertTrue(workflow.state.value is RecoveryUiState.Completed)
            coVerify(exactly = 1) { sync.confirmRecovery(ticket) }
        }
    }
    @Test fun closingDuringCommitDoesNotCancelItOrReopenTheDialogFromItsLateResult() = runBlocking {
        fixture { workflow, scope ->
            val started = CompletableDeferred<Unit>(); val release = gate(); var finished = false
            coEvery { sync.confirmRecovery(ticket) } coAnswers {
                started.complete(Unit); release.await(); finished = true; recoveryUiProof()
            }
            review(workflow); workflow.approve(true); workflow.confirm(); started.await()
            workflow.dismiss(); assertEquals(RecoveryUiState.Hidden, workflow.state.value)
            release.complete(Unit); settle(scope)
            assertTrue(finished); assertEquals(RecoveryUiState.Hidden, workflow.state.value)
        }
    }
    @Test fun lostConfirmationResultRetriesTheSameReceiptWithoutNewPreviewOrApproval() = runBlocking {
        fixture { workflow, scope ->
            review(workflow); workflow.approve(true)
            coEvery { sync.confirmRecovery(ticket) } throws IllegalStateException("lost local result")
            workflow.confirm(); settle(scope)
            assertTrue(workflow.state.value is RecoveryUiState.RetryConfirmation)
            workflow.databaseChanged()
            coEvery { sync.confirmRecovery(ticket) } returns recoveryUiProof()
            workflow.confirm(); settle(scope)
            assertTrue(workflow.state.value is RecoveryUiState.Completed)
            coVerify(exactly = 1) { sync.prepareRecoveryConfirmation(any(), any()) }
            coVerify(exactly = 2) { sync.confirmRecovery(ticket) }
        }
    }
    @Test fun cancellationPropagatesButRetainsOriginalReceiptForOutcomeVerification() = runBlocking {
        fixture { workflow, scope ->
            review(workflow); workflow.approve(true)
            coEvery { sync.confirmRecovery(ticket) } throws CancellationException("result lost after commit")
            workflow.confirm(); settle(scope)
            assertTrue(workflow.state.value is RecoveryUiState.RetryConfirmation)
            coEvery { sync.confirmRecovery(ticket) } returns recoveryUiProof()
            workflow.confirm(); settle(scope)
            assertTrue(workflow.state.value is RecoveryUiState.Completed)
        }
    }
    @Test fun staleConfirmationMustRepreviewAndObtainNewExplicitApproval() = runBlocking {
        fixture { workflow, scope ->
            review(workflow); workflow.approve(true)
            coEvery { sync.confirmRecovery(ticket) } throws IllegalArgumentException("SYNC_RECOVERY_CHECKPOINT_CHANGED")
            workflow.confirm(); settle(scope)
            assertEquals(RecoveryUiState.Stale, workflow.state.value)
            workflow.confirm(); coVerify(exactly = 1) { sync.confirmRecovery(ticket) }
            workflow.refresh(); workflow.state.first { it is RecoveryUiState.Review }
            assertFalse((workflow.state.value as RecoveryUiState.Review).approved)
        }
    }
    @Test fun accountTransitionDuringConfirmationClearsReviewAndRejectsLateCompletion() = runBlocking {
        fixture { workflow, scope ->
            val started = CompletableDeferred<Unit>(); val release = gate()
            coEvery { sync.confirmRecovery(ticket) } coAnswers {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; recoveryUiProof()
            }
            review(workflow); workflow.approve(true); workflow.confirm(); started.await()
            workflow.authenticationTransition(true); workflow.authenticationTransition(false)
            release.complete(Unit); settle(scope)
            assertEquals(RecoveryUiState.Hidden, workflow.state.value)
        }
    }
}
