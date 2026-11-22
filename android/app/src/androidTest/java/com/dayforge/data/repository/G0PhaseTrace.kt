package com.dayforge.data.repository

import android.os.SystemClock
import com.dayforge.data.history.HistoryBlockDiagnostics
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import android.database.Cursor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Operation-local diagnostics, never authorization. Nested spans are NOT additive.
 * Ciphertext labels live only until this operation returns and are never logged or reused.
 */
internal class G0PhaseTrace(val driverTiming: Boolean = true, private val waitDiagnostics: Boolean = false) {
    val waitSamples: MutableList<G0WaitDiagnostics.Sample> = Collections.synchronizedList(mutableListOf())
    private fun beginWait(phase:String) = if(waitDiagnostics) G0WaitDiagnostics.begin(phase) else null
    private fun endWait(started:G0WaitDiagnostics.Started?,ok:Boolean) {
        if(started!=null) waitSamples.add(G0WaitDiagnostics.end(started,ok))
    }
    data class Span(val phase: String, val start: Long, val end: Long, val ok: Boolean, val cpuNanos: Long? = null)
    val spans: MutableList<Span> = Collections.synchronizedList(mutableListOf())
    @Volatile private var access: String? = null
    @Volatile private var refresh: String? = null
    var beforeFinal: suspend () -> Unit = {}
    fun now() = SystemClock.elapsedRealtimeNanos()
    fun record(phase: String, start: Long, ok: Boolean) { spans.add(Span(phase, start, now(), ok)) }
    suspend fun <T> stage(phase: String, block: suspend () -> T): T {
        val diagnostic=beginWait(phase)
        val start = now(); var ok = false
        return try { block().also { ok = true } } finally { record(phase, start, ok);endWait(diagnostic,ok) }
    }
    fun <T> call(phase: String, block: () -> T): T {
        val diagnostic=beginWait(phase)
        val start = now(); var ok = false
        return try { block().also { ok = true } } finally { record(phase, start, ok);endWait(diagnostic,ok) }
    }
    // Synchronous same-thread call only. CPU indication is optional and is not an I/O profiler.
    fun <T> cpuCall(phase: String, block: () -> T): T {
        val diagnostic=beginWait(phase)
        val start=now();val cpuStart=android.os.Debug.threadCpuTimeNanos();var ok=false
        return try { block().also { ok=true } } finally {
            val cpuEnd=android.os.Debug.threadCpuTimeNanos()
            val cpu=if(cpuStart>=0 && cpuEnd>=cpuStart) cpuEnd-cpuStart else null
            spans.add(Span(phase,start,now(),ok,cpu))
            endWait(diagnostic,ok)
        }
    }
    private val historyBlockObserver = HistoryBlockDiagnostics.Observer { stage ->
        val phase = when (stage) {
            HistoryBlockDiagnostics.Stage.NODE_DECODE -> "count_block_node_decode"
            HistoryBlockDiagnostics.Stage.NODE_ENCODE -> "count_block_node_encode"
        }
        val diagnostic = beginWait(phase)
        val start = now(); val cpuStart = android.os.Debug.threadCpuTimeNanos()
        HistoryBlockDiagnostics.Observation { ok ->
            val cpuEnd = android.os.Debug.threadCpuTimeNanos()
            val cpu = if (cpuStart >= 0 && cpuEnd >= cpuStart) cpuEnd - cpuStart else null
            spans.add(Span(phase, start, now(), ok, cpu))
            endWait(diagnostic, ok)
        }
    }
    fun role(value: String): String = when (value) {
        access -> "access"
        refresh -> "refresh"
        else -> "unclassified"
    }
    suspend fun <T> run(block: suspend () -> T): T {
        check(current.get() == null) { "G0_TRACE_NESTED" }
        return try { withContext(current.asContextElement(this) + HistoryBlockDiagnostics.current.asContextElement(historyBlockObserver)) { stage("operation", block) } }
        finally { access = null; refresh = null }
    }
    fun nanos(phase: String) = spans.filter { it.phase == phase }.sumOf { it.end - it.start }
    fun count(phase: String) = spans.count { it.phase == phase }
    companion object {
        val current = ThreadLocal<G0PhaseTrace>()
        fun wrap(data: DataStore<Preferences>): DataStore<Preferences> = object : DataStore<Preferences> {
            override val data = flow {
                val trace = current.get()
                var waiting = trace?.now()
                // Preserve DataStore's continuous Flow contract, even though fresh authority
                // reads take only the first value. Do not turn subscriptions into one-shot reads.
                try {
                    data.data.collect { snapshot ->
                        waiting?.let { trace?.record("datastore_read", it, true) }
                        waiting = null
                        trace?.access = snapshot[stringPreferencesKey("access_token")]
                        trace?.refresh = snapshot[stringPreferencesKey("refresh_token")]
                        emit(snapshot)
                        waiting = trace?.now()
                    }
                } catch (failure: Throwable) {
                    waiting?.let { trace?.record("datastore_read", it, false) }
                    throw failure
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = data.updateData(transform)
        }
    }
}

/** Measures real driver calls, including begin/end and cursor stepping, below Room.
 * QueryCallback is unsuitable for elapsed SQL timing: it runs before execution.
 */
internal class G0TimingFactory(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            private var previous: SupportSQLiteDatabase? = null
            private var wrapped: SupportSQLiteDatabase? = null
            private fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(this) {
                if (previous !== raw) {
                    previous = raw
                    wrapped = proxy(SupportSQLiteDatabase::class.java, raw, "sql_driver")
                }
                requireNotNull(wrapped)
            }
            override val readableDatabase get() = wrap(helper.readableDatabase)
            override val writableDatabase get() = wrap(helper.writableDatabase)
        }
    }
    private fun <T : Any> proxy(type: Class<T>, target: T, category: String): T = requireNotNull(type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            fun invoke(): Any? = try { method.invoke(target, *(args ?: emptyArray())) }
                catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
            val trace = G0PhaseTrace.current.get()
            if (trace == null || (!trace.driverTiming && !method.name.startsWith("beginTransaction") && method.name != "endTransaction")) invoke() else {
                val outer = method.name.startsWith("beginTransaction") && target is SupportSQLiteDatabase && !target.inTransaction()
                val phase = when {
                    method.name.startsWith("beginTransaction") -> if (outer) "tx_begin_outer" else "tx_begin_nested"
                    method.name == "endTransaction" -> "tx_end"
                    category == "sql_cursor" && method.name == "moveToNext" -> "sql_cursor_step"
                    else -> category
                }
                val endStart = if (method.name == "endTransaction") trace.now() else null
                var ended = false
                val result = try { trace.call(phase) { invoke() }.also { ended = true } } finally {
                    if (endStart != null && target is SupportSQLiteDatabase) {
                        val inside=trace.call("tx_end_boundary_probe") { target.inTransaction() }
                        if(!inside) trace.record("tx_end_outer", endStart, ended)
                    }
                }
                when (result) {
                    is Cursor -> proxy(Cursor::class.java, result, "sql_cursor")
                    is SupportSQLiteStatement -> proxy(SupportSQLiteStatement::class.java, result, "sql_statement")
                    else -> result
                }
            }
        }
    ))
}
