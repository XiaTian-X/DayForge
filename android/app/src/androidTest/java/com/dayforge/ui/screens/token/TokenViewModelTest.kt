package com.dayforge.ui.screens.token

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.TokenApi
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TokenViewModelTest {
    @Test fun createAndDeleteAdmitOnceBeforeDispatchAndReleaseOnFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = mockk<TokenApi>()
        coEvery { api.listTokens() } returns emptyList()
        val model = TokenViewModel(api)
        try {
            assertTrue(model.isLoading.value)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            coEvery { api.createToken(any()) } coAnswers { release.await(); mockk(relaxed = true) }
            repeat(20) { model.createToken("synthetic", 1) }
            assertTrue(model.isLoading.value) // Immediate admission; no waiting for I/O/dispatch.
            runCurrent()
            coVerify(exactly = 1) { api.createToken(any()) }
            release.complete(Unit); runCurrent()
            assertFalse(model.isLoading.value)
            assertNotNull(model.createdToken.value)
            val deletion = CompletableDeferred<Unit>()
            coEvery { api.deleteToken(1) } coAnswers { deletion.await(); error("synthetic failure") }
            repeat(20) { model.deleteToken(1) }
            assertTrue(model.isLoading.value); runCurrent()
            coVerify(exactly = 1) { api.deleteToken(1) }
            deletion.complete(Unit); runCurrent()
            assertFalse(model.isLoading.value); assertNotNull(model.error.value)
            coEvery { api.deleteToken(1) } returns Unit
            model.deleteToken(1); runCurrent()
            coVerify(exactly = 2) { api.deleteToken(1) }
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
