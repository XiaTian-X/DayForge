package com.dayforge.data.history

import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Node
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One operation's already-decoded immutable nodes, never account authority or a current-root
 * certificate. Only this implementation can create a decoded entry, through the unchanged codec.
 * A supplied Lookup is fixed for the use block and supplies Bytes, never trusted Node callbacks.
 * The surrounding owner must still authenticate the root and revalidate SQL/commit/publication.
 */
internal object HistoryNodeSession {
    fun interface Lookup { fun load(digest: String, maxBytes: Int): Bytes? }
    data class Budget(val maxVisits: Int = 4096, val maxProcessedBytes: Long = 64L * 1024 * 1024,
        val maxDecodedNodes: Int = 4096, val maxDecodedBytes: Long = 32L * 1024 * 1024,
        val maxTrees: Int = 64)
    data class Stats(val visits: Int, val processedBytes: Long, val decodedNodes: Int,
        val decodedBytes: Long, val hits: Int, val trees: Int)
    internal data class Loaded(val node: Node, val encodedBytes: Int)
    private data class Entry(val ref: Ref, val loaded: Loaded)
    private data class Key(val treeRoot: Ref, val digest: String)

    /** baseIdentity is only the exact immutable State instance for this operation; it is not a
     * credential. Each Unit may have one complete root inside a session. A later committed root
     * requires a new session, even if some content-addressed bytes are shared. */
    class Session private constructor(private val baseIdentity: Any, private val baseRoot: Ref,
        private val lookup: Lookup, private val budget: Budget) {
        private val active = AtomicBoolean(true)
        private val firstFailure = AtomicReference<Throwable?>()
        private val busy = AtomicBoolean()
        private val lock = Any()
        private val roots = LinkedHashMap<UnitKey, Ref>().apply { put(baseRoot.range.unit, baseRoot) }
        private val trees = HashMap<UnitKey, Tree>()
        private val cache = HashMap<Key, Entry>()
        private var visits = 0
        private var processedBytes = 0L
        private var decodedBytes = 0L
        private var hits = 0

        fun tree(identity: Any, root: Ref): Tree {
            ensureActive()
            if (busy.get()) fail()
            return synchronized(lock) {
                ensureActive()
                if (identity !== baseIdentity || root.range.unit.partition.scope != baseRoot.range.unit.partition.scope ||
                    root.range.lowerInclusive != null || root.range.upperExclusive != null) fail()
                val previous = roots[root.range.unit]
                if (previous != null && previous != root) fail()
                if (previous == null) {
                    if (roots.size >= budget.maxTrees) fail(Code.BUDGET)
                    roots[root.range.unit] = root
                }
                trees.getOrPut(root.range.unit) { Tree(this, root) }
            }
        }

        fun stats(): Stats = synchronized(lock) {
            ensureActive()
            Stats(visits, processedBytes, cache.size, decodedBytes, hits, roots.size)
        }

        internal fun ensureActive() {
            firstFailure.get()?.let { throw it }
            if (!active.get()) throw Rejected(Code.CONTEXT)
        }
        internal fun invalidate(failure: Throwable): Throwable {
            firstFailure.compareAndSet(null, failure)
            return requireNotNull(firstFailure.get())
        }
        private fun fail(code: Code = Code.CONTEXT): Nothing = throw invalidate(Rejected(code))
        private fun close() {
            active.set(false)
            synchronized(lock) { cache.clear(); trees.clear(); roots.clear() }
        }

        private fun load(treeRoot: Ref, expected: Ref, allowance: Int, poll: () -> Unit): Loaded {
            ensureActive()
            if (!busy.compareAndSet(false, true)) fail()
            try {
                return synchronized(lock) {
                    ensureActive(); poll(); ensureActive()
                    if (roots[treeRoot.range.unit] != treeRoot || expected.range.unit != treeRoot.range.unit ||
                        expected.height > treeRoot.height) fail()
                    if (allowance <= 0 || visits >= budget.maxVisits) fail(Code.BUDGET)
                    val key = Key(treeRoot, expected.digest)
                    val previous = cache[key]
                    val result: Loaded
                    if (previous != null) {
                        // Same digest is insufficient: range, Unit, height and count must also match.
                        if (previous.ref != expected) fail()
                        result = previous.loaded
                        if (result.encodedBytes > allowance || result.encodedBytes.toLong() > budget.maxProcessedBytes - processedBytes)
                            fail(Code.BUDGET)
                        visits++; processedBytes += result.encodedBytes; hits++
                    } else {
                        if (cache.size >= budget.maxDecodedNodes) fail(Code.BUDGET)
                        val limit = minOf(allowance.toLong(), HistoryBlockCodec.MAX_NODE_BYTES.toLong(),
                            budget.maxProcessedBytes - processedBytes, budget.maxDecodedBytes - decodedBytes)
                        if (limit <= 0) fail(Code.BUDGET)
                        val bytes = lookup.load(expected.digest, limit.toInt()) ?: fail(Code.MALFORMED)
                        ensureActive()
                        if (bytes.size.toLong() > limit) fail(Code.BUDGET)
                        // Bytes is owned and immutable. The existing codec still makes its own
                        // input snapshot before hash/strict parse; no external Node is accepted.
                        val raw = bytes.toByteArray() // Same timer boundary as the uncached Loader path.
                        val node = HistoryBlockDiagnostics.measure(HistoryBlockDiagnostics.Stage.NODE_DECODE) {
                            HistoryBlockCodec.decode(expected, raw, poll)
                        }
                        result = Loaded(node, bytes.size)
                        poll(); ensureActive()
                        visits++; processedBytes += bytes.size; decodedBytes += bytes.size
                        cache[key] = Entry(expected, result)
                    }
                    poll(); ensureActive()
                    result
                }
            } catch (failure: Throwable) {
                val first = invalidate(failure)
                synchronized(lock) { cache.clear() }
                throw first
            } finally { busy.set(false) }
        }

        /** Optional reuse after a consumer's original Loader and independent byte accounting.
         * A data mismatch is left to that consumer's original codec, without poisoning this
         * session. Capacity misses never load or retain nodes. Capability failures still latch.
         */
        private fun reuse(treeRoot: Ref, expected: Ref, raw: ByteArray, poll: () -> Unit): Loaded? {
            ensureActive()
            if (!busy.compareAndSet(false, true)) fail()
            try {
                return synchronized(lock) {
                    ensureActive(); poll(); ensureActive()
                    if (roots[treeRoot.range.unit] != treeRoot) fail()
                    val previous = cache[Key(treeRoot, expected.digest)]
                    val loaded = previous?.loaded
                    val candidate = if (previous == null || previous.ref != expected || loaded == null ||
                        raw.size != loaded.encodedBytes || raw.size > HistoryBlockCodec.MAX_NODE_BYTES ||
                        visits >= budget.maxVisits || loaded.encodedBytes.toLong() > budget.maxProcessedBytes - processedBytes) null
                    else {
                        // Freeze the just-loaded image before comparing it with the previously
                        // strict-decoded content. Neither borrowed raw nor a caller Node is kept.
                        val owned = raw.copyOf()
                        poll(); ensureActive()
                        if (HistoryBlockCodec.hash(owned) != expected.digest) null else loaded
                    }
                    poll(); ensureActive()
                    if (candidate != null) {
                        visits++; processedBytes += candidate.encodedBytes; hits++
                    }
                    candidate
                }
            } catch (failure: Throwable) {
                val first = invalidate(failure)
                synchronized(lock) { cache.clear() }
                throw first
            } finally { busy.set(false) }
        }

        private fun requireTree(tree: Tree, root: Ref, expected: Ref) {
            ensureActive()
            if (busy.get()) fail()
            synchronized(lock) {
                ensureActive()
                if (trees[root.range.unit] !== tree || roots[root.range.unit] != root || root != expected) fail()
            }
        }

        /** The constructor is only an identity: an externally fabricated Tree cannot match the
         * Session's private registry. Consumers must present the exact whole Unit root, then keep
         * their normal visited/range/member/CAS/result checks for every traversal. */
        class Tree internal constructor(private val owner: Session, private val root: Ref) {
            internal fun requireRoot(expected: Ref) = owner.requireTree(this, root, expected)
            internal fun load(expected: Ref, allowance: Int, poll: () -> Unit): Loaded {
                owner.requireTree(this, root, root)
                return owner.load(root, expected, allowance, poll)
            }
            internal fun reuse(expected: Ref, raw: ByteArray, poll: () -> Unit): Loaded? {
                owner.requireTree(this, root, root)
                return owner.reuse(root, expected, raw, poll)
            }
            internal fun ensureActive() = owner.requireTree(this, root, root)
            internal fun invalidate(failure: Throwable): Throwable = owner.invalidate(failure)
        }

        companion object {
            internal suspend fun <T> scoped(identity: Any, root: Ref, lookup: Lookup, budget: Budget,
                block: suspend (Session) -> T): T {
                if (root.range.lowerInclusive != null || root.range.upperExclusive != null ||
                    root.height !in 0..HistoryBlockCodec.MAX_DEPTH || root.count < 0 ||
                    root.digest.length != 64 || root.digest.any { it !in "0123456789abcdef" }) throw Rejected(Code.MALFORMED)
                if (budget.maxVisits !in 1..4096 || budget.maxDecodedNodes !in 1..4096 || budget.maxTrees !in 1..64 ||
                    budget.maxProcessedBytes !in 0..64L * 1024 * 1024 || budget.maxDecodedBytes !in 0..32L * 1024 * 1024)
                    throw Rejected(Code.BUDGET)
                val session = Session(identity, root, lookup, budget)
                return try { block(session).also { session.ensureActive() } }
                catch (failure: Throwable) { throw session.invalidate(failure) }
                finally { session.close() }
            }
        }
    }

    /** Escaped Session/Tree values are closed in finally and cannot be reused. Structured callers
     * must not share one session across concurrent operations; concurrent/reentrant node work
     * marks the operation failed. Cancellation propagates without becoming a cache miss. */
    suspend fun <T> use(identity: Any, root: Ref, lookup: Lookup, budget: Budget = Budget(),
        block: suspend (Session) -> T): T {
        return Session.scoped(identity, root, lookup, budget, block)
    }
}
