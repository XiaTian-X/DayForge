package com.dayforge.data.repository

import android.database.Cursor
import java.util.Collections

/** Checks the COMPLETE captured journal before the original P/Q expectation is projected.
 * This is a pure transcript check, not a business validator, signer, SQL capability or END receipt.
 * Gate.pending() owns first-sequence/consumed provenance; this layer only checks positive,
 * adjacent sequence numbers. Gate and ProofIndex must still receive the untouched actual list.
 */
internal object G0CountRootChanges {
    /** Existing seal must be clean and match the marker generation. An absent seal is
     * first issuance from a dirty boundary; its business/root qualification is the issuer
     * prerequisite, not inferred here from the absence of H. */
    data class Boundary(val header: List<G0DomainJournal.Cell>?, val marker: List<G0DomainJournal.Cell>)
    class Rejected : IllegalStateException("G0_COUNT_ROOT_CHANGES_REJECTED")
    private val sources = G0DomainSqlCatalog.columns + G0CountBlockSql.tables
    private const val MAX_CHANGES = 4096
    private const val MAX_CAPTURE_BYTES = 64L * 1024 * 1024

    /** expectedPq is the issuer's original ordered list (sequence=0, including all P and Q).
     * newHeader is the exact owned Packet's native-cell encoding. Cryptographic validity and
     * current-root provenance belong to the issuer/cold matcher, never to this method.
     * No normalized or partial list escapes; after success callers use original actual events.
     */
    fun validate(boundary: Boundary, newHeader: List<G0DomainJournal.Cell>,
        actual: List<G0DomainJournal.Change>, expectedPq: List<G0DomainJournal.Change>) {
        val capture = Capture()
        val oldHeader = boundary.header?.let(capture::row)
        val oldMarker = capture.row(boundary.marker)
        val nextHeader = capture.row(newHeader)
        val observed = capture.changes(actual)
        val expected = capture.changes(expectedPq)
        marker(oldMarker); oldHeader?.let(::header); header(nextHeader)
        if (oldHeader == null) demand(number(oldMarker, 2) == 1L)
        else demand(number(oldMarker, 2) == 0L && number(oldMarker, 1) == number(oldHeader, 8))
        demand(observed.size >= 2 && expected.all { it.sequence == 0L && it.table in sources })
        expected.forEach { demandShape(it, sources.getValue(it.table).size) }
        observed.forEachIndexed { index, change ->
            demand(change.sequence > 0)
            if (index > 0) demand(change.sequence == plusOne(observed[index - 1].sequence))
        }
        val sourceEvents = observed.filter { it.table in sources }
        demand(sourceEvents.size == expected.size)
        val sealEvents = observed.filter { it.table == G0CountRootSql.sealTable }
        demand(sealEvents.size == 1)
        val seal = sealEvents.single()
        demandShape(seal, 10)
        demand(seal.operation == if (oldHeader == null) "INSERT" else "UPDATE")
        demand(seal.old == oldHeader && seal.new == nextHeader)
        // The issuer writes H only after every source mutation and marker increment, then
        // clears once. No source/marker/H work may follow the clear before final authentication.
        demand(observed[observed.lastIndex - 1] == seal)
        val clear = observed.last()
        demand(clear.table == G0CountRootSql.markerTable && clear.operation == "UPDATE")
        demandShape(clear, 3)
        var current = oldMarker
        var increments = 0
        for (event in observed.dropLast(2)) {
            when {
                event.table in sources -> Unit
                event.table == G0CountRootSql.markerTable -> {
                    demand(event.operation == "UPDATE"); demandShape(event, 3)
                    val old = requireNotNullOrReject(event.old); val next = requireNotNullOrReject(event.new)
                    marker(old); marker(next)
                    demand(old == current && number(next, 0) == 1L &&
                        number(next, 1) == plusOne(number(old, 1)) && number(next, 2) == 1L)
                    current = next; increments++
                }
                else -> reject()
            }
        }
        demand(increments == sourceEvents.size)
        val clearOld = requireNotNullOrReject(clear.old); val clearNew = requireNotNullOrReject(clear.new)
        marker(clearOld); marker(clearNew)
        demand(clearOld == current && number(current, 2) == 1L)
        demand(clearNew == listOf(integer(1), integer(number(current, 1)), integer(0)))
        demand(number(nextHeader, 8) == number(current, 1))
        // All excluded H/marker work is now proven; only then compare the original P/Q
        // projection. No partial validation result or normalized list is returned.
        sourceEvents.zip(expected).forEach { (found, wanted) ->
            demandShape(found, sources.getValue(found.table).size)
            // Only comparison maps the ordinal. The captured actual and caller lists stay intact.
            demand(found.copy(sequence = 0L) == wanted)
        }
    }

