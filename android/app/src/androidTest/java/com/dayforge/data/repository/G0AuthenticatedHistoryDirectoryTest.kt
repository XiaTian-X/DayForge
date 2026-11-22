package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only representation proof tests. Trusted roots are supplied by the fixture; this class
 * does not issue account authority, verify native business tables, or measure cold/UI latency. */
@RunWith(AndroidJUnit4::class)
class G0AuthenticatedHistoryDirectoryTest {
    private val directory = AuthenticatedHistoryDirectory()
    private val unit = DirectoryTestData.unit
    private val all = LeafRange(unit, null, null)

    @Test fun fixedEncodingMatchesIndependentGoldenBytesAndDigest() {
        val goldenUnit = UnitKey(Partition(Scope("scope-a"), "count", "habit-a", null), YearMonth.of(1970, 1))
        val record = Record(OrderKey(LocalDate.ofEpochDay(0), "a"), "device-a", Bytes.copyOf(byteArrayOf(0, -1)))
        val encoded = HistoryBlockCodec.encode(Leaf(LeafRange(goldenUnit, null, null), listOf(record)))
        val expected = DirectoryTestData.fromHex("474842310000000100000001010000000773636f70652d6100000005636f756e740000000768616269742d6100000007b20100000000000000000000000000010000000100000000000000000000000161000000086465766963652d610000000200ff")
        assertArrayEquals(expected, encoded.bytes.toByteArray())
        assertEquals("99c7583d23da562fbf3684116ae47e83c25f2b23cd631a397646fb1122de1065", encoded.ref.digest)
        val decoded = HistoryBlockCodec.decode(encoded.ref, expected) as Leaf
        DirectoryTestData.assertRecords(listOf(record), decoded.records)
    }

    @Test fun wholeAndCrossLeafRangesMatchIndependentOriginalInputOracle() {
        val input = DirectoryTestData.records(400).reversed()
        val built = directory.build(unit, input)
        val same = directory.build(unit, input.drop(17) + input.take(17))
        assertEquals(built.root, same.root)
        val changed = input.toMutableList()
        changed[0] = changed[0].copy(sourceId = "different-device")
        assertNotEquals(built.root.digest, directory.build(unit, changed).root.digest)
        changed[0] = input[0].copy(payload = Bytes.copyOf(byteArrayOf(9, 8, 7)))
        assertNotEquals(built.root.digest, directory.build(unit, changed).root.digest)
        val loader = DirectoryTestData.MapLoader(built.blocks)
        val complete = directory.readRange(built.root, null, null, loader)
        DirectoryTestData.assertRecords(DirectoryTestData.oracle(input), complete.records)
        val lower = DirectoryTestData.key(120)
        val upper = DirectoryTestData.key(270)
        val selected = directory.readRange(built.root, lower, upper, loader)
        assertEquals(built.root, selected.root)
        assertEquals(AuthenticatedHistoryDirectory.Query(null, lower, upper), selected.query)
        DirectoryTestData.assertRecords(DirectoryTestData.oracle(input, lower, upper), selected.records)
        assertEquals(selected.records.size, selected.stats.returnedRecords)
        assertEquals(selected.records.sumOf { it.payload.size.toLong() }, selected.stats.returnedPayloadBytes)
        val dateAndCase = listOf(DirectoryTestData.record("0", DirectoryTestData.day.plusDays(1)),
            DirectoryTestData.record("a"), DirectoryTestData.record("B"), DirectoryTestData.record("z", DirectoryTestData.day.minusDays(1)))
        val dateTree = directory.build(unit, dateAndCase)
        DirectoryTestData.assertRecords(DirectoryTestData.oracle(dateAndCase),
            directory.readRange(dateTree.root, null, null, DirectoryTestData.MapLoader(dateTree.blocks)).records)
    }

