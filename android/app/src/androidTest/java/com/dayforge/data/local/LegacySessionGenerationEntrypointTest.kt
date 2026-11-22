package com.dayforge.data.local

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.dayforge.MainActivity
import com.dayforge.R
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.di.DatabaseModule
import com.dayforge.di.WidgetEntryPoint
import com.dayforge.domain.service.AccountIconTransferService
import com.dayforge.domain.service.DeviceCalendar
import com.dayforge.domain.service.DeviceThemeController
import com.dayforge.domain.service.IconMaterialOutcome
import com.dayforge.reminder.HabitReminderController
import com.dayforge.reminder.ReminderDetailRequest
import com.dayforge.reminder.reminderScope
import com.dayforge.sync.AccountIconWorker
import com.dayforge.sync.AccountIconWorkerAccess
import com.dayforge.sync.AutoSyncWorker
import com.dayforge.widget.WidgetUpdateWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.mockk.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.runner.RunWith

/** Actual production Host, default AutoSync/Widget worker dependencies and Activity control UI.
 * Material service is a counted terminal collaborator only; this class claims no material HTTP. */
@HiltAndroidTest @UninstallModules(DatabaseModule::class) @RunWith(AndroidJUnit4::class)
class LegacySessionGenerationEntrypointTest {
    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(150)
    @get:Rule(order = 2) internal val preferences = LegacySessionHostPreferences()
    /** Covers setup, Activities, service cancellation, original Host drain and preferences.
     * A failed owner drain keeps isolation in place and blocks following fixture cleanup. */
    @get:Rule(order = 1) val widgets = TestRule { base: Statement, _: Description -> object : Statement() {
        override fun evaluate() {
            var failure: Throwable? = null
            mockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } returns null
            try { base.evaluate() } catch (error: Throwable) { failure = error }
            try {
                preferences.requireCleanupCompleted()
                unmockkObject(com.dayforge.widget.WidgetRefreshScheduler)
            } catch (error: Throwable) {
                preferences.preserve(error)
                failure = preserveLegacySessionMethodFailure(failure, error)
            }
            failure?.let { throw it }
        }
    } }
    @get:Rule(order = 3) val epochs = PhysicalDatabaseEpochFixture().also { preferences.bind(it) }
    @get:Rule(order = 4) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 5) val compose = createEmptyComposeRule()
    @Inject lateinit var settings: PreferencesManager
    @Inject lateinit var reminders: HabitReminderController
    @Inject lateinit var themes: DeviceThemeController
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var activity: ActivityScenario<MainActivity>? = null
    private var timerActivity: ActivityScenario<androidx.activity.ComponentActivity>? = null
    private var timerServiceOwned = false
    private var consumersClosed = false
    private var consumerCleanupFailure: Throwable? = null
    init {
        val original = epochs.beforeCleanup
        epochs.beforeCleanup = { original?.invoke(); closeConsumers() }
    }
    private val generationKey = stringPreferencesKey("auth_session_generation")
    private suspend fun generation() = preferences.store.data.first()[generationKey]
    @Before fun start() { hilt.inject(); epochs.connect() }
    @After fun finish() = closeConsumers()
    private fun preparedTest(block: suspend CoroutineScope.() -> Unit) = preferences.ownedTest {
        withTimeout(45_000) { themes.current() }
        assertEquals(0, epochs.realRoomCreates); assertNull(generation())
        preferences.store.committedChanges.set(0)
        block()
    }

    private fun startOwnedTimer(intent: android.content.Intent) {
        timerServiceOwned = true // Set before the actual platform call, including a lost return.
        app.startService(intent)
    }
    private suspend fun awaitTimerServiceGone(limit: Long) = withTimeout(limit) {
        while (true) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "dumpsys activity services ${app.packageName}/com.dayforge.domain.service.TimerService")
            val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            if (!dump.contains("ServiceRecord{")) break
            delay(50)
        }
    }
    private fun closeConsumers() = runBlocking<Unit> {
        consumerCleanupFailure?.let { throw it }
        if (consumersClosed) return@runBlocking
        var failure: Throwable? = null
        suspend fun finishOne(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        finishOne { preferences.drainMethod() }
        finishOne {
            if (timerServiceOwned) {
                app.stopService(android.content.Intent(app, com.dayforge.domain.service.TimerService::class.java))
                awaitTimerServiceGone(5_000)
            }
        }
        // Independent disposal attempts: a service/drain failure cannot skip either Activity.
        finishOne { activity?.close(); activity = null }
        finishOne { timerActivity?.close(); timerActivity = null }
        finishOne { InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
        // Ready/current did not end its eager DataStore consumer. Join the same injected
        // owned scope before the original Host/preferences actor disposal can proceed.
        finishOne { if (::themes.isInitialized) withTimeout(5_000) { themes.close() } }
        failure?.let {
            consumerCleanupFailure = it
            preferences.preserve(it)
            throw it
        }
        // Keep scheduler isolation through the following original tokens/Host drain as well.
        every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } returns null
        consumersClosed = true
    }
    private fun failOnce() { preferences.store.observeNextGenerationChange(afterTransform = { throw IOException("synthetic local preparation failure") }) }
    private suspend fun control() = epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { it }

    @Test fun actualDefaultAutoSyncWorkerCannotTreatLocalPreparationFailureAsNetworkRetryOrSuccess() = preparedTest {
        MaterialSocketServer { _, _ -> error("Preparation failure must not open a network request") }.use { server ->
            settings.setServerUrl(server.origin.toString())
            failOnce()
            assertEquals(Result.failure(), AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)).doWork())
            assertTrue(epochs.host.sessionPreparation.value is SessionPreparationState.RetryableFailure)
            assertNull(generation()); assertTrue(server.requests.isEmpty())
            assertEquals(Result.failure(), AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)).doWork())
            assertTrue(server.requests.isEmpty()); control()
        }
    }

    @Test fun realIconWorkerEntryNeverInitializesMaterialRuntimeFromFailedControlOrNestedControl() = preparedTest {
        failOnce(); control()
        val materials = AtomicInteger()
        val dependencies = object : AccountIconWorker.Dependencies {
            override fun iconWorkerAccess() = AccountIconWorkerAccess(epochs.host)
            override fun materials(): AccountIconTransferService {
                materials.incrementAndGet(); error("Material provider must remain unopened")
            }
        }
        val worker = AccountIconWorker(app, mockk<WorkerParameters>(relaxed = true)) { AccountIconWorker.synchronize(dependencies) }
        assertEquals(Result.failure(androidx.work.workDataOf(AccountIconWorker.STATUS to "ATTENTION")), worker.doWork())
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) {
            assertEquals(IconMaterialOutcome.ATTENTION, AccountIconWorker.synchronize(dependencies))
        }
        assertEquals(0, materials.get()); assertNull(generation())
    }

    @Test fun defaultWidgetWorkerAndReminderEntryCannotRunOrdinaryWorkWhilePreparationFailed() = preparedTest {
        failOnce(); control()
        clearMocks(com.dayforge.widget.WidgetRefreshScheduler, answers = false)
        every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } answers { error("Unprepared worker cannot enqueue") }
        assertEquals(Result.failure(), WidgetUpdateWorker(app, mockk<WorkerParameters>(relaxed = true)).doWork())
        verify(exactly = 0) { com.dayforge.widget.WidgetRefreshScheduler.request(any()) }
        assertTrue(runCatching { WidgetEntryPoint.withGraph(app) { fail("Widget body must not run") } }.exceptionOrNull() is
            DatabaseRuntimeHost.SessionPreparationUnavailable)
        assertTrue(runCatching { reminders.rescheduleAll() }.exceptionOrNull() is DatabaseRuntimeHost.SessionPreparationUnavailable)
        assertNull(generation()); control()
    }

    @Test fun foregroundWidgetMaterialAndReminderConcurrentFirstEntriesShareOnePersistentGeneration() = preparedTest {
        val material = mockk<AccountIconTransferService>()
        coEvery { material.synchronize() } returns IconMaterialOutcome.INACTIVE
        val captures = java.util.concurrent.CopyOnWriteArrayList<String>()
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { epochs.host.withGraph { captures += requireNotNull(generation()) } },
                async(Dispatchers.IO) { WidgetEntryPoint.withGraph(app) { captures += requireNotNull(generation()) } },
                async(Dispatchers.IO) { AccountIconWorkerAccess(epochs.host).synchronize { material }; captures += requireNotNull(generation()) },
                async(Dispatchers.IO) { reminders.rescheduleAll(); captures += requireNotNull(generation()) }
            ).awaitAll()
        }
        assertEquals(4, captures.size); assertEquals(1, captures.toSet().size)
        assertEquals(1, preferences.store.committedChanges.get()); assertEquals(1, epochs.realRoomCreates)
        assertEquals(captures.singleOrNull() ?: captures.first(), generation())
    }

    @Test fun oldRealReminderScopeCannotNavigateAfterMigrationAndNewCaptureUsesCurrentScope() = preparedTest {
        val old = requireNotNull(epochs.tokens.localFactAccess()).session
        assertEquals("legacy", old.authentication.generation)
        epochs.host.withGraph { }
        val row = HabitEntity(name = "Reminder after preparation", habitType = HabitType.CHECK_IN,
            iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, uuid = legacyGenerationId(80))
        val id = epochs.current { it.habitDao.insert(row) }
        val stale = ReminderDetailRequest(id, row.uuid, reminderScope(old))
        var navigations = 0
        assertFalse(reminders.openDetailSelection(stale) { navigations++ })
        val current = requireNotNull(epochs.tokens.localFactAccess()).session
        assertNotEquals(reminderScope(old), reminderScope(current))
        assertTrue(reminders.openDetailSelection(stale.copy(scope = reminderScope(current))) {
            assertEquals(id, it.id); assertEquals(row.uuid, it.uuid); navigations++
        })
        assertEquals(1, navigations); epochs.current { assertEquals(0, it.database.completionDao().countAll()) }
    }

    @Test fun actualReminderReceiverFirstEntryPreparesThenRejectsItsOldGenerationWake() = preparedTest {
        val old = requireNotNull(epochs.tokens.localFactAccess()).session
        val date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val intent = com.dayforge.reminder.AndroidReminderAlarms.alarmIntent(app, 1).apply {
            putExtra("habitUuid", legacyGenerationId(81)); putExtra("scope", reminderScope(old))
            putExtra("stamp", "old-claim"); putExtra("date", date.toString()); putExtra("zone", zone.id)
            putExtra("minute", -1); putExtra("trigger", date.atStartOfDay(zone).toInstant().toEpochMilli())
            putExtra("firstSlot", -1); putExtra("lastSlot", -1)
        }
        assertNotNull(com.dayforge.reminder.AndroidReminderAlarms.decode(intent))
        val finished = CompletableDeferred<Unit>()
        val receiver = spyk(com.dayforge.reminder.HabitReminderReceiver())
        val pending = mockk<android.content.BroadcastReceiver.PendingResult>()
        every { receiver.goAsync() } returns pending
        every { pending.finish() } answers { finished.complete(Unit); Unit }
        withContext(Dispatchers.Main) { receiver.onReceive(app, intent) }
        withTimeout(45_000) { finished.await() }
        assertNotNull(generation()); assertNotEquals("legacy", generation())
        assertEquals(1, preferences.store.committedChanges.get())
        epochs.current { graph ->
            assertEquals(0, graph.database.completionDao().countAll())
            assertTrue(graph.database.syncOutboxDao().getAll().isEmpty())
        }
        verify(exactly = 1) { pending.finish() }
    }

    @Test fun actualActivityShowsPreparationFailureAndExplicitRetryWithoutSubmittingBusiness() = preparedTest {
        val preferencesBefore = preferences.store.data.first()
        val lastSeenDateKey = longPreferencesKey("last_seen_date")
        val expectedDate = settings.calendar.changes.value.date.toEpochDay()
        assertNull(generation()); assertNull(preferencesBefore[lastSeenDateKey])
        val generationTransforms = AtomicInteger()
        val persisted = java.util.concurrent.atomic.AtomicReference<Pair<Preferences, Int>>()
        failOnce()
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(45_000) { epochs.host.sessionPreparation.value is SessionPreparationState.RetryableFailure }
        compose.onNodeWithTag("session-preparation").assertIsDisplayed()
        assertEquals(preferencesBefore, preferences.store.data.first())
        assertEquals(0, preferences.store.committedChanges.get())
        preferences.store.observeNextGenerationChange(afterTransform = {
            generationTransforms.incrementAndGet()
        }, afterPersistence = {
            // Actual delegate END has returned, but Host has not published Satisfied yet.
            // Capture only; all assertions stay on the owned JUnit coroutine.
            persisted.set(preferences.store.data.first() to preferences.store.committedChanges.get())
        })
        compose.onNodeWithTag("session-preparation-retry").performClick()
        compose.waitUntil(45_000) { epochs.host.sessionPreparation.value is SessionPreparationState.Satisfied }
        val (generationPreferences, generationCommits) = requireNotNull(persisted.get())
        val createdGeneration = requireNotNull(generationPreferences[generationKey])
        assertTrue(com.dayforge.domain.model.isContractUuid(createdGeneration))
        assertEquals(1, generationTransforms.get()); assertEquals(1, generationCommits)
        assertEquals(preferencesBefore.asMap(), generationPreferences.asMap().filterKeys { it != generationKey })
        assertNull(generationPreferences[lastSeenDateKey])
        compose.onNodeWithTag("session-preparation").assertDoesNotExist()
        // Reaching Satisfied can precede NavHost's first business entry. Wait for the
        // real panel/default Hilt model, not merely disappearance of the preparation page.
        val recoveryLabel = app.getString(R.string.count_recovery_open)
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(recoveryLabel).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText(recoveryLabel).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.count_recovery_explanation)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.count_recovery_close)).performClick()
        compose.onNodeWithText(app.getString(R.string.count_recovery_explanation)).assertDoesNotExist()
        compose.onNodeWithText(recoveryLabel).assertIsDisplayed()
        val graph = control()
        assertEquals(0, graph.database.completionDao().countAll())
        assertTrue(graph.database.syncOutboxDao().getAll().isEmpty())
        // MainScreen's real CalendarRefreshEffect has one separate presentation checkpoint.
        // Require its exact key/value and preserve EVERY original credential/permission field.
        val finalPreferences = withTimeout(45_000) {
            // DataStore may emit before its caller's post-persistence counter resumes.
            while (preferences.store.committedChanges.get() == generationCommits) delay(10)
            preferences.store.data.first { it[lastSeenDateKey] == expectedDate }
        }
        assertEquals(generationPreferences.asMap(), finalPreferences.asMap().filterKeys { it != lastSeenDateKey })
        assertEquals(createdGeneration, generation())
        assertEquals(1, generationTransforms.get())
        assertEquals(1, preferences.store.committedChanges.get() - generationCommits)
    }

    @Test fun actualActivityPreparationFailureKeepsOriginalLoginAndRecoveryScreensReachable() = preparedTest {
        failOnce(); activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(45_000) { epochs.host.sessionPreparation.value is SessionPreparationState.RetryableFailure }
        compose.onNodeWithTag("session-preparation-login").performClick()
        compose.onNodeWithText(app.getString(R.string.login_username)).assertExists()
        activity!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("session-preparation-recovery").performClick()
        compose.onNodeWithTag("replica-recovery-list").assertExists()
        assertNull(generation())
    }

    private suspend fun seedPausedTimer(): com.dayforge.data.local.entity.TimeLogEntity {
        val failed = epochs.host.sessionPreparation.value
        assertTrue(failed is SessionPreparationState.RetryableFailure)
        val preferencesBefore = preferences.store.data.first()
        assertNull(generation()); assertNull(epochs.tokens.localCoreWriteAccess())
        val pausedAt = System.currentTimeMillis(); val began = pausedAt - 2_000L
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        // Test-private historical INPUT through the original atomic DAO transition storage.
        // This is not an ordinary user operation or an authorization grant under CONTROL.
        // START and PAUSE, their commands and the closed running segment all precede recovery;
        // no completed timer result, acceptance or duration allocation is manufactured.
        val original = epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { current -> current.database.withTransaction {
            current.database.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val habit = HabitEntity(name = "Retained paused timer", habitType = HabitType.TIMER,
                iconResId = 1, colorHex = "#123456", schedule = HabitSchedule.Daily, targetValue = 10,
                uuid = legacyGenerationId(82))
            val habitId = current.habitDao.insert(habit)
            val session = legacyGenerationId(83)
            val start = com.dayforge.data.local.entity.TimerCommandEntity(commandId = legacyGenerationId(84),
                sessionUuid = session, sequence = 1, commandType = "start", occurredAt = began,
                expectedControlGeneration = 0, activityUuid = habit.uuid, timezone = zone.id, createdAt = began)
            val row = com.dayforge.data.local.entity.TimeLogEntity(habitId = habitId, startTime = began,
                endTime = null, durationSeconds = 0, isPaused = false, pausedAt = null,
                timerNextCommandSequence = 2, timerControlGeneration = 1, timerLastCommandAt = began,
                timerTimezone = zone.id, timerActiveElapsedMillis = 0,
                date = java.time.Instant.ofEpochMilli(began).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
                uuid = session, createdAt = began, updatedAt = began)
            val id = current.timeLogDao.insertSyncedTimer(row, start,
                com.dayforge.data.local.entity.TimerSegmentEntity(sessionUuid = session, sequence = 1, startedAt = began))
            current.timeLogDao.updatePauseAndQueue(id, true, pausedAt, 0L, 3, pausedAt,
                pausedAt - began, null, null,
                com.dayforge.data.local.entity.TimerCommandEntity(commandId = legacyGenerationId(85), sessionUuid = session,
                    sequence = 2, commandType = "pause", occurredAt = pausedAt, expectedControlGeneration = 1,
                    activeElapsedMillis = pausedAt - began, createdAt = pausedAt))
            current.database.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            requireNotNull(current.timeLogDao.getById(id))
        } }
        // Independent read AFTER actual setup END checks the complete prior state, not just a
        // larger nextSequence. The original pending command list is later compared unchanged.
        epochs.host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { current -> current.database.withTransaction {
            val saved = requireNotNull(current.timeLogDao.getActiveTimeLog())
            assertEquals(original, saved)
            val commands = current.timeLogDao.getPendingTimerCommands()
            assertEquals(2, current.timeLogDao.countPendingTimerCommands())
            assertTrue(current.timeLogDao.getRejectedTimerCommands().isEmpty())
            assertEquals(listOf("start", "pause"), commands.map { it.commandType })
            assertEquals(listOf(1, 2), commands.map { it.sequence })
            assertEquals(2, commands.map { it.commandId }.toSet().size)
            assertTrue(commands.all { it.sessionUuid == saved.uuid && it.attemptCount == 0 && it.deadLetteredAt == null })
            assertEquals(listOf(0, 1), commands.map { it.expectedControlGeneration })
            assertEquals(saved.startTime, commands.first().occurredAt)
            assertEquals(saved.timerTimezone, commands.first().timezone)
            assertEquals(requireNotNull(current.habitDao.getHabitById(saved.habitId)).uuid, commands.first().activityUuid)
            assertTrue(commands.last().occurredAt > commands.first().occurredAt)
            assertEquals(commands.last().sequence + 1, saved.timerNextCommandSequence)
            assertEquals(commands.last().expectedControlGeneration, saved.timerControlGeneration)
            assertEquals(commands.last().occurredAt, saved.timerLastCommandAt)
            assertTrue(saved.isPaused); assertEquals(commands.last().occurredAt, saved.pausedAt)
            val segments = current.timeLogDao.getTimerSegments(saved.uuid)
            assertEquals(1, segments.size)
            val segment = segments.single()
            assertEquals(saved.uuid, segment.sessionUuid); assertEquals(1, segment.sequence)
            assertEquals(commands.first().occurredAt, segment.startedAt)
            assertEquals(commands.last().occurredAt, segment.endedAt)
            val active = requireNotNull(segment.endedAt) - segment.startedAt
            assertEquals(active, commands.last().activeElapsedMillis); assertEquals(active, saved.timerActiveElapsedMillis)
            assertNull(saved.endTime); assertEquals(0, saved.durationSeconds)
            assertNull(saved.timerElapsedRealtimeAnchor); assertTrue(current.timeLogDao.getDayAllocations(saved.uuid).isEmpty())
        } }
        assertSame(failed, epochs.host.sessionPreparation.value)
        assertNull(generation()); assertNull(epochs.tokens.localCoreWriteAccess())
        assertEquals(preferencesBefore, preferences.store.data.first())
        var entered = false
        val denied = try { epochs.host.withGraph { entered = true }; null }
        catch (error: DatabaseRuntimeHost.SessionPreparationUnavailable) { error }
        assertNotNull(denied); assertFalse(entered)
        return original
    }

    @Test fun actualNullActionTimerServiceWaitsForExplicitPreparationThenRestoresSamePersistedSession() = preparedTest {
        failOnce(); val graph = control()
        val failed = epochs.host.sessionPreparation.value as SessionPreparationState.RetryableFailure
        val original = seedPausedTimer()
        val commands = graph.timeLogDao.getPendingTimerCommands()
        val requests = AtomicInteger()
        clearMocks(com.dayforge.widget.WidgetRefreshScheduler, answers = false)
        every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } answers { requests.incrementAndGet(); null }
        timerActivity = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        val intent = android.content.Intent(app, com.dayforge.domain.service.TimerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, "android.permission.POST_NOTIFICATIONS")
        startOwnedTimer(intent) // Original unclaimed null-action system recovery path.
        withTimeout(45_000) {
            while (app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {
                it.id == com.dayforge.domain.service.TimerService.NOTIFICATION_ID }) delay(50)
        }
        assertEquals(original, graph.timeLogDao.getActiveTimeLog()); assertEquals(commands, graph.timeLogDao.getPendingTimerCommands())
        assertNull(generation()); assertEquals(0, requests.get())
        epochs.host.retrySessionPreparation(failed.attempt)
        withTimeout(45_000) {
            while (requests.get() == 0 || app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {
                it.id == com.dayforge.domain.service.TimerService.NOTIFICATION_ID &&
                    it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ==
                    app.getString(R.string.timer_notification_title_paused) }) delay(50)
        }
        assertEquals(original, graph.timeLogDao.getActiveTimeLog())
        assertEquals(commands, graph.timeLogDao.getPendingTimerCommands()); assertNotNull(generation())
    }

    @Test fun malformedClaimedNullTimerIntentsCannotQueueSystemRecoveryAcrossPreparationFailure() = preparedTest {
        failOnce(); val graph = control(); val original = seedPausedTimer()
        val failed = epochs.host.sessionPreparation.value as SessionPreparationState.RetryableFailure
        val commands = graph.timeLogDao.getPendingTimerCommands()
        timerActivity = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        clearMocks(com.dayforge.widget.WidgetRefreshScheduler, answers = false)
        every { com.dayforge.widget.WidgetRefreshScheduler.request(any()) } answers { error("Rejected claim cannot publish a timer") }
        for (key in listOf("com.dayforge.widget.timer.legacy_claim", "com.dayforge.timer.authority")) {
            val intent = android.content.Intent(app, com.dayforge.domain.service.TimerService::class.java)
                .putExtra(key, "malformed")
            startOwnedTimer(intent)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitTimerServiceGone(45_000)
            assertNull(generation()); assertEquals(original, graph.timeLogDao.getActiveTimeLog())
        }
        epochs.host.retrySessionPreparation(failed.attempt)
        assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none {
            it.id == com.dayforge.domain.service.TimerService.NOTIFICATION_ID })
        assertEquals(original, graph.timeLogDao.getActiveTimeLog()); assertEquals(commands, graph.timeLogDao.getPendingTimerCommands())
        verify(exactly = 0) { com.dayforge.widget.WidgetRefreshScheduler.request(any()) }
    }

    @Module @InstallIn(SingletonComponent::class)
    object PreferencesModule {
        @Provides @Singleton fun data(): DataStore<Preferences> = LegacySessionHostPreferences.active().store
        @Provides @Singleton fun cipher(): TokenCipher = LegacySessionHostPreferences.active().cipher
        @Provides @Singleton fun preferences(data: DataStore<Preferences>, calendar: DeviceCalendar, tokens: TokenManager) =
            PreferencesManager(data, calendar, tokens)
        @Provides @Singleton fun connectivity(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}
