package com.dayforge.data.repository

import android.database.Cursor
import android.os.Debug
import android.os.SystemClock
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Read-only decomposition on a real authenticated root. It never supplies production authority.
 * The primitive pass REPEATS public production primitives; it is not instrumentation inside open.
 * Real open passes bracket it. Native schema queries bypass the public Room callback, so bytes,
 * rows and monotonic primitive durations are recorded independently of callback query counts.
 */
internal class CountTrustOpenCostProbe(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val habits: List<HabitEntity>) {
    private data class Step(val pass: String, val name: String, val start: Long, val end: Long,
        val cpu: Long?, val returned: Boolean)
    private data class Control(val storage: String, val generation: Long, val dirty: Boolean)
    private data class Result(val header: CountTrustSeal.Header, val environment: CountTrustValidationEnvironment.Snapshot,
        val eligible: List<Boolean>, val common: Boolean)
    private val steps = arrayListOf<Step>()
    private val records = arrayListOf<JsonObject>()

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private suspend fun <T> step(pass: String, name: String, body: suspend () -> T): T {
        val thread = Thread.currentThread(); val cpu = Debug.threadCpuTimeNanos(); val start = now()
        var returned = false
        try { return body().also { returned = true } }
        finally {
            val end = now()
            steps += Step(pass, name, start, end,
                if (Thread.currentThread() === thread) Debug.threadCpuTimeNanos() - cpu else null, returned)
        }
    }

    suspend fun run(): List<JsonObject> {
        val first = transaction("original_before", false)
        val decomposed = transaction("primitive_replay", true)
        val last = transaction("original_after", false)
        check(first == decomposed && first == last) { "OPEN_DIAGNOSTIC_RESULT_CHANGED" }
        records += buildJsonObject {
            put("pass", "primitive_replay"); put("stage", "primitive_component_subtotals")
            put("open_replay_component_sum_ns", steps.filter { it.pass == "primitive_replay" &&
                !it.name.startsWith("extra_") && !it.name.startsWith("fresh_authority_") &&
                !it.name.startsWith("eligible_") && it.name != "global_common" &&
                it.name != "control_schema_guard" }.sumOf { it.end - it.start })
            put("extra_mac_reproduction_sum_ns", steps.filter { it.pass == "primitive_replay" &&
                it.name.startsWith("extra_") }.sumOf { it.end - it.start })
            put("subtotals_are_sequential_replays_not_additive_attribution_of_real_open", true)
        }
        steps.forEach { value -> records += buildJsonObject {
            put("pass", value.pass); put("stage", value.name)
            put("start_ns", value.start); put("end_ns", value.end); put("elapsed_ns", value.end - value.start)
            put("endpoint_thread_cpu_delta_ns", value.cpu?.let(::JsonPrimitive) ?: JsonNull)
            put("same_endpoint_thread_does_not_exclude_intermediate_thread_hops", true)
            put("endpoint_cpu_is_not_continuous_operation_cpu", true)
            put("returned", value.returned); put("cpu_not_a_scheduler_or_binder_wait_measure", true)
        } }
        return records.toList()
    }

