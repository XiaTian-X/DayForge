package com.dayforge.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Test lifecycle only: Android destruction requests cancellation but does not join all
 * accepted Room work. Preserve the original per-Host 30s teardown capacity and real close.
 * A live accepted operation is never cancelled or force-closed by this helper. The strict
 * production probe remains unchanged; only its exact known BUSY result is retryable.
 * A native drain BUSY can already have revoked publication; its epoch remains retained
 * and every retry still performs the original capture, seal and actual close checks.
 */
internal suspend fun shutdownTestHost(host: DatabaseRuntimeHost) {
    var lastBusy: IllegalStateException? = null
    var waits = 0
    val started = android.os.SystemClock.elapsedRealtime()
    try {
        withTimeout(30_000) {
            withContext(Dispatchers.IO) {
                while (true) {
                    try {
                        host.shutdownWhenIdle()
                        break
                    } catch (busy: IllegalStateException) {
                        if (busy.message != "DATABASE_EPOCH_SHUTDOWN_BUSY") throw busy
                        lastBusy = busy
                        waits++
                        delay(25)
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        lastBusy?.let { if (it !== failure) failure.addSuppressed(it) }
        throw failure
    } finally {
        android.util.Log.i("TestHostDrain", "busy_attempts=$waits " +
            "elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started} closed=${host.lifecycleEnded}")
    }
}
