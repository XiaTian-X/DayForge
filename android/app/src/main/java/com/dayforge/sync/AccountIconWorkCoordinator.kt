package com.dayforge.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountIconController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Separate from business sync. Durable material journals, not wakeups, are the source of truth. */
@Singleton
class AccountIconWorkCoordinator internal constructor(
    private val context: Context, private val access: Flow<LocalIconAccess?>,
    private val network: Flow<NetworkMonitor.Snapshot>, private val requests: Flow<Unit>,
    private val scope: CoroutineScope
) {
    @Inject constructor(@ApplicationContext context: Context, tokens: TokenManager,
        network: NetworkMonitor, icons: AccountIconController) : this(context, tokens.iconAccessChanges,
        network.state, icons.workRequests, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val started = AtomicBoolean(false)
    private val workManager by lazy { WorkManager.getInstance(context) }
    internal enum class Status { INACTIVE, OBSERVING, FAILED }
    private val mutableStatus = MutableStateFlow(Status.INACTIVE)
    internal val status = mutableStatus.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                access.collectLatest { authority ->
                    if (authority == null) {
                        workManager.cancelUniqueWork(ONE_TIME)
                        workManager.cancelUniqueWork(PERIODIC)
                        mutableStatus.value = Status.INACTIVE
                        return@collectLatest
                    }
                    val periodic = PeriodicWorkRequestBuilder<AccountIconWorker>(30, TimeUnit.MINUTES)
                        .setInitialDelay(30, TimeUnit.MINUTES)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
                    workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
                    enqueue()
                    mutableStatus.value = Status.OBSERVING
                    // Loss cancels a pending wakeup; local-only LAN is deliberately NOT an INTERNET constraint.
                    merge(network.map { it.mayBeConnected }, requests.map { true }).collectLatest { wake ->
                        if (wake) { delay(2_000); enqueue() }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Observation/scheduling failure is visible and finite, never a process crash or
                // fabricated success. Preserve the material journals and stop only our schedules.
                mutableStatus.value = Status.FAILED
                Log.e("AccountIconWork", "Material scheduling failed; recovery requires a new coordinator start")
                for (name in listOf(ONE_TIME, PERIODIC)) try { workManager.cancelUniqueWork(name) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    Log.e("AccountIconWork", "Material schedule cancellation failed")
                }
            } finally { started.set(false) }
        }
    }

    private fun enqueue() {
        val work = OneTimeWorkRequestBuilder<AccountIconWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        // Replacement cancels the old worker. The canonical runtime mutex waits for real IO to join
        // before recovering its sending rows; never replace stable durable operation IDs.
        workManager.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, work)
    }

    internal companion object {
        const val ONE_TIME = "dayforge-icon-material"
        const val PERIODIC = "dayforge-icon-material-periodic"
    }
}
