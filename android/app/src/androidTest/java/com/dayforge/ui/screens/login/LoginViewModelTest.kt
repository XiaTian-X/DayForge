package com.dayforge.ui.screens.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.EndpointResolver
import com.dayforge.data.api.HealthResponse
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.TokenResponse
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.domain.service.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LoginViewModelTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var tokenManager: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var repository: HabitRepository
    private lateinit var syncManager: SyncManager
    private lateinit var storeScope: CoroutineScope
    private lateinit var viewModel: LoginViewModel
    private lateinit var endpointResolver: EndpointResolver
    private var healthStatus = "ok"
    private var rejectCredentialWrite = false
    private val dispatcher = UnconfinedTestDispatcher()

    private val response = TokenResponse("access", "refresh", userId = "new-account", username = "member", isAdmin = true)
    private var loginResponse = response
    private val authApi = object : AuthApi {
        override suspend fun login(request: LoginRequest) = loginResponse
        override suspend fun refreshToken(request: RefreshRequest) = response
        override suspend fun health() = HealthResponse(healthStatus)
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        healthStatus = "ok"
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "login_vm_v2_${UUID.randomUUID()}.preferences_pb")
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
        tokenManager = TokenManager(store, object : TokenCipher {
            override fun encrypt(value: String): String {
                check(!rejectCredentialWrite) { "credential storage unavailable" }
                return value
            }
            override fun decrypt(value: String) = value
        })
        preferences = PreferencesManager(store)
        repository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        endpointResolver = mockk(relaxed = true)
        coEvery { endpointResolver.resolve() } returns null
        coEvery { syncManager.hasLocalData() } returns false
        coEvery { syncManager.sync(any()) } returns Result.success(Unit)
        viewModel = LoginViewModel(
            authApi,
            endpointResolver,
            tokenManager,
            syncManager,
            repository,
            preferences,
            AccountSessionCoordinator(),
            context
        )
    }

    @After
    fun teardown() {
        runBlocking {
            if (::viewModel.isInitialized) viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            storeScope.coroutineContext[Job]?.cancelAndJoin()
        }
        assertTrue(file.delete() || !file.exists())
        Dispatchers.resetMain()
    }

    @Test
    fun login_persists_server_identity_and_authoritative_admin_role() = runTest(dispatcher.scheduler) {
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        assertEquals(viewModel.errorMessage.value, "new-account", tokenManager.userId.first())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertTrue(tokenManager.isAdmin.first())
        assertTrue(viewModel.showSyncPrompt.value)
    }

    @Test
    fun switching_account_clears_old_cache_before_credentials_become_active() = runTest(dispatcher.scheduler) {
        tokenManager.prepareSyncAccount("old-account")
        preferences.setNeverAskAgain(42L, true)
        preferences.setHabitNotificationEnabled(42L, false)
        preferences.addPendingMetricHabit(42L)
        preferences.setExpandedParentUuids(setOf("old-parent"))
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        coVerify(exactly = 1) { repository.clearAllData(context) }
        assertEquals("new-account", tokenManager.userId.first())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertFalse(preferences.getNeverAskAgain(42L).first())
        assertTrue(preferences.getHabitNotificationEnabled(42L).first())
        assertTrue(preferences.pendingMetricHabits.first().isEmpty())
        assertTrue(preferences.expandedParentUuids.first().isEmpty())
    }

    @Test
    fun legacy_unowned_local_changes_require_a_decision_and_can_be_merged() = runTest(dispatcher.scheduler) {
        coEvery { syncManager.hasLocalData() } returns true
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")

        viewModel.onLoginClick()
        awaitNotLoading()

        assertNull(tokenManager.accessToken.first())
        assertEquals(viewModel.errorMessage.value, true, viewModel.localDataPrompt.value?.canMerge)
        coVerify(exactly = 0) { repository.clearAllData(any()) }

        viewModel.onMergeLocalData()
        awaitNotLoading()

        assertEquals("new-account", tokenManager.userId.first())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertNull(viewModel.localDataPrompt.value)
        assertTrue(viewModel.showSyncPrompt.value)
        coVerify(exactly = 0) { repository.clearAllData(any()) }
    }

    @Test
    fun changes_owned_by_another_account_cannot_be_merged_and_need_explicit_discard() = runTest(dispatcher.scheduler) {
        tokenManager.prepareSyncAccount("old-account")
        coEvery { syncManager.hasLocalData() } returns true
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")

        viewModel.onLoginClick()
        awaitNotLoading()

        assertNull(tokenManager.accessToken.first())
        assertEquals(viewModel.errorMessage.value, false, viewModel.localDataPrompt.value?.canMerge)
        coVerify(exactly = 0) { repository.clearAllData(any()) }

        viewModel.onMergeLocalData()
        advanceUntilIdle()
        assertNull(tokenManager.accessToken.first())

        viewModel.onDiscardLocalData()
        awaitNotLoading()

        coVerify(exactly = 1) { repository.clearAllData(context) }
        assertEquals("new-account", tokenManager.userId.first())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertTrue(viewModel.showSyncPrompt.value)
    }

    @Test
    fun skipping_first_sync_keeps_offline_data_owned_and_blocks_cross_account_merge() = runTest(dispatcher.scheduler) {
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.loginEvent.first() }
        viewModel.onSyncSkip()
        assertEquals(LoginViewModel.LoginEvent.Success, event.await())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertNull(tokenManager.syncDeviceId.first())
        assertNull(tokenManager.syncEpoch.first())
        assertFalse(tokenManager.isSyncBootstrapped.first())
        coVerify(exactly = 0) { syncManager.sync(any()) }

        // Model offline edits followed by expired credentials, before any registration/pull.
        coEvery { syncManager.hasLocalData() } returns true
        tokenManager.clearAuthenticationTokens()
        loginResponse = response.copy(userId = "another-account", username = "another")
        viewModel.onLoginClick()
        awaitNotLoading()
        assertEquals(false, viewModel.localDataPrompt.value?.canMerge)
        viewModel.onMergeLocalData()
        advanceUntilIdle()
        assertNull(tokenManager.userId.first())
        assertEquals("new-account", tokenManager.syncAccountId.first())
        // Only the initial empty/unowned cache was cleared, not the subsequent offline data.
        coVerify(exactly = 1) { repository.clearAllData(context) }
    }

    @Test
    fun same_account_relogin_preserves_replica_progress_and_permissions() = runTest(dispatcher.scheduler) {
        tokenManager.prepareSyncAccount("new-account")
        tokenManager.saveServerIdentity("server", "epoch")
        tokenManager.saveDeviceRegistration("device", setOf("facts.write"), false, 7)
        tokenManager.saveSyncCursor(42)
        tokenManager.saveTokens("old-access", "old-refresh", "member", "new-account", false)
        val oldSession = tokenManager.authenticationSnapshot()!!.session
        coEvery { syncManager.hasLocalData() } returns true
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertEquals("server", tokenManager.serverInstanceId.first())
        assertEquals("epoch", tokenManager.syncEpoch.first())
        assertEquals("device", tokenManager.syncDeviceId.first())
        assertEquals(42L, tokenManager.syncCursor.first())
        assertTrue(tokenManager.isSyncBootstrapped.first())
        assertFalse(tokenManager.canEditStructure.first())
        assertEquals(setOf("facts.write"), tokenManager.deviceCapabilities.first())
        assertNotEquals(oldSession, tokenManager.authenticationSnapshot()!!.session)
        assertNull(viewModel.localDataPrompt.value)
        assertTrue(viewModel.showSyncPrompt.value)
        coVerify(exactly = 0) { repository.clearAllData(any()) }
    }

    @Test
    fun failed_legacy_adoption_does_not_partially_bind_owner_and_can_be_retried() = runTest(dispatcher.scheduler) {
        coEvery { syncManager.hasLocalData() } returns true
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()
        rejectCredentialWrite = true
        viewModel.onMergeLocalData()
        awaitNotLoading()

        assertNull(tokenManager.authenticationSnapshot())
        assertNull(tokenManager.syncAccountId.first())
        assertEquals(true, viewModel.localDataPrompt.value?.canMerge)
        assertEquals("credential storage unavailable", viewModel.errorMessage.value)
        assertFalse(viewModel.showSyncPrompt.value)

        rejectCredentialWrite = false
        viewModel.onMergeLocalData()
        awaitNotLoading()
        assertEquals("new-account", tokenManager.syncAccountId.first())
        assertEquals("new-account", tokenManager.userId.first())
        assertNull(viewModel.localDataPrompt.value)
        assertTrue(viewModel.showSyncPrompt.value)
        coVerify(exactly = 0) { repository.clearAllData(any()) }
    }

    @Test
    fun cancelling_account_switch_preserves_the_active_account_and_local_changes() = runTest(dispatcher.scheduler) {
        tokenManager.saveTokens("old-access", "old-refresh", "old-member", "old-account", false)
        tokenManager.prepareSyncAccount("old-account")
        coEvery { syncManager.hasLocalData() } returns true
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")

        viewModel.onLoginClick()
        awaitNotLoading()
        viewModel.onCancelLocalDataDecision()

        assertEquals("old-account", tokenManager.userId.first())
        assertEquals("old-access", tokenManager.accessToken.first())
        assertEquals("old-account", tokenManager.syncAccountId.first())
        assertNull(viewModel.localDataPrompt.value)
        assertFalse(viewModel.showSyncPrompt.value)
        coVerify(exactly = 0) { repository.clearAllData(any()) }
    }

    @Test
    fun failed_initial_sync_stays_on_login_and_retry_succeeds() = runTest(dispatcher.scheduler) {
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()
        coEvery { syncManager.sync(any()) } returns Result.failure(IllegalStateException("offline"))
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.loginEvent.first() }

        viewModel.onSyncConfirm()
        awaitNotLoading()

        assertFalse(event.isCompleted)
        assertTrue(viewModel.showSyncPrompt.value)
        assertNotNull(viewModel.syncErrorMessage.value)

        coEvery { syncManager.sync(any()) } returns Result.success(Unit)
        viewModel.onSyncConfirm()
        awaitNotLoading()

        assertEquals(LoginViewModel.LoginEvent.Success, event.await())
        assertFalse(viewModel.showSyncPrompt.value)
        assertNull(viewModel.syncErrorMessage.value)
    }

    @Test
    fun unhealthy_server_never_stores_credentials() = runTest(dispatcher.scheduler) {
        healthStatus = "starting"
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        assertNull(tokenManager.accessToken.first())
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun failedCacheClearNeverActivatesNewCredentialsAndRetryCanComplete() = runTest(dispatcher.scheduler) {
        tokenManager.saveTokens("old-access", "old-refresh", "old-member", "old-account", false)
        tokenManager.prepareSyncAccount("old-account")
        preferences.setNeverAskAgain(42L, true)
        var tokenAtCacheClear: String? = "not-called"
        coEvery { repository.clearAllData(context) } coAnswers {
            tokenAtCacheClear = tokenManager.accessToken.first()
            throw IllegalStateException("cache unavailable")
        }
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()
        assertNull("The old session must be invalidated before cache removal", tokenAtCacheClear)
        assertNull(tokenManager.accessToken.first())
        assertNull(tokenManager.userId.first())
        assertEquals("old-account", tokenManager.syncAccountId.first())
        assertTrue(preferences.getNeverAskAgain(42L).first())
        assertEquals("cache unavailable", viewModel.errorMessage.value)
        assertFalse(viewModel.showSyncPrompt.value)
        coEvery { repository.clearAllData(context) } returns Unit
        viewModel.onLoginClick()
        awaitNotLoading()
        assertEquals("new-account", tokenManager.userId.first())
        assertFalse(preferences.getNeverAskAgain(42L).first())
        assertTrue(viewModel.showSyncPrompt.value)
        coVerify(exactly = 2) { repository.clearAllData(context) }
    }

    private suspend fun awaitNotLoading() {
        viewModel.isLoading.first { !it }
    }
}
