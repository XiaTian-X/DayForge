package com.dayforge.data.repository

import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.businessDate
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.model.HabitWithStats
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.CheckInService
import com.dayforge.domain.service.CompletionActionTiming
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*

/** Real Provider, Keystore, service, outbox, HTTP ACK and consumers. One command per history
 * is a component-chain sample, not a P95, rendered screen, process death or background race.
 * The same-file cold reopen keeps a signed clean root but no Room/reader memory. OS caches
 * are not flushed. Qualification is measured separately and never charged as fast recovery.
 * All original budgets are evaluated only after terminal/source-oracle/replay assertions.
 */
abstract class CountTrustProductionCostFixture : NextCoreRequestFixture() {
    private val trace = CountTrustProductionCostTrace()
    private val alias = "dayforge.test.count.production.cost.${UUID.randomUUID()}"
    private val owners = mutableListOf<AccountIconController>()
    private val failures = mutableListOf<String>()
    private var root: Root? = null
    private fun now() = SystemClock.elapsedRealtimeNanos()

    init {
        // This is the unchanged production builder with its supported synchronous observer.
        // Do not put G0DomainSqlGate/G0TimingFactory between Room and the production factory.
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, trace) }
    }
    override fun tokenCipher(): TokenCipher {
        val real = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = real.encrypt(value)
            override fun decrypt(value: String): String? {
                val started = now()
                return try { real.decrypt(value) } finally { trace.decrypted(now() - started) }
            }
        }
    }
    private data class Root(val revision: Long?, val digest: String?, val generation: Long,
        val dirty: Long, val clean: Boolean, val globalHealthy: Boolean)
    private data class Measured<T>(val value: T, val start: Long, val end: Long,
        val sql: CountTrustProductionCostTrace.Sample) { val elapsed get() = end - start }
    private data class Home(val batch: DashboardReadBatch, val cards: List<HabitWithStats>)
    private data class Graph(val counts: CountHistoryReader, val service: CheckInService,
        val dashboard: DashboardReadRepository, val list: DashboardHabitListBuilder,
        val prompt: LinkedMetricCoordinator)

    private fun graph(): Graph {
        val icons = AccountIconController({ error("COUNT_TRUST_COST_UNEXPECTED_ICON_ACCESS") }, tokens)
        owners += icons
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
        return Graph(counts, CheckInService(habits, db.completionDao(), db.timeLogDao()),
            DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions), counts, checks),
            DashboardHabitListBuilder(status), LinkedMetricCoordinator(app, preferences, metrics, habits))
    }
    private suspend fun root(): Root = sessions.exclusive { db.withTransaction {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db)) { "PRODUCTION_FACTORY_REQUIRED" }
        check(!owner.isRetired) { "PRODUCTION_FACTORY_RETIRED" }
        val access = requireNotNull(tokens.localCoreWriteAccess())
        owner.maintenance(db) {
            val sql = db.openHelper.writableDatabase
            CountTrustSchema.requireControl(sql)
            val control = sql.query("SELECT generation,dirty FROM count_trust_control WHERE id=1").use {
                check(it.moveToFirst()); it.getLong(0) to it.getLong(1)
            }
            val header = CountTrustSeal.read(sql)
            val context = CountTrustKernel(db, tokens, sessions).open(access)
            Root(header?.revision, header?.root?.digest, control.first, control.second,
                context != null && control.second == 0L && header?.generation == control.first,
                context?.index?.get("global_common") == JsonPrimitive(true))
        }
    } }

    private fun log(body: JsonObjectBuilder.() -> Unit) {
        val line = buildJsonObject(body).toString()
        check(line.toByteArray(Charsets.UTF_8).size < 4000)
        Log.i("CountTrustProductionCost", line)
    }
    private fun JsonObjectBuilder.root(prefix: String, value: Root?) {
        put("${prefix}_revision", value?.revision?.let(::JsonPrimitive) ?: JsonNull)
        put("${prefix}_generation", value?.generation?.let(::JsonPrimitive) ?: JsonNull)
        put("${prefix}_dirty", value?.dirty?.let(::JsonPrimitive) ?: JsonNull)
        put("${prefix}_clean", value?.clean ?: false); put("${prefix}_global_healthy", value?.globalHealthy ?: false)
    }
    private fun budget(label: String, elapsed: Long, limit: Long) {
        if (elapsed > limit) failures += "$label=$elapsed>$limit ns"
    }
    private suspend fun <T> measure(history: Int, stage: String, fast: Boolean = true,
        mutation: Boolean = false, qualification: Boolean = false, body: suspend () -> T): Measured<T> {
        val before = root; val start = now(); trace.begin()
        var finished = false
        try {
            val value = body(); val end = now(); val sql = trace.end(end); finished = true
            val diagnosticStart = now(); val after = root(); val diagnosticEnd = now(); root = after
            log {
                put("kind", "stage"); put("history", history); put("stage", stage); put("returned", true)
                put("start_ns", start); put("end_ns", end); put("elapsed_ns", end - start)
                put("diagnostic_after_ns", diagnosticEnd - diagnosticStart)
                put("query_callback_queries", sql.queries); put("query_callback_writes", sql.writes)
                put("full_history_proof_sql_sentinels", sql.fullProofSentinels)
                put("all_completion_detail_scans", sql.allDetailScans); put("scoped_lifetime_detail_scans", sql.scopedLifetimeDetailScans)
                put("transaction_begin_callbacks", sql.beginCount); put("transaction_end_callbacks", sql.endCount)
                put("begin_to_end_callback_max_ns", sql.callbackSpanMax)
                put("begin_to_confirmed_completion_upper_bound_max_ns", sql.completionUpperBoundMax)
                put("unmatched_transactions", sql.unmatched); put("database_lock_occupancy_measured", false)
                put("native_private_sql_counted", false); put("decryptions", sql.decryptions); put("decrypt_ns", sql.decryptNanos)
                root("before", before); root("after", after)
                put("ui", false); put("background_competition", false); put("percentile", false)
            }
            if (!after.clean || !after.globalHealthy) failures += "$stage did not retain clean qualified root"
            if (qualification) {
                if (before?.revision != null || after.revision != 1L || sql.fullProofSentinels == 0L)
                    failures += "$stage did not prove first qualification"
            } else if (fast) {
                if (sql.fullProofSentinels != 0L) failures += "$stage repeated original complete history proof"
                val expectedRevision = before?.revision?.let { Math.addExact(it, if (mutation) 1L else 0L) }
                if (expectedRevision == null || after.revision != expectedRevision)
                    failures += "$stage root revision transition was not exact"
                if (!mutation && (after.digest != before?.digest || after.generation != before?.generation))
                    failures += "$stage changed read-only root"
            }
            if (sql.beginCount != sql.endCount || sql.unmatched != 0) failures += "$stage transaction callback measurement incomplete"
            return Measured(value, start, end, sql)
        } finally {
            if (!finished) {
                val end = now(); val sql = trace.end(end)
                log {
                    put("kind", "stage"); put("history", history); put("stage", stage); put("returned", false)
                    put("start_ns", start); put("end_ns", end); put("elapsed_ns", end - start)
                    put("query_callback_queries", sql.queries); put("full_history_proof_sql_sentinels", sql.fullProofSentinels)
                    root("before", before)
                }
            }
        }
    }
    private fun warm(label: String, value: Measured<*>) {
        if (value.sql.beginCount == 0L) failures += "$label has no observed transaction; 150ms evidence unavailable"
        budget("$label transaction completion upper bound", value.sql.completionUpperBoundMax, 150_000_000L)
    }
    private suspend fun home(graph: Graph): Home {
        val batch = graph.dashboard.read(ZonedDateTime.now()); val cards = graph.list.project(batch)
        var published = false
        assertTrue(graph.dashboard.publish(batch) { published = true }); assertTrue(published)
        cards.forEach { card ->
            assertNull(card.countHistory); assertSame(batch.counts.getValue(card.habit.id), card.countSummary)
        }
        return Home(batch, cards)
    }
    private suspend fun businessRows(): Map<String, List<JsonObject>> = db.withTransaction {
        val rows = CountTrustRows(db.openHelper.writableDatabase)
        CountTrustSchema.BUSINESS_TABLES.associateWith { table -> rows.read(table).map { it.value } }
    }

    /** Existing debug mark: repository write returned after its actual Room END, then the
     * production service observed a real save. Never replace it with service return timing.
     */
    private fun savedMillis(start: Long, end: Long): Long? {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d -v raw --pid=${Process.myPid()} -s CompletionActionTiming:D '*:S'")
        val entries = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { lines ->
            lines.filter { it.contains("operation=COUNT_INCREMENT ") }.map { line ->
                line.trim().split(' ').mapNotNull { word -> word.indexOf('=').takeIf { it > 0 }?.let {
                    word.substring(0, it) to word.substring(it + 1)
                } }.toMap()
            }.filter { row -> row["elapsed_realtime_ms"]?.toLongOrNull()?.let { it in start..end } == true }.toList()
        }
        val commits = entries.filter { it["stage"] == "COMMIT_OBSERVED" && it["outcome"] == "observed" }
        val commit = commits.singleOrNull() ?: return null
        if (entries.count { it["operation_id"] == commit["operation_id"] && it["stage"] == "OPERATION" && it["outcome"] == "returned" } != 1) return null
        return commit["operation_ms"]?.toLongOrNull()
    }

    internal suspend fun journey(history: Int) {
        val totalStart = now()
        val seedStart = now(); val seed = CountTrustProductionCostSeed(this).create(history); val seedEnd = now()
        log { put("kind", "seed"); put("history", history); put("elapsed_ns", seedEnd - seedStart)
            put("history_shape", "two_count_habits_one_per_past_day_each"); put("historical_acceptances", "constructed_and_originally_verified") }
        root = root()
        assertNull("Seeding must not issue a trusted root", root?.revision)
        assertEquals(1L, root?.dirty)
        var graph = graph()
        val qualification = measure(history, "first_qualification_and_dashboard", fast = false, qualification = true) { home(graph) }
        assertTrue("Summary dashboard must not load COUNT detail rows", qualification.value.batch.completions.isEmpty())
        assertEquals(history.toLong(), qualification.value.batch.counts.values.sumOf { it.totalQuantity })
        assertTrue(qualification.value.batch.counts.values.all { it.todayQuantity == 0L })
        budget("first qualification ready", qualification.elapsed, 2_000_000_000L)

        val retained = requireNotNull(root)
        val cold = measure(history, "clean_root_same_file_reopen_first_dashboard") {
            storage.reopen(); graph = graph()
            assertEquals(0, graph.counts.reuseDiagnostics.committedEntries)
            home(graph)
        }
        assertEquals(retained, root)
        assertEquals(qualification.value.batch.counts, cold.value.batch.counts)
        budget("trusted cold method-ready", cold.elapsed, 2_000_000_000L)

        val promptRequest = graph.prompt.captureCompletionPrompt()
        val serviceStartMs = SystemClock.elapsedRealtime()
        val service = measure(history, "service_count_local_save_and_result", mutation = true) {
            CompletionActionTiming.withOperation(CompletionActionTiming.Operation.COUNT_INCREMENT) {
                graph.service.incrementCount(app, seed.habits.first().id)
            }
        }
        val serviceEndMs = SystemClock.elapsedRealtime()
        val result = service.value as? CheckInResult.Success ?: error("COUNT_TRUST_COST_COMMAND_FAILED")
        assertTrue(result.recorded)
        val source = requireNotNull(result.followUp); val factUuid = requireNotNull(source.claim.completionUuid)
        assertEquals(seed.habits.first().uuid, source.claim.habitUuid)
        val prompt = measure(history, "necessary_prompt_prepare") {
            graph.prompt.showCompletionPrompt(source, promptRequest)
            requireNotNull(graph.prompt.postCheckInState.value)
        }
        assertSame(source, prompt.value.factPrompt?.completion)
        assertEquals(listOf(metric.id), prompt.value.linkedMetrics.map { it.metricId })
        graph.prompt.closePrompt(prompt.value); assertNull(graph.prompt.postCheckInState.value)
        val shown = measure(history, "post_save_home_and_cards") { home(graph) }
        val current = shown.value.batch.habits.single { it.id == seed.habits.first().id }
        assertEquals(1L, shown.value.batch.counts.getValue(current.id).todayQuantity)
        assertTrue("Summary dashboard must not load COUNT detail rows", shown.value.batch.completions.isEmpty())
        assertEquals(history + 1L, shown.value.batch.counts.values.sumOf { it.totalQuantity })
        val detail = measure(history, "explicit_detail") { graph.counts.read(current, seed.today) }
        assertEquals(shown.value.batch.counts.getValue(current.id), detail.value.summary)
        assertEquals(history / 2 + 1, detail.value.completions.size)
        assertEquals(factUuid, detail.value.completions.single { it.id == detail.value.summary.todayLatestCompletionId }.uuid)

        val queue = measure(history, "outbox_original_source_lookup") { sessions.exclusive { db.withTransaction {
            db.syncOutboxDao().getAll().single().also {
                assertEquals(factUuid, it.entityUuid); assertEquals(current.uuid, it.referenceUuid)
                assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, it.operationId))
            }
        } } }.value
        val requests = sender(seed.http)
        val send = measure(history, "real_loopback_send", mutation = true) { requireNotNull(requests.sendOperation(access(), queue.operationId)) }
        val ack = measure(history, "real_ack_commit", mutation = true) { requests.acceptOperation(send.value) }
        assertEquals(NextOperationAcceptance.COMMITTED, ack.value)
        val terminal = measure(history, "terminal_home_and_cards") { home(graph) }
        assertEquals(shown.value.batch.counts, terminal.value.batch.counts)
        assertEquals(history + 1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))

        val native = businessRows()
        val oracle = measure(history, "terminal_original_complete_validator", fast = false) { sessions.exclusive { db.withTransaction {
            requests.requireAcceptedCountInTransaction(access(), queue.operationId)
            val original = RecurringCompletionEvidenceReader(db, tokens, sessions)
            for (seeded in seed.habits) {
                val row = terminal.value.batch.habits.single { it.id == seeded.id && it.uuid == seeded.uuid }
                val evidence = original.readInTransaction(row, seed.today)
                val quantities = evidence.completions.groupBy { it.businessDate }.mapValues { (_, rows) -> rows.sumOf { it.value.toLong() } }
                val expected = CountHistory(seed.today, evidence.policies, quantities,
                    evidence.policies[seed.today] ?: CountDayPolicy(row.targetValue, row.isCountdown),
                    evidence.unknownDates, evidence.completions, evidence.roundHead, evidence.countIdentity)
                assertEquals(terminal.value.batch.counts.getValue(row.id), expected.summary)
                // Full fact equality belongs to the explicit detail contract. Keep this
                // independent source oracle; an empty summary DTO is not a detail proof.
                val actualDetails = requireNotNull(CountTrustKernel(db, tokens, sessions)
                    .details(row, requireNotNull(tokens.localCoreWriteAccess())))
                assertEquals(expected.completions.size, actualDetails.size)
                assertEquals(expected.completions.toSet(), actualDetails.toSet())
                assertEquals(seed.facts.filter { it.habitId == row.id }.toSet(),
                    actualDetails.filter { it.uuid != factUuid }.toSet())
            }
        } } }
        assertEquals(native, businessRows())
        val replay = measure(history, "ack_replay") { requests.acceptOperation(send.value) }
        assertEquals(NextOperationAcceptance.REPLAYED, replay.value)
        assertEquals(native, businessRows())
        assertEquals(seed.today, LocalDate.now(seed.zone)); assertEquals(seed.zone, ZoneId.systemDefault())

        val saved = savedMillis(serviceStartMs, serviceEndMs)
        if (saved == null) failures += "missing unique production COMMIT_OBSERVED trace"
        else budget("local reliable save", saved * 1_000_000L, 500_000_000L)
        budget("service result", service.elapsed, 700_000_000L)
        budget("necessary prompt from command", prompt.end - service.start, 700_000_000L)
        budget("correct home/cards from command", shown.end - service.start, 700_000_000L)
        budget("ACK", ack.elapsed, 500_000_000L)
        budget("terminal home/cards", terminal.elapsed, 700_000_000L)
        for ((name, value) in listOf("service" to service, "prompt" to prompt, "home" to shown,
            "detail" to detail, "send" to send, "ACK" to ack, "terminal home" to terminal, "replay" to replay)) warm(name, value)
        log {
            put("kind", "terminal"); put("history", history); put("actual_new_commands", 1); put("actual_new_acks", 1)
            put("past_days_per_object", history / 2); put("whole_case_ns", now() - totalStart)
            put("first_qualification_ns", qualification.elapsed); put("clean_root_reopen_ns", cold.elapsed)
            put("local_save_observed_ms", saved?.let(::JsonPrimitive) ?: JsonNull)
            put("service_ns", service.elapsed); put("prompt_from_command_ns", prompt.end - service.start)
            put("home_from_command_ns", shown.end - service.start); put("send_ns", send.elapsed); put("ack_ns", ack.elapsed)
            put("original_complete_oracle_ns", oracle.elapsed); put("replay_ns", replay.elapsed)
            put("original_oracle_passed", true); put("replay_business_unchanged", true); put("outbox", 0)
            put("budgets", "save500ms_result_prompt700ms_cold2s_transaction_completion_upper150ms")
            put("diagnostic_gaps_in_chain_are_included", true); put("database_lock_occupancy_measured", false)
            put("ui", false); put("background_competition", false); put("percentile", false)
            put("failures", JsonArray(failures.map(::JsonPrimitive))); root("terminal", root)
        }
        assertTrue("Original budgets or fast-path gates failed: ${failures.joinToString()}", failures.isEmpty())
    }

    @After fun closeCostResources() {
        owners.forEach { it.close() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
