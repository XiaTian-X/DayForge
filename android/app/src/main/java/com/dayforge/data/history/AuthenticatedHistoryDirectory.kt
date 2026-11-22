package com.dayforge.data.history

import com.dayforge.data.history.HistoryBlockCodec.Branch
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Encoded
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.time.YearMonth
import java.util.Collections

/** Conditional content/range proof under a supplied, fixed Unit root. This layer neither establishes
 * the root's authority nor authenticates a session, validates business payloads, issues publication
 * permission, or proves other units/physical P rows absent. Consumers must establish that external authority separately.
 */
internal class AuthenticatedHistoryDirectory {
    data class Built(val root: Ref, val blocks: Map<String, Bytes>)

    /** Implementations must bound BLOB retrieval before allocating it. The reader additionally
     * checks the returned size before copying/hashing. A missing row is a failure, never absence. */
    fun interface Loader { fun load(digest: String, maxBytes: Int): ByteArray? }
    data class Budget(
        val maxNodes: Int = 256,
        val maxLoadedBytes: Long = 8L * 1024 * 1024,
        val maxRecords: Int = 4096,
        // Payload bytes only; maxRecords separately bounds result objects, not a heap estimate.
        val maxResultBytes: Long = 8L * 1024 * 1024
    )
    data class Stats(val visitedNodes: Int, val loadedBytes: Long,
        val returnedRecords: Int, val returnedPayloadBytes: Long)
    data class Slice(val root: Ref, val query: Query, val records: List<Record>, val stats: Stats)

    /** Full input construction only. A real adapter must first validate business inputs and later
     * atomically commit its root; this method does neither. No normal read invokes this builder. */
    fun build(unit: UnitKey, records: List<Record>): Built {
        val size = records.size
        if (size !in 0..MAX_BUILD_RECORDS) fail(Code.BUDGET)
        val owned = ArrayList<Record>(size)
        for (index in 0 until size) owned += records[index]
        val planner = HistoryBlockPlanner(HistoryBlockPlanner.Limits(
            pageHeaderBytes = HistoryBlockCodec.LEAF_HEADER_UPPER_BOUND,
            maxPreparedFacts = MAX_BUILD_RECORDS, maxPreparedBytes = MAX_BUILD_BYTES
        ))
        val prepared = owned.map { record -> HistoryBlockPlanner.Fact(unit.partition,
            record.key, record.sourceId, HistoryBlockCodec.recordEncodedSize(record)) }
        val planned = planner.planInitial(listOf(HistoryBlockPlanner.PreparedUnit(unit, prepared)))
        val leaves = when (planned) {
            is HistoryBlockPlanner.PlanResult.Ready -> planned.leaves
            is HistoryBlockPlanner.PlanResult.Unsupported -> fail(when (planned.reason) {
                HistoryBlockPlanner.Reason.PREPARED_BUDGET_EXCEEDED,
                HistoryBlockPlanner.Reason.NEEDS_LARGE_RECORD_HANDLING -> Code.BUDGET
                else -> Code.MALFORMED
            })
        }
        // Planner already rejects duplicate stable identities across all dates in this unit.
        val byKey = owned.associateBy { it.key }
        val blocks = LinkedHashMap<String, Bytes>()
        var remainingStoredBytes = MAX_BUILD_BYTES
        fun retain(encoded: Encoded): Ref {
            val previous = blocks[encoded.ref.digest]
            if (previous != null && previous != encoded.bytes) fail(Code.MALFORMED)
            if (previous == null) {
                if (encoded.bytes.size.toLong() > remainingStoredBytes) fail(Code.BUDGET)
                remainingStoredBytes -= encoded.bytes.size.toLong()
                blocks[encoded.ref.digest] = encoded.bytes
            }
            return encoded.ref
        }
        var level = leaves.map { leaf ->
            val encoded = HistoryBlockCodec.encode(Leaf(leaf.range,
                leaf.facts.map { byKey[it.key] ?: fail(Code.MALFORMED) }))
            if (encoded.bytes.size.toLong() > leaf.plannedEncodedBytes) fail(Code.MALFORMED)
            retain(encoded)
        }
        while (level.size > 1) {
            level = level.chunked(HistoryBlockCodec.MAX_CHILDREN).map { children ->
                val range = LeafRange(unit, children.first().range.lowerInclusive,
                    children.last().range.upperExclusive)
                retain(HistoryBlockCodec.encode(Branch(range, children.first().height + 1, children)))
            }
        }
        val root = level.single()
        if (root.range != LeafRange(unit, null, null) || root.count != size.toLong()) fail(Code.MALFORMED)
        return Built(root, Collections.unmodifiableMap(LinkedHashMap(blocks)))
    }

    fun readPoint(root: Ref, key: OrderKey, loader: Loader, budget: Budget = Budget(),
        checkCancelled: () -> Unit = {}): Slice = read(root, Query(key, null, null), loader, null, budget, checkCancelled)

