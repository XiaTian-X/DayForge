package com.dayforge.ui.screens.recovery

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import io.mockk.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical UI state contracts with mocked actions. Sentinels are not archive/SQL/auth proofs;
 * the real service/composition and UI integration suites exercise those actual participants.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryViewModelTest {
    private val actions = mockk<ReplicaRecoveryActions>()
    private val control = AtomicReference(ReplicaRecoveryControl(AuthenticationSession(recoveryId(1), recoveryId(6)), null))
    private val entryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private lateinit var invalidator: AccountIconMemory.Cache
    private val stores = mutableListOf<Pair<ViewModelStore, ReplicaRecoveryViewModel>>()
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private val key = ReplicaRecordKey("metric", recoveryId(30))
    init {
        every { actions.observeInvalidation(any()) } answers { invalidator = firstArg() }
        every { actions.entryChanges() } returns entryChanges
        coEvery { actions.control() } answers { control.get() }
    }
    private fun stage(phase: ReplicaSwitchPhase = ReplicaSwitchPhase.ARCHIVE_VERIFIED): ReplicaSwitchStage = recoveryUiStage(phase)
    private fun preview(blocked: Boolean = false): ReplicaDifferencePreview = mockk {
        every { hasUnfinishedTimer } returns blocked
        every { unconfirmedLocalRecords } returns emptyList()
        every { differences } returns listOf(ReplicaDifference(key, ReplicaDifferenceKind.MISSING, buildJsonObject { put("name", "Private metric") }, null, false))
        every { unchangedRecords } returns 0
        every { assessment } returns mockk { every { reviewCounts } returns emptyMap() }
    }
    private fun evidence(expectedStage: ReplicaSwitchStage, preview: ReplicaDifferencePreview): ReplicaRecoveryConfirmationEvidence = mockk {
        every { stage } returns expectedStage
        every { differences } returns preview
        every { materials } returns mockk { every { unresolvedRoles } returns emptySet() }
    }
    private suspend fun open(): ReplicaRecoveryViewModel = withContext(Dispatchers.Main) {
        ReplicaRecoveryViewModel(actions).also { model ->
            val store = ViewModelStore(); store.put("recovery", model); stores += store to model
        }
    }.also { idle(it) }
    private suspend fun idle(model: ReplicaRecoveryViewModel) = withTimeout(10_000) { model.state.first { !it.busy && it.checked } }
    private suspend fun act(model: ReplicaRecoveryViewModel, block: () -> Unit) {
        withContext(Dispatchers.Main) { block() }; idle(model)
    }
    private fun transition(stage: ReplicaSwitchStage?, authentication: AuthenticationSession? = control.get().authentication) {
        invalidator.authenticationTransition(true)
        control.set(ReplicaRecoveryControl(authentication, stage))
        invalidator.authenticationTransition(false)
    }
    private suspend fun ready(model: ReplicaRecoveryViewModel): ReplicaRecoveryConfirmationEvidence {
        val preview = preview(); val stage = stage(); val evidence = evidence(stage, preview)
        coEvery { actions.preview() } returns preview
        coEvery { actions.prepare(preview) } coAnswers { transition(stage); stage }
        coEvery { actions.confirmation(stage) } returns evidence
        act(model) { model.preview() }; act(model) { model.prepare() }
        return evidence
    }
    @After fun close() = runBlocking<Unit> {
        releases.forEach { it.complete(Unit) }
        stores.forEach { (store, model) ->
            val job = model.viewModelScope.coroutineContext.job
            withContext(Dispatchers.Main) { store.clear() }
            withTimeout(5_000) { job.join() }
        }
    }

    @Test fun coldStageInspectionCannotPreviewPrepareAuthenticateCommitOrResumeAutomatically() = runBlocking<Unit> {
        val stage = stage(); control.set(control.get().copy(stage = stage))
        val model = open(); assertEquals(stage, model.state.value.stage); assertNull(model.state.value.evidence)
        assertFalse(model.state.value.approved); assertFalse(model.state.value.visible)
        act(model) { model.show() }; assertTrue(model.state.value.visible)
        coVerify(exactly = 0) { actions.preview() }; coVerify(exactly = 0) { actions.prepare(any()) }
        coVerify(exactly = 0) { actions.confirm(any()) }; coVerify(exactly = 0) { actions.resume(any()) }
        coVerify(exactly = 0) { actions.reauthenticate(any(), any()) }
    }
    @Test fun localEntryRefreshPreservesEvidenceConsentAndNoticeAndDismissKeepsTheDoorway() = runBlocking<Unit> {
        control.set(control.get().copy(entryAvailable = true))
        val model = open(); val evidence = ready(model)
        withContext(Dispatchers.Main) { model.approve(true) }
        control.set(control.get().copy(entryAvailable = true))
        entryChanges.emit(Unit)
        withTimeout(5_000) { model.state.first { it.entryAvailable } }
        assertSame(evidence, model.state.value.evidence); assertTrue(model.state.value.approved)
        // A local observation after a user-visible result must not run inspect() and erase it.
        coEvery { actions.reauthenticate(any(), any()) } returns requireNotNull(control.get().authentication)
        act(model) { model.reauthenticate("member", "synthetic") }
        assertEquals(ReplicaRecoveryNotice.AUTHENTICATED, model.state.value.notice)
        control.set(control.get().copy(entryAvailable = false)); entryChanges.emit(Unit)
        withTimeout(5_000) { model.state.first { !it.entryAvailable } }
        assertEquals(ReplicaRecoveryNotice.AUTHENTICATED, model.state.value.notice)
        control.set(control.get().copy(entryAvailable = true)); entryChanges.emit(Unit)
        withTimeout(5_000) { model.state.first { it.entryAvailable } }
        withContext(Dispatchers.Main) { model.dismiss() }
        assertTrue(model.state.value.entryAvailable)
        coVerify(exactly = 0) { actions.confirm(any()) }; coVerify(exactly = 0) { actions.resume(any()) }
    }
    @Test fun ownFreezeRetainsFreshEvidenceButConfirmationRequiresApprovalAndDoubleClickSendsOnce() = runBlocking<Unit> {
        val model = open(); val evidence = ready(model)
        assertSame(evidence, model.state.value.evidence); assertFalse(model.state.value.canConfirm)
        act(model) { model.confirm() }; coVerify(exactly = 0) { actions.confirm(any()) }
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>().also { releases += it }
        coEvery { actions.confirm(evidence) } coAnswers {
            started.complete(Unit); release.await(); transition(null)
            ReplicaCompletionOutcome.Completed(stage(ReplicaSwitchPhase.COMPLETED))
        }
        withContext(Dispatchers.Main) { model.approve(true); model.confirm(); model.confirm() }
        started.await(); assertTrue(model.state.value.busy); assertFalse(model.state.value.approved)
        release.complete(Unit); idle(model)
        assertEquals(ReplicaRecoveryNotice.COMPLETED, model.state.value.notice); assertNull(model.state.value.stage)
        assertNull(model.state.value.evidence); coVerify(exactly = 1) { actions.confirm(evidence) }
    }
    @Test fun coldPrecommitResumeOnlyReturnsNewEvidenceWithoutReusingApproval() = runBlocking<Unit> {
        val stage = stage(); control.set(control.get().copy(stage = stage)); val model = open()
        val evidence = evidence(stage, preview()); coEvery { actions.resume(stage) } returns ReplicaRecoveryContinuation.Confirmation(evidence)
        act(model) { model.resume() }
        assertSame(evidence, model.state.value.evidence); assertFalse(model.state.value.approved)
        act(model) { model.confirm() }; coVerify(exactly = 0) { actions.confirm(any()) }
    }
    @Test fun postcommitCannotCancelAndWaitingOrChangedNeverLoopsOrReleases() = runBlocking<Unit> {
        val stage = stage(ReplicaSwitchPhase.DESTINATION_BOUND); control.set(control.get().copy(stage = stage)); val model = open()
        act(model) { model.cancel() }; coVerify(exactly = 0) { actions.cancel(any()) }
        coEvery { actions.resume(stage) } returns ReplicaRecoveryContinuation.Destination(ReplicaCompletionOutcome.Waiting(stage, IconMaterialOutcome.RETRY))
        act(model) { model.resume() }; assertEquals(ReplicaRecoveryNotice.WAITING, model.state.value.notice)
        assertEquals(stage, model.state.value.stage)
        coEvery { actions.resume(stage) } returns ReplicaRecoveryContinuation.Destination(ReplicaCompletionOutcome.Changed(stage))
        act(model) { model.resume() }; assertEquals(ReplicaRecoveryNotice.CHANGED, model.state.value.notice)
        coVerify(exactly = 2) { actions.resume(stage) }; assertNull(model.state.value.evidence)
    }
    @Test fun failedOrCancelledPreparationInspectsActualDurableStageWithoutAutomaticRetry() = runBlocking<Unit> {
        val model = open(); val preview = preview(); val stage = stage(ReplicaSwitchPhase.FROZEN)
        coEvery { actions.preview() } returns preview
        coEvery { actions.prepare(preview) } coAnswers { transition(stage); throw CancellationException("lost caller after freeze") }
        act(model) { model.preview() }; act(model) { model.prepare() }
        assertEquals(stage, model.state.value.stage); assertFalse(model.state.value.busy)
        assertNull(model.state.value.evidence); assertNull(model.state.value.preview); assertTrue(model.state.value.canAttemptCancel)
        coVerify(exactly = 1) { actions.prepare(preview) }; coVerify(exactly = 0) { actions.resume(any()) }
    }
    @Test fun latePreviewAfterAccountTransitionCannotPublishOldDataOrReviveDrawPermission() = runBlocking<Unit> {
        val model = open(); val old = model.state.value
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>().also { releases += it }
        coEvery { actions.preview() } coAnswers { started.complete(Unit); withContext(NonCancellable) { release.await() }; preview() }
        withContext(Dispatchers.Main) { model.preview() }; started.await()
        transition(null, AuthenticationSession(recoveryId(9), recoveryId(10)))
        assertFalse(model.canRender(old)); assertNull(model.state.value.preview); assertNull(model.state.value.evidence)
        release.complete(Unit); idle(model)
        assertNull(model.state.value.preview); assertEquals(ReplicaRecoveryError.CHANGED, model.state.value.error)
        assertFalse(model.canRender(old)); assertFalse(model.state.value.approved)
    }
    @Test fun sameOwnerReauthenticationClearsApprovalAndUpdatesOnlyTheCredentialFreeView() = runBlocking<Unit> {
        val model = open(); ready(model); withContext(Dispatchers.Main) { model.approve(true) }
        val stage = control.get().stage; val authenticated = AuthenticationSession(recoveryId(1), recoveryId(11))
        coEvery { actions.reauthenticate("member", "synthetic-password") } coAnswers { transition(stage, authenticated); authenticated }
        act(model) { model.reauthenticate("member", "synthetic-password") }
        assertEquals(ReplicaRecoveryNotice.AUTHENTICATED, model.state.value.notice); assertEquals(stage, model.state.value.stage)
        assertNull(model.state.value.evidence); assertFalse(model.state.value.approved)
        coVerify(exactly = 0) { actions.confirm(any()) }; coVerify(exactly = 0) { actions.cancel(any()) }
    }
    @Test fun newModelNeverRestoresConsentOrPrivatePreviewsAndClearedModelCannotPublishLateResults() = runBlocking<Unit> {
        val model = open(); ready(model); withContext(Dispatchers.Main) { model.approve(true) }
        val old = model.state.value; val store = stores.first().first
        withContext(Dispatchers.Main) { store.clear() }
        assertFalse(model.canRender(old)); assertNull(model.state.value.evidence)
        val cold = open(); assertEquals(control.get().stage, cold.state.value.stage)
        assertNull(cold.state.value.evidence); assertFalse(cold.state.value.approved)
        coVerify(exactly = 0) { actions.confirm(any()) }
    }
    @Test fun pendingWorkPreventsPrepareAndUnknownExceptionCannotExposeItsPrivateMessage() = runBlocking<Unit> {
        val model = open(); coEvery { actions.preview() } returns preview(blocked = true)
        act(model) { model.preview() }; assertFalse(model.state.value.canPrepare)
        act(model) { model.prepare() }; coVerify(exactly = 0) { actions.prepare(any()) }
        coEvery { actions.preview() } throws IllegalStateException("password=private-secret body=private-row")
        act(model) { model.preview() }; assertEquals(ReplicaRecoveryError.FAILED, model.state.value.error)
        assertFalse(model.state.value.toString().contains("private-secret")); assertNull(model.state.value.preview)
    }
    @Test fun materialFailureRetainsExactGapsButNeverBecomesConfirmationOrSuccess() = runBlocking<Unit> {
        val model = open(); val preview = preview(); val gaps = mockk<ReplicaMaterialPreflight>()
        coEvery { actions.preview() } returns preview
        coEvery { actions.prepare(preview) } throws ReplicaMaterialUnavailable(gaps, "ICON_RECOVERY_REFERENCE_MISSING")
        act(model) { model.preview() }; act(model) { model.prepare() }
        assertSame(gaps, model.state.value.material); assertEquals(ReplicaRecoveryError.MATERIAL, model.state.value.error)
        assertNull(model.state.value.evidence); assertFalse(model.state.value.canConfirm)
    }
    @Test fun explicitCancelObservesTerminalControlAndRetainsNoConfirmation() = runBlocking<Unit> {
        val stage = stage(); control.set(control.get().copy(stage = stage)); val model = open()
        coEvery { actions.cancel(stage) } coAnswers { transition(null); stage.transition(ReplicaSwitchPhase.ABORTED) }
        act(model) { model.cancel() }; assertEquals(ReplicaRecoveryNotice.CANCELLED, model.state.value.notice)
        assertNull(model.state.value.stage); assertNull(model.state.value.preview); assertNull(model.state.value.evidence)
        coVerify(exactly = 0) { actions.preview() }; coVerify(exactly = 0) { actions.resume(any()) }
    }
    @Test fun fullValueSlicesRetainEveryCharacterIncludingUnicodeAtPageBoundaries() {
        val text = "x".repeat(4095) + "😀" + "y".repeat(4094) + "😀" + "tail"
        val value = ReplicaRecoveryValue(text)
        assertEquals(text, (0 until value.pages).joinToString("") { value.page(it) })
        assertTrue((0 until value.pages).all { value.page(it).length <= 4097 })
    }
    @Test fun rawRecordsWithSameLogicalKeyInDifferentTablesRemainIndividuallyReadable() = runBlocking<Unit> {
        val model = open(); val preview = preview()
        every { preview.differences } returns emptyList()
        every { preview.unconfirmedLocalRecords } returns listOf("completions", "timelogs").map {
            ReplicaUnconfirmedLocalRecord(key, it, buildJsonObject { put("source_table", it) })
        }
        coEvery { actions.preview() } returns preview
        act(model) { model.preview() }; act(model) { model.detail(key, "timelogs") }
        assertEquals("timelogs", model.state.value.detail!!.table)
        assertTrue(model.state.value.detail!!.before!!.page(0).contains("timelogs"))
        assertFalse(model.state.value.canPrepare); coVerify(exactly = 1) { actions.preview() }
    }
}

