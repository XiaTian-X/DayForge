package com.dayforge.widget

import android.annotation.SuppressLint
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.Logger
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Real original WorkManager for non-Widget work; no private WorkDatabase/TestScheduler.
 * The previous four TestScheduler tests belong to the retained failed694 candidate, not to
 * this replacement. These four identities verify its actual narrower isolation contract. */
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class BusinessWidgetIsolationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> withOwner(block: (BusinessWidgetIsolation) -> T): T {
        val owner = BusinessWidgetIsolation.open(context)
        var primary: Throwable? = null
        try { return block(owner) } catch (error: Throwable) { primary = error; throw error }
        finally { if (!owner.isRestored) try { owner.finishAfterResourcesClosed() } catch (cleanup: Throwable) {
            val error = primary
            if (error == null) throw cleanup
            if (error !== cleanup && error.suppressed.none { it === cleanup }) error.addSuppressed(cleanup)
        } }
    }
    private fun requests(manager: WorkManager) = manager.getWorkInfosForUniqueWork(WidgetRefreshScheduler.WORK_NAME)
        .get(5, TimeUnit.SECONDS).sortedBy { it.id.toString() }
    private fun witness(): OneTimeWorkRequest {
        // Use the original factory's actual loader; never install a replacement loader/factory.
        assertSame(WitnessWorker::class.java, Class.forName(WitnessWorker::class.java.name, true,
            androidx.work.WorkerFactory::class.java.classLoader))
        return OneTimeWorkRequestBuilder<WitnessWorker>()
            .setInputData(Data.Builder().putString("witness", UUID.randomUUID().toString()).build()).build()
    }
    private fun assertRealSuccess(manager: WorkManager, request: OneTimeWorkRequest) {
        val end = SystemClock.elapsedRealtime() + 5_000
        var info: WorkInfo?
        do {
            info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
            if (info?.state == WorkInfo.State.SUCCEEDED) break
            check(info?.state?.isFinished != true && SystemClock.elapsedRealtime() < end)
            Thread.sleep(5)
        } while (true)
        assertEquals(request.workSpec.input.getString("witness"), requireNotNull(info).outputData.getString("witness"))
    }
    /** Created by the ORIGINAL WorkManager's real default factory/executor/DB, not MockK. */
    class WitnessWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success(Data.Builder()
            .putString("witness", requireNotNull(inputData.getString("witness"))).build())
    }

    @Test(timeout = 150_000) fun exactWidgetRequestsDoNotConstructRuntimeOrChangeOriginalSchedulingState() {
        val original = WorkManagerImpl.getInstance(context)
        val baseline = requests(original)
        val db = original.workDatabase; val processor = original.processor; val config = original.configuration
        val logger = Logger.get()
        val receiver = ComponentName(context, "androidx.work.impl.background.systemalarm.RescheduleReceiver")
        val receiverState = context.packageManager.getComponentEnabledSetting(receiver)
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id to it.service }.sortedBy { it.first }
        withOwner { owner ->
            assertNotSame(original, owner.proxy)
            assertSame(owner.proxy, WorkManager.getInstance(context))
            assertSame(db, owner.proxy.workDatabase); assertSame(processor, owner.proxy.processor)
            assertSame(config, owner.proxy.configuration)
            repeat(3) { requireNotNull(WidgetRefreshScheduler.request(context)).result.get(5, TimeUnit.SECONDS) }
            assertEquals(3, owner.isolatedRequests().distinct().size)
            owner.isolatedRequests().forEach { assertNull(original.getWorkInfoById(it).get(5, TimeUnit.SECONDS)) }
            assertEquals(baseline, requests(original))
            assertSame(logger, Logger.get())
            assertEquals(receiverState, context.packageManager.getComponentEnabledSetting(receiver))
            assertEquals(jobs, context.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id to it.service }.sortedBy { it.first })
        }
        assertSame(original, WorkManager.getInstance(context)); assertSame(logger, Logger.get())
        assertSame(db, original.workDatabase); assertSame(processor, original.processor); assertSame(config, original.configuration)
        assertEquals(baseline, requests(original))
    }

    @Test(timeout = 150_000) fun nonWidgetSameNameAndFinalConvenienceMethodsExecuteOnTheOriginalManager() {
        val original = WorkManagerImpl.getInstance(context)
        withOwner { owner ->
            val sameName = witness()
            owner.proxy.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                sameName).result.get(5, TimeUnit.SECONDS)
            assertRealSuccess(original, sameName)
            val direct = witness(); owner.proxy.enqueue(direct).result.get(5, TimeUnit.SECONDS); assertRealSuccess(original, direct)
            val first = witness(); owner.proxy.beginWith(first).enqueue().result.get(5, TimeUnit.SECONDS); assertRealSuccess(original, first)
            val unique = witness()
            owner.proxy.beginUniqueWork("proxy-real-${unique.id}", ExistingWorkPolicy.REPLACE, unique)
                .enqueue().result.get(5, TimeUnit.SECONDS)
            assertRealSuccess(original, unique)
            assertTrue(owner.isolatedRequests().isEmpty())
            assertEquals(requests(original), requests(owner.proxy))
        }
        assertSame(original, WorkManager.getInstance(context))
    }

    @Test(timeout = 150_000) fun wrongWidgetShapeRemainsAFailureAfterKnownSafeRestoration() {
        val original = WorkManagerImpl.getInstance(context); val baseline = requests(original)
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        var first: Throwable? = null
        val delivered = try {
            withOwner { owner ->
                first = try {
                    owner.proxy.enqueueUniqueWork(WidgetRefreshScheduler.WORK_NAME, ExistingWorkPolicy.KEEP, request)
                    null
                } catch (error: IllegalStateException) { error }
                assertEquals("BUSINESS_WIDGET_REQUEST_SHAPE_CHANGED", requireNotNull(first).message)
                assertNull(original.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS))
                assertTrue(owner.isolatedRequests().isEmpty())
            }
            null
        } catch (error: Throwable) { error }
        assertSame(first, delivered); assertNotNull(delivered)
        assertSame(original, WorkManager.getInstance(context)); assertEquals(baseline, requests(original))
    }

    @Test(timeout = 150_000) fun originalFailureIsPreservedAndCachedNonWidgetCallsRemainRealAfterScopeEnds() {
        val original = WorkManagerImpl.getInstance(context)
        val expected = IllegalStateException("original-business-rejection")
        var resourcesClosed = false
        val rule = BusinessWidgetIsolationRule { resourcesClosed }
        val actual = try {
            rule.apply(object : Statement() {
                override fun evaluate() {
                    try { requireNotNull(WidgetRefreshScheduler.request(context)).result.get(5, TimeUnit.SECONDS); throw expected }
                    finally { resourcesClosed = true }
                }
            }, Description.createTestDescription(javaClass, "knownOriginalFailure")).evaluate()
            null
        } catch (error: Throwable) { error }
        assertSame(expected, actual); assertTrue(expected.suppressed.isEmpty())
        assertTrue(rule.owner.isRestored); assertSame(original, WorkManager.getInstance(context))
        // AutoSync/Material's lazy field can retain exactly this object. Restoring the global
        // delegate must not revoke or clear its real query/enqueue forwarding answers.
        val cached = rule.owner.proxy; val request = witness(); val name = "proxy-cached-${request.id}"
        cached.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).also { assertTrue(it.isEmpty()) }
        cached.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request).result.get(5, TimeUnit.SECONDS)
        assertRealSuccess(original, request)
        assertEquals(original.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS),
            cached.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS))
        assertSame(original, WorkManager.getInstance(context))
    }
}
