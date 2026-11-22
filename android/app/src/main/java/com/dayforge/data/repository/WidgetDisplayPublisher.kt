package com.dayforge.data.repository

import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Account-scoped display publication, not business/action authority or a Room snapshot.
 * Readers keep their own account/Room boundaries. Preparation must not write display state;
 * its returned callback runs outside Room with the account lock held and must not reenter
 * repositories/the account lock, write business data, or start detached work.
 */
@Singleton
class WidgetDisplayPublisher private constructor(
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator,
    private val fixedReplica: ReplicaPresentationRepository?,
    private val host: com.dayforge.data.local.DatabaseRuntimeHost?
) {
    constructor(tokens: TokenManager, sessions: AccountSessionCoordinator, replica: ReplicaPresentationRepository) :
        this(tokens, sessions, replica, null)
    @Inject internal constructor(tokens: TokenManager, sessions: AccountSessionCoordinator,
        host: com.dayforge.data.local.DatabaseRuntimeHost) : this(tokens, sessions, null, host)
    private val replica get() = fixedReplica ?: requireNotNull(host).boundGraph().replicaPresentationRepository
    private suspend fun <T> withGraph(block: suspend () -> T): T =
        if (host == null) block() else host.withGraph { block() }
    private class Scope(val stamp: Any, val access: LocalCoreWriteAccess?,
        val epoch: com.dayforge.data.local.DatabaseEpochPublication?, val storageId: String?)
    internal class Publication(
        private val guardedPublish: suspend (suspend () -> Unit) -> Boolean,
        private val current: suspend () -> Boolean,
        private val legacyRestart: (com.dayforge.data.local.entity.HabitEntity) -> LegacyWidgetRestartClaim?,
        val frameOwner: String?,
        val session: com.dayforge.data.local.LocalDataSession?,
        private val memoryCurrent: () -> Boolean,
        private val withReadGraph: suspend (suspend () -> Boolean) -> Boolean
    ) {
        /** Display provenance only, never command authority. Stamp WITH the full frame transaction. */
        fun stampFrame(preferences: MutablePreferences) {
            frameOwner?.let { preferences[FRAME_OWNER_KEY] = it } ?: preferences.remove(FRAME_OWNER_KEY)
        }
        fun ownsFrame(preferences: Preferences): Boolean = frameOwner != null && preferences[FRAME_OWNER_KEY] == frameOwner
        fun isCurrentInMemory(): Boolean = memoryCurrent()
        suspend operator fun invoke(display: suspend () -> Unit): Boolean = guardedPublish(display)
        suspend fun renderPrepared(onReadFailure: suspend (Exception) -> Unit,
            prepare: suspend () -> (suspend () -> Unit)): Boolean = withReadGraph {
            val display = try { prepare() } catch (error: Exception) {
                if (error is CancellationException) throw error
                invoke { onReadFailure(error) }
                return@withReadGraph false
            }
            invoke(display)
        }
        internal suspend fun inOriginalReadGraph(body: suspend () -> Boolean): Boolean = withReadGraph(body)
        /** Only a writer ALREADY holding the shared account lock may use this read-only check. */
        suspend fun isCurrentWhileAccountLocked(): Boolean = current()
        /** Pure display claim from the original pre-read scope; publication still verifies that scope. */
        fun legacyRestartClaim(habit: com.dayforge.data.local.entity.HabitEntity): LegacyWidgetRestartClaim? = legacyRestart(habit)
    }
    @Volatile private var blocked = false
    @Volatile private var graphBlocked = false
    @Volatile private var stamp = Any()
    private val mutableInvalidations = MutableStateFlow(stamp)
    internal val invalidations = mutableInvalidations.asStateFlow()
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) {
            this@WidgetDisplayPublisher.blocked = blocked
            stamp = Any()
            mutableInvalidations.value = stamp
        }
    }
    private val graphListener = object : com.dayforge.data.local.DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            graphBlocked = blocked; stamp = Any(); mutableInvalidations.value = stamp
        }
    }
    private val graphRegistration = host?.observeMemory(graphListener)
    init { tokens.registerIconCache(invalidator) }

    /**
     * A discarded old read (including its error) must never replace a new account's state.
     * Nullable access preserves legacy reads; the synchronous stamp also rejects anonymous
     * -> login -> anonymous ABA. Typed readers still require their actual account authority.
     * Theme/display failures propagate to the caller's existing bounded retry, not read errors.
     */
    suspend fun renderPrepared(
        onReadFailure: suspend (Exception) -> Unit,
        prepare: suspend () -> (suspend () -> Unit)
    ): Boolean = withGraph {
        val publication = capturePublication() ?: return@withGraph false
        publication.renderPrepared(onReadFailure, prepare)
    }

    /**
     * Ephemeral display-only capability captured BEFORE any source read. Used where healthy
     * typed publication already owns a stronger writer boundary, or a display and its alarm
     * need the same original scope. Never invoke inside an already-held account lock.
     */
    internal suspend fun capturePublication(): Publication? = withGraph {
        val scope = sessions.exclusive {
            val captured = stamp
            val access = tokens.localCoreWriteAccess()
            if (blocked || graphBlocked || captured !== stamp || tokens.replicaSwitchPending()) null
            else replica.publicationSourceWhileAccountLocked()?.let { source ->
                Scope(captured, access, host?.currentPublication(), source.storageId)
            }
        } ?: return@withGraph null
        Publication({ display -> publish(scope, display) }, { isCurrent(scope) }, { habit ->
            if (habit.appearance != null) null else scope.access?.let { LegacyWidgetRestartClaim.displayed(habit, it, scope.storageId) }
        }, frameOwner(scope.access, scope.storageId), scope.access?.session,
            { !blocked && !graphBlocked && scope.stamp === stamp && scope.epoch?.isCurrentInMemory() != false },
            { body -> withGraph { if (scope.epoch?.isCurrentInMemory() == false) false else body() } })
    }

    private suspend fun publish(scope: Scope, display: suspend () -> Unit): Boolean = withGraph {
        sessions.exclusive {
            if (!isCurrent(scope)) return@exclusive false
            currentCoroutineContext().ensureActive()
            display()
            true
        }
    }

    private suspend fun isCurrent(scope: Scope): Boolean {
        val access = tokens.localCoreWriteAccess()
        return !blocked && !graphBlocked && scope.epoch?.isCurrentInMemory() != false &&
            !tokens.replicaSwitchPending() && scope.stamp === stamp && access == scope.access &&
            replica.publicationSourceWhileAccountLocked()?.let { it.storageId == scope.storageId } == true
    }

    internal companion object {
        val FRAME_OWNER_KEY = stringPreferencesKey("dayforgeFrameOwnerV1")

        /** Stable across process death/cursor/token refresh; different login, replica or permissions cannot inherit a frame. */
        private fun frameOwner(access: LocalCoreWriteAccess?, storageId: String?): String? {
            access ?: return null // Anonymous state can never bless persisted personal content.
            val session = access.session
            val fields = listOf(session.authentication.userId, session.authentication.generation,
                session.serverInstanceId, session.syncEpoch, access.capturedDeviceId,
                access.capabilities?.sorted()?.joinToString("\n"), storageId)
            val value = buildString {
                append("widget-frame-v2;")
                fields.forEach { field -> if (field == null) append("-1:") else { append(field.length); append(':'); append(field) } }
            }
            return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
