package com.dayforge.ui.components

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.*
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.appearance.ResolvedTheme
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.settings.IconLibraryFixture
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*

/** Real private files/Room/DataStore/native renderer; only the credential cipher is a counted boundary. */
internal class ObjectIconFixture {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope
    lateinit var directory: File
    lateinit var database: AccountIconDatabase
    lateinit var tokens: TokenManager
    lateinit var controller: AccountIconController
    val sessions = AccountSessionCoordinator()
    val decrypts = AtomicInteger()
    val draws = AtomicInteger()
    var drawBarrier: (() -> Unit)? = null
    private val handles = mutableListOf<IconImageHandle>()
    fun id(n: Int) = IconLibraryFixture.id(n)
    suspend fun open() {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(), "object-icons-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cipher = object : TokenCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String): String { decrypts.incrementAndGet(); return value }
        }
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(directory, "auth.preferences_pb") }), cipher)
        database = AccountIconDatabase.open(app)
        val metadata = AccountIconRepository(database, tokens, sessions)
        val store = AccountIconStore(metadata, AccountIconFiles(directory))
        val renderer = AccountIconRenderer(metadata, store, draw = { bytes, asset, dark, size, tint ->
            draws.incrementAndGet()
            val image = renderIcon(bytes, asset, dark, size, tint)
            drawBarrier?.invoke()
            image
        })
        val imports = AccountIconImport(metadata, store)
        controller = AccountIconController({ AccountIconRuntime(metadata, store, renderer, imports,
            AccountIconDocuments(imports, app.contentResolver), database::close) }, tokens)
        login()
    }
    suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    suspend fun install(offset: Int = 0, color: Int = IconLibraryFixture.red): AccountIconPackPreview {
        val file = File(directory, "pack-$offset.zip").also { it.writeBytes(IconLibraryFixture.archive(offset, color)) }
        val preview = controller.preview(controller.capture(), Uri.fromFile(file))
        controller.install(preview)
        return preview
    }
    suspend fun choose(offset: Int? = 0) {
        val context = controller.capture()
        val catalog = controller.library(context)
        controller.select(context, catalog.selection.generation, offset?.let { IconPackVersion(id(10 + it), 1) })
    }
    fun handle() = controller.referenceImage().also(handles::add)
    fun theme(dark: Boolean = false) = app.assets.open("appearance/themes/ocean-v1.json").use {
        ResolvedTheme.from(ValidatedTheme.parse(it.readBytes()).definition, dark)
    }
    suspend fun close() {
        handles.forEach(IconImageHandle::close)
        if (::controller.isInitialized) { controller.awaitImages(); controller.close() }
        if (::database.isInitialized) database.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::directory.isInitialized) assertTrue(directory.deleteRecursively())
        if (::database.isInitialized)
            assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
}
