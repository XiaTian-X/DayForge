package com.dayforge.domain.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.*
import com.dayforge.data.repository.*
import com.dayforge.domain.model.*
import io.mockk.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInServiceRefreshTest {
    private val context = mockk<Context>()
    private val repository = mockk<HabitRepository>()
    private val service = CheckInService(repository, mockk(), mockk())
    private val habit = HabitEntity(id = 7, name = "Count", habitType = HabitType.COUNTING,
        iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 1,
        targetCycles = 1, completionPolicy = "recurring",
        appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"))

    private fun prepare(): CompletionActionSnapshot {
        val captured = CompletionActionSnapshot(ObjectEditSnapshot(habit, null))
        coEvery { repository.prepareCompletion(habit.id, any()) } returns captured
        coEvery { repository.getHabitForEditing(habit.id) } returns ObjectEditSnapshot(habit, null)
        coEvery { repository.getHabitById(habit.id) } returns habit
        coEvery { repository.logCompletion(context, habit.id, 1, null, habit.uuid) } returns 10
        return captured
    }

    private fun projection(row: HabitEntity = habit, count: CountHistory? = null,
        check: CheckHistory? = null): RecurringCompletionSnapshot {
        val completed = count?.completedToday ?: requireNotNull(check).completedToday
        val progress = count?.qualifiedDates?.size ?: requireNotNull(check).qualifiedDates.size
        return RecurringCompletionSnapshot(row, mockk(), check, count,
            WidgetFactSnapshot(row, mockk(), count, completed, progress))
    }

    private fun completedCount(): CountHistory {
        val today = LocalDate.now()
        val policy = CountDayPolicy(1, false)
        return CountHistory(today, mapOf(today to policy), mapOf(today to 1L), policy, emptySet(), emptyList())
    }

    private fun assertOutcome(result: CheckInResult, completed: Boolean, progress: Int, goalReached: Boolean,
        expected: RecurringCompletionSnapshot, recorded: Boolean = true) {
        assertTrue(result is CheckInResult.Success)
        result as CheckInResult.Success
        assertEquals(completed, result.completed)
        assertEquals(progress, result.progress)
        assertEquals(goalReached, result.goalReached)
        assertEquals(recorded, result.recorded)
        assertSame(expected.followUp, result.followUp)
    }

    @Test fun postCommitRaceRetriesOnlyFreshReadsAndWritesExactlyOnce() = runTest {
        val captured = prepare()
        val after = projection(count = completedCount())
        var reads = 0
        coEvery { repository.readCompletionAfter(captured, true, 10L) } coAnswers {
            if (reads++ < 2) throw HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
            after
        }
        assertOutcome(service.incrementCount(context, habit.id), true, 1, true, after)
        assertEquals(3, reads)
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
        coVerify(exactly = 1) { repository.getHabitById(habit.id) }
        coVerify(exactly = 0) { repository.getCountHistory(any()) }
    }

    @Test fun persistentRaceAndSecurityFailureAreReportedAsCommittedWithoutInventingProgress() = runTest {
        for (race in listOf(true, false)) {
            clearMocks(repository)
            val captured = prepare()
            var reads = 0
            coEvery { repository.readCompletionAfter(captured, true, 10L) } coAnswers {
                reads++
                if (race) throw HabitReadSnapshotChanged("COUNT_ACTIVITY_CHANGED")
                else throw IllegalStateException("COUNT_SESSION_CHANGED")
            }
            try {
                service.incrementCount(context, habit.id)
                fail("A failed projection must not become a success with invented progress")
            } catch (error: CommittedActionRefreshFailed) {
                assertEquals(if (race) "COUNT_ACTIVITY_CHANGED" else "COUNT_SESSION_CHANGED", error.cause?.message)
            }
            assertEquals(if (race) 4 else 1, reads)
            coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
        }
    }

    @Test fun writeFailureIsNotMisclassifiedAsCommitted() = runTest {
        prepare()
        coEvery { repository.logCompletion(context, habit.id, 1, null, habit.uuid) } throws IllegalStateException("WRITE_DENIED")
        try { service.incrementCount(context, habit.id); fail("Write must fail") }
        catch (error: IllegalStateException) { assertEquals("WRITE_DENIED", error.message) }
        coVerify(exactly = 0) { repository.readCompletionAfter(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getCountHistory(any()) }
    }

    @Test fun cancellationIsNeverWrappedOrRetried() = runTest {
        val captured = prepare()
        coEvery { repository.readCompletionAfter(captured, true, 10L) } throws CancellationException("cancelled")
        try { service.incrementCount(context, habit.id); fail("Cancellation must escape") }
        catch (expected: CancellationException) { assertEquals("cancelled", expected.message) }
        coVerify(exactly = 1) { repository.readCompletionAfter(captured, true, 10L) }
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
    }

    @Test fun decrementUsesPreparedAndPostCommitSnapshotsWithoutRereadingLatestId() = runTest {
        prepare()
        val today = LocalDate.now()
        val policy = CountDayPolicy(1, false)
        val fact = com.dayforge.data.local.entity.CompletionEntity(id = 10, habitId = habit.id,
            date = System.currentTimeMillis(), recordedLocalDate = today.toString())
        val before = CountHistory(today, mapOf(today to policy), mapOf(today to 1L), policy, emptySet(), listOf(fact))
        val captured = CompletionActionSnapshot(ObjectEditSnapshot(habit, null), projection(count = before))
        val after = projection(count = before.copy(quantities = emptyMap(), completions = emptyList()))
        coEvery { repository.prepareCompletion(habit.id, true) } returns captured
        coEvery { repository.readCompletionAfter(captured, false, null) } returns after
        coEvery { repository.undoCompletion(context, 10, authority = null) } just Runs
        assertOutcome(service.decrementCount(context, habit.id), false, 0, false, after)
        coVerify(exactly = 1) { repository.prepareCompletion(habit.id, true) }
        coVerify(exactly = 1) { repository.readCompletionAfter(captured, false, null) }
        coVerify(exactly = 0) { repository.getCountHistory(habit) }
        coVerify(exactly = 0) { repository.getTodayCompletionId(any()) }
        coVerify(exactly = 0) { repository.getTodayCompletionCount(any()) }
        coVerify(exactly = 1) { repository.undoCompletion(context, 10, authority = null) }
    }

    @Test fun checkUndoUsesTwoSnapshotsAndRetainsOtherSameDayFacts() = runTest {
        val check = habit.copy(habitType = HabitType.CHECK_IN)
        coEvery { repository.getHabitForEditing(check.id) } returns ObjectEditSnapshot(check, null)
        coEvery { repository.getHabitById(check.id) } returns check
        val today = LocalDate.now()
        val first = com.dayforge.data.local.entity.CompletionEntity(id = 10, habitId = check.id,
            date = System.currentTimeMillis(), recordedLocalDate = today.toString())
        val latest = first.copy(id = 11)
        val captured = CompletionActionSnapshot(ObjectEditSnapshot(check, null),
            projection(check, check = CheckHistory(today, listOf(first, latest), null)))
        val after = projection(check, check = CheckHistory(today, listOf(first), null))
        coEvery { repository.prepareCompletion(check.id, true) } returns captured
        coEvery { repository.readCompletionAfter(captured, false, null) } returns after
        coEvery { repository.undoCompletion(context, 11, authority = null) } just Runs
        assertOutcome(service.toggleCheckIn(context, check.id), true, 1, false, after)
        coVerify(exactly = 1) { repository.prepareCompletion(check.id, true) }
        coVerify(exactly = 1) { repository.readCompletionAfter(captured, false, null) }
        coVerify(exactly = 0) { repository.getCheckHistory(check) }
        coVerify(exactly = 0) { repository.getTodayCompletionCount(any()) }
        coVerify(exactly = 0) { repository.getTodayCompletionId(any()) }
        coVerify(exactly = 1) { repository.undoCompletion(context, 11, authority = null) }
    }

    @Test fun noOpCountDoesNotAnnounceGoalOrClaimANewRecord() = runTest {
        val captured = prepare()
        val after = projection(count = completedCount())
        coEvery { repository.logCompletion(context, habit.id, 1, null, habit.uuid) } returns 0
        coEvery { repository.readCompletionAfter(captured, false, null) } returns after
        assertOutcome(service.incrementCount(context, habit.id), true, 1, false, after, recorded = false)
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
        coVerify(exactly = 0) { repository.readCompletionAfter(any(), true, any()) }
    }

    @Test fun queuedActionCapturesOriginalTicketBeforeWaitingAndDoesNotPrepareAgain() = runTest {
        val captured = prepare()
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val queuedCaptured = CompletableDeferred<Unit>()
        val queuedAuthority = mockk<ObjectEditAuthority>()
        val queued = ObjectEditSnapshot(habit, queuedAuthority)
        coEvery { repository.getHabitForEditing(habit.id) } coAnswers {
            queuedCaptured.complete(Unit)
            queued
        }
        coEvery { repository.logCompletion(context, habit.id, 1, null, habit.uuid) } coAnswers {
            firstWriteEntered.complete(Unit)
            releaseFirstWrite.await()
            10L
        }
        coEvery { repository.logCompletion(context, habit.id, 1, queuedAuthority, habit.uuid) } throws
            IllegalStateException("ORIGINAL_TICKET_EXPIRED")
        val after = projection(count = completedCount())
        coEvery { repository.readCompletionAfter(captured, true, 10L) } returns after
        val first = async { service.incrementCount(context, habit.id) }
        runCurrent()
        assertTrue(firstWriteEntered.isCompleted)
        val second = async { runCatching { service.incrementCount(context, habit.id) } }
        runCurrent()
        assertTrue(queuedCaptured.isCompleted)
        assertFalse(second.isCompleted)
        // A new plan is visible by the time the queued action obtains its lock. The repository
        // must still receive the original captured ticket and reject it, never a fresh ticket.
        coEvery { repository.getHabitById(habit.id) } returns habit.copy(targetCycles = 2)
        releaseFirstWrite.complete(Unit)
        assertOutcome(first.await(), true, 1, true, after)
        assertEquals("ORIGINAL_TICKET_EXPIRED", second.await().exceptionOrNull()?.message)
        coVerify(exactly = 1) { repository.prepareCompletion(habit.id, false) }
        coVerify(exactly = 1) { repository.getHabitForEditing(habit.id) }
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, queuedAuthority, habit.uuid) }
    }

    @Test fun failedPreparationReleasesActionLockForTheNextFastPath() = runTest {
        val captured = prepare()
        var prepares = 0
        coEvery { repository.prepareCompletion(habit.id, false) } coAnswers {
            if (prepares++ == 0) throw IllegalStateException("PREPARE_FAILED")
            captured
        }
        val after = projection(count = completedCount())
        coEvery { repository.readCompletionAfter(captured, true, 10L) } returns after
        try { service.incrementCount(context, habit.id); fail("Preparation must fail") }
        catch (error: IllegalStateException) { assertEquals("PREPARE_FAILED", error.message) }
        assertOutcome(service.incrementCount(context, habit.id), true, 1, true, after)
        assertEquals(2, prepares)
        coVerify(exactly = 0) { repository.getHabitForEditing(any()) }
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
    }

    @Test fun cancelledPreparationReleasesActionLockWithoutWritingOrReplaying() = runTest {
        val captured = prepare()
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var prepares = 0
        coEvery { repository.prepareCompletion(habit.id, false) } coAnswers {
            if (prepares++ == 0) {
                entered.complete(Unit)
                never.await()
            }
            captured
        }
        val after = projection(count = completedCount())
        coEvery { repository.readCompletionAfter(captured, true, 10L) } returns after
        val cancelled = async { service.incrementCount(context, habit.id) }
        runCurrent()
        assertTrue(entered.isCompleted)
        cancelled.cancelAndJoin()
        coVerify(exactly = 0) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
        assertOutcome(service.incrementCount(context, habit.id), true, 1, true, after)
        assertEquals(2, prepares)
        coVerify(exactly = 0) { repository.getHabitForEditing(any()) }
        coVerify(exactly = 1) { repository.logCompletion(context, habit.id, 1, null, habit.uuid) }
    }

    @Test fun checkIdentityFromAnotherAccountIsRejectedBeforeAnyFactWrite() = runTest {
        val check = habit.copy(habitType = HabitType.CHECK_IN)
        val account = com.dayforge.data.local.LocalDataSession(
            com.dayforge.data.local.AuthenticationSession("account", "generation"), null, null)
        val other = account.copy(authentication = account.authentication.copy(userId = "other-account"))
        val plan = kotlinx.serialization.json.JsonObject(emptyMap())
        val authority = ObjectEditAuthority(account, check.uuid, "plan_node", plan,
            capturedDeviceId = "device", completionDeviceCaptured = true)
        val captured = CompletionActionSnapshot(ObjectEditSnapshot(check, authority))
        val stale = CheckActionIdentity(other, check.id, check.uuid, "device", plan, null)
        coEvery { repository.prepareCompletion(check.id, true) } returns captured
        coEvery { repository.getHabitById(check.id) } returns check
        try {
            service.toggleCheckIn(context, check.id, check.uuid, identity = stale)
            fail("An old display must not adopt the freshly captured account ticket")
        } catch (error: IllegalStateException) {
            assertEquals("CHECK_ACTION_EXPIRED", error.message)
        }
        coVerify(exactly = 0) { repository.getCheckHistory(any()) }
        coVerify(exactly = 0) { repository.readCompletionAfter(any(), any(), any()) }
        coVerify(exactly = 0) {
            repository.logCompletion(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { repository.undoCompletion(any(), any(), any(), any(), any(), any(), any()) }
    }
}