    @Test fun pointsProveMembershipAndGapsByReadingOwningLeaf() {
        val input = DirectoryTestData.records(300).filterIndexed { index, _ -> index % 2 == 0 }
        val built = directory.build(unit, input)
        for (index in listOf(0, 1, 128, 129, 298, 299)) {
            val loader = DirectoryTestData.MapLoader(built.blocks)
            val point = directory.readPoint(built.root, DirectoryTestData.key(index), loader)
            assertEquals(built.root, point.root)
            assertEquals(AuthenticatedHistoryDirectory.Query(DirectoryTestData.key(index), null, null), point.query)
            DirectoryTestData.assertRecords(input.filter { it.key == DirectoryTestData.key(index) }, point.records)
            assertTrue(loader.loaded.isNotEmpty())
            assertEquals(point.stats.visitedNodes, loader.loaded.size)
        }
        val absent = directory.readPoint(built.root, OrderKey(DirectoryTestData.day, "!"), DirectoryTestData.MapLoader(built.blocks))
        assertTrue(absent.records.isEmpty())
    }

    @Test fun exactHalfOpenBoundariesDoNotDuplicateOrSkipRecords() {
        val input = DirectoryTestData.records(300)
        val built = directory.build(unit, input)
        val lower = DirectoryTestData.key(128)
        val upper = DirectoryTestData.key(256)
        val slice = directory.readRange(built.root, lower, upper, DirectoryTestData.MapLoader(built.blocks))
        DirectoryTestData.assertRecords(input.subList(128, 256), slice.records)
        DirectoryTestData.rejected { directory.readRange(built.root, upper, lower, DirectoryTestData.MapLoader(built.blocks)) }
        DirectoryTestData.rejected(Code.MALFORMED) { directory.readRange(built.root, lower, lower, DirectoryTestData.MapLoader(built.blocks)) }
    }

