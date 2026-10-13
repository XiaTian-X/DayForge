package com.dayforge.data.repository

import com.dayforge.data.api.*
import com.dayforge.data.appearance.AccountIconHttp
import com.dayforge.data.local.AuthenticationSession
import com.dayforge.data.local.ReplicaLoginWitness
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** Explicit-action transport owner. No normal endpoint writes, login adoption, sync or activation. */
@Singleton
internal class ReplicaRecoveryConnection @Inject constructor(private val endpoints: EndpointResolver,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator,
    private val shared: OkHttpClient, private val businessHttp: NextSyncHttp, private val materialHttp: AccountIconHttp) {

    suspend fun reauthenticate(username: String, password: String): AuthenticationSession = withContext(Dispatchers.IO) {
        val witness = sessions.exclusive { tokens.replicaLoginWitness() }
        val endpoint = resolve(witness)
        tokens.requireReplicaLoginWitness(witness)
        ReplicaRecoveryHttp(shared, endpoint.route).use { login ->
            suspend fun verifyIdentity() {
                val identity = login.identity()
                check(identity.serverInstanceId == endpoint.identity.serverInstanceId &&
                    identity.syncEpoch == endpoint.identity.syncEpoch && identity.protocolVersion == 5) {
                    "SYNC_RECOVERY_ENDPOINT_CHANGED"
                }
                tokens.requireReplicaLoginWitness(witness)
            }
            verifyIdentity() // Public proof BEFORE sending the explicitly supplied password.
            val reply = login.login(username, password)
            check(reply.userId == witness.accountId) { "SYNC_RECOVERY_LOGIN_ACCOUNT_MISMATCH" }
            verifyIdentity() // Restore/account changes while login was in flight cannot publish credentials.
            sessions.exclusive {
                tokens.saveReplicaLogin(witness, reply.accessToken, reply.refreshToken, reply.username, reply.userId, reply.isAdmin)
            }
        }
    }

    suspend fun <T> use(block: suspend (ConnectedReplicaRecovery) -> T): T {
        val witness = sessions.exclusive { tokens.replicaLoginWitness() }
        val endpoint = resolve(witness)
        tokens.requireReplicaLoginWitness(witness)
        val owner = requireNotNull(currentCoroutineContext()[Job])
        val connection = ConnectedReplicaRecovery(endpoint.identity.syncEpoch, owner, businessHttp, materialHttp, endpoint.route)
        try { return block(connection) } finally { connection.close() }
    }

    private suspend fun resolve(witness: ReplicaLoginWitness): ReplicaRecoveryEndpoint {
        val endpoint = endpoints.resolveReplicaRecovery(witness.serverInstanceId, witness.destinationEpoch)
        check(witness.destinationEpoch != null || endpoint.identity.syncEpoch != witness.currentEpoch) {
            "SYNC_RECOVERY_NOT_REQUIRED"
        }
        return endpoint
    }
}

/** Lifetime only, never a substitute for exact per-request account/SQL/archive/material proof. */
internal class ConnectedReplicaRecovery internal constructor(val epoch: String, private val owner: Job,
    business: NextSyncHttp, material: AccountIconHttp, route: MaterialHttpRoute) {
    @Volatile private var open = true
    private suspend fun requireOpen() {
        currentCoroutineContext().ensureActive()
        check(open && owner.isActive) { "SYNC_RECOVERY_CONNECTION_CLOSED" }
    }
    private val fixedRoute = route.forReplicaRecovery()
    val businessHttp = business.onReplicaRoute(fixedRoute, ::requireOpen)
    val materialHttp = material.onReplicaRoute(fixedRoute, ::requireOpen)
    internal fun close() { open = false }
}
