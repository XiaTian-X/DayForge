package com.dayforge.data.repository

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.Build
import android.app.ActivityManager
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Test-only bounded protocol helper. It is NOT a test itself or a production fault/SQL API.
 * The real native-wrapper driver must call checkpoint only at the exact independently
 * verified outer-transaction boundary, with one hit and setTransactionSuccessful observed.
 */
internal class CountCommandProcessProtocol(private val context: Context) {
    enum class Phase { PREPARE, RECOVER }
    enum class Case(val wire: String, val result: String) {
        PRE_T0_END("pre_t0_end", "not_admitted_unchanged"),
        POST_T0_END("post_t0_end_pre_return", "same_count_applied_once"),
        PRE_T1_END("pre_t1_end", "same_count_applied_once"),
        POST_T1_END("post_t1_end_pre_return", "count_already_committed"),
        POST_METRIC_T0_END("post_metric_t0_end", "same_metric_group_applied_once"),
        POST_METRIC_T1_END("post_metric_t1_end_pre_return", "metric_group_already_committed"),
        POST_T2_END("post_t2_end_pre_return", "delivery_committed_results_retained"),
        POST_GENERATION_PERSIST("post_generation_persist_pre_return", "same_generation_retained_no_command"),
        POST_RETAINED_ENROLL_END("post_retained_enroll_end_pre_return", "retained_enrollment_committed_once"),
        POST_INDEPENDENT_T0_END("post_independent_t0_end_pre_return", "same_independent_value_applied_once"),
        POST_INDEPENDENT_T1_END("post_independent_t1_end_pre_return", "independent_value_already_committed"),
        POST_INDEPENDENT_T2_END("post_independent_t2_end_pre_return", "independent_delivery_already_committed")
    }
    private val args = InstrumentationRegistry.getArguments()
    val run = requireNotNull(args.getString("df_command_run")).also { require(UUID.fromString(it).toString() == it) }
    val nonce = requireNotNull(args.getString("df_command_nonce")).also { require(it.matches(Regex("[0-9a-f]{32}"))) }
    val case = Case.entries.single { it.wire == args.getString("df_command_case") }
    val phase = when (args.getString("df_command_phase")) { "prepare" -> Phase.PREPARE; "recover" -> Phase.RECOVER; else -> error("Unknown phase") }
    val directory = File(context.filesDir, "dayforge-command-process-recovery/$run/${case.wire}")
    private var hits = 0
    private fun processName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else requireNotNull(context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.singleOrNull { it.pid == Process.myPid() }?.processName)

    init {
        check(context.packageName == "com.dayforge.testbed" && processName() == context.packageName)
        check(directory.isDirectory || directory.mkdirs())
    }
    private fun base(stage: String) = buildJsonObject {
        put("format", 1); put("run", run); put("case", case.wire); put("nonce", nonce); put("stage", stage)
        put("target_package", context.packageName); put("process_name", processName()); put("pid", Process.myPid())
    }
    private fun write(name: String, body: JsonObject) {
        require(name in setOf("checkpoint.json", "terminal.json", "checkpoint-left.json", "prepare-terminal.json"))
        val bytes = body.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 16384)
        val atomic = AtomicFile(File(directory, name))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    /** Returning from this method is a TEST FAILURE. No timeout can simulate a successful
     * process death, native rollback, unknown END, recovery result or actual assertion pass.
     */
    fun checkpoint(reference: CountCommandReference, nativeFaultHits: Int, observedOuterCommitBoundary: Boolean) {
        check(phase == Phase.PREPARE && ++hits == 1 && nativeFaultHits == 1 && observedOuterCommitBoundary)
        write("checkpoint.json", JsonObject(base(case.wire) + buildJsonObject {
            put("native_fault_hits", nativeFaultHits); put("reference", Json.parseToJsonElement(reference.encode()))
            put("outer_commit_boundary_observed", true); put("expected_interruption_is_not_junit_pass", true)
        }))
        try {
            check(!CountDownLatch(1).await(45, TimeUnit.SECONDS))
        } finally {
            // SIGKILL cannot run finally. Every timeout/interruption/exception leaving the
            // blocking native window marks it BEFORE the native transaction can unwind.
            write("checkpoint-left.json", JsonObject(base("checkpoint_left") +
                buildJsonObject { put("native_fault_hits", hits); put("may_classify_as_expected_kill", false) }))
        }
        error("HOST_DID_NOT_TERMINATE_EXACT_TARGET_PROCESS")
    }

    fun recordDriverTerminal(result: String) {
        require(result in setOf("PASS", "FAIL"))
        if (phase == Phase.PREPARE) write("prepare-terminal.json", JsonObject(base("driver_terminal") +
            buildJsonObject { put("result", result); put("may_classify_as_expected_kill", false) }))
    }

    fun priorCheckpoint(): JsonObject {
        check(phase == Phase.RECOVER)
        val file = File(directory, "checkpoint.json")
        check(file.isFile && file.length() in 1..16384)
        val body = CountCommandRecords.parse(file.readBytes(), 16384, {})
        check(body["run"] == JsonPrimitive(run) && body["case"] == JsonPrimitive(case.wire) && body["nonce"] == JsonPrimitive(nonce) &&
            body["stage"] == JsonPrimitive(case.wire) && body["target_package"] == JsonPrimitive(context.packageName))
        check(body.getValue("pid").jsonPrimitive.int != Process.myPid()) { "FRESH_PROCESS_REQUIRED" }
        return body
    }

