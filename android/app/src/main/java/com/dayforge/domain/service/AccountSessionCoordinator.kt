package com.dayforge.domain.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes sync, account switching, and logout so account-owned data cannot cross sessions. */
@Singleton
class AccountSessionCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
