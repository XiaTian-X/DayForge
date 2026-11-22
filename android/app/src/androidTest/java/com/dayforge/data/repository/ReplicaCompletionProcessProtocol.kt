package com.dayforge.data.repository

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Fixed replica-only host evidence. It is neither a durable business API nor a COUNT receipt. */
internal class ReplicaCompletionProcessProtocol(
    private val context: Context,
    private val deadlineElapsedMillis: Long
) {
    enum class Phase(val wire: String) {
        PREPARE_BOUND("prepare_bound"), ACTUAL_FAULT("actual_fault"), FRESH_COMPLETE("fresh_complete")
    }
    enum class Case(val wire: String, val fault: String) {
        FILE_REPAIR("file_repair", "IMAGE_HASH"),
        FINAL_DS_FORWARD("final_ds_forward", "FINAL_DATASTORE_WRITE_FAILURE")
    }
    private val args = InstrumentationRegistry.getArguments()
    val run = requireNotNull(args.getString("df_replica_run")).also { require(UUID.fromString(it).toString() == it) }
    val nonce = requireNotNull(args.getString("df_replica_nonce")).also { require(it.matches(Regex("[0-9a-f]{32}"))) }
    val case = Case.entries.single { it.wire == args.getString("df_replica_case") }
    val phase = Phase.entries.single { it.wire == args.getString("df_replica_phase") }
    val directory = File(context.filesDir, "dayforge-replica-final-process/$run/${case.wire}")
    val phaseDirectory = File(directory, phase.wire)
    private var checkpointHits = 0

    init {
        require(args.getString("timeout_msec") == "150000")
        check(context.packageName == "com.dayforge.testbed" && Application.getProcessName() == context.packageName)
        require(remainingMillis() in 1..PHASE_MILLIS)
        if (phase == Phase.PREPARE_BOUND) check(!directory.exists()) { "REPLICA_PROCESS_CASE_ALREADY_STARTED" }
        else check(directory.isDirectory && File(directory, "seed.json").isFile) { "REPLICA_PROCESS_ORIGINAL_FILES_MISSING" }
        check(!phaseDirectory.exists() && phaseDirectory.mkdirs()) { "REPLICA_PROCESS_PHASE_ALREADY_STARTED" }
    }

    fun remainingMillis(): Long = (deadlineElapsedMillis - SystemClock.elapsedRealtime()).also {
        check(it > 0) { "REPLICA_PROCESS_ABSOLUTE_PHASE_TIMEOUT" }
    }

    private fun base(stage: String) = buildJsonObject {
        put("format", 1); put("family", FAMILY); put("run", run); put("case", case.wire)
        put("nonce", nonce); put("phase", phase.wire); put("stage", stage)
        put("target_package", context.packageName); put("process_name", Application.getProcessName())
        put("pid", Process.myPid()); put("phase_budget_ms", PHASE_MILLIS)
    }

    private fun write(name: String, body: JsonObject) {
        require(name in setOf("checkpoint.json", "checkpoint-left.json", "driver-terminal.json", "terminal.json"))
        val file = File(phaseDirectory, name)
        check(!file.exists()) { "REPLICA_PROCESS_EVIDENCE_ALREADY_EXISTS" }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16384)
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    private fun merged(stage: String, evidence: JsonObject): JsonObject {
        val original = base(stage)
        require(evidence.keys.intersect(original.keys).isEmpty())
        return JsonObject(original + evidence)
    }

    /** Prior marker is an identity link, never a substitute for opening/validating real state. */
    fun priorCheckpoint(prior: Phase): JsonObject {
        require(prior != Phase.FRESH_COMPLETE && prior.ordinal < phase.ordinal)
        val folder = File(directory, prior.wire)
        check(!File(folder, "checkpoint-left.json").exists() && !File(folder, "driver-terminal.json").exists()) {
            "REPLICA_PROCESS_PRIOR_CHECKPOINT_LEFT"
        }
        val file = File(folder, "checkpoint.json")
        check(file.isFile && file.length() in 1..16384)
        val value = CountCommandRecords.parse(file.readBytes(), 16384, {})
        check(value["format"] == JsonPrimitive(1) && value["family"] == JsonPrimitive(FAMILY) &&
            value["run"] == JsonPrimitive(run) && value["case"] == JsonPrimitive(case.wire) &&
            value["nonce"] == JsonPrimitive(nonce) && value["phase"] == JsonPrimitive(prior.wire) &&
            value["target_package"] == JsonPrimitive(context.packageName) &&
            value["process_name"] == JsonPrimitive(context.packageName) && value["phase_budget_ms"] == JsonPrimitive(PHASE_MILLIS))
        check(value["stage"] == JsonPrimitive(if (prior == Phase.PREPARE_BOUND) "bound_closed" else "fault_closed"))
        check(value.getValue("pid").jsonPrimitive.int > 0 && value.getValue("pid").jsonPrimitive.int != Process.myPid()) {
            "REPLICA_PROCESS_FRESH_PID_REQUIRED"
        }
        check(value["resources_closed"] == JsonPrimitive(true) && value["no_unknown_graph_boundary"] == JsonPrimitive(true) &&
            value["expected_interruption_is_not_junit_pass"] == JsonPrimitive(true))
        if (prior == Phase.ACTUAL_FAULT) {
            val bound = priorCheckpoint(Phase.PREPARE_BOUND)
            check(value["prepare_pid"] == bound["pid"] && value["pid"] != bound["pid"] &&
                value["prepare_checkpoint_sha256"] == JsonPrimitive(checkpointHash(Phase.PREPARE_BOUND)))
            check(listOf("seed_sha256", "archive_sha256", "recovery_id").all { value[it] == bound[it] })
            check(value["fault_code"] == JsonPrimitive(case.fault) && value["injection_hits"] == JsonPrimitive(1) &&
                value["exact_fault_cause_confirmed"] == JsonPrimitive(true) && value["release_fate"] == JsonPrimitive("CLOSED"))
        }
        return value
    }

    fun checkpointHash(prior: Phase): String = sha256(File(directory, "${prior.wire}/checkpoint.json").readBytes())

    fun checkpoint(evidence: JsonObject): Nothing {
        check(phase != Phase.FRESH_COMPLETE && ++checkpointHits == 1)
        check(evidence["resources_closed"] == JsonPrimitive(true) && evidence["no_unknown_graph_boundary"] == JsonPrimitive(true))
        remainingMillis()
        write("checkpoint.json", merged(if (phase == Phase.PREPARE_BOUND) "bound_closed" else "fault_closed",
            JsonObject(evidence + ("expected_interruption_is_not_junit_pass" to JsonPrimitive(true)))))
        try {
            val wait = minOf(45_000L, remainingMillis())
            check(!CountDownLatch(1).await(wait, TimeUnit.MILLISECONDS))
        } finally {
            // A real SIGKILL cannot write this. Any return/timeout/cancel invalidates host classification.
            write("checkpoint-left.json", JsonObject(base("checkpoint_left") +
                buildJsonObject { put("may_classify_as_expected_kill", false) }))
        }
        error("REPLICA_HOST_DID_NOT_TERMINATE_EXACT_PROCESS")
    }

    fun terminal(evidence: JsonObject) {
        check(phase == Phase.FRESH_COMPLETE)
        val assertions = evidence.getValue("assertions").jsonObject
        check(assertions.keys == ASSERTIONS && assertions.values.all { it == JsonPrimitive(true) })
        val prepared = priorCheckpoint(Phase.PREPARE_BOUND); val fault = priorCheckpoint(Phase.ACTUAL_FAULT)
        check(evidence["prepare_pid"] == prepared["pid"] && evidence["fault_pid"] == fault["pid"])
        check(evidence["prepare_checkpoint_sha256"] == JsonPrimitive(checkpointHash(Phase.PREPARE_BOUND)) &&
            evidence["fault_checkpoint_sha256"] == JsonPrimitive(checkpointHash(Phase.ACTUAL_FAULT)))
        check(evidence["resources_closed"] == JsonPrimitive(true) && evidence["result"] == JsonPrimitive("same_replica_completed_and_usable"))
        remainingMillis(); write("terminal.json", merged("terminal", evidence)); remainingMillis()
    }

    fun recordDriverTerminal(result: String) {
        require(result in setOf("PASS", "FAIL"))
        if (result == "PASS") {
            check(phase == Phase.FRESH_COMPLETE && File(phaseDirectory, "terminal.json").isFile)
            remainingMillis()
        }
        // FAIL is best-effort failure recording even after the business deadline expired.
        write("driver-terminal.json", JsonObject(base("driver_terminal") + buildJsonObject {
            put("result", result); put("may_classify_as_expected_kill", false)
        }))
        if (result == "PASS") remainingMillis()
    }

    companion object {
        const val FAMILY = "replica-final-process/v1"
        const val PHASE_MILLIS = 150_000L
        val ASSERTIONS = setOf("same_recovery_and_namespace", "closed_sql_and_durable_stage", "original_archive_and_seed",
            "fresh_material_proof", "fresh_authority_and_server_state", "completed_witness_and_pointer",
            "original_business_projection", "ordinary_access_restored_without_writes")
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
