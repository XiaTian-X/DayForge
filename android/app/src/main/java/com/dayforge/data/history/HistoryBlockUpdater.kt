package com.dayforge.data.history

import com.dayforge.data.history.HistoryBlockCodec.Branch
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Encoded
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import com.dayforge.data.history.HistoryBlockCodec.Node
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import java.time.YearMonth
import java.util.Collections
import java.util.TreeMap

/** Pure append/replace path-copy update under a supplied fixed Unit root. This does not authenticate the root,
 * authorize a write, install blocks, or maintain native business rows. The issuer must prove global
 * stable-identity uniqueness (including unvisited leaves and other Units) through its E directory.
 * Here exact old record/absence is proven only in the owning leaf and duplicate identities in the
 * edited/visited subset are rejected. This method never calls a whole-tree builder or scans siblings.
 */
internal object HistoryBlockUpdater {
    data class Edit(val key: OrderKey, val expected: Record?, val replacement: Record?)
    data class Budget(
        val maxEdits: Int = 32,
        val maxLoadedNodes: Int = 256,
        val maxLoadedBytes: Long = 8L * 1024 * 1024,
        val maxNewNodes: Int = 256,
        val maxNewBytes: Long = 8L * 1024 * 1024,
        val maxPreparedBytes: Long = 8L * 1024 * 1024
    )
    data class Stats(val loadedNodes: Int, val loadedBytes: Long, val newNodes: Int,
        val newBytes: Long, val edits: Int)
    data class Patch(val root: Ref, val newBlocks: Map<String, Bytes>, val stats: Stats)
    class Conflict : IllegalStateException("G0_HISTORY_BLOCK_CAS_MISMATCH")
    enum class Reason { DELETE_UNSUPPORTED }
    class Unsupported(val reason: Reason) : IllegalStateException("G0_HISTORY_BLOCK_UPDATE_$reason")

