package com.dayforge.ui.metrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import android.content.Context
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.HabitRepository
import com.dayforge.data.repository.MetricRepository
import com.dayforge.data.repository.NextTimerWriter
import com.dayforge.domain.model.TimerActionAuthority
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class LinkedMetricCoordinatorTest {
    private lateinit var habitRepository: HabitRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var coordinator: LinkedMetricCoordinator
    private lateinit var accountListener: com.dayforge.data.local.PersonalDataDisplayMemory.Listener

    @Before
    fun setUp() {
        habitRepository = mockk()
        every { habitRepository.registerCompletionConsumer(any()) } answers { accountListener = firstArg(); Unit }
        metricRepository = mockk()
        preferencesManager = mockk()
        coordinator = LinkedMetricCoordinator(
            context = mockk<Context>(relaxed = true),
            preferencesManager = preferencesManager,
            metricRepository = metricRepository,
            habitRepository = habitRepository
        )
    }

    private fun completionSnapshot(): com.dayforge.data.repository.WidgetFactSnapshot {
        fun id() = java.util.UUID.randomUUID().toString()
        val appearance = com.dayforge.domain.model.ObjectAppearance(
            com.dayforge.domain.model.IconReference.Role("habit.custom"), "#123456", "object")
        val habit = HabitEntity(id = 7, name = "Completion", habitType = com.dayforge.data.model.HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = com.dayforge.data.model.HabitSchedule.Daily,
            completionPolicy = "recurring", appearance = appearance)
        val claim = com.dayforge.data.repository.WidgetFactClaim(id(), id(), null, null, null,
            habit.id, habit.uuid, kotlinx.serialization.json.JsonObject(emptyMap()), java.time.LocalDate.now().toString(),
            java.time.ZoneId.systemDefault().id, id(), null, null)
        return com.dayforge.data.repository.WidgetFactSnapshot(habit, claim, null, true, 1)
    }

    private fun prepareCompletionPrompt(snapshot: com.dayforge.data.repository.WidgetFactSnapshot): com.dayforge.data.repository.CompletionMetricRead {
        val metric = com.dayforge.data.local.entity.MetricEntity(id = 4, name = "Metric", unit = "kg", iconResId = 1,
            colorHex = "#123456", appearance = snapshot.habit.appearance)
        val authority = com.dayforge.data.repository.ObjectEditAuthority(snapshot.claim.session(), metric.uuid,
            "metric", kotlinx.serialization.json.JsonObject(emptyMap()))
        every { preferencesManager.getNeverAskAgain(snapshot.claim.session(), snapshot.habit.uuid) } returns flowOf(false)
        coEvery { metricRepository.getLinkedMetricSnapshots(snapshot.habit.id) } returns listOf(
            com.dayforge.data.local.dao.LinkedMetricSnapshot(snapshot.habit.id, metric.id, metric.name,
                null, metric.unit, 0, true, true))
        val prepared = com.dayforge.data.repository.CompletionMetricRead(snapshot,
            listOf(com.dayforge.data.repository.ObjectEditSnapshot(metric, authority)),
            listOf(com.dayforge.data.local.dao.LinkedMetricSnapshot(snapshot.habit.id, metric.id, metric.name,
                5.0, metric.unit, 0, true, true)))
        coEvery { metricRepository.publishCompletionPrompt(snapshot, any()) } coAnswers {
            secondArg<(com.dayforge.data.repository.CompletionMetricRead) -> Unit>().invoke(prepared)
        }
        coEvery { habitRepository.publishCompletion(any(), any(), any(), any()) } coAnswers { arg<suspend () -> Unit>(3).invoke() }
        return prepared
    }

    @Test fun completionPromptKeepsOriginalSnapshotAndOldPreparationCannotReplaceNewDraft() = runTest {
        val snapshot = completionSnapshot()
        prepareCompletionPrompt(snapshot)
        val old = coordinator.captureCompletionPrompt()
        val current = coordinator.captureCompletionPrompt()
        coordinator.showCompletionPrompt(snapshot, old)
        assertNull(coordinator.postCheckInState.value)
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
        coordinator.showCompletionPrompt(snapshot, current)
        val displayed = requireNotNull(coordinator.postCheckInState.value)
        org.junit.Assert.assertSame(snapshot, displayed.factPrompt?.completion)
        org.junit.Assert.assertSame(snapshot.claim, displayed.factPrompt?.source)
        assertEquals(5.0, displayed.linkedMetrics.single().latestValue)
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        org.junit.Assert.assertSame(displayed, coordinator.postCheckInState.value)
        coVerify(exactly = 0) { habitRepository.getHabitById(any()) }
        coVerify(exactly = 1) { metricRepository.publishCompletionPrompt(snapshot, any()) }
        coVerify(exactly = 0) { habitRepository.publishCompletion(any(), any(), any(), any()) }
        coVerify(exactly = 0) { metricRepository.getMetricsForEditing(any()) }
    }

    @Test fun completionPromptAccountClearCannotBeUndoneByLatePublication() = runTest {
        val snapshot = completionSnapshot()
        val prepared = prepareCompletionPrompt(snapshot)
        coEvery { metricRepository.publishCompletionPrompt(snapshot, any()) } coAnswers {
            accountListener.transition(true); accountListener.transition(false)
            secondArg<(com.dayforge.data.repository.CompletionMetricRead) -> Unit>().invoke(prepared)
        }
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        assertNull(coordinator.postCheckInState.value)
    }

    @Test fun completionPromptNoLinksAndNeverAskDoNotEnterTheAuditedReadPlan() = runTest {
        val snapshot = completionSnapshot()
        prepareCompletionPrompt(snapshot)
        coEvery { metricRepository.getLinkedMetricSnapshots(snapshot.habit.id) } returns emptyList()
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        every { preferencesManager.getNeverAskAgain(snapshot.claim.session(), snapshot.habit.uuid) } returns flowOf(true)
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        assertNull(coordinator.postCheckInState.value)
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(any()) }
        coVerify(exactly = 0) { metricRepository.publishCompletionPrompt(any(), any()) }
    }

    @Test fun suspendedCompletionPreparationCannotOverwriteANewerPrompt() = runTest {
        val snapshot = completionSnapshot()
        val prepared = prepareCompletionPrompt(snapshot)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        coEvery { metricRepository.publishCompletionPrompt(snapshot, any()) } coAnswers {
            val publish = secondArg<(com.dayforge.data.repository.CompletionMetricRead) -> Unit>()
            if (++calls == 1) { started.complete(Unit); release.await() }
            publish(prepared)
        }
        val old = coordinator.captureCompletionPrompt()
        val work = launch { coordinator.showCompletionPrompt(snapshot, old) }
        started.await()
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        val current = requireNotNull(coordinator.postCheckInState.value)
        release.complete(Unit); work.join()
        org.junit.Assert.assertSame(current, coordinator.postCheckInState.value)
        assertEquals(2, calls)
    }

    @Test fun rejectedCompletionPromptDoesNotDiscardAnOpenDurableDraftSaveOrRefresh() = runTest {
        val owner = mockk<com.dayforge.data.repository.OneTimeRepository>()
        every { owner.pendingHabitIds } returns flowOf(emptySet())
        every { owner.registerConsumer(any()) } returns Unit
        val row = completionSnapshot().habit.copy(completionPolicy = "one_and_done")
        val event = java.util.UUID.randomUUID().toString()
        fun prompt(input: String) = mockk<com.dayforge.data.repository.OneTimeMetricPrompt> {
            every { habitId } returns row.id
            every { habitName } returns row.name
            every { eventUuid } returns event
            every { entries } returns listOf(com.dayforge.data.repository.OneTimeMetricEntry(4, "Metric", "kg", 0, input, ""))
        }
        val original = prompt("")
        val updated = prompt("5")
        coEvery { habitRepository.getHabitById(row.id) } returns row
        coEvery { owner.prompt(row.id) } returns original
        coEvery { owner.publish(any(), any()) } coAnswers { secondArg<() -> Unit>().invoke() }
        coordinator = LinkedMetricCoordinator(mockk<Context>(relaxed = true), preferencesManager,
            metricRepository, habitRepository, oneTimeRepository = owner)
        for (refresh in listOf(false, true)) {
            coordinator.showPromptIfNeeded(row.id, row.name)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            coEvery { owner.saveDraft(original, any()) } coAnswers { started.complete(Unit); release.await(); updated }
            coEvery { owner.refresh(original) } coAnswers { started.complete(Unit); release.await(); updated }
            val work = launch {
                if (refresh) coordinator.refreshPrompt()
                else coordinator.savePromptDraft(event, listOf(com.dayforge.ui.components.MetricInputState(4, "Metric", "kg", 0, "5")))
            }
            started.await()
            val rejected = coordinator.captureCompletionPrompt()
            org.junit.Assert.assertFalse(rejected.eligible)
            release.complete(Unit)
            work.join()
            org.junit.Assert.assertSame(updated, coordinator.postCheckInState.value?.oneTimePrompt)
            assertEquals("5", coordinator.postCheckInState.value?.oneTimePrompt?.entries?.single()?.input)
            coordinator.closePrompt()
        }
    }

    @Test fun completionPromptPreferenceAndSkipKeepFactIdentityAndOldCloseCannotClearNewState() = runTest {
        val snapshot = completionSnapshot()
        prepareCompletionPrompt(snapshot)
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        val old = requireNotNull(coordinator.postCheckInState.value)
        val proof = requireNotNull(old.factPrompt)
        coEvery { preferencesManager.setNeverAskAgain(snapshot.claim.session(), snapshot.habit.uuid, true) } returns Unit
        coEvery { preferencesManager.removePendingMetricHabit(snapshot.claim.session(), snapshot.habit.uuid) } returns Unit
        coordinator.setNeverAskAgain(snapshot.habit.id, true, expectedFactPrompt = proof)
        coordinator.skipPrompt(snapshot.habit.id, expectedFactPrompt = proof)
        assertNull(coordinator.postCheckInState.value)
        coordinator.showCompletionPrompt(snapshot, coordinator.captureCompletionPrompt())
        val latest = requireNotNull(coordinator.postCheckInState.value)
        coordinator.closePrompt(old)
        org.junit.Assert.assertSame(latest, coordinator.postCheckInState.value)
        coVerify(exactly = 1) { preferencesManager.setNeverAskAgain(snapshot.claim.session(), snapshot.habit.uuid, true) }
        coVerify(exactly = 1) { preferencesManager.removePendingMetricHabit(snapshot.claim.session(), snapshot.habit.uuid) }
    }

    @Test
    fun ordinary_prompt_uses_one_metadata_lookup() = runTest {
        val habit = mockk<HabitEntity> { every { completionPolicy } returns "recurring" }
        coEvery { habitRepository.getHabitById(7L) } returns habit
        coEvery { metricRepository.getLinkedMetricSnapshots(7L) } returns emptyList()
        coordinator.showPromptIfNeeded(7L, "Check")
        coVerify(exactly = 1) { habitRepository.getHabitById(7L) }
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(7L) }
        assertNull(coordinator.postCheckInState.value)
    }

    @Test
    fun reused_metadata_never_bypasses_fresh_preference_identity_validation() = runTest {
        val habit = mockk<HabitEntity> { every { completionPolicy } returns "recurring" }
        val preferences = mockk<com.dayforge.data.repository.HabitPreferenceRepository>()
        every { preferences.pendingHabitIds } returns flowOf(emptySet())
        coEvery { habitRepository.getHabitById(7L) } returns habit
        coEvery { preferences.read(habit, null, null) } throws IllegalStateException("HABIT_PREFERENCE_IDENTITY_CHANGED")
        coordinator = LinkedMetricCoordinator(mockk<Context>(relaxed = true), preferencesManager,
            metricRepository, habitRepository, habitPreferences = preferences)
        val error = runCatching { coordinator.showPromptIfNeeded(7L, "Check") }.exceptionOrNull()
        assertEquals("HABIT_PREFERENCE_IDENTITY_CHANGED", error?.message)
        coVerify(exactly = 1) { habitRepository.getHabitById(7L) }
        coVerify(exactly = 1) { preferences.read(habit, null, null) }
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
        assertNull(coordinator.postCheckInState.value)
    }

    @Test
    fun timer_stop_prompt_reloads_habit_after_service_persistence_delay() = runTest {
        val habit = mockk<HabitEntity> {
            every { name } returns "Timer"
            every { completionPolicy } returns null
            every { targetCycles } returns null
        }
        coEvery { habitRepository.getHabitById(7L) } returns habit
        coEvery { metricRepository.getLinkedMetricSnapshots(7L) } returns emptyList()

        coordinator.showPromptAfterTimerStop(7L)

        coVerify(exactly = 2) { habitRepository.getHabitById(7L) }
        coVerify(exactly = 1) { metricRepository.getLinkedMetricSnapshots(7L) }
    }

    @Test
    fun timer_stop_prompt_ignores_unaccepted_stop() = runTest {
        coordinator.showPromptAfterTimerStop(null)

        coVerify(exactly = 0) { habitRepository.getHabitById(any()) }
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
    }

    @Test
    fun typed_timer_prompt_does_not_read_metrics_before_confirmed_owned_completion() = runTest {
        val writer = mockk<NextTimerWriter>()
        val ticket = TimerActionAuthority("aa310000-0000-4000-8000-000000000001",
            "aa310000-0000-4000-8000-000000000002", null, null, null,
            "aa310000-0000-4000-8000-000000000003", "aa310000-0000-4000-8000-000000000004", 2, null)
        coordinator = LinkedMetricCoordinator(mockk<Context>(relaxed = true), preferencesManager,
            metricRepository, habitRepository, timerWriter = writer)
        coEvery { writer.afterCompletion(7L, ticket, any()) } returns Unit
        coordinator.showPromptAfterTimerStop(7L, ticket)
        coVerify(exactly = 1) { writer.afterCompletion(7L, ticket, any()) }
        coVerify(exactly = 0) { habitRepository.getHabitById(any()) }
        coVerify(exactly = 0) { metricRepository.getLinkedMetricSnapshots(any()) }
        assertNull(coordinator.postCheckInState.value)
    }
}
