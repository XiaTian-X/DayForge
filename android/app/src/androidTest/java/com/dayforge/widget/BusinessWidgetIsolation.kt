package com.dayforge.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkRequest
import androidx.work.impl.OperationImpl
import androidx.work.impl.WorkManagerImpl
import com.dayforge.data.local.PhysicalDatabaseRule
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Business tests suppress ONLY the original WidgetRefreshScheduler request. Every other
 * WorkManager operation goes to the captured real manager, including cached AutoSync calls
 * after this scope ends. A normal non-final class mock works by subclassing on API27.
 * No WorkManagerImpl constructor, test DB, executor, worker or scheduler is created. */
@SuppressLint("RestrictedApi")
internal class BusinessWidgetIsolation private constructor(private val context: Context) {
    private val monitor = Any()
    private lateinit var original: WorkManagerImpl
    internal lateinit var proxy: WorkManagerImpl
        private set
    private var open = true
    private var restored = false
    private var firstRejection: Throwable? = null
    private val isolated = mutableListOf<UUID>()
    internal fun isolatedRequests(): List<UUID> = synchronized(monitor) { isolated.toList() }
    internal val isRestored: Boolean get() = synchronized(monitor) { restored }

    private fun reject(message: String): Nothing = synchronized(monitor) {
        val failure = IllegalStateException(message)
        if (firstRejection == null) firstRejection = failure
        // A late captured Widget caller is an unjoined producer. Ordinary cached AutoSync
        // calls never pass through this branch and remain real after restoration.
        if (!open) PhysicalDatabaseRule.preserveProcessCleanupFailure(failure)
        throw failure
    }
    private fun widget(request: WorkRequest) = request.workSpec.workerClassName == WidgetRefreshWorker::class.java.name
    private fun nonWidget(requests: List<WorkRequest>) {
        if (requests.any(::widget)) reject("BUSINESS_WIDGET_UNEXPECTED_ENTRY")
    }
    private fun unique(name: String, policy: ExistingWorkPolicy, requests: List<OneTimeWorkRequest>,
        actual: () -> Operation): Operation {
        // A non-Widget using the same unique name is STILL real work; never fake its success.
        if (requests.none(::widget)) return actual()
        return synchronized(monitor) {
            if (!open) reject("BUSINESS_WIDGET_SCOPE_ENDED")
            if (name != WidgetRefreshScheduler.WORK_NAME || policy != ExistingWorkPolicy.APPEND_OR_REPLACE ||
                requests.size != 1 || !widget(requests.single())) reject("BUSINESS_WIDGET_REQUEST_SHAPE_CHANGED")
            isolated += requests.single().id
            OperationImpl().also { it.markState(Operation.SUCCESS) }
        }
    }

