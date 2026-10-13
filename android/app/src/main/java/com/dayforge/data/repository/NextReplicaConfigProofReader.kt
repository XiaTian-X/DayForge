package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Complete retained configuration JOURNAL proof, not source bundle/material/archive authority. */
internal class ReplicaConfigProof internal constructor(val requests: ReplicaRequestProof, journals: List<ReplicaConfigJournal>) {
    val journals: List<ReplicaConfigJournal> = Collections.unmodifiableList(journals.toList())
    val hasUnsettledWork: Boolean get() = journals.any { it.hasUnsettledWork }
    val activationAuthorized: Boolean get() = false
}

internal class NextReplicaConfigProofReader(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    suspend fun read(requests: ReplicaRequestProof): ReplicaConfigProof = withContext(Dispatchers.IO) {
        sessions.exclusive { database.withTransaction {
            fun unchanged(hash: String) = require(hash == requests.storageHash) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
            check(tokens.isCurrentSyncAuthority(requests.original)) {
                "SYNC_ACCOUNT_CHANGED"
            }
            unchanged(NextReplicaStorageProof(database).readInTransaction().hash)
            val journals = NextConfigImportStore(database).recoveryInTransaction(requests.original, requests.states)
            unchanged(NextReplicaStorageProof(database).readInTransaction().hash)
            check(tokens.isCurrentSyncAuthority(requests.original)) {
                "SYNC_ACCOUNT_CHANGED"
            }
            ReplicaConfigProof(requests, journals)
        } }
    }
}
