package com.dayforge.ui.screens.recovery

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.MainActivity
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.domain.service.ReplicaRecoveryService
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MainActivity/Hilt in com.dayforge.testbed only; default v5 stays disabled. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryHiltEntryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 2) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()
    @Inject internal lateinit var service: ReplicaRecoveryService
    @Test fun mainActivityResolvesItsRecoveryModelOutsideBusinessNavigationWithoutOpeningRecovery() = runBlocking<Unit> {
        hilt.inject(); check(compose.activity.packageName == "com.dayforge.testbed")
        lateinit var model: ReplicaRecoveryViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[ReplicaRecoveryViewModel::class.java] }
        compose.waitUntil(10_000) { model.state.value.checked && !model.state.value.busy }
        assertFalse(model.state.value.visible); assertNull(model.state.value.stage)
        assertNull(model.state.value.preview); assertNull(model.state.value.evidence)
        assertNull(service.control().stage)
        compose.onNodeWithTag("replica-recovery-list").assertDoesNotExist()
    }
}
