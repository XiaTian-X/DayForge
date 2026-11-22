package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual original producer/trigger/native journal supplies the independent oracle. This
 * class tests the pure complete plan, not the not-yet-integrated short-TX executor.
 */
@RunWith(AndroidJUnit4::class)
class CountAddNativePlanTest : CountTrustFormalProfileFixture() {
    private class Rollback : RuntimeException()
    private val sql get() = db.openHelper.writableDatabase

    private suspend fun plan(context: CountTrustKernel.Context, habit: HabitEntity, at: Instant,
        mutateCallerInputs: Boolean = false): CountAddNativePlan {
        val date = at.atZone(ZoneId.systemDefault()).toLocalDate(); val month = YearMonth.from(date)
        val nativeHabit = context.checked(CountTrustMembership.key("habit.uuid", habit.uuid),
            "habits", "uuid=?", arrayOf(habit.uuid)).single()
        val days = context.checked(CountTrustMembership.key("day.month", habit.uuid, month.toString()),
            "count_days", "habitId=? AND localDate>=? AND localDate<?",
            arrayOf<Any>(habit.id, month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString()))
        val day = days.singleOrNull { it.day().localDate == date.toString() }
        val head = requireNotNull(context.head(habit.uuid))
        val known = context.known(habit.uuid).map { requireNotNull(it) }
        val touched = if (day == null) known else listOf(head)
        val summaries = touched.associateWith {
            context.index.get(CountTrustRoundEvidence.summaryKey(habit.uuid, month, it))?.jsonObject
        }
        val months = touched.associateWith {
            context.members(CountTrustRoundEvidence.monthsKey(habit.uuid, it))
        }
        val layouts = listOf("habits", "completions", "count_days", "sync_outbox", "next_request_origins")
            .associateWith(context.rows::table)
        val mutableColumns = nativeHabit.table.columns.toMutableList()
        val nativeAlias = CountTrustRows.Row(nativeHabit.table.copy(columns = mutableColumns), nativeHabit.cells)
        val nested = linkedMapOf<com.dayforge.domain.model.ChallengeRoundHead, MutableMap<String, JsonElement>>()
        val mutableSummaries = summaries.mapValues { (partition, value) -> value?.let {
            val quantities = it.getValue("quantities").jsonObject.toMutableMap()
            nested[partition] = quantities
            JsonObject(it.toMutableMap().apply { put("quantities", JsonObject(quantities)) })
        } }.toMutableMap()
        val mutableMonths = months.mapValues { it.value.toMutableList() }.toMutableMap()
        val mutableLayouts = layouts.toMutableMap()
        val prepared = requireNotNull(CountAddNativePlan.prepare(context.access, habit, nativeAlias, at,
            ZoneId.systemDefault(), head, day, days, mutableSummaries, known, mutableMonths, mutableLayouts,
            NextRequestSql.watermark(sql, "sync_outbox")))
        if (mutateCallerInputs) {
            assertTrue("This case must exercise a real nested existing calculation", nested.isNotEmpty())
            mutableColumns.clear()
            nested.values.forEach { it[date.toString()] = JsonPrimitive("not-an-integer") }
            mutableSummaries.clear(); mutableLayouts.clear()
            mutableMonths.values.forEach { it += "1999-01" }
            mutableMonths.clear()
        }
        return prepared
    }

