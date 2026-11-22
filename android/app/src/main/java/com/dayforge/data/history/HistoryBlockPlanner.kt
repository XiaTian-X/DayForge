package com.dayforge.data.history

import java.time.LocalDate
import java.time.YearMonth
import java.util.Collections

/** Pure candidate planning only. None of these values authenticate an account, a directory,
 * membership/absence, encoded bytes, or business validity. Prepared means that the caller has
 * supplied the complete explicit unit/leaf; this class cannot establish that completeness.
 *
 * Initial construction may enumerate all explicitly prepared history once. Normal callers locate
 * a bounded directory window and load only the affected keyset range; no DAO or SQL is implemented
 * here. The eventual SQL comparator must be differentially checked against KEY_ENCODING_VERSION.
 */
internal class HistoryBlockPlanner(private val limits: Limits) {
    data class Scope(val localBindingId: String)
    data class Partition(val scope: Scope, val domain: String, val objectId: String, val roundId: String?)
    data class UnitKey(val partition: Partition, val month: YearMonth)

    /** Date is the record's existing business date, never recomputed from Instant or current zone. */
    data class OrderKey(val recordedBusinessDate: LocalDate, val stableFactId: String) : Comparable<OrderKey> {
        override fun compareTo(other: OrderKey): Int {
            val date = recordedBusinessDate.compareTo(other.recordedBusinessDate)
            return if (date != 0) date else stableFactId.compareTo(other.stableFactId)
        }
    }

    /** fullEncodedBytes includes the entire encoded record, framing, source and dependencies that
     * the selected codec places in this record. It is caller-supplied sizing, not verified encoding.
     * Source is content metadata; it never substitutes for Scope or creates a device partition.
     */
    data class Fact(val partition: Partition, val key: OrderKey, val sourceId: String, val fullEncodedBytes: Long)
    data class PreparedUnit(val unit: UnitKey, val facts: List<Fact>)
    data class LeafRange(val unit: UnitKey, val lowerInclusive: OrderKey?, val upperExclusive: OrderKey?)
    data class PreparedLeaf(val range: LeafRange, val facts: List<Fact>)
    data class Leaf(val range: LeafRange, val facts: List<Fact>, val plannedEncodedBytes: Long)

    /** Bounds describe the supplied window, not a statement that the global directory is complete.
     * Null means that side of this unit's key space is open. Adjacent ranges cover this window.
     */
    data class DirectoryWindow(val bounds: LeafRange, val ranges: List<LeafRange>)

    data class Limits(
        val pageHeaderBytes: Long,
        val maxPreparedFacts: Int,
        val maxPreparedBytes: Long,
        val targetPageBytes: Long = 64L * 1024,
        val hardMaxPageBytes: Long = 256L * 1024,
        val maxFactsPerPage: Int = 128,
        val maxPreparedUnits: Int = 1024,
        val maxDirectoryRanges: Int = 128
    )

    enum class Reason {
        UNSUPPORTED_LIMITS, UNSUPPORTED_IDENTITY, DUPLICATE_UNIT, DUPLICATE_IDENTITY,
        WRONG_PARTITION_OR_MONTH, INVALID_RANGE, FACT_OUTSIDE_RANGE, INVALID_DIRECTORY_WINDOW,
        INVALID_ENCODED_SIZE, NEEDS_LARGE_RECORD_HANDLING, PREPARED_BUDGET_EXCEEDED,
        DIRECTORY_BUDGET_EXCEEDED
    }
    sealed interface PlanResult {
        data class Ready(val leaves: List<Leaf>) : PlanResult
        /** No partial plan is usable. Caller retains all facts and uses large-record handling or
         * the unchanged path; this is never a product/business rejection. */
        data class Unsupported(val reason: Reason, val factId: String? = null) : PlanResult
    }
    sealed interface LocateResult {
        data class Located(val range: LeafRange) : LocateResult
        data object NeedDirectoryLookup : LocateResult
        data class Unsupported(val reason: Reason) : LocateResult
    }

