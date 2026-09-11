package com.dayforge.domain.service

import com.dayforge.data.local.TokenManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StructuralEditDeniedException : IllegalStateException(
    "当前设备用于打卡、计数、指标和计时；请先在设置中将其设为主要编辑设备"
)

/** Cached server capability guard. Unknown capability means legacy-compatible access. */
@Singleton
class StructuralEditGuard @Inject constructor(
    private val tokenManager: TokenManager,
    private val syncManager: SyncManager
) {
    val allowed: Flow<Boolean> = tokenManager.canEditStructure
    private var lastRefreshAttemptAt = 0L
    private val refreshMutex = Mutex()

    suspend fun requireAllowed() {
        if (!allowed.first()) throw StructuralEditDeniedException()
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastRefreshAttemptAt >= REFRESH_COOLDOWN_MILLIS) {
                lastRefreshAttemptAt = now
                // A failed NAS/LAN request must not turn cached permission into an
                // online-only requirement. The durable outbox still protects the edit.
                syncManager.sync()
            }
        }
        // A successful refresh may have revoked this device's cached capability.
        if (!allowed.first()) throw StructuralEditDeniedException()
    }

    private companion object {
        const val REFRESH_COOLDOWN_MILLIS = 15_000L
    }
}
