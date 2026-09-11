package com.dayforge.ui.screens.metricdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.util.isOnline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Represents a habit-metric link with the habit name resolved.
 * Used for displaying linked habits in the UI.
 */
data class HabitMetricLinkWithHabit(
    val link: HabitMetricLinkEntity,
    val habitName: String
)

/**
 * UI state for the MetricDetailScreen.
 *
 * @param metric The loaded metric entity, null if not loaded yet
 * @param latestValue The most recently recorded value for this metric
 * @param logs List of all recorded logs for this metric
 * @param links List of linked habits with their names
 * @param isLoading Whether the metric is currently being loaded
 * @param showValueInput Whether the value input dialog should be shown
 * @param showDeleteConfirm Whether the delete confirmation dialog should be shown
 * @param showUnlinkConfirm Link ID to show unlink confirmation for, null if not shown
 * @param isDeleted Whether the metric has been deleted
 * @param errorMessage Optional error message to display
 */
data class MetricDetailUiState(
    val metric: MetricEntity? = null,
    val latestValue: Double? = null,
    val logs: List<MetricLogEntity> = emptyList(),
    val links: List<HabitMetricLinkWithHabit> = emptyList(),
    val isLoading: Boolean = true,
    val showValueInput: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val showUnlinkConfirm: Long? = null,
    val showLinkHabit: Boolean = false,
    val availableHabits: List<HabitForLinking> = emptyList(),
    val selectedHabitIds: Set<Long> = emptySet(),
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Represents a habit available for linking.
 */
data class HabitForLinking(
    val id: Long,
    val name: String,
    val isAlreadyLinked: Boolean
)

/**
 * ViewModel for the MetricDetailScreen.
 *
 * Handles loading metric data, recording new values, viewing history,
 * managing habit links, and deleting metrics.
 * Uses SavedStateHandle to extract metricId from navigation arguments.
 *
 * Per D-14: Save to MetricLogEntity via onConfirm callback
 * Per D-15: View history, manage links
 * Per D-16: Delete with confirmation, cascade to logs and links
 */
@HiltViewModel
class MetricDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metricDao: MetricDao,
    private val metricLogDao: MetricLogDao,
    private val habitMetricLinkDao: HabitMetricLinkDao,
    private val habitDao: HabitDao,
    private val structuralEditGuard: StructuralEditGuard,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val metricId: Long = savedStateHandle["metricId"] ?: 0L

    private val _uiState = MutableStateFlow(MetricDetailUiState())
    val uiState: StateFlow<MetricDetailUiState> = _uiState.asStateFlow()

    init {
        observeMetric()
        observeLogs()
        observeLinks()
    }

    /**
     * Observe the metric from the database.
     * Updates the UI state whenever the metric changes.
     */
    private fun observeMetric() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            metricDao.observeMetricById(metricId).collect { metric ->
                if (metric == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.metric_error_not_found)
                    )
                    return@collect
                }

                _uiState.value = _uiState.value.copy(
                    metric = metric,
                    isLoading = false
                )

                // Also update latest value when metric loads
                viewModelScope.launch {
                    val latestLog = metricLogDao.getLatestLog(metricId)
                    _uiState.value = _uiState.value.copy(
                        latestValue = latestLog?.value
                    )
                }
            }
        }
    }

    /**
     * Observe logs for this metric from the database.
     * Updates the UI state whenever logs change.
     */
    private fun observeLogs() {
        viewModelScope.launch {
            metricLogDao.getLogsByMetric(metricId).collect { logs ->
                _uiState.value = _uiState.value.copy(logs = logs)
            }
        }
    }

    /**
     * Observe habit links for this metric from the database.
     * Resolves habit names for display in the UI.
     */
    private fun observeLinks() {
        viewModelScope.launch {
            habitMetricLinkDao.getLinksByMetric(metricId).collect { links ->
                val linksWithHabits = links.map { link ->
                    val habit = habitDao.getHabitById(link.habitId)
                    HabitMetricLinkWithHabit(
                        link = link,
                        habitName = habit?.name ?: context.getString(R.string.metric_error_unknown_habit)
                    )
                }
                _uiState.value = _uiState.value.copy(links = linksWithHabits)
            }
        }
    }

    /**
     * Record a new value for this metric.
     * Creates a MetricLogEntity and inserts it into the database.
     *
     * @param value The numeric value to record
     * @param note Optional note for this record
     */
    fun recordValue(value: Double, note: String) {
        val metric = _uiState.value.metric ?: return

        viewModelScope.launch {
            try {
                val log = com.dayforge.data.local.entity.MetricLogEntity(
                    metricId = metric.id,
                    date = System.currentTimeMillis(),
                    value = value,
                    unit = metric.unit,
                    note = note
                )
                metricLogDao.insert(log)

                // Update UI state
                _uiState.value = _uiState.value.copy(
                    showValueInput = false,
                    latestValue = value
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = context.getString(R.string.metric_error_record_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * Update the aggregation type for this metric.
     * Persists the change to the database.
     *
     * @param aggregationType The new aggregation type value ("average", "sum", or "by_time")
     */
    fun updateAggregationType(aggregationType: String) {
        val metric = _uiState.value.metric ?: return

        viewModelScope.launch {
            try {
                structuralEditGuard.requireAllowed()
                val updatedMetric = metric.copy(
                    aggregationType = aggregationType,
                    updatedAt = System.currentTimeMillis()
                )
                metricDao.update(updatedMetric)
                // The UI will update automatically via observeMetric()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = context.getString(R.string.metric_error_aggregation_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * Toggle the visibility of the value input dialog.
     */
    fun toggleValueInput() {
        _uiState.value = _uiState.value.copy(
            showValueInput = !_uiState.value.showValueInput
        )
    }

    /**
     * Toggle the visibility of the delete confirmation dialog.
     */
    fun toggleDeleteConfirm() {
        _uiState.value = _uiState.value.copy(
            showDeleteConfirm = !_uiState.value.showDeleteConfirm
        )
    }

    /**
     * Delete this metric and all associated data.
     * Room cascades and the durable v2 outbox make this operation offline-safe.
     */
    fun deleteMetric() {
        val metric = _uiState.value.metric ?: return

        viewModelScope.launch {
            try {
                structuralEditGuard.requireAllowed()
                // Offline-safe: Room cascades and the v2 outbox are committed together.
                metricDao.delete(metric)
                _uiState.value = _uiState.value.copy(
                    isDeleted = true,
                    showDeleteConfirm = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = context.getString(R.string.metric_error_delete_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * Show the unlink confirmation dialog for a specific link.
     *
     * @param linkId The ID of the link to potentially unlink
     */
    fun showUnlinkConfirm(linkId: Long) {
        _uiState.value = _uiState.value.copy(showUnlinkConfirm = linkId)
    }

    /**
     * Dismiss the unlink confirmation dialog.
     */
    fun dismissUnlinkConfirm() {
        _uiState.value = _uiState.value.copy(showUnlinkConfirm = null)
    }

    /**
     * Unlink a habit from this metric.
     * The durable v2 outbox synchronizes the local unlink later.
     */
    fun unlinkHabit() {
        val linkId = _uiState.value.showUnlinkConfirm ?: return

        viewModelScope.launch {
            try {
                structuralEditGuard.requireAllowed()
                val link = habitMetricLinkDao.getById(linkId)
                if (link != null) {
                    habitMetricLinkDao.delete(link)
                }
                _uiState.value = _uiState.value.copy(showUnlinkConfirm = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = context.getString(R.string.metric_error_unlink_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Show the link habit dialog.
     * Loads all habits and marks which are already linked.
     */
    fun showLinkHabitDialog() {
        viewModelScope.launch {
            val allHabits = habitDao.getAllHabitsOnce()
            val linkedHabitIds = _uiState.value.links.map { it.link.habitId }.toSet()

            val habitsForLinking = allHabits.map { habit ->
                HabitForLinking(
                    id = habit.id,
                    name = habit.name,
                    isAlreadyLinked = habit.id in linkedHabitIds
                )
            }

            _uiState.value = _uiState.value.copy(
                showLinkHabit = true,
                availableHabits = habitsForLinking,
                selectedHabitIds = emptySet()
            )
        }
    }

    /**
     * Dismiss the link habit dialog.
     */
    fun dismissLinkHabitDialog() {
        _uiState.value = _uiState.value.copy(
            showLinkHabit = false,
            availableHabits = emptyList(),
            selectedHabitIds = emptySet()
        )
    }

    /**
     * Toggle habit selection in the link dialog.
     */
    fun toggleHabitSelection(habitId: Long) {
        val currentSelected = _uiState.value.selectedHabitIds
        val newSelected = if (habitId in currentSelected) {
            currentSelected - habitId
        } else {
            currentSelected + habitId
        }
        _uiState.value = _uiState.value.copy(selectedHabitIds = newSelected)
    }

    /**
     * Create links for selected habits.
     */
    fun linkSelectedHabits() {
        val selectedIds = _uiState.value.selectedHabitIds
        val metric = _uiState.value.metric
        if (selectedIds.isEmpty() || metric == null) {
            dismissLinkHabitDialog()
            return
        }

        viewModelScope.launch {
            try {
                structuralEditGuard.requireAllowed()
                selectedIds.forEach { habitId ->
                    val habit = habitDao.getHabitById(habitId) ?: return@forEach
                    val link = HabitMetricLinkEntity(
                        habitId = habitId,
                        habitUuid = habit.uuid,
                        metricId = metricId,
                        metricUuid = metric.uuid,
                        showInHabitDetail = true,
                        promptOnComplete = true
                    )
                    habitMetricLinkDao.insert(link)
                }
                _uiState.value = _uiState.value.copy(
                    showLinkHabit = false,
                    availableHabits = emptyList(),
                    selectedHabitIds = emptySet()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = context.getString(R.string.metric_error_link_failed, e.message ?: "")
                )
            }
        }
    }
}
