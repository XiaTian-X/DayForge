package com.dayforge.domain.service

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
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

/** Same real owner/account/storage and numeric ID, different UUID. Host epoch validity alone
 * cannot authorize the old dialog to modify its replacement; both actual Repo writers recheck.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HabitGoalEpochIdentityTest {
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture()
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var coordinator: HabitLifecycleCoordinator
    @Before fun start() = runBlocking<Unit> { hilt.inject(); epochs.connect(); epochs.login() }

    @Test fun oldCompleteGoalCannotDeactivateSameIdReplacementAndFreshGoalSucceeds() = runBlocking<Unit> { verify(true) }
    @Test fun oldDismissGoalCannotChangeSameIdReplacementModeAndFreshGoalSucceeds() = runBlocking<Unit> { verify(false) }

    private suspend fun verify(complete: Boolean) {
        val original = HabitEntity(id = 43, uuid = "bc197000-0000-4000-8000-000000000001", name = "Original goal",
            habitType = HabitType.COUNTING, iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily,
            targetValue = 1, targetCycles = 1, failMode = FailMode.STRICT)
        epochs.current { graph -> graph.database.withTransaction { assertEquals(original.id, graph.habitDao.insert(original)) } }
        epochs.host.withGraph { coordinator.showGoalCompletion(original, 1) }
        val oldFrame = requireNotNull(coordinator.goalFrames.value)
        assertEquals(original.uuid, oldFrame.value.habitUuid)
        assertTrue(coordinator.publishGoalDisplay(oldFrame) { })
        val oldAction = coordinator.captureGoalCompletion(complete, oldFrame)
        val replacement = original.copy(uuid = "bc197000-0000-4000-8000-000000000002", name = "Replacement goal")
        epochs.current { graph -> graph.database.withTransaction { graph.habitDao.update(replacement) } }
        val before = epochs.exactMain()
        val rejected = try { oldAction(); null } catch (error: IllegalStateException) { error }
        assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", rejected?.message)
        assertSame(oldFrame, coordinator.goalFrames.value)
        assertTrue(coordinator.showGoalDialog.value)
        assertEquals(before, epochs.exactMain())
        epochs.current { assertEquals(replacement, it.habitDao.getHabitById(replacement.id)) }
        epochs.host.withGraph { coordinator.showGoalCompletion(replacement, 1) }
        val freshFrame = requireNotNull(coordinator.goalFrames.value)
        assertNotSame(oldFrame, freshFrame)
        assertEquals(replacement.uuid, freshFrame.value.habitUuid)
        assertTrue(coordinator.publishGoalDisplay(freshFrame) { })
        assertTrue(coordinator.captureGoalCompletion(complete, freshFrame)())
        assertNull(coordinator.goalFrames.value)
        assertFalse(coordinator.showGoalDialog.value)
        epochs.current { graph ->
            val actual = requireNotNull(graph.habitDao.getHabitById(replacement.id))
            assertEquals(replacement.uuid, actual.uuid)
            if (complete) { assertFalse(actual.isActive); assertEquals(FailMode.STRICT, actual.failMode) }
            else { assertTrue(actual.isActive); assertEquals(FailMode.LOOSE, actual.failMode) }
            assertTrue(graph.completionDao.getAllCompletionsOnce().isEmpty())
            assertEquals(0, graph.metricLogDao.countAll())
        }
    }
}
