package com.dayforge

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.serialization.json.*
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.repository.DualPhysicalScenario
import dagger.hilt.android.testing.HiltTestApplication
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed dual-installation testbed acceptance, separate from JUnit and P1/P2 success. */
class DualPhysicalDriver : Instrumentation() {
    private lateinit var input: Bundle
    private var deadline = 0L
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        input = Bundle(requireNotNull(arguments))
        require(input.getString("timeout_msec") == "150000")
        require(UUID.fromString(requireNotNull(input.getString("df_dual_run"))).toString() == input.getString("df_dual_run"))
        require(requireNotNull(input.getString("df_dual_nonce")).matches(Regex("[a-f0-9]{32}")))
        require(input.getString("df_dual_phase") in listOf("A1", "B1", "B2", "B3", "A2", "B4", "A3", "B5", "A4"))
        require(input.getString("df_dual_previous") == "none" || requireNotNull(input.getString("df_dual_previous")).matches(Regex("[a-f0-9]{64}")))
        deadline = SystemClock.elapsedRealtime() + 150_000
        InstrumentationRegistry.registerInstance(this, Bundle(input))
        start()
    }
    override fun onStart() {
        val result = Bundle().apply {
            putString("df_dual_driver", "dual-physical/v1")
            for (key in listOf("df_dual_run", "df_dual_nonce", "df_dual_phase", "df_dual_previous"))
                putString(key, requireNotNull(input.getString(key)))
            putInt("df_dual_pid", Process.myPid())
            putString("df_dual_result", "FAIL")
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "dayforge-dual-phase") }
        val phase = executor.submit<String> {
            check(targetContext.packageName == "com.dayforge.testbed")
            waitForIdleSync()
            DualPhysicalScenario().runScenarioPhase(deadline)
        }
        var success = false
        try {
            val left = deadline - SystemClock.elapsedRealtime()
            check(left > 0) { "DUAL_STARTUP_TIMEOUT" }
            result.putString("df_dual_report_sha256", phase.get(left, TimeUnit.MILLISECONDS))
            result.putString("df_dual_result", "PASS")
            success = true
        } catch (failure: Throwable) {
            phase.cancel(true)
            val original = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
            result.putString("df_dual_failure", original.javaClass.name)
            try { result.putString("df_dual_failure_sha256", retainFailure(original)) }
            catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
                result.putString("df_dual_diagnostic_failure", diagnostic.javaClass.name)
                // Never Throwable.toString()/message: even the fallback is the safe tree.
                logFailure(failureTree(original).toString())
            }
        } finally {
            executor.shutdownNow()
            // A cancelled/hung phase is never success. The host retains the isolated files,
            // stops only this package, and does not advance or pretend native END succeeded.
            finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        }
    }
    /** Bounded type/frame/cause/suppressed evidence only. No exception message, HTTP body,
     * object.toString, credential or caller value is serialized; truncation stays explicit. */
    private fun failureTree(error: Throwable): JsonObject {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var nodes = 0
        fun symbol(value: String, limit: Int): String =
            value.takeIf { it.length <= limit && it.matches(Regex("[A-Za-z0-9_.$<>/-]+")) } ?: "REDACTED_SYMBOL"
        fun visit(value: Throwable, depth: Int): JsonObject = buildJsonObject {
            put("type", symbol(value.javaClass.name, 256))
            if (!seen.add(value)) { put("repeated", true); return@buildJsonObject }
            if (depth >= 8 || nodes >= 24) { put("truncated", true); return@buildJsonObject }
            nodes++
            val frames = value.stackTrace
            put("frame_count", frames.size)
            put("frames", buildJsonArray { frames.take(48).forEach { frame -> add(buildJsonObject {
                put("class", symbol(frame.className, 256)); put("method", symbol(frame.methodName, 128))
                put("file", frame.fileName?.let { JsonPrimitive(symbol(it, 96)) } ?: JsonNull)
                put("line", frame.lineNumber)
            }) } })
            put("frames_truncated", frames.size > 48)
            put("cause", value.cause?.let { visit(it, depth + 1) } ?: JsonNull)
            val suppressed = value.suppressed
            put("suppressed_count", suppressed.size)
            put("suppressed", JsonArray(suppressed.take(24).map { visit(it, depth + 1) }))
            put("suppressed_truncated", suppressed.size > 24)
        }
        return visit(error, 0)
    }
    private fun logFailure(safe: String) {
        safe.chunked(3_500).forEachIndexed { index, chunk -> Log.e("DualPhysicalFailure", "part=$index $chunk") }
    }
    private fun retainFailure(error: Throwable): String {
        check(targetContext.packageName == "com.dayforge.testbed")
        val run = requireNotNull(input.getString("df_dual_run")); val phase = requireNotNull(input.getString("df_dual_phase"))
        val directory = File(targetContext.filesDir, "dayforge-dual-physical/$run/${phase.take(1)}")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$phase-failure.json"); check(!file.exists())
        val tree = buildJsonObject {
            put("format", "dual-physical-failure/v1"); put("run", run); put("phase", phase)
            put("nonce", requireNotNull(input.getString("df_dual_nonce"))); put("pid", Process.myPid())
            put("previous", requireNotNull(input.getString("df_dual_previous")))
            put("failure", failureTree(error))
        }
        val bytes = tree.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 1_048_576)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (write: Throwable) { atomic.failWrite(output); throw write }
        logFailure(tree.toString())
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

}
