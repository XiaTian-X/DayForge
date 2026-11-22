package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One-time compatibility authentication, not COUNT business qualification. The old node
 * tree contains a valid retained directory record, not a fabricated eligible business root.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandSchemaBridgeTest : CountCommandMigrationFixture() {
    private suspend fun valid(dirty: Boolean, validator: String) {
        val access = access(); var old: CountTrustSeal.Header? = null
        val before = source { old = oldCountRoot(it, access, dirty, validator) }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        val migrated = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(migrated)).contents)
        CountCommandStore(current, tokens, sessions).bindEmptyNamespace()
        val changed = requireNotNull(CountTrustSeal.read(sql)); val original = requireNotNull(old)
        assertEquals(original.root, changed.root); assertEquals(original.binding, changed.binding)
        assertEquals(original.generation, changed.generation); assertEquals(original.validator, changed.validator)
        assertEquals(original.revision + 1, changed.revision); assertNotEquals(original.schemaHash, changed.schemaHash)
        assertEquals(schemaHash(sql), changed.schemaHash)
        assertTrue(CountTrustSeal().matches(changed, changed.binding, changed.schemaHash))
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        val oldTables = before.contents.getValue("tables").let { it as kotlinx.serialization.json.JsonObject }
        val newTables = after.contents.getValue("tables").let { it as kotlinx.serialization.json.JsonObject }
        for (table in oldTables.keys - setOf("room_master_table", "count_trust_root")) {
            val currentRows = newTables.getValue(table)
            val oldView = if (table == "count_trust_control") JsonArray(currentRows.jsonArray.map { row ->
                assertEquals(setOf("__recovery_rowid", "id", "storageId", "generation", "dirty", "commandRootHash"), row.jsonObject.keys)
                JsonObject(row.jsonObject.filterKeys { it != "commandRootHash" })
            }) else currentRows
            assertEquals(table, oldTables.getValue(table), oldView)
        }
        val initialAnchor = migrated.contents.getValue("tables").jsonObject.getValue("count_trust_control").jsonArray.single().jsonObject.getValue("commandRootHash")
        val afterAnchor = newTables.getValue("count_trust_control").jsonArray.single().jsonObject.getValue("commandRootHash")
        assertNotEquals(initialAnchor, afterAnchor)
        current.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(sql) }
        assertEquals(CountCommandRootAnchor.digest(CountCommandNativeRows(sql) {}.root()), CountCommandRootAnchor.read(sql).hash)
        val owner = requireNotNull(CountTrustRuntime.forDatabase(current))
        repeat(2) {
            current.withTransaction { owner.maintenance(current) {
                assertFalse(CountCommandSchemaBridge.pending(current, access))
            } }
        }
        assertEquals(after.contents, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
        assertFalse(owner.isRetired)
    }
    @Test(timeout = 150_000) fun cleanV3RootTransfersOnlyItsEnvelopeOnce() = runBlocking<Unit> {
        valid(false, CountTrustSeal.LEGACY_ENVIRONMENT_VALIDATOR)
    }
    @Test(timeout = 150_000) fun dirtyV4RootKeepsItsDirtyGenerationAndAllOldBytes() = runBlocking<Unit> {
        valid(true, CountTrustSeal.VALIDATOR)
    }
    @Test(timeout = 150_000) fun badOldMacCannotBecomeAMissOrAnEmptyPrimaryEnrollment() = runBlocking<Unit> {
        val access = access()
        source { oldCountRoot(it, access, dirty = true); it.execSQL("UPDATE count_trust_root SET mac=zeroblob(32) WHERE id=1") }
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        val failure = runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull()
        assertTrue(failure is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
        assertEquals("EMPTY", CountCommandNativeRows(current.openHelper.writableDatabase) {}.root().mode)
    }
    @Test(timeout = 150_000) fun authenticatedDirtyHeaderDoesNotHideCorruptOldReachableNode() = runBlocking<Unit> {
        val access = access()
        source { sql -> val root = oldCountRoot(sql, access, dirty = true)
            sql.execSQL("UPDATE count_trust_nodes SET body=X'00' WHERE digest=?", arrayOf(root.root.digest)) }
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        assertTrue(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull() is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }
    @Test(timeout = 150_000) fun missingOldKeystoreKeyIsNeverRecreatedByTheCompatibilityBridge() = runBlocking<Unit> {
        val access = access(); source { oldCountRoot(it, access, dirty = false) }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Fixed alias inside the already isolated testbed UID; never the production app.
        keys.deleteEntry("dayforge.count.trust.root.v1"); assertFalse(keys.containsAlias("dayforge.count.trust.root.v1"))
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        assertTrue(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull() is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
        assertFalse(keys.containsAlias("dayforge.count.trust.root.v1"))
    }
    @Test(timeout = 150_000) fun foreignAccountOldRootCannotEnrollIntoCurrentAuthenticatedCatalog() = runBlocking<Unit> {
        val currentAccess = access()
        val foreign = currentAccess.copy(session = currentAccess.session.copy(authentication = currentAccess.session.authentication.copy(userId = id(99))))
        source { oldCountRoot(it, foreign, dirty = true) }
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        assertTrue(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull() is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }
    @Test(timeout = 150_000) fun oldAuthenticatedRevisionOverflowCannotPartiallyAdvanceEitherRoot() = runBlocking<Unit> {
        val access = access()
        source { sql ->
            val old = oldCountRoot(sql, access, dirty = true)
            CountTrustSeal.store(sql, CountTrustSeal().prepare(old.binding, old.schemaHash, old.root, Long.MAX_VALUE, old.generation, old.validator))
        }
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        val failure = runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull()
        assertTrue(failure is CountCommandSchemaBridge.Invalid)
        assertTrue(generateSequence(failure) { it.cause }.any { it is ArithmeticException })
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }

}
