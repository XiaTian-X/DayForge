package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockCodec.Branch
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
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.history.HistoryBlockUpdater.Budget
import com.dayforge.data.history.HistoryBlockUpdater.Edit
import com.dayforge.data.history.HistoryNodeSession
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only differentials for optional decoded reuse after the complete original Loader.
 * These pure roots are neither account credentials nor evidence of an authenticated H1. */
@RunWith(AndroidJUnit4::class)
class G0HistoryNodeBorrowTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val date = LocalDate.of(2026, 10, 5)
    private val unit = UnitKey(Partition(Scope("scope"), "count-F-v1", "habit", null), YearMonth.from(date))
    private fun key(index: Int) = OrderKey(date, index.toString().padStart(5, '0'))
    private fun record(index: Int) = Record(key(index), "request-$index", Bytes.copyOf(byteArrayOf(index.toByte())))
    private fun built(count: Int) = directory.build(unit, List(count, ::record))
    private fun edit(index: Int) = Edit(key(index), record(index), record(index).copy(sourceId = "changed-$index"))
    private data class Load(val digest: String, val limit: Int)
    private fun loader(blocks: Map<String, Bytes>, loads: MutableList<Load> = mutableListOf()) =
        AuthenticatedHistoryDirectory.Loader { digest, limit ->
            loads += Load(digest, limit)
            blocks[digest]?.toByteArray()
        }
    private fun lookup(blocks: Map<String, Bytes>, loads: MutableList<Load> = mutableListOf()) =
        HistoryNodeSession.Lookup { digest, limit -> loads += Load(digest, limit); blocks[digest] }
    private suspend fun <T> scoped(root: Ref, blocks: Map<String, Bytes>,
        budget: HistoryNodeSession.Budget = HistoryNodeSession.Budget(),
        loads: MutableList<Load> = mutableListOf(),
        block: suspend (HistoryNodeSession.Session, HistoryNodeSession.Session.Tree) -> T): T {
        val identity = Any()
        return HistoryNodeSession.use(identity, root, lookup(blocks, loads), budget) { session ->
            block(session, session.tree(identity, root))
        }
    }
    private fun rejected(code: Code, failure: Throwable?) {
        assertTrue("Expected $code, got $failure", failure is Rejected)
        assertEquals(code, (failure as Rejected).code)
    }
    private fun sameFailure(expected: Throwable?, actual: Throwable?) {
        assertNotNull(expected); assertNotNull(actual)
        assertEquals(expected!!.javaClass, actual!!.javaClass)
        assertEquals(expected.message, actual.message)
    }
    private fun whole(root: Ref, blocks: Map<String, Bytes>): List<Record> =
        directory.readRange(root, null, null, loader(blocks), AuthenticatedHistoryDirectory.Budget(
            maxNodes = 1024, maxLoadedBytes = 32L * 1024 * 1024, maxRecords = 10_000,
            maxResultBytes = 32L * 1024 * 1024)).records

    @Test(timeout = 150_000) fun originalLoaderRequestsRemainExactWhileCodecSpansAndOnlyCodecSpansDecrease() = runBlocking<Unit> {
        val built = built(400); val edits = listOf(edit(3))
        val originalLoads = mutableListOf<Load>(); val originalTrace = G0PhaseTrace(false)
        val expected = originalTrace.run {
            directory.readPoint(built.root, key(3), loader(built.blocks, originalLoads))
            HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks, originalLoads))
        }
        val candidateLoads = mutableListOf<Load>(); val candidateTrace = G0PhaseTrace(false)
        val actual = candidateTrace.run { scoped(built.root, built.blocks, loads = candidateLoads) { session, tree ->
            directory.readPoint(built.root, key(3), tree)
            HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks, candidateLoads), tree).also {
                val stats = session.stats()
                assertEquals(4, stats.visits); assertEquals(2, stats.hits); assertEquals(2, stats.decodedNodes)
                assertEquals(it.stats.loadedBytes * 2, stats.processedBytes)
            }
        } }
        assertEquals(expected, actual) // Complete root, every block byte and all original Work stats.
        assertEquals(originalLoads, candidateLoads); assertEquals(4, candidateLoads.size)
        assertEquals(4, originalTrace.count("count_block_node_decode"))
        assertEquals(2, candidateTrace.count("count_block_node_decode"))
        assertEquals(originalTrace.count("count_block_node_encode"), candidateTrace.count("count_block_node_encode"))
        assertEquals(List(400) { if (it == 3) requireNotNull(edits.single().replacement) else record(it) },
            whole(actual.root, built.blocks + actual.newBlocks))
    }

    @Test(timeout = 150_000) fun exhaustedOptionalBudgetsAndUncachedPathsFallBackWithoutGrowingTheSession() = runBlocking<Unit> {
        val built = built(8); val size = built.blocks.getValue(built.root.digest).size.toLong()
        val edits = listOf(edit(3)); val expected = HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks))
        for (budget in listOf(HistoryNodeSession.Budget(maxVisits = 1), HistoryNodeSession.Budget(maxProcessedBytes = size))) {
            val trace = G0PhaseTrace(false); val loads = mutableListOf<Load>()
            trace.run { scoped(built.root, built.blocks, budget) { session, tree ->
                directory.readPoint(built.root, key(3), tree)
                val before = session.stats()
                assertEquals(expected, HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks, loads), tree))
                assertEquals(before, session.stats())
            } }
            assertEquals(1, loads.size); assertEquals(2, trace.count("count_block_node_decode"))
        }
        val branch = this@G0HistoryNodeBorrowTest.built(400); val other = listOf(edit(399))
        val original = HistoryBlockUpdater.apply(branch.root, other, loader(branch.blocks))
        val trace = G0PhaseTrace(false)
        trace.run { scoped(branch.root, branch.blocks) { session, tree ->
            directory.readPoint(branch.root, key(3), tree)
            val before = session.stats()
            assertEquals(original, HistoryBlockUpdater.apply(branch.root, other, loader(branch.blocks), tree))
            val after = session.stats()
            assertEquals(before.decodedNodes, after.decodedNodes); assertEquals(before.decodedBytes, after.decodedBytes)
            assertEquals(before.hits + 1, after.hits); assertEquals(before.visits + 1, after.visits)
        } }
        assertEquals(3, trace.count("count_block_node_decode")) // Warm root/leaf and the other uncached leaf.
    }

    @Test(timeout = 150_000) fun independentUpdaterBudgetAndCasFailuresDoNotPoisonTheReadSession() = runBlocking<Unit> {
        val built = built(8); val edits = listOf(edit(3))
        val budgets = listOf(Budget(maxEdits = 0), Budget(maxLoadedNodes = 0), Budget(maxLoadedBytes = 1),
            Budget(maxPreparedBytes = 1), Budget(maxNewNodes = 0), Budget(maxNewBytes = 1))
        scoped(built.root, built.blocks) { session, tree ->
            directory.readPoint(built.root, key(3), tree)
            for (budget in budgets) {
                val expected = runCatching { HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks), budget) }.exceptionOrNull()
                val actual = runCatching { HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks), tree, budget) }.exceptionOrNull()
                rejected(Code.BUDGET, actual); sameFailure(expected, actual)
                assertEquals(listOf(record(3)), directory.readPoint(built.root, key(3), tree).records)
                session.stats()
            }
            val wrong = listOf(edit(3).copy(expected = record(3).copy(sourceId = "wrong")))
            val expected = runCatching { HistoryBlockUpdater.apply(built.root, wrong, loader(built.blocks)) }.exceptionOrNull()
            val actual = runCatching { HistoryBlockUpdater.apply(built.root, wrong, loader(built.blocks), tree) }.exceptionOrNull()
            assertTrue(actual is HistoryBlockUpdater.Conflict); sameFailure(expected, actual)
            assertEquals(HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks)),
                HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks), tree))
        }
    }

    @Test(timeout = 150_000) fun cachedContentCannotHideChangedMissingOrOversizedCurrentLoaderBytes() = runBlocking<Unit> {
        val built = built(8); val edits = listOf(edit(3))
        val damaged = built.blocks.getValue(built.root.digest).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val badLoaders = listOf(
            AuthenticatedHistoryDirectory.Loader { _, _ -> damaged },
            AuthenticatedHistoryDirectory.Loader { _, _ -> null },
            AuthenticatedHistoryDirectory.Loader { _, limit -> ByteArray(limit + 1) }
        )
        scoped(built.root, built.blocks) { session, tree ->
            directory.readPoint(built.root, key(3), tree)
            for (bad in badLoaders) {
                val expected = runCatching { HistoryBlockUpdater.apply(built.root, edits, bad) }.exceptionOrNull()
                val actual = runCatching { HistoryBlockUpdater.apply(built.root, edits, bad, tree) }.exceptionOrNull()
                sameFailure(expected, actual)
                assertEquals(listOf(record(3)), directory.readPoint(built.root, key(3), tree).records)
                session.stats()
            }
            assertEquals(HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks)),
                HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks), tree))
        }
    }

    @Test(timeout = 150_000) fun dataRefMismatchFallsThroughToOriginalCodecWithoutInvalidatingTheCapability() = runBlocking<Unit> {
        val built = built(8); val raw = built.blocks.getValue(built.root.digest).toByteArray()
        val changed = listOf(built.root.copy(count = 7), built.root.copy(height = 1),
            built.root.copy(range = built.root.range.copy(upperExclusive = key(9))),
            built.root.copy(range = built.root.range.copy(unit = unit.copy(partition = unit.partition.copy(scope = Scope("other"))))))
        scoped(built.root, built.blocks) { session, tree ->
            directory.readPoint(built.root, key(3), tree)
            for (ref in changed) {
                val before = session.stats()
                assertNull(tree.reuse(ref, raw) {})
                assertEquals(before, session.stats())
                val expected = runCatching { HistoryBlockCodec.decode(ref, raw) }.exceptionOrNull()
                val actual = runCatching { tree.reuse(ref, raw) {}?.node ?: HistoryBlockCodec.decode(ref, raw) }.exceptionOrNull()
                rejected(Code.CONTEXT, actual); sameFailure(expected, actual)
                assertEquals(listOf(record(3)), directory.readPoint(built.root, key(3), tree).records)
            }
        }
    }

    @Test(timeout = 150_000) fun forgedOwnerRootAndEscapedCapabilitiesCannotReachTheOriginalLoader() = runBlocking<Unit> {
        val built = built(8); val edits = listOf(edit(3))
        for (mode in listOf("identity", "forged", "root")) {
            val identity = Any(); var loads = 0; var caught: Throwable? = null
            val original = AuthenticatedHistoryDirectory.Loader { digest, _ -> loads++; built.blocks[digest]?.toByteArray() }
            val failure = runCatching { HistoryNodeSession.use(identity, built.root, lookup(built.blocks)) { session ->
                val tree = session.tree(identity, built.root)
                directory.readPoint(built.root, key(3), tree)
                caught = runCatching {
                    when (mode) {
                        "identity" -> session.tree(Any(), built.root)
                        "forged" -> HistoryBlockUpdater.apply(built.root, edits, original,
                            HistoryNodeSession.Session.Tree(session, built.root))
                        else -> HistoryBlockUpdater.apply(built.root.copy(count = 7), edits, original, tree)
                    }
                }.exceptionOrNull()
                "cannot-succeed-after-catching"
            } }.exceptionOrNull()
            rejected(Code.CONTEXT, failure); assertSame(caught, failure); assertEquals(0, loads)
        }
        var escaped: HistoryNodeSession.Session.Tree? = null
        scoped(built.root, built.blocks) { _, tree -> directory.readPoint(built.root, key(3), tree); escaped = tree }
        var loads = 0
        rejected(Code.CONTEXT, runCatching { HistoryBlockUpdater.apply(built.root, edits,
            AuthenticatedHistoryDirectory.Loader { digest, _ -> loads++; built.blocks[digest]?.toByteArray() }, requireNotNull(escaped))
        }.exceptionOrNull())
        assertEquals(0, loads)
    }

    @Test(timeout = 150_000) fun cancellationAndCaughtReentryDuringBorrowRemainLatchedFailures() = runBlocking<Unit> {
        val built = built(8); val raw = built.blocks.getValue(built.root.digest).toByteArray()
        for (cancel in listOf(true, false)) {
            val cancelled = CancellationException("borrow-cancelled"); var caught: Throwable? = null
            val failure = runCatching { scoped(built.root, built.blocks) { _, tree ->
                directory.readPoint(built.root, key(3), tree)
                runCatching { tree.reuse(built.root, raw) {
                    if (cancel) throw cancelled
                    caught = runCatching { tree.reuse(built.root, raw) {} }.exceptionOrNull()
                } }.exceptionOrNull().also { if (cancel) caught = it }
                "cannot-succeed-after-catching"
            } }.exceptionOrNull()
            if (cancel) assertSame(cancelled, failure) else rejected(Code.CONTEXT, failure)
            assertSame(caught, failure)
        }
    }

    @Test(timeout = 150_000) fun overlappingBorrowFailsClosedBeforeEitherCallerCanReturnANode() = runBlocking<Unit> {
        val built = built(8); val raw = built.blocks.getValue(built.root.digest).toByteArray()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var firstFailure: Throwable? = null; var firstResult: HistoryNodeSession.Loaded? = null
        val failure = runCatching { scoped(built.root, built.blocks) { _, tree ->
            directory.readPoint(built.root, key(3), tree)
            val first = async(Dispatchers.Default) { runCatching {
                firstResult = tree.reuse(built.root, raw) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }.exceptionOrNull() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val second = try { runCatching { tree.reuse(built.root, raw) {} }.exceptionOrNull() }
                finally { release.countDown() }
            rejected(Code.CONTEXT, second); firstFailure = first.await()
        } }.exceptionOrNull()
        rejected(Code.CONTEXT, failure); assertSame(firstFailure, failure); assertNull(firstResult)
    }

    @Test(timeout = 150_000) fun borrowedNodesAndUpdaterInputsRetainOwnedImmutableSnapshots() = runBlocking<Unit> {
        val records = MutableList(8, ::record)
        val node = Leaf(com.dayforge.data.history.HistoryBlockPlanner.LeafRange(unit, null, null), records)
        val encoded = HistoryBlockCodec.encode(node); records.clear()
        val blocks = mapOf(encoded.ref.digest to encoded.bytes); val expectedEdit = edit(3)
        val expected = HistoryBlockUpdater.apply(encoded.ref, listOf(expectedEdit), loader(blocks))
        scoped(encoded.ref, blocks) { _, tree ->
            val slice = directory.readPoint(encoded.ref, key(3), tree)
            slice.records.single().payload.toByteArray().fill(0)
            val raw = encoded.bytes.toByteArray(); val borrowed = requireNotNull(tree.reuse(encoded.ref, raw) {}).node as Leaf
            raw.fill(0)
            assertTrue(runCatching { (borrowed.records as MutableList<Record>).clear() }.isFailure)
            borrowed.records[3].payload.toByteArray().fill(0)
            val edits = mutableListOf(expectedEdit)
            val actual = HistoryBlockUpdater.apply(encoded.ref, edits,
                AuthenticatedHistoryDirectory.Loader { digest, _ -> edits.clear(); blocks[digest]?.toByteArray() }, tree)
            assertEquals(expected, actual)
            actual.newBlocks.values.forEach { it.toByteArray().fill(0) }
            assertEquals(List(8) { if (it == 3) requireNotNull(expectedEdit.replacement) else record(it) },
                whole(actual.root, blocks + actual.newBlocks))
        }
        val branch = built(400)
        scoped(branch.root, branch.blocks) { _, tree ->
            directory.readPoint(branch.root, key(3), tree)
            val borrowed = requireNotNull(tree.reuse(branch.root, branch.blocks.getValue(branch.root.digest).toByteArray()) {}).node as Branch
            assertTrue(runCatching { (borrowed.children as MutableList<Ref>).clear() }.isFailure)
            assertEquals(listOf(record(3)), directory.readPoint(branch.root, key(3), tree).records)
        }
    }

    @Test(timeout = 150_000) fun separateH0AndH1SessionsPreserveBothPurePatchesAndTheCompleteOldOracle() = runBlocking<Unit> {
        val built = built(400); val firstEdits = listOf(edit(3))
        val first = HistoryBlockUpdater.apply(built.root, firstEdits, loader(built.blocks))
        val secondRecord = requireNotNull(firstEdits.single().replacement).copy(sourceId = "second-step")
        val secondEdits = listOf(Edit(key(3), firstEdits.single().replacement, secondRecord), edit(399))
        val h1Blocks = built.blocks + first.newBlocks
        val second = HistoryBlockUpdater.apply(first.root, secondEdits, loader(h1Blocks))
        var oldTree: HistoryNodeSession.Session.Tree? = null
        val actualFirst = scoped(built.root, built.blocks) { _, tree ->
            oldTree = tree; directory.readPoint(built.root, key(3), tree)
            HistoryBlockUpdater.apply(built.root, firstEdits, loader(built.blocks), tree)
        }
        assertEquals(first, actualFirst)
        val actualBlocks = built.blocks + actualFirst.newBlocks
        val actualSecond = scoped(actualFirst.root, actualBlocks) { _, tree ->
            assertNotSame(oldTree, tree)
            directory.readPoint(actualFirst.root, key(3), tree)
            directory.readPoint(actualFirst.root, key(399), tree)
            HistoryBlockUpdater.apply(actualFirst.root, secondEdits, loader(actualBlocks), tree)
        }
        assertEquals(second, actualSecond)
        assertEquals(List(400) { when (it) { 3 -> secondRecord; 399 -> requireNotNull(edit(399).replacement); else -> record(it) } },
            whole(actualSecond.root, actualBlocks + actualSecond.newBlocks))
        assertEquals(List(400, ::record), whole(built.root, built.blocks))
    }
}
