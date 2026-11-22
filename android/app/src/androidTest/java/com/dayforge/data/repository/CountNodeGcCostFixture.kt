package com.dayforge.data.repository

import android.os.SystemClock
import androidx.room.withTransaction
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.businessDate
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Component/maintenance samples, not UI pixels or P95. Both foreground variants restore the
 * same closed seed image and retain credentials/Keystore. No OS cache flush is claimed.
 */
abstract class CountNodeGcCostFixture : CountNodeGcFixture() {
    // Business/GC diagnostic: OS widget work is counted at its existing boundary, not rendered.
    @get:org.junit.Rule(order = -2) val widgetRefresh = com.dayforge.widget.IsolatedWidgetRefreshRule()
    private data class Graph(val icons: AccountIconController, val counts: CountHistoryReader,
        val service: CheckInService, val home: DashboardReadRepository, val cards: DashboardHabitListBuilder,
        val prompt: LinkedMetricCoordinator)
    private data class Chain(val initialHomeNs: Long, val captureNs: Long, val serviceNs: Long, val promptNs: Long, val homeNs: Long,
        val sendNs: Long, val ackNs: Long, val completeNs: Long, val journeyNs: Long, val competingHomeNs: Long)
    private fun graph(): Graph {
        val icons = AccountIconController({ error("GC cost must not load icon storage") }, tokens)
        val editor = NextObjectEditor(db, tokens, sessions, icons)
        val counts = CountHistoryReader(db, tokens, sessions); val checks = CheckHistoryReader(db, tokens, sessions)
        val widgets = WidgetFactReader(db, tokens, sessions, preferences, counts, checks)
        val habits = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, countHistoryReader = counts, checkHistoryReader = checks,
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor, counts, checks),
            widgetFactReader = widgets, countActions = CountActionReader(db, tokens, sessions))
        val metrics = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao(),
            nextObjectEditor = editor, widgetFactReader = widgets)
        val status = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts, checks),
            db.completionDao(), db.timeLogDao(), countHistoryReader = counts, checkHistoryReader = checks)
        return Graph(icons, counts, CheckInService(habits, db.completionDao(), db.timeLogDao(), CountActionGate(tokens)),
            DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), counts, checks),
            DashboardHabitListBuilder(status), LinkedMetricCoordinator(app, preferences, metrics, habits))
    }
    private suspend fun home(graph: Graph): DashboardReadBatch {
        val batch = graph.home.read(ZonedDateTime.now())
        val cards = graph.cards.project(batch)
        cards.forEach { assertNull(it.countHistory); assertSame(batch.counts.getValue(it.habit.id), it.countSummary) }
        assertTrue(graph.home.publish(batch) {})
        return batch
    }
    private suspend fun chain(seed: CountTrustProductionCostSeed.Seed): Chain {
        val graph = graph()
        try {
            val journeyStart = SystemClock.elapsedRealtimeNanos()
            val initial = home(graph)
            val initialEnd = SystemClock.elapsedRealtimeNanos()
            assertEquals(seed.history.toLong(), initial.counts.values.sumOf { it.totalQuantity })
            val start = SystemClock.elapsedRealtimeNanos()
            val request = graph.prompt.captureCompletionPrompt()
            val captured = SystemClock.elapsedRealtimeNanos()
            val saved = graph.service.incrementCount(app, habit.id,
                requireNotNull(initial.counts.getValue(habit.id).actionIdentity)) as CheckInResult.Success
            val serviceEnd = SystemClock.elapsedRealtimeNanos(); assertTrue(saved.recorded)
            val follow = requireNotNull(saved.followUp)
            graph.prompt.showCompletionPrompt(follow, request)
            val state = requireNotNull(graph.prompt.postCheckInState.value)
            assertSame(follow, state.factPrompt?.completion)
            assertEquals(listOf(metric.id), state.linkedMetrics.map { it.metricId })
            graph.prompt.closePrompt(state)
            val promptEnd = SystemClock.elapsedRealtimeNanos()
            val shown = home(graph); val homeEnd = SystemClock.elapsedRealtimeNanos()
            assertEquals(seed.history + 1L, shown.counts.values.sumOf { it.totalQuantity })
            val source = db.syncOutboxDao().getAll().single()
            assertEquals(follow.claim.completionUuid, source.entityUuid)
            assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
            val sender = sender(seed.http)
            val (syncResult, competingHomeNs) = coroutineScope {
                val background = async(Dispatchers.IO) {
                    val sentStart = SystemClock.elapsedRealtimeNanos()
                    val delivery = requireNotNull(sender.sendOperation(access(), source.operationId))
                    val sent = SystemClock.elapsedRealtimeNanos()
                    assertEquals(NextOperationAcceptance.COMMITTED, sender.acceptOperation(delivery))
                    Triple(delivery, sent - sentStart, SystemClock.elapsedRealtimeNanos() - sent)
                }
                val readStart = SystemClock.elapsedRealtimeNanos()
                assertEquals(shown.counts, home(graph).counts)
                val elapsed = SystemClock.elapsedRealtimeNanos() - readStart
                background.await() to elapsed
            }
            val delivery = syncResult.first
            val terminal = home(graph); val finished = SystemClock.elapsedRealtimeNanos()
            assertEquals(shown.counts, terminal.counts); assertEquals(0, count("sync_outbox"))
            sessions.exclusive { db.withTransaction {
                sender.requireAcceptedCountInTransaction(access(), source.operationId)
                val original = RecurringCompletionEvidenceReader(db, tokens, sessions)
                for (row in terminal.habits) {
                    val evidence = original.readInTransaction(row, seed.today)
                    val quantities = evidence.completions.groupBy { it.businessDate }.mapValues { (_, values) -> values.sumOf { it.value.toLong() } }
                    val expected = CountHistory(seed.today, evidence.policies, quantities,
                        evidence.policies[seed.today] ?: CountDayPolicy(row.targetValue, row.isCountdown),
                        evidence.unknownDates, evidence.completions, evidence.roundHead, evidence.countIdentity)
                    assertEquals(expected.summary, terminal.counts.getValue(row.id))
                }
            } }
            assertEquals(NextOperationAcceptance.REPLAYED, sender.acceptOperation(delivery))
            return Chain(initialEnd - journeyStart, captured - start, serviceEnd - captured,
                promptEnd - serviceEnd, homeEnd - promptEnd, syncResult.second, syncResult.third,
                finished - start, finished - journeyStart, competingHomeNs)
        } finally { graph.icons.close() }
    }
    private suspend fun volume(): JsonObject = db.withTransaction {
        fun scalar(query: String) = sql.query(query).use { assertTrue(it.moveToFirst()); it.getLong(0) }
        val databaseFile = app.getDatabasePath("habit_database")
        buildJsonObject {
            put("nodes", scalar("SELECT count(*) FROM main.count_trust_nodes"))
            put("node_bytes", scalar("SELECT COALESCE(sum(length(body)),0) FROM main.count_trust_nodes"))
            put("page_count", scalar("PRAGMA main.page_count")); put("free_pages", scalar("PRAGMA main.freelist_count"))
            put("page_size", scalar("PRAGMA main.page_size")); put("db_file_bytes", databaseFile.length())
            put("wal_file_bytes", File(databaseFile.path + "-wal").let { if (it.exists()) it.length() else 0L })
        }
    }
    internal suspend fun maintenanceJourney(history: Int) {
        val seed = CountTrustProductionCostSeed(this).create(history)
        val initial = graph()
        try { assertEquals(history.toLong(), home(initial).counts.values.sumOf { it.totalQuantity }) }
        finally { initial.icons.close() }
        repeat(64) { orphan(20_000 + it) }
        val image = storage.snapshotClosedDatabase()
        val baseline = chain(seed)
        val baselineAfter = volume()
        storage.restoreClosedDatabase(image)
        val before = volume()
        val competing = arrayListOf<CountTrustNodeCollector.Report>()
        val concurrent = coroutineScope {
            val gc = collector()
            val background = launch(Dispatchers.IO) {
                while (isActive) {
                    if (gc.requested) {
                        val report = gc.collectSlice(); competing += report
                        check(report.status != CountTrustNodeCollector.Status.FAILED) { "GC failed during the real foreground journey" }
                    }
                    delay(250) // Same active-cadence yield as the production idle worker.
                }
            }
            try { chain(seed) } finally { background.cancelAndJoin() }
        }
        // The complete foreground oracle/replay finished. Idle work must now make real progress,
        // rather than counting DEFERRED/unsupported as a successful collector.
        val beforeIdle = compactNativeProof(); val live = reachable()
        val gc = collector()
        val idleStart = SystemClock.elapsedRealtimeNanos()
        val idle = if (gc.requested) finish(gc) else emptyList()
        val idleNs = SystemClock.elapsedRealtimeNanos() - idleStart
        val afterIdle = compactNativeProof(); assertEquals(beforeIdle - "count_trust_nodes", afterIdle - "count_trust_nodes")
        assertEquals(live, reachable()); assertEquals(live.size, afterIdle.getValue("count_trust_nodes").size)
        val after = volume(); val reports = competing + idle
        assertTrue(reports.any { it.deletedNodes > 0 })
        assertTrue(reports.all { it.catalogueRows <= 16384 && it.scannedRows <= 64 && it.graphBytes <= 512 * 1024 &&
            it.deletedNodes <= 32 && it.deletedBytes <= 512 * 1024 })
        assertTrue(reports.none { it.status == CountTrustNodeCollector.Status.FAILED })
        assertEquals(seed.today, LocalDate.now(seed.zone)); assertEquals(seed.zone, ZoneId.systemDefault())
        fun chainJson(value: Chain) = buildJsonObject {
            put("initial_home_cards_publish_ns", value.initialHomeNs); put("prompt_capture_ns", value.captureNs)
            put("service_including_postcommit_ns", value.serviceNs); put("necessary_prompt_ns", value.promptNs)
            put("home_cards_publish_ns", value.homeNs); put("send_ns", value.sendNs); put("ack_ns", value.ackNs)
            put("command_entry_through_terminal_home_ns", value.completeNs)
            put("initial_home_through_terminal_home_ns", value.journeyNs)
            put("foreground_home_during_ack_ns", value.competingHomeNs)
        }
        android.util.Log.i("CountNodeGcCost", buildJsonObject {
            put("history", history); put("history_shape", "two_habits_one_fact_per_past_day_each")
            put("same_closed_seed_restored", true); put("baseline", chainJson(baseline)); put("with_gc", chainJson(concurrent))
            put("baseline_after_chain", baselineAfter); put("before_gc_chain", before); put("after_gc_chain_and_idle", after)
            put("idle_work_ns", idleNs); put("slices", reports.size)
            put("cohorts_started", (competing.maxOfOrNull { it.cohort } ?: 0) + (idle.maxOfOrNull { it.cohort } ?: 0))
            put("root_restarts", (competing.maxOfOrNull { it.restarts } ?: 0) + (idle.maxOfOrNull { it.restarts } ?: 0))
            put("background_cycle_abandoned_at_cancel", competing.lastOrNull()?.status == CountTrustNodeCollector.Status.PROGRESS)
            put("busy_or_deferred", reports.count { it.status in setOf(CountTrustNodeCollector.Status.BUSY, CountTrustNodeCollector.Status.DEFERRED) })
            put("metadata_rows", reports.sumOf { it.scannedRows }); put("graph_nodes", reports.sumOf { it.walkedNodes })
            put("graph_bytes", reports.sumOf { it.graphBytes }); put("catalogue_high_water", reports.maxOfOrNull { it.catalogueRows } ?: 0)
            put("account_callback_and_end_sum_ns", reports.sumOf { it.accountAndEndNanos })
            put("account_callback_and_end_max_ns", reports.maxOfOrNull { it.accountAndEndNanos } ?: 0)
            put("lock_free_json_parse_sum_ns", reports.sumOf { it.parseNanos })
            put("deleted_nodes", reports.sumOf { it.deletedNodes }); put("deleted_bytes", reports.sumOf { it.deletedBytes })
            put("idle_drain_excludes_scheduler_sleep", true); put("physical_file_shrink_promised", false)
            put("precise_commit_or_db_lock_measured", false); put("one_background_ack_competition_sample", true)
            put("widget_scheduler_requests", widgetRefresh.requestCount)
            put("os_widget_render_measured", false)
            put("actual_pixels", false); put("percentile", false); put("product_sla_evaluated", false)
        }.toString())
    }
}