    private suspend fun originalAndCompare(habit: HabitEntity, extraNoop: Boolean,
        at: Instant = Instant.now(), mutateCallerInputs: Boolean = false, delegatedBoundary: Boolean = false) {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val scope = requireNotNull(authority.rounds)
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
        db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(access) { beforeContext ->
                val before = requireNotNull(beforeContext)
                val prepared = plan(before, habit, at, mutateCallerInputs)
                assertFalse(prepared.noEffect)
                val stage = prepared.beginAtOriginalConstructionPoint(UUID.randomUUID().toString(), System.currentTimeMillis())
                val boundary = runtime.maintenance(db) { runtime.captureChangeBoundary() }
                var rate: CountTrustKernel.RateCalculation? = null
                val inserted = producer().writeRounds(scope) {
                    // Original day, DAO, rate and outer producer; the new plan does not execute SQL.
                    NextCountDayStore(db).capture(habit, stage.completion)
                    val id = db.completionDao().insert(stage.completion)
                    val actualHabit = requireNotNull(db.habitDao().getHabitById(habit.id))
                    val current = requireNotNull(CountTrustKernel.preparedActivityRate(db, actualHabit))
                    rate = current
                    db.habitDao().updateActivityRate(habit.id, current.rate, current.instant)
                    id
                }
                val source = db.syncOutboxDao().getAll().single { it.entityUuid == stage.completion.uuid }
                val expected = stage.bind(inserted, source.id, source.operationId, source.createdAt, requireNotNull(rate))
                assertEquals(expected.source, source)
                assertEquals(expected.origin, db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
                assertEquals(CountAddOriginHash.hash(expected.origin), NextRequestSql.rowHash(db,
                    "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, source.operationId)))
                val actual = runtime.maintenance(db) { runtime.pendingChanges(boundary) }
                val projected = runtime.maintenance(db) { runtime.readCountAddEffects(boundary, expected.journalExpectation()) }
                assertEquals("Bounded actual cells must agree with the independent original full journal", actual, projected)
                expected.verifyActual(actual)
                expected.verifyFinalContents(sql)
                val keys = expected.requiredKeys()
                val patch = expected.edits(keys.associateWith { before.index.get(it) })
                CountTrustKernel(db, tokens, sessions).withOpen(access) { after ->
                    val current = requireNotNull(after)
                    for ((key, value) in patch) assertEquals("The independent successor must equal original root key $key",
                        value, current.index.get(key))
                }
                if (delegatedBoundary) {
                    val refused = supervisorScope {
                        val child = async {
                            runtime.maintenance(db) { runtime.readCountAddEffects(boundary, expected.journalExpectation()) }
                        }
                        try { child.await(); null } catch (error: IllegalStateException) { error }
                    }
                    assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", requireNotNull(refused).message)
                    throw Rollback()
                }
                if (extraNoop) {
                    sql.execSQL("UPDATE habits SET activityRate=activityRate WHERE id=?", arrayOf(habit.id))
                    val extra = runtime.maintenance(db) { runtime.pendingChanges(boundary) }
                    val failure = try { expected.verifyActual(extra); null } catch (error: IllegalStateException) { error }
                    assertEquals("COUNT_ADD_EXTRA_EFFECT", requireNotNull(failure).message)
                    throw Rollback()
                }
                val saved = requireNotNull(db.habitDao().getHabitById(habit.id))
                val dates = db.completionDao().getByHabitOnce(habit.id).map { LocalDate.parse(it.recordedLocalDate) }
                assertEquals(ActivityRateCalculator.calculate(saved.schedule, saved.createdAt, dates,
                    saved.activityRateUpdatedAt, ZoneId.systemDefault()), saved.activityRate)
            }
        }
    }

    @Test fun firstDayFullNativeEffectsAndEveryCalculationEqualOriginalProducer() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        originalAndCompare(habit, false)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun existingDayFullNativeEffectsAndEveryCalculationEqualOriginalProducer() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val at = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        originalAndCompare(habit, false, at)
        originalAndCompare(requireNotNull(db.habitDao().getHabitById(habit.id)), false, at.plusSeconds(1))
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun callerOwnedCollectionsAndNestedJsonCannotChangePreparedNativeOrCalculationResults() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val at = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        originalAndCompare(habit, false, at)
        originalAndCompare(requireNotNull(db.habitDao().getHabitById(habit.id)), false, at.plusSeconds(1), true)
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun extraRealNoopCannotBeClaimedByPlanAndEntireTransactionRollsBack() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = images.capture()
        val failure = try { originalAndCompare(habit, true); null } catch (error: Rollback) { error }
        assertTrue(failure is Rollback)
        images.assertUnchanged(before)
    }

    @Test fun closedCountProjectionCannotBorrowAnOuterJobBoundary() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        val rejected = try { originalAndCompare(habit, false, delegatedBoundary = true); false }
            catch (_: Rollback) { true }
        assertTrue(rejected)
        images.assertUnchanged(before)
    }

    @Test fun unrelatedActualSendAndAckCanAdvanceRootWithoutInvalidatingExactReadSet() = runBlocking<Unit> {
        val habits = openCountAddPlanInputs()
        val target = habits.first(); val other = habits.last()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        var previousRevision = -1L
        val frozen = db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
            val current = requireNotNull(context); previousRevision = current.header.revision
            val builder = CountAddReadSet.Builder.create(owner, current)
            builder.leaf(CountTrustRoundEvidence.headKey(target.uuid))
            builder.rows(CountTrustMembership.key("habit.uuid", target.uuid), "habits", mapOf("uuid" to target.uuid))
            builder.rows(CountTrustMembership.key("challenge.id", "1"), "next_challenge_state", mapOf("id" to 1),
                markerProjection = true)
            builder.seal()
        } }
        creatingHabits().logCompletion(app, other.id)
        val source = db.syncOutboxDao().getAll().single { it.referenceUuid == other.uuid }
        val request = countAddPlanSender()
        val delivery = requireNotNull(request.sendOperation(access(), source.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, request.acceptOperation(delivery))
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
            val current = requireNotNull(context)
            assertTrue(current.header.revision > previousRevision)
            assertTrue(frozen.matchesCurrent(owner, current))
        } }
    }

    @Test fun realTargetChangeInvalidatesReadSetWithoutPretendingAuthorityExpired() = runBlocking<Unit> {
        val target = openCountAddPlanInputs().first()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val frozen = db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
            val builder = CountAddReadSet.Builder.create(owner, requireNotNull(context))
            builder.rows(CountTrustMembership.key("habit.uuid", target.uuid), "habits", mapOf("uuid" to target.uuid))
            builder.leaf(CountTrustRoundEvidence.monthsKey(target.uuid, requireNotNull(context).head(target.uuid)))
            builder.seal()
        } }
        creatingHabits().logCompletion(app, target.id)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
            assertFalse(frozen.matchesCurrent(owner, requireNotNull(context)))
        } }
    }
}
