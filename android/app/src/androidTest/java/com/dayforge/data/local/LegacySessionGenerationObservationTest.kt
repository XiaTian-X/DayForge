package com.dayforge.data.local

import androidx.datastore.preferences.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The new observer never replaces the original generic hooks, actor or token preparation. */
@RunWith(AndroidJUnit4::class)
class LegacySessionGenerationObservationTest : LegacySessionGenerationFixture() {
    @Test fun unrelatedPreferenceUpdateDoesNotConsumeEitherTargetHookAndGenericHooksKeepTheirOrder() = legacyGenerationTest {
        val events = CopyOnWriteArrayList<String>()
        store.afterTransform.set { events += "generic-transform" }
        store.afterPersistence.set { events += "generic-persist" }
        store.observeNextGenerationChange(afterTransform = { events += "generation-transform" },
            afterPersistence = { events += "generation-persist" })
        val unrelated = stringPreferencesKey("test_unrelated_preference")
        store.edit { it[unrelated] = "synthetic metadata" }
        assertEquals(listOf("generic-transform", "generic-persist"), events.toList())
        assertNull(generation()); assertEquals(1, store.committedChanges.get())
        val before = raw().asMap()
        val result = prepare() as LegacySessionGenerationResult.CreatedGeneration
        assertEquals(listOf("generic-transform", "generic-persist", "generation-transform", "generation-persist"), events.toList())
        assertEquals(result.generation, generation())
        assertEquals(before, raw().asMap().filterKeys { it != generationKey })
        assertEquals(2, store.committedChanges.get())
        assertEquals(LegacySessionGenerationResult.CurrentGeneration(result.generation), prepare())
        assertEquals(4, events.size); assertEquals(2, store.committedChanges.get())
    }

    @Test fun malformedNativeKeyTypesAndNoncanonicalTextDoNotConsumeOrCrashTargetObserver() = legacyGenerationTest {
        val original = raw()
        val events = CopyOnWriteArrayList<String>()
        store.observeNextGenerationChange(afterTransform = { events += "transform" }, afterPersistence = { events += "persist" })
        val variants: List<MutablePreferences.() -> Unit> = listOf(
            { this[intPreferencesKey(generationKey.name)] = 7 },
            { this[booleanPreferencesKey(generationKey.name)] = true },
            { this[generationKey] = "legacy" },
            { this[generationKey] = "" })
        for (variant in variants) {
            store.updateData { original }; store.edit { variant(it) }
            val exact = raw()
            assertEquals(LegacySessionGenerationResult.NotApplicable, prepare())
            assertEquals(exact, raw()); assertTrue(events.isEmpty())
        }
        store.updateData { original }
        val created = prepare() as LegacySessionGenerationResult.CreatedGeneration
        assertEquals(created.generation, generation()); assertEquals(listOf("transform", "persist"), events.toList())
    }

    @Test fun matchingAfterPersistenceLostReturnKeepsActualCommittedGenerationAndColdReuseDoesNotRepeatIt() = legacyGenerationTest {
        val original = raw().asMap()
        val events = CopyOnWriteArrayList<String>()
        val lost = IOException("target-generation-actual-persisted-return-lost")
        store.observeNextGenerationChange(afterTransform = { events += "transform" }, afterPersistence = {
            events += "persist"; throw lost
        })
        val observed = try { prepare(); null } catch (error: IOException) { error }
        assertSame(lost, observed); assertEquals(listOf("transform", "persist"), events.toList())
        val saved = requireNotNull(generation())
        assertEquals(original, raw().asMap().filterKeys { it != generationKey })
        assertEquals(1, store.committedChanges.get())
        cold() // The original actual DataStore actor joins before the next reader exists.
        assertEquals(saved, generation())
        assertEquals(LegacySessionGenerationResult.CurrentGeneration(saved), prepare())
        assertEquals(0, store.committedChanges.get()); assertEquals(listOf("transform", "persist"), events.toList())
    }
}