/** Valid-shaped UI journal sentinel only; never supplied to real COMMIT/archive APIs. */
internal fun recoveryId(n: Int) = "db160000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
internal fun recoveryUiStage(phase: ReplicaSwitchPhase): ReplicaSwitchStage {
    var value = ReplicaSwitchStage(recoveryId(2), recoveryId(1), recoveryId(3), recoveryId(4), recoveryId(5),
        recoveryId(6), recoveryId(7), 1, listOf("sync.read"), recoveryId(8), "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), 20)
    if (phase == ReplicaSwitchPhase.FREEZING) return value
    value = value.transition(ReplicaSwitchPhase.FROZEN)
    if (phase == ReplicaSwitchPhase.FROZEN) return value
    value = value.transition(ReplicaSwitchPhase.ARCHIVE_VERIFIED, ReplicaStageArchive(recoveryId(20), "b".repeat(64), 12,
        "e".repeat(64), listOf("input.json", "business.json", "icons.json").map { ReplicaStageArchiveEntry(it, 4, "b".repeat(64)) }))
    if (phase == ReplicaSwitchPhase.ARCHIVE_VERIFIED) return value
    value = value.transition(ReplicaSwitchPhase.COMMITTING, destination = ReplicaStageTarget("f".repeat(64), "1".repeat(64), recoveryId(7), 1, listOf("sync.read"), false, false))
    if (phase == ReplicaSwitchPhase.COMMITTING) return value
    value = value.transition(ReplicaSwitchPhase.SQL_COMMITTED)
    if (phase == ReplicaSwitchPhase.SQL_COMMITTED) return value
    value = value.transition(ReplicaSwitchPhase.DESTINATION_BOUND)
    if (phase == ReplicaSwitchPhase.DESTINATION_BOUND) return value
    value = value.transition(ReplicaSwitchPhase.RELEASING, releasing = ReplicaStageRelease(6, "2".repeat(64), recoveryId(6)))
    if (phase == ReplicaSwitchPhase.RELEASING) return value
    require(phase == ReplicaSwitchPhase.COMPLETED)
    return value.transition(ReplicaSwitchPhase.COMPLETED, completed = ReplicaStageCompletion("3".repeat(64), recoveryId(6)))
}
