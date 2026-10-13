package com.dayforge.ui.screens.recovery

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import com.dayforge.ui.navigation.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real DS/Room control and production root/lifetime; action boundary is mocked, no COMMIT claim. */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryRootTest : NextCoreRequestFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var controller: ReplicaPresentationController? = null
    private val models = ViewModelStore()
    private var model: ReplicaRecoveryViewModel? = null
    private var businessCreated = 0; private var businessCleared = 0; private var themeComposed = 0
    private class Reader(val number: Int, private val close: () -> Unit) : ViewModel() { override fun onCleared() = close() }
    private lateinit var actions: ReplicaRecoveryActions
    private suspend fun start() {
        register()
        actions = mockk()
        every { actions.observeInvalidation(any()) } answers { tokens.registerIconCache(firstArg()) }
        coEvery { actions.control() } coAnswers { ReplicaRecoveryControl(tokens.replicaUiAuthentication(), tokens.activeReplicaStage()) }
        val gate = ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions)).also { controller = it }
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        val recovery = withContext(Dispatchers.Main) { ReplicaRecoveryViewModel(actions).also { model = it; models.put("recovery", it) } }
        withTimeout(5_000) { recovery.state.first { it.checked && !it.busy } }
        compose.setContent {
            ReplicaRecoveryRoot(gate, recovery) {
                ReplicaPresentationGate(gate) { permit ->
                    SideEffect { themeComposed++ }
                    ReplicaNavigationLifetime(permit) {
                        val reader: Reader = viewModel(factory = viewModelFactory {
                            initializer { Reader(++businessCreated) { businessCleared++ } }
                        })
                        Text("Business ${reader.number}")
                    }
                }
            }
        }
    }
    @After fun finish() = runBlocking<Unit> {
        val job = model?.viewModelScope?.coroutineContext?.job
        withContext(Dispatchers.Main) { models.clear() }
        withTimeout(5_000) { job?.join() }
        controller?.close(); controller?.awaitClosed()
    }
    @Test fun actualFreezeReplacesAllBusinessNavigationWithRecoveryAndCancelStartsANewBusinessLifetime() = runBlocking<Unit> {
        start(); compose.onNodeWithText("Business 1").assertIsDisplayed()
        val stage = NextReplicaStageStore(db, tokens, sessions).begin(tokens.replicaStageFixture())
        withTimeout(5_000) { controller!!.state.first { it is ReplicaPresentationState.Pending } }
        compose.onNodeWithTag("replica-recovery-list").assertExists()
        compose.onNodeWithText("Business 1").assertDoesNotExist()
        var themes = 0
        compose.runOnIdle { assertEquals(1, businessCleared); themes = themeComposed }
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag("replica-recovery-inspect"))
        compose.onNodeWithTag("replica-recovery-inspect").performClick()
        withTimeout(5_000) { model!!.state.first { it.checked && !it.busy } }
        assertEquals(stage, model!!.state.value.stage)
        compose.runOnIdle { assertEquals(themes, themeComposed) }
        coVerify(exactly = 0) { actions.resume(any()) }; coVerify(exactly = 0) { actions.confirm(any()) }
        NextReplicaStageStore(db, tokens, sessions).cancel(stage)
        withTimeout(5_000) { controller!!.state.first { it is ReplicaPresentationState.Open } }
        compose.onNodeWithText("Business 2").assertIsDisplayed()
        assertNotNull(model); assertEquals(2, businessCreated)
    }
    @Test fun explicitRootEntryRemovesBusinessButReusesTheActivityModelWithoutAutomaticNetwork() = runBlocking<Unit> {
        start(); val rootModel = model!!
        withContext(Dispatchers.Main) { rootModel.show() }
        withTimeout(5_000) { rootModel.state.first { it.checked && !it.busy } }
        compose.onNodeWithTag("replica-recovery-list").assertExists()
        compose.onNodeWithText("Business 1").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, businessCleared) }
        coVerify(exactly = 0) { actions.preview() }; coVerify(exactly = 0) { actions.resume(any()) }
        withContext(Dispatchers.Main) { rootModel.dismiss() }
        compose.onNodeWithText("Business 2").assertIsDisplayed()
        assertSame(rootModel, model)
    }
}