    /** Driver calls only after ALL real assertions and the original business/native oracle.
     * Explicit booleans are provided individually; no default values create a green terminal.
     */
    fun terminal(sameOriginalIdentity: Boolean, fullNativeTerminal: Boolean, originalBusinessOracle: Boolean,
        noDuplicateEffects: Boolean, freshAuthority: Boolean, knownReferenceAndPending: Boolean, requiredChildState: Boolean) {
        val prior = priorCheckpoint()
        check(listOf(sameOriginalIdentity, fullNativeTerminal, originalBusinessOracle, noDuplicateEffects,
            freshAuthority, knownReferenceAndPending, requiredChildState).all { it })
        write("terminal.json", JsonObject(base("terminal") + buildJsonObject {
            put("old_pid", prior.getValue("pid")); put("result", case.result)
            put("assertions", buildJsonObject {
                put("same_original_identity", sameOriginalIdentity); put("full_native_terminal", fullNativeTerminal)
                put("original_business_oracle", originalBusinessOracle); put("no_duplicate_effects", noDuplicateEffects)
                put("fresh_authority", freshAuthority); put("known_reference_and_pending_directory", knownReferenceAndPending)
                put("required_child_state", requiredChildState)
            })
        }))
    }
    /** Real DataStore updateData has returned to the test wrapper, but not TokenManager/Host.
     * This is not a SQL commit point; the original control SQL END is separately observed. */
    fun checkpointGeneration(generation: String, preferencesHash: String, controlEnds: Int) {
        check(case == Case.POST_GENERATION_PERSIST && phase == Phase.PREPARE && ++hits == 1 && controlEnds == 1)
        check(UUID.fromString(generation).toString() == generation && preferencesHash.matches(Regex("[0-9a-f]{64}")))
        write("checkpoint.json", JsonObject(base(case.wire) + buildJsonObject {
            put("metadata_commit_observed", true); put("control_native_ends", controlEnds)
            put("generation", generation); put("preferences_hash", preferencesHash)
            put("expected_interruption_is_not_junit_pass", true)
        }))
        awaitHostDeath()
    }
    fun checkpointEnrollment(storageId: String, datasetId: String, legacyCount: Long, legacyHash: String,
        revision: Long, nativeFaultHits: Int, actualOuterEnd: Boolean) {
        check(case == Case.POST_RETAINED_ENROLL_END && phase == Phase.PREPARE && ++hits == 1 && nativeFaultHits == 1 && actualOuterEnd)
        check(legacyCount == 1L && revision == 2L)
        write("checkpoint.json", JsonObject(base(case.wire) + buildJsonObject {
            put("native_fault_hits", nativeFaultHits); put("outer_commit_boundary_observed", true)
            put("storage_id", storageId); put("dataset_id", datasetId); put("legacy_count", legacyCount)
            put("legacy_hash", legacyHash); put("revision", revision)
            put("expected_interruption_is_not_junit_pass", true)
        }))
        awaitHostDeath()
    }
    private fun awaitHostDeath(): Nothing {
        try { check(!CountDownLatch(1).await(45, TimeUnit.SECONDS)) }
        finally { write("checkpoint-left.json", JsonObject(base("checkpoint_left") +
            buildJsonObject { put("native_fault_hits", hits); put("may_classify_as_expected_kill", false) })) }
        error("HOST_DID_NOT_TERMINATE_EXACT_TARGET_PROCESS")
    }
    fun terminalRetained(sameGenerationAndAuthority: Boolean, oldNativePartition: Boolean,
        actualPersistentStage: Boolean, commandIdentityOrAbsence: Boolean, singleEffects: Boolean,
        freshAuthority: Boolean, terminalRetryReadOnly: Boolean) {
        check(case in RETAINED_CASES)
        val checks = mapOf("same_generation_and_authority" to sameGenerationAndAuthority,
            "old_native_partition" to oldNativePartition, "actual_persistent_stage" to actualPersistentStage,
            "command_identity_or_absence" to commandIdentityOrAbsence, "single_effects" to singleEffects,
            "fresh_authority" to freshAuthority, "terminal_retry_read_only" to terminalRetryReadOnly)
        check(checks.values.all { it })
        val prior = priorCheckpoint()
        write("terminal.json", JsonObject(base("terminal") + buildJsonObject {
            put("old_pid", prior.getValue("pid")); put("result", case.result)
            put("assertions", buildJsonObject { checks.forEach { (key, value) -> put(key, value) } })
        }))
    }
    companion object {
        val RETAINED_CASES = setOf(Case.POST_GENERATION_PERSIST, Case.POST_RETAINED_ENROLL_END,
            Case.POST_INDEPENDENT_T0_END, Case.POST_INDEPENDENT_T1_END, Case.POST_INDEPENDENT_T2_END)
    }

}
