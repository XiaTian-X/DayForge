package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.FailMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same file/account/storage, different UUID at the same local ID. Real Room DTO reads and
 * real controlled writes form the race; there is no suspend mock/continuation interception.
 * The small DAO delegate pauses only the pre-transaction captured-row return, after a real
 * replacement committed. All original writer/trigger/footer calls remain concrete.
 */
@RunWith(AndroidJUnit4::class)
class LocalObjectIdentityWriteTest : NextObjectEditorFixture() {
    private suspend fun legacy() {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(0, count("sync_outbox"))
    }
    private fun habits(dao: HabitDao = db.habitDao()) = HabitRepository(dao, db.completionDao(), db.timeLogDao(), db)
    private fun metrics(dao: MetricDao = db.metricDao()) = MetricRepository(db, dao, db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao())
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun unchanged(block: suspend () -> Unit) {
        val before = image(); rejected { block() }; assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun staleMetricIdentityCannotUpdateDeleteAggregateOrLinkTheReplacement() = runBlocking<Unit> {
        legacy(); val repository = metrics(); val replacement = metric.copy(uuid = id(801))
        db.metricDao().update(replacement)
        unchanged { repository.updateMetric(metric.copy(name = "wrong object")) }
        unchanged { repository.deleteMetric(metric) }
        unchanged { repository.updateAggregationType(metric.id, "sum", expectedMetricUuid = metric.uuid) }
        unchanged { repository.linkHabits(metric, setOf(habit.id), expectedHabitUuids = mapOf(habit.id to habit.uuid)) }
        assertEquals(replacement, db.metricDao().getMetricById(metric.id))
        repository.updateMetric(replacement.copy(name = "right object"))
        assertEquals("right object", db.metricDao().getMetricById(metric.id)?.name)
        repository.deleteMetric(requireNotNull(db.metricDao().getMetricById(metric.id)))
        val afterDelete = image(); repository.deleteMetric(replacement); assertEquals(afterDelete, image())
    }

    @Test(timeout = 150_000) fun metricPreflightDtoCannotRetargetAfterItsOriginalRowWasReplaced() = runBlocking<Unit> {
        legacy()
        for ((index, aggregate) in listOf(false, true).withIndex()) {
            val original = requireNotNull(db.metricDao().getMetricById(metric.id))
            val replacement = original.copy(uuid = id(810 + index))
            val raw = db.metricDao(); var hits = 0
            var afterReplacement: kotlinx.serialization.json.JsonObject? = null
            val delegated = object : MetricDao by raw {
                override suspend fun getMetricById(id: Long): MetricEntity? {
                    val actual = raw.getMetricById(id)
                    if (id == original.id && hits == 0) {
                        check(!db.inTransaction()); hits++
                        raw.update(replacement)
                        afterReplacement = image()
                    }
                    return actual
                }
            }
            val repository = metrics(delegated)
            val failure = rejected {
                if (aggregate) repository.updateAggregationType(original.id, "sum", expectedMetricUuid = original.uuid)
                else repository.recordValue(original.id, 2.5, "ordinary input", expectedMetricUuid = original.uuid)
            }
            assertEquals(1, hits); assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", failure.message)
            assertEquals(requireNotNull(afterReplacement), image())
            assertEquals(replacement, raw.getMetricById(original.id)); assertEquals(0, count("metric_logs"))
        }
    }

    @Test(timeout = 150_000) fun staleHabitCannotMutateDeleteOrReactivateTheSameIdReplacement() = runBlocking<Unit> {
        legacy(); val repository = habits(); val request = repository.prepareReactivation(habit)
        val replacement = habit.copy(uuid = id(820)); db.habitDao().update(replacement)
        unchanged { repository.updateHabit(habit.copy(name = "wrong object")) }
        unchanged { repository.deleteHabit(habit) }
        unchanged { repository.deleteHabitWithChildren(habit) }
        unchanged { repository.deleteHabitOrphanChildren(habit) }
        unchanged { repository.clearHabitHistory(habit, app) }
        unchanged { repository.reactivate(request, app) }
        unchanged { repository.updateIsActive(habit.id, false, app, expectedHabitUuid = habit.uuid) }
        unchanged { repository.updateFailMode(habit.id, FailMode.LOOSE, app, expectedHabitUuid = habit.uuid) }
        assertEquals(replacement, db.habitDao().getHabitById(habit.id))
        repository.updateHabit(replacement.copy(name = "right object"))
        assertEquals("right object", db.habitDao().getHabitById(habit.id)?.name)
        repository.deleteHabit(requireNotNull(db.habitDao().getHabitById(habit.id)))
        val afterDelete = image(); repository.deleteHabit(replacement); assertEquals(afterDelete, image())
    }

    @Test(timeout = 150_000) fun habitPreflightDtoCannotRetargetActivationOrCount() = runBlocking<Unit> {
        legacy()
        for ((index, active) in listOf(false, true).withIndex()) {
            val original = requireNotNull(db.habitDao().getHabitById(habit.id))
            val replacement = original.copy(uuid = id(830 + index))
            val raw = db.habitDao(); var hits = 0
            var afterReplacement: kotlinx.serialization.json.JsonObject? = null
            val delegated = object : HabitDao by raw {
                override suspend fun getHabitById(id: Long): HabitEntity? {
                    val actual = raw.getHabitById(id)
                    if (id == original.id && hits == 0) {
                        check(!db.inTransaction()); hits++
                        raw.update(replacement)
                        afterReplacement = image()
                    }
                    return actual
                }
            }
            val repository = habits(delegated)
            val failure = rejected {
                if (active) repository.updateIsActive(original.id, false, app, expectedHabitUuid = original.uuid)
                else repository.logCompletion(app, original.id, expectedHabitUuid = original.uuid)
            }
            assertEquals(1, hits); assertEquals("OBJECT_WRITE_CHANGED_RELOAD_REQUIRED", failure.message)
            assertEquals(requireNotNull(afterReplacement), image()); assertEquals(replacement, raw.getHabitById(original.id))
            assertEquals(0, count("completions"))
        }
    }

    @Test(timeout = 150_000) fun completeSelectedIdentityMapsAreRecheckedAndLegalSelectionsStillCommit() = runBlocking<Unit> {
        legacy(); val h = habits(); val m = metrics()
        val newMetric = metric.copy(uuid = id(840)); db.metricDao().update(newMetric)
        unchanged { h.updateHabit(habit.copy(name = "must rollback"), selectedMetricIds = setOf(metric.id),
            expectedMetricUuids = mapOf(metric.id to metric.uuid)) }
        unchanged { h.updateHabit(habit, selectedMetricIds = setOf(metric.id), expectedMetricUuids = emptyMap()) }
        h.updateHabit(habit, selectedMetricIds = setOf(metric.id), expectedMetricUuids = mapOf(metric.id to newMetric.uuid))
        assertEquals(newMetric.uuid, db.habitMetricLinkDao().getLink(habit.id, metric.id)?.metricUuid)
        val newHabit = timerHabit.copy(uuid = id(841)); db.habitDao().update(newHabit)
        unchanged { m.linkHabits(newMetric, setOf(timerHabit.id), expectedHabitUuids = mapOf(timerHabit.id to timerHabit.uuid)) }
        unchanged { m.linkHabits(newMetric, setOf(timerHabit.id), expectedHabitUuids = emptyMap()) }
        m.linkHabits(newMetric, setOf(timerHabit.id), expectedHabitUuids = mapOf(timerHabit.id to newHabit.uuid))
        assertEquals(newHabit.uuid, db.habitMetricLinkDao().getLink(timerHabit.id, metric.id)?.habitUuid)
    }
}
