package com.dayforge.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.IconRasterSize
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.service.IconImageHandle
import com.dayforge.domain.service.IconImageState
import com.dayforge.ui.screens.settings.IconLibraryFixture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObjectIconControllerTest {
    private val fixture = ObjectIconFixture()
    private val theme get() = ThemeVersionRef(fixture.id(20), 1)
    @Before fun setup() = runBlocking { fixture.open() }
    @After fun cleanup() = runBlocking { fixture.close() }
    private suspend fun load(handle: IconImageHandle, ref: IconReference, once: Boolean = false,
        dark: Boolean = false, tint: Int = IconLibraryFixture.green) {
        fixture.controller.loadReference(handle, requireNotNull(handle.requests.value), ref,
            once, theme, dark, IconRasterSize(8, 8), tint)
    }
    private fun pixel(handle: IconImageHandle) = (handle.state.value as IconImageState.Ready).raster.bitmap.getPixel(4, 4)

    @Test fun rolesTrackSelectedStyleButFixedAssetsRemainOwnedAndUnchanged() = runBlocking {
        fixture.install(); fixture.choose()
        val role = fixture.handle(); val fixed = fixture.handle()
        val reference = IconReference.Role("habit.exercise")
        val fixedReference = IconReference.Asset(fixture.id(11))
        load(role, reference); load(fixed, fixedReference)
        assertEquals(IconLibraryFixture.red, pixel(role)); assertEquals(IconLibraryFixture.red, pixel(fixed))
        val priorRequest = role.requests.value
        fixture.install(100, 0xff0000ff.toInt()); fixture.choose(100)
        assertNotSame(priorRequest, role.requests.value)
        load(role, reference); load(fixed, fixedReference)
        assertEquals(0xff0000ff.toInt(), pixel(role)); assertEquals(IconLibraryFixture.red, pixel(fixed))
        fixture.choose(null)
        load(role, reference); load(fixed, fixedReference)
        assertSame(IconImageState.Empty, role.state.value); assertEquals(IconLibraryFixture.red, pixel(fixed))
        assertEquals("habit.exercise", reference.role); assertEquals(fixture.id(11), fixedReference.assetId)
    }
    @Test fun purposeAndMissingReferencesNeverAuthorizeHiddenBuiltInContent() = runBlocking {
        fixture.install(); fixture.choose()
        val handle = fixture.handle()
        load(handle, IconReference.Role("task.shopping"))
        assertEquals("ICON_PURPOSE_MISMATCH", (handle.state.value as IconImageState.Failed).error.message)
        load(handle, IconReference.Asset(fixture.id(12)))
        assertTrue(handle.state.value is IconImageState.Failed)
        load(handle, IconReference.Role("task.shopping"), once = true, tint = 0xff123456.toInt())
        assertEquals(0xff123456.toInt(), pixel(handle))
        load(handle, IconReference.Asset(fixture.id(11)), dark = true, tint = 0xff123456.toInt())
        assertEquals(IconLibraryFixture.green, pixel(handle)) // Original never receives template tint.
        val missing = IconReference.Role("task.custom")
        load(handle, missing, once = true)
        assertEquals(IconLibraryFixture.red, pixel(handle)) // Display-only placeholder, not a new reference.
        assertEquals("task.custom", missing.role)
        fixture.choose(null); load(handle, missing, once = true)
        assertSame(IconImageState.Empty, handle.state.value)
    }
    @Test fun transitionDuringNativeDrawingRejectsLatePixelsAndCannotRebindSurvivingHandle() = runBlocking {
        fixture.install()
        val handle = fixture.handle()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.drawBarrier = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val ref = IconReference.Asset(fixture.id(11))
        val pending = async(Dispatchers.IO) { load(handle, ref) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            fixture.login(owner = fixture.id(5))
            assertSame(IconImageState.Empty, handle.state.value)
        } finally { release.countDown(); pending.await(); fixture.drawBarrier = null }
        assertSame(IconImageState.Empty, handle.state.value)
        val draws = fixture.draws.get()
        load(handle, ref)
        assertEquals("ICON_IMAGE_NAMESPACE_CHANGED", (handle.state.value as IconImageState.Failed).error.message)
        fixture.login(server = fixture.id(6)); load(handle, ref)
        assertTrue(handle.state.value is IconImageState.Failed)
        fixture.login(epoch = fixture.id(7)); load(handle, ref)
        assertTrue(handle.state.value is IconImageState.Failed)
        assertEquals(draws, fixture.draws.get())
        fixture.login(); load(handle, ref)
        assertEquals(IconLibraryFixture.red, pixel(handle))
        val raster = (handle.state.value as IconImageState.Ready).raster
        val request = requireNotNull(handle.requests.value)
        handle.close()
        val decrypts = fixture.decrypts.get()
        fixture.controller.loadReference(handle, request, ref, false, theme, false, IconRasterSize(8, 8), 0)
        withTimeout(5000) { fixture.controller.awaitImages() }
        assertEquals(decrypts, fixture.decrypts.get())
        assertSame(IconImageState.Empty, handle.state.value); assertNull(handle.requests.value)
        assertFalse(raster.bitmap.isRecycled)
    }
    @Test fun readyFileCorruptionIsFailureAndExplicitRefreshKeepsOriginalBytes() = runBlocking {
        val preview = fixture.install()
        val handle = fixture.handle(); val ref = IconReference.Asset(fixture.id(11))
        load(handle, ref); assertEquals(IconLibraryFixture.red, pixel(handle))
        val ns = preview.context.namespace
        val file = File(fixture.directory,
            "account-icons-v1/${ns.accountId}/${ns.serverInstanceId}/${ns.syncEpoch}/${preview.manifest.assets.first().light.sha256}")
        file.writeBytes(byteArrayOf(0))
        val request = handle.requests.value
        fixture.controller.library(fixture.controller.capture())
        assertNotSame(request, handle.requests.value)
        load(handle, ref)
        assertTrue(handle.state.value is IconImageState.Failed)
        assertArrayEquals(byteArrayOf(0), file.readBytes())
        assertEquals(fixture.id(11), ref.assetId)
    }
}
