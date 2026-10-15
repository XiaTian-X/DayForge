package com.dayforge.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalCompletionDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun busyCompletionDisablesBothChoicesAndRetryRestoresThem() {
        val busy = mutableStateOf(false)
        var submissions = 0
        val context: Context = ApplicationProvider.getApplicationContext()
        compose.setContent { MaterialTheme {
            GoalCompletionDialog("synthetic goal", 3, 3,
                onConfirm = { submissions++; busy.value = true }, onDismiss = {}, busy = busy.value)
        } }
        compose.onNodeWithText(context.getString(R.string.action_confirm_complete)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_continue_checkin)).assertIsNotEnabled()
        compose.onAllNodes(isEnabled() and hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, submissions); busy.value = false }
        compose.onNodeWithText(context.getString(R.string.action_confirm_complete)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, submissions) }
    }
}
