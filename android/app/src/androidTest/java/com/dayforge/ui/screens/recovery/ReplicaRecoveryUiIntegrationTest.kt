package com.dayforge.ui.screens.recovery

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.api.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.local.entity.NextReplicaFenceEntity
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production root -> VM -> service -> canonical repository/Room/DS/archive/native material.
 * Owned TCP fixture, not actual FastAPI restore or formal APK/manual acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryUiIntegrationTest : NextReplicaDestinationFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private var model: ReplicaRecoveryViewModel? = null
    private var presentation: ReplicaPresentationController? = null
    @After fun closeUi() = runBlocking<Unit> {
        val job = model?.viewModelScope?.coroutineContext?.job
        withContext(Dispatchers.Main) { models.clear() }
        withTimeout(10_000) { job?.join() }
        presentation?.close(); presentation?.awaitClosed()
    }
    private suspend fun settle() = withTimeout(140_000) { model!!.state.first { it.checked && !it.busy } }
    private suspend fun click(tag: String) {
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick(); settle()
    }
    @Test fun explicitUiArchiveConsentAndForwardCompletionUseTheCanonicalProductionChain() = runBlocking<Unit> {
        initialize(); materialReply = { materialResponse(it, emptyList()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = directory }
        val network = mockk<NetworkMonitor>(); coEvery { network.localNetworks() } returns emptyList()
        val business = BusinessSyncRepository(mockk(), mockk(), db, tokens, sessions, http,
            NextSyncRuntime(db, tokens, sessions, http, preferences))
        val icons = AccountIconController({ runtime() }, tokens)
        val connection = ReplicaRecoveryConnection(EndpointResolver(network, preferences, tokens, Json, SelectedNetworkTransport()),
            tokens, sessions, client, http, AccountIconHttp(client, tokens, peer.origin))
        val repository = ReplicaRecoveryRepository(context, db, tokens, sessions, preferences, dataStore, business,
            NextReplicaCandidateValidator(context, tokens, sessions, preferences), icons, connection)
        val viewModel = withContext(Dispatchers.Main) {
            ReplicaRecoveryViewModel(ReplicaRecoveryService(repository, tokens, sessions)).also { model = it; models.put("root", it) }
        }
        settle()
        val gate = ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions)).also { presentation = it }
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        val oldTheme = com.dayforge.data.appearance.DeviceThemeStore(dataStore, themes).read()
        val calls = peer.requests.size
        withContext(Dispatchers.Main) { viewModel.show() }; settle()
        compose.setContent { ReplicaRecoveryRoot(gate, viewModel) { Text("Ordinary business") } }
        assertEquals(calls, peer.requests.size) // Opening/inspection alone never sends HTTP.
        click("replica-recovery-preview")
        assertNotNull(viewModel.state.value.preview); assertNull(tokens.activeReplicaStage())
        click("replica-recovery-prepare")
        val stage = requireNotNull(tokens.activeReplicaStage()); val retained = archive(stage).readBytes()
        assertNotNull(viewModel.state.value.evidence); assertFalse(viewModel.state.value.approved)
        compose.onNodeWithText("Ordinary business").assertDoesNotExist()
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag("replica-recovery-confirm"))
        compose.onNodeWithTag("replica-recovery-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("replica-recovery-approve").performClick()
        click("replica-recovery-confirm")
        assertEquals(ReplicaRecoveryNotice.COMPLETED, viewModel.state.value.notice)
        assertNull(tokens.activeReplicaStage()); assertEquals(id(99), tokens.syncEpoch.first())
        assertEquals(NextReplicaFenceEntity.CLOSED, db.nextReplicaFenceDao().state()!!.mode)
        assertArrayEquals(retained, archive(stage).readBytes())
        assertEquals(oldTheme, com.dayforge.data.appearance.DeviceThemeStore(dataStore, themes).read())
        assertTrue(peer.requests.all { it.method == "GET" })
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        compose.onNodeWithText(app.getString(R.string.replica_recovery_completed)).assertExists()
        click("replica-recovery-close")
        compose.onNodeWithText("Ordinary business").assertIsDisplayed()
        assertFalse(db.nextRequestDao().hasAny()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }
}
