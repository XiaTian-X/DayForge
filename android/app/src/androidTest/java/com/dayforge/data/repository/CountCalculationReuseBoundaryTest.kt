package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountCalculationBlocks
import com.dayforge.domain.model.CountDayPolicy
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Arithmetic-cache lifecycle only; business proof equivalence is in the companion safety suite. */
@RunWith(AndroidJUnit4::class)
class CountCalculationReuseBoundaryTest : NextCoreRequestFixture() {
    private val day = LocalDate.parse("2026-10-06")
    private val policy = CountDayPolicy(10, false)

    private suspend fun partition(number: Int = 1) = CountCalculationReuse.Partition(local(), null,
        number.toLong(), id(600 + number), null)
    private fun fact(number: Int) = CountCalculationBlocks.Fact(id(1000 + number), number.toLong(), day, 1)
    private fun prepare(cache: CountCalculationReuse, key: CountCalculationReuse.Partition,
        facts: List<CountCalculationBlocks.Fact>, ticket: CountCalculationReuse.Ticket = cache.begin()) =
        cache.prepare(ticket, key, facts, mapOf(day to policy), emptySet(), day)

    @Test fun olderPreparedResultCannotReplaceAStillRetainedNewerEntry() = runBlocking<Unit> {
        val cache = CountCalculationReuse(tokens)
        val key = partition()
        val first = listOf(fact(1))
        cache.promote(prepare(cache, key, first))
        val old = prepare(cache, key, first, cache.begin())
        val changed = first + fact(2)
        val recent = prepare(cache, key, changed, cache.begin())
        cache.promote(recent)
        val promotions = cache.diagnostics().promotions
        cache.promote(old)
        assertEquals(promotions, cache.diagnostics().promotions)
        assertEquals(1L, cache.diagnostics().discarded)
        val rechecked = prepare(cache, key, changed)
        assertEquals(1, rechecked.result.stats.reusedBlocks)
        assertEquals(0, rechecked.result.stats.changedBlocks)
        assertEquals(2L, rechecked.result.state.summary(day, policy, null, null).todayQuantity)
    }

    @Test fun realPersistedLoginTransitionClearsEntriesAndRejectsAlreadyPreparedEpoch() = runBlocking<Unit> {
        val cache = CountCalculationReuse(tokens)
        val key = partition()
        val facts = listOf(fact(1))
        cache.promote(prepare(cache, key, facts))
        val abandoned = prepare(cache, key, facts)
        val before = cache.diagnostics()
        tokens.saveLoginSession("replacement", "refresh", "member", id(1), false)
        assertEquals(0, cache.diagnostics().committedEntries)
        assertTrue(cache.diagnostics().invalidations > before.invalidations)
        cache.promote(abandoned)
        assertEquals(0, cache.diagnostics().committedEntries)
        assertEquals(before.promotions, cache.diagnostics().promotions)
        assertEquals(before.discarded + 1, cache.diagnostics().discarded)
        val newKey = partition()
        assertNotEquals(key.session, newKey.session)
        val fresh = prepare(cache, newKey, facts)
        assertEquals(0, fresh.result.stats.reusedBlocks)
        cache.promote(fresh)
        assertEquals(1, cache.diagnostics().committedEntries)
        assertEquals(before.promotions + 1, cache.diagnostics().promotions)
    }

    @Test fun boundedLruRetainsTheRecentlyReadPartitionAndRecalculatesAnEvictedOne() = runBlocking<Unit> {
        val cache = CountCalculationReuse(tokens)
        val facts = listOf(fact(1))
        val keys = (1..33).map { partition(it) }
        keys.take(32).forEach { cache.promote(prepare(cache, it, facts)) }
        assertEquals(32, cache.diagnostics().committedEntries)
        assertEquals(1, prepare(cache, keys[0], facts).result.stats.reusedBlocks)
        cache.promote(prepare(cache, keys[32], facts))
        assertEquals(32, cache.diagnostics().committedEntries)
        assertEquals(1, prepare(cache, keys[0], facts).result.stats.reusedBlocks)
        assertEquals(0, prepare(cache, keys[1], facts).result.stats.reusedBlocks)
        assertEquals(1, prepare(cache, keys[32], facts).result.stats.reusedBlocks)
    }

    @Test fun oversizeResultStillComputesAndDoesNotEvictThePriorCommittedEntry() = runBlocking<Unit> {
        val cache = CountCalculationReuse(tokens)
        val key = partition()
        val small = listOf(fact(1))
        cache.promote(prepare(cache, key, small))
        val before = cache.diagnostics()
        // Exceeds cache retention units, not a product or history acceptance limit.
        val large = prepare(cache, key, (1..16_385).map(::fact))
        assertEquals(16_385L, large.result.state.summary(day, policy, null, null).todayQuantity)
        cache.promote(large)
        assertEquals(before.promotions, cache.diagnostics().promotions)
        assertEquals(before.committedEntries, cache.diagnostics().committedEntries)
        assertEquals(before.discarded + 1, cache.diagnostics().discarded)
        val retained = prepare(cache, key, small)
        assertEquals(1, retained.result.stats.reusedBlocks)
        assertEquals(1L, retained.result.state.summary(day, policy, null, null).todayQuantity)
    }
}