    /** A complete half-open range, not a LIMIT result. Over-budget ranges fail as a whole;
     * cursor pagination and whole-account/month-directory absence are separate future adapters. */
    fun readRange(root: Ref, lower: OrderKey?, upper: OrderKey?, loader: Loader,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Slice =
        read(root, Query(null, lower, upper), loader, null, budget, checkCancelled)

    /** Explicit operation-local candidate; the original Loader overload remains uncached. */
    fun readPoint(root: Ref, key: OrderKey, tree: HistoryNodeSession.Session.Tree,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Slice =
        readScoped(root, Query(key, null, null), tree, budget, checkCancelled)

    fun readRange(root: Ref, lower: OrderKey?, upper: OrderKey?, tree: HistoryNodeSession.Session.Tree,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Slice =
        readScoped(root, Query(null, lower, upper), tree, budget, checkCancelled)

    private fun readScoped(root: Ref, query: Query, tree: HistoryNodeSession.Session.Tree,
        budget: Budget, poll: () -> Unit): Slice = try {
        tree.requireRoot(root)
        read(root, query, null, tree, budget, poll).also { tree.ensureActive() }
    } catch (failure: Throwable) { throw tree.invalidate(failure) }

    data class Query(val point: OrderKey?, val lower: OrderKey?, val upper: OrderKey?) {
        fun includes(key: OrderKey): Boolean = if (point != null) key == point
            else (lower == null || key >= lower) && (upper == null || key < upper)
        fun intersects(range: LeafRange): Boolean = if (point != null)
            (range.lowerInclusive == null || point >= range.lowerInclusive) &&
                (range.upperExclusive == null || point < range.upperExclusive)
        else (lower == null || range.upperExclusive == null || range.upperExclusive > lower) &&
            (upper == null || range.lowerInclusive == null || range.lowerInclusive < upper)
    }

    private fun read(root: Ref, query: Query, loader: Loader?, tree: HistoryNodeSession.Session.Tree?, budget: Budget,
        checkCancelled: () -> Unit): Slice {
        checkCancelled()
        if (budget.maxNodes <= 0 || budget.maxLoadedBytes < 0 || budget.maxRecords < 0 ||
            budget.maxResultBytes < 0) fail(Code.BUDGET)
        if (root.range.lowerInclusive != null || root.range.upperExclusive != null ||
            root.height !in 0..HistoryBlockCodec.MAX_DEPTH || root.count < 0 ||
            !root.digest.matches(Regex("[0-9a-f]{64}"))) fail(Code.MALFORMED)
        for (key in listOfNotNull(query.point, query.lower, query.upper)) {
            if (YearMonth.from(key.recordedBusinessDate) != root.range.unit.month ||
                key.stableFactId.length !in 1..HistoryBlockPlanner.MAX_ID_BYTES ||
                key.stableFactId.any { it.code !in 0x21..0x7e }) fail(Code.MALFORMED)
        }
        if (query.lower != null && query.upper != null && query.lower >= query.upper) fail(Code.MALFORMED)
        val pending = ArrayDeque<Ref>()
        pending.addLast(root)
        val visited = HashSet<String>()
        val visitedIdentities = HashSet<String>()
        val result = ArrayList<Record>()
        var loadedBytes = 0L
        var resultBytes = 0L
        while (pending.isNotEmpty()) {
            checkCancelled()
            val expected = pending.removeLast()
            if (visited.size >= budget.maxNodes) fail(Code.BUDGET)
            if (!visited.add(expected.digest)) fail(Code.MALFORMED)
            val allowance = minOf(HistoryBlockCodec.MAX_NODE_BYTES.toLong(), budget.maxLoadedBytes - loadedBytes)
            if (allowance <= 0) fail(Code.BUDGET)
            val node = if (tree != null) {
                val loaded = tree.load(expected, allowance.toInt(), checkCancelled)
                // Logical traversal budgets still charge every hit as well as every miss.
                loadedBytes += loaded.encodedBytes
                loaded.node
            } else {
                val bytes = requireNotNull(loader).load(expected.digest, allowance.toInt()) ?: fail(Code.MALFORMED)
                if (bytes.size.toLong() > allowance) fail(Code.BUDGET)
                loadedBytes += bytes.size.toLong()
                // The original default path and its decode timing are retained for paired tests.
                HistoryBlockDiagnostics.measure(HistoryBlockDiagnostics.Stage.NODE_DECODE) {
                    HistoryBlockCodec.decode(expected,bytes,checkCancelled)
                }
            }
            when (node) {
                is Leaf -> for (record in node.records) {
                    checkCancelled()
                    if (!visitedIdentities.add(record.key.stableFactId)) fail(Code.MALFORMED)
                    if (query.includes(record.key)) {
                        if (result.size >= budget.maxRecords || record.payload.size.toLong() > budget.maxResultBytes - resultBytes)
                            fail(Code.BUDGET)
                        resultBytes += record.payload.size.toLong()
                        if (result.isNotEmpty() && result.last().key >= record.key) fail(Code.MALFORMED)
                        result += record
                    }
                }
                is Branch -> {
                    // decode verifies every child descriptor, including skipped children. Only
                    // overlapping child contents are loaded. Non-overlap is proved by these bounds.
                    for (index in node.children.indices.reversed()) {
                        val child = node.children[index]
                        if (query.intersects(child.range)) pending.addLast(child)
                    }
                }
            }
        }
        checkCancelled()
        return Slice(root, query, Collections.unmodifiableList(ArrayList(result)),
            Stats(visited.size, loadedBytes, result.size, resultBytes))
    }

    private fun fail(code: Code): Nothing = throw Rejected(code)

    companion object {
        const val MAX_BUILD_RECORDS = 10_000
        const val MAX_BUILD_BYTES = 32L * 1024 * 1024
    }
}
