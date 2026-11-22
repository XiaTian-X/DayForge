package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** G0 STRICT metric-block pipeline. Independent structure merge; all other original
 * validators and fresh authentication remain. Full captures remain mandatory without a
 * sealed domain SQL boundary. Not proof of bounded incremental cost or a shorter account lock.
 * The broad permit is a safety baseline until a prepared/final bridge can be proven equivalent.
 * HTTP preparation/delivery is outside this bridge; only an already delivered ACK enters it.
 */
internal class G0MetricBlockRuntime(
    private val db: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val authority: G0AuthorityOwner,
    val scheduler: G0CommitScheduler,
    private val faultContext: CoroutineContext = EmptyCoroutineContext,
    private val blocks: G0MetricBlocks
) {
    private val poisoned = AtomicBoolean()
    private var boundSession: LocalDataSession? = null

    private suspend fun <T> commit(kind: G0CommitScheduler.Kind, session: LocalDataSession, block: suspend () -> T): T {
        check(!poisoned.get()) { "G0_DOMAIN_RECOVERY_REQUIRED" }
        val lease = authority.lease()
        return scheduler.run(kind) {
            check(!poisoned.get()) { "G0_DOMAIN_RECOVERY_REQUIRED" }
            authority.checkLease(lease)
            val permit = authority.finalPermit(lease)
            try {
                require(boundSession == null || boundSession == session) { "G0_BLOCK_SCOPE_CHANGED" }
                boundSession = session
                blocks.begin()
                withContext(faultContext) { block() }.also { authority.checkLease(lease); blocks.committed() }
            } catch (failure: Throwable) {
                // An exception is not evidence of rollback. Keep admission closed even when
                // the engine committed and only its result was lost. No automatic replay here.
                blocks.discard()
                poisoned.set(true)
                scheduler.quarantine()
                throw failure
            } finally { permit.close() }
        }
    }

    suspend fun <T> write(session: LocalDataSession, body: suspend () -> T): T =
        commit(G0CommitScheduler.Kind.WRITE, session) { G0MetricBlockLocalProbe(db, tokens, sessions, blocks).write(session, body) }

    suspend fun acknowledge(input: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance {
        // Freeze all collection-backed response data before queuing. Caller mutation must not
        // change the operation, receipt or round metadata while waiting for admission.
        val context = currentCoroutineContext()
        val access = input.access.copy(capabilities = input.access.capabilities.toSet())
        val responseBytes = capture(NextSyncPushResponse.serializer(), input.result) { context.ensureActive() }
        val metadataBytes = input.challengeMetadata?.let { capture(ChallengeMetadata.serializer(), it) { context.ensureActive() } }
        val frozen = input.copy(access = access,
            result = decodeFrozenSyncRequest(responseBytes, NextSyncPushResponse.serializer()),
            challengeMetadata = metadataBytes?.let { decodeFrozenSyncRequest(it, ChallengeMetadata.serializer()) })
        return commit(G0CommitScheduler.Kind.ACK, frozen.access.session) {
            G0MetricBlockAckProbe(db, tokens, sessions, blocks).acceptOperation(frozen)
        }
    }

    /** Test-only computed-result observer, not the production object/home reader replacement. */
    suspend fun read(access: com.dayforge.data.local.LocalSyncAccess): G0MetricBlocks.View {
        val lease = authority.lease()
        return scheduler.run(G0CommitScheduler.Kind.READ) {
            authority.checkLease(lease)
            val permit = authority.finalPermit(lease)
            try {
                require(boundSession == access.session) { "G0_BLOCK_SCOPE_CHANGED" }
                require(tokens.isCurrentSyncAuthority(access) && "sync.read" in access.capabilities)
                val value = db.withTransaction { blocks.readStrict() }
                require(tokens.isCurrentSyncAuthority(access))
                authority.checkLease(lease)
                value
            } finally { permit.close() }
        }
    }

    // Unlike the suspending production encoder, this must own the bytes BEFORE the first
    // dispatcher hop. The same hard byte bound applies; no SQL/auth resource is held here.
    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> capture(serializer: KSerializer<T>, body: T, active: () -> Unit): ByteArray {
        val output = object : ByteArrayOutputStream(8192) {
            override fun write(value: Int) { active(); require(size() < SYNC_REQUEST_LIMIT); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                active(); require(length <= SYNC_REQUEST_LIMIT - size()); super.write(bytes, offset, length)
            }
        }
        return output.use { captureJson.encodeToStream(serializer, body, it); it.toByteArray() }
    }
    private companion object { val captureJson = Json { encodeDefaults = true } }
}
