package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.history.HistoryNodeSession

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import com.dayforge.data.history.HistoryBlockUpdater.Edit
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only representation differential. No root/account/publication authority is issued.
 * CPU spans count actual unchanged codec invocations; Lookup calls alone are not the oracle. */
@RunWith(AndroidJUnit4::class)
class G0HistoryNodeSessionTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val date = LocalDate.of(2026, 10, 5)
    private val unit = UnitKey(Partition(Scope("scope"), "count-F-v1", "habit", null), YearMonth.from(date))
    private fun key(n: Int) = OrderKey(date, n.toString().padStart(5, '0'))
    private fun record(n: Int) = Record(key(n), "request-$n", Bytes.copyOf(byteArrayOf(n.toByte())))
    private fun built(n: Int) = directory.build(unit, List(n, ::record))
    private fun raw(map: Map<String, Bytes>) = AuthenticatedHistoryDirectory.Loader { id, _ -> map[id]?.toByteArray() }
    private fun lookup(map: Map<String, Bytes>) = HistoryNodeSession.Lookup { id, _ -> map[id] }
    private fun rejected(code: Code, failure: Throwable?) {
        assertTrue("Expected $code, got $failure", failure is Rejected)
        assertEquals(code, (failure as Rejected).code)
    }
    private suspend fun <T> scoped(built: AuthenticatedHistoryDirectory.Built,
        budget: HistoryNodeSession.Budget = HistoryNodeSession.Budget(),
        block: suspend (HistoryNodeSession.Session, HistoryNodeSession.Session.Tree) -> T): T {
        val identity = Any()
        return HistoryNodeSession.use(identity, built.root, lookup(built.blocks), budget) { session ->
            block(session, session.tree(identity, built.root))
        }
    }

    @Test fun repeatedQueriesKeepLogicalAccountingAndStrictlyReduceActualCodecSpans() = runBlocking<Unit> {
        val built = built(8); val baseline = G0PhaseTrace(false)
        val expected = baseline.run { listOf(directory.readPoint(built.root, key(2), raw(built.blocks)),
            directory.readPoint(built.root, key(7), raw(built.blocks))) }
        val candidate = G0PhaseTrace(false)
        var stats: HistoryNodeSession.Stats? = null
        val actual = candidate.run { scoped(built) { session, tree ->
            listOf(directory.readPoint(built.root, key(2), tree), directory.readPoint(built.root, key(7), tree))
                .also { stats = session.stats() }
        } }
        assertEquals(expected, actual)
        assertEquals(2, baseline.count("count_block_node_decode"))
        assertEquals(1, candidate.count("count_block_node_decode"))
        val measured = requireNotNull(stats)
        assertEquals(2, measured.visits); assertEquals(1, measured.decodedNodes); assertEquals(1, measured.hits)
        assertEquals(measured.decodedBytes * 2, measured.processedBytes)
        val spans = candidate.spans.filter { it.phase == "count_block_node_decode" }
        assertTrue(spans.all { it.ok && (it.cpuNanos == null || it.cpuNanos >= 0L) })
    }

    @Test fun priorReadAndPathCopyUpdaterShareVerifiedNodesButProduceIdenticalPatchBytes() = runBlocking<Unit> {
        val built = built(400); val replacement = record(3).copy(sourceId = "changed")
        val edits = listOf(Edit(key(3), record(3), replacement))
        val baselineTrace = G0PhaseTrace(false)
        val baseline = baselineTrace.run {
            directory.readPoint(built.root, key(3), raw(built.blocks))
            HistoryBlockUpdater.apply(built.root, edits, raw(built.blocks))
        }
        val candidateTrace = G0PhaseTrace(false)
        var stats: HistoryNodeSession.Stats? = null
        val candidate = candidateTrace.run { scoped(built) { session, tree ->
            directory.readPoint(built.root, key(3), tree)
            HistoryBlockUpdater.apply(built.root, edits, tree).also { stats = session.stats() }
        } }
        assertEquals(baseline, candidate)
        assertEquals(4, baselineTrace.count("count_block_node_decode"))
        assertEquals(2, candidateTrace.count("count_block_node_decode"))
        assertEquals(2, requireNotNull(stats).hits)
        assertEquals(baselineTrace.count("count_block_node_encode"), candidateTrace.count("count_block_node_encode"))
        val all = directory.readRange(candidate.root, null, null, raw(built.blocks + candidate.newBlocks)).records
        assertEquals(List(400) { if (it == 3) replacement else record(it) }, all)
    }

    @Test fun aCacheHitNeverSkipsQueryRangeAbsenceOrResultBudgetChecks() = runBlocking<Unit> {
        val built = built(10)
        scoped(built) { _, tree ->
            assertTrue(directory.readPoint(built.root, key(99), tree).records.isEmpty())
            assertEquals(listOf(record(2), record(3)), directory.readRange(built.root, key(2), key(4), tree).records)
        }
        val baseline = runCatching { directory.readRange(built.root, key(2), key(4), raw(built.blocks),
            AuthenticatedHistoryDirectory.Budget(maxRecords = 1)) }.exceptionOrNull()
        val candidate = runCatching { scoped(built) { _, tree ->
            directory.readPoint(built.root, key(9), tree)
            directory.readRange(built.root, key(2), key(4), tree,
                AuthenticatedHistoryDirectory.Budget(maxRecords = 1))
        } }.exceptionOrNull()
        rejected(Code.BUDGET, baseline); rejected(Code.BUDGET, candidate)
    }

    @Test fun cachedOldValueDoesNotAuthorizeAnIncorrectCompareAndSwap() = runBlocking<Unit> {
        val built = built(4); val wrong = record(1).copy(sourceId = "not-the-old-source")
        val edits = listOf(Edit(key(1), wrong, record(1).copy(sourceId = "next")))
        val baseline = runCatching { HistoryBlockUpdater.apply(built.root, edits, raw(built.blocks)) }.exceptionOrNull()
        val candidate = runCatching { scoped(built) { _, tree ->
            directory.readPoint(built.root, key(1), tree)
            HistoryBlockUpdater.apply(built.root, edits, tree)
        } }.exceptionOrNull()
        assertTrue(baseline is HistoryBlockUpdater.Conflict)
        assertTrue(candidate is HistoryBlockUpdater.Conflict)
    }

    @Test fun identicalDigestWithDifferentCompleteRefCannotBorrowACachedNode() = runBlocking<Unit> {
        val built = built(4)
        for (changed in listOf(built.root.copy(count = 3), built.root.copy(height = 1))) {
            var calls = 0; val identity = Any()
            val failure = runCatching { HistoryNodeSession.use(identity, built.root,
                HistoryNodeSession.Lookup { id, _ -> calls++; built.blocks[id] }) { session ->
                val tree = session.tree(identity, built.root)
                directory.readPoint(built.root, key(1), tree)
                tree.load(changed, HistoryBlockCodec.MAX_NODE_BYTES) {}
            } }.exceptionOrNull()
            rejected(Code.CONTEXT, failure); assertEquals(1, calls)
        }
    }

    @Test fun stateIdentityUnitRootAndPrivateTreeRegistryCannotBeSubstituted() = runBlocking<Unit> {
        val built = built(4)
        for (mode in listOf("state", "scope", "root", "forged")) {
            val identity = Any()
            val failure = runCatching { HistoryNodeSession.use(identity, built.root, lookup(built.blocks)) { session ->
                val registered = session.tree(identity, built.root)
                when (mode) {
                    "state" -> session.tree(Any(), built.root)
                    "scope" -> session.tree(identity, built.root.copy(range = built.root.range.copy(unit =
                        unit.copy(partition = unit.partition.copy(scope = Scope("other-scope"))))))
                    "root" -> session.tree(identity, built.root.copy(digest = "a".repeat(64)))
                    else -> directory.readPoint(built.root, key(1), HistoryNodeSession.Session.Tree(session, built.root))
                }
                directory.readPoint(built.root, key(1), registered)
            } }.exceptionOrNull()
            rejected(Code.CONTEXT, failure)
        }
    }

    @Test fun escapedTreeClosesAndANewOperationReloadsChangedBytes() = runBlocking<Unit> {
        val built = built(4); val bytes = built.blocks.toMutableMap()
        var escaped: HistoryNodeSession.Session.Tree? = null
        val identity = Any()
        HistoryNodeSession.use(identity, built.root, lookup(bytes)) { session ->
            escaped = session.tree(identity, built.root)
            directory.readPoint(built.root, key(1), requireNotNull(escaped))
        }
        rejected(Code.CONTEXT, runCatching { directory.readPoint(built.root, key(1), requireNotNull(escaped)) }.exceptionOrNull())
        val corrupted = bytes.getValue(built.root.digest).toByteArray().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        bytes[built.root.digest] = Bytes.copyOf(corrupted)
        val failure = runCatching { HistoryNodeSession.use(Any(), built.root, lookup(bytes)) { session ->
            // A fresh identity must be the one used to obtain this operation's Tree.
            session.tree(identity, built.root)
        } }.exceptionOrNull()
        rejected(Code.CONTEXT, failure)
        val nextIdentity = Any()
        val nextFailure = runCatching { HistoryNodeSession.use(nextIdentity, built.root, lookup(bytes)) { session ->
            directory.readPoint(built.root, key(1), session.tree(nextIdentity, built.root))
        } }.exceptionOrNull()
        rejected(Code.HASH_MISMATCH, nextFailure)
        bytes.clear()
        val missingIdentity = Any()
        val missing = runCatching { HistoryNodeSession.use(missingIdentity, built.root, lookup(bytes)) { session ->
            directory.readPoint(built.root, key(1), session.tree(missingIdentity, built.root))
        } }.exceptionOrNull()
        rejected(Code.MALFORMED, missing)
    }

    @Test fun lookupCannotCatchAReentrantFailureAndMakeTheSessionSucceed() = runBlocking<Unit> {
        val built = built(4); val identity = Any()
        lateinit var tree: HistoryNodeSession.Session.Tree
        var swallowed: Throwable? = null
        val lookup = HistoryNodeSession.Lookup { id, _ ->
            swallowed = runCatching { directory.readPoint(built.root, key(1), tree) }.exceptionOrNull()
            built.blocks[id]
        }
        val failure = runCatching { HistoryNodeSession.use(identity, built.root, lookup) { session ->
            tree = session.tree(identity, built.root)
            runCatching { directory.readPoint(built.root, key(1), tree) }
            // Even if the caller swallowed the operation's failure, use must not return success.
            "not-success"
        } }.exceptionOrNull()
        rejected(Code.CONTEXT, failure); assertSame(swallowed, failure)
    }

    @Test fun cacheHitsStillConsumeOperationAndConsumerByteAndVisitBudgets() = runBlocking<Unit> {
        val built = built(8); val size = built.blocks.getValue(built.root.digest).size.toLong()
        for (budget in listOf(HistoryNodeSession.Budget(maxVisits = 1),
            HistoryNodeSession.Budget(maxProcessedBytes = size))) {
            val failure = runCatching { scoped(built, budget) { _, tree ->
                directory.readPoint(built.root, key(1), tree)
                directory.readPoint(built.root, key(2), tree)
            } }.exceptionOrNull()
            rejected(Code.BUDGET, failure)
        }
        val limited = runCatching { scoped(built) { _, tree ->
            directory.readPoint(built.root, key(1), tree)
            directory.readPoint(built.root, key(2), tree,
                AuthenticatedHistoryDirectory.Budget(maxLoadedBytes = size - 1))
        } }.exceptionOrNull()
        rejected(Code.BUDGET, limited)
        val branch = this@G0HistoryNodeSessionTest.built(400)
        val updaterFailure = runCatching { scoped(branch) { _, tree ->
            directory.readPoint(branch.root, key(1), tree)
            HistoryBlockUpdater.apply(branch.root, listOf(Edit(key(1), record(1), record(1).copy(sourceId = "new"))),
                tree, HistoryBlockUpdater.Budget(maxLoadedNodes = 1))
        } }.exceptionOrNull()
        rejected(Code.BUDGET, updaterFailure)
    }

    @Test fun uniqueDecodeAndTreeDomainBudgetsAreBoundedSeparatelyFromVisits() = runBlocking<Unit> {
        val branch = built(400)
        rejected(Code.BUDGET, runCatching { scoped(branch, HistoryNodeSession.Budget(maxDecodedNodes = 1)) { _, tree ->
            directory.readPoint(branch.root, key(1), tree)
        } }.exceptionOrNull())
        val identity = Any(); val other = directory.build(unit.copy(partition = unit.partition.copy(domain = "other")), listOf(record(1)))
        rejected(Code.BUDGET, runCatching { HistoryNodeSession.use(identity, branch.root,
            lookup(branch.blocks + other.blocks), HistoryNodeSession.Budget(maxTrees = 1)) { session ->
            session.tree(identity, other.root)
        } }.exceptionOrNull())
    }

    @Test fun cancellationDuringACacheHitPropagatesAndCannotReturnPartialOutput() = runBlocking<Unit> {
        val built = built(4); val cancelled = CancellationException("hit-cancelled")
        var result: AuthenticatedHistoryDirectory.Slice? = null
        val failure = runCatching { scoped(built) { _, tree ->
            directory.readPoint(built.root, key(1), tree)
            var calls = 0
            result = directory.readPoint(built.root, key(2), tree, checkCancelled = {
                calls++; if (calls == 4) throw cancelled
            })
        } }.exceptionOrNull()
        assertSame(cancelled, failure); assertNull(result)
    }

    @Test fun overlappingNodeWorkFailsClosedWithoutThreadAffinityAssumptions() = runBlocking<Unit> {
        val built = built(4); val identity = Any()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var primaryFailure: Throwable? = null
        val failure = runCatching { HistoryNodeSession.use(identity, built.root,
            HistoryNodeSession.Lookup { id, _ ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); built.blocks[id]
            }) { session ->
            val tree = session.tree(identity, built.root)
            val first = async(Dispatchers.Default) { runCatching { directory.readPoint(built.root, key(1), tree) }.exceptionOrNull() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val second = try { runCatching { directory.readPoint(built.root, key(2), tree) }.exceptionOrNull() }
                finally { release.countDown() }
            rejected(Code.CONTEXT, second)
            primaryFailure = first.await()
        } }.exceptionOrNull()
        rejected(Code.CONTEXT, failure); assertSame(primaryFailure, failure)
    }

    @Test fun immutableDecodedNodesCannotBeModifiedThroughResultOrBinaryCopies() = runBlocking<Unit> {
        val built = built(4)
        scoped(built) { _, tree ->
            val first = directory.readPoint(built.root, key(1), tree)
            assertTrue(runCatching { (first.records as MutableList<Record>).clear() }.isFailure)
            first.records.single().payload.toByteArray().fill(99)
            val node = tree.load(built.root, HistoryBlockCodec.MAX_NODE_BYTES) {}.node as Leaf
            assertTrue(runCatching { (node.records as MutableList<Record>).clear() }.isFailure)
            assertEquals(first, directory.readPoint(built.root, key(1), tree))
        }
    }

    @Test fun malformedContentFailsTheSameStrictCodecOnBothPathsAndFailureIsLatched() = runBlocking<Unit> {
        val built = built(4)
        val malformed = built.blocks.getValue(built.root.digest).toByteArray().also { it[0] = 0 }
        val ref = built.root.copy(digest = HistoryBlockCodec.hash(malformed))
        val map = mapOf(ref.digest to Bytes.copyOf(malformed))
        rejected(Code.MALFORMED, runCatching { directory.readPoint(ref, key(1), raw(map)) }.exceptionOrNull())
        val identity = Any(); var swallowed: Throwable? = null
        val failure = runCatching { HistoryNodeSession.use(identity, ref, lookup(map)) { session ->
            val tree = session.tree(identity, ref)
            swallowed = runCatching { directory.readPoint(ref, key(1), tree) }.exceptionOrNull()
            "caught"
        } }.exceptionOrNull()
        rejected(Code.MALFORMED, failure); assertSame(swallowed, failure)
    }
}
