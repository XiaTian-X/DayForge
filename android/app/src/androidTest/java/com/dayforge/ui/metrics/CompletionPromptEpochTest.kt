package com.dayforge.ui.metrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.WidgetFactSnapshot
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real Host/Hilt/controlled Room and original COUNT -> linked metric read plan.
 * These are exact producer-frame/assignment gates, not a claim about rendered pixels.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CompletionPromptEpochTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var coordinator: LinkedMetricCoordinator
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun start() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }

    private suspend fun completed(): Pair<WidgetFactSnapshot, Long> = epochs.host.withGraph { graph ->
        val appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme")
        val habitId = graph.habitRepository.createHabit("Count prompt", "", HabitType.COUNTING,
            1, "#123456", HabitSchedule.Daily, targetValue = 3, completionPolicy = "recurring",
            appearance = appearance, creationAuthority = graph.nextObjectCreator.capture())
        val metricId = graph.metricRepository.createMetric(MetricEntity(name = "Prompt measurement", unit = "kg",
            decimalPlaces = 2, iconResId = 1, colorHex = "#123456",
            appearance = ObjectAppearance(IconReference.Role("metric.custom"), "#123456", "theme")),
            creationAuthority = graph.nextObjectCreator.capture())
        graph.metricRepository.linkHabits(requireNotNull(graph.metricDao.getMetricById(metricId)), setOf(habitId))
        val habit = requireNotNull(graph.habitRepository.getHabitById(habitId))
        val identity = requireNotNull(graph.habitRepository.getCountSummary(habit).actionIdentity)
        val result = graph.checkInService.incrementCount(context, habitId, identity)
        assertTrue(result is CheckInResult.Success)
        val source = requireNotNull((result as CheckInResult.Success).followUp)
        assertNotNull(source.claim.completionUuid)
        assertEquals(1, graph.completionDao.getCompletionsInRange(habitId,
            java.time.LocalDate.parse(source.claim.date), java.time.LocalDate.parse(source.claim.date).plusDays(1)).size)
        source to metricId
    }

    @Test fun originalCompletionReadPlanPublishesExactHostFrameAndReloginRevokesAssignment() = runBlocking<Unit> {
        val (source, metricId) = completed()
        val before = epochs.exactMain()
        val stale = coordinator.captureCompletionPrompt()
        val current = coordinator.captureCompletionPrompt()
        epochs.host.withGraph { coordinator.showCompletionPrompt(source, stale) }
        assertNull(coordinator.postCheckInFrames.value)
        epochs.host.withGraph { coordinator.showCompletionPrompt(source, current) }
        val frame = requireNotNull(coordinator.postCheckInFrames.value)
        val state = requireNotNull(frame.value)
        assertSame(state, coordinator.postCheckInState.value)
        assertSame(source, state.factPrompt?.completion)
        assertSame(source.claim, state.factPrompt?.source)
        assertEquals(metricId, state.linkedMetrics.single().metricId)
        assertEquals(source.habit.uuid, state.habitUuid)
        var assignments = 0
        assertTrue(coordinator.publishPromptFrame(frame) { assignments++ })
        assertEquals(1, assignments)
        epochs.host.withGraph { coordinator.showCompletionPrompt(source, coordinator.captureCompletionPrompt()) }
        assertSame(frame, coordinator.postCheckInFrames.value)
        assertEquals(before, epochs.exactMain())
        epochs.login() // Same account, real new authentication generation; no database replacement.
        assertNull(coordinator.postCheckInFrames.value)
        assertFalse(coordinator.publishPromptFrame(frame) { assignments++ })
        assertEquals(1, assignments)
        assertEquals(before, epochs.exactMain())
    }

    @Test fun actualClearReplacesOwnerAndOldPromptFrameCannotPublishOrSaveIntoNewGraph() = runBlocking<Unit> {
        val (source, metricId) = completed()
        epochs.host.withGraph { coordinator.showCompletionPrompt(source, coordinator.captureCompletionPrompt()) }
        val frame = requireNotNull(coordinator.postCheckInFrames.value)
        val proof = requireNotNull(frame.value?.factPrompt)
        assertEquals(metricId, frame.value?.linkedMetrics?.single()?.metricId)
        assertTrue(coordinator.publishPromptFrame(frame) { })
        epochs.clearAndAwaitReplacement()
        val before = epochs.exactMain()
        assertNull(coordinator.postCheckInFrames.value)
        assertFalse(coordinator.publishPromptFrame(frame) { fail("Old owner prompt assigned") })
        var entered = false
        val failure = try {
            epochs.host.withSourceGraph(frame.publication) { entered = true }
            null
        } catch (error: IllegalStateException) { error }
        assertEquals("DATABASE_EPOCH_INPUT_EXPIRED", failure?.message)
        assertFalse(entered)
        assertSame(source, proof.completion)
        assertEquals(before, epochs.exactMain())
    }
}
