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
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.ReplicaSwitchGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Stable-snapshot primitive differential, ONLY for an already registered owner. The joint
 * call always receives non-null registered authority. Registration/null-registered fallback,
 * authority leases, interleaved preference changes and COMMIT ordering are outside this class.
 * It does not turn a display fence into a reusable write grant or replace final authentication.
 * G0DomainFixture supplies real Keystore, DataStore and the original business tables.
 */
@RunWith(AndroidJUnit4::class)
class G0CountJointAuthorityDifferentialTest : G0DomainFixture() {
    private data class Captured(val core: LocalCoreWriteAccess, val registered: LocalSyncAccess) {
        init { require(registered.deviceId != null) { "G0_JOINT_REGISTERED_ONLY" } }
    }
    private data class Outcome(val allowed: Boolean?, val failureType: String?)
    private data class Observed(val outcome: Outcome, val access: Int, val refresh: Int, val reads: Int)
    private data class Fault(val label: String, val mutate: (MutablePreferences) -> Unit)

    private suspend fun capture(): Captured {
        register(); observation()
        return Captured(requireNotNull(tokens.localCoreWriteAccess()), access()).also {
            assertEquals(it.core.session, it.registered.session)
            assertEquals(it.core.capturedDeviceId, it.registered.deviceId)
            assertEquals(it.core.capabilities, it.registered.capabilities)
        }
    }

    private suspend fun reference(captured: Captured): Boolean =
        tokens.localCoreWriteAccess() == captured.core && tokens.isCurrentSyncAuthority(captured.registered)

    private suspend fun joint(captured: Captured): Boolean =
        tokens.isCurrentDisplayReadAuthority(captured.core, captured.registered)

    /** Preserve actual native values, including source rows/outbox/sequences. Digests avoid
     * printing row contents; authentication preferences are compared without stringifying. */
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

