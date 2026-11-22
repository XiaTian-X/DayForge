package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Each shape has its own complete150s case. Both sides perform real native commits. */
@RunWith(AndroidJUnit4::class)
class CountCommandMetricDeliveryCompositeTest : CountCommandMetricDeliveryCompositeFixture() {
    @Test(timeout = 150_000) fun adjacentPairMatchesOriginalRowsRootsMacAndNodeRowids() = runBlocking<Unit> {
        group(); val steps = differential()
        assertEquals(2, steps.size)
        val before = steps.first().previous!!.status
        val after = commands().resolve(parent).historical.status
        assertEquals(Math.addExact(before.revision, 2L), after.revision)
        assertEquals(Math.addExact(steps.first().before.revision, 2L), steps.last().after.revision)
    }

    @Test(timeout = 150_000) fun sharedNeighbourRetainsItsTwoOriginalRevisionIncrements() = runBlocking<Unit> {
        group(between = 1); val steps = differential()
        assertEquals(2, steps.size)
        val neighbour = intervening.single()
        val before = steps.first().previous!!.status
        assertEquals(neighbour.commandId, before.commandId)
        assertEquals(Math.addExact(before.revision, 2L), commands().resolve(neighbour).historical.status.revision)
    }

    @Test(timeout = 150_000) fun separatedNeighboursAndBothOuterLinksMatchCompleteOriginalImage() = runBlocking<Unit> {
        group(between = 2, outerNeighbours = true)
        assertEquals(2, differential().size)
    }

    @Test(timeout = 150_000) fun previouslyDeliveredChildKeepsTheOriginalSingleParentStep() = runBlocking<Unit> {
        group(); commands().markDelivered(child)
        val steps = differential(); assertEquals(1, steps.size)
        assertEquals(parent.commandId, steps.single().current.intent.commandId)
    }

    @Test(timeout = 150_000) fun previouslyDeliveredParentKeepsTheOriginalSingleChildStep() = runBlocking<Unit> {
        group(); commands().markDelivered(parent)
        val steps = differential(); assertEquals(1, steps.size)
        assertEquals(child.commandId, steps.single().current.intent.commandId)
    }

    @Test(timeout = 150_000) fun alreadyDeliveredPairIsNativeReadOnlyAfterRestoreAndRetry() = runBlocking<Unit> {
        group(); commands().markDelivered(child); commands().markDelivered(parent)
        assertTrue(differential().isEmpty())
    }
}
