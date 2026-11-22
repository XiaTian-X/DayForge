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
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.repository.MetricRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.DatabaseObjectSelection
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
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
    val trendLogs: List<MetricLogEntity>? = null,
    val totalLogCount: Long? = null,
    val latestLogDate: Long? = null,
    val metricSaveReference: com.dayforge.data.repository.CountCommandReference? = null,
    val metricSaveUnknown: Boolean = false,
    val metricSaved: Boolean = false,
    val metricEntryEnded: Boolean = false,
    val metricSavedValue: Double? = null,
    val metricSavedUnit: String? = null,
    val metricSavePendingDelivery: Boolean = false,
    val links: List<HabitMetricLinkWithHabit> = emptyList(),
    val isLoading: Boolean = true,
    val isRecording: Boolean = false,
    val isMutating: Boolean = false,
    val showValueInput: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val showUnlinkConfirm: Long? = null,
    val showLinkHabit: Boolean = false,
    val availableHabits: List<HabitForLinking> = emptyList(),
    val selectedHabitIds: Set<Long> = emptySet(),
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val writeAuthority: com.dayforge.data.repository.ObjectEditAuthority? = null,
    val sourceSelection: DatabaseObjectSelection? = null,
    val sourceExpired: Boolean = false,
    val valueInputRequest: MetricDetailInteraction? = null,
    val deleteRequest: MetricDetailInteraction? = null,
    val unlinkRequest: MetricDetailInteraction? = null,
    val linkRequest: MetricDetailInteraction? = null,
    val unlinkTarget: HabitMetricLinkEntity? = null
)

/**
 * Represents a habit available for linking.
 */
class MetricDetailInteraction internal constructor()

