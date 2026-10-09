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

/**
 * Account-scoped display publication, not business/action authority or a Room snapshot.
 * Readers keep their own account/Room boundaries. Preparation must not write display state;
 * its returned callback runs outside Room with the account lock held and must not reenter
 * repositories/the account lock, write business data, or start detached work.
 */
@Singleton
class WidgetDisplayPublisher @Inject constructor(
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator
) {
    private class Scope(val stamp: Any, val access: LocalCoreWriteAccess?)
    @Volatile private var blocked = false
    @Volatile private var stamp = Any()
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) {
            this@WidgetDisplayPublisher.blocked = blocked
            stamp = Any()
        }
    }
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
    ): Boolean {
        val scope = sessions.exclusive {
            val captured = stamp
            val access = tokens.localCoreWriteAccess()
            if (blocked || captured !== stamp) null else Scope(captured, access)
        } ?: return false
        val display = try {
            prepare()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            publish(scope) { onReadFailure(error) }
            return false
        }
        return publish(scope, display)
    }

    private suspend fun publish(scope: Scope, display: suspend () -> Unit): Boolean = sessions.exclusive {
        val access = tokens.localCoreWriteAccess()
        if (blocked || scope.stamp !== stamp || access != scope.access) return@exclusive false
        currentCoroutineContext().ensureActive()
        display()
        true
    }
}
