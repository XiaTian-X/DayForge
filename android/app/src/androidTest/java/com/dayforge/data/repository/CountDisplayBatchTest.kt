package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockDiagnostics
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CountSummary
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real controlled Room/Keystore: proof sharing never authorizes writes or publication. */
@RunWith(AndroidJUnit4::class)
class CountDisplayBatchTest : NextCoreRequestFixture() {
    private val spy = CountDisplayNativeSpy()
    private val decrypts = AtomicInteger()
    private val alias = "dayforge.test.display-batch.${UUID.randomUUID()}"
    init { storage.isolatedBuilder = { spy.build(it) } }
    override fun tokenCipher(): TokenCipher {
        val real = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = real.encrypt(value)
            override fun decrypt(value: String): String? { decrypts.incrementAndGet(); return real.decrypt(value) }
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private val today get() = DateTimeUtils.today()
    private fun kernel() = CountTrustKernel(db, tokens, sessions)
    private fun assertions() = CountConsumerReadFixtureAssertions(db, tokens, sessions)
    private suspend fun <T> display(block: suspend (RecurringDisplayReadScope) -> T): T =
        RecurringDisplayReadScope.withSnapshot(db, tokens, sessions, block = block)
    private suspend fun ready(): CompletionEntity {
        register()
        val zone = ZoneId.systemDefault()
        val instant = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val input = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(91_001), value = 2,
            date = today.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = instant, createdAt = instant,
            recordedLocalDate = today.toString(), recordedTimezone = zone.id)
        producer().write(local()) { NextCountDayStore(db).capture(habit, input); db.completionDao().insertForSync(input) }
        val fact = requireNotNull(db.completionDao().getCompletionByUuid(input.uuid))
        CountHistoryReader(db, tokens, sessions).readSummary(habit, today)
        db.withTransaction { kernel().withOpen(requireNotNull(tokens.localCoreWriteAccess())) { assertNotNull(it) } }
        return fact
    }
    private data class Projection(val summary: CountSummary, val fact: CompletionEntity, val details: List<CompletionEntity>)
    private class Decodes : HistoryBlockDiagnostics.Observer {
        var count = 0
        override fun begin(stage: HistoryBlockDiagnostics.Stage): HistoryBlockDiagnostics.Observation {
            if (stage == HistoryBlockDiagnostics.Stage.NODE_DECODE) count++
            return HistoryBlockDiagnostics.Observation { }
        }
    }

