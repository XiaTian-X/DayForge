package com.dayforge.ui.screens.login

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
import kotlinx.coroutines.cancel
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
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
    private val dispatcher = UnconfinedTestDispatcher()

    private val response = TokenResponse("access", "refresh", userId = "new-account", username = "member", isAdmin = true)
    private val authApi = object : AuthApi {
        override suspend fun login(request: LoginRequest) = response
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
        tokenManager = TokenManager(store)
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
        Dispatchers.resetMain()
        storeScope.cancel()
        file.delete()
    }

    @Test
    fun `login persists server identity and authoritative admin role`() = runTest(dispatcher.scheduler) {
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        assertEquals(viewModel.errorMessage.value, "new-account", tokenManager.userId.first())
        assertTrue(tokenManager.isAdmin.first())
        assertTrue(viewModel.showSyncPrompt.value)
    }

    @Test
    fun `switching account clears old cache before credentials become active`() = runTest(dispatcher.scheduler) {
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
        assertFalse(preferences.getNeverAskAgain(42L).first())
        assertTrue(preferences.getHabitNotificationEnabled(42L).first())
        assertTrue(preferences.pendingMetricHabits.first().isEmpty())
        assertTrue(preferences.expandedParentUuids.first().isEmpty())
    }

    @Test
    fun `legacy unowned local changes require a decision and can be merged`() = runTest(dispatcher.scheduler) {
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
        assertNull(tokenManager.syncAccountId.first())
        assertNull(viewModel.localDataPrompt.value)
        assertTrue(viewModel.showSyncPrompt.value)
        coVerify(exactly = 0) { repository.clearAllData(any()) }
    }

    @Test
    fun `changes owned by another account cannot be merged and need explicit discard`() = runTest(dispatcher.scheduler) {
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
        assertTrue(viewModel.showSyncPrompt.value)
    }

    @Test
    fun `cancelling account switch preserves the active account and local changes`() = runTest(dispatcher.scheduler) {
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
    fun `failed initial sync stays on login and retry succeeds`() = runTest(dispatcher.scheduler) {
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
    fun `unhealthy server never stores credentials`() = runTest(dispatcher.scheduler) {
        healthStatus = "starting"
        viewModel.onUsernameChange("member")
        viewModel.onPasswordChange("password123")
        viewModel.onLoginClick()
        awaitNotLoading()

        assertNull(tokenManager.accessToken.first())
        assertNotNull(viewModel.errorMessage.value)
    }

    private suspend fun awaitNotLoading() {
        viewModel.isLoading.first { !it }
    }
}
