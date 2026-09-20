package com.dayforge.data.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.AuthenticationSnapshot
import com.dayforge.data.local.TokenManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class AuthenticationSessionTest {
    private val session = AuthenticationSession("account-a", "login-1")
    private val manager = mockk<TokenManager>()
    private val authenticator = TokenAuthenticator(manager, mockk(), mockk())

    @Test
    fun old_request_cannot_retry_under_another_account_or_new_login() {
        for (currentSession in listOf(AuthenticationSession("account-b", "login-2"), session.copy(generation = "login-2"))) {
            coEvery { manager.authenticationSnapshot() } returns AuthenticationSnapshot(currentSession, "new", "refresh")
            assertNull(authenticator.authenticate(null, unauthorized(request(session))))
        }
    }

    @Test
    fun concurrent_refresh_in_the_same_session_reuses_new_access_token() {
        coEvery { manager.authenticationSnapshot() } returns AuthenticationSnapshot(session, "new", "refresh")
        val retry = authenticator.authenticate(null, unauthorized(request(session)))!!
        assertEquals("Bearer new", retry.header("Authorization"))
        assertEquals(session, retry.tag(AuthenticationSession::class.java))
    }

    @Test
    fun logout_and_untagged_requests_cannot_be_refreshed() {
        coEvery { manager.authenticationSnapshot() } returns null
        assertNull(authenticator.authenticate(null, unauthorized(request(session))))
        assertNull(authenticator.authenticate(null, unauthorized(request(null))))
    }

    @Test
    fun interceptor_attaches_the_same_snapshot_account_as_the_bearer_token() {
        coEvery { manager.authenticationSnapshot() } returns AuthenticationSnapshot(session, "new", "refresh")
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request(null)
        every { chain.proceed(any()) } answers {
            val outgoing = firstArg<Request>()
            assertEquals(listOf("Bearer new"), outgoing.headers.values("Authorization"))
            assertEquals(session, outgoing.tag(AuthenticationSession::class.java))
            unauthorized(outgoing)
        }
        AuthInterceptor(manager).intercept(chain)
    }

    private fun request(session: AuthenticationSession?) = Request.Builder()
        .url("https://example.invalid/api/v2/sync/push")
        .header("Authorization", "Bearer old")
        .tag(AuthenticationSession::class.java, session)
        .build()

    private fun unauthorized(request: Request) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
}
