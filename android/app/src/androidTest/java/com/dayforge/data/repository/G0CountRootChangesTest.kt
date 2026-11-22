package com.dayforge.data.repository

import android.database.Cursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.G0DomainJournal.Cell
import com.dayforge.data.repository.G0DomainJournal.Change
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure transcript checks executed on Android. These fake rows are not business certificates,
 * SQLite trigger execution, cryptographic signatures, or cold/write readiness evidence. */
@RunWith(AndroidJUnit4::class)
class G0CountRootChangesTest {
    private fun cells(vararg values: Any?): List<Cell> = values.map { value -> when (value) {
        null -> Cell(Cursor.FIELD_TYPE_NULL, null)
        is Long -> Cell(Cursor.FIELD_TYPE_INTEGER, value)
        is String -> Cell(Cursor.FIELD_TYPE_STRING, value)
        is ByteArray -> Cell(Cursor.FIELD_TYPE_BLOB, value.toList())
        else -> error("fixture type")
    } }
    private fun marker(gen: Long, dirty: Long) = cells(1L, gen, dirty)
    private fun header(gen: Long, revision: Long = 2) = cells(1L, G0CountRootSeal.FORMAT.toLong(), G0CountRootSeal.VALIDATOR,
        "a".repeat(64), 1L, 3L, "b".repeat(64), revision, gen, ByteArray(32) { 4 })
    private fun source(table: String, key: Long): Change {
        val columns = (G0DomainSqlCatalog.columns + G0CountBlockSql.tables).getValue(table)
        return Change(0, table, "INSERT", null, List(columns.size) { if (it == 0) Cell(Cursor.FIELD_TYPE_INTEGER, key) else Cell(Cursor.FIELD_TYPE_NULL, null) })
    }
    private fun update(old: List<Cell>, next: List<Cell>) = Change(0, G0CountRootSql.markerTable, "UPDATE", old, next)
    private fun ordinal(changes: List<Change>) = changes.mapIndexed { i, c -> c.copy(sequence = 101L + i) }
    private data class Fixture(val boundary: G0CountRootChanges.Boundary, val next: List<Cell>, val expected: List<Change>, val actual: List<Change>)
    private fun valid(order: Int = 0): Fixture {
        val p = source("completions", 1); val q = source(G0CountBlockSql.nodes, 2)
        val old = header(7, 1); val next = header(9)
        val first = update(marker(7, 0), marker(8, 1)); val second = update(marker(8, 1), marker(9, 1))
        val prefix = if (order == 0) listOf(p, first, q, second) else listOf(first, p, second, q)
        val actual = ordinal(prefix + Change(0, G0CountRootSql.sealTable, "UPDATE", old, next) + update(marker(9, 1), marker(9, 0)))
        return Fixture(G0CountRootChanges.Boundary(old, marker(7, 0)), next, listOf(p, q), actual)
    }
    private fun validate(f: Fixture, actual: List<Change> = f.actual, expected: List<Change> = f.expected,
        boundary: G0CountRootChanges.Boundary = f.boundary, next: List<Cell> = f.next) =
        G0CountRootChanges.validate(boundary, next, actual, expected)
    private fun rejected(body: () -> Unit) {
        val failure = runCatching(body).exceptionOrNull()
        assertTrue("Expected RootChanges.Rejected, got $failure", failure is G0CountRootChanges.Rejected)
    }
    private fun changed(list: List<Change>, index: Int, change: Change) = list.toMutableList().also { it[index] = change }

    @Test fun bothTriggerInterleavingsPreserveExactSourceOrderValuesAndOriginalSequences() {
        for (order in 0..1) {
            val f = valid(order); val original = f.actual.toList()
            validate(f)
            assertEquals(original, f.actual)
            assertEquals(List(6) { 101L + it }, f.actual.map { it.sequence })
            assertEquals(listOf(0L, 0L), f.expected.map { it.sequence })
        }
    }

    @Test fun initialDirtyBoundaryCanSealAnAlreadyProvenRootWithoutFakeSourceEvents() {
        val next = header(0, 1)
        val actual = ordinal(listOf(Change(0, G0CountRootSql.sealTable, "INSERT", null, next), update(marker(0, 1), marker(0, 0))))
        G0CountRootChanges.validate(G0CountRootChanges.Boundary(null, marker(0, 1)), next, actual, emptyList())
        rejected { G0CountRootChanges.validate(G0CountRootChanges.Boundary(null, marker(0, 0)), next,
            ordinal(listOf(actual[0], update(marker(0, 0), marker(0, 0)))), emptyList()) }
    }

