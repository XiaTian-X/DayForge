package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import io.mockk.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production entry, original ACK and current GC. The only planning interposition is
 * test-side scheduling around the pure function; callOriginal executes its real body once.
 */
@RunWith(AndroidJUnit4::class)
class CountAddExecutorTest : CountTrustFormalProfileFixture() {
    private val nativeSpy = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = nativeSpy
    private val planningCalls = AtomicInteger()
    @Volatile private var plannedAt: Instant? = null
    @Volatile private var plannedZone: ZoneId? = null
    private var releasePlanning: CountDownLatch? = null
    private var planningMocked = false
    private val cancelAtOrigin = AtomicBoolean(false)
    private val cancelledAtOrigin = AtomicBoolean(false)
    @Volatile private var cancelTarget: Job? = null
    private val sql get() = db.openHelper.writableDatabase

    override fun countAddObservedQuery(query: String) {
        if (cancelAtOrigin.get() && query.trim().uppercase().startsWith("INSERT") && query.contains("next_request_origins")) {
            cancelAtOrigin.set(false); cancelledAtOrigin.set(true)
            requireNotNull(cancelTarget).cancel(CancellationException("count-add-real-origin-cancel"))
        }
    }

    @After fun releasePurePlanning() {
        releasePlanning?.countDown(); releasePlanning = null
        if (planningMocked) unmockkObject(CountAddNativePlan.Companion)
    }

    private suspend fun ticket(habit: HabitEntity) = requireNotNull(editor.habit(habit.id).authority)

