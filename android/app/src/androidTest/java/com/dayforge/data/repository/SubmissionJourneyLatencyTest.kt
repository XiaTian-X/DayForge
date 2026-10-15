package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.createhabit.CreateHabitViewModel
import com.dayforge.ui.screens.dashboard.*
import com.dayforge.ui.screens.metricdetail.MetricDetailViewModel
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal challenge, Keystore, disk Room and production ViewModels. Publication is the
 * actual screen StateFlow, NOT a rendered frame or a network benchmark. Home stays subscribed
 * through the form round-trip so its competing refresh is included, not replaced by fake loops.
 */
@RunWith(AndroidJUnit4::class)
class SubmissionJourneyLatencyTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.journey.${UUID.randomUUID()}"
    private val decrypts = AtomicLong()
    private val decryptMs = AtomicLong()
    private val queries = AtomicLong()
    private val challengeQueries = AtomicLong()
    init {
        storage.queryCallback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                queries.incrementAndGet()
                if (sqlQuery.contains("next_challenge_")) challengeQueries.incrementAndGet()
            }
        }
    }
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? {
                val start = now()
                return try { actual.decrypt(value) } finally {
                    decrypts.incrementAndGet(); decryptMs.addAndGet(now() - start)
                }
            }
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private fun now() = SystemClock.elapsedRealtime()
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")
    private lateinit var checks: List<HabitEntity>
    private fun canonical(type: String, uuid: String, body: JsonObject, revision: Long = 1) =
        SyncV2Change(0, type, uuid, "upsert", revision, JsonObject(body + mapOf(
            "public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(revision),
            "created_at" to (body["created_at"] ?: JsonPrimitive(time)), "updated_at" to JsonPrimitive(time),
            "deleted_at" to JsonNull)), time)

    private suspend fun fixture() {
        register()
        val zone = ZoneId.of("Asia/Shanghai")
        val first = LocalDate.now(zone).minusDays(14)
        val created = first.atStartOfDay(zone).toInstant()
        val rows = mutableListOf<HabitEntity>()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            suspend fun add(row: HabitEntity): HabitEntity = row.copy(id = db.habitDao().insert(row)).also(rows::add)
            val base = habit.copy(id = 0, createdAt = created.toEpochMilli(), updatedAt = created.toEpochMilli(),
                targetCycles = 100, planMetadata = habit.planMetadata!!.copy(creationTimestamp = created.toString()))
            val goals = (0..1).map { n -> add(base.copy(uuid = id(100 + n), name = "Goal $n", habitType = HabitType.GOAL,
                targetValue = 1, completionPolicy = null, appearance = appearance("goal.default"),
                planMetadata = base.planMetadata!!.copy(timezone = null))) }
            habit = base.copy(id = habit.id, uuid = habit.uuid, name = habit.name, parentHabitId = goals[0].uuid)
            db.habitDao().update(habit); rows += habit
            timerHabit = base.copy(id = timerHabit.id, uuid = timerHabit.uuid, name = timerHabit.name,
                habitType = HabitType.TIMER, targetValue = 1, parentHabitId = goals[1].uuid,
                planMetadata = base.planMetadata!!.copy(targetUnit = "second"))
            db.habitDao().update(timerHabit); rows += timerHabit
            add(habit.copy(id = 0, uuid = id(110), name = "Countdown count", isCountdown = true))
            add(timerHabit.copy(id = 0, uuid = id(111), name = "Countdown timer", isCountdown = true))
            checks = (0..1).map { n -> add(base.copy(uuid = id(120 + n), name = "Check $n", targetValue = 1,
                habitType = HabitType.CHECK_IN, parentHabitId = goals[n].uuid)) }
            (0..1).forEach { n -> add(base.copy(uuid = id(130 + n), name = "Task $n", habitType = HabitType.CHECK_IN,
                targetValue = 1, targetCycles = null, schedule = HabitSchedule.Once(), completionPolicy = "one_and_done",
                oneTimeConfirmedVersion = 0, appearance = appearance("task.custom"))) }
            db.metricDao().insert(metric.copy(id = 0, uuid = id(140), name = "Second metric"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val checkpoints = rows.filter { it.completionPolicy == "recurring" }.map { row ->
            val initial = ChallengeRoundRecord(initialChallengeRoundHead(row.uuid), null, null, null)
            if (row.uuid != habit.uuid) ChallengeCheckpoint(initial.head, listOf(initial)) else {
                val restart = ChallengeRoundRecord(ChallengeRoundHead(row.uuid, id(301), 1), id(4), id(302),
                    ChallengeRestartIntent(row.uuid, id(301), initial.head.roundUuid, 0, 1))
                ChallengeCheckpoint(restart.head, listOf(initial, restart))
            }
        }
        val births = mutableListOf<ChallengeBirth>()
        val changes = rows.map { canonical("plan_node", it.uuid, NextStructureMapper.writePlan(it), if (it.uuid == habit.uuid) 2 else 1) }.toMutableList()
        val metricRows = db.metricDao().getAllMetricsOnce()
        changes += metricRows.map { canonical("metric", it.uuid, NextStructureMapper.writeMetric(it)) }
        checks.forEachIndexed { n, row ->
            changes += canonical("activity_metric_link", id(150 + n), SyncV2Mapper.link(HabitMetricLinkEntity(
                habitId = row.id, habitUuid = row.uuid, metricId = metricRows[n].id, metricUuid = metricRows[n].uuid,
                uuid = id(150 + n), promptOnComplete = true, createdAt = created.toEpochMilli(), updatedAt = created.toEpochMilli())))
        }
        repeat(14) { day ->
            val date = first.plusDays(day.toLong())
            val instant = date.atTime(12, 0).atZone(zone).toInstant()
            rows.filter { it.completionPolicy == "recurring" }.forEachIndexed { index, row ->
                val uuid = id(1000 + day * 10 + index)
                val timer = row.habitType == HabitType.TIMER
                val checkpoint = checkpoints.single { it.head.activityUuid == row.uuid }
                val head = if (row.uuid == habit.uuid && day < 7) checkpoint.records.first().head else checkpoint.head
                births += ChallengeBirth("activity_event", uuid, head)
                if (timer) births += ChallengeBirth("timer_session", uuid, head)
                changes += canonical("activity_event", uuid, buildJsonObject {
                    put("activity_uuid", row.uuid)
                    put("event_type", if (timer) "duration_session" else if (row.habitType == HabitType.COUNTING) "count_delta" else "check_in")
                    put("value", if (timer) JsonNull else JsonPrimitive(if (row.habitType == HabitType.COUNTING) "10" else "1"))
                    put("duration_seconds", if (timer) JsonPrimitive(60) else JsonNull)
                    put("duration_milliseconds", if (timer) JsonPrimitive(60_000) else JsonNull)
                    put("started_at", if (timer) JsonPrimitive(instant.toString()) else JsonNull)
                    put("ended_at", if (timer) JsonPrimitive(instant.plusSeconds(60).toString()) else JsonNull)
                    put("occurred_at", (if (timer) instant.plusSeconds(60) else instant).toString())
                    put("received_at", instant.plusSeconds(61).toString()); put("local_date", date.toString()); put("timezone", zone.id)
                    put("note", ""); put("source_type", "app"); put("source_device_id", id(4))
                    put("external_event_id", if (timer) JsonPrimitive(uuid) else JsonNull); put("reverts_event_uuid", JsonNull)
                    put("metadata", buildJsonObject { if (timer) put("timer_session_id", uuid) })
                    if (row.habitType == HabitType.COUNTING) put("count_policy", CountDayPolicy(10, row.isCountdown).toJson())
                    if (timer) put("day_allocations", buildJsonArray { add(buildJsonObject {
                        put("local_date", date.toString()); put("timezone", zone.id); put("duration_milliseconds", 60_000)
                    }) })
                })
            }
        }
        val http = channel().first
        val timerRequests = NextTimerRequestStore(db, tokens, sessions, sender(http))
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions,
            OneTimeLocalIntentStore(db, tokens, sessions, preferences), timerRequests = timerRequests), timerRequests)
            .bootstrap(access(), null, RoundSyncBootstrapResponse(changes, 200, time,
                rows.filter { it.completionPolicy == "one_and_done" }.map { OneTimeProjection(it.uuid, OneTimeState(0, null, null)) },
                1, checkpoints, births))
        assertEquals(10, count("habits")); assertEquals(56, count("completions")); assertEquals(28, count("timelogs"))
        assertEquals(0, count("sync_outbox")); assertEquals(7, count("next_challenge_rounds"))
        preferences.setFilterMode(FilterMode.ALL.value)
    }

    private suspend fun dashboard(repo: HabitRepository, metrics: MetricRepository): DashboardViewModel {
        val count = CountHistoryReader(db, tokens, sessions)
        val timer = NextTimerWriter(db, tokens, sessions)
        val once = onceRepository()
        val status = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), count),
            db.completionDao(), db.timeLogDao(), once, timer, count, CheckHistoryReader(db, tokens, sessions))
        val linked = LinkedMetricCoordinator(app, preferences, metrics, repo,
            habitPreferences = HabitPreferenceRepository(db, tokens, sessions, preferences, ReplicaPresentationRepository(db, tokens, sessions)))
        return withContext(Dispatchers.Main) {
            own(DashboardViewModel(app, repo,
                HabitCompletionCoordinator(app, CheckInService(repo, db.completionDao(), db.timeLogDao()), repo, metrics),
                HabitDeletionCoordinator(app, repo), DashboardHabitListBuilder(status), DashboardTimeWindowTicker(repo, preferences),
                HabitLifecycleCoordinator(app, repo), HabitTimerCoordinator(TimerManager(app, db.habitDao(), db.timeLogDao(), timer),
                    ActiveTimerStateProvider(db.timeLogDao(), repo, app, timer)),
                db.timeLogDao(), db.habitDao(), preferences, db.completionDao(), metrics, linked, MetricOverviewProvider(metrics),
                DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), count,
                    CheckHistoryReader(db, tokens, sessions)), CountActionGate(tokens))).also { model ->
                model.habitsWithStats.launchIn(model.viewModelScope)
                model.busyHabitIds.launchIn(model.viewModelScope)
                model.activeTimerState.launchIn(model.viewModelScope)
                model.linkedMetricsByHabit.launchIn(model.viewModelScope)
                model.pendingMetricHabits.launchIn(model.viewModelScope)
            }
        }
    }

    // Twelve operations plus preparation/publication; this runner budget is NOT the
    // per-operation performance budget asserted below. Preserve slow samples for diagnosis.
    @Test(timeout = 300_000) fun formalCreationCheckCountAndMetricToVisiblePublication() = runBlocking<Unit> {
        fixture()
        val repo = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, nextObjectCreator = creator, oneTimeRepository = onceRepository(),
            countHistoryReader = CountHistoryReader(db, tokens, sessions), checkHistoryReader = CheckHistoryReader(db, tokens, sessions),
            timerHistoryReader = TimerHistoryReader(db, tokens, sessions), recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor),
            widgetFactReader = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)),
            countActions = CountActionReader(db, tokens, sessions))
        val metrics = creatingMetrics()
        val cold = now()
        val home = dashboard(repo, metrics)
        withTimeout(30_000) { home.habitsWithStats.first { it.size == 10 }; home.linkedMetricsByHabit.first { it.size == 2 } }
        Log.i("JourneyLatency", "phase=cold_home published_ms=${now() - cold}")
        val durations = mutableListOf<Long>()
        val feedback = mutableListOf<Long>()
        var journeySample = 0
        suspend fun measure(kind: String, action: suspend () -> Unit, admitted: suspend () -> Unit,
            finished: suspend () -> Unit, published: suspend () -> Unit) {
            val start = now(); val calls = decrypts.get(); val cipher = decryptMs.get(); val sql = queries.get()
            val challengeSql = challengeQueries.get()
            val sample = journeySample++
            Log.i("JourneyLatency", "operation=$kind phase=begin journey_sample=$sample elapsed_realtime_ms=$start")
            action(); admitted()
            val admission = now() - start; feedback += admission
            withTimeout(30_000) { finished() }
            val completed = now() - start
            withTimeout(30_000) { published() }
            val shown = now() - start; durations += completed
            Log.i("JourneyLatency", "operation=$kind feedback_ms=$admission callback_finished_ms=$completed " +
                "published_ms=$shown decrypt_calls=${decrypts.get() - calls} decrypt_ms=${decryptMs.get() - cipher} sql_callbacks=${queries.get() - sql} challenge_sql=${challengeQueries.get() - challengeSql} " +
                "journey_sample=$sample elapsed_realtime_ms=${now()}")
            assertFalse(home.readError.value)
        }
        repeat(3) { sample ->
            val prepare = now()
            val ticket = requireNotNull(creator.captureForNavigation())
            val form = withContext(Dispatchers.Main) {
                own(CreateHabitViewModel(repo, db.habitDao(), db.metricDao(), preferences, app)).also {
                    it.beginCreation(ticket); it.updateName("Journey $sample")
                }
            }
            Log.i("JourneyLatency", "operation=create phase=form_ready elapsed_ms=${now() - prepare}")
            measure("create", { withContext(Dispatchers.Main) { repeat(10) { form.saveHabit() } } },
                { assertTrue(form.uiState.value.isSaving) },
                { form.uiState.first { !it.isSaving }.also { assertNotNull(it.savedHabitId); assertNull(it.errorMessage) } },
                { home.habitsWithStats.first { rows -> rows.any { it.habit.id == form.uiState.value.savedHabitId } } })
            val check = checks[sample % 2]
            val completed = sample != 2
            measure("check", { withContext(Dispatchers.Main) { repeat(10) { home.checkIn(check.id) } } },
                { withTimeout(1000) { home.busyHabitIds.first { check.id in it } } },
                { home.busyHabitIds.first { check.id !in it } },
                { home.habitsWithStats.first { rows -> rows.any { it.habit.id == check.id && it.completedToday == completed } } })
            withContext(Dispatchers.Main) { home.dismissPostCheckInDialog() }
            measure("count", { withContext(Dispatchers.Main) { repeat(10) { home.incrementCount(habit.id) } } },
                { withTimeout(1000) { home.busyHabitIds.first { habit.id in it } } },
                { home.busyHabitIds.first { habit.id !in it } },
                { home.habitsWithStats.first { rows -> rows.any { it.habit.id == habit.id && it.actualTodayCount == sample + 1L } } })
            val metricStart = now()
            val detail = withContext(Dispatchers.Main) { own(MetricDetailViewModel(app, db.metricDao(), db.metricLogDao(),
                db.habitMetricLinkDao(), db.habitDao(), metrics, SavedStateHandle(mapOf("metricId" to metric.id)))) }
            withTimeout(30_000) { detail.uiState.first { !it.isLoading && it.writeAuthority != null } }
            Log.i("JourneyLatency", "operation=metric phase=form_ready elapsed_ms=${now() - metricStart}")
            withContext(Dispatchers.Main) { detail.toggleValueInput() }
            measure("metric", { withContext(Dispatchers.Main) { repeat(10) { detail.recordValue(20.0 + sample, "") } } },
                { assertTrue(detail.uiState.value.isRecording) },
                { detail.uiState.first { !it.isRecording }.also { assertNull(it.errorMessage); assertFalse(it.showValueInput) } },
                { detail.uiState.first { it.logs.size == sample + 1 && it.latestValue == 20.0 + sample } })
            withContext(Dispatchers.Main) { detail.pauseReads() }
        }
        assertEquals(13, count("habits")); assertEquals(60, count("completions")); assertEquals(3, count("metric_logs"))
        assertEquals(12, count("sync_outbox")); assertEquals(12, count("next_request_origins"))
        val sorted = durations.sorted()
        Log.i("JourneyLatency", "samples=${sorted.size} callback_median_ms=${sorted[sorted.size / 2]} callback_max_ms=${sorted.last()} feedback_max_ms=${feedback.max()}")
        assertTrue("Feedback exceeded 100ms: $feedback", feedback.all { it <= 100 })
        assertTrue("Local callback exceeded 2 seconds: $durations", sorted.last() <= 2000)
        assertTrue("Median callback exceeded 1 second: $durations", sorted[sorted.size / 2] <= 1000)
    }
}
