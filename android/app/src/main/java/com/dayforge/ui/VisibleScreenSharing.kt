package com.dayforge.ui

import kotlinx.coroutines.flow.SharingStarted

/** Brief navigation grace only. An abandoned page must neither compete with foreground writes
 * nor replay an indefinitely old account projection when reopened. Persistent jobs are not UI flows.
 */
internal val VisibleScreenSharing = SharingStarted.WhileSubscribed(
    stopTimeoutMillis = 1_000,
    replayExpirationMillis = 0
)
