package com.dayforge.data.appearance

import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconStoreTest {
    /** Freeze one real continuation at the IO -> caller dispatch boundary, not a fake repository. */
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private val sessions = AccountSessionCoordinator()
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "97000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun blob(bytes: ByteArray = svg, media: String = "image/svg+xml") = IconBlob(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size, media, 1, 1)
    private fun asset(bytes: IconBlob = blob()) = IconAsset(id(10), "icon", "general", "template", bytes, null)
    private fun repository() = AccountIconRepository(db, tokens, sessions)
    private fun store(io: IconFileIo = IconFileIo()) = AccountIconStore(repository(), AccountIconFiles(parent, io))
    private fun path(context: AccountIconContext) = File(parent,
        "account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}")
    private fun readyCount(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM icon_blob_ready").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    private suspend fun reserve(bytes: IconBlob = blob()): AccountIconContext {
        val repository = repository(); val context = repository.capture()
        repository.reserveAsset(context, asset(bytes)); return context
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        var failed = false
        try { action() } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
        assertTrue("Operation must fail", failed)
    }
    private suspend fun failedJob(action: suspend () -> Unit): Throwable? = try { action(); null } catch (error: Exception) { error }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-store-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app)
        login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun pngAndSvgInstallReadAndRetrySurviveColdReopen() = runBlocking<Unit> {
        val png = Base64.decode(Json.parseToJsonElement(instrumentation.context.assets.open("next/png.json")
            .bufferedReader().use { it.readText() }).jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
        val context = reserve()
        val repo = repository()
        val pngBlob = blob(png, "image/png")
        repo.reserveAsset(context, asset(pngBlob).copy(assetId = id(11)))
        for ((assetId, bytes, expected) in listOf(Triple(id(10), svg, blob()), Triple(id(11), png, pngBlob))) {
            val current = store(); current.install(context, assetId, expected.sha256, bytes)
            assertArrayEquals(bytes, current.read(context, assetId, expected.sha256))
            val target = File(path(context), expected.sha256)
            val inode = Os.lstat(target.path).st_ino
            current.install(context, assetId, expected.sha256, bytes)
            assertEquals(inode, Os.lstat(target.path).st_ino)
            val read = current.read(context, assetId, expected.sha256); read.fill(0)
            assertArrayEquals(bytes, current.read(context, assetId, expected.sha256))
        }
        assertEquals(2, readyCount())
        db.close(); db = AccountIconDatabase.open(app)
        assertArrayEquals(svg, store().read(context, id(10), blob().sha256))
        assertEquals(IconFileRecovery(2, 0, emptyList()), store().recover(context))
    }

    @Test fun allReplicaNamespacesRequireSeparateInstallationEvenWithIdenticalHashes() = runBlocking<Unit> {
        val first = reserve(); store().install(first, id(10), blob().sha256, svg)
        val original = File(path(first), blob().sha256)
        val inode = Os.lstat(original.path).st_ino
        for ((owner, server, epoch) in listOf(Triple(id(5), id(2), id(3)), Triple(id(1), id(6), id(3)), Triple(id(1), id(2), id(7)))) {
            login(owner, server, epoch)
            val next = reserve(); rejected { store().read(next, id(10), blob().sha256) }
            assertFalse(path(next).exists())
            rejected { store().read(first, id(10), blob().sha256) }
            store().install(next, id(10), blob().sha256, svg)
            assertNotEquals(inode, Os.lstat(File(path(next), blob().sha256).path).st_ino)
        }
        login(); val active = repository().capture()
        assertArrayEquals(svg, store().read(active, id(10), blob().sha256))
        assertEquals(inode, Os.lstat(original.path).st_ino); assertEquals(4, readyCount())
    }

    @Test fun unownedAssetWrongHashAndReadOnlyPermissionDoNotCreatePaths() = runBlocking<Unit> {
        val context = reserve()
        rejected { store().install(context, id(99), blob().sha256, svg) }
        rejected { store().install(context, id(10), "0".repeat(64), svg) }
        assertFalse(path(context).exists())
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2) }
        val readOnly = repository().capture()
        rejected { store().install(readOnly, id(10), blob().sha256, svg) }
        rejected { store().recover(readOnly) }; assertFalse(path(context).exists()); assertEquals(0, readyCount())
    }

    @Test fun invalidIncomingBytesCannotBecomeReadyOrOverwriteExistingBytes() = runBlocking<Unit> {
        val context = reserve(); val invalid = svg.copyOf().apply { this[0] = 0 }
        rejected { store().install(context, id(10), blob().sha256, invalid) }; assertEquals(0, readyCount())
        assertFalse(File(path(context), blob().sha256).exists())
        store().install(context, id(10), blob().sha256, svg)
        rejected { store().install(context, id(10), blob().sha256, invalid) }
        assertArrayEquals(svg, store().read(context, id(10), blob().sha256))
    }

    @Test fun failedWriteRecoveryOnlyCleansDurablyOwnedTemporaryAndDoesNotPromoteReady() = runBlocking<Unit> {
        val context = reserve(); val reservation = repository().reservations(context).single()
        val faulty = store(object : IconFileIo() { override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = 0 })
        rejected { faulty.install(context, id(10), blob().sha256, svg) }
        val known = File(path(context), ".install-${reservation.operationId}.part")
        val unknown = File(path(context), ".install-${id(98)}.part").apply { writeText("preserve") }
        assertTrue(known.exists()); assertEquals(0, readyCount())
        db.close(); db = AccountIconDatabase.open(app)
        val result = store().recover(context)
        assertEquals(IconFileRecovery(0, 1, listOf(unknown.name)), result)
        assertFalse(known.exists()); assertEquals("preserve", unknown.readText()); assertEquals(0, readyCount())
        assertEquals(reservation, repository().reservations(context).single())
        store().install(context, id(10), blob().sha256, svg); assertEquals(1, readyCount())
    }

    @Test fun failedDirectorySyncAfterRenameLeavesPendingFinalAndRetryUsesOriginalJournal() = runBlocking<Unit> {
        val context = reserve(); val reservation = repository().reservations(context).single()
        val fault = object : IconFileIo() {
            var published = false
            override fun rename(source: String, target: String) { super.rename(source, target); published = true }
            override fun sync(fd: FileDescriptor) {
                if (published && OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("synthetic sync")
                super.sync(fd)
            }
        }
        rejected { store(fault).install(context, id(10), blob().sha256, svg) }
        assertArrayEquals(svg, File(path(context), blob().sha256).readBytes()); assertEquals(0, readyCount())
        assertEquals(IconFileRecovery(0, 1, emptyList()), store().recover(context)); assertEquals(0, readyCount())
        store().install(context, id(10), blob().sha256, svg)
        assertEquals(reservation, repository().reservations(context).single()); assertEquals(1, readyCount())
    }

    @Test fun readyAbortAndIgnoredWritesRollBackButRetainValidatedFileForExactRetry() = runBlocking<Unit> {
        val context = reserve()
        for (action in listOf("ABORT, 'synthetic ready failure'", "IGNORE")) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_ready BEFORE INSERT ON icon_blob_ready BEGIN SELECT RAISE($action); END")
            rejected { store().install(context, id(10), blob().sha256, svg) }
            assertEquals(0, readyCount()); assertArrayEquals(svg, File(path(context), blob().sha256).readBytes())
            assertEquals(IconFileRecovery(0, 1, emptyList()), store().recover(context)); assertEquals(0, readyCount())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_ready")
        }
        store().install(context, id(10), blob().sha256, svg); assertEquals(1, readyCount())
    }

    @Test fun readyTriggerMutationIsDetectedBeforeCommitAndOtherNamespacesAreUnchanged() = runBlocking<Unit> {
        val context = reserve()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER damage_ready AFTER INSERT ON icon_blob_ready BEGIN UPDATE icon_blob_ready SET validationProfile='future'; END")
        rejected { store().install(context, id(10), blob().sha256, svg) }; assertEquals(0, readyCount())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_ready")
        store().install(context, id(10), blob().sha256, svg)
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET validationProfile='future'")
        rejected { store().read(context, id(10), blob().sha256) }; rejected { store().recover(context) }
        login(id(5)); val other = reserve()
        store().install(other, id(10), blob().sha256, svg); assertEquals(2, readyCount())
    }

    @Test fun missingOrCorruptReadyFilesFailAndKnownTemporaryIsNotCleanedOnFailure() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val final = File(path(context), blob().sha256)
        val known = File(path(context), ".install-${repository().reservations(context).single().operationId}.part").apply { writeText("partial") }
        for (missing in listOf(false, true)) {
            if (missing) assertTrue(final.delete()) else final.writeText("corrupt")
            rejected { store().read(context, id(10), blob().sha256) }; rejected { store().recover(context) }
            assertTrue(known.exists()); assertEquals(1, readyCount())
            rejected { store().install(context, id(10), blob().sha256, svg) }
        }
        assertEquals("partial", known.readText())
    }

    @Test fun pendingCorruptFinalAndUnknownSpecialFilesCannotAuthorizeCleanup() = runBlocking<Unit> {
        val context = reserve(); path(context).mkdirs()
        val journal = repository().reservations(context).single()
        val known = File(path(context), ".install-${journal.operationId}.part").apply { writeText("partial") }
        val final = File(path(context), blob().sha256).apply { writeText("bad") }
        rejected { store().recover(context) }; assertTrue(known.exists()); assertEquals(0, readyCount())
        assertTrue(final.delete())
        val special = File(path(context), "unknown")
        Os.symlink(known.path, special.path)
        rejected { store().recover(context) }; assertTrue(known.exists())
        Os.remove(special.path); Os.mkfifo(special.path, 384)
        rejected { store().recover(context) }; assertTrue(known.exists()); Os.remove(special.path)
        assertTrue(special.mkdir()); rejected { store().recover(context) }; assertTrue(known.exists())
        assertTrue(special.delete()); store().recover(context); assertFalse(known.exists())
    }

    @Test fun everyNamespaceAncestorRejectsLinksWithoutTouchingLinkTarget() = runBlocking<Unit> {
        val context = reserve()
        val segments = listOf("account-icons-v1", id(1), id(2), id(3))
        val target = File(parent, "unowned").apply { mkdir() }
        var base = parent
        for (segment in segments) {
            val link = File(base, segment); Os.symlink(target.path, link.path)
            rejected { store().install(context, id(10), blob().sha256, svg) }
            assertTrue(target.list()!!.isEmpty()); assertEquals(0, readyCount())
            Os.remove(link.path); assertTrue(link.mkdir()); base = link
        }
        store().install(context, id(10), blob().sha256, svg); assertEquals(1, readyCount())
    }

    @Test fun concurrentStoreInstancesSerializeExactRetriesToOneImmutablePublication() = runBlocking<Unit> {
        val context = reserve(); val reservation = repository().reservations(context).single()
        withTimeout(10_000) { List(8) { async(Dispatchers.IO) { store().install(context, id(10), blob().sha256, svg) } }.awaitAll() }
        assertEquals(1, readyCount()); assertEquals(setOf(blob().sha256), path(context).list()!!.toSet())
        assertEquals(reservation, repository().reservations(context).single())
    }

    @Test fun logoutDuringBlockingFilePhaseDoesNotHoldAccountLockOrPublishStaleReady() = runBlocking<Unit> {
        val context = reserve(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val fault = object : IconFileIo() {
            override fun rename(source: String, target: String) {
                super.rename(source, target); reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val result = async(Dispatchers.IO) { failedJob { store(fault).install(context, id(10), blob().sha256, svg) } }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { sessions.exclusive { tokens.clearTokens() } }
        } finally { release.countDown() }
        assertNotNull(result.await()); assertEquals(0, readyCount())
        assertArrayEquals(svg, File(path(context), blob().sha256).readBytes())
        login(); val current = repository().capture()
        assertEquals(IconFileRecovery(0, 1, emptyList()), store().recover(current))
        store().install(current, id(10), blob().sha256, svg); assertEquals(1, readyCount())
    }

    @Test fun cancellationWaitsForActualIoAndQueuedSecondInstanceBeforeReleasingLease() = runBlocking<Unit> {
        val context = reserve(); val reached = CountDownLatch(1); val release = CountDownLatch(1); val secondEntered = CountDownLatch(1)
        val fault = object : IconFileIo() {
            override fun rename(source: String, target: String) {
                super.rename(source, target); reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val first = async(Dispatchers.IO) { store(fault).install(context, id(10), blob().sha256, svg) }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        first.cancel()
        val second = async(Dispatchers.IO) {
            store(object : IconFileIo() {
                override fun sync(fd: FileDescriptor) { secondEntered.countDown(); super.sync(fd) }
            }).install(context, id(10), blob().sha256, svg)
        }
        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS)); assertFalse(first.isCompleted)
            assertEquals(0, readyCount())
        } finally { release.countDown() }
        first.join(); second.await(); assertEquals(1, readyCount())
        assertArrayEquals(svg, store().read(context, id(10), blob().sha256))
    }

    @Test fun permissionChangesInvalidateOldContextWhileReadOnlyNewContextMayReadReady() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2) }
        rejected { store().read(context, id(10), blob().sha256) }
        val current = repository().capture()
        assertArrayEquals(svg, store().read(current, id(10), blob().sha256))
        rejected { store().install(current, id(10), blob().sha256, svg) }; assertEquals(1, readyCount())
    }

    @Test fun reauthenticationDuringActualReadRejectsLateBytesWithoutLosingReady() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val result = async(Dispatchers.IO) { failedJob {
            store(object : IconFileIo() {
                override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                    reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return super.read(fd, bytes, offset, length)
                }
            }).read(context, id(10), blob().sha256)
        } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login() } }
        finally { release.countDown() }
        assertNotNull(result.await()); assertEquals(1, readyCount())
        assertArrayEquals(svg, store().read(repository().capture(), id(10), blob().sha256))
    }

    @Test fun physicalInventoryByteBoundaryAllowsExactReplayButNotNewPublicationOrCleanupAboveLimit() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val unknown = File(path(context), "preserved-sparse")
        java.io.RandomAccessFile(unknown, "rw").use { it.setLength(536_870_912L - svg.size) }
        store().install(context, id(10), blob().sha256, svg)
        assertEquals(listOf(unknown.name), store().recover(context).unknownFiles)
        java.io.RandomAccessFile(unknown, "rw").use { it.setLength(536_870_913L - svg.size) }
        val known = File(path(context), ".install-${repository().reservations(context).single().operationId}.part").apply { createNewFile() }
        rejected { store().recover(context) }; rejected { store().install(context, id(10), blob().sha256, svg) }
        assertTrue(known.exists()); assertEquals(1, readyCount()); assertEquals(536_870_913L - svg.size, unknown.length())
    }

    @Test fun inventoryEntryBoundaryIsMeasuredWithoutTruncatingOrAdoptingUnknownFiles() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        for (n in 1..4095) assertTrue(File(path(context), "unknown-$n").createNewFile())
        assertEquals(4095, store().recover(context).unknownFiles.size)
        store().install(context, id(10), blob().sha256, svg)
        val extra = File(path(context), "unknown-extra").apply { createNewFile() }
        rejected { store().recover(context) }; assertTrue(extra.exists()); assertEquals(4097, path(context).list()!!.size)
    }

    @Test fun cleanupFailureRetainsJournalAndSupportsExplicitRecoveryRetry() = runBlocking<Unit> {
        val context = reserve(); path(context).mkdirs()
        val operation = repository().reservations(context).single().operationId
        val part = File(path(context), ".install-$operation.part").apply { writeText("partial") }
        rejected { store(object : IconFileIo() { override fun unlink(path: String) { throw IOException("synthetic cleanup") } }).recover(context) }
        assertEquals("partial", part.readText()); assertEquals(0, readyCount())
        assertEquals(operation, repository().reservations(context).single().operationId)
        assertEquals(IconFileRecovery(0, 1, emptyList()), store().recover(context)); assertFalse(part.exists())
    }

    @Test fun storedReadyTypesAndOperationBindingsAreCheckedBeforeReadOrRecovery() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val operation = repository().reservations(context).single().operationId
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET operationId=?", arrayOf(id(99)))
        rejected { store().read(context, id(10), blob().sha256) }; rejected { store().recover(context) }
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET operationId=?, validationProfile=CAST(validationProfile AS BLOB)", arrayOf(operation))
        rejected { store().read(context, id(10), blob().sha256) }; assertEquals(1, readyCount())
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET validationProfile=CAST(validationProfile AS TEXT)")
        assertArrayEquals(svg, store().read(context, id(10), blob().sha256))
    }

    @Test fun filePhaseReleasesDatabaseWriteTransactionForIndependentMetadataCommit() = runBlocking<Unit> {
        val context = reserve(); val reached = CountDownLatch(1); val release = CountDownLatch(1)
        val result = async(Dispatchers.IO) { store(object : IconFileIo() {
            override fun rename(source: String, target: String) {
                super.rename(source, target); reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }).install(context, id(10), blob().sha256, svg) }
        val independent = AccountIconDatabase.open(app)
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) {
                AccountIconRepository(independent, tokens, sessions).reserveAsset(context, asset().copy(assetId = id(11)))
            }
        } finally { release.countDown(); independent.close() }
        result.await(); assertEquals(asset().copy(assetId = id(11)), repository().asset(context, id(11)))
        assertEquals(1, readyCount()); assertEquals(1, repository().reservations(context).size)
    }

    @Test fun metadataDeclarationCannotSilentlyRemoveExistingReadyReceipt() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER drop_ready AFTER INSERT ON icon_assets BEGIN DELETE FROM icon_blob_ready; END")
        rejected { repository().reserveAsset(context, asset().copy(assetId = id(11))) }
        assertEquals(1, readyCount()); assertNull(repository().asset(context, id(11)))
        assertArrayEquals(svg, store().read(context, id(10), blob().sha256))
    }

    @Test fun accountChangeWhileReadResultWaitsForCallerDispatcherRejectsPublication() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        ReturnBarrier().use { barrier ->
            val result = async(barrier) { failedJob {
                store(object : IconFileIo() {
                    override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(fd, bytes, offset, length)
                        if (count == 0) barrier.armed.set(true)
                        return count
                    }
                }).read(context, id(10), blob().sha256)
            } }
            try { assertTrue(barrier.reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login() } }
            finally { barrier.release() }
            assertNotNull(withTimeout(5_000) { result.await() })
        }
        assertEquals(1, readyCount()); assertArrayEquals(svg, store().read(repository().capture(), id(10), blob().sha256))
    }

    @Test fun committedInstallWaitingForCallerReportsStaleSessionButPreservesActualReady() = runBlocking<Unit> {
        val context = reserve()
        ReturnBarrier().use { barrier ->
            val result = async(barrier) { failedJob {
                store(object : IconFileIo() {
                    override fun rename(source: String, target: String) {
                        super.rename(source, target); barrier.armed.set(true)
                    }
                }).install(context, id(10), blob().sha256, svg)
            } }
            try {
                assertTrue(barrier.reached.await(5, TimeUnit.SECONDS)); assertEquals(1, readyCount())
                withTimeout(2_000) { login() }
            } finally { barrier.release() }
            assertNotNull(withTimeout(5_000) { result.await() })
        }
        assertEquals(1, readyCount()); assertEquals(IconFileRecovery(1, 0, emptyList()), store().recover(repository().capture()))
    }
}
