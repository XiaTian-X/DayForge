package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.withTransaction
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.model.HabitSchedule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete real root cold → first count → outbox → send/ACK/replay paths. No UI,
 * process-death, yearly load or general production-domain support claim. */
@RunWith(AndroidJUnit4::class)
class G0CountRootWriteSafetyTest : G0CountRootWriteFixture() {
    private var weeklyWindow = false
    override suspend fun beforeHouseholdFacts() {
        if (!weeklyWindow) return
        // Synthetic initial structure only, before any fact, source or certified root exists.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            householdHabits = householdHabits.map { row ->
                row.copy(schedule = HabitSchedule.Weekly((1..7).toList()), description = "家庭：每周窗口",
                    bestTime = 495L, planMetadata = requireNotNull(row.planMetadata).copy(
                        targetUnit = "次", preferredLocalTime = "08:15:00")).also { db.habitDao().update(it) }
            }
            habit = householdHabits.first()
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    @Test fun coldFirstAndLaterCountsAcrossBothObjectsMatchOriginalWithoutFullHistoryRebuild() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2)
        val (http, server) = channel { successReply(it) }
        issueWriteRoot(http)
        val issued = head()
        val trace = G0PhaseTrace(driverTiming = false)
        val ledger = G0ColdWorkLedger()
        lateinit var store: G0CountRootStore
        ledger.run { trace.run {
            store = openWriter().store
            val a = seed.habits[0].uuid; val b = seed.habits[1].uuid
            val firstA = store.increase(a)
            val firstB = store.increase(b)
            val secondA = store.increase(a)
            val thirdA = store.increase(a)
            val pending = writerDay(store, a, seed.today)
            assertEquals(3L, pending.day.quantity); assertTrue(pending.day.summary.completedToday)
            assertEquals(1L, writerDay(store, b, seed.today).day.quantity)
            for (saved in listOf(firstA, firstB, secondA, thirdA)) writerAck(store, http, saved)
            assertFalse(pending.valid())
            assertTrue(store.pendingRequestIds().isEmpty())
        } }
        assertNoFullRebuild(trace, ledger)
        assertEquals(4, server.requests.count { it.path.endsWith("/push") })
        assertEquals(6, pageRowCount("completions")); assertEquals(6, pageRowCount("next_request_origins"))
        assertEquals(6, pageRowCount("next_acceptances")); assertEquals(0, pageRowCount("sync_outbox"))
        assertEquals(issued.root.revision + 12, head().root.revision)
        assertWriterMatchesOriginal(store, seed.today)
    }

    @Test fun completelyNewObjectsCanReadWithoutWritesThenIndependentlyCreateTheirFirstDays() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val before = pageNativeState(); val store = openWriter().store
        for (row in seed.habits) {
            val day = writerDay(store, row.uuid, seed.today).day
            assertEquals(0L, day.quantity); assertNull(day.day); assertTrue(day.facts.isEmpty())
            assertNotNull(day.effectivePolicy)
        }
        assertEquals(before, pageNativeState())
        for (row in seed.habits) writerAck(store, http, store.increase(row.uuid))
        assertEquals(2, pageRowCount("count_days")); assertEquals(2, pageRowCount("completions"))
        assertEquals(2, pageRowCount("next_acceptances")); assertEquals(0, pageRowCount("sync_outbox"))
        assertWriterMatchesOriginal(store, seed.today)
    }

    @Test fun unsentFirstDaysSurviveColdOpenAndLaterSendRequiresItsOwnFirstAcceptance() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, server) = channel { successReply(it) }
        issueWriteRoot(http); var store = openWriter().store
        val a1 = store.increase(seed.habits[0].uuid); val a2 = store.increase(seed.habits[0].uuid)
        val b1 = store.increase(seed.habits[1].uuid); val b2 = store.increase(seed.habits[1].uuid)
        val durable = pageNativeState(); store = openWriter().store
        assertEquals(durable, pageNativeState())
        assertEquals(listOf(a1.requestId, a2.requestId, b1.requestId, b2.requestId), store.pendingRequestIds())
        val blocked = rejectedUnchangedRoot { store.send(http, a2.requestId) }
        assertTrue(blocked is NextRequestException)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING, (blocked as NextRequestException).reason)
        assertEquals(0, server.requests.count { it.path.endsWith("/push") })
        writerAck(store, http, a1)
        val cross = rejectedUnchangedRoot { store.send(http, b2.requestId) }
        assertTrue(cross is NextRequestException)
        val crossReason = (cross as NextRequestException).reason
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING, crossReason)
        val beforeOriginal = pageNativeState(); reopenReference()
        val old = rejectedUnchangedRoot { sender(http).sendOperation(access(), b2.requestId) }
        assertTrue(old is NextRequestException)
        assertEquals(crossReason, (old as NextRequestException).reason)
        assertEquals(beforeOriginal, pageNativeState())
        store = openWriter().store
        for (saved in listOf(a2, b1, b2)) writerAck(store, http, saved)
        assertEquals(4, server.requests.count { it.path.endsWith("/push") })
        assertEquals(0, pageRowCount("sync_outbox")); assertWriterMatchesOriginal(store, seed.today)
    }

    @Test fun duplicateCommandAndExactAckReplayNeverAddASecondFactOrAdvanceTheRoot() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); var store = openWriter().store
        val command = store.newCommand(seed.habits[0].uuid); val saved = store.increase(command)
        rejectedUnchangedRoot { store.increase(command) }
        closeFailedRootOwner(); store = openWriter().store
        val delivery = writerAck(store, http, saved); val durable = pageNativeState(); val signed = head()
        assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(durable, pageNativeState()); assertEquals(signed.root, head().root)
        store = openWriter().store
        val found = requireNotNull(store.recover(command))
        assertEquals(saved, found)
        assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(durable, pageNativeState())
        reopenReference()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(durable, pageNativeState())
    }

    @Test fun unknownFirstSaveRecoversTheSameUnsentIdentityWithoutRepeatingBusinessWrites() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val command = store.newCommand(seed.habits[0].uuid); val trace = G0PhaseTrace(driverTiming = false)
        // Arm only at the actual BUSINESS final boundary; the preparatory READ has its own END.
        store.beforeFinalForTest = { endFaults.failAfterNextEndIn(trace) }
        val failure = runCatching { trace.run { store.increase(command) } }.exceptionOrNull()
        assertNotNull(failure); assertTrue(hasCause(requireNotNull(failure), "G0_COMMIT_RESULT_LOST"))
        assertEquals(1, pageRowCount("completions")); assertEquals(1, pageRowCount("count_days"))
        assertEquals(1, pageRowCount("sync_outbox")); val committed = pageNativeState(); head()
        assertTrue(runCatching { store.increase(command) }.isFailure)
        closeFailedRootOwner()
        var recovered = openWriter().store; val saved = requireNotNull(recovered.recover(command))
        assertEquals(command.requestId, saved.requestId); assertEquals(command.factUuid, saved.factUuid)
        assertEquals(command.habitUuid, saved.habitUuid); assertEquals(committed, pageNativeState())
        rejectedUnchangedRoot { recovered.increase(command) }
        closeFailedRootOwner(); recovered = openWriter().store
        writerAck(recovered, http, saved)
        assertEquals(1, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        assertWriterMatchesOriginal(recovered, seed.today)
    }

    @Test fun unknownAckReopensWithExactReplayAndKeepsAnotherObjectsUnsentCommand() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val a = store.increase(seed.habits[0].uuid); val b = store.increase(seed.habits[1].uuid)
        val delivery = requireNotNull(store.send(http, a.requestId)); val trace = G0PhaseTrace(driverTiming = false)
        store.beforeFinalForTest = { endFaults.failAfterNextEndIn(trace) }
        val failure = runCatching { trace.run { store.acknowledge(delivery) } }.exceptionOrNull()
        assertNotNull(failure); assertTrue(hasCause(requireNotNull(failure), "G0_COMMIT_RESULT_LOST"))
        assertEquals(1, pageRowCount("next_acceptances")); assertEquals(1, pageRowCount("sync_outbox"))
        val durable = pageNativeState(); closeFailedRootOwner()
        val recovered = openWriter().store
        assertEquals(listOf(b.requestId), recovered.pendingRequestIds())
        assertEquals(NextOperationAcceptance.REPLAYED, recovered.acknowledge(delivery))
        assertEquals(durable, pageNativeState()); writerAck(recovered, http, b)
        assertEquals(2, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        assertWriterMatchesOriginal(recovered, seed.today)
    }

    @Test fun knownFinalAuthRollbackRecoversAbsenceWithoutInventingAnotherCommand() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val command = store.newCommand(seed.habits[0].uuid); val before = pageNativeState()
        store.beforeFinalForTest = { failDecrypt = true }
        try { rejectedUnchangedRoot { store.increase(command) } } finally { failDecrypt = false }
        assertEquals(before, pageNativeState()); closeFailedRootOwner()
        val recovered = openWriter().store
        assertNull(recovered.recover(command)); assertEquals(before, pageNativeState())
        val saved = recovered.increase(command); assertEquals(command.requestId, saved.requestId)
        writerAck(recovered, http, saved); assertEquals(1, pageRowCount("completions"))
        assertWriterMatchesOriginal(recovered, seed.today)
    }

    @Test fun crossObjectDeliveryAndForgedTransmissionCannotChangeEitherSignedFact() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val a = store.increase(seed.habits[0].uuid); val b = store.increase(seed.habits[1].uuid)
        val da = requireNotNull(store.send(http, a.requestId)); val db = requireNotNull(store.send(http, b.requestId))
        rejectedUnchangedRoot { store.acknowledge(da.copy(requestId = b.requestId)) }
        rejectedUnchangedRoot { store.acknowledge(db.copy(transmissionProof = "0".repeat(64))) }
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(da))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(db))
        assertEquals(0, pageRowCount("sync_outbox")); assertWriterMatchesOriginal(store, seed.today)
    }

    @Test fun coldInflightIsExplicitlyUnsupportedWithoutDeletingTheFrozenRequest() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val saved = store.increase(seed.habits[0].uuid); val delivery = requireNotNull(store.send(http, saved.requestId))
        val before = pageNativeState()
        val failure = rejectedUnchangedRoot { openWriter() }
        assertTrue(hasCause(failure, "INFLIGHT_UNSUPPORTED")); assertEquals(before, pageNativeState())
        closeFailedRootOwner(); reopenReference()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertEquals(1, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        // The original path is valid; its unmaintained P write leaves the candidate root dirty.
        assertTrue(nativeRoot { raw -> raw.rawQuery(G0CountRootSql.readMarker, null).use {
            requireNotNull(G0CountRootSql.captureMarker(it)).dirty
        } })
    }

    @Test fun freshAuthenticationStillProtectsColdAndPreparedReadPublication() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val durable = pageNativeState()
        try { rejectedUnchangedRoot { openWriter { it.beforeColdFinalForTest = { failDecrypt = true } } } }
        finally { failDecrypt = false; closeFailedRootOwner() }
        assertEquals(durable, pageNativeState())
        val store = openWriter().store; var published = false
        store.beforePublishForTest = { failDecrypt = true }
        try { rejectedUnchangedRoot { store.readDay(seed.habits[0].uuid, seed.today) { published = true } } }
        finally { failDecrypt = false }
        assertFalse(published); assertEquals(durable, pageNativeState())
    }
    @Test fun aNonDailyActivityWindowAndCompletePlanFieldsSurviveColdFirstWrite() = runBlocking<Unit> {
        weeklyWindow = true
        val seed = seedHouseholdAccepted(2, daysAgo = listOf(1, 10)); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val saved = store.increase(seed.habits[0].uuid)
        assertEquals(1L, writerDay(store, seed.habits[0].uuid, seed.today).day.quantity)
        writerAck(store, http, saved); assertWriterMatchesOriginal(store, seed.today)
        val row = requireNotNull(db.habitDao().getHabitById(seed.habits[0].id))
        assertEquals(HabitSchedule.Weekly((1..7).toList()), row.schedule)
        assertEquals("家庭：每周窗口", row.description); assertEquals(495L, row.bestTime ?: -1L)
        assertEquals("次", row.planMetadata?.targetUnit); assertEquals("08:15:00", row.planMetadata?.preferredLocalTime)
        // Eight scheduled dates, of which only yesterday and today's new count participate.
        assertEquals(28, row.activityRate)
    }

    @Test fun nonCanonicalAckBusinessChangeIsRejectedByBothKernelsAndCannotOverwriteReceipt() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store
        val saved = store.increase(seed.habits[0].uuid); val delivery = requireNotNull(store.send(http, saved.requestId))
        val result = delivery.result.results.single()
        val altered = result.copy(entity = JsonObject(requireNotNull(result.entity) + ("value" to JsonPrimitive(2))))
        val forged = delivery.copy(result = NextSyncPushResponse(listOf(altered)))
        rejectedUnchangedRoot { store.acknowledge(forged) }
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        val accepted = pageNativeState()
        rejectedUnchangedRoot { store.acknowledge(forged) }
        assertEquals(accepted, pageNativeState()); assertEquals(1, pageRowCount("completions"))
        reopenReference()
        rejectedUnchangedRoot { sender(http).acceptOperation(forged) }
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(accepted, pageNativeState())
    }

}
