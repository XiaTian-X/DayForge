package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.R
import com.dayforge.data.appearance.AccountIconDatabase
import com.dayforge.data.appearance.IconPackVersion
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Picker launch/return is the official registry test boundary. Real URI/Room/files/pixels and DI. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class IconLibraryIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    @Inject lateinit var icons: AccountIconController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    private val app: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ViewModelStore()
    private lateinit var model: IconLibraryViewModel
    private lateinit var file: File
    private val launches = AtomicInteger()
    private var tint = 0
    private fun id(n: Int) = IconLibraryFixture.id(n)
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocument)
                assertArrayEquals(arrayOf("*/*"), input as Array<*>)
                launches.incrementAndGet()
                // Native contract's actual parseResult; registry controls delivery only.
                val result = contract.parseResult(android.app.Activity.RESULT_OK,
                    android.content.Intent().setData(Uri.fromFile(file)))
                dispatchResult(requestCode, result)
            }
        }
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject()
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        file = File.createTempFile("library-ui-", ".zip", app.filesDir).also { it.writeBytes(IconLibraryFixture.archive()) }
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", id(1), false)
            tokens.saveServerIdentity(id(2), id(3))
            tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
        }
        withContext(Dispatchers.Main) { model = IconLibraryViewModel(icons); models.put("icons", model) }
        compose.setContent {
            MaterialTheme {
                val primary = MaterialTheme.colorScheme.primary.toArgb()
                SideEffect { tint = primary }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    IconLibraryScreen(onNavigateBack = {}, viewModel = model)
                }
            }
        }
        compose.waitUntil(5000) { model.state.value.catalog != null }
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::model.isInitialized) {
            compose.setContent { }
            compose.waitForIdle()
            withContext(Dispatchers.Main) { models.clear() }
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        if (::icons.isInitialized) { withTimeout(5000) { icons.awaitImages() }; icons.close() }
        if (::tokens.isInitialized) tokens.clearTokens()
        if (::file.isInitialized) assertTrue(file.delete())
        val namespace = File(app.filesDir, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}")
        if (namespace.exists()) assertTrue(namespace.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    private fun touch(tag: String) {
        compose.onNodeWithTag("icon-library-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun preview() {
        touch("icon-library-import")
        compose.waitUntil(5000) { model.state.value.source?.preview != null && !model.state.value.busy }
    }
    private fun assertPixels(assetId: String, color: Int) {
        val tag = "icon-library-image:$assetId"
        compose.onNodeWithTag("icon-library-list").performScrollToNode(hasTestTag(tag))
        compose.waitUntil(5000) {
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].toArgb() == color
        }
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(color, pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
    @Test fun pickerPreviewNativePngSvgDarkAndTemplatePixelsDoNotInstallOrSelect() {
        preview(); assertEquals(1, launches.get())
        assertTrue(model.state.value.catalog!!.packs.isEmpty())
        assertPixels(id(11), IconLibraryFixture.red)
        assertPixels(id(12), tint)
        touch("icon-library-dark")
        assertPixels(id(11), IconLibraryFixture.green)
        assertPixels(id(12), tint)
        assertTrue(model.state.value.catalog!!.packs.isEmpty())
        assertNull(model.state.value.catalog!!.selection.pack)
    }
    @Test fun installAndChoiceUseActualTouchesAndClearingDoesNotDeletePackBytes() {
        preview(); touch("icon-library-install")
        compose.waitUntil(5000) { model.state.value.installed && !model.state.value.busy }
        val version = IconPackVersion(id(10), 1)
        assertEquals(setOf(version), model.state.value.catalog!!.readyVersions)
        assertNull(model.state.value.catalog!!.selection.pack)
        touch("icon-library-select:${id(10)}:1")
        compose.waitUntil(5000) { model.state.value.catalog?.selection?.pack == version }
        touch("icon-library-clear")
        compose.waitUntil(5000) { model.state.value.catalog?.selection?.generation == 2L }
        assertNull(model.state.value.catalog!!.selection.pack)
        touch("icon-library-inspect:${id(10)}:1")
        assertPixels(id(11), IconLibraryFixture.red)
        assertEquals(1, launches.get())
    }
    @Test fun logoutDropsPageDirectoryAndFrozenPixelsWithoutRemovingDurableInstallation() {
        preview(); touch("icon-library-install")
        compose.waitUntil(5000) { model.state.value.installed && !model.state.value.busy }
        assertPixels(id(11), IconLibraryFixture.red)
        val namespace = File(app.filesDir, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}")
        val files = namespace.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first }
        runBlocking { tokens.clearTokens() }
        compose.waitUntil(5000) { model.state.value.error == "ICON_ACCESS_DENIED" }
        assertNull(model.state.value.catalog); assertNull(model.state.value.source)
        compose.onNodeWithTag("icon-library-image:${id(11)}").assertDoesNotExist()
        compose.onNodeWithTag("icon-library-import").assertIsNotEnabled()
        assertEquals(files, namespace.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first })
    }
}
