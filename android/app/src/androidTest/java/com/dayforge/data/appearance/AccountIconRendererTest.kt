package com.dayforge.data.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.PlaintextTokenCipher
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.ui.theme.toComposeImage
import com.dayforge.widget.base.toGlanceImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconRendererTest {
    private class ReturnBarrier : CoroutineDispatcher(), java.io.Closeable {
        private val delegate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val armed = AtomicBoolean(false)
        val reached = CountDownLatch(1)
        private val queued = AtomicReference<Pair<CoroutineContext, Runnable>?>(null)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (armed.compareAndSet(true, false)) {
                check(queued.compareAndSet(null, context to block)); reached.countDown()
            } else delegate.dispatch(context, block)
        }
        fun release() { queued.getAndSet(null)?.let { (context, block) -> delegate.dispatch(context, block) } }
        override fun close() { release(); delegate.close() }
    }
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: DataStore<Preferences>
    private val memory = AccountIconMemory()
    private lateinit var db: AccountIconDatabase
    private lateinit var repo: AccountIconRepository
    private lateinit var store: AccountIconStore
    private val sessions = AccountSessionCoordinator()
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private fun id(n: Int) = "98000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val theme get() = ThemeVersionRef(id(20), 1)
    private fun svg(color: String = "#ff0000") =
        """<svg width="1" height="1"><rect width="1" height="1" fill="$color"/></svg>""".toByteArray()
    private fun blob(bytes: ByteArray, png: Boolean = false) = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size,
        if (png) "image/png" else "image/svg+xml", 1, 1)
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    private suspend fun installed(mode: String = "template", png: Boolean = false, withDark: Boolean = false,
        assetId: String = id(10)): AccountIconContext {
        val light = if (png) {
            val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try { image.eraseColor(red); ByteArrayOutputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
            } } finally { image.recycle() }
        } else svg()
        val dark = svg("#0000ff")
        val asset = IconAsset(assetId, "icon", "general", mode, blob(light, png), if (withDark) blob(dark) else null)
        val context = repo.capture()
        repo.reserveAsset(context, asset)
        store.install(context, assetId, asset.light.sha256, light)
        if (withDark) store.install(context, assetId, asset.dark!!.sha256, dark)
        return context
    }
    private suspend fun draw(renderer: AccountIconRenderer, context: AccountIconContext, tint: Int = blue,
        size: IconRasterSize = IconRasterSize(8, 8), dark: Boolean = false,
        version: ThemeVersionRef = theme, assetId: String = id(10)) =
        renderer.render(context, assetId, version, dark, size, tint)
    private suspend fun rejected(action: suspend () -> Unit) {
        var failed = false
        try { action() } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
        assertTrue("Operation must fail", failed)
    }
    private suspend fun outcome(action: suspend () -> Unit): Throwable? = try { action(); null } catch (e: Exception) { e }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-render-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") })
        tokens = TokenManager(preferences, PlaintextTokenCipher, memory)
        db = AccountIconDatabase.open(app); repo = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(repo, AccountIconFiles(parent)); login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun pngAndSvgHitsReuseImmutablePixelsButRespectColorMode() = runBlocking<Unit> {
        val renderer = AccountIconRenderer(repo, store)
        for ((n, png) in listOf(10 to false, 11 to true)) {
            val context = installed(png = png, assetId = id(n))
            val first = draw(renderer, context, assetId = id(n))
            assertSame(first, draw(renderer, context, assetId = id(n)))
            assertEquals(blue, first.bitmap.getPixel(4, 4)); assertFalse(first.bitmap.isMutable)
            assertEquals(blue, first.toComposeImage().toPixelMap()[4, 4].toArgb())
            assertNotNull(first.toGlanceImage())
        }
        val context = installed(mode = "original", assetId = id(12))
        val original = draw(renderer, context, tint = 0, assetId = id(12))
        assertEquals(red, original.bitmap.getPixel(4, 4))
    }

    @Test fun themeRevisionIdentityModeTintSizeAndAssetHaveSeparateCacheEntries() = runBlocking<Unit> {
        val context = installed(withDark = true)
        installed(assetId = id(11))
        val renderer = AccountIconRenderer(repo, store)
        val first = draw(renderer, context)
        for (other in listOf(
            draw(renderer, context, version = theme.copy(revision = 2)),
            draw(renderer, context, version = ThemeVersionRef(id(21), 1)),
            draw(renderer, context, dark = true),
            draw(renderer, context, tint = red),
            draw(renderer, context, size = IconRasterSize(4, 16)),
            draw(renderer, context, assetId = id(11))
        )) assertNotSame(first, other)
        assertSame(first, draw(renderer, context))
        assertEquals(red, draw(renderer, context, tint = red).bitmap.getPixel(4, 4))
        assertEquals(4, draw(renderer, context, size = IconRasterSize(4, 16)).width)
        val originalContext = installed(mode = "original", withDark = true, assetId = id(12))
        assertEquals(blue, draw(renderer, originalContext, dark = true, tint = red, assetId = id(12)).bitmap.getPixel(4, 4))
        val fallback = draw(renderer, context, dark = true, assetId = id(11))
        assertEquals(blue, fallback.bitmap.getPixel(4, 4))
    }

    @Test fun accountServerEpochAndReauthenticationNeverReuseOldRaster() = runBlocking<Unit> {
        val renderer = AccountIconRenderer(repo, store)
        var context = installed(); var previous = draw(renderer, context)
        for ((owner, server, epoch) in listOf(Triple(id(5), id(2), id(3)), Triple(id(1), id(6), id(3)),
            Triple(id(1), id(2), id(7)), Triple(id(1), id(2), id(3)), Triple(id(1), id(2), id(3)))) {
            val old = context; login(owner, server, epoch); context = installed()
            rejected { draw(renderer, old) }
            val next = draw(renderer, context); assertNotSame(previous, next)
            assertFalse(previous.bitmap.isRecycled); previous = next
        }
    }

    @Test fun readOnlyRecapturedDeviceMayRenderButOldPermissionSnapshotCannot() = runBlocking<Unit> {
        val old = installed(); val renderer = AccountIconRenderer(repo, store); val first = draw(renderer, old)
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2) }
        rejected { draw(renderer, old) }
        val second = draw(renderer, repo.capture()); assertNotSame(first, second)
        assertEquals(blue, second.bitmap.getPixel(4, 4))
        sessions.exclusive { tokens.clearTokens() }
        rejected { draw(renderer, repo.capture()) }; rejected { draw(renderer, old) }
    }

    @Test fun unownedMissingAndNotReadyAssetsNeverResolveFromHashOrMemory() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); draw(renderer, context)
        rejected { draw(renderer, context, assetId = id(99)) }
        repo.reserveAsset(context, IconAsset(id(12), "pending", "general", "template", blob(svg("#00ff00")), null))
        rejected { draw(renderer, context, assetId = id(12)) }
        login(owner = id(5)); rejected { draw(renderer, repo.capture()) }
        login(); assertEquals(blue, draw(renderer, repo.capture()).bitmap.getPixel(4, 4))
    }

    @Test fun missingCorruptAndUnrecognizedReadyFilesAreRejectedEvenOnMemoryHit() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); val first = draw(renderer, context)
        val target = File(parent, "account-icons-v1/${id(1)}/${id(2)}/${id(3)}/${blob(svg()).sha256}")
        target.writeBytes("corrupt".toByteArray()); rejected { draw(renderer, context) }
        assertFalse(first.bitmap.isRecycled); target.writeBytes(svg())
        assertSame(first, draw(renderer, context))
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET validationProfile='unknown'")
        rejected { draw(renderer, context) }
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET validationProfile='svg-v1'")
        assertTrue(target.delete()); rejected { draw(renderer, context) }
    }

    @Test fun lruByteAndEntryBudgetsEvictWithoutRecyclingConsumerPixels() = runBlocking<Unit> {
        val context = installed()
        for (renderer in listOf(AccountIconRenderer(repo, store, byteLimit = 512), AccountIconRenderer(repo, store, entryLimit = 2))) {
            val a = draw(renderer, context, tint = red); val b = draw(renderer, context, tint = blue)
            assertSame(a, draw(renderer, context, tint = red)) // Touch a, so b is least recent.
            draw(renderer, context, tint = 0xff00ff00.toInt())
            assertSame(a, draw(renderer, context, tint = red))
            assertNotSame(b, draw(renderer, context, tint = blue))
            assertFalse(a.bitmap.isRecycled); assertFalse(b.bitmap.isRecycled)
            assertEquals(red, a.bitmap.getPixel(4, 4))
        }
    }

    @Test fun oversizeAndDisabledCachesStillReturnValidUnretainedPixels() = runBlocking<Unit> {
        val context = installed()
        for (renderer in listOf(AccountIconRenderer(repo, store, byteLimit = 255), AccountIconRenderer(repo, store, byteLimit = 0),
            AccountIconRenderer(repo, store, entryLimit = 0))) {
            val a = draw(renderer, context); val b = draw(renderer, context)
            assertNotSame(a, b); assertEquals(blue, a.bitmap.getPixel(4, 4)); assertFalse(a.bitmap.isRecycled)
        }
        assertThrows(IllegalArgumentException::class.java) { AccountIconRenderer(repo, store, byteLimit = 8_388_609) }
        assertThrows(IllegalArgumentException::class.java) { AccountIconRenderer(repo, store, entryLimit = 129) }
    }

    @Test fun explicitInvalidationReleasesOnlyMemoryAndColdRendererReadsOwnedFiles() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store)
        val before = draw(renderer, context); renderer.invalidate()
        val after = draw(renderer, context); assertNotSame(before, after); assertFalse(before.bitmap.isRecycled)
        assertArrayEquals(svg(), store.read(context, id(10), blob(svg()).sha256))
        assertEquals(blue, draw(AccountIconRenderer(repo, store), context).bitmap.getPixel(4, 4))
    }

    @Test fun everyAuthenticationAndReplicaMutationImmediatelyEvictsAllRegisteredRenderers() = runBlocking<Unit> {
        val first = AccountIconRenderer(repo, store); val second = AccountIconRenderer(repo, store)
        val mutations: List<suspend () -> Unit> = listOf(
            { tokens.clearTokens() }, { tokens.clearAuthenticationTokens() },
            { tokens.clearRejectedRefresh(requireNotNull(tokens.authenticationSnapshot())) },
            { tokens.saveLoginSession("new", "refresh", "member", id(1), false) },
            { tokens.saveTokens("new", "refresh", "member", id(5), false) },
            { tokens.prepareSyncAccount(id(5)) }, { tokens.saveSyncDeviceId(id(6)) },
            { tokens.saveServerIdentity(id(6), id(3)) }, { tokens.saveServerIdentity(id(2), id(7)) },
            { tokens.resetReplicaForEpoch(id(2), id(7)) }, { tokens.clearSyncState() },
            { tokens.markStructuralEditingDenied() },
            { tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 2) },
            { tokens.saveDeviceRegistration(id(4), emptySet(), false, 1) },
            { tokens.saveDeviceRegistration(id(4), setOf("facts.append"), false, 1) }
        )
        for (change in mutations) {
            login(); val context = installed()
            val a = draw(first, context); val b = draw(second, context)
            assertEquals(1 to 256, first.memoryUsage()); assertEquals(1 to 256, second.memoryUsage())
            change() // No subsequent capture/render/Flow collection to trigger the eviction.
            assertEquals(0 to 0, first.memoryUsage()); assertEquals(0 to 0, second.memoryUsage())
            assertFalse(a.bitmap.isRecycled); assertFalse(b.bitmap.isRecycled)
            assertEquals(blue, a.bitmap.getPixel(4, 4))
        }
    }

    @Test fun refreshCursorAndUnrelatedPreferenceChangesDoNotEvictOrInvalidateContext() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); val first = draw(renderer, context)
        val old = requireNotNull(tokens.authenticationSnapshot())
        assertTrue(tokens.saveRefreshedTokens(old, "refreshed", "new-refresh", "renamed", id(1), true))
        assertFalse(tokens.saveRefreshedTokens(old, "stale", "stale", "old", id(1), false))
        tokens.clearRejectedRefresh(old); tokens.migrateLegacyTokenStorage()
        tokens.getOrCreateInstallationId(); tokens.saveUserEmail("member-renamed")
        tokens.saveSyncCursor(42); tokens.requireSyncBootstrap(); tokens.prepareSyncAccount(id(1))
        tokens.saveServerIdentity(id(2), id(3)); tokens.saveSyncDeviceId(id(4))
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), false, 1)
        assertEquals(1 to 256, renderer.memoryUsage()); assertSame(first, draw(renderer, context))
    }

    @Test fun logoutAndOtherReplicasRetainReadyBytesAndPendingIntentAcrossColdReopen() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); draw(renderer, context)
        repo.reserveAsset(context, IconAsset(id(12), "pending", "general", "template", blob(svg("#00ff00")), null))
        val before = repo.installations(context)
        tokens.clearTokens(); assertEquals(0 to 0, renderer.memoryUsage())
        login(owner = id(5)); assertNull(repo.asset(repo.capture(), id(10)))
        login(server = id(6)); assertNull(repo.asset(repo.capture(), id(10)))
        login(epoch = id(7)); assertNull(repo.asset(repo.capture(), id(10)))
        db.close(); db = AccountIconDatabase.open(app)
        repo = AccountIconRepository(db, tokens, sessions); store = AccountIconStore(repo, AccountIconFiles(parent))
        login(); val current = repo.capture()
        assertEquals(before, repo.installations(current))
        assertEquals("svg-v1", repo.installation(current, id(10), blob(svg()).sha256).validationProfile)
        assertNull(repo.installation(current, id(12), blob(svg("#00ff00")).sha256).validationProfile)
        assertArrayEquals(svg(), store.read(current, id(10), blob(svg()).sha256))
        assertEquals(blue, draw(AccountIconRenderer(repo, store), current).bitmap.getPixel(4, 4))
    }

    @Test fun cancelledWriteJoinsRealPersistenceAndBlocksOldAndNewRendererUntilItFinishes() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); draw(renderer, context)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val delayed = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { original -> transform(original).also {
                    reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
                } }
        }
        val writer = TokenManager(delayed, PlaintextTokenCipher, memory)
        val write = async(Dispatchers.IO) { writer.clearAuthenticationTokens() }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS)); assertEquals(0 to 0, renderer.memoryUsage())
            write.cancel(); assertFalse(write.isCompleted)
            rejected { draw(renderer, context) }
            rejected { draw(AccountIconRenderer(repo, store), context) }
        } finally { release.countDown() }
        write.join(); assertTrue(write.isCancelled); assertNull(tokens.authenticationSnapshot())
        assertEquals(0 to 0, renderer.memoryUsage()); rejected { draw(renderer, context) }
        login(); assertArrayEquals(svg(), store.read(repo.capture(), id(10), blob(svg()).sha256))
        assertEquals(blue, draw(renderer, repo.capture()).bitmap.getPixel(4, 4))
    }

    @Test fun failedWriteAfterTransformEvictsMemoryButPreservesActualOwnerAndReadyJournal() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); val first = draw(renderer, context)
        val before = repo.installations(context)
        val broken = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { original -> transform(original); throw IOException("synthetic write failure") }
        }
        val writer = TokenManager(broken, PlaintextTokenCipher, memory)
        assertTrue(outcome { writer.clearTokens() } is IOException)
        assertEquals(0 to 0, renderer.memoryUsage()); assertFalse(first.bitmap.isRecycled)
        assertEquals(context.access, tokens.localIconAccess()); assertEquals(before, repo.installations(context))
        assertNotSame(first, draw(renderer, context)); assertEquals(blue, draw(renderer, context).bitmap.getPixel(4, 4))
    }

    @Test fun cancellationBeforeQueuedTransformKeepsPersistedSessionAndExistingCache() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store); val first = draw(renderer, context)
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val queued = CountDownLatch(1)
        val occupied = async(Dispatchers.IO) { preferences.edit {
            reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
            it[stringPreferencesKey("unrelated_test_key")] = "synthetic"
        } }
        val waiting = object : DataStore<Preferences> {
            override val data = preferences.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                queued.countDown(); return preferences.updateData(transform)
            }
        }
        var write: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            write = async(Dispatchers.IO) { TokenManager(waiting, PlaintextTokenCipher, memory).clearTokens() }
            assertTrue(queued.await(5, TimeUnit.SECONDS)); write.cancel(); assertFalse(write.isCompleted)
            assertEquals(1 to 256, renderer.memoryUsage())
        } finally { release.countDown() }
        occupied.await(); write!!.join(); assertTrue(write.isCancelled)
        assertEquals(context.access, tokens.localIconAccess()); assertEquals(1 to 256, renderer.memoryUsage())
        assertSame(first, draw(renderer, context))
    }

    @Test fun logoutDuringBlockingRealDrawDoesNotWaitForAccountLockOrPublishOldRaster() = runBlocking<Unit> {
        val context = installed(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val renderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
            reached.countDown(); check(release.await(5, TimeUnit.SECONDS)); renderIcon(source, asset, dark, size, tint)
        })
        val result = async(Dispatchers.IO) { outcome { draw(renderer, context) } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login() } }
        finally { release.countDown() }
        assertNotNull(result.await()); assertEquals(blue, draw(renderer, repo.capture()).bitmap.getPixel(4, 4))
    }

    @Test fun explicitInvalidationDuringBlockingDrawPreventsRefillAndAllowsFreshRetry() = runBlocking<Unit> {
        val context = installed(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val count = AtomicInteger()
        val renderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
            count.incrementAndGet(); reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
            renderIcon(source, asset, dark, size, tint)
        })
        val result = async(Dispatchers.IO) { outcome { draw(renderer, context) } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); renderer.invalidate() }
        finally { release.countDown() }
        assertNotNull(result.await()); draw(renderer, context); assertEquals(2, count.get())
    }

    @Test fun cancellationJoinsActualRenderBeforeSecondRendererCanDraw() = runBlocking<Unit> {
        val context = installed(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val secondDraws = AtomicInteger()
        val firstRenderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
            reached.countDown(); check(release.await(5, TimeUnit.SECONDS)); renderIcon(source, asset, dark, size, tint)
        })
        val secondRenderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
            secondDraws.incrementAndGet(); renderIcon(source, asset, dark, size, tint)
        })
        val first = async(Dispatchers.IO) { draw(firstRenderer, context) }
        var second: kotlinx.coroutines.Deferred<IconRaster>? = null
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS)); first.cancel()
            withTimeout(2_000) { login() } // No account lock held by the cancelled blocking renderer.
            val current = repo.capture()
            val started = CountDownLatch(1)
            second = async(Dispatchers.IO) { started.countDown(); draw(secondRenderer, current) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(first.isCompleted); assertEquals(0, secondDraws.get())
        } finally { release.countDown() }
        first.join(); assertTrue(first.isCancelled)
        assertEquals(blue, second!!.await().bitmap.getPixel(4, 4)); assertEquals(1, secondDraws.get())
    }

    @Test fun reauthenticationAfterIoBeforeCallerResumeRejectsAlreadyDrawnResult() = runBlocking<Unit> {
        val context = installed()
        ReturnBarrier().use { barrier ->
            val renderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
                renderIcon(source, asset, dark, size, tint).also { barrier.armed.set(true) }
            })
            val result = async(barrier) { outcome { draw(renderer, context) } }
            try { assertTrue(barrier.reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login() } }
            finally { barrier.release() }
            assertNotNull(result.await())
        }
    }

    @Test fun invalidationAfterIoBeforeCallerResumeRejectsAlreadyCachedResult() = runBlocking<Unit> {
        val context = installed(); val count = AtomicInteger()
        ReturnBarrier().use { barrier ->
            val renderer = AccountIconRenderer(repo, store, draw = { source, asset, dark, size, tint ->
                count.incrementAndGet()
                renderIcon(source, asset, dark, size, tint).also { if (count.get() == 1) barrier.armed.set(true) }
            })
            val result = async(barrier) { outcome { draw(renderer, context) } }
            try { assertTrue(barrier.reached.await(5, TimeUnit.SECONDS)); renderer.invalidate() }
            finally { barrier.release() }
            assertNotNull(result.await()); draw(renderer, context); assertEquals(2, count.get())
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun ownedCachedRasterProducesSameActualComposeAndGlancePixelsWithoutActivity() = runBlocking<Unit> {
        val context = installed(); val renderer = AccountIconRenderer(repo, store)
        val raster = draw(renderer, context, size = IconRasterSize(32, 32))
        assertSame(raster, draw(renderer, context, size = IconRasterSize(32, 32)))
        assertEquals(blue, raster.toComposeImage().toPixelMap()[16, 16].toArgb())
        val views = GlanceRemoteViews().compose(app, DpSize(32.dp, 32.dp)) {
            Image(raster.toGlanceImage(), "owned icon", GlanceModifier.size(32.dp))
        }.remoteViews
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = views.apply(app, FrameLayout(app))
            val px = (32 * app.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, px, px)
            val pixels = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            try { root.draw(Canvas(pixels)); assertEquals(blue, pixels.getPixel(px / 2, px / 2)) }
            finally { pixels.recycle() }
        }
        renderer.invalidate(); assertFalse(raster.bitmap.isRecycled)
    }
}
