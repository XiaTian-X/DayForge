package com.dayforge.sync

import android.content.Context
import com.dayforge.data.api.NetworkMonitor
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Converts durable local changes and network transitions into best-effort sync
 * attempts. The requests deliberately have no INTERNET constraint: a NAS may be
 * reachable through a Wi-Fi LAN that Android does not classify as internet-capable.
 */
@Singleton
class AutoSyncCoordinator internal constructor(
    private val context: Context,
    private val outboxDao: SyncOutboxDao,
    private val timeLogDao: TimeLogDao,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        outboxDao: SyncOutboxDao,
        timeLogDao: TimeLogDao,
        networkMonitor: NetworkMonitor
    ) : this(context, outboxDao, timeLogDao, networkMonitor,
        CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val started = AtomicBoolean(false)
    private val workManager by lazy { WorkManager.getInstance(context) }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        schedulePeriodicSafetyNet()
        scope.launch {
            networkMonitor.state.collectLatest { snapshot ->
                if (snapshot.mayBeConnected) {
                    delay(NETWORK_DEBOUNCE_MILLIS)
                    enqueueNow()
                }
            }
        }
        enqueueNow()
        scope.launch {
            combine(
                outboxDao.observePendingCount(),
                timeLogDao.observePendingTimerCommandCount()
            ) { ordinary, timers -> ordinary + timers }
                .distinctUntilChanged()
                .collectLatest { count ->
                    if (count > 0) {
                        delay(OUTBOX_DEBOUNCE_MILLIS)
                        enqueueNow()
                    }
                }
        }
    }

    fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()
        // A durable queue makes cancellation safe: the replacement retries the
        // same operation IDs. Coalescing to one latest worker also prevents an
        // offline burst from replaying a long chain of empty syncs after recovery.
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun schedulePeriodicSafetyNet() {
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(
            PERIODIC_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            MIN_RETRY_BACKOFF_SECONDS,
            TimeUnit.SECONDS
        ).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private companion object {
        const val ONE_TIME_WORK_NAME = "dayforge-auto-sync"
        const val PERIODIC_WORK_NAME = "dayforge-periodic-sync"
        const val OUTBOX_DEBOUNCE_MILLIS = 3_000L
        const val NETWORK_DEBOUNCE_MILLIS = 2_000L
        const val MIN_RETRY_BACKOFF_SECONDS = 30L
        const val PERIODIC_INTERVAL_MINUTES = 30L
    }
}
