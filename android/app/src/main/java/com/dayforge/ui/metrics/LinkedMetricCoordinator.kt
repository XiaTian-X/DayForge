package com.dayforge.ui.metrics

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.dayforge.R
import com.dayforge.data.model.HabitType
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
    internal val metrics: List<ObjectEditSnapshot<MetricEntity>>,
    internal val completion: com.dayforge.data.repository.WidgetFactSnapshot? = null
) {
    internal var submitted: List<MetricValueInput>? = null
}

/** Captured at the initiating click. Never a credential or a persisted command receipt. */
class CompletionPromptRequest internal constructor(internal val generation: Long, internal val eligible: Boolean)

/** A regular screen prompt retains original metric identity, not just reused numeric IDs. */
class HabitMetricPrompt internal constructor(
    internal val source: com.dayforge.data.repository.HabitPreferenceClaim,
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
    val factPrompt: FactMetricPrompt? = null,
    val preferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null,
    val habitPrompt: HabitMetricPrompt? = null
)

/** Coordinates linked-metric card, prompt, and recording behavior for habit screens. */
class LinkedMetricCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val metricRepository: MetricRepository,
    private val habitRepository: HabitRepository,
    private val oneTimeRepository: OneTimeRepository? = null,
    private val timerWriter: NextTimerWriter? = null,
    private val factReader: com.dayforge.data.repository.WidgetFactReader? = null,
    private val habitPreferences: com.dayforge.data.repository.HabitPreferenceRepository? = null,
    private val legacyWidgets: com.dayforge.data.repository.LegacyWidgetFactWorkflow? = null
) {
    val pendingMetricHabits: Flow<Set<Long>> = combine(habitPreferences?.pendingHabitIds ?: kotlinx.coroutines.flow.flowOf(emptySet()),
        oneTimeRepository?.pendingHabitIds ?: kotlinx.coroutines.flow.flowOf(emptySet())) { old, once -> old + once }
    private val promptWrites = Mutex()

    private val _postCheckInState = MutableStateFlow<LinkedMetricPromptState?>(null)
    val postCheckInState: StateFlow<LinkedMetricPromptState?> = _postCheckInState
    private val promptGeneration = java.util.concurrent.atomic.AtomicLong()
    private val presentationMonitor = Any()
    @Volatile private var presentationBlocked = false
    private val invalidator = object : com.dayforge.data.local.AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) {
            synchronized(presentationMonitor) {
                presentationBlocked = blocked
                promptGeneration.incrementAndGet()
                _postCheckInState.value = null
            }
        }
    }
    private val completionInvalidator = object : com.dayforge.data.local.PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) { invalidator.authenticationTransition(blocked) }
    }
    init {
        oneTimeRepository?.registerConsumer(invalidator)
        habitRepository.registerCompletionConsumer(completionInvalidator)
    }

    fun captureCompletionPrompt() = synchronized(presentationMonitor) {
        val eligible = !presentationBlocked && _postCheckInState.value == null
        // A rejected new prompt must not invalidate an open durable draft's save/refresh.
        CompletionPromptRequest(if (eligible) promptGeneration.incrementAndGet() else promptGeneration.get(), eligible)
    }
    fun isCurrentCompletionPrompt(request: CompletionPromptRequest) = !presentationBlocked && request.eligible &&
        request.generation == promptGeneration.get() && _postCheckInState.value == null

    private fun publishPrepared(state: LinkedMetricPromptState, generation: Long) = synchronized(presentationMonitor) {
        if (!presentationBlocked && generation == promptGeneration.get()) _postCheckInState.value = state
    }
    private fun beginPrompt(clear: Boolean = false): Long = synchronized(presentationMonitor) {
        if (clear) _postCheckInState.value = null
        promptGeneration.incrementAndGet()
    }

    suspend fun showCompletionPrompt(snapshot: com.dayforge.data.repository.WidgetFactSnapshot,
        request: CompletionPromptRequest, isCurrent: () -> Boolean = { true }) =
        com.dayforge.domain.service.CompletionActionTiming.measure(
            com.dayforge.domain.service.CompletionActionTiming.Stage.PROMPT_PREPARE) {
        fun owns() = isCurrentCompletionPrompt(request) && isCurrent()
        if (!owns()) return@measure
        val claim = snapshot.claim
        check(snapshot.habit.completionPolicy == "recurring" && claim.completionUuid != null) {
            "COMPLETION_PROMPT_FACT_REQUIRED"
        }
        if (preferencesManager.getNeverAskAgain(claim.session(), snapshot.habit.uuid).first()) return@measure
        val infos = metricRepository.getLinkedMetricSnapshots(snapshot.habit.id).filter { it.promptOnComplete }
        if (infos.isEmpty() || !owns()) return@measure
        // Preflight is only a scheduling hint; publication uses one freshly verified read plan.
        metricRepository.publishCompletionPrompt(snapshot) { prepared ->
            val source = prepared.source
            val proof = FactMetricPrompt(source.claim, source.habit, prepared.metrics, source)
            val state = LinkedMetricPromptState(source.habit.id, source.habit.name, prepared.metrics.map { entry ->
                val metric = requireNotNull(entry.value)
                LinkedMetricInfo(metric.name, metric.id, prepared.links.single { it.metricId == metric.id }.latestValue,
                    metric.unit, metric.decimalPlaces)
            }, factPrompt = proof)
            synchronized(presentationMonitor) { if (owns()) _postCheckInState.value = state }
        }
    }

    private suspend fun publishFact(prompt: FactMetricPrompt, block: suspend () -> Unit) {
        prompt.completion?.let {
            habitRepository.publishCompletion(it, requireDay = false) {
                check(_postCheckInState.value?.factPrompt === prompt) { "FACT_PROMPT_EXPIRED" }
                block()
            }
            return
        }
        requireNotNull(factReader).publish(prompt.source, block)
    }

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
        val generation = beginPrompt()
        val habit = habitRepository.getHabitById(habitId) ?: return
        showPromptForPolicy(habitId, habitName, isTempTask,
            habit.completionPolicy, initialHabit = habit, generation = generation)
    }

    suspend fun showLegacyWidgetPrompt(identity: com.dayforge.data.repository.LegacyWidgetRestartClaim) {
        val generation = beginPrompt(clear = true)
        val habit = requireNotNull(legacyWidgets).requireCurrent(identity)
        showPromptForPolicy(habit.id, habit.name, false, habit.completionPolicy, legacyIdentity = identity, generation = generation)
    }

    suspend fun showCountPrompt(identity: com.dayforge.domain.model.CountActionIdentity, habitName: String) {
        val generation = beginPrompt(clear = true)
        showPromptForPolicy(identity.habitId, habitName, false, "recurring", countIdentity = identity, generation = generation)
    }

    suspend fun showWidgetFactPrompt(claim: com.dayforge.data.repository.WidgetFactClaim) {
        val generation = beginPrompt(clear = true)
        val reader = requireNotNull(factReader)
        val habit = reader.requireCurrent(claim)
        if (habit.completionPolicy == "one_and_done") {
            // The persisted once prompt already owns its event, metadata, draft and observations.
            val owner = requireNotNull(oneTimeRepository)
            val prompt = owner.prompt(habit.id) ?: return
            check(prompt.eventUuid == claim.completionUuid && prompt.snapshot.session == claim.session()) {
                "ONE_TIME_PROMPT_EXPIRED"
            }
            owner.publish(prompt) { publishPrepared(prompt.toState(), generation) }
            return
        }
        check(claim.completionUuid != null) { "FACT_PROMPT_COMPLETION_CHANGED" }
        if (preferencesManager.getNeverAskAgain(claim.session(), habit.uuid).first()) return
        val infos = metricRepository.getLinkedMetricSnapshots(habit.id).filter { it.promptOnComplete }
        if (infos.isEmpty()) return
        val snapshots = metricRepository.getMetricsForEditing(infos.map { it.metricId }).onEach {
            check(it.authority?.session == claim.session() && it.value?.appearance != null) { "FACT_PROMPT_STALE_METRIC" }
        }
        val proof = FactMetricPrompt(claim, habit, snapshots)
        reader.publish(claim) {
            publishPrepared(LinkedMetricPromptState(habit.id, habit.name,
                snapshots.map { snapshot ->
                    val metric = requireNotNull(snapshot.value)
                    LinkedMetricInfo(metric.name, metric.id, infos.single { it.metricId == metric.id }.latestValue,
                        metric.unit, metric.decimalPlaces)
                }, factPrompt = proof), generation)
        }
    }

    private suspend fun showPromptForPolicy(
        habitId: Long,
        habitName: String,
        isTempTask: Boolean,
        completionPolicy: String?,
        timerAuthority: TimerActionAuthority? = null,
        legacyIdentity: com.dayforge.data.repository.LegacyWidgetRestartClaim? = null,
        countIdentity: com.dayforge.domain.model.CountActionIdentity? = null,
        initialHabit: HabitEntity? = null,
        generation: Long = promptGeneration.get()
    ) {
        if (completionPolicy == "one_and_done") {
            promptWrites.withLock {
                val owner = requireNotNull(oneTimeRepository)
                val prompt = owner.prompt(habitId) ?: return@withLock
                owner.publish(prompt) { publishPrepared(prompt.toState(), generation) }
            }
            return
        }
        // Only the immediately preceding screen lookup may supply metadata here. This is not
        // authority: preference.read/publish still recheck the original UUID/session and row.
        val actualHabit = initialHabit ?: habitRepository.getHabitById(habitId) ?: return
        countIdentity?.let { check(actualHabit.uuid == it.habitUuid && actualHabit.habitType == HabitType.COUNTING) {
            "COUNT_ACTION_EXPIRED"
        } }
        val preference = if (timerAuthority == null) habitPreferences?.read(actualHabit,
            expectedSession = countIdentity?.session ?: legacyIdentity?.session(), widgetIdentity = legacyIdentity) else null
        if (legacyIdentity != null) requireNotNull(preference) { "LEGACY_WIDGET_PROMPT_OWNER_REQUIRED" }
        if (countIdentity != null) requireNotNull(preference) { "COUNT_PROMPT_OWNER_REQUIRED" }
        if (timerAuthority != null) {
            check(actualHabit.uuid == timerAuthority.habitUuid)
            if (preferencesManager.getNeverAskAgain(timerAuthority.session(), actualHabit.uuid).first()) return
        } else if (preference?.neverAsk == true) return

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
                val metrics = metricRepository.getMetricsForEditing(metricInfos.map { it.metricId }).onEach {
                    check(it.authority?.session == ticket.session() && it.value?.appearance != null) { "TIMER_PROMPT_STALE_METRIC" }
                }
                TimerMetricPrompt(ticket, habit, metrics)
            }
            val habitProof = preference?.let { captured ->
                val metrics = metricRepository.getMetricsForEditing(metricInfos.map { it.metricId }).onEach {
                    val metric = requireNotNull(it.value) { "METRIC_PROMPT_METADATA_CHANGED" }
                    if (actualHabit.appearance == null) check(metric.appearance == null && it.authority == null) {
                        "METRIC_PROMPT_MIXED_PROTOCOL"
                    } else check(metric.appearance != null && it.authority?.session == captured.claim.session) {
                        "METRIC_PROMPT_STALE_METRIC"
                    }
                }
                HabitMetricPrompt(captured.claim, actualHabit, metrics)
            }
            val state = LinkedMetricPromptState(
                habitId = habitId,
                habitName = habitName,
                linkedMetrics = (timerProof?.metrics ?: habitProof?.metrics)?.map { snapshot ->
                    val row = requireNotNull(snapshot.value)
                    LinkedMetricInfo(row.name, row.id, metricInfos.single { it.metricId == row.id }.latestValue, row.unit, row.decimalPlaces)
                } ?: metricInfos,
                show = true,
                isTempTask = isTempTask,
                timerPrompt = timerProof,
                preferenceClaim = preference?.claim,
                habitPrompt = habitProof
            )
            fun publish() { publishPrepared(state, generation) }
            if (timerAuthority != null) requireNotNull(timerWriter).publishPrompt(habitId, timerAuthority) { publish() }
            else if (preference != null) requireNotNull(habitPreferences).publish(preference.claim) { publish() }
            else publish()
        }
    }

    /** Explicit pending-card entry verifies the display identity before capturing metric tickets. */
    suspend fun showPendingTimerWidgetPrompt(habitId: Long, authority: TimerActionAuthority) {
        val generation = beginPrompt()
        require(authority.sessionUuid == null)
        requireNotNull(timerWriter).requireAction(habitId, authority)
        if (authority.habitUuid !in preferencesManager.pendingMetricHabitUuids(authority.session(), setOf(authority.habitUuid)).first()) return
        val habit = habitRepository.getHabitById(habitId) ?: return
        showPromptForPolicy(habitId, habit.name, false, habit.completionPolicy, authority, generation = generation)
    }

    /** Waits for TimerService persistence; dispatch alone never creates a prompt. */
    suspend fun showPromptAfterTimerStop(stoppedHabitId: Long?, authority: TimerActionAuthority? = null) =
        showTimerCompletionPrompt(stoppedHabitId, authority, afterGoal = false)

    /** Explicit follow-up skips only dialog priority, never the original completion proof. */
    suspend fun showPromptAfterTimerGoal(habitId: Long, authority: TimerActionAuthority) =
        showTimerCompletionPrompt(habitId, authority, afterGoal = true)

    private suspend fun showTimerCompletionPrompt(stoppedHabitId: Long?, authority: TimerActionAuthority?, afterGoal: Boolean) {
        stoppedHabitId ?: return
        val generation = beginPrompt()
        if (authority != null) {
            var confirmed: com.dayforge.data.local.entity.HabitEntity? = null
            requireNotNull(timerWriter).afterCompletion(stoppedHabitId, authority) { view ->
                if (afterGoal || !view.goalReached) confirmed = view.habit
            }
            // afterCompletion publishes under the non-reentrant account lock. Metric tickets are
            // captured only after releasing it, then the final prompt publication rechecks ownership.
            confirmed?.let { habit ->
                showPromptForPolicy(stoppedHabitId, habit.name, false, habit.completionPolicy, authority, generation = generation)
            }
            return
        }
        // The inactive legacy path retains its existing behavior until coordinated v5 activation.
        delay(POST_TIMER_STOP_PROMPT_DELAY_MS)
        val habit = habitRepository.getHabitById(stoppedHabitId) ?: return
        if (habit.targetCycles != null && requireNotNull(timerWriter).legacyGoalReached(stoppedHabitId)) return
        showPromptForPolicy(stoppedHabitId, habit.name, false, habit.completionPolicy, generation = generation)
    }

    suspend fun recordMetricValues(habitId: Long, values: List<MetricValueInput>, expectedEventUuid: String? = null,
        expectedTimerPrompt: TimerMetricPrompt? = null, expectedFactPrompt: FactMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null): Boolean {
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
                            promptHabit = expectedFactPrompt.habit, promptFact = expectedFactPrompt.source,
                            promptCompletionSource = expectedFactPrompt.completion != null)
                        expectedFactPrompt.submitted = values.toList()
                    } else check(expectedFactPrompt.submitted == values) { "FACT_PROMPT_ALREADY_SUBMITTED" }
                    com.dayforge.domain.service.afterCommittedAction {
                        publishFact(expectedFactPrompt) {
                            preferencesManager.removePendingMetricHabit(expectedFactPrompt.source.session(), expectedFactPrompt.habit.uuid)
                        }
                    }
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
                            expectedMetrics = selected.map { requireNotNull(it.value) }, promptHabit = expectedTimerPrompt.habit,
                            promptTimer = expectedTimerPrompt.source.takeIf { it.sessionUuid != null })
                        expectedTimerPrompt.submitted = values.toList()
                    } else check(expectedTimerPrompt.submitted == values) { "TIMER_PROMPT_ALREADY_SUBMITTED" }
                    com.dayforge.domain.service.afterCommittedAction {
                        requireNotNull(timerWriter).publishPrompt(habitId, expectedTimerPrompt.source) {
                            preferencesManager.removePendingMetricHabit(expectedTimerPrompt.source.session(), expectedTimerPrompt.habit.uuid)
                        }
                    }
                }
            } else if (expectedPreferenceClaim != null) {
                promptWrites.withLock {
                    val prompt = requireNotNull(_postCheckInState.value?.habitPrompt) { "METRIC_PROMPT_EXPIRED" }
                    check(prompt.source === expectedPreferenceClaim && prompt.habit.id == habitId &&
                        _postCheckInState.value?.preferenceClaim === expectedPreferenceClaim) { "METRIC_PROMPT_EXPIRED" }
                    if (prompt.submitted == null) {
                        val selected = values.map { input -> requireNotNull(prompt.metrics.singleOrNull {
                            it.value?.id == input.metricId
                        }) { "METRIC_PROMPT_METADATA_CHANGED" } }
                        require(selected.size == selected.map { it.value?.id }.toSet().size)
                        val drafts = values.map { MetricValueDraft(it.metricId, it.value, it.note) }
                        if (selected.isNotEmpty() && prompt.habit.appearance == null) {
                            check(selected.all { it.authority == null }) { "METRIC_PROMPT_MIXED_PROTOCOL" }
                            metricRepository.recordLegacyPromptValues(drafts, System.currentTimeMillis(), prompt.source,
                                requireNotNull(habitPreferences), prompt.habit, selected.map { requireNotNull(it.value) })
                        } else if (selected.isNotEmpty()) {
                            // This writer takes the account lock itself. Never wrap it in publish().
                            metricRepository.recordValues(drafts, System.currentTimeMillis(),
                                authority = requireNotNull(selected.first().authority),
                                expectedMetrics = selected.map { requireNotNull(it.value) }, promptHabit = prompt.habit)
                        }
                        prompt.submitted = values.toList()
                    } else check(prompt.submitted == values) { "METRIC_PROMPT_ALREADY_SUBMITTED" }
                    com.dayforge.domain.service.afterCommittedAction {
                        requireNotNull(habitPreferences).removePending(prompt.source)
                    }
                }
            } else {
                if (expectedEventUuid != null || habitRepository.getHabitById(habitId)?.completionPolicy == "one_and_done") {
                    promptWrites.withLock {
                        val owner = requireNotNull(oneTimeRepository)
                        val current = requireNotNull(_postCheckInState.value?.oneTimePrompt) { "ONE_TIME_PROMPT_EXPIRED" }
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
            }
            com.dayforge.domain.service.afterCommittedAction { com.dayforge.widget.WidgetRefreshScheduler.request(context) }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed to record linked metrics", error)
            Toast.makeText(
                context,
                com.dayforge.ui.actionFailureMessage(context, error),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    suspend fun setNeverAskAgain(habitId: Long, value: Boolean, expectedEventUuid: String? = null,
        expectedTimerPrompt: TimerMetricPrompt? = null, expectedFactPrompt: FactMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null) {
        promptWrites.withLock {
            if (expectedFactPrompt != null) {
                check(expectedFactPrompt.habit.id == habitId && _postCheckInState.value?.factPrompt === expectedFactPrompt)
                publishFact(expectedFactPrompt) {
                    preferencesManager.setNeverAskAgain(expectedFactPrompt.source.session(), expectedFactPrompt.habit.uuid, value)
                }
                return@withLock
            }
            if (expectedTimerPrompt != null) {
                check(expectedTimerPrompt.habit.id == habitId && _postCheckInState.value?.timerPrompt === expectedTimerPrompt)
                requireNotNull(timerWriter).publishPrompt(habitId, expectedTimerPrompt.source) {
                    preferencesManager.setNeverAskAgain(expectedTimerPrompt.source.session(), expectedTimerPrompt.habit.uuid, value)
                }
                return@withLock
            }
            if (expectedEventUuid != null) {
                val prompt = requireNotNull(_postCheckInState.value?.oneTimePrompt) { "ONE_TIME_PROMPT_EXPIRED" }
                check(prompt.eventUuid == expectedEventUuid && prompt.habitId == habitId)
                requireNotNull(oneTimeRepository).setNeverAskAgain(prompt, value)
            } else {
                val claim = requireNotNull(expectedPreferenceClaim) { "METRIC_PROMPT_EXPIRED" }
                check(claim.habitId == habitId && _postCheckInState.value?.preferenceClaim === claim) { "METRIC_PROMPT_EXPIRED" }
                requireNotNull(habitPreferences).setNeverAskAgain(claim, value)
            }
        }
    }

    fun dismissPrompt() {
        synchronized(presentationMonitor) {
            promptGeneration.incrementAndGet()
            _postCheckInState.value = null
        }
    }

    suspend fun closePrompt(expected: LinkedMetricPromptState? = null) = promptWrites.withLock {
        synchronized(presentationMonitor) {
            if (expected == null || _postCheckInState.value === expected) dismissPrompt()
        }
    }

    suspend fun refreshPrompt() = promptWrites.withLock {
        val generation = promptGeneration.get()
        val prompt = requireNotNull(_postCheckInState.value?.oneTimePrompt)
        val owner = requireNotNull(oneTimeRepository)
        val updated = owner.refresh(prompt)
        owner.publish(updated) { publishPrepared(updated.toState(), generation) }
    }

    /** Raw text remains durable, including incomplete input; submission performs validation. */
    suspend fun savePromptDraft(eventUuid: String, inputs: List<com.dayforge.ui.components.MetricInputState>) {
        promptWrites.withLock {
            val generation = promptGeneration.get()
            val current = _postCheckInState.value?.oneTimePrompt ?: return@withLock
            if (current.eventUuid != eventUuid) return@withLock
            val values = inputs.associate { it.metricId to (it.inputValue to it.note) }
            if (values.size == current.entries.size && current.entries.all {
                values[it.metricId] == (it.input to it.note)
            }) return@withLock
            val owner = requireNotNull(oneTimeRepository)
            val updated = owner.saveDraft(current, values)
            owner.publish(updated) { publishPrepared(updated.toState(), generation) }
        }
    }

    /** Explicit skip closes the durable prompt; closing the window alone retains it for retry. */
    suspend fun skipPrompt(habitId: Long, expectedEventUuid: String? = null, expectedTimerPrompt: TimerMetricPrompt? = null,
        expectedFactPrompt: FactMetricPrompt? = null,
        expectedPreferenceClaim: com.dayforge.data.repository.HabitPreferenceClaim? = null) {
        val displayed = _postCheckInState.value
        promptWrites.withLock {
            if (expectedFactPrompt != null) {
                check(expectedFactPrompt.habit.id == habitId && _postCheckInState.value?.factPrompt === expectedFactPrompt)
                publishFact(expectedFactPrompt) {
                    preferencesManager.removePendingMetricHabit(expectedFactPrompt.source.session(), expectedFactPrompt.habit.uuid)
                }
                return@withLock
            }
            if (expectedTimerPrompt != null) {
                check(expectedTimerPrompt.habit.id == habitId && _postCheckInState.value?.timerPrompt === expectedTimerPrompt)
                requireNotNull(timerWriter).publishPrompt(habitId, expectedTimerPrompt.source) {
                    preferencesManager.removePendingMetricHabit(expectedTimerPrompt.source.session(), expectedTimerPrompt.habit.uuid)
                }
                return@withLock
            }
            if (expectedEventUuid != null) {
                check(_postCheckInState.value?.oneTimePrompt?.eventUuid == expectedEventUuid) {
                    "ONE_TIME_PROMPT_EXPIRED"
                }
            }
            val prompt = _postCheckInState.value?.oneTimePrompt
            if (prompt == null) {
                expectedPreferenceClaim?.let { claim ->
                    check(claim.habitId == habitId && _postCheckInState.value?.preferenceClaim === claim) { "METRIC_PROMPT_EXPIRED" }
                    requireNotNull(habitPreferences).removePending(claim)
                }
                return@withLock
            }
            check(prompt.habitId == habitId)
            requireNotNull(oneTimeRepository).dismiss(prompt)
        }
        synchronized(presentationMonitor) { if (_postCheckInState.value === displayed) dismissPrompt() }
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
