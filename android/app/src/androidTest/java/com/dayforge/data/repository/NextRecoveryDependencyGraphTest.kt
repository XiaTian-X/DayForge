package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure planner on the authorized device; does not pretend to establish real SQL/HTTP evidence. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryDependencyGraphTest {
    private fun key(i: Int) = NextRecoveryKey(NEXT_OPERATION, "00000000-0000-4000-8000-${i.toString().padStart(12, '0')}")
    private fun node(i: Int, state: NextRecoveryRequestState = NextRecoveryRequestState.NEVER_SENT,
        category: NextRecoveryCategory = NextRecoveryCategory.STRUCTURE,
        protections: Set<NextRecoveryProtection> = emptySet()) = NextRecoveryNode(key(i), category, state,
        if (category == NextRecoveryCategory.STRUCTURE) "metric" else "activity_event", key(9999).requestId,
        "a".repeat(64), protections)
    private fun edge(a: Int, b: Int) = NextRecoveryDependency(key(a), key(b),
        NextRecoveryDependencyReason.STRUCTURAL_PREDECESSOR, "b".repeat(64))
    private fun root() = node(1, NextRecoveryRequestState.PERMANENT_REJECTION)

    @Test fun completeBranchingClosureExcludesUnrelatedAndAcceptedAncestors() = runBlocking {
        val graph = NextRecoveryDependencyGraph(listOf(root(), node(2), node(3), node(4), node(5),
            node(6, NextRecoveryRequestState.ACCEPTED)), listOf(edge(6, 1), edge(1, 2), edge(1, 3), edge(2, 4), edge(3, 4)))
        val result = graph.preview(key(1))
        assertTrue(result.canConfirm); assertEquals(listOf(key(1), key(2), key(3), key(4)), result.affected.map { it.key })
    }
    @Test fun everyProtectedDispositionBlocksTheWholeSetWithoutHidingMembers() = runBlocking {
        for (state in listOf(NextRecoveryRequestState.UNKNOWN_RESULT, NextRecoveryRequestState.ACCEPTED,
            NextRecoveryRequestState.ABANDONED, NextRecoveryRequestState.SUPERSEDED)) {
            val result = NextRecoveryDependencyGraph(listOf(root(), node(2, state), node(3)), listOf(edge(1, 2), edge(2, 3)))
                .preview(key(1))
            assertFalse(result.canConfirm); assertEquals(3, result.affected.size)
            assertTrue(result.blockers.any { it.key == key(2) })
        }
    }
    @Test fun allRealFactsAndLocalLocksBlockEvenIfTheyNeverEnteredSending() = runBlocking {
        for (protection in NextRecoveryProtection.entries) {
            val result = NextRecoveryDependencyGraph(listOf(root(), node(2, protections = setOf(protection))), listOf(edge(1, 2)))
                .preview(key(1))
            assertEquals(listOf(NextRecoveryBlocker(key(2), NextRecoveryBlockReason.PROTECTED_LOCAL_DATA)), result.blockers)
        }
        for (category in listOf(NextRecoveryCategory.FACT, NextRecoveryCategory.ONE_TIME,
            NextRecoveryCategory.RESTART, NextRecoveryCategory.CONFIG_IMPORT)) {
            val result = NextRecoveryDependencyGraph(listOf(root(), node(2, category = category)), listOf(edge(1, 2))).preview(key(1))
            assertFalse(result.canConfirm)
        }
    }
    @Test fun timerCannotBecomeOrdinaryStructureThroughKeyOrCategoryCoercion() = runBlocking {
        val timerKey = NextRecoveryKey(NEXT_TIMER, key(2).requestId)
        val timer = NextRecoveryNode(timerKey, NextRecoveryCategory.TIMER, NextRecoveryRequestState.NEVER_SENT,
            "timer_session", key(3).requestId, "a".repeat(64))
        val result = NextRecoveryDependencyGraph(listOf(root(), timer), listOf(NextRecoveryDependency(key(1), timerKey,
            NextRecoveryDependencyReason.TIMER_POLICY, "b".repeat(64)))).preview(key(1))
        assertFalse(result.canConfirm)
        assertNotNull(runCatching { timer.copy(category = NextRecoveryCategory.STRUCTURE) }.exceptionOrNull())
        assertNotNull(runCatching { node(2).copy(key = timerKey) }.exceptionOrNull())
    }
    @Test fun rootMustHaveActualPermanentRejection() = runBlocking {
        for (state in NextRecoveryRequestState.entries.filter { it != NextRecoveryRequestState.PERMANENT_REJECTION }) {
            val result = NextRecoveryDependencyGraph(listOf(node(1, state)), emptyList()).preview(key(1))
            assertFalse(result.canConfirm)
            assertTrue(result.blockers.contains(NextRecoveryBlocker(key(1), NextRecoveryBlockReason.ROOT_NOT_PERMANENT_REJECTION)))
        }
    }
    @Test fun compactPhaseGateIncludesAllLaterAffectedStepsButNotOtherPredecessors() = runBlocking {
        val gate = NextRecoveryDependencyGroup(listOf(key(1), key(2)), listOf(key(3), key(4)),
            NextRecoveryDependencyReason.IMPORT_PHASE, "c".repeat(64))
        val result = NextRecoveryDependencyGraph(listOf(root(), node(2), node(3), node(4), node(5)),
            listOf(edge(4, 5)), listOf(gate)).preview(key(1))
        assertEquals(listOf(key(1), key(3), key(4), key(5)), result.affected.map { it.key })
    }
    @Test fun structuralAndMixedGateCyclesAreRejectedIncludingOutsideTheSelectedClosure() = runBlocking {
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root(), node(2), node(3)),
            listOf(edge(2, 3), edge(3, 2))).preview(key(1)) }.exceptionOrNull())
        val gate = NextRecoveryDependencyGroup(listOf(key(1)), listOf(key(2)),
            NextRecoveryDependencyReason.IMPORT_PHASE, "c".repeat(64))
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root(), node(2)), listOf(edge(2, 1)),
            listOf(gate)).preview(key(1)) }.exceptionOrNull())
    }
    @Test fun logicalOriginalIsResolvedToAuditedReplacementWithoutRetiringTheOriginalAgain() = runBlocking {
        val old = node(2, NextRecoveryRequestState.SUPERSEDED)
        val replacement = node(1, NextRecoveryRequestState.PERMANENT_REJECTION)
        val graph = NextRecoveryDependencyGraph(listOf(old, replacement, node(3)), listOf(edge(2, 3)),
            aliases = mapOf(key(2) to key(1)))
        val result = graph.preview(key(1))
        assertTrue(result.canConfirm); assertEquals(listOf(key(1), key(3)), result.affected.map { it.key })
        assertNotNull(runCatching { graph.preview(key(2)) }.exceptionOrNull())
    }
    @Test fun duplicateMissingAndAmbiguousAliasesNeverBecomeAnEmptyHealthyGraph() {
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root(), root()), emptyList()) }.exceptionOrNull())
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root()), listOf(edge(1, 2))) }.exceptionOrNull())
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root(), node(2)), listOf(edge(1, 2), edge(1, 2))) }.exceptionOrNull())
        assertNotNull(runCatching { NextRecoveryDependencyGraph(listOf(root(), node(2)), emptyList(),
            aliases = mapOf(key(2) to key(1))) }.exceptionOrNull())
    }
    @Test fun orderIsCanonicalAndEvidenceChangesInvalidateEqualCountPreview() = runBlocking {
        val nodes = listOf(root(), node(2), node(3)); val edges = listOf(edge(1, 2), edge(2, 3))
        val before = NextRecoveryDependencyGraph(nodes, edges).preview(key(1))
        assertEquals(before.graphHash, NextRecoveryDependencyGraph(nodes.reversed(), edges.reversed()).preview(key(1)).graphHash)
        assertNotEquals(before.graphHash, NextRecoveryDependencyGraph(nodes.map {
            if (it.key == key(2)) it.copy(evidenceHash = "d".repeat(64)) else it
        }, edges).preview(key(1)).graphHash)
        assertNotEquals(before.graphHash, NextRecoveryDependencyGraph(nodes,
            edges.map { it.copy(evidenceHash = "e".repeat(64)) }).preview(key(1)).graphHash)
    }
    @Test fun callerCannotMutateTheFrozenInputOrPublishedAffectedMembers() = runBlocking {
        val locks = mutableSetOf(NextRecoveryProtection.COUNT_DAY)
        val source = mutableListOf(root(), node(2, protections = locks))
        val edges = mutableListOf(edge(1, 2))
        val graph = NextRecoveryDependencyGraph(source, edges)
        locks.clear(); source.clear(); edges.clear()
        val result = graph.preview(key(1))
        assertFalse(result.canConfirm); assertEquals(2, result.affected.size)
        assertNotNull(runCatching { (result.affected as MutableList).clear() }.exceptionOrNull())
        assertNotNull(runCatching { (result.affected.last().protections as MutableSet).clear() }.exceptionOrNull())
    }
    @Test fun maximumExistingQueueChainIsIterativeAndComplete() = runBlocking {
        val nodes = (1..10_000).map { if (it == 1) root() else node(it) }
        val result = NextRecoveryDependencyGraph(nodes, (1 until 10_000).map { edge(it, it + 1) }).preview(key(1))
        assertTrue(result.canConfirm); assertEquals(10_000, result.affected.size)
    }
    @Test fun largePhaseUsesOneGateRatherThanMillionsOfPairwiseEdges() = runBlocking {
        val nodes = (1..10_000).map { i -> if (i == 1) root().copy(category = NextRecoveryCategory.CONFIG_IMPORT) else node(i) }
        val gate = NextRecoveryDependencyGroup((1..5000).map(::key), (5001..10_000).map(::key),
            NextRecoveryDependencyReason.IMPORT_PHASE, "c".repeat(64))
        val result = NextRecoveryDependencyGraph(nodes, emptyList(), listOf(gate)).preview(key(1))
        assertFalse(result.canConfirm); assertEquals(5001, result.affected.size)
        assertEquals(listOf(NextRecoveryBlocker(key(1), NextRecoveryBlockReason.UNSUPPORTED_CATEGORY)), result.blockers)
    }
    @Test fun mutableGateListsAreCopiedAndOrdinarySuccessorsCanAlsoBePermanentlyRejected() = runBlocking {
        val before = mutableListOf(key(1)); val after = mutableListOf(key(2))
        val graph = NextRecoveryDependencyGraph(listOf(root(), node(2, NextRecoveryRequestState.PERMANENT_REJECTION)),
            emptyList(), listOf(NextRecoveryDependencyGroup(before, after,
                NextRecoveryDependencyReason.GOAL_CHILD_FRONTIER, "c".repeat(64))))
        before.clear(); after.clear()
        val result = graph.preview(key(1))
        assertTrue(result.canConfirm); assertEquals(2, result.affected.size)
    }
}