    private fun install() {
        check(context.packageName == "com.dayforge.testbed")
        original = WorkManagerImpl.getInstance(context)
        // mockk, not spyk: no copied WorkManager state and no callOriginal on an empty proxy.
        proxy = mockk(relaxed = false, relaxUnitFun = false)
        every { proxy.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
            val name = firstArg<String>(); val policy = secondArg<ExistingWorkPolicy>(); val request = thirdArg<OneTimeWorkRequest>()
            unique(name, policy, listOf(request)) { original.enqueueUniqueWork(name, policy, request) }
        }
        every { proxy.enqueueUniqueWork(any(), any(), any<List<OneTimeWorkRequest>>()) } answers {
            val name = firstArg<String>(); val policy = secondArg<ExistingWorkPolicy>(); val requests = thirdArg<List<OneTimeWorkRequest>>()
            unique(name, policy, requests) { original.enqueueUniqueWork(name, policy, requests) }
        }
        every { proxy.enqueue(any<List<WorkRequest>>()) } answers {
            val requests = firstArg<List<WorkRequest>>(); nonWidget(requests); original.enqueue(requests)
        }
        every { proxy.beginWith(any<List<OneTimeWorkRequest>>()) } answers {
            val requests = firstArg<List<OneTimeWorkRequest>>(); nonWidget(requests); original.beginWith(requests)
        }
        every { proxy.beginUniqueWork(any(), any(), any<List<OneTimeWorkRequest>>()) } answers {
            val requests = thirdArg<List<OneTimeWorkRequest>>(); nonWidget(requests)
            original.beginUniqueWork(firstArg(), secondArg(), requests)
        }
        // API27 cannot intercept final methods: the original three convenience bodies only
        // wrap a list and dispatch to the virtual overloads above. >=28 uses MockK inline.
        if (Build.VERSION.SDK_INT >= 28) {
            every { proxy.enqueue(any<WorkRequest>()) } answers {
                val request = firstArg<WorkRequest>(); nonWidget(listOf(request)); original.enqueue(request)
            }
            every { proxy.beginWith(any<OneTimeWorkRequest>()) } answers {
                val request = firstArg<OneTimeWorkRequest>(); nonWidget(listOf(request)); original.beginWith(request)
            }
            every { proxy.beginUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
                val request = thirdArg<OneTimeWorkRequest>(); nonWidget(listOf(request))
                original.beginUniqueWork(firstArg(), secondArg(), request)
            }
        }
        every { proxy.enqueueUniquePeriodicWork(any(), any(), any()) } answers {
            val request = thirdArg<androidx.work.PeriodicWorkRequest>(); nonWidget(listOf(request))
            original.enqueueUniquePeriodicWork(firstArg(), secondArg(), request)
        }
        every { proxy.createWorkContinuationForUniquePeriodicWork(any(), any(), any()) } answers {
            val request = thirdArg<androidx.work.PeriodicWorkRequest>(); nonWidget(listOf(request))
            original.createWorkContinuationForUniquePeriodicWork(firstArg(), secondArg(), request)
        }
        every { proxy.updateWork(any()) } answers {
            val request = firstArg<WorkRequest>(); nonWidget(listOf(request)); original.updateWork(request)
        }
        every { proxy.applicationContext } answers { original.applicationContext }
        every { proxy.configuration } answers { original.configuration }
        every { proxy.workDatabase } answers { original.workDatabase }
        every { proxy.schedulers } answers { original.schedulers }
        every { proxy.processor } answers { original.processor }
        every { proxy.workTaskExecutor } answers { original.workTaskExecutor }
        every { proxy.preferenceUtils } answers { original.preferenceUtils }
        every { proxy.trackers } answers { original.trackers }
        every { proxy.remoteWorkManager } answers { original.remoteWorkManager }
        every { proxy.cancelWorkById(any()) } answers { original.cancelWorkById(firstArg()) }
        every { proxy.cancelAllWorkByTag(any()) } answers { original.cancelAllWorkByTag(firstArg()) }
        every { proxy.cancelUniqueWork(any()) } answers { original.cancelUniqueWork(firstArg()) }
        every { proxy.cancelAllWork() } answers { original.cancelAllWork() }
        every { proxy.createCancelPendingIntent(any()) } answers { original.createCancelPendingIntent(firstArg()) }
        every { proxy.pruneWork() } answers { original.pruneWork() }
        every { proxy.lastCancelAllTimeMillisLiveData } answers { original.lastCancelAllTimeMillisLiveData }
        every { proxy.lastCancelAllTimeMillis } answers { original.lastCancelAllTimeMillis }
        every { proxy.getWorkInfoByIdLiveData(any()) } answers { original.getWorkInfoByIdLiveData(firstArg()) }
        every { proxy.getWorkInfoByIdFlow(any()) } answers { original.getWorkInfoByIdFlow(firstArg()) }
        every { proxy.getWorkInfoById(any()) } answers { original.getWorkInfoById(firstArg()) }
        every { proxy.getWorkInfosByTagLiveData(any()) } answers { original.getWorkInfosByTagLiveData(firstArg()) }
        every { proxy.getWorkInfosByTagFlow(any()) } answers { original.getWorkInfosByTagFlow(firstArg()) }
        every { proxy.getWorkInfosByTag(any()) } answers { original.getWorkInfosByTag(firstArg()) }
        every { proxy.getWorkInfosForUniqueWorkLiveData(any()) } answers { original.getWorkInfosForUniqueWorkLiveData(firstArg()) }
        every { proxy.getWorkInfosForUniqueWorkFlow(any()) } answers { original.getWorkInfosForUniqueWorkFlow(firstArg()) }
        every { proxy.getWorkInfosForUniqueWork(any()) } answers { original.getWorkInfosForUniqueWork(firstArg()) }
        every { proxy.getWorkInfosLiveData(any()) } answers { original.getWorkInfosLiveData(firstArg()) }
        every { proxy.getWorkInfosFlow(any()) } answers { original.getWorkInfosFlow(firstArg()) }
        every { proxy.getWorkInfos(any()) } answers { original.getWorkInfos(firstArg()) }
        every { proxy.stopForegroundWork(any()) } answers { original.stopForegroundWork(firstArg()) }
        every { proxy.rescheduleEligibleWork() } answers { original.rescheduleEligibleWork() }
        every { proxy.onForceStopRunnableCompleted() } answers { original.onForceStopRunnableCompleted() }
        every { proxy.setReschedulePendingResult(any()) } answers { original.setReschedulePendingResult(firstArg()) }
        check(WorkManagerImpl.getInstance(context) === original)
        WorkManagerImpl.setDelegate(proxy)
        requireInstalled()
    }