    @Test fun anExistingSealRequiresTheExactCleanBoundaryRatherThanLaunderingADirtyOrStaleRoot() {
        val f = valid()
        val dirty = marker(7, 1)
        val transcript = changed(f.actual, 1, f.actual[1].copy(old = dirty))
        rejected { validate(f, transcript, boundary = f.boundary.copy(marker = dirty)) }
        val stale = header(6, 1)
        rejected { validate(f, changed(f.actual, 4, f.actual[4].copy(old = stale)), boundary = f.boundary.copy(header = stale)) }
        val ahead = header(8, 1)
        rejected { validate(f, changed(f.actual, 4, f.actual[4].copy(old = ahead)), boundary = f.boundary.copy(header = ahead)) }
    }

    @Test fun reorderedMissingExtraOrAlteredPrimaryAndQEventsNeverDisappearBehindProjection() {
        val f = valid()
        rejected { validate(f, ordinal(changed(changed(f.actual, 0, f.actual[2]), 2, f.actual[0]))) }
        rejected { validate(f, ordinal(f.actual.filterIndexed { i, _ -> i != 0 })) }
        rejected { validate(f, ordinal(listOf(f.actual[0]) + f.actual)) }
        val row = requireNotNull(f.actual[0].new).toMutableList().also { it[0] = Cell(Cursor.FIELD_TYPE_STRING, "1") }
        rejected { validate(f, changed(f.actual, 0, f.actual[0].copy(new = row))) }
        rejected { validate(f, expected = f.expected.reversed()) }
    }

    @Test fun sequenceGapsDuplicatesNonpositiveValuesAndOverflowAreRejected() {
        val f = valid()
        for (seq in listOf(100L, 103L, 0L, -1L)) rejected { validate(f, changed(f.actual, 1, f.actual[1].copy(sequence = seq))) }
        rejected { validate(f, f.actual.mapIndexed { i, c -> c.copy(sequence = if (i == 0) Long.MAX_VALUE else i.toLong()) }) }
        rejected { validate(f, expected = f.expected.map { it.copy(sequence = 99) }) }
    }

    @Test fun eachSourceRequiresOneIncrementAndExtraMarkerWorkCannotCompensateForMissingBusinessWork() {
        val f = valid()
        rejected { validate(f, ordinal(f.actual.filterIndexed { i, _ -> i != 1 })) }
        val extra = update(marker(9, 1), marker(10, 1))
        val next = header(10)
        val actual = ordinal(f.actual.take(4) + extra + f.actual[4].copy(new = next) + update(marker(10, 1), marker(10, 0)))
        rejected { validate(f, actual, next = next) }
        rejected { validate(f, ordinal(f.actual.filterIndexed { i, _ -> i != 2 }), expected = f.expected.take(1)) }
    }

    @Test fun markerOldNewChainCannotBeRebasedDecrementedSkippedOrChangedToAnotherSlot() {
        val f = valid()
        val original = f.actual[1]
        val alternatives = listOf(original.copy(old = marker(6, 0)), original.copy(new = marker(7, 1)),
            original.copy(new = marker(9, 1)), original.copy(new = marker(8, 0)),
            original.copy(new = cells(2L, 8L, 1L)), original.copy(new = cells(1L, "8", 1L)),
            original.copy(new = marker(8, 2)), original.copy(operation = "INSERT", old = null))
        for (change in alternatives) rejected { validate(f, changed(f.actual, 1, change)) }
        val highOld = header(Long.MAX_VALUE, 1); val highNext = header(Long.MAX_VALUE)
        val high = ordinal(listOf(f.expected.first(), update(marker(Long.MAX_VALUE, 0), marker(Long.MAX_VALUE, 1)),
            Change(0, G0CountRootSql.sealTable, "UPDATE", highOld, highNext), update(marker(Long.MAX_VALUE, 1), marker(Long.MAX_VALUE, 0))))
        rejected { validate(f, high, expected = f.expected.take(1), boundary = G0CountRootChanges.Boundary(highOld, marker(Long.MAX_VALUE, 0)), next = highNext) }
    }

    @Test fun clearMustBeUniqueLastAndCannotChangeGenerationOrLeaveDirty() {
        val f = valid(); val clear = f.actual.last()
        for (replacement in listOf(clear.copy(new = marker(10, 0)), clear.copy(new = marker(9, 1)),
            clear.copy(old = marker(8, 1)), clear.copy(operation = "DELETE", new = null)))
            rejected { validate(f, changed(f.actual, 5, replacement)) }
        rejected { validate(f, ordinal(f.actual + clear)) }
        rejected { validate(f, ordinal(listOf(clear) + f.actual.dropLast(1))) }
        rejected { validate(f, ordinal(f.actual + f.expected.first())) }
    }

