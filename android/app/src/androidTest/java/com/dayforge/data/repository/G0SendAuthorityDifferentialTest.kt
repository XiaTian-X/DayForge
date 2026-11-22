package com.dayforge.data.repository

import android.database.Cursor
import android.util.Base64
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.ReplicaSwitchGuard
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Primitive differential only. The reference below preserves NextSyncHttp.authorize's
 * pending-first/snapshot-null rules; it does not invoke that private method or replace HTTP.
 * G0DomainFixture supplies real Android Keystore and per-operation credential diagnostics.
 * Registration, recovery reads, 401 refresh, transport and HTTP check placement stay original.
 */
@RunWith(AndroidJUnit4::class)
class G0SendAuthorityDifferentialTest : G0DomainFixture() {
    private data class Failure(val type: String, val message: String?)
    private data class Observation(val failure: Failure?, val access: Int, val refresh: Int, val reads: Int)
    private enum class Path { ORIGINAL, REGISTERED_ORDINARY }

    private fun path(context: LocalSyncAccess, recoveryReadOnly: Boolean) =
        if (context.deviceId == null || recoveryReadOnly) Path.ORIGINAL else Path.REGISTERED_ORDINARY

    // These two decisions mirror the current private NextSyncHttp.authorize. No network
    // call, business validator or request construction is copied into this experiment.
    private suspend fun original(context: LocalSyncAccess, recoveryReadOnly: Boolean = false) {
        if (!recoveryReadOnly && tokens.replicaSwitchPending()) throw IOException(ReplicaSwitchGuard.PENDING)
        if (tokens.syncAuthenticationSnapshot(context) == null) throw IOException(SESSION_CHANGED)
    }

    private suspend fun registered(context: LocalSyncAccess, recoveryReadOnly: Boolean = false) {
        require(context.deviceId != null && !recoveryReadOnly) { SCOPE_UNSUPPORTED }
        if (tokens.replicaSwitchPending()) throw IOException(ReplicaSwitchGuard.PENDING)
        if (!tokens.isCurrentSyncAuthority(context)) throw IOException(SESSION_CHANGED)
    }

    private suspend fun selected(context: LocalSyncAccess, recoveryReadOnly: Boolean = false) {
        when (path(context, recoveryReadOnly)) {
            Path.ORIGINAL -> original(context, recoveryReadOnly)
            Path.REGISTERED_ORDINARY -> registered(context, recoveryReadOnly)
        }
    }

