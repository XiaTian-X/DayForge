package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockUpdater
import com.dayforge.data.history.HistoryBlockCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical refusal differentials plus explicitly pure high-counter boundary checks.
 * High revisions below are never signed, installed or called Authenticated. */
@RunWith(AndroidJUnit4::class)
class CountCommandMetricDeliveryCompositeRejectionTest : CountCommandMetricDeliveryCompositeFixture() {
    /** Actual authenticated parent/child inputs, then independent old-constructor refusal
     * comparisons. No synthetic member/hash is ever installed or treated as authority. */
    @Test(timeout = 150_000) fun statusDerivationKeepsOriginalRefusalsAndCannotBorrowMutablePayloadAliases() = runBlocking<Unit> {
        group()
        val originals = listOf(commands().resolve(parent).historical, commands().resolve(child).historical)
        val before = image()
        val wrongResult = requireNotNull(originals[1].outcome)
        val originalPairFailure = runCatching { CountCommandRowPlan.Aggregate(
            originals[0].intent, originals[0].status, wrongResult) }.exceptionOrNull()
        val projectedPairFailure = runCatching { CountCommandRowPlan.Aggregate.forStatusChanges(
            originals[0].intent, originals[0].status, wrongResult) }.exceptionOrNull()
        assertNotNull(originalPairFailure); assertNotNull(projectedPairFailure)
        assertEquals(requireNotNull(originalPairFailure).javaClass, requireNotNull(projectedPairFailure).javaClass)
        assertEquals(originalPairFailure.message, projectedPairFailure.message)
        fun assertSameRows(expected: CountCommandRowPlan.Aggregate, actual: CountCommandRowPlan.Aggregate) {
            assertEquals(expected.status, actual.status)
            assertEquals(CountCommandRowPlan.intent(expected.intent), actual.intentImage())
            assertEquals(CountCommandRowPlan.status(expected.status), actual.statusImage())
            assertEquals(expected.outcome?.let(CountCommandRowPlan::outcome), actual.outcomeImage())
            assertEquals(expected.member(), actual.member())
        }
        fun refusedBoth(original: CountCommandRowPlan.Aggregate, status: CountCommandRecords.Status) {
            val old = runCatching { CountCommandRowPlan.Aggregate(original.intent, status, original.outcome) }.exceptionOrNull()
            val derived = runCatching { original.withStatus(status) }.exceptionOrNull()
            assertNotNull("The original full constructor must independently refuse", old)
            assertNotNull(derived)
            assertEquals(requireNotNull(old).javaClass, requireNotNull(derived).javaClass)
            assertEquals(old.message, derived.message)
        }
        for (original in originals) {
            val originalMember = original.member()
            val intentBytes = original.intent.text().toByteArray(Charsets.UTF_8)
            val outcomeBytes = requireNotNull(original.outcome).text().toByteArray(Charsets.UTF_8)
            val copiedIntent = CountCommandRecords.Intent.decode(intentBytes, intentBytes.size) { }
            val copiedOutcome = CountCommandOutcome.decode(outcomeBytes, outcomeBytes.size) { }
            val owned = CountCommandRowPlan.Aggregate.forStatusChanges(copiedIntent, original.status, copiedOutcome)
            assertEquals(originalMember, owned.member())
            intentBytes.fill(0); outcomeBytes.fill(0)
            // Input bytes, returned JSON and collection views must not mutate the private
            // encoded source after its row/member projection has already been computed.
            val exposed = copiedIntent.body()
            @Suppress("UNCHECKED_CAST")
            val bodyChange = runCatching { (exposed as MutableMap<String, kotlinx.serialization.json.JsonElement>)["input"] =
                kotlinx.serialization.json.JsonNull }.exceptionOrNull()
            assertTrue(bodyChange == null || bodyChange is UnsupportedOperationException || bodyChange is ClassCastException)
            assertEquals(original.intent.body(), copiedIntent.body())
            val effectsChange = runCatching { (copiedOutcome.effects as MutableList<CountCommandOutcome.Effect>).clear() }.exceptionOrNull()
            val originsChange = runCatching { (copiedOutcome.origins as MutableList<CountCommandOutcome.Origin>).clear() }.exceptionOrNull()
            val evidenceChange = runCatching { (copiedOutcome.evidence.records as MutableList<CountCommandEvidence.Record>).clear() }.exceptionOrNull()
            assertTrue(effectsChange is UnsupportedOperationException)
            assertTrue(originsChange is UnsupportedOperationException)
            assertTrue(evidenceChange is UnsupportedOperationException)
            assertEquals(requireNotNull(original.outcome).text(), copiedOutcome.text())
            assertEquals(originalMember, owned.member())
            val imageChange = runCatching { (owned.intentImage().cells as MutableList<CountCommandRowPlan.Cell>).clear() }.exceptionOrNull()
            val layoutChange = runCatching { (owned.intentImage().table.columns as MutableList<String>).clear() }.exceptionOrNull()
            assertTrue(imageChange is UnsupportedOperationException)
            assertTrue(layoutChange is UnsupportedOperationException)

            val intermediate = owned.status.copy(revision = Math.addExact(owned.status.revision, 1L), next = null)
            val first = owned.withStatus(intermediate)
            assertSameRows(CountCommandRowPlan.Aggregate(copiedIntent, intermediate, copiedOutcome), first)
            val terminal = CountCommandTransitionRules.delivered(first.status)
            val second = first.withStatus(terminal)
            assertSameRows(CountCommandRowPlan.Aggregate(copiedIntent, terminal, copiedOutcome), second)
            assertEquals(originalMember, owned.member()) // Earlier version must not be rewritten.
            assertEquals(Math.addExact(original.status.revision, 2L), second.status.revision)

            refusedBoth(owned, owned.status.copy(commandId = java.util.UUID.randomUUID().toString()))
            val changedHash = if (owned.status.inputHash == "f".repeat(64)) "e".repeat(64) else "f".repeat(64)
            refusedBoth(owned, owned.status.copy(inputHash = changedHash))
            refusedBoth(owned, owned.status.copy(execution = CountCommandRecords.Execution.NO_EFFECT,
                delivery = CountCommandRecords.Delivery.RESULT_PENDING, child = null))
            refusedBoth(owned, owned.status.copy(parent = if (owned.status.parent == null)
                originals.first { it.intent.commandId != owned.intent.commandId }.intent.commandId else null))
        }
        assertCompleteImage(before, image())
    }

