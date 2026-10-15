package com.dayforge.data.repository

import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.DataReadMonitor
import com.dayforge.ui.screens.dashboard.*
import java.security.KeyStore
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual authenticated Room readers and publication holder, including real Keystore costs. */
@RunWith(AndroidJUnit4::class)
class DashboardBatchReadTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.dashboard.${UUID.randomUUID()}"
    private val decryptions = AtomicInteger()
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? { decryptions.incrementAndGet(); return actual.decrypt(value) }
        }
    }
    @After fun deleteTestKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private fun reader() = DashboardReadRepository(db, tokens, sessions,
        ReplicaPresentationRepository(db, tokens, sessions), CountHistoryReader(db, tokens, sessions),
        CheckHistoryReader(db, tokens, sessions))
    private fun calculator() = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(),
        CountHistoryReader(db, tokens, sessions)), db.completionDao(), db.timeLogDao(), onceRepository(),
        NextTimerWriter(db, tokens, sessions), CountHistoryReader(db, tokens, sessions), CheckHistoryReader(db, tokens, sessions))
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun checkHabit(name: String): Long = creatingHabits().createHabit(name, "", HabitType.CHECK_IN, 0,
        "#123456", HabitSchedule.Daily, appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
        completionPolicy = "recurring", creationAuthority = creator.capture())

    @Test fun singleInvalidationSourceKeepsSameRowUpdatesWithoutCommitFanout() = runBlocking<Unit> {
        register()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch { reader().changes.collect { pulses.send(Unit) } }
        try {
            withTimeout(5_000) { pulses.receive() }
            assertNull("One initial signal, not one per DAO", withTimeoutOrNull(250) { pulses.receive() })
            // The normal write modifies facts, day policy, original request and outbox atomically.
            creatingHabits().logCompletion(app, habit.id, 1)
            withTimeout(5_000) { pulses.receive() }
            assertNull("One committed invalidation must not fan out", withTimeoutOrNull(250) { pulses.receive() })
            db.withTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=value WHERE habitId=?", arrayOf(habit.id))
                db.openHelper.writableDatabase.execSQL("UPDATE habits SET activityRate=activityRate WHERE id=?", arrayOf(habit.id))
            }
            withTimeout(5_000) { pulses.receive() }
            assertNull("Same-row multi-table update is still one pulse", withTimeoutOrNull(250) { pulses.receive() })
            // Receipt-only changes cannot be discarded just because the displayed values match.
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET action=action") }
            withTimeout(5_000) { pulses.receive() }
        } finally { observer.cancelAndJoin(); pulses.close() }
    }

    @Test fun initialDependenciesDoNotScheduleASecondHistoryRead() = runBlocking<Unit> {
        register()
        val projected = AtomicInteger()
        val builder = DashboardHabitListBuilder(calculator())
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main)
        try {
            val holder = DashboardListPresentation(scope, app, reader(), builder, preferences,
                MutableStateFlow(emptySet()), DataReadMonitor("InitialDashboardTest"), project = {
                    projected.incrementAndGet(); builder.project(it)
                })
            holder.habits.launchIn(scope)
            holder.todayProgress.launchIn(scope)
            withTimeout(15_000) { holder.habits.first { it.isNotEmpty() } }
            delay(500) // Observe late initialization pulses; never used to mask a business race.
            assertEquals("Initial Room/calendar/authority signals describe one initial read", 1, projected.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun batchMatchesIndependentReadersAndReducesAuthenticationWithoutWrites() = runBlocking<Unit> {
        register()
        val checks = List(4) { checkHabit("Check $it") }
        creatingHabits().logCompletion(app, habit.id, 3)
        checks.forEach { creatingHabits().logCompletion(app, it, 1) }
        val before = proof()
        val rows = db.habitDao().getVisibleHabitsOnce().filter { it.habitType != HabitType.TIMER }
        val now = ZonedDateTime.now()
        decryptions.set(0)
        val started = SystemClock.elapsedRealtime()
        val individual = rows.associate { row -> row.id to if (row.habitType == HabitType.COUNTING)
            CountHistoryReader(db, tokens, sessions).read(row, now.toLocalDate())
            else CheckHistoryReader(db, tokens, sessions).read(row, now.toLocalDate()) }
        val individualMs = SystemClock.elapsedRealtime() - started
        val individualCalls = decryptions.get()
        decryptions.set(0)
        val batchStarted = SystemClock.elapsedRealtime()
        val batch = reader().read(now)
        val batchMs = SystemClock.elapsedRealtime() - batchStarted
        val batchCalls = decryptions.get()
        batch.counts.forEach { (id, result) -> assertEquals(individual[id], result) }
        batch.checks.forEach { (id, result) -> assertEquals(individual[id], result) }
        assertEquals(rows.size, batch.counts.size + batch.checks.size)
        assertTrue("Authentication must be shared, not per item", batchCalls < individualCalls)
        val status = calculator()
        for (row in rows) {
            val projected = batch.counts[row.id]?.let { status.projectCount(row, it, now) }
                ?: status.projectCheck(row, batch.checks.getValue(row.id), now)
            assertEquals(status.calculate(row), projected)
        }
        var published = false
        assertTrue(reader().publish(batch) { published = true }); assertTrue(published)
        assertEquals(before, proof())
        android.util.Log.i("DashboardBatchCost", "items=${rows.size} independent_ms=$individualMs batch_ms=$batchMs independent_decrypts=$individualCalls batch_decrypts=$batchCalls")
    }

    @Test fun publicationRechecksSessionPermissionsRevisionDeviceEpochAndReplica() = runBlocking<Unit> {
        register()
        val original = dataStore.data.first()
        val changes: List<suspend () -> Unit> = listOf(
            { tokens.saveLoginSession("replacement", "refresh", "member", id(1), false) },
            { dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" } },
            { dataStore.edit { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("structure.write") } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_id")] = id(99) } },
            { dataStore.edit { it[stringPreferencesKey("sync_epoch")] = id(99) } },
            { dataStore.edit { it[ReplicaSwitchGuard.POINTER] = "invalid-fence" } }
        )
        for (change in changes) {
            dataStore.updateData { original }
            val batch = reader().read(ZonedDateTime.now())
            change()
            assertFalse(reader().publish(batch) { fail("Old result must never publish") })
        }
    }

    @Test fun warmKeyRemovalAndCrossDateBatchCannotPublish() = runBlocking<Unit> {
        register()
        val batch = reader().read(ZonedDateTime.now().minusDays(1))
        assertFalse(reader().publish(batch) { fail("Previous day") })
        val current = reader().read(ZonedDateTime.now())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertFalse(reader().publish(current) { fail("Invalidated credentials") })
    }

    @Test fun mixedTimerGoalAndRetainedTaskPreserveOriginalProjectionAndFiltering() = runBlocking<Unit> {
        register()
        val once = onceRepository(); val repo = onceHabits(once)
        val goal = com.dayforge.data.model.HabitDraft(id = id(180), name = "Container", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "theme"))
        repo.createGoal(goal, emptyList(), creationAuthority = creator.capture())
        val task = repo.createHabit("Once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            parentHabitId = goal.id, failMode = com.dayforge.data.model.FailMode.LOOSE,
            appearance = ObjectAppearance(IconReference.Role("task.reading"), "#123456", "object"),
            completionPolicy = "one_and_done", creationAuthority = creator.capture())
        repo.logCompletion(app, task)
        start()
        val before = proof()
        val batch = reader().read(ZonedDateTime.now())
        assertFalse(batch.checks.containsKey(task)); assertFalse(batch.counts.containsKey(timerHabit.id))
        val builder = DashboardHabitListBuilder(calculator())
        val projected = builder.project(batch)
        assertTrue(projected.single { it.habit.id == task }.completedForDisplay)
        assertFalse(projected.single { it.habit.id == task }.shouldCountToday)
        assertNotNull(projected.single { it.habit.id == timerHabit.id }.timerAuthority)
        for (mode in FilterMode.entries) {
            val old = builder.build(batch.habits, batch.completions, mode, batch.time, setOf(task))
            val actual = builder.arrange(projected, mode, batch.time, setOf(task))
            // One-time action proofs intentionally have identity equality. Compare every
            // display field (and the value-based timer ticket), then validate the proof below.
            fun display(rows: List<com.dayforge.data.model.HabitWithStats>) = rows.map {
                it.copy(oneTimeStatus = it.oneTimeStatus?.copy(authority = null))
            }
            assertEquals(display(old), display(actual))
        }
        assertTrue(reader().publish(batch) {})
        val status = requireNotNull(projected.single { it.habit.id == task }.oneTimeStatus)
        assertNotNull(status.authority)
        // Same-state change is rejected AFTER the real ticket has passed identity validation.
        // This proves the batch did not lose/misbind its action proof, without writing a fact.
        val rejected = runCatching { once.change(task, true, status.completionId,
            db.habitDao().getHabitById(task)!!.uuid, status.authority) }.exceptionOrNull()
        assertEquals("ONE_TIME_STATE_CHANGED", rejected?.message)
        assertEquals(before, proof())
    }

    @Test fun promptPreferenceFieldsShareOneFreshReadAndStillRejectLostCredentials() = runBlocking<Unit> {
        register()
        val owner = HabitPreferenceRepository(db, tokens, sessions, preferences,
            ReplicaPresentationRepository(db, tokens, sessions))
        val claim = owner.read(habit).claim
        owner.setNotification(claim, false); owner.setNeverAskAgain(claim, true)
        decryptions.set(0)
        val notification = preferences.getHabitNotificationEnabled(claim.session, habit.uuid).first()
        val never = preferences.getNeverAskAgain(claim.session, habit.uuid).first()
        val separate = decryptions.get()
        decryptions.set(0)
        val combined = preferences.readHabitPreferences(claim.session, habit.uuid)
        assertEquals(notification, combined.notifications); assertEquals(never, combined.neverAsk)
        assertTrue(decryptions.get() < separate)
        val actual = owner.read(habit)
        assertFalse(actual.notificationEnabled); assertTrue(actual.neverAsk)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertNotNull(runCatching { owner.read(habit) }.exceptionOrNull())
    }

    @Test fun mixedDisplayOneTimeAuthorityRespectsReadOnlyPermissionAndFreshAccount() = runBlocking<Unit> {
        register()
        val once = onceRepository()
        val task = onceHabits(once).createHabit("Read only task", "", HabitType.CHECK_IN, 0, "#123456",
            HabitSchedule.Once(), failMode = com.dayforge.data.model.FailMode.LOOSE,
            appearance = ObjectAppearance(IconReference.Role("task.reading"), "#123456", "object"),
            completionPolicy = "one_and_done", creationAuthority = creator.capture())
        val uuid = db.habitDao().getHabitById(task)!!.uuid
        val builder = DashboardHabitListBuilder(calculator())
        val writable = requireNotNull(builder.project(reader().read(ZonedDateTime.now())).single { it.habit.id == task }.oneTimeStatus)
        assertTrue(writable.canChange)
        dataStore.edit { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") }
        val batch = reader().read(ZonedDateTime.now())
        val readOnly = requireNotNull(builder.project(batch).single { it.habit.id == task }.oneTimeStatus)
        assertFalse(readOnly.canChange)
        assertEquals(once.read(task, uuid).copy(authority = null), readOnly.copy(authority = null))
        assertNotNull(runCatching { once.change(task, true, expectedUuid = uuid,
            authority = writable.authority) }.exceptionOrNull())
        tokens.saveLoginSession("other-access", "other-refresh", "other", id(99), false)
        assertFalse(reader().publish(batch) { fail("Old mixed display") })
        assertNotNull(runCatching { once.change(task, true, expectedUuid = uuid,
            authority = readOnly.authority) }.exceptionOrNull())
    }

    @Test fun explicitTimeZoneIsUsedByScheduleAndActivityProjection() {
        val created = java.time.Instant.parse("2026-10-13T23:30:00Z").toEpochMilli()
        val date = java.time.LocalDate.of(2026, 10, 14)
        val utc = java.time.ZoneId.of("UTC"); val shanghai = java.time.ZoneId.of("Asia/Shanghai")
        val schedule = HabitSchedule.Custom(2)
        assertFalse(ScheduleValidator.isCheckInAllowedOnDate(schedule, created, date, utc))
        assertTrue(ScheduleValidator.isCheckInAllowedOnDate(schedule, created, date, shanghai))
        assertEquals(date.plusDays(1), ScheduleValidator.getNextCheckInDate(schedule, created, date, utc))
        assertEquals(date, ScheduleValidator.getNextCheckInDate(schedule, created, date, shanghai))
        val now = date.atTime(12, 0).atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(emptyList<Long>(), ActivityRateCalculator.generateWindowCheckInDays(schedule, created, now, shanghai))
        assertEquals(listOf(date.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()),
            ActivityRateCalculator.generateWindowCheckInDays(schedule, created, now, utc))
    }

    @Test fun unregisteredLocalAccountStillReadsWithoutChangingHistoryOrQueue() = runBlocking<Unit> {
        val before = proof()
        val batch = reader().read(ZonedDateTime.now())
        assertNull(batch.registered)
        assertEquals(setOf(habit.id), batch.counts.keys)
        assertTrue(reader().publish(batch) {})
        assertEquals(before, proof())
    }

    @Test fun interactiveSchedulingNeverDelaysLogoutOrAuthorizesAnOldPublication() = runBlocking<Unit> {
        register()
        val reader = reader()
        val original = reader.read(ZonedDateTime.now())
        val before = proof()
        val finish = CompletableDeferred<Unit>()
        val interaction = launch(start = CoroutineStart.UNDISPATCHED) {
            reader.withInteractiveReadPriority { finish.await() }
        }
        try {
            withTimeout(5_000) { sessions.exclusive { tokens.clearTokens() } }
            assertFalse(reader.publish(original) { fail("A scheduling hint is not authority") })
            finish.complete(Unit); interaction.join()
            rejected { reader.read(ZonedDateTime.now()) }
            assertEquals(before, proof())
        } finally { finish.complete(Unit); interaction.cancelAndJoin() }
    }

    @Test fun delayedProjectionCannotRestoreLoggedOutListOrProgress() = runBlocking<Unit> {
        register()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val builder = DashboardHabitListBuilder(calculator())
        val job = SupervisorJob()
        val presentationScope = CoroutineScope(job + Dispatchers.Main)
        try {
            val holder = DashboardListPresentation(presentationScope, app, reader(), builder,
                preferences, MutableStateFlow(emptySet()), DataReadMonitor("DelayedDashboardTest"), project = { batch ->
                    val rows = builder.project(batch)
                    entered.complete(Unit); release.await(); rows
                })
            holder.habits.launchIn(presentationScope)
            withTimeout(15_000) { entered.await() }
            tokens.clearTokens()
            release.complete(Unit)
            delay(500)
            assertTrue(holder.habits.value.isEmpty())
            assertEquals(0 to 0, holder.todayProgress.value)
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun ordinaryInvalidationsDoNotCancelProjectionAndFilterOnlyRearranges() = runBlocking<Unit> {
        register()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocked = AtomicBoolean(false)
        val projectionCompleted = CompletableDeferred<Unit>()
        val builder = DashboardHabitListBuilder(calculator())
        val projectionCalls = AtomicInteger()
        val job = SupervisorJob()
        val presentationScope = CoroutineScope(job + Dispatchers.Main)
        try {
            val holder = DashboardListPresentation(presentationScope, app, reader(), builder,
                preferences, MutableStateFlow(emptySet()), DataReadMonitor("ConflatedDashboardTest"), project = { batch ->
                    projectionCalls.incrementAndGet()
                    val rows = builder.project(batch)
                    if (blocked.compareAndSet(false, true)) {
                        entered.complete(Unit); release.await(); projectionCompleted.complete(Unit)
                    }
                    rows
                })
            holder.habits.launchIn(presentationScope)
            withTimeout(15_000) { entered.await() }
            // Actual Room invalidations with unchanged row counts, while projection is in flight.
            repeat(4) {
                db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE habits SET activityRate=activityRate WHERE id=?", arrayOf(habit.id)) }
            }
            release.complete(Unit)
            withTimeout(15_000) { projectionCompleted.await(); holder.habits.first { it.isNotEmpty() } }
            delay(500) // Drain the bounded dirty read before measuring a UI-only change.
            val beforeFilter = projectionCalls.get()
            preferences.setFilterMode(FilterMode.TERMINATED.value)
            withTimeout(5_000) { holder.habits.first { it.isEmpty() } }
            preferences.setFilterMode(FilterMode.ALL.value)
            withTimeout(5_000) { holder.habits.first { it.isNotEmpty() } }
            assertEquals("Filtering must not reload history", beforeFilter, projectionCalls.get())
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    private suspend fun withPresentation(block: suspend (DashboardListPresentation, DataReadMonitor) -> Unit) {
        val job = SupervisorJob()
        val presentationScope = CoroutineScope(job + Dispatchers.Main)
        val monitor = DataReadMonitor("DashboardBatchTest")
        try {
            val holder = DashboardListPresentation(presentationScope, app, reader(), DashboardHabitListBuilder(calculator()),
                preferences, MutableStateFlow(emptySet()), monitor)
            holder.habits.launchIn(presentationScope)
            holder.todayProgress.launchIn(presentationScope)
            withTimeout(15_000) { holder.habits.first { it.any { it.habit.id == habit.id } } }
            block(holder, monitor)
        } finally { job.cancelAndJoin() }
    }

    @Test fun visibleListAndProgressAreSynchronouslyRevokedBeforeAccountChangeReturns() = runBlocking<Unit> {
        register()
        creatingHabits().logCompletion(app, habit.id, 10)
        withPresentation { holder, _ ->
            withTimeout(15_000) { holder.todayProgress.first { it.first == 1 } }
            tokens.clearTokens()
            assertTrue(holder.habits.value.isEmpty())
            assertEquals(0 to 0, holder.todayProgress.value)
            delay(250) // Late work cannot restore either state after the revocation.
            assertTrue(holder.habits.value.isEmpty())
            assertEquals(0 to 0, holder.todayProgress.value)
        }
    }

    @Test fun emptyListRevocationNeverResumesCollectorsWithOldProgress() = runBlocking<Unit> {
        register()
        withPresentation { holder, _ ->
            withTimeout(15_000) { holder.todayProgress.first { it.second > 0 } }
            val observed = CompletableDeferred<Pair<Int, Int>>()
            // Resume synchronously from the list's setter, so this is a deterministic
            // publication-order regression, not a timing-sensitive sleep or polling test.
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                holder.habits.drop(1).first { it.isEmpty() }
                observed.complete(holder.todayProgress.value)
            }
            try {
                tokens.clearTokens()
                assertEquals(0 to 0, withTimeout(5_000) { observed.await() })
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun sameRowCountChangesRefreshAndCorruptReadCanRecoverWithoutLosingFacts() = runBlocking<Unit> {
        register()
        creatingHabits().logCompletion(app, habit.id, 3)
        withPresentation { holder, monitor ->
            withTimeout(15_000) { holder.habits.first { it.any { row -> row.habit.id == habit.id && row.actualTodayCount == 3L } } }
            val facts = db.completionDao().getByHabitOnce(habit.id)
            val outbox = db.syncOutboxDao().getAll()
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE count_days SET targetValue=0 WHERE habitId=?", arrayOf(habit.id)) }
            withTimeout(15_000) { monitor.error.first { it }; holder.habits.first { it.isEmpty() } }
            assertEquals(0 to 0, holder.todayProgress.value)
            db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE count_days SET targetValue=10 WHERE habitId=?", arrayOf(habit.id)) }
            monitor.retry()
            withTimeout(15_000) { holder.habits.first { it.any { row -> row.habit.id == habit.id && row.actualTodayCount == 3L } } }
            assertFalse(monitor.error.value)
            assertEquals(facts, db.completionDao().getByHabitOnce(habit.id))
            assertEquals(outbox, db.syncOutboxDao().getAll())
        }
    }
}
