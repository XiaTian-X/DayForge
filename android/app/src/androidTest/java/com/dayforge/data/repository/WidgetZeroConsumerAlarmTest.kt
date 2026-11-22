package com.dayforge.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.sync.readyWorkFuture
import com.dayforge.widget.*
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.timer.TimerWidgetReceiver
import com.dayforge.widget.progress.ProgressWidgetReceiver
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.focus.FocusWidgetReceiver
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt/Room/auth and production alarm/refresher. Only platform inventory/AlarmManager
 * and WorkManager's enqueue boundary are controlled for deterministic races/failures. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetZeroConsumerAlarmTest {
    @get:Rule(order = 0) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 1) val hilt = HiltAndroidRule(this)
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var publisher: WidgetDisplayPublisher
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private val alarms = mockk<AlarmManager>(relaxed = true)
    private val installed = mockk<AppWidgetManager>()
    private val work = mockk<WorkManager>(relaxed = true)
    private val armed = AtomicBoolean()
    private val historyReads = AtomicInteger()
    private val allReads = AtomicInteger()
    private val captures = AtomicInteger()
    private val platformQueries = AtomicInteger()
    private lateinit var context: Context
    private lateinit var realEntry: com.dayforge.di.WidgetGraphAccess
    private lateinit var entry: com.dayforge.di.WidgetGraphAccess
    @Volatile private var actualInventory = false
    @Volatile private var inventory: (ComponentName) -> IntArray = { intArrayOf() }
    @Volatile private var enqueue: () -> Operation = { readyOperation() }
    private val requests = java.util.Collections.synchronizedList(mutableListOf<OneTimeWorkRequest>())
    private val providers = listOf(CheckInWidgetReceiver::class.java, CountingWidgetReceiver::class.java,
        TimerWidgetReceiver::class.java, ProgressWidgetReceiver::class.java,
        MotivationWidgetReceiver::class.java, FocusWidgetReceiver::class.java)
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            private val history = Regex("(?i)\\b(?:FROM|JOIN)\\s+(?:main\\.)?[`\"]?(habits|completions|count_days|time_logs|timer_command_outbox|timer_segments)[`\"]?\\b")
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                if (!armed.get()) return
                if (sqlQuery.trimStart().startsWith("SELECT", true)) allReads.incrementAndGet()
                if (history.containsMatchIn(sqlQuery)) historyReads.incrementAndGet()
            }
        }
    }
    private fun id(n: Int) = "ab340000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun readyOperation() = mockk<Operation> { every { result } returns readyWorkFuture(Operation.SUCCESS) }
    private fun operation(future: ListenableFuture<Operation.State.SUCCESS>) = mockk<Operation> { every { result } returns future }
    @Before fun setup() = runBlocking<Unit> {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns work
        every { work.getWorkInfosForUniqueWork(any()) } returns readyWorkFuture(emptyList())
        every { work.cancelUniqueWork(any()) } answers { readyOperation() }
        every { work.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
            if (firstArg<String>() == WidgetRefreshScheduler.WORK_NAME) {
                assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, secondArg<ExistingWorkPolicy>())
                requests += thirdArg<OneTimeWorkRequest>(); enqueue()
            } else readyOperation()
        }
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-zero", "synthetic-refresh", "member", id(1), false) }
        every { alarms.canScheduleExactAlarms() } returns false
        every { installed.getAppWidgetIds(any<ComponentName>()) } answers {
            platformQueries.incrementAndGet(); inventory(firstArg())
        }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = when (name) {
                ALARM_SERVICE -> alarms
                APPWIDGET_SERVICE -> if (actualInventory) super.getSystemService(name) else installed
                else -> super.getSystemService(name)
            }
        }
        realEntry = WidgetEntryPoint.from(app)
        entry = mockk()
        coEvery { entry.withGraph(any<suspend () -> Any?>()) } coAnswers { realEntry.withGraph(firstArg<suspend () -> Any?>()) }
        every { entry.bound() } answers { realEntry.bound() }
        every { entry.habitRepository() } answers { habits }
        every { entry.timerWriter() } answers { realEntry.timerWriter() }
        val observedPublisher = mockk<WidgetDisplayPublisher>()
        coEvery { observedPublisher.capturePublication() } coAnswers { captures.incrementAndGet(); publisher.capturePublication() }
        every { entry.displayPublisher() } returns observedPublisher
        mockkObject(WidgetEntryPoint.Companion)
        every { WidgetEntryPoint.from(context) } returns entry
    }
    @After fun cleanup() = runBlocking<Unit> {
        armed.set(false)
        try {
            if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        } finally {
            unmockkObject(WidgetEntryPoint.Companion)
            unmockkStatic(WorkManager::class)
        }
    }
    private suspend fun countHabit(): HabitEntity {
        val now = ZonedDateTime.now()
        val id = habits.createHabit("Zero-consumer typed count", "", HabitType.COUNTING, 0, "#123456", HabitSchedule.Daily,
            targetValue = 2, bestTime = (now.hour * 60 + now.minute).toLong(), completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme"), creationAuthority = creator.capture())
        return requireNotNull(habits.getHabitById(id))
    }
    private suspend fun activeTimer() {
        val now = ZonedDateTime.now()
        val habitId = habits.createHabit("Zero-consumer active timer", "", HabitType.TIMER, 0, "#123456", HabitSchedule.Daily,
            targetValue = 1, completionPolicy = "recurring", appearance = ObjectAppearance(IconReference.Role("habit.custom"),
                "#123456", "theme"), creationAuthority = creator.capture())
        val row = requireNotNull(habits.getHabitById(habitId)); val at = System.currentTimeMillis() - 1000
        NextCoreLocalIntentStore(db, tokens, sessions).write(requireNotNull(tokens.localCoreWriteAccess()).session) {
            db.timeLogDao().insertSyncedTimer(com.dayforge.data.local.entity.TimeLogEntity(habitId = habitId, uuid = id(80),
                startTime = at, date = com.dayforge.util.DateTimeUtils.startOfDayMillis(), endTime = null, durationSeconds = 0,
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = at, timerTimezone = now.zone.id),
                com.dayforge.data.local.entity.TimerCommandEntity(commandId = id(81), sessionUuid = id(80), sequence = 1,
                    commandType = "start", occurredAt = at, expectedControlGeneration = 0, activityUuid = row.uuid, timezone = now.zone.id),
                com.dayforge.data.local.entity.TimerSegmentEntity(sessionUuid = id(80), sequence = 1, startedAt = at))
        }
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun beginReads() { historyReads.set(0); allReads.set(0); armed.set(true) }
    private fun endReads() { armed.set(false) }
    private fun noAlarmSet() {
        verify(exactly = 0) { alarms.setWindow(any(), any(), any(), any()) }
        verify(exactly = 0) { alarms.setAlarmClock(any(), any()) }
        verify(exactly = 0) { alarms.setExactAndAllowWhileIdle(any(), any(), any()) }
    }
    private fun arrival() {
        platformQueries.set(0)
        inventory = { if (platformQueries.get() <= providers.size) intArrayOf() else intArrayOf(701) }
    }
    private fun realAlarmRefresher() = WidgetRefresher(listOf(
        WidgetRefresher.Target("real-focus-alarm") { listOf(suspend { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }) }
    )) { _, _ -> }
    private class PendingEnqueue : FutureTask<Operation.State.SUCCESS>(Callable { Operation.SUCCESS }), ListenableFuture<Operation.State.SUCCESS> {
        private val listeners = mutableListOf<Pair<Runnable, Executor>>()
        private val listenerCount = AtomicInteger()
        val awaitRegistered = CompletableDeferred<Unit>()
        override fun addListener(listener: Runnable, executor: Executor) {
            // request() registers its diagnostic listener first; Operation.await is the second.
            if (listenerCount.incrementAndGet() == 2) awaitRegistered.complete(Unit)
            synchronized(listeners) { if (!isDone) { listeners += listener to executor; return } }
            executor.execute(listener)
        }
        override fun done() {
            val ready = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
            ready.forEach { (listener, executor) -> executor.execute(listener) }
        }
        fun failWith(error: Throwable) { setException(error) }
    }

    @Test(timeout = 150_000) fun realPlatformZeroStillRunsSeventhTargetAndFreshControlWithoutAnyHabitCountOrTimerRead() = runBlocking<Unit> {
        countHabit(); activeTimer(); actualInventory = true
        val platform = requireNotNull(AppWidgetManager.getInstance(app))
        for (receiver in providers) assertTrue("Actual isolated package must have zero platform instances", platform.getAppWidgetIds(ComponentName(app, receiver)).isEmpty())
        val before = image(); val enqueued = requests.size
        beginReads()
        try { assertEquals(0, WidgetRefresher(context).refresh()) } finally { endReads() }
        assertEquals(1, captures.get()); assertTrue("Real publication still reads control/replica state", allReads.get() > 0)
        assertEquals(0, historyReads.get()); assertEquals(enqueued, requests.size)
        verify(exactly = 0) { entry.habitRepository() }; verify(exactly = 0) { entry.timerWriter() }
        verify(exactly = 1) { alarms.cancel(any<PendingIntent>()) }; noAlarmSet()
        assertEquals(before, image()); assertEquals(id(1), tokens.authenticationSnapshot()!!.session.userId)
    }
    @Test(timeout = 150_000) fun preparedRealCountCannotRecreateAnAlarmAfterTheLastConsumerDisappears() = runBlocking<Unit> {
        val row = countHabit(); inventory = { intArrayOf(701) }
        val real = habits; val forwarded = mockk<HabitRepository>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { forwarded.getCountHistory(row) } coAnswers {
            val result = real.getCountHistory(row); entered.complete(Unit); release.await(); result
        }
        every { entry.habitRepository() } returns forwarded
        val scheduling = async(Dispatchers.IO) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        try {
            withTimeout(5000) { entered.await() }; val prepared = image()
            inventory = { intArrayOf() }; release.complete(Unit)
            withTimeout(5000) { scheduling.await() }
            coVerify(exactly = 1) { forwarded.getCountHistory(row) }
            verify(exactly = 1) { alarms.cancel(any<PendingIntent>()) }; noAlarmSet(); assertEquals(prepared, image())
        } finally { release.complete(Unit); scheduling.cancelAndJoin() }
    }
    @Test(timeout = 150_000) fun zeroToOneDoesNotCancelOrPrepareAndWaitsOnlyForDurableEnqueueThenFreshRunPrepares() = runBlocking<Unit> {
        countHabit(); arrival(); val pending = PendingEnqueue(); val queued = CompletableDeferred<Unit>()
        enqueue = {
            runBlocking { withTimeout(5000) { sessions.exclusive { assertNotNull(tokens.authenticationSnapshot()) } } }
            queued.complete(Unit); operation(pending)
        }
        val before = image(); beginReads()
        val scheduling = async(Dispatchers.IO) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        try {
            withTimeout(5000) { queued.await(); pending.awaitRegistered.await() }; assertFalse(scheduling.isCompleted)
            assertEquals(0, historyReads.get()); noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
            pending.run(); withTimeout(5000) { scheduling.await() }
        } finally { endReads(); scheduling.cancelAndJoin() }
        assertEquals(before, image()); assertEquals(1, captures.get())
        inventory = { intArrayOf(701) }; enqueue = { readyOperation() }
        FocusWidgetAlarmScheduler.scheduleNextRefresh(context)
        assertEquals(2, captures.get()); verify(exactly = 1) { alarms.setWindow(any(), any(), any(), any()) }
    }
    @Test(timeout = 150_000) fun failedOrMissingSuccessorEnqueueIsARealRefreshFailureWithBoundedWorkerRetry() = runBlocking<Unit> {
        countHabit(); arrival(); enqueue = { throw IllegalStateException("enqueue unavailable") }
        assertEquals(1, realAlarmRefresher().refresh()); noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
        fun worker(attempt: Int): WidgetRefreshWorker {
            val params = mockk<WorkerParameters>(relaxed = true); every { params.runAttemptCount } returns attempt
            return WidgetRefreshWorker(context, params) { arrival(); realAlarmRefresher().refresh() }
        }
        assertEquals(ListenableWorker.Result.retry(), worker(0).doWork())
        assertEquals(ListenableWorker.Result.success(workDataOf(WidgetRefreshWorker.FAILED_STEPS to 1)), worker(2).doWork())
        arrival(); val failed = PendingEnqueue().also { it.failWith(IOException("durable enqueue failed")) }
        enqueue = { operation(failed) }; assertEquals(1, realAlarmRefresher().refresh())
        noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
    }
    @Test(timeout = 150_000) fun cancelledEnqueueAwaitPropagatesAndDoesNotClaimRefreshOrAlarmCompletion() = runBlocking<Unit> {
        arrival(); val future = PendingEnqueue(); val queued = CompletableDeferred<Unit>()
        enqueue = { queued.complete(Unit); operation(future) }
        val scheduling = async(Dispatchers.IO) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        withTimeout(5000) { queued.await(); future.awaitRegistered.await() }; scheduling.cancel()
        try { scheduling.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        withTimeout(5000) { scheduling.join() }; assertTrue(future.isCancelled)
        noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
        withTimeout(5000) { sessions.exclusive { assertNotNull(tokens.authenticationSnapshot()) } }
    }
    @Test(timeout = 150_000) fun discoveryFailureAndCancellationAreUnknownRatherThanAnEmptyInventory() = runBlocking<Unit> {
        val failure = IOException("platform inventory unavailable")
        platformQueries.set(0)
        inventory = { if (it.className == FocusWidgetReceiver::class.java.name) throw failure else intArrayOf(701) }
        var observed: Throwable? = null
        try { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) } catch (error: IOException) { observed = error }
        assertSame(failure, observed); assertEquals(6, platformQueries.get())
        noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
        inventory = { throw CancellationException("platform cancelled") }
        try { FocusWidgetAlarmScheduler.scheduleNextRefresh(context); fail("Cancellation must propagate") }
        catch (error: CancellationException) { assertEquals("platform cancelled", error.message) }
        noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
    }
    @Test(timeout = 150_000) fun cancellationDuringFinalInventoryCannotPerformEvenAnEmptyCancel() = runBlocking<Unit> {
        var caller: Job? = null
        inventory = {
            if (platformQueries.get() == providers.size * 2) requireNotNull(caller).cancel(CancellationException("final inventory cancelled"))
            intArrayOf()
        }
        val scheduling = async(Dispatchers.IO) { caller = currentCoroutineContext()[Job]; FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        try { scheduling.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        withTimeout(5000) { scheduling.join() }; assertEquals(12, platformQueries.get())
        noAlarmSet(); verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
    }
    @Test(timeout = 150_000) fun oldZeroObservationCannotCancelTheNewAccountsActiveTimerAlarm() = runBlocking<Unit> {
        val captured = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val observed = mockk<WidgetDisplayPublisher>()
        coEvery { observed.capturePublication() } coAnswers {
            val scope = publisher.capturePublication(); captured.complete(Unit); release.await(); scope
        }
        every { entry.displayPublisher() } returns observed
        val old = async(Dispatchers.IO) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
        try {
            withTimeout(5000) { captured.await() }
            sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false) }
            inventory = { intArrayOf(702) }
            FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
            val beforeOldReturn = requests.size
            inventory = { intArrayOf() }; release.complete(Unit); withTimeout(5000) { old.await() }
            assertEquals(beforeOldReturn, requests.size)
            verify(exactly = 1) { alarms.setWindow(any(), any(), any(), any()) }
            verify(exactly = 0) { alarms.cancel(any<PendingIntent>()) }
            assertEquals(id(50), tokens.authenticationSnapshot()!!.session.userId)
        } finally { release.complete(Unit); old.cancelAndJoin() }
    }
    @Test(timeout = 150_000) fun activeTimerEntryUsesTheSameZeroArrivalAndLastDeletionRules() = runBlocking<Unit> {
        activeTimer(); val before = image(); val initialRequests = requests.size
        inventory = { intArrayOf() }
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
        verify(exactly = 1) { alarms.cancel(any<PendingIntent>()) }; noAlarmSet()
        arrival()
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
        assertEquals(initialRequests + 1, requests.size); verify(exactly = 1) { alarms.cancel(any<PendingIntent>()) }; noAlarmSet()
        platformQueries.set(0); inventory = { if (platformQueries.get() <= 6) intArrayOf(701) else intArrayOf() }
        FocusWidgetAlarmScheduler.scheduleNextRefreshForActiveTimer(context, requireNotNull(publisher.capturePublication())::invoke)
        verify(exactly = 2) { alarms.cancel(any<PendingIntent>()) }; noAlarmSet(); assertEquals(before, image())
    }
}
