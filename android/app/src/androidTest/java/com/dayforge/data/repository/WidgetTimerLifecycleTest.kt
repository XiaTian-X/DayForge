package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.widget.timer.TimerWidgetReceiver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.clearWidgetSelection
import com.dayforge.widget.timer.TimerWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.IOException
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Hilt Host/controlled Room/Glance storage. Native cursor gates preserve original
 * read failures/cancellation without replacing the database, DAO or production graph. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetTimerLifecycleTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    private val reads = NativeReadGate()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = reads }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    private val tokens get() = epochs.tokens
    private val sessions get() = epochs.sessions
    @Inject lateinit var publisher: WidgetDisplayPublisher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun id(n: Int) = "ab320000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val ids = mutableListOf<GlanceId>()
    private fun glanceId(): GlanceId = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, (System.nanoTime() and 0x1fffffff).toInt() + 1))).also { ids.add(it) }
    private suspend fun state(key: GlanceId) = TimerWidget().getAppWidgetState<Preferences>(app, key)
    private suspend fun proof() = epochs.current { graph -> graph.database.withTransaction { nextRestartDatabaseProof(graph.database) } }
    private suspend fun create(typed: Boolean = true): Long = epochs.current { graph -> graph.habitRepository.createHabit("Lifecycle timer $typed", "", HabitType.TIMER,
        0, "#123456", HabitSchedule.Daily, targetValue = 1, isCountdown = true, completionPolicy = if (typed) "recurring" else null,
        appearance = if (typed) ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme") else null,
        creationAuthority = if (typed) graph.nextObjectCreator.capture() else null) }

    /** Actual Host graph owns this physical fixture. The shared fixed-graph helper resolves
     * raw Hilt singletons through HabitDatabaseProvider and must not be used across these
     * per-test physical Host files. This is explicit selection before any delayed read. */
    private suspend fun bindWidgetSelection(context: android.content.Context, glanceId: GlanceId, habitId: Long) {
        epochs.host.withGraph { graph ->
            val habit = requireNotNull(graph.habitRepository.getHabitById(habitId))
            assertEquals(HabitType.TIMER, habit.habitType)
            val spec = WidgetConfigurationSpec(HabitType.TIMER, TimerWidget.PREFS_NAME,
                TimerWidget.PREF_HABIT_ID_PREFIX, TimerWidgetReceiver::class.java)
            val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
            val repository = graph.widgetConfigurationRepository
            val snapshot = repository.load(spec, widgetId)
            assertTrue(snapshot.habits.any { it.id == habit.id && it.uuid == habit.uuid })
            repository.configure(snapshot, habit) { publication -> check(publication { }) }
            // Decode the real persisted identity, then resolve it again through this same
            // actual graph. Numeric ID reuse or a stale fixed Provider cannot satisfy this.
            val encoded = requireNotNull(context.getSharedPreferences(spec.preferences,
                android.content.Context.MODE_PRIVATE).getString("binding_v1_$widgetId", null))
            val stored = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(HabitType.TIMER.name, stored.getValue("type").jsonPrimitive.content)
            val identity = requireNotNull(StableHabitBinding.decodeOrNull(stored.getValue("identity").toString()))
            assertEquals(habit.uuid, identity.habitUuid)
            assertNotNull(identity.resolve(requireNotNull(snapshot.publication.session), habit.uuid))
            val bound = requireNotNull(repository.bound(spec, widgetId))
            assertEquals(habit.id, bound.habit?.id)
            assertEquals(habit.uuid, bound.habit?.uuid)
            assertTrue(bound.publication { })
        }
    }

    @Before fun setup() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login(id(1))
    }
    @After fun cleanup() = runBlocking<Unit> {
        reads.disarm()
        ids.forEach {
            clearWidgetSelection(app, it)
            updateAppWidgetState(app, it) { prefs -> prefs.clear() }
        }
    }

    private suspend fun seedOldProof(key: GlanceId) = updateAppWidgetState(app, key) {
        it[TimerWidget.ACTION_PROOF_KEY] = "old proof"
        it[TimerWidget.LEGACY_RESTART_PROOF_KEY] = "old legacy proof"
        it[TimerWidget.LEGACY_ACTION_PROOF_KEY] = "old legacy timer claim"
        it[TimerWidget.TICK_PLAN_KEY] = "old plan"; it[TimerWidget.TICK_LOG_HASH_KEY] = "old hash"
        it[TimerWidget.TICK_DATE_KEY] = "old date"; it[TimerWidget.TICK_ZONE_KEY] = "old zone"
        it[TimerWidget.TICK_COMPLETED_SECONDS_KEY] = 99; it[TimerWidget.READ_FAILED_KEY] = true
    }
    private fun assertNoActionOrTick(value: Preferences) {
        assertNull(value[TimerWidget.ACTION_PROOF_KEY]); assertNull(value[TimerWidget.TICK_PLAN_KEY])
        assertNull(value[TimerWidget.TICK_LOG_HASH_KEY]); assertNull(value[TimerWidget.TICK_DATE_KEY])
        assertNull(value[TimerWidget.TICK_ZONE_KEY]); assertNull(value[TimerWidget.TICK_COMPLETED_SECONDS_KEY])
    }

    @Test fun deletionClearsOldErrorActionAndTickMarkersAndLegacyHealthyReadRemainsUsable() = runBlocking<Unit> {
        val key = glanceId(); seedOldProof(key)
        val before = proof()
        // Exercise the source's deleted-row branch directly; ordinary refresh now resolves
        // an explicit stable selection and will not adopt a nonexistent numeric binding.
        TimerWidget.refreshWidgetDataInScope(app, key, Long.MAX_VALUE, requireNotNull(publisher.capturePublication()))
        val deleted = state(key)
        assertEquals(true, deleted[TimerWidget.IS_DELETED_KEY]); assertEquals(true, deleted[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, deleted[TimerWidget.READ_FAILED_KEY]); assertNoActionOrTick(deleted)
        assertNull(deleted[TimerWidget.LEGACY_RESTART_PROOF_KEY])
        assertNull(deleted[TimerWidget.LEGACY_ACTION_PROOF_KEY])
        assertEquals(before, proof())
        val habitId = create(typed = false)
        bindWidgetSelection(app, key, habitId)
        val current = proof()
        TimerWidget.refreshWidgetData(app, key, habitId)
        val loaded = state(key)
        assertEquals(false, loaded[TimerWidget.IS_DELETED_KEY]); assertEquals(true, loaded[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, loaded[TimerWidget.READ_FAILED_KEY]); assertEquals(1, loaded[TimerWidget.TARGET_MINUTES_KEY])
        assertEquals(true, loaded[TimerWidget.IS_COUNTDOWN_KEY]); assertNoActionOrTick(loaded)
        assertEquals(habitId, LegacyWidgetRestartClaim.decode(requireNotNull(loaded[TimerWidget.LEGACY_RESTART_PROOF_KEY])).habitId)
        val legacyAction = LegacyWidgetTimerClaim.decode(requireNotNull(loaded[TimerWidget.LEGACY_ACTION_PROOF_KEY]))
        assertEquals(habitId, legacyAction.identity.habitId)
        assertEquals(requireNotNull(tokens.localCoreWriteAccess()).session, legacyAction.identity.session())
        assertNull(legacyAction.sessionUuid)
        assertNull(legacyAction.incumbent)
        assertEquals(current, proof())
    }

    @Test fun realSourceFailureHidesActionsAndColdRetryRestoresDisplayWithoutChangingFacts() = runBlocking<Unit> {
        val habitId = create(); val key = glanceId(); val row = epochs.current { requireNotNull(it.habitDao.getVisibleHabitById(habitId)) }
        bindWidgetSelection(app, key, habitId)
        seedOldProof(key); val before = proof()
        val fault = reads.arm(habitId, occurrence = 2, fail = true, pause = false)
        TimerWidget.refreshWidgetData(app, key, habitId)
        fault.assertReached(expectedRow = true); fault.assertFinished()
        val failed = state(key)
        assertEquals(true, failed[TimerWidget.READ_FAILED_KEY]); assertEquals(false, failed[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, failed[TimerWidget.IS_DELETED_KEY]); assertNoActionOrTick(failed)
        assertNull(failed[TimerWidget.LEGACY_RESTART_PROOF_KEY])
        assertNull(failed[TimerWidget.LEGACY_ACTION_PROOF_KEY])
        assertEquals(before, proof())
        reads.disarm()
        TimerWidget.refreshWidgetData(app, key, habitId)
        assertEquals(false, state(key)[TimerWidget.READ_FAILED_KEY]); assertEquals(row.name, state(key)[TimerWidget.HABIT_NAME_KEY])
        assertNotNull(state(key)[TimerWidget.ACTION_PROOF_KEY]); assertEquals(before, proof())
        assertNull(state(key)[TimerWidget.LEGACY_RESTART_PROOF_KEY])
        assertNull(state(key)[TimerWidget.LEGACY_ACTION_PROOF_KEY])
    }

    @Test fun oldDeletedLegacyHealthyAndSourceErrorCannotOverwriteReplacementAccount() = runBlocking<Unit> {
        val legacy = create(typed = false)
        val typed = create()
        for (mode in listOf("deleted", "legacy", "error", "typed")) {
            sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
            val key = glanceId(); val before = proof()
            val habitId = when (mode) { "deleted" -> Long.MAX_VALUE; "typed" -> typed; else -> legacy }
            if (mode != "deleted") bindWidgetSelection(app, key, habitId)
            val deletedPublication = if (mode == "deleted") requireNotNull(publisher.capturePublication()) else null
            val fault = reads.arm(habitId, occurrence = if (mode == "deleted") 1 else 2,
                fail = mode == "error", pause = true)
            val reading = async(Dispatchers.IO) {
                if (deletedPublication != null) TimerWidget.refreshWidgetDataInScope(app, key, habitId, deletedPublication)
                else TimerWidget.refreshWidgetData(app, key, habitId)
            }
            try {
                withTimeout(5000) { fault.entered.await() }; fault.assertReached(expectedRow = mode != "deleted")
                sessions.exclusive {
                    tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                    updateAppWidgetState(app, key) { it.clear(); it[TimerWidget.HABIT_NAME_KEY] = "new account" }
                }
                val replacement = state(key)
                fault.release(); withTimeout(5000) { reading.await() }; fault.assertFinished()
                assertEquals(replacement, state(key)); assertEquals(before, proof())
            } finally { fault.release(); withTimeout(5000) { reading.cancelAndJoin() }; reads.disarm() }
        }
    }

    @Test fun sourceCancellationDoesNotPublishErrorAndReleasesAccountLock() = runBlocking<Unit> {
        val habitId = create(); val key = glanceId()
        bindWidgetSelection(app, key, habitId)
        val original = state(key); val before = proof()
        val fault = reads.arm(habitId, occurrence = 2, fail = false, pause = true)
        val reading = launch(Dispatchers.IO) { TimerWidget.refreshWidgetData(app, key, habitId) }
        try {
            withTimeout(5000) { fault.entered.await() }; fault.assertReached(expectedRow = true)
            reading.cancel(); fault.release()
            withTimeout(5000) { reading.join() }
            assertTrue(reading.isCancelled); fault.assertFinished()
            assertEquals(original, state(key)); withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
        } finally { fault.release(); withTimeout(5000) { reading.cancelAndJoin() }; reads.disarm() }
    }

    /** Exact original visible-habit SELECT, below CountTrust's closed SQL/cursor boundary.
     * First ordinary occurrence resolves the stable widget binding under the account lock;
     * the second is TimerWidget's original source read outside that lock. Deleted-row direct
     * calls have only the latter. Ordinal and actual stepped row are asserted, never optional.
     */
    private class NativeReadGate : SupportSQLiteOpenHelper.Factory {
        class Gate(val habitId: Long, val occurrence: Int, val fail: Boolean, val pause: Boolean) {
            val entered = CompletableDeferred<Unit>()
            val matches = AtomicInteger()
            val reached = AtomicBoolean()
            val hasRow = AtomicBoolean()
            val observedId = AtomicReference<Long?>()
            val timedOut = AtomicBoolean()
            val failureThrown = AtomicBoolean()
            private val released = CountDownLatch(1)
            fun release() = released.countDown()
            fun afterStep(cursor: Cursor, found: Boolean) {
                check(reached.compareAndSet(false, true))
                hasRow.set(found)
                if (found) observedId.set(cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                entered.complete(Unit)
                if (pause && !released.await(5, TimeUnit.SECONDS)) {
                    timedOut.set(true)
                    error("WIDGET_NATIVE_READ_GATE_TIMEOUT")
                }
                if (fail) { failureThrown.set(true); throw IOException("source failed") }
            }
            fun assertReached(expectedRow: Boolean) {
                assertTrue("Actual native source was not stepped", reached.get())
                assertEquals(occurrence, matches.get())
                assertEquals(expectedRow, hasRow.get())
                if (expectedRow) assertEquals(habitId, observedId.get()) else assertNull(observedId.get())
            }
            fun assertFinished() {
                assertFalse("Native wait must be explicitly released, not time out", timedOut.get())
                assertEquals(fail, failureThrown.get())
            }
        }
        private val active = AtomicReference<Gate?>()
        fun arm(id: Long, occurrence: Int, fail: Boolean, pause: Boolean): Gate =
            Gate(id, occurrence, fail, pause).also { check(active.compareAndSet(null, it)) }
        fun disarm() { active.getAndSet(null)?.release() }
        private fun bindings(query: SupportSQLiteQuery): Array<out Any?> {
            val values = arrayOfNulls<Any>(query.argCount)
            query.bindTo(object : SupportSQLiteProgram {
                override fun bindNull(index: Int) { values[index - 1] = null }
                override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
                override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
                override fun bindString(index: Int, value: String) { values[index - 1] = value }
                override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value.copyOf() }
                override fun clearBindings() { values.fill(null) }
                override fun close() = Unit
            })
            return values
        }
        private fun cursor(sql: String, args: Array<out Any?>, actual: Cursor): Cursor {
            val gate = active.get()?.takeUnless { it.reached.get() } ?: return actual
            val exact = "SELECT * FROM habits WHERE id=? AND NOT EXISTS (SELECT 1 FROM sync_outbox q JOIN next_request_origins o ON o.kind='sync_operation' AND o.requestId=q.operationId AND o.queueId=q.id AND o.protocol=5 WHERE q.recordType='habit' AND q.action='delete' AND q.entityUuid=habits.uuid)"
            if (sql.trim().replace(Regex("\\s+"), " ") != exact || args.size != 1 || args[0] != gate.habitId) return actual
            if (gate.matches.incrementAndGet() != gate.occurrence) return actual
            val stepped = AtomicBoolean()
            return object : CursorWrapper(actual) {
                private fun after(found: Boolean): Boolean {
                    if (stepped.compareAndSet(false, true)) gate.afterStep(actual, found)
                    return found
                }
                override fun moveToFirst(): Boolean = after(actual.moveToFirst())
                override fun moveToNext(): Boolean = after(actual.moveToNext())
                override fun moveToPosition(position: Int): Boolean = after(actual.moveToPosition(position))
            }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String): Cursor = cursor(query, emptyArray(), raw.query(query))
                        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = cursor(query, bindArgs, raw.query(query, bindArgs))
                        override fun query(query: SupportSQLiteQuery): Cursor = cursor(query.sql, bindings(query), raw.query(query))
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                            cursor(query.sql, bindings(query), raw.query(query, cancellationSignal))
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
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by delegate {
                override val writableDatabase get() = wrap(delegate.writableDatabase)
                override val readableDatabase get() = wrap(delegate.readableDatabase)
            }
        }
    }
}
