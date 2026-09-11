package com.dayforge.data.local.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HabitDaoTest {

    private lateinit var habitDao: HabitDao
    private lateinit var database: HabitDatabase

    @Before
    fun setup() {
        // Use in-memory database for test isolation
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HabitDatabase::class.java
        ).build()
        habitDao = database.habitDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertHabit_returnsGeneratedIdGreaterThanZero() = runTest {
        val habit = createTestHabit(name = "Test Habit")

        val insertedId = habitDao.insert(habit)

        assertTrue("Inserted ID should be greater than 0", insertedId > 0)
    }

    @Test
    fun getHabitById_returnsInsertedHabitWithSameProperties() = runTest {
        val habit = createTestHabit(name = "Test Habit", description = "Test Description")

        val insertedId = habitDao.insert(habit)
        val retrievedHabit = habitDao.getHabitById(insertedId)

        assertNotNull("Retrieved habit should not be null", retrievedHabit)
        assertEquals("Habit name should match", "Test Habit", retrievedHabit?.name)
        assertEquals("Habit description should match", "Test Description", retrievedHabit?.description)
        assertEquals("Habit type should match", HabitType.CHECK_IN, retrievedHabit?.habitType)
    }

    @Test
    fun getAllHabits_emitsListContainingInsertedHabit() = runTest {
        val habit = createTestHabit(name = "Test Habit")

        habitDao.insert(habit)

        val habits = habitDao.getAllHabits().first()

        assertEquals("Should have one habit", 1, habits.size)
        assertEquals("Habit name should match", "Test Habit", habits[0].name)
    }

    @Test
    fun updateHabit_changesPersistedData() = runTest {
        val habit = createTestHabit(name = "Original Name")
        val insertedId = habitDao.insert(habit)

        // Retrieve the inserted habit to get the correct ID and createdAt
        val insertedHabit = habitDao.getHabitById(insertedId)!!
        val updatedHabit = insertedHabit.copy(name = "Updated Name", updatedAt = System.currentTimeMillis())
        habitDao.update(updatedHabit)

        val retrievedHabit = habitDao.getHabitById(insertedId)

        assertEquals("Habit name should be updated", "Updated Name", retrievedHabit?.name)
        assertTrue("Updated timestamp should be later",
            (retrievedHabit?.updatedAt ?: 0) > (retrievedHabit?.createdAt ?: 0))
    }

    @Test
    fun deleteHabit_removesFromDatabase() = runTest {
        val habit = createTestHabit(name = "To Delete")
        val insertedId = habitDao.insert(habit)

        // Retrieve the inserted habit to get the correct ID
        val insertedHabit = habitDao.getHabitById(insertedId)!!
        habitDao.delete(insertedHabit)

        val retrievedHabit = habitDao.getHabitById(insertedId)
        val habits = habitDao.getAllHabits().first()

        assertNull("Deleted habit should be null", retrievedHabit)
        assertTrue("Habits list should be empty", habits.isEmpty())
    }

    // ========== Unique Constraint Tests (Task 13-01) ==========

    @Test
    fun insertDuplicateName_throwsSQLiteConstraintException() = runTest {
        val habit1 = createTestHabit(name = "Duplicate Name")
        habitDao.insert(habit1)

        val habit2 = createTestHabit(name = "Duplicate Name")

        var exceptionThrown = false
        try {
            habitDao.insert(habit2)
        } catch (e: SQLiteConstraintException) {
            exceptionThrown = true
        }
        assertTrue("Should throw SQLiteConstraintException for duplicate name", exceptionThrown)
    }

    @Test
    fun insertHabit_afterDeleteWithSameName_succeeds() = runTest {
        // Create and insert first habit
        val habit1 = createTestHabit(name = "Test Habit")
        val id1 = habitDao.insert(habit1)

        // Delete the habit
        val inserted = habitDao.getHabitById(id1)
        assertNotNull("Inserted habit should exist", inserted)
        habitDao.delete(inserted!!)

        // Create new habit with same name - should succeed
        val habit2 = createTestHabit(name = "Test Habit")
        val id2 = habitDao.insert(habit2)

        assertTrue("New habit should be inserted with valid ID", id2 > 0)
        val retrieved = habitDao.getHabitById(id2)
        assertEquals("Name should match", "Test Habit", retrieved?.name)
    }

    @Test
    fun insertHabit_withUniqueName_succeeds() = runTest {
        val habit1 = createTestHabit(name = "Unique Habit 1")
        val id1 = habitDao.insert(habit1)

        val habit2 = createTestHabit(name = "Unique Habit 2")
        val id2 = habitDao.insert(habit2)

        assertTrue("First habit ID should be valid", id1 > 0)
        assertTrue("Second habit ID should be valid", id2 > 0)
        assertNotEquals("IDs should be different", id1, id2)
    }

    private fun createTestHabit(
        name: String,
        description: String = "",
        habitType: HabitType = HabitType.CHECK_IN,
        iconResId: Int = 1,
        colorHex: String = "#2196F3",
        schedule: HabitSchedule = HabitSchedule.Daily,
        targetValue: Int = 1
    ): HabitEntity {
        return HabitEntity(
            name = name,
            description = description,
            habitType = habitType,
            iconResId = iconResId,
            colorHex = colorHex,
            schedule = schedule,
            targetValue = targetValue
        )
    }

    private fun createTestHabitWithParent(
        name: String,
        parentHabitId: String? = null,
        description: String = "",
        habitType: HabitType = HabitType.CHECK_IN,
        iconResId: Int = 1,
        colorHex: String = "#2196F3",
        schedule: HabitSchedule = HabitSchedule.Daily,
        targetValue: Int = 1
    ): HabitEntity {
        return HabitEntity(
            name = name,
            description = description,
            habitType = habitType,
            iconResId = iconResId,
            colorHex = colorHex,
            schedule = schedule,
            targetValue = targetValue,
            parentHabitId = parentHabitId
        )
    }

    // ========== Parent-Child DAO Tests (Task 42-01) ==========

    @Test
    fun getChildrenByParentUuid_returnsChildHabits() = runTest {
        // Create parent habit
        val parent = createTestHabit(name = "Parent Habit")
        val parentId = habitDao.insert(parent)
        val parentUuid = habitDao.getHabitById(parentId)!!.uuid

        // Create child habits with parent reference
        val child1 = createTestHabitWithParent(name = "Child 1", parentHabitId = parentUuid)
        val child2 = createTestHabitWithParent(name = "Child 2", parentHabitId = parentUuid)
        habitDao.insert(child1)
        habitDao.insert(child2)

        // Query for children
        val children = habitDao.getChildrenByParentUuid(parentUuid).first()

        assertEquals("Should return 2 child habits", 2, children.size)
        assertTrue("Children should have correct parent UUID",
            children.all { it.parentHabitId == parentUuid })
    }

    @Test
    fun getChildrenByParentUuid_returnsEmptyList_whenNoChildren() = runTest {
        val nonExistentUuid = "non-existent-uuid-12345"

        val children = habitDao.getChildrenByParentUuid(nonExistentUuid).first()

        assertTrue("Should return empty list when no children exist", children.isEmpty())
    }

    @Test
    fun getTopLevelHabits_returnsOnlyNullParentHabits() = runTest {
        // Create 2 top-level habits (no parent)
        val topLevel1 = createTestHabit(name = "Top Level 1")
        val topLevel2 = createTestHabit(name = "Top Level 2")
        habitDao.insert(topLevel1)
        habitDao.insert(topLevel2)

        // Create parent and child habit
        val parent = createTestHabit(name = "Parent Habit")
        val parentId = habitDao.insert(parent)
        val parentUuid = habitDao.getHabitById(parentId)!!.uuid
        val child = createTestHabitWithParent(name = "Child Habit", parentHabitId = parentUuid)
        habitDao.insert(child)

        // Query for top-level habits
        val topLevelHabits = habitDao.getTopLevelHabits().first()

        assertEquals("Should return only top-level habits (parentHabitId is null)", 3, topLevelHabits.size)
        assertTrue("All returned habits should have null parentHabitId",
            topLevelHabits.all { it.parentHabitId == null })
    }

    @Test
    fun getTopLevelHabits_excludesChildHabits() = runTest {
        // Create parent habit
        val parent = createTestHabit(name = "Parent Habit")
        val parentId = habitDao.insert(parent)
        val parentUuid = habitDao.getHabitById(parentId)!!.uuid

        // Create child habit
        val child = createTestHabitWithParent(name = "Child Habit", parentHabitId = parentUuid)
        habitDao.insert(child)

        // Query for top-level habits
        val topLevelHabits = habitDao.getTopLevelHabits().first()

        assertEquals("Should exclude child habits", 1, topLevelHabits.size)
        assertEquals("Should be the parent habit", "Parent Habit", topLevelHabits[0].name)
    }

    @Test
    fun updateParentHabitId_setsParentForHabit() = runTest {
        // Create parent habit
        val parent = createTestHabit(name = "Parent Habit")
        val parentId = habitDao.insert(parent)
        val parentUuid = habitDao.getHabitById(parentId)!!.uuid

        // Create child habit without parent
        val child = createTestHabit(name = "Child Habit")
        val childId = habitDao.insert(child)

        // Update child to have parent
        habitDao.updateParentHabitId(childId, parentUuid)

        // Verify parent is set
        val updatedChild = habitDao.getHabitById(childId)
        assertEquals("Child should have parent UUID set", parentUuid, updatedChild?.parentHabitId)
    }

    @Test
    fun updateParentHabitId_clearsParent_whenSetToNull() = runTest {
        // Create parent habit
        val parent = createTestHabit(name = "Parent Habit")
        val parentId = habitDao.insert(parent)
        val parentUuid = habitDao.getHabitById(parentId)!!.uuid

        // Create child habit with parent
        val child = createTestHabitWithParent(name = "Child Habit", parentHabitId = parentUuid)
        val childId = habitDao.insert(child)

        // Clear parent by setting to null
        habitDao.updateParentHabitId(childId, null)

        // Verify parent is cleared
        val updatedChild = habitDao.getHabitById(childId)
        assertNull("Child should have no parent (null)", updatedChild?.parentHabitId)
    }

    @Test
    fun updateParentHabitId_updatesTimestamp() = runTest {
        // Create habit
        val habit = createTestHabit(name = "Test Habit")
        val habitId = habitDao.insert(habit)
        val originalHabit = habitDao.getHabitById(habitId)!!
        val originalUpdatedAt = originalHabit.updatedAt

        // Small delay to ensure timestamp difference
        Thread.sleep(10)

        // Update parent
        val newParentUuid = "new-parent-uuid"
        habitDao.updateParentHabitId(habitId, newParentUuid)

        // Verify timestamp updated
        val updatedHabit = habitDao.getHabitById(habitId)!!
        assertTrue("updatedAt should be updated",
            updatedHabit.updatedAt > originalUpdatedAt)
    }
}
