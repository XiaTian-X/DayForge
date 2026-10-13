package com.dayforge.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.StableHabitBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitPreferenceRepositoryTest : NextCoreRequestFixture() {
    private fun repository() = HabitPreferenceRepository(db, tokens, sessions, preferences,
        ReplicaPresentationRepository(db, tokens, sessions))

    private suspend fun rejects(action: suspend () -> Unit) {
        assertNotNull("A stale preference claim must be refused", runCatching { action() }.exceptionOrNull())
    }

    @Test fun idReuseCannotAdoptPreferencesAndSameUuidRetainsPreferencesButNotOldEpochPending() = runBlocking {
        register()
        preferences.setLanguageCode("zh")
        val repository = repository()
        val original = repository.read(habit)
        repository.setNotification(original.claim, false)
        repository.setNeverAskAgain(original.claim, true)
        preferences.addPendingMetricHabit(local(), habit.uuid)
        val replacement = habit.copy(uuid = id(80), name = "Different identity")
        val relocated = db.withTransaction {
            db.habitDao().update(replacement)
            habit.copy(id = db.habitDao().insert(habit.copy(id = 0)))
        }
        val fresh = repository.read(replacement)
        assertTrue(fresh.notificationEnabled); assertFalse(fresh.neverAsk)
        val retained = repository.read(relocated)
        assertFalse(retained.notificationEnabled); assertTrue(retained.neverAsk)
        assertEquals(setOf(relocated.id), repository.pendingHabitIds.first())
        rejects { repository.setNotification(original.claim, false) }
        tokens.saveServerIdentity(id(2), id(81))
        val restored = repository.read(relocated)
        assertFalse(restored.notificationEnabled); assertTrue(restored.neverAsk)
        assertTrue(repository.pendingHabitIds.first().isEmpty())
        rejects { repository.setNeverAskAgain(retained.claim, false) }
        rejects { preferences.addPendingMetricHabit(retained.claim.session, relocated.uuid) }
        assertEquals("zh", preferences.languageCode.first())
        assertTrue(repository.read(replacement).notificationEnabled)
    }

    @Test fun reauthenticationPreservesKnownIdentityButAccountAndServerChangesDoNot() = runBlocking {
        register()
        val repository = repository()
        val original = repository.read(habit)
        repository.setNeverAskAgain(original.claim, true)
        tokens.saveLoginSession("fresh-access", "fresh-refresh", "member", id(1), false)
        assertTrue(repository.read(habit).neverAsk)
        rejects { repository.setNeverAskAgain(original.claim, false) }
        tokens.saveServerIdentity(id(90), id(3))
        assertFalse(repository.read(habit).neverAsk)
        tokens.saveLoginSession("other-access", "other-refresh", "other", id(91), false)
        tokens.saveServerIdentity(id(2), id(3))
        assertFalse(repository.read(habit).neverAsk)
    }

    @Test fun provisionalPreferencesPromoteOnlyWithinOriginalLoginAndActualUuid() = runBlocking {
        val repository = repository()
        val provisional = repository.read(habit)
        repository.setNotification(provisional.claim, false)
        preferences.addPendingMetricHabit(local(), habit.uuid)
        register()
        assertFalse(repository.read(habit).notificationEnabled)
        assertEquals(setOf(habit.id), repository.pendingHabitIds.first())
        rejects { repository.setNotification(provisional.claim, true) }

        tokens.clearTokens()
        tokens.saveLoginSession("offline-access", "offline-refresh", "member", id(92), false)
        val unregistered = repository.read(habit)
        assertNull(unregistered.claim.session.serverInstanceId)
        repository.setNeverAskAgain(unregistered.claim, true)
        tokens.saveLoginSession("renewed-access", "renewed-refresh", "member", id(92), false)
        register()
        assertFalse(repository.read(habit).neverAsk)
    }

    @Test fun oldLongValuesAndMissingOrUndecryptableCredentialsNeverBecomeStableAuthority() = runBlocking {
        dataStore.edit {
            it[booleanPreferencesKey("habit_notification_${habit.id}")] = false
            it[booleanPreferencesKey("never_ask_metric_${habit.id}")] = true
            it[stringPreferencesKey("pending_metric_habits")] = habit.id.toString()
        }
        val snapshot = repository().read(habit)
        assertTrue(snapshot.notificationEnabled); assertFalse(snapshot.neverAsk)
        assertTrue(repository().pendingHabitIds.first().isEmpty())
        val unbound = PreferencesManager(dataStore)
        rejects { unbound.setNeverAskAgain(snapshot.claim.session, habit.uuid, true) }
        val badTokens = TokenManager(dataStore, object : TokenCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String): String = error("synthetic keystore failure")
        })
        val badPreferences = PreferencesManager(dataStore, tokens = badTokens)
        rejects { badPreferences.setNeverAskAgain(snapshot.claim.session, habit.uuid, true) }
        preferences.clearAccountScopedPreferences()
        assertTrue(dataStore.data.first().asMap().keys.none { it.name.startsWith("never_ask_metric_") ||
            it.name.startsWith("habit_notification_") || it.name.startsWith("stable_habit_preferences_v1_") })
    }

    @Test fun stableCodecIsBoundedStrictAndDoesNotGrantTemporaryActionAuthority() = runBlocking {
        val offline = local()
        val binding = StableHabitBinding.capture(offline, habit.uuid)
        assertEquals(binding, StableHabitBinding.decodeOrNull(binding.encode()))
        assertNull(StableHabitBinding.decodeOrNull(binding.encode().dropLast(1) + ",\"unknown\":true}"))
        assertNull(StableHabitBinding.decodeOrNull(" ".repeat(4_097)))
        assertNull(binding.resolve(offline.copy(authentication = offline.authentication.copy(generation = id(98))), habit.uuid))
        register()
        val known = requireNotNull(binding.resolve(local(), habit.uuid))
        assertNotEquals(binding, known)
        val renewed = local().copy(authentication = offline.authentication.copy(generation = id(99)), syncEpoch = id(97))
        assertEquals(known, known.resolve(renewed, habit.uuid))
        assertNull(known.resolve(renewed, id(96)))
    }

    @Test fun actualPersistentFenceBlocksReadingAndPublishingEvenWithoutDataStoreStagePointer() = runBlocking {
        register()
        val repository = repository()
        val original = repository.read(habit)
        preferences.addPendingMetricHabit(local(), habit.uuid)
        val before = dataStore.data.first()
        val session = local()
        sessions.exclusive {
            db.nextReplicaFenceDao().insert(com.dayforge.data.local.entity.NextReplicaFenceEntity(id(91),
                session.authentication.userId, id(2), id(3), id(92), session.authentication.generation,
                id(4), 1, "a".repeat(64)))
        }
        rejects { repository.read(habit) }
        rejects { repository.setNotification(original.claim, false) }
        rejects { repository.setNeverAskAgain(original.claim, true) }
        rejects { repository.removePending(original.claim) }
        assertTrue(repository.pendingHabitIds.first().isEmpty())
        assertEquals(before, dataStore.data.first())
    }
}
