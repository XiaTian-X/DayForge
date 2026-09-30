package com.dayforge.data.appearance

import android.system.Os
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeCatalogRepositoryTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-catalog-").toFile()
    private val prefsDir = File(parent, "preferences").also { check(it.mkdir()) }
    private val dataFile = File(prefsDir, "catalog.preferences_pb")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun dataStore() = PreferenceDataStoreFactory.create(scope = scope, produceFile = { dataFile })
    private var preferences = dataStore()
    private val builtIns = BuiltInThemes(app.targetContext.assets)
    private val files = ThemeFileRepository(parent)
    private val root = File(parent, "theme-definitions-v1")
    private val unrelated = stringPreferencesKey("synthetic_other_setting")
    private fun repository(data: DataStore<Preferences> = preferences, storage: ThemeFileRepository = files) =
        ThemeCatalogRepository(data, storage, builtIns)
    private fun selection(data: DataStore<Preferences> = preferences) = DeviceThemeStore(data, files)
    private fun defaults() = DeviceThemeSelection(
        ThemeVersionRef(BuiltInTheme.OCEAN.themeId, 1), ThemeVersionRef(BuiltInTheme.DUSK.themeId, 1),
        DeviceThemeMode.SYSTEM, DeviceCardStyle.FOLLOW_THEME)
    private fun custom(index: Int = 1): ValidatedTheme {
        val text = app.context.assets.open("next/theme.json").bufferedReader().use { it.readText() }
        return ValidatedTheme.parse(text.replace("50000000-0000-0000-0000-000000000001",
            "50000000-0000-0000-0000-${index.toString().padStart(12, '0')}").toByteArray())
    }
    private fun themeFile(ref: ThemeVersionRef) = File(root, "${ref.themeId}-${ref.revision}.json")
    private suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); preferences = dataStore()
    }
    private suspend fun initialize() { repository().initialize(); selection().select(0, defaults()) }
    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); assertTrue(parent.deleteRecursively())
    }
    private fun code(expected: String, action: () -> Unit) =
        assertEquals(expected, assertThrows(ThemeCatalogException::class.java) { action() }.code)

    @Test fun initializeAndImportAreDurableWithoutChangingSelectionOrUnrelatedPreferences() = runBlocking<Unit> {
        preferences.edit { it[unrelated] = "keep" }
        assertNull(repository().state()); assertNull(selection().read())
        val initial = repository().initialize()
        assertEquals(1L, initial.revision); assertEquals(7, initial.slots.size)
        assertNull(selection().read())
        assertEquals(initial, repository().initialize())
        selection().select(0, defaults())
        val selected = selection().read()
        val theme = custom(); val published = repository().install(theme)
        assertEquals(3L, published.revision); assertEquals(8, published.slots.size)
        assertEquals(ThemeInstallPhase.ACTIVE, published.slots.single { it.ref == theme.ref() }.phase)
        assertArrayEquals(theme.exportBytes(), repository().export(theme.ref()).exportBytes())
        assertEquals(selected, selection().read()); assertEquals("keep", preferences.data.first()[unrelated])
        reopen(); assertEquals(published, repository().state()); assertEquals(selected, selection().read())
        assertArrayEquals(theme.exportBytes(), repository().export(theme.ref()).exportBytes())
        assertThrows(UnsupportedOperationException::class.java) { (published.slots as MutableList).clear() }
    }

    @Test fun repeatedAndConcurrentImportsPreserveFirstBytesAndRejectChangedVersionOrBuiltinIdentity() = runBlocking<Unit> {
        initialize(); val theme = custom()
        val results = (0..7).map { async { repository().install(theme) } }.awaitAll()
        assertEquals(1, results.toSet().size); assertEquals(8, repository().state()!!.slots.size)
        val spaced = ValidatedTheme.parse((" \n" + theme.exportBytes().toString(Charsets.UTF_8)).toByteArray())
        assertEquals(results[0], repository().install(spaced))
        assertArrayEquals(theme.exportBytes(), repository().export(theme.ref()).exportBytes())
        val altered = ValidatedTheme.parse(theme.exportBytes().toString(Charsets.UTF_8).replace("#245EAC", "#123456").toByteArray())
        code("THEME_VERSION_REUSED") { runBlocking { repository().install(altered) } }
        for (themeInput in builtIns.readAll()) {
            code("THEME_BUILTIN_RESERVED") { runBlocking { repository().install(themeInput) } }
            code("THEME_BUILTIN_RESERVED") { runBlocking { repository().delete(themeInput.ref(), 3) } }
        }
        val sameName = ValidatedTheme.parse(custom(2).exportBytes().toString(Charsets.UTF_8)
            .replace("Synthetic resolved theme", "Ocean").toByteArray())
        assertEquals(9, repository().install(sameName).slots.size)
    }

    @Test fun conflictingUnlistedFileCannotBeClaimedOrDeletedByAFailedImport() = runBlocking<Unit> {
        initialize()
        val original = custom()
        files.install(original)
        val before = preferences.data.first()
        val altered = ValidatedTheme.parse(original.exportBytes().toString(Charsets.UTF_8)
            .replace("#245EAC", "#123456").toByteArray())
        assertNotEquals(original.definition, altered.definition)
        code("THEME_VERSION_REUSED") { runBlocking { repository().install(altered) } }
        assertEquals(before, preferences.data.first())
        code("THEME_NOT_AVAILABLE") { runBlocking { repository().delete(original.ref(), 1) } }
        assertArrayEquals(original.exportBytes(), themeFile(original.ref()).readBytes())
        reopen()
        assertEquals(7, repository().recover().slots.size)
        assertArrayEquals(original.exportBytes(), themeFile(original.ref()).readBytes())
        assertEquals(8, repository().install(original).slots.size)
        assertArrayEquals(original.exportBytes(), repository().export(original.ref()).exportBytes())
    }

    @Test fun bothSelectedReferencesAreProtectedEvenInForcedModeAndDeletionIsDurable() = runBlocking<Unit> {
        initialize(); val theme = custom(); repository().install(theme)
        selection().select(1, defaults().copy(light = theme.ref()))
        code("THEME_IN_USE") { runBlocking { repository().delete(theme.ref(), 3) } }
        selection().select(2, defaults().copy(dark = theme.ref(), mode = DeviceThemeMode.LIGHT))
        code("THEME_IN_USE") { runBlocking { repository().delete(theme.ref(), 3) } }
        selection().select(3, defaults())
        code("THEME_CATALOG_CONFLICT") { runBlocking { repository().delete(theme.ref(), 1) } }
        val removed = repository().delete(theme.ref(), 3)
        assertEquals(5L, removed.revision); assertEquals(7, removed.slots.size); assertFalse(themeFile(theme.ref()).exists())
        code("THEME_NOT_AVAILABLE") { runBlocking { repository().export(theme.ref()) } }
        reopen(); assertEquals(removed, repository().state()); assertEquals(defaults(), selection().read()!!.selection)
        code("THEME_MODE_UNSUPPORTED") {
            runBlocking { selection().select(4, defaults().copy(light = defaults().dark)) }
        }
    }

    @Test fun deletionBetweenFileValidationAndSelectionCommitCannotPublishADanglingReference() = runBlocking<Unit> {
        initialize(); val theme = custom(); repository().install(theme)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                entered.complete(Unit); release.await()
                return preferences.updateData(transform)
            }
        }
        val pending = async { runCatching { selection(boundary).select(1, defaults().copy(light = theme.ref())) } }
        try {
            withTimeout(5000) { entered.await() }
            repository().delete(theme.ref(), 3)
        } finally { release.complete(Unit) }
        assertEquals("THEME_NOT_AVAILABLE", (pending.await().exceptionOrNull() as ThemeCatalogException).code)
        assertEquals(defaults(), selection().read()!!.selection); assertEquals(1L, selection().read()!!.revision)
        assertFalse(themeFile(theme.ref()).exists())
        reopen(); assertNotNull(selection().load())
    }

    @Test fun installWriteFailureLeavesOwnedJournalAndRecoveryOnlyDiscardsItsKnownTemporary() = runBlocking<Unit> {
        initialize(); val theme = custom()
        val failure = IOException("synthetic write failure")
        val broken = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw failure
        })
        assertSame(failure, assertThrows(IOException::class.java) { runBlocking { repository(storage = broken).install(theme) } })
        val slot = repository().state()!!.slots.single { it.ref == theme.ref() }
        assertEquals(ThemeInstallPhase.INSTALLING, slot.phase); assertFalse(themeFile(theme.ref()).exists())
        val owned = File(root, ".theme-${slot.operationId}.part").also { it.writeText("interrupted") }
        val unknown = File(root, ".theme-unknown.part").also { it.writeText("preserve") }
        reopen(); assertEquals(7, repository().recover().slots.size)
        assertFalse(owned.exists()); assertEquals("preserve", unknown.readText())
        assertEquals(8, repository().install(theme).slots.size)
        assertEquals(defaults(), selection().read()!!.selection)
    }

    @Test fun publishedFileAfterRenameFailureRecoversWithoutRereadingAnExternalSource() = runBlocking<Unit> {
        initialize(); val theme = custom(); val failure = IOException("after rename")
        val broken = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun rename(source: String, target: String) { super.rename(source, target); throw failure }
        })
        assertSame(failure, assertThrows(IOException::class.java) { runBlocking { repository(storage = broken).install(theme) } })
        assertTrue(themeFile(theme.ref()).isFile)
        assertEquals(ThemeInstallPhase.INSTALLING, repository().state()!!.slots.last().phase)
        reopen(); val recovered = repository().recover()
        assertEquals(ThemeInstallPhase.ACTIVE, recovered.slots.last().phase)
        assertArrayEquals(theme.exportBytes(), repository().export(theme.ref()).exportBytes())
        assertEquals(recovered, repository().recover())
    }

    @Test fun actualMetadataDiskFailureAfterPublicationPreservesJournalForColdRecovery() = runBlocking<Unit> {
        initialize(); val theme = custom(); val moved = File(parent, "moved-preferences")
        var writes = 0
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = preferences.updateData { current ->
                val next = transform(current)
                if (++writes == 2) { assertTrue(prefsDir.renameTo(moved)); prefsDir.writeText("blocked") }
                next
            }
        }
        try {
            assertThrows(IOException::class.java) { runBlocking { repository(boundary).install(theme) } }
        } finally {
            assertTrue(prefsDir.delete()); assertTrue(moved.renameTo(prefsDir))
        }
        assertTrue(themeFile(theme.ref()).isFile)
        reopen(); assertEquals(ThemeInstallPhase.INSTALLING, repository().state()!!.slots.last().phase)
        assertEquals(ThemeInstallPhase.ACTIVE, repository().recover().slots.last().phase)
        assertEquals(defaults(), selection().read()!!.selection)
    }

    @Test fun failedUnlinkLeavesVersionUnselectableAndBothBeforeAndAfterUnlinkFailuresRecover() = runBlocking<Unit> {
        initialize()
        for (after in listOf(false, true)) {
            val theme = custom(if (after) 2 else 1); val installed = repository().install(theme)
            val broken = ThemeFileRepository(parent, object : ThemeFileIo() {
                override fun remove(path: String) {
                    if (path.endsWith(".json")) {
                        if (after) super.remove(path)
                        throw IOException("synthetic unlink failure")
                    }
                    super.remove(path)
                }
            })
            assertThrows(IOException::class.java) { runBlocking { repository(storage = broken).delete(theme.ref(), installed.revision) } }
            assertEquals(!after, themeFile(theme.ref()).exists())
            assertEquals(ThemeInstallPhase.DELETING, repository().state()!!.slots.last().phase)
            code("THEME_DELETE_PENDING") { runBlocking { repository().install(theme) } }
            code("THEME_NOT_AVAILABLE") { runBlocking { repository().export(theme.ref()) } }
            reopen(); assertEquals(7, repository().recover().slots.size); assertFalse(themeFile(theme.ref()).exists())
        }
    }

    @Test fun changedValidContentIsRejectedByExportReplaySelectionAndColdLoad() = runBlocking<Unit> {
        initialize(); val theme = custom(); repository().install(theme)
        selection().select(1, defaults().copy(light = theme.ref()))
        val file = themeFile(theme.ref())
        file.writeText(theme.exportBytes().toString(Charsets.UTF_8).replace("#245EAC", "#123456"))
        code("THEME_CONTENT_CHANGED") { runBlocking { repository().export(theme.ref()) } }
        code("THEME_CONTENT_CHANGED") { runBlocking { repository().install(theme) } }
        code("THEME_CONTENT_CHANGED") { runBlocking { selection().select(2, defaults().copy(light = theme.ref())) } }
        reopen(); code("THEME_CONTENT_CHANGED") { runBlocking { selection().load() } }
        selection().select(2, defaults())
        // Explicit deletion of an unselected corrupt version is allowed, not an implicit fallback.
        assertEquals(7, repository().delete(theme.ref(), 3).slots.size)
        assertFalse(file.exists())
    }

    @Test fun specialDeletionTargetsArePreservedAndJournalCanRecoverAfterRepair() = runBlocking<Unit> {
        initialize()
        val outside = File(parent, "outside.json").also { it.writeText("preserve outside") }
        for (kind in listOf("symlink", "directory", "fifo")) {
            val theme = custom()
            val installed = repository().install(theme)
            val target = themeFile(theme.ref())
            assertTrue(target.delete())
            when (kind) {
                "symlink" -> Os.symlink(outside.path, target.path)
                "directory" -> assertTrue(target.mkdir())
                "fifo" -> Os.mkfifo(target.path, 384)
            }
            assertThrows(Exception::class.java) {
                runBlocking { withTimeout(5000) { repository().delete(theme.ref(), installed.revision) } }
            }.also { assertFalse(it is kotlinx.coroutines.CancellationException) }
            assertEquals("preserve outside", outside.readText())
            assertEquals(ThemeInstallPhase.DELETING, repository().state()!!.slots.last().phase)
            assertTrue(Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            // Repair only this synthetic test target, not an implicit production cleanup policy.
            assertTrue(target.delete())
            reopen()
            assertEquals(7, repository().recover().slots.size)
        }
    }

    @Test fun rootReplacementAfterUnlinkCannotFinalizeDeletionAgainstADifferentDirectory() = runBlocking<Unit> {
        initialize()
        val theme = custom()
        val installed = repository().install(theme)
        val moved = File(parent, "held-theme-root")
        val changed = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun remove(path: String) {
                super.remove(path)
                if (path.endsWith(".json")) {
                    assertTrue(root.renameTo(moved))
                    assertTrue(root.mkdir())
                }
            }
        })
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { repository(storage = changed).delete(theme.ref(), installed.revision) }
            }
            assertEquals("THEME_DIRECTORY_CHANGED", error.message)
            assertEquals(ThemeInstallPhase.DELETING, repository().state()!!.slots.last().phase)
        } finally {
            assertTrue(root.delete())
            assertTrue(moved.renameTo(root))
        }
        reopen()
        assertEquals(7, repository().recover().slots.size)
        assertNotNull(selection().load())
    }

    @Test fun reservationDiskFailureDoesNotWriteThemeBytesOrReserveOwnership() = runBlocking<Unit> {
        initialize()
        val theme = custom()
        val before = preferences.data.first()
        val moved = File(parent, "saved-preferences")
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = preferences.updateData { current ->
                val next = transform(current)
                assertTrue(prefsDir.renameTo(moved)); prefsDir.writeText("blocked")
                next
            }
        }
        try {
            assertThrows(IOException::class.java) { runBlocking { repository(boundary).install(theme) } }
        } finally {
            assertTrue(prefsDir.delete()); assertTrue(moved.renameTo(prefsDir))
        }
        assertFalse(themeFile(theme.ref()).exists())
        reopen()
        assertEquals(before, preferences.data.first())
        assertEquals(7, repository().recover().slots.size)
        assertEquals(8, repository().install(theme).slots.size)
    }

    @Test fun invalidOrCrossFieldInconsistentMetadataNeverDeletesFilesOrFallsBack() = runBlocking<Unit> {
        preferences.edit { it[THEME_CATALOG_KEY] = "{}" }
        code("THEME_CATALOG_JSON") { runBlocking { repository().initialize() } }
        assertFalse(root.exists())
        preferences.edit { it.remove(THEME_CATALOG_KEY) }; initialize()
        val theme = custom(); val installed = repository().install(theme)
        selection().select(1, defaults().copy(light = theme.ref()))
        val corrupted = installed.copy(slots = installed.slots.map { if (it.ref == theme.ref()) it.copy(phase = ThemeInstallPhase.DELETING) else it })
        preferences.edit { it[THEME_CATALOG_KEY] = Json.encodeToString(corrupted) }
        val before = preferences.data.first()
        code("THEME_NOT_AVAILABLE") { runBlocking { repository().recover() } }
        assertTrue(themeFile(theme.ref()).exists()); assertEquals(before, preferences.data.first())
    }

    @Test fun countAndPhysicalStorageLimitsIncludePendingWorkAndUnknownFiles() = runBlocking<Unit> {
        initialize()
        for (index in 1..128) repository().install(custom(index))
        assertEquals(135, repository().state()!!.slots.size)
        code("THEME_CATALOG_LIMIT") { runBlocking { repository().install(custom(129)) } }
        assertFalse(themeFile(custom(129).ref()).exists())
        val first = custom(1); repository().delete(first.ref(), repository().state()!!.revision)
        assertEquals(135, repository().install(custom(129)).slots.size)
        repository().delete(custom(2).ref(), repository().state()!!.revision)
        val filler = File(root, "unknown-large-file")
        val used = root.listFiles()!!.sumOf { it.length() }
        RandomAccessFile(filler, "rw").use { it.setLength(64L * 1024 * 1024 - used) }
        val error = assertThrows(ThemeInputException::class.java) { runBlocking { repository().install(custom(130)) } }
        assertEquals("THEME_STORAGE_LIMIT", error.code)
        assertEquals(ThemeInstallPhase.INSTALLING, repository().state()!!.slots.last().phase)
        assertEquals(134, repository().recover().slots.size); assertTrue(filler.exists())
        assertTrue(filler.delete()); assertEquals(135, repository().install(custom(130)).slots.size)
    }

    @Test fun listingPreservesBrokenAndPendingEntriesWithoutInventingPalettes() = runBlocking<Unit> {
        initialize()
        val brokenTheme = custom()
        repository().install(brokenTheme)
        themeFile(brokenTheme.ref()).writeText("not a theme")
        val pendingTheme = custom(2)
        val brokenStorage = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("pending")
        })
        assertThrows(IOException::class.java) { runBlocking { repository(storage = brokenStorage).install(pendingTheme) } }
        val before = preferences.data.first()
        val listing = repository().listing()
        assertEquals(repository().state(), listing.state)
        assertEquals(selection().read(), listing.selected)
        assertEquals(9, listing.items.size)
        assertEquals(7, listing.items.count { it.content is ThemeCatalogContent.Available })
        assertTrue(listing.items.single { it.slot.ref == brokenTheme.ref() }.content is ThemeCatalogContent.Unavailable)
        assertEquals(ThemeCatalogContent.Pending, listing.items.single { it.slot.ref == pendingTheme.ref() }.content)
        assertEquals(BuiltInTheme.entries.toSet(), listing.items.mapNotNull { it.builtIn }.toSet())
        assertEquals(before, preferences.data.first())
        assertThrows(UnsupportedOperationException::class.java) { (listing.items as MutableList).clear() }
        // A damaged custom entry can be explicitly removed without hiding the rest of the library.
        repository().delete(brokenTheme.ref(), listing.state.revision)
        assertEquals(7, repository().recover().slots.size)
    }

    @Test fun revisionHeadroomReservesCompletionForEveryPendingOperation() = runBlocking<Unit> {
        initialize()
        val base = repository().state()!!
        preferences.edit { it[THEME_CATALOG_KEY] = Json.encodeToString(base.copy(revision = Long.MAX_VALUE - 2)) }
        val broken = ThemeFileRepository(parent, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("stop before publication")
        })
        assertThrows(IOException::class.java) { runBlocking { repository(storage = broken).install(custom()) } }
        assertEquals(Long.MAX_VALUE - 1, repository().state()!!.revision)
        code("THEME_CATALOG_EXHAUSTED") { runBlocking { repository().install(custom(2)) } }
        val recovered = repository().recover()
        assertEquals(Long.MAX_VALUE, recovered.revision); assertEquals(7, recovered.slots.size)
        assertNotNull(selection().load())
    }

    @Test fun physicalEntryCountIncludesUnknownFilesAndPermitsItsExactBoundary() = runBlocking<Unit> {
        initialize()
        repeat(504) { index -> File(root, "unmanaged-$index").writeText("retain") }
        assertEquals(511, root.listFiles()!!.size)
        repository().install(custom())
        assertEquals(512, root.listFiles()!!.size)
        val error = assertThrows(ThemeInputException::class.java) { runBlocking { repository().install(custom(2)) } }
        assertEquals("THEME_STORAGE_LIMIT", error.code)
        assertEquals(9, repository().state()!!.slots.size)
        assertEquals(8, repository().recover().slots.size)
        assertEquals(512, root.listFiles()!!.size)
        repeat(504) { index -> assertEquals("retain", File(root, "unmanaged-$index").readText()) }
    }

    @Test fun malformedCatalogCannotBeMistakenForAnAbsentOrEmptyLibrary() = runBlocking<Unit> {
        initialize()
        val valid = preferences.data.first()[THEME_CATALOG_KEY]!!
        val invalid = listOf(
            "null", "[]", "{}",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"revision\":1", "\"revision\":0"),
            valid.replaceFirst("\"revision\":1", "\"revision\":\"1\""),
            valid.replaceFirst("\"revision\":1", "\"revision\":1.0"),
            valid.replaceFirst("{", "{\"unexpected\":true,"),
            valid.replaceFirst("{", "{\"formatVersion\":1,"),
            valid.replace("\"phase\":\"active\"", "\"phase\":\"installing\""),
            valid.replace(BuiltInTheme.OCEAN.themeId, BuiltInTheme.DUSK.themeId),
            valid.replaceFirst("\"slots\":[", "\"slots\":null,\"extra\":[")
        )
        for (raw in invalid) {
            assertNotEquals(valid, raw)
            preferences.edit { it[THEME_CATALOG_KEY] = raw }
            val before = preferences.data.first()
            assertThrows(ThemeCatalogException::class.java) { runBlocking { repository().initialize() } }
            assertThrows(ThemeCatalogException::class.java) { runBlocking { repository().recover() } }
            assertEquals(before, preferences.data.first())
            assertEquals(7, root.listFiles()!!.size)
        }
        preferences.edit { it[THEME_CATALOG_KEY] = valid }
        reopen()
        assertNotNull(selection().load())
    }

    @Test fun exactMetadataCacheIsImmutableAndDoesNotAcceptChangedOrMalformedInput() = runBlocking<Unit> {
        initialize()
        val raw = preferences.data.first()[THEME_CATALOG_KEY]!!
        val first = ThemeCatalogState.decode(raw)!!
        repeat(100) { assertSame(first, ThemeCatalogState.decode(raw)) }
        assertThrows(UnsupportedOperationException::class.java) { (first.slots as MutableList).clear() }
        val changed = Json.encodeToString(first.copy(revision = first.revision + 1))
        val next = ThemeCatalogState.decode(changed)!!
        assertNotSame(first, next)
        assertEquals(first.revision + 1, next.revision)
        assertEquals(first.slots, next.slots)
        code("THEME_CATALOG_JSON") { runBlocking { ThemeCatalogState.decode("{}") } }
        // An invalid value never authorizes a cached older value for the changed input.
        assertSame(next, ThemeCatalogState.decode(changed))
        assertEquals(first, ThemeCatalogState.decode(raw))
        assertNull(ThemeCatalogState.decode(null))
        assertEquals(first, repository().state())
    }

    @Test fun semanticDigestKeepsIndependentCanonicalHashAcrossLocales() {
        val theme = custom()
        // Independently SHA-256'd sorted, compact JSON from the shared raw fixture, not this encoder.
        val expected = "9c00a463d0033a1bdb9d186e996c2821ac69c967d0d8c84031aaa1a9936f910c"
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                assertEquals(expected, theme.definitionDigest())
            }
        } finally { Locale.setDefault(original) }
    }
}
