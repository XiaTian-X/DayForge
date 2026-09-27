package com.dayforge.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.OneTimeTransitionException
import com.dayforge.domain.model.PendingOneTimeIntent
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneTimeLocalIntentStoreTest {
    @get:Rule val rule = PhysicalDatabaseRule()
    private val sessions = AccountSessionCoordinator()
    private lateinit var tokens: TokenManager
    private lateinit var preferences: PreferencesManager
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var habit: HabitEntity
    private val db get() = rule.database
    private fun store(coordinator: AccountSessionCoordinator = sessions) = OneTimeLocalIntentStore(db, tokens, coordinator, preferences)
    private val midnight = Instant.parse("2026-09-27T15:59:59Z").toEpochMilli()

    @Before fun setup() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "once-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        tokens = TokenManager(dataStore)
        preferences = PreferencesManager(dataStore)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        habit = seed()
    }

    @After fun teardown() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(file.delete() || !file.exists())
    }

    private suspend fun seed(uuid: String = UUID.randomUUID().toString()): HabitEntity = db.withTransaction {
        // A staged v5 activity: schedule/UI creation is not activated by this storage test.
        val row = HabitEntity(name = "Once $uuid", uuid = uuid, habitType = HabitType.CHECK_IN,
            iconResId = 0, colorHex = "#000000", schedule = HabitSchedule.Once(),
            failMode = FailMode.LOOSE, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        val id = db.habitDao().insert(row)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        row.copy(id = id)
    }

    private fun command(state: OneTimeState, time: Long = midnight) = OneTimeLocalCommand(
        habit.uuid,
        PendingOneTimeIntent(UUID.randomUUID().toString(), OneTimeIntent(UUID.randomUUID().toString(),
            if (state.completionEventUuid == null) "complete" else "undo", state.version,
            state.headEventUuid, state.completionEventUuid)), time, "Asia/Shanghai"
    )

    private suspend fun expect(reason: OneTimeLocalException.Reason, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $reason, got $error", error is OneTimeLocalException)
        assertEquals(reason, (error as OneTimeLocalException).reason)
    }

    @Test fun offlineCrossMidnightChainPersistsOriginalFactsAndOperationsAcrossReopen() = runBlocking {
        val initial = store().read(habit.uuid)
        assertNull(tokens.syncDeviceId.first())
        assertNull(initial.session.syncEpoch)
        val complete = command(initial.queue.optimisticState)
        val first = store().append(initial.session, complete)
        val undo = command(first.snapshot.queue.optimisticState, midnight + 2000)
        val second = store().append(initial.session, undo)
        val repeat = command(second.snapshot.queue.optimisticState, midnight + 3000)
        store().append(initial.session, repeat)
        val originalQueue = db.syncOutboxDao().getAll()
        val originalFacts = db.completionDao().getByHabitOnce(habit.id)
        assertEquals(listOf("complete", "undo", "complete"), originalFacts.map { it.oneTimeAction })
        assertEquals(listOf("2026-09-27", "2026-09-28", "2026-09-28"), originalFacts.map { it.recordedLocalDate })
        assertEquals(listOf(1, 0, 1), originalFacts.map { it.value })
        assertEquals(listOf(complete, undo, repeat).map { it.pending.operationId }, originalQueue.map { it.operationId })
        originalQueue.forEach { row ->
            assertEquals("one_time_completion", row.recordType)
            assertNull(row.attemptedAt)
            validateNextSyncOperation(SyncV2Operation(row.operationId, "activity_event", row.entityUuid,
                "upsert", payload = Json.parseToJsonElement(row.payloadJson!!).jsonObject))
        }
        rule.reopen()
        val restored = store().read(habit.uuid)
        assertEquals(OneTimeState(0, null, null), restored.confirmed)
        assertEquals(OneTimeState(3, repeat.pending.intent.eventUuid, repeat.pending.intent.eventUuid), restored.queue.optimisticState)
        assertEquals(originalFacts, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(originalQueue, db.syncOutboxDao().getAll())
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
    }

    @Test fun localRetryIsIdempotentButChangedContentAndReusedEventAreRejected() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        val first = store().append(snapshot.session, request)
        val frozen = db.syncOutboxDao().getAll().single()
        db.syncOutboxDao().markPrepared(frozen.id, frozen.wireEntityUuid, frozen.action, frozen.payloadJson!!,
            null, null, midnight + 100)
        rule.reopen()
        val prepared = db.syncOutboxDao().getAll().single()
        val retry = store().append(snapshot.session, request)
        assertTrue(retry.alreadyStored)
        assertEquals(first.factId, retry.factId)
        assertEquals(prepared, db.syncOutboxDao().getAll().single())
        expect(OneTimeLocalException.Reason.OPERATION_ID_REUSED) {
            store().append(snapshot.session, request.copy(occurredAtMillis = midnight + 1))
        }
        expect(OneTimeLocalException.Reason.EVENT_ID_REUSED) {
            store().append(snapshot.session, request.copy(pending = request.pending.copy(operationId = UUID.randomUUID().toString())))
        }
        assertEquals(1, db.completionDao().countAll())
        assertEquals(prepared, db.syncOutboxDao().getAll().single())
    }

    @Test fun outboxFailureRollsBackFactAndSuppressionFlagThenOriginalRequestCanRetry() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_once_outbox BEFORE INSERT ON sync_outbox
            WHEN NEW.recordType = 'one_time_completion'
            BEGIN SELECT RAISE(ABORT, 'intent write unavailable'); END
        """.trimIndent())
        val failure = runCatching { store().append(snapshot.session, request) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("intent write unavailable"))
        rule.reopen()
        assertEquals(0, db.completionDao().countAll())
        assertEquals(0, db.syncOutboxDao().count())
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
        db.openHelper.readableDatabase.query("SELECT suppressOutbox FROM sync_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_once_outbox")
        assertFalse(store().append(snapshot.session, request).alreadyStored)
        assertEquals(request.pending.operationId, db.syncOutboxDao().getAll().single().operationId)
    }

    @Test fun competingWritersCannotBothAdvanceTheSameBaseEvenWithDifferentCoordinators() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val start = CompletableDeferred<Unit>()
        val results = List(2) {
            async(Dispatchers.IO) {
                val request = command(snapshot.queue.optimisticState)
                start.await()
                runCatching { store(AccountSessionCoordinator()).append(snapshot.session, request) }
            }
        }
        start.complete(Unit)
        val completed = results.awaitAll()
        assertEquals(1, completed.count { it.isSuccess })
        assertEquals("TASK_STATE_CONFLICT", (completed.single { it.isFailure }.exceptionOrNull() as OneTimeTransitionException).code)
        assertEquals(1, db.completionDao().countAll())
        assertEquals(1, db.syncOutboxDao().count())
    }

    @Test fun sameAccountReloginAndEpochChangeInvalidateCapturedIntentsButRefreshDoesNot() = runBlocking {
        val original = store().read(habit.uuid)
        val request = command(original.queue.optimisticState)
        tokens.saveLoginSession("synthetic-a", "synthetic-r", "member", "account-a", false)
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().append(original.session, request) }
        val relogged = store().read(habit.uuid)
        tokens.saveServerIdentity("server", "epoch")
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().append(relogged.session, request) }
        val withEpoch = store().read(habit.uuid)
        val authentication = tokens.authenticationSnapshot()!!
        assertTrue(tokens.saveRefreshedTokens(authentication, "renewed", "renewed-r", "member", "account-a", false))
        store().append(withEpoch.session, request)
        tokens.resetReplicaForEpoch("server", "epoch-new")
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().append(withEpoch.session, request) }
        assertEquals(1, db.completionDao().countAll())
    }

    @Test fun accountSwitchCannotWriteThroughAnOldSnapshotEvenWhenActivityUuidIsReused() = runBlocking {
        val original = store().read(habit.uuid)
        val request = command(original.queue.optimisticState)
        sessions.exclusive {
            tokens.clearAuthenticationTokens()
            db.clearAllData()
            tokens.saveLoginSession("other-a", "other-r", "other", "account-b", false)
            habit = seed(habit.uuid)
        }
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().append(original.session, request) }
        assertEquals(0, db.completionDao().countAll())
        assertEquals(0, db.syncOutboxDao().count())
        val current = store().read(habit.uuid)
        store().append(current.session, command(current.queue.optimisticState))
        assertEquals(1, db.completionDao().countAll())
        tokens.clearAuthenticationTokens()
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().read(habit.uuid) }
    }

    @Test fun permissionsAreRecheckedAndFactsOnlyDeviceNeverDeletesTheActivity() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        tokens.saveDeviceRegistration("device", setOf("sync.read", "structure.write"), false, 1)
        expect(OneTimeLocalException.Reason.FACTS_DENIED) { store().append(snapshot.session, request) }
        assertEquals(0, db.syncOutboxDao().count())
        tokens.saveDeviceRegistration("device", setOf("sync.read", "facts.append"), false, 2)
        val complete = store().append(snapshot.session, request)
        store().append(snapshot.session, command(complete.snapshot.queue.optimisticState, midnight + 2000))
        assertEquals(habit, db.habitDao().getHabitById(habit.id))
        assertEquals(2, db.completionDao().countAll())
    }

    @Test fun rejectedPredecessorBlocksTheCausalSuffixWithoutErasingHistoryOrRenewingIds() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val complete = command(snapshot.queue.optimisticState)
        val first = store().append(snapshot.session, complete)
        val undo = command(first.snapshot.queue.optimisticState, midnight + 2000)
        val second = store().append(snapshot.session, undo)
        val rows = db.syncOutboxDao().getAll()
        db.syncOutboxDao().markDeadLetter(rows.first().id, "TASK_STATE_CONFLICT", "synthetic rejection", midnight + 3000)
        rule.reopen()
        val blocked = store().read(habit.uuid)
        assertEquals(rows.map { it.operationId }, blocked.queue.blockedOperationIds)
        expect(OneTimeLocalException.Reason.PENDING_REJECTED) {
            store().append(snapshot.session, command(second.snapshot.queue.optimisticState, midnight + 4000))
        }
        assertTrue(store().append(snapshot.session, complete).alreadyStored)
        assertEquals(2, db.completionDao().countAll())
        assertEquals(rows.map { it.operationId }, db.syncOutboxDao().getActivityIntents(habit.uuid).map { it.operationId })
    }

    @Test fun newerConfirmedBaseWithUnknownOperationResultRequiresOriginalReplayNotANewIntent() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        store().append(snapshot.session, request)
        val before = db.syncOutboxDao().getAll().single()
        db.habitDao().update(habit.copy(oneTimeConfirmedVersion = 1,
            oneTimeConfirmedHeadEventUuid = request.pending.intent.eventUuid,
            oneTimeConfirmedCompletionEventUuid = request.pending.intent.eventUuid))
        rule.reopen()
        val changed = store().read(habit.uuid)
        assertEquals(listOf(request.pending.operationId), changed.queue.awaitingReplayOperationIds)
        expect(OneTimeLocalException.Reason.PENDING_REPLAY) {
            store().append(snapshot.session, command(changed.queue.optimisticState, midnight + 2000))
        }
        assertTrue(store().append(snapshot.session, request).alreadyStored)
        assertEquals(before, db.syncOutboxDao().getAll().single())
        assertEquals(1, db.completionDao().countAll())
    }

    @Test fun uninitializedWrongModeTombstoneAndHistoricalEventIdentityFailWithoutWriting() = runBlocking {
        for (row in listOf(habit.copy(completionPolicy = null), habit.copy(habitType = HabitType.TIMER),
            habit.copy(habitType = HabitType.COUNTING), habit.copy(isCountdown = true),
            habit.copy(schedule = HabitSchedule.Daily))) {
            db.habitDao().update(row)
            assertTrue(runCatching { store().read(habit.uuid) }.isFailure)
        }
        db.habitDao().update(habit)
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("activity_event", request.pending.intent.eventUuid, 1, deleted = true))
        expect(OneTimeLocalException.Reason.EVENT_ID_REUSED) { store().append(snapshot.session, request) }
        db.syncOutboxDao().upsertState(SyncEntityStateEntity("plan_node", habit.uuid, 2, deleted = true))
        expect(OneTimeLocalException.Reason.ENTITY_DELETED) { store().append(snapshot.session, request) }
        assertEquals(0, db.completionDao().countAll())
        assertTrue(db.syncOutboxDao().getAll().none { it.recordType == "one_time_completion" })
    }

    @Test fun orphanedLocalFactAndOwnerMismatchAreNotTreatedAsConfirmedData() = runBlocking {
        val snapshot = store().read(habit.uuid)
        store().append(snapshot.session, command(snapshot.queue.optimisticState))
        db.syncOutboxDao().deleteById(db.syncOutboxDao().getAll().single().id)
        expect(OneTimeLocalException.Reason.INVALID_LOCAL_STATE) { store().read(habit.uuid) }
        tokens.prepareSyncAccount("different-owner")
        expect(OneTimeLocalException.Reason.STALE_SESSION) { store().read(habit.uuid) }
        assertEquals(1, db.completionDao().countAll())
    }

    @Test fun malformedPersistedStateAndFrozenPayloadFailWithoutRepairingHistory() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val request = command(snapshot.queue.optimisticState)
        store().append(snapshot.session, request)
        val queue = db.syncOutboxDao().getAll()
        db.habitDao().update(habit.copy(oneTimeConfirmedVersion = -1))
        expect(OneTimeLocalException.Reason.INVALID_LOCAL_STATE) { store().read(habit.uuid) }
        db.habitDao().update(habit)
        val original = db.completionDao().getByHabitOnce(habit.id).single()
        db.completionDao().updateForSync(original.copy(oneTimeAction = "invalid"))
        expect(OneTimeLocalException.Reason.INVALID_LOCAL_STATE) { store().append(snapshot.session, request) }
        // Direct corruption injection must not enqueue a second legacy mutation.
        db.completionDao().updateForSync(original)
        val row = queue.single()
        db.syncOutboxDao().markPrepared(row.id, row.wireEntityUuid, row.action, "{}", null, null, midnight)
        val corrupt = db.syncOutboxDao().getAll()
        expect(OneTimeLocalException.Reason.INVALID_LOCAL_STATE) { store().read(habit.uuid) }
        assertEquals(corrupt, db.syncOutboxDao().getAll())
        assertEquals(listOf(original), db.completionDao().getByHabitOnce(habit.id))
    }

    @Test fun validConfirmedHeadCannotConcealAnInvalidOlderTransition() = runBlocking {
        val snapshot = store().read(habit.uuid)
        val complete = command(snapshot.queue.optimisticState)
        val first = store().append(snapshot.session, complete)
        val undo = command(first.snapshot.queue.optimisticState, midnight + 2000)
        store().append(snapshot.session, undo)
        db.habitDao().update(habit.copy(oneTimeConfirmedVersion = 2,
            oneTimeConfirmedHeadEventUuid = undo.pending.intent.eventUuid))
        db.syncOutboxDao().getAll().forEach { db.syncOutboxDao().deleteById(it.id) }
        assertEquals(2, store().read(habit.uuid).confirmed.version)
        val original = db.completionDao().getCompletionByUuid(complete.pending.intent.eventUuid)!!
        // Well-shaped intent, but complete at odd version is an impossible transition.
        val corrupt = original.copy(oneTimeExpectedVersion = 1,
            oneTimeExpectedHeadEventUuid = UUID.randomUUID().toString())
        db.completionDao().updateForSync(corrupt)
        rule.reopen()
        expect(OneTimeLocalException.Reason.INVALID_LOCAL_STATE) { store().read(habit.uuid) }
        assertEquals(corrupt, db.completionDao().getCompletionByUuid(corrupt.uuid))
        assertEquals(0, db.syncOutboxDao().count())
    }
}
