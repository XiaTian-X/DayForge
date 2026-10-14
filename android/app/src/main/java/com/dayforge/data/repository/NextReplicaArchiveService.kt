package com.dayforge.data.repository

import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No UI, active data mutation, protocol stage transition, publication fence or switch authority. */
internal class NextReplicaArchiveService(private val reader: NextReplicaArchiveInputReader,
    private val files: ReplicaPrivateArchiveStore, private val tokens: TokenManager) {
    suspend fun create(original: LocalSyncAccess, storageHash: String): ReplicaArchiveReceipt =
        reader.inspect(original, storageHash) { input, access ->
            files.create(input, access) { authorize(original) }
        }

    /** Only the exact authenticated old namespace; post-COMMIT recovery needs its explicit stage. */
    suspend fun verify(original: LocalSyncAccess, receipt: ReplicaArchiveReceipt) {
        authorize(original)
        require(receipt.namespace == AccountIconNamespace(original.session.authentication.userId,
            requireNotNull(original.session.serverInstanceId), requireNotNull(original.session.syncEpoch))) { "SYNC_ARCHIVE_SCOPE_CHANGED" }
        files.verify(receipt) { authorize(original) }
    }

    private suspend fun authorize(original: LocalSyncAccess) {
        currentCoroutineContext().ensureActive()
        check(tokens.isCurrentSyncAuthority(original)) { "SYNC_ACCOUNT_CHANGED" }
    }
}
