package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockPlanner

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockPlanner.DirectoryWindow
import com.dayforge.data.history.HistoryBlockPlanner.Fact
import com.dayforge.data.history.HistoryBlockPlanner.Leaf
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.Limits
import com.dayforge.data.history.HistoryBlockPlanner.LocateResult
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.PlanResult
import com.dayforge.data.history.HistoryBlockPlanner.PreparedLeaf
import com.dayforge.data.history.HistoryBlockPlanner.PreparedUnit
import com.dayforge.data.history.HistoryBlockPlanner.Reason
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only candidate planning tests. Except for one scratch SQLite ordering test these do not
 * access persistence. They do not prove certified reads, business acceptance, or product latency.
 */
@RunWith(AndroidJUnit4::class)
class G0HistoryBlockPlannerTest {
    private val partition = Partition(Scope("local-binding-a"), "recurring-fact", "object-a", "round-1")
    private val day = LocalDate.of(2026, 10, 21)
    private val unit = UnitKey(partition, YearMonth.from(day))
    private val limits = Limits(pageHeaderBytes = 128, maxPreparedFacts = 20_000, maxPreparedBytes = 64L * 1024 * 1024)
    private val planner = HistoryBlockPlanner(limits)

    private fun key(id: String, date: LocalDate = day) = OrderKey(date, id)
    private fun fact(id: String, date: LocalDate = day, bytes: Long = 100L,
        owner: Partition = partition, source: String = "device-a") = Fact(owner, key(id, date), source, bytes)
    private fun ready(result: PlanResult): List<Leaf> {
        assertTrue("expected ready, got $result", result is PlanResult.Ready)
        return (result as PlanResult.Ready).leaves
    }
    private fun unsupported(expected: Reason, result: PlanResult) {
        assertTrue("expected unsupported, got $result", result is PlanResult.Unsupported)
        assertEquals(expected, (result as PlanResult.Unsupported).reason)
    }
    private fun initial(facts: List<Fact>, candidate: HistoryBlockPlanner = planner): List<Leaf> =
        ready(candidate.planInitial(listOf(PreparedUnit(unit, facts))))
    private fun conserved(expected: List<Fact>, leaves: List<Leaf>) {
        val actual = leaves.flatMap { it.facts }
        assertEquals(expected.size, actual.size)
        assertEquals(expected.associateBy { it.key }, actual.associateBy { it.key })
        assertEquals(actual.size, actual.map { it.key.stableFactId }.distinct().size)
        for (leaf in leaves) {
            assertEquals(leaf.facts.sortedBy { it.key }, leaf.facts)
            assertEquals(limits.pageHeaderBytes + leaf.facts.sumOf { it.fullEncodedBytes }, leaf.plannedEncodedBytes)
        }
    }

    @Test fun sparseYearsCreateOnlyExplicitMonthBucketsAndPreserveEveryFact() {
        val facts = listOf(fact("old", LocalDate.of(1999, 1, 1)), fact("recent", day),
            fact("later", LocalDate.of(2042, 12, 31)))
        val prepared = facts.map { PreparedUnit(UnitKey(partition, YearMonth.from(it.key.recordedBusinessDate)), listOf(it)) }
        val leaves = ready(planner.planInitial(prepared.reversed()))
        assertEquals(3, leaves.size)
        assertEquals(listOf(YearMonth.of(1999, 1), YearMonth.of(2026, 10), YearMonth.of(2042, 12)), leaves.map { it.range.unit.month })
        assertTrue(leaves.all { it.range.lowerInclusive == null && it.range.upperExclusive == null })
        conserved(facts, leaves)
    }