    private suspend fun transaction(pass: String, decomposed: Boolean): Result {
        val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
        val accountRequested = now()
        var accountEntered = 0L; var txRequested = 0L; var txEntered = 0L
        var txBodyEnd = 0L; var txReturned = 0L
        var maintenanceRequested = 0L; var maintenanceEntered = 0L
        var maintenanceBodyEnd = 0L; var maintenanceReturned = 0L
        val value = sessions.exclusive {
            accountEntered = now(); txRequested = now()
            val completed = owner.transaction(database) {
                txEntered = now()
                val access = step(pass, "fresh_authority_entry") { requireNotNull(tokens.localCoreWriteAccess()) }
                maintenanceRequested = now()
                val result = owner.maintenance(database) {
                    maintenanceEntered = now()
                    step(pass, "control_schema_guard") { CountTrustSchema.requireControl(database.openHelper.writableDatabase) }
                    val observed = if (decomposed) primitives(pass, owner, access) else original(pass, access)
                    maintenanceBodyEnd = now()
                    observed
                }
                maintenanceReturned = now()
                // The diagnosis itself cannot reuse entry authority at the final boundary.
                val finalAccess = step(pass, "fresh_authority_final") { requireNotNull(tokens.localCoreWriteAccess()) }
                check(access == finalAccess) { "OPEN_DIAGNOSTIC_AUTHORITY_CHANGED" }
                txBodyEnd = now()
                result
            }
            txReturned = now()
            completed.end.requireCandidate(completed.value)
            completed.value
        }
        val accountReturned = now()
        records += buildJsonObject {
            put("pass", pass); put("stage", "controlled_transaction_boundaries")
            put("account_request_to_entry_ns", accountEntered - accountRequested)
            put("transaction_request_to_body_entry_ns", txEntered - txRequested)
            put("transaction_body_ns", txBodyEnd - txEntered)
            put("outer_maintenance_request_to_callback_entry_ns", maintenanceEntered - maintenanceRequested)
            put("outer_maintenance_callback_body_ns", maintenanceBodyEnd - maintenanceEntered)
            put("outer_maintenance_callback_exit_to_return_ns", maintenanceReturned - maintenanceBodyEnd)
            put("kernel_open_runs_with_preheld_maintenance", true)
            put("open_stage_excludes_outer_maintenance_entry_and_exit", true)
            put("body_exit_to_actual_transaction_return_ns", txReturned - txBodyEnd)
            put("account_callback_entry_to_return_ns", accountReturned - accountEntered)
            put("actual_end_receipt_checked", true)
            put("exact_database_lock_or_commit_duration", false)
            put("waiting_components_not_separated", "Room dispatch+BEGIN; END+dispatch; account wrapper overhead")
        }
        return value
    }

    private suspend fun original(pass: String, access: LocalCoreWriteAccess): Result {
        val opened = step(pass, "real_kernel_open_with_preheld_maintenance") {
            requireNotNull(CountTrustKernel(database, tokens, sessions).open(access))
        }
        val eligible = habits.mapIndexed { position, habit -> step(pass, "eligible_$position") { opened.eligible(habit) } }
        val common = step(pass, "global_common") { opened.index.get("global_common") == JsonPrimitive(true) }
        check(eligible.all { it } && common && opened.header.validator == CountTrustSeal.VALIDATOR)
        records += buildJsonObject { put("pass", pass); put("stage", "index_volume")
            put("loaded_nodes", opened.index.loadedNodes); put("loaded_bytes", opened.index.loadedBytes)
            put("written_nodes", opened.index.writtenNodes); put("verify_contents_called", false) }
        check(opened.index.writtenNodes == 0)
        return Result(opened.header, opened.environment, eligible, common)
    }

