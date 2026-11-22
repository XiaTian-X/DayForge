package com.dayforge.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.timer.TimerWidget
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Test-only observation of the actual Worker/refresher. No replacement scheduler or DB.
 * Requires the explicit real-worker target overlay to remove WorkManagerInitializer. */
internal object RealWidgetRefreshRuntime {
    const val ARGUMENT = "dayforge.widgetRefresh"
    private const val TAG = "RealWidgetLifecycle"
    private val lock = Any()
    private var installed = false
    private var current: Session? = null
    private var poison: String? = null

    fun isReal(value: String?): Boolean = when (value) {
        null -> false
        "real" -> true
        else -> error("Unsupported $ARGUMENT=$value")
    }

    fun install(context: Context) {
        check(context.packageName == "com.dayforge.testbed")
        synchronized(lock) { check(!installed) }
        WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(context: Context, workerClassName: String, params: WorkerParameters): ListenableWorker? {
                if (workerClassName != WidgetRefreshWorker::class.java.name) return null
                val owner = synchronized(lock) {
                    current ?: run {
                        poison = "Widget worker created without a live test owner: ${params.id}"
                        error(requireNotNull(poison))
                    }
                }
                // Use the production worker and its unmodified retry/output/cancellation logic.
                return WidgetRefreshWorker(context, params) {
                    owner.track(params.id, params.runAttemptCount) { WidgetRefresher(context.applicationContext).refresh() }
                }
            }
        }).build())
        synchronized(lock) { installed = true }
        Log.i(TAG, "mode=real factory=actual-WidgetRefreshWorker installed=true elapsed_ns=${SystemClock.elapsedRealtimeNanos()}")
    }

    suspend fun open(
        context: Context,
        afterRealRefresh: suspend () -> Unit = {},
        beforeBodyExit: suspend () -> Unit = {}
    ): Session {
        check(context.packageName == "com.dayforge.testbed")
        // Reserve before any suspend: opening a successor never steals an active owner.
        val owner = synchronized(lock) {
            check(installed) { "Real WorkerFactory not installed by instrumentation runner" }
            check(poison == null) { requireNotNull(poison) }
            check(current == null) { "A previous real widget owner has not drained" }
            Session(context.applicationContext, afterRealRefresh, beforeBodyExit).also { current = it }
        }
        try {
            owner.prepare()
            return owner
        } catch (error: Throwable) {
            synchronized(lock) { poison = "Real widget admission failed: ${error.javaClass.name}" }
            throw error
        }
    }

    internal data class AttemptSnapshot(
        val id: UUID, val attempt: Int, val bodyExited: Boolean, val jobCompleted: Boolean, val error: String?
    )

    internal class Session internal constructor(
        private val context: Context,
        private val afterRealRefresh: suspend () -> Unit,
        private val beforeBodyExit: suspend () -> Unit
    ) {
        private val monitor = Any()
        private val records = mutableListOf<AttemptSnapshot>()
        private var baseline = emptySet<UUID>()
        private var emptyTypes = emptyMap<String, Int>()
        private var finalInfos = emptyList<WorkInfo>()
        private var prepared = false
        @Volatile var quiescent = false
            private set
        private var outcome = "open"
        private val manager get() = WorkManager.getInstance(context)

        // Locked WorkManager 2.9 dispatches enqueue and this status Future on the same
        // FIFO SerialExecutor. Once UI producers join, this query follows their enqueues.
        private suspend fun infos(): List<WorkInfo> = withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(WidgetRefreshScheduler.WORK_NAME).get(5, TimeUnit.SECONDS)
        }

        internal suspend fun prepare() {
            val rows = infos()
            check(rows.all { it.state.isFinished }) { "Prior unique widget queue is not terminal" }
            baseline = rows.map { it.id }.toSet()
            val glance = GlanceAppWidgetManager(context)
            emptyTypes = listOf(CheckInWidget(), CountingWidget(), TimerWidget(), ProgressWidget(), MotivationWidget(), FocusWidget())
                .associate { it.javaClass.name to glance.getGlanceIds(it.javaClass).size }
            check(emptyTypes.size == 6 && emptyTypes.values.all { it == 0 }) { "Real-worker calibration requires all six widget types empty" }
            prepared = true
            Log.i(TAG, "admitted empty_glance_types=6 baseline=${baseline.size}; alarm/auth/Room costs remain real")
        }

        internal suspend fun track(id: UUID, attempt: Int, refresh: suspend () -> Int): Int {
            val job = requireNotNull(coroutineContext[Job])
            val index = synchronized(monitor) {
                check(prepared && !quiescent) { "Widget work entered outside the live owner" }
                check(id !in baseline) { "A prior owner's widget work cannot enter the next Host" }
                check(records.none { it.id == id && it.attempt == attempt }) { "Duplicate worker attempt" }
                records.add(AttemptSnapshot(id, attempt, false, false, null)); records.lastIndex
            }
            job.invokeOnCompletion { error -> synchronized(monitor) {
                records[index] = records[index].copy(jobCompleted = true, error = error?.javaClass?.name ?: records[index].error)
            } }
            try {
                val result = refresh()
                afterRealRefresh() // Empty in UI2; lifecycle negatives run only after the actual refresh.
                return result
            } catch (error: Throwable) {
                synchronized(monitor) { records[index] = records[index].copy(error = error.javaClass.name) }
                throw error
            } finally {
                try { beforeBodyExit() } finally {
                    synchronized(monitor) { records[index] = records[index].copy(bodyExited = true) }
                }
            }
        }

        fun attempts(): List<AttemptSnapshot> = synchronized(monitor) { records.toList() }

        private suspend fun mainEventBarrier() = withTimeout(5_000) {
            withContext(Dispatchers.Main) { Unit }
        }

        /** Call only after Activity and ViewModel producers plus structured journey jobs joined.
         * Operation completion is not read as worker completion. Never cancels WorkManager work. */
        suspend fun drainAfterProducersJoined() {
            check(prepared && !quiescent)
            try {
                // Test teardown only. Preserve the real alarm path during the measured journey.
                withTimeout(45_000) {
                    FocusWidgetAlarmScheduler.cancelScheduledRefresh(context)
                    mainEventBarrier()
                    while (true) {
                        val rows = infos()
                        val observed = attempts()
                        if (rows.all { it.state.isFinished } && observed.all { it.bodyExited && it.jobCompleted }) {
                            // A completed worker may have rescheduled its alarm. Remove it only
                            // after body exit, then flush main-thread receiver enqueues and requery.
                            FocusWidgetAlarmScheduler.cancelScheduledRefresh(context)
                            mainEventBarrier()
                            val confirm = infos()
                            val confirmedAttempts = attempts()
                            if (confirm.map { it.id to it.state }.toSet() == rows.map { it.id to it.state }.toSet() &&
                                confirmedAttempts == observed && confirmedAttempts.all { it.bodyExited && it.jobCompleted }) {
                                finalInfos = confirm.filter { it.id !in baseline }
                                val sealed = synchronized(monitor) {
                                    if (records.toList() != confirmedAttempts) false else {
                                        // Seal refresh admission atomically with the last ledger read.
                                        // A constructed/cancelled worker entering later is rejected
                                        // before touching the real Refresher or a successor Host.
                                        quiescent = true
                                        true
                                    }
                                }
                                if (sealed) break
                            }
                        }
                        delay(25)
                    }
                }
            } catch (error: Throwable) {
                outcome = "unproven"
                synchronized(lock) { poison = "Widget drain did not prove body/job exit: ${error.javaClass.name}" }
                throw error
            }
            // Safe terminal proof and healthy refresh are separate. A negative test may clean
            // its Host after actual cancellation exit, but cannot label CANCELLED successful.
            val observed = attempts()
            val healthy = finalInfos.isNotEmpty() && finalInfos.all {
                it.state == WorkInfo.State.SUCCEEDED && it.outputData.getInt(WidgetRefreshWorker.FAILED_STEPS, -1) == 0 &&
                    observed.any { attempt -> attempt.id == it.id }
            } && observed.all { it.id in finalInfos.map { info -> info.id } && it.error == null }
            outcome = if (healthy) "succeeded_zero_failed_steps" else "terminal_but_failed"
            synchronized(lock) { check(current === this); current = null }
            Log.i(TAG, "drained outcome=$outcome work=${finalInfos.size} attempts=${observed.size} elapsed_ns=${SystemClock.elapsedRealtimeNanos()}")
            check(healthy) { "Real widget queue was terminal but did not SUCCEED with failed_steps=0" }
        }

        fun assertSafeForHostCleanup() {
            check(quiescent) { "Cannot release tokens/Host before all real widget bodies and jobs exit" }
        }

        fun writeEvidence(directory: File?) {
            if (directory == null) return
            val file = File(directory, "widget-real-lifecycle.json")
            file.writeText(buildJsonObject {
                put("format", 1); put("mode", "real"); put("unique_work", WidgetRefreshScheduler.WORK_NAME)
                put("quiescent", quiescent); put("outcome", outcome); put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                put("empty_glance_types", buildJsonObject { emptyTypes.forEach { (name, count) -> put(name, count) } })
                put("work", JsonArray(finalInfos.map { info -> buildJsonObject {
                    put("id", info.id.toString()); put("state", info.state.name)
                    put("failed_steps", info.outputData.getInt(WidgetRefreshWorker.FAILED_STEPS, -1))
                } }))
                put("attempts", JsonArray(attempts().map { attempt -> buildJsonObject {
                    put("id", attempt.id.toString()); put("attempt", attempt.attempt)
                    put("body_exited", attempt.bodyExited); put("job_completed", attempt.jobCompleted)
                    put("error", attempt.error?.let(::JsonPrimitive) ?: JsonNull)
                } }))
            }.toString())
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Log.i(TAG, "evidence directory=${directory.name} sha256=$hash")
        }
    }
}
