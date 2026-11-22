package com.dayforge.data.repository

import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.DatabaseEpochTestRooms
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PhysicalDatabaseRule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Two fixed UI diagnostics only. Observes original native calls; no query, injected fault,
 * SQL execution, retry, listener replacement or writable handle escapes. A returned END is
 * classified separately from coroutine completion and from the visible result. */
internal class OnceUiCommitProbe : SupportSQLiteOpenHelper.Factory {
    enum class Phase { DETAIL_UNDO, WIDGET_INITIAL_TOGGLE, WIDGET_STALE_CLAIM, WIDGET_FRESH_UNDO }
    enum class Stage { INITIAL_LOAD_BEGIN, INITIAL_LOAD_VISIBLE, ACTION_ENTER, WINDOW_SATISFIED,
        ORIGINAL_WINDOW_FAILED, ORIGINAL_TAIL_REACHED, TEST_BODY_EXIT, CLEANUP_ENTER, RULE_EXIT }
    enum class Life { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED, JOB_COMPLETED }
    private enum class Mode { DETAIL, WIDGET }
    private enum class Api { EXECUTE, INSERT, UPDATE_DELETE, EXEC_SQL }
    private data class Target(val habitId: Long, val habitUuid: String, val action: String,
        val reverts: String?)
    private class Attempt(val phase: Phase, val target: Target) {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        var matched = 0
        var insertReturned = 0
        var insertError = "NONE"
        var outerMarked = false
        var end = "NOT_REACHED"
        var fault = false
        var detail = "NOT_OBSERVED"
        val events = ArrayList<String>()
        var overflow = false
    }
    private class Frame(val parent: Frame?, val beganInside: Boolean) {
        val root: Frame = parent?.root ?: this
        var successful = false
        var nestedRollback = false
        var trackingGap = false
        val attempts = linkedSetOf<Attempt>()
    }
    private class NativeState {
        private val frames = ThreadLocal.withInitial { ArrayList<Frame>() }
        fun stack(): ArrayList<Frame> = checkNotNull(frames.get()) { "ONCE_UI_PROBE_STACK_UNAVAILABLE" }
    }
    private val monitor = Any()
    private var mode: Mode? = null
    private var installed = false
    private var factoryCreates = 0
    private var nativeOpens = 0
    private var nativeBegins = 0
    private var activeNativeFrames = 0
    private var exactCompiles = 0
    private var observerFault = false
    private var active: Attempt? = null
    private val attempts = ArrayList<Attempt>()
    private var detailRoom: HabitDatabase? = null
    private var detailModel: com.dayforge.ui.screens.habitdetail.HabitDetailViewModel? = null
    private var detailHabitId = 0L
    private var widgetConfiguration: DatabaseEpochTestRooms.Configuration? = null
    private var widgetHost: com.dayforge.data.local.DatabaseRuntimeHost? = null
    private var widgetHostMatches = 0
    private val lifecycle = ArrayList<String>()
    private var lifecycleOverflow = false

    fun detailRule(storage: PhysicalDatabaseRule): TestRule = rule(storage, Mode.DETAIL,
        "dashboardNestedAndActualDetailReadLifetimeStateWithoutDailyProgressOrStreak")
    fun widgetRule(storage: PhysicalDatabaseRule): TestRule = rule(storage, Mode.WIDGET,
        "realWidgetActivityCompletesAndRejectsStaleClickWithoutAnotherToggle")