    /** All main tables, including immutable sources/outbox/sequence. Only digests are kept;
     * the authentication preference contents and credential plaintext are never printed. */
    private fun businessState(): Map<String, String> {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val quoted = table.replace("\"", "\"\"")
            sql.query("SELECT * FROM main.\"$quoted\"").use { cursor ->
                val rows = buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> "N"
                            Cursor.FIELD_TYPE_INTEGER -> "I${cursor.getLong(index)}"
                            Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(cursor.getDouble(index))}"
                            Cursor.FIELD_TYPE_BLOB -> "B${digest(cursor.getBlob(index).toList().toString())}"
                            else -> "S${digest(cursor.getString(index))}"
                        }
                    })
                }
                digest(cursor.columnNames.toList().toString() + rows.sorted().toString())
            }
        }
    }

    private suspend fun observe(label: String, block: suspend () -> Unit): Observation {
        val before = businessState()
        val preferencesBefore = dataStore.data.first()
        writes.clear()
        val trace = G0PhaseTrace(driverTiming = false)
        var failure: Failure? = null
        try {
            trace.run { block() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = Failure(error.javaClass.name, error.message)
        }
        assertEquals("$label must not attempt business writes", emptyList<String>(), writes.toList())
        assertEquals("$label must preserve every main-table cell", before, businessState())
        assertTrue("$label must not mutate authentication preferences", preferencesBefore == dataStore.data.first())
        assertEquals("$label must classify every decryption", 0, trace.count("decrypt_unclassified"))
        return Observation(failure, trace.count("decrypt_access"), trace.count("decrypt_refresh"), trace.count("datastore_read"))
    }

    private suspend fun compare(label: String, context: LocalSyncAccess, failure: String? = null,
        access: Int = 1, referenceRefresh: Int = 1, reads: Int = 2) {
        assertEquals(Path.REGISTERED_ORDINARY, path(context, false))
        val reference = observe("$label/reference") { original(context) }
        val candidate = observe("$label/candidate") { selected(context) }
        val expectedFailure = failure?.let { Failure(IOException::class.java.name, it) }
        assertEquals("$label reference outcome", expectedFailure, reference.failure)
        assertEquals("$label identical outcome and refusal priority", reference.failure, candidate.failure)
        assertEquals("$label original access decryption", access, reference.access)
        assertEquals("$label candidate access must be freshly decrypted", access, candidate.access)
        assertEquals("$label original refresh decryption", referenceRefresh, reference.refresh)
        assertEquals("$label candidate never decrypts unused refresh", 0, candidate.refresh)
        assertEquals("$label original fresh snapshots", reads, reference.reads)
        assertEquals("$label candidate fresh snapshots", reads, candidate.reads)
    }

    private suspend fun restore(snapshot: Preferences) {
        // Fault-matrix setup only. Reuses the same real TokenManager/cipher instance so
        // a cached successful authority would be exposed by the next mutation/recheck.
        dataStore.updateData { snapshot }
    }

    private fun damaged(ciphertext: String): String {
        require(ciphertext.startsWith("enc:v1:"))
        val bytes = Base64.decode(ciphertext.removePrefix("enc:v1:"), Base64.NO_WRAP)
        require(bytes.size > 28)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        return "enc:v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Test fun registeredChecksReadAndDecryptCurrentAccessOnEveryInvocation() = runBlocking<Unit> {
        register(); observation()
        val captured = access()
        val values = dataStore.data.first()
        assertTrue(requireNotNull(values[ACCESS]).startsWith("enc:v1:"))
        assertTrue(requireNotNull(values[REFRESH]).startsWith("enc:v1:"))
        repeat(3) { compare("fresh-$it", captured) }
    }

    @Test fun missingOrCryptographicallyDamagedRefreshRemainsOptionalForBooleanAuthority() = runBlocking<Unit> {
        register(); observation()
        val captured = access(); val baseline = dataStore.data.first()
        dataStore.edit { it.remove(REFRESH) }
        compare("missing-refresh", captured, referenceRefresh = 0)
        restore(baseline)
        dataStore.edit { it[REFRESH] = damaged(requireNotNull(baseline[REFRESH])) }
        compare("damaged-refresh", captured)
        restore(baseline)
        compare("restored-refresh", captured)
    }

    @Test fun missingOrCryptographicallyDamagedAccessCannotReusePriorSuccess() = runBlocking<Unit> {
        register(); observation()
        val captured = access(); val baseline = dataStore.data.first()
        compare("before-access-loss", captured)
        dataStore.edit { it.remove(ACCESS) }
        compare("missing-access", captured, SESSION_CHANGED, access = 0, referenceRefresh = 0)
        restore(baseline)
        dataStore.edit { it[ACCESS] = damaged(requireNotNull(baseline[ACCESS])) }
        compare("damaged-access", captured, SESSION_CHANGED, referenceRefresh = 0)
        restore(baseline)
        compare("restored-access", captured)
    }

    @Test fun identityReplicaDeviceAndPermissionChangesRejectBeforeDecryption() = runBlocking<Unit> {
        register(); observation()
        val captured = access(); val baseline = dataStore.data.first()
        val mutations: List<Pair<String, (MutablePreferences) -> Unit>> = listOf(
            "user" to { it[stringPreferencesKey("user_public_id")] = id(3101) },
            "session" to { it[stringPreferencesKey("auth_session_generation")] = id(3102) },
            "owner" to { it[stringPreferencesKey("sync_account_id")] = id(3103) },
            "server" to { it[stringPreferencesKey("sync_server_instance_id")] = id(3104) },
            "epoch" to { it[stringPreferencesKey("sync_epoch")] = id(3105) },
            "device" to { it[stringPreferencesKey("sync_device_id")] = id(3106) },
            "capability-device" to { it[stringPreferencesKey("sync_device_capabilities_device")] = id(3107) },
            "capability-revision" to { it[stringPreferencesKey("sync_device_capability_revision")] = "2" },
            "invalid-revision" to { it[stringPreferencesKey("sync_device_capability_revision")] = "0" },
            "unknown-capabilities" to { it[booleanPreferencesKey("sync_device_capabilities_known")] = false },
            "changed-capabilities" to { it[stringSetPreferencesKey("sync_device_capabilities")] = caps - "facts.append" },
            "missing-sync-read" to { it[stringSetPreferencesKey("sync_device_capabilities")] = caps - "sync.read" }
        )
        for ((label, mutate) in mutations) {
            restore(baseline); dataStore.edit { mutate(it) }
            compare(label, captured, SESSION_CHANGED, access = 0, referenceRefresh = 0)
        }
        restore(baseline); compare("restored-metadata", captured)
    }

    @Test fun frozenReplicaPresenceWinsOverBrokenCredentialsAndChangedIdentity() = runBlocking<Unit> {
        register(); observation()
        val captured = access(); val baseline = dataStore.data.first()
        for (pointer in listOf(id(3110), "malformed", "")) {
            restore(baseline)
            dataStore.edit { it[ReplicaSwitchGuard.POINTER] = pointer }
            compare("pending-presence", captured, ReplicaSwitchGuard.PENDING, access = 0, referenceRefresh = 0, reads = 1)
        }
        dataStore.edit {
            it[ACCESS] = damaged(requireNotNull(baseline[ACCESS]))
            it[stringPreferencesKey("user_public_id")] = id(3111)
        }
        compare("pending-before-session-error", captured, ReplicaSwitchGuard.PENDING, access = 0, referenceRefresh = 0, reads = 1)
        restore(baseline); compare("unfrozen", captured)
    }

    @Test fun registrationWithoutDeviceMustKeepOriginalSnapshotPath() = runBlocking<Unit> {
        val registration = requireNotNull(tokens.registrationSyncAccess(id(2), id(3)))
        assertNull(registration.deviceId)
        assertEquals(Path.ORIGINAL, path(registration, false))
        val reference = observe("registration/reference") { original(registration) }
        val fallback = observe("registration/fallback") { selected(registration) }
        assertEquals(Observation(null, 1, 1, 2), reference)
        assertEquals(reference, fallback)
        val excluded = observe("registration/excluded") { registered(registration) }
        assertEquals(Observation(Failure(IllegalArgumentException::class.java.name, SCOPE_UNSUPPORTED), 0, 0, 0), excluded)
        assertFalse("Registered primitive is not registration authority", tokens.isCurrentSyncAuthority(registration))
    }

    @Test fun recoveryReadOnlyMustKeepOriginalPathAndPendingErrorCannotBeDropped() = runBlocking<Unit> {
        register(); observation()
        val captured = access()
        dataStore.edit { it[ReplicaSwitchGuard.POINTER] = id(3120) }
        assertEquals(Path.ORIGINAL, path(captured, true))
        val reference = observe("recovery/reference") { original(captured, recoveryReadOnly = true) }
        val fallback = observe("recovery/fallback") { selected(captured, recoveryReadOnly = true) }
        assertEquals(Observation(null, 1, 1, 1), reference)
        assertEquals(reference, fallback)
        val excluded = observe("recovery/excluded") { registered(captured, recoveryReadOnly = true) }
        assertEquals(Observation(Failure(IllegalArgumentException::class.java.name, SCOPE_UNSUPPORTED), 0, 0, 0), excluded)
        // The bare primitive deliberately has no replica-freeze check. The ordinary
        // selected path must retain PENDING even though metadata/access still match.
        assertTrue(tokens.isCurrentSyncAuthority(captured))
        compare("ordinary-while-recovery-frozen", captured, ReplicaSwitchGuard.PENDING, access = 0, referenceRefresh = 0, reads = 1)
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        const val SESSION_CHANGED = "SYNC_SESSION_CHANGED"
        const val SCOPE_UNSUPPORTED = "G0_SEND_AUTHORITY_SCOPE_UNSUPPORTED"
    }
}
