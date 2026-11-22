package com.dayforge.data.local

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Permanent facts about this physical owner. A later close never erases an unknown END. */
internal enum class CountTrustRetirementReason {
    RECOVERY_PREPARATION, RECOVERY_PREVIEW, ACCOUNT_CLEARED, CONNECTION_REPLACED,
    CONNECTION_CLOSED, CONNECTION_IDENTITY_UNKNOWN, UNKNOWN_BEGIN, UNKNOWN_END, TRANSACTION_STATE_UNKNOWN,
    UNOWNED_SCHEMA_CHANGE, JOURNAL_OR_EXTERNAL_CHANGE, CORRUPTION
}

/** Memory-only suppression of optional qualification, never business/authentication authority.
 * No SQL, file access, callbacks or suspensions occur under this registry monitor. A returned
 * preview owns one hold. Closing invalidates it immediately; in-flight work retains its own
 * reference until its original SQL/network cleanup has returned. Durable stage/fence gates
 * remain authoritative after handoff, after process death and on unknown operation results.
 */
internal class CountTrustRecoveryPreview internal constructor(
    private val owner: CountTrustRuntime, private val epoch: Long
) {
    internal data class Storage(val file: Any, val storageId: String)
    private var storage: Storage? = null
    // A null key after binding means the real file identity is UNKNOWN, not an in-memory DB.
    // Such a preview can finish its original workflow. Its independently read storageId also
    // suppresses known-path owners conservatively; no owner-local file identity is invented.
    private var bound = false
    private var boundStorageId: String? = null
    private var valid = true
    private var preparing = true
    private var operations = 1
    private var handed: String? = null

    val isValid: Boolean get() = synchronized(monitor) { valid }
    val isRetained: Boolean get() = synchronized(monitor) { bound && retained() }
    val handedRecoveryId: String? get() = synchronized(monitor) { handed }

    internal fun bind(actual: CountTrustRuntime, generation: Long, storageId: String, key: Storage?) = synchronized(monitor) {
        check(actual === owner && generation == epoch && preparing && valid && operations == 1) {
            "SYNC_RECOVERY_PREVIEW_CHANGED"
        }
        check((key == null || key.storageId == storageId) &&
            (!bound || storage == key && boundStorageId == storageId)) { "SYNC_RECOVERY_PREVIEW_CHANGED" }
        bound = true; storage = key; boundStorageId = storageId
        val entries = if (key == null) unknownFileHolds.getOrPut(storageId) { identitySet() }
            else holds.getOrPut(key) { identitySet() }
        entries.add(this)
        Unit
    }
    internal fun finishPreparation(delivered: Boolean) {
        synchronized(monitor) {
            check(preparing && operations == 1 && (!delivered || bound))
            preparing = false; operations--
            if (!delivered) valid = false
            releaseIfFinished()
        }
        owner.qualificationReleased()
    }
    /** Revocation does not cancel or repeat an owned storage transition. */
    fun close() {
        synchronized(monitor) { valid = false; releaseIfFinished() }
        owner.qualificationReleased()
    }
    internal suspend fun <T> use(actual: CountTrustRuntime, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        synchronized(monitor) {
            check(actual === owner && actual.generation == epoch && !preparing && valid && bound) {
                "SYNC_RECOVERY_PREVIEW_CHANGED"
            }
            operations = Math.addExact(operations, 1)
        }
        try { return block() } finally {
            synchronized(monitor) { operations--; check(operations >= 0); releaseIfFinished() }
            owner.qualificationReleased()
        }
    }
    /** Called only after the original StageStore.begin returns from its actual transaction.
     * The caller is still pinned through freeze/archive and their real cleanup. This id is
     * diagnostic ownership, not persisted consent or permission to release a durable stage.
     */
    internal fun handoff(recoveryId: String) = synchronized(monitor) {
        check(!preparing && operations > 0 && bound && (handed == null || handed == recoveryId)) {
            "SYNC_RECOVERY_PREVIEW_CHANGED"
        }
        handed = recoveryId; valid = false
        releaseIfFinished()
    }
    private fun retained() = valid || operations > 0
    private fun releaseIfFinished() {
        if (retained()) return
        if (!bound) return
        val key = storage
        if (key == null) {
            val id = requireNotNull(boundStorageId)
            unknownFileHolds[id]?.let { values ->
                values.remove(this)
                if (values.isEmpty()) unknownFileHolds.remove(id)
            }
        } else holds[key]?.let { values ->
            values.remove(this)
            if (values.isEmpty()) holds.remove(key)
        }
    }
    companion object {
        private val monitor = Any()
        private val holds = mutableMapOf<Storage, MutableSet<CountTrustRecoveryPreview>>()
        private val unknownFileHolds = mutableMapOf<String, MutableSet<CountTrustRecoveryPreview>>()
        private fun identitySet(): MutableSet<CountTrustRecoveryPreview> =
            Collections.newSetFromMap(IdentityHashMap<CountTrustRecoveryPreview, Boolean>())
        internal fun suppressed(key: Storage): Boolean = synchronized(monitor) {
            holds[key]?.isNotEmpty() == true || unknownFileHolds[key.storageId]?.isNotEmpty() == true
        }
    }
}