    /** Whole-batch operation for explicitly prepared units; not a normal-read entry point. */
    fun planInitial(prepared: List<PreparedUnit>): PlanResult {
        if (!validLimits()) return PlanResult.Unsupported(Reason.UNSUPPORTED_LIMITS)
        val unitCount = prepared.size
        if (unitCount > limits.maxPreparedUnits) return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
        // Read each outer entry once, and inspect nested sizes before enumerating any fact. The
        // captured sizes bound the later copies even if a borrowed list changes its size/iterator.
        val borrowed = ArrayList<BorrowedUnit>(unitCount)
        var factCount = 0
        for (index in 0 until unitCount) {
            val entry = prepared[index]
            val size = entry.facts.size
            if (size > limits.maxPreparedFacts - factCount)
                return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
            factCount += size
            borrowed += BorrowedUnit(entry.unit, entry.facts, size)
        }
        // Every explicit unit, including an empty one, needs one page header at minimum.
        if (limits.pageHeaderBytes > 0L && unitCount.toLong() > limits.maxPreparedBytes / limits.pageHeaderBytes)
            return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
        val owned = borrowed.map { PreparedUnit(it.unit, snapshot(it.facts, it.factCount)) }
        val units = HashSet<UnitKey>()
        val identities = HashSet<Identity>()
        val ready = ArrayList<PreparedLeaf>()
        var remainingBytes = limits.maxPreparedBytes
        for (entry in owned.sortedWith(compareBy<PreparedUnit>(
            { it.unit.partition.scope.localBindingId }, { it.unit.partition.domain },
            { it.unit.partition.objectId }, { it.unit.partition.roundId }, { it.unit.month }
        ))) {
            if (!units.add(entry.unit)) return PlanResult.Unsupported(Reason.DUPLICATE_UNIT)
            val range = LeafRange(entry.unit, null, null)
            val malformed = validate(range, entry.facts, identities)
            if (malformed != null) return malformed
            val sorted = entry.facts.sortedBy { it.key }
            remainingBytes = remainingAfterPages(sorted, remainingBytes)
                ?: return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
            ready += PreparedLeaf(range, sorted)
        }
        // No output leaf or per-page fact list is allocated until the entire batch fits.
        val leaves = ArrayList<Leaf>()
        for (entry in ready) leaves.addAll(split(entry.range, entry.facts))
        return PlanResult.Ready(frozen(leaves))
    }

    /** Only the supplied leaf is replaced. Its outside boundaries are retained, so a late insert
     * cannot repack subsequent leaves. Caller must supply every current fact in this exact leaf,
     * including the prepared change, and separately handle moves across dates/months/partitions.
     */
    fun replaceAffectedLeaf(prepared: PreparedLeaf): PlanResult {
        if (!validLimits()) return PlanResult.Unsupported(Reason.UNSUPPORTED_LIMITS)
        val factCount = prepared.facts.size
        if (factCount > limits.maxPreparedFacts || limits.pageHeaderBytes > limits.maxPreparedBytes)
            return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
        val owned = snapshot(prepared.facts, factCount)
        val malformed = validate(prepared.range, owned, HashSet())
        if (malformed != null) return malformed
        val sorted = owned.sortedBy { it.key }
        remainingAfterPages(sorted, limits.maxPreparedBytes)
            ?: return PlanResult.Unsupported(Reason.PREPARED_BUDGET_EXCEEDED)
        val leaves = split(prepared.range, sorted)
        return PlanResult.Ready(frozen(leaves))
    }

