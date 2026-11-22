package com.dayforge.data.local

import androidx.datastore.preferences.core.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySessionGenerationMigrationTest : LegacySessionGenerationFixture() {
    @Test fun realOldKeystoreSessionAddsOnlyOneLocalKeyAndColdReadKeepsIt() = legacyGenerationTest {
        val before = raw().asMap()
        assertFalse(before.keys.any { it.name == generationKey.name })
        assertTrue(raw()[stringPreferencesKey("access_token")]!!.startsWith("enc:v1:"))
        val result = prepare() as LegacySessionGenerationResult.CreatedGeneration
        assertTrue(isContractUuid(result.generation)); assertEquals(result.generation, generation())
        assertEquals(before, raw().asMap().filterKeys { it.name != generationKey.name })
        assertEquals(1, cipher.decryptions.get()); assertEquals(0, cipher.mainDecryptions.get())
        assertEquals(1, store.committedChanges.get())
        cold()
        assertEquals(LegacySessionGenerationResult.CurrentGeneration(result.generation), prepare())
        assertEquals(result.generation, generation()); assertEquals(0, store.committedChanges.get())
        assertNotNull(tokens.localCoreWriteAccess())
    }

    @Test fun concurrentAccountLockedPreparationsCommitExactlyOneGeneration() = legacyGenerationTest {
        val results = coroutineScope { List(8) { async(Dispatchers.IO) { prepare() } }.awaitAll() }
        val saved = requireNotNull(generation())
        assertEquals(1, results.count { it is LegacySessionGenerationResult.CreatedGeneration })
        assertEquals(7, results.count { it == LegacySessionGenerationResult.CurrentGeneration(saved) })
        assertEquals(1, store.committedChanges.get()); assertEquals(1, cipher.decryptions.get())
    }

    @Test fun allPresentMalformedKeyShapesArePreservedWithoutNormalization() = legacyGenerationTest {
        val original = raw()
        val variants: List<MutablePreferences.() -> Unit> = listOf(
            { this[generationKey] = "" }, { this[generationKey] = "legacy" },
            { this[generationKey] = "not-a-uuid" }, { this[intPreferencesKey(generationKey.name)] = 7 },
            { this[booleanPreferencesKey(generationKey.name)] = true })
        for (variant in variants) {
            store.updateData { original }; store.edit { variant(it) }
            val before = raw()
            assertEquals(LegacySessionGenerationResult.NotApplicable, prepare())
            assertEquals(before, raw())
        }
        assertEquals(0, cipher.decryptions.get())
    }

    @Test fun unownedMismatchedPartialAndOpaqueIdentitiesNeverGetInventedCanonicalValues() = legacyGenerationTest {
        val original = raw()
        val variants: List<Pair<String, MutablePreferences.() -> Unit>> = listOf(
            "unowned" to { remove(stringPreferencesKey("sync_account_id")); Unit },
            "other owner" to { this[stringPreferencesKey("sync_account_id")] = legacyGenerationId(9) },
            "opaque account" to { this[stringPreferencesKey("user_public_id")] = "account-a"; this[stringPreferencesKey("sync_account_id")] = "account-a" },
            "half namespace" to { remove(stringPreferencesKey("sync_epoch")); Unit },
            "opaque server" to { this[stringPreferencesKey("sync_server_instance_id")] = "server-a" },
            "opaque epoch" to { this[stringPreferencesKey("sync_epoch")] = "epoch-a" },
            "opaque device" to { this[stringPreferencesKey("sync_device_id")] = "device-a" })
        for ((name, variant) in variants) {
            store.updateData { original }; store.edit { variant(it) }; val before = raw()
            assertEquals(name, LegacySessionGenerationResult.NotApplicable, prepare())
            assertEquals(name, before, raw()); assertNull(name, generation())
        }
    }

    @Test fun missingOrUnreadableAccessIsVisibleAuthenticationAbsenceAndWritesNothing() = legacyGenerationTest {
        val original = raw()
        for (value in listOf<String?>(null, "enc:v1:invalid")) {
            store.updateData { original }
            store.edit { if (value == null) it.remove(stringPreferencesKey("access_token")) else it[stringPreferencesKey("access_token")] = value }
            val before = raw()
            assertEquals(LegacySessionGenerationResult.AuthenticationUnavailable, prepare())
            assertEquals(before, raw()); assertNull(generation())
        }
    }

    @Test fun missingAndUnreadableRefreshAreNotAdditionalLocalAuthenticationRequirements() = legacyGenerationTest {
        val original = raw()
        for (value in listOf<String?>(null, "enc:v1:invalid-refresh")) {
            store.updateData { original }
            store.edit { if (value == null) it.remove(stringPreferencesKey("refresh_token")) else it[stringPreferencesKey("refresh_token")] = value }
            val before = raw().asMap(); cipher.decryptions.set(0)
            assertTrue(prepare() is LegacySessionGenerationResult.CreatedGeneration)
            assertEquals(1, cipher.decryptions.get())
            assertEquals(before, raw().asMap().filterKeys { it.name != generationKey.name })
        }
    }

    @Test fun actorCasIncludesEveryMetadataFieldAndOriginalAccessAndRefreshCiphertext() = legacyGenerationTest {
        val original = raw()
        val updates: List<Pair<String, MutablePreferences.() -> Unit>> = listOf(
            "user" to { this[stringPreferencesKey("user_public_id")] = legacyGenerationId(8) },
            "owner" to { this[stringPreferencesKey("sync_account_id")] = legacyGenerationId(8) },
            "generation" to { this[generationKey] = legacyGenerationId(8) },
            "server" to { this[stringPreferencesKey("sync_server_instance_id")] = legacyGenerationId(8) },
            "epoch" to { this[stringPreferencesKey("sync_epoch")] = legacyGenerationId(8) },
            "device" to { this[stringPreferencesKey("sync_device_id")] = legacyGenerationId(8) },
            "installation" to { this[stringPreferencesKey("sync_installation_id")] = legacyGenerationId(8) },
            "known" to { this[booleanPreferencesKey("sync_device_capabilities_known")] = false },
            "capabilities" to { this[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") },
            "capability device" to { this[stringPreferencesKey("sync_device_capabilities_device")] = legacyGenerationId(8) },
            "revision" to { this[stringPreferencesKey("sync_device_capability_revision")] = "4" },
            "primary" to { this[booleanPreferencesKey("sync_device_primary_editor")] = false },
            "access ciphertext" to { this[stringPreferencesKey("access_token")] = cipher.encrypt("synthetic-legacy-access") },
            "refresh ciphertext" to { this[stringPreferencesKey("refresh_token")] = cipher.encrypt("synthetic-legacy-refresh") },
            "cursor" to { this[stringPreferencesKey("sync_cursor")] = "52" },
            "recovery pointer" to { this[ReplicaSwitchGuard.POINTER] = legacyGenerationId(8) },
            "recovery journal" to { this[ReplicaSwitchStage.key(legacyGenerationId(8))] = "invalid-journal" })
        for ((name, update) in updates) sessions.exclusive {
            store.updateData { original }
            val expected = tokens.captureLegacySessionPreparationWhileAccountLocked()
            store.edit { update(it) }; val changed = raw(); cipher.decryptions.set(0)
            assertEquals(name, LegacySessionGenerationResult.Stale, tokens.prepareLegacySessionGenerationWhileAccountLocked(expected))
            assertEquals(name, changed, raw()); assertEquals(name, 0, cipher.decryptions.get())
        }
    }

    @Test fun realRefreshLoginLogoutAndRegistrationWinnerCannotBeOverwrittenByOldPreparation() = legacyGenerationTest {
        val original = raw()
        val competitors: List<suspend () -> Unit> = listOf(
            { val old = requireNotNull(tokens.authenticationSnapshot()); assertTrue(tokens.saveRefreshedTokens(old,
                "refreshed-access", "refreshed-refresh", "refreshed", legacyGenerationId(1), false)) },
            { tokens.saveLoginSession("new-access", "new-refresh", "new", legacyGenerationId(9), false) },
            { tokens.clearTokens() },
            { tokens.saveDeviceRegistration(legacyGenerationId(4), setOf("sync.read"), false, 4) })
        for (competitor in competitors) sessions.exclusive {
            store.updateData { original }; val expected = tokens.captureLegacySessionPreparationWhileAccountLocked()
            competitor(); val winner = raw()
            assertEquals(LegacySessionGenerationResult.Stale, tokens.prepareLegacySessionGenerationWhileAccountLocked(expected))
            assertEquals(winner, raw())
        }
    }

    @Test fun oldRefreshCannotReplaceCredentialsAfterGenerationWasPersisted() = legacyGenerationTest {
        val old = requireNotNull(tokens.authenticationSnapshot())
        prepare(); val before = raw()
        assertFalse(tokens.saveRefreshedTokens(old, "late-access", "late-refresh", "late", legacyGenerationId(1), false))
        tokens.clearRejectedRefresh(old)
        assertEquals(before, raw())
    }

    @Test fun readinessDoesNotGrantFactsPermissionAndExistingGenerationDoesNotAuthenticate() = legacyGenerationTest {
        store.edit { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") }
        prepare(); assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        val generated = requireNotNull(generation())
        store.edit { it[stringPreferencesKey("access_token")] = "enc:v1:unreadable" }
        assertEquals(LegacySessionGenerationResult.CurrentGeneration(generated), prepare())
        assertNull(tokens.localCoreWriteAccess()); assertNull(tokens.localFactAccess())
    }

    @Test fun neverRegisteredSessionGetsOnlyLocalGenerationWithoutRemoteIdentityOrCapabilityChanges() = legacyGenerationTest {
        store.edit { values ->
            listOf("sync_server_instance_id", "sync_epoch", "sync_device_id", "sync_device_capabilities_device",
                "sync_device_capability_revision").forEach { values.remove(stringPreferencesKey(it)) }
            values.remove(booleanPreferencesKey("sync_device_capabilities_known"))
            values.remove(stringSetPreferencesKey("sync_device_capabilities"))
        }
        val before = raw().asMap(); assertTrue(prepare() is LegacySessionGenerationResult.CreatedGeneration)
        assertEquals(before, raw().asMap().filterKeys { it.name != generationKey.name })
        val core = requireNotNull(tokens.localCoreWriteAccess())
        assertNull(core.session.serverInstanceId); assertNull(core.session.syncEpoch); assertNull(core.capturedDeviceId)
    }
}
