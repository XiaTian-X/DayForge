package com.dayforge.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.local.replicaStageFixture
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual disk DS/Room + formal gate/lifetime helper + real Compose/NavHost, not a formal APK journey. */
@RunWith(AndroidJUnit4::class)
class ReplicaPresentationGateTest : NextCoreRequestFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var controller: ReplicaPresentationController? = null
    private var created = 0
    private var cleared = 0
    private val instances = mutableListOf<ReadingViewModel>()
    private lateinit var nav: NavHostController
    private class ReadingViewModel(val value: Int, private val clear: () -> Unit) : ViewModel() {
        override fun onCleared() = clear()
    }
    private suspend fun start(): ReplicaPresentationController {
        register()
        return ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions)).also {
            controller = it
            withTimeout(10_000) { it.state.first { state -> state is ReplicaPresentationState.Open } }
        }
    }
    @After fun closeController() = runBlocking<Unit> {
        controller?.close(); controller?.awaitClosed()
    }
    @Composable private fun ReadingScreen(name: String, entry: androidx.navigation.NavBackStackEntry) {
        val owner = businessGraphOwner(nav, entry)
        val model: ReadingViewModel = viewModel(owner, factory = viewModelFactory {
            initializer { ReadingViewModel(++created) { cleared++ }.also { instances.add(it) } }
        })
        Text("$name ${model.value}")
    }
    @Composable private fun Graph(start: String = BUSINESS_GRAPH) {
        nav = rememberNavController()
        NavHost(nav, start) {
            composable("login") { Text("Login") }
            navigation(startDestination = "home", route = BUSINESS_GRAPH) {
                composable("home") { ReadingScreen("Home", it) }
                composable("detail") { ReadingScreen("Detail", it) }
            }
        }
    }

    @Test fun checkingAndPendingDoNotComposeBusinessOrThemeRepairAndRetryIsReadOnly() {
        val state = mutableStateOf<ReplicaPresentationState>(ReplicaPresentationState.Checking)
        var composed = 0; var retries = 0
        compose.setContent {
            ReplicaPresentationGate(state.value, { retries++ }) { composed++; Text("Business and repair") }
        }
        compose.onNodeWithText("Business and repair").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, composed)
            state.value = ReplicaPresentationState.Pending(ReplicaPresentationCheck(ReplicaPresentationProblem.DAMAGED_STATE))
        }
        compose.onNodeWithText(app.getString(R.string.replica_display_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, composed) }
    }

    @Test fun realFreezeDisposesAllOldNavigationModelsAndCancelStartsNewScope() = runBlocking<Unit> {
        val gate = start()
        compose.setContent { ReplicaPresentationGate(gate) { permit -> ReplicaNavigationLifetime(permit) { Graph() } } }
        compose.onNodeWithText("Home 1").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("detail") }
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, cleared) }
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        compose.onNodeWithText(app.getString(R.string.replica_display_pending)).assertIsDisplayed()
        compose.onNodeWithText("Detail 1").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cleared) }
        stages.cancel(stage)
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        compose.onNodeWithText("Home 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, created); assertNotSame(instances[0], instances[1]) }
    }

    @Test fun loginDoesNotCreateBusinessReadersAndLogoutClearsAuthenticatedGraph() = runBlocking<Unit> {
        val gate = start()
        compose.setContent { ReplicaPresentationGate(gate) { permit -> ReplicaNavigationLifetime(permit) { Graph("login") } } }
        compose.onNodeWithText("Login").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, created); nav.navigate(BUSINESS_GRAPH) { popUpTo("login") { inclusive = true } } }
        compose.onNodeWithText("Home 1").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("detail") }
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, created); nav.navigate("login") { popUpTo(BUSINESS_GRAPH) { inclusive = true } } }
        compose.onNodeWithText("Login").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun cursorUpdateDoesNotResetDetailNavigationOrSharedModel() = runBlocking<Unit> {
        val gate = start()
        compose.setContent { ReplicaPresentationGate(gate) { permit -> ReplicaNavigationLifetime(permit) { Graph() } } }
        compose.onNodeWithText("Home 1").assertIsDisplayed()
        compose.runOnIdle { nav.navigate("detail") }
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        tokens.saveSyncCursor(31); gate.retry()
        compose.waitForIdle()
        compose.onNodeWithText("Detail 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, cleared) }
    }

    @Test fun ordinaryNavigationRemovalAlsoClosesReadersWithoutInvalidatingTheReplica() = runBlocking<Unit> {
        val gate = start()
        val visible = mutableStateOf(true)
        compose.setContent {
            ReplicaPresentationGate(gate) { permit ->
                if (visible.value) ReplicaNavigationLifetime(permit) { Graph() } else Text("Theme loading")
            }
        }
        compose.onNodeWithText("Home 1").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Theme loading").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, cleared); visible.value = true }
        compose.onNodeWithText("Home 2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, created); assertEquals(1, cleared) }
        assertTrue((gate.state.value as ReplicaPresentationState.Open).permit.canRender())
    }

    @Test fun navigationStoreRegistryReusesSameLifetimeAndClearsOnlyRetiredStore() {
        val registry = ReplicaNavigationStores()
        val first = registry.acquire(1)
        val model = ReadingViewModel(1) { cleared++ }
        first.viewModelStore.put("reader", model)
        assertSame(first, registry.acquire(1)); assertEquals(0, cleared)
        val second = registry.acquire(2)
        assertNotSame(first, second); assertEquals(1, cleared)
        registry.release(1); assertSame(second, registry.acquire(2))
        registry.release(2); assertNotSame(second, registry.acquire(2))
        registry.release(2)
    }
}
