package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.api.dto.ReplicaRecoveryPreviewResponse
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.local.*
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import okhttp3.Request

/** Short-lived GET-only destination proof, not an ordinary Session or release permission.
 * The factory observes actual SQL, the complete private archive and published destination.
 * No caller can create it from an epoch, cached DTO or phase name alone.
 */
internal class ReplicaDestinationReadAccess private constructor(private val tokens: TokenManager,
    private val stage: ReplicaSwitchStage, val access: LocalSyncAccess, private val owner: Job) {
    @Volatile private var open = true
    val installationId: String get() = stage.installationId

    internal suspend fun authorize(values: TokenManager) {
        check(open && owner.isActive && values === tokens) { "SYNC_RECOVERY_READ_CLOSED" }
        check(tokens.replicaDestinationAccess(stage) == access && tokens.syncAuthenticationSnapshot(access) != null) {
            "SYNC_RECOVERY_READ_AUTHORITY_CHANGED"
        }
        check(open && owner.isActive) { "SYNC_RECOVERY_READ_CLOSED" }
    }

    internal suspend fun requireOwner() {
        check(currentCoroutineContext()[Job] === owner) { "SYNC_RECOVERY_READ_OWNER_CHANGED" }
        authorize(tokens)
    }

    /** Called again before authentication exemptions and at the network boundary. */
    internal suspend fun authorizeRequest(values: TokenManager, request: Request) {
        authorize(values)
        val route = request.tag(MaterialHttpRoute::class.java)
        if (route == null || !route.matches(request.url) || request.method != "GET" || request.body != null ||
            request.tag(ReplicaDestinationReadAccess::class.java) !== this ||
            request.tag(LocalSyncAccess::class.java) != access || request.tag(LocalIconAccess::class.java) != null ||
            request.tag(AuthenticationSession::class.java) != access.session.authentication)
            throw IOException("SYNC_RECOVERY_READ_ONLY")
        val query = request.url
        if (query.encodedPath == MaterialHttpRoute.IDENTITY_PATH) {
            if (query.queryParameterNames.isNotEmpty()) throw IOException("SYNC_RECOVERY_READ_QUERY_INVALID")
            return
        }
        val expected = mapOf("device_id" to requireNotNull(access.deviceId), "challenge_contract" to "1",
            "installation_id" to installationId)
        if (query.encodedPath != "/api/v2/sync/rounds/recovery-preview" ||
            request.header("X-DayForge-Protocol") != "5" ||
            request.header("X-DayForge-Server-Instance") != access.session.serverInstanceId ||
            request.header("X-DayForge-Sync-Epoch") != access.session.syncEpoch ||
            query.queryParameterNames != expected.keys || expected.any { (key, value) -> query.queryParameterValues(key) != listOf(value) })
            throw IOException("SYNC_RECOVERY_READ_ONLY")
    }

    suspend fun read(http: NextSyncHttp): ReplicaRecoveryPreviewResponse {
        requireOwner()
        return http.destinationPreview(this).also { authorize(tokens) }
    }

    companion object {
        /** The owning workflow must serialize with the shared business sync coordinator.
         * Nothing in this callback may mutate the frozen business SQL or release its gates.
         */
        suspend fun <T> verified(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
            archives: ReplicaPrivateArchiveStore, stage: ReplicaSwitchStage,
            block: suspend (ReplicaDestinationReadAccess) -> T): T {
            require(stage.destinationPending)
            val commits = NextReplicaCommitStore(database, tokens, sessions)
            val controls = ReplicaPresentationRepository(database, tokens, sessions)
            suspend fun verifySql() {
                check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
                sessions.exclusive { database.withTransaction {
                    // The typed DAO can coerce a damaged REAL into a Long. Reuse the actual
                    // raw control reader in the SAME transaction as the full business fate.
                    val control = controls.inspectWhileAccountLocked()
                    check(control.problem == ReplicaPresentationProblem.RECOVERY_PENDING && control.phase == stage.phase) {
                        "SYNC_REPLICA_STAGE_CHANGED"
                    }
                    if (stage.phase == ReplicaSwitchPhase.CATCHUP_COMMITTING)
                        check(commits.catchupFateInTransaction(stage) == ReplicaCatchupFate.PREVIOUS) { "SYNC_REPLICA_ALREADY_COMMITTED" }
                    else if (stage.phase == ReplicaSwitchPhase.RELEASING) commits.releaseFateInTransaction(stage)
                    else check(commits.fateInTransaction(stage) == ReplicaCommitFate.COMMITTED) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
                } }
            }
            val saved = requireNotNull(stage.archive)
            val receipt = ReplicaArchiveReceipt(AccountIconNamespace(stage.accountId, stage.serverInstanceId, stage.oldEpoch),
                saved.archiveId, saved.inputHash, saved.byteLength, saved.contentHash,
                saved.entries.map { ReplicaArchiveEntry(it.name, it.byteLength, it.contentHash) })
            verifySql()
            archives.verify(receipt) { tokens.replicaDestinationAccess(stage) }
            val access = tokens.replicaDestinationAccess(stage).let { it.copy(capabilities = it.capabilities.toSet()) }
            val lease = ReplicaDestinationReadAccess(tokens, stage, access, requireNotNull(currentCoroutineContext()[Job]))
            try {
                lease.authorize(tokens)
                val result = block(lease)
                lease.authorize(tokens)
                verifySql()
                archives.verify(receipt) { lease.authorize(tokens) }
                return result
            } finally { lease.open = false }
        }
    }
}

/** A changed server snapshot is evidence for the still-required catch-up stage, not a signal
 * to replace rows here. Even an unchanged snapshot cannot authorize release by itself.
 */
internal class ReplicaDestinationPreview internal constructor(val candidate: ValidatedReplicaCandidate,
    val matchesCommittedSnapshot: Boolean) {
    val activationAuthorized: Boolean get() = false
}
