package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D-003: only top-level goals may parent basic habits; every rejected write is atomic. */
@RunWith(AndroidJUnit4::class)
class HabitHierarchyRepositoryTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private lateinit var repository: HabitRepository

    @Before fun setup() { bind() }

    private fun bind() {
        val db = storage.database
        repository = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db)
    }

    private suspend fun create(name: String, type: HabitType = HabitType.CHECK_IN,
        parent: String? = null, uuid: String? = null): Long = repository.createHabit(
        name, "", type, 1, "#2196F3", HabitSchedule.Daily,
        parentHabitId = parent, predefinedUuid = uuid)

    private suspend fun rejectedWithoutWrites(action: suspend () -> Unit) {
        val rows = storage.database.habitDao().getAllHabitsOnce()
        val outbox = storage.database.syncOutboxDao().getAll()
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("D-003 violation must be rejected, got $error", error is IllegalArgumentException)
        storage.reopen()
        bind()
        assertEquals(rows, storage.database.habitDao().getAllHabitsOnce())
        assertEquals(outbox, storage.database.syncOutboxDao().getAll())
    }

    @Test fun missingParentIsRejectedBeforeHabitAndOutboxWrite() = runBlocking {
        rejectedWithoutWrites { create("Child", parent = "missing") }
    }

    @Test fun ordinaryHabitCannotBecomeAParent() = runBlocking {
        val id = create("Basic")
        val basic = requireNotNull(repository.getHabitById(id))
        rejectedWithoutWrites { create("Child", parent = basic.uuid) }
    }

    @Test fun selfParentAndNestedGoalAreRejected() = runBlocking {
        rejectedWithoutWrites { create("Self", parent = "same", uuid = "same") }
        val goal = requireNotNull(repository.getHabitById(create("Goal", HabitType.GOAL)))
        rejectedWithoutWrites { create("Nested goal", HabitType.GOAL, parent = goal.uuid) }
    }

    @Test fun updatesCannotCreateSelfOrDescendantCycles() = runBlocking {
        val goal = requireNotNull(repository.getHabitById(create("Goal", HabitType.GOAL)))
        val child = requireNotNull(repository.getHabitById(create("Child", parent = goal.uuid)))
        rejectedWithoutWrites { repository.updateHabit(child.copy(parentHabitId = child.uuid)) }
        rejectedWithoutWrites { repository.updateHabit(goal.copy(parentHabitId = child.uuid)) }
    }

    @Test fun changingParentGoalIntoBasicHabitCannotInvalidateChildren() = runBlocking {
        val goal = requireNotNull(repository.getHabitById(create("Goal", HabitType.GOAL)))
        create("Child", parent = goal.uuid)
        rejectedWithoutWrites { repository.updateHabit(goal.copy(habitType = HabitType.CHECK_IN)) }
    }

    @Test fun validReparentAndDetachPersistWithTheirOutbox() = runBlocking {
        val first = requireNotNull(repository.getHabitById(create("First", HabitType.GOAL)))
        val second = requireNotNull(repository.getHabitById(create("Second", HabitType.GOAL)))
        val child = requireNotNull(repository.getHabitById(create("Child", parent = first.uuid)))
        repository.updateHabit(child.copy(parentHabitId = second.uuid))
        storage.reopen(); bind()
        assertEquals(second.uuid, repository.getHabitById(child.id)?.parentHabitId)
        repository.updateHabit(requireNotNull(repository.getHabitById(child.id)).copy(parentHabitId = null))
        storage.reopen(); bind()
        assertNull(repository.getHabitById(child.id)?.parentHabitId)
        assertEquals(3, storage.database.syncOutboxDao().getAll().count { it.entityUuid == child.uuid })
    }
}
