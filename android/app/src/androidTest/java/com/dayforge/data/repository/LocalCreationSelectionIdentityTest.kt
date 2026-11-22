package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real replacement rows in the same local storage; real original insert/link transactions.
 * These are legacy/fixed repository gates. Existing full typed creation tests remain required.
 */
@RunWith(AndroidJUnit4::class)
class LocalCreationSelectionIdentityTest : NextObjectEditorFixture() {
    private suspend fun legacy() {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private fun localHabits() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db)
    private fun localMetrics() = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun rejectedUnchanged(reason: String, block: suspend () -> Unit) {
        val before = image(); val failure = rejected(block)
        assertEquals(reason, failure.message); assertEquals(before, image())
    }
    private suspend fun createHabit(identities: Map<Long, String>?) = localHabits().createHabit("New identity-bound habit", "", HabitType.COUNTING,
        0, "#123456", HabitSchedule.Daily, selectedMetricIds = setOf(metric.id), expectedMetricUuids = identities)

    @Test(timeout = 150_000) fun newHabitCannotLinkReplacementAtTheOriginalMetricIdAndWholeInsertRollsBack() = runBlocking<Unit> {
        legacy(); val replacement = metric.copy(uuid = id(8801)); db.metricDao().update(replacement)
        rejectedUnchanged("OBJECT_WRITE_LINK_CHANGED") { createHabit(mapOf(metric.id to metric.uuid)) }
        rejectedUnchanged("OBJECT_WRITE_LINK_SELECTION_CHANGED") { createHabit(emptyMap()) }
        val created = createHabit(mapOf(replacement.id to replacement.uuid))
        assertEquals(replacement.uuid, db.habitMetricLinkDao().getLink(created, replacement.id)?.metricUuid)
        assertEquals(replacement, db.metricDao().getMetricById(metric.id))
    }

    @Test(timeout = 150_000) fun newMetricCannotLinkReplacementAtTheOriginalHabitIdAndOriginalNullApiStillWorks() = runBlocking<Unit> {
        legacy(); val replacement = habit.copy(uuid = id(8802)); db.habitDao().update(replacement)
        val fresh = metric.copy(id = 0, uuid = id(8803), name = "New identity-bound metric")
        rejectedUnchanged("OBJECT_WRITE_LINK_CHANGED") {
            localMetrics().createMetric(fresh, setOf(habit.id), expectedHabitUuids = mapOf(habit.id to habit.uuid))
        }
        rejectedUnchanged("OBJECT_WRITE_LINK_SELECTION_CHANGED") {
            localMetrics().createMetric(fresh, setOf(habit.id), expectedHabitUuids = emptyMap())
        }
        val created = localMetrics().createMetric(fresh, setOf(habit.id), expectedHabitUuids = mapOf(habit.id to replacement.uuid))
        assertEquals(replacement.uuid, db.habitMetricLinkDao().getLink(habit.id, created)?.habitUuid)
        val fixed = localMetrics().createMetric(fresh.copy(uuid = id(8804), name = "Original fixed API"), setOf(timerHabit.id))
        assertEquals(timerHabit.uuid, db.habitMetricLinkDao().getLink(timerHabit.id, fixed)?.habitUuid)
    }

    @Test(timeout = 150_000) fun wholeGoalUsesUnionOfOriginalChildSelectionsIncludingItsIdempotentReturn() = runBlocking<Unit> {
        legacy(); val replacement = metric.copy(uuid = id(8805)); db.metricDao().update(replacement)
        val goal = HabitDraft(id = id(8806), name = "Parent", habitType = HabitType.GOAL)
        val children = listOf(HabitDraft(id = id(8807), name = "Child one", habitType = HabitType.COUNTING, selectedMetricIds = setOf(metric.id)),
            HabitDraft(id = id(8808), name = "Child two", habitType = HabitType.CHECK_IN, selectedMetricIds = setOf(metric.id)))
        rejectedUnchanged("OBJECT_WRITE_LINK_CHANGED") {
            localHabits().createGoal(goal, children, expectedMetricUuids = mapOf(metric.id to metric.uuid))
        }
        rejectedUnchanged("OBJECT_WRITE_LINK_SELECTION_CHANGED") { localHabits().createGoal(goal, children, expectedMetricUuids = emptyMap()) }
        assertNull(db.habitDao().getHabitByUuid(goal.id)); children.forEach { assertNull(db.habitDao().getHabitByUuid(it.id)) }
        val expected = mapOf(metric.id to replacement.uuid)
        val created = localHabits().createGoal(goal, children, expectedMetricUuids = expected)
        val original = image(); assertEquals(created, localHabits().createGoal(goal, children, expectedMetricUuids = expected)); assertEquals(original, image())
        for (draft in children) {
            val child = requireNotNull(db.habitDao().getHabitByUuid(draft.id))
            assertEquals(goal.id, child.parentHabitId); assertEquals(replacement.uuid, db.habitMetricLinkDao().getLink(child.id, metric.id)?.metricUuid)
        }
        db.metricDao().update(replacement.copy(uuid = id(8809)))
        rejectedUnchanged("OBJECT_WRITE_LINK_CHANGED") { localHabits().createGoal(goal, children, expectedMetricUuids = expected) }
    }
}
