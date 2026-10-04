package com.dayforge.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconMemoryTest {
    private class Cache : AccountIconMemory.Cache {
        val events = mutableListOf<Boolean>()
        override fun authenticationTransition(blocked: Boolean) { events.add(blocked) }
    }

    @Test fun nestedTransitionsStayBlockedUntilEveryWriterHasFinished() {
        val memory = AccountIconMemory(); val cache = Cache(); memory.register(cache)
        memory.beginTransition(); memory.beginTransition(); memory.endTransition()
        assertEquals(listOf(true, true, true), cache.events)
        memory.endTransition(); assertEquals(listOf(true, true, true, false), cache.events)
        assertThrows(IllegalStateException::class.java) { memory.endTransition() }
    }

    @Test fun cacheRegisteredDuringPersistenceCannotPublishUntilTheTransitionEnds() {
        val memory = AccountIconMemory(); memory.beginTransition()
        val cache = Cache(); memory.register(cache)
        assertEquals(listOf(true), cache.events)
        memory.endTransition(); assertEquals(listOf(true, false), cache.events)
    }

    @Test fun registrationOutsideTransitionDoesNotEraseUnrelatedCaches() {
        val memory = AccountIconMemory(); val first = Cache(); val second = Cache()
        memory.register(first); memory.register(second)
        assertTrue(first.events.isEmpty()); assertTrue(second.events.isEmpty())
        memory.beginTransition(); memory.endTransition()
        assertEquals(listOf(true, false), first.events); assertEquals(first.events, second.events)
    }
}
