package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRecoveryDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf<RecoveryUiState>(RecoveryUiState.Hidden)
    private var confirmed = 0
    private var refreshed = 0
    private var closed = 0
    private fun text(id: Int) = compose.activity.getString(id)
    private fun show(value: RecoveryUiState) {
        state.value = value
        compose.setContent { MaterialTheme {
            RecoveryConfirmationDialog(state.value, onDismiss = { closed++ }, onRefresh = { refreshed++ },
                onApprove = { selected -> state.value = (state.value as RecoveryUiState.Review).copy(approved = selected) },
                onConfirm = { confirmed++ })
        } }
    }
    @Test fun exactChangesAndWholeGroupApprovalAreRequiredBeforeConfirmation() {
        show(RecoveryUiState.Review(recoveryUiReview()))
        compose.onNodeWithTag("recovery-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("recovery-details").performScrollToNode(hasTestTag("recovery-change-${recoveryUiId(9000)}"))
        compose.onNodeWithText(compose.activity.getString(R.string.sync_recovery_field_before, "Current name")).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_recovery_field_after, "Accepted name")).assertExists()
        compose.onNodeWithTag("recovery-details").performScrollToNode(hasTestTag("recovery-approve"))
        compose.onNodeWithTag("recovery-approve").performClick()
        compose.onNodeWithTag("recovery-confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
    @Test fun protectedGroupShowsBlockerWithoutAnyConfirmationOrCheckbox() {
        show(RecoveryUiState.Review(recoveryUiReview(recoveryUiGraph(protected = true))))
        compose.onNodeWithTag("recovery-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recovery-approve").assertDoesNotExist()
        compose.onNodeWithTag("recovery-details").performScrollToNode(hasText(text(R.string.sync_recovery_block_protected), substring = true))
        compose.onNodeWithText(text(R.string.sync_recovery_block_protected), substring = true).assertExists()
        assertEquals(0, confirmed)
    }
    @Test fun unverifiedProjectionCannotExposeAnEnabledConfirmationEvenWithApprovalSet() {
        show(RecoveryUiState.Review(recoveryUiReview(failed = true), approved = true))
        compose.onNodeWithTag("recovery-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recovery-approve").assertDoesNotExist()
        assertEquals(0, confirmed)
    }
    @Test fun stalePreviewShowsOnlyRefreshNotOldChangesOrConfirmation() {
        show(RecoveryUiState.Stale)
        compose.onNodeWithText(text(R.string.sync_recovery_stale)).assertExists()
        compose.onNodeWithText("Current name").assertDoesNotExist()
        compose.onNodeWithTag("recovery-confirm").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sync_recovery_refresh)).performClick()
        assertEquals(1, refreshed); assertEquals(0, confirmed)
    }
    @Test fun committingCanBeClosedButCannotDispatchASecondConfirmation() {
        show(RecoveryUiState.Committing(recoveryUiReview()))
        compose.onNodeWithText(text(R.string.sync_recovery_committing)).assertExists()
        compose.onNodeWithTag("recovery-confirm").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.action_close)).performClick()
        assertEquals(1, closed); assertEquals(0, confirmed)
    }
    @Test fun missingResultRequiresExplicitSameConfirmationRetryAndNeverClaimsSyncSuccess() {
        show(RecoveryUiState.RetryConfirmation(recoveryUiReview()))
        compose.onNodeWithText(text(R.string.sync_recovery_retry_message)).assertExists()
        compose.onNodeWithTag("recovery-retry-confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
        compose.onNodeWithText(text(R.string.sync_success)).assertDoesNotExist()
    }
    @Test fun completedLocalRecoveryIsClearlyDifferentFromServerSyncSuccess() {
        show(RecoveryUiState.Completed(recoveryUiId(9100), 2))
        compose.onNodeWithText(text(R.string.sync_recovery_completed)).assertExists()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.sync_recovery_completed_count, 2, 2)).assertExists()
        compose.onNodeWithTag("recovery-confirm").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sync_success)).assertDoesNotExist()
        compose.runOnIdle { state.value = RecoveryUiState.Completed(recoveryUiId(9100), 1) }
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.sync_recovery_completed_count, 1, 1)).assertExists()
    }
    @Test fun completeLongDependencyListRemainsReachableWithoutTruncationOrGiantEagerColumn() {
        show(RecoveryUiState.Review(recoveryUiReview(recoveryUiGraph(size = 2000))))
        // Jump via the real LazyColumn index; scanning 2000 viewports would measure the test
        // driver's repeated scrolling rather than the application's lazy rendering.
        compose.onNodeWithTag("recovery-details").performScrollToIndex(2002)
        compose.onNodeWithTag("recovery-request-${recoveryUiId(2000)}").assertExists()
        compose.onNodeWithTag("recovery-details").performScrollToIndex(2005)
        compose.onNodeWithTag("recovery-approve").assertExists()
        compose.onNodeWithTag("recovery-confirm").assertIsNotEnabled()
    }
}