    /** Nonempty batch; one edit per exact key/stable identity. Delete and key moves are deliberately
     * unavailable in this first append/replace candidate, not rejected as invalid product actions.
     * The input list is captured once before callbacks; Record and Bytes are immutable owned values.
     */
    fun apply(root: Ref, edits: List<Edit>, loader: AuthenticatedHistoryDirectory.Loader,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Patch =
        applyInternal(root, edits, loader, null, budget, checkCancelled)

    /** Explicit decoded-node reuse. Only the sealed Tree issued by one scoped Session is accepted. */
    fun apply(root: Ref, edits: List<Edit>, tree: HistoryNodeSession.Session.Tree,
        budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Patch = try {
        tree.requireRoot(root)
        applyInternal(root, edits, null, tree, budget, checkCancelled).also { tree.ensureActive() }
    } catch (failure: Throwable) { throw tree.invalidate(failure) }

    /** Keep the original Loader and independent update budgets; borrow only matching cached
     * content. Ordinary update/data failures retain the Loader overload's catchable semantics.
     */
    fun apply(root: Ref, edits: List<Edit>, loader: AuthenticatedHistoryDirectory.Loader,
        tree: HistoryNodeSession.Session.Tree, budget: Budget = Budget(), checkCancelled: () -> Unit = {}): Patch {
        tree.requireRoot(root)
        return applyInternal(root, edits, loader, null, budget, checkCancelled, tree).also { tree.ensureActive() }
    }

    private fun applyInternal(root: Ref, edits: List<Edit>, loader: AuthenticatedHistoryDirectory.Loader?,
        tree: HistoryNodeSession.Session.Tree?, budget: Budget, checkCancelled: () -> Unit,
        reuseTree: HistoryNodeSession.Session.Tree? = null): Patch {
        validateBudget(budget)
        validateRoot(root)
        val size = edits.size
        demand(size in 1..budget.maxEdits, Code.BUDGET)
        val owned = ArrayList<Edit>(size)
        for (index in 0 until size) owned += edits[index]
        val keys = HashSet<OrderKey>()
        val ids = HashSet<String>()
        var remainingPrepared = budget.maxPreparedBytes
        for (edit in owned) {
            demand(validIdentity(edit.key.stableFactId) && YearMonth.from(edit.key.recordedBusinessDate) == root.range.unit.month)
            demand(keys.add(edit.key) && ids.add(edit.key.stableFactId))
            val replacement = edit.replacement ?: throw Unsupported(Reason.DELETE_UNSUPPORTED)
            for (record in listOfNotNull(edit.expected, replacement)) {
                demand(record.key == edit.key)
                val bytes = HistoryBlockCodec.recordEncodedSize(record)
                demand(bytes <= remainingPrepared, Code.BUDGET)
                remainingPrepared -= bytes
            }
        }
        checkCancelled()
        val work = Work(loader, tree, budget, checkCancelled, reuseTree)
        var level = work.update(root, owned.sortedBy { it.key })
        while (level.size > 1) {
            checkCancelled()
            val height = level.first().height + 1
            demand(height <= HistoryBlockCodec.MAX_DEPTH, Code.BUDGET)
            level = level.chunked(HistoryBlockCodec.MAX_CHILDREN).map { children ->
                work.retain(encode(Branch(
                    LeafRange(root.range.unit, children.first().range.lowerInclusive, children.last().range.upperExclusive),
                    height, children)))
            }
        }
        val next = level.single()
        demand(next.range == root.range)
        checkCancelled()
        return work.finish(next, owned.size)
    }

    private class Work(val loader: AuthenticatedHistoryDirectory.Loader?, val tree: HistoryNodeSession.Session.Tree?, val budget: Budget,
        val poll: () -> Unit, val reuseTree: HistoryNodeSession.Session.Tree?) {
        private val visited = HashSet<String>()
        private val oldIdentities = HashSet<String>()
        private val resultIdentities = HashSet<String>()
        private val produced = LinkedHashMap<String, Bytes>()
        private var loadedBytes = 0L
        private var newBytes = 0L

        private fun load(ref: Ref): Node {
            poll()
            demand(visited.size < budget.maxLoadedNodes, Code.BUDGET)
            demand(visited.add(ref.digest))
            val allowance = minOf(HistoryBlockCodec.MAX_NODE_BYTES.toLong(), budget.maxLoadedBytes - loadedBytes)
            demand(allowance > 0, Code.BUDGET)
            if (tree != null) {
                val loaded = tree.load(ref, allowance.toInt(), poll)
                loadedBytes += loaded.encodedBytes
                return loaded.node
            }
            val raw = requireNotNull(loader).load(ref.digest, allowance.toInt()) ?: throw Rejected(Code.MALFORMED)
            demand(raw.size.toLong() <= allowance, Code.BUDGET)
            loadedBytes += raw.size
            reuseTree?.reuse(ref, raw, poll)?.let { return it.node }
            return HistoryBlockDiagnostics.measure(HistoryBlockDiagnostics.Stage.NODE_DECODE) {
                HistoryBlockCodec.decode(ref,raw,poll)
            }
        }

        fun update(ref: Ref, edits: List<Edit>): List<Ref> {
            poll()
            demand(edits.isNotEmpty() && edits.all { contains(ref.range, it.key) })
            return when (val node = load(ref)) {
                is Leaf -> updateLeaf(ref, node, edits)
                is Branch -> {
                    val routed = Array(node.children.size) { ArrayList<Edit>() }
                    for (edit in edits) {
                        val child = node.children.indexOfFirst { contains(it.range, edit.key) }
                        demand(child >= 0)
                        routed[child] += edit
                    }
                    val children = ArrayList<Ref>()
                    for ((index, child) in node.children.withIndex()) {
                        poll()
                        if (routed[index].isEmpty()) children += child
                        else children += update(child, routed[index])
                    }
                    if (children == node.children) listOf(ref)
                    else children.chunked(HistoryBlockCodec.MAX_CHILDREN).map { group ->
                        retain(encode(Branch(
                            LeafRange(node.range.unit, group.first().range.lowerInclusive, group.last().range.upperExclusive),
                            node.height, group)))
                    }
                }
            }
        }

        private fun updateLeaf(ref: Ref, node: Leaf, edits: List<Edit>): List<Ref> {
            val rows = TreeMap<OrderKey, Record>()
            for (record in node.records) {
                poll()
                demand(oldIdentities.add(record.key.stableFactId))
                rows[record.key] = record
            }
            for (edit in edits) {
                poll()
                if (rows[edit.key] != edit.expected) throw Conflict()
                rows[edit.key] = requireNotNull(edit.replacement)
            }
            val records = rows.values.toList()
            for (record in records) demand(resultIdentities.add(record.key.stableFactId))
            if (records == node.records) return listOf(ref)
            val planner = HistoryBlockPlanner(HistoryBlockPlanner.Limits(
                pageHeaderBytes = HistoryBlockCodec.LEAF_HEADER_UPPER_BOUND,
                maxPreparedFacts = HistoryBlockCodec.MAX_RECORDS + MAX_EDITS,
                maxPreparedBytes = MAX_BYTES
            ))
            val prepared = records.map { HistoryBlockPlanner.Fact(node.range.unit.partition,
                it.key, it.sourceId, HistoryBlockCodec.recordEncodedSize(it)) }
            val plan = planner.replaceAffectedLeaf(HistoryBlockPlanner.PreparedLeaf(node.range, prepared))
            val leaves = when (plan) {
                is HistoryBlockPlanner.PlanResult.Ready -> plan.leaves
                is HistoryBlockPlanner.PlanResult.Unsupported -> throw Rejected(when (plan.reason) {
                    HistoryBlockPlanner.Reason.PREPARED_BUDGET_EXCEEDED,
                    HistoryBlockPlanner.Reason.NEEDS_LARGE_RECORD_HANDLING -> Code.BUDGET
                    else -> Code.MALFORMED
                })
            }
            return leaves.map { leaf ->
                poll()
                retain(encode(Leaf(leaf.range, leaf.facts.map { rows.getValue(it.key) })))
            }
        }

        fun retain(encoded: Encoded): Ref {
            poll()
            val previous = produced[encoded.ref.digest]
            if (previous == null) {
                demand(produced.size < budget.maxNewNodes && encoded.bytes.size.toLong() <= budget.maxNewBytes - newBytes, Code.BUDGET)
                produced[encoded.ref.digest] = encoded.bytes
                newBytes += encoded.bytes.size
            } else demand(previous == encoded.bytes)
            return encoded.ref
        }

        fun finish(root: Ref, edits: Int): Patch = Patch(root,
            Collections.unmodifiableMap(LinkedHashMap(produced)),
            Stats(visited.size, loadedBytes, produced.size, newBytes, edits))
    }

    private fun encode(node:Node):Encoded {
        return HistoryBlockDiagnostics.measure(HistoryBlockDiagnostics.Stage.NODE_ENCODE) {
            HistoryBlockCodec.encode(node)
        }
    }

    private fun validateRoot(root: Ref) {
        val partition = root.range.unit.partition
        demand(root.range.lowerInclusive == null && root.range.upperExclusive == null)
        demand(validIdentity(partition.scope.localBindingId) && validIdentity(partition.domain) &&
            validIdentity(partition.objectId) && (partition.roundId == null || validIdentity(partition.roundId)))
        demand(root.height in 0..HistoryBlockCodec.MAX_DEPTH)
        var maximum = HistoryBlockCodec.MAX_RECORDS.toLong()
        repeat(root.height) { maximum *= HistoryBlockCodec.MAX_CHILDREN }
        demand(root.count in 0..maximum && root.digest.length == 64 && root.digest.all { it in "0123456789abcdef" })
    }

    private fun validateBudget(b: Budget) {
        demand(b.maxEdits in 1..MAX_EDITS && b.maxLoadedNodes in 0..MAX_NODES && b.maxNewNodes in 0..MAX_NODES &&
            b.maxLoadedBytes in 0..MAX_BYTES && b.maxNewBytes in 0..MAX_BYTES && b.maxPreparedBytes in 0..MAX_BYTES, Code.BUDGET)
    }
    private fun contains(range: LeafRange, key: OrderKey) =
        YearMonth.from(key.recordedBusinessDate) == range.unit.month &&
            (range.lowerInclusive == null || key >= range.lowerInclusive) &&
            (range.upperExclusive == null || key < range.upperExclusive)
    private fun validIdentity(value: String) = value.length in 1..HistoryBlockPlanner.MAX_ID_BYTES && value.all { it.code in 0x21..0x7e }
    private fun demand(value: Boolean, code: Code = Code.MALFORMED) { if (!value) throw Rejected(code) }
    private const val MAX_EDITS = 32
    private const val MAX_NODES = 4096
    private const val MAX_BYTES = 64L * 1024 * 1024
}
