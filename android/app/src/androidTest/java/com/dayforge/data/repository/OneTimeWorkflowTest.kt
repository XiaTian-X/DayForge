package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.components.MetricInputState
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.ui.screens.habitdetail.HabitDetailViewModel
import com.dayforge.ui.screens.nested.NestedHabitTreeBuilder
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual App services/readers and durable follow-up, without activating the v4 navigator. */
@RunWith(AndroidJUnit4::class)
class OneTimeWorkflowTest : NextObjectEditorFixture() {
    private val onceUiProbe = OnceUiCommitProbe()
    @get:org.junit.Rule(order = -3) val onceUiRule: org.junit.rules.TestRule = onceUiProbe.detailRule(storage)
    private suspend fun create(repo: HabitRepository, parent: String? = null, clock: Instant? = null): Long = repo.createHabit(
        "Retained once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
        parentHabitId = parent, failMode = FailMode.LOOSE, selectedMetricIds = setOf(metric.id),
        appearance = ObjectAppearance(IconReference.Role("task.reading"), "#123456", "object"),
        completionPolicy = "one_and_done", creationAuthority = if (clock == null) creator.capture()
            else ObjectCreationAuthority(local()) { clock to ZoneId.of("Asia/Shanghai") })

    private fun coordinator(repo: HabitRepository, once: OneTimeRepository) =
        LinkedMetricCoordinator(app, preferences, creatingMetrics(), repo, once)

