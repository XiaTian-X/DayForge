package com.dayforge.data.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountActionIdentity
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.model.initialChallengeRoundHead
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class CountCalculationBlocksTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val policy = CountDayPolicy(2, false)

    @Test fun unchangedSealedMonthReusesItsResultWhileActiveMonthAddsOneContribution() {
        val september = today.minusMonths(1)
        val facts = listOf(fact(1, september, 3), fact(2, today, 1))
        val policies = mapOf(september to policy, today to policy)
        val old = reconcile(null, facts, policies).state
        val result = reconcile(old, facts + fact(3, today, 4), policies)
        assertSame(old.blockIdentity(YearMonth.from(september)), result.state.blockIdentity(YearMonth.from(september)))
        assertNotSame(old.blockIdentity(YearMonth.from(today)), result.state.blockIdentity(YearMonth.from(today)))
        assertEquals(CountCalculationBlocks.Stats(3, 1, 1, 1, 0, 1, 1), result.stats)
        assertEquals(3, result.state.factCount)
        assertEquals(8L, summary(result.state).totalQuantity)
        assertEquals(4L, summary(old).totalQuantity)
    }

    @Test fun reorderedExactInputReusesAllBlocksAndWholeCalculationState() {
        val facts = listOf(fact(1, today.minusMonths(2), 1), fact(2, today.minusMonths(1), 4), fact(3, today, 2))
        val policies = facts.associate { it.date to policy }
        val old = reconcile(null, facts, policies).state
        val result = reconcile(old, facts.reversed(), policies.toList().reversed().toMap())
        assertSame(old, result.state)
        assertEquals(CountCalculationBlocks.Stats(3, 3, 0, 0, 0, 2, 0), result.stats)
    }

    @Test fun undoRemovesWholeValueAndLastDateWithoutRemovingItsCapturedPolicy() {
        val earlier = today.minusMonths(1)
        val facts = listOf(fact(1, earlier, 7), fact(2, today, 2), fact(3, today, 5))
        val policies = mapOf(earlier to policy, today to policy)
        val old = reconcile(null, facts, policies).state
        val undo = reconcile(old, facts.take(2), policies)
        assertEquals(2L, summary(undo.state).todayQuantity)
        assertEquals(CountCalculationBlocks.Stats(2, 1, 1, 0, 1, 1, 1), undo.stats)
        val removeFirst = reconcile(undo.state, listOf(facts[1]), policies)
        assertEquals(today, summary(removeFirst.state).firstDate)
        assertFalse(summary(removeFirst.state).quantities.containsKey(earlier))
        assertEquals(policy, summary(removeFirst.state).policies[earlier])
        val empty = reconcile(removeFirst.state, emptyList(), policies)
        assertNull(summary(empty.state).firstDate)
        assertNull(summary(empty.state).todayLatestCompletionId)
        assertEquals(0L, summary(empty.state).totalQuantity)
        assertTrue(summary(empty.state).participatingDates.isEmpty())
        assertEquals(policies, summary(empty.state).policies)
    }

    @Test fun sameIdentityDateAndQuantityChangesInvalidateExactlyTheirMonths() {
        val earlier = today.minusMonths(1)
        val oldFacts = listOf(fact(1, earlier, 3), fact(2, today, 2))
        val policies = mapOf(earlier to policy, today to policy)
        val old = reconcile(null, oldFacts, policies).state
        val moved = oldFacts[0].copy(date = today, quantity = 8)
        val result = reconcile(old, listOf(moved, oldFacts[1]), policies)
        assertEquals(CountCalculationBlocks.Stats(2, 0, 2, 1, 1, 0, 1), result.stats)
        assertEquals(mapOf(today to 10L), summary(result.state).quantities)
        assertEquals(today, summary(result.state).firstDate)
        assertNotSame(old.blockIdentity(YearMonth.from(earlier)), result.state.blockIdentity(YearMonth.from(earlier)))
        assertEquals(mapOf(earlier to 3L, today to 2L), summary(old).quantities)
    }

    @Test fun replacingMembersWithEqualTotalsCannotPassAsAnUnchangedBlock() {
        val oldFacts = listOf(fact(1, today, 2), fact(2, today, 4))
        val policies = mapOf(today to policy)
        val old = reconcile(null, oldFacts, policies).state
        val replaced = reconcile(old, listOf(oldFacts[0], oldFacts[1].copy(uuid = "replacement", id = 3)), policies)
        assertEquals(summary(old).quantities, summary(replaced.state).quantities)
        assertEquals(3L, summary(replaced.state).todayLatestCompletionId)
        assertEquals(CountCalculationBlocks.Stats(2, 0, 1, 1, 1, 0, 1), replaced.stats)
        assertNotSame(old.blockIdentity(YearMonth.from(today)), replaced.state.blockIdentity(YearMonth.from(today)))
    }

    @Test fun policyAndUnknownChangesRequalifyWithoutInventingFactContributions() {
        val earlier = today.minusMonths(1)
        val facts = listOf(fact(1, earlier, 2), fact(2, today, 2))
        val old = reconcile(null, facts, mapOf(earlier to policy), setOf(today)).state
        val unknownSummary = old.summary(today, null, null, null)
        assertEquals(2L, unknownSummary.todayQuantity)
        assertFalse(unknownSummary.completedToday)
        assertEquals(setOf(earlier), unknownSummary.qualifiedDates)
        val result = reconcile(old, facts, mapOf(earlier to policy, today to CountDayPolicy(3, true)))
        assertEquals(CountCalculationBlocks.Stats(2, 1, 1, 0, 0, 1, 1), result.stats)
        assertTrue(summary(result.state).unknownDates.isEmpty())
        assertEquals(setOf(earlier), summary(result.state).qualifiedDates)
        val qualified = reconcile(result.state, facts, mapOf(earlier to policy, today to CountDayPolicy(1, true)))
        assertEquals(setOf(earlier, today), summary(qualified.state).qualifiedDates)
        assertEquals(0, qualified.stats.addedContributions)
        assertEquals(0, qualified.stats.removedContributions)
    }

    @Test fun ackMetadataUsesFreshDetailsWithoutRecalculatingNumericState() {
        val original = row(fact(1, today, 2))
        val accepted = original.copy(createdAt = original.createdAt + 800L)
        val policies = mapOf(today to policy)
        val old = reconcile(null, listOf(asFact(original)), policies).state
        val result = reconcile(old, listOf(asFact(accepted)), policies)
        assertSame(old, result.state)
        assertEquals(CountCalculationBlocks.Stats(1, 1, 0, 0, 0, 0, 0), result.stats)
        val details = CountHistory.fromSummary(summary(result.state), listOf(accepted))
        assertEquals(accepted, details.completions.single())
        assertEquals(2L, details.todayQuantity)
        assertEquals(accepted.id, details.summary.todayLatestCompletionId)
    }

    @Test fun freshViewCanChangeDayRuleRoundAndIdentityWithoutChangingArithmetic() {
        val facts = listOf(fact(1, today, 2), fact(2, today.plusDays(1), 1))
        val state = reconcile(null, facts, facts.associate { it.date to policy }).state
        val round = initialChallengeRoundHead("80e3ed1a-929e-4d6b-b0dd-ae717c9eeb34")
        val identity = CountActionIdentity(LocalDataSession(AuthenticationSession("owner", "new-generation"),
            "server", "epoch"), 1L, round.activityUuid, "device", null, round)
        val initial = state.summary(today, policy, null, null)
        val next = state.summary(today.plusDays(1), CountDayPolicy(1, true), round, identity)
        assertSame(initial.quantities, next.quantities)
        assertEquals(1L, next.todayQuantity)
        assertTrue(next.completedToday)
        assertEquals(2L, next.todayLatestCompletionId)
        assertSame(identity, next.actionIdentity)
        assertEquals(round, next.roundHead)
        assertNull(initial.actionIdentity)
        assertNull(initial.roundHead)
        val unknown = state.summary(today, null, null, null)
        assertFalse(unknown.completedToday)
    }

    @Test fun invalidOrOverflowingCandidateNeverChangesPriorState() {
        val originalFacts = listOf(fact(1, today, 2))
        val policies = mapOf(today to policy)
        val old = reconcile(null, originalFacts, policies).state
        val invalid = listOf(
            originalFacts + fact(1, today.minusDays(1), 1),
            originalFacts + fact(2, today, 1).copy(uuid = originalFacts.single().uuid),
            listOf(fact(1, today, 0)), listOf(fact(1, today, -1)),
            listOf(fact(1, today, 2).copy(uuid = " "))
        )
        invalid.forEach { facts ->
            assertThrows(IllegalArgumentException::class.java) { reconcile(old, facts, policies) }
            assertEquals(2L, summary(old).totalQuantity)
        }
        assertThrows(ArithmeticException::class.java) {
            reconcile(old, listOf(fact(1, today, Long.MAX_VALUE), fact(2, today, 1)), policies)
        }
        assertThrows(ArithmeticException::class.java) {
            reconcile(old, listOf(fact(1, today, Long.MAX_VALUE), fact(2, today.minusMonths(1), 1)), policies)
        }
        assertEquals(2L, summary(old).totalQuantity)
        assertSame(old, reconcile(old, originalFacts, policies).state)
    }

    @Test fun signedSqliteIdsRetainTheOriginalMaximumIdSelection() {
        val facts = listOf(fact(Long.MIN_VALUE, today, 1), fact(-3, today, 2), fact(0, today, 4))
        val policies = mapOf(today to policy)
        val state = reconcile(null, facts, policies).state
        assertEquals(0L, summary(state).todayLatestCompletionId)
        val removed = reconcile(state, facts.take(2), policies).state
        assertEquals(-3L, summary(removed).todayLatestCompletionId)
        assertEquals(3L, summary(removed).todayQuantity)
    }

    @Test fun mutableInputsAndFailedWritesCannotMutatePublishedCalculation() {
        val facts = mutableListOf(fact(1, today, 3))
        val policies = linkedMapOf(today to policy)
        val unknown = linkedSetOf(today.minusDays(1))
        val state = reconcile(null, facts, policies, unknown).state
        facts.clear(); policies.clear(); unknown.clear()
        val view = summary(state)
        assertEquals(3L, view.totalQuantity)
        assertEquals(mapOf(today to policy), view.policies)
        assertEquals(setOf(today.minusDays(1)), view.unknownDates)
        assertThrows(UnsupportedOperationException::class.java) { (view.quantities as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.qualifiedDates as MutableSet).clear() }
    }

    @Test fun mixedEditsAcrossMonthsMatchIndependentFullRecomputationAndExistingConsumers() {
        val dates = listOf(today.minusMonths(2), today.minusMonths(1), today.minusDays(2),
            today.minusDays(1), today, today.plusDays(1))
        val policies = dates.associateWith { CountDayPolicy(if (it == today) 3 else 2, it == today) }
        var facts = dates.mapIndexed { index, date -> fact(index.toLong() + 1, date, if (index % 2 == 0) 2 else 1) }
        var previous: CountCalculationBlocks.State? = null
        repeat(24) { step ->
            facts = when (step % 4) {
                0 -> facts + fact(100L + step, dates[step % dates.size], step.toLong() + 1)
                1 -> facts.mapIndexed { index, fact -> if (index == 0) fact.copy(quantity = fact.quantity + 1) else fact }
                2 -> facts.mapIndexed { index, fact -> if (index == 0) fact.copy(date = dates[(step + 1) % dates.size]) else fact }
                else -> facts.drop(1)
            }
            val result = reconcile(previous, facts, policies)
            assertMatchesFullReference(facts, policies, emptySet(),
                result.state.summary(today, policies[today], null, null))
            previous = result.state
        }
    }

    private fun assertMatchesFullReference(facts: List<CountCalculationBlocks.Fact>,
        policies: Map<LocalDate, CountDayPolicy>, unknown: Set<LocalDate>, actual: CountSummary) {
        val quantities = facts.groupBy { it.date }.mapValues { (_, values) ->
            values.fold(0L) { total, fact -> Math.addExact(total, fact.quantity) }
        }
        val qualified = quantities.filter { (date, quantity) ->
            policies[date]?.let { quantity >= it.targetValue } == true
        }.keys
        val participating = facts.map { it.date }.toSet()
        val reference = CountHistory(today, policies, quantities, policies[today], unknown, facts.map(::row))
        assertEquals(reference.summary, actual)
        assertEquals(reference.completedToday, actual.completedToday)
        assertEquals(quantities, actual.quantities)
        assertEquals(quantities.values.fold(0L, Math::addExact), actual.totalQuantity)
        assertEquals(qualified, actual.qualifiedDates)
        assertEquals(participating, actual.participatingDates)
        assertEquals(quantities.keys.minOrNull(), actual.firstDate)
        assertEquals(facts.filter { it.date == today }.maxOfOrNull { it.id }, actual.todayLatestCompletionId)
        assertEquals(StreakCalculator.currentFromBusinessDates(qualified, today),
            StreakCalculator.currentFromBusinessDates(actual.qualifiedDates, today))
        assertEquals(StreakCalculator.bestFromBusinessDates(qualified),
            StreakCalculator.bestFromBusinessDates(actual.qualifiedDates))
        val zone = ZoneOffset.UTC
        val habit = HabitEntity(id = 1, name = "count", habitType = HabitType.COUNTING,
            iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Daily, targetCycles = 90,
            failMode = FailMode.STRICT, createdAt = today.minusYears(1).atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(FailureCheckerUtils.countHasFailed(habit, reference, zone),
            FailureCheckerUtils.countHasFailed(habit, actual, zone))
        val now = today.atTime(12, 0).toInstant(zone).toEpochMilli()
        assertEquals(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, participating.toList(), now, zone),
            ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, actual.participatingDates.toList(), now, zone))
    }

    private fun reconcile(previous: CountCalculationBlocks.State?, facts: List<CountCalculationBlocks.Fact>,
        policies: Map<LocalDate, CountDayPolicy>, unknown: Set<LocalDate> = emptySet()) =
        CountCalculationBlocks.reconcile(previous, facts, policies, unknown, today)

    private fun summary(state: CountCalculationBlocks.State) = state.summary(today, policy, null, null)

    private fun fact(id: Long, date: LocalDate, quantity: Long) =
        CountCalculationBlocks.Fact("fact-$id", id, date, quantity)

    private fun row(fact: CountCalculationBlocks.Fact) = CompletionEntity(id = fact.id, habitId = 1L,
        date = 0L, value = Math.toIntExact(fact.quantity), uuid = fact.uuid, createdAt = 1L,
        recordedTimezone = "UTC", recordedLocalDate = fact.date.toString())

    private fun asFact(row: CompletionEntity) = CountCalculationBlocks.Fact(row.uuid, row.id,
        LocalDate.parse(row.recordedLocalDate), row.value.toLong())
}