    /** Validates only the shape of an already selected window. Located does not mean membership,
     * absence, authorization, or that these ranges came from an authenticated directory. */
    fun locate(window: DirectoryWindow, key: OrderKey): LocateResult {
        if (!validLimits()) return LocateResult.Unsupported(Reason.UNSUPPORTED_LIMITS)
        val rangeCount = window.ranges.size
        if (rangeCount > limits.maxDirectoryRanges) return LocateResult.Unsupported(Reason.DIRECTORY_BUDGET_EXCEEDED)
        val ranges = snapshot(window.ranges, rangeCount)
        if (!supportedId(key.stableFactId)) return LocateResult.Unsupported(Reason.UNSUPPORTED_IDENTITY)
        if (!validRange(window.bounds) || ranges.isEmpty())
            return LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW)
        var next = window.bounds.lowerInclusive
        for ((index, range) in ranges.withIndex()) {
            if (!validRange(range) || range.unit != window.bounds.unit || range.lowerInclusive != next ||
                (range.upperExclusive == null && index != ranges.lastIndex))
                return LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW)
            next = range.upperExclusive
        }
        if (next != window.bounds.upperExclusive)
            return LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW)
        if (!contains(window.bounds, key)) return LocateResult.NeedDirectoryLookup
        var low = 0
        var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val range = ranges[middle]
            val lower = range.lowerInclusive
            val upper = range.upperExclusive
            if (lower != null && key < lower) high = middle - 1
            else if (upper != null && key >= upper) low = middle + 1
            else return LocateResult.Located(range)
        }
        return LocateResult.Unsupported(Reason.INVALID_DIRECTORY_WINDOW)
    }

    private data class Identity(val scope: Scope, val domain: String, val factId: String)
    private data class BorrowedUnit(val unit: UnitKey, val facts: List<Fact>, val factCount: Int)

    private fun validate(range: LeafRange, facts: List<Fact>, identities: MutableSet<Identity>): PlanResult.Unsupported? {
        if (!validRange(range)) return PlanResult.Unsupported(Reason.INVALID_RANGE)
        val partition = range.unit.partition
        for (fact in facts) {
            if (!supportedId(fact.key.stableFactId) || !supportedId(fact.sourceId))
                return PlanResult.Unsupported(Reason.UNSUPPORTED_IDENTITY, fact.key.stableFactId)
            if (fact.partition != partition || YearMonth.from(fact.key.recordedBusinessDate) != range.unit.month)
                return PlanResult.Unsupported(Reason.WRONG_PARTITION_OR_MONTH, fact.key.stableFactId)
            if (!contains(range, fact.key)) return PlanResult.Unsupported(Reason.FACT_OUTSIDE_RANGE, fact.key.stableFactId)
            if (!identities.add(Identity(partition.scope, partition.domain, fact.key.stableFactId)))
                return PlanResult.Unsupported(Reason.DUPLICATE_IDENTITY, fact.key.stableFactId)
            if (fact.fullEncodedBytes < 0L)
                return PlanResult.Unsupported(Reason.INVALID_ENCODED_SIZE, fact.key.stableFactId)
            if (fact.fullEncodedBytes > limits.hardMaxPageBytes - limits.pageHeaderBytes)
                return PlanResult.Unsupported(Reason.NEEDS_LARGE_RECORD_HANDLING, fact.key.stableFactId)
        }
        return null
    }

    private fun validLimits(): Boolean = limits.pageHeaderBytes >= 0L &&
        limits.targetPageBytes > 0L && limits.hardMaxPageBytes >= limits.targetPageBytes &&
        limits.pageHeaderBytes <= limits.targetPageBytes && limits.maxFactsPerPage > 0 &&
        limits.maxPreparedFacts >= 0 && limits.maxPreparedBytes >= 0L &&
        limits.maxPreparedUnits >= 0 && limits.maxDirectoryRanges >= 0

    private fun validRange(range: LeafRange): Boolean {
        val partition = range.unit.partition
        if (!supportedId(partition.scope.localBindingId) || !supportedId(partition.domain) ||
            !supportedId(partition.objectId) || (partition.roundId != null && !supportedId(partition.roundId))) return false
        for (key in listOfNotNull(range.lowerInclusive, range.upperExclusive)) {
            if (!supportedId(key.stableFactId) || YearMonth.from(key.recordedBusinessDate) != range.unit.month) return false
        }
        return range.lowerInclusive == null || range.upperExclusive == null || range.lowerInclusive < range.upperExclusive
    }

    private fun contains(range: LeafRange, key: OrderKey): Boolean =
        YearMonth.from(key.recordedBusinessDate) == range.unit.month &&
            (range.lowerInclusive == null || key >= range.lowerInclusive) &&
            (range.upperExclusive == null || key < range.upperExclusive)

    /** Size-only preflight over the owned, validated facts. Subtraction includes every new page
     * header and avoids Long overflow; no leaves or page lists are allocated by this pass. */
    private fun remainingAfterPages(sorted: List<Fact>, available: Long): Long? {
        if (limits.pageHeaderBytes > available) return null
        var remaining = available - limits.pageHeaderBytes
        var pageBytes = limits.pageHeaderBytes
        var pageFacts = 0
        for (fact in sorted) {
            if (startsNewPage(pageFacts, pageBytes, fact)) {
                if (limits.pageHeaderBytes > remaining) return null
                remaining -= limits.pageHeaderBytes
                pageBytes = limits.pageHeaderBytes
                pageFacts = 0
            }
            if (fact.fullEncodedBytes > remaining) return null
            remaining -= fact.fullEncodedBytes
            pageBytes += fact.fullEncodedBytes
            pageFacts++
        }
        return remaining
    }

    private fun startsNewPage(count: Int, bytes: Long, fact: Fact): Boolean = count > 0 &&
        (count == limits.maxFactsPerPage || fact.fullEncodedBytes > limits.targetPageBytes - bytes)

    private fun split(range: LeafRange, sorted: List<Fact>): List<Leaf> {
        val leaves = ArrayList<Leaf>()
        var lower = range.lowerInclusive
        var batch = ArrayList<Fact>()
        var bytes = limits.pageHeaderBytes
        for (fact in sorted) {
            if (startsNewPage(batch.size, bytes, fact)) {
                leaves += Leaf(LeafRange(range.unit, lower, fact.key), frozen(batch), bytes)
                lower = fact.key
                batch = ArrayList()
                bytes = limits.pageHeaderBytes
            }
            batch += fact
            // validate() guarantees an individual record fits the hard bound. An above-target
            // singleton is closed before another fact is added, keeping this addition bounded.
            bytes += fact.fullEncodedBytes
        }
        // An explicitly prepared empty unit/leaf remains an explicit empty range; this does not
        // manufacture empty buckets for missing months/days or prove that the range is empty.
        leaves += Leaf(LeafRange(range.unit, lower, range.upperExclusive), frozen(batch), bytes)
        return leaves
    }

    private fun supportedId(value: String): Boolean = value.length in 1..MAX_ID_BYTES && value.all { it.code in 0x21..0x7e }
    /** Only captured, bounded indices are used. A concurrently shortened/broken caller list may
     * throw and fail this call, but no later phase can publish values it did not validate. */
    private fun <T> snapshot(values: List<T>, capturedSize: Int): List<T> {
        val owned = ArrayList<T>(capturedSize)
        for (index in 0 until capturedSize) owned += values[index]
        return Collections.unmodifiableList(owned)
    }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    companion object {
        const val KEY_ENCODING_VERSION = 1
        const val MAX_ID_BYTES = 128
    }
}
