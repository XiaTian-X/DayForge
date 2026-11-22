package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real consumers/Room/Keystore; original full CHECK reads remain the independent oracle.
 * A positive-calibrated DAO counter observes only whole per-habit detail fetches, not all SQL.
 */
@RunWith(AndroidJUnit4::class)
class CheckProjectionConsumerTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.check-projection.${UUID.randomUUID()}"
    private val watched = AtomicLong(-1)
    private val fullDetails = AtomicInteger()
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val normalized = sqlQuery.replace("`", "").lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ").trim()
                if (Regex("^select \\* from completions where habitid\\s*=\\s*\\? order by .+").matches(normalized) &&
                    (bindArgs.firstOrNull() as? Number)?.toLong() == watched.get()) fullDetails.incrementAndGet()
            }
        }
    }
    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher(alias)
    @After fun drainOwnedMethodBeforeEditorCleanup() = runBlocking<Unit> {
        // Subclass @After runs before the inherited editor/DS cleanup. Do not close those
        // resources while an original timed-out method still owns real Room work.
        storage.recoveryCleanup {
            storage.afterDatabaseClose { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
        }
    }
    private fun checks() = CheckHistoryReader(db, tokens, sessions)
    private fun counts() = CountHistoryReader(db, tokens, sessions)
    private fun facts() = WidgetFactReader(db, tokens, sessions, preferences, counts(), checks())
    private fun repository() = creatingHabits()
    private fun service() = CheckInService(repository(), db.completionDao(), db.timeLogDao())
    private fun dashboard() = DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), counts(), checks())
    private fun status() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts(), checks()),
        db.completionDao(), db.timeLogDao(), onceRepository(), timerWriter = NextTimerWriter(db, tokens, sessions),
        countHistoryReader = counts(), checkHistoryReader = checks())
    private fun image() = CountConsumerReadFixtureAssertions(db, tokens, sessions)
    private suspend fun current(): HabitEntity = requireNotNull(db.habitDao().getHabitById(habit.id))
    private suspend fun configure(strict: Boolean = false, linked: Boolean = false) {
        register()
        habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1,
            targetCycles = if (strict) 3 else null, failMode = if (strict) FailMode.STRICT else FailMode.LOOSE)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            if (linked) db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        watched.set(habit.id)
    }
    private suspend fun historical(day: LocalDate, value: Int, zone: ZoneId = ZoneId.systemDefault()): CompletionEntity {
        val instant = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val input = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = UUID.randomUUID().toString(), value = value,
            date = day.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = instant, createdAt = instant,
            recordedLocalDate = day.toString(), recordedTimezone = zone.id)
        producer().write(local()) { db.completionDao().insertForSync(input) }
        return requireNotNull(db.completionDao().getCompletionByUuid(input.uuid))
    }
    private suspend fun rejection(block: suspend () -> Unit): Throwable {
        try { block() } catch (failure: Exception) { if (failure is CancellationException) throw failure; return failure }
        throw AssertionError("The original refusal must remain")
    }
    private fun sameFactResult(original: WidgetFactSnapshot, projected: WidgetFactSnapshot) {
        assertEquals(original.habit, projected.habit); assertEquals(original.claim, projected.claim)
        assertEquals(original.completed, projected.completed); assertEquals(original.targetProgress, projected.targetProgress)
        assertEquals(original.hasFailed, projected.hasFailed)
    }

    @Test(timeout = 150_000)
    fun dashboardMatchesFullOracleAndAvoidsThePositivelyCalibratedCheckDetailDao() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(strict = true)
        val today = DateTimeUtils.today()
        historical(today.minusDays(5), 1); historical(today, 1); val last = historical(today, 1)
        val countId = repository().createHabit("Mixed COUNT", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 7, appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
            completionPolicy = "recurring", creationAuthority = creator.capture())
        repository().logCompletion(app, countId, 2)
        val time = ZonedDateTime.now(); val row = current()
        fullDetails.set(0)
        val full = checks().read(row, time.toLocalDate())
        assertTrue("Original complete history must hit the observed DAO", fullDetails.get() > 0)
        assertEquals(2L, full.todayQuantity); assertEquals(last.id, full.todayCompletions.maxByOrNull { it.id }!!.id)
        val expected = status().projectCheck(row, full, time)
        assertTrue(expected.hasFailed)
        // Cold preparation includes every real consumer: the existing mixed TIMER can
        // issue its proof while the builder runs, after the dashboard COUNT/CHECK read.
        // Keep that first complete page separate from the unchanged warm no-detail gate.
        val coldCards = DashboardHabitListBuilder(status()).project(dashboard().readProjection(time))
        assertEquals(expected, coldCards.single { it.habit.id == habit.id })
        val before = image().capture()
        fullDetails.set(0)
        val batch = dashboard().readProjection(time)
        val cards = DashboardHabitListBuilder(status()).project(batch)
        assertEquals(0, fullDetails.get())
        assertEquals(expected, cards.single { it.habit.id == habit.id })
        assertTrue(batch.completions.none { it.habitId == habit.id })
        assertEquals(setOf(habit.id), batch.checks.keys)
        assertTrue(countId in batch.counts)
        assertNotNull(batch.checks.getValue(habit.id).card)
        assertEquals(before, image().capture())
        val old = dashboard().read(time)
        assertEquals(full, old.checks.getValue(habit.id)) // Explicit full API remains complete.
        assertEquals(before, image().capture())
    }

    @Test(timeout = 150_000)
    fun actualToggleKeepsMultipleFactsMaxIdUndoAndOwnActivationWithoutTypedReceipt() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure()
        val repo = repository()
        val first = repo.logCompletion(app, habit.id, 1)
        val last = repo.logCompletion(app, habit.id, 1)
        assertTrue(last > first)
        val checkService = service()
        val before = repo.prepareCheckCompletionProjected(habit.id)
        assertNull(before.evidence!!.checkHistory); assertNotNull(before.evidence!!.checkDisplay)
        val undoLast = checkService.toggleCheckIn(app, habit.id, habit.uuid,
            identity = before.evidence!!.checkDisplay!!.actionIdentity) as CheckInResult.Success
        assertTrue(undoLast.completed); assertEquals(1, undoLast.progress); assertFalse(undoLast.goalReached)
        assertEquals(listOf(first), db.completionDao().getByHabitOnce(habit.id).map { it.id })
        assertNull(undoLast.command); assertNotNull(undoLast.followUp!!.checkDisplay)
        val undoFirst = checkService.toggleCheckIn(app, habit.id, habit.uuid) as CheckInResult.Success
        assertFalse(undoFirst.completed); assertEquals(0, undoFirst.progress); assertFalse(undoFirst.goalReached)
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        val edit = repo.getHabitForEditing(habit.id)
        repo.updateHabit(requireNotNull(edit.value).copy(isActive = false), editAuthority = edit.authority)
        val inactive = current(); assertFalse(inactive.isActive)
        val identity = requireNotNull(checks().readProjection(inactive).actionIdentity)
        val added = checkService.toggleCheckIn(app, habit.id, habit.uuid, identity = identity) as CheckInResult.Success
        val saved = db.completionDao().getByHabitOnce(habit.id).single()
        val follow = requireNotNull(added.followUp)
        assertTrue(added.completed); assertEquals(1, added.progress); assertNull(added.command)
        assertTrue(current().isActive); assertEquals(saved.uuid, follow.claim.completionUuid)
        assertNotNull(follow.checkDisplay)
        assertEquals(identity.completionSuccessor(inactive), follow.checkDisplay!!.actionIdentity)
        assertEquals(1L, follow.checkDisplay!!.todayQuantity)
        val queued = db.syncOutboxDao().getEntityIntents("completion", saved.uuid).single()
        assertEquals(saved.uuid, queued.entityUuid)
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, queued.operationId))
    }

    @Test(timeout = 150_000)
    fun exactCrossDaySourceKeepsItsOwnZoneAndPromptThenRejectsActualRevocation() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(linked = true)
        val repo = repository(); val captured = repo.prepareCompletion(habit.id)
        val sourceZone = ZoneId.of("Etc/UTC")
        val day = DateTimeUtils.today().minusDays(1)
        val old = historical(day, 1, sourceZone)
        repo.logCompletion(app, habit.id, 1)
        val original = repo.readCompletionAfter(captured, false, old.id)
        val projected = repo.readCompletionAfterProjected(captured, false, old.id)
        assertNull(projected.checkHistory); assertNotNull(projected.checkDisplay)
        assertEquals(DateTimeUtils.today(), projected.checkDisplay!!.today)
        assertEquals(day.toString(), projected.followUp.claim.date)
        assertEquals(sourceZone.id, projected.followUp.claim.timezone)
        assertEquals(old.uuid, projected.followUp.claim.completionUuid)
        sameFactResult(original.followUp, projected.followUp)
        var delivered: CompletionMetricRead? = null
        val stable = image().capture()
        creatingMetrics().publishCompletionPrompt(projected.followUp) {
            assertFalse(db.inTransaction()); delivered = it
        }
        assertEquals(old.uuid, requireNotNull(delivered).source.claim.completionUuid)
        assertEquals(listOf(metric.uuid), delivered!!.metrics.map { it.value!!.uuid })
        assertEquals(stable, image().capture())
        repo.undoCompletion(app, old.id)
        val afterUndo = image().capture(); var published = false
        val rejected = rejection { creatingMetrics().publishCompletionPrompt(projected.followUp) { published = true } }
        assertEquals("COMPLETION_SOURCE_CHANGED", rejected.message)
        assertFalse(published); assertEquals(afterUndo, image().capture())
    }

    @Test(timeout = 150_000)
    fun nestedAndWidgetUseTheFiniteCheckResultWithOriginalStatsAndLatestClaim() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(strict = true)
        val repo = repository()
        val parent = repo.createHabit("CHECK parent", "", HabitType.GOAL, 0, "#123456", HabitSchedule.Daily,
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
            completionPolicy = null, creationAuthority = creator.capture())
        val parentRow = requireNotNull(db.habitDao().getHabitById(parent))
        val edit = repo.getHabitForEditing(habit.id)
        repo.updateHabit(requireNotNull(edit.value).copy(parentHabitId = parentRow.uuid), editAuthority = edit.authority)
        historical(DateTimeUtils.today().minusDays(3), 1)
        val last = historical(DateTimeUtils.today(), 1)
        val row = current(); val time = ZonedDateTime.now(); val full = checks().read(row, time.toLocalDate())
        val expected = status().projectCheck(row, full, time)
        val tree = NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(),
            FailureChecker(db.completionDao(), db.timeLogDao(), counts(), checks()), onceRepository(),
            countHistoryReader = counts(), checkHistoryReader = checks())
        val raw = db.completionDao().getAllCompletionsOnce()
        checks().readProjection(row, time.toLocalDate(), time.zone, time.toInstant().toEpochMilli())
        val before = image().capture(); fullDetails.set(0)
        val child = tree.build(listOf(parentRow), raw).single().children.single()
        assertEquals(0, fullDetails.get())
        assertEquals(expected.completedToday, child.completedToday); assertEquals(expected.todayCount, child.todayCount)
        assertEquals(expected.currentStreak, child.currentStreak); assertEquals(expected.bestStreak, child.bestStreak)
        assertEquals(expected.activityRate, child.activityRate); assertEquals(expected.hasFailed, child.hasFailed)
        assertEquals(expected.targetProgress, child.targetProgress); assertEquals(expected.checkActionIdentity, child.checkActionIdentity)
        assertEquals(last.id, child.lastCompletionId)
        val widget = facts().read(row)
        assertNotNull(widget.checkDisplay); assertEquals(last.uuid, widget.claim.completionUuid)
        assertEquals(expected.completedToday, widget.completed); assertEquals(expected.targetProgress, widget.targetProgress)
        assertEquals(expected.hasFailed, widget.hasFailed); assertEquals(0, fullDetails.get())
        assertEquals(before, image().capture())
    }

    @Test(timeout = 150_000)
    fun projectedPromptRefusesFreshAccountAndPlanChangesWithoutPublishingOrMutating() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(linked = true)
        val added = service().toggleCheckIn(app, habit.id, habit.uuid) as CheckInResult.Success
        val source = requireNotNull(added.followUp); assertNotNull(source.checkDisplay)
        val repo = repository(); val edit = repo.getHabitForEditing(habit.id)
        repo.updateHabit(requireNotNull(edit.value).copy(name = "Changed CHECK"), editAuthority = edit.authority)
        val changed = image().capture(); var published = false
        assertEquals("FACT_WIDGET_PLAN_CHANGED", rejection {
            creatingMetrics().publishCompletionPrompt(source) { published = true }
        }.message)
        assertFalse(published); assertEquals(changed, image().capture())
        tokens.saveLoginSession("synthetic-check-new", "synthetic-refresh", "member", id(1), false)
        val afterLogin = image().capture()
        assertEquals("FACT_WIDGET_STALE_ACCOUNT", rejection {
            creatingMetrics().publishCompletionPrompt(source) { published = true }
        }.message)
        assertFalse(published); assertEquals(afterLogin, image().capture())
    }
}
