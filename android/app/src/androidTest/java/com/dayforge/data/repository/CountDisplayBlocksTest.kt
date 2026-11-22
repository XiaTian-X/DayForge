package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountSummary
import com.dayforge.domain.model.initialChallengeRoundHead
import com.dayforge.domain.service.StreakCalculator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Strict pure encoding/maintenance checks. Map lookups are calculation inputs, not fake
 * authenticated database evidence; the later physical root/native/ACK classes remain required. */
@RunWith(AndroidJUnit4::class)
class CountDisplayBlocksTest {
    private val zone = ZoneId.of("Etc/UTC")
    private val uuid = "5c09c807-dcb5-4c2e-bb18-1baf3cfb5141"
    private val head = initialChallengeRoundHead(uuid)
    private val habit = HabitEntity(id = 1, uuid = uuid, name = "compact", habitType = HabitType.COUNTING,
        iconResId = 0, colorHex = "#FFFFFF", schedule = HabitSchedule.Daily, targetValue = 2, targetCycles = 4,
        createdAt = LocalDate.of(2023, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
    private val scope = CountDisplayBlocks.Scope(uuid, head)
    private fun body(month: YearMonth, quantities: Map<LocalDate, Long>, policies: Set<LocalDate> = quantities.keys) = buildJsonObject {
        put("habit_uuid", uuid); put("month", month.toString()); put("fact_count", quantities.size)
        put("quantities", buildJsonObject { quantities.toSortedMap().forEach { (date, quantity) -> put(date.toString(), quantity) } })
        put("policies", buildJsonObject { policies.sorted().forEach { put(it.toString(), CountDayPolicy(2, false).toJson()) } })
        put("unknown_dates", JsonArray(emptyList()))
        put("latest_ids", buildJsonObject { quantities.keys.sorted().forEach { put(it.toString(), it.toEpochDay()) } })
    }
    @Test fun issueAndSingleYearAdvanceMatchOldFullValuesWithoutReadingOtherYearBodies() {
        val first = YearMonth.of(2023, 12); val second = YearMonth.of(2024, 1)
        val oldBodies = mapOf(first to body(first, mapOf(first.atEndOfMonth() to 2L)),
            second to body(second, mapOf(second.atDay(1) to 1L)))
        val issued = CountDisplayBlocks.issue(habit, head, oldBodies, zone).toMutableMap()
        val before = CountDisplayBlocks.global(issued.getValue(scope.globalKey()).jsonObject, scope, habit)
        val next = body(second, mapOf(second.atDay(1) to 2L))
        val reads = mutableListOf<String>()
        val changes = CountDisplayBlocks.advance(listOf(CountDisplayBlocks.Change(habit, head, second, next))) {
            reads += it; issued[it]
        }
        assertFalse(reads.contains(scope.yearKey(2023)))
        assertTrue(reads.contains(scope.yearKey(2024)))
        assertFalse(changes.containsKey(scope.yearKey(2023)))
        val oldYear = issued.getValue(scope.yearKey(2023)); issued.putAll(changes)
        assertSame(oldYear, issued.getValue(scope.yearKey(2023)))
        val after = CountDisplayBlocks.global(issued.getValue(scope.globalKey()).jsonObject, scope, habit)
        val oldOracle = CountSummary.Values.combine(listOf(CountTrustMembership.values(oldBodies.getValue(first)), CountTrustMembership.values(next)))
        assertEquals(oldOracle.qualifiedDates.size, after.fold.qualifiedCount)
        assertEquals(StreakCalculator.bestFromBusinessDates(oldOracle.qualifiedDates), after.fold.bestStreak)
        assertEquals(1, before.fold.qualifiedCount); assertEquals(2, after.fold.qualifiedCount)
        val sameMasks = body(second, mapOf(second.atDay(1) to 3L))
        val unchanged = CountDisplayBlocks.advance(listOf(CountDisplayBlocks.Change(habit, head, second, sameMasks)), issued::get)
        assertTrue("Changed quantity above the same target must reuse the complete issued calendar result", unchanged.isEmpty())
        val years = listOf(2023, 2024).associateWith { requireNotNull(CountDisplayBlocks.year(after, it, habit, issued::get)) }
        assertEquals(StreakCalculator.currentFromBusinessDates(oldOracle.qualifiedDates, second.atDay(1)),
            after.fold.currentStreak(second.atDay(1), years))
    }
    @Test fun claimedBlockLossContentDamageScopeAndFoldMismatchAreNotEmptyResults() {
        val month = YearMonth.of(2024, 2)
        val issued = CountDisplayBlocks.issue(habit, head, mapOf(month to body(month, mapOf(month.atDay(29) to 2L))), zone)
        val raw = issued.getValue(scope.globalKey()).jsonObject
        val global = CountDisplayBlocks.global(raw, scope, habit)
        assertThrows(IllegalArgumentException::class.java) { CountDisplayBlocks.year(global, 2024, habit) { null } }
        assertThrows(IllegalArgumentException::class.java) {
            CountDisplayBlocks.year(global, 2024, habit) { JsonObject(issued.getValue(scope.yearKey(2024)).jsonObject + ("extra" to JsonNull)) }
        }
        assertThrows(IllegalArgumentException::class.java) { CountDisplayBlocks.global(JsonObject(raw + ("qualified_count" to JsonPrimitive(99))), scope, habit) }
        assertThrows(IllegalArgumentException::class.java) { CountDisplayBlocks.global(raw, scope, habit.copy(schedule = HabitSchedule.Custom(3))) }
        val other = "157421fa-d271-461c-8f62-0e35f3189745"
        assertThrows(IllegalArgumentException::class.java) { CountDisplayBlocks.global(raw,
            CountDisplayBlocks.Scope(other, initialChallengeRoundHead(other)), habit.copy(uuid = other)) }
        assertNull(CountDisplayBlocks.year(global, 2025, habit, issued::get))
    }
    @Test fun emptyStateFirstDayAndUndoRetainedPolicyKeepCompleteYearMembership() {
        val issued = CountDisplayBlocks.issue(habit, head, emptyMap(), zone).toMutableMap()
        val empty = CountDisplayBlocks.global(issued.getValue(scope.globalKey()).jsonObject, scope, habit)
        assertTrue(empty.entries.isEmpty()); assertEquals(0, empty.fold.qualifiedCount)
        val month = YearMonth.of(2024, 3); val day = month.atDay(2)
        issued.putAll(CountDisplayBlocks.advance(listOf(CountDisplayBlocks.Change(habit, head, month,
            body(month, mapOf(day to 2L)))), issued::get))
        val saved = CountDisplayBlocks.global(issued.getValue(scope.globalKey()).jsonObject, scope, habit)
        assertEquals(1, saved.fold.qualifiedCount)
        issued.putAll(CountDisplayBlocks.advance(listOf(CountDisplayBlocks.Change(habit, head, month,
            body(month, emptyMap(), setOf(day)))), issued::get))
        val undone = CountDisplayBlocks.global(issued.getValue(scope.globalKey()).jsonObject, scope, habit)
        assertEquals(0, undone.fold.qualifiedCount); assertNull(undone.fold.firstQuantity)
        assertEquals(listOf(2024), undone.entries.map { it.summary.year })
        assertEquals(setOf(3), requireNotNull(CountDisplayBlocks.year(undone, 2024, habit, issued::get)).months.keys)
    }
}
