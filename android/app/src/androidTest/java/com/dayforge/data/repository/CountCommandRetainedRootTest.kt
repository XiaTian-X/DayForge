package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandRetainedRootTest : CountCommandRetainedFixture() {
    private fun reissue(header: CountCommandRootCodec.Header): CountCommandRecoveryRootEntity {
        val key = requireNotNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(CountCommandSeal.ALIAS, null) as? SecretKey)
        return header.entity(Mac.getInstance("HmacSHA256").run { init(key); doFinal(CountCommandRootCodec.body(header)) })
    }
    private fun installFault(row: CountCommandRecoveryRootEntity) {
        val fields = CountCommandNativeRows.ROOT_COLUMNS.drop(1)
        external("UPDATE count_command_recovery_roots SET ${fields.joinToString(",") { "`$it`=?" }} WHERE id=1",
            CountCommandNativeRows.rootBindings(row).drop(1).toTypedArray())
        external("UPDATE count_trust_control SET commandRootHash=? WHERE id=1", arrayOf(CountCommandRootAnchor.digest(row)))
    }
    @Test(timeout = 150_000) fun format3HasExplicitOldOriginAndDoesNotAdmitCommandFormat3OrDiscardRetainedState() = runBlocking<Unit> {
        seed(); val database = openCurrent(); val old = CountCommandRootCodec.fromEntity(root(database))
        store(database).ensureForwardEnrollment(); val fresh = CountCommandRootCodec.fromEntity(root(database))
        val body = Json.parseToJsonElement(CountCommandRootCodec.body(fresh).toString(Charsets.UTF_8)).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), body.getValue("command_formats"))
        val retained = body.getValue("retained").jsonObject
        assertEquals(JsonPrimitive(old.legacyHash), retained.getValue("hash")); assertEquals(JsonPrimitive(1L), retained.getValue("count"))
        assertEquals(JsonPrimitive("MIGRATION_19_20"), retained.getValue("origin").jsonObject.getValue("source"))
        assertTrue(CountCommandRootCodec.admits(fresh, 1)); assertTrue(CountCommandRootCodec.admits(fresh, 2)); assertFalse(CountCommandRootCodec.admits(fresh, 3))
        assertNotNull(runCatching { fresh.copy(legacyCount = null, legacyHash = null) }.exceptionOrNull())
        val discarded = fresh.copy(mode = CountCommandRootCodec.Mode.ACCOUNT_BOUND, format = 2,
            validator = CountCommandRecords.METRIC_VALIDATOR, legacyCount = null, legacyHash = null, revision = fresh.revision + 1)
        assertNotNull(runCatching { CountCommandRootCodec.requireNext(fresh, discarded) }.exceptionOrNull())
        assertNotNull(runCatching { CountCommandRootCodec.requireNext(fresh, fresh.copy(legacyHash = "b".repeat(64), revision = fresh.revision + 1)) }.exceptionOrNull())
        assertLegacy(database)
    }
    @Test(timeout = 150_000) fun validMacWithWrongInitialSourceRejectsBeforeAnyEnrollmentMutation() = runBlocking<Unit> {
        seed(); val database = openCurrent()
        installFault(reissue(CountCommandRootCodec.fromEntity(root(database)).copy(source = CountCommandRootCodec.Source.NEW_DATABASE)))
        val cold = openCurrent(); val before = image(cold)
        assertNotNull(runCatching { store(cold).ensureForwardEnrollment() }.exceptionOrNull())
        assertEquals(before, image(cold)); assertEquals(0L, count(cold, "count_command_nodes")); assertLegacy(cold)
    }
    @Test(timeout = 150_000) fun validMacWithNonInitialRevisionRejectsBeforeAnyEnrollmentMutation() = runBlocking<Unit> {
        seed(); val database = openCurrent()
        installFault(reissue(CountCommandRootCodec.fromEntity(root(database)).copy(revision = 2L)))
        val cold = openCurrent(); val before = image(cold)
        assertNotNull(runCatching { store(cold).ensureForwardEnrollment() }.exceptionOrNull())
        assertEquals(before, image(cold)); assertEquals(0L, count(cold, "count_command_nodes")); assertLegacy(cold)
    }
    @Test(timeout = 150_000) fun firstRegistrationKeepsFixedOldCommitmentButDoesNotRebindAnotherEpoch() = runBlocking<Unit> {
        seed(); tokens.clearTokens()
        tokens.saveLoginSession("local", "refresh", "member", id(1), false)
        assertNotNull(tokens.localCoreWriteAccess()); assertNull(tokens.localSyncAccess())
        val database = openCurrent(); store(database).ensureForwardEnrollment(); val before = root(database)
        assertNull(before.registrationPreviousHash)
        tokens.saveServerIdentity(id(2), id(3)); tokens.getOrCreateInstallationId()
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
        store(database).acknowledgeInitialRegistration(); val registered = root(database)
        assertEquals(before.namespaceHash, registered.registrationPreviousHash)
        assertEquals(before.legacyHash, registered.legacyHash); assertEquals(before.legacyCount, registered.legacyCount)
        assertEquals(before.datasetId, registered.datasetId); assertLegacy(database)
        tokens.saveServerIdentity(id(2), id(8))
        val original = image(database)
        assertNotNull(runCatching { store(database).acknowledgeInitialRegistration() }.exceptionOrNull())
        assertEquals(original, image(database))
    }
    @Test(timeout = 150_000) fun badRetainedMacAndMatchingAnchorRemainARejectionNotAnEnrollmentMiss() = runBlocking<Unit> {
        seed(); val database = openCurrent(); store(database).ensureForwardEnrollment()
        installFault(root(database).copy(mac = ByteArray(32)))
        val failure = runCatching { openCurrent() }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("COMMAND_PRIMARY_MAC_INVALID") })
    }
}
