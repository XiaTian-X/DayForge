package com.dayforge.data.appearance

import android.system.Os
import android.system.OsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconRecoveryFilesTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var db: AccountIconDatabase
    private val sessions = AccountSessionCoordinator()
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "98000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun blob(bytes: ByteArray = svg) = IconBlob(hash(bytes), bytes.size, "image/svg+xml", 1, 1)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun asset(n: Int = 10, bytes: ByteArray = svg) = IconAsset(id(n), "icon$n", "general", "template", blob(bytes), null)
    private fun metadata() = AccountIconRepository(db, tokens, sessions)
    private fun store() = AccountIconStore(metadata(), AccountIconFiles(parent))
    private fun reader(io: IconFileIo = IconFileIo()) = AccountIconRecoveryFiles(metadata(), AccountIconFiles(parent, io))
    private fun directory(context: AccountIconContext) = File(parent,
        "account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}")
    private suspend fun temporary(context: AccountIconContext) = File(directory(context),
        ".install-${metadata().reservations(context).single().operationId}.part")
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3)) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write"), true, 1)
    }
    private suspend fun reserve(): AccountIconContext = metadata().let { repo ->
        repo.capture().also { repo.reserveAsset(it, asset()) }
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            return error
        }
        error("Operation must be rejected")
    }
    private fun rawTables(): List<List<String?>> = db.openHelper.readableDatabase.let { sql ->
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        tables.flatMap { table -> sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(listOf(table) + (0 until cursor.columnCount).map { cursor.getString(it) }) }
        } }
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-archive-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(parent, "auth.preferences_pb") })
        tokens = TokenManager(preferences)
        db = AccountIconDatabase.open(app)
        login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun allOwnedAssetsAndUnselectedPacksAreCapturedWithoutDatabaseOrPreferenceWrites() = runBlocking<Unit> {
        val context = reserve()
        val otherBytes = svg.toString(Charsets.UTF_8).replace("ff0000", "00ff00").toByteArray()
        val repo = metadata()
        repo.reserveAsset(context, asset(11, otherBytes))
        val first = IconPack("dayforge.icon-pack", 1, id(20), 1, "selected", listOf(asset()),
            mapOf("habit.test" to id(10)), id(10))
        val second = IconPack("dayforge.icon-pack", 1, id(21), 1, "unused", listOf(asset(11, otherBytes)),
            mapOf("habit.test" to id(11)), id(11))
        repo.reservePack(context, first); repo.reservePack(context, second)
        store().install(context, id(10), blob().sha256, svg)
        store().install(context, id(11), blob(otherBytes).sha256, otherBytes)
        store().select(context, 0, IconPackVersion(id(20), 1))
        val rows = rawTables()
        val beforePreferences = preferences.data.first()
        reader().inspect(context) { snapshot, access ->
            assertEquals(context.namespace, snapshot.namespace)
            assertFalse(snapshot.activationAuthorized)
            assertEquals(listOf(id(10), id(11)), snapshot.metadata.assets.map { it.assetId })
            assertEquals(listOf(id(20), id(21)), snapshot.metadata.packs.map { it.packId })
            assertEquals(AccountIconSelection(1, IconPackVersion(id(20), 1)), snapshot.metadata.selection)
            assertEquals(2, snapshot.metadata.installations.size)
            assertEquals(2, snapshot.files.size)
            for (witness in snapshot.files) {
                assertFalse(witness.temporary); assertEquals(witness.blobHash, witness.contentHash)
                assertEquals(witness.byteLength, access.read(witness.name).size)
            }
        }
        assertEquals(rows, rawTables())
        assertEquals(beforePreferences, preferences.data.first())
    }

    @Test fun emptyAndPendingNamespaceDoNotCreateDirectoriesEvenWithReadOnlyAccess() = runBlocking<Unit> {
        val empty = metadata().capture()
        reader().inspect(empty) { snapshot, _ ->
            assertTrue(snapshot.files.isEmpty()); assertTrue(snapshot.metadata.assets.isEmpty())
        }
        assertFalse(directory(empty).exists())
        val context = reserve()
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2) }
        val readonly = metadata().capture()
        reader().inspect(readonly) { snapshot, _ ->
            assertEquals(1, snapshot.metadata.installations.size)
            assertNull(snapshot.metadata.installations.single().validationProfile)
            assertTrue(snapshot.files.isEmpty()); assertFalse(snapshot.activationAuthorized)
        }
        assertFalse(directory(context).exists())
    }

    @Test fun partialAndEmptyTemporaryArePreservedAfterColdReopenWithoutBecomingReady() = runBlocking<Unit> {
        val context = reserve(); assertTrue(directory(context).mkdirs())
        val temp = temporary(context)
        for (content in listOf(byteArrayOf(), svg.copyOf(3), svg)) {
            temp.writeBytes(content)
            val inode = Os.lstat(temp.path).st_ino
            db.close(); db = AccountIconDatabase.open(app)
            reader().inspect(context) { snapshot, access ->
                val witness = snapshot.files.single()
                assertTrue(witness.temporary); assertEquals(temp.name, witness.name)
                assertEquals(hash(content), witness.contentHash); assertEquals(content.size, witness.byteLength)
                assertArrayEquals(content, access.read(temp.name))
                assertNull(snapshot.metadata.installations.single().validationProfile)
            }
            assertArrayEquals(content, temp.readBytes()); assertEquals(inode, Os.lstat(temp.path).st_ino)
            assertFalse(File(directory(context), blob().sha256).exists())
        }
    }

    @Test fun pendingValidFinalIsArchivedButNeverAdoptedAsReady() = runBlocking<Unit> {
        val context = reserve()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_ready BEFORE INSERT ON icon_blob_ready BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        rejected { store().install(context, id(10), blob().sha256, svg) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_ready")
        val rows = rawTables()
        reader().inspect(context) { snapshot, access ->
            assertFalse(snapshot.files.single().temporary)
            assertNull(snapshot.metadata.installations.single().validationProfile)
            assertArrayEquals(svg, access.read(blob().sha256))
        }
        assertEquals(rows, rawTables())
    }

    @Test fun missingReadyOrCorruptedFinalStopsBeforeConsumerAndDoesNotCleanTemporary() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val temp = temporary(context).apply { writeBytes(svg.copyOf(2)) }
        val target = File(directory(context), blob().sha256)
        for (missing in listOf(false, true)) {
            if (missing) assertTrue(target.delete()) else target.writeBytes(svg.copyOf().apply { this[0] = 0 })
            rejected { reader().inspect(context) { _, _ -> fail("Corrupt closure must not reach consumer") } }
            assertArrayEquals(svg.copyOf(2), temp.readBytes())
            assertNotNull(metadata().installations(context, writing = false).single().validationProfile)
        }
    }

    @Test fun oversizePartialAndUnknownRegularFileAreNotTruncatedOrRemoved() = runBlocking<Unit> {
        val context = reserve(); assertTrue(directory(context).mkdirs())
        val temp = temporary(context).apply { writeBytes(svg + 0) }
        rejected { reader().inspect(context) { _, _ -> fail("Oversize temporary must be refused") } }
        assertArrayEquals(svg + 0, temp.readBytes())
        temp.writeBytes(svg.copyOf(2))
        val unknown = File(directory(context), ".install-${id(99)}.part").apply { writeText("preserve") }
        rejected { reader().inspect(context) { _, _ -> fail("Unknown file must be refused") } }
        assertEquals("preserve", unknown.readText()); assertArrayEquals(svg.copyOf(2), temp.readBytes())
    }

    @Test fun symbolicLinkDirectoryAndFifoCannotBeReadOrCleaned() = runBlocking<Unit> {
        val context = reserve(); assertTrue(directory(context).mkdirs())
        val temp = temporary(context)
        val outside = File(parent, "outside").apply { writeBytes(svg) }
        Os.symlink(outside.path, temp.path)
        rejected { reader().inspect(context) { _, _ -> fail("Symlink must be refused") } }
        assertArrayEquals(svg, outside.readBytes()); Os.remove(temp.path)
        assertTrue(temp.mkdir())
        rejected { reader().inspect(context) { _, _ -> fail("Directory must be refused") } }
        assertTrue(temp.delete()); Os.mkfifo(temp.path, 384)
        rejected { reader().inspect(context) { _, _ -> fail("FIFO must be refused without blocking") } }
        assertTrue(OsConstants.S_ISFIFO(Os.lstat(temp.path).st_mode)); Os.remove(temp.path)
    }

    @Test fun accountServerAndEpochIsolationEvenWhenHashesAreIdentical() = runBlocking<Unit> {
        val first = reserve(); store().install(first, id(10), blob().sha256, svg)
        val firstInode = Os.lstat(File(directory(first), blob().sha256).path).st_ino
        for ((owner, server, epoch) in listOf(Triple(id(5), id(2), id(3)), Triple(id(1), id(6), id(3)), Triple(id(1), id(2), id(7)))) {
            login(owner, server, epoch)
            val current = reserve()
            rejected { reader().inspect(first) { _, _ -> fail("Old authorization must fail") } }
            reader().inspect(current) { snapshot, _ -> assertTrue(snapshot.files.isEmpty()) }
            assertFalse(directory(current).exists())
            store().install(current, id(10), blob().sha256, svg)
            reader().inspect(current) { snapshot, access ->
                assertEquals(current.namespace, snapshot.namespace)
                assertEquals(1, snapshot.files.size); assertArrayEquals(svg, access.read(blob().sha256))
            }
            assertEquals(firstInode, Os.lstat(File(directory(first), blob().sha256).path).st_ino)
        }
    }

    @Test fun returnedAccessIsClosedAndCapturedCollectionsCannotBeMutated() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        val access = reader().inspect(context) { snapshot, fileAccess ->
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.files as MutableList).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.metadata.assets as MutableList).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.metadata.installations as MutableList).clear() }
            val bytes = fileAccess.read(blob().sha256); bytes.fill(0)
            assertArrayEquals(svg, fileAccess.read(blob().sha256))
            rejected { fileAccess.read("../outside") }
            rejected { fileAccess.read(".install-${id(99)}.part") }
            fileAccess
        }
        assertEquals("ICON_RECOVERY_ACCESS_CLOSED", rejected { access.read(blob().sha256) }.message)
    }

    @Test fun actualCatalogOrPermissionChangesInConsumerInvalidateWitness() = runBlocking<Unit> {
        val context = reserve(); store().install(context, id(10), blob().sha256, svg)
        rejected { reader().inspect(context) { _, _ -> metadata().reserveAsset(context, asset(11)) } }
        assertEquals(2, metadata().recoveryMetadata(context).assets.size)
        rejected { reader().inspect(context) { _, _ ->
            sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), false, 2) }
        } }
        assertArrayEquals(svg, File(directory(context), blob().sha256).readBytes())
    }

    @Test fun sameLengthPartialChangeAndAddedFileAreDetectedAtClosureRecheck() = runBlocking<Unit> {
        val context = reserve(); assertTrue(directory(context).mkdirs())
        val temp = temporary(context).apply { writeBytes(byteArrayOf(1, 2)) }
        assertEquals("ICON_RECOVERY_CONTENT_CHANGED", rejected { reader().inspect(context) { _, _ ->
            temp.writeBytes(byteArrayOf(2, 1))
        } }.message)
        assertArrayEquals(byteArrayOf(2, 1), temp.readBytes())
        val unknown = File(directory(context), "unknown")
        assertEquals("ICON_RECOVERY_INVENTORY_CHANGED", rejected { reader().inspect(context) { _, _ ->
            unknown.writeBytes(byteArrayOf(9))
        } }.message)
        assertArrayEquals(byteArrayOf(9), unknown.readBytes())
    }

    @Test fun readerFailureAndConsumerCancellationLeaveBytesAndMetadataUntouched() = runBlocking<Unit> {
        val context = reserve(); assertTrue(directory(context).mkdirs())
        val temp = temporary(context).apply { writeBytes(svg.copyOf(3)) }
        val before = rawTables()
        val failure = IOException("synthetic read")
        val faulty = reader(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw failure
        })
        assertSame(failure, rejected { faulty.inspect(context) { _, _ -> fail("Read failure must stop consumer") } })
        val cancellation = CancellationException("synthetic consumer cancellation")
        var seen: CancellationException? = null
        try { reader().inspect(context) { _, _ -> throw cancellation } } catch (error: CancellationException) { seen = error }
        assertSame(cancellation, seen)
        assertEquals(before, rawTables()); assertArrayEquals(svg.copyOf(3), temp.readBytes())
        reader().inspect(context) { snapshot, _ -> assertEquals(1, snapshot.files.size) }
    }
}
