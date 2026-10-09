package com.dayforge.ui.metrics

import android.content.Context
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
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.data.repository.ObjectEditSnapshot
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.TimerActionAuthority
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

/** In-memory owner/metadata proof; never restored from UI state or a caller-selected account. */
class TimerMetricPrompt internal constructor(
    internal val source: TimerActionAuthority,
    internal val habit: HabitEntity,
    internal val metrics: List<ObjectEditSnapshot<MetricEntity>>
) {
    // Protected by promptWrites. A double tap or follow-up preference retry cannot append a second batch.
    internal var submitted: List<MetricValueInput>? = null
}

/** Ordinary-widget prompt tickets stay in memory, just like timer metric tickets. */
class FactMetricPrompt internal constructor(
    internal val source: com.dayforge.data.repository.WidgetFactClaim,
    internal val habit: HabitEntity,
    internal val metrics: List<ObjectEditSnapshot<MetricEntity>>
) {
    internal var submitted: List<MetricValueInput>? = null
}

data class LinkedMetricPromptState(
    val habitId: Long,
    val habitName: String,
    val linkedMetrics: List<LinkedMetricInfo>,
    val show: Boolean = true,
    val isTempTask: Boolean = false,
    val oneTimePrompt: OneTimeMetricPrompt? = null,
    val timerPrompt: TimerMetricPrompt? = null,
    val factPrompt: FactMetricPrompt? = null
)