    @Test(timeout = 150_000) fun originalFirstPatchOnlyNodeCollisionIsStillRejectedBeforeSuccessfulEnd() = runBlocking<Unit> {
        group(); val before = image(); val disk = closedImage()
        val steps = oldTwoStepOracle(); assertEquals(2, steps.size)
        val intermediate = steps.first().patch.root.digest
        assertNotEquals(intermediate, steps.last().patch.root.digest)
        assertTrue(steps.first().patch.newBlocks.containsKey(intermediate))
        restore(disk); assertCompleteImage(before, image())
        db.withTransaction { runtime.maintenance(db) {
            sql.query("SELECT 1 FROM count_command_nodes WHERE digest=?", arrayOf(intermediate)).use { assertFalse(it.moveToFirst()) }
            sql.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(intermediate, byteArrayOf(0)))
        } }
        // The poison is an unreferenced P1 node on H0. Both operations must preserve that
        // complete original image when its collision is rejected, never silently omit P1.
        rejectionBoth()
    }

    @Test(timeout = 150_000) fun extraSameValueNativeStatusWriteStillFailsExactJournalAndRollsBackEveryCell() = runBlocking<Unit> {
        group(); rejectionBoth(extraWrite = true, expected = "COMMAND_JOURNAL_EXTRA_OR_GAP")
    }

    @Test(timeout = 150_000) fun sameValueOutcomeWriteDuringBaselineReadCannotBecomeAnAcceptedCachedProof() = runBlocking<Unit> {
        group(); rejectionBoth(preparationWrite = true, expected = "COMMAND_JOURNAL_EXTRA_OR_GAP")
    }

    @Test(timeout = 150_000) fun realFirstPatchOverlayStillEnforcesHashSizeCodecAndOriginalNodeBudget() = runBlocking<Unit> {
        group(); val first = oldTwoStepOracle().first(); val before = image()
        db.withTransaction { runtime.maintenance(db) {
            val token = runtime.capturePrimaryReadToken(db); val caller = currentCoroutineContext()
            val lifetime = { runtime.requirePrimaryReadToken(db, token); caller.ensureActive() }
            val binding = requireNotNull(first.before.namespace).catalogBinding
            suspend fun member(ref: HistoryBlockCodec.Ref, overlay: Map<String, HistoryBlockCodec.Bytes>) =
                CountCommandDirectory(sql, binding, ref, lifetime, lifetime, nodeOverlay = overlay).read { it.member(parent.commandId) }
            val root = first.patch.root
            val original = first.batch.after.single { it.intent.commandId == parent.commandId }.member()
            assertEquals(original, member(root, first.patch.newBlocks))
            val bytes = first.patch.newBlocks.getValue(root.digest).toByteArray()
            val changed = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertEquals("COMMAND_NODE_CHANGED", rejected {
                member(root, mapOf(root.digest to HistoryBlockCodec.Bytes.copyOf(changed)))
            }.message)
            assertEquals("COMMAND_NODE_TYPE_OR_SIZE", rejected {
                member(root, mapOf(root.digest to HistoryBlockCodec.Bytes.copyOf(byteArrayOf())))
            }.message)
            // Deliberately untrusted codec input, never signed or presented as Authenticated.
            val malformed = byteArrayOf(0)
            val untrusted = root.copy(digest = HistoryBlockCodec.hash(malformed))
            val direct = rejected { HistoryBlockCodec.decode(untrusted, malformed) }
            val viaOverlay = rejected { member(untrusted, mapOf(untrusted.digest to HistoryBlockCodec.Bytes.copyOf(malformed))) }
            assertTrue(direct is HistoryBlockCodec.Rejected)
            assertEquals(direct.javaClass, viaOverlay.javaClass); assertEquals(direct.message, viaOverlay.message)
            assertTrue(bytes.size + 2 <= HistoryBlockCodec.MAX_NODE_BYTES)
            val budget = HistoryBlockUpdater.Budget()
            val tooMany = linkedMapOf(root.digest to first.patch.newBlocks.getValue(root.digest))
            repeat(budget.maxNewNodes) { index ->
                val extra = bytes + byteArrayOf((index ushr 8).toByte(), index.toByte())
                tooMany[HistoryBlockCodec.hash(extra)] = HistoryBlockCodec.Bytes.copyOf(extra)
            }
            assertEquals(budget.maxNewNodes + 1, tooMany.size)
            assertTrue(rejected { member(root, tooMany) } is IllegalArgumentException)
        } }
        assertCompleteImage(before, image())
    }

    @Test(timeout = 150_000) fun mismatchedNeighbourNativeRevisionCannotBeReplacedByAnOverlayValue() = runBlocking<Unit> {
        group(between = 1)
        val neighbour = intervening.single()
        db.withTransaction { runtime.maintenance(db) {
            sql.execSQL("UPDATE count_command_status SET revision=revision+1 WHERE commandId=?", arrayOf(neighbour.commandId))
        } }
        rejectionBoth()
    }

    private fun oldRemoval(frontier: CountCommandPendingRules.Frontier, current: CountCommandRowPlan.Aggregate,
        previous: CountCommandRowPlan.Aggregate?, next: CountCommandRowPlan.Aggregate?): Pair<CountCommandRowPlan.Batch, CountCommandPendingRules.Frontier>? {
        if (current.status.delivery == CountCommandRecords.Delivery.DELIVERED) return null
        val removal = CountCommandPendingRules.remove(frontier, current.status,
            CountCommandTransitionRules.delivered(current.status), previous?.status, next?.status)
        val old = listOfNotNull(current, previous, next).associateBy { it.intent.commandId }
        val fresh = removal.changes.map { change -> old.getValue(change.after.commandId).let {
            CountCommandRowPlan.Aggregate(it.intent, change.after, it.outcome)
        } }
        return CountCommandRowPlan.Batch(old.values.toList(), fresh) to removal.after
    }
    private fun highRevision(row: CountCommandRowPlan.Aggregate, revision: Long) =
        CountCommandRowPlan.Aggregate(row.intent, row.status.copy(revision = revision), row.outcome)
    private fun arithmetic(block: () -> Unit) {
        try { block() } catch (_: ArithmeticException) { return }
        fail("Expected the original exact-add overflow")
    }

    @Test(timeout = 150_000) fun pureStatusBoundariesKeepFirstStepAndSharedNeighbourSecondStepOverflow() = runBlocking<Unit> {
        group(between = 1); val steps = oldTwoStepOracle(); val before = image()
        val first = steps[0]; val second = steps[1]
        val tooHigh = highRevision(first.current, Long.MAX_VALUE)
        arithmetic { oldRemoval(first.before.pending, tooHigh, first.previous, first.next) }
        arithmetic { CountCommandMetricDeliveryRules.remove(first.before.pending, tooHigh, first.previous, first.next) }
        val shared = highRevision(requireNotNull(first.previous), Long.MAX_VALUE - 1)
        val oldFirst = requireNotNull(oldRemoval(first.before.pending, first.current, shared, first.next))
        val newFirst = requireNotNull(CountCommandMetricDeliveryRules.remove(first.before.pending, first.current, shared, first.next))
        assertEquals(oldFirst.second, newFirst.pending)
        assertEquals(oldFirst.first.after.map { it.member() }, newFirst.batch.after.map { it.member() })
        val oldOverlay = oldFirst.first.after.associateBy { it.intent.commandId }
        val newOverlay = newFirst.batch.after.associateBy { it.intent.commandId }
        fun from(rows: Map<String, CountCommandRowPlan.Aggregate>, row: CountCommandRowPlan.Aggregate?) =
            row?.let { rows[it.intent.commandId] ?: it }
        assertEquals(Long.MAX_VALUE, oldOverlay.getValue(shared.intent.commandId).status.revision)
        arithmetic { oldRemoval(oldFirst.second, requireNotNull(from(oldOverlay, second.current)),
            from(oldOverlay, second.previous), from(oldOverlay, second.next)) }
        arithmetic { CountCommandMetricDeliveryRules.remove(newFirst.pending, requireNotNull(from(newOverlay, second.current)),
            from(newOverlay, second.previous), from(newOverlay, second.next)) }
        assertCompleteImage(before, image())
    }

    private fun oldNext(before: CountCommandRootCodec.Header, pending: CountCommandPendingRules.Frontier,
        patch: HistoryBlockUpdater.Patch): CountCommandRootCodec.Header = before.copy(catalog = patch.root, pending = pending,
        revision = Math.addExact(before.revision, 1L)).also { CountCommandRootCodec.requireNext(before, it) }

    @Test(timeout = 150_000) fun pureRootBoundariesKeepTheTwoExactIncrementsAndSecondStepOverflow() = runBlocking<Unit> {
        group(); val steps = oldTwoStepOracle(); val beforeImage = image()
        val first = steps[0]; val second = steps[1]
        val firstRemoval = requireNotNull(CountCommandMetricDeliveryRules.remove(first.before.pending,
            first.current, first.previous, first.next))
        val secondRemoval = requireNotNull(CountCommandMetricDeliveryRules.remove(second.before.pending,
            second.current, second.previous, second.next))
        val nearLimit = first.before.copy(revision = Long.MAX_VALUE - 1)
        val oldFirst = oldNext(nearLimit, first.pending, first.patch)
        val newFirst = CountCommandMetricDeliveryRules.nextHeader(nearLimit, firstRemoval, first.patch)
        assertEquals(oldFirst, newFirst); assertEquals(Long.MAX_VALUE, newFirst.revision)
        arithmetic { oldNext(oldFirst, second.pending, second.patch) }
        arithmetic { CountCommandMetricDeliveryRules.nextHeader(newFirst, secondRemoval, second.patch) }
        val atLimit = first.before.copy(revision = Long.MAX_VALUE)
        arithmetic { oldNext(atLimit, first.pending, first.patch) }
        arithmetic { CountCommandMetricDeliveryRules.nextHeader(atLimit, firstRemoval, first.patch) }
        assertCompleteImage(beforeImage, image())
    }
}
