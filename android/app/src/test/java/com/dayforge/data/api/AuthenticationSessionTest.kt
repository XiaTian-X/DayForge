package com.dayforge.data.api

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

class AuthenticationSessionTest {
    private val session = AuthenticationSession("account-a", "login-1")
    private val manager = mockk<TokenManager>()
    private val authenticator = TokenAuthenticator(manager, mockk(), mockk())

    @Test
    fun `old request cannot retry under another account or new login`() {
        for (currentSession in listOf(AuthenticationSession("account-b", "login-2"), session.copy(generation = "login-2"))) {
            coEvery { manager.authenticationSnapshot() } returns AuthenticationSnapshot(currentSession, "new", "refresh")
            assertNull(authenticator.authenticate(null, unauthorized(request(session))))
        }
    }

    @Test
    fun `concurrent refresh in the same session reuses new access token`() {
        coEvery { manager.authenticationSnapshot() } returns AuthenticationSnapshot(session, "new", "refresh")
        val retry = authenticator.authenticate(null, unauthorized(request(session)))!!
        assertEquals("Bearer new", retry.header("Authorization"))
        assertEquals(session, retry.tag(AuthenticationSession::class.java))
    }

    @Test
    fun `logout and untagged requests cannot be refreshed`() {
        coEvery { manager.authenticationSnapshot() } returns null
        assertNull(authenticator.authenticate(null, unauthorized(request(session))))
        assertNull(authenticator.authenticate(null, unauthorized(request(null))))
    }

    @Test
    fun `interceptor attaches the same snapshot account as the bearer token`() {
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
