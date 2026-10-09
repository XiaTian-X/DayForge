package com.dayforge.data.repository

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.R
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.appearance.*
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.SyncProgress
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.settings.*
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.ThemeDefinition
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.IconReference
import com.dayforge.data.model.HabitType
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.FailMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings/VM, persistent Room, producer, owned files and loopback HTTP. Only the system
 * picker/network observations and provider opens are boundaries; all descriptors/bytes are real.
 */
@RunWith(AndroidJUnit4::class)
class SettingsConfigV2WorkflowTest : NextObjectEditorFixture() {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var themes: DeviceThemeController
    private var model: SettingsViewModel? = null
    private val inputUri = Uri.parse("content://config-test/source")
    private val outputUri = Uri.parse("content://config-test/output")
    private val input get() = File(directory, "input.zip")
    private val output get() = File(directory, "output.zip")
    private var reads = 0
    private var writes = 0
    private var request: Pair<Int, ActivityResultContract<*, *>>? = null
    private var pickerInput: Any? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) { request = requestCode to contract; pickerInput = input }
    }

    @Before fun openThemes() = runBlocking<Unit> {
        register()
        themes = DeviceThemeController(DeviceThemeRepository(dataStore, ThemeFileRepository(directory),
            BuiltInThemes(app.assets)), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        themes.current()
    }
    @After fun closeThemes() = runBlocking<Unit> {
        model?.let { withTimeout(5000) { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        if (::themes.isInitialized) themes.close()
    }
    private fun importer() = NextConfigImportRepository(db, tokens, sessions, icons,
        NextObjectCreator(db, tokens, sessions, icons), app)
    private fun exporter() = NextConfigExportRepository(db, tokens, sessions, preferences, icons, themes)
    private fun workflow(documents: ConfigBundleDocuments = documents()) =
        SettingsConfigV2Workflow(tokens, exporter(), importer(), documents, themes)
    private fun documents() = ConfigBundleDocuments({ uri, _ ->
        assertEquals(inputUri, uri); reads++
        AssetFileDescriptor(ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY), 0, input.length())
    }, { uri, _ ->
        assertEquals(outputUri, uri); writes++
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY)
    })
    private suspend fun source(name: String = "Daily check", sourceThemes: List<ThemeDefinition>? = null): ValidatedConfigBundle {
        val full = ConfigFileFixture.manifest()
        val bundle = full.copy(themes = sourceThemes ?: full.themes, nodes = listOf(full.nodes[1].copy(name = name, parentKey = null),
            full.nodes.last().copy(parentKey = null)), iconPack = null,
            unresolvedRoles = listOf("habit.water", "metric.weight", "task.default"))
        return ConfigBundleOutput.create(bundle) { error("No asset dependency") }
    }
    private suspend fun emptyReplica() {
        db.clearAllData() // Isolated testbed only, not production reset/bootstrap.
        db.nextSyncStateDao().insert(NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0,
            "a".repeat(64), "b".repeat(64)))
    }
    private suspend fun entry(importId: String, source: ValidatedConfigBundle): NextConfigImportStore.Entry {
        val context = icons.capture()
        return sessions.exclusive { db.withTransaction { NextConfigImportStore(db).read(context, importId, source) } }
    }
    private suspend fun preview(flow: SettingsConfigV2Workflow, source: ValidatedConfigBundle) {
        input.writeBytes(source.exportBytes()); flow.refresh(); assertTrue(flow.beginImport())
        flow.readImport(inputUri); assertNotNull(flow.state.value.preview)
    }
    private suspend fun mount(flow: SettingsConfigV2Workflow) {
        val sync = mockk<SyncManager>()
        every { sync.syncProgress } returns MutableStateFlow(SyncProgress.Idle)
        every { sync.getLastSyncTime() } returns flowOf(null)
        every { sync.canEditStructure() } returns flowOf(true)
        every { sync.isPrimaryEditor() } returns flowOf(false)
        every { sync.observeRejectedChanges() } returns flowOf(emptyList())
        every { sync.observeConflicts() } returns flowOf(emptyList())
        every { sync.observeRejectedTimerCommands() } returns flowOf(emptyList())
        val monitor = mockk<NetworkMonitor>()
        every { monitor.state } returns MutableStateFlow(NetworkMonitor.Snapshot())
        val config = SettingsConfigWorkflow(app, db.habitDao(), db.metricDao(), db.timeLogDao(), db.completionDao(),
            db.metricLogDao(), ConfigExportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao()),
            ConfigImportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao(), db), flow)
        val vm = withContext(Dispatchers.Main) { own(SettingsViewModel(app, sync, tokens, preferences, monitor,
            habits(), db.habitDao(), db.timeLogDao(), config, SettingsAppearanceWorkflow(app, preferences, themes), sessions)) }
        model = vm
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { MaterialTheme { SettingsScreen(vm, onNavigateBack = {}) } }
        }
        compose.waitUntil(5000) { flow.state.value.profile == ConfigFileProfile.NEXT }
    }
    private fun settingsClick(id: Int) {
        val matcher = hasText(app.getString(id)) and hasClickAction()
        compose.onNodeWithTag("settings-list").performScrollToNode(matcher)
        compose.onNode(matcher).performClick()
    }
    private fun deliver(uri: Uri?) {
        compose.waitUntil(5000) { request != null }
        val saved = requireNotNull(request); request = null
        compose.runOnIdle {
            val result = saved.second.parseResult(if (uri == null) android.app.Activity.RESULT_CANCELED
                else android.app.Activity.RESULT_OK, uri?.let { android.content.Intent().setData(it) })
            registry.dispatchResult(saved.first, result)
        }
    }

    @Test fun formalExportFreezesBeforePickerAndWritesRealValidatedArchiveWithoutReadingProvider() = runBlocking<Unit> {
        val flow = workflow(); mount(flow)
        settingsClick(R.string.settings_export_config)
        compose.waitUntil(5000) { flow.state.value.exportChoices != null }
        compose.onNodeWithTag("config-export-confirm").performClick()
        compose.waitUntil(5000) { request != null }
        assertTrue(request!!.second is ActivityResultContracts.CreateDocument)
        assertTrue((pickerInput as String).endsWith(".zip")); assertFalse(output.exists())
        producer().write(local()) { db.habitDao().update(habit.copy(name = "Changed while picker was open")) }
        deliver(outputUri)
        compose.waitUntil(10000) { flow.state.value.message == ConfigFileMessage.EXPORTED }
        assertEquals(1, writes); assertEquals(0, reads)
        val frozen = ValidatedConfigBundle.parse(output.readBytes()).manifest
        assertTrue(frozen.nodes.any { it.name == habit.name }); assertFalse(frozen.nodes.any { it.name.startsWith("Changed") })
        assertEquals("Changed while picker was open", db.habitDao().getHabitById(habit.id)!!.name)
        assertEquals(0, count("next_config_imports"))
    }

    @Test fun formalChoicesExportOnlySelectedCompletedTemplateAndExactSameNameThemeVersionFromFrozenDefinitions() = runBlocking<Unit> {
        val repo = onceHabits(onceRepository())
        suspend fun completed(name: String): Long {
            val id = repo.createHabit(name, "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
                failMode = FailMode.LOOSE,
                appearance = ObjectAppearance(IconReference.Role("task.reading"), "#123456", "theme"),
                completionPolicy = "one_and_done", creationAuthority = creator.capture())
            withContext(Dispatchers.Main) { repo.logCompletion(app, id) }
            return id
        }
        val first = completed("Selected completed item"); val second = completed("Excluded completed item")
        val firstRow = db.habitDao().getHabitById(first)!!
        val secondRow = db.habitDao().getHabitById(second)!!
        val original = ConfigFileFixture.manifest().themes.single()
        val newer = original.copy(revision = 2)
        for (theme in listOf(original, newer)) themes.install(ValidatedTheme.parse(
            kotlinx.serialization.json.Json.encodeToString(theme).toByteArray()))
        val selection = themes.current().saved
        val flow = workflow(); mount(flow); settingsClick(R.string.settings_export_config)
        compose.waitUntil(5000) { flow.state.value.exportChoices != null }
        assertTrue(flow.state.value.selectedItems.isEmpty()); assertTrue(flow.state.value.selectedThemes.isEmpty())
        assertEquals(setOf(firstRow.uuid, secondRow.uuid), flow.state.value.exportChoices!!.items.map { it.first }.toSet())
        val itemTag = "config-template-${firstRow.uuid}"; val themeTag = "config-theme-${newer.themeId}:2"
        compose.onNodeWithTag("config-export-list").performScrollToNode(hasTestTag(itemTag))
        compose.onNodeWithTag(itemTag).performClick()
        compose.waitUntil(5000) { firstRow.uuid in flow.state.value.selectedItems }
        compose.onNodeWithTag("config-export-list").performScrollToNode(hasTestTag(themeTag))
        compose.onNodeWithTag(themeTag).performClick()
        compose.waitUntil(5000) { ThemeVersionRef(newer.themeId, 2) in flow.state.value.selectedThemes }
        // The choice view is a capture, not an invitation to mix later definitions or a third version.
        producer().write(local()) { db.habitDao().update(firstRow.copy(name = "Renamed after capture")) }
        themes.install(ValidatedTheme.parse(kotlinx.serialization.json.Json.encodeToString(newer.copy(revision = 3)).toByteArray()))
        val before = db.syncOutboxDao().getAll()
        compose.onNodeWithTag("config-export-confirm").performClick(); deliver(outputUri)
        compose.waitUntil(10000) { flow.state.value.message == ConfigFileMessage.EXPORTED }
        val bundle = ValidatedConfigBundle.parse(output.readBytes()).manifest
        assertTrue(bundle.nodes.any { it.name == firstRow.name }); assertFalse(bundle.nodes.any { it.name == secondRow.name })
        assertFalse(bundle.nodes.any { it.name.startsWith("Renamed") }); assertEquals(listOf(newer), bundle.themes)
        assertEquals(before, db.syncOutboxDao().getAll()); assertEquals(selection, themes.current().saved)
        assertTrue(onceRepository().read(first).completed); assertTrue(onceRepository().read(second).completed)
        assertEquals(1, writes); assertEquals(0, reads)
    }

    @Test fun selectedThemesKeepSavedNewIdentitiesThroughRealBusinessFailureColdRecoveryAndIdempotentRetry() = runBlocking<Unit> {
        emptyReplica(); val source = source(); val originalTheme = source.manifest.themes.single()
        val selection = themes.current().saved; val originalCatalog = themes.catalog()!!
        val first = workflow(); input.writeBytes(source.exportBytes()); mount(first)
        settingsClick(R.string.settings_import_config); deliver(inputUri)
        compose.waitUntil(10000) { first.state.value.preview != null }
        val themeTag = "config-theme-${originalTheme.themeId}:${originalTheme.revision}"
        compose.onNodeWithTag(themeTag).performScrollTo().performClick()
        compose.waitUntil(5000) { ThemeVersionRef(originalTheme.themeId, originalTheme.revision) in first.state.value.selectedThemes }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER config_theme_business_fault BEFORE INSERT ON metrics " +
            "BEGIN SELECT RAISE(ABORT,'real business failure after theme install'); END")
        compose.onNodeWithTag("config-file-confirm").performClick()
        compose.waitUntil(10000) { first.state.value.message == ConfigFileMessage.FAILED && !first.state.value.busy &&
            first.state.value.progress?.phase == ConfigImportPhase.PREPARED }
        val id = first.state.value.progress!!.importId; val saved = entry(id, source); val installed = saved.plan.themes.single()
        assertFalse(saved.committed); assertEquals(0, count("habits")); assertEquals(0, count("sync_outbox"))
        assertNotEquals(originalTheme.themeId, installed.themeId); assertEquals(1, installed.revision)
        assertEquals(originalTheme.light, installed.light); assertEquals(originalTheme.dark, installed.dark)
        val ref = ThemeVersionRef(installed.themeId, 1)
        assertEquals(installed, themes.export(ref).definition)
        compose.waitUntil(5000) { requireNotNull(model).themeChoices.value.any { it.ref == ref && it.available } }
        assertEquals(originalCatalog.slots.size + 1, themes.catalog()!!.slots.size); assertEquals(selection, themes.current().saved)
        val installedCatalog = themes.catalog()!!
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER config_theme_business_fault")
        withContext(Dispatchers.Main) { requireNotNull(model).viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        model = null
        storage.reopen(); themes.close()
        themes = DeviceThemeController(DeviceThemeRepository(dataStore, ThemeFileRepository(directory), BuiltInThemes(app.assets)),
            CoroutineScope(SupervisorJob() + Dispatchers.IO)); themes.current()
        val cold = workflow(); cold.refresh(); assertTrue(cold.beginImport(true))
        input.writeBytes(source.exportBytes()); cold.readImport(inputUri)
        assertTrue(cold.state.value.selectedThemes.isEmpty()) // recovery never silently reenables optional installs
        cold.selectTheme(ThemeVersionRef(originalTheme.themeId, originalTheme.revision), true); cold.confirmImport()
        assertEquals(ConfigFileMessage.LOCAL_COMMITTED, cold.state.value.message)
        assertEquals(saved.plan.identities, entry(id, source).plan.identities)
        assertEquals(installedCatalog, themes.catalog()); assertEquals(installed, themes.export(ref).definition)
        assertEquals(selection, themes.current().saved); assertEquals(2, count("habits")); assertEquals(0, count("next_acceptances"))
    }

    @Test fun optionalThemeFileFailureKeepsRealPartialCatalogAndPreparedGroupWithoutBusinessWritesAndExplicitCancelRetainsIt() = runBlocking<Unit> {
        emptyReplica()
        val original = ConfigFileFixture.manifest().themes.single()
        val second = original.copy(themeId = id(720), name = "Second config theme")
        val source = source(sourceThemes = listOf(original, second))
        val selection = themes.current().saved
        themes.close()
        themes = DeviceThemeController(DeviceThemeRepository(dataStore, ThemeFileRepository(directory, object : ThemeFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (ValidatedTheme.parse(bytes).definition.name == second.name) throw IOException("actual theme write failure")
                return super.write(fd, bytes, offset, length)
            }
        }), BuiltInThemes(app.assets)), CoroutineScope(SupervisorJob() + Dispatchers.IO)); themes.current()
        val flow = workflow(); preview(flow, source)
        flow.selectTheme(ThemeVersionRef(original.themeId, original.revision), true)
        flow.selectTheme(ThemeVersionRef(second.themeId, second.revision), true)
        flow.confirmImport(); assertEquals(ConfigFileMessage.FAILED, flow.state.value.message)
        val id = flow.state.value.progress!!.importId; val saved = entry(id, source)
        val firstRef = ThemeVersionRef(saved.plan.themes[0].themeId, 1)
        val secondRef = ThemeVersionRef(saved.plan.themes[1].themeId, 1)
        assertEquals(saved.plan.themes[0], themes.export(firstRef).definition)
        assertEquals(ThemeInstallPhase.INSTALLING, themes.catalog()!!.slots.single { it.ref == secondRef }.phase)
        assertFalse(saved.committed); assertEquals(0, count("habits")); assertEquals(0, count("sync_outbox"))
        assertEquals(selection, themes.current().saved)
        flow.refresh(); assertTrue(flow.beginImport(true)); input.writeBytes(source.exportBytes()); flow.readImport(inputUri)
        flow.abandonPrepared(); assertEquals(ConfigFileMessage.ABANDONED, flow.state.value.message)
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("habits"))
        assertEquals(saved.plan.themes[0], themes.export(firstRef).definition)
        assertEquals(ThemeInstallPhase.INSTALLING, themes.catalog()!!.slots.single { it.ref == secondRef }.phase)
        assertEquals(selection, themes.current().saved)
    }

    @Test fun exportChoicesCancelOrReauthenticationNeverLaunchesImplicitNewSnapshotAndForeignThemeSelectionCannotInstall() = runBlocking<Unit> {
        val flow = workflow(); flow.refresh(); flow.openExportChoices()
        assertNotNull(flow.state.value.exportChoices); flow.dismissPreview()
        assertFalse(flow.beginExport(requireSelection = true)); assertEquals(0, writes)
        flow.refresh(); flow.openExportChoices()
        tokens.saveLoginSession("synthetic-export-new", "synthetic-refresh", "member", id(1), false); register()
        flow.refresh(); assertNull(flow.state.value.exportChoices); assertFalse(flow.beginExport(requireSelection = true))
        emptyReplica(); val source = source(); val importer = importer()
        val id = importer.confirmReplacement(importer.previewReplacement(source))
        val context = icons.capture(); val before = themes.catalog()
        assertEquals("CONFIG_THEME_NOT_AVAILABLE", runCatching { importer.prepareThemeInstall(id, source,
            setOf(ThemeVersionRef(id(999), 1)), context) }.exceptionOrNull()?.message)
        tokens.saveLoginSession("synthetic-theme-new", "synthetic-refresh", "member", id(1), false); register()
        assertTrue(runCatching { importer.prepareThemeInstall(id, source,
            setOf(ThemeVersionRef(source.manifest.themes.single().themeId, 1)), context) }.isFailure)
        assertEquals(before, themes.catalog()); assertFalse(entry(id, source).committed); assertEquals(0, count("habits"))
    }

    @Test fun actualThemeChoiceLimitAllowsSixteenAndRejectsSeventeenWithoutTruncatingOrChangingSelection() = runBlocking<Unit> {
        val original = ConfigFileFixture.manifest().themes.single()
        val definitions = (1..17).map { original.copy(themeId = id(730 + it), name = "Theme choice $it") }
        for (theme in definitions) themes.install(ValidatedTheme.parse(kotlinx.serialization.json.Json.encodeToString(theme).toByteArray()))
        val before = themes.catalog(); val selection = themes.current().saved
        val flow = workflow(); flow.refresh(); flow.openExportChoices()
        for (theme in definitions) flow.selectTheme(ThemeVersionRef(theme.themeId, theme.revision), true)
        assertEquals(16, flow.state.value.selectedThemes.size)
        assertFalse(ThemeVersionRef(definitions.last().themeId, definitions.last().revision) in flow.state.value.selectedThemes)
        val options = exporter().options()
        assertEquals("CONFIG_THEME_NOT_AVAILABLE", runCatching { exporter().prepare(options, emptySet(),
            definitions.map { ThemeVersionRef(it.themeId, it.revision) }.toSet()) }.exceptionOrNull()?.message)
        assertTrue(flow.beginExport(requireSelection = true)); flow.writeExport(outputUri)
        assertEquals(definitions.take(16), ValidatedConfigBundle.parse(output.readBytes()).manifest.themes)
        assertEquals(before, themes.catalog()); assertEquals(selection, themes.current().saved)
        assertEquals(0, count("next_config_imports"))
    }

    @Test fun formalImportUsesOneFrozenReadAndShowsLocalNotNetworkSuccessWithUnfinishedNewItems() = runBlocking<Unit> {
        emptyReplica(); val source = source(); input.writeBytes(source.exportBytes())
        val flow = workflow(); val selection = themes.current().saved; mount(flow)
        settingsClick(R.string.settings_import_config)
        compose.waitUntil(5000) { request != null }
        assertTrue(request!!.second is ActivityResultContracts.OpenDocument)
        deliver(inputUri)
        compose.waitUntil(10000) { flow.state.value.preview != null }
        assertEquals(3, flow.state.value.preview!!.unresolvedRoles)
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("habits")); assertEquals(1, reads)
        compose.onNodeWithTag("config-file-preview").assertIsDisplayed()
        input.writeBytes(byteArrayOf(0)) // confirmation cannot reopen the URI
        compose.onNodeWithTag("config-file-confirm").performClick()
        compose.waitUntil(10000) { flow.state.value.message == ConfigFileMessage.LOCAL_COMMITTED && !flow.state.value.busy }
        assertEquals(2, count("habits")); assertEquals(1, count("metrics")); assertEquals(1, count("habit_metric_links"))
        assertEquals(4, count("sync_outbox")); assertEquals(1, reads); assertEquals(selection, themes.current().saved)
        val progress = requireNotNull(flow.state.value.progress)
        assertEquals(ConfigImportPhase.LOCAL_COMMITTED, progress.phase); assertEquals(0, progress.accepted); assertEquals(4, progress.total)
        val once = db.habitDao().getAllHabitsOnce().single { it.completionPolicy == "one_and_done" }
        assertFalse(onceRepository().read(once.id).completed)
        for (table in listOf("completions", "timelogs", "metric_logs", "next_acceptances")) assertEquals(table, 0, count(table))
        compose.onNodeWithTag("config-file-result").assertIsDisplayed()
    }

    @Test fun realBusinessFailureAndColdWrongFileRecoveryKeepOriginalMappingUntilExplicitExactRetry() = runBlocking<Unit> {
        emptyReplica(); val source = source(); val first = workflow()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER config_ui_fault BEFORE INSERT ON metrics " +
            "BEGIN SELECT RAISE(ABORT,'actual business failure'); END")
        preview(first, source); first.confirmImport()
        assertEquals(ConfigFileMessage.FAILED, first.state.value.message)
        val importId = requireNotNull(first.state.value.progress).importId; val original = entry(importId, source)
        assertFalse(original.committed); assertEquals(0, count("habits")); assertEquals(0, count("next_request_origins"))
        assertFalse(first.beginImport()); db.openHelper.writableDatabase.execSQL("DROP TRIGGER config_ui_fault")
        storage.reopen()
        val cold = workflow(); cold.refresh(); assertEquals(importId, cold.state.value.progress!!.importId)
        assertTrue(cold.beginImport(true)); input.writeBytes(source("Different file").exportBytes()); cold.readImport(inputUri)
        assertNull(cold.state.value.preview); assertEquals(ConfigFileMessage.FAILED, cold.state.value.message)
        assertEquals(original.plan.identities, entry(importId, source).plan.identities); assertEquals(1, count("next_config_imports"))
        assertTrue(cold.beginImport(true)); input.writeBytes(source.exportBytes()); cold.readImport(inputUri)
        assertTrue(cold.state.value.preview!!.canAbandon); cold.confirmImport()
        assertEquals(original.plan.identities, entry(importId, source).plan.identities)
        assertEquals(original.plan.creationOperationIds.values.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
        assertEquals(ConfigFileMessage.LOCAL_COMMITTED, cold.state.value.message); assertEquals(1, count("next_config_imports"))
    }

    @Test fun explicitAbandonRequiresOriginalFileAndRemovesOnlyOwnPreparedJournal() = runBlocking<Unit> {
        emptyReplica(); val source = source(); val importer = importer()
        val importId = importer.confirmReplacement(importer.previewReplacement(source))
        val flow = workflow(); flow.refresh(); assertTrue(flow.beginImport(true))
        input.writeBytes(source.exportBytes()); flow.readImport(inputUri); flow.abandonPrepared()
        assertEquals(ConfigFileMessage.ABANDONED, flow.state.value.message)
        assertNull(importer.pendingImportId()); assertEquals(0, count("habits")); assertEquals(0, count("next_config_import_payloads"))
        assertEquals(0, count("sync_outbox")); assertTrue(flow.beginImport())
        assertTrue(com.dayforge.domain.model.isContractUuid(importId))
    }

    @Test fun changedPreviewNeverAllocatesOrImportsAndSameAccountReauthenticationRejectsOldCallbacks() = runBlocking<Unit> {
        emptyReplica(); val source = source(); val flow = workflow(); preview(flow, source)
        assertEquals(1, db.nextSyncStateDao().update(db.nextSyncStateDao().rows().single().copy(cursor = 1)))
        flow.confirmImport(); assertEquals(ConfigFileMessage.FAILED, flow.state.value.message)
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("habits"))
        flow.dismissMessage(); assertTrue(flow.beginImport())
        val generation = tokens.localIconAccess()!!.session.authentication.generation
        tokens.saveLoginSession("synthetic-again", "synthetic-refresh", "member", id(1), false); register()
        assertNotEquals(generation, tokens.localIconAccess()!!.session.authentication.generation)
        flow.readImport(inputUri); assertNull(flow.state.value.preview); assertEquals(0, count("next_config_imports"))
        flow.refresh(); assertTrue(flow.beginImport()); flow.readImport(inputUri)
        val previewContext = icons.capture()
        val importId = importer().confirmReplacement(importer().previewReplacement(source))
        tokens.saveLoginSession("synthetic-third", "synthetic-refresh", "member", id(1), false); register()
        assertEquals("CONFIG_IMPORT_SESSION_CHANGED", runCatching {
            importer().resume(importId, source, previewContext)
        }.exceptionOrNull()?.message)
        assertEquals("CONFIG_IMPORT_SESSION_CHANGED", runCatching {
            importer().cancelPrepared(importId, source, previewContext)
        }.exceptionOrNull()?.message)
        // Explicit fresh same-owner cold intent remains allowed; only the old callback is denied.
        val fresh = importer().previewRecovery(importId, source)
        importer().cancelPrepared(importId, source, fresh.original.context); assertEquals(0, count("next_config_imports"))
    }

    @Test fun progressCountsOnlyAuditedRealHttpAcceptancesAndNeverUsesEmptyQueueAsProof() = runBlocking<Unit> {
        emptyReplica(); val source = source(); val flow = workflow(); preview(flow, source); flow.confirmImport()
        val progress = flow.state.value.progress!!
        val steps = db.withTransaction { NextConfigImportStore(db).network(progress.importId).plan.steps }
        val (http, server) = channel { input -> if (input.path.endsWith("/identity")) reply(input) else successReply(input) }
        val first = steps.first()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        flow.refresh(); assertEquals(1, flow.state.value.progress!!.accepted)
        assertEquals(ConfigImportPhase.LOCAL_COMMITTED, flow.state.value.progress!!.phase)
        val real = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, first.operationId))
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
            arrayOf("0".repeat(64), first.operationId))
        flow.refresh(); assertEquals(ConfigFileProfile.UNAVAILABLE, flow.state.value.profile); assertNull(flow.state.value.progress)
        // Restore that exact original HTTP receipt; never invent a success to repair the test.
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
            arrayOf(real.resultHash, first.operationId))
        flow.refresh(); assertEquals(1, flow.state.value.progress!!.accepted)
        for (step in steps.drop(1)) assertEquals(NextOperationAcceptance.COMMITTED,
            sender(http).sendAndAcceptOperation(access(), step.operationId))
        flow.refresh(); val accepted = flow.state.value.progress!!
        assertEquals(ConfigImportPhase.OPERATIONS_ACCEPTED, accepted.phase); assertEquals(4, accepted.accepted)
        assertEquals(4, accepted.total); assertEquals(0, count("sync_outbox")); assertTrue(server.requests.isNotEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?",
            arrayOf("0".repeat(64), first.operationId))
        flow.refresh(); assertEquals(ConfigFileProfile.UNAVAILABLE, flow.state.value.profile); assertNull(flow.state.value.progress)
        assertFalse(flow.beginImport())
    }

    @Test fun readOnlyNextReplicaCanExportButCannotImportAndOrphanJournalNeverFallsBackToLegacy() = runBlocking<Unit> {
        register(permissions = setOf("sync.read"), revision = 2)
        val flow = workflow(); flow.refresh(); assertEquals(ConfigFileProfile.NEXT, flow.state.value.profile)
        assertFalse(flow.state.value.canImport); assertFalse(flow.beginImport()); assertTrue(flow.beginExport())
        flow.writeExport(null); assertEquals(0, writes); assertEquals(0, count("next_config_imports"))
        emptyReplica()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys=OFF")
        try { sql.execSQL("INSERT INTO next_config_import_payloads(importId,payloadKind,part,bytes) " +
            "VALUES(?, 'identities', 0, ?)", arrayOf(id(900), byteArrayOf(0))) }
        finally { sql.execSQL("PRAGMA foreign_keys=ON") }
        flow.refresh(); assertEquals(ConfigFileProfile.NEXT, flow.state.value.profile) // hint cannot authorize a mutation
        register(); flow.refresh(); assertEquals(ConfigFileProfile.UNAVAILABLE, flow.state.value.profile)
        assertFalse(flow.beginImport()); assertEquals(0, count("next_config_imports"))
        assertNotEquals(ConfigFileProfile.LEGACY, flow.state.value.profile)
    }

    @Test fun genuineLegacyProfileAndMissingPickerIntentCannotImportNextBytesOrWritePartialFile() = runBlocking<Unit> {
        db.clearAllData(); val flow = workflow(); flow.refresh()
        assertEquals(ConfigFileProfile.LEGACY, flow.state.value.profile); assertFalse(flow.beginExport())
        input.writeBytes(source().exportBytes()); flow.readImport(inputUri); flow.writeExport(outputUri)
        assertEquals(0, reads); assertEquals(0, writes); assertFalse(output.exists())
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("habits")); assertFalse(flow.state.value.busy)
    }

    @Test fun oldPickerMustDrainBeforeNewAccountIntentAndCancellationClosesActualProviderPipe() = runBlocking<Unit> {
        val flow = workflow(); flow.refresh(); assertTrue(flow.beginExport())
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false); register()
        flow.refresh(); assertTrue(flow.state.value.pickerPending); assertFalse(flow.beginExport())
        flow.writeExport(outputUri); assertFalse(output.exists()); assertEquals(0, writes)
        assertFalse(flow.state.value.pickerPending); assertTrue(flow.beginExport()); flow.writeExport(null)
        val pipe = ParcelFileDescriptor.createPipe()
        val opened = CompletableDeferred<android.os.CancellationSignal>()
        try {
            val owner = workflow(ConfigBundleDocuments({ _, signal -> opened.complete(signal)
                AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }, { _, _ -> error("No writer") }, 10_000))
            owner.refresh(); assertTrue(owner.beginImport())
            val task = async(Dispatchers.IO) { owner.readImport(inputUri) }
            val signal = withTimeout(3000) { opened.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled); assertTrue(signal.isCanceled); assertFalse(pipe[0].fileDescriptor.valid())
            assertFalse(owner.state.value.busy); assertNull(owner.state.value.preview); assertNull(owner.state.value.message)
            assertEquals(0, count("next_config_imports")); assertEquals(0, count("next_acceptances"))
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun actualCorruptPreferencesFailClosedForRefreshAndObservationWithoutResetOrBusinessWrites() = runBlocking<Unit> {
        val badScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(directory, "corrupt.preferences_pb").also { it.writeBytes(byteArrayOf(0x80.toByte())) }
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = badScope,
                produceFile = { file })
            val badTokens = com.dayforge.data.local.TokenManager(store)
            val imports = NextConfigImportRepository(db, badTokens, sessions, icons,
                NextObjectCreator(db, badTokens, sessions, icons), app)
            val exports = NextConfigExportRepository(db, badTokens, sessions, preferences, icons, themes)
            val flow = SettingsConfigV2Workflow(badTokens, exports, imports, documents())
            flow.refresh(); assertEquals(ConfigFileProfile.UNAVAILABLE, flow.state.value.profile)
            val observer = async(Dispatchers.IO) { flow.observe() }
            withTimeout(5000) { observer.await() }
            assertEquals(ConfigFileProfile.UNAVAILABLE, flow.state.value.profile)
            assertFalse(flow.beginImport()); assertFalse(flow.beginExport())
            assertEquals(0, reads); assertEquals(0, writes); assertEquals(0, count("next_config_imports"))
            assertEquals(2, count("habits")); assertArrayEquals(byteArrayOf(0x80.toByte()), file.readBytes())
        } finally { badScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun legacyServiceAndLateSettingsCallbacksCannotExportNextObjectsOrClearTheirReplica() = runBlocking<Unit> {
        val flow = workflow(); mount(flow)
        val before = db.habitDao().getAllHabitsOnce(); val metrics = db.metricDao().getAllMetricsOnce()
        val exporter = ConfigExportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao())
        assertTrue(exporter.exportConfigToJson().isFailure)
        val importer = ConfigImportService(db.habitDao(), db.metricDao(), db.habitMetricLinkDao(), db)
        val old = kotlinx.serialization.json.Json.encodeToString(com.dayforge.data.export.dto.ConfigExportDto())
        assertTrue(importer.importConfig(old).isFailure)
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(metrics, db.metricDao().getAllMetricsOnce())
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_config_imports"))
        val vm = requireNotNull(model)
        assertNull(vm.exportConfigToJson())
        assertNull(vm.exportResult.value)
        flow.dismissMessage(); vm.prepareImport(inputUri)
        compose.waitUntil(5000) { flow.state.value.message == ConfigFileMessage.FAILED }
        flow.dismissMessage(); vm.confirmImport()
        compose.waitUntil(5000) { flow.state.value.message == ConfigFileMessage.FAILED }
        compose.waitForIdle()
        assertNull(vm.importConfirmData.value); assertNull(vm.importResult.value)
        assertEquals(0, reads); assertEquals(0, writes)
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(metrics, db.metricDao().getAllMetricsOnce())
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_config_imports"))
    }
}
