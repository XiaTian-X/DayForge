package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionMetricPromptStoreTest {
    @get:Rule val storage = PhysicalDatabaseRule()
    private val db get() = storage.database
    private val sessions = AccountSessionCoordinator()
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var habit: HabitEntity
    private lateinit var metrics: List<MetricEntity>
    private var clock = Instant.parse("2026-09-27T15:59:59Z").toEpochMilli()
    private fun intents() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun prompts(coordinator: AccountSessionCoordinator = sessions) =
        CompletionMetricPromptStore(db, tokens, coordinator, now = { clock }, zone = { "Asia/Shanghai" })

    @Before fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "prompt-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        tokens = TokenManager(data)
        preferences = PreferencesManager(data)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = HabitEntity(name = "One-time", habitType = HabitType.CHECK_IN, iconResId = 0,
                colorHex = "#000000", schedule = HabitSchedule.Once(), failMode = FailMode.LOOSE,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
            habit = row.copy(id = db.habitDao().insert(row))
            metrics = (1..2).map { index ->
                val metric = MetricEntity(name = "Metric $index", unit = "kg", decimalPlaces = 2, iconResId = 0, colorHex = "#000000")
                metric.copy(id = db.metricDao().insert(metric)).also {
                    db.habitMetricLinkDao().insertForSync(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                        metricId = it.id, metricUuid = it.uuid, promptOnComplete = true))
                }
            }
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }

    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(file.delete() || !file.exists())
    }

    private suspend fun transition(): OneTimeLocalCommand {
        val state = intents().read(habit.uuid)
        val current = state.queue.optimisticState
        val request = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(UUID.randomUUID().toString(),
            OneTimeIntent(UUID.randomUUID().toString(), if (current.completionEventUuid == null) "complete" else "undo",
                current.version, current.headEventUuid, current.completionEventUuid)), clock, "Asia/Shanghai")
        intents().append(state.session, request)
        return request
    }

    private suspend fun filled(): CompletionMetricPrompt {
        val request = transition()
        val prompt = prompts().read(request.pending.intent.eventUuid)
        return prompts().updateDraft(prompt, metrics.associate { it.uuid to CompletionMetricInput("12.25", "note") })
    }

    private suspend fun expect(reason: CompletionMetricPromptException.Reason, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $reason, got $error", error is CompletionMetricPromptException)
        assertEquals(reason, (error as CompletionMetricPromptException).reason)
    }

    @Test fun promptDraftAndStableIdentitiesSurviveRestartAndSubmitOnce() = runBlocking {
        val draft = filled()
        storage.reopen()
        assertEquals(draft, prompts().pending().single())
        clock += 2000
        val saved = prompts().submit(draft)
        assertFalse(saved.alreadyStored)
        val logs = metrics.map { db.metricLogDao().getAllLogsForMetric(it.id).single() }
        assertEquals(draft.entries.map { it.observationUuid }.toSet(), logs.map { it.uuid }.toSet())
        logs.forEach { assertEquals("2026-09-28", it.recordedLocalDate); assertEquals(clock, it.date) }
        val queue = db.syncOutboxDao().getAll()
        assertEquals(draft.entries.map { it.operationId }.toSet(), queue.filter { it.recordType == "metric_log" }.map { it.operationId }.toSet())
        assertTrue(prompts().pending().isEmpty())
        storage.reopen()
        assertTrue(prompts().submit(draft).alreadyStored)
        assertEquals(queue, db.syncOutboxDao().getAll())
        // Ack consumption and intentional metric-log deletion must not resurrect observations.
        queue.filter { it.recordType == "metric_log" }.forEach { db.syncOutboxDao().deleteById(it.id) }
        logs.forEach { db.metricLogDao().delete(it) }
        val afterDelete = db.syncOutboxDao().getAll()
        assertTrue(prompts().submit(draft).alreadyStored)
        assertEquals(0, db.metricLogDao().countAll())
        assertEquals(afterDelete, db.syncOutboxDao().getAll())
    }

    @Test fun promptCreationFailureRollsBackCompletionOutboxAndReceiptTogether() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_prompt BEFORE INSERT ON completion_metric_prompts
            BEGIN SELECT RAISE(ABORT, 'prompt unavailable'); END""")
        val failure = runCatching { transition() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("prompt unavailable"))
        storage.reopen()
        assertEquals(0, db.completionDao().countAll())
        assertEquals(0, db.syncOutboxDao().count())
        assertTrue(prompts().pending().isEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM local_fact_submissions").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_prompt")
        transition()
        assertEquals(1, prompts().pending().size)
    }

    @Test fun secondOutboxFailureKeepsDraftAndTimestampButRollsBackAllObservations() = runBlocking {
        val draft = filled()
        val lastOperation = draft.entries.last().operationId
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_observation BEFORE INSERT ON sync_outbox
            WHEN NEW.operationId = '$lastOperation' BEGIN SELECT RAISE(ABORT, 'outbox unavailable'); END""")
        val before = db.syncOutboxDao().getAll()
        val failure = runCatching { prompts().submit(draft) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("outbox unavailable"))
        storage.reopen()
        val pending = prompts().pending().single()
        assertEquals(draft.entries, pending.entries)
        assertEquals(clock, pending.row.recordedAtMillis)
        assertEquals(0, db.metricLogDao().countAll())
        assertEquals(before, db.syncOutboxDao().getAll())
        draft.entries.forEach { assertNull(db.completionFollowUpDao().submission(it.operationId)) }
        db.openHelper.readableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_observation")
        val originalTime = clock
        clock += 86_400_000
        prompts().submit(pending)
        metrics.forEach { assertEquals(originalTime, db.metricLogDao().getAllLogsForMetric(it.id).single().date) }
    }

    @Test fun cancellationUndoAndRepeatDoNotReusePromptsOrEraseMetricFacts() = runBlocking {
        val first = filled()
        prompts().dismiss(first)
        prompts().dismiss(first)
        storage.reopen()
        assertTrue(prompts().pending().isEmpty())
        assertEquals(1, intents().read(habit.uuid).queue.optimisticState.version)
        transition() // undo
        assertTrue(prompts().pending().isEmpty())
        transition() // distinct completion even on the same date
        val repeated = prompts().pending().single()
        assertNotEquals(first.row.eventUuid, repeated.row.eventUuid)
        assertTrue(repeated.entries.none { it.observationUuid in first.entries.map { entry -> entry.observationUuid } })
        val selected = prompts().updateDraft(repeated, mapOf(metrics.first().uuid to CompletionMetricInput("3")))
        prompts().submit(selected)
        transition() // undo keeps the actual measurement
        assertEquals(1, db.metricLogDao().countAll())
        assertEquals("dismissed", prompts().read(first.row.eventUuid).row.state)
    }

    @Test fun neverAskAndInactiveLinksDoNotGenerateOrRetroactivelyAddPrompts() = runBlocking {
        preferences.setNeverAskAgain(habit.id, true)
        val first = transition()
        assertTrue(prompts().pending().isEmpty())
        preferences.setNeverAskAgain(habit.id, false)
        val state = intents().read(habit.uuid)
        assertTrue(intents().append(state.session, first).alreadyStored)
        assertTrue(prompts().pending().isEmpty())
        transition()
        db.habitMetricLinkDao().getAllLinksForHabit(habit.id).forEach {
            db.habitMetricLinkDao().update(it.copy(isActive = false))
        }
        transition()
        assertTrue(prompts().pending().isEmpty())
    }

    @Test fun concurrentDraftEditsUseCasAndConcurrentSubmissionsNeverDuplicate() = runBlocking {
        val draft = filled()
        val start = CompletableDeferred<Unit>()
        val edits = (1..2).map { number -> async(Dispatchers.IO) {
            start.await()
            runCatching { prompts(AccountSessionCoordinator()).updateDraft(draft,
                mapOf(metrics.first().uuid to CompletionMetricInput(number.toString()))) }
        } }
        start.complete(Unit)
        assertEquals(1, edits.awaitAll().count { it.isSuccess })
        val current = prompts().read(draft.row.eventUuid)
        val submitStart = CompletableDeferred<Unit>()
        val submits = (1..2).map { async(Dispatchers.IO) {
            submitStart.await(); prompts(AccountSessionCoordinator()).submit(current)
        } }
        submitStart.complete(Unit)
        val results = submits.awaitAll()
        assertEquals(1, results.count { !it.alreadyStored })
        assertEquals(2, db.metricLogDao().countAll())
        assertEquals(2, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
    }

    @Test fun deletedAndRecreatedLinkIsNotSilentlyAcceptedAndInputRemains() = runBlocking {
        val draft = filled()
        val link = db.habitMetricLinkDao().getLinkByUuid(draft.entries.first().linkUuid)!!
        db.habitMetricLinkDao().delete(link)
        db.habitMetricLinkDao().insertForSync(link.copy(id = 0, uuid = UUID.randomUUID().toString()))
        expect(CompletionMetricPromptException.Reason.TARGET_MISSING) { prompts().submit(draft) }
        assertEquals(draft.entries, prompts().pending().single().entries)
        assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun changedUnitsRequireExplicitRefreshAndOldSnapshotCannotSubmit() = runBlocking {
        val draft = filled()
        db.metricDao().update(metrics.first().copy(unit = "g"))
        expect(CompletionMetricPromptException.Reason.METRIC_CHANGED) { prompts().submit(draft) }
        val refreshed = prompts().refreshMetadata(draft)
        assertEquals("12.25", refreshed.entries.first { it.metricUuid == metrics.first().uuid }.input)
        expect(CompletionMetricPromptException.Reason.STALE_DRAFT) { prompts().submit(draft) }
        prompts().submit(refreshed)
        assertEquals("g", db.metricLogDao().getAllLogsForMetric(metrics.first().id).single().unit)
    }

    @Test fun invalidTextIsDurableAndNeverPartiallySaved() = runBlocking {
        val draft = filled()
        val invalid = prompts().updateDraft(draft, mapOf(metrics.first().uuid to CompletionMetricInput("-")))
        storage.reopen()
        expect(CompletionMetricPromptException.Reason.INVALID_INPUT) { prompts().submit(invalid) }
        assertEquals(invalid.entries, prompts().pending().single().entries)
        assertEquals(0, db.metricLogDao().countAll())
        val overflow = prompts().updateDraft(invalid, mapOf(metrics.first().uuid to CompletionMetricInput("1e999")))
        expect(CompletionMetricPromptException.Reason.INVALID_INPUT) { prompts().submit(overflow) }
        val partial = prompts().updateDraft(overflow, mapOf(metrics.first().uuid to CompletionMetricInput("")))
        prompts().submit(partial)
        assertEquals(1, db.metricLogDao().countAll())
    }

    @Test fun unicodeNoteLimitsAndGlobalOperationReceiptsRemainStable() = runBlocking {
        val draft = filled()
        val invalid = prompts().updateDraft(draft, mapOf(metrics.first().uuid to CompletionMetricInput("1", "📝".repeat(1001))))
        expect(CompletionMetricPromptException.Reason.INVALID_INPUT) { prompts().submit(invalid) }
        assertEquals(0, db.metricLogDao().countAll())
        val valid = prompts().updateDraft(invalid, mapOf(metrics.first().uuid to CompletionMetricInput("1", "📝".repeat(1000))))
        prompts().submit(valid)
        val state = intents().read(habit.uuid)
        val current = state.queue.optimisticState
        val request = OneTimeLocalCommand(habit.uuid, PendingOneTimeIntent(valid.entries.first().operationId,
            OneTimeIntent(UUID.randomUUID().toString(), "undo", current.version, current.headEventUuid, current.completionEventUuid)),
            clock, "Asia/Shanghai")
        assertEquals(OneTimeLocalException.Reason.OPERATION_ID_REUSED,
            (runCatching { intents().append(state.session, request) }.exceptionOrNull() as OneTimeLocalException).reason)
        assertEquals(1, db.completionDao().countAll())
        assertEquals(2, db.metricLogDao().countAll())
    }

    @Test fun tombstonesBlockSavingButCompletionConflictDoesNotDeleteRealMeasurements() = runBlocking {
        val draft = filled()
        val linkUuid = draft.entries.first().linkUuid
        listOf("activity_metric_link" to linkUuid, "plan_node" to habit.uuid, "metric" to metrics.first().uuid).forEach { (type, uuid) ->
            db.syncOutboxDao().upsertState(SyncEntityStateEntity(type, uuid, 1, deleted = true))
            expect(CompletionMetricPromptException.Reason.TARGET_MISSING) { prompts().submit(draft) }
            db.syncOutboxDao().upsertState(SyncEntityStateEntity(type, uuid, 2, deleted = false))
        }
        val completion = db.syncOutboxDao().getAll().first { it.recordType == "one_time_completion" }
        db.syncOutboxDao().markDeadLetter(completion.id, "TASK_STATE_CONFLICT", "synthetic", clock)
        prompts().submit(draft)
        assertEquals(2, db.metricLogDao().countAll())
        assertEquals("saved", prompts().read(draft.row.eventUuid).row.state)
    }

    @Test fun sessionEpochPermissionAndAccountClearProtectPromptOperations() = runBlocking {
        val draft = filled()
        tokens.saveDeviceRegistration("device", setOf("sync.read"), false, 1)
        assertEquals(OneTimeLocalException.Reason.FACTS_DENIED,
            (runCatching { prompts().submit(draft) }.exceptionOrNull() as OneTimeLocalException).reason)
        assertEquals(draft, prompts().read(draft.row.eventUuid))
        tokens.saveDeviceRegistration("device", setOf("facts.append"), false, 2)
        tokens.saveServerIdentity("server", "epoch")
        assertEquals(OneTimeLocalException.Reason.STALE_SESSION,
            (runCatching { prompts().submit(draft) }.exceptionOrNull() as OneTimeLocalException).reason)
        val current = prompts().read(draft.row.eventUuid)
        tokens.saveLoginSession("other", "other-refresh", "member", "account-a", false)
        assertEquals(OneTimeLocalException.Reason.STALE_SESSION,
            (runCatching { prompts().dismiss(current) }.exceptionOrNull() as OneTimeLocalException).reason)
        sessions.exclusive { db.clearAllData(); tokens.saveLoginSession("b", "b-r", "member", "account-b", false) }
        assertTrue(prompts().pending().isEmpty())
        assertNull(db.completionFollowUpDao().submissionForEntity("activity_event", draft.row.eventUuid))
        assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun originalCompletionRetryAfterAcknowledgementStillUsesTheSameReceipt() = runBlocking {
        val request = transition()
        val initial = intents().read(habit.uuid)
        val prompt = prompts().pending().single()
        db.habitDao().update(habit.copy(oneTimeConfirmedVersion = 1,
            oneTimeConfirmedHeadEventUuid = request.pending.intent.eventUuid,
            oneTimeConfirmedCompletionEventUuid = request.pending.intent.eventUuid))
        db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
        storage.reopen()
        assertTrue(intents().append(initial.session, request).alreadyStored)
        assertEquals(prompt, prompts().pending().single())
        assertEquals(1, db.completionDao().countAll())
        assertEquals(0, db.syncOutboxDao().count())
        assertEquals(OneTimeLocalException.Reason.OPERATION_ID_REUSED,
            (runCatching { intents().append(initial.session, request.copy(occurredAtMillis = clock + 1)) }
                .exceptionOrNull() as OneTimeLocalException).reason)
    }

    @Test fun terminalReceiptTamperingIsRejectedWithoutRewritingFactsOrQueue() = runBlocking {
        val draft = filled()
        prompts().submit(draft)
        val logs = metrics.flatMap { db.metricLogDao().getAllLogsForMetric(it.id) }
        val before = db.syncOutboxDao().getAll()
        db.openHelper.writableDatabase.execSQL("UPDATE local_fact_submissions SET payloadJson='{}' WHERE operationId=?",
            arrayOf(draft.entries.first().operationId))
        storage.reopen()
        expect(CompletionMetricPromptException.Reason.CORRUPT) { prompts().submit(draft) }
        assertEquals(logs, metrics.flatMap { db.metricLogDao().getAllLogsForMetric(it.id) })
        assertEquals(before, db.syncOutboxDao().getAll())
        val saved = prompts().read(draft.row.eventUuid)
        db.completionFollowUpDao().updatePrompt(saved.row.copy(entriesJson = "[]"))
        expect(CompletionMetricPromptException.Reason.CORRUPT) { prompts().read(draft.row.eventUuid) }
        assertEquals(before, db.syncOutboxDao().getAll())
    }
}
