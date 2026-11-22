package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.dayforge.R
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.local.DatabaseEpochPublication
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.repository.ConfigImportPreviewReader
import com.dayforge.domain.service.ConfigExportService
import com.dayforge.domain.service.ConfigImportService
import com.dayforge.reminder.HabitReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsImportConfirmData(val uri: Uri, val importHabitCount: Int, val importMetricCount: Int,
    val importLinkCount: Int, val deleteHabitCount: Int, val deleteMetricCount: Int,
    val deleteCompletionCount: Int, val deleteTimeLogCount: Int, val deleteMetricLogCount: Int)
internal class SettingsLegacyPicker internal constructor(internal val frame: ConfigV2UiState, internal val export: Boolean)
internal data class SettingsLegacyUiState(val source: DatabaseEpochPublication? = null, val request: Any = Any(),
    val exportProgress: Boolean = false, val exportResult: Result<String>? = null,
    val importProgress: Boolean = false, val confirm: SettingsImportConfirmData? = null,
    val importResult: Result<Unit>? = null)

/** Original legacy behavior, with one graph for each complete operation and original picker/
 * confirmation identity. The importer retains all original validation and transaction semantics.
 */
class SettingsConfigWorkflow private constructor(private val context: Context,
    private val fixedReader: ConfigImportPreviewReader?, private val fixedExport: ConfigExportService?,
    private val fixedImport: ConfigImportService?, internal val v2: SettingsConfigV2Workflow?,
    private val host: DatabaseRuntimeHost?) {
    constructor(context: Context, habitDao: HabitDao, metricDao: MetricDao, timeLogDao: TimeLogDao,
        completionDao: CompletionDao, metricLogDao: MetricLogDao, configExportService: ConfigExportService,
        configImportService: ConfigImportService, v2: SettingsConfigV2Workflow? = null) :
        this(context, ConfigImportPreviewReader(habitDao, metricDao, timeLogDao, completionDao, metricLogDao),
            configExportService, configImportService, v2, null)
    @Inject internal constructor(@ApplicationContext context: Context, host: DatabaseRuntimeHost,
        v2: SettingsConfigV2Workflow) : this(context, null, null, null, v2, host)
    private val reader get() = fixedReader ?: requireNotNull(host).boundGraph().configImportPreviewReader
    private val exporter get() = fixedExport ?: requireNotNull(host).boundGraph().configExportService
    private val importer get() = fixedImport ?: requireNotNull(host).boundGraph().configImportService
    private val json = Json { ignoreUnknownKeys = true }
    private val monitor = Any()
    private val mutable = MutableStateFlow(SettingsLegacyUiState())
    internal val frame = mutable.asStateFlow()
    private var pendingPicker: SettingsLegacyPicker? = null
    private var confirmProfile: ConfigV2UiState? = null
    private val _exportProgress = MutableStateFlow(false)
    val exportProgress: StateFlow<Boolean> = _exportProgress.asStateFlow()
    private val _exportResult = MutableStateFlow<Result<String>?>(null)
    val exportResult: StateFlow<Result<String>?> = _exportResult.asStateFlow()
    private val _importProgress = MutableStateFlow(false)
    val importProgress: StateFlow<Boolean> = _importProgress.asStateFlow()
    private val _importConfirmData = MutableStateFlow<SettingsImportConfirmData?>(null)
    val importConfirmData: StateFlow<SettingsImportConfirmData?> = _importConfirmData.asStateFlow()
    private val _importResult = MutableStateFlow<Result<Unit>?>(null)
    val importResult: StateFlow<Result<Unit>?> = _importResult.asStateFlow()
    private fun assign(value: SettingsLegacyUiState) {
        mutable.value = value
        _exportProgress.value = value.exportProgress; _exportResult.value = value.exportResult
        _importProgress.value = value.importProgress; _importConfirmData.value = value.confirm
        _importResult.value = value.importResult
    }
    private val listener = object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val before = mutable.value
            if (blocked || before.source?.isCurrentInMemory() != true) synchronized(monitor) {
                if (mutable.value.source === before.source) { confirmProfile = null; assign(SettingsLegacyUiState()) }
            }
        }
    }
    @Suppress("unused") private val registration = host?.observeMemory(listener)
    private fun publish(source: DatabaseEpochPublication?, update: () -> Unit): Boolean {
        if (host == null) { synchronized(monitor) { update() }; return true }
        return source?.withSourceFence { synchronized(monitor) { source.publish(update) } } == true
    }
    private fun edit(expected: SettingsLegacyUiState, update: (SettingsLegacyUiState) -> SettingsLegacyUiState): Boolean {
        var changed = false
        publish(expected.source) { if (mutable.value.request === expected.request && mutable.value.source === expected.source) {
            assign(update(mutable.value)); changed = true
        } }
        return changed
    }
    private fun fixedFrame(): SettingsLegacyUiState { check(host == null) { "CONFIG_ORIGINAL_INPUT_REQUIRED" }; return mutable.value }
    private suspend fun <T> inSource(source: DatabaseEpochPublication?, block: suspend () -> T): T =
        if (host == null) block() else host.withSourceGraph(source) { block() }
    private suspend fun requireLegacy(profile: ConfigV2UiState?) {
        if (profile != null) check(requireNotNull(v2).allowLegacyFrom(profile)) { "CONFIG_REPLICA_CHANGED" }
        else check(host == null) { "CONFIG_ORIGINAL_INPUT_REQUIRED" }
    }
    internal suspend fun beginPickerFrom(profile: ConfigV2UiState, export: Boolean): SettingsLegacyPicker? {
        requireLegacy(profile)
        currentCoroutineContext().ensureActive()
        val ticket = SettingsLegacyPicker(profile, export)
        var accepted = false
        publish(profile.source?.business) {
            if (pendingPicker == null && !mutable.value.exportProgress && !mutable.value.importProgress && mutable.value.confirm == null) {
                pendingPicker = ticket; accepted = true
            }
        }
        return ticket.takeIf { accepted }
    }
    private fun consume(ticket: SettingsLegacyPicker?): Boolean = synchronized(monitor) {
        if (ticket == null || pendingPicker !== ticket) false else { pendingPicker = null; true }
    }
    internal fun pickerFailedFrom(ticket: SettingsLegacyPicker?) { consume(ticket) }
    internal suspend fun readImportFrom(ticket: SettingsLegacyPicker?, uri: Uri?) {
        if (!consume(ticket) || uri == null || ticket?.export != false) return
        prepare(ticket.frame.source?.business, ticket.frame, uri)
    }
    internal suspend fun writeExportFrom(ticket: SettingsLegacyPicker?, uri: Uri?) {
        if (!consume(ticket) || uri == null || ticket?.export != true) return
        export(ticket.frame.source?.business, ticket.frame) { content ->
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray()) }
            }
        }
    }
    suspend fun exportConfigToJson(): String? { fixedFrame(); return export(null, null) {} }
    private suspend fun export(source: DatabaseEpochPublication?, profile: ConfigV2UiState?,
        deliver: suspend (String) -> Unit): String? {
        var running: SettingsLegacyUiState? = null
        publish(source) { if (!mutable.value.exportProgress && !mutable.value.importProgress) {
            running = SettingsLegacyUiState(source = source, exportProgress = true); assign(requireNotNull(running))
        } }
        val started = running ?: return null
        return try { inSource(source) {
            requireLegacy(profile)
            val result = withContext(Dispatchers.IO) { exporter.exportConfigToJson() }
            requireLegacy(profile)
            if (result.isFailure) edit(started) { it.copy(exportResult = result) }
            val content = result.getOrNull() ?: return@inSource null
            // Original source check at the irrevocable byte handoff; file I/O itself holds no
            // account or producer lock. A later logout cannot recall already handed-off bytes.
            check(edit(started) { it }) { "CONFIG_SOURCE_EXPIRED" }
            deliver(content)
            content
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { edit(started) { it.copy(exportResult = Result.failure(failure)) }; null }
        finally { edit(started) { it.copy(exportProgress = false) } }
    }
    suspend fun prepareImport(uri: Uri) { fixedFrame(); prepare(null, null, uri) }
    private suspend fun prepare(source: DatabaseEpochPublication?, profile: ConfigV2UiState?, uri: Uri) {
        var running: SettingsLegacyUiState? = null
        publish(source) { if (!mutable.value.importProgress && !mutable.value.exportProgress) {
            running = SettingsLegacyUiState(source = source, importProgress = true); assign(requireNotNull(running))
        } }
        val started = running ?: return
        try { inSource(source) {
            requireLegacy(profile)
            val dto = withContext(Dispatchers.IO) { json.decodeFromString<ConfigExportDto>(readDocument(uri)) }
            val duplicates = dto.habits.map { it.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            if (duplicates.isNotEmpty()) {
                edit(started) { it.copy(importResult = Result.failure(Exception(context.getString(
                    R.string.error_duplicate_habit_names, duplicates.joinToString(", "))))) }
                return@inSource
            }
            val counts = reader.counts()
            requireLegacy(profile)
            val confirm = SettingsImportConfirmData(uri, dto.habits.size, dto.metrics.size, dto.links.size,
                counts.habits, counts.metrics, counts.completions, counts.times, counts.metricLogs)
            edit(started) { confirmProfile = profile; it.copy(confirm = confirm) }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { edit(started) { it.copy(importResult = Result.failure(Exception(
            context.getString(R.string.error_read_file_failed, error.message)))) } }
        finally { edit(started) { it.copy(importProgress = false) } }
    }
    suspend fun confirmImport() = confirmImportFrom(fixedFrame())
    internal suspend fun confirmImportFrom(expected: SettingsLegacyUiState) {
        val confirm = expected.confirm ?: return
        var profile: ConfigV2UiState? = null
        var admitted = false
        edit(expected) { if (it.importProgress || it.exportProgress || it.confirm !== confirm) it else {
            profile = confirmProfile; admitted = true; it.copy(importProgress = true)
        } }
        if (!admitted) return
        try { inSource(expected.source) {
            requireLegacy(profile)
            val result = withContext(Dispatchers.IO) { importer.importConfig(readDocument(confirm.uri)) }
            if (result.isSuccess) {
                try { HabitReminderScheduler.rescheduleAllReminders(context) }
                catch (error: Exception) { if (error is CancellationException) throw error }
            }
            edit(expected) { confirmProfile = null; it.copy(importResult = result, confirm = null) }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { edit(expected) { confirmProfile = null; it.copy(confirm = null,
            importResult = Result.failure(Exception(context.getString(R.string.error_import_failed, error.message)))) } }
        finally { edit(expected) { it.copy(importProgress = false) } }
    }
    fun cancelImport() = cancelImportFrom(fixedFrame())
    internal fun cancelImportFrom(expected: SettingsLegacyUiState) {
        edit(expected) { if (it.importProgress) it else { confirmProfile = null; it.copy(confirm = null, request = Any()) } }
    }
    fun dismissImportResult() = dismissImportResultFrom(fixedFrame())
    internal fun dismissImportResultFrom(expected: SettingsLegacyUiState) {
        edit(expected) { it.copy(importResult = null, request = Any()) }
    }
    fun dismissExportResult() = dismissExportResultFrom(fixedFrame())
    internal fun dismissExportResultFrom(expected: SettingsLegacyUiState) {
        edit(expected) { it.copy(exportResult = null, request = Any()) }
    }
    private fun readDocument(uri: Uri): String =
        (context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException(context.getString(R.string.error_cannot_open_file))).bufferedReader().use { it.readText() }
}
