package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.CompletionActionTiming
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*

/** Actual production count/service/ACK/summary/detail/prompt preparation, with its complete
 * original evidence audit retained. One command per scale is a component-chain sample, NOT
 * a rendered screen, a background-contention sample or a percentile. Historical setup is
 * independently audited; it is excluded from component costs and reported separately.
 *
 * Production CompletionActionTiming is the unmodified save/result boundary observer. Match
 * its COUNT_INCREMENT operation_id to this class's service_start_ms..service_end_ms window:
 * COMMIT_OBSERVED gives the known-save upper bound, WRITE_CALL the repository call, AFTER_READ
 * the post-commit read. Evaluate the original 500 ms save budget from that trace externally.
 * Do not substitute the whole service return for save latency, or drop a missing trace.
 */
abstract class CountSummaryProductionCostFixture : G0CompletionPromptFixture() {
    // Use the production schema callback, not G0's batched opening candidate.
    override fun domainOpeningCallback(): androidx.room.RoomDatabase.Callback = SyncSchemaCallback
    private val owners = mutableListOf<AccountIconController>()
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private data class Measured<T>(val value: T, val start: Long, val end: Long, val resource: Long) {
        val elapsed: Long get() = end - start
    }
    private data class Home(val batch: DashboardReadBatch, val cards: List<HabitWithStats>)
    private data class Graph(val counts: CountHistoryReader, val habits: HabitRepository,
        val service: CheckInService, val dashboard: DashboardReadRepository,
        val list: DashboardHabitListBuilder, val prompt: LinkedMetricCoordinator)

