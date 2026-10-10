package com.dayforge.domain.service

import com.dayforge.data.model.SyncProgress
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.BusinessSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the synchronization flow between local database and server.
 * Exposes the shared version dispatcher and existing v4 durable-queue controls to the UI.
 */
@Singleton
class SyncManager @Inject constructor(
    private val syncRepository: IncrementalSyncRepository,
    private val businessSyncRepository: BusinessSyncRepository
) {
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val syncProgress: Flow<SyncProgress> = _syncProgress.asStateFlow()
    internal fun problemChanges() = businessSyncRepository.problemChanges()
    internal suspend fun readProblems() = businessSyncRepository.readProblems()

    /**
     * Pushes pending V2 operations, then pulls and merges remote changes.
     *
     * @param progressCallback Callback for progress updates
     * @return Result.success if sync completed, Result.failure if an error occurred
     */
    suspend fun sync(progressCallback: (SyncProgress) -> Unit = {}): Result<Unit> {
        return synchronize(progressCallback, retryRejected = false)
    }

    suspend fun retrySync(progressCallback: (SyncProgress) -> Unit = {}): Result<Unit> {
        return synchronize(progressCallback, retryRejected = true)
    }

    private suspend fun synchronize(progressCallback: (SyncProgress) -> Unit, retryRejected: Boolean): Result<Unit> {
        return try {
            val report: (SyncProgress) -> Unit = { progress ->
                _syncProgress.value = progress
                progressCallback(progress)
            }
            if (retryRejected) businessSyncRepository.retrySync(report) else businessSyncRepository.sync(progress = report)

            _syncProgress.value = SyncProgress.Success
            progressCallback(SyncProgress.Success)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val errorProgress = SyncProgress.Error(
                message = e.message ?: "Unknown error",
                isNetworkFailure = e.isSyncTransportFailure()
            )
            _syncProgress.value = errorProgress
            progressCallback(errorProgress)
            Result.failure(e)
        }
    }

    /** Runs cleanup while the sync/account lock is still held. */
    suspend fun syncAndThen(afterSync: suspend () -> Unit): Result<Unit> {
        return try {
            businessSyncRepository.sync(
                progress = { _syncProgress.value = it },
                afterSync = afterSync
            )
            _syncProgress.value = SyncProgress.Success
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _syncProgress.value = SyncProgress.Error(
                message = e.message ?: "Unknown error",
                isNetworkFailure = e.isSyncTransportFailure()
            )
            Result.failure(e)
        }
    }

    /**
     * Checks whether the durable V2 queue has operations waiting to sync.
     */
    suspend fun hasLocalData(): Boolean =
        syncRepository.hasPendingChanges()

    fun observeRejectedChanges(): Flow<List<SyncOutboxEntity>> =
        syncRepository.observeDeadLetters()

    fun observeConflicts(): Flow<List<SyncConflictEntity>> =
        syncRepository.observeConflicts()

    suspend fun resolveConflictUseServer(id: Long) {
        syncRepository.resolveConflictUseServer(id)
    }

    suspend fun resolveConflictUseLocal(id: Long) {
        syncRepository.resolveConflictUseLocal(id)
    }

    fun observeRejectedTimerCommands(): Flow<List<TimerCommandEntity>> =
        syncRepository.observeRejectedTimerCommands()

    suspend fun retryRejectedChange(id: Long) {
        syncRepository.retryDeadLetter(id)
    }

    suspend fun retryRejectedTimerCommand(id: Long) {
        syncRepository.retryRejectedTimerCommand(id)
    }

    suspend fun cancelRejectedTimerCommandAndUseServer(id: Long) {
        syncRepository.cancelRejectedTimerCommandAndUseServer(id)
    }

    suspend fun discardRejectedChange(id: Long) {
        syncRepository.discardDeadLetter(id)
    }

    /**
     * Gets the last sync timestamp as a Flow.
     * @return Flow of last sync time in milliseconds, or null if never synced
     */
    fun getLastSyncTime(): Flow<Long?> =
        syncRepository.getLastSyncTime()

    fun canEditStructure(): Flow<Boolean> = syncRepository.canEditStructure()

    fun isPrimaryEditor(): Flow<Boolean> = syncRepository.isPrimaryEditor()

    suspend fun makeCurrentDevicePrimary() {
        businessSyncRepository.makeCurrentDevicePrimary()
    }

    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean) {
        businessSyncRepository.setCurrentDeviceStructuralEditing(enabled)
    }

    /**
     * Resets the sync progress to Idle state.
     * Call this after showing success/error to the user.
     */
    fun resetProgress() {
        _syncProgress.value = SyncProgress.Idle
    }

}