    private suspend fun primitives(pass: String, owner: CountTrustRuntime, access: LocalCoreWriteAccess): Result {
        step(pass, "runtime_require_usable") { owner.requireUsable() }
        val sql = database.openHelper.writableDatabase
        val control = step(pass, "control_read") {
            sql.query("SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1").use {
                check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING &&
                    it.getType(1) == Cursor.FIELD_TYPE_INTEGER && it.getType(2) == Cursor.FIELD_TYPE_INTEGER &&
                    it.getLong(1) >= 0 && it.getLong(2) in 0..1)
                Control(it.getString(0), it.getLong(1), it.getLong(2) == 1L).also { _ -> check(!it.moveToNext()) }
            }
        }
        check(!control.dirty)
        val header = step(pass, "header_read") { requireNotNull(CountTrustSeal.read(sql)) }
        check(header.generation == control.generation && header.validator == CountTrustSeal.VALIDATOR)
        val binding = step(pass, "binding_json_and_hash") { CountTrustSeal.binding(access, control.storage, header.validator) }
        check(binding == header.binding)
        val schema = step(pass, "schema_native_snapshot") { owner.schemaSnapshot() }
        val tree = step(pass, "schema_json_tree") { JsonArray(schema.map { row ->
            JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }) }
        val text = step(pass, "schema_json_serialize") { tree.toString() }
        val bytes = step(pass, "schema_utf8") { text.toByteArray(Charsets.UTF_8) }
        val hash = step(pass, "schema_sha256") { CountTrustIndex.hash(bytes) }
        check(hash == header.schemaHash)
        check(step(pass, "real_seal_matches_key_body_mac") { CountTrustSeal().matches(header, binding, hash) })
        records += buildJsonObject { put("pass", pass); put("stage", "schema_volume")
            put("rows", schema.size); put("utf8_bytes", bytes.size); put("schema_text_recorded", false) }
        val caller = currentCoroutineContext()
        val index = step(pass, "index_constructor") { CountTrustIndex(sql, binding, header.root) { caller.ensureActive() } }
        val encoded = step(pass, "environment_index_get") { requireNotNull(index.get(CountTrustValidationEnvironment.KEY)) }
        val environment = step(pass, "environment_decode") { CountTrustValidationEnvironment.Snapshot.decode(encoded) }
        val current = step(pass, "environment_current") { CountTrustValidationEnvironment.current() }
        check(environment == current)
        val eligible = habits.mapIndexed { position, habit -> step(pass, "eligible_$position") {
            index.get("eligible:${habit.uuid}") == JsonPrimitive(true)
        } }
        val common = step(pass, "global_common") { index.get("global_common") == JsonPrimitive(true) }
        check(eligible.all { it } && common && index.writtenNodes == 0)
        records += buildJsonObject { put("pass", pass); put("stage", "index_volume")
            put("loaded_nodes", index.loadedNodes); put("loaded_bytes", index.loadedBytes)
            put("written_nodes", index.writtenNodes); put("verify_contents_called", false) }
        // Extra READ-ONLY seal reproduction is separately labelled; never substituted for
        // real matches above, never included in the primitive-open subtotal, never signs a root.
        // It lets key lookup and MAC work be measured without adding production hooks.
        sealReproduction(pass, header)
        return Result(header, environment, eligible, common)
    }

    private suspend fun sealReproduction(pass: String, header: CountTrustSeal.Header) {
        val body = step(pass, "extra_seal_body_reproduction") { buildJsonObject {
            put("domain", "DayForge/count-trust-root"); put("format", CountTrustSeal.FORMAT); put("validator", header.validator)
            put("binding", header.binding); put("schema", header.schemaHash); put("index", "count-trust-v1")
            put("scope", "directory/1970-01/null-round/open-bounds")
            put("height", header.root.height); put("count", header.root.count); put("digest", header.root.digest)
            put("revision", header.revision); put("generation", header.generation)
        }.toString().toByteArray(Charsets.UTF_8) }
        val store = step(pass, "extra_keystore_get_instance_load") { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
        val key = step(pass, "extra_keystore_existing_key_lookup") {
            requireNotNull(store.getKey("dayforge.count.trust.root.v1", null) as? SecretKey)
        }
        val mac = step(pass, "extra_mac_get_instance") { Mac.getInstance("HmacSHA256") }
        step(pass, "extra_mac_init") { mac.init(key) }
        val tag = step(pass, "extra_mac_do_final") { mac.doFinal(body) }
        check(MessageDigest.isEqual(header.mac(), tag)) { "OPEN_DIAGNOSTIC_SEAL_REPRODUCTION_MISMATCH" }
    }
}

/** Passive duration accounting around the REAL fixture cipher. No plaintext/ciphertext/key is
 * retained. Timing does not cache credentials, change auth decisions or swallow exceptions.
 */
internal class CountTrustCredentialCostProbe {
    private data class Event(val start: Long, val end: Long, val cpu: Long, val returned: Boolean, val wasNull: Boolean)
    private val events = arrayListOf<Event>()
    private var overflow = false
    fun decrypt(body: () -> String?): String? {
        val cpu = Debug.threadCpuTimeNanos(); val start = SystemClock.elapsedRealtimeNanos()
        var returned = false; var wasNull = false
        try { return body().also { returned = true; wasNull = it == null } }
        finally {
            val end = SystemClock.elapsedRealtimeNanos(); val consumed = Debug.threadCpuTimeNanos() - cpu
            synchronized(this) { if (events.size < 4096) events += Event(start, end, consumed, returned, wasNull) else overflow = true }
        }
    }
    @Synchronized fun summary(start: Long, end: Long): JsonObject {
        val contained = events.filter { it.start >= start && it.end <= end }
        val crossing = events.count { it.start < end && it.end > start && (it.start < start || it.end > end) }
        return buildJsonObject {
            put("completed_calls", contained.size); put("wall_sum_ns", contained.sumOf { it.end - it.start })
            put("wall_max_ns", contained.maxOfOrNull { it.end - it.start } ?: 0)
            put("thread_cpu_sum_ns", contained.sumOf { it.cpu }); put("returned_null", contained.count { it.wasNull })
            put("threw", contained.count { !it.returned }); put("crossing_intervals", crossing); put("overflow", overflow)
            put("overlap_observed", contained.sortedBy { it.start }.zipWithNext().any { (before, after) -> before.end > after.start })
            put("sum_is_not_exclusive_critical_path_or_binder_wait", true)
        }
    }
}