    @Test fun sealIsExactlyOneInsertOrUpdateWithTheFullCapturedOldAndPreparedNew() {
        val f = valid(); val seal = f.actual[4]
        val modified = f.next.toMutableList().also { it[6] = Cell(Cursor.FIELD_TYPE_STRING, "c".repeat(64)) }
        for (replacement in listOf(seal.copy(new = modified), seal.copy(old = header(6, 1)),
            seal.copy(operation = "INSERT", old = null), seal.copy(operation = "DELETE", new = null)))
            rejected { validate(f, changed(f.actual, 4, replacement)) }
        rejected { validate(f, ordinal(f.actual.take(4) + seal + seal + f.actual.last())) }
        rejected { validate(f, ordinal(f.actual.filterIndexed { i, _ -> i != 4 })) }
        rejected { validate(f, ordinal(listOf(seal) + f.actual.take(4) + f.actual.last())) }
    }

    @Test fun preparedSealGenerationAndNativeHeaderShapeMustMatchTheVerifiedTerminalMarker() {
        val f = valid()
        val invalid = listOf(header(8), f.next.toMutableList().also { it[9] = Cell(Cursor.FIELD_TYPE_BLOB, List(31) { 1.toByte() }) },
            f.next.toMutableList().also { it[7] = Cell(Cursor.FIELD_TYPE_STRING, "2") },
            f.next.toMutableList().also { it[9] = Cell(Cursor.FIELD_TYPE_BLOB, List(32) { 1 }) },
            f.next.dropLast(1), f.next.toMutableList().also { it[1] = Cell(Cursor.FIELD_TYPE_INTEGER, 0L) },
            f.next.toMutableList().also { it[1] = Cell(Cursor.FIELD_TYPE_INTEGER, 2L) },
            f.next.toMutableList().also { it[2] = Cell(Cursor.FIELD_TYPE_STRING, "wrong-validator") },
            f.next.toMutableList().also { it[5] = Cell(Cursor.FIELD_TYPE_INTEGER, 0L) },
            f.next.toMutableList().also { it[5] = Cell(Cursor.FIELD_TYPE_INTEGER, Long.MAX_VALUE) })
        for (next in invalid) rejected { validate(f, changed(f.actual, 4, f.actual[4].copy(new = next)), next = next) }
    }

    @Test fun unknownTablesAndUnshapedSourceOperationsCannotBeSilentlyFiltered() {
        val f = valid()
        for (unknown in listOf("unknown", "sqlite_sequence", "g0_count_root_seal_extra"))
            rejected { validate(f, changed(f.actual, 0, f.actual[0].copy(table = unknown))) }
        rejected { validate(f, changed(f.actual, 0, f.actual[0].copy(operation = "REPLACE")), expected = listOf(f.expected[0].copy(operation = "REPLACE"), f.expected[1])) }
        rejected { validate(f, changed(f.actual, 0, f.actual[0].copy(new = emptyList())), expected = listOf(f.expected[0].copy(new = emptyList()), f.expected[1])) }
        rejected { validate(f, expected = f.expected + f.actual[4].copy(sequence = 0)) }
    }

    @Test fun sourceDeleteAndNativeBlobValuesAreComparedAsFullOrderedChanges() {
        val f = valid()
        val original = requireNotNull(f.expected[0].new)
        val deletion = f.expected[0].copy(operation = "DELETE", old = original, new = null)
        val qrow = requireNotNull(f.expected[1].new).toMutableList().also { it[1] = Cell(Cursor.FIELD_TYPE_BLOB, listOf(1.toByte(), 2.toByte())) }
        val q = f.expected[1].copy(new = qrow)
        val actual = changed(changed(f.actual, 0, deletion.copy(sequence = 101)), 2, q.copy(sequence = 103))
        validate(f, actual, listOf(deletion, q))
        val wrong = qrow.toMutableList().also { it[1] = Cell(Cursor.FIELD_TYPE_BLOB, listOf(2.toByte(), 1.toByte())) }
        rejected { validate(f, changed(actual, 2, actual[2].copy(new = wrong)), listOf(deletion, q)) }
        assertEquals(listOf(1.toByte(), 2.toByte()), qrow[1].value)
    }

    @Test fun collectionAndCellBudgetsRejectBeforeReadingUnboundedTranscript() {
        val f = valid()
        val huge = object : AbstractList<Change>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Change = error("Must not read an over-budget transcript")
        }
        rejected { validate(f, actual = huge) }
        val wide = object : AbstractList<Cell>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Cell = error("Must not read an over-budget row")
        }
        rejected { validate(f, next = wide) }
        val hugeBlob = object : AbstractList<Byte>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Byte = error("Must not read an over-budget blob")
        }
        val next = f.next.toMutableList().also { it[9] = Cell(Cursor.FIELD_TYPE_BLOB, hugeBlob) }
        rejected { validate(f, next = next) }
    }
}