    @Test fun threeStandaloneProofsAndOnePreparedBatchHaveIdenticalFullResultsAndNativeContents() = runBlocking<Unit> {
        val fact = ready(); val image = assertions().capture()
        val schemaReads = intArrayOf(-1, -1, -1)
        sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            val standalone = NextEvidenceReadSession.read(db) {
                spy.begin()
                val value = try { Projection(requireNotNull(kernel().summary(habit, today, access)),
                    requireNotNull(kernel().exactFact(habit, access, uuid = fact.uuid)), requireNotNull(kernel().details(habit, access))) }
                finally { schemaReads[0] = spy.end() }
                value
            }
            val counter = Decodes(); val old = HistoryBlockDiagnostics.current.get()
            HistoryBlockDiagnostics.current.set(counter)
            spy.begin(); decrypts.set(0)
            val candidate = try { kernel().withPreparedDisplayRead(access, true) { borrowed ->
                val read = requireNotNull(borrowed)
                Projection(requireNotNull(read.summary(db, tokens, habit, today)),
                    requireNotNull(read.exactFact(db, tokens, habit, null, fact.uuid)), requireNotNull(read.details(db, tokens, habit)))
            } } finally { HistoryBlockDiagnostics.current.set(old); schemaReads[1] = spy.end() }
            assertEquals(standalone, candidate)
            assertEquals(0, decrypts.get()) // This low-level proof participant creates no authority.
            assertTrue("Actual original codecs must still execute", counter.count > 0)
            // A second operation must reopen/authenticate; no permanent root or decoded session.
            spy.begin()
            try { kernel().withPreparedDisplayRead(access, true) {
                assertEquals(candidate.summary, requireNotNull(it).summary(db, tokens, habit, today))
            } } finally { schemaReads[2] = spy.end() }
        } }
        assertions().assertUnchanged(image)
        val original = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, today)
        val projected = CountHistory.fromSummary(CountHistoryReader(db, tokens, sessions).readSummary(habit, today), original.completions)
        assertEquals(setOf(fact.uuid), projected.completions.map { it.uuid }.toSet())
        // Room20 retains both mandatory schema-bridge inspection and COUNT warm open.
        // Check exact counts only after the original body and unchanged-image oracles:
        // a diagnostic finally assertion must not replace the actual participant failure.
        assertEquals(3, schemaReads[0])
        assertEquals(2, schemaReads[1])
        assertEquals(2, schemaReads[2])
    }

    @Test fun participantClosesAtLexicalEndAndRejectsChildJobForeignTokensAndLaterTransactions() = runBlocking<Unit> {
        ready(); val image = assertions().capture()
        lateinit var leaked: CountTrustKernel.DisplayRead
        sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            kernel().withPreparedDisplayRead(access, true) { read ->
                leaked = requireNotNull(read)
                assertNotNull(leaked.summary(db, tokens, habit, today))
                val foreign = TokenManager(dataStore, PlaintextTokenCipher)
                assertEquals("COUNT_DISPLAY_READ_EXPIRED", rejected { leaked.summary(db, foreign, habit, today) }.message)
                coroutineScope { assertEquals("COUNT_DISPLAY_READ_EXPIRED", async {
                    rejected { leaked.summary(db, tokens, habit, today) }.message
                }.await()) }
            }
            assertEquals("COUNT_DISPLAY_READ_EXPIRED", rejected { leaked.summary(db, tokens, habit, today) }.message)
            kernel().withPreparedDisplayRead(access, true) {
                assertEquals("COUNT_DISPLAY_READ_EXPIRED", rejected { leaked.summary(db, tokens, habit, today) }.message)
                assertNotNull(requireNotNull(it).summary(db, tokens, habit, today))
            }
        } }
        assertEquals("COUNT_DISPLAY_READ_EXPIRED", rejected { leaked.summary(db, tokens, habit, today) }.message)
        db.withTransaction { assertEquals("COUNT_DISPLAY_READ_EXPIRED", rejected { leaked.summary(db, tokens, habit, today) }.message) }
        assertions().assertUnchanged(image)
    }

    @Test fun actualChangedNativeHabitRejectsAfterAReusedSummaryAndRollsBackEveryTable() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); val counts = CountHistoryReader(db, tokens, sessions)
        val failure = rejected { display {
            counts.readSummaryInTransaction(habit, today, it)
            db.habitDao().update(habit.copy(targetValue = habit.targetValue + 1))
            counts.readSummaryInTransaction(habit, today, it)
        } }
        assertEquals("COUNT_TRUST_CONTENT_CHANGED", failure.message)
        assertions().assertUnchanged(image)
        assertEquals(2L, counts.readSummary(habit, today).todayQuantity)
    }

    @Test fun unrelatedNoopAndChangedThenRestoredRowsCannotPublishAndFullyRollBack() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); val counts = CountHistoryReader(db, tokens, sessions)
        for (restore in listOf(false, true)) {
            var completed = false
            val failure = rejected {
                display { read ->
                    counts.readSummaryInTransaction(habit, today, read)
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("UPDATE metrics SET updatedAt=updatedAt+? WHERE id=?", arrayOf(if (restore) 1 else 0, metric.id))
                    if (restore) sql.execSQL("UPDATE metrics SET updatedAt=updatedAt-1 WHERE id=?", arrayOf(metric.id))
                    counts.readSummaryInTransaction(habit, today, read)
                }
                completed = true
            }
            assertEquals("DISPLAY_READ_SCOPE_WROTE_DATA", failure.message); assertFalse(completed)
            assertions().assertUnchanged(image)
        }
    }

    @Test fun cancellationClosesTheBatchRollsBackAndDoesNotPoisonTheNextRead() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); lateinit var escaped: RecurringDisplayReadScope
        val child = launch { display { read ->
            escaped = read
            CountHistoryReader(db, tokens, sessions).readSummaryInTransaction(habit, today, read)
            currentCoroutineContext().cancel(); currentCoroutineContext().ensureActive()
        } }
        child.join(); assertTrue(child.isCancelled)
        assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { escaped.borrow(db, tokens) }.message)
        assertions().assertUnchanged(image)
        assertEquals(2L, CountHistoryReader(db, tokens, sessions).readSummary(habit, today).todayQuantity)
        assertions().assertUnchanged(image)
    }

    @Test fun finalFreshAuthStillRejectsSameAccountSessionReplacementWithoutDatabaseSideEffects() = runBlocking<Unit> {
        ready(); val image = assertions().capture(); decrypts.set(0)
        val failure = rejected { display { read ->
            CountHistoryReader(db, tokens, sessions).readSummaryInTransaction(habit, today, read)
            tokens.saveLoginSession("replacement-access", "replacement-refresh", "member", id(1), false)
        } }
        assertEquals("RECURRING_READ_SESSION_CHANGED", failure.message)
        assertTrue(decrypts.get() >= 2)
        assertions().assertUnchanged(image)
    }

    @Test fun damagedSealKeepsStandaloneRejectionAndNeverRepeatsAConsumerAfterItsException() = runBlocking<Unit> {
        ready(); val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val sentinel = IllegalStateException("consumer sentinel")
        var calls = 0
        val failed = rejected { display { calls++; throw sentinel } }
        assertSame(sentinel, failed); assertEquals(1, calls)
        db.withTransaction { owner.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_trust_root SET mac=zeroblob(32) WHERE id=1")
        } }
        val image = assertions().capture()
        val old = rejected { sessions.exclusive { db.withTransaction {
            kernel().summary(habit, today, requireNotNull(tokens.localCoreWriteAccess()))
        } } }
        val shared = rejected { CountHistoryReader(db, tokens, sessions).readSummary(habit, today) }
        assertEquals("COUNT_TRUST_SEAL_INVALID", old.message)
        assertEquals(old.javaClass, shared.javaClass); assertEquals(old.message, shared.message)
        assertions().assertUnchanged(image)
    }
}
