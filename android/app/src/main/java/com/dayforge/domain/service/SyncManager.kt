package com.dayforge.domain.service

import com.dayforge.data.model.SyncProgress
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.repository.IncrementalSyncRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the synchronization flow between local database and server.
 * Exposes V2 incremental synchronization and durable-queue controls to the UI.
 */
@Singleton
class SyncManager @Inject constructor(
    private val syncRepository: IncrementalSyncRepository
) {
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val syncProgress: Flow<SyncProgress> = _syncProgress.asStateFlow()

    /**
     * Pushes pending V2 operations, then pulls and merges remote changes.
     *
     * @param progressCallback Callback for progress updates
     * @return Result.success if sync completed, Result.failure if an error occurred
     */
    suspend fun sync(progressCallback: (SyncProgress) -> Unit = {}): Result<Unit> {
        return try {
            syncRepository.sync { progress ->
                _syncProgress.value = progress
                progressCallback(progress)
            }

            _syncProgress.value = SyncProgress.Success
            progressCallback(SyncProgress.Success)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val errorProgress = SyncProgress.Error(
                message = e.message ?: "Unknown error",
                isNetworkFailure = e.hasIOExceptionCause()
            )
            _syncProgress.value = errorProgress
            progressCallback(errorProgress)
            Result.failure(e)
        }
    }

    /** Runs cleanup while the sync/account lock is still held. */
    suspend fun syncAndThen(afterSync: suspend () -> Unit): Result<Unit> {
        return try {
            syncRepository.syncAndThen(
                progressCallback = { _syncProgress.value = it },
                afterSync = afterSync
            )
            _syncProgress.value = SyncProgress.Success
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _syncProgress.value = SyncProgress.Error(
                message = e.message ?: "Unknown error",
                isNetworkFailure = e.hasIOExceptionCause()
            )
            Result.failure(e)
        }
    }

    /**
     * Checks whether the durable V2 queue has operations waiting to sync.
     */
    suspend fun hasLocalData(): Boolean =
        syncRepository.hasPendingChanges()

    /** Move quarantined operations back to the active queue before a user retry. */
    suspend fun retryRejectedChanges() {
        syncRepository.retryAllDeadLetters()
    }

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
        syncRepository.makeCurrentDevicePrimary()
    }

    suspend fun setCurrentDeviceStructuralEditing(enabled: Boolean) {
        syncRepository.setCurrentDeviceStructuralEditing(enabled)
    }

    /**
     * Resets the sync progress to Idle state.
     * Call this after showing success/error to the user.
     */
    fun resetProgress() {
        _syncProgress.value = SyncProgress.Idle
    }

    private fun Throwable.hasIOExceptionCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is IOException) return true
            current = current.cause
        }
        return false
    }
}
