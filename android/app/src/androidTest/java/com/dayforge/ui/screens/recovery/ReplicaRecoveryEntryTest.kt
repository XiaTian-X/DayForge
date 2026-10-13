package com.dayforge.ui.screens.recovery

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.AuthApi
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.repository.*
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.login.LoginScreen
import com.dayforge.ui.screens.login.LoginViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real control-only Room/DataStore observation and actual Login/Root components. Network,
 * preview and persistence authorities remain mocked and must never be called by an entry hint.
 */
@RunWith(AndroidJUnit4::class)
class ReplicaRecoveryEntryTest : NextCoreRequestFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val jobs = mutableListOf<Job>()
    private val connection = mockk<ReplicaRecoveryConnection>()
    private val icons = mockk<AccountIconController>()
    private var presentation: ReplicaPresentationController? = null
    private fun service(): ReplicaRecoveryService {
        val repository = ReplicaRecoveryRepository(app, db, tokens, sessions, preferences, dataStore,
            mockk(), mockk(), icons, connection)
        return ReplicaRecoveryService(repository, tokens, sessions)
    }
    private suspend fun registerProfile() { register(); tokens.getOrCreateInstallationId() }
    private fun profile() = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0,
        "a".repeat(64), "b".repeat(64), challengeContract = 1)
    private suspend fun installProfile(row: NextSyncStateEntity = profile()) = db.withTransaction {
        db.nextSyncStateDao().insert(row)
    }
    private fun makeOldCipherUnreadable() {
        tokens = TokenManager(dataStore, object : TokenCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String): String? = null
        })
    }
    private suspend fun openModel(): ReplicaRecoveryViewModel = withContext(Dispatchers.Main) {
        ReplicaRecoveryViewModel(service()).also {
            models.put("recovery", it); jobs += it.viewModelScope.coroutineContext.job
        }
    }.also { model -> withTimeout(10_000) { model.state.first { it.checked && !it.busy } } }
    private suspend fun awaitEntry(model: ReplicaRecoveryViewModel, expected: Boolean) {
        withTimeout(10_000) { model.state.first { it.entryAvailable == expected } }
    }
    @After fun closeUi() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { models.clear() }
        withTimeout(10_000) { jobs.forEach { it.join() } }
        presentation?.close(); presentation?.awaitClosed()
        verify { connection wasNot Called }; verify { icons wasNot Called }
    }

    @Test fun credentialFreeHintRequiresActualMatchingRoundProfileAndNeverGrantsAuthentication() = runBlocking<Unit> {
        registerProfile(); makeOldCipherUnreadable(); val service = service()
        val before = dataStore.data.first()
        assertFalse(service.control().entryAvailable) // Registered metadata alone is not next adoption.
        installProfile(profile().copy(challengeContract = 0))
        assertFalse(service.control().entryAvailable)
        for (row in listOf(profile().copy(accountId = id(71)), profile().copy(serverInstanceId = id(72)),
            profile().copy(syncEpoch = id(73)), profile().copy(deviceId = id(74)))) {
            db.withTransaction { db.nextSyncStateDao().update(row) }
            assertFalse(service.control().entryAvailable)
        }
        db.withTransaction { db.nextSyncStateDao().update(profile()) }
        assertTrue(service.control().entryAvailable)
        assertNull(tokens.accessToken.first()); assertNull(tokens.localSyncAccess())
        assertEquals(before, dataStore.data.first())
        assertEquals(listOf(profile()), db.nextSyncStateDao().rows())
    }

    @Test fun firstBootstrapRefreshesTheExistingModelAndAccountChangeRevokesTheOldDoorway() = runBlocking<Unit> {
        registerProfile(); val model = openModel(); assertFalse(model.state.value.entryAvailable)
        installProfile(); awaitEntry(model, true)
        val old = model.state.value
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(88), false)
        assertFalse(model.canRender(old)); assertFalse(model.state.value.entryAvailable)
        registerProfile() // A different owner cannot use the old SQL profile, even with matching endpoint/device.
        assertFalse(service().control().entryAvailable)
        db.withTransaction { db.nextSyncStateDao().update(profile().copy(accountId = id(88))) }
        awaitEntry(model, true)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("DELETE FROM next_sync_state") }
        awaitEntry(model, false)
    }

    @Test fun unreadableOldCredentialStillExposesExplicitRecoveryFromTheActualLoginScreen() = runBlocking<Unit> {
        registerProfile(); installProfile(); makeOldCipherUnreadable()
        assertNull(tokens.accessToken.first()) // The navigation graph chooses Login in this case.
        val model = openModel(); awaitEntry(model, true)
        val auth = mockk<AuthApi>()
        val login = withContext(Dispatchers.Main) {
            LoginViewModel(auth, mockk(), tokens, mockk(), habits(), preferences, sessions, app).also {
                models.put("login", it); jobs += it.viewModelScope.coroutineContext.job
            }
        }
        val gate = ReplicaPresentationController(tokens, ReplicaPresentationRepository(db, tokens, sessions))
            .also { presentation = it }
        withTimeout(10_000) { gate.state.first { it is ReplicaPresentationState.Open } }
        compose.setContent {
            ReplicaRecoveryRoot(gate, model) { LoginScreen(login, onLoginSuccess = { fail("Entry must not login") }) }
        }
        compose.onNodeWithTag("login-replica-recovery").performScrollTo().performClick()
        withTimeout(10_000) { model.state.first { it.visible && it.checked && !it.busy } }
        compose.onNodeWithTag("replica-recovery-list").assertExists()
        compose.onNodeWithTag("login-replica-recovery").assertDoesNotExist()
        compose.onNodeWithTag("replica-recovery-list").performScrollToNode(hasTestTag("replica-recovery-login"))
        compose.onNodeWithTag("replica-recovery-login").performClick()
        compose.onNodeWithTag("replica-recovery-password").assertExists()
        assertNull(tokens.accessToken.first()); assertNull(tokens.activeReplicaStage())
        coVerify(exactly = 0) { auth.login(any()) }
    }
}