    private fun marker(row: List<G0DomainJournal.Cell>) {
        demand(row.size == 3 && number(row, 0) == 1L && number(row, 1) >= 0 && number(row, 2) in 0L..1L)
    }
    private fun header(row: List<G0DomainJournal.Cell>) {
        demand(row.size == 10 && number(row, 0) == 1L && number(row, 1) == G0CountRootSeal.FORMAT.toLong())
        demand(text(row, 2) == G0CountRootSeal.VALIDATOR && hash(text(row, 3)))
        demand(G0CountRootSeal.validMetadata(number(row, 4), number(row, 5)))
        demand(hash(text(row, 6)) && number(row, 7) > 0 && number(row, 8) >= 0)
        demand(row[9].type == Cursor.FIELD_TYPE_BLOB && (row[9].value as? List<*>)?.size == 32)
    }
    private fun demandShape(change: G0DomainJournal.Change, width: Int) {
        demand(change.operation in setOf("INSERT", "UPDATE", "DELETE"))
        demand((change.old != null) == (change.operation != "INSERT"))
        demand((change.new != null) == (change.operation != "DELETE"))
        change.old?.let { demand(it.size == width) }; change.new?.let { demand(it.size == width) }
    }
    private fun number(row: List<G0DomainJournal.Cell>, index: Int): Long {
        val value = row.getOrNull(index) ?: reject()
        demand(value.type == Cursor.FIELD_TYPE_INTEGER)
        return value.value as? Long ?: reject()
    }
    private fun text(row: List<G0DomainJournal.Cell>, index: Int): String {
        val value = row.getOrNull(index) ?: reject()
        demand(value.type == Cursor.FIELD_TYPE_STRING)
        return value.value as? String ?: reject()
    }
    private fun hash(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
    private fun plusOne(value: Long): Long { demand(value < Long.MAX_VALUE); return value + 1 }
    private fun integer(value: Long) = G0DomainJournal.Cell(Cursor.FIELD_TYPE_INTEGER, value)
    private fun <T : Any> requireNotNullOrReject(value: T?): T = value ?: reject()
    private fun demand(value: Boolean) { if (!value) reject() }
    private fun reject(): Nothing = throw Rejected()

    /** Freeze caller collections and BLOB lists before comparison. This is bounded copying of
     * this transaction's transcript, not a whole-database baseline or a source of authority. */
    private class Capture {
        private var bytes = 0L
        private fun charge(amount: Long) { demand(amount >= 0 && amount <= MAX_CAPTURE_BYTES - bytes); bytes += amount }
        fun row(value: List<G0DomainJournal.Cell>): List<G0DomainJournal.Cell> {
            val size = value.size; demand(size in 0..64); charge(size.toLong() * 16)
            return Collections.unmodifiableList(List(size) { index ->
                val cell = value[index]
                val frozen = when (cell.type) {
                    Cursor.FIELD_TYPE_NULL -> { demand(cell.value == null); null }
                    Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> cell.value as? Long ?: reject()
                    Cursor.FIELD_TYPE_STRING -> (cell.value as? String ?: reject()).also { charge(it.length.toLong() * 2) }
                    Cursor.FIELD_TYPE_BLOB -> {
                        val source = cell.value as? List<*> ?: reject()
                        val count = source.size; demand(count >= 0); charge(count.toLong())
                        Collections.unmodifiableList(List(count) { source[it] as? Byte ?: reject() })
                    }
                    else -> reject()
                }
                G0DomainJournal.Cell(cell.type, frozen)
            })
        }
        fun changes(value: List<G0DomainJournal.Change>): List<G0DomainJournal.Change> {
            val size = value.size; demand(size in 0..MAX_CHANGES); charge(size.toLong() * 32)
            return Collections.unmodifiableList(List(size) { index ->
                val change = value[index]
                demand(change.table.length in 1..128 && change.operation.length in 1..8)
                G0DomainJournal.Change(change.sequence, change.table, change.operation, change.old?.let(::row), change.new?.let(::row))
            })
        }
    }
}
