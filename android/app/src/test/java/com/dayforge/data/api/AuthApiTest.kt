package com.dayforge.data.api

import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.TokenResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthApiTest {
    private val authApi = mockk<AuthApi>()
    private val token = TokenResponse(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "account-id",
        username = "member",
        isAdmin = false
    )

    @Test
    fun `login returns the complete account contract`() = runTest {
        val request = LoginRequest("member", "password123")
        coEvery { authApi.login(request) } returns token

        assertEquals(token, authApi.login(request))
        coVerify(exactly = 1) { authApi.login(request) }
    }

    @Test
    fun `refresh preserves account identity and role`() = runTest {
        val request = RefreshRequest("old-refresh")
        coEvery { authApi.refreshToken(request) } returns token

        val result = authApi.refreshToken(request)
        assertEquals("account-id", result.userId)
        assertEquals(false, result.isAdmin)
    }

    @Test
    fun `health exposes server readiness`() = runTest {
        coEvery { authApi.health() } returns HealthResponse("ok")
        assertEquals("ok", authApi.health().status)
    }
}
