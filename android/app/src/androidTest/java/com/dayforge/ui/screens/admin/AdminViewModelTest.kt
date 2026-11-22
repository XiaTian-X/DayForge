package com.dayforge.ui.screens.admin

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.AdminApi
import com.dayforge.data.api.dto.AdminUserResponse
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AdminViewModelTest {
    @Test fun userCreationAndPasswordResetRejectRepeatedClicksAndUnlockAfterFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<AdminApi>()
        coEvery { api.listUsers() } returns emptyList()
        val model = AdminViewModel(api, ApplicationProvider.getApplicationContext<Context>())
        try {
            runCurrent()
            val created = CompletableDeferred<Unit>()
            coEvery { api.createUser(any()) } coAnswers { created.await(); mockk(relaxed = true) }
            repeat(20) { model.createUser("synthetic", "test-password", false) }
            assertTrue(model.isLoading.value); runCurrent()
            coVerify(exactly = 1) { api.createUser(any()) }
            created.complete(Unit); runCurrent(); assertFalse(model.isLoading.value)
            val user = mockk<AdminUserResponse>(relaxed = true) { every { id } returns 1 }
            model.showResetPasswordDialog(user); model.onResetPasswordInputChange("new-password")
            val reset = CompletableDeferred<Unit>()
            coEvery { api.resetPassword(1, any()) } coAnswers { reset.await(); error("synthetic failure") }
            repeat(20) { model.confirmResetPassword() }
            assertTrue(model.isLoading.value); runCurrent()
            coVerify(exactly = 1) { api.resetPassword(1, any()) }
            reset.complete(Unit); runCurrent(); assertFalse(model.isLoading.value)
            assertSame(user, model.resetPasswordDialog.value)
            coEvery { api.resetPassword(1, any()) } returns Unit
            model.confirmResetPassword(); runCurrent()
            assertNull(model.resetPasswordDialog.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun tokenCreationHasImmediateFeedbackAndKeepsCapturedUserUntilFinished() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<AdminApi>()
        coEvery { api.listUsers() } returns emptyList()
        coEvery { api.listUserTokens(any()) } returns emptyList()
        val model = AdminViewModel(api, ApplicationProvider.getApplicationContext<Context>())
        try {
            runCurrent()
            val first = mockk<AdminUserResponse>(relaxed = true) { every { id } returns 1 }
            val second = mockk<AdminUserResponse>(relaxed = true) { every { id } returns 2 }
            model.selectUser(first); runCurrent()
            val release = CompletableDeferred<Unit>()
            coEvery { api.createUserToken(1, any()) } coAnswers { release.await(); mockk(relaxed = true) }
            repeat(20) { model.createUserToken("synthetic", 1) }
            assertTrue(model.tokensLoading.value)
            model.selectUser(second); model.dismissSelectedUser()
            assertSame(first, model.selectedUser.value)
            runCurrent(); coVerify(exactly = 1) { api.createUserToken(1, any()) }
            release.complete(Unit); runCurrent(); assertFalse(model.tokensLoading.value)
            model.selectUser(second); runCurrent(); assertSame(second, model.selectedUser.value)
            coVerify(exactly = 0) { api.createUserToken(2, any()) }
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
