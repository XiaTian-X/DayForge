package com.dayforge.data.repository

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Epoch-owned scheduling metadata only. Each collector still obtains its own original
 * admission, fresh authority and actual-END proof; a deadline never authorizes collection.
 */
internal class CountNodeCollectionSchedule(
    private val count: CountTrustNodeCollector,
    private val command: CountCommandNodeCollector? = null
) {
    internal enum class Pool { COUNT, COMMAND }
    internal data class Slice(val pool: Pool?, val report: CountTrustNodeCollector.Report?, val pauseMillis: Long)
    private val running = AtomicBoolean(false)
    private var preferred = Pool.COUNT
    private var countAfter = 0L
    private var commandAfter = 0L

    private fun requested(pool: Pool) = when (pool) {
        Pool.COUNT -> count.requested
        Pool.COMMAND -> command?.requested == true
    }
    private fun after(pool: Pool) = if (pool == Pool.COUNT) countAfter else commandAfter
    private fun other(pool: Pool) = if (pool == Pool.COUNT) Pool.COMMAND else Pool.COUNT
    private fun pause(report: CountTrustNodeCollector.Report): Long = when (report.status) {
        CountTrustNodeCollector.Status.PROGRESS -> 250L
        CountTrustNodeCollector.Status.RESTARTED -> 2_000L
        CountTrustNodeCollector.Status.BUSY -> 1_000L
        CountTrustNodeCollector.Status.DEFERRED -> if (report.reason == "READER_OR_OWNER_PRESENT") 1_000L else 30_000L
        CountTrustNodeCollector.Status.FAILED, CountTrustNodeCollector.Status.FINISHED -> 30_000L
    }
    private fun waitForRequested(now: Long): Long = Pool.entries.filter(::requested)
        .minOfOrNull { after(it) - now }?.coerceIn(250L, 30_000L) ?: 30_000L

    /** One serial slice, including its bounded frozen validation after transaction END.
     * The caller releases its graph lease before honoring pauseMillis or selecting again.
     */
    internal suspend fun next(): Slice {
        currentCoroutineContext().ensureActive()
        if (!running.compareAndSet(false, true)) return Slice(null, null, 1_000L)
        try {
            val now = SystemClock.elapsedRealtime()
            val selected = listOf(preferred, other(preferred)).firstOrNull { requested(it) && after(it) <= now }
                ?: return Slice(null, null, waitForRequested(now))
            // An attempted BUSY/DEFERRED/FAILED slice must also yield its turn to the other pool.
            preferred = other(selected)
            val report = when (selected) {
                Pool.COUNT -> count.collectSlice()
                Pool.COMMAND -> requireNotNull(command).collectSlice()
            }
            currentCoroutineContext().ensureActive()
            val ended = SystemClock.elapsedRealtime()
            val ownPause = pause(report)
            when (selected) {
                Pool.COUNT -> countAfter = ended + ownPause
                Pool.COMMAND -> commandAfter = ended + ownPause
            }
            // With only COUNT requested, preserve the original report-to-delay behavior.
            // Independent backoff lets a ready second pool run, without a tighter slice cadence.
            val nextPause = if (!requested(other(selected))) ownPause else waitForRequested(ended)
            return Slice(selected, report, nextPause)
        } finally { running.set(false) }
    }
}

/** Process-owned optional idle work. It never opens a cold database only to collect, waits
 * behind no account queue, and releases account/SQLite/graph resources after every bounded slice.
 */
@Singleton
internal class CountTrustNodeMaintenance private constructor(
    private val fixed: CountNodeCollectionSchedule?, private val host: com.dayforge.data.local.DatabaseRuntimeHost?
) {
    constructor(collector: CountTrustNodeCollector) : this(CountNodeCollectionSchedule(collector), null)
    constructor(collector: CountTrustNodeCollector, command: CountCommandNodeCollector) :
        this(CountNodeCollectionSchedule(collector, command), null)
    @Inject constructor(host: com.dayforge.data.local.DatabaseRuntimeHost) : this(null, host)
    private suspend fun next(): CountNodeCollectionSchedule.Slice? = if (host == null) {
        requireNotNull(fixed).next()
    } else host.tryWithActiveGraph { graph -> graph.countNodeCollectionSchedule.next() }
    private var job: Job? = null
    @Synchronized fun start(scope: CoroutineScope) {
        // Cancellation may still be completing the collector's END/discard/finally work.
        if (job?.isCompleted == false) return
        job = scope.launch {
            var pause = 30_000L
            val lastAttention = mutableMapOf<CountNodeCollectionSchedule.Pool, String?>()
            while (isActive) {
                delay(pause)
                val slice = next()
                if (slice == null) { pause = 30_000L; continue }
                pause = slice.pauseMillis
                val report = slice.report ?: continue
                val pool = requireNotNull(slice.pool)
                val attention = report.reason.takeIf { report.status == CountTrustNodeCollector.Status.DEFERRED }
                if (com.dayforge.BuildConfig.DEBUG && attention != null && attention != lastAttention[pool])
                    android.util.Log.d("CountNodeMaintenance", "pool=$pool status=DEFERRED reason=$attention")
                lastAttention[pool] = attention
                if (com.dayforge.BuildConfig.DEBUG && report.status in setOf(
                    CountTrustNodeCollector.Status.FINISHED, CountTrustNodeCollector.Status.FAILED)) {
                    android.util.Log.d("CountNodeMaintenance", "pool=$pool status=${report.status} cohort=${report.cohort} " +
                        "restarts=${report.restarts} batch_deleted=${report.deletedNodes} batch_bytes=${report.deletedBytes} " +
                        "account_end_ns=${report.accountAndEndNanos} parse_ns=${report.parseNanos}")
                }
            }
        }
    }
}