    @Test fun explicitEmptyRootIsDifferentFromMissingStorage() {
        val built = directory.build(unit, emptyList())
        assertEquals(0L, built.root.count)
        assertTrue(directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks)).records.isEmpty())
        assertTrue(directory.readPoint(built.root, DirectoryTestData.key(1), DirectoryTestData.MapLoader(built.blocks)).records.isEmpty())
        DirectoryTestData.rejected { directory.readPoint(built.root, DirectoryTestData.key(1), AuthenticatedHistoryDirectory.Loader { _, _ -> null }) }
    }

    @Test fun contextRangeAndExpectedRootMetadataCannotBeRebound() {
        val built = directory.build(unit, DirectoryTestData.records(2))
        val original = built.root
        val wrongUnits = listOf(
            unit.copy(partition = unit.partition.copy(scope = Scope("wrong-scope"))),
            unit.copy(partition = unit.partition.copy(domain = "wrong-domain")),
            unit.copy(partition = unit.partition.copy(objectId = "wrong-object")),
            unit.copy(partition = unit.partition.copy(roundId = "wrong-round")),
            unit.copy(month = unit.month.plusMonths(1)))
        for (wrongUnit in wrongUnits) {
            val wrong = original.copy(range = original.range.copy(unit = wrongUnit))
            DirectoryTestData.rejected { directory.readRange(wrong, null, null, DirectoryTestData.MapLoader(built.blocks)) }
        }
        DirectoryTestData.rejected { directory.readRange(original.copy(count = original.count + 1), null, null, DirectoryTestData.MapLoader(built.blocks)) }
        DirectoryTestData.rejected { directory.readRange(original.copy(height = original.height + 1), null, null, DirectoryTestData.MapLoader(built.blocks)) }
        DirectoryTestData.rejected { directory.readRange(original.copy(range = original.range.copy(lowerInclusive = DirectoryTestData.key(0))), null, null, DirectoryTestData.MapLoader(built.blocks)) }
    }

    @Test fun storedByteTamperMissingNodesAndWrongHashResponsesRejectWithoutPartialSlice() {
        val built = directory.build(unit, DirectoryTestData.records(400))
        val clean = DirectoryTestData.MapLoader(built.blocks)
        directory.readRange(built.root, null, null, clean)
        for (digest in clean.loaded.map { it.first }) {
            val tampered = DirectoryTestData.MapLoader(built.blocks)
            tampered.raw.getValue(digest).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            DirectoryTestData.rejected { directory.readRange(built.root, null, null, tampered) }
            val missing = DirectoryTestData.MapLoader(built.blocks)
            missing.raw.remove(digest)
            DirectoryTestData.rejected { directory.readRange(built.root, null, null, missing) }
        }
        val wrong = DirectoryTestData.MapLoader(built.blocks)
        wrong.raw[built.root.digest] = byteArrayOf(1, 2, 3)
        DirectoryTestData.rejected(Code.HASH_MISMATCH) { directory.readRange(built.root, null, null, wrong) }
    }

    @Test fun unvisitedLeafDamageIsDeferredUntilItsActualUse() {
        val input = DirectoryTestData.records(400)
        val built = directory.build(unit, input)
        val firstPath = DirectoryTestData.MapLoader(built.blocks)
        directory.readPoint(built.root, input.first().key, firstPath)
        val lastPath = DirectoryTestData.MapLoader(built.blocks)
        directory.readPoint(built.root, input.last().key, lastPath)
        val exclusiveLast = lastPath.loaded.last().first
        assertFalse(firstPath.loaded.any { it.first == exclusiveLast })
        val damaged = DirectoryTestData.MapLoader(built.blocks)
        damaged.raw.getValue(exclusiveLast).let { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        DirectoryTestData.assertRecords(listOf(input.first()), directory.readPoint(built.root, input.first().key, damaged).records)
        assertFalse(damaged.loaded.any { it.first == exclusiveLast })
        DirectoryTestData.rejected { directory.readPoint(built.root, input.last().key, damaged) }
    }

    @Test fun hashValidMalformedLeavesCannotUseDigestAsSemanticApproval() {
        val a = DirectoryTestData.record("a")
        val b = DirectoryTestData.record("b")
        val invalidLists = listOf(listOf(b, a), listOf(a, a), listOf(a, a.copy(key = a.key.copy(recordedBusinessDate = DirectoryTestData.day.plusDays(1)))))
        for (records in invalidLists) {
            val bytes = DirectoryTestData.leafWire(all, records)
            val ref = Ref(all, 0, records.size.toLong(), DirectoryTestData.sha(bytes))
            DirectoryTestData.rejected { HistoryBlockCodec.decode(ref, bytes) }
        }
        val outside = LeafRange(unit, DirectoryTestData.key(2), DirectoryTestData.key(4))
        val bytes = DirectoryTestData.leafWire(outside, listOf(DirectoryTestData.records(1).single()))
        val ref = Ref(outside, 0, 1, DirectoryTestData.sha(bytes))
        DirectoryTestData.rejected { HistoryBlockCodec.decode(ref, bytes) }
    }

    @Test fun hashValidDirectoriesRejectGapOverlapOrderAndParentBounds() {
        val split = DirectoryTestData.key(100)
        val left = HistoryBlockCodec.encode(Leaf(LeafRange(unit, null, split), listOf(DirectoryTestData.records(1).single())))
        val right = HistoryBlockCodec.encode(Leaf(LeafRange(unit, split, null), listOf(DirectoryTestData.record("z"))))
        val badChildren = listOf(
            listOf(right.ref, left.ref),
            listOf(left.ref, right.ref.copy(range = right.ref.range.copy(lowerInclusive = DirectoryTestData.key(101)))),
            listOf(left.ref, right.ref.copy(range = right.ref.range.copy(lowerInclusive = DirectoryTestData.key(99)))),
            listOf(left.ref, left.ref),
            listOf(left.ref.copy(range = left.ref.range.copy(lowerInclusive = DirectoryTestData.key(0))), right.ref))
        for (children in badChildren) {
            val wire = DirectoryTestData.branchWire(all, 1, children)
            val ref = Ref(all, 1, children.sumOf { it.count }, DirectoryTestData.sha(wire))
            DirectoryTestData.rejected { HistoryBlockCodec.decode(ref, wire) }
        }
    }

    @Test fun authenticatedParentClaimsMustMatchTheActuallyLoadedChild() {
        val split = DirectoryTestData.key(100)
        val left = HistoryBlockCodec.encode(Leaf(LeafRange(unit, null, split), listOf(DirectoryTestData.records(1).single())))
        val right = HistoryBlockCodec.encode(Leaf(LeafRange(unit, split, null), listOf(DirectoryTestData.record("z"))))
        val children = listOf(left.ref.copy(count = 2), right.ref)
        val wire = DirectoryTestData.branchWire(all, 1, children)
        val root = Ref(all, 1, 3, DirectoryTestData.sha(wire))
        val raw = mapOf(root.digest to wire, left.ref.digest to left.bytes.toByteArray(), right.ref.digest to right.bytes.toByteArray())
        DirectoryTestData.rejected { directory.readPoint(root, DirectoryTestData.key(0), AuthenticatedHistoryDirectory.Loader { digest, _ -> raw[digest] }) }
        val malformedHeight = DirectoryTestData.branchWire(all, 2, listOf(left.ref, right.ref))
        DirectoryTestData.rejected { HistoryBlockCodec.decode(Ref(all, 2, 2, DirectoryTestData.sha(malformedHeight)), malformedHeight) }
    }

    @Test fun strictWireParsingRejectsTruncationTrailingUnknownAndUnboundedLengths() {
        val good = DirectoryTestData.leafWire(all, listOf(DirectoryTestData.record("a")))
        val variants = mutableListOf<ByteArray>()
        for (size in listOf(0, 1, 12, good.size - 1)) variants += good.copyOf(size)
        variants += good + byteArrayOf(0)
        variants += good.copyOf().also { it[7] = 2 }
        variants += good.copyOf().also { it[12] = 99 }
        variants += good.copyOf().also { for (index in 13..16) it[index] = 0x7f }
        variants += DirectoryTestData.leafWire(all, emptyList(), declaredCount = -1, itemCount = -1)
        variants += DirectoryTestData.leafWire(all, emptyList(), declaredCount = Int.MAX_VALUE.toLong(), itemCount = Int.MAX_VALUE)
        for ((index, bytes) in variants.withIndex()) {
            try { HistoryBlockCodec.decode(Ref(all, 0, 1, DirectoryTestData.sha(bytes)), bytes); fail("malformed variant $index accepted") }
            catch (_: Rejected) { }
        }
        val oversize = ByteArray(HistoryBlockCodec.MAX_NODE_BYTES + 1)
        DirectoryTestData.rejected(Code.BUDGET) { HistoryBlockCodec.decode(Ref(all, 0, 1, DirectoryTestData.sha(oversize)), oversize) }
    }

    @Test fun traversalBudgetsStopBeforeFurtherLoadsAndNeverReturnPartialResults() {
        val built = directory.build(unit, DirectoryTestData.records(400))
        val noNodes = DirectoryTestData.MapLoader(built.blocks)
        DirectoryTestData.rejected(Code.BUDGET) { directory.readRange(built.root, null, null, noNodes, AuthenticatedHistoryDirectory.Budget(maxNodes = 0)) }
        assertTrue(noNodes.loaded.isEmpty())
        val oneNode = DirectoryTestData.MapLoader(built.blocks)
        DirectoryTestData.rejected(Code.BUDGET) { directory.readRange(built.root, null, null, oneNode, AuthenticatedHistoryDirectory.Budget(maxNodes = 1)) }
        assertEquals(1, oneNode.loaded.size)
        DirectoryTestData.rejected(Code.BUDGET) { directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks), AuthenticatedHistoryDirectory.Budget(maxLoadedBytes = 1)) }
        DirectoryTestData.rejected(Code.BUDGET) { directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks), AuthenticatedHistoryDirectory.Budget(maxRecords = 399)) }
        DirectoryTestData.rejected(Code.BUDGET) { directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks), AuthenticatedHistoryDirectory.Budget(maxResultBytes = 1)) }
    }

    @Test fun buildAndResultsOwnTheirInputCollectionsAndPayloadBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val copied = Bytes.copyOf(bytes)
        val original = Record(DirectoryTestData.key(1), "source-a", copied)
        val input = mutableListOf(original)
        val built = directory.build(unit, input)
        input.clear(); bytes[0] = 99
        val exported = copied.toByteArray(); exported[1] = 99
        val retainedDigest = HistoryBlockCodec.hash(copied)
        copied.writeTo(DataOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (length > 0) buffer[offset] = 99
            }
        }))
        assertArrayEquals(byteArrayOf(1, 2, 3), copied.toByteArray())
        assertEquals(retainedDigest, HistoryBlockCodec.hash(copied))
        val oneRead = object : AbstractList<Record>() {
            var reads = 0
            override val size = 1
            override fun get(index: Int): Record = if (reads++ == 0) original else original.copy(sourceId = "unverified-later-value")
        }
        assertEquals(built.root, directory.build(unit, oneRead).root)
        val encoded = HistoryBlockCodec.encode(Leaf(all, listOf(original)))
        val borrowedRaw = encoded.bytes.toByteArray()
        var freezeChecks = 0
        val frozen = HistoryBlockCodec.decode(encoded.ref, borrowedRaw) {
            // Current codec callbacks: before freeze, after freeze, then after hash and before parse.
            // Mutating borrowed bytes at the third callback must not change authenticated input.
            if (++freezeChecks == 3) borrowedRaw[borrowedRaw.lastIndex] = 99
        } as Leaf
        assertEquals(99.toByte(), borrowedRaw.last())
        assertArrayEquals(byteArrayOf(1, 2, 3), frozen.records.single().payload.toByteArray())
        val result = directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks))
        assertArrayEquals(byteArrayOf(1, 2, 3), result.records.single().payload.toByteArray())
        val exportedResult = result.records.single().payload.toByteArray(); exportedResult[2] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), result.records.single().payload.toByteArray())
        try { (result.records as MutableList<Record>).clear(); fail("published list mutable") } catch (_: UnsupportedOperationException) { }
        DirectoryTestData.assertRecords(listOf(original), directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks)).records)
    }

    @Test fun loaderBufferMutationCannotChangeAlreadyDecodedRecords() {
        val input = DirectoryTestData.records(300)
        val built = directory.build(unit, input)
        val raw = built.blocks.mapValues { it.value.toByteArray() }
        var previous: ByteArray? = null
        val loader = AuthenticatedHistoryDirectory.Loader { digest, _ ->
            previous?.fill(0)
            raw.getValue(digest).copyOf().also { previous = it }
        }
        val slice = directory.readRange(built.root, null, null, loader)
        previous?.fill(0)
        DirectoryTestData.assertRecords(input, slice.records)
    }

    @Test fun cancellationAndLoaderExceptionsPropagateWithoutBeingConvertedToAbsence() {
        val built = directory.build(unit, DirectoryTestData.records(300))
        val cancelled = CancellationException("test cancellation")
        val loader = DirectoryTestData.MapLoader(built.blocks)
        try { directory.readRange(built.root, null, null, loader, checkCancelled = { throw cancelled }); fail("cancelled read returned") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertTrue(loader.loaded.isEmpty())
        val failure = IllegalStateException("loader I/O failure")
        try { directory.readPoint(built.root, DirectoryTestData.key(0), AuthenticatedHistoryDirectory.Loader { _, _ -> throw failure }); fail("failed read returned") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        var checks = 0
        try {
            directory.readRange(built.root, null, null, DirectoryTestData.MapLoader(built.blocks), checkCancelled = { if (++checks == 4) throw cancelled })
            fail("mid-read cancellation returned")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun androidSqliteUsesPointAddressedBlobsAndMatchesIndependentRangeOracle() {
        val input = DirectoryTestData.records(400)
        val built = directory.build(unit, input)
        DirectoryTestData.SqlStore.memory().use { store ->
            store.install(built.blocks)
            val plan = store.queryPlan()
            assertTrue(plan, plan.contains("SEARCH", ignoreCase = true))
            assertFalse(plan, plan.contains("SCAN", ignoreCase = true))
            val lower = DirectoryTestData.key(120); val upper = DirectoryTestData.key(140)
            val slice = directory.readRange(built.root, lower, upper, store)
            DirectoryTestData.assertRecords(DirectoryTestData.oracle(input, lower, upper), slice.records)
            assertEquals(slice.stats.visitedNodes, store.loaded.size)
            assertEquals(slice.stats.loadedBytes, store.loaded.sumOf { it.second.toLong() })
            assertTrue(store.loaded.size < built.blocks.size)
            store.loaded.clear()
            val projection = store.boundedProjection(built.root.digest, 1)
            assertEquals("blob", projection.storageType)
            assertTrue("fixture root must exceed one byte", requireNotNull(projection.byteLength) > 1)
            assertTrue("SQL CASE must return NULL before getBlob", projection.bodyNull)
            DirectoryTestData.rejected(Code.BUDGET) { directory.readPoint(built.root, input[0].key, store,
                AuthenticatedHistoryDirectory.Budget(maxLoadedBytes = 1)) }
            assertTrue("oversized BLOB must not be materialized", store.loaded.isEmpty())
        }
    }

    @Test fun sqliteRejectsTextStorageBeforeBlobMaterializationIncludingNulAndMultibyte() {
        val variants = listOf("multibyte" to ("é".repeat(16) to 16),
            "embedded-nul" to ("a\u0000" + "b".repeat(16) to 1))
        DirectoryTestData.SqlStore.memory().use { store ->
            for ((variant, valueAndLimit) in variants) {
                val (value, limit) = valueAndLimit
                val digest = DirectoryTestData.sha(value.toByteArray(Charsets.UTF_8))
                store.installTextForRejection(digest, value)
                val beforeBlobReads = store.loaderBlobReads
                val guarded = store.boundedProjection(digest, limit)
                assertEquals(variant, "text", guarded.storageType)
                assertNull("$variant must not use TEXT character length as bytes", guarded.byteLength)
                assertTrue("$variant body projection must be NULL", guarded.bodyNull)
                DirectoryTestData.rejected(Code.MALFORMED) { store.load(digest, limit) }
                assertEquals("$variant rejected before loader getBlob", beforeBlobReads, store.loaderBlobReads)
                assertTrue(store.loaded.isEmpty())

                // Negative control intentionally materializes the old unsafe projection only in
                // this test helper: TEXT length counts characters and stops at an embedded NUL.
                val old = store.legacyTextProjection(digest, limit)
                assertTrue("$variant old SQL sees a length inside its limit", old.first <= limit)
                assertTrue("$variant old SQL nevertheless returns more bytes", old.second > limit)
            }
        }
    }

    @Test fun sqliteCloseAndReopenReadsOnlyNeededPathFromSuppliedTrustedRoot() {
        val input = DirectoryTestData.records(400)
        val built = directory.build(unit, input)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "g0-directory-${UUID.randomUUID()}.db")
        try {
            DirectoryTestData.SqlStore.file(file).use { it.install(built.blocks) }
            DirectoryTestData.SqlStore.file(file).use { store ->
                val slice = directory.readPoint(built.root, input[200].key, store)
                DirectoryTestData.assertRecords(listOf(input[200]), slice.records)
                assertTrue(store.loaded.size < built.blocks.size)
                assertEquals(slice.stats.loadedBytes, store.loaded.sumOf { it.second.toLong() })
            }
        } finally { assertTrue(!file.exists() || SQLiteDatabase.deleteDatabase(file)) }
    }

    @Test fun buildRejectsDuplicateIdentityWrongMonthAndOversizedWorkWithoutTruncation() {
        val a = DirectoryTestData.record("same")
        DirectoryTestData.rejected { directory.build(unit, listOf(a, a.copy(key = a.key.copy(recordedBusinessDate = DirectoryTestData.day.plusDays(1))))) }
        DirectoryTestData.rejected { directory.build(unit, listOf(a.copy(key = a.key.copy(recordedBusinessDate = DirectoryTestData.day.plusMonths(1))))) }
        DirectoryTestData.rejected { directory.build(unit, listOf(a.copy(payload = Bytes.copyOf(ByteArray(HistoryBlockCodec.MAX_NODE_BYTES))))) }
        val unreadable = object : AbstractList<Record>() {
            override val size = 10_001
            override fun get(index: Int): Record = error("oversized preparation was traversed")
        }
        DirectoryTestData.rejected(Code.BUDGET) { directory.build(unit, unreadable) }
    }
}

/** Independent original-input oracle and SQLite BLOB adapter; no native business tables. */
internal object DirectoryTestData {
    val day: LocalDate = LocalDate.of(2026, 10, 21)
    val unit = UnitKey(Partition(Scope("local-binding-a"), "count-fact", "habit-a", null), YearMonth.from(day))
    fun key(index: Int) = OrderKey(day, "fact-" + index.toString().padStart(5, '0'))
    fun record(id: String, date: LocalDate = day, payload: ByteArray = byteArrayOf(1, 2, 3)) =
        Record(OrderKey(date, id), "device-a", Bytes.copyOf(payload))
    fun records(count: Int): List<Record> = (0 until count).map { index ->
        Record(key(index), if (index % 2 == 0) "device-a" else "device-b", Bytes.copyOf(ByteArray(24) { byte -> ((index + byte) and 255).toByte() }))
    }
    private fun compare(left: OrderKey, right: OrderKey): Int {
        val date = left.recordedBusinessDate.toEpochDay().compareTo(right.recordedBusinessDate.toEpochDay())
        if (date != 0) return date
        for (index in 0 until minOf(left.stableFactId.length, right.stableFactId.length)) {
            val char = left.stableFactId[index].code.compareTo(right.stableFactId[index].code)
            if (char != 0) return char
        }
        return left.stableFactId.length.compareTo(right.stableFactId.length)
    }
    fun oracle(original: List<Record>, lower: OrderKey? = null, upper: OrderKey? = null): List<Record> =
        original.filter { (lower == null || compare(it.key, lower) >= 0) && (upper == null || compare(it.key, upper) < 0) }
            .sortedWith { a, b -> compare(a.key, b.key) }
    fun assertRecords(expected: List<Record>, actual: List<Record>) {
        assertEquals("record count", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (a, b) ->
            assertEquals("key $index", a.key, b.key); assertEquals("source $index", a.sourceId, b.sourceId)
            assertArrayEquals("payload $index", a.payload.toByteArray(), b.payload.toByteArray())
        }
    }
    fun rejected(code: Code? = null, block: () -> Any?) {
        try { block(); fail("expected rejection") }
        catch (failure: Rejected) { if (code != null) assertEquals(code, failure.code) }
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun fromHex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ascii(out: DataOutputStream, value: String) { val bytes = value.toByteArray(Charsets.US_ASCII); out.writeInt(bytes.size); out.write(bytes) }
    private fun key(out: DataOutputStream, value: OrderKey) { out.writeLong(value.recordedBusinessDate.toEpochDay()); ascii(out, value.stableFactId) }
    private fun bound(out: DataOutputStream, value: OrderKey?) { out.writeByte(if (value == null) 0 else 1); if (value != null) key(out, value) }
    private fun header(out: DataOutputStream, range: LeafRange, kind: Int, height: Int, count: Long, items: Int) {
        out.writeInt(0x47484231); out.writeInt(1); out.writeInt(1); out.writeByte(kind)
        val partition = range.unit.partition
        ascii(out, partition.scope.localBindingId); ascii(out, partition.domain); ascii(out, partition.objectId)
        out.writeByte(if (partition.roundId == null) 0 else 1); partition.roundId?.let { ascii(out, it) }
        out.writeInt(range.unit.month.year); out.writeByte(range.unit.month.monthValue)
        bound(out, range.lowerInclusive); bound(out, range.upperExclusive)
        out.writeInt(height); out.writeLong(count); out.writeInt(items)
    }
    fun leafWire(range: LeafRange, records: List<Record>, declaredCount: Long = records.size.toLong(), itemCount: Int = records.size): ByteArray =
        ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            header(out, range, 1, 0, declaredCount, itemCount)
            for (record in records) { key(out, record.key); ascii(out, record.sourceId); val bytes = record.payload.toByteArray(); out.writeInt(bytes.size); out.write(bytes) }
        } }.toByteArray()
    fun branchWire(range: LeafRange, height: Int, children: List<Ref>): ByteArray =
        ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            header(out, range, 2, height, children.sumOf { it.count }, children.size)
            for (child in children) { bound(out, child.range.lowerInclusive); bound(out, child.range.upperExclusive)
                out.writeInt(child.height); out.writeLong(child.count); out.write(fromHex(child.digest)) }
        } }.toByteArray()
    class MapLoader(blocks: Map<String, Bytes>) : AuthenticatedHistoryDirectory.Loader {
        val raw = blocks.mapValues { it.value.toByteArray() }.toMutableMap()
        val loaded = mutableListOf<Pair<String, Int>>()
        override fun load(digest: String, maxBytes: Int): ByteArray? = raw[digest]?.also {
            if (it.size > maxBytes) throw Rejected(Code.BUDGET)
            loaded += digest to it.size
        }
    }
    class SqlStore private constructor(private val db: SQLiteDatabase) : AuthenticatedHistoryDirectory.Loader, AutoCloseable {
        val loaded = mutableListOf<Pair<String, Int>>()
        var loaderBlobReads = 0
            private set
        data class Projection(val storageType: String, val byteLength: Long?, val bodyNull: Boolean)
        init { db.execSQL("CREATE TABLE IF NOT EXISTS g0_blocks(digest TEXT PRIMARY KEY COLLATE BINARY NOT NULL,body BLOB NOT NULL)") }
        fun install(blocks: Map<String, Bytes>) {
            db.beginTransaction()
            try { db.compileStatement("INSERT INTO g0_blocks(digest,body) VALUES(?,?)").use { statement ->
                for ((digest, bytes) in blocks) { statement.clearBindings(); statement.bindString(1, digest); statement.bindBlob(2, bytes.toByteArray()); statement.executeInsert() }
            }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
        override fun load(digest: String, maxBytes: Int): ByteArray? = db.rawQuery(
            BOUNDED_SELECT, arrayOf(maxBytes.toString(), digest)).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                if (cursor.getString(0) != "blob") throw Rejected(Code.MALFORMED)
                if (cursor.isNull(1) || cursor.getLong(1) > maxBytes || cursor.isNull(2)) throw Rejected(Code.BUDGET)
                loaderBlobReads++
                cursor.getBlob(2).also { bytes ->
                    assertFalse("digest must be unique", cursor.moveToNext()); loaded += digest to bytes.size
                }
            }
        }
        /** Uses exactly the loader projection; does not call getBlob or count an application load. */
        fun boundedProjection(digest: String, maxBytes: Int): Projection = db.rawQuery(
            BOUNDED_SELECT, arrayOf(maxBytes.toString(), digest)).use { cursor ->
            assertTrue("fixture node exists", cursor.moveToFirst())
            val projection = Projection(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getLong(1), cursor.isNull(2))
            assertFalse(cursor.moveToNext())
            projection
        }
        /** Deliberately inserts the wrong SQLite storage class; no product SQL entry is exposed. */
        fun installTextForRejection(digest: String, text: String) {
            db.compileStatement("INSERT INTO g0_blocks(digest,body) VALUES(?,?)").use { statement ->
                statement.bindString(1, digest); statement.bindString(2, text); statement.executeInsert()
            }
        }
        /** Negative control for the former length-only SQL, not a usable loader. */
        fun legacyTextProjection(digest: String, maxBytes: Int): Pair<Long, Int> = db.rawQuery(
            "SELECT length(body),CASE WHEN length(body)<=CAST(? AS INTEGER) THEN body ELSE NULL END FROM g0_blocks WHERE digest=?",
            arrayOf(maxBytes.toString(), digest)).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertFalse("old SQL lets TEXT through", cursor.isNull(1))
            val actual = cursor.getLong(0) to cursor.getBlob(1).size
            assertFalse(cursor.moveToNext())
            actual
        }
        fun queryPlan(): String = db.rawQuery("EXPLAIN QUERY PLAN $BOUNDED_SELECT", arrayOf("262144", "0".repeat(64))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("; ")
        }
        override fun close() { db.close() }
        companion object {
            // rawQuery parameters are TEXT: explicit INTEGER affinity is required for this guard.
            private const val BOUNDED_SELECT = "SELECT typeof(body),CASE WHEN typeof(body)='blob' THEN length(body) END," +
                "CASE WHEN typeof(body)='blob' THEN CASE WHEN length(body)<=CAST(? AS INTEGER) THEN body END END FROM g0_blocks WHERE digest=?"
            fun memory() = SqlStore(SQLiteDatabase.create(null))
            fun file(path: java.io.File) = SqlStore(SQLiteDatabase.openOrCreateDatabase(path, null))
        }
    }
}
