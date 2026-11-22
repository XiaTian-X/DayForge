package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.HabitEntity
import io.mockk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real typed A phase first sees a fresh live owner and only then observes suppression.
 * No ticket/home read is allowed to pre-retire the replacement owner. This is a new complete
 * joint class; the frozen short-TX32 and owner22 tests/assertions remain unchanged.
 */
@RunWith(AndroidJUnit4::class)
class CountAddOwnerFallbackTest : CountTrustFormalProfileFixture() {
    private var spy = SourceWindowNativeSpy()
    override fun sourceWindowNativeSpy() = spy
    private val holds = mutableListOf<CountTrustRecoveryPreview>()
    private var planMocked = false
    private val bCalls = AtomicInteger()
    private val operationActive = AtomicBoolean()
    private val firstControlTransaction = AtomicLong(0L)
    private val aControl = AtomicBoolean()
    private val controls = AtomicInteger()
    private var planningRelease: CountDownLatch? = null
    private val cancelAtControl = AtomicBoolean()
    private val cancellationTouched = AtomicBoolean()
    @Volatile private var cancellationTarget: Job? = null
    private val sql get() = db.openHelper.writableDatabase
    private val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))

    override fun countAddObservedQuery(query: String) {
        if (operationActive.get() && spy.currentCountAddTransactionId() != null && query == "SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1") {
            firstControlTransaction.compareAndSet(0L, requireNotNull(spy.currentCountAddTransactionId()))
            aControl.set(true); controls.incrementAndGet()
            if (cancelAtControl.compareAndSet(true, false)) {
                cancellationTouched.set(true)
                requireNotNull(cancellationTarget).cancel(CancellationException("count-add-A-control-cancel"))
            }
        }
    }

    @After fun releaseOwnedTestState() {
        planningRelease?.countDown()
        holds.forEach(CountTrustRecoveryPreview::close)
        if (planMocked) unmockkObject(CountAddNativePlan.Companion)
    }

    private data class Prepared(val habit: HabitEntity, val authority: ObjectEditAuthority,
        val oldOwner: CountTrustRuntime, val hold: CountTrustRecoveryPreview)

    private suspend fun prepared(): Prepared {
        val habit = openCountAddPlanInputs().first()
        val authority = requireNotNull(editor.habit(habit.id).authority) // BEFORE discard/reopen.
        requireNotNull(authority.rounds)
        assertTrue(authority.rounds.pendingInitials.isEmpty()); assertTrue(authority.rounds.pendingRestarts.isEmpty())
        val old = owner
        val hold = old.newRecoveryPreview(); holds += hold
        var delivered = false
        try {
            CountTrustRecoveryPreparation(db, tokens, sessions).discardCache(access(), hold)
            delivered = true
        } finally { hold.finishPreparation(delivered) }
        assertTrue(old.isRetired); assertTrue(hold.isRetained); assertTrue(hold.isValid)
        return Prepared(habit, authority, old, hold)
    }

    private fun neverPlan() {
        mockkObject(CountAddNativePlan.Companion); planMocked = true
        every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) } answers {
            bCalls.incrementAndGet()
            throw AssertionError("Pure B must not receive any frozen value from suppressed/failed A")
        }
    }

    private fun originalFallbackEntry(): CountTrustRuntime {
        val fresh = owner
        assertFalse("Replacement owner must still be live before the public operation", fresh.isRetired)
        editor = NextObjectEditor(db, tokens, sessions, icons)
        neverPlan()
        return fresh
    }

    private fun beginObservation(): kotlin.coroutines.CoroutineContext {
        firstControlTransaction.set(0L); operationActive.set(true)
        return spy.startCountAddTransactionObservation()
    }
    private fun endObservation(): List<SourceWindowNativeSpy.CountAddTransactionObservation> {
        operationActive.set(false)
        return spy.finishCountAddTransactionObservation()
    }
    private fun assertActualReadAThenOriginalCommit(events: List<SourceWindowNativeSpy.CountAddTransactionObservation>) {
        val aId = requireNotNull(firstControlTransaction.get().takeIf { it > 0L })
        val index = events.indexOfFirst { it.id == aId }
        assertTrue("The operation's own A native END must precede a different original write transaction", index >= 0)
        val a = events[index]
        assertTrue(a.controlReads > 0); assertTrue(a.knownEnd)
        assertEquals(0, a.explicitBusinessWrites); assertEquals(0, a.completionInserts); assertEquals(0, a.originInserts)
        val writers = events.filter { it.completionInserts > 0 || it.originInserts > 0 }
        val writer = writers.single()
        assertTrue(events.indexOf(writer) > index); assertTrue(writer.knownEnd)
        assertEquals(1, writer.completionInserts); assertEquals(1, writer.originInserts)
        assertTrue(events.all { it.knownEnd })
        assertEquals(0, spy.countAddClosedEffectReads())
    }
    private fun assertActualReadWithoutBusiness(events: List<SourceWindowNativeSpy.CountAddTransactionObservation>) {
        val a = events.single { it.id == requireNotNull(firstControlTransaction.get().takeIf { it > 0L }) }
        assertTrue(a.knownEnd)
        assertTrue(events.all { it.knownEnd && it.explicitBusinessWrites == 0 })
        assertEquals(0, spy.countAddClosedEffectReads())
    }

    private suspend fun assertNoCleanRoot() = db.withTransaction {
        assertNull(CountTrustSeal.read(sql))
        sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertFalse(it.moveToNext())
        }
    }

    private suspend fun originalTerminal(expected: HabitEntity, quantity: Long) {
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions)
        val before = images.capture()
        val current = requireNotNull(db.habitDao().getHabitById(expected.id))
        val evidence = RecurringCompletionEvidenceReader(db, tokens, sessions).read(current)
        assertEquals(quantity, evidence.completions.sumOf { it.value.toLong() })
        assertEquals(quantity, CountHistoryReader(db, tokens, sessions).readSummary(current).totalQuantity)
        if (quantity == 1L) {
            val fact = evidence.completions.single()
            val source = db.syncOutboxDao().getAll().single()
            assertEquals("completion", source.recordType); assertEquals(fact.uuid, source.entityUuid)
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
            sessions.exclusive { db.withTransaction {
                assertEquals(origin, NextOriginalRequestProofStore(db).liveOrigin(access(), NEXT_OPERATION, source.operationId))
                assertEquals(origin.sourceHash, NextRequestSql.rowHash(db, "sync_outbox", "id=?", arrayOf(source.id)))
                val round = requireNotNull(roundOperationIntent(origin.intentJson))
                assertEquals(fact.uuid, round.operation.entityUuid)
                assertEquals(JsonPrimitive(1), round.operation.payload["value"])
                assertEquals(evidence.roundHead, round.context.head)
                assertNotNull(NextCountDayStore(db).read(current, fact.recordedLocalDate))
            } }
        } else {
            assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
            assertTrue(db.syncOutboxDao().getAll().isEmpty())
        }
        images.assertUnchanged(before)
        assertNoCleanRoot()
    }

    @Test fun retainedRealPreviewSoftRetiresInsideAAndPublicCountUsesTheOriginalProducer() = runBlocking<Unit> {
        val prepared = prepared()
        storage.reopen()
        val fresh = originalFallbackEntry()
        assertNotSame(prepared.oldOwner, fresh)
        val observation = beginObservation()
        val saved = withContext(observation) {
            creatingHabits().logCompletion(app, prepared.habit.id, authority = prepared.authority)
        }
        val events = endObservation()
        assertTrue(saved > 0); assertActualReadAThenOriginalCommit(events)
        assertTrue("The actual A control query must be reached before its suppression", aControl.get())
        assertEquals(0, bCalls.get()); assertTrue(fresh.isRetired)
        assertEquals(setOf(CountTrustRetirementReason.RECOVERY_PREVIEW), fresh.retirementReasons)
        assertTrue(prepared.hold.isRetained)
        originalTerminal(prepared.habit, 1)
    }

    @Test fun unknownSameFilePathSoftRetiresInsideAWithoutBreakingOriginalCount() = runBlocking<Unit> {
        val prepared = prepared()
        // The same real named file and builder retain the native observer and public callback;
        // only its path getter returns the original unknown identity shape.
        spy = SourceWindowNativeSpy(unknownPath = true)
        storage.reopen()
        val fresh = originalFallbackEntry()
        assertNotSame(prepared.oldOwner, fresh)
        val observation = beginObservation()
        val saved = withContext(observation) {
            creatingHabits().logCompletion(app, prepared.habit.id, authority = prepared.authority)
        }
        val events = endObservation()
        assertTrue(saved > 0); assertActualReadAThenOriginalCommit(events)
        assertEquals(0, bCalls.get()); assertTrue(aControl.get()); assertTrue(fresh.isRetired)
        assertEquals(setOf(CountTrustRetirementReason.CONNECTION_IDENTITY_UNKNOWN), fresh.retirementReasons)
        assertTrue(prepared.hold.isRetained)
        originalTerminal(prepared.habit, 1)
    }

    private enum class CFinish { ORIGINAL, CANCEL, UNKNOWN_END }
    private suspend fun previewDuringB(finish: CFinish) = supervisorScope {
        val habit = openCountAddPlanInputs().first()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        // A fresh owner consumes the persisted root without an installed old change journal.
        // Another real owner can then start a preview while B owns only frozen values.
        storage.reopen()
        val current = owner
        editor = NextObjectEditor(db, tokens, sessions, icons)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); planningRelease = release
        var cBefore: ConsumerNativeImage? = null
        var aControlCount = -1
        mockkObject(CountAddNativePlan.Companion); planMocked = true
        every { CountAddNativePlan.prepare(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()) } answers {
            bCalls.incrementAndGet(); assertFalse(db.inTransaction())
            entered.complete(Unit)
            check(release.await(60, TimeUnit.SECONDS)) { "COUNT_ADD_TEST_PREVIEW_RELEASE_TIMEOUT" }
            callOriginal()
        }
        val observation = beginObservation()
        // Direct real typed call preserves the exact pre-fallback image assertion without
        // intercepting a suspending business function. The public original path runs only
        // after that successful Original result and complete equality are established.
        val operation = async(Dispatchers.IO + observation, start = CoroutineStart.LAZY) {
            editor.addCountPrepared(habit, authority, null, Instant.now(), ZoneId.systemDefault())
        }
        cancellationTarget = operation
        operation.start()
        try {
            withTimeout(10_000) { entered.await() }
            assertFalse(current.isRetired); aControlCount = controls.get()
            val other = HabitDatabaseProvider.build(app)
            try {
                // Match real ReplicaRecoveryRepository.preview: open/check the source before
                // capturing the preview epoch. A hold created on a lazy epoch-zero owner is
                // intentionally invalid after its first native open changes that epoch.
                withContext(Dispatchers.IO) {
                    ReplicaPresentationRepository(other, tokens, sessions).requireOrdinaryAccess()
                }
                val second = requireNotNull(CountTrustRuntime.forDatabase(other))
                val hold = second.newRecoveryPreview(); holds += hold
                var delivered = false
                try {
                    CountTrustRecoveryPreparation(other, tokens, sessions).discardCache(access(), hold)
                    delivered = true
                } finally { hold.finishPreparation(delivered) }
                assertTrue(hold.isRetained); assertTrue(second.isRetired)
            } finally { other.close() }
            assertFalse("C must discover suppression itself, not use the early retired-owner branch", current.isRetired)
            cBefore = CountConsumerReadFixtureAssertions(db, tokens, sessions).capture()
            when (finish) {
                CFinish.ORIGINAL -> Unit
                CFinish.CANCEL -> cancelAtControl.set(true)
                CFinish.UNKNOWN_END -> spy.armCountAddPreparationEndFault()
            }
        } finally { release.countDown() }
        if (finish == CFinish.ORIGINAL) {
            val result = operation.await()
            val events = endObservation()
            assertTrue(result is CountAddExecutor.Result.Original)
            assertNull((result as CountAddExecutor.Result.Original).constructedFact)
            assertTrue("A real new C control read must observe the preview", controls.get() > aControlCount)
            assertEquals(setOf(CountTrustRetirementReason.RECOVERY_PREVIEW), current.retirementReasons)
            assertActualReadWithoutBusiness(events)
            val controlledReadTransactions = events.filter { it.controlReads > 0 }
            assertEquals("A and the empty C selection must be different actual transactions", 2, controlledReadTransactions.size)
            assertNotEquals(controlledReadTransactions[0].id, controlledReadTransactions[1].id)
            CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(requireNotNull(cBefore))
            // A separate real public request proves the suppressed owner still serves the
            // original path exactly once; it cannot obscure a preceding A/C side effect.
            assertTrue(creatingHabits().logCompletion(app, habit.id, authority = authority) > 0)
            assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
            assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
            originalTerminal(habit, 1)
        } else {
            var result: CountAddExecutor.Result? = null
            val failure = try { result = operation.await(); null } catch (error: Exception) { error }
            val events = endObservation()
            assertNull(result); assertNotNull(failure)
            assertActualReadWithoutBusiness(events)
            assertTrue("A real new C control read must observe the preview", controls.get() > aControlCount)
            if (finish == CFinish.CANCEL) {
                assertTrue(cancellationTouched.get()); assertTrue(failure is CancellationException)
            } else {
                assertEquals("COUNT_ADD_TEST_PREPARATION_END_OUTCOME_LOST", failure?.message)
                assertTrue(spy.countAddPreparationControlTouched()); assertTrue(spy.countAddPreparationEndFaultTouched())
                assertTrue(CountTrustRetirementReason.UNKNOWN_END in current.retirementReasons)
                storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
            }
            CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(requireNotNull(cBefore))
            originalTerminal(habit, 0)
        }
        assertEquals(1, bCalls.get())
    }

    @Test fun previewRegisteredDuringBAffectsOnlyEmptyCSelectionBeforeOneOriginalCommit() = runBlocking<Unit> {
        previewDuringB(CFinish.ORIGINAL)
    }
    @Test fun cancelledEmptyCSelectionCannotFallThroughToTheOriginalWriter() = runBlocking<Unit> {
        previewDuringB(CFinish.CANCEL)
    }
    @Test fun unknownActualEndOfEmptyCSelectionCannotFallThroughToTheOriginalWriter() = runBlocking<Unit> {
        previewDuringB(CFinish.UNKNOWN_END)
    }

    @Test fun cancellationAtTheRealAControlReadCannotContinueBOrTheOriginalWriter() = runBlocking<Unit> {
        val prepared = prepared()
        storage.reopen()
        originalFallbackEntry()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        val observation = beginObservation()
        supervisorScope {
            val attempt = async(Dispatchers.IO + observation, start = CoroutineStart.LAZY) {
                creatingHabits().logCompletion(app, prepared.habit.id, authority = prepared.authority)
            }
            cancellationTarget = attempt; cancelAtControl.set(true)
            val failure = try { attempt.await(); null } catch (error: CancellationException) { error }
            assertTrue(failure is CancellationException)
        }
        val events = endObservation()
        assertTrue(cancellationTouched.get()); assertTrue(aControl.get())
        assertActualReadWithoutBusiness(events); assertEquals(0, bCalls.get())
        images.assertUnchanged(before)
        assertTrue(prepared.hold.isRetained)
        originalTerminal(prepared.habit, 0)
    }

    @Test fun actualAEndOutcomeLostCannotTurnSuppressionIntoOriginalWritePermission() = runBlocking<Unit> {
        val prepared = prepared()
        storage.reopen()
        val failedOwner = originalFallbackEntry()
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        spy.armCountAddPreparationEndFault()
        val observation = beginObservation()
        var returned: Long? = null
        val failure = try {
            returned = withContext(observation) {
                creatingHabits().logCompletion(app, prepared.habit.id, authority = prepared.authority)
            }
            null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { error }
        val events = endObservation()
        assertActualReadWithoutBusiness(events)
        assertNotNull(failure); assertEquals("COUNT_ADD_TEST_PREPARATION_END_OUTCOME_LOST", failure?.message)
        assertNull(returned); assertEquals(0, bCalls.get())
        assertTrue(spy.countAddPreparationControlTouched()); assertTrue(spy.countAddPreparationEndFaultTouched())
        assertTrue(aControl.get()); assertTrue(failedOwner.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in failedOwner.retirementReasons)
        // Never use an unknown-END owner as the evidence of its own rollback/commit. New
        // original owner opens the same file; A contained no business or root-issuance DML.
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        assertNotSame(failedOwner, owner)
        CountConsumerReadFixtureAssertions(db, tokens, sessions).assertUnchanged(before)
        assertTrue(prepared.hold.isRetained)
        originalTerminal(prepared.habit, 0)
    }
}
