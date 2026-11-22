package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Four complete local participants share only a single actual no-write transaction.
 * The validated entry includes schema checks in that same read; read(schema) keeps its
 * independent entry. File/network work and the following business
 * revalidation occur after it has ended and must obtain fresh native contents again.
 * Every participant keeps its original entry/final authentication and domain proof.
 */
internal class NextReplicaLocalProofPipeline(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager, private val http: NextSyncHttp) {
    /** Purpose-specific schema + local proof entry. No expected schema, stored contents,
     * authority grant or observation can be supplied/reused by the caller.
     */
    suspend fun readValidated(context: Context, original: LocalSyncAccess,
        expectedStorageHash: String): ReplicaBusinessProof {
        val validator = NextReplicaSchemaValidator(context, database, tokens, sessions)
        if (CountTrustRuntime.forDatabase(database) == null)
            return read(validator.validate(original, expectedStorageHash))
        return validator.validateAndReadLocal(original, expectedStorageHash, this)
    }

    internal fun requireBinding(database: HabitDatabase, tokens: TokenManager,
        sessions: AccountSessionCoordinator) {
        check(this.database === database && this.tokens === tokens && this.sessions === sessions) {
            "SYNC_REPLICA_PIPELINE_BINDING_INVALID"
        }
    }

    /** Called only after schema validation in that same opaque observation. Every original
     * participant still owns its entry/final fresh authorization and complete domain checks.
     */
    internal suspend fun readInSnapshot(schema: ReplicaSchemaProof,
        observed: ReplicaStorageSnapshot): ReplicaBusinessProof {
        observed.requireCurrent(database)
        val requests = NextReplicaRequestProofReader(database, tokens, sessions, preferences, http)
        val configuration = NextReplicaConfigProofReader(database, tokens, sessions)
        val facts = NextReplicaLocalFactProofReader(database, tokens, sessions)
        val business = NextReplicaBusinessProofReader(database, tokens, sessions, preferences, http)
        val requestProof = requests.readInSnapshot(schema, observed)
        val configProof = configuration.readInSnapshot(requestProof, observed)
        val localProof = facts.readInSnapshot(configProof, observed)
        return business.readInSnapshot(localProof, observed)
    }

    suspend fun read(schema: ReplicaSchemaProof): ReplicaBusinessProof = withContext(Dispatchers.IO) {
        // Without native write exclusion retain all independent original reads and locks.
        // Neither an old hash nor a caller-supplied raw proof can enable reuse.
        if (CountTrustRuntime.forDatabase(database) == null) {
            val requests = NextReplicaRequestProofReader(database, tokens, sessions, preferences, http)
            val configuration = NextReplicaConfigProofReader(database, tokens, sessions)
            val facts = NextReplicaLocalFactProofReader(database, tokens, sessions)
            val business = NextReplicaBusinessProofReader(database, tokens, sessions, preferences, http)
            return@withContext business.read(facts.read(configuration.read(requests.read(schema))))
        }
        sessions.exclusive { database.withTransaction {
            ReplicaStorageSnapshot.inspect(database) { captured ->
                val observed = requireNotNull(captured) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }
                readInSnapshot(schema, observed)
            }
        } }
    }
}