    private suspend fun observe(label: String, body: suspend () -> Boolean): Observed {
        val state = businessState()
        val preferences = dataStore.data.first()
        writes.clear()
        val trace = G0PhaseTrace(driverTiming = false)
        val outcome = try {
            Outcome(trace.run { body() }, null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Outcome(null, error.javaClass.name)
        }
        assertEquals("$label must not attempt business writes", emptyList<String>(), writes.toList())
        assertEquals("$label must not change any main-table cell", state, businessState())
        assertTrue("$label must not change authentication preferences", preferences == dataStore.data.first())
        assertEquals("$label must classify every decrypt", 0, trace.count("decrypt_unclassified"))
        return Observed(outcome, trace.count("decrypt_access"), trace.count("decrypt_refresh"), trace.count("datastore_read"))
    }

    private suspend fun compare(label: String, captured: Captured, allowed: Boolean) {
        val original = observe("$label/reference") { reference(captured) }
        val candidate = observe("$label/joint") { joint(captured) }
        assertEquals("$label reference Boolean result", Outcome(allowed, null), original.outcome)
        assertEquals("$label Boolean/exception-type equivalence", original.outcome, candidate.outcome)
        assertEquals("$label original does not need refresh", 0, original.refresh)
        assertEquals("$label joint does not need refresh", 0, candidate.refresh)
        if (allowed) {
            assertEquals("$label original access decryptions", 2, original.access)
            assertEquals("$label original fresh snapshots", 2, original.reads)
            assertEquals("$label joint real access decryption", 1, candidate.access)
            assertEquals("$label joint fresh snapshot", 1, candidate.reads)
        }
        // Rejections can short-circuit at different predicates; equal decrypt/read counts
        // would be a false requirement. The complete Boolean contract and no writes matter.
    }

    private suspend fun restore(snapshot: Preferences) { dataStore.updateData { snapshot } }

    private fun damaged(ciphertext: String): String {
        require(ciphertext.startsWith("enc:v1:"))
        val bytes = Base64.decode(ciphertext.removePrefix("enc:v1:"), Base64.NO_WRAP)
        require(bytes.size > 28)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        return "enc:v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Test fun stableRegisteredCallsUseOneFreshSnapshotAndDecryptInsteadOfTwo() = runBlocking<Unit> {
        val captured = capture()
        val preferences = dataStore.data.first()
        assertTrue(requireNotNull(preferences[ACCESS]).startsWith("enc:v1:"))
        assertTrue(requireNotNull(preferences[REFRESH]).startsWith("enc:v1:"))
        repeat(3) { compare("fresh-$it", captured, true) }
    }

    @Test fun coreIdentityOwnerGenerationReplicaAndDeviceChangesRejectEqually() = runBlocking<Unit> {
        val captured = capture(); val baseline = dataStore.data.first()
        val faults = listOf(
            Fault("user") { it[stringPreferencesKey("user_public_id")] = id(3201) },
            Fault("missing-user") { it.remove(stringPreferencesKey("user_public_id")) },
            Fault("owner") { it[stringPreferencesKey("sync_account_id")] = id(3202) },
            Fault("generation") { it[stringPreferencesKey("auth_session_generation")] = id(3203) },
            Fault("missing-generation") { it.remove(stringPreferencesKey("auth_session_generation")) },
            Fault("server") { it[stringPreferencesKey("sync_server_instance_id")] = id(3204) },
            Fault("missing-server-only") { it.remove(stringPreferencesKey("sync_server_instance_id")) },
            Fault("epoch") { it[stringPreferencesKey("sync_epoch")] = id(3205) },
            Fault("invalid-epoch") { it[stringPreferencesKey("sync_epoch")] = "not-a-uuid" },
            Fault("device") { it[stringPreferencesKey("sync_device_id")] = id(3206) },
            Fault("missing-device") { it.remove(stringPreferencesKey("sync_device_id")) }
        )
        for (fault in faults) {
            restore(baseline); dataStore.edit { fault.mutate(it) }
            compare(fault.label, captured, false)
        }
        restore(baseline); compare("restored-core-metadata", captured, true)
    }

    @Test fun registeredBindingRevisionAndCompletePermissionSetRemainRequired() = runBlocking<Unit> {
        val captured = capture(); val baseline = dataStore.data.first()
        val faults = listOf(
            Fault("capability-device") { it[stringPreferencesKey("sync_device_capabilities_device")] = id(3210) },
            Fault("missing-capability-device") { it.remove(stringPreferencesKey("sync_device_capabilities_device")) },
            Fault("capability-revision") { it[stringPreferencesKey("sync_device_capability_revision")] = "2" },
            Fault("invalid-revision") { it[stringPreferencesKey("sync_device_capability_revision")] = "0" },
            Fault("missing-revision") { it.remove(stringPreferencesKey("sync_device_capability_revision")) },
            Fault("unknown-capabilities") { it[booleanPreferencesKey("sync_device_capabilities_known")] = false },
            Fault("missing-known-flag") { it.remove(booleanPreferencesKey("sync_device_capabilities_known")) },
            Fault("missing-capabilities") { it.remove(CAPABILITIES) },
            Fault("changed-capabilities") { it[CAPABILITIES] = caps - "facts.append" },
            Fault("missing-sync-read") { it[CAPABILITIES] = caps - "sync.read" },
            Fault("extra-capability") { it[CAPABILITIES] = caps + "synthetic.extra" }
        )
        for (fault in faults) {
            restore(baseline); dataStore.edit { fault.mutate(it) }
            compare(fault.label, captured, false)
        }
        restore(baseline); compare("restored-registered-metadata", captured, true)
    }

    @Test fun accessLossRejectsButMissingOrDamagedRefreshDoesNotChangeThePredicate() = runBlocking<Unit> {
        val captured = capture(); val baseline = dataStore.data.first()
        compare("before-credential-faults", captured, true)
        dataStore.edit { it.remove(ACCESS) }
        compare("missing-access", captured, false)
        restore(baseline)
        dataStore.edit { it[ACCESS] = damaged(requireNotNull(baseline[ACCESS])) }
        compare("damaged-access", captured, false)
        restore(baseline)
        dataStore.edit { it.remove(REFRESH) }
        compare("missing-refresh", captured, true)
        restore(baseline)
        dataStore.edit { it[REFRESH] = damaged(requireNotNull(baseline[REFRESH])) }
        compare("damaged-refresh", captured, true)
        restore(baseline); compare("restored-credentials", captured, true)
    }

    @Test fun anyReplicaFreezePointerStillClosesOrdinaryCoreAuthority() = runBlocking<Unit> {
        val captured = capture(); val baseline = dataStore.data.first()
        for (pointer in listOf(id(3220), "malformed", "")) {
            restore(baseline); dataStore.edit { it[ReplicaSwitchGuard.POINTER] = pointer }
            compare("frozen-pointer-presence", captured, false)
        }
        dataStore.edit {
            it[ACCESS] = damaged(requireNotNull(baseline[ACCESS]))
            it[stringPreferencesKey("user_public_id")] = id(3221)
        }
        compare("frozen-with-credential-and-identity-faults", captured, false)
        restore(baseline); compare("unfrozen", captured, true)
    }

    @Test fun fullCapturedCoreAndRegisteredScopesCannotBeReplacedByFieldSimilarity() = runBlocking<Unit> {
        val captured = capture()
        assertTrue(tokens.isCurrentSyncAuthority(captured.registered))
        val changedCore = listOf(
            captured.core.copy(capabilities = caps - "facts.append"),
            captured.core.copy(capturedDeviceId = id(3230)),
            captured.core.copy(session = captured.core.session.copy(
                authentication = captured.core.session.authentication.copy(generation = id(3231))))
        )
        changedCore.forEachIndexed { index, core -> compare("captured-core-$index", captured.copy(core = core), false) }
        assertEquals(captured.core, tokens.localCoreWriteAccess())
        compare("captured-registered-revision", captured.copy(registered = captured.registered.copy(capabilityRevision = 2)), false)
        compare("captured-registered-capabilities", captured.copy(registered = captured.registered.copy(capabilities = caps - "facts.append")), false)
        compare("original-captured-scopes", captured, true)
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val CAPABILITIES = stringSetPreferencesKey("sync_device_capabilities")
    }
}
