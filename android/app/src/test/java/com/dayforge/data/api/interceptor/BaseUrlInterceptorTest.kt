package com.dayforge.data.api.interceptor

import com.dayforge.data.local.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlInterceptorTest {
    private val preferences = mockk<PreferencesManager>(relaxed = true).also {
        every { it.serverUrl } returns flowOf(null)
        every { it.activeServerUrl } returns flowOf(null)
    }
    private val interceptor = BaseUrlInterceptor(preferences)

    @Test
    fun `custom host preserves v2 path and query`() {
        val original = "http://localhost:8000/api/v2/sync/changes?cursor=12".toHttpUrl()
        val custom = "https://family.example:9443/old/path".toHttpUrl()

        val rebuilt = interceptor.rebuildUrl(original, custom)

        assertEquals("https", rebuilt.scheme)
        assertEquals("family.example", rebuilt.host)
        assertEquals(9443, rebuilt.port)
        assertEquals("/api/v2/sync/changes", rebuilt.encodedPath)
        assertEquals("12", rebuilt.queryParameter("cursor"))
    }

    @Test
    fun `custom host also preserves current v1 auth path`() {
        val rebuilt = interceptor.rebuildUrl(
            "http://localhost:8000/api/v1/auth/login".toHttpUrl(),
            "http://192.168.1.10:8000/api/v1/".toHttpUrl()
        )
        assertEquals("http://192.168.1.10:8000/api/v1/auth/login", rebuilt.toString())
    }
}