    private fun rule(storage: PhysicalDatabaseRule, kind: Mode, method: String) = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                if (description.methodName != method) { base.evaluate(); return }
                val previous = storage.isolatedBuilder
                check(previous == null) { "ONCE_UI_PROBE_BUILDER_ALREADY_SELECTED" }
                synchronized(monitor) { check(!installed); installed = true; mode = kind }
                if (kind == Mode.DETAIL) {
                    // Before the original PhysicalDatabaseRule opens its one Room. No reopen.
                    storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context,
                        storage.queryCallback, this@OnceUiCommitProbe).also { detailRoom = it } }
                } else {
                    // The existing Hilt test module supplies this native delegate to each REAL
                    // Host builder. The fixed fixture DB/Provider is deliberately not replaced.
                    widgetConfiguration = DatabaseEpochTestRooms.install(null, this@OnceUiCommitProbe)
                }
                var originalFailure: Throwable? = null
                try { base.evaluate() }
                catch (failure: Throwable) { originalFailure = failure; throw failure }
                finally { try {
                    emit(Stage.RULE_EXIT)
                    storage.isolatedBuilder = previous
                    widgetConfiguration?.let { configuration ->
                        // Original inner platform/Host/DB cleanup has already run. Do not
                        // release an observer configuration while a Host still owns work.
                        check(com.dayforge.data.local.DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) {
                            "ONCE_UI_PROBE_HOST_CLEANUP_UNKNOWN"
                        }
                        DatabaseEpochTestRooms.release(configuration)
                    }
                } catch (cleanup: Throwable) {
                    val original = originalFailure
                    if (original == null) throw cleanup
                    if (original !== cleanup) original.addSuppressed(cleanup)
                } }
            }
        }
    }

    fun requireDetailInstallation(database: HabitDatabase) {
        check(mode == Mode.DETAIL && detailRoom === database && installed) { "ONCE_UI_PROBE_WRONG_DETAIL_ROOM" }
        synchronized(monitor) { check(factoryCreates > 0 && nativeOpens > 0 && nativeBegins > 0) {
            "ONCE_UI_PROBE_NATIVE_NOT_OBSERVED"
        } }
    }
    fun bindDetail(model: com.dayforge.ui.screens.habitdetail.HabitDetailViewModel, habitId: Long) {
        check(mode == Mode.DETAIL && detailModel == null && habitId > 0)
        detailModel = model; detailHabitId = habitId
    }
    fun bindWidgetHost(host: com.dayforge.data.local.DatabaseRuntimeHost) {
        check(mode == Mode.WIDGET && widgetConfiguration?.native === this && installed)
        check(widgetHost == null); widgetHost = host
    }

    fun arm(phase: Phase, habitId: Long, habitUuid: String, reverts: String?) {
        check(habitId > 0 && com.dayforge.domain.model.isContractUuid(habitUuid))
        check(reverts == null || com.dayforge.domain.model.isContractUuid(reverts))
        val undo = phase == Phase.DETAIL_UNDO || phase == Phase.WIDGET_FRESH_UNDO
        check(undo == (reverts != null)) { "ONCE_UI_PROBE_TARGET_SHAPE" }
        synchronized(monitor) {
            check(installed && (phase == Phase.DETAIL_UNDO) == (mode == Mode.DETAIL))
            val next = Attempt(phase, Target(habitId, habitUuid, if (undo) "undo" else "complete", reverts))
            active = next; attempts += next
        }
        emit(Stage.ACTION_ENTER)
    }

    /** Called by one test-owned StateFlow observer. Booleans only, no UI payload or error text. */
    fun detail(state: com.dayforge.ui.screens.habitdetail.HabitDetailUiState, busy: Boolean) = observe {
        synchronized(monitor) {
            val a = active ?: return@synchronized
            val error = when {
                state.errorMessage == null -> "NONE"
                state.errorMessage == "ONE_TIME_ACTION_EXPIRED" -> "ACTION_EXPIRED"
                state.errorMessage == "ONE_TIME_STATE_CHANGED" -> "STATE_CHANGED"
                state.errorMessage == "ONE_TIME_COMPLETION_CHANGED" -> "COMPLETION_CHANGED"
                state.errorMessage == "ONE_TIME_PENDING_OR_DENIED" -> "PENDING_OR_DENIED"
                else -> "OTHER_PRESENT"
            }
            val value = "loading=${state.isLoading},readError=${state.readError},completed=${state.oneTimeStatus?.completed}," +
                "hasCompletion=${state.lastCompletionId != null},busy=$busy,error=$error"
            if (a.detail != value) { a.detail = value; event(a, "DETAIL:$value") }
        }
    }

    /** Existing platform observer invokes this; it creates no Activity, Job or extra collector. */
    fun lifecycle(activity: android.app.Activity, stage: Life, failure: Throwable? = null) = observe {
        val kind = when (activity) {
            is com.dayforge.widget.checkin.WidgetFactActionActivity -> "ACTION"
            is com.dayforge.widget.checkin.MetricPromptActivity -> "PROMPT"
            else -> return@observe
        }
        synchronized(monitor) {
            if (stage == Life.CREATED && activity is com.dayforge.widget.checkin.WidgetFactActionActivity) {
                if (widgetHost != null && activity.databaseHost === widgetHost) widgetHostMatches++ else observerFault = true
            }
            val value = "$kind:$stage:${errorKind(failure)}"
            if (lifecycle.size < 64) lifecycle += "${android.os.SystemClock.elapsedRealtimeNanos()}:$value"
            else lifecycleOverflow = true
            active?.let { event(it, value) }
        }
    }

    fun emit(stage: Stage) {
        if (stage == Stage.RULE_EXIT) detailModel?.let { model ->
            detail(model.uiState.value, detailHabitId in model.busyHabitIds.value)
        } // Final memory-only observation after original VM cleanup, never a new read/await.
        val lines = synchronized(monitor) {
            val configuration = widgetConfiguration
            buildList {
                add("stage=$stage mode=$mode installed=$installed factories=$factoryCreates natives=$nativeOpens " +
                    "begins=$nativeBegins activeNativeFrames=$activeNativeFrames exactCompiles=$exactCompiles observerFault=$observerFault " +
                    "hostMatches=$widgetHostMatches hostCreates=${configuration?.creates?.get() ?: 0} " +
                    "hostRooms=${configuration?.createdRooms?.size ?: 0}")
                attempts.forEach { a ->
                    add("stage=$stage phase=${a.phase} matched=${a.matched} insertReturned=${a.insertReturned} " +
                        "insertError=${a.insertError} outerMarked=${a.outerMarked} end=${a.end} observerFault=${a.fault} " +
                        "overflow=${a.overflow} detail=${a.detail}")
                    a.events.chunked(6).forEachIndexed { index, values ->
                        add("stage=$stage phase=${a.phase} eventsPart=$index ${values.joinToString("|")}")
                    }
                }
                lifecycle.chunked(6).forEachIndexed { index, values ->
                    add("stage=$stage lifecyclePart=$index overflow=$lifecycleOverflow ${values.joinToString("|")}")
                }
            }
        }
        // Bounded fixed-vocabulary lines stay below logcat's single-message truncation.
        // Native callbacks themselves only record memory; never log binds or exception text.
        lines.forEach { android.util.Log.i("OnceUiCommitProbe", it) }
    }

    /** Additional positive calibration only AFTER the original assertions have reached their tail. */
    fun requireOriginalTailObserved() = synchronized(monitor) {
        check(installed && !observerFault && factoryCreates > 0 && nativeOpens > 0 && exactCompiles > 0)
        if (mode == Mode.WIDGET) {
            check(widgetHostMatches == 3 && widgetConfiguration?.native === this)
            check(widgetConfiguration?.createdRooms?.size == 1 && widgetConfiguration?.creates?.get() == 1) {
                "ONCE_UI_PROBE_HOST_CHANGED"
            }
        }
        check(attempts.size == if (mode == Mode.DETAIL) 1 else 3)
        attempts.forEach { a ->
            check(!a.fault && !a.overflow)
            if (a.phase == Phase.WIDGET_STALE_CLAIM) check(a.matched == 0) { "ONCE_UI_PROBE_STALE_ACTOR_REACHED" }
            else check(a.matched == 1 && a.insertReturned == 1 && a.insertError == "NONE" &&
                a.outerMarked && a.end == "COMMIT_RETURNED") { "ONCE_UI_PROBE_EXPECTED_NATIVE_COMMIT_NOT_OBSERVED" }
        }
    }

    private fun errorKind(error: Throwable?): String = when (error) {
        null -> "NONE"
        is kotlinx.coroutines.CancellationException -> "CANCELLED"
        is android.database.sqlite.SQLiteException -> "SQLITE"
        else -> "OTHER"
    }
    private fun event(a: Attempt, value: String) {
        if (a.events.size < 96) a.events += "${(android.os.SystemClock.elapsedRealtimeNanos() - a.started) / 1_000_000}:$value"
        else a.overflow = true
    }
    private inline fun observe(block: () -> Unit) {
        try { block() } catch (_: Throwable) { synchronized(monitor) { observerFault = true; active?.fault = true } }
    }

    private fun begin(raw: SupportSQLiteDatabase, state: NativeState, action: () -> Unit) {
        var frame: Frame? = null
        observe {
            val stack = state.stack()
            frame = Frame(stack.lastOrNull(), raw.inTransaction()).also { next ->
                if (next.beganInside != stack.isNotEmpty()) next.root.trackingGap = true
                stack += next
                synchronized(monitor) { activeNativeFrames++ }
            }
        }
        try {
            action()
            observe { synchronized(monitor) { nativeBegins++ } }
        } catch (error: Throwable) {
            observe {
                val stack = state.stack()
                frame?.let { f ->
                    f.root.trackingGap = true
                    synchronized(monitor) { f.root.attempts.forEach { a -> event(a, "BEGIN_EXCEPTION:${errorKind(error)}"); a.fault = true } }
                    if (stack.lastOrNull() === f) {
                        stack.removeAt(stack.lastIndex)
                        synchronized(monitor) { activeNativeFrames-- }
                    }
                }
            }
            throw error
        }
    }
    private fun marked(state: NativeState, action: () -> Unit) {
        try {
            action()
            observe {
                val frame = state.stack().lastOrNull()
                if (frame == null) { synchronized(monitor) { observerFault = true }; return@observe }
                frame.successful = true
                synchronized(monitor) { frame.root.attempts.forEach { a ->
                    if (frame.parent == null) a.outerMarked = true
                    event(a, if (frame.parent == null) "OUTER_SUCCESS_RETURNED" else "NESTED_SUCCESS_RETURNED")
                } }
            }
        } catch (error: Throwable) {
            observe { synchronized(monitor) { state.stack().lastOrNull()?.root?.attempts?.forEach {
                event(it, "SUCCESS_EXCEPTION:${errorKind(error)}")
            } } }
            throw error
        }
    }
    private fun end(raw: SupportSQLiteDatabase, state: NativeState, action: () -> Unit) {
        val frame = state.stack().lastOrNull()
        observe {
            val tracked = frame ?: return@observe
            synchronized(monitor) { tracked.root.attempts.forEach { a ->
                if (tracked.parent == null) a.end = "IN_PROGRESS"
                event(a, if (tracked.parent == null) "OUTER_END_ENTER" else "NESTED_END_ENTER")
            } }
        }
        var returned = false
        var failure: Throwable? = null
        try { action(); returned = true }
        catch (error: Throwable) { failure = error; throw error }
        finally { observe {
            val stillInside = raw.inTransaction() // Native state only; no SQL/rowid/changes read.
            val stack = state.stack()
            if (frame == null || stack.lastOrNull() !== frame) synchronized(monitor) { observerFault = true }
            else {
                stack.removeAt(stack.lastIndex)
                synchronized(monitor) { activeNativeFrames-- }
                if (frame.parent != null && (!frame.successful || !returned)) frame.root.nestedRollback = true
                if (frame.parent != null && !stillInside) frame.root.trackingGap = true
                synchronized(monitor) { frame.root.attempts.forEach { a ->
                    if (frame.parent == null) {
                        a.end = when {
                            stillInside && failure != null -> "EXCEPTION_STILL_OPEN"
                            stillInside -> "RETURNED_STILL_OPEN"
                            failure != null -> "EXCEPTION_CLOSED_UNKNOWN"
                            frame.root.trackingGap -> "CLOSED_TRACKING_UNKNOWN"
                            frame.successful && !frame.nestedRollback -> "COMMIT_RETURNED"
                            else -> "ROLLBACK_RETURNED"
                        }
                        event(a, "ACTUAL_END:${a.end}:${errorKind(failure)}")
                    } else event(a, "NESTED_END_RETURNED=$returned:stillInside=$stillInside")
                } }
            }
        } }
    }
    private fun <T> insert(state: NativeState, api: Api, habitId: Long?, habitUuid: String?,
        actionName: String?, reverts: String?, factUuid: String?, action: () -> T): T {
        var observed: Attempt? = null
        observe {
            synchronized(monitor) {
                val a = active
                if (a != null && a.target == Target(habitId ?: -1, habitUuid.orEmpty(), actionName.orEmpty(), reverts)) {
                    observed = a; a.matched++; a.end = "TRANSACTION_IN_PROGRESS"
                    val frame = state.stack().lastOrNull()
                    if (frame == null || factUuid == null || !com.dayforge.domain.model.isContractUuid(factUuid)) a.fault = true
                    else { frame.root.attempts += a; if (frame.root.trackingGap) a.fault = true }
                    event(a, "FACT_INSERT_ENTER:$api:depth=${state.stack().size}")
                }
            }
        }
        try {
            val result = action()
            observe { synchronized(monitor) { observed?.let { it.insertReturned++; event(it, "FACT_INSERT_RETURNED:$api") } } }
            return result
        } catch (error: Throwable) {
            observe { synchronized(monitor) { observed?.let { it.insertError = errorKind(error); event(it, "FACT_INSERT_EXCEPTION:${it.insertError}") } } }
            throw error
        }
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        synchronized(monitor) { factoryCreates++ }
        val wrapped = java.util.IdentityHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrapped) {
            wrapped[raw] ?: run {
                val state = NativeState()
                synchronized(monitor) { nativeOpens++ }
                object : SupportSQLiteDatabase by raw {
                    override fun beginTransaction() = begin(raw, state) { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin(raw, state) { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin(raw, state) { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin(raw, state) { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin(raw, state) { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin(raw, state) { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun setTransactionSuccessful() = marked(state) { raw.setTransactionSuccessful() }
                    override fun endTransaction() = end(raw, state) { raw.endTransaction() }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val original = raw.compileStatement(sql)
                        if (normalize(sql) != INSERT) return original
                        synchronized(monitor) { exactCompiles++ }
                        return object : SupportSQLiteStatement by original {
                            private var habitId: Long? = null
                            private var habitUuid: String? = null
                            private var actionName: String? = null
                            private var reverts: String? = null
                            private var factUuid: String? = null
                            private fun clear(index: Int) { when (index) {
                                2 -> habitId = null; 6 -> factUuid = null; 7 -> habitUuid = null
                                12 -> actionName = null; 15 -> reverts = null
                            } }
                            override fun clearBindings() { original.clearBindings(); habitId = null; habitUuid = null; actionName = null; reverts = null; factUuid = null }
                            override fun bindNull(index: Int) { original.bindNull(index); clear(index) }
                            override fun bindLong(index: Int, value: Long) { original.bindLong(index, value); clear(index); if (index == 2) habitId = value }
                            override fun bindDouble(index: Int, value: Double) { original.bindDouble(index, value); clear(index) }
                            override fun bindBlob(index: Int, value: ByteArray) { original.bindBlob(index, value); clear(index) }
                            override fun bindString(index: Int, value: String) { original.bindString(index, value); clear(index); when (index) {
                                6 -> factUuid = value; 7 -> habitUuid = value; 12 -> actionName = value; 15 -> reverts = value
                            } }
                            override fun execute() = insert(state, Api.EXECUTE, habitId, habitUuid, actionName, reverts, factUuid) { original.execute() }
                            override fun executeInsert() = insert(state, Api.INSERT, habitId, habitUuid, actionName, reverts, factUuid) { original.executeInsert() }
                            override fun executeUpdateDelete() = insert(state, Api.UPDATE_DELETE, habitId, habitUuid, actionName, reverts, factUuid) { original.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        if (normalize(sql) != INSERT) raw.execSQL(sql, bindArgs)
                        else insert(state, Api.EXEC_SQL, bindArgs.getOrNull(1) as? Long,
                            bindArgs.getOrNull(6) as? String, bindArgs.getOrNull(11) as? String,
                            bindArgs.getOrNull(14) as? String, bindArgs.getOrNull(5) as? String) { raw.execSQL(sql, bindArgs) }
                    }
                }.also { wrapped[raw] = it }
            }
        }
        val callback = configuration.callback
        val forwarded = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
        }
        val real = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(forwarded).noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery).build())
        return object : SupportSQLiteOpenHelper by real {
            override val writableDatabase get() = wrap(real.writableDatabase)
            override val readableDatabase get() = wrap(real.readableDatabase)
        }
    }
    private companion object {
        fun normalize(sql: String) = sql.replace("`", "").filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
        val INSERT = normalize("INSERT OR ABORT INTO `completions` (`id`,`habitId`,`date`,`value`,`actualCompletedAt`,`uuid`,`habitUuid`,`createdAt`,`recordedTimezone`,`recordedLocalDate`,`timeMetadataSource`,`oneTimeAction`,`oneTimeExpectedVersion`,`oneTimeExpectedHeadEventUuid`,`oneTimeRevertsEventUuid`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
    }
}