    @Test fun completionCoordinatorRetainsTaskAndCrossDayUndoKeepsImmutableHistoryAndArchiveFlag() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once)
        val task = create(repo, clock = Instant.now().minusSeconds(172_801))
        val row = db.habitDao().getHabitById(task)!!
        val old = Instant.now().minusSeconds(172_800).toEpochMilli()
        val captured = OneTimeLocalIntentStore(db, tokens, sessions, preferences).read(row.uuid)
        OneTimeLocalIntentStore(db, tokens, sessions, preferences).append(captured.session,
            OneTimeLocalCommand(row.uuid, PendingOneTimeIntent(id(171), OneTimeIntent(id(170), "complete", 0, null, null)),
                old, "Asia/Shanghai"))
        val completion = db.completionDao().getCompletionByUuid(id(170))!!
        repo.updateIsActive(task, false, app)
        repo.undoCompletion(app, completion.id)
        assertFalse(repo.getOneTimeStatus(task).completed)
        assertEquals(completion, db.completionDao().getCompletionByUuid(completion.uuid))
        assertFalse(db.habitDao().getHabitById(task)!!.isActive)
        val undo = db.completionDao().getByHabitOnce(task).single { it.oneTimeAction == "undo" }
        assertEquals(completion.uuid, undo.oneTimeRevertsEventUuid)
        assertNotEquals(completion.recordedLocalDate, undo.recordedLocalDate)
        val service = HabitCompletionCoordinator(app, CheckInService(repo, db.completionDao(), db.timeLogDao()), repo, creatingMetrics())
        val outcome = service.checkIn(task, true) { row }
        assertFalse(outcome.shouldDeleteTemporaryTask); assertNull(outcome.goalProgress)
        assertEquals(task, outcome.metricPromptHabit!!.id)
        assertTrue(repo.getOneTimeStatus(task).completed)
        assertFalse(db.habitDao().getHabitById(task)!!.isActive)
        assertEquals(3, db.completionDao().getByHabitOnce(task).size)
        storage.reopen()
        assertTrue(onceHabits(onceRepository()).getOneTimeStatus(task).completed)
        assertEquals(3, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun dashboardNestedAndActualDetailReadLifetimeStateWithoutDailyProgressOrStreak() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once)
        val goal = HabitDraft(id = id(180), name = "Container", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "object"))
        repo.createGoal(goal, emptyList(), creationAuthority = creator.capture())
        val task = create(repo, goal.id)
        repo.logCompletion(app, task)
        val row = db.habitDao().getHabitById(task)!!
        val failure = FailureChecker(db.completionDao(), db.timeLogDao())
        val calculator = HabitStatusCalculator(failure, db.completionDao(), db.timeLogDao(), once)
        val stats = calculator.calculate(row)
        assertTrue(stats.completedForDisplay); assertFalse(stats.completedToday); assertFalse(stats.shouldCountToday)
        assertEquals(0, stats.todayCount); assertEquals(0, stats.currentStreak); assertEquals(0, stats.targetProgress)
        val builder = DashboardHabitListBuilder(calculator)
        assertTrue(builder.build(listOf(row), db.completionDao().getByHabitOnce(task), FilterMode.CHECKABLE,
            java.time.ZonedDateTime.now(), emptySet()).isEmpty())
        assertEquals(1, builder.build(listOf(row), emptyList(), FilterMode.CHECKABLE,
            java.time.ZonedDateTime.now(), setOf(task)).size)
        val tree = NestedHabitTreeBuilder(db.habitDao(), db.completionDao(), db.timeLogDao(), failure, once)
            .build(listOf(db.habitDao().getHabitByUuid(goal.id)!!), db.completionDao().getByHabitOnce(task)).single()
        assertTrue(tree.children.single().completedForDisplay); assertEquals(0, tree.totalChildren)
        val detail = withContext(Dispatchers.Main) { own(HabitDetailViewModel(app, repo, preferences,
            db.timeLogDao(), db.completionDao(), db.habitDao(), db.habitMetricLinkDao(), db.metricDao(), db.metricLogDao())) }
        onceUiProbe.requireDetailInstallation(db)
        onceUiProbe.bindDetail(detail, task)
        onceUiProbe.emit(OnceUiCommitProbe.Stage.INITIAL_LOAD_BEGIN)
        val observer = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.flow.combine(detail.uiState, detail.busyHabitIds) { state, busy ->
                state to (task in busy)
            }.collect { (state, busy) -> onceUiProbe.detail(state, busy) }
        }
        try {
        val originalWindows = OnceOriginalUiWindows(listOf("initial_load", "after_original_undo"))
        withContext(Dispatchers.Main) { detail.loadHabit(task) }
        suspend fun awaitDetail(phase: String, condition: (com.dayforge.ui.screens.habitdetail.HabitDetailUiState) -> Boolean)
            : com.dayforge.ui.screens.habitdetail.HabitDetailUiState = try {
            originalWindows.await(phase) { detail.uiState.first { condition(it) } }
        } catch (error: TimeoutCancellationException) {
            val state = detail.uiState.value
            onceUiProbe.detail(state, task in detail.busyHabitIds.value)
            onceUiProbe.emit(OnceUiCommitProbe.Stage.ORIGINAL_WINDOW_FAILED)
            throw AssertionError("Functional 45000 ms Detail wait: phase=$phase loading=${state.isLoading} " +
                "readError=${state.readError} completed=${state.oneTimeStatus?.completed} " +
                "hasCompletion=${state.lastCompletionId != null} busy=${task in detail.busyHabitIds.value} " +
                "errorPresent=${state.errorMessage != null}", error)
        }
        val loaded = awaitDetail("initial_load") { !it.isLoading && it.oneTimeStatus != null }
        assertTrue(loaded.oneTimeStatus!!.completed); assertNull(loaded.streakStats); assertNotNull(loaded.lastCompletionId)
        onceUiProbe.emit(OnceUiCommitProbe.Stage.INITIAL_LOAD_VISIBLE)
        onceUiProbe.arm(OnceUiCommitProbe.Phase.DETAIL_UNDO, task, row.uuid,
            loaded.completions.single { it.id == loaded.lastCompletionId }.uuid)
        onceUiProbe.detail(loaded, task in detail.busyHabitIds.value)
        withContext(Dispatchers.Main) { detail.undoCompletion() }
        awaitDetail("after_original_undo") { it.oneTimeStatus?.completed == false }
        onceUiProbe.emit(OnceUiCommitProbe.Stage.WINDOW_SATISFIED)
        assertEquals(2, db.completionDao().getByHabitOnce(task).size)
        onceUiProbe.emit(OnceUiCommitProbe.Stage.ORIGINAL_TAIL_REACHED)
        onceUiProbe.requireOriginalTailObserved()
        originalWindows.assertOriginalBudgetsAfterFunctionalTail()
        } finally {
            onceUiProbe.detail(detail.uiState.value, task in detail.busyHabitIds.value)
            onceUiProbe.emit(OnceUiCommitProbe.Stage.TEST_BODY_EXIT)
            observer.cancel() // No extra wait and no command/prompt cancellation here.
        }
    }

    @Test fun actualPromptPersistsTextAcrossCloseAndColdRestartAndCreatesOriginalMetricRequestAtBirth() = runBlocking<Unit> {
        register()
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        val prompt = coordinator(repo, once)
        prompt.showPromptIfNeeded(task, "ignored live name")
        val event = prompt.postCheckInState.value!!.oneTimePrompt!!.eventUuid
        prompt.savePromptDraft(event, listOf(MetricInputState(metric.id, metric.name, "kg", 3, "12.250", "saved note")))
        prompt.closePrompt()
        assertEquals(setOf(task), once.pendingHabitIds.first())
        storage.reopen()
        val reopened = coordinator(onceHabits(onceRepository()), onceRepository())
        reopened.showPromptIfNeeded(task, "")
        assertEquals("12.250", reopened.postCheckInState.value!!.oneTimePrompt!!.entries.single().input)
        withContext(Dispatchers.Main) { assertTrue(reopened.recordMetricValues(task, listOf(MetricValueInput(metric.id, 12.25, "saved note")))) }
        val queue = db.syncOutboxDao().getAll().single { it.recordType == "metric_log" }
        val original = originalIntent(queue)
        assertEquals(5, original.protocol); assertEquals(id(1), original.accountId)
        val fact = db.metricLogDao().getLogByUuid(queue.entityUuid)!!
        assertEquals(12.25, fact.value, 0.0); assertEquals("saved note", fact.note)
        withContext(Dispatchers.Main) { assertTrue(reopened.recordMetricValues(task, listOf(MetricValueInput(metric.id, 12.25, "saved note")))) }
        assertEquals(1, db.metricLogDao().countAll())
        val (http, _) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), queue.operationId))
        assertEquals(1, db.metricLogDao().countAll()); assertNull(db.syncOutboxDao().getByOperationId(queue.operationId))
        assertEquals(original, db.nextRequestDao().origin(NEXT_OPERATION, queue.operationId))
    }

    @Test fun originFailureRollsBackMetricFactAndRetryKeepsFrozenDraftInstantAndIdentities() = runBlocking<Unit> {
        val nativeFault = OneTimeOriginAbortFault()
        storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, nativeFault, storage.queryCallback) }
        storage.reopen()
        assertTrue(db.openHelper.writableDatabase.isOpen)
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        var prompt = once.prompt(task)!!
        prompt = once.saveDraft(prompt, mapOf(metric.id to ("3.5" to "same")))
        val entry = prompt.snapshot.entries.single()
        val before = db.syncOutboxDao().getAll()
        nativeFault.arm(prompt.snapshot)
        val failure = rejected { once.submit(prompt) }
        assertTrue(failure.message.orEmpty().contains("origin unavailable"))
        nativeFault.requireTriggeredAndRolledBack(failure)
        val frozen = db.completionFollowUpDao().prompt(prompt.eventUuid)!!
        assertEquals(nativeFault.frozenPrompt(), frozen)
        assertNotNull(frozen.recordedAtMillis); assertEquals("pending", frozen.state)
        assertEquals(prompt.snapshot.row.entriesJson, frozen.entriesJson)
        assertEquals(0, db.metricLogDao().countAll()); assertEquals(before, db.syncOutboxDao().getAll())
        assertNull(db.completionFollowUpDao().submission(entry.operationId))
        assertNull(db.nextRequestDao().origin(NEXT_OPERATION, entry.operationId))
        val failedOwner = db
        val failedRuntime = requireNotNull(CountTrustRuntime.forDatabase(failedOwner))
        storage.reopen()
        assertNotSame(failedOwner, db); assertFalse(failedOwner.isOpen)
        assertTrue(db.openHelper.writableDatabase.isOpen)
        assertNotSame(failedRuntime, requireNotNull(CountTrustRuntime.forDatabase(db)))
        nativeFault.requireColdFaultAbsentAndFrozenState()
        val reopened = onceRepository()
        val retry = reopened.prompt(task)!!
        assertEquals(prompt.snapshot.entries, retry.snapshot.entries)
        val noChange = reopened.saveDraft(retry, mapOf(metric.id to ("3.5" to "same")))
        assertEquals(frozen, noChange.snapshot.row)
        reopened.submit(noChange)
        val fact = db.metricLogDao().getLogByUuid(prompt.snapshot.entries.single().observationUuid)!!
        assertEquals(frozen.recordedAtMillis, fact.date)
        assertEquals(1, db.metricLogDao().countAll())
        assertEquals(entry.observationUuid, fact.uuid); assertEquals(3.5, fact.value, 0.0); assertEquals("same", fact.note)
        val queued = requireNotNull(db.syncOutboxDao().getByOperationId(entry.operationId))
        assertEquals(entry.observationUuid, queued.entityUuid); assertEquals("metric_log", queued.recordType)
        assertEquals(frozen.recordedAtMillis, queued.createdAt)
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, entry.operationId))
        assertEquals(queued.id, origin.queueId); assertEquals(entry.operationId, origin.requestId)
        assertEquals(entry.observationUuid, db.completionFollowUpDao().submission(entry.operationId)!!.entityUuid)
        assertEquals("saved", db.completionFollowUpDao().prompt(prompt.eventUuid)!!.state)
    }

    @Test fun reauthenticationClearsVisiblePromptAndStaleOwnerCannotWriteOrPublish() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        val ui = coordinator(repo, once)
        ui.showPromptIfNeeded(task, "")
        val old = ui.postCheckInState.value!!.oneTimePrompt!!
        tokens.saveLoginSession("new-auth", "new-refresh", "member", id(1), false)
        assertNull(ui.postCheckInState.value)
        rejected { once.saveDraft(old, mapOf(metric.id to ("9" to ""))) }
        rejected { once.publish(old) { fail("Stale UI publication") } }
        assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun knownFactRevocationAndRejectedOnceSuffixCannotAppendAnotherCompletion() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        val complete = repo.logCompletion(app, task)
        val row = db.completionDao().getCompletionById(complete)!!
        val queue = db.syncOutboxDao().getAll().single { it.entityUuid == row.uuid }
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET deadLetteredAt=1 WHERE id=${queue.id}")
        assertTrue(repo.getOneTimeStatus(task).blocked)
        assertFalse(repo.getOneTimeStatus(task).canChange)
        rejected { repo.undoCompletion(app, complete) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        register(permissions = setOf("sync.read"))
        rejected { repo.logCompletion(app, task) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun staleDisplayedActionCannotUndoNewCompletionOrSurviveAuthenticationReplacement() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        val initial = repo.getOneTimeStatus(task)
        val service = CheckInService(repo, db.completionDao(), db.timeLogDao())
        assertTrue((service.toggleCheckIn(app, task, oneTimeAuthority = initial.authority) as CheckInResult.Success).completed)
        rejected { service.toggleCheckIn(app, task, oneTimeAuthority = initial.authority) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
        val completed = repo.getOneTimeStatus(task)
        tokens.saveLoginSession("new", "new-refresh", "member", id(1), false)
        rejected { repo.undoCompletion(app, completed.completionId!!, completed.authority) }
        assertEquals(1, db.completionDao().getByHabitOnce(task).size)
    }

    @Test fun missingMetricTargetStillAllowsExplicitPromptSkipWithoutForgingAnObservation() = runBlocking<Unit> {
        val once = onceRepository(); val repo = onceHabits(once); val task = create(repo)
        repo.logCompletion(app, task)
        var prompt = once.prompt(task)!!
        prompt = once.saveDraft(prompt, mapOf(metric.id to ("8" to "")))
        db.metricDao().delete(metric)
        val missing = once.prompt(task)!!
        assertFalse(missing.entries.single().available)
        assertTrue(missing.entries.single().metricId < 0)
        rejected { once.submit(missing) }
        once.dismiss(missing)
        assertNull(once.prompt(task)); assertEquals(0, db.metricLogDao().countAll())
        assertTrue(repo.getOneTimeStatus(task).completed)
    }
}

/** One fixed test-only native ABORT, below the unchanged controlled factory. TEMP only;
 * no native handle, arbitrary SQL or callback is exposed to the test or production. */
private class OneTimeOriginAbortFault : SupportSQLiteOpenHelper.Factory {
    private class Attempt(val draft: CompletionMetricPromptEntity, val entry: CompletionMetricDraft) {
        val armed = AtomicBoolean(true)
        val freezeActors = AtomicInteger()
        val freezeReturned = AtomicInteger()
        val freezeMarks = AtomicInteger()
        val freezeEnds = AtomicInteger()
        val originActors = AtomicInteger()
        val originMarks = AtomicInteger()
        val originEnds = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
        val endFailure = AtomicReference<Throwable?>()
        val frozen = AtomicReference<CompletionMetricPromptEntity?>()
        @Volatile var frozenMain: MainImage? = null
        @Volatile var originalEffects = false
        @Volatile var rollbackEqual = false
    }
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val rows: List<List<Cell>>)
    private data class MainImage(val version: Int, val schema: Rows, val tables: Map<String, Rows>)
    private data class Frame(val attempt: Attempt, val raw: SupportSQLiteDatabase, val freeze: Boolean)
    private val installNext = AtomicBoolean(true)
    private val installations = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Frame?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun arm(prompt: CompletionMetricPrompt) {
        check(installations.get() == 1 && !installNext.get())
        val entry = prompt.entries.single()
        require(isContractUuid(prompt.row.eventUuid) && isContractUuid(entry.operationId) && isContractUuid(entry.observationUuid))
        require(prompt.row.state == "pending" && prompt.row.recordedAtMillis == null && prompt.row.timezone == null)
        require(entry.input == "3.5" && entry.note == "same")
        check(selected.compareAndSet(null, Attempt(prompt.row, entry)))
    }
    fun frozenPrompt(): CompletionMetricPromptEntity = requireNotNull(requireNotNull(selected.get()).frozen.get())
    fun requireTriggeredAndRolledBack(failure: Throwable) {
        val a = requireNotNull(selected.get())
        assertEquals("The original freeze UPDATE executed once", 1, a.freezeActors.get())
        assertEquals(1, a.freezeReturned.get()); assertTrue(a.freezeMarks.get() > 0); assertEquals(1, a.freezeEnds.get())
        assertNotNull(a.frozenMain); assertNotNull(a.frozen.get())
        assertFalse(a.armed.get()); assertEquals("The exact original origin INSERT executed once", 1, a.originActors.get())
        val original = requireNotNull(a.originalConstraint.get())
        assertTrue(original.message.orEmpty().contains("origin unavailable"))
        assertTrue("Propagate the same SQLite engine ABORT", generateSequence(failure) { it.cause }.any { it === original })
        assertTrue("Metric, outbox and local receipt all existed before origin failed", a.originalEffects)
        assertEquals("Failed business transaction cannot be marked successful", 0, a.originMarks.get())
        assertEquals("Actual outer native rollback ended", 1, a.originEnds.get())
        assertNull("No END or diagnostic error may substitute for the original fault", a.endFailure.get())
        assertTrue("Every MAIN typed cell/schema/rowid returns to the committed frozen image", a.rollbackEqual)
    }
    fun requireColdFaultAbsentAndFrozenState() {
        val raw = requireNotNull(opened); val a = requireNotNull(selected.get())
        assertFalse(raw.inTransaction()); assertEquals(1, installations.get()); assertFalse(installNext.get())
        assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'once_origin_fault_*'"))
        assertEquals(requireNotNull(a.frozenMain), image(raw))
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun rows(raw: SupportSQLiteDatabase, query: String): Rows = raw.query(query).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList { while (c.moveToNext()) add(columns.indices.map { i ->
            val type = c.getType(i)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Unknown SQLite cell type")
            })
        }) })
    }
    private fun image(raw: SupportSQLiteDatabase): MainImage {
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = raw.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) } }
        }
        return MainImage(raw.version, schema, tables.associateWith { table ->
            rows(raw, "SELECT rowid AS __once_fault_rowid,* FROM main.\"${table.replace("\"", "\"\"")}\" ORDER BY rowid")
        })
    }
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction()); val before = image(raw)
        raw.execSQL("CREATE TEMP TABLE once_origin_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,requestId TEXT)")
        raw.execSQL("INSERT INTO temp.once_origin_fault_gate VALUES(1,0,NULL)")
        raw.execSQL("CREATE TEMP TRIGGER once_origin_fault_abort BEFORE INSERT ON main.next_request_origins " +
            "WHEN NEW.kind='$NEXT_OPERATION' AND (SELECT enabled FROM once_origin_fault_gate WHERE id=1)=1 " +
            "AND NEW.requestId=(SELECT requestId FROM once_origin_fault_gate WHERE id=1) " +
            "BEGIN SELECT RAISE(ABORT,'origin unavailable'); END")
        check(before == image(raw)) { "Fixed TEMP fault changed MAIN" }
        check(installations.incrementAndGet() == 1)
    }
    private fun requireFrozenRow(raw: SupportSQLiteDatabase, a: Attempt) {
        val frozen = requireNotNull(a.frozen.get())
        check(scalar(raw, "SELECT count(*) FROM main.completion_metric_prompts WHERE eventUuid=? AND activityUuid=? " +
            "AND state='pending' AND revision=? AND entriesJson=? AND recordedAtMillis=? AND timezone=?",
            arrayOf<Any?>(frozen.eventUuid, frozen.activityUuid, frozen.revision, frozen.entriesJson, frozen.recordedAtMillis, frozen.timezone)) == 1L)
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, sql: String, keys: Map<Int, Any?>, action: () -> T): T {
        val a = selected.get()
        if (a == null || !a.armed.get()) return action()
        if (sql == FREEZE && keys[1] == a.draft.eventUuid && keys[8] == a.draft.eventUuid) {
            check(raw.inTransaction() && writer.get() == null)
            check(keys[2] == a.draft.activityUuid && keys[3] == "pending" && keys[4] == a.draft.revision && keys[5] == a.draft.entriesJson)
            val instant = keys[6] as? Long ?: error("Freeze must retain a real integer instant")
            val zone = keys[7] as? String ?: error("Freeze must retain its real timezone")
            check(a.freezeActors.incrementAndGet() == 1)
            writer.set(Frame(a, raw, true))
            val result = action()
            check(scalar(raw, "SELECT changes()") == 1L)
            check(a.frozen.compareAndSet(null, a.draft.copy(recordedAtMillis = instant, timezone = zone)))
            requireFrozenRow(raw, a); check(a.freezeReturned.incrementAndGet() == 1)
            return result
        }
        if (sql != ORIGIN || keys[1] != NEXT_OPERATION || keys[2] != a.entry.operationId) return action()
        check(raw.inTransaction() && writer.get() == null && a.freezeEnds.get() == 1 && a.frozenMain != null)
        val queueId = keys[3] as? Long ?: error("Actual original queue ID missing")
        check(queueId > 0 && a.armed.compareAndSet(true, false)); requireFrozenRow(raw, a)
        check(scalar(raw, "SELECT count(*) FROM main.metric_logs WHERE uuid=? AND date=? AND value=3.5 AND note='same'",
            arrayOf<Any?>(a.entry.observationUuid, a.frozen.get()!!.recordedAtMillis)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=? AND recordType='metric_log' " +
            "AND entityUuid=? AND wireEntityUuid=? AND referenceUuid=?", arrayOf<Any?>(queueId, a.entry.operationId,
                a.entry.observationUuid, a.entry.observationUuid, a.entry.metricUuid)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.local_fact_submissions WHERE operationId=? AND entityType='metric_observation' AND entityUuid=?",
            arrayOf(a.entry.operationId, a.entry.observationUuid)) == 1L)
        check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, a.entry.operationId)) == 0L)
        a.originalEffects = true
        check(scalar(raw, "SELECT enabled FROM temp.once_origin_fault_gate WHERE id=1") == 0L)
        raw.execSQL("UPDATE temp.once_origin_fault_gate SET enabled=1,requestId=? WHERE id=1", arrayOf(a.entry.operationId))
        check(scalar(raw, "SELECT changes()") == 1L)
        writer.set(Frame(a, raw, false)); check(a.originActors.incrementAndGet() == 1)
        try { action(); error("Expected native origin ABORT did not execute") }
        catch (failure: SQLiteConstraintException) {
            check(failure.message.orEmpty().contains("origin unavailable"))
            check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, a.entry.operationId)) == 0L)
            check(a.originalConstraint.compareAndSet(null, failure)); throw failure
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (sql != ORIGIN && sql != FREEZE) return statement
                        return object : SupportSQLiteStatement by statement {
                            private val keys = hashMapOf<Int, Any?>()
                            override fun clearBindings() { keys.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { keys[index] = null; statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { keys[index] = value; statement.bindLong(index, value) }
                            override fun bindDouble(index: Int, value: Double) { keys[index] = value; statement.bindDouble(index, value) }
                            override fun bindString(index: Int, value: String) { keys[index] = value; statement.bindString(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { keys[index] = value.copyOf(); statement.bindBlob(index, value) }
                            override fun execute() = execute(raw, sql, keys.toMap()) { statement.execute() }
                            override fun executeInsert() = execute(raw, sql, keys.toMap()) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, sql, keys.toMap()) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (sql != ORIGIN && sql != FREEZE) raw.execSQL(sql, bindArgs)
                        else execute(raw, sql, bindArgs.mapIndexed { index, value -> index + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        writer.get()?.takeIf { it.raw === raw }?.let {
                            (if (it.freeze) it.attempt.freezeMarks else it.attempt.originMarks).incrementAndGet()
                        }
                    }
                    override fun endTransaction() {
                        val frame = writer.get()?.takeIf { it.raw === raw }
                        if (frame == null) { raw.endTransaction(); return }
                        var returned = false
                        try { raw.endTransaction(); returned = true }
                        catch (failure: Throwable) { frame.attempt.endFailure.compareAndSet(null, failure); throw failure }
                        finally {
                            try { if (!raw.inTransaction()) {
                                try {
                                    val a = frame.attempt
                                    if (frame.freeze) {
                                        check(returned && a.freezeMarks.get() > 0); requireFrozenRow(raw, a)
                                        a.frozenMain = image(raw); a.freezeEnds.incrementAndGet()
                                    } else {
                                        check(returned); a.originEnds.incrementAndGet()
                                        a.rollbackEqual = image(raw) == requireNotNull(a.frozenMain)
                                    }
                                } finally { writer.remove() }
                            } } catch (observation: Throwable) { frame.attempt.endFailure.compareAndSet(null, observation) }
                        }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); if (installNext.compareAndSet(true, false)) install(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    private companion object {
        const val ORIGIN = "INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)"
        const val FREEZE = "UPDATE OR ABORT `completion_metric_prompts` SET `eventUuid` = ?,`activityUuid` = ?,`state` = ?,`revision` = ?,`entriesJson` = ?,`recordedAtMillis` = ?,`timezone` = ? WHERE `eventUuid` = ?"
    }
}
