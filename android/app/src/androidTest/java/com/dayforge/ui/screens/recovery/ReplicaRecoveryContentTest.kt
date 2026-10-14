package com.dayforge.ui.screens.recovery

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.appearance.AccountIconSelection
import com.dayforge.data.local.ReplicaSwitchPhase
import com.dayforge.data.repository.*
import io.mockk.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose on the physical phone. Action/evidence boundaries are sentinels, not SQL proofs. */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(ReplicaRecoveryUiState(visible = true, checked = true))
    private var diagnostic: ReplicaPresentationCheck? = null
    private var confirmations = 0; private var resumes = 0; private var inspections = 0
    private var cancelled = 0; private var logins = 0
    private val current = AtomicBoolean(true)
    @Composable private fun content() = MaterialTheme {
        ReplicaRecoveryContent(state.value, diagnostic, { inspections++ }, {}, {}, { resumes++ },
            { state.value = state.value.copy(approved = it) }, { confirmations++ }, { cancelled++ },
            { _, _ -> logins++ }, { _, _ -> }, {}, {}, isCurrent = { current.get() })
    }
    private fun clickTag(tag: String) {
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun evidence(): ReplicaRecoveryConfirmationEvidence = mockk {
        every { stage } returns recoveryUiStage(ReplicaSwitchPhase.ARCHIVE_VERIFIED)
        every { materials } returns mockk { every { unresolvedRoles } returns emptySet() }
        every { differences } returns mockk {
            every { differences } returns emptyList(); every { unchangedRecords } returns 0
            every { unconfirmedLocalRecords } returns emptyList(); every { hasUnfinishedTimer } returns false
            every { assessment } returns mockk { every { reviewCounts } returns emptyMap() }
        }
    }
    @Test fun confirmationIsDisabledUntilExplicitConsentAndPhoneWidthIsRespected() {
        val proof = evidence(); state.value = state.value.copy(stage = proof.stage, evidence = proof)
        compose.setContent { Box(Modifier.width(320.dp)) { content() } }
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag("replica-recovery-confirm"))
        compose.onNodeWithTag("replica-recovery-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("replica-recovery-approve").performClick()
        compose.onNodeWithTag("replica-recovery-confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmations); assertEquals(0, resumes) }
        val list = compose.onNodeWithTag("replica-recovery-list").fetchSemanticsNode().boundsInRoot
        assertTrue(list.width <= 320 * compose.activity.resources.displayMetrics.density + 1)
    }
    @Test fun postcommitOnlyOffersForwardContinuationAndNeverCancellationOrImplicitConfirmation() {
        state.value = state.value.copy(stage = recoveryUiStage(ReplicaSwitchPhase.DESTINATION_BOUND))
        compose.setContent { content() }
        compose.onNodeWithTag("replica-recovery-cancel").assertDoesNotExist()
        compose.onNodeWithTag("replica-recovery-confirm").assertDoesNotExist()
        clickTag("replica-recovery-resume")
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(0, confirmations); assertEquals(0, cancelled) }
    }
    @Test fun loginFormIsNotSavedAndDismissOrSubmitDoesNotRetainCredentials() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { content() }
        clickTag("replica-recovery-login")
        compose.onNodeWithTag("replica-recovery-username").performTextInput("member")
        compose.onNodeWithTag("replica-recovery-password").performTextInput("synthetic-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("replica-recovery-password").assertDoesNotExist()
        clickTag("replica-recovery-login")
        assertEquals("", compose.onNodeWithTag("replica-recovery-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("replica-recovery-username").performTextInput("member")
        compose.onNodeWithTag("replica-recovery-password").performTextInput("synthetic-password")
        compose.onNodeWithTag("replica-recovery-login-submit").performClick()
        compose.onNodeWithTag("replica-recovery-password").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, logins); assertEquals(0, confirmations) }
        clickTag("replica-recovery-login")
        assertEquals("", compose.onNodeWithTag("replica-recovery-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    @Test fun orphanControlsHidePrivateReviewAndOnlyPermitLocalInspection() {
        val proof = evidence(); state.value = state.value.copy(stage = proof.stage, evidence = proof, approved = true)
        diagnostic = ReplicaPresentationCheck(ReplicaPresentationProblem.ORPHAN_STAGE)
        compose.setContent { content() }
        compose.onNodeWithTag("replica-recovery-login").assertDoesNotExist()
        compose.onNodeWithTag("replica-recovery-confirm").assertDoesNotExist()
        compose.onNodeWithTag("replica-recovery-preview").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.replica_recovery_damaged)).assertExists()
        clickTag("replica-recovery-inspect")
        compose.runOnIdle { assertEquals(1, inspections); assertEquals(0, confirmations); assertEquals(0, logins) }
    }
    @Test fun staleLoginPopupCannotSubmitCredentialsBeforeTheNextCompositionFrame() {
        compose.setContent { content() }
        clickTag("replica-recovery-login")
        compose.onNodeWithTag("replica-recovery-username").performTextInput("member")
        compose.onNodeWithTag("replica-recovery-password").performTextInput("synthetic-password")
        current.set(false) // No Compose state write: exercise the surviving old callback/window.
        compose.onNodeWithTag("replica-recovery-login-submit").performClick()
        compose.runOnIdle { assertEquals(0, logins); assertEquals(0, confirmations) }
    }
    @Test fun materialGapsDisplayExactAssetAndVariantsWithoutAConfirmButton() {
        val gaps = mockk<ReplicaMaterialPreflight> {
            every { missingPack } returns false; every { changedPack } returns false
            every { selection } returns AccountIconSelection(0, null)
            every { missingAssets } returns setOf(recoveryId(30))
            every { pendingVariants } returns mapOf(recoveryId(31) to setOf("dark"))
        }
        state.value = state.value.copy(material = gaps, error = ReplicaRecoveryError.MATERIAL)
        compose.setContent { content() }
        val missing = compose.activity.getString(R.string.replica_recovery_asset_missing, recoveryId(30))
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasText(missing))
        compose.onNodeWithText(missing).assertIsDisplayed()
        val pending = compose.activity.getString(R.string.replica_recovery_variants_pending, recoveryId(31), "dark")
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasText(pending))
        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithTag("replica-recovery-confirm").assertDoesNotExist()
    }
}
