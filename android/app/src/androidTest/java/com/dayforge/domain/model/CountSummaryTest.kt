package com.dayforge.domain.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.CompletionEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CountSummaryTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val policy = CountDayPolicy(2, false)

    @Test fun historyOwnsEveryCollectionAndReturnsUnmodifiableViews() {
        val policies = linkedMapOf(today to policy)
        val quantities = linkedMapOf(today to 2L)
        val unknown = linkedSetOf(today.minusDays(1))
        val rows = mutableListOf(row(1, today, 2))
        val history = CountHistory(today, policies, quantities, policy, unknown, rows)
        policies.clear(); quantities.clear(); unknown.clear(); rows.clear()
        assertEquals(mapOf(today to policy), history.policies)
        assertEquals(mapOf(today to 2L), history.quantities)
        assertEquals(setOf(today.minusDays(1)), history.unknownDates)
        assertEquals(listOf(row(1, today, 2)), history.completions)
        assertEquals(2L, history.summary.totalQuantity)
        assertEquals(setOf(today), history.summary.participatingDates)
        assertThrows(UnsupportedOperationException::class.java) {
            (history.quantities as MutableMap)[today] = 7L
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (history.policies as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (history.unknownDates as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (history.completions as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (history.summary.qualifiedDates as MutableSet).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (history.summary.participatingDates as MutableSet).clear()
        }
    }

    @Test fun copyRecalculatesAllDerivedValuesWithoutChangingOriginal() {
        val original = CountHistory(today, mapOf(today to policy), mapOf(today to 2L),
            policy, emptySet(), listOf(row(1, today, 2)))
        val tomorrow = today.plusDays(1)
        val replacement = original.copy(today = tomorrow, policies = mapOf(tomorrow to policy),
            quantities = mapOf(tomorrow to 1L), completions = listOf(row(8, tomorrow, 1)))
        assertEquals(1L, replacement.todayQuantity)
        assertFalse(replacement.completedToday)
        assertTrue(replacement.qualifiedDates.isEmpty())
        assertEquals(tomorrow, replacement.firstDate)
        assertEquals(1L, replacement.summary.totalQuantity)
        assertEquals(setOf(tomorrow), replacement.summary.participatingDates)
        assertEquals(8L, replacement.summary.todayLatestCompletionId)
        assertEquals(2L, original.summary.totalQuantity)
        assertTrue(original.completedToday)
        assertEquals(setOf(today), original.qualifiedDates)
        assertEquals(1L, original.summary.todayLatestCompletionId)
    }

    @Test fun publicConstructorPreservesIndependentQuantitiesAndDetailSemantics() {
        val yesterday = today.minusDays(1)
        val history = CountHistory(today, mapOf(today to policy), mapOf(today to 9L), policy,
            emptySet(), listOf(row(3, yesterday, 4), row(5, today, 1), row(2, today, 7)))
        assertEquals(9L, history.todayQuantity)
        assertEquals(9L, history.summary.totalQuantity)
        assertEquals(today, history.firstDate)
        assertEquals(setOf(yesterday, today), history.summary.participatingDates)
        assertEquals(5L, history.summary.todayLatestCompletionId)
    }

    @Test fun unknownTodayDoesNotBorrowAnotherDayRuleAndZeroRetainsPolicy() {
        val unknown = CountHistory(today, mapOf(today.minusDays(1) to policy), mapOf(today to 8L),
            null, setOf(today), listOf(row(1, today, 8)))
        assertEquals(8L, unknown.todayQuantity)
        assertFalse(unknown.completedToday)
        assertTrue(unknown.qualifiedDates.isEmpty())
        val undone = CountHistory(today, mapOf(today to policy), emptyMap(), policy, emptySet(), emptyList())
        assertEquals(0L, undone.todayQuantity)
        assertEquals(policy, undone.todayPolicy)
        assertEquals(policy, undone.policies[today])
        assertNull(undone.firstDate)
        assertNull(undone.summary.todayLatestCompletionId)
        assertFalse(undone.completedToday)
        // Preserve the public constructor's raw-input contract even for an explicitly supplied zero.
        val explicitZero = undone.copy(quantities = mapOf(today to 0L))
        assertEquals(today, explicitZero.firstDate)
        assertEquals(mapOf(today to 0L), explicitZero.quantities)
        assertTrue(explicitZero.summary.participatingDates.isEmpty())
    }

    @Test fun longQuantitiesRemainExactAndOverflowCannotBecomeWrappedResult() {
        val large = Int.MAX_VALUE.toLong() + 10L
        val history = CountHistory(today, mapOf(today to policy), mapOf(today to large), policy,
            emptySet(), emptyList())
        assertEquals(large, history.summary.totalQuantity)
        val unrepresentable = history.copy(quantities = mapOf(today to Long.MAX_VALUE, today.minusDays(1) to 1L))
        assertEquals(Long.MAX_VALUE, unrepresentable.todayQuantity)
        assertEquals(setOf(today), unrepresentable.qualifiedDates)
        assertEquals(today.minusDays(1), unrepresentable.firstDate)
        assertEquals(unrepresentable, unrepresentable.copy())
        assertThrows(ArithmeticException::class.java) { unrepresentable.summary.totalQuantity }
        assertEquals(large, history.summary.totalQuantity)
    }

    @Test fun copyEqualityHashComponentsAndStringKeepOriginalEightFieldContract() {
        val round = initialChallengeRoundHead("80e3ed1a-929e-4d6b-b0dd-ae717c9eeb34")
        val identity = CountActionIdentity(LocalDataSession(AuthenticationSession("owner", "generation"),
            "server", "epoch"), 1L, round.activityUuid, "device", null, round)
        val history = CountHistory(today, mapOf(today to policy), mapOf(today to 2L), policy,
            emptySet(), listOf(row(1, today, 2)), round, identity)
        val copy = history.copy()
        assertNotSame(history, copy)
        assertEquals(history, copy)
        assertEquals(history.hashCode(), copy.hashCode())
        assertEquals(history.summary, copy.summary)
        assertEquals(history.summary.hashCode(), copy.summary.hashCode())
        val (date, policies, quantities, todayPolicy, unknown, completions, head, action) = copy
        assertEquals(today, date); assertEquals(history.policies, policies)
        assertEquals(history.quantities, quantities); assertEquals(policy, todayPolicy)
        assertEquals(history.unknownDates, unknown); assertEquals(history.completions, completions)
        assertEquals(round, head); assertSame(identity, action)
        assertEquals("CountHistory(today=$today, policies=${history.policies}, quantities=${history.quantities}, " +
            "todayPolicy=$policy, unknownDates=[], completions=${history.completions}, " +
            "roundHead=$round, actionIdentity=$identity)", history.toString())
    }

    @Test fun fromSummaryOwnsFreshDetailsAndPreservesTheCalculatedResult() {
        val old = CountHistory(today, mapOf(today to policy), mapOf(today to 2L), policy,
            emptySet(), listOf(row(1, today, 2)))
        val acknowledged = old.completions.single().copy(createdAt = 999L)
        val freshRows = mutableListOf(acknowledged)
        val details = CountHistory.fromSummary(old.summary, freshRows)
        freshRows.clear()
        assertSame(old.summary, details.summary)
        assertEquals(listOf(acknowledged), details.completions)
        assertNotEquals(old, details)
        assertEquals(details, old.copy(completions = listOf(acknowledged)))
    }

    private fun row(id: Long, date: LocalDate, quantity: Int) = CompletionEntity(id = id,
        habitId = 1L, date = 0L, value = quantity, uuid = "fact-$id", createdAt = 1L,
        recordedTimezone = "UTC", recordedLocalDate = date.toString())
}