    internal fun requireInstalled() {
        try {
            synchronized(monitor) { check(open && !restored && WorkManagerImpl.getInstance(context) === proxy) {
                "BUSINESS_WIDGET_FOREIGN_OR_ENDED_DELEGATE"
            } }
        } catch (error: Throwable) { PhysicalDatabaseRule.preserveProcessCleanupFailure(error); throw error }
    }
    /** Call only after original producer/Host/fixture cleanup is known complete. There is no
     * test-owned queue to drain. A known rejected Widget request still fails the test after
     * safe restoration, so WidgetRefreshScheduler's optional-error catch cannot hide it. */
    internal fun finishAfterResourcesClosed() {
        val rejected = synchronized(monitor) {
            requireInstalled()
            open = false
            WorkManagerImpl.setDelegate(original)
            check(WorkManagerImpl.getInstance(context) === original)
            restored = true
            synchronized(global) { check(current === this); current = null }
            firstRejection
        }
        // Do not clearMocks: AutoSync/Material can retain the proxy lazily. Its ordinary
        // answers hold only the real manager and always continue forwarding to it.
        rejected?.let { throw it }
    }
    companion object {
        private val global = Any()
        private var current: BusinessWidgetIsolation? = null
        internal fun open(context: Context): BusinessWidgetIsolation {
            val owner = synchronized(global) {
                PhysicalDatabaseRule.requireProcessCleanupClear()
                check(current == null) { "BUSINESS_WIDGET_PREVIOUS_SCOPE_NOT_RELEASED" }
                BusinessWidgetIsolation(context.applicationContext).also { current = it }
            }
            try { owner.install() } catch (error: Throwable) {
                PhysicalDatabaseRule.preserveProcessCleanupFailure(error); throw error
            }
            return owner
        }
    }
}

class BusinessWidgetIsolationRule(private val resourcesClosed: () -> Boolean) : TestRule {
    internal lateinit var owner: BusinessWidgetIsolation
        private set
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            owner = BusinessWidgetIsolation.open(InstrumentationRegistry.getInstrumentation().targetContext)
            var primary: Throwable? = null
            try { base.evaluate() } catch (error: Throwable) { primary = error; throw error }
            finally {
                try {
                    check(resourcesClosed()) { "BUSINESS_WIDGET_ORIGINAL_RESOURCES_NOT_CLOSED" }
                    owner.finishAfterResourcesClosed()
                } catch (cleanup: Throwable) {
                    if (!owner.isRestored) PhysicalDatabaseRule.preserveProcessCleanupFailure(cleanup)
                    val error = primary
                    if (error == null) throw cleanup
                    if (error !== cleanup && error.suppressed.none { it === cleanup }) error.addSuppressed(cleanup)
                }
            }
        }
    }
}