/** Coordinates linked-metric card, prompt, and recording behavior for habit screens. */
class LinkedMetricCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository,
    private val habitRepository: HabitRepository,
    private val oneTimeRepository: OneTimeRepository? = null,
    private val timerWriter: NextTimerWriter? = null,
    private val factReader: com.dayforge.data.repository.WidgetFactReader? = null
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

    suspend fun showWidgetFactPrompt(claim: com.dayforge.data.repository.WidgetFactClaim) {
        _postCheckInState.value = null
        val reader = requireNotNull(factReader)
        val habit = reader.requireCurrent(claim)
        if (habit.completionPolicy == "one_and_done") {
            // The persisted once prompt already owns its event, metadata, draft and observations.
            val owner = requireNotNull(oneTimeRepository)
            val prompt = owner.prompt(habit.id) ?: return
            check(prompt.eventUuid == claim.completionUuid && prompt.snapshot.session == claim.session()) {
                "ONE_TIME_PROMPT_EXPIRED"
            }
            owner.publish(prompt) { _postCheckInState.value = prompt.toState() }
            return
        }
        check(claim.completionUuid != null) { "FACT_PROMPT_COMPLETION_CHANGED" }
        if (preferencesManager.getNeverAskAgain(habit.id).first()) return
        val infos = metricRepository.getLinkedMetricSnapshots(habit.id).filter { it.promptOnComplete }
        if (infos.isEmpty()) return
        val snapshots = infos.map { info -> metricRepository.getMetricForEditing(info.metricId).also {
            check(it.authority?.session == claim.session() && it.value?.appearance != null) { "FACT_PROMPT_STALE_METRIC" }
        } }
        val proof = FactMetricPrompt(claim, habit, snapshots)
        reader.publish(claim) {
            _postCheckInState.value = LinkedMetricPromptState(habit.id, habit.name,
                snapshots.map { snapshot ->
                    val metric = requireNotNull(snapshot.value)
                    LinkedMetricInfo(metric.name, metric.id, infos.single { it.metricId == metric.id }.latestValue,
                        metric.unit, metric.decimalPlaces)
                }, factPrompt = proof)
        }
    }

    private suspend fun showPromptForPolicy(
        habitId: Long,
        habitName: String,
        isTempTask: Boolean,
        completionPolicy: String?,
        timerAuthority: TimerActionAuthority? = null
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
            val timerProof = timerAuthority?.let { ticket ->
                val habit = requireNotNull(habitRepository.getHabitById(habitId))
                check(habit.uuid == ticket.habitUuid)
                val metrics = metricInfos.map { info -> metricRepository.getMetricForEditing(info.metricId).also {
                    check(it.authority?.session == ticket.session() && it.value?.appearance != null) { "TIMER_PROMPT_STALE_METRIC" }
                } }
                TimerMetricPrompt(ticket, habit, metrics)
            }
            val state = LinkedMetricPromptState(
                habitId = habitId,
                habitName = habitName,
                linkedMetrics = timerProof?.metrics?.map { snapshot ->
                    val row = requireNotNull(snapshot.value)
                    LinkedMetricInfo(row.name, row.id, metricInfos.single { it.metricId == row.id }.latestValue, row.unit, row.decimalPlaces)
                } ?: metricInfos,
                show = true,
                isTempTask = isTempTask,
                timerPrompt = timerProof
            )
            if (timerAuthority != null) requireNotNull(timerWriter).publish(habitId, timerAuthority) { _postCheckInState.value = state }
            else _postCheckInState.value = state
        }
    }

    /** Explicit pending-card entry verifies the display identity before capturing metric tickets. */
    suspend fun showPendingTimerWidgetPrompt(habitId: Long, authority: TimerActionAuthority) {
        require(authority.sessionUuid == null)
        requireNotNull(timerWriter).requireAction(habitId, authority)
        if (habitId !in preferencesManager.pendingMetricHabits.first()) return
        val habit = habitRepository.getHabitById(habitId) ?: return
        showPromptForPolicy(habitId, habit.name, false, habit.completionPolicy, authority)
    }

    /** Waits for TimerService persistence; dispatch alone never creates a prompt. */
    suspend fun showPromptAfterTimerStop(stoppedHabitId: Long?, authority: TimerActionAuthority? = null) {
        stoppedHabitId ?: return
        if (authority != null) {
            var confirmed = false
            requireNotNull(timerWriter).afterCompletion(stoppedHabitId, authority) {
                confirmed = true
            }
            // afterCompletion publishes under the non-reentrant account lock. Metric tickets are
            // captured only after releasing it, then the final prompt publication rechecks ownership.
            if (confirmed) {
                val habit = habitRepository.getHabitById(stoppedHabitId) ?: return
                showPromptForPolicy(stoppedHabitId, habit.name, false, habit.completionPolicy, authority)
            }
            return
        }
        // The inactive legacy path retains its existing behavior until coordinated v5 activation.
        delay(POST_TIMER_STOP_PROMPT_DELAY_MS)
        val habit = habitRepository.getHabitById(stoppedHabitId) ?: return
        showPromptForPolicy(stoppedHabitId, habit.name, false, habit.completionPolicy)
    }

    suspend fun recordMetricValues(habitId: Long, values: List<MetricValueInput>, expectedEventUuid: String? = null,
        expectedTimerPrompt: TimerMetricPrompt? = null, expectedFactPrompt: FactMetricPrompt? = null): Boolean {
        return try {
            if (expectedFactPrompt != null) {
                promptWrites.withLock {
                    check(expectedFactPrompt.habit.id == habitId && _postCheckInState.value?.factPrompt === expectedFactPrompt) {
                        "FACT_PROMPT_EXPIRED"
                    }
                    require(values.isNotEmpty() && values.map { it.metricId }.distinct().size == values.size)
                    if (expectedFactPrompt.submitted == null) {
                        val selected = values.map { input -> expectedFactPrompt.metrics.single { it.value?.id == input.metricId } }
                        metricRepository.recordValues(values.map { MetricValueDraft(it.metricId, it.value, it.note) },
                            authority = requireNotNull(selected.first().authority), expectedMetrics = selected.map { requireNotNull(it.value) },
                            promptHabit = expectedFactPrompt.habit, promptFact = expectedFactPrompt.source)
                        expectedFactPrompt.submitted = values.toList()
                    } else check(expectedFactPrompt.submitted == values) { "FACT_PROMPT_ALREADY_SUBMITTED" }
                    requireNotNull(factReader).publish(expectedFactPrompt.source) { preferencesManager.removePendingMetricHabit(habitId) }
                }
            } else if (expectedTimerPrompt != null) {
                promptWrites.withLock {
                    check(expectedTimerPrompt.habit.id == habitId && _postCheckInState.value?.timerPrompt === expectedTimerPrompt) {
                        "TIMER_PROMPT_EXPIRED"
                    }
                    require(values.isNotEmpty() && values.map { it.metricId }.distinct().size == values.size)
                    if (expectedTimerPrompt.submitted == null) {
                        val selected = values.map { input -> expectedTimerPrompt.metrics.single { it.value?.id == input.metricId } }
                        metricRepository.recordValues(values.map { MetricValueDraft(it.metricId, it.value, it.note) },
                            recordedAt = System.currentTimeMillis(), authority = requireNotNull(selected.first().authority),
                            expectedMetrics = selected.map { requireNotNull(it.value) }, promptHabit = expectedTimerPrompt.habit)
                        expectedTimerPrompt.submitted = values.toList()
                    } else check(expectedTimerPrompt.submitted == values) { "TIMER_PROMPT_ALREADY_SUBMITTED" }
                    requireNotNull(timerWriter).publish(habitId, expectedTimerPrompt.source) {
                        preferencesManager.removePendingMetricHabit(habitId)
                    }
                }
            } else {
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
            }
            com.dayforge.widget.WidgetRefreshScheduler.request(context)
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

    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null,
        expectedTimerPrompt: TimerMetricPrompt? = null, expectedFactPrompt: FactMetricPrompt? = null) {
        promptWrites.withLock {
            if (expectedFactPrompt != null) {
                check(expectedFactPrompt.habit.id == habitId && _postCheckInState.value?.factPrompt === expectedFactPrompt)
                requireNotNull(factReader).publish(expectedFactPrompt.source) { preferencesManager.setNeverAskAgain(habitId, value) }
                return@withLock
            }
            if (expectedTimerPrompt != null) {
                check(expectedTimerPrompt.habit.id == habitId && _postCheckInState.value?.timerPrompt === expectedTimerPrompt)
                requireNotNull(timerWriter).publish(habitId, expectedTimerPrompt.source) { preferencesManager.setNeverAskAgain(habitId, value) }
                return@withLock
            }
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
    suspend fun skipPrompt(habitId: Long, expectedEventUuid: String? = null, expectedTimerPrompt: TimerMetricPrompt? = null,
        expectedFactPrompt: FactMetricPrompt? = null) {
        promptWrites.withLock {
            if (expectedFactPrompt != null) {
                check(expectedFactPrompt.habit.id == habitId && _postCheckInState.value?.factPrompt === expectedFactPrompt)
                requireNotNull(factReader).publish(expectedFactPrompt.source) { preferencesManager.removePendingMetricHabit(habitId) }
                return@withLock
            }
            if (expectedTimerPrompt != null) {
                check(expectedTimerPrompt.habit.id == habitId && _postCheckInState.value?.timerPrompt === expectedTimerPrompt)
                requireNotNull(timerWriter).publish(habitId, expectedTimerPrompt.source) { preferencesManager.removePendingMetricHabit(habitId) }
                return@withLock
            }
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
