package com.dayforge.domain.service

import android.content.Context
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.HabitRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class HabitDeletionCoordinatorTest {
    private lateinit var context: Context
    private lateinit var repository: HabitRepository
    private lateinit var coordinator: HabitDeletionCoordinator

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        repository = mockk()
        coordinator = HabitDeletionCoordinator(context, repository)
    }

    @Test
    fun leaf_habit_is_deleted_without_opening_confirmation() = runTest {
        val habit = habit("parent")
        coEvery { repository.getHabitChildren(habit.uuid) } returns emptyList()
        coJustRun { repository.deleteHabit(habit, context) }

        coordinator.requestDeletion(habit)

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabit(habit, context) }
    }

    @Test
    fun parent_habit_opens_confirmation_with_direct_child_count() = runTest {
        val parent = habit("parent")
        coEvery { repository.getHabitChildren(parent.uuid) } returns
            listOf(habit("child-1"), habit("child-2"))

        coordinator.requestDeletion(parent)

        assertEquals(PendingHabitDeletion(parent, 2), coordinator.pendingDeletion.value)
        coVerify(exactly = 0) { repository.deleteHabit(any(), any()) }
    }

    @Test
    fun confirmed_cascade_deletion_clears_pending_state() = runTest {
        val parent = preparePendingDeletion()
        coJustRun { repository.deleteHabitWithChildren(parent, context) }

        coordinator.deleteWithChildren()

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabitWithChildren(parent, context) }
    }

    @Test
    fun keeping_children_orphans_them_and_clears_pending_state() = runTest {
        val parent = preparePendingDeletion()
        coJustRun { repository.deleteHabitOrphanChildren(parent, context) }

        coordinator.deleteKeepingChildren()

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabitOrphanChildren(parent, context) }
    }

    @Test
    fun failedCascadeRetainsConfirmationForRetry() = runTest {
        val parent = preparePendingDeletion()
        val pending = coordinator.pendingDeletion.value
        val failure = java.io.IOException("delete failed")
        coEvery { repository.deleteHabitWithChildren(parent, context) } throws failure
        assertEquals(failure, runCatching { coordinator.deleteWithChildren() }.exceptionOrNull())
        assertEquals(pending, coordinator.pendingDeletion.value)
        coJustRun { repository.deleteHabitWithChildren(parent, context) }
        coordinator.deleteWithChildren()
        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 2) { repository.deleteHabitWithChildren(parent, context) }
    }

    private suspend fun preparePendingDeletion(): HabitEntity {
        val parent = habit("parent")
        coEvery { repository.getHabitChildren(parent.uuid) } returns listOf(habit("child"))
        coordinator.requestDeletion(parent)
        return parent
    }

    private fun habit(uuid: String) = HabitEntity(
        id = uuid.hashCode().toLong(),
        uuid = uuid,
        name = uuid,
        habitType = HabitType.CHECK_IN,
        iconResId = 1,
        colorHex = "#2196F3",
        schedule = HabitSchedule.Daily,
        targetValue = 1
    )
}
