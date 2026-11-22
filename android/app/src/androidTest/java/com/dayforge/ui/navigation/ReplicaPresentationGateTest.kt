package com.dayforge.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
    // Public constructor deliberately requires the real entry's SavedState factory;
    // a bare/default no-argument owner cannot instantiate this probe.
    class SavedStateProbe(val state: SavedStateHandle) : ViewModel() {
        var closed = false
            private set
        override fun onCleared() { closed = true }
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

    @Test(timeout = 150_000) fun outsideNavHostGraphOwnerPreservesFactoryAndNeverReusesRetiredSavedState() = runBlocking<Unit> {
        val gate = start()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<androidx.navigation.NavBackStackEntry, SavedStateProbe>>()
        val rootOwner = java.util.concurrent.atomic.AtomicReference<androidx.lifecycle.ViewModelStoreOwner>()
        compose.setContent {
            ReplicaPresentationGate(gate) { permit -> ReplicaNavigationLifetime(permit) {
                nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val bareOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
                SideEffect { rootOwner.set(requireNotNull(bareOwner)) }
                Column(Modifier.fillMaxSize()) {
                    if (entry?.destination?.route == "home") {
                        // Same outside-NavHost ownership boundary as MainScreen's recovery panel.
                        val owner = businessGraphOwner(nav, requireNotNull(entry))
                        val model: SavedStateProbe = viewModel(owner) // No hand-made factory/extras.
                        SideEffect { if (seen.none { it.second === model }) seen.add(owner to model) }
                        Text("Owned recovery reader")
                    }
                    NavHost(nav, BUSINESS_GRAPH, modifier = Modifier.weight(1f)) {
                        composable("login") { Text("Login") }
                        navigation(startDestination = "home", route = BUSINESS_GRAPH) {
                            composable("home") { Text("Home") }
                        }
                    }
                }
            } }
        }
        compose.waitUntil(10_000) { seen.size == 1 }
        compose.onNodeWithText("Owned recovery reader").assertIsDisplayed()
        val first = seen.single()
        val firstJob = first.second.viewModelScope.coroutineContext.job
        compose.runOnIdle {
            // Deterministic original failure: the isolation-only owner has no factory;
            // never make it an Activity-global store or hide the default-factory rejection.
            val bare = requireNotNull(rootOwner.get())
            assertFalse(bare is androidx.lifecycle.HasDefaultViewModelProviderFactory)
            val refusal = runCatching {
                androidx.lifecycle.ViewModelProvider(bare)[SavedStateProbe::class.java]
            }.exceptionOrNull()
            assertNotNull(refusal)
            assertTrue(generateSequence(refusal) { it.cause }.any { it is NoSuchMethodException })
            assertSame(first.first, first.first.defaultViewModelCreationExtras[VIEW_MODEL_STORE_OWNER_KEY])
            assertSame(first.first, first.first.defaultViewModelCreationExtras[SAVED_STATE_REGISTRY_OWNER_KEY])
            assertNotSame(compose.activity.viewModelStore, first.first.viewModelStore)
            first.second.state["private-input"] = "retired-epoch"
        }
        val stages = NextReplicaStageStore(db, tokens, sessions)
        val stage = stages.begin(tokens.replicaStageFixture())
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Pending } }
        compose.onNodeWithText(app.getString(R.string.replica_display_pending)).assertIsDisplayed()
        compose.onNodeWithText("Owned recovery reader").assertDoesNotExist()
        withTimeout(5_000) { firstJob.join() }
        compose.runOnIdle { assertTrue(first.second.closed); assertTrue(firstJob.isCancelled) }
        stages.cancel(stage)
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        compose.waitUntil(10_000) { seen.size == 2 }
        val second = seen[1]
        val secondJob = second.second.viewModelScope.coroutineContext.job
        compose.runOnIdle {
            assertNotSame(first.first, second.first); assertNotSame(first.second, second.second)
            assertNull(second.second.state.get<String>("private-input"))
            assertSame(second.first, second.first.defaultViewModelCreationExtras[VIEW_MODEL_STORE_OWNER_KEY])
            assertSame(second.first, second.first.defaultViewModelCreationExtras[SAVED_STATE_REGISTRY_OWNER_KEY])
            second.second.state["private-input"] = "retired-business-graph"
            nav.navigate("login") { popUpTo(BUSINESS_GRAPH) { inclusive = true } }
        }
        compose.onNodeWithText("Login").assertIsDisplayed()
        compose.onNodeWithText("Owned recovery reader").assertDoesNotExist()
        withTimeout(5_000) { secondJob.join() }
        compose.runOnIdle {
            assertTrue(second.second.closed); assertTrue(secondJob.isCancelled)
            nav.navigate(BUSINESS_GRAPH) { popUpTo("login") { inclusive = true } }
        }
        compose.waitUntil(10_000) { seen.size == 3 }
        compose.onNodeWithText("Owned recovery reader").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(second.first, seen[2].first); assertNotSame(second.second, seen[2].second)
            assertNull(seen[2].second.state.get<String>("private-input"))
            assertEquals(3, seen.size)
        }
    }
}