    /** The actual synchronous B function only. Never proxy a real suspend editor method:
     * native closed-effect reads plus public committed facts establish the actual C route.
     */
    private fun observePurePlanning() {
        mockkObject(CountAddNativePlan.Companion); planningMocked = true
        every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) } answers {
            planningCalls.incrementAndGet(); plannedAt = arg(3); plannedZone = arg(4)
            callOriginal()
        }
    }

    private fun pausePurePlanning(): CompletableDeferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1); releasePlanning = release
        mockkObject(CountAddNativePlan.Companion)
        planningMocked = true
        every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) } answers {
            planningCalls.incrementAndGet(); plannedAt = arg(3); plannedZone = arg(4)
            assertFalse("A must have reached actual END before pure planning", db.inTransaction())
            // A real separate coroutine must acquire the account mutex while B is paused.
            // A transient legitimate maintenance owner may finish first; an immediate
            // tryLock result alone cannot attribute BUSY to this operation.
            runBlocking { withTimeout(10_000) {
                sessions.exclusive(AccountSessionCoordinator.Work.NEXT_WRITE) { Unit }
            } }
            entered.complete(Unit)
            check(release.await(60, TimeUnit.SECONDS)) { "COUNT_ADD_TEST_PLANNING_RELEASE_TIMEOUT" }
            callOriginal()
        }
        return entered
    }

    private suspend fun assertOriginalCount(habit: HabitEntity, expected: Long) {
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(current)
        assertEquals(expected, original.completions.sumOf { it.value.toLong() })
        assertEquals(expected, CountHistoryReader(db, tokens, sessions).readSummary(current).totalQuantity)
    }

    @Test fun publicRepositorySelectsClosedTypedFirstDayAndExistingDayPath() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        observePurePlanning()
        val repository = creatingHabits()
        val beforeFirst = nativeSpy.countAddClosedEffectReads()
        val first = repository.logCompletion(app, habit.id, authority = ticket(habit))
        val afterFirst = nativeSpy.countAddClosedEffectReads()
        assertTrue("First-day public commit must execute the actual closed native verifier", afterFirst > beforeFirst)
        val second = repository.logCompletion(app, habit.id, authority = ticket(habit))
        assertTrue("Existing-day public commit must execute the actual closed native verifier",
            nativeSpy.countAddClosedEffectReads() > afterFirst)
        assertTrue(first > 0 && second > first)
        assertEquals(2, planningCalls.get())
        assertOriginalCount(habit, 2)
        assertEquals(2, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
    }

    @Test fun completedCountdownReturnsOriginalZeroWithoutAllocatingOrChangingAnyMainContent() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs(countdown = true).first()
        val first = creatingHabits().logCompletion(app, habit.id, authority = ticket(habit))
        assertTrue(first > 0)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        val result = editor.addCountPrepared(requireNotNull(db.habitDao().getHabitById(habit.id)), ticket(habit),
            null, Instant.now(), ZoneId.systemDefault())
        assertEquals(CountAddExecutor.Result.Committed(0), result)
        images.assertUnchanged(before)
        assertEquals(0L, creatingHabits().logCompletion(app, habit.id))
        images.assertUnchanged(before)
    }

    @Test fun unsupportedValueUsesOriginalProducerOnceWithoutPurePlanningOrClosedEffects() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        observePurePlanning()
        val beforeClosed = nativeSpy.countAddClosedEffectReads()
        val inserted = creatingHabits().logCompletion(app, habit.id, value = 2, authority = ticket(habit))
        assertTrue(inserted > 0)
        assertEquals(0, planningCalls.get())
        assertEquals(beforeClosed, nativeSpy.countAddClosedEffectReads())
        val facts = db.completionDao().getByHabitOnce(habit.id)
        assertEquals(1, facts.size); assertEquals(2, facts.single().value)
        assertOriginalCount(habit, 2)
    }

    @Test fun existingOuterRoomWriterKeepsOriginalPathAcrossDispatcherAndCompleteRollback() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first(); val authority = ticket(habit)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        val beforeClosed = nativeSpy.countAddClosedEffectReads()
        mockkObject(CountAddNativePlan.Companion); planningMocked = true
        every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) } answers {
            planningCalls.incrementAndGet()
            error("COUNT_ADD_TEST_B_MUST_NOT_RUN_INSIDE_AMBIENT_TRANSACTION")
        }
        val sentinel = IllegalStateException("COUNT_ADD_TEST_EXISTING_OUTER_ROLLBACK")
        val failure = try {
            db.withTransaction<Unit> {
                val first = creatingHabits().logCompletion(app, habit.id, authority = authority)
                assertTrue(first > 0)
                val second = withContext(Dispatchers.Default) {
                    assertFalse("Prove the inherited Room context is not a current-thread native transaction", db.inTransaction())
                    creatingHabits().logCompletion(app, habit.id, authority = authority)
                }
                assertTrue(second > first)
                assertEquals("Neither ambient call may enter pure B", 0, planningCalls.get())
                assertEquals("Ambient calls must not execute the closed native verifier", beforeClosed, nativeSpy.countAddClosedEffectReads())
                assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
                assertEquals(2, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
                throw sentinel
            }
            null
        } catch (error: IllegalStateException) { error }
        assertSame("Only the explicit outer sentinel may end this original transaction", sentinel, failure)
        assertEquals(0, planningCalls.get())
        assertEquals(beforeClosed, nativeSpy.countAddClosedEffectReads())
        verify(exactly = 0) { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) }
        images.assertUnchanged(before)
    }

    @Test fun realSourceSendAndAckPreserveTypedRootAndCurrentCount() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val result = editor.addCountPrepared(habit, ticket(habit), null, Instant.now(), ZoneId.systemDefault())
        assertTrue(result is CountAddExecutor.Result.Committed && result.id > 0)
        val queued = db.syncOutboxDao().getAll().single { it.recordType == "completion" }
        val requests = countAddPlanSender()
        val delivery = requireNotNull(requests.sendOperation(access(), queued.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertOriginalCount(habit, 1)
    }

    @Test fun cancellationAfterRealFactAndSourceWritesRollsBackAndAllowsFreshRetry() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first(); val authority = ticket(habit)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        supervisorScope {
            val attempt = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                editor.addCountPrepared(habit, authority, null, Instant.now(), ZoneId.systemDefault())
            }
            cancelTarget = attempt; cancelAtOrigin.set(true)
            val failure = try { attempt.await(); null } catch (error: CancellationException) { error }
            assertTrue(failure is CancellationException)
        }
        assertTrue("The actual origin boundary after fact/source/rate must be reached", cancelledAtOrigin.get())
        images.assertUnchanged(before)
        val retry = editor.addCountPrepared(habit, ticket(habit), null, Instant.now(), ZoneId.systemDefault())
        assertTrue(retry is CountAddExecutor.Result.Committed && retry.id > 0)
        assertOriginalCount(habit, 1)
    }

    @Test fun relevantConcurrentChangeRejectsPreparedPlanBeforeAnySecondFact() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first(); val authority = ticket(habit)
        val entered = pausePurePlanning()
        val attempt = async(Dispatchers.IO) {
            editor.addCountPrepared(habit, authority, null, Instant.now(), ZoneId.systemDefault())
        }
        try {
            withTimeout(10_000) { entered.await() }
            // No authority parameter: the existing public path deliberately uses its original
            // producer, and does not recursively enter the paused typed pure plan.
            assertTrue(creatingHabits().logCompletion(app, habit.id) > 0)
        } finally { releasePlanning?.countDown() }
        assertTrue(attempt.await() is CountAddExecutor.Result.Original)
        assertOriginalCount(habit, 1)
    }

    /** Read-only explanation of exactly the existing-day dependencies selected by freeze().
     * These observations cannot authorize C: its private read set must independently match.
     * Reports contain only fixed labels, never row bodies, identities or credentials.
     */
    private suspend fun sameHabitAckInputs(habit: HabitEntity, at: Instant, zone: ZoneId): Map<String, String> = db.withTransaction {
        CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { supplied ->
            val context = requireNotNull(supplied)
            val day = at.atZone(zone).toLocalDate(); val month = YearMonth.from(day)
            val head = requireNotNull(context.head(habit.uuid))
            val values = linkedMapOf<String, String>()
            fun leaf(label: String, key: String) { values[label] = context.index.get(key).toString() }
            fun rows(label: String, key: String, table: String, where: String, args: Array<out Any?>,
                markerProjection: Boolean = false): List<CountTrustRows.Row> {
                val actual = context.checked(key, table, where, args)
                values[label] = JsonArray(actual.map { if (markerProjection)
                    JsonObject(it.value.filterKeys { field -> field != "metadataHash" }) else it.value }).toString()
                return actual
            }
            rows("habit-native", CountTrustMembership.key("habit.uuid", habit.uuid), "habits", "uuid=?", arrayOf(habit.uuid))
            rows("structure-range", CountTrustMembership.key("outbox.record", "habit", habit.uuid), "sync_outbox",
                "recordType=? AND entityUuid=?", arrayOf("habit", habit.uuid))
            rows("cursor-native", CountTrustMembership.key("cursor.id", "1"), "next_sync_state", "id=?", arrayOf(1))
            rows("challenge-projection", CountTrustMembership.key("challenge.id", "1"), "next_challenge_state", "id=?", arrayOf(1), true)
            rows("round-native", CountTrustMembership.key("round.uuid", head.roundUuid), "next_challenge_rounds", "roundUuid=?", arrayOf(head.roundUuid))
            val days = rows("day-month-native", CountTrustMembership.key("day.month", habit.uuid, month.toString()), "count_days",
                "habitId=? AND localDate>=? AND localDate<?", arrayOf<Any>(habit.id, month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString()))
            val current = days.single { it.day().localDate == day.toString() }.day()
            if (current.originRequestId != null) rows("first-origin-native", CountTrustMembership.key("origin.request", NEXT_OPERATION, current.originRequestId),
                "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, current.originRequestId))
            else rows("first-shadow-native", CountTrustMembership.key("shadow.entity", "activity_event", current.firstEventUuid),
                "sync_entity_state", "entityType=? AND entityUuid=?", arrayOf("activity_event", current.firstEventUuid))
            leaf("eligible-leaf", "eligible:${habit.uuid}")
            leaf("coverage-leaf", CountTrustRoundEvidence.COVERAGE_KEY)
            leaf("head-leaf", CountTrustRoundEvidence.headKey(habit.uuid))
            leaf("known-leaf", CountTrustRoundEvidence.knownKey(habit.uuid))
            leaf("summary-leaf", CountTrustRoundEvidence.summaryKey(habit.uuid, month, head))
            leaf("months-leaf", CountTrustRoundEvidence.monthsKey(habit.uuid, head))
            values
        }
    }

    @Test fun sameHabitPreviousCountAckBetweenAAndCReportsExactInvalidationAndCommitsOnce() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        assertTrue(creatingHabits().logCompletion(app, habit.id) > 0)
        val previousSource = db.syncOutboxDao().getAll().single { it.recordType == "completion" }
        val requests = countAddPlanSender()
        val delivery = requireNotNull(requests.sendOperation(access(), previousSource.operationId))
        val authority = ticket(habit)
        val beforeClosed = nativeSpy.countAddClosedEffectReads()
        val entered = pausePurePlanning()
        val attempt = async(Dispatchers.IO) { creatingHabits().logCompletion(app, habit.id, authority = authority) }
        var before: Map<String, String> = emptyMap(); var after: Map<String, String> = emptyMap()
        try {
            withTimeout(10_000) { entered.await() }
            before = sameHabitAckInputs(habit, requireNotNull(plannedAt), requireNotNull(plannedZone))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
            after = sameHabitAckInputs(habit, requireNotNull(plannedAt), requireNotNull(plannedZone))
        } finally { releasePlanning?.countDown() }
        val id = attempt.await(); assertTrue(id > 0)
        val typed = nativeSpy.countAddClosedEffectReads() > beforeClosed
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
        if (changed.isEmpty()) assertTrue("Identical authenticated dependencies must keep the typed path", typed)
        else assertTrue("Relevant ACK input change must preserve the original fallback", !typed)
        fun changedFields(name: String): List<String> {
            val old = before[name]?.let { Json.parseToJsonElement(it) }
            val fresh = after[name]?.let { Json.parseToJsonElement(it) }
            if (old == null || fresh == null) return listOf("selection-presence")
            if (old is JsonObject && fresh is JsonObject)
                return (old.keys + fresh.keys).filter { old[it] != fresh[it] }.sorted()
            if (old is JsonArray && fresh is JsonArray && name.endsWith("native")) {
                if (old.size != fresh.size) return listOf("membership")
                return old.zip(fresh).flatMap { (left, right) ->
                    val a = left.jsonObject; val b = right.jsonObject
                    (a.keys + b.keys).filter { a[it] != b[it] }
                }.distinct().sorted()
            }
            return listOf("value")
        }
        android.util.Log.i("CountAddAckCompetition", buildJsonObject {
            put("scenario", "same_habit_previous_count_ack_between_A_C")
            put("route", if (typed) "typed" else "original_fallback")
            put("changed_dependencies", JsonArray(changed.map(::JsonPrimitive)))
            put("changed_fields", buildJsonObject { changed.forEach { name ->
                put(name, JsonArray(changedFields(name).map(::JsonPrimitive)))
            } })
            put("new_fact_count", 1); put("actual_ack", true); put("performance_measured", false)
            put("closed_effect_native_reads", nativeSpy.countAddClosedEffectReads() - beforeClosed)
            put("pure_b_calls", planningCalls.get())
        }.toString())
        assertEquals("Exactly one real pure plan serves this public command", 1, planningCalls.get())
        assertOriginalCount(habit, 2)
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
        val remaining = db.syncOutboxDao().getAll().filter { it.recordType == "completion" }
        assertEquals(1, remaining.size); assertNotEquals(previousSource.operationId, remaining.single().operationId)
        assertEquals(NextOperationAcceptance.REPLAYED, requests.acceptOperation(delivery))
    }

    @Test fun oldRootCanBeActuallyCollectedWhileBHasOnlyValuesAndCRebasesOnCurrentAckRoot() = runBlocking<Unit> {
        val habits = openCountAddPlanInputs(); val target = habits.first(); val other = habits.last()
        val authority = ticket(target)
        val oldRoot = db.withTransaction { requireNotNull(CountTrustSeal.read(sql)).root.digest }
        val entered = pausePurePlanning()
        val attempt = async(Dispatchers.IO) {
            editor.addCountPrepared(target, authority, null, Instant.now(), ZoneId.systemDefault())
        }
        try {
            withTimeout(10_000) { entered.await() }
            assertTrue(creatingHabits().logCompletion(app, other.id) > 0)
            val queue = db.syncOutboxDao().getAll().single { it.referenceUuid == other.uuid }
            val requests = countAddPlanSender()
            val delivery = requireNotNull(requests.sendOperation(access(), queue.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
            db.withTransaction { assertNotEquals(oldRoot, requireNotNull(CountTrustSeal.read(sql)).root.digest) }
            val collector = CountTrustNodeCollector(db, tokens, sessions)
            var finished = false
            withTimeout(40_000) {
                while (!finished) {
                    val report = collector.collectSlice()
                    when (report.status) {
                        CountTrustNodeCollector.Status.FINISHED -> finished = true
                        CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.RESTARTED -> Unit
                        CountTrustNodeCollector.Status.BUSY -> delay(50)
                        CountTrustNodeCollector.Status.DEFERRED -> {
                            assertEquals("Only real temporary readers may defer this enabled collector", "READER_OR_OWNER_PRESENT", report.reason)
                            delay(50)
                        }
                        CountTrustNodeCollector.Status.FAILED -> error("Real GC failed: ${report.reason}")
                    }
                }
            }
            assertTrue(finished)
            db.withTransaction { sql.query("SELECT 1 FROM count_trust_nodes WHERE digest=?", arrayOf(oldRoot)).use {
                assertFalse("Old A root must be physically absent before C starts", it.moveToFirst())
            } }
        } finally { releasePlanning?.countDown() }
        val result = attempt.await()
        assertTrue("Unrelated ACK and GC must not force old long-path fallback", result is CountAddExecutor.Result.Committed && result.id > 0)
        assertOriginalCount(target, 1); assertOriginalCount(other, 1)
        db.withTransaction { sql.query("SELECT 1 FROM count_trust_nodes WHERE digest=?", arrayOf(oldRoot)).use { assertFalse(it.moveToFirst()) } }
    }
}
