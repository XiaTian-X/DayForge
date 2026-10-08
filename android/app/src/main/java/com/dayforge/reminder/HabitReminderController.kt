package com.dayforge.reminder

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.room.withTransaction
import com.dayforge.data.local.AccountIconMemory
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalFactAccess
import com.dayforge.data.local.LocalDataSession
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitType
import com.dayforge.data.repository.CountHistoryReader
import com.dayforge.data.repository.OneTimeLocalIntentStore
import com.dayforge.domain.service.AccountSessionCoordinator
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ReminderReading(val access: LocalFactAccess, val habit: HabitEntity,
    val target: Int?, val countdown: Boolean?, val quantity: Long, val onceCompleted: Boolean, val enabled: Boolean,
    val roundHead: com.dayforge.domain.model.ChallengeRoundHead? = null) {
    val scope: String get() = reminderScope(access.session)
    val stamp: String get() = MessageDigest.getInstance("SHA-256").digest(listOf(scope, habit.uuid,
        habit.name, habit.bestTime, habit.schedule, habit.habitType, habit.isActive, target, countdown,
        habit.completionPolicy).let { if (roundHead == null) it else it + roundHead }
        .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun reminderScope(session: LocalDataSession): String = listOf(session.authentication.userId,
    session.authentication.generation, session.serverInstanceId, session.syncEpoch).joinToString("|")

internal data class ReminderDelivery(val habitId: Long, val habitUuid: String, val scope: String,
    val stamp: String, val wake: HabitReminderWake)

internal interface ReminderAlarms {
    fun replace(reading: ReminderReading, wake: HabitReminderWake)
    fun cancel(habitId: Long)
    fun cancelAll()
    fun publish(reading: ReminderReading, wake: HabitReminderWake)
    fun clearPublished(keepScope: String? = null)
}

/** Only presentation side effects. No fact/outbox mutations or unverified owner from an Intent. */
@Singleton
class HabitReminderController internal constructor(
    private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val counts: CountHistoryReader,
    private val preferences: PreferencesManager, private val dataStore: DataStore<Preferences>,
    private val alarms: ReminderAlarms
) {
    @Inject internal constructor(database: HabitDatabase, tokens: TokenManager, sessions: AccountSessionCoordinator,
        counts: CountHistoryReader, preferences: PreferencesManager, dataStore: DataStore<Preferences>,
        alarms: AndroidReminderAlarms) : this(database, tokens, sessions, counts, preferences, dataStore, alarms as ReminderAlarms)

    private val started = AtomicBoolean(false)
    @Volatile private var publicationBlocked = false
    private val publicationGuard = Any()
    private var publicationGeneration = 0L
    private val invalidator = object : AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) {
            synchronized(publicationGuard) {
                publicationBlocked = blocked
                if (blocked) publicationGeneration++
                // This registry allows only short memory invalidation. OS/storage cleanup is
                // performed by the session observer, not inside TokenManager's transition hook.
            }
        }
    }
    init { tokens.registerIconCache(invalidator) }

    /** Account cleanup may already hold the non-reentrant coordinator. No DB or account re-entry. */
    suspend fun cancelAllNow() = withContext(Dispatchers.IO) {
        synchronized(publicationGuard) { alarms.cancelAll(); alarms.clearPublished() }
    }

    /** Resolve only in the current account; keep switching serialized through the main-thread handoff. */
    suspend fun openDetail(request: ReminderDetailRequest, navigate: (Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            sessions.exclusive {
                val generation = synchronized(publicationGuard) { publicationGeneration }
                val access = tokens.localFactAccess() ?: return@exclusive false
                if (publicationBlocked || reminderScope(access.session) != request.scope) return@exclusive false
                val row = database.withTransaction {
                    database.habitDao().getVisibleHabitByIdSync(request.habitId)
                } ?: return@exclusive false
                if (row.uuid != request.habitUuid) return@exclusive false
                // Decryption may use Android Keystore: finish snapshot validation on IO.
                val current = tokens.localFactAccess()
                // No DAO access or disk IO on Main, and no navigation inside Room's transaction.
                withContext(Dispatchers.Main.immediate) {
                    synchronized(publicationGuard) {
                        if (publicationBlocked || publicationGeneration != generation || current != access) false
                        else { navigate(row.id); true }
                    }
                }
            }
        }

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                coroutineScope {
                    launch {
                        tokens.factAccessChanges.map { it?.session }.distinctUntilChanged().collectLatest {
                            sessions.exclusive {
                                val current = tokens.localFactAccess()?.session
                                synchronized(publicationGuard) {
                                    alarms.clearPublished(current?.let(::reminderScope)); alarms.cancelAll()
                                }
                            }
                            rescheduleAll()
                        }
                    }
                    combine(database.habitDao().getAllHabits(), database.completionDao().getAllCompletions(),
                        counts.changes, database.completionFollowUpDao().observeOneTimeChanges(), dataStore.data) {
                        _, _, _, _, _ -> Unit
                    }.collectLatest { delay(200); rescheduleAll() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.util.Log.e("HabitReminder", "Reminder observation stopped", error)
            } finally { started.set(false) }
        }
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        // Cancel tracked obsolete/deleted identities before rebuilding from current Room, not a cache.
        sessions.exclusive { alarms.cancelAll() }
        val ids = database.habitDao().getVisibleHabitsOnce().map { it.id }
        for (id in ids) try { schedule(id) } catch (error: Exception) {
            if (error is CancellationException) throw error
            android.util.Log.e("HabitReminder", "Cannot read reminder for habitId=$id", error)
        }
    }

    suspend fun schedule(id: Long, now: ZonedDateTime = ZonedDateTime.now()) = withContext(Dispatchers.IO) {
        sessions.exclusive {
            val reading = database.withTransaction { read(id, now) }
            // Account lock remains held across publication, but all OS calls are after Room COMMIT.
            synchronized(publicationGuard) {
                if (reading == null || !reading.enabled || reading.onceCompleted || publicationBlocked) alarms.cancel(id)
                else {
                    val wake = HabitReminderPlan.next(reading.habit, reading.target, reading.quantity, now)
                    if (wake == null) alarms.cancel(id) else alarms.replace(reading, wake)
                }
            }
        }
    }

    internal suspend fun deliver(delivery: ReminderDelivery, now: ZonedDateTime = ZonedDateTime.now()) =
        withContext(Dispatchers.IO) {
            sessions.exclusive {
                val currentAccess = tokens.localFactAccess() ?: return@exclusive
                val currentScope = reminderScope(currentAccess.session)
                if (currentScope != delivery.scope) return@exclusive
                val reading = database.withTransaction { read(delivery.habitId, now) }
                // Old-account broadcasts must not cancel/replace a new account's current alarm.
                if (reading == null || publicationBlocked || reading.scope != delivery.scope ||
                    reading.habit.uuid != delivery.habitUuid) return@exclusive
                val wake = delivery.wake
                val sameDate = wake.date == now.toLocalDate() && wake.zone == now.zone
                val due = now.toInstant() >= wake.trigger
                val eligible = HabitReminderPlan.nextEligible(reading.habit, now.toLocalDate(), now.zone) == now.toLocalDate()
                synchronized(publicationGuard) {
                if (publicationBlocked) return@synchronized
                if (sameDate && due && eligible && reading.enabled && !reading.onceCompleted &&
                    reading.habit.isActive && reading.habit.bestTime != null && reading.stamp == delivery.stamp && wake.minute != null) {
                    val countWindow = HabitReminderPlan.window(reading.habit, reading.target, wake.minute, now)
                    if (reading.habit.habitType != HabitType.COUNTING ||
                        (countWindow == (wake.firstSlot to wake.lastSlot) && reading.quantity <= wake.lastSlot.toLong()))
                        alarms.publish(reading, wake)
                }
                // Silent/late/changed-plan broadcasts replan, never shift old slots to tomorrow.
                if (!reading.enabled || reading.onceCompleted) alarms.cancel(reading.habit.id)
                else HabitReminderPlan.next(reading.habit, reading.target, reading.quantity, now)?.let {
                    alarms.replace(reading, it)
                } ?: alarms.cancel(reading.habit.id)
                }
            }
        }

    private suspend fun read(id: Long, now: ZonedDateTime): ReminderReading? {
        val access = tokens.localFactAccess() ?: return null
        val habit = database.habitDao().getVisibleHabitByIdSync(id) ?: return null
        val prefs = dataStore.data.first()
        val enabled = (prefs[booleanPreferencesKey("global_notifications_enabled")] ?: true) &&
            (prefs[booleanPreferencesKey("habit_notification_$id")] ?: true)
        val history = if (habit.habitType == HabitType.COUNTING && habit.appearance != null)
            counts.readInTransaction(habit, now.toLocalDate()) else null
        val quantity = if (habit.habitType == HabitType.COUNTING) history?.todayQuantity ?: run {
            database.completionDao().getCompletionsInRangeSync(id, now.toLocalDate(), now.toLocalDate().plusDays(1))
                .fold(0L) { sum, row -> Math.addExact(sum, row.value.toLong()) }
        } else 0L
        val once = if (habit.completionPolicy == "one_and_done") OneTimeLocalIntentStore(database, tokens, sessions, preferences)
            .readInTransaction(habit.uuid, access.session).queue.optimisticState.completionEventUuid != null else false
        check(tokens.localFactAccess() == access) { "REMINDER_SESSION_CHANGED" }
        return ReminderReading(access, habit,
            if (history != null) history.todayPolicy?.targetValue else habit.targetValue,
            if (history != null) history.todayPolicy?.isCountdown else habit.isCountdown, quantity, once, enabled, history?.roundHead)
    }
}
