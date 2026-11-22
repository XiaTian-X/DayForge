package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room DAO/trigger/journal differential for the independent legacy effect plan.
 * This is a plan gate, not a claim that the durable legacy producer/Widget is connected.
 */
@RunWith(AndroidJUnit4::class)
class LegacyCountEffectPlanTest : NextCoreRequestFixture() {
    private class Rollback : RuntimeException()
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    private fun native() = CountTrustRows(sql)
    private val zone = ZoneId.of("UTC")
    private val rate = LegacyCountEffectPlan.RateAllocation(12, 1770000012345L)
    private suspend fun legacy(active: Boolean = true) {
        db.withTransaction {
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, isActive = active,
                activityRate = rate.value, activityRateUpdatedAt = rate.updateAt)
            db.habitDao().update(habit)
        }
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun generation() = db.withTransaction { sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    } }
    private fun allocations(watermark: Long): List<LegacyCountEffectPlan.SourceAllocation> = sql.query(
        "SELECT id,operationId,createdAt FROM sync_outbox WHERE id>? ORDER BY id LIMIT 3", arrayOf(watermark)).use { c ->
        buildList { while (c.moveToNext()) add(LegacyCountEffectPlan.SourceAllocation(c.getLong(0), c.getString(1), c.getLong(2))) }
    }
    private fun verifyNative(bound: LegacyCountEffectPlan.Bound) {
        val native = native()
        bound.verifyFinal(bound.finalRows.mapValues { (name, after) ->
            val original = after ?: requireNotNull(bound.effects.first { it.before?.name == name }.before)
            native.byIdentity(original.table.name, original.identity)
        })
    }
    private suspend fun add(active: Boolean) {
        legacy(active)
        val before = image()
        try {
            db.withTransaction<Unit> {
                val raw = native(); val old = raw.read("habits", "id=?", arrayOf(habit.id)).single()
                val watermark = NextRequestSql.watermark(sql, "sync_outbox")
                val plan = LegacyCountEffectPlan.add(old,
                    LegacyCountEffectPlan.Action.Add(id(65101), Instant.parse(time), zone, 1),
                    raw.table("completions"), raw.table("sync_outbox"), watermark)
                val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
                val activation = if (!active) millis + 11 else null
                if (activation != null) db.habitDao().updateIsActive(habit.id, true, activation)
                val fact = plan.completionAtOriginalConstruction(millis + 12)
                val inserted = db.completionDao().insert(fact)
                db.habitDao().updateActivityRate(habit.id, rate.value, rate.updateAt)
                val bound = plan.bind(fact, inserted, activation, allocations(watermark), rate)
                val changes = owner.maintenance(db) { owner.pendingChanges(boundary) }
                assertEquals(if (active) 3 else 5, changes.size)
                bound.verifyActual(changes); verifyNative(bound)
                assertEquals(fact.copy(id = inserted), db.completionDao().getCompletionById(inserted))
                assertEquals(if (active) 1 else 2, allocations(watermark).size)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun actualActiveAddMatchesAllThreeOldNewEventsAndRollsBack() = runBlocking<Unit> { add(true) }

    @Test(timeout = 150_000) fun actualInactiveAddMatchesActivationAndBothTriggerSources() = runBlocking<Unit> { add(false) }

    @Test(timeout = 150_000) fun actualDeleteRetainsNullOldHabitUuidAndOriginalTriggerCoalesce() = runBlocking<Unit> {
        legacy()
        val target = CompletionEntity(habitId = habit.id, habitUuid = null, uuid = id(65102), date = millis,
            actualCompletedAt = millis, createdAt = millis, recordedTimezone = zone.id)
        val inserted = db.withTransaction { db.completionDao().insert(target) }
        val selected = requireNotNull(db.completionDao().getCompletionById(inserted))
        val before = image()
        try {
            db.withTransaction<Unit> {
                val raw = native(); val old = raw.read("habits", "id=?", arrayOf(habit.id)).single()
                val targetRow = raw.read("completions", "id=?", arrayOf(inserted)).single()
                val watermark = NextRequestSql.watermark(sql, "sync_outbox")
                val plan = LegacyCountEffectPlan.delete(old, targetRow, raw.table("sync_outbox"), watermark)
                val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
                db.completionDao().delete(selected)
                db.habitDao().updateActivityRate(habit.id, rate.value, rate.updateAt)
                val bound = plan.bind(null, null, null, allocations(watermark), rate)
                bound.verifyActual(owner.maintenance(db) { owner.pendingChanges(boundary) }); verifyNative(bound)
                assertNull(db.completionDao().getCompletionById(inserted))
                val source = requireNotNull(db.syncOutboxDao().getById(allocations(watermark).single().id))
                assertEquals("delete", source.action); assertEquals(habit.uuid, source.referenceUuid)
                assertNull(selected.habitUuid)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        assertEquals(before, image())
    }

    @Test(timeout = 150_000) fun omittedRequiredNoopCannotBeHiddenByEqualGenerationAndEqualTargetRows() = runBlocking<Unit> {
        legacy(); val before = image(); val oldGeneration = generation()
        try {
            db.withTransaction<Unit> {
                val raw = native(); val old = raw.read("habits", "id=?", arrayOf(habit.id)).single()
                val watermark = NextRequestSql.watermark(sql, "sync_outbox")
                val plan = LegacyCountEffectPlan.add(old,
                    LegacyCountEffectPlan.Action.Add(id(65103), Instant.parse(time), zone, 1),
                    raw.table("completions"), raw.table("sync_outbox"), watermark)
                val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
                val fact = plan.completionAtOriginalConstruction(millis + 12)
                val inserted = db.completionDao().insert(fact)
                // Omit the required same-value habit UPDATE and replace it with unrelated
                // same-value metric DML: count and every expected final target still agree.
                sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
                val bound = plan.bind(fact, inserted, null, allocations(watermark), rate)
                val changes = owner.maintenance(db) { owner.pendingChanges(boundary) }
                assertEquals(3, changes.size)
                assertEquals(oldGeneration + 3, generation())
                verifyNative(bound)
                val failure = try { bound.verifyActual(changes); null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: IllegalStateException) { error }
                assertEquals("COMMAND_LEGACY_UNEXPECTED_OLD_NEW_EFFECT", failure?.message)
                throw Rollback()
            }
        } catch (_: Rollback) { }
        assertEquals(before, image()); assertEquals(oldGeneration, generation())
    }
}