    private fun graph(): Graph {
        val icons = AccountIconController({ error("COUNT_COST_UNEXPECTED_ICON_ACCESS") }, tokens)
        owners += icons
        val editor = NextObjectEditor(db, tokens, sessions, icons)
        val counts = CountHistoryReader(db, tokens, sessions)
        val checks = CheckHistoryReader(db, tokens, sessions)
        val widgets = WidgetFactReader(db, tokens, sessions, preferences, counts, checks)
        val habits = HabitRepository(db.habitDao(), db.completionDao(), db.timeLogDao(), db,
            nextObjectEditor = editor, countHistoryReader = counts, checkHistoryReader = checks,
            recurringHabitReader = RecurringHabitReader(db, tokens, sessions, editor, counts, checks),
            widgetFactReader = widgets, countActions = CountActionReader(db, tokens, sessions))
        val metrics = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(), db.habitMetricLinkDao(),
            nextObjectEditor = editor, widgetFactReader = widgets)
        val status = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts, checks),
            db.completionDao(), db.timeLogDao(), countHistoryReader = counts, checkHistoryReader = checks)
        return Graph(counts, habits, CheckInService(habits, db.completionDao(), db.timeLogDao()),
            DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), counts, checks),
            DashboardHabitListBuilder(status), LinkedMetricCoordinator(app, preferences, metrics, habits))
    }

    private fun log(body: JsonObjectBuilder.() -> Unit) {
        val line = buildJsonObject(body).toString()
        check(line.toByteArray(Charsets.UTF_8).size < 4000) { "COUNT_COST_RECORD_TOO_LARGE" }
        Log.i("CountSummaryProductionCost", line)
    }

    private fun JsonObjectBuilder.reuse(prefix: String, state: CountCalculationReuse.Diagnostics?) {
        if (state == null) return
        put("${prefix}_entries", state.committedEntries); put("${prefix}_promotions", state.promotions)
        put("${prefix}_invalidations", state.invalidations); put("${prefix}_discarded", state.discarded)
        state.lastCalculation?.let {
            // Dashboard may audit multiple objects; these are the LAST calculation only.
            put("${prefix}_last_input_facts", it.inputFacts); put("${prefix}_last_reused_blocks", it.reusedBlocks)
            put("${prefix}_last_changed_blocks", it.changedBlocks)
            put("${prefix}_last_added", it.addedContributions); put("${prefix}_last_removed", it.removedContributions)
            put("${prefix}_last_sealed_reused", it.sealedReusedBlocks)
            put("${prefix}_last_active_updated", it.activeUpdatedBlocks)
        }
    }

    private suspend fun <T> measure(history: Int, stage: String, reader: CountHistoryReader? = null,
        body: suspend () -> T): Measured<T> {
        val trace = G0PhaseTrace(driverTiming = false)
        val ledger = G0ColdWorkLedger()
        val before = reader?.reuseDiagnostics
        val start = now(); var end = 0L; var returned = false
        try {
            val value = ledger.run { trace.run(body) }
            end = now(); returned = true
            val spans = synchronized(trace.spans) { trace.spans.toList() }
            val begins = spans.filter { it.phase == "tx_begin_outer" }.sortedBy { it.start }
            val ends = spans.filter { it.phase == "tx_end_outer" }.sortedBy { it.start }
            val resource = begins.zip(ends).maxOfOrNull { (a, b) -> b.end - a.start } ?: 0L
            // Log before assertions so a boundary mismatch also keeps its measurements.
            log {
                put("kind", "stage"); put("history", history); put("stage", stage); put("returned", true)
                put("start_ns", start); put("end_ns", end); put("elapsed_ns", end - start)
                put("transaction_begin_count", begins.size); put("transaction_end_count", ends.size)
                put("transaction_max_begin_through_end_ns", resource)
                put("access_decryptions", trace.count("decrypt_access")); put("refresh_decryptions", trace.count("decrypt_refresh"))
                put("datastore_ns", trace.nanos("datastore_read")); put("sql_attempts", ledger.queries)
                put("write_attempts", ledger.writes)
                put("matched_whole_table_queries", buildJsonObject { ledger.wholeTableQueries.forEach { (name, count) -> put(name, count) } })
                reuse("before", before); reuse("after", reader?.reuseDiagnostics)
                put("production_complete_history_audit_retained", true); put("same_reader", reader != null)
                put("ui", false); put("background_competition", false)
            }
            assertEquals("Every completed outer transaction must have its actual END", begins.size, ends.size)
            assertTrue(begins.zip(ends).all { (a, b) -> a.ok && b.ok && b.end >= a.start })
            return Measured(value, start, end, resource)
        } finally {
            if (!returned) log {
                put("kind", "stage"); put("history", history); put("stage", stage); put("returned", false)
                put("start_ns", start); put("end_ns", end.takeIf { it != 0L } ?: now())
                reuse("before", before); reuse("after", reader?.reuseDiagnostics)
                put("ui", false)
            }
        }
    }

    private suspend fun home(graph: Graph): Home {
        val batch = graph.dashboard.read(ZonedDateTime.now())
        val cards = graph.list.project(batch)
        var published = false
        assertTrue(graph.dashboard.publish(batch) { published = true })
        assertTrue(published)
        cards.forEach { card ->
            assertNull("Summary card must not fabricate completion details", card.countHistory)
            assertSame(batch.counts.getValue(card.habit.id), card.countSummary)
        }
        return Home(batch, cards)
    }

    internal suspend fun journey(history: Int) {
        require(history in setOf(8, 80, 730))
        val wholeStart = now()
        val seed = measure(history, "fixture_with_independent_original_oracle") { seedPrompt(history) }.value
        val today = LocalDate.now(); val zone = ZoneId.systemDefault()
        val rows = currentHousehold
        // Same file, new Room connection and empty arithmetic memory. This is NOT an OS
        // page-cache cold start, process death or a persistent-proof recovery measurement.
        freshGateForColdOpen()
        val opened = measure(history, "same_file_reopen_new_reader") { storage.reopen(); graph() }
        val graph = opened.value
        assertEquals(0, graph.counts.reuseDiagnostics.committedEntries)
        val failures = mutableListOf<String>()
        fun budget(label: String, actual: Long, limit: Long) { if (actual > limit) failures += "$label=$actual>$limit ns" }
        fun warm(label: String, measured: Measured<*>) {
            budget("$label resource", measured.resource, 150_000_000L)
        }
        val cold = measure(history, "cold_dashboard_summary_and_card_projection", graph.counts) { home(graph) }
        assertEquals(rows.map { it.id }.toSet(), cold.value.batch.counts.keys)
        assertEquals(history, cold.value.batch.completions.size)
        assertTrue(cold.value.batch.counts.values.all { it.todayQuantity == 0L })
        budget("cold method-ready", cold.end - opened.start, 2_000_000_000L)

        val promptRequest = graph.prompt.captureCompletionPrompt()
        val serviceStartMs = SystemClock.elapsedRealtime()
        val service = measure(history, "production_service_result", graph.counts) {
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
                graph.service.incrementCount(app, rows.first().id)
            }
        }
        val serviceEndMs = SystemClock.elapsedRealtime()
        log {
            put("kind", "save_trace_window"); put("history", history)
            put("service_start_ms", serviceStartMs); put("service_end_ms", serviceEndMs)
            put("tag", "CompletionActionTiming"); put("operation", "COUNT_INCREMENT")
            put("save_stage", "COMMIT_OBSERVED"); put("save_budget_ms", 500)
            put("method_result_elapsed_ns", service.elapsed); put("ui", false)
        }
        val result = service.value as? CheckInResult.Success ?: error("COUNT_COST_OPERATION_NOT_SUCCESSFUL")
        assertTrue(result.recorded)
        val source = requireNotNull(result.followUp)
        val factUuid = requireNotNull(source.claim.completionUuid)
        assertEquals(rows.first().uuid, source.claim.habitUuid)
        val prompt = measure(history, "necessary_prompt_preparation", graph.counts) {
            graph.prompt.showCompletionPrompt(source, promptRequest)
            requireNotNull(graph.prompt.postCheckInState.value)
        }
        assertSame(source, prompt.value.factPrompt?.completion)
        assertEquals(listOf(metric.id), prompt.value.linkedMetrics.map { it.metricId })
        graph.prompt.closePrompt(prompt.value)
        assertNull(graph.prompt.postCheckInState.value)
        budget("service method-result", service.elapsed, 700_000_000L)
        budget("necessary prompt from command", prompt.end - service.start, 700_000_000L)
        warm("service", service); warm("prompt", prompt)

        val read = measure(history, "dashboard_summary_and_card_projection", graph.counts) { home(graph) }
        val current = read.value.batch.habits.single { it.id == rows.first().id }
        val detail = measure(history, "explicit_detail_read", graph.counts) { graph.counts.read(current, today) }
        assertEquals(detail.value.summary, read.value.batch.counts.getValue(current.id))
        assertEquals(1L, detail.value.todayQuantity)
        val fact = detail.value.completions.single { it.uuid == factUuid }
        assertEquals(fact.id, detail.value.summary.todayLatestCompletionId)
        assertEquals(history / 2 + 1, detail.value.completions.size)
        budget("dashboard method-result", read.elapsed, 700_000_000L)
        warm("dashboard", read); warm("detail", detail)

        val queue = measure(history, "explicit_outbox_source_lookup") { sessions.exclusive { db.withTransaction {
            val pending = db.syncOutboxDao().getAll().single()
            assertEquals(factUuid, pending.entityUuid); assertEquals(current.uuid, pending.referenceUuid)
            assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, pending.operationId))
            pending
        } } }.value
        val requests = sender(seed.http)
        val sent = measure(history, "real_loopback_send", graph.counts) {
            requireNotNull(requests.sendOperation(access(), queue.operationId))
        }
        val ack = measure(history, "production_ack", graph.counts) { requests.acceptOperation(sent.value) }
        assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
        budget("ACK", ack.elapsed, 500_000_000L)
        warm("send", sent); warm("ACK", ack)
        val terminal = measure(history, "terminal_dashboard_summary_and_card_projection", graph.counts) { home(graph) }
        val terminalRow = terminal.value.batch.habits.single { it.id == current.id }
        val terminalDetail = measure(history, "terminal_detail_and_original_source_oracle", graph.counts) {
            sessions.exclusive { db.withTransaction {
                requests.requireAcceptedCountInTransaction(access(), queue.operationId)
            } }
            graph.counts.read(terminalRow, today)
        }
        assertEquals(read.value.batch.counts, terminal.value.batch.counts)
        assertEquals(terminal.value.batch.counts.getValue(current.id), terminalDetail.value.summary)
        assertEquals(detail.value.completions.map { it.uuid }.toSet(), terminalDetail.value.completions.map { it.uuid }.toSet())
        assertEquals(cold.value.batch.completions.toSet(), terminal.value.batch.completions.filter { it.uuid != factUuid }.toSet())
        assertEquals(history + 1, terminal.value.batch.completions.size)
        assertEquals(terminalDetail.value.completions.toSet(), terminal.value.batch.completions.filter { it.habitId == current.id }.toSet())
        assertEquals(0, count("sync_outbox")); assertEquals(history + 1, count("next_acceptances"))
        val native = pageNativeState()
        val replay = measure(history, "production_ack_replay", graph.counts) { requests.acceptOperation(sent.value) }
        assertEquals(NextOperationAcceptance.REPLAYED, replay.value)
        assertEquals(native, pageNativeState())
        assertEquals(today, LocalDate.now(zone)); assertEquals(zone, ZoneId.systemDefault())
        budget("terminal dashboard method-result", terminal.elapsed, 700_000_000L)
        warm("terminal dashboard", terminal); warm("terminal detail/oracle", terminalDetail); warm("replay", replay)
        log {
            put("kind", "terminal"); put("history", history); put("actual_new_commands", 1); put("actual_new_acks", 1)
            put("objects", 2); put("past_days_per_object", history / 2); put("whole_case_ns", now() - wholeStart)
            put("service_start_ms", serviceStartMs); put("service_end_ms", serviceEndMs)
            put("service_result_ns", service.elapsed); put("prompt_from_command_ns", prompt.end - service.start)
            put("summary_read_ns", read.elapsed); put("explicit_detail_read_ns", detail.elapsed)
            put("send_ns", sent.elapsed); put("ack_ns", ack.elapsed); put("terminal_summary_ns", terminal.elapsed)
            put("terminal_detail_and_source_oracle_ns", terminalDetail.elapsed); put("ack_replay_ns", replay.elapsed)
            put("source_oracle_passed", true); put("replay_native_unchanged", true); put("outbox", 0)
            put("complete_audit_retained", true); put("summary_reader", "production_shared_single_instance")
            put("detail_reader", "production_same_instance_explicit_detail")
            put("save_requires_completion_action_timing_correlation", true)
            put("ui", false); put("background_competition", false); put("percentile", false)
            put("budget_failures", JsonArray(failures.map(::JsonPrimitive)))
        }
        assertTrue("Original component budgets exceeded: ${failures.joinToString()}", failures.isEmpty())
    }

    @After fun closeCostOwners() { owners.forEach { it.close() } }
}
