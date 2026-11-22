package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical repository/consumer/prompt paths sharing one real database. No Activity Host is
 * mixed with a fixed Provider fixture. Original full audit independently calibrates H reads;
 * these tests prove the named readers/builders, not launcher rendering or release latency.
 */
@RunWith(AndroidJUnit4::class)
class OnceProjectionConsumerTest : NextCoreRequestFixture() {
    private val fullHistory = AtomicInteger()
    private val fault = PromptNativeFault()
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context,
        object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val text = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), "")
                if (text == "select*fromcompletionswherehabitid=?orderbyid") fullHistory.incrementAndGet()
            }
        }, fault) } }
    private fun localOnce() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private fun once() = OneTimeRepository(db, tokens, sessions, preferences)
    private fun prompts() = CompletionMetricPromptStore(db, tokens, sessions)
    private fun repository() = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
        oneTimeRepository = once())
    private fun facts() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private fun calculator() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao()),
        db.completionDao(), db.timeLogDao(), once())
    private var parent: HabitEntity? = null

    private suspend fun configure(pair: Boolean = false, links: Boolean = true, deletedMetricMaximum: Boolean = false) {
        register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val goal = if (pair) habit.copy(id = 0, uuid = id(81_000), name = "Once container",
                habitType = HabitType.GOAL, targetValue = 1, completionPolicy = null,
                appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "object")) else null
            parent = goal?.let { it.copy(id = db.habitDao().insert(it)) }
            habit = habit.copy(habitType = HabitType.CHECK_IN, name = "First once", schedule = HabitSchedule.Once(),
                targetValue = 1, isCountdown = false, targetCycles = null, bestTime = null,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                oneTimeConfirmedHeadEventUuid = null, oneTimeConfirmedCompletionEventUuid = null,
                parentHabitId = parent?.uuid,
                appearance = requireNotNull(habit.appearance).copy(icon = IconReference.Role("task.custom")))
            db.habitDao().update(habit)
            if (pair) {
                timerHabit = timerHabit.copy(habitType = HabitType.CHECK_IN, name = "Second once",
                    schedule = HabitSchedule.Once(), targetValue = 1, isCountdown = false, targetCycles = null,
                    bestTime = null, completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
                    oneTimeConfirmedHeadEventUuid = null, oneTimeConfirmedCompletionEventUuid = null,
                    planMetadata = habit.planMetadata, parentHabitId = parent?.uuid,
                    appearance = requireNotNull(timerHabit.appearance).copy(icon = IconReference.Role("task.custom")))
                db.habitDao().update(timerHabit)
            } else db.habitDao().delete(timerHabit)
            if (links) db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id,
                habitUuid = habit.uuid, metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            if (deletedMetricMaximum) {
                assertEquals(9_000L, db.metricLogDao().insertForSync(MetricLogEntity(id = 9_000L,
                    metricId = metric.id, date = millis, value = 1.0, unit = metric.unit,
                    uuid = id(81_050), createdAt = millis, updatedAt = millis, recordedTimezone = "Asia/Shanghai")))
                sql.execSQL("DELETE FROM metric_logs WHERE id=?", arrayOf(9_000L))
                assertEquals(0, db.metricLogDao().countAll())
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private suspend fun append(n: Int = 81_100): OneTimeLocalAppendResult {
        val before = localOnce().read(habit.uuid)
        val state = before.queue.optimisticState
        return localOnce().append(before.session, OneTimeLocalCommand(habit.uuid,
            PendingOneTimeIntent(id(n), OneTimeIntent(id(n + 1000),
                if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
                state.completionEventUuid)), millis + n, "Asia/Shanghai"))
    }
    private suspend fun oracle(uuid: String = habit.uuid) = sessions.exclusive { db.withTransaction {
        localOnce().readOriginalInTransaction(uuid, local())
    } }
    private fun dirty() = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun root(): CountTrustSeal.Header {
        assertEquals(0L, dirty())
        return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
    }
    private suspend fun image() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun warm(phase: String): OneTimeLocalSnapshot {
        val expected = oracle()
        fullHistory.set(0)
        val result = localOnce().read(habit.uuid)
        assertEquals("Warm $phase must avoid the calibrated retained-history DAO", 0, fullHistory.get())
        assertEquals(expected, result); root()
        return result
    }

    @Test(timeout = 150_000)
    fun pairedDashboardNestedAndWidgetReadersKeepFullStatusAndReadOnlyWarmState() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(pair = true)
        val saved = append()
        val expected = oracle(); val second = oracle(timerHabit.uuid)
        assertTrue(fullHistory.get() > 0)
        val before = image(); val originalRoot = root()
        fullHistory.set(0)
        val statuses = once().readBatch(listOf(habit, timerHabit))
        assertTrue(statuses.getValue(habit.id).completed)
        assertFalse(statuses.getValue(timerHabit.id).completed)
        statuses.values.forEach { assertTrue(it.canChange); assertFalse(it.awaitingReplay); assertFalse(it.blocked); assertNotNull(it.authority) }
        assertEquals(saved.factId, statuses.getValue(habit.id).completionId)
        val clock = ZonedDateTime.now()
        val countReader = CountHistoryReader(db, tokens, sessions)
        val checkReader = CheckHistoryReader(db, tokens, sessions)
        val batch = DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions),
            countReader, checkReader).readProjection(clock)
        val cards = DashboardHabitListBuilder(calculator()).project(batch).filter { it.habit.id in setOf(habit.id, timerHabit.id) }
        assertEquals(2, cards.size)
        assertEquals(true, cards.single { it.habit.id == habit.id }.completedForDisplay)
        assertEquals(false, cards.single { it.habit.id == timerHabit.id }.completedForDisplay)
        cards.forEach { assertEquals(0, it.todayCount); assertEquals(0, it.currentStreak); assertEquals(0, it.targetProgress) }
        val nested = NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(),
            FailureChecker(db.completionDao(), db.timeLogDao()), once()).build(listOf(requireNotNull(parent)), emptyList()).single()
        assertEquals(2, nested.children.size)
        assertTrue(nested.children.single { it.habit.id == habit.id }.completedForDisplay)
        val widget = facts().read(habit)
        assertEquals(expected.queue.optimisticState, widget.claim.oneTimeState)
        assertEquals(expected.queue.optimisticState.completionEventUuid, widget.claim.completionUuid)
        assertTrue(widget.completed); assertNull(widget.claim.countPolicy)
        assertEquals(second.queue.optimisticState, facts().read(timerHabit).claim.oneTimeState)
        assertEquals(0, fullHistory.get())
        assertEquals(originalRoot, root()); assertEquals(before, image())
        val undone = append(81_101); assertNotEquals(saved.factId, undone.factId)
        assertNotNull(db.completionDao().getCompletionByUuid(expected.queue.optimisticState.completionEventUuid!!))
        assertNull(warm("consumer undo").queue.optimisticState.completionEventUuid)
    }

    @Test(timeout = 150_000)
    fun draftRefreshDismissAndRepeatedDismissKeepQualificationAndOriginalCas() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val saved = append(); val event = requireNotNull(db.completionDao().getCompletionById(saved.factId)).uuid
        val initial = prompts().read(event)
        val drafted = prompts().updateDraft(initial, mapOf(metric.uuid to CompletionMetricInput("12.", "draft")))
        assertEquals("12.", drafted.entries.single().input); warm("draft")
        val refreshed = prompts().refreshMetadata(drafted)
        assertEquals(drafted.row.revision + 1, refreshed.row.revision)
        assertEquals("12.", refreshed.entries.single().input); warm("metadata refresh")
        prompts().dismiss(refreshed); warm("dismiss")
        assertEquals("dismissed", db.completionFollowUpDao().prompt(event)!!.state)
        assertEquals(0, db.metricLogDao().countAll())
        val before = image(); prompts().dismiss(refreshed); assertEquals(before, image())
        val stale = runCatching { prompts().updateDraft(drafted, emptyMap()) }.exceptionOrNull()
        assertTrue(stale is CompletionMetricPromptException)
        assertEquals(before, image())
        append(81_102); assertNull(warm("undo after dismiss").queue.optimisticState.completionEventUuid)
    }

    @Test(timeout = 150_000)
    fun submitKeepsSeparateFrozenCommitRollbackAndOriginalSavedRetryWarm() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(deletedMetricMaximum = true); val saved = append(); val event = requireNotNull(db.completionDao().getCompletionById(saved.factId)).uuid
        val initial = prompts().read(event)
        val draft = prompts().updateDraft(initial, mapOf(metric.uuid to CompletionMetricInput("3.5", "same")))
        warm("before submit")
        val old = root(); val queues = db.syncOutboxDao().getActivityIntents(habit.uuid)
        fault.abortNextOrigin.set(true)
        val error = runCatching { prompts().submit(draft) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(generateSequence(error) { it.cause }.any { it is SQLiteConstraintException && it.message.orEmpty().contains("ONCE_PROMPT_TEST_ORIGIN_ABORT") })
        assertFalse(fault.abortNextOrigin.get())
        val frozen = requireNotNull(db.completionFollowUpDao().prompt(event))
        assertNotNull(frozen.recordedAtMillis); assertNotNull(frozen.timezone)
        assertEquals("pending", frozen.state); assertEquals(draft.row.entriesJson, frozen.entriesJson)
        assertEquals(draft.row.revision, frozen.revision)
        assertEquals(old.generation + 1, root().generation)
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(queues, db.syncOutboxDao().getActivityIntents(habit.uuid))
        warm("committed freeze after original origin rollback")
        val result = prompts().submit(prompts().read(event))
        assertFalse(result.alreadyStored); assertEquals(listOf(draft.entries.single().observationUuid), result.observationUuids)
        val finalPrompt = requireNotNull(db.completionFollowUpDao().prompt(event))
        assertEquals(frozen.recordedAtMillis, finalPrompt.recordedAtMillis); assertEquals(frozen.timezone, finalPrompt.timezone)
        assertEquals("saved", finalPrompt.state); assertEquals(1, db.metricLogDao().countAll())
        assertEquals(9_001L, requireNotNull(db.metricLogDao().getLogByUuid(result.observationUuids.single())).id)
        db.withTransaction {
            db.openHelper.writableDatabase.query("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'").use {
                assertTrue("Real TEMP journal sequence is present during persistent-ID calibration", it.moveToFirst())
                assertTrue(it.getLong(0) > 0)
            }
        }
        warm("metric save after deleted persistent maximum")
        val beforeReplay = image()
        val repeated = prompts().submit(prompts().read(event))
        assertTrue(repeated.alreadyStored); assertEquals(result.observationUuids, repeated.observationUuids)
        assertEquals(beforeReplay, image()); warm("already saved retry")
        val operation = draft.entries.single().operationId
        val queuedMetric = requireNotNull(db.syncOutboxDao().getByOperationId(operation))
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, operation))
        val submission = requireNotNull(db.completionFollowUpDao().submission(operation))
        val (http, _) = channel { successReply(it) }
        fullHistory.set(0)
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), operation))
        android.util.Log.i("OncePromptTrustCost", "phase=original_metric_ack full_history_queries=${fullHistory.get()} " +
            "elapsed_ns=${android.os.SystemClock.elapsedRealtimeNanos() - started}")
        assertNull(db.syncOutboxDao().getById(queuedMetric.id))
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, operation))
        assertEquals(submission, db.completionFollowUpDao().submission(operation))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, operation))
        warm("real metric ACK after prompt")
        val afterAck = image()
        assertTrue(prompts().submit(prompts().read(event)).alreadyStored)
        assertEquals(afterAck, image())
        append(81_103); assertNull(warm("undo after saved metrics").queue.optimisticState.completionEventUuid)
        assertEquals(1, db.metricLogDao().countAll()); assertEquals(finalPrompt, db.completionFollowUpDao().prompt(event))
    }

    @Test(timeout = 150_000)
    fun sameValueExtraNativePromptWriteKeepsDirtyAndFullFallbackInsteadOfSigning() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val saved = append(); val event = requireNotNull(db.completionDao().getCompletionById(saved.factId)).uuid
        val original = root(); val prompt = prompts().read(event)
        fault.duplicatePrompt.set(event)
        val changed = prompts().updateDraft(prompt, mapOf(metric.uuid to CompletionMetricInput("8", "extra event")))
        assertNull(fault.duplicatePrompt.get())
        assertEquals("8", changed.entries.single().input)
        assertEquals(1L, dirty()); assertEquals(original, CountTrustSeal.read(db.openHelper.writableDatabase))
        val expected = oracle()
        fullHistory.set(0)
        assertEquals(expected, localOnce().read(habit.uuid))
        assertTrue("The dirty reader itself must take the full audit", fullHistory.get() > 0)
        assertEquals(1L, dirty())
        assertEquals(0, db.metricLogDao().countAll())
    }

    /** Native fixed SQLite ABORT and same-connection extra event, without exposing a handle.
     * The TEMP gate is test-only and cannot change MAIN or manufacture commit evidence.
     */
    private class PromptNativeFault : SupportSQLiteOpenHelper.Factory {
        val abortNextOrigin = AtomicBoolean()
        val duplicatePrompt = AtomicReference<String?>()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var wrapped: SupportSQLiteDatabase? = null
            val lock = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(lock) {
                if (original !== raw) {
                    original = raw
                    wrapped = object : SupportSQLiteDatabase by raw {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = raw.compileStatement(sql)
                            val normalized = sql.lowercase().replace("`", "")
                            return object : SupportSQLiteStatement by statement {
                                private fun <T> executeOriginal(action: () -> T): T {
                                    val abort = normalized.contains("into next_request_origins") && abortNextOrigin.compareAndSet(true, false)
                                    if (abort) raw.execSQL("UPDATE temp.once_prompt_abort_gate SET enabled=1")
                                    try {
                                        val result = action()
                                        if (Regex("^update(?:\\s+or\\s+\\w+)?\\s+completion_metric_prompts\\s+set\\s").containsMatchIn(normalized.trim()) &&
                                            duplicatePrompt.get() != null) {
                                            check(raw.inTransaction())
                                            fun changed() = raw.query("SELECT changes()").use { cursor ->
                                                check(cursor.moveToFirst()); val count = cursor.getLong(0)
                                                check(!cursor.moveToNext()); count
                                            }
                                            check(changed() == 1L)
                                            val event = requireNotNull(duplicatePrompt.getAndSet(null))
                                            raw.execSQL("UPDATE completion_metric_prompts SET revision=revision WHERE eventUuid=?", arrayOf(event))
                                            check(changed() == 1L)
                                        }
                                        return result
                                    } finally { if (abort) raw.execSQL("UPDATE temp.once_prompt_abort_gate SET enabled=0") }
                                }
                                override fun execute() = executeOriginal { statement.execute() }
                                override fun executeInsert(): Long = executeOriginal { statement.executeInsert() }
                                override fun executeUpdateDelete(): Int = executeOriginal { statement.executeUpdateDelete() }
                            }
                        }
                    }
                }
                requireNotNull(wrapped)
            }
            val parent = configuration.callback
            val callbacks = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) {
                    parent.onOpen(wrap(db))
                    db.execSQL("CREATE TEMP TABLE once_prompt_abort_gate(enabled INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO temp.once_prompt_abort_gate VALUES(0)")
                    db.execSQL("CREATE TEMP TRIGGER once_prompt_abort BEFORE INSERT ON main.next_request_origins " +
                        "WHEN (SELECT enabled FROM once_prompt_abort_gate)=1 BEGIN SELECT RAISE(ABORT,'ONCE_PROMPT_TEST_ORIGIN_ABORT'); END")
                }
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }
}