    @Test fun monthRoundObjectAndScopePartitionWhileSourcesStayInTheSameLogicalUnit() {
        val nextRound = partition.copy(roundId = "round-2")
        val otherObject = partition.copy(objectId = "object-b")
        val otherScope = partition.copy(scope = Scope("local-binding-b"))
        val nextMonth = day.plusMonths(1)
        val facts = listOf(fact("a", source = "device-a"), fact("b", source = "device-b"))
        val prepared = listOf(PreparedUnit(unit, facts),
            PreparedUnit(UnitKey(partition, YearMonth.from(nextMonth)), listOf(fact("c", nextMonth))),
            PreparedUnit(UnitKey(nextRound, unit.month), listOf(fact("d", owner = nextRound))),
            PreparedUnit(UnitKey(otherObject, unit.month), listOf(fact("e", owner = otherObject))),
            PreparedUnit(UnitKey(otherScope, unit.month), listOf(fact("a", owner = otherScope))))
        val leaves = ready(planner.planInitial(prepared))
        assertEquals(5, leaves.size)
        assertEquals(facts, leaves.single { it.range.unit == unit }.facts)
        assertEquals(setOf("device-a", "device-b"), leaves.single { it.range.unit == unit }.facts.map { it.sourceId }.toSet())
        assertEquals(prepared.sumOf { it.facts.size }, leaves.sumOf { it.facts.size })
    }

    @Test fun datePrecedesFactIdAndHalfOpenBoundaryLocatesOnlyTheRightLeaf() {
        val candidate = HistoryBlockPlanner(limits.copy(maxFactsPerPage = 2))
        val facts = listOf(fact("z", day.minusDays(1)), fact("B"), fact("a"), fact("0", day.plusDays(1)))
        val leaves = initial(facts.reversed(), candidate)
        assertEquals(listOf("z", "B", "a", "0"), leaves.flatMap { it.facts }.map { it.key.stableFactId })
        val boundary = key("a")
        assertEquals(boundary, leaves[0].range.upperExclusive)
        assertEquals(boundary, leaves[1].range.lowerInclusive)
        val window = DirectoryWindow(LeafRange(unit, null, null), leaves.map { it.range })
        assertEquals(LocateResult.Located(leaves[0].range), candidate.locate(window, key("B")))
        assertEquals(LocateResult.Located(leaves[1].range), candidate.locate(window, boundary))
        assertEquals(LocateResult.NeedDirectoryLookup, candidate.locate(window, key("a", day.plusMonths(1))))
        val localWindow = DirectoryWindow(leaves[0].range, listOf(leaves[0].range))
        assertEquals(LocateResult.NeedDirectoryLookup, candidate.locate(localWindow, boundary))
    }

    @Test fun denseSameDaySplitsAtFactCapWithoutTurningMonthOrDayIntoAHardPage() {
        val facts = (0 until 300).map { fact("fact-" + it.toString().padStart(4, '0')) }
        val leaves = initial(facts.reversed())
        assertEquals(listOf(128, 128, 44), leaves.map { it.facts.size })
        assertTrue(leaves.all { it.range.unit == unit })
        conserved(facts, leaves)
        assertTrue(leaves.all { it.plannedEncodedBytes <= limits.targetPageBytes })
    }

