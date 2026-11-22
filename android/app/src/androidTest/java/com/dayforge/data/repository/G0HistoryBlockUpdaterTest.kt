package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockUpdater

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec.Branch
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Leaf
import com.dayforge.data.history.HistoryBlockCodec.Record
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import com.dayforge.data.history.HistoryBlockUpdater.Budget
import com.dayforge.data.history.HistoryBlockUpdater.Edit
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only representation tests. Supplied roots/opaque payloads have no business or account
 * authority. The independent oracle is the complete original record list plus the exact edits;
 * the updater never uses this full read path, and tests do not assert cold or UI performance. */
@RunWith(AndroidJUnit4::class)
class G0HistoryBlockUpdaterTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val day = LocalDate.of(2026, 10, 5)
    private val unit = UnitKey(Partition(Scope("scope"), "count-F-v1", "habit", null), YearMonth.from(day))
    private val full = LeafRange(unit, null, null)
    private fun key(index: Int) = OrderKey(day, index.toString().padStart(6, '0'))
    private fun record(index: Int, payload: Int = index, size: Int = 4): Record =
        Record(key(index), "request-$index", Bytes.copyOf(ByteArray(size) { (payload + it).toByte() }))
    private fun rows(count: Int) = List(count) { record(it) }
    private fun loader(blocks: Map<String, Bytes>, seen: MutableList<String> = mutableListOf()) =
        AuthenticatedHistoryDirectory.Loader { digest, limit ->
            seen += digest
            val bytes = blocks[digest] ?: return@Loader null
            assertTrue(bytes.size <= limit)
            bytes.toByteArray()
        }
    private fun whole(root: Ref, blocks: Map<String, Bytes>): List<Record> =
        directory.readRange(root, null, null, loader(blocks), AuthenticatedHistoryDirectory.Budget(
            maxNodes = 1024, maxLoadedBytes = 32L * 1024 * 1024, maxRecords = 10_000,
            maxResultBytes = 32L * 1024 * 1024)).records
    private fun expected(before: List<Record>, edits: List<Edit>): List<Record> {
        val map = before.associateBy { it.key }.toMutableMap()
        edits.forEach { edit -> assertEquals(map[edit.key], edit.expected); map[edit.key] = requireNotNull(edit.replacement) }
        return map.values.sortedBy { it.key }
    }
    private fun rejected(code: Code = Code.MALFORMED, body: () -> Unit) {
        val failure = runCatching(body).exceptionOrNull()
        assertTrue("Expected $code, got $failure", failure is Rejected)
        assertEquals(code, (failure as Rejected).code)
    }
    private fun conflict(body: () -> Unit) {
        assertTrue(runCatching(body).exceptionOrNull() is HistoryBlockUpdater.Conflict)
    }

    @Test fun explicitEmptyRootAcceptsFirstRecordAndExactNoOpPreservesTheOriginalRoot() {
        val empty = HistoryBlockCodec.encode(Leaf(full, emptyList()))
        val initial = mapOf(empty.ref.digest to empty.bytes)
        val first = record(4)
        val patch = HistoryBlockUpdater.apply(empty.ref, listOf(Edit(first.key, null, first)), loader(initial))
        assertEquals(listOf(first), whole(patch.root, initial + patch.newBlocks))
        assertEquals(1, patch.stats.loadedNodes); assertEquals(1, patch.stats.newNodes)
        assertEquals(1L, patch.root.count)
        val noOp = HistoryBlockUpdater.apply(patch.root, listOf(Edit(first.key, first, first)), loader(initial + patch.newBlocks))
        assertEquals(patch.root, noOp.root); assertTrue(noOp.newBlocks.isEmpty())
        assertEquals(0L, noOp.stats.newBytes)
    }

    @Test fun unsortedSameLeafBatchUsesOneSnapshotAndPreservesAllUneditedRecords() {
        val original = rows(30)
        val built = directory.build(unit, original)
        val appended = record(40)
        val edits = listOf(Edit(appended.key, null, appended), Edit(key(4), original[4], record(4, payload = 99)),
            Edit(key(2), original[2], original[2].copy(sourceId = "changed-source")))
        val seen = mutableListOf<String>()
        val patch = HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks, seen))
        assertEquals(expected(original, edits), whole(patch.root, built.blocks + patch.newBlocks))
        assertEquals(1, seen.size); assertEquals(seen.size, seen.distinct().size)
        assertEquals(3, patch.stats.edits)
        assertEquals(original, whole(built.root, built.blocks))
    }

    @Test fun editsOnDifferentPathsMergeAtTheirCommonAncestorWithoutLosingEitherChange() {
        val original = rows(600)
        val built = directory.build(unit, original)
        val edits = listOf(Edit(key(3), original[3], record(3, 71)),
            Edit(key(598), original[598], record(598, 72)))
        val seen = mutableListOf<String>()
        val patch = HistoryBlockUpdater.apply(built.root, edits, loader(built.blocks, seen))
        assertEquals(expected(original, edits), whole(patch.root, built.blocks + patch.newBlocks))
        assertEquals(3, seen.size); assertEquals(seen.size, seen.distinct().size)
        assertEquals(3, patch.stats.newNodes)
        val old = HistoryBlockCodec.decode(built.root, built.blocks.getValue(built.root.digest).toByteArray()) as Branch
        val next = HistoryBlockCodec.decode(patch.root, patch.newBlocks.getValue(patch.root.digest).toByteArray()) as Branch
        assertEquals(old.children.drop(1).dropLast(1), next.children.drop(1).dropLast(1))
    }

    @Test fun fullLeafAndSixteenChildRootSplitLocallyAndKeepFarLeafReferences() {
        val original = rows(2048)
        val built = directory.build(unit, original)
        assertEquals(1, built.root.height)
        val inserted = Record(OrderKey(day, "000000-extra"), "new-request", Bytes.copyOf(byteArrayOf(19)))
        val edit = Edit(inserted.key, null, inserted)
        val seen = mutableListOf<String>()
        val patch = HistoryBlockUpdater.apply(built.root, listOf(edit), loader(built.blocks, seen))
        assertEquals(2, patch.root.height); assertEquals(2049L, patch.root.count)
        assertEquals(2, seen.size)
        assertEquals(expected(original, listOf(edit)), whole(patch.root, built.blocks + patch.newBlocks))
        val oldRoot = HistoryBlockCodec.decode(built.root, built.blocks.getValue(built.root.digest).toByteArray()) as Branch
        val newRoot = HistoryBlockCodec.decode(patch.root, patch.newBlocks.getValue(patch.root.digest).toByteArray()) as Branch
        val afterLeaves = newRoot.children.flatMap { ref ->
            (HistoryBlockCodec.decode(ref, patch.newBlocks.getValue(ref.digest).toByteArray()) as Branch).children
        }
        assertEquals(oldRoot.children.drop(1), afterLeaves.drop(2))
        assertEquals(5, patch.stats.newNodes)
    }

    @Test fun payloadGrowthSplitsOnlyItsLeafAndMatchesTheCompleteRecordOracle() {
        val original = List(8) { record(it, size = 20 * 1024) }
        val built = directory.build(unit, original)
        val edited = original.first().copy(payload = Bytes.copyOf(ByteArray(90 * 1024) { 9 }))
        val edit = Edit(edited.key, original.first(), edited)
        val patch = HistoryBlockUpdater.apply(built.root, listOf(edit), loader(built.blocks))
        assertEquals(expected(original, listOf(edit)), whole(patch.root, built.blocks + patch.newBlocks))
        assertEquals(original.size.toLong(), patch.root.count)
        val old = HistoryBlockCodec.decode(built.root, built.blocks.getValue(built.root.digest).toByteArray()) as Branch
        val next = HistoryBlockCodec.decode(patch.root, patch.newBlocks.getValue(patch.root.digest).toByteArray()) as Branch
        assertEquals(old.children.drop(1), next.children.drop(2))
    }

    @Test fun exactCompareAndSwapRequiresBothFullOldValueAndAuthenticatedAbsence() {
        val original = rows(4); val built = directory.build(unit, original)
        conflict { HistoryBlockUpdater.apply(built.root, listOf(Edit(key(0), null, record(0, 9))), loader(built.blocks)) }
        conflict { HistoryBlockUpdater.apply(built.root, listOf(Edit(key(0), record(0, 8), record(0, 9))), loader(built.blocks)) }
        conflict { HistoryBlockUpdater.apply(built.root, listOf(Edit(key(0), original[0].copy(sourceId = "wrong"), record(0, 9))), loader(built.blocks)) }
        conflict { HistoryBlockUpdater.apply(built.root, listOf(Edit(key(9), record(9), record(9, 9))), loader(built.blocks)) }
        assertEquals(original, whole(built.root, built.blocks))
        // The first changed leaf may already have been encoded when a later path fails CAS.
        // Nothing may escape as a usable partial patch, and the source map remains unchanged.
        val many = rows(400); val multiple = directory.build(unit, many)
        var partial: HistoryBlockUpdater.Patch? = null
        conflict { partial = HistoryBlockUpdater.apply(multiple.root,
            listOf(Edit(key(0), many[0], record(0, 91)), Edit(key(399), record(399, 17), record(399, 92))),
            loader(multiple.blocks)) }
        assertNull(partial); assertEquals(many, whole(multiple.root, multiple.blocks))
    }

    @Test fun duplicateEditsMovesDeletionAndWrongUnitAreUnavailableWithoutReadingStorage() {
        val built = directory.build(unit, rows(3)); var loads = 0
        val noRead = AuthenticatedHistoryDirectory.Loader { _, _ -> loads++; error("Must validate before I/O") }
        val replacement = record(7)
        val edit = Edit(replacement.key, null, replacement)
        rejected { HistoryBlockUpdater.apply(built.root, listOf(edit, edit), noRead) }
        val otherDate = replacement.copy(key = replacement.key.copy(recordedBusinessDate = day.plusDays(1)))
        rejected { HistoryBlockUpdater.apply(built.root, listOf(edit, Edit(otherDate.key, null, otherDate)), noRead) }
        rejected { HistoryBlockUpdater.apply(built.root, listOf(edit.copy(replacement = record(8))), noRead) }
        val outside = replacement.copy(key = replacement.key.copy(recordedBusinessDate = day.plusMonths(1)))
        rejected { HistoryBlockUpdater.apply(built.root, listOf(Edit(outside.key, null, outside)), noRead) }
        val deletion = runCatching { HistoryBlockUpdater.apply(built.root, listOf(Edit(key(0), record(0), null)), noRead) }.exceptionOrNull()
        assertTrue(deletion is HistoryBlockUpdater.Unsupported)
        assertEquals(HistoryBlockUpdater.Reason.DELETE_UNSUPPORTED, (deletion as HistoryBlockUpdater.Unsupported).reason)
        assertEquals(0, loads)
    }

    @Test fun malformedOrChangedRootContentNeverProducesAPartialPatch() {
        val built = directory.build(unit, rows(3)); val edit = Edit(key(0), record(0), record(0, 6))
        val raw = built.blocks.getValue(built.root.digest).toByteArray()
        val damaged = raw.copyOf().also { it[0] = 0 }
        rejected(Code.HASH_MISMATCH) { HistoryBlockUpdater.apply(built.root, listOf(edit),
            AuthenticatedHistoryDirectory.Loader { _, _ -> damaged }) }
        val malformedRoot = built.root.copy(digest = HistoryBlockCodec.hash(damaged))
        rejected { HistoryBlockUpdater.apply(malformedRoot, listOf(edit),
            AuthenticatedHistoryDirectory.Loader { _, _ -> damaged }) }
        rejected(Code.CONTEXT) { HistoryBlockUpdater.apply(built.root.copy(count = 2), listOf(edit), loader(built.blocks)) }
        rejected { HistoryBlockUpdater.apply(built.root.copy(range = full.copy(lowerInclusive = key(0))), listOf(edit), loader(built.blocks)) }
        rejected { HistoryBlockUpdater.apply(built.root, listOf(edit), AuthenticatedHistoryDirectory.Loader { _, _ -> null }) }
    }

    @Test fun cumulativeReadPreparedAndWriteBudgetsRejectWithoutTruncation() {
        val built = directory.build(unit, rows(400)); val edit = Edit(key(0), record(0), record(0, 99))
        val cases = listOf(Budget(maxLoadedNodes = 0), Budget(maxLoadedBytes = 1),
            Budget(maxNewNodes = 0), Budget(maxNewBytes = 1), Budget(maxPreparedBytes = 1),
            Budget(maxLoadedNodes = 1))
        for (budget in cases) rejected(Code.BUDGET) {
            HistoryBlockUpdater.apply(built.root, listOf(edit), AuthenticatedHistoryDirectory.Loader { digest, _ ->
                built.blocks[digest]?.toByteArray()
            }, budget)
        }
        val tooMany = (500..532).map { Edit(key(it), null, record(it)) }
        rejected(Code.BUDGET) { HistoryBlockUpdater.apply(built.root, tooMany, loader(built.blocks)) }
        rejected(Code.BUDGET) { HistoryBlockUpdater.apply(built.root, listOf(edit), loader(built.blocks), Budget(maxNewBytes = -1)) }
        assertEquals(rows(400), whole(built.root, built.blocks))
    }

    @Test fun cancelledEntryOrLateEncodingReturnsNoPatchAndLeavesSourceBytesUnchanged() {
        val built = directory.build(unit, rows(300)); val edit = Edit(key(0), record(0), record(0, 99))
        var polls = 0
        HistoryBlockUpdater.apply(built.root, listOf(edit), loader(built.blocks)) { polls++ }
        assertTrue(polls > 5)
        for (cancelAt in listOf(1, polls - 1, polls)) {
            var seen = 0; var result: HistoryBlockUpdater.Patch? = null
            val failure = runCatching {
                result = HistoryBlockUpdater.apply(built.root, listOf(edit), loader(built.blocks)) {
                    seen++; if (seen == cancelAt) throw CancellationException("cancel")
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException); assertNull(result)
            assertEquals(rows(300), whole(built.root, built.blocks))
        }
    }

    @Test fun inputListIsFrozenBeforeLoaderCallbacksAndReturnedBlockBytesCannotBeMutated() {
        val original = rows(5); val built = directory.build(unit, original)
        val wanted = record(0, 43)
        val edits = mutableListOf(Edit(key(0), original[0], wanted))
        val patch = HistoryBlockUpdater.apply(built.root, edits, AuthenticatedHistoryDirectory.Loader { digest, _ ->
            edits.clear(); built.blocks[digest]?.toByteArray()
        })
        assertEquals(listOf(wanted) + original.drop(1), whole(patch.root, built.blocks + patch.newBlocks))
        assertTrue(runCatching { (patch.newBlocks as MutableMap<String, Bytes>).clear() }.isFailure)
        patch.newBlocks.values.forEach { it.toByteArray().fill(0) }
        assertEquals(listOf(wanted) + original.drop(1), whole(patch.root, built.blocks + patch.newBlocks))
    }

    @Test fun onePointUpdateDoesNotRequestFarHistoricalPagesAtAnyTreeDepth() {
        val original = rows(4096); val built = directory.build(unit, original)
        assertEquals(2, built.root.height)
        val path = mutableListOf<String>()
        directory.readPoint(built.root, key(3), loader(built.blocks, path))
        assertEquals(3, path.size)
        val seen = mutableListOf<String>()
        val edit = Edit(key(3), original[3], record(3, 88))
        val patch = HistoryBlockUpdater.apply(built.root, listOf(edit), AuthenticatedHistoryDirectory.Loader { digest, _ ->
            assertTrue("Unrelated historical node requested", digest in path)
            seen += digest; built.blocks[digest]?.toByteArray()
        })
        assertEquals(path.toSet(), seen.toSet()); assertEquals(3, patch.stats.loadedNodes)
        assertEquals(3, patch.stats.newNodes)
        assertEquals(expected(original, listOf(edit)), whole(patch.root, built.blocks + patch.newBlocks))
    }

    @Test fun consecutiveMixedBatchesAlwaysMatchAnIndependentFullRecordOracle() {
        var oracle = rows(256)
        val built = directory.build(unit, oracle)
        var root = built.root
        var blocks = built.blocks
        repeat(4) { batch ->
            val existing = oracle[batch * 7]
            val additions = (0..3).map { record(300 + batch * 4 + it) }
            val edits = additions.map { Edit(it.key, null, it) } +
                Edit(existing.key, existing, existing.copy(sourceId = "updated-$batch"))
            val patch = HistoryBlockUpdater.apply(root, edits.reversed(), loader(blocks))
            oracle = expected(oracle, edits)
            blocks = blocks + patch.newBlocks; root = patch.root
            assertEquals(oracle, whole(root, blocks))
            assertEquals(oracle.size.toLong(), root.count)
        }
        // First-build control bytes and root remain immutable despite all later patches.
        assertEquals(rows(256), whole(built.root, built.blocks))
    }
}
