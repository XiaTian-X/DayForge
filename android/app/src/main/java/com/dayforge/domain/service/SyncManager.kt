package com.dayforge.domain.service

import com.dayforge.data.model.SyncProgress
import com.dayforge.data.model.SyncProblemFrame
import kotlinx.coroutines.flow.merge
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.BusinessSyncRepository
import com.dayforge.data.repository.NextMaterialPending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Stable sync identity; each complete operation captures one physical dependency graph. */
@Singleton
class SyncManager private constructor(
    private val fixed: Fixed?,
    private val host: DatabaseRuntimeHost?,
    private val coordination: DatabaseSyncCoordination?,
    private val tokens: TokenManager?,
    private val preferences: PreferencesManager?
) {
    private class Fixed(val legacy: IncrementalSyncRepository, val business: BusinessSyncRepository)
    // Explicit fixed graph remains useful for isolated original protocol fixtures.
    constructor(syncRepository: IncrementalSyncRepository, businessSyncRepository: BusinessSyncRepository) :
        this(Fixed(syncRepository, businessSyncRepository), null, null, null, null)
    @Inject internal constructor(host: DatabaseRuntimeHost, coordination: DatabaseSyncCoordination,
        tokens: TokenManager, preferences: PreferencesManager) : this(null, host, coordination, tokens, preferences)
    private val syncRepository get() = fixed?.legacy ?: requireNotNull(host).boundGraph().incrementalSyncRepository
    private val businessSyncRepository get() = fixed?.business ?: requireNotNull(host).boundGraph().businessSyncRepository
    private val completions = coordination?.completed?.asStateFlow() ?: requireNotNull(fixed).business.completions
    private suspend fun <T> withDatabases(block: suspend () -> T): T =
        if (host == null) block() else host.withGraph { block() }
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    private data class MaterialWait(val session: LocalDataSession?, val after: Long)
    private val materialWait = MutableStateFlow<MaterialWait?>(null)
    val syncProgress: Flow<SyncProgress> = combine(_syncProgress.asStateFlow(), materialWait, completions) {
        progress, waiting, completion ->
        if (progress == SyncProgress.WaitingForMaterials && waiting?.session != null &&
            waiting.session == completion.session && completion.sequence > waiting.after) SyncProgress.Success else progress
    }
    internal fun problemChanges(): Flow<Unit> = host?.let { owner ->
        merge(owner.controlHints { it.businessSyncRepository.problemChanges() }, owner.controlChanges)
    } ?: requireNotNull(fixed).business.problemChanges()
    internal suspend fun readProblemFrame(): SyncProblemFrame {
        val owner = requireNotNull(host)
        return owner.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            SyncProblemFrame(businessSyncRepository.readProblems(), owner.currentPublication(), owner.currentControlPublication())
        }
    }
    private fun requireFixedProblemInput() { check(host == null) { "SYNC_PROBLEM_ORIGINAL_INPUT_REQUIRED" } }
    private suspend fun <T> withProblemSource(frame: SyncProblemFrame, recovery: Boolean = false,
        block: suspend () -> T): T = if (host == null) block()
        else if (recovery) host.withControlSourceGraph(frame.control) { block() }
        else host.withSourceGraph(frame.business) { block() }
    internal suspend fun readProblems() = withDatabases { businessSyncRepository.readProblems() }
    internal suspend fun readRecoveryPreview(root: com.dayforge.data.repository.NextRecoveryKey) = run {
        requireFixedProblemInput()
        withDatabases { businessSyncRepository.readRecoveryPreview(root) }
    }
    internal suspend fun prepareRecoveryConfirmation(preview: com.dayforge.data.repository.NextRecoveryDependencyPreview,
        selected: Set<com.dayforge.data.repository.NextRecoveryKey>) = run {
        requireFixedProblemInput()
        withDatabases { businessSyncRepository.prepareRecoveryConfirmation(preview, selected) }
    }
    internal suspend fun confirmRecovery(ticket: com.dayforge.data.repository.NextRecoveryConfirmation) = run {
        requireFixedProblemInput()
        withDatabases { businessSyncRepository.confirmRecovery(ticket) }
    }
    internal suspend fun readRecoveryPreview(frame: SyncProblemFrame, root: com.dayforge.data.repository.NextRecoveryKey) =
        withProblemSource(frame, recovery = true) { frame.requireRecovery(root); businessSyncRepository.readRecoveryPreview(root) }
    internal suspend fun prepareRecoveryConfirmation(frame: SyncProblemFrame,
        preview: com.dayforge.data.repository.NextRecoveryDependencyPreview, selected: Set<com.dayforge.data.repository.NextRecoveryKey>) =
        withProblemSource(frame, recovery = true) { businessSyncRepository.prepareRecoveryConfirmation(preview, selected) }
    internal suspend fun confirmRecovery(frame: SyncProblemFrame, ticket: com.dayforge.data.repository.NextRecoveryConfirmation) =
        withProblemSource(frame, recovery = true) { businessSyncRepository.confirmRecovery(ticket) }

    suspend fun sync(progressCallback: (SyncProgress) -> Unit = {}): Result<Unit> = synchronize(progressCallback, false)
    suspend fun retrySync(progressCallback: (SyncProgress) -> Unit = {}): Result<Unit> = synchronize(progressCallback, true)
    private suspend fun synchronize(progressCallback: (SyncProgress) -> Unit, retryRejected: Boolean): Result<Unit> =
        withDatabases {
            try {
                val report: (SyncProgress) -> Unit = { progress -> _syncProgress.value = progress; progressCallback(progress) }
                if (retryRejected) businessSyncRepository.retrySync(report) else businessSyncRepository.sync(progress = report)
                _syncProgress.value = SyncProgress.Success
                progressCallback(SyncProgress.Success)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: NextMaterialPending) {
                materialWait.value = MaterialWait(pending.session, pending.completedAfter ?: completions.value.sequence)
                _syncProgress.value = SyncProgress.WaitingForMaterials
                progressCallback(SyncProgress.WaitingForMaterials)
                Result.failure(pending)
            } catch (error: Exception) {
                val progress = SyncProgress.Error(error.message ?: "Unknown error", error.isSyncTransportFailure())
                _syncProgress.value = progress; progressCallback(progress); Result.failure(error)
            }
        }

    /** Original sync/account lock still covers the original callback, and its graph remains pinned. */
    suspend fun syncAndThen(afterSync: suspend () -> Unit): Result<Unit> = withDatabases {
        try {
            businessSyncRepository.sync(progress = { _syncProgress.value = it }, afterSync = afterSync)
            _syncProgress.value = SyncProgress.Success
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (pending: NextMaterialPending) {
            materialWait.value = MaterialWait(pending.session, pending.completedAfter ?: completions.value.sequence)
            _syncProgress.value = SyncProgress.WaitingForMaterials
            Result.failure(pending)
        } catch (error: Exception) {
            _syncProgress.value = SyncProgress.Error(error.message ?: "Unknown error", error.isSyncTransportFailure())
            Result.failure(error)
        }
    }
    suspend fun hasLocalData(): Boolean = withDatabases { syncRepository.hasPendingChanges() }

    // Business-valued flows require the outer screen's epoch observation. These getters resolve
    // only its already pinned graph and cannot pick a newer DAO in the middle of a collection.
    fun observeRejectedChanges(): Flow<List<SyncOutboxEntity>> = syncRepository.observeDeadLetters()
    fun observeConflicts(): Flow<List<SyncConflictEntity>> = syncRepository.observeConflicts()
    fun observeRejectedTimerCommands(): Flow<List<TimerCommandEntity>> = syncRepository.observeRejectedTimerCommands()
    suspend fun resolveConflictUseServer(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.resolveConflictUseServer(id) }
    }
    suspend fun resolveConflictUseLocal(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.resolveConflictUseLocal(id) }
    }
    suspend fun retryRejectedChange(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.retryDeadLetter(id) }
    }
    suspend fun retryRejectedTimerCommand(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.retryRejectedTimerCommand(id) }
    }
    suspend fun cancelRejectedTimerCommandAndUseServer(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.cancelRejectedTimerCommandAndUseServer(id) }
    }
    suspend fun discardRejectedChange(id: Long) = run {
        requireFixedProblemInput()
        withDatabases { syncRepository.discardDeadLetter(id) }
    }
    internal suspend fun retryRejectedChange(frame: SyncProblemFrame, row: SyncOutboxEntity) =
        withProblemSource(frame) { syncRepository.retryDeadLetter(frame.change(row)) }
    internal suspend fun discardRejectedChange(frame: SyncProblemFrame, row: SyncOutboxEntity) =
        withProblemSource(frame) { syncRepository.discardDeadLetter(frame.change(row)) }
    internal suspend fun resolveConflictUseServer(frame: SyncProblemFrame, row: SyncConflictEntity) =
        withProblemSource(frame) { syncRepository.resolveConflictUseServer(frame.conflict(row)) }
    internal suspend fun resolveConflictUseLocal(frame: SyncProblemFrame, row: SyncConflictEntity) =
        withProblemSource(frame) { syncRepository.resolveConflictUseLocal(frame.conflict(row)) }
    internal suspend fun retryRejectedTimerCommand(frame: SyncProblemFrame, row: TimerCommandEntity) =
        withProblemSource(frame) { syncRepository.retryRejectedTimerCommand(frame.timer(row)) }
    internal suspend fun cancelRejectedTimerCommandAndUseServer(frame: SyncProblemFrame, row: TimerCommandEntity) =
        withProblemSource(frame) { syncRepository.cancelRejectedTimerCommandAndUseServer(frame.timer(row)) }
    fun getLastSyncTime(): Flow<Long?> = preferences?.lastSyncTimestamp ?: requireNotNull(fixed).legacy.getLastSyncTime()
    fun canEditStructure(): Flow<Boolean> = tokens?.canEditStructure ?: requireNotNull(fixed).legacy.canEditStructure()
    fun isPrimaryEditor(): Flow<Boolean> = tokens?.isPrimaryEditor ?: requireNotNull(fixed).legacy.isPrimaryEditor()
    suspend fun makeCurrentDevicePrimary() { withDatabases { businessSyncRepository.makeCurrentDevicePrimary() } }
    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean) {
        withDatabases { businessSyncRepository.setCurrentDeviceStructuralEditing(enabled) }
    }
    fun resetProgress() { _syncProgress.value = SyncProgress.Idle }
}
