package com.dayforge.ui.screens.permission

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionItemTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pendingExternalPermissionHasDisabledButtonUntilResultReturns() {
        val pending = mutableStateOf(false)
        var launches = 0
        compose.setContent { MaterialTheme {
            PermissionItem("permission", "synthetic", PermissionViewModel.PermissionStatus.Denied,
                Icons.Default.Notifications, "open" to { launches++; pending.value = true },
                actionEnabled = !pending.value)
        } }
        compose.onNodeWithText("open").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, launches); pending.value = false }
        compose.onNodeWithText("open").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, launches) }
    }
}
