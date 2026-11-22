package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Schema/type proof only; does not validate business/source/material closure or authorize restore. */
internal class ReplicaSchemaProof internal constructor(val original: LocalSyncAccess, val storageHash: String) {
    val activationAuthorized: Boolean get() = false
}

/** Compare with this build's completed private Room reference, not a hand-maintained schema.
 * Only the fixed expected metadata is reused; actual storage remains freshly SELECT-validated.
 */
internal class NextReplicaSchemaValidator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    suspend fun validate(original: LocalSyncAccess, expectedStorageHash: String): ReplicaSchemaProof = withContext(Dispatchers.IO) {
        require(expectedStorageHash.matches(Regex("[0-9a-f]{64}")))
        sessions.exclusive {
            authorize(original)
            // The first reference must have ended and closed successfully before it can be
            // reused. A later actual-source failure does not invalidate this fixed template.
            val expected = ReplicaBusinessReferenceSchema.read(context)
            database.withTransaction {
                validateInTransaction(original, expectedStorageHash, expected, null)
            }
        }
    }

    /** Only the local pipeline borrows this owned continuous read. The expected reference
     * is constructed/closed before entering the primary transaction and cannot be supplied
     * by a caller. Existing independent validation keeps its original transaction below.
     */
    internal suspend fun validateAndReadLocal(original: LocalSyncAccess, expectedStorageHash: String,
        pipeline: NextReplicaLocalProofPipeline): ReplicaBusinessProof = withContext(Dispatchers.IO) {
        pipeline.requireBinding(database, tokens, sessions)
        require(expectedStorageHash.matches(Regex("[0-9a-f]{64}")))
        sessions.exclusive {
            authorize(original)
            val expected = ReplicaBusinessReferenceSchema.read(context)
            database.withTransaction {
                ReplicaStorageSnapshot.inspect(database) { captured ->
                    val observed = requireNotNull(captured) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }
                    val schema = validateInTransaction(original, expectedStorageHash, expected, observed)
                    pipeline.readInSnapshot(schema, observed)
                }
            }
        }
    }

    private suspend fun validateInTransaction(original: LocalSyncAccess, expectedStorageHash: String,
        expected: ReplicaReferenceSchema, observed: ReplicaStorageSnapshot?): ReplicaSchemaProof {
        observed?.requireCurrent(database)
        val sql = database.openHelper.writableDatabase
        val actual = ReplicaSqlSchema.read(sql)
        require(ApprovedBusinessSchema.matches(actual, expected.objects)) { "SYNC_RECOVERY_SCHEMA_INVALID" }
        require(sql.version == expected.version) { "SYNC_RECOVERY_SCHEMA_INVALID" }
        sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING && it.getString(1) == expected.roomIdentity && !it.moveToNext()) {
                "SYNC_RECOVERY_SCHEMA_INVALID"
            }
        }
        for (table in actual.values.filter { it.type == "table" }) {
            currentCoroutineContext().ensureActive()
            ReplicaSqlSchema.verifyTypes(sql, table.name)
        }
        sql.query("PRAGMA integrity_check").use {
            require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) == "ok" && !it.moveToNext()) {
                "SYNC_RECOVERY_STORAGE_INVALID"
            }
        }
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "SYNC_RECOVERY_FOREIGN_KEY_INVALID" } }
        val proof = observed?.storage(database) ?: NextReplicaStorageProof(database).readInTransaction()
        require(proof.hash == expectedStorageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        authorize(original)
        return ReplicaSchemaProof(original.copy(capabilities = original.capabilities.toSet()), proof.hash)
    }

    private suspend fun authorize(original: LocalSyncAccess) {
        check(tokens.isCurrentSyncAuthority(original)) { "SYNC_ACCOUNT_CHANGED" }
    }

}
