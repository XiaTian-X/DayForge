package com.dayforge.ui.metrics

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.dayforge.R
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.dao.LinkedMetricSnapshot
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.MetricValueDraft
import com.dayforge.data.repository.OneTimeRepository
import com.dayforge.data.repository.OneTimeMetricPrompt
import com.dayforge.domain.service.TimerService
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.components.MetricValueInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class LinkedMetricPromptState(
    val habitId: Long,
    val habitName: String,
    val linkedMetrics: List<LinkedMetricInfo>,
    val show: Boolean = true,
    val isTempTask: Boolean = false,
    val oneTimePrompt: OneTimeMetricPrompt? = null
)

/** Coordinates linked-metric card, prompt, and recording behavior for habit screens. */
class LinkedMetricCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository,
    private val habitRepository: HabitRepository,
    private val oneTimeRepository: OneTimeRepository? = null
) {
    val pendingMetricHabits: Flow<Set<Long>> = combine(preferencesManager.pendingMetricHabits,
        oneTimeRepository?.pendingHabitIds ?: kotlinx.coroutines.flow.flowOf(emptySet())) { old, once -> old + once }
    private val promptWrites = Mutex()

    private val _postCheckInState = MutableStateFlow<LinkedMetricPromptState?>(null)
    val postCheckInState: StateFlow<LinkedMetricPromptState?> = _postCheckInState
    private val invalidator = object : com.dayforge.data.local.AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) { _postCheckInState.value = null }
    }
    init { oneTimeRepository?.registerConsumer(invalidator) }

    /**
     * Observe card metrics without per-habit queries. The Room projection also makes changes made
     * by sync visible when links, metric metadata, or metric logs change.
     */
    fun observeLinkedMetrics(
        habitIds: Flow<Set<Long>>,
        onlyShownInHabitDetail: Boolean
    ): Flow<Map<Long, List<LinkedMetricInfo>>> = combine(
        habitIds,
        metricRepository.observeLinkedMetricSnapshots()
    ) { currentHabitIds, snapshots ->
        snapshots
            .asSequence()
            .filter { snapshot ->
                snapshot.habitId in currentHabitIds &&
                    (!onlyShownInHabitDetail || snapshot.showInHabitDetail)
            }
            .groupBy(LinkedMetricSnapshot::habitId) { it.toLinkedMetricInfo() }
    }.catch { error ->
        Log.e(TAG, "Failed to observe linked metrics", error)
        emit(emptyMap())
    }

    suspend fun showPromptIfNeeded(
        habitId: Long,
        habitName: String,
        isTempTask: Boolean = false
    ) {
        showPromptForPolicy(habitId, habitName, isTempTask,
            habitRepository.getHabitById(habitId)?.completionPolicy)
    }

    private suspend fun showPromptForPolicy(
        habitId: Long,
        habitName: String,
        isTempTask: Boolean,
        completionPolicy: String?
    ) {
        if (completionPolicy == "one_and_done") {
            promptWrites.withLock {
                val owner = requireNotNull(oneTimeRepository)
                val prompt = owner.prompt(habitId) ?: return@withLock
                owner.publish(prompt) { _postCheckInState.value = prompt.toState() }
            }
            return
        }
        if (preferencesManager.getNeverAskAgain(habitId).first()) return

        val metricInfos = try {
            metricRepository.getLinkedMetricSnapshots(habitId)
                .asSequence()
                .filter(LinkedMetricSnapshot::promptOnComplete)
                .map { it.toLinkedMetricInfo() }
                .toList()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to load linked metrics for habit $habitId", error)
            emptyList()
        }

        if (metricInfos.isNotEmpty()) {
            _postCheckInState.value = LinkedMetricPromptState(
                habitId = habitId,
                habitName = habitName,
                linkedMetrics = metricInfos,
                show = true,
                isTempTask = isTempTask
            )
        }
    }

    /** Waits for TimerService persistence, then shows the existing linked-metric prompt if needed. */
    suspend fun showPromptAfterTimerStop(stoppedHabitId: Long?) {
        stoppedHabitId ?: return
        delay(POST_TIMER_STOP_PROMPT_DELAY_MS)
        val habit = habitRepository.getHabitById(stoppedHabitId) ?: return
        showPromptForPolicy(stoppedHabitId, habit.name, false, habit.completionPolicy)
    }

    suspend fun recordMetricValues(habitId: Long, values: List<MetricValueInput>, expectedEventUuid: String? = null): Boolean {
        return try {
            if (expectedEventUuid != null || habitRepository.getHabitById(habitId)?.completionPolicy == "one_and_done") {
                promptWrites.withLock {
                    val owner = requireNotNull(oneTimeRepository)
                    val current = requireNotNull(_postCheckInState.value?.oneTimePrompt)
                    check(current.habitId == habitId)
                    if (expectedEventUuid != null) check(current.eventUuid == expectedEventUuid) { "ONE_TIME_PROMPT_EXPIRED" }
                    val selected = current.entries.filter { it.input.isNotBlank() }
                    check(selected.size == values.size && selected.all { entry -> values.any {
                        it.metricId == entry.metricId && it.note == entry.note &&
                            com.dayforge.util.NumericInputUtils.parseFiniteDouble(entry.input) == it.value
                    } }) { "ONE_TIME_PROMPT_DRAFT_CHANGED" }
                    // Retry the same persisted draft/time/identities; do not rewrite it on submit.
                    owner.submit(current)
                }
            } else {
                metricRepository.recordValues(
                    values.map { input -> MetricValueDraft(input.metricId, input.value, input.note) },
                    recordedAt = System.currentTimeMillis()
                )
            }
            preferencesManager.removePendingMetricHabit(habitId)
            context.sendBroadcast(Intent(TimerService.ACTION_WIDGET_UPDATE).apply {
                putExtra(TimerService.EXTRA_HABIT_ID, habitId)
                setPackage(context.packageName)
            })
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to record linked metrics", error)
            Toast.makeText(
                context,
                context.getString(R.string.metric_error_record_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null) {
        promptWrites.withLock {
            if (expectedEventUuid != null) {
                val prompt = requireNotNull(_postCheckInState.value?.oneTimePrompt) { "ONE_TIME_PROMPT_EXPIRED" }
                check(prompt.eventUuid == expectedEventUuid && prompt.habitId == habitId)
                requireNotNull(oneTimeRepository).setNeverAskAgain(prompt, value)
            } else preferencesManager.setNeverAskAgain(habitId, value)
        }
    }

    fun dismissPrompt() {
        _postCheckInState.value = null
    }

    suspend fun closePrompt() = promptWrites.withLock { dismissPrompt() }

    suspend fun refreshPrompt() = promptWrites.withLock {
        val prompt = requireNotNull(_postCheckInState.value?.oneTimePrompt)
        val owner = requireNotNull(oneTimeRepository)
        val updated = owner.refresh(prompt)
        owner.publish(updated) { _postCheckInState.value = updated.toState() }
    }

    /** Raw text remains durable, including incomplete input; submission performs validation. */
    suspend fun savePromptDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>) {
        promptWrites.withLock {
            val current = _postCheckInState.value?.oneTimePrompt ?: return@withLock
            if (current.eventUuid != eventUuid) return@withLock
            val owner = requireNotNull(oneTimeRepository)
            val updated = owner.saveDraft(current, inputs.associate { it.metricId to (it.inputValue to it.note) })
            owner.publish(updated) { _postCheckInState.value = updated.toState() }
        }
    }

    /** Explicit skip closes the durable prompt; closing the window alone retains it for retry. */
    suspend fun skipPrompt(habitId: Long, expectedEventUuid: String? = null) {
        promptWrites.withLock {
            if (expectedEventUuid != null) check(_postCheckInState.value?.oneTimePrompt?.eventUuid == expectedEventUuid) {
                "ONE_TIME_PROMPT_EXPIRED"
            }
            val prompt = _postCheckInState.value?.oneTimePrompt ?: return@withLock
            check(prompt.habitId == habitId)
            requireNotNull(oneTimeRepository).dismiss(prompt)
        }
        dismissPrompt()
    }

    private fun OneTimeMetricPrompt.toState() = LinkedMetricPromptState(habitId, habitName,
        entries.map { LinkedMetricInfo(it.name, it.metricId, null, it.unit, it.decimalPlaces) },
        oneTimePrompt = this)

    private fun LinkedMetricSnapshot.toLinkedMetricInfo() = LinkedMetricInfo(
        metricName = metricName,
        metricId = metricId,
        latestValue = latestValue,
        unit = unit,
        decimalPlaces = decimalPlaces
    )

    private companion object {
        const val TAG = "LinkedMetricCoordinator"
        const val POST_TIMER_STOP_PROMPT_DELAY_MS = 100L
    }
}
