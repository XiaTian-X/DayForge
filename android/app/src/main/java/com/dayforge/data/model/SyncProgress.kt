package com.dayforge.data.model

/**
 * Represents the progress state of a sync operation.
 * Used to communicate sync status to the UI layer.
 */
sealed class SyncProgress {
    /**
     * No sync operation in progress.
     */
    object Idle : SyncProgress()

    /** Uploading durable Sync V2 operations. */
    data class UploadingChanges(val current: Int, val total: Int) : SyncProgress()

    /**
     * Downloading data from the server.
     */
    object Downloading : SyncProgress()

    /** Replacing a clean local cache after an incremental payload cannot be merged. */
    object Recovering : SyncProgress()

    /**
     * Sync completed successfully.
     */
    object Success : SyncProgress()

    /**
     * Sync failed with an error.
     * @param message Error message describing the failure
     */
    data class Error(
        val message: String,
        val isNetworkFailure: Boolean = false
    ) : SyncProgress()
}