data class HabitForLinking(
    val id: Long,
    val name: String,
    val isAlreadyLinked: Boolean,
    val uuid: String? = null
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
 * Per D-16: Delete with confirmation through the protocol-specific repository lifecycle
 */
@HiltViewModel
class MetricDetailViewModel internal constructor(
    @param:ApplicationContext private val context: Context,
    metricDao: MetricDao?, metricLogDao: MetricLogDao?, habitMetricLinkDao: HabitMetricLinkDao?,
    habitDao: HabitDao?, metricRepository: MetricRepository?, savedStateHandle: SavedStateHandle,
    private val databases: DatabaseRuntimeHost? = null,
    private val metricDelivery: MetricValueDelivery = RepositoryMetricValueDelivery
) : ViewModel() {
    @Inject internal constructor(@ApplicationContext context: Context, databases: DatabaseRuntimeHost,
        savedStateHandle: SavedStateHandle) : this(context, null, null, null, null, null, savedStateHandle, databases)

    private val fixedReads = if (databases == null) com.dayforge.data.repository.MetricDetailSourceReader(
        requireNotNull(metricDao), requireNotNull(metricLogDao), requireNotNull(habitMetricLinkDao), requireNotNull(habitDao)) else null
    private val reads get() = databases?.boundGraph()?.metricDetailSourceReader ?: requireNotNull(fixedReads)
    private val fixedRepository = metricRepository
    private val metricRepository get() = databases?.boundGraph()?.metricRepository ?: requireNotNull(fixedRepository)
    internal val usesSourceEpoch: Boolean get() = databases != null
    private val metricId: Long = savedStateHandle["metricId"] ?: 0L
    private val _uiState = MutableStateFlow(MetricDetailUiState(sourceExpired = databases != null))
    val uiState: StateFlow<MetricDetailUiState> = _uiState.asStateFlow()
    @Volatile private var boundSelection: DatabaseObjectSelection? = null
    @Volatile private var readGeneration: Any = Any()
    private val memoryRegistration = databases?.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val source = boundSelection
            if (blocked || source?.publication?.isCurrentInMemory() != true) expireSource()
        }
    })
    private var readJob: Job? = null
    private var independentAttempt: com.dayforge.data.repository.IndependentMetricAttempt? = null
    private var independentSaved: com.dayforge.data.repository.IndependentMetricSaved? = null
    private var deliveryAttempted: com.dayforge.data.repository.IndependentMetricSaved? = null
    private var independentEnded: com.dayforge.data.repository.IndependentMetricEnded? = null
    private var endDeliveryAttempted: com.dayforge.data.repository.IndependentMetricEnded? = null
    private var endDeliveryOwner: com.dayforge.data.repository.IndependentMetricEnded? = null
    private var endDeliveryJob: Job? = null
    private var deliveryJob: Job? = null
    private var deliveryOwner: com.dayforge.data.repository.IndependentMetricSaved? = null
    init { if (databases == null) resumeReads() }

    internal fun isTerminalCurrent(frame: MetricDetailUiState): Boolean = databases == null ||
        !frame.sourceExpired && frame.sourceSelection != null && frame.sourceSelection === boundSelection &&
            frame.sourceSelection.publication.isCurrentInMemory()

    private fun expireSource() {
        readGeneration = Any()
        independentAttempt = null; independentSaved = null; deliveryAttempted = null
        independentEnded = null; endDeliveryAttempted = null
        _uiState.update { it.copy(metric = null, writeAuthority = null, latestValue = null,
            logs = emptyList(), trendLogs = null, totalLogCount = null, latestLogDate = null,
            metricSaveReference = null, metricSaveUnknown = false, metricSaved = false, metricEntryEnded = false, metricSavedValue = null, metricSavedUnit = null, metricSavePendingDelivery = false,
            links = emptyList(), availableHabits = emptyList(), selectedHabitIds = emptySet(),
            showValueInput = false, showDeleteConfirm = false, showUnlinkConfirm = null, showLinkHabit = false,
            valueInputRequest = null, deleteRequest = null, unlinkRequest = null, linkRequest = null, unlinkTarget = null,
            isLoading = false, isDeleted = false, sourceExpired = true,
            errorMessage = context.getString(R.string.database_view_expired)) }
    }
    private fun inputFailure(error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error), android.widget.Toast.LENGTH_LONG).show()
    }
    private fun actionFrame(frame: MetricDetailUiState?): MetricDetailUiState? = try {
        if (databases == null) _uiState.value else requireNotNull(frame) { "DATABASE_EPOCH_INPUT_REQUIRED" }.also {
            val original = requireNotNull(it.sourceSelection) { "DATABASE_EPOCH_INPUT_REQUIRED" }
            check(original === boundSelection && original.id == metricId && !it.sourceExpired &&
                !_uiState.value.sourceExpired && _uiState.value.sourceSelection === original &&
                original.publication.isCurrentInMemory()) { "DATABASE_EPOCH_INPUT_EXPIRED" }
            it.metric?.let(original::requireMetric)
        }
    } catch (error: Exception) { inputFailure(error); null }
    private fun publish(frame: MetricDetailUiState, update: (MetricDetailUiState) -> MetricDetailUiState): Boolean {
        if (databases == null) { _uiState.update(update); return true }
        val source = frame.sourceSelection ?: return false
        return source.publication.publish { if (boundSelection === source) _uiState.update(update) }
    }
    private fun sameInteraction(expected: MetricDetailInteraction?, actual: MetricDetailInteraction?): Boolean {
        if (databases == null || expected != null && expected === actual) return true
        inputFailure(IllegalStateException("DATABASE_EPOCH_INPUT_EXPIRED")); return false
    }
    private suspend fun <T> withInput(frame: MetricDetailUiState, block: suspend () -> T): T {
        val host = databases ?: return block()
        return host.withSelection(frame.sourceSelection, DatabaseObjectSelection.Kind.METRIC) { graph ->
            val original = requireNotNull(frame.sourceSelection)
            graph.metricDetailSourceReader.visibleMetric(metricId)?.let(original::requireMetric)
            // Original repository missing-row/no-op semantics remain; actual write UUID
            // checks still prevent a replacement appearing after this preliminary read.
            block()
        }
    }

    fun resumeReads(sourceSelection: DatabaseObjectSelection? = null) {
        if (databases != null && boundSelection != null && boundSelection !== sourceSelection) {
            expireSource(); inputFailure(IllegalStateException("DATABASE_EPOCH_INPUT_EXPIRED")); return
        }
        if (readJob?.isActive == true) return
        val host = databases
        if (host == null) {
            readJob = viewModelScope.launch { observeMetric(this); observeLogs(this); observeLinks(this) }
            return
        }
        val selection = try {
            requireNotNull(sourceSelection) { "DATABASE_EPOCH_INPUT_REQUIRED" }.also {
                check(it.kind == DatabaseObjectSelection.Kind.METRIC && it.id == metricId &&
                    (boundSelection == null || boundSelection === it) && it.publication.isCurrentInMemory()) {
                    "DATABASE_EPOCH_INPUT_EXPIRED"
                }
            }
        } catch (error: Exception) { expireSource(); inputFailure(error); return }
        boundSelection = selection
        val generation = Any().also { readGeneration = it }
        // Memory-listener registration may have cleared an unbound initial frame. A fresh
        // explicit read starts like the original fixed reader: clear that prior read error,
        // under this source's fence, without clearing errors on every later DB emission.
        selection.publication.publish {
            if (boundSelection === selection && readGeneration === generation) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
        }
        readJob = viewModelScope.launch {
            try {
                host.observe { graph -> kotlinx.coroutines.flow.channelFlow {
                    host.withSelection(selection, DatabaseObjectSelection.Kind.METRIC) { selected ->
                        check(selected === graph)
                        graph.metricDetailSourceReader.observe(selection).collect { send(it) }
                    }
                } }.collect { frame ->
                    // Original selection's fence survives observation buffers and account changes.
                    selection.publication.publish {
                        if (readGeneration === generation && boundSelection === selection) {
                            val data = frame.value
                            _uiState.update { old -> if (data.metric == null) old.copy(metric = null, writeAuthority = null,
                                logs = emptyList(), trendLogs = null, totalLogCount = null, latestLogDate = null,
                                links = emptyList(), latestValue = null, showValueInput = false,
                                showDeleteConfirm = false, showUnlinkConfirm = null, showLinkHabit = false,
                                availableHabits = emptyList(), selectedHabitIds = emptySet(),
                                sourceSelection = selection, sourceExpired = false, isLoading = false,
                                errorMessage = context.getString(R.string.metric_error_not_found))
                            else old.copy(metric = data.metric, writeAuthority = data.authority, latestValue = data.latest?.value,
                                logs = data.logs, trendLogs = data.trendLogs, totalLogCount = data.totalLogCount,
                                latestLogDate = data.latestLogDate ?: data.latest?.date, links = data.links.map { HabitMetricLinkWithHabit(it.value,
                                    it.habitName ?: context.getString(R.string.metric_error_unknown_habit)) },
                                sourceSelection = selection, sourceExpired = false, isLoading = false) }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (readGeneration === generation) { expireSource(); inputFailure(error) }
            }
        }
    }
    fun pauseReads() {
        readGeneration = Any(); readJob?.cancel(); readJob = null
        _uiState.update { it.copy(writeAuthority = null, sourceExpired = databases != null) }
    }

    /**
     * Observe the metric from the database.
     * Updates the UI state whenever the metric changes.
     */
    private fun observeMetric(scope: CoroutineScope) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            reads.observeMetric(metricId).collect { metric ->
                if (metric == null) {
                    _uiState.value = _uiState.value.copy(
                        metric = null,
                        writeAuthority = null,
                        showValueInput = false,
                        showDeleteConfirm = false,
                        showUnlinkConfirm = null,
                        showLinkHabit = false,
                        availableHabits = emptyList(),
                        selectedHabitIds = emptySet(),
                        isLoading = false,
                        errorMessage = context.getString(R.string.metric_error_not_found)
                    )
                    return@collect
                }

                val captured = try {
                    if (metric.appearance != null) metricRepository.getMetricForEditing(metric.id) else null
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _uiState.value = _uiState.value.copy(isLoading = false, writeAuthority = null,
                        errorMessage = context.getString(R.string.metric_error_record_failed, error.message.orEmpty()))
                    return@collect
                }
                if (captured != null && captured.value != metric) return@collect
                _uiState.value = _uiState.value.copy(
                    metric = metric,
                    writeAuthority = captured?.authority,
                    isLoading = false
                )

                val latestLog = reads.latestLog(metricId)
                _uiState.value = _uiState.value.copy(latestValue = latestLog?.value)

            }
        }
    }

    /**
     * Observe logs for this metric from the database.
     * Updates the UI state whenever logs change.
     */
    private fun observeLogs(scope: CoroutineScope) {
        scope.launch {
            reads.observeLogs(metricId).collect { logs ->
                _uiState.value = _uiState.value.copy(logs = logs)
            }
        }
    }

    /**
     * Observe habit links for this metric from the database.
     * Resolves habit names for display in the UI.
     */
    private fun observeLinks(scope: CoroutineScope) {
        scope.launch {
            reads.observeLinks(metricId).collect { links ->
                val linksWithHabits = links.map { link ->
                    val habit = reads.habit(link.habitId)
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
    fun recordValue(value: Double, note: String, frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val now = _uiState.value
        if (now.isRecording || now.isMutating || !now.showValueInput || now.metricSaveUnknown) return
        if (!sameInteraction(captured.valueInputRequest, now.valueInputRequest)) return
        val metric = captured.metric ?: return
        val authority = captured.writeAuthority
        if (!publish(captured) { it.copy(isRecording = true, metricSaved = false, metricEntryEnded = false, metricSavedValue = null, metricSavedUnit = null,
                metricSavePendingDelivery = false, metricSaveReference = null, errorMessage = null) }) return
        viewModelScope.launch {
            var reference: com.dayforge.data.repository.CountCommandReference? = null
            try {
                if (databases == null) {
                    if (metric.appearance != null) metricRepository.recordValue(metric.id, value, note,
                        authority = requireNotNull(authority), expectedMetricUuid = metric.uuid)
                    else metricRepository.recordValue(metric.id, value, note)
                    publish(captured) { it.copy(showValueInput = false, valueInputRequest = null, latestValue = value) }
                } else withInput(captured) {
                    val attempt = metricRepository.beginIndependentValue(metric.id, value, note,
                        authority = authority, expectedMetricUuid = metric.uuid)
                    independentAttempt = attempt
                    reference = attempt.reference
                    publish(captured) { it.copy(metricSaveReference = reference) }
                    acceptIndependentSave(captured, metricRepository.executeIndependentValue(attempt))
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val known = reference
                if (known != null) {
                    val resolved = try { withInput(captured) { metricRepository.resolveIndependentValue(known) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (resolved is com.dayforge.data.repository.IndependentMetricResolution.Saved)
                        acceptIndependentSave(captured, resolved.value)
                    else if (resolved is com.dayforge.data.repository.IndependentMetricResolution.Ended)
                        acceptIndependentEnd(captured, resolved.value)
                    else if (resolved is com.dayforge.data.repository.IndependentMetricResolution.Unregistered)
                        releaseUnregistered(captured)
                    else publish(captured) { it.copy(metricSaveUnknown = true, metricSaveReference = known,
                        errorMessage = context.getString(R.string.independent_metric_unknown)) }
                } else if (databases != null && independentAttempt?.let { it.started && it.reference == null } == true) {
                    // Explicit opaque-v4 / LEGACY_UNENROLLED keeps the original writer but has no
                    // durable new command identity. An exception after admission is not proof
                    // of rollback. Do not advertise failure or offer a blind replay.
                    publish(captured) { it.copy(metricSaveUnknown = true,
                        errorMessage = context.getString(R.string.independent_metric_unknown)) }
                } else if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_record_failed, error.message.orEmpty())) })
                    inputFailure(error)
            } finally { _uiState.update { it.copy(isRecording = false) } }
        }
    }

    private fun acceptIndependentSave(frame: MetricDetailUiState, saved: com.dayforge.data.repository.IndependentMetricSaved) {
        check(saved.metricUuid == frame.metric?.uuid) { "METRIC_SAVED_IDENTITY_CHANGED" }
        independentAttempt = null; independentEnded = null
        if (publish(frame) { it.copy(showValueInput = false, valueInputRequest = null,
                metricSaveReference = saved.reference, metricSaveUnknown = false, metricSaved = true, metricEntryEnded = false,
                metricSavedValue = saved.value, metricSavedUnit = saved.unit,
                metricSavePendingDelivery = saved.reference != null && saved.deliveryAllowed, errorMessage = null) }) {
            independentSaved = saved; deliveryAttempted = null
        }
        // The current latest/window is published by the same-snapshot reader. A saved value
        // is not necessarily latest (future/late history), so never replace it with input here.
    }

    fun continueIndependentValue(frame: MetricDetailUiState) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isRecording || _uiState.value.isMutating) return
        if (!captured.metricSaveUnknown) {
            if (captured.metricEntryEnded) deliveredIndependentEnd(captured, explicit = true)
            else deliveredIndependentResult(captured, explicit = true)
            return
        }
        val reference = captured.metricSaveReference ?: return
        if (!publish(captured) { it.copy(isRecording = true) }) return
        viewModelScope.launch {
            try {
                when (val result = withInput(captured) {
                    independentAttempt?.takeIf { it.reference == reference }?.let {
                        metricRepository.resolveIndependentAttempt(it, continueIfProvenUnexecuted = true)
                    } ?: metricRepository.resolveIndependentValue(reference, continueIfProvenUnexecuted = true)
                }) {
                    is com.dayforge.data.repository.IndependentMetricResolution.Saved -> acceptIndependentSave(captured, result.value)
                    is com.dayforge.data.repository.IndependentMetricResolution.Ended -> acceptIndependentEnd(captured, result.value)
                    is com.dayforge.data.repository.IndependentMetricResolution.Unregistered -> releaseUnregistered(captured)
                    is com.dayforge.data.repository.IndependentMetricResolution.Unresolved -> publish(captured) {
                        it.copy(errorMessage = context.getString(R.string.independent_metric_unknown)) }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publish(captured) { it.copy(errorMessage = context.getString(R.string.independent_metric_unknown)) }
            } finally { _uiState.update { it.copy(isRecording = false) } }
        }
    }

    /** Explicit user action. A closed form or UNKNOWN alone never cancels anything. The
     * Store must issue a complete current NoEffects plan before this producer may run. */
    fun endIndependentValue(frame: MetricDetailUiState) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        val reference = captured.metricSaveReference ?: return
        if (!captured.metricSaveUnknown || current.metricSaveReference != reference ||
            current.isRecording || current.isMutating) return
        if (!publish(captured) { it.copy(isRecording = true) }) return
        viewModelScope.launch {
            try {
                val ended = withInput(captured) {
                    val plan = metricRepository.prepareIndependentEnd(reference) ?: return@withInput null
                    metricRepository.endIndependentValue(plan) {
                        isTerminalCurrent(captured) && _uiState.value.metricSaveReference == reference &&
                            _uiState.value.metricSaveUnknown
                    }
                }
                if (ended != null) acceptIndependentEnd(captured, ended)
                else publish(captured) { it.copy(errorMessage = context.getString(R.string.independent_metric_unknown)) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val observed = try { withInput(captured) { metricRepository.resolveIndependentValue(reference) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                when (observed) {
                    is com.dayforge.data.repository.IndependentMetricResolution.Ended -> acceptIndependentEnd(captured, observed.value)
                    is com.dayforge.data.repository.IndependentMetricResolution.Saved -> acceptIndependentSave(captured, observed.value)
                    else -> publish(captured) { it.copy(errorMessage = context.getString(R.string.independent_metric_unknown)) }
                }
            } finally { _uiState.update { it.copy(isRecording = false) } }
        }
    }
    private fun acceptIndependentEnd(frame: MetricDetailUiState, ended: com.dayforge.data.repository.IndependentMetricEnded) {
        independentAttempt = null; independentSaved = null
        if (publish(frame) { it.copy(showValueInput = false, valueInputRequest = null,
                metricSaveReference = ended.reference, metricSaveUnknown = false, metricSaved = false,
                metricEntryEnded = true, metricSavedValue = null, metricSavedUnit = null,
                metricSavePendingDelivery = ended.pendingDelivery && ended.deliveryAllowed, errorMessage = null) }) {
            independentEnded = ended; endDeliveryAttempted = null
        }
    }
    fun deliveredIndependentEnd(frame: MetricDetailUiState, explicit: Boolean = false) {
        val ended = independentEnded ?: return
        if (!frame.metricEntryEnded || !frame.metricSavePendingDelivery || frame.metricSaveReference != ended.reference ||
            !isTerminalCurrent(frame) || endDeliveryOwner === ended && endDeliveryJob?.isActive == true ||
            !explicit && endDeliveryAttempted === ended) return
        endDeliveryAttempted = ended; endDeliveryOwner = ended
        endDeliveryJob = viewModelScope.launch {
            try {
                withInput(frame) { metricRepository.acknowledgeIndependentEnd(ended) {
                    isTerminalCurrent(frame) && independentEnded === ended
                } }
                publish(frame) { current -> if (independentEnded === ended && current.metricSaveReference == ended.reference)
                    current.copy(metricSavePendingDelivery = false, metricSaveReference = null) else current }
                if (independentEnded === ended) independentEnded = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publish(frame) { current -> if (independentEnded === ended && current.metricSaveReference == ended.reference)
                    current.copy(metricSavePendingDelivery = true) else current }
            } finally { if (endDeliveryOwner === ended) { endDeliveryOwner = null; endDeliveryJob = null } }
        }
    }

    private fun releaseUnregistered(frame: MetricDetailUiState) {
        independentAttempt = null
        publish(frame) { it.copy(metricSaveReference = null, metricSaveUnknown = false,
            errorMessage = context.getString(R.string.independent_metric_unregistered)) }
    }

    /** Called by the rendered saved result, never a prerequisite for saved/result feedback.
     * One automatic delivery attempt only; a failure remains visibly retryable, not a loop. */
    fun deliveredIndependentResult(frame: MetricDetailUiState, explicit: Boolean = false) {
        val saved = independentSaved ?: return
        if (!frame.metricSaved || !frame.metricSavePendingDelivery || frame.metricSaveReference != saved.reference ||
            !isTerminalCurrent(frame) || deliveryOwner === saved && deliveryJob?.isActive == true || !explicit && deliveryAttempted === saved) return
        deliveryAttempted = saved
        deliveryOwner = saved
        deliveryJob = viewModelScope.launch {
            try {
                metricDelivery.deliver(requireNotNull(databases), requireNotNull(frame.sourceSelection), saved) {
                    isTerminalCurrent(frame) && independentSaved === saved
                }
                publish(frame) { current ->
                    if (independentSaved === saved && current.metricSaveReference == saved.reference)
                        current.copy(metricSavePendingDelivery = false, metricSaveReference = null)
                    else current
                }
                if (independentSaved === saved) independentSaved = null
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                publish(frame) { current ->
                    if (independentSaved === saved && current.metricSaveReference == saved.reference)
                        current.copy(metricSavePendingDelivery = true)
                    else current
                }
            } finally {
                if (deliveryOwner === saved) { deliveryOwner = null; deliveryJob = null }
            }
        }
    }

    /**
     * Update the aggregation type for this metric.
     * Persists the change to the database.
     *
     * @param aggregationType The new aggregation type value ("average", "sum", or "by_time")
     */
    private val aggregationActions = com.dayforge.ui.InFlightActions()

    fun updateAggregationType(aggregationType: String, frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val metric = captured.metric ?: return
        val authority = captured.writeAuthority
        aggregationActions.launch(viewModelScope, metric.id) {
            try {
                withInput(captured) {
                    val updatedMetric = metric.copy(aggregationType = aggregationType, updatedAt = System.currentTimeMillis())
                    metricRepository.updateMetric(updatedMetric, authority)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_aggregation_failed, error.message.orEmpty())) })
                    inputFailure(error)
            }
        }
    }

    /**
     * Toggle the visibility of the value input dialog.
     */
    fun toggleValueInput(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        if (current.isRecording || current.isMutating) return
        if (databases != null && (current.showValueInput != captured.showValueInput ||
                current.showValueInput && !sameInteraction(captured.valueInputRequest, current.valueInputRequest))) return
        publish(captured) { it.copy(showValueInput = !it.showValueInput,
            valueInputRequest = if (it.showValueInput) null else MetricDetailInteraction()) }
    }

    /**
     * Toggle the visibility of the delete confirmation dialog.
     */
    fun toggleDeleteConfirm(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        if (current.isMutating || current.isRecording) return
        if (databases != null && (current.showDeleteConfirm != captured.showDeleteConfirm ||
                current.showDeleteConfirm && !sameInteraction(captured.deleteRequest, current.deleteRequest))) return
        publish(captured) { it.copy(showDeleteConfirm = !it.showDeleteConfirm,
            deleteRequest = if (it.showDeleteConfirm) null else MetricDetailInteraction()) }
    }

    /**
     * Delete this metric and all associated data.
     * Legacy cascades or retained v5 rows and their outbox are committed atomically.
     */
    fun deleteMetric(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        if (current.isMutating || current.isRecording || current.isDeleted) return
        if (databases != null && (!current.showDeleteConfirm || !sameInteraction(captured.deleteRequest, current.deleteRequest))) return
        val metric = captured.metric ?: return
        val authority = captured.writeAuthority
        if (!publish(captured) { it.copy(isMutating = true) }) return
        viewModelScope.launch {
            try {
                withInput(captured) { metricRepository.deleteMetric(metric, authority) }
                publish(captured) { it.copy(isDeleted = true, showDeleteConfirm = false, deleteRequest = null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_delete_failed, error.message.orEmpty())) })
                    inputFailure(error)
            } finally { _uiState.update { it.copy(isMutating = false) } }
        }
    }

    /**
     * Show the unlink confirmation dialog for a specific link.
     *
     * @param linkId The ID of the link to potentially unlink
     */
    fun showUnlinkConfirm(linkId: Long, frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isMutating) return
        val original = captured.links.singleOrNull { it.link.id == linkId }?.link
        if (databases != null && original == null) { inputFailure(IllegalStateException("DATABASE_EPOCH_SELECTION_CHANGED")); return }
        publish(captured) { it.copy(showUnlinkConfirm = linkId, unlinkTarget = original, unlinkRequest = MetricDetailInteraction()) }
    }

    /**
     * Dismiss the unlink confirmation dialog.
     */
    fun dismissUnlinkConfirm(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isMutating || !sameInteraction(captured.unlinkRequest, _uiState.value.unlinkRequest)) return
        publish(captured) { it.copy(showUnlinkConfirm = null, unlinkTarget = null, unlinkRequest = null) }
    }

    /**
     * Unlink a habit from this metric.
     * The durable v2 outbox synchronizes the local unlink later.
     */
    fun unlinkHabit(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        if (current.isMutating || current.isRecording || !sameInteraction(captured.unlinkRequest, current.unlinkRequest)) return
        val linkId = captured.showUnlinkConfirm ?: return
        val link = if (databases == null) captured.links.find { it.link.id == linkId }?.link ?: return
            else captured.unlinkTarget ?: return
        val authority = captured.writeAuthority
        if (!publish(captured) { it.copy(isMutating = true) }) return
        viewModelScope.launch {
            try {
                withInput(captured) { metricRepository.unlinkHabit(linkId, link.uuid, authority) }
                publish(captured) { it.copy(showUnlinkConfirm = null, unlinkTarget = null, unlinkRequest = null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_unlink_failed, error.message.orEmpty())) })
                    inputFailure(error)
            } finally { _uiState.update { it.copy(isMutating = false) } }
        }
    }

    /**
     * Clear any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Show the link habit dialog.
     * Loads all habits and marks which are already linked.
     */
    fun showLinkHabitDialog(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isMutating || _uiState.value.showLinkHabit) return
        if (!publish(captured) { it.copy(isMutating = true) }) return
        viewModelScope.launch {
            try {
                val options = withInput(captured) {
                    val allHabits = if (databases == null) reads.visibleHabits()
                        else databases.boundGraph().metricDetailSourceReader.availableHabits(requireNotNull(captured.sourceSelection))
                    val linkedIds = captured.links.map { it.link.habitId }.toSet()
                    allHabits.map { HabitForLinking(it.id, it.name, it.id in linkedIds, if (databases == null) null else it.uuid) }
                }
                publish(captured) { it.copy(showLinkHabit = true, availableHabits = options,
                    selectedHabitIds = emptySet(), linkRequest = MetricDetailInteraction()) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_link_failed, error.message.orEmpty())) })
                    inputFailure(error)
            } finally { _uiState.update { it.copy(isMutating = false) } }
        }
    }

    /**
     * Dismiss the link habit dialog.
     */
    fun dismissLinkHabitDialog(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isMutating || !sameInteraction(captured.linkRequest, _uiState.value.linkRequest)) return
        publish(captured) { it.copy(showLinkHabit = false, availableHabits = emptyList(),
            selectedHabitIds = emptySet(), linkRequest = null) }
    }

    /**
     * Toggle habit selection in the link dialog.
     */
    fun toggleHabitSelection(habitId: Long, frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        if (_uiState.value.isMutating || !sameInteraction(captured.linkRequest, _uiState.value.linkRequest)) return
        if (databases != null && captured.availableHabits.none { it.id == habitId }) {
            inputFailure(IllegalStateException("DATABASE_EPOCH_SELECTION_CHANGED")); return
        }
        publish(captured) { old -> old.copy(selectedHabitIds = if (habitId in old.selectedHabitIds)
            old.selectedHabitIds - habitId else old.selectedHabitIds + habitId) }
    }

    /**
     * Create links for selected habits.
     */
    fun linkSelectedHabits(frame: MetricDetailUiState? = null) {
        val captured = actionFrame(frame) ?: return
        val current = _uiState.value
        if (current.isMutating || current.isRecording || !sameInteraction(captured.linkRequest, current.linkRequest)) return
        val selectedIds = current.selectedHabitIds.toSet()
        val metric = captured.metric
        val authority = captured.writeAuthority
        if (selectedIds.isEmpty() || metric == null) { dismissLinkHabitDialog(captured); return }
        val expected = if (databases == null) null else try {
            selectedIds.associateWith { id -> requireNotNull(captured.availableHabits.single { it.id == id }.uuid) {
                "DATABASE_EPOCH_SELECTION_CHANGED"
            } }
        } catch (error: Exception) { inputFailure(error); return }
        if (!publish(captured) { it.copy(isMutating = true) }) return
        viewModelScope.launch {
            try {
                withInput(captured) {
                    if (expected == null) metricRepository.linkHabits(metric, selectedIds, authority)
                    else metricRepository.linkHabits(metric, selectedIds, authority, expectedHabitUuids = expected)
                }
                publish(captured) { it.copy(showLinkHabit = false, availableHabits = emptyList(),
                    selectedHabitIds = emptySet(), linkRequest = null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!publish(captured) { it.copy(errorMessage = context.getString(R.string.metric_error_link_failed, error.message.orEmpty())) })
                    inputFailure(error)
            } finally { _uiState.update { it.copy(isMutating = false) } }
        }
    }
}
