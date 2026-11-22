package com.dayforge.ui.screens.dashboard

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.os.CancellationSignal
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseEpochFixture
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.service.CountActionGate
import com.dayforge.domain.service.HabitCompletionCoordinator
import com.dayforge.domain.service.HabitDeletionCoordinator
import com.dayforge.domain.service.HabitLifecycleCoordinator
import com.dayforge.domain.service.HabitTimerCoordinator
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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

/** Actual Dashboard command -> original repository. The gate returns the real original DAO
 * value, then a controlled concurrent writer replaces its UUID before repository admission. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DashboardAggregationIdentityTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    private val native = MetricReadGate()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { it.nativeFactory = native }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var completion: HabitCompletionCoordinator
    @Inject lateinit var deletion: HabitDeletionCoordinator
    @Inject lateinit var windows: DashboardTimeWindowTicker
    @Inject lateinit var lifecycle: HabitLifecycleCoordinator
    @Inject lateinit var timers: HabitTimerCoordinator
    @Inject lateinit var metrics: LinkedMetricCoordinator
    @Inject lateinit var counts: CountActionGate
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ViewModelStore()
    private lateinit var model: DashboardViewModel

    @Before fun start() = runBlocking<Unit> {
        hilt.inject(); epochs.connect(); epochs.login()
        withContext(Dispatchers.Main.immediate) {
            model = DashboardViewModel(context, epochs.host, completion, deletion, windows,
                lifecycle, timers, preferences, metrics, counts)
            store.put("dashboard", model)
        }
    }
    @After fun finish() = runBlocking<Unit> {
        native.release()
        if (::model.isInitialized) withTimeout(5_000) {
            val job = withContext(Dispatchers.Main.immediate) {
                requireNotNull(model.viewModelScope.coroutineContext[Job]).also { store.clear() }
            }
            job.join()
        }
    }
    private suspend fun launchCommand(action: () -> Unit): List<Job> = withContext(Dispatchers.Main.immediate) {
        val owner = requireNotNull(model.viewModelScope.coroutineContext[Job])
        val before = owner.children.toSet()
        action()
        owner.children.filter { it !in before }.toList()
    }

    @Test fun originalDashboardSelectionCannotAuthorizeSameIdReplacementBetweenItsReadAndRepositoryWrite() = runBlocking<Unit> {
        val row = MetricEntity(id = 41L, name = "Aggregation source", unit = "kg", iconResId = 1,
            colorHex = "#123456", uuid = "ac496000-0000-4000-8000-000000000001")
        epochs.current { graph -> graph.database.withTransaction { assertEquals(41L, graph.metricDao.insert(row)) } }
        val source = epochs.metricSelection(41L)
        native.arm(41L, row.uuid)
        val jobs = launchCommand { model.updateAggregationType(41L, "sum", source) }
        try {
            withTimeout(5_000) { native.entered.await() }
            native.requireOriginalRead()
            // The selected cursor is physically closed and no Room transaction/account lock
            // is held by that preliminary read. This is a REAL controlled competing write.
            val replacement = row.copy(name = "Same id replacement", uuid = "ac496000-0000-4000-8000-000000000002")
            withTimeout(5_000) { epochs.current { graph -> graph.database.withTransaction {
                graph.metricDao.update(replacement)
            } } }
            assertTrue(source.publication.isCurrentInMemory()) // Same Host/Token/storage, changed row UUID only.
            val afterConcurrentWrite = epochs.exactMain()
            val requests = widgets.requestCount
            native.release(); withTimeout(5_000) { jobs.joinAll() }
            native.requireFinished(replacement.uuid)
            assertEquals(afterConcurrentWrite, epochs.exactMain())
            assertEquals(requests, widgets.requestCount)
            epochs.current { assertEquals(replacement, it.metricDao.getMetricById(41L)) }

            native.disarm()
            val fresh = epochs.metricSelection(41L)
            val queuedBefore = epochs.current { it.database.syncOutboxDao().getAll() }
            val freshJobs = launchCommand { model.updateAggregationType(41L, "sum", fresh) }
            withTimeout(5_000) { freshJobs.joinAll() }
            epochs.current { graph ->
                assertEquals(replacement.copy(aggregationType = "sum"), graph.metricDao.getMetricById(41L))
                val queue = graph.database.syncOutboxDao().getAll()
                assertEquals(queuedBefore.size + 1, queue.size)
                assertEquals(queuedBefore, queue.filter { it.id in queuedBefore.map { before -> before.id }.toSet() })
                val created = queue.single { it.id !in queuedBefore.map { before -> before.id }.toSet() }
                assertEquals("metric", created.recordType); assertEquals(replacement.uuid, created.entityUuid)
                assertEquals("upsert", created.action)
            }
        } finally {
            native.release(); withTimeout(5_000) { jobs.joinAll() }; native.disarm()
        }
    }

    /** Fixed exact source SQL only, below the normal CountTrust/Room wrappers. No mutation,
     * return-value replacement, generic callback, production hook or suspend spy. */
    private class MetricReadGate : SupportSQLiteOpenHelper.Factory {
        val entered = CompletableDeferred<Unit>()
        private val latch = CountDownLatch(1)
        private val armed = AtomicBoolean()
        private val reads = AtomicInteger()
        private val timedOut = AtomicBoolean()
        private val firstUuid = AtomicReference<String?>()
        private val secondUuid = AtomicReference<String?>()
        private var expectedId = 0L
        private var expectedUuid = ""
        fun arm(id: Long, uuid: String) { expectedId = id; expectedUuid = uuid; check(armed.compareAndSet(false, true)) }
        fun release() = latch.countDown()
        fun disarm() { armed.set(false); release() }
        fun requireOriginalRead() { assertEquals(expectedUuid, firstUuid.get()); assertEquals(1, reads.get()) }
        fun requireFinished(replacementUuid: String) {
            assertFalse("Native gate timed out rather than being released", timedOut.get())
            // The second actual DAO lookup is the original Repository entry. A missing
            // expected UUID would continue to a third writer lookup and mutate the new row.
            assertEquals(2, reads.get()); assertEquals(replacementUuid, secondUuid.get())
        }
        private fun args(query: SupportSQLiteQuery): Array<out Any?> {
            val values = arrayOfNulls<Any>(query.argCount)
            query.bindTo(object : SupportSQLiteProgram {
                override fun bindNull(index: Int) { values[index - 1] = null }
                override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
                override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
                override fun bindString(index: Int, value: String) { values[index - 1] = value }
                override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value.copyOf() }
                override fun clearBindings() { values.fill(null) }
                override fun close() = Unit
            }); return values
        }
        private fun wrapCursor(sql: String, args: Array<out Any?>, raw: SupportSQLiteDatabase, cursor: Cursor): Cursor {
            if (!armed.get() || sql.trim().replace(Regex("\\s+"), " ") != "SELECT * FROM metrics WHERE id = ? LIMIT 1" ||
                args.size != 1 || args[0] != expectedId) return cursor
            val ordinal = reads.incrementAndGet()
            return object : CursorWrapper(cursor) {
                private val closed = AtomicBoolean()
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    check(!cursor.isBeforeFirst && !cursor.isAfterLast)
                    check(cursor.getLong(cursor.getColumnIndexOrThrow("id")) == expectedId)
                    val uuid = cursor.getString(cursor.getColumnIndexOrThrow("uuid"))
                    if (ordinal == 1) firstUuid.set(uuid) else if (ordinal == 2) secondUuid.set(uuid)
                    cursor.close()
                    if (ordinal == 1) {
                        check(!raw.inTransaction()) { "DASHBOARD_PRE_READ_MUST_NOT_HOLD_TRANSACTION" }
                        entered.complete(Unit)
                        if (!latch.await(5, TimeUnit.SECONDS)) { timedOut.set(true); error("DASHBOARD_NATIVE_READ_TIMEOUT") }
                    }
                }
            }
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
            val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (original !== raw) {
                    original = raw
                    adapted = object : SupportSQLiteDatabase by raw {
                        override fun query(query: String) = wrapCursor(query, emptyArray(), raw, raw.query(query))
                        override fun query(query: String, bindArgs: Array<out Any?>) = wrapCursor(query, bindArgs, raw, raw.query(query, bindArgs))
                        override fun query(query: SupportSQLiteQuery) = wrapCursor(query.sql, args(query), raw, raw.query(query))
                        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?) =
                            wrapCursor(query.sql, args(query), raw, raw.query(query, cancellationSignal))
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
