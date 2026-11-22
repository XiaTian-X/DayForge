package com.dayforge.ui

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dayforge.sync.AutoSyncWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Fixed two AutoSync unique queues only. Original public WorkInfo + original WM INFO logs;
 * no WorkerFactory replacement, scheduler/test driver, reflection, logger or business spy. */
internal class CountBacklogWork(private val context: Context, private val directory: File) {
    data class Row(val name: String, val id: UUID, val state: WorkInfo.State, val attempt: Int,
        val generation: Int, val nextAt: Long, val periodic: Boolean, val at: Long)
    data class ResultLine(val id: UUID, val result: String, val line: String)
    private val manager get() = WorkManager.getInstance(context)
    private val monitor = Any()
    private val baseline = linkedSetOf<UUID>()
    private val latest = linkedMapOf<UUID, Row>()
    private val history = arrayListOf<Row>()
    private val ran = linkedSetOf<UUID>()
    private var startEpochMillis = 0L
    private var active = false
    @Volatile var safe = false
        private set
    private var finalLogs = emptyList<ResultLine>()
    private var removed = emptySet<UUID>()
    suspend fun begin() {
        check(!active && context.packageName == "com.dayforge.testbed")
        for (name in NAMES) {
            val old = infos(name)
            check(old.all { it.state.isFinished }) { "Prior AutoSync work remains active" }
            baseline += old.map { it.id }
        }
        startEpochMillis = System.currentTimeMillis(); active = true
    }
    private suspend fun infos(name: String): List<WorkInfo> = withContext(Dispatchers.IO) {
        manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
    }
    suspend fun sample(): List<Row> {
        check(active)
        val rows = NAMES.flatMap { name -> infos(name).filter { it.id !in baseline }.map { info ->
            check(AutoSyncWorker::class.java.name in info.tags) { "Unexpected worker in AutoSync queue" }
            check((info.periodicityInfo != null) == (name == PERIODIC)) { "AutoSync unique queue has the wrong periodicity" }
            Row(name, info.id, info.state, info.runAttemptCount, info.generation,
                info.nextScheduleTimeMillis, info.periodicityInfo != null, SystemClock.elapsedRealtimeNanos())
        } }
        synchronized(monitor) {
            for (row in rows) {
                if (row.state == WorkInfo.State.RUNNING) ran += row.id
                val old = latest[row.id]
                if (old == null || old.copy(at = row.at) != row) {
                    check(history.size < 128) { "Work observation capacity exceeded" }
                    history += row
                }
                latest[row.id] = row
            }
        }
        val immediate = rows.filter { it.name == IMMEDIATE }
        check(immediate.count { it.state == WorkInfo.State.RUNNING } <= 1)
        // A retrying owner may be ENQUEUED while its one successor remains BLOCKED.
        check(immediate.count { !it.state.isFinished } <= 2)
        if (immediate.any { it.state == WorkInfo.State.RUNNING })
            check(immediate.count { it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) } <= 1)
        return rows
    }
    fun recorded(): List<Row> = synchronized(monitor) { history.toList() }
    suspend fun readResultLogs(): List<ResultLine> = withContext(Dispatchers.IO) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "logcat -d -v epoch -t 1000 --pid=${Process.myPid()} -s WM-WorkerWrapper:I")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val out = java.io.ByteArrayOutputStream(); val block = ByteArray(4096)
            while (true) {
                val count = stream.read(block); if (count < 0) break
                check(out.size() + count <= 524288) { "Worker log observation capacity exceeded" }
                out.write(block, 0, count)
            }
            out.toByteArray()
        }
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        check(lines.size < 1000) { "Worker log tail may be truncated" }
        File(directory, "worker-results.log").writeBytes(bytes)
        val result = Regex("Worker result (SUCCESS|RETRY|FAILURE) for .*id=([0-9a-f-]{36}).*")
        lines.mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 4)
            val epoch = fields.firstOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
            if (epoch * 1000.0 < startEpochMillis || fields.getOrNull(1)?.toIntOrNull() != Process.myPid() ||
                AutoSyncWorker::class.java.name !in line) return@mapNotNull null
            val match = result.find(line) ?: return@mapNotNull null
            val id = UUID.fromString(match.groupValues[2]); if (id in baseline) return@mapNotNull null
            ResultLine(id, match.groupValues[1], line)
        }
    }
    /** Call after foreground commands are proven quiescent and the owned Coordinator scope joined.
     * Read-only UI observers may remain for the final candidate; failure teardown joins all UI.
     * Cancellation is never exit evidence.
     * Periodic next iteration is cancelled only AFTER its real SUCCESS and future30min proof. */
    suspend fun drain() = withTimeout(45_000) {
        check(active && !safe)
        while (true) {
            val current = sample(); val logs = readResultLogs(); val byId = logs.groupBy { it.id }
            val all = synchronized(monitor) { latest.toMap() }
            val known = all.keys
            check(logs.all { it.id in known }) { "AutoSync result UUID was not observed in either owned queue" }
            val currentIds = current.map { it.id }.toSet()
            val unproven = all.values.filter { row ->
                val last = byId[row.id]?.lastOrNull()?.result
                when {
                    row.id !in currentIds -> last !in setOf("SUCCESS", "FAILURE")
                    row.state in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED) ->
                        last != if (row.state == WorkInfo.State.SUCCEEDED) "SUCCESS" else "FAILURE"
                    row.periodic && row.state == WorkInfo.State.ENQUEUED && row.attempt == 0 &&
                        row.nextAt > System.currentTimeMillis() + 900_000L && row.id in synchronized(monitor) { ran.toSet() } ->
                        last !in setOf("SUCCESS", "FAILURE")
                    else -> true
                }
            }
            if (all.isEmpty() && current.isEmpty() && logs.isEmpty()) {
                safe = true // No new work after the joined producer/serial query barrier; not a success claim.
                break
            }
            if (all.isNotEmpty() && unproven.isEmpty()) {
                // Only a future periodic iteration after a proven returned body remains.
                for (row in current.filter { it.periodic && !it.state.isFinished }) {
                    withContext(Dispatchers.IO) { manager.cancelWorkById(row.id).result.get(5, TimeUnit.SECONDS) }
                    val after = withContext(Dispatchers.IO) { manager.getWorkInfoById(row.id).get(5, TimeUnit.SECONDS) }
                    check(after != null && after.state == WorkInfo.State.CANCELLED &&
                        after.runAttemptCount == row.attempt && after.generation == row.generation) {
                        "Periodic cancellation raced an unproven new attempt"
                    }
                }
                val final = sample()
                check(final.all { it.state.isFinished }) { "AutoSync enqueued after owned producer drain" }
                finalLogs = logs; removed = known - currentIds
                safe = true // Actual non-cancelled body returns established above, not from CANCELLED.
                break
            }
            delay(100)
        }
    }
    fun requireHealthy(expectPeriodic: Boolean) {
        check(safe)
        check(recorded().map { it.name }.toSet() == if (expectPeriodic) NAMES.toSet() else setOf(IMMEDIATE)) {
            "The original expected AutoSync queues were not both observed"
        }
        check(finalLogs.isNotEmpty() && finalLogs.none { it.result == "FAILURE" }) { "AutoSync reported a real failure" }
        check(finalLogs.groupBy { it.id }.values.all { it.last().result == "SUCCESS" }) { "A retry is not a completed sync" }
    }
    fun writeEvidence() {
        File(directory, "work.json").writeText(buildJsonObject {
            put("format", 1); put("pid", Process.myPid()); put("start_epoch_ms", startEpochMillis)
            put("safe_body_exit_proven", safe); put("normal_results_not_cancel_inference", true)
            put("baseline", JsonArray(baseline.map { JsonPrimitive(it.toString()) }))
            put("removed_after_normal_result", JsonArray(removed.map { JsonPrimitive(it.toString()) }))
            put("states", JsonArray(recorded().map { row -> buildJsonObject {
                put("name", row.name); put("id", row.id.toString()); put("state", row.state.name)
                put("attempt", row.attempt); put("generation", row.generation); put("next_epoch_ms", row.nextAt)
                put("periodic", row.periodic); put("observed_elapsed_ns", row.at)
            } }))
            put("results", JsonArray(finalLogs.map { buildJsonObject { put("id", it.id.toString()); put("result", it.result) } }))
        }.toString())
    }
    companion object {
        const val IMMEDIATE = "dayforge-auto-sync"
        const val PERIODIC = "dayforge-periodic-sync"
        val NAMES = listOf(IMMEDIATE, PERIODIC)
    }
}
