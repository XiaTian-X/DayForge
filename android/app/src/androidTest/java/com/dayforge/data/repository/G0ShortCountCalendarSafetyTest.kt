package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Calendar distribution only. Single owner, existing current day, and 128 cap unchanged. */
@RunWith(AndroidJUnit4::class)
open class G0ShortCountCalendarSafetyTest : G0ShortCountCalendarFixture() {
    @Test fun acceptedHistoricalDaysAndCurrentAppendAckReplayMatchOriginalCompletely() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(30L, 7L, 7L, 1L, 0L, 0L))
        val store = openCount()
        val initial = view(store)
        assertEquals(seed.reference, initial.history)
        val saved = store.increase()
        val pending = view(store, saved.factId)
        val expected = seed.quantities + (seed.today to (seed.quantities.getValue(seed.today) + 1L))
        assertEquals(expected, pending.history.quantities)
        assertEquals(seed.reference.policies, pending.history.policies)
        assertEquals(seed.reference.completions.toSet(),
            pending.history.completions.filter { it.id != saved.factId }.toSet())
        assertEquals(saved.factUuid, pending.followUp.claim.completionUuid)
        assertEquals(seed.factIds.size + 1, pageRowCount("completions"))
        assertEquals(1, pageRowCount("sync_outbox"))
        assertEquals(seed.requestIds.size + 1, pageRowCount("next_request_origins"))
        assertEquals(seed.requestIds.size, pageRowCount("next_acceptances"))

        val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(store.send(http, saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        val accepted = view(store, saved.factId)
        assertEquals(expected, accepted.history.quantities)
        assertEquals(seed.reference.policies, accepted.history.policies)
        assertEquals(seed.reference.completions.toSet(),
            accepted.history.completions.filter { it.id != saved.factId }.toSet())
        assertFalse(pending.valid())
        assertEquals(pending.presentationRevision, accepted.presentationRevision)
        assertTrue(accepted.proofRevision > pending.proofRevision)
        val state = pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(state, pageNativeState())
        assertEquals(0, pageRowCount("sync_outbox"))
        assertEquals(seed.requestIds.size + 1, pageRowCount("next_acceptances"))

        reopenReference()
        sameAsOriginal(accepted)
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(state, pageNativeState())
        val recovered = openCount()
        assertEquals(accepted.history, view(recovered).history)
        assertEquals(state, pageNativeState())
    }

    @Test fun nonCurrentDayPolicyCorruptionCannotBecomeColdCertified() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(9L, 1L, 0L))
        val oldDay = seed.dates.first()
        assertNotEquals(seed.today, oldDay)
        corruptCalendarFile("UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=? AND localDate=?",
            arrayOf(habit.id, oldDay.toString()))
        val corrupted = pageNativeState()
        assertTrue(runCatching { CountHistoryReader(db, tokens, sessions).read(habit, seed.today) }.isFailure)
        assertEquals(corrupted, pageNativeState())
        assertCalendarColdRejectedWithoutWrites()
    }

    @Test fun nonCurrentDayBirthSourceHashChangeCannotBecomeColdCertified() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(9L, 1L, 0L))
        assertNotEquals(seed.today, seed.dates.first())
        // This is the OLD DAY'S FIRST source. NextCountDayStore.read validates the
        // retained day.originHash against the entire current origin row, including
        // sourceHash. This does not claim that a generic accepted non-birth source
        // receives the same historical sourceHash check from CountHistoryReader.
        corruptCalendarFile("UPDATE next_request_origins SET sourceHash=? WHERE kind=? AND requestId=?",
            arrayOf("0".repeat(64), NEXT_OPERATION, seed.requestIds.first()))
        val corrupted = pageNativeState()
        assertTrue(runCatching { CountHistoryReader(db, tokens, sessions).read(habit, seed.today) }.isFailure)
        assertEquals(corrupted, pageNativeState())
        assertCalendarColdRejectedWithoutWrites()
    }

    @Test fun externalOldFactChangeInvalidatesWarmReadAndWriteWithoutPublishing() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(30L, 1L, 0L))
        val store = openCount()
        val originalView = view(store)
        assertNotEquals(seed.today, seed.dates.first())
        corruptCalendarFile("UPDATE completions SET createdAt=createdAt+1 WHERE id=?",
            arrayOf(seed.factIds.first()))
        val corrupted = pageNativeState()
        var published = false
        // The cheap View predicate does not poll independent-connection data_version.
        // Detection is promised at the next guarded transaction, before publication
        // or mutation; that failed entry then retires this owner's prior views.
        assertTrue(runCatching { store.readAndPublish { published = true } }.isFailure)
        assertFalse(published)
        assertFalse(originalView.valid())
        assertEquals(corrupted, pageNativeState())
        assertTrue(runCatching { store.increase() }.isFailure)
        assertEquals(corrupted, pageNativeState())
    }

    @Test fun directWriteDetectsExternalOldFactChangeWithoutAPrecedingRead() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(30L, 1L, 0L))
        val store = openCount()
        corruptCalendarFile("UPDATE completions SET createdAt=createdAt+1 WHERE id=?",
            arrayOf(seed.factIds.first()))
        val corrupted = pageNativeState()
        assertTrue(runCatching { store.increase() }.isFailure)
        assertEquals(corrupted, pageNativeState())
        assertEquals(3, pageRowCount("completions"))
        assertEquals(0, pageRowCount("sync_outbox"))
    }

    @Test fun acceptedPastDaysDoNotAuthorizeMissingCurrentDay() = runBlocking<Unit> {
        val seed = seedCalendarAccepted(listOf(30L, 7L, 1L))
        assertFalse(seed.today in seed.reference.policies)
        assertEquals(0L, seed.reference.todayQuantity)
        val before = pageNativeState()
        assertEquals("G0_COUNT_EXISTING_DAY_REQUIRED", runCatching { openCount() }.exceptionOrNull()?.message)
        assertEquals(before, pageNativeState())
        assertEquals(3, pageRowCount("completions"))
        assertEquals(0, pageRowCount("sync_outbox"))
    }
}
