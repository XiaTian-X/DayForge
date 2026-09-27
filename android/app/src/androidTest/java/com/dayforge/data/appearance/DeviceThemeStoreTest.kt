package com.dayforge.data.appearance

import android.content.res.Configuration
import android.system.ErrnoException
import android.system.OsConstants
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.domain.appearance.DeviceCardStyle
import com.dayforge.domain.appearance.DeviceThemeMode
import com.dayforge.domain.appearance.DeviceThemeSelection
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.ui.theme.toComposeColors
import com.dayforge.widget.base.toGlanceColors
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceThemeStoreTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val parent = Files.createTempDirectory(app.targetContext.filesDir.toPath(), "theme-selection-").toFile()
    private val prefsDir = File(parent, "preferences").also { check(it.mkdir()) }
    private val file = File(prefsDir, "device.preferences_pb")
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun create() = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    private var preferences = create()
    private val key = stringPreferencesKey("appearance_theme_selection_v1")
    private val unrelated = stringPreferencesKey("synthetic_unrelated")
    private val themes = ThemeFileRepository(parent)
    private fun store(data: DataStore<Preferences> = preferences) = DeviceThemeStore(data, themes)
    private val lightId = "50000000-0000-0000-0000-000000000001"
    private val darkId = "50000000-0000-0000-0000-000000000002"
    private fun choice(mode: DeviceThemeMode = DeviceThemeMode.SYSTEM) = DeviceThemeSelection(
        ThemeVersionRef(lightId, 1), ThemeVersionRef(darkId, 7), mode, DeviceCardStyle.PERSONALIZED)
    private fun persisted(revision: String = "1") = """{"formatVersion":1,"revision":$revision,"selection":{"light":{"themeId":"$lightId","revision":1},"dark":{"themeId":"$darkId","revision":7},"mode":"system","cardStyle":"personalized"}}"""
    private fun themeFile(id: String, revision: Int) = File(parent, "theme-definitions-v1/$id-$revision.json")
    private suspend fun install() {
        val raw = app.context.assets.open("next/theme.json").bufferedReader().use { it.readText() }
        themes.install(ValidatedTheme.parse(raw.toByteArray()))
        themes.install(ValidatedTheme.parse(raw.replace(lightId, darkId).replace("\"revision\": 1", "\"revision\": 7")
            .replace("#AFC6FF", "#FEDCBA").toByteArray()))
    }
    private suspend fun reopen() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = create()
    }
    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(parent.deleteRecursively())
    }
    private fun code(expected: String, action: () -> Unit) =
        assertEquals(expected, assertThrows(ThemeSelectionException::class.java) { action() }.code)

    @Test fun uninitializedIsExplicitAndDoesNotInterpretOrOverwriteLegacyPreferences() = runBlocking {
        preferences.edit { it[stringPreferencesKey("light_color_theme")] = "ocean"; it[unrelated] = "keep" }
        val before = preferences.data.first()
        assertNull(store().read()); assertNull(store().load())
        assertNull(store().resolvedThemes().first())
        assertEquals(before, preferences.data.first())
        assertFalse(File(parent, "theme-definitions-v1").exists())
        reopen(); assertNull(store().read()); assertEquals(before, preferences.data.first())
    }

    @Test fun completeSelectionSurvivesColdReopenAndFeedsActualComposeAndGlanceInEveryMode() = runBlocking {
        install(); preferences.edit { it[unrelated] = "keep" }
        var revision = 0L
        for (mode in DeviceThemeMode.entries) {
            val committed = store().select(revision, choice(mode))
            revision++
            assertEquals(revision, committed.saved.revision)
            assertEquals(choice(mode), committed.saved.selection)
            if (mode == DeviceThemeMode.SYSTEM) assertEquals(Json.parseToJsonElement(persisted()),
                Json.parseToJsonElement(checkNotNull(preferences.data.first()[key])))
            reopen()
            val loaded = checkNotNull(store().load())
            assertEquals(committed.saved, loaded.saved)
            assertEquals(lightId, loaded.light.themeId); assertEquals(darkId, loaded.dark.themeId)
            assertEquals(7, loaded.dark.revision)
            assertEquals(DeviceCardStyle.PERSONALIZED, loaded.saved.selection.cardStyle)
            for (night in listOf(false, true)) {
                val expected = if (mode == DeviceThemeMode.DARK || (mode == DeviceThemeMode.SYSTEM && night))
                    0xfffedcba.toInt() else 0xff245eac.toInt()
                val base = app.targetContext
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                })
                assertEquals(expected, loaded.resolve(night).toComposeColors().primary.toArgb())
                assertEquals(expected, loaded.toGlanceColors().primary.getColor(context).toArgb())
            }
            assertEquals("keep", preferences.data.first()[unrelated])
        }
        val sameVersion = choice().copy(dark = choice().light, cardStyle = DeviceCardStyle.FOLLOW_THEME)
        val selected = store().select(revision, sameVersion)
        assertEquals(0xffafc6ff.toInt(), selected.dark.material.getValue("primary"))
        assertEquals(DeviceCardStyle.FOLLOW_THEME, selected.saved.selection.cardStyle)
    }

    @Test fun staleSnapshotsIncludingAbaAndParallelWritersCannotOverwriteNewerChoices() = runBlocking {
        install()
        val first = store().select(0, choice())
        val results = (0..7).map { index -> async(Dispatchers.Default) {
            runCatching { store().select(1, choice(if (index % 2 == 0) DeviceThemeMode.LIGHT else DeviceThemeMode.DARK)) }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach {
            assertEquals("THEME_SELECTION_CONFLICT", (it.exceptionOrNull() as ThemeSelectionException).code)
        }
        assertEquals(results.single { it.isSuccess }.getOrThrow().saved, store().read())
        assertEquals(1L, first.saved.revision); assertEquals(choice(), first.saved.selection)
        val returned = store().select(2, choice())
        assertEquals(3L, returned.saved.revision)
        code("THEME_SELECTION_CONFLICT") { runBlocking { store().select(1, choice()) } }
        val before = preferences.data.first()
        assertEquals(returned.saved, store().select(3, choice()).saved)
        assertEquals(before, preferences.data.first())
        code("THEME_SELECTION_CONFLICT") { runBlocking { store().select(0, choice()) } }
        reopen(); assertEquals(returned.saved, store().read())
    }

    @Test fun saveReturnsItsOwnCommittedSnapshotEvenWhenANewerWriterFinishesBeforeReturn() = runBlocking {
        install()
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val committed = preferences.updateData(transform)
                // Another legitimate writer commits before the first caller receives its result.
                store().select(1, choice(DeviceThemeMode.DARK))
                return committed
            }
        }
        val original = store(boundary).select(0, choice())
        assertEquals(1L, original.saved.revision)
        assertEquals(DeviceThemeMode.SYSTEM, original.saved.selection.mode)
        assertEquals(0xff245eac.toInt(), original.resolve(false).material.getValue("primary"))
        val latest = checkNotNull(store().load())
        assertEquals(2L, latest.saved.revision)
        assertEquals(DeviceThemeMode.DARK, latest.saved.selection.mode)
        assertEquals(0xfffedcba.toInt(), latest.resolve(false).material.getValue("primary"))
    }

    @Test fun missingOrDamagedFilesRejectBothChangesAndNoOpsWithoutChangingStoredSelection() = runBlocking {
        assertThrows(IOException::class.java) { runBlocking { store().select(0, choice()) } }
        assertNull(store().read())
        install(); store().select(0, choice())
        val before = preferences.data.first()
        val dark = themeFile(darkId, 7); val original = dark.readBytes()
        assertTrue(dark.delete())
        val missing = assertThrows(ErrnoException::class.java) { runBlocking { store().select(1, choice(DeviceThemeMode.LIGHT)) } }
        assertEquals(OsConstants.ENOENT, missing.errno)
        assertEquals(before, preferences.data.first())
        dark.writeText("{}")
        assertThrows(ThemeInputException::class.java) { runBlocking { store().select(1, choice()) } }
        assertThrows(ThemeInputException::class.java) { runBlocking { store().load() } }
        assertEquals(before, preferences.data.first())
        reopen(); assertEquals(1L, store().read()!!.revision)
        assertThrows(ThemeInputException::class.java) { runBlocking { store().load() } }
        dark.writeBytes(original)
        assertEquals(1L, store().select(1, choice()).saved.revision)
        assertNotNull(store().load())
    }

    @Test fun malformedStoredValuesAreNeverTreatedAsMissingOrOverwrittenByASelection() = runBlocking {
        install()
        val source = persisted()
        val bad = listOf(
            "null", "[]", "{}", source + " {}", source.replaceFirst("{", "{\"revision\":1,"),
            source.replaceFirst("{", "{\"revi\\u0073ion\":1,"), source.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            source.replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            source.replace("\"revision\":1", "\"revision\":\"1\""), persisted("0"), persisted("-1"),
            persisted("9223372036854775808"), persisted("1.0"), source.replace("system", "auto"),
            source.replace("personalized", "unknown"), source.replace("\"system\"", "1"),
            source.replace(lightId, "../unsafe"), source.replace("\"revision\":7", "\"revision\":0"),
            source.replaceFirst("{", "{\"unknown\":true,"), " ".repeat(4097),
            source.replace("\"mode\":\"system\",", ""), source.replace("personalized", "\\uD800")
        )
        for (text in bad) {
            preferences.edit { it[key] = text; it[unrelated] = "keep" }
            val before = preferences.data.first()
            assertThrows(text.take(80), ThemeSelectionException::class.java) { runBlocking { store().read() } }
            assertThrows(ThemeSelectionException::class.java) { runBlocking { store().select(0, choice()) } }
            assertEquals(before, preferences.data.first())
        }
        preferences.edit { it[intPreferencesKey(key.name)] = 1 }
        val before = preferences.data.first()
        assertThrows(ClassCastException::class.java) { runBlocking { store().read() } }
        assertThrows(ClassCastException::class.java) { runBlocking { store().select(0, choice()) } }
        assertEquals(before, preferences.data.first())
        reopen(); assertEquals(before, preferences.data.first())
    }

    @Test fun revisionExhaustionRejectsChangesButAllowsVerifiedNoOpWithoutOverflow() = runBlocking {
        install(); preferences.edit { it[key] = persisted(Long.MAX_VALUE.toString()) }
        val before = preferences.data.first()
        assertEquals(Long.MAX_VALUE, store().select(Long.MAX_VALUE, choice()).saved.revision)
        code("THEME_SELECTION_EXHAUSTED") { runBlocking { store().select(Long.MAX_VALUE, choice(DeviceThemeMode.DARK)) } }
        assertEquals(before, preferences.data.first())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store().select(-1, choice()) } }
        reopen(); assertEquals(Long.MAX_VALUE, store().read()!!.revision)
    }

    @Test fun exactSelectionSizeIsAllowedAndInvalidReferenceConstructorsAreRejected() = runBlocking {
        install()
        preferences.edit { it[key] = persisted().padEnd(4096) }
        assertEquals(choice(), store().read()!!.selection)
        val before = preferences.data.first()
        assertEquals(1L, store().select(1, choice()).saved.revision)
        assertEquals(before, preferences.data.first())
        for (id in listOf("", "../theme", lightId.uppercase().replaceFirst("500", "ABC"), "ocean")) {
            assertThrows(IllegalArgumentException::class.java) { ThemeVersionRef(id, 1) }
        }
        for (revision in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ThemeVersionRef(lightId, revision) }
        }
        preferences.edit { it[key] = persisted().padEnd(4097) }
        code("THEME_SELECTION_LIMIT") { runBlocking { store().read() } }
    }

    @Test fun actualDatastoreWriteFailurePreservesWholePreviousValueAndCanRetryAfterRepair() = runBlocking {
        install(); store().select(0, choice()); preferences.edit { it[unrelated] = "keep" }
        val before = preferences.data.first()
        val moved = File(parent, "moved-preferences")
        // Fault after DataStore reads the old value, immediately before its real disk commit.
        // Moving it before the transaction would test a missing-file read/CAS conflict instead.
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                preferences.updateData { current ->
                    val next = transform(current)
                    assertTrue(prefsDir.renameTo(moved)); prefsDir.writeText("not a directory")
                    next
                }
        }
        try {
            assertThrows(IOException::class.java) { runBlocking { store(boundary).select(1, choice(DeviceThemeMode.DARK)) } }
        } finally {
            assertTrue(prefsDir.delete()); assertTrue(moved.renameTo(prefsDir))
        }
        reopen(); assertEquals(before, preferences.data.first())
        val retried = store().select(1, choice(DeviceThemeMode.DARK))
        assertEquals(2L, retried.saved.revision)
        reopen(); assertEquals(retried.saved, store().read()); assertEquals("keep", preferences.data.first()[unrelated])
    }

    @Test fun cancelledCallerDoesNotPublishAChoiceAndNextCallerCanProceed() = runBlocking {
        install()
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch { preferences.edit { locked.complete(Unit); release.await() } }
        locked.await()
        val entered = CompletableDeferred<Unit>()
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                entered.complete(Unit)
                return preferences.updateData(transform)
            }
        }
        val caller = launch { store(boundary).select(0, choice()) }
        try { withTimeout(5000) { entered.await() }; caller.cancelAndJoin() }
        finally { release.complete(Unit); holder.join(); caller.cancelAndJoin() }
        assertNull(store().read())
        assertEquals(1L, store().select(0, choice()).saved.revision)
        reopen(); assertEquals(1L, store().read()!!.revision)
    }

    @Test fun cancellationAfterCommitRequiresRereadAndCannotOverwriteWithAnOldRevision() = runBlocking {
        install()
        val committed = CompletableDeferred<Unit>()
        val boundary = object : DataStore<Preferences> by preferences {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                preferences.updateData(transform)
                committed.complete(Unit)
                awaitCancellation()
            }
        }
        val caller = launch { store(boundary).select(0, choice()) }
        try { withTimeout(5000) { committed.await() } } finally { caller.cancelAndJoin() }
        reopen(); assertEquals(1L, store().read()!!.revision)
        code("THEME_SELECTION_CONFLICT") { runBlocking { store().select(0, choice(DeviceThemeMode.DARK)) } }
        assertEquals(1L, store().select(1, choice()).saved.revision)
    }

    @Test fun observationIgnoresUnrelatedPreferencesAndPropagatesThemeLoadFailure() = runBlocking {
        install(); store().select(0, choice())
        val events = Channel<Result<Long>>(Channel.UNLIMITED)
        val observer = launch {
            try { store().resolvedThemes().collect { events.send(Result.success(checkNotNull(it).saved.revision)) } }
            catch (error: ThemeInputException) { events.send(Result.failure(error)) }
        }
        try {
            assertEquals(1L, withTimeout(5000) { events.receive().getOrThrow() })
            themeFile(darkId, 7).writeText("{}")
            preferences.edit { it[unrelated] = "changed" }
            assertNull(withTimeoutOrNull(200) { events.receive() })
            // A new selected snapshot must load again and must not disguise corruption as defaults.
            preferences.edit { it[key] = persisted("2") }
            assertTrue(withTimeout(5000) { events.receive() }.exceptionOrNull() is ThemeInputException)
        } finally { observer.cancelAndJoin(); events.close() }
        assertEquals(2L, store().read()!!.revision)
        assertEquals("changed", preferences.data.first()[unrelated])
    }
}
