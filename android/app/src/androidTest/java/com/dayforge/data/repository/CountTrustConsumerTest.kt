package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CountSummary
import com.dayforge.util.DateTimeUtils
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real provider: current action/summary readers select precise facts; explicit detail remains full. */
@RunWith(AndroidJUnit4::class)
class CountTrustConsumerTest : NextObjectEditorFixture() {
    private val countScans = AtomicInteger()
    private val allScans = AtomicInteger()
    private val completeProofReads = AtomicInteger()
    private var countId = -1L
    init {
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val sql = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ")
                if (sql.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,")) completeProofReads.incrementAndGet()
                if (sql.startsWith("select * from completions order by")) allScans.incrementAndGet()
                if ("from completions where" in sql && Regex("where habitid\\s*=\\s*\\?").containsMatchIn(sql) &&
                    (bindArgs.firstOrNull() as? Number)?.toLong() == countId) countScans.incrementAndGet()
            }
        }) }
    }

    private suspend fun append(index: Int, date: LocalDate): CompletionEntity {
        val zone = ZoneId.systemDefault()
        val instant = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(40_000 + index), value = index,
            date = date.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = instant, createdAt = instant,
            recordedLocalDate = date.toString(), recordedTimezone = zone.id)
        producer().write(local()) { NextCountDayStore(db).capture(habit, fact); db.completionDao().insertForSync(fact) }
        return requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid))
    }
    private suspend fun ready(): List<CompletionEntity> {
        register(); countId = habit.id
        val today = DateTimeUtils.today()
        val facts = listOf(append(1, today.minusMonths(13)), append(2, today.minusDays(1)), append(3, today))
        CountHistoryReader(db, tokens, sessions).readSummary(habit, today)
        db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
        }
        countScans.set(0); allScans.set(0)
        return facts
    }
    private fun widgets() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))

    /** Independent original validator; never consults the persistent count root. */
    private suspend fun originalSummary(today: LocalDate): CountSummary {
        val source = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, today)
        val quantities = source.completions.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { total, row -> Math.addExact(total, row.value.toLong()) }
        }
        return CountHistory(source.today, source.policies, quantities,
            source.policies[source.today] ?: if (source.today in source.unknownDates) null
                else CountDayPolicy(habit.targetValue, habit.isCountdown),
            source.unknownDates, source.completions, source.roundHead, source.countIdentity).summary
    }

    private fun cleanRoot(): CountTrustSeal.Header {
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
        }
        return requireNotNull(CountTrustSeal.read(sql))
    }

    @Test fun undoLastConvertedCheckInRestoresDefaultPolicyWithoutRequalifyingOrBlockingNextCount() = runBlocking<Unit> {
        register(); countId = habit.id
        val today = DateTimeUtils.today()
        // Produce a genuine pre-conversion check-in. Its original command intentionally has no
        // count_policy; the conversion must not invent one for this historical source.
        producer().write(local()) { db.habitDao().update(habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1)) }
        habit = requireNotNull(db.habitDao().getHabitById(habit.id))
        val zone = ZoneId.systemDefault()
        val instant = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val old = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(45_001), value = 1,
            date = today.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = instant, createdAt = instant,
            recordedLocalDate = today.toString(), recordedTimezone = zone.id)
        producer().write(local()) { db.completionDao().insertForSync(old) }
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(old.uuid))
        val checkInRequest = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        val checkInOrigin = originalIntent(checkInRequest)
        val checkInPayload = decodeNextOperationIntent(checkInOrigin.intentJson).payload
        assertEquals(JsonPrimitive("check_in"), checkInPayload["event_type"])
        assertFalse(checkInPayload.containsKey("count_policy"))
        producer().write(local()) { db.habitDao().update(habit.copy(habitType = HabitType.COUNTING, targetValue = 10)) }
        habit = requireNotNull(db.habitDao().getHabitById(habit.id))

        val counts = CountHistoryReader(db, tokens, sessions)
        val before = counts.readSummary(habit, today)
        assertEquals(1L, before.todayQuantity)
        assertEquals(setOf(today), before.unknownDates)
        assertNull(before.todayPolicy)
        assertEquals(originalSummary(today), before)
        val beforeRoot = cleanRoot()
        completeProofReads.set(0); countScans.set(0)

        // The real local producer freezes the new revert and preserves the old source. Keeping
        // this within its fact ingress excludes the repository's unrelated activity-rate update.
        producer().write(local()) { db.completionDao().delete(fact) }
        val undoRoot = cleanRoot()
        assertEquals(beforeRoot.revision + 1, undoRoot.revision)
        assertEquals(checkInOrigin, originalIntent(checkInRequest))
        val undoRequest = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid && it.action == "delete" }
        val undo = decodeNextOperationIntent(originalIntent(undoRequest).intentJson)
        assertEquals(JsonPrimitive("revert"), undo.payload["event_type"])
        assertEquals(JsonPrimitive(fact.uuid), undo.payload["reverts_event_uuid"])
        assertNull(db.completionDao().getCompletionByUuid(fact.uuid))

        val after = counts.readSummary(habit, today)
        assertEquals(0L, after.todayQuantity)
        assertTrue(after.unknownDates.isEmpty())
        assertEquals(CountDayPolicy(10, false), after.todayPolicy)
        assertTrue(after.policies.isEmpty())
        assertEquals(after, creatingHabits().prepareCompletion(habit.id).evidence!!.countSummary)
        assertEquals(undoRoot, cleanRoot())
        assertEquals("The valid undo and consumer reads must retain the incremental root", 0, completeProofReads.get())
        assertEquals(0, countScans.get())
        assertEquals(originalSummary(today), after)
        assertTrue("The independent original oracle must actually execute its full proof", completeProofReads.get() > 0)

        completeProofReads.set(0); countScans.set(0)
        val next = append(1, today)
        val nextRoot = cleanRoot()
        assertEquals(undoRoot.revision + 1, nextRoot.revision)
        // Starting the new day rule legitimately probes that one day's remaining facts.
        // Consumer reads must not need the original lifetime scan afterward.
        countScans.set(0)
        val current = counts.readSummary(habit, today)
        assertEquals(1L, current.todayQuantity)
        assertEquals(CountDayPolicy(10, false), current.todayPolicy)
        assertTrue(current.unknownDates.isEmpty())
        assertEquals(next.id, requireNotNull(current.todayLatestCompletionId))
        assertEquals(nextRoot, cleanRoot())
        assertEquals("The next legitimate count must not need a complete requalification", 0, completeProofReads.get())
        assertEquals(0, countScans.get())
        assertEquals(originalSummary(today), current)
    }

    @Test fun dashboardActionAndWidgetUsePreciseSelectionWhileExplicitDetailRetainsEveryFact() = runBlocking<Unit> {
        val facts = ready(); val today = DateTimeUtils.today()
        val counts = CountHistoryReader(db, tokens, sessions)
        val dashboard = DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions),
            counts, CheckHistoryReader(db, tokens, sessions)).read(ZonedDateTime.now())
        assertTrue(dashboard.completions.none { it.habitId == habit.id })
        assertEquals(6L, dashboard.counts.getValue(habit.id).totalQuantity)
        val action = creatingHabits().prepareCompletion(habit.id).evidence!!
        assertNull("A summary cannot pretend to contain full details", action.countHistory)
        assertEquals(dashboard.counts.getValue(habit.id), action.countSummary)
        assertEquals(facts.last().uuid, action.followUp.claim.completionUuid)
        val widget = widgets().read(habit)
        assertEquals(action.countSummary, widget.count)
        assertEquals(facts.last().uuid, widget.claim.completionUuid)
        assertEquals(0, countScans.get()); assertEquals(0, allScans.get())
        val detail = counts.read(habit, today)
        assertEquals(facts.map { it.uuid }.toSet(), detail.completions.map { it.uuid }.toSet())
        assertEquals(action.countSummary, detail.summary)
        assertTrue("Explicit details still use complete month membership", countScans.get() > 0)
        val publicDetail = creatingHabits().getRecurringSnapshot(habit)
        assertEquals(detail, publicDetail.countHistory)
    }

    @Test fun recordedSourceKeepsItsOwnDayAndIdentityInsteadOfAdoptingTheNewestFact() = runBlocking<Unit> {
        val facts = ready(); val source = facts[1]; val repository = creatingHabits()
        val captured = repository.prepareCompletion(habit.id, includeHistory = false)
        val after = repository.readCompletionAfter(captured, false, source.id)
        assertNull(after.countHistory)
        assertEquals(DateTimeUtils.today(), after.countSummary!!.today)
        assertEquals(source.recordedLocalDate, after.followUp.claim.date)
        assertEquals(source.uuid, after.followUp.claim.completionUuid)
        assertEquals(source.recordedTimezone, after.followUp.claim.timezone)
        assertEquals(LocalDate.parse(source.recordedLocalDate), after.followUp.count!!.today)
        assertEquals(2L, after.followUp.count.todayQuantity)
        assertEquals(0, countScans.get())
        widgets().readCompletionPlanAndPublish(after.followUp, read = { current, _ -> current.claim }) {
            assertEquals(source.uuid, it.completionUuid)
        }
        assertEquals(0, countScans.get())
        producer().write(local()) { db.completionDao().delete(source) }
        assertEquals("COMPLETION_SOURCE_CHANGED", rejected {
            widgets().readCompletionPlanAndPublish(after.followUp, read = { _, _ -> Unit }) { fail("Removed source published") }
        }.message)
    }

    @Test fun dirtyFallbackPreservesOriginalCompleteEvidenceAndNewAccountCannotAdoptSource() = runBlocking<Unit> {
        ready(); val view = widgets().read(habit)
        db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE metrics SET updatedAt=updatedAt WHERE id=?", arrayOf(metric.id)) }
        countScans.set(0)
        val fallback = widgets().read(habit)
        assertEquals(view.count, fallback.count); assertEquals(view.claim, fallback.claim)
        assertTrue("Dirty state must take the original complete evidence path", countScans.get() > 0)
        tokens.saveLoginSession("new-session", "new-refresh", "member", id(99), false)
        assertNotNull(rejected { widgets().requireCurrent(view.claim) })
    }
}
