package com.dayforge.data.appearance

import android.system.Os
import android.system.OsConstants
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceThemeRecoveryReaderTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-archive-").toFile()
    private val dataFile = File(parent, "appearance.preferences_pb")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun dataStore() = PreferenceDataStoreFactory.create(scope = scope, produceFile = { dataFile })
    private var preferences = dataStore()
    private val builtIns = BuiltInThemes(app.targetContext.assets)
    private val files = ThemeFileRepository(parent)
    private val root = File(parent, "theme-definitions-v1")
    private val selectionKey = stringPreferencesKey("appearance_theme_selection_v1")
    private val secret = stringPreferencesKey("access_token")
    private val unrelated = stringPreferencesKey("synthetic_other_preference")
    private fun id(n: Int) = "99000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun catalog() = ThemeCatalogRepository(preferences, files, builtIns)
    private fun selection() = DeviceThemeStore(preferences, files)
    private fun reader(storage: ThemeFileRepository = files) = DeviceThemeRecoveryReader(preferences, storage, builtIns)
    private fun defaults() = DeviceThemeSelection(
        ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1), ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1),
        DeviceThemeMode.SYSTEM, DeviceCardStyle.PERSONALIZED)
    private fun theme(n: Int = 10, revision: Int = 1): ValidatedTheme {
        val raw = app.context.assets.open("next/theme.json").bufferedReader().use { it.readText() }
        return ValidatedTheme.parse(raw.replace("50000000-0000-0000-0000-000000000001", id(n))
            .replace("\"revision\": 1", "\"revision\": $revision").toByteArray())
    }
    private fun target(ref: ThemeVersionRef) = File(root, "${ref.themeId}-${ref.revision}.json")
    private fun temp(slot: ThemeCatalogSlot) = File(root, ".theme-${slot.operationId}.part")
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private suspend fun initialize() { catalog().initialize(); selection().select(0, defaults()) }
    private suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); preferences = dataStore()
    }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            return error
        }
        error("Operation must be rejected")
    }
    private suspend fun pending(value: ValidatedTheme, phase: ThemeInstallPhase): ThemeCatalogSlot {
        val before = checkNotNull(catalog().state())
        val slot = ThemeCatalogSlot(value.ref(), value.definitionDigest(), id(100 + value.definition.revision), phase)
        val after = before.next(before.slots + slot)
        preferences.edit { it[THEME_CATALOG_KEY] = Json.encodeToString(after) }
        return slot
    }
    @After fun cleanup() = runBlocking<Unit> {
        scope.coroutineContext[Job]!!.cancelAndJoin(); assertTrue(parent.deleteRecursively())
    }

    @Test fun allInstalledVersionsOriginalBytesAndPreferencesAreCapturedWithoutCredentialsOrWrites() = runBlocking<Unit> {
        initialize()
        catalog().install(theme()); catalog().install(theme(revision = 2))
        preferences.edit { it[secret] = "synthetic-secret"; it[unrelated] = "preserve" }
        // Whitespace/BOM preserve meaning but are still part of the exact archive witness.
        val custom = target(theme().ref())
        val original = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + custom.readBytes() + " \n".toByteArray()
        custom.writeBytes(original)
        val values = preferences.data.first()
        val content = root.listFiles()!!.associate { it.name to hash(it.readBytes()) }
        val inodes = root.listFiles()!!.associate { it.name to Os.lstat(it.path).st_ino }
        reader().inspect { snapshot, access ->
            assertFalse(snapshot.activationAuthorized)
            assertEquals(values[selectionKey], snapshot.preferences.selectionRaw)
            assertEquals(values[THEME_CATALOG_KEY], snapshot.preferences.catalogRaw)
            assertEquals(defaults(), snapshot.preferences.selected!!.selection)
            assertEquals(9, snapshot.files.size); assertEquals(9, snapshot.preferences.catalog!!.slots.size)
            assertFalse(snapshot.toString().contains("synthetic-secret"))
            assertFalse(snapshot.toString().contains("synthetic_other_preference"))
            for (witness in snapshot.files) {
                assertFalse(witness.temporary)
                val bytes = access.read(witness.name)
                assertEquals(content.getValue(witness.name), witness.contentHash)
                assertEquals(witness.contentHash, hash(bytes)); assertEquals(witness.byteLength, bytes.size)
            }
            assertArrayEquals(original, access.read(custom.name))
        }
        assertEquals(values, preferences.data.first())
        assertEquals(content, root.listFiles()!!.associate { it.name to hash(it.readBytes()) })
        assertEquals(inodes, root.listFiles()!!.associate { it.name to Os.lstat(it.path).st_ino })
        reopen(); assertEquals(values, preferences.data.first())
        reader().inspect { snapshot, _ -> assertEquals(9, snapshot.files.size) }
    }

    @Test fun uninitializedStateDoesNotCreateFilesOrInterpretLegacyAndUnrelatedValues() = runBlocking<Unit> {
        preferences.edit { it[stringPreferencesKey("light_color_theme")] = "ocean"; it[secret] = "synthetic-secret" }
        val values = preferences.data.first()
        reader().inspect { snapshot, _ ->
            assertTrue(snapshot.files.isEmpty()); assertNull(snapshot.preferences.catalogRaw)
            assertNull(snapshot.preferences.selectionRaw); assertNull(snapshot.preferences.selected)
        }
        assertFalse(root.exists()); assertEquals(values, preferences.data.first())
    }

    @Test fun standaloneValidSelectionKeepsBothModesWithoutCreatingAnInventedCatalog() = runBlocking<Unit> {
        val light = theme(); val dark = theme(11)
        files.install(light); files.install(dark)
        val choice = DeviceThemeSelection(light.ref(), dark.ref(), DeviceThemeMode.DARK, DeviceCardStyle.FOLLOW_THEME)
        selection().select(0, choice)
        val values = preferences.data.first()
        reader().inspect { snapshot, access ->
            assertNull(snapshot.preferences.catalog); assertEquals(choice, snapshot.preferences.selected!!.selection)
            assertEquals(2, snapshot.files.size)
            assertArrayEquals(light.exportBytes(), access.read(target(light.ref()).name))
        }
        assertEquals(values, preferences.data.first())
    }

    @Test fun installingEmptyPartialAndPublishedVersionStayPendingAfterColdRead() = runBlocking<Unit> {
        initialize()
        val value = theme(); val slot = pending(value, ThemeInstallPhase.INSTALLING)
        val partial = temp(slot)
        for (bytes in listOf(byteArrayOf(), value.exportBytes().copyOf(3), value.exportBytes())) {
            partial.writeBytes(bytes)
            val values = preferences.data.first()
            reopen()
            reader().inspect { snapshot, access ->
                val witness = snapshot.files.single { it.temporary }
                assertEquals(partial.name, witness.name); assertEquals(slot.operationId, witness.operationId)
                assertArrayEquals(bytes, access.read(witness.name))
                assertEquals(ThemeInstallPhase.INSTALLING, snapshot.preferences.catalog!!.slots.last().phase)
            }
            assertArrayEquals(bytes, partial.readBytes()); assertEquals(values, preferences.data.first())
        }
        // Fault fixture: publication happened, but catalog activation did not commit.
        Os.rename(partial.path, target(value.ref()).path)
        reader().inspect { snapshot, access ->
            assertEquals(ThemeInstallPhase.INSTALLING, snapshot.preferences.catalog!!.slots.last().phase)
            assertArrayEquals(value.exportBytes(), access.read(target(value.ref()).name))
        }
        assertEquals(ThemeInstallPhase.INSTALLING, catalog().state()!!.slots.last().phase)
    }

    @Test fun deletingPresentOrMissingVersionKeepsJournalAndNeverFinishesDeletion() = runBlocking<Unit> {
        initialize(); val value = theme()
        val slot = pending(value, ThemeInstallPhase.DELETING)
        files.install(value, slot.operationId)
        val partial = temp(slot).apply { writeBytes(byteArrayOf(1)) }
        for (present in listOf(true, false)) {
            if (!present) assertTrue(target(value.ref()).delete())
            val values = preferences.data.first()
            reader().inspect { snapshot, _ ->
                assertEquals(ThemeInstallPhase.DELETING, snapshot.preferences.catalog!!.slots.last().phase)
                assertEquals(present, snapshot.files.any { it.name == target(value.ref()).name })
                assertTrue(snapshot.files.any { it.name == partial.name && it.temporary })
            }
            assertEquals(values, preferences.data.first()); assertArrayEquals(byteArrayOf(1), partial.readBytes())
        }
    }

    @Test fun missingOrChangedUnselectedActiveFileBlocksConsumerWithoutFallbackOrCleanup() = runBlocking<Unit> {
        initialize(); val value = theme(); val state = catalog().install(value)
        val partial = temp(state.slots.last()).apply { writeBytes(byteArrayOf(1)) }
        val file = target(value.ref())
        file.writeText(value.exportBytes().toString(Charsets.UTF_8).replace("#245EAC", "#123456"))
        val values = preferences.data.first()
        rejected { reader().inspect { _, _ -> fail("Unselected changed theme must fail closure") } }
        assertTrue(file.delete())
        assertEquals("THEME_RECOVERY_ACTIVE_FILE_MISSING", rejected {
            reader().inspect { _, _ -> fail("Unselected missing theme must fail closure") }
        }.message)
        assertEquals(values, preferences.data.first()); assertArrayEquals(byteArrayOf(1), partial.readBytes())
        assertEquals(defaults(), selection().read()!!.selection)
    }

    @Test fun unknownAndOversizeTemporaryFilesArePreservedNotImportedOrTruncated() = runBlocking<Unit> {
        initialize(); val slot = pending(theme(), ThemeInstallPhase.INSTALLING)
        val partial = temp(slot).apply { writeBytes(ByteArray(THEME_FILE_LIMIT + 1)) }
        rejected { reader().inspect { _, _ -> fail("Oversize temporary must fail closure") } }
        assertEquals(THEME_FILE_LIMIT + 1L, partial.length())
        partial.writeBytes(byteArrayOf(1))
        val unknown = File(root, ".theme-${id(99)}.part").apply { writeText("preserve") }
        rejected { reader().inspect { _, _ -> fail("Unknown operation must fail closure") } }
        assertEquals("preserve", unknown.readText()); assertArrayEquals(byteArrayOf(1), partial.readBytes())
    }

    @Test fun symbolicLinkDirectoryAndFifoAreRefusedWithoutFollowingOrBlocking() = runBlocking<Unit> {
        initialize(); val slot = pending(theme(), ThemeInstallPhase.INSTALLING)
        val partial = temp(slot); val outside = File(parent, "outside").apply { writeText("preserve") }
        Os.symlink(outside.path, partial.path)
        rejected { reader().inspect { _, _ -> fail("Symlink must fail closure") } }
        assertEquals("preserve", outside.readText()); Os.remove(partial.path)
        assertTrue(partial.mkdir()); rejected { reader().inspect { _, _ -> fail("Directory must fail closure") } }
        assertTrue(partial.delete()); Os.mkfifo(partial.path, 384)
        rejected { reader().inspect { _, _ -> fail("FIFO must fail closure") } }
        assertTrue(OsConstants.S_ISFIFO(Os.lstat(partial.path).st_mode)); Os.remove(partial.path)
    }

    @Test fun malformedOrCrossFieldInconsistentPreferencesAreNotRepaired() = runBlocking<Unit> {
        initialize(); val initial = preferences.data.first()
        for (bad in listOf("{}", initial[THEME_CATALOG_KEY]!!.replace("\"revision\":1", "\"revision\":1,\"revision\":1"))) {
            preferences.edit { it[THEME_CATALOG_KEY] = bad }
            val values = preferences.data.first()
            rejected { reader().inspect { _, _ -> fail("Malformed catalog must fail before file access") } }
            assertEquals(values, preferences.data.first())
        }
        preferences.edit { it[THEME_CATALOG_KEY] = initial[THEME_CATALOG_KEY]!!; it[selectionKey] = "{}" }
        rejected { reader().inspect { _, _ -> fail("Malformed selection must fail") } }
    }

    @Test fun builtinDigestAndFileCannotBeRedefinedByRehashingCatalog() = runBlocking<Unit> {
        initialize()
        val before = catalog().state()!!
        val slot = before.slots.first()
        val file = target(slot.ref)
        val altered = ValidatedTheme.parse(file.readText().replace("\"Ocean\"", "\"Changed Ocean\"").toByteArray())
        assertNotEquals(slot.digest, altered.definitionDigest())
        file.writeBytes(altered.exportBytes())
        val forged = before.next(before.slots.map { if (it.ref == slot.ref) it.copy(digest = altered.definitionDigest()) else it })
        preferences.edit { it[THEME_CATALOG_KEY] = Json.encodeToString(forged) }
        val values = preferences.data.first()
        assertEquals("THEME_RECOVERY_BUILTIN_CHANGED", rejected { reader().inspect { _, _ -> fail("Forged built-in must fail") } }.message)
        assertEquals(values, preferences.data.first()); assertArrayEquals(altered.exportBytes(), file.readBytes())
    }

    @Test fun preferenceAndSameLengthFileChangesInvalidateCompletedConsumer() = runBlocking<Unit> {
        initialize(); val slot = pending(theme(), ThemeInstallPhase.INSTALLING)
        val partial = temp(slot).apply { writeBytes(byteArrayOf(1, 2)) }
        assertEquals("THEME_RECOVERY_CONTENT_CHANGED", rejected { reader().inspect { _, _ ->
            partial.writeBytes(byteArrayOf(2, 1))
        } }.message)
        assertArrayEquals(byteArrayOf(2, 1), partial.readBytes())
        assertEquals("THEME_RECOVERY_PREFERENCES_CHANGED", rejected { reader().inspect { _, _ ->
            preferences.edit { values -> values[selectionKey] = values[selectionKey]!!.replace("system", "dark") }
        } }.message)
        assertEquals(DeviceThemeMode.DARK, selection().read()!!.selection.mode)
    }

    @Test fun readonlyWhitelistedPreferencesIgnoreUnrelatedChangesButNotActualRawChanges() = runBlocking<Unit> {
        initialize()
        reader().inspect { _, _ -> preferences.edit { it[unrelated] = "keep"; it[secret] = "synthetic-secret" } }
        assertEquals("keep", preferences.data.first()[unrelated])
        assertEquals("THEME_RECOVERY_PREFERENCES_CHANGED", rejected { reader().inspect { _, _ ->
            preferences.edit { it[selectionKey] = " " + it[selectionKey]!! }
        } }.message)
    }

    @Test fun closedAccessImmutableSnapshotAndConsumerCancellationCannotLeakOrCleanFiles() = runBlocking<Unit> {
        initialize(); val slot = pending(theme(), ThemeInstallPhase.INSTALLING)
        val partial = temp(slot).apply { writeBytes(byteArrayOf(1, 2)) }
        val access = reader().inspect { snapshot, fileAccess ->
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.files as MutableList).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.preferences.catalog!!.slots as MutableList).clear() }
            rejected { fileAccess.read("../outside") }
            val bytes = fileAccess.read(partial.name); bytes.fill(0)
            assertArrayEquals(byteArrayOf(1, 2), fileAccess.read(partial.name))
            fileAccess
        }
        assertEquals("THEME_RECOVERY_ACCESS_CLOSED", rejected { access.read(partial.name) }.message)
        val failure = CancellationException("synthetic consumer cancellation")
        var seen: CancellationException? = null
        try { reader().inspect { _, _ -> throw failure } } catch (error: CancellationException) { seen = error }
        assertSame(failure, seen)
        reader().inspect { snapshot, _ -> assertTrue(snapshot.files.any { it.name == partial.name }) }
        assertArrayEquals(byteArrayOf(1, 2), partial.readBytes())
    }

    @Test fun actualReaderFailurePathReplacementAndShortEofCannotClaimVerifiedFiles() = runBlocking<Unit> {
        initialize(); val slot = pending(theme(), ThemeInstallPhase.INSTALLING)
        val partial = temp(slot).apply { writeBytes(byteArrayOf(1, 2)) }
        val values = preferences.data.first()
        val failure = IOException("synthetic read")
        val faulty = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw failure
        })
        assertSame(failure, rejected { reader(faulty).inspect { _, _ -> fail("I/O failure must stop consumer") } })
        val short = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int) = 0
        })
        rejected { reader(short).inspect { _, _ -> fail("Short EOF must stop consumer") } }
        val original = File(parent, "held-original")
        var replaced = false
        val replacement = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (!replaced && Os.fstat(fd).st_size == 2L) {
                    replaced = true; Os.rename(partial.path, original.path); partial.writeBytes(byteArrayOf(1, 2))
                }
                return super.read(fd, bytes, offset, length)
            }
        })
        rejected { reader(replacement).inspect { _, _ -> fail("Path replacement must stop consumer") } }
        assertTrue(replaced); assertArrayEquals(byteArrayOf(1, 2), original.readBytes())
        assertArrayEquals(byteArrayOf(1, 2), partial.readBytes()); assertEquals(values, preferences.data.first())
    }
}
