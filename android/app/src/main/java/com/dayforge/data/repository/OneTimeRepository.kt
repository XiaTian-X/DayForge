package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.model.OneTimeStatus
import com.dayforge.domain.model.OneTimeActionAuthority
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Opaque account/revision-bound prompt. UI never constructs fact identities or sync envelopes. */
class OneTimeMetricPrompt internal constructor(
    internal val snapshot: CompletionMetricPrompt,
    val habitId: Long,
    val habitName: String,
    val entries: List<OneTimeMetricEntry>
) {
    val eventUuid: String get() = snapshot.row.eventUuid
}

/** Negative input IDs identify a missing target in this prompt only, never a database write. */
data class OneTimeMetricEntry(val metricId: Long, val name: String, val unit: String,
    val decimalPlaces: Int, val input: String, val note: String, val available: Boolean = true)

@Singleton
class OneTimeRepository @Inject constructor(
    private val database: HabitDatabase,
    private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator,
    private val preferences: PreferencesManager
) {
    private class ActionAuthority(val snapshot: OneTimeLocalSnapshot) : OneTimeActionAuthority()
    @Volatile private var publicationBlocked = false
    @Volatile private var publicationStamp = Any()
    private val invalidator = object : com.dayforge.data.local.AccountIconMemory.Cache {
        override fun authenticationTransition(blocked: Boolean) {
            publicationBlocked = blocked
            publicationStamp = Any()
        }
    }
    init { tokens.registerIconCache(invalidator) }
    internal fun registerConsumer(cache: com.dayforge.data.local.AccountIconMemory.Cache) = tokens.registerIconCache(cache)
    private val intents = OneTimeLocalIntentStore(database, tokens, sessions, preferences)
    private val prompts = CompletionMetricPromptStore(database, tokens, sessions)

    // Include outbox changes even when no business row changes (rejection/replay/ACK).
    val changes: Flow<Unit> = combine(database.completionFollowUpDao().observeOneTimeChanges(),
        tokens.factAccessChanges) { _, _ -> Unit }

    internal suspend fun read(id: Long, expectedUuid: String? = null): OneTimeStatus = withContext(Dispatchers.IO) {
        capture(id, expectedUuid).second
    }

    private suspend fun capture(id: Long, expectedUuid: String? = null): Pair<OneTimeLocalSnapshot, OneTimeStatus> = sessions.exclusive {
        val access = requireNotNull(tokens.localFactAccess()) { "ONE_TIME_SESSION_CHANGED" }
        database.withTransaction {
            val habit = requireNotNull(database.habitDao().getHabitById(id)) { "ONE_TIME_NOT_FOUND" }
            if (expectedUuid != null) check(habit.uuid == expectedUuid) { "ONE_TIME_ACTIVITY_CHANGED" }
            val snapshot = intents.readInTransaction(habit.uuid, access.session)
            val state = snapshot.queue.optimisticState
            val factId = state.completionEventUuid?.let { uuid ->
                val fact = requireNotNull(database.completionDao().getCompletionByUuid(uuid))
                check(fact.habitId == id && fact.oneTimeAction == "complete")
                fact.id
            }
            check(tokens.localFactAccess() == access) { "ONE_TIME_SESSION_CHANGED" }
            snapshot to OneTimeStatus(state.completionEventUuid != null, factId,
                access.canAppend && !database.habitDao().hasPendingNextDeletion(habit.uuid) && snapshot.queue.blockedOperationIds.isEmpty() &&
                    snapshot.queue.awaitingReplayOperationIds.isEmpty(),
                snapshot.queue.awaitingReplayOperationIds.isNotEmpty(), snapshot.queue.blockedOperationIds.isNotEmpty(),
                ActionAuthority(snapshot))
        }
    }

    /** A racing action is a CAS conflict, never a silent second toggle or a physical fact delete. */
    internal suspend fun change(id: Long, complete: Boolean, expectedCompletionId: Long? = null,
        expectedUuid: String? = null, authority: OneTimeActionAuthority? = null,
        widgetClaim: WidgetFactClaim? = null, widgetReader: WidgetFactReader? = null): Long =
        withContext(Dispatchers.IO) {
            val (snapshot, status) = capture(id, expectedUuid)
            validateAuthority(snapshot, authority)
            if (widgetClaim != null) check(snapshot.session == widgetClaim.session() &&
                snapshot.activityUuid == widgetClaim.habitUuid && snapshot.queue.optimisticState == widgetClaim.oneTimeState) {
                "ONE_TIME_ACTION_EXPIRED"
            }
            check(status.canChange) { "ONE_TIME_PENDING_OR_DENIED" }
            check(status.completed != complete) { "ONE_TIME_STATE_CHANGED" }
            if (expectedCompletionId != null) check(status.completionId == expectedCompletionId) {
                "ONE_TIME_COMPLETION_CHANGED"
            }
            val state = snapshot.queue.optimisticState
            val instant = Instant.now()
            val zone = ZoneId.systemDefault()
            val command = OneTimeLocalCommand(snapshot.activityUuid,
                PendingOneTimeIntent(UUID.randomUUID().toString(), OneTimeIntent(UUID.randomUUID().toString(),
                    if (complete) "complete" else "undo", state.version, state.headEventUuid,
                    if (complete) null else state.completionEventUuid)), instant.toEpochMilli(), zone.id)
            intents.append(snapshot.session, command) {
                if (widgetClaim != null) {
                    requireNotNull(widgetReader).requireInTransaction(widgetClaim)
                    check(instant.atZone(zone).toLocalDate().toString() == widgetClaim.date && zone.id == widgetClaim.timezone) {
                        "FACT_WIDGET_DAY_CHANGED"
                    }
                }
            }.factId
        }

    internal suspend fun toggle(id: Long, expectedUuid: String? = null, authority: OneTimeActionAuthority? = null): Boolean = withContext(Dispatchers.IO) {
        val (snapshot, status) = capture(id, expectedUuid)
        validateAuthority(snapshot, authority)
        check(status.canChange) { "ONE_TIME_PENDING_OR_DENIED" }
        val state = snapshot.queue.optimisticState
        val complete = !status.completed
        val instant = Instant.now()
        val zone = ZoneId.systemDefault()
        intents.append(snapshot.session, OneTimeLocalCommand(snapshot.activityUuid,
            PendingOneTimeIntent(UUID.randomUUID().toString(), OneTimeIntent(UUID.randomUUID().toString(),
                if (complete) "complete" else "undo", state.version, state.headEventUuid,
                if (complete) null else state.completionEventUuid)), instant.toEpochMilli(), zone.id))
        complete
    }

    private fun validateAuthority(snapshot: OneTimeLocalSnapshot, authority: OneTimeActionAuthority?) {
        if (authority == null) return
        val captured = (authority as? ActionAuthority)?.snapshot
        check(captured != null && captured.session == snapshot.session && captured.activityUuid == snapshot.activityUuid &&
            captured.queue.optimisticState == snapshot.queue.optimisticState) { "ONE_TIME_ACTION_EXPIRED" }
    }

    val pendingHabitIds: Flow<Set<Long>> = changes.map {
        withContext(Dispatchers.IO) { sessions.exclusive {
            val access = tokens.localFactAccess() ?: return@exclusive emptySet()
            database.withTransaction {
                val ids = prompts.pendingInTransaction(access.session).map {
                    database.habitDao().getHabitByUuid(it.row.activityUuid)?.id
                }.filterNotNull().toSet()
                check(tokens.localFactAccess() == access) { "ONE_TIME_SESSION_CHANGED" }
                ids
            }
        } }
    }

    suspend fun prompt(id: Long): OneTimeMetricPrompt? = withContext(Dispatchers.IO) {
        val pending = prompts.pending().firstOrNull {
            database.habitDao().getHabitByUuid(it.row.activityUuid)?.id == id
        } ?: return@withContext null
        view(pending)
    }

    suspend fun saveDraft(prompt: OneTimeMetricPrompt, inputs: Map<Long, Pair<String, String>>): OneTimeMetricPrompt =
        withContext(Dispatchers.IO) {
            val byId = prompt.entries.associateBy { it.metricId }
            require(byId.keys.containsAll(inputs.keys))
            val mapped = inputs.map { (id, input) ->
                val index = prompt.entries.indexOf(byId.getValue(id))
                val uuid = prompt.snapshot.entries[index].metricUuid
                uuid to CompletionMetricInput(input.first, input.second)
            }.toMap()
            view(prompts.updateDraft(prompt.snapshot, mapped))
        }

    suspend fun submit(prompt: OneTimeMetricPrompt) = withContext(Dispatchers.IO) {
        prompts.submit(prompt.snapshot)
        Unit
    }

    suspend fun dismiss(prompt: OneTimeMetricPrompt) = withContext(Dispatchers.IO) {
        prompts.dismiss(prompt.snapshot)
    }

    suspend fun refresh(prompt: OneTimeMetricPrompt): OneTimeMetricPrompt = withContext(Dispatchers.IO) {
        view(prompts.refreshMetadata(prompt.snapshot))
    }

    suspend fun setNeverAskAgain(prompt: OneTimeMetricPrompt, value: Boolean) = withContext(Dispatchers.IO) {
        sessions.exclusive {
            check(tokens.localFactAccess()?.session == prompt.snapshot.session) { "ONE_TIME_SESSION_CHANGED" }
            preferences.setNeverAskAgain(prompt.habitId, value)
        }
    }

    /** Publish only while the exact owner/generation/replica is still current. */
    suspend fun publish(prompt: OneTimeMetricPrompt, block: () -> Unit) = withContext(Dispatchers.IO) {
        sessions.exclusive {
            val stamp = publicationStamp
            check(!publicationBlocked && tokens.localFactAccess()?.session == prompt.snapshot.session) { "ONE_TIME_SESSION_CHANGED" }
            withContext(Dispatchers.Main) {
                check(!publicationBlocked && stamp === publicationStamp) { "ONE_TIME_SESSION_CHANGED" }
                block()
            }
        }
    }

    private suspend fun view(snapshot: CompletionMetricPrompt): OneTimeMetricPrompt = sessions.exclusive {
        check(tokens.localFactAccess()?.session == snapshot.session) { "ONE_TIME_SESSION_CHANGED" }
        database.withTransaction {
            val habit = requireNotNull(database.habitDao().getHabitByUuid(snapshot.row.activityUuid))
            val entries = snapshot.entries.mapIndexed { index, entry ->
                val metric = database.metricDao().getMetricByUuid(entry.metricUuid)
                OneTimeMetricEntry(metric?.id ?: -(index + 1L), entry.metricName, entry.unit, entry.decimalPlaces,
                    entry.input, entry.note, metric != null)
            }
            OneTimeMetricPrompt(snapshot, habit.id, NextPlanDeletionStore(database).displayName(habit), java.util.Collections.unmodifiableList(entries))
        }
    }
}