    @Test fun initialPlanIsDeterministicAcrossInputOrderAndOwnsItsListSnapshots() {
        val facts = (0 until 17).map { fact("fact-$it", day.minusDays((it % 3).toLong())) }.toMutableList()
        val candidate = HistoryBlockPlanner(limits.copy(maxFactsPerPage = 3))
        val first = initial(facts, candidate)
        assertEquals(first, initial(facts.reversed(), candidate))
        assertEquals(first, initial(facts.drop(5) + facts.take(5), candidate))
        val retainedCount = first.sumOf { it.facts.size }
        facts.clear()
        assertEquals(17, retainedCount)
        assertEquals(17, first.sumOf { it.facts.size })
        try {
            (first as MutableList<Leaf>).clear()
            fail("output leaves were mutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(17, first.sumOf { it.facts.size })
        }
        try {
            (first.first().facts as MutableList<Fact>).clear()
            fail("output fact list was mutable")
        } catch (_: UnsupportedOperationException) {
            assertEquals(17, first.sumOf { it.facts.size })
        }
    }

    @Test fun explicitEmptyUnitAndEmptiedLeafKeepTheirRangeWithoutInventingOtherBuckets() {
        assertTrue(ready(planner.planInitial(emptyList())).isEmpty())
        val empty = initial(emptyList()).single()
        assertEquals(LeafRange(unit, null, null), empty.range)
        assertEquals(limits.pageHeaderBytes, empty.plannedEncodedBytes)
        val range = LeafRange(unit, key("b"), key("d"))
        val emptied = ready(planner.replaceAffectedLeaf(PreparedLeaf(range, emptyList()))).single()
        assertEquals(range, emptied.range)
        assertTrue(emptied.facts.isEmpty())
        assertEquals(LocateResult.Located(range), planner.locate(DirectoryWindow(range, listOf(range)), key("c")))
    }

    @Test fun lateInsertSplitsOnlyItsExistingLeafAndLeavesAllLaterBoundariesUnchanged() {
        val candidate = HistoryBlockPlanner(limits.copy(maxFactsPerPage = 2))
        val facts = listOf("b", "d", "f", "h", "j", "l").map { fact(it) }
        val old = initial(facts, candidate)
        val oldRange = old.first().range
        val late = fact("c")
        val replacement = ready(candidate.replaceAffectedLeaf(PreparedLeaf(oldRange, old.first().facts + late)))
        assertEquals(2, replacement.size)
        assertEquals(oldRange.lowerInclusive, replacement.first().range.lowerInclusive)
        assertEquals(oldRange.upperExclusive, replacement.last().range.upperExclusive)
        val updated = replacement + old.drop(1)
        assertEquals(old.drop(1), updated.drop(replacement.size))
        conserved(facts + late, updated)
        val window = DirectoryWindow(LeafRange(unit, null, null), updated.map { it.range })
        assertEquals(LocateResult.Located(replacement.first().range), candidate.locate(window, late.key))
        assertEquals(LocateResult.Located(old[1].range), candidate.locate(window, key("f")))
    }

    @Test fun targetHeaderAndHardMaximumAreDistinctAndIncludedInEveryPage() {
        val candidate = HistoryBlockPlanner(limits.copy(pageHeaderBytes = 10, targetPageBytes = 100, hardMaxPageBytes = 150))
        val exactTarget = initial(listOf(fact("a", bytes = 45), fact("b", bytes = 45)), candidate)
        assertEquals(listOf(100L), exactTarget.map { it.plannedEncodedBytes })
        val overTarget = initial(listOf(fact("a", bytes = 46), fact("b", bytes = 45)), candidate)
        assertEquals(listOf(56L, 55L), overTarget.map { it.plannedEncodedBytes })
        val large = initial(listOf(fact("a", bytes = 140), fact("b", bytes = 1)), candidate)
        assertEquals(listOf(150L, 11L), large.map { it.plannedEncodedBytes })
        assertEquals(listOf("a", "b"), large.flatMap { it.facts }.map { it.key.stableFactId })
    }

    @Test fun hugeSingleFactReturnsExplicitFallbackWithoutDroppingOrPartiallyPlanningInput() {
        val normal = fact("a")
        val huge = fact("b", bytes = limits.hardMaxPageBytes - limits.pageHeaderBytes + 1)
        val facts = mutableListOf(normal, huge, fact("c"))
        val snapshot = facts.toList()
        val result = planner.planInitial(listOf(PreparedUnit(unit, facts)))
        unsupported(Reason.NEEDS_LARGE_RECORD_HANDLING, result)
        assertEquals("b", (result as PlanResult.Unsupported).factId)
        assertEquals(snapshot, facts)
        val range = LeafRange(unit, null, null)
        unsupported(Reason.NEEDS_LARGE_RECORD_HANDLING, planner.replaceAffectedLeaf(PreparedLeaf(range, facts)))
        assertEquals(snapshot, facts)
    }

    @Test fun negativeSizingOverflowAndWholeBatchBudgetsCannotProduceWrappedOrPartialPlans() {
        unsupported(Reason.INVALID_ENCODED_SIZE, planner.planInitial(listOf(PreparedUnit(unit, listOf(fact("negative", bytes = -1))))))
        unsupported(Reason.NEEDS_LARGE_RECORD_HANDLING, planner.planInitial(listOf(PreparedUnit(unit, listOf(fact("huge", bytes = Long.MAX_VALUE))))))
        val countBound = HistoryBlockPlanner(limits.copy(maxPreparedFacts = 1))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, countBound.planInitial(listOf(PreparedUnit(unit, listOf(fact("a"), fact("b"))))))
        val headerBound = HistoryBlockPlanner(limits.copy(maxPreparedBytes = limits.pageHeaderBytes - 1))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, headerBound.planInitial(listOf(PreparedUnit(unit, emptyList()))))
        val overflow = HistoryBlockPlanner(limits.copy(targetPageBytes = Long.MAX_VALUE, hardMaxPageBytes = Long.MAX_VALUE,
            pageHeaderBytes = 1, maxPreparedBytes = Long.MAX_VALUE))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, overflow.planInitial(listOf(PreparedUnit(unit,
            listOf(fact("a", bytes = Long.MAX_VALUE - 1), fact("b", bytes = 1))))))
        val invalid = HistoryBlockPlanner(limits.copy(targetPageBytes = 0))
        unsupported(Reason.UNSUPPORTED_LIMITS, invalid.planInitial(emptyList()))
    }

    @Test fun unsupportedAndDuplicateIdentitiesAreNotNormalizedOrDeduplicated() {
        for (id in listOf("", " x", "x ", "中文", "x\u0000y", "x".repeat(129))) {
            unsupported(Reason.UNSUPPORTED_IDENTITY, planner.planInitial(listOf(PreparedUnit(unit, listOf(fact(id))))))
        }
        val supported = listOf(fact("A"), fact("a"), fact("x".repeat(128)), fact("00000000-0000-4000-8000-000000000001"))
        assertEquals(supported.size, initial(supported).sumOf { it.facts.size })
        val duplicate = listOf(fact("same"), fact("same", day.plusDays(1), source = "device-b"))
        unsupported(Reason.DUPLICATE_IDENTITY, planner.planInitial(listOf(PreparedUnit(unit, duplicate))))
        assertEquals(2, duplicate.size)
        val next = day.plusMonths(1)
        unsupported(Reason.DUPLICATE_IDENTITY, planner.planInitial(listOf(PreparedUnit(unit, listOf(fact("same"))),
            PreparedUnit(UnitKey(partition, YearMonth.from(next)), listOf(fact("same", next))))))
        for (other in listOf(partition.copy(objectId = "object-b"), partition.copy(roundId = "round-2"))) {
            unsupported(Reason.DUPLICATE_IDENTITY, planner.planInitial(listOf(PreparedUnit(unit, listOf(fact("same"))),
                PreparedUnit(UnitKey(other, unit.month), listOf(fact("same", owner = other))))))
        }
        val noRound = partition.copy(roundId = null)
        val rounds = ready(planner.planInitial(listOf(PreparedUnit(unit, listOf(fact("with-round"))),
            PreparedUnit(UnitKey(noRound, unit.month), listOf(fact("without-round", owner = noRound))))))
        assertEquals(setOf(null, "round-1"), rounds.map { it.range.unit.partition.roundId }.toSet())
        unsupported(Reason.DUPLICATE_UNIT, planner.planInitial(listOf(PreparedUnit(unit, emptyList()), PreparedUnit(unit, emptyList()))))
    }

    @Test fun wrongPartitionMonthRangeAndDirectoryShapeCannotBecomeUsablePlansOrAbsenceClaims() {
        val range = LeafRange(unit, key("b"), key("d"))
        unsupported(Reason.WRONG_PARTITION_OR_MONTH, planner.replaceAffectedLeaf(PreparedLeaf(range, listOf(fact("c", owner = partition.copy(roundId = "round-2"))))))
        unsupported(Reason.WRONG_PARTITION_OR_MONTH, planner.replaceAffectedLeaf(PreparedLeaf(range, listOf(fact("c", day.plusMonths(1))))))
        for (id in listOf("a", "d")) unsupported(Reason.FACT_OUTSIDE_RANGE, planner.replaceAffectedLeaf(PreparedLeaf(range, listOf(fact(id)))))
        unsupported(Reason.INVALID_RANGE, planner.replaceAffectedLeaf(PreparedLeaf(LeafRange(unit, key("d"), key("b")), emptyList())))
        val gap = DirectoryWindow(LeafRange(unit, null, null), listOf(LeafRange(unit, null, key("b")), LeafRange(unit, key("c"), null)))
        assertEquals(LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW), planner.locate(gap, key("a")))
        val overlap = gap.copy(ranges = listOf(LeafRange(unit, null, key("d")), LeafRange(unit, key("c"), null)))
        assertEquals(LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW), planner.locate(overlap, key("a")))
        assertEquals(LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW), planner.locate(DirectoryWindow(range, emptyList()), key("c")))
        assertEquals(LocateResult.NeedDirectoryLookup, planner.locate(DirectoryWindow(range, listOf(range)), key("z")))
    }

    @Test fun androidSqliteEpochDayAndBinaryIdentityOrderingMatchPlannerAndKeysetHalfOpenRange() {
        val oldDay = LocalDate.of(-1, 2, 1)
        val laterDay = LocalDate.of(12_000, 2, 1)
        val keys = listOf(OrderKey(laterDay, "a"), OrderKey(oldDay, "a"), OrderKey(day, "0"),
            OrderKey(oldDay, "A"), OrderKey(oldDay, "~"), OrderKey(oldDay.plusDays(1), "!"))
        val database = SQLiteDatabase.create(null)
        try {
            database.execSQL("CREATE TABLE ordering_fixture(epochDay INTEGER NOT NULL, id TEXT COLLATE BINARY NOT NULL)")
            for (value in keys) database.execSQL("INSERT INTO ordering_fixture(epochDay,id) VALUES(?,?)",
                arrayOf<Any>(value.recordedBusinessDate.toEpochDay(), value.stableFactId))
            val actual = database.rawQuery("SELECT epochDay,id FROM ordering_fixture ORDER BY epochDay,id COLLATE BINARY", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(OrderKey(LocalDate.ofEpochDay(cursor.getLong(0)), cursor.getString(1))) }
            }
            assertEquals(keys.sorted(), actual)
            val lower = OrderKey(oldDay, "a")
            val upper = OrderKey(oldDay.plusDays(1), "!")
            val selected = database.rawQuery("SELECT epochDay,id FROM ordering_fixture WHERE " +
                "(epochDay>? OR (epochDay=? AND id COLLATE BINARY>=?)) AND " +
                "(epochDay<? OR (epochDay=? AND id COLLATE BINARY<?)) ORDER BY epochDay,id COLLATE BINARY",
                arrayOf(lower.recordedBusinessDate.toEpochDay().toString(), lower.recordedBusinessDate.toEpochDay().toString(), lower.stableFactId,
                    upper.recordedBusinessDate.toEpochDay().toString(), upper.recordedBusinessDate.toEpochDay().toString(), upper.stableFactId)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(OrderKey(LocalDate.ofEpochDay(cursor.getLong(0)), cursor.getString(1))) }
            }
            assertEquals(keys.filter { it >= lower && it < upper }.sorted(), selected)
            assertEquals(listOf(OrderKey(oldDay, "a"), OrderKey(oldDay, "~")), selected)
            assertFalse(selected.contains(upper))
            val oldUnit = UnitKey(partition, YearMonth.from(oldDay))
            val range = LeafRange(oldUnit, lower, upper)
            val window = DirectoryWindow(range, listOf(range))
            for (value in selected) assertEquals(LocateResult.Located(range), planner.locate(window, value))
            assertEquals(LocateResult.NeedDirectoryLookup, planner.locate(window, upper))
        } finally {
            database.close()
        }
    }

    @Test fun unitFactAndDirectoryCapsRejectBeforeEnumeratingBorrowedContents() {
        fun <T> unreadable(count: Int): List<T> = object : AbstractList<T>() {
            override val size: Int = count
            override fun get(index: Int): T = error("over-budget input was enumerated at $index")
        }
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED,
            planner.planInitial(unreadable<PreparedUnit>(limits.maxPreparedUnits + 1)))
        val countBound = HistoryBlockPlanner(limits.copy(maxPreparedFacts = 3))
        val tooMany = unreadable<Fact>(4)
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, countBound.planInitial(listOf(PreparedUnit(unit, tooMany))))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED,
            countBound.replaceAffectedLeaf(PreparedLeaf(LeafRange(unit, null, null), tooMany)))
        // Neither individually acceptable nested list may be read before the aggregate count.
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, countBound.planInitial(listOf(
            PreparedUnit(unit, unreadable(2)), PreparedUnit(unit.copy(month = unit.month.plusMonths(1)), unreadable(2)))))
        val oversizedWindow = DirectoryWindow(LeafRange(unit, null, null), unreadable<LeafRange>(limits.maxDirectoryRanges + 1))
        assertEquals(LocateResult.Unsupported(Reason.DIRECTORY_BUDGET_EXCEEDED), planner.locate(oversizedWindow, key("a")))
        val emptyUnitCap = HistoryBlockPlanner(limits.copy(maxPreparedUnits = 0))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, emptyUnitCap.planInitial(unreadable<PreparedUnit>(1)))
        assertTrue(ready(emptyUnitCap.planInitial(emptyList())).isEmpty())
    }

    @Test fun wholeBatchBytePreflightIncludesEmptyUnitsAndEachSplitHeaderBeforeBuildingOutput() {
        val emptyUnits = (0L..2L).map { PreparedUnit(unit.copy(month = unit.month.plusMonths(it)), emptyList()) }
        val short = HistoryBlockPlanner(limits.copy(maxPreparedBytes = 3 * limits.pageHeaderBytes - 1))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, short.planInitial(emptyUnits))
        val exact = HistoryBlockPlanner(limits.copy(maxPreparedBytes = 3 * limits.pageHeaderBytes))
        assertEquals(listOf(128L, 128L, 128L), ready(exact.planInitial(emptyUnits)).map { it.plannedEncodedBytes })
        val records = listOf(fact("a", bytes = 90), fact("b", bytes = 90))
        val pages = limits.copy(pageHeaderBytes = 10, targetPageBytes = 100, hardMaxPageBytes = 150, maxPreparedBytes = 199)
        val insufficient = HistoryBlockPlanner(pages)
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED, insufficient.planInitial(listOf(PreparedUnit(unit, records))))
        unsupported(Reason.PREPARED_BUDGET_EXCEEDED,
            insufficient.replaceAffectedLeaf(PreparedLeaf(LeafRange(unit, null, null), records)))
        val enough = HistoryBlockPlanner(pages.copy(maxPreparedBytes = 200))
        assertEquals(listOf(100L, 100L), initial(records, enough).map { it.plannedEncodedBytes })
        assertEquals(listOf("a", "b"), records.map { it.key.stableFactId })
    }

    @Test fun repeatedGetCannotReplaceValidatedFactsOrDirectoryRangesWithUnverifiedValues() {
        fun <T> changing(first: T, later: T): List<T> = object : AbstractList<T>() {
            var reads = 0
            override val size: Int = 1
            override fun get(index: Int): T {
                assertEquals(0, index)
                return if (reads++ == 0) first else later
            }
        }
        val good = fact("a")
        val wrong = fact("b", owner = partition.copy(scope = Scope("different-binding")))
        val result = planner.planInitial(changing(PreparedUnit(unit, changing(good, wrong)),
            PreparedUnit(unit, listOf(wrong))))
        assertEquals(listOf(good), ready(result).single().facts)
        val range = LeafRange(unit, null, null)
        val replaced = planner.replaceAffectedLeaf(PreparedLeaf(range, changing(good, wrong)))
        assertEquals(listOf(good), ready(replaced).single().facts)
        val otherRange = range.copy(unit = unit.copy(partition = wrong.partition))
        assertEquals(LocateResult.Located(range), planner.locate(DirectoryWindow(range, changing(range, otherRange)), key("a")))

        // A later size read must not expand copying or switch to an unbounded iterator.
        val capturedSizeOnly = object : AbstractList<Fact>() {
            var sizeReads = 0
            override val size: Int get() = if (sizeReads++ == 0) 1 else Int.MAX_VALUE
            override fun get(index: Int): Fact {
                assertEquals(0, index)
                return good
            }
        }
        assertEquals(listOf(good), initial(capturedSizeOnly).single().facts)
    }

    @Test fun successiveMiddleLeafInsertDeleteAndMetadataReplacementKeepNeighborsAndRangesStable() {
        val candidate = HistoryBlockPlanner(limits.copy(maxFactsPerPage = 3))
        val originalFacts = listOf("b", "d", "f", "h", "j", "l", "n", "p", "r").map { fact(it) }
        val original = initial(originalFacts, candidate)
        assertEquals(3, original.size)
        val priorNeighbor = original[0]
        val nextNeighbor = original[2]
        val middle = original[1]
        assertEquals(key("h"), middle.range.lowerInclusive)
        assertEquals(key("n"), middle.range.upperExclusive)

        fun verify(expected: List<Fact>, current: List<Leaf>) {
            conserved(expected, current)
            assertEquals(priorNeighbor, current.first())
            assertEquals(nextNeighbor, current.last())
            val window = DirectoryWindow(LeafRange(unit, null, null), current.map { it.range })
            for (value in expected) {
                val owning = current.single { leaf ->
                    (leaf.range.lowerInclusive == null || value.key >= leaf.range.lowerInclusive) &&
                        (leaf.range.upperExclusive == null || value.key < leaf.range.upperExclusive)
                }
                assertEquals(LocateResult.Located(owning.range), candidate.locate(window, value.key))
            }
        }

        val inserted = fact("i")
        val splitMiddle = ready(candidate.replaceAffectedLeaf(PreparedLeaf(middle.range, middle.facts + inserted)))
        assertEquals(2, splitMiddle.size)
        var current = listOf(priorNeighbor) + splitMiddle + nextNeighbor
        var expected = originalFacts + inserted
        verify(expected, current)

        val rangeWithOldBoundary = current[1].range
        val afterDelete = ready(candidate.replaceAffectedLeaf(PreparedLeaf(rangeWithOldBoundary,
            current[1].facts.filterNot { it.key.stableFactId == "h" })))
        assertEquals(listOf(rangeWithOldBoundary), afterDelete.map { it.range })
        current = listOf(priorNeighbor) + afterDelete + current.drop(2)
        expected = expected.filterNot { it.key.stableFactId == "h" }
        verify(expected, current)
        assertEquals(LocateResult.Located(rangeWithOldBoundary), candidate.locate(
            DirectoryWindow(LeafRange(unit, null, null), current.map { it.range }), key("h")))
        assertFalse(current.flatMap { it.facts }.any { it.key.stableFactId == "h" })

        // Pure metadata preservation only: this does not authorize a source change in business data.
        val changed = inserted.copy(sourceId = "device-b", fullEncodedBytes = 1_000)
        val changedLeaf = ready(candidate.replaceAffectedLeaf(PreparedLeaf(current[1].range,
            current[1].facts.map { if (it.key == changed.key) changed else it })))
        current = listOf(priorNeighbor) + changedLeaf + current.drop(2)
        expected = expected.map { if (it.key == changed.key) changed else it }
        verify(expected, current)
        assertEquals(changed, current.flatMap { it.facts }.single { it.key == changed.key })
        assertEquals(rangeWithOldBoundary, current[1].range)
    }
}
