package com.dayforge.data.appearance

import androidx.room.withTransaction
import com.dayforge.data.api.MaterialHttpRoute
import com.dayforge.data.local.*
import com.dayforge.data.repository.*
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request

/** Scoped READ/download plus archived exact local selection recovery, never an ordinary
 * writer/publication override. Network authority remains strictly authenticated GET-only.
 * Only the actual destination SQL/archive verification factory creates it. It stays bound to
 * one live owner and one fresh authentication generation and closes after all owned IO joins.
 */
internal class ReplicaMaterialAccess private constructor(private val tokens: TokenManager,
    private val stage: ReplicaSwitchStage, val access: LocalIconAccess, private val owner: Job,
    val intent: ReplicaPackIntent) {
    @Volatile private var open = true

    suspend fun authorize(values: TokenManager) {
        check(open && owner.isActive && values === tokens) { "ICON_RECOVERY_ACCESS_CLOSED" }
        val actual = tokens.replicaDestinationAccess(stage)
        check(actual.session == access.session && actual.deviceId == access.deviceId &&
            actual.capabilityRevision == access.capabilityRevision &&
            ("structure.write" in actual.capabilities) == access.canDeclare) { "ICON_RECOVERY_AUTHORITY_CHANGED" }
        check(open && owner.isActive) { "ICON_RECOVERY_ACCESS_CLOSED" }
    }

    /** Exact, credentialed GETs only. No query/header-only exception, redirect, registration,
     * refresh, upload, declaration or arbitrary path is authorized by this object.
     */
    suspend fun authorizeRequest(values: TokenManager, request: Request) {
        authorize(values)
        val route = request.tag(MaterialHttpRoute::class.java)
        val path = request.url.encodedPath
        if (route == null || !route.matches(request.url) || request.method != "GET" || request.body != null ||
            request.tag(LocalIconAccess::class.java) != access || request.tag(LocalSyncAccess::class.java) != null ||
            request.tag(AuthenticationSession::class.java) != access.session.authentication ||
            request.header("X-DayForge-Protocol") != "5") throw IOException("MATERIAL_RECOVERY_READ_ONLY")
        val keys = request.url.queryParameterNames
        if (keys.any { request.url.queryParameterValues(it).size != 1 }) throw IOException("MATERIAL_RECOVERY_QUERY_INVALID")
        if (path == MaterialHttpRoute.IDENTITY_PATH) {
            if (keys.isNotEmpty()) throw IOException("MATERIAL_RECOVERY_QUERY_INVALID")
            return
        }
        val scope = setOf("server_instance_id", "sync_epoch", "device_id")
        if (request.url.queryParameter("server_instance_id") != stage.serverInstanceId ||
            request.url.queryParameter("sync_epoch") != stage.newEpoch ||
            request.url.queryParameter("device_id") != access.deviceId) throw IOException("MATERIAL_RECOVERY_SCOPE_CHANGED")
        if (path == "/api/v2/appearance/catalog") {
            if (keys - scope !in setOf(setOf("after", "limit"), setOf("after", "limit", "through")))
                throw IOException("MATERIAL_RECOVERY_QUERY_INVALID")
            for (name in keys - scope) {
                val raw = requireNotNull(request.url.queryParameter(name))
                val number = raw.toLongOrNull()
                if (number == null || raw != number.toString() || number < 0 ||
                    name == "limit" && number !in 1..AccountIconRemoteCatalog.PAGE_LIMIT.toLong() ||
                    name != "limit" && number > IconCatalogCheckpoint.MAX_ENTRIES)
                    throw IOException("MATERIAL_RECOVERY_QUERY_INVALID")
            }
        } else {
            val parts = path.split('/')
            if (keys != scope || parts.size != 8 || parts.take(5) != listOf("", "api", "v2", "appearance", "assets") ||
                !isContractUuid(parts[5]) || parts[6] != "content" || parts[7] !in setOf("light", "dark"))
                throw IOException("MATERIAL_RECOVERY_READ_ONLY")
        }
    }

    companion object {
        /** Owning recovery workflow holds the actual shared sync coordinator, not a generic
         * ordinary-write bypass callback. Business SQL and the pointer remain frozen throughout.
         */
        suspend fun <T> verified(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
            archives: ReplicaPrivateArchiveStore, stage: ReplicaSwitchStage,
            block: suspend (AccountIconContext) -> T): T {
            require(stage.phase in setOf(ReplicaSwitchPhase.DESTINATION_BOUND, ReplicaSwitchPhase.CATCHUP_BOUND))
            val commits = NextReplicaCommitStore(database, tokens, sessions)
            suspend fun verifySql() = sessions.exclusive { database.withTransaction {
                val control = ReplicaPresentationRepository(database, tokens, sessions).inspectWhileAccountLocked()
                check(control.problem == ReplicaPresentationProblem.RECOVERY_PENDING && control.phase == stage.phase &&
                    tokens.activeReplicaStage() == stage) { "SYNC_REPLICA_STAGE_CHANGED" }
                check(commits.fateInTransaction(stage) == ReplicaCommitFate.COMMITTED) { "SYNC_REPLICA_COMMIT_STATE_INVALID" }
            } }
            val saved = requireNotNull(stage.archive)
            val receipt = ReplicaArchiveReceipt(AccountIconNamespace(stage.accountId, stage.serverInstanceId, stage.oldEpoch),
                saved.archiveId, saved.inputHash, saved.byteLength, saved.contentHash,
                saved.entries.map { ReplicaArchiveEntry(it.name, it.byteLength, it.contentHash) })
            check(!database.inTransaction()) { "SYNC_REPLICA_AMBIENT_TRANSACTION" }
            verifySql()
            val intent = archives.iconIntent(receipt) { tokens.replicaDestinationAccess(stage) }
            val current = tokens.replicaDestinationAccess(stage)
            val access = LocalIconAccess(current.session, requireNotNull(current.deviceId),
                requireNotNull(current.capabilityRevision), "structure.write" in current.capabilities)
            val lease = ReplicaMaterialAccess(tokens, stage, access, requireNotNull(currentCoroutineContext()[Job]), intent)
            try {
                lease.authorize(tokens)
                val result = block(AccountIconContext(access, lease))
                lease.authorize(tokens)
                verifySql()
                archives.verify(receipt) { lease.authorize(tokens) }
                return result
            } finally { lease.open = false }
        }
    }
}
