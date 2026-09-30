package com.dayforge.ui.screens.settings

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.appearance.BuiltInThemes
import com.dayforge.data.appearance.DeviceThemeLoadState
import com.dayforge.data.appearance.DeviceThemeRepository
import com.dayforge.data.appearance.ThemeFileRepository
import com.dayforge.data.appearance.ThemeFileIo
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.data.appearance.ThemeInputException
import com.dayforge.data.local.PreferencesManager
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.widget.WidgetRefreshScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsThemeDocumentsTest {
    private val app = InstrumentationRegistry.getInstrumentation()
    private val context = app.targetContext
    private val directory = Files.createTempDirectory(context.filesDir.toPath(), "theme-documents-").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) { File(directory, "prefs.preferences_pb") }
    private var failThemeWrite = false
    private val files = ThemeFileRepository(directory, object : ThemeFileIo() {
        override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
            if (failThemeWrite) throw IOException("synthetic theme write failure")
            return super.write(fd, bytes, offset, length)
        }
    })
    private val repository = DeviceThemeRepository(preferences, files, BuiltInThemes(context.assets))
    private val controller = DeviceThemeController(repository, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val workflow = SettingsAppearanceWorkflow(context, PreferencesManager(preferences), controller)
    private fun sample() = app.context.assets.open("next/theme.json").use { it.readBytes() }
    private fun input() = File(directory, "source.json").also { it.writeBytes(sample()) }
    private suspend fun import(): ThemeChoiceSummary {
        workflow.importTheme(Uri.fromFile(input()))
        workflow.confirmThemeImport(checkNotNull(workflow.themePreview.value))
        return workflow.choices.value.single { it.isCustom }
    }
    @Before fun start() = runBlocking<Unit> {
        withTimeout(5000) { controller.current() }
        workflow.refreshLibrary()
        mockkObject(WidgetRefreshScheduler)
        every { WidgetRefreshScheduler.request(context) } returns mockk()
    }
    @After fun finish() = runBlocking<Unit> {
        controller.close()
        dataScope.coroutineContext.job.cancelAndJoin()
        unmockkObject(WidgetRefreshScheduler)
        assertTrue(directory.deleteRecursively())
    }

    @Test fun previewIsReadOnlyAndConfirmationNeverReopensChangedSource() = runBlocking<Unit> {
        val source = input()
        val before = preferences.data.first()
        workflow.importTheme(Uri.fromFile(source))
        val preview = checkNotNull(workflow.themePreview.value)
        assertNull(workflow.themeImportResult.value)
        assertEquals(before, preferences.data.first())
        assertEquals(7, repository.catalog()!!.slots.size)
        source.writeText("changed after preview")
        workflow.confirmThemeImport(preview)
        assertNull(workflow.themePreview.value)
        assertTrue(workflow.themeImportResult.value!!.isSuccess)
        val theme = workflow.choices.value.single { it.isCustom }
        assertArrayEquals(sample(), controller.export(theme.ref).exportBytes())
        assertEquals(8, repository.catalog()!!.slots.size)
        assertEquals("df000000-0000-4000-8000-000000000001", repository.savedSelection()!!.selection.light.themeId)
    }

    @Test fun cancelPreviewAndStaleConfirmationCannotInstallAnything() = runBlocking<Unit> {
        val before = preferences.data.first()
        workflow.importTheme(Uri.fromFile(input()))
        val preview = checkNotNull(workflow.themePreview.value)
        workflow.cancelThemeImport()
        workflow.confirmThemeImport(preview)
        assertNull(workflow.themePreview.value)
        assertEquals(before, preferences.data.first())
        assertEquals(7, workflow.choices.value.size)
    }

    @Test fun fullExportReportsSuccessOnlyAfterDestinationWriteAndCanBeReimported() = runBlocking<Unit> {
        val theme = import()
        assertNotNull(workflow.prepareThemeExport(theme))
        assertNull(workflow.themeExportResult.value)
        val destination = File(directory, "export.json")
        workflow.writeThemeExport(Uri.fromFile(destination))
        assertTrue(workflow.themeExportResult.value!!.isSuccess)
        assertArrayEquals(sample(), destination.readBytes())
        workflow.importTheme(Uri.fromFile(destination))
        workflow.confirmThemeImport(checkNotNull(workflow.themePreview.value))
        assertEquals(8, repository.catalog()!!.slots.size)
    }

    @Test fun outputFailureIsVisibleAndFrozenExportCanRetryWithoutChangingSelection() = runBlocking<Unit> {
        val theme = import()
        val before = repository.savedSelection()
        workflow.prepareThemeExport(theme)
        workflow.writeThemeExport(Uri.fromFile(directory))
        assertTrue(workflow.themeExportResult.value!!.isFailure)
        assertFalse(workflow.themeExportProgress.value)
        assertEquals(before, repository.savedSelection())
        workflow.dismissThemeExportResult()
        val destination = File(directory, "retry.json")
        workflow.writeThemeExport(Uri.fromFile(destination))
        assertTrue(workflow.themeExportResult.value!!.isSuccess)
        assertArrayEquals(sample(), destination.readBytes())
    }

    @Test fun cancelledDestinationDiscardsFrozenExportAndLateCallbackCannotWriteIt() = runBlocking<Unit> {
        workflow.prepareThemeExport(import())
        workflow.writeThemeExport(null)
        assertNull(workflow.themeExportResult.value)
        val destination = File(directory, "late.json")
        workflow.writeThemeExport(Uri.fromFile(destination))
        assertTrue(workflow.themeExportResult.value!!.isFailure)
        assertFalse(destination.exists())
    }

    @Test fun anotherPickerRequestCannotReplaceTheExportAlreadyAwaitingADestination() = runBlocking<Unit> {
        val custom = import()
        assertNotNull(workflow.prepareThemeExport(custom))
        assertNull(workflow.prepareThemeExport(workflow.choices.value.single { it.builtInSlug == "ocean" }))
        assertTrue(workflow.themeExportResult.value!!.isFailure)
        val destination = File(directory, "first-request.json")
        workflow.writeThemeExport(Uri.fromFile(destination))
        assertArrayEquals(sample(), destination.readBytes())
        assertTrue(workflow.themeExportResult.value!!.isSuccess)
    }

    @Test fun failedPickerLaunchReleasesPendingFlagAndKeepsFrozenBytesForRetry() = runBlocking<Unit> {
        val custom = import()
        assertNotNull(workflow.prepareThemeExport(custom))
        val failure = android.content.ActivityNotFoundException("synthetic missing picker")
        workflow.themeExportPickerFailed(failure)
        assertSame(failure, workflow.themeExportResult.value!!.exceptionOrNull())
        assertNotNull(workflow.prepareThemeExport(custom))
        assertNull(workflow.themeExportResult.value)
        val destination = File(directory, "picker-retry.json")
        workflow.writeThemeExport(Uri.fromFile(destination))
        assertArrayEquals(sample(), destination.readBytes())
        assertTrue(workflow.themeExportResult.value!!.isSuccess)
    }

    @Test fun failedImportPickerIsVisibleWithoutInstallingAndNextImportCanRetry() = runBlocking<Unit> {
        val before = preferences.data.first()
        val failure = android.content.ActivityNotFoundException("synthetic missing picker")
        workflow.themeImportPickerFailed(failure)
        assertSame(failure, workflow.themeImportResult.value!!.exceptionOrNull())
        assertNull(workflow.themePreview.value)
        assertFalse(workflow.themeImportProgress.value)
        assertEquals(before, preferences.data.first())
        workflow.dismissThemeImportResult()
        workflow.importTheme(Uri.fromFile(input()))
        assertNull(workflow.themeImportResult.value)
        assertNotNull(workflow.themePreview.value)
        assertEquals(7, repository.catalog()!!.slots.size)
    }

    @Test fun failedConfirmedImportShowsItsOwnedJournalAndRetainsTheSamePreviewForRetry() = runBlocking<Unit> {
        workflow.importTheme(Uri.fromFile(input()))
        val preview = checkNotNull(workflow.themePreview.value)
        val selection = repository.savedSelection()
        failThemeWrite = true
        workflow.confirmThemeImport(preview)
        assertTrue(workflow.themeImportResult.value!!.isFailure)
        assertSame(preview, workflow.themePreview.value)
        assertFalse(workflow.themeImportProgress.value)
        assertEquals(selection, repository.savedSelection())
        val pending = workflow.choices.value.single { it.isCustom }
        assertEquals(ThemeInstallPhase.INSTALLING, pending.item.slot.phase)
        assertTrue(pending.item.content is ThemeCatalogContent.Pending)
        assertFalse(pending.available)
        failThemeWrite = false
        workflow.dismissThemeImportResult()
        workflow.confirmThemeImport(preview)
        assertTrue(workflow.themeImportResult.value!!.isSuccess)
        val installed = workflow.choices.value.single { it.isCustom }
        assertEquals(pending.ref, installed.ref)
        assertTrue(installed.available)
        assertArrayEquals(sample(), controller.export(installed.ref).exportBytes())
        assertEquals(selection, repository.savedSelection())
    }

    @Test fun legacyAndOversizedFilesFailWithoutInstallingOrChangingSavedPreferences() = runBlocking<Unit> {
        val before = preferences.data.first()
        val source = File(directory, "invalid.json")
        source.writeText("{\"id\":\"old\",\"seedColor\":\"#123456\"}")
        workflow.importTheme(Uri.fromFile(source))
        assertEquals("THEME_VERSION", (workflow.themeImportResult.value!!.exceptionOrNull() as ThemeInputException).code)
        assertNull(workflow.themePreview.value)
        source.writeBytes(ByteArray(1_048_577) { 32 })
        workflow.importTheme(Uri.fromFile(source))
        assertEquals("THEME_LIMIT", (workflow.themeImportResult.value!!.exceptionOrNull() as ThemeInputException).code)
        assertEquals(before, preferences.data.first())
        assertEquals(7, repository.catalog()!!.slots.size)
    }

    @Test fun selectedCustomThemeIsProtectedUntilUserChoosesAnotherVersion() = runBlocking<Unit> {
        val theme = import()
        workflow.changeLightColorTheme(theme.id)
        workflow.deleteCustomTheme(theme)
        assertTrue(workflow.themeDeleteResult.value!!.isFailure)
        assertEquals(theme.ref, repository.savedSelection()!!.selection.light)
        workflow.changeLightColorTheme(workflow.choices.value.single { it.builtInSlug == "ocean" }.id)
        workflow.deleteCustomTheme(theme)
        assertTrue(workflow.themeDeleteResult.value!!.isSuccess)
        assertEquals(7, repository.catalog()!!.slots.size)
    }

    @Test fun cachedWidgetReadsDoNotDecodeFilesUntilExplicitReloadOrSelectionChange() = runBlocking<Unit> {
        val loaded = controller.current()
        val ref = loaded.saved.selection.light
        val file = File(directory, "theme-definitions-v1/${ref.themeId}-${ref.revision}.json")
        assertTrue(file.delete())
        repeat(20) { assertSame(loaded, controller.current()) }
        controller.retry()
        val failure = withTimeout(5000) { controller.state.first { it is DeviceThemeLoadState.Failed } }
        assertTrue(failure is DeviceThemeLoadState.Failed)
        assertEquals(loaded.saved, repository.savedSelection())
    }
}
