package com.dayforge.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.NextSyncProblem
import com.dayforge.data.model.SyncProblems
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncProblemsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "aa310000-0000-4000-8000-000000000099"
    private var actions = 0
    private fun label(resource: Int) = compose.activity.getString(resource)
    private fun show(state: SyncProblems?) {
        val row = SyncOutboxEntity(id = 1, operationId = id, recordType = "metric", entityUuid = id,
            wireEntityUuid = id, action = "upsert", createdAt = 0, lastError = "legacy reason", deadLetteredAt = 1)
        compose.setContent { MaterialTheme { RejectedChangesDialog(listOf(row), emptyList(), emptyList(),
            onDismiss = {}, onRetry = { actions++ }, onRetryTimerCommand = { actions++ },
            onCancelTimerAndUseServer = { actions++ }, onUseServer = { actions++ }, onUseLocal = { actions++ },
            onDiscard = { actions++ }, problems = if (state is SyncProblems.Legacy) state.copy(changes = listOf(row)) else state) } }
    }
    @Test fun nextProblemHasReasonAndNoLegacyMutationEvenWhenOldRowsAreStillCached() {
        show(SyncProblems.Next(listOf(NextSyncProblem("sync_operation", id, "metric", id, "INVALID_PAYLOAD"))))
        compose.onNodeWithTag("next-sync-problem-$id").assertExists()
        compose.onNodeWithText("INVALID_PAYLOAD").assertExists()
        compose.onNodeWithText(label(R.string.sync_rejected_retry)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.sync_rejected_discard)).assertDoesNotExist()
        compose.onNodeWithText("legacy reason").assertDoesNotExist(); assertEquals(0, actions)
    }
    @Test fun unreadableSnapshotNeverExposesOldActions() {
        show(SyncProblems.Unavailable)
        compose.onNodeWithText(label(R.string.sync_problems_unavailable)).assertExists()
        compose.onNodeWithText(label(R.string.sync_rejected_retry)).assertDoesNotExist()
        compose.onNodeWithText("legacy reason").assertDoesNotExist(); assertEquals(1, SyncProblems.Unavailable.count)
    }
    @Test fun checkingDoesNotPretendToHaveVerifiedAProblem() {
        show(SyncProblems.Checking)
        compose.onNodeWithText(label(R.string.sync_problems_loading)).assertExists()
        compose.onNodeWithText("legacy reason").assertDoesNotExist(); assertEquals(0, SyncProblems.Checking.count)
    }
    @Test fun legacyProblemRetainsExistingRetryAndDiscardControls() {
        show(SyncProblems.Legacy(null, emptyList(), emptyList(), emptyList()))
        compose.onNodeWithText("legacy reason").assertExists()
        compose.onNodeWithText(label(R.string.sync_rejected_retry)).performClick()
        compose.onNodeWithText(label(R.string.sync_rejected_discard)).performClick()
        assertEquals(2, actions)
    }
}
