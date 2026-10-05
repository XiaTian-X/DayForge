package com.dayforge.data.appearance

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconDocumentsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private lateinit var repo: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var imports: AccountIconImport
    private lateinit var transfers: AccountIconTransfers
    private val sessions = AccountSessionCoordinator()
    private val uri = Uri.parse("content://synthetic.icon.provider/document")
    private fun id(n: Int) = "9b000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val image = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(content: ByteArray = image, declared: ByteArray = content): ByteArray {
        // Independent manifest and platform writer, not a production DTO round trip.
        val manifest = """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${id(10)}","revision":1,
            "name":"文件风格","assets":[{"asset_id":"${id(11)}","name":"red","purpose":"general",
            "color_mode":"original","light":{"sha256":"${hash(declared)}","byte_length":${declared.size},
            "media_type":"image/svg+xml","width":1,"height":1},"dark":null}],
            "roles":{"habit.exercise":"${id(11)}"},"placeholder_asset_id":null}"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest.toByteArray(), "blobs/${hash(declared)}" to content)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun docs(timeout: Long = 5000, open: (Uri, CancellationSignal) -> AssetFileDescriptor?) =
        AccountIconDocuments(imports, open, timeout)
    private suspend fun login(owner: String = id(1)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(id(2), id(3))
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    private fun readyCount() = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM icon_blob_ready").use {
        it.moveToFirst(); it.getInt(0)
    }
    private suspend fun assertNoImport() {
        assertTrue(transfers.jobs(repo.capture()).isEmpty())
        assertEquals(emptyList<IconReservation>(), repo.reservations(repo.capture()))
        assertEquals(0, readyCount())
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
        assertFalse(File(directory, "account-icons-v1").exists())
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(), "icon-documents-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app)
        repo = AccountIconRepository(db, tokens, sessions)
        store = AccountIconStore(repo, AccountIconFiles(directory))
        transfers = AccountIconTransfers(db, repo, store)
        imports = AccountIconImport(repo, store, transfers)
        login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::directory.isInitialized) assertTrue(directory.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun sectionPreviewOpensOnceClosesAndConfirmationNeverReopensChangedFile() = runBlocking<Unit> {
        val bytes = archive()
        val file = File(directory, "section.zip").also { it.writeBytes("prefix".toByteArray() + bytes + "suffix".toByteArray()) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val opens = AtomicInteger()
        val documents = docs { selected, _ ->
            assertEquals(uri, selected); opens.incrementAndGet()
            AssetFileDescriptor(descriptor, 6, bytes.size.toLong())
        }
        val value = documents.preview(uri)
        assertEquals("文件风格", value.manifest.name)
        assertEquals(1, opens.get()); assertFalse(descriptor.fileDescriptor.valid()); assertNoImport()
        file.writeBytes(byteArrayOf(0))
        val receipt = imports.confirm(value)
        assertEquals(listOf(hash(image)), receipt.verifiedHashes)
        assertArrayEquals(image, store.read(repo.capture(), id(11), hash(image)))
        assertEquals(1, opens.get()); assertEquals(1, readyCount())
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
        val jobs = transfers.jobs(repo.capture())
        assertEquals(3, jobs.size); assertTrue(jobs.all { it.state == IconTransferState.PENDING })
        assertEquals(receipt, imports.confirm(value)); assertEquals(jobs, transfers.jobs(repo.capture()))
    }

    @Test fun productionResolverReadsFileUriWithoutInstallingOrTakingPersistentPermission() = runBlocking<Unit> {
        val file = File(directory, "pack.zip").also { it.writeBytes(archive()) }
        val value = AccountIconDocuments(imports, app.contentResolver).preview(Uri.fromFile(file))
        assertEquals(id(10), value.manifest.packId); assertNoImport()
    }

    @Test fun missingAccountRejectsBeforeCallingProvider() = runBlocking<Unit> {
        tokens.clearTokens()
        val opens = AtomicInteger()
        val error = runCatching { docs { _, _ -> opens.incrementAndGet(); error("Must not open") }.preview(uri) }.exceptionOrNull()
        assertEquals("ICON_ACCESS_DENIED", error?.message)
        assertEquals(0, opens.get()); assertEquals(0, readyCount())
        assertFalse(File(directory, "account-icons-v1").exists())
    }

    @Test fun missingDescriptorAndOriginalProviderFailureRemainDistinct() = runBlocking<Unit> {
        assertEquals("ICON_DOCUMENT_UNAVAILABLE", runCatching { docs { _, _ -> null }.preview(uri) }.exceptionOrNull()?.message)
        val original = IOException("synthetic open failure")
        assertSame(original, runCatching { docs { _, _ -> throw original }.preview(uri) }.exceptionOrNull())
        assertNoImport()
    }

    @Test fun declaredLengthTruncationCannotTurnValidZipPrefixIntoSuccess() = runBlocking<Unit> {
        val bytes = archive()
        val file = File(directory, "short.zip").also { it.writeBytes(bytes) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val failure = runCatching {
            docs { _, _ -> AssetFileDescriptor(descriptor, 0, bytes.size + 1L) }.preview(uri)
        }.exceptionOrNull()
        assertEquals("ICON_DOCUMENT_TRUNCATED", failure?.message)
        assertFalse(descriptor.fileDescriptor.valid()); assertNoImport()
    }

    @Test fun unknownLengthPipeWaitsForEofAndClosesReader() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val producer = async(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(archive()) }
            }
            val value = docs { _, _ -> AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH) }.preview(uri)
            producer.await()
            assertEquals(id(10), value.manifest.packId)
            assertFalse(pipe[0].fileDescriptor.valid()); assertNoImport()
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun validZipWithoutEofIsNotAnEarlySuccessfulPreview() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        var signal: CancellationSignal? = null
        try {
            // Keep the writer open after a complete ZIP; EOF remains part of the document proof.
            writer.write(archive())
            val failure = withTimeout(3000) { runCatching {
                docs(150) { _, cancellation -> signal = cancellation
                    AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH) }.preview(uri)
            }.exceptionOrNull() }
            assertEquals("ICON_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(signal!!.isCanceled); assertFalse(pipe[0].fileDescriptor.valid()); assertNoImport()
        } finally { writer.close(); pipe.forEach { it.close() } }
    }

    @Test fun reliablePipeErrorAfterValidZipIsNotSuccessfulEof() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        try {
            val producer = async(Dispatchers.IO) {
                val writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                try { writer.write(archive()); pipe[1].closeWithError("synthetic interrupted provider") }
                finally { writer.close() }
            }
            val failure = runCatching { docs { _, _ ->
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH) }.preview(uri) }.exceptionOrNull()
            producer.await()
            assertTrue(failure is IOException); assertFalse(pipe[0].fileDescriptor.valid()); assertNoImport()
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun callerCancellationJoinsDescriptorCleanupAndDoesNotPublishPreview() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val opened = CompletableDeferred<CancellationSignal>()
        try {
            val task = async(Dispatchers.IO) { docs(10_000) { _, signal ->
                opened.complete(signal); AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }.preview(uri) }
            val signal = withTimeout(3000) { opened.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled); assertTrue(signal.isCanceled)
            assertFalse(pipe[0].fileDescriptor.valid()); assertNoImport()
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun throwingCancellationListenerPreservesTimeoutAndStillJoinsReader() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val cleanup = IllegalStateException("synthetic cancellation cleanup")
        try {
            val failure = withTimeout(3000) { runCatching { docs(150) { _, signal ->
                signal.setOnCancelListener { throw cleanup }
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }.preview(uri) }.exceptionOrNull() }
            assertEquals("ICON_DOCUMENT_TIMEOUT", failure?.message)
            assertTrue(failure!!.suppressed.any { it === cleanup })
            assertFalse(pipe[0].fileDescriptor.valid()); assertNoImport()
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun cooperativeProviderOpeningReceivesDeadlineCancellation() = runBlocking<Unit> {
        val released = CountDownLatch(1)
        val failure = withTimeout(4000) { runCatching { docs(150) { _, signal ->
            signal.setOnCancelListener { released.countDown() }
            assertTrue(released.await(3, TimeUnit.SECONDS)); signal.throwIfCanceled()
            error("Cancelled provider must not return")
        }.preview(uri) }.exceptionOrNull() }
        assertEquals("ICON_DOCUMENT_TIMEOUT", failure?.message); assertEquals(0, released.count)
        assertNoImport()
    }

    @Test fun accountSwitchDuringProviderIoDoesNotHoldAccountLockOrReturnOldPreview() = runBlocking<Unit> {
        val pipe = ParcelFileDescriptor.createPipe()
        val opened = CompletableDeferred<Unit>()
        try {
            val task = async(Dispatchers.IO) { runCatching { docs { _, _ ->
                opened.complete(Unit); AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }.preview(uri) }.exceptionOrNull() }
            withTimeout(3000) { opened.await() }
            withTimeout(2000) { login(id(20)) }
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(archive()) }
            val failure = withTimeout(3000) { task.await() }
            assertEquals("ICON_SESSION_CHANGED", failure?.message); assertNoImport()
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun oversizedProviderIsBoundedAndClosedBeforeZipParsing() = runBlocking<Unit> {
        val file = File(directory, "oversized.zip")
        RandomAccessFile(file, "rw").use { it.setLength(ICON_ARCHIVE_LIMIT + 1L) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val failure = runCatching { docs { _, _ ->
            AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }.preview(uri) }.exceptionOrNull()
        assertEquals("PACK_ARCHIVE_LIMIT", failure?.message)
        assertFalse(descriptor.fileDescriptor.valid()); assertNoImport()
    }

    @Test fun actualInvalidSvgIsRejectedBeforeAnyInstallation() = runBlocking<Unit> {
        val unsafe = """<svg width="1" height="1"><script/></svg>""".toByteArray()
        val file = File(directory, "unsafe.zip").also { it.writeBytes(archive(unsafe)) }
        val failure = runCatching { AccountIconDocuments(imports, app.contentResolver).preview(Uri.fromFile(file)) }.exceptionOrNull()
        assertEquals("SVG_ELEMENT", failure?.message); assertNoImport()
    }

    @Test fun throwingFinalCleanupCannotMisreportAValidPreviewAsSuccess() = runBlocking<Unit> {
        val file = File(directory, "valid.zip").also { it.writeBytes(archive()) }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val cleanup = IllegalStateException("synthetic final cleanup")
        val failure = runCatching { docs { _, signal ->
            signal.setOnCancelListener { throw cleanup }
            AssetFileDescriptor(descriptor, 0, file.length())
        }.preview(uri) }.exceptionOrNull()
        assertSame(cleanup, failure); assertFalse(descriptor.fileDescriptor.valid()); assertNoImport()
    }

    @Test fun frozenParserOwnsItsInputAndReturnsIndependentBlobCopies() = runBlocking<Unit> {
        val bytes = archive()
        val value = ValidatedIconPack.parse(bytes)
        bytes.fill(0)
        val first = value.readBlob(hash(image))
        assertArrayEquals(image, first)
        first.fill(0)
        assertArrayEquals(image, value.readBlob(hash(image)))
        assertNoImport()
    }
}
