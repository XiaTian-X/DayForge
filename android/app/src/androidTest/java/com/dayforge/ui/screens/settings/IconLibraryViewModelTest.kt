package com.dayforge.ui.screens.settings

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.TokenCipher
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.service.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconLibraryViewModelTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private lateinit var repo: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var renderer: AccountIconRenderer
    private lateinit var controller: AccountIconController
    private lateinit var model: IconLibraryViewModel
    private val models = ViewModelStore()
    private val sessions = AccountSessionCoordinator()
    private val opens = AtomicInteger()
    private val constructions = AtomicInteger()
    private var openDocument: ((Uri, CancellationSignal) -> AssetFileDescriptor?)? = null
    private var drawBarrier: (() -> Unit)? = null
    private fun id(n: Int) = IconLibraryFixture.id(n)
    private val uri get() = Uri.fromFile(File(directory, "pack.zip"))
    private suspend fun login(owner: String = id(1), write: Boolean = true) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(id(4), if (write) setOf("sync.read", "structure.write") else setOf("sync.read"), true, 1)
    }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: (IconLibraryState) -> Boolean): IconLibraryState = withTimeout(5000) { model.state.first(check) }
    private suspend fun open() { main { model.openPage() }; await { it.catalog != null && !it.loading } }
    private suspend fun preview() {
        main { assertTrue(model.beginPicker()); model.pickerResult(uri) }
        await { it.source?.preview != null && !it.busy }
    }
    private suspend fun install() { main { model.install() }; await { it.installed && !it.busy } }
    private suspend fun dispose() {
        if (::model.isInitialized) {
            main { models.clear() }
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(), "icon-library-").toFile()
        File(directory, "pack.zip").writeBytes(IconLibraryFixture.archive())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app); repo = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(repo, AccountIconFiles(directory))
        renderer = AccountIconRenderer(repo, store, draw = { bytes, asset, dark, size, tint ->
            val result = renderIcon(bytes, asset, dark, size, tint)
            drawBarrier?.invoke(); result
        })
        val imports = AccountIconImport(repo, store, AccountIconTransfers(db, repo, store))
        val documents = AccountIconDocuments(imports, { selected, signal ->
            opens.incrementAndGet()
            openDocument?.invoke(selected, signal) ?: AssetFileDescriptor(
                ParcelFileDescriptor.open(File(selected.path!!), ParcelFileDescriptor.MODE_READ_ONLY), 0, File(selected.path!!).length())
        })
        controller = AccountIconController({ constructions.incrementAndGet()
            AccountIconRuntime(repo, store, renderer, imports, documents, db::close) }, tokens)
        login()
        main { model = IconLibraryViewModel(controller); models.put("icons", model) }
    }
    @After fun cleanup() = runBlocking<Unit> {
        dispose()
        if (::controller.isInitialized) controller.close()
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::directory.isInitialized) assertTrue(directory.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    @Test fun constructionAndCancelledPickerDoNotOpenStorageProviderOrInstall() = runBlocking<Unit> {
        assertEquals(0, constructions.get()); assertEquals(0, opens.get())
        open(); assertEquals(1, constructions.get())
        main { assertTrue(model.beginPicker()); assertFalse(model.beginPicker()); model.pickerResult(null) }
        assertFalse(model.state.value.picking); assertNull(model.state.value.error)
        assertEquals(0, opens.get()); assertEquals(emptyList<IconReservation>(), repo.reservations(repo.capture()))
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
    }
    @Test fun previewInstallSelectionAndClearAreDistinctAndColdPersistent() = runBlocking<Unit> {
        open(); preview()
        assertEquals(1, opens.get()); assertEquals(emptyList<IconReservation>(), repo.reservations(repo.capture()))
        val frozen = model.state.value.source!!.preview
        File(directory, "pack.zip").writeBytes(byteArrayOf(0))
        install(); assertSame(frozen, model.state.value.source!!.preview)
        val catalog = model.state.value.catalog!!
        val version = IconPackVersion(id(10), 1)
        assertEquals(setOf(version), catalog.readyVersions)
        assertEquals(AccountIconSelection(0, null), catalog.selection); assertEquals(1, opens.get())
        main { model.select(version) }; await { it.catalog?.selection?.pack == version }
        assertNull(model.state.value.source)
        main { model.select(null) }; await { it.catalog?.selection == AccountIconSelection(2, null) }
        db.close(); db = AccountIconDatabase.open(app)
        val cold = AccountIconRepository(db, tokens, sessions).library(repo.capture())
        assertEquals(AccountIconSelection(2, null), cold.selection)
        assertEquals(catalog.packs, cold.packs); assertEquals(catalog.readyVersions, cold.readyVersions)
    }
    @Test fun latePickerAndUnsolicitedRestoredResultCannotInstallForNewAccount() = runBlocking<Unit> {
        open(); main { assertTrue(model.beginPicker()) }
        login(id(5)); await { it.context?.namespace?.accountId == id(5) }
        main { assertFalse(model.beginPicker()); model.pickerResult(uri) }
        assertEquals("ICON_PICKER_EXPIRED", model.state.value.error); assertEquals(0, opens.get())
        assertTrue(model.state.value.catalog!!.packs.isEmpty())
        main { model.pickerResult(uri) }; assertEquals("ICON_PICKER_EXPIRED", model.state.value.error)
        preview(); install()
        assertEquals(id(5), model.state.value.source!!.context.namespace.accountId)
        login(); await { it.context?.namespace?.accountId == id(1) }
        assertTrue(model.state.value.catalog!!.packs.isEmpty()); assertNull(model.state.value.source)
    }
    @Test fun restoredResultBeforeDirectoryLoadRetainsExpiredNoticeAndNeverOpensProvider() = runBlocking<Unit> {
        main { model.pickerResult(uri) }
        open()
        assertEquals("ICON_PICKER_EXPIRED", model.state.value.error)
        assertEquals(0, opens.get()); assertTrue(model.state.value.catalog!!.packs.isEmpty())
        preview(); assertNull(model.state.value.error)
        assertEquals(1, opens.get()); assertTrue(model.state.value.catalog!!.packs.isEmpty())
    }
    @Test fun permissionLossClearsPreviewAndReadOnlyCannotDeclareButMayChooseExistingPack() = runBlocking<Unit> {
        open(); preview(); install()
        login(write = false); await { it.context?.access?.canDeclare == false && it.catalog != null }
        assertNull(model.state.value.source)
        preview(); main { model.install() }; await { it.error == "ICON_DECLARATION_DENIED" && !it.busy }
        assertNotNull(model.state.value.source?.preview)
        main { model.select(IconPackVersion(id(10), 1)) }; await { it.catalog?.selection?.pack == IconPackVersion(id(10), 1) }
        assertFalse(model.state.value.context!!.access.canDeclare)
    }
    @Test fun failedInstallationRetainsExactFrozenPreviewAndExplicitRetryRepairsOnlyMissingReceipt() = runBlocking<Unit> {
        open(); preview()
        val original = model.state.value.source!!.preview
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_library_ready BEFORE INSERT ON icon_blob_ready BEGIN SELECT RAISE(ABORT,'synthetic ready failure'); END")
        main { model.install() }; await { it.error != null && !it.busy }
        assertSame(original, model.state.value.source!!.preview)
        assertFalse(model.state.value.installed); assertEquals(1, opens.get())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_library_ready")
        install(); assertSame(original, model.state.value.source!!.preview); assertEquals(1, opens.get())
        assertEquals(AccountIconSelection(0, null), model.state.value.catalog!!.selection)
    }
    @Test fun closeDuringRealProviderReadJoinsAndDoesNotRepopulatePage() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val entered = CountDownLatch(1)
        openDocument = { _, _ -> entered.countDown(); AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
        try {
            open(); main { assertTrue(model.beginPicker()); model.pickerResult(uri) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            main { model.closePage() }; dispose()
            assertEquals(IconLibraryState(), model.state.value)
            assertFalse(pipe[0].fileDescriptor.valid())
            assertEquals(emptyList<IconReservation>(), repo.reservations(repo.capture()))
        } finally { pipe.forEach { it.close() } }
    }
    @Test fun imageHandlesDropPublishedPixelsSynchronouslyAndNeverRecycleBorrowedBitmap() = runBlocking<Unit> {
        open(); preview()
        val source = model.state.value.source!!
        val handle = controller.image()
        controller.load(handle, source, id(11), ThemeVersionRef(id(20), 1), false, IconRasterSize(8, 8), IconLibraryFixture.green)
        val raster = (handle.state.value as IconImageState.Ready).raster
        assertEquals(IconLibraryFixture.red, raster.bitmap.getPixel(4, 4))
        assertEquals(0 to 0, renderer.memoryUsage()) // Frozen preview is not a catalog cache entry.
        sessions.exclusive { tokens.clearTokens(); assertSame(IconImageState.Empty, handle.state.value)
            assertNull(model.state.value.source); assertNull(model.state.value.catalog) }
        assertFalse(raster.bitmap.isRecycled)
        controller.load(handle, source, id(11), ThemeVersionRef(id(20), 1), false, IconRasterSize(8, 8), 0)
        assertFalse(handle.state.value is IconImageState.Ready)
        handle.close(); assertSame(IconImageState.Empty, handle.state.value)
    }
    @Test fun corruptInstalledFileIsNotRepairedByInspectOrSelection() = runBlocking<Unit> {
        open(); preview(); install()
        val source = model.state.value.source!!
        val hash = source.pack.assets.first().light.sha256
        val ns = source.context.namespace
        val file = File(directory, "account-icons-v1/${ns.accountId}/${ns.serverInstanceId}/${ns.syncEpoch}/$hash")
        assertTrue(file.isFile); file.writeBytes(byteArrayOf(0))
        main { model.select(IconPackVersion(id(10), 1)) }; await { it.error != null && !it.busy }
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
        assertArrayEquals(byteArrayOf(0), file.readBytes())
        main { model.inspect(model.state.value.catalog!!.packs.single()) }
        val handle = controller.image()
        controller.load(handle, model.state.value.source!!, id(11), ThemeVersionRef(id(20), 1), false, IconRasterSize(8, 8), 0)
        assertTrue(handle.state.value is IconImageState.Failed); assertArrayEquals(byteArrayOf(0), file.readBytes())
        handle.close()
    }
    @Test fun rolledBackSelectionTransitionReportsFailureAndReloadsActualUnchangedChoice() = runBlocking<Unit> {
        open(); preview(); install()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_library_selection BEFORE INSERT ON icon_pack_selection BEGIN SELECT RAISE(ABORT,'synthetic selection failure'); END")
        main { model.select(IconPackVersion(id(10), 1)) }
        await { it.error == "ICON_LIBRARY_FAILED" && it.catalog != null && !it.busy }
        assertEquals(AccountIconSelection(0, null), model.state.value.catalog!!.selection)
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
        assertNull(model.state.value.source)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_library_selection")
        main { model.select(IconPackVersion(id(10), 1)) }; await { it.catalog?.selection?.generation == 1L }
        assertNull(model.state.value.error)
    }
    @Test fun authenticationDuringPreviewDrawingRejectsLatePixelsAndJoinsNativeWork() = runBlocking<Unit> {
        open(); preview()
        val source = model.state.value.source!!
        val handle = controller.image()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        drawBarrier = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val draw = async(Dispatchers.IO) {
            controller.load(handle, source, id(11), ThemeVersionRef(id(20), 1), false, IconRasterSize(8, 8), 0)
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            login(id(5)); assertSame(IconImageState.Empty, handle.state.value)
            release.countDown(); draw.await(); controller.awaitImages()
            assertSame(IconImageState.Empty, handle.state.value)
            await { it.context?.namespace?.accountId == id(5) }; assertNull(model.state.value.source)
            assertTrue(model.state.value.catalog!!.packs.isEmpty())
        } finally { release.countDown(); draw.cancelAndJoin(); handle.close() }
    }
    @Test fun productionControllerGuardsRunOffMainAndStillProveCurrentAccessWithoutUnusedRefreshDecryption() = runBlocking<Unit> {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var enforceBackground = false
        val cipher = object : TokenCipher {
            override fun encrypt(value: String) = "encrypted:$value"
            override fun decrypt(value: String): String? {
                if (enforceBackground) assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread())
                calls.add(value)
                return value.takeIf { it.startsWith("encrypted:") }?.removePrefix("encrypted:")
            }
        }
        val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "counted.preferences_pb") })
        val manager = TokenManager(preferences, cipher)
        manager.saveLoginSession("access", "refresh", "member", id(1), false)
        manager.saveServerIdentity(id(2), id(3))
        manager.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
        val metadata = AccountIconRepository(db, manager, sessions)
        val files = AccountIconStore(metadata, AccountIconFiles(directory))
        val imports = AccountIconImport(metadata, files, AccountIconTransfers(db, metadata, files))
        val actual = AccountIconController({ AccountIconRuntime(metadata, files, AccountIconRenderer(metadata, files), imports,
            AccountIconDocuments(imports, app.contentResolver), {}) }, manager)
        enforceBackground = true
        val context = withContext(Dispatchers.Main) {
            val captured = actual.capture()
            assertTrue(actual.library(captured).packs.isEmpty())
            actual.publish(captured) { assertNotSame(android.os.Looper.getMainLooper().thread, Thread.currentThread()) }
            captured
        }
        assertTrue(calls.size >= 5); assertTrue(calls.all { it == "encrypted:access" })
        calls.clear()
        preferences.edit { it[stringPreferencesKey("access_token")] = "corrupt-access" }
        assertNull(manager.localIconAccess()); assertEquals(listOf("corrupt-access"), calls.toList())
        preferences.edit { it[stringPreferencesKey("access_token")] = "encrypted:access"
            it[stringPreferencesKey("refresh_token")] = "corrupt-refresh" }
        calls.clear()
        assertEquals(context.access, manager.localIconAccess())
        assertEquals(listOf("encrypted:access"), calls.toList())
        calls.clear()
        val snapshot = manager.authenticationSnapshot()!!
        assertEquals(context.access.session.authentication, snapshot.session); assertNull(snapshot.refreshToken)
        assertEquals(listOf("encrypted:access", "corrupt-refresh"), calls.toList())
    }
}
