package com.dayforge.ui.screens.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.dayforge.data.local.PersonalDataDisplayMemory
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.model.HabitWithStats
import com.dayforge.data.repository.DashboardReadBatch
import com.dayforge.data.repository.DashboardReadRepository
import com.dayforge.domain.model.FilterMode
import com.dayforge.domain.service.readCurrentHabitSnapshot
import com.dayforge.ui.DataReadMonitor
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** One active read + one dirty flag. Only this holder publishes the list AND its progress.
 * Registry -> monitor is the lock order; no repository/registry call may run under monitor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DashboardListPresentation(
    scope: CoroutineScope,
    private val context: Context,
    private val repository: DashboardReadRepository,
    private val builder: DashboardHabitListBuilder,
    private val preferences: PreferencesManager,
    private val pending: StateFlow<Set<Long>>,
    private val monitorRead: DataReadMonitor,
    private val project: suspend (DashboardReadBatch) -> List<HabitWithStats> = builder::project,
    private val onRead: () -> Unit = {}
) {
    private val monitor = Any()
    private var generation = Any()
    private var blocked = false
    private var active = false
    private var dirty = true
    private var cached: Pair<DashboardReadBatch, List<HabitWithStats>>? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow<List<HabitWithStats>>(emptyList())
    private val progress = MutableStateFlow(0 to 0)
    private val windows = MutableStateFlow<List<HabitWithStats>>(emptyList())
    val habits = mutable.asStateFlow()
    val todayProgress = progress.asStateFlow()

    private fun clearLocked() {
        cached = null
        progress.value = 0 to 0
        windows.value = emptyList()
        // Empty list is the revocation marker observed by collectors. Clear its dependent
        // progress first, including undispatched collectors resumed inside StateFlow.value.
        mutable.value = emptyList()
    }
    private val listener = object : PersonalDataDisplayMemory.Listener {
        override fun transition(blocked: Boolean) = synchronized(monitor) {
            generation = Any()
            this@DashboardListPresentation.blocked = blocked
            clearLocked()
            dirty = true
            wake.trySend(Unit)
            Unit
        }
    }

    private fun requestRead() = synchronized(monitor) { dirty = true; wake.trySend(Unit); Unit }
    private fun requestArrange() { wake.trySend(Unit) }
    private fun authorityChanged() = synchronized(monitor) {
        // An asynchronous observer cannot reopen a synchronous persistence transition.
        generation = Any(); clearLocked(); dirty = true; wake.trySend(Unit); Unit
    }
    private fun clockChanged() {
        synchronized(monitor) {
            val now = ZonedDateTime.now()
            val old = cached?.first?.time
            if (old == null || old.zone != now.zone || old.toLocalDate() != now.toLocalDate()) {
                generation = Any(); clearLocked(); dirty = true
            }
            wake.trySend(Unit)
        }
    }

    init {
        repository.register(listener) // Register BEFORE the first read, including blocked registration.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(monitor) { active = false; generation = Any(); clearLocked() }
            wake.close()
        }
        scope.launch {
            combine(mutable.subscriptionCount, progress.subscriptionCount) { a, b -> a + b > 0 }
                .distinctUntilChanged().collectLatest { visible ->
                    if (!visible) {
                        delay(1_000)
                        synchronized(monitor) { generation = Any(); clearLocked() }
                    } else {
                        synchronized(monitor) { active = true; dirty = true }
                        try {
                            monitorRead.recover(Unit) { flow {
                                try { observe() }
                                finally { authorityChanged() }
                            } }.collect { }
                        }
                        finally { synchronized(monitor) { active = false; generation = Any() } }
                    }
                }
        }
    }

    private suspend fun observe(): Unit = coroutineScope {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { clockChanged() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // Register every read dependency before the first read. Their initial emissions are
            // initialization, not three separate mutations racing an already-started history audit.
            // Later signals still mark dirty, including same-value DB updates; none are dropped.
            val authorityReady = CompletableDeferred<Unit>()
            val databaseReady = CompletableDeferred<Unit>()
            val calendarReady = CompletableDeferred<Unit>()
            launch { repository.authorityChanges.collect { authorityChanged(); authorityReady.complete(Unit) } }
            launch { repository.changes.collect { requestRead(); databaseReady.complete(Unit) } }
            launch { preferences.dateChangeTrigger.collect { clockChanged(); requestRead(); calendarReady.complete(Unit) } }
            launch { monitorRead.retries.collect { requestRead() } }
            launch { combine(preferences.filterMode.distinctUntilChanged(), pending) { _, _ -> Unit }.collect { requestArrange() } }
            launch(Dispatchers.Default) {
                combine(windows, preferences.filterMode.distinctUntilChanged()) { rows, mode -> rows to mode }.collectLatest { (rows, mode) ->
                    while (true) {
                        val now = ZonedDateTime.now()
                        val midnight = Duration.between(now.toInstant(), now.toLocalDate().plusDays(1)
                            .atStartOfDay(now.zone).toInstant()).toMillis().coerceAtLeast(1)
                        val next = if (mode == FilterMode.TIME_WINDOW.value)
                            minOf(midnight, DashboardTimeWindowRefreshCalculator.calculateDelayMillis(
                                rows.filter { it.countRuleKnown }.map { it.windowHabit }, now)) else midnight
                        delay(next)
                        clockChanged()
                    }
                }
            }
            awaitAll(authorityReady, databaseReady, calendarReady)
            requestRead()
            for (ignored in wake) refresh()
        } finally { context.unregisterReceiver(receiver) }
    }

    private suspend fun refresh() {
        val work = synchronized(monitor) {
            if (!active || blocked) return
            val previous = cached.takeUnless { dirty }
            dirty = false
            generation to previous
        }
        var succeeded = false
        try {
            monitorRead.read(Unit) {
                val (batch, stats) = work.second ?: readCurrentHabitSnapshot {
                    val batch = repository.read(ZonedDateTime.now())
                    batch to withContext(Dispatchers.Default) { project(batch) }
                }
                val mode = FilterMode.fromValue(preferences.filterMode.first())
                val arranged = withContext(Dispatchers.Default) {
                    builder.arrange(stats, mode, ZonedDateTime.now(), pending.value)
                }
                val accepted = repository.publish(batch) {
                    synchronized(monitor) {
                        if (active && !blocked && generation === work.first) {
                            cached = batch to stats
                            monitorRead.publicationSucceeded()
                            windows.value = stats
                            mutable.value = arranged
                            val eligible = arranged.filter { it.shouldCountToday }
                            progress.value = eligible.count { it.completedToday } to eligible.size
                            succeeded = true
                        }
                    }
                }
                if (!accepted) {
                    synchronized(monitor) { if (generation === work.first) clearLocked() }
                    // Date/authority changes have their own wakeups. Do not spin on denied access.
                    if (batch.time.toLocalDate() != ZonedDateTime.now().toLocalDate() || batch.time.zone != ZonedDateTime.now().zone)
                        requestRead()
                }
            }
        } finally {
            synchronized(monitor) { if (active && generation === work.first) {
                if (!succeeded) clearLocked()
                onRead()
            } }
        }
    }
}
