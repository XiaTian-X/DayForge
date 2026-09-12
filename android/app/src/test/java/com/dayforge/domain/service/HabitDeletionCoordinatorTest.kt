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
    fun `leaf habit is deleted without opening confirmation`() = runTest {
        val habit = habit("parent")
        coEvery { repository.getHabitChildren(habit.uuid) } returns emptyList()
        coJustRun { repository.deleteHabit(habit, context) }

        coordinator.requestDeletion(habit)

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabit(habit, context) }
    }

    @Test
    fun `parent habit opens confirmation with direct child count`() = runTest {
        val parent = habit("parent")
        coEvery { repository.getHabitChildren(parent.uuid) } returns
            listOf(habit("child-1"), habit("child-2"))

        coordinator.requestDeletion(parent)

        assertEquals(PendingHabitDeletion(parent, 2), coordinator.pendingDeletion.value)
        coVerify(exactly = 0) { repository.deleteHabit(any(), any()) }
    }

    @Test
    fun `confirmed cascade deletion clears pending state`() = runTest {
        val parent = preparePendingDeletion()
        coJustRun { repository.deleteHabitWithChildren(parent, context) }

        coordinator.deleteWithChildren()

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabitWithChildren(parent, context) }
    }

    @Test
    fun `keeping children orphans them and clears pending state`() = runTest {
        val parent = preparePendingDeletion()
        coJustRun { repository.deleteHabitOrphanChildren(parent, context) }

        coordinator.deleteKeepingChildren()

        assertNull(coordinator.pendingDeletion.value)
        coVerify(exactly = 1) { repository.deleteHabitOrphanChildren(parent, context) }
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
