package com.dayforge.ui.screens.settings

import android.content.Context
import android.net.Uri
import com.dayforge.R
import com.dayforge.data.export.dto.ConfigExportDto
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.domain.service.ConfigExportService
import com.dayforge.domain.service.ConfigImportService
import com.dayforge.reminder.HabitReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class SettingsImportConfirmData(
    val uri: Uri,
    val importHabitCount: Int,
    val importMetricCount: Int,
    val importLinkCount: Int,
    val deleteHabitCount: Int,
    val deleteMetricCount: Int,
    val deleteCompletionCount: Int,
    val deleteTimeLogCount: Int,
    val deleteMetricLogCount: Int
)

/** Owns the settings configuration import/export workflow and its transient UI state. */
class SettingsConfigWorkflow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val habitDao: HabitDao,
    private val metricDao: MetricDao,
    private val timeLogDao: TimeLogDao,
    private val completionDao: CompletionDao,
    private val metricLogDao: MetricLogDao,
    private val configExportService: ConfigExportService,
    private val configImportService: ConfigImportService
) {
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

    suspend fun exportConfigToJson(): String? {
        _exportProgress.value = true
        val result = configExportService.exportConfigToJson()
        _exportProgress.value = false
        if (result.isFailure) {
            @Suppress("UNCHECKED_CAST")
            _exportResult.value = result as Result<String>
        }
        return result.getOrNull()
    }

    fun dismissExportResult() {
        _exportResult.value = null
    }

    suspend fun prepareImport(uri: Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                _importResult.value = Result.failure(
                    Exception(context.getString(R.string.error_cannot_open_file))
                )
                return
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true }
            val configDto = json.decodeFromString<ConfigExportDto>(jsonString)

            val duplicateNames = configDto.habits
                .map { it.name }
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
                .keys
            if (duplicateNames.isNotEmpty()) {
                _importResult.value = Result.failure(
                    Exception(
                        context.getString(
                            R.string.error_duplicate_habit_names,
                            duplicateNames.joinToString(", ")
                        )
                    )
                )
                return
            }

            _importConfirmData.value = SettingsImportConfirmData(
                uri = uri,
                importHabitCount = configDto.habits.size,
                importMetricCount = configDto.metrics.size,
                importLinkCount = configDto.links.size,
                deleteHabitCount = habitDao.getAllHabitsOnce().size,
                deleteMetricCount = metricDao.getAllMetricsOnce().size,
                deleteCompletionCount = completionDao.countAll(),
                deleteTimeLogCount = timeLogDao.countAll(),
                deleteMetricLogCount = metricLogDao.countAll()
            )
        } catch (error: Exception) {
            _importResult.value = Result.failure(
                Exception(context.getString(R.string.error_read_file_failed, error.message))
            )
        }
    }

    suspend fun confirmImport() {
        val confirmData = _importConfirmData.value ?: return
        _importProgress.value = true

        try {
            val inputStream = context.contentResolver.openInputStream(confirmData.uri)
            if (inputStream == null) {
                _importProgress.value = false
                _importResult.value = Result.failure(
                    Exception(context.getString(R.string.error_cannot_open_file))
                )
                _importConfirmData.value = null
                return
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val result = configImportService.importConfig(jsonString)
            if (result.isSuccess) {
                try {
                    HabitReminderScheduler.rescheduleAllReminders(context)
                } catch (_: Exception) {
                    // Reminder scheduling must not turn a successful import into a failure.
                }
            }

            _importProgress.value = false
            _importResult.value = result
            _importConfirmData.value = null
        } catch (error: Exception) {
            _importProgress.value = false
            _importResult.value = Result.failure(
                Exception(context.getString(R.string.error_import_failed, error.message))
            )
            _importConfirmData.value = null
        }
    }

    fun cancelImport() {
        _importConfirmData.value = null
    }

    fun dismissImportResult() {
        _importResult.value = null
    }
}
