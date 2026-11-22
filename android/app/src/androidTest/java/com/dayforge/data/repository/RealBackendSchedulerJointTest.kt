package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NetworkMonitor
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.appearance.AccountIconContext
import com.dayforge.data.appearance.AccountIconDatabase
import com.dayforge.data.appearance.AccountIconPackPreview
import com.dayforge.data.appearance.IconTransferState
import com.dayforge.data.appearance.ValidatedIconPack
import com.dayforge.data.local.*
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.sync.AccountIconWorkCoordinator
import com.dayforge.sync.AccountIconWorker
import com.dayforge.sync.AutoSyncCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Actual WorkManager/default WorkerFactory, Hilt, Room and owned FastAPI. No direct doWork or
 * scheduler mock. The account-lock cancellation case is NOT interruption of an in-flight upload,
 * OS process-death/reboot, manufacturer background restrictions or manual acceptance.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealBackendSchedulerJointTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.beforeCleanup = { ownerFailure?.let { throw it }; check(ownersClosed) { "SCHEDULER_JOINT_OWNERS_NOT_JOINED" } }
        fixture.beforeDatabaseDeletion = {
            check(ownersClosed && fixture.host.lifecycleEnded)
            val owned = File(app.filesDir, "account-icons-v1")
            assertTrue(!owned.exists() || owned.deleteRecursively())
        }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val testName = TestName()
    @Inject lateinit var icons: AccountIconController
    @Inject lateinit var themes: com.dayforge.domain.service.DeviceThemeController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var auth: AuthApi
    @Inject lateinit var business: BusinessSyncRepository
    @Inject lateinit var network: NetworkMonitor
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = epochs.host.boundGraph()
    private val db get() = graph.database
    private val metrics get() = graph.metricRepository
    private val creator get() = graph.nextObjectCreator
    private var connected = false
    private val methodOwnerMonitor = Any()
    private var methodClosing = false
    private var methodJob: Job? = null
    private var ownersClosed = false
    private var ownerFailure: Throwable? = null
    private val businessOwners = mutableListOf<Pair<AutoSyncCoordinator, Job>>()
    private fun <T> journey(block: suspend CoroutineScope.() -> T): T = runBlocking {
        val running = requireNotNull(currentCoroutineContext()[Job])
        synchronized(methodOwnerMonitor) {
            // JUnit timeout cleanup may run before this worker reaches registration.
            check(!methodClosing && methodJob == null) { "JOINT_METHOD_REGISTRATION_CLOSED" }
            methodJob = running
        }
        block()
    }
    private suspend fun <T> current(block: suspend () -> T): T = epochs.host.withGraph { block() }
    private lateinit var manager: WorkManager
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Default)
    private val baseline = mutableSetOf<UUID>()
    private val workNames = listOf(BUSINESS, BUSINESS_PERIODIC,
        AccountIconWorkCoordinator.ONE_TIME, AccountIconWorkCoordinator.PERIODIC)
    private val wire = Json { encodeDefaults = true }
    private fun id(n: Int) = "ac390000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val image = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        hilt.inject(); epochs.connect(); connected = true
        manager = WorkManager.getInstance(app)
        for (name in workNames) {
            assertTrue("A prior case left live work: $name", work(name).all { it.state.isFinished })
            baseline.addAll(work(name).map { it.id })
        }
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        sessions.exclusive { tokens.clearTokens() }
        epochs.clearAndAwaitReplacement()
        preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        preferences.setServerUrl("http://127.0.0.1:$port/")
        val name = when {
            testName.methodName == "realOrdinaryMaterialWakeKeepsRunningOwnerAndOneSuccessor" -> "joint-scheduler-coalesced"
            testName.methodName.contains("Replacement") -> "joint-scheduler-replace"
            else -> "joint-scheduler"
        }
        val reply = auth.login(LoginRequest(name, "TestPassword123!")) // Synthetic seed-only fixture.
        sessions.exclusive { tokens.saveLoginSession(reply.accessToken, reply.refreshToken, name, reply.userId, false) }
        // Only initial registration/bootstrap is foreground. All work under test uses real schedules.
        business.sync()
        assertNotNull(tokens.localIconAccess())
    }

    @After fun cleanup() = runBlocking<Unit> {
        val ownedMethod = synchronized(methodOwnerMonitor) {
            methodClosing = true
            methodJob
        }
        try {
            // Every scheduler below was constructed and registered by THIS case, before start().
            ownerFailure?.let { throw it }
            withTimeout(5_000) { ownedMethod?.cancelAndJoin() }
            check(ownedMethod?.isCompleted != false)
            ownerFailure?.let { throw it } // A constructor/registration failure may arrive before join completes.
            withTimeout(5_000) {
                businessOwners.forEach { it.second.cancel() }
                businessOwners.forEach { it.second.join() }
                owner.cancelAndJoin()
            }
            check(businessOwners.all { it.second.isCompleted } && owner.isCompleted)
            if (::manager.isInitialized) {
                for (name in workNames) withContext(Dispatchers.IO) {
                    manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
                }
                await("owned schedule termination", 15_000) {
                    workNames.all { name -> work(name).all { it.state.isFinished } }
                }
            }
            // FINISHED work alone is not a native drain witness; preserve unknown/busy failures.
            if (connected) { shutdownTestHost(epochs.host); check(epochs.host.lifecycleEnded) }
            if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
            if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
            if (::themes.isInitialized) withTimeout(5_000) { themes.close() }
            if (::preferences.isInitialized) {
                preferences.setServerUrl(null); preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
            }
            check(app.packageName == "com.dayforge.testbed"); ownersClosed = true
        } catch (failure: Throwable) { ownerFailure = failure; throw failure }
    }

    private suspend fun work(name: String): List<WorkInfo> = withContext(Dispatchers.IO) {
        manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
    }
    private suspend fun newWork(name: String) = work(name).filter { it.id !in baseline }
    private suspend fun byId(id: UUID): WorkInfo? = withContext(Dispatchers.IO) {
        manager.getWorkInfoById(id).get(5, TimeUnit.SECONDS)
    }
    private suspend fun await(name: String, timeout: Long = 60_000, condition: suspend () -> Boolean) {
        try { withTimeout(timeout) { while (!condition()) delay(100) } }
        catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Scheduler joint step timed out: $name", failure)
        }
    }
    /** Production Host.observe constructor, never the fixed-DAO compatibility overload.
     * Only this explicitly test-owned instance is inspected. Reflection reads its real Job;
     * it never replaces the scope, touches a Hilt singleton, or issues database authority. */
    private fun businessCoordinator(): AutoSyncCoordinator {
        ownerFailure?.let { throw it }
        check(!ownersClosed)
        return try {
            val created = AutoSyncCoordinator(app, epochs.host, network, icons)
            val field = AutoSyncCoordinator::class.java.getDeclaredField("scope").apply { isAccessible = true }
            val actual = field.get(created) as CoroutineScope
            val job = requireNotNull(actual.coroutineContext[Job])
            check(job.isActive && job !== owner && businessOwners.none { it.first === created || it.second === job })
            businessOwners += created to job // Registration precedes any start/observation.
            created
        } catch (failure: Throwable) {
            // A reflection/ownership mismatch never becomes clean teardown or a fallback constructor.
            ownerFailure = failure
            throw failure
        }
    }
    private fun materialCoordinator() = AccountIconWorkCoordinator(app, tokens.iconAccessChanges,
        network.state, icons.workRequests, scope)

    private suspend fun install(): AccountIconContext {
        val context = icons.capture()
        val hash = MessageDigest.getInstance("SHA-256").digest(image).joinToString("") { "%02x".format(it) }
        val asset = IconAsset(id(10), "Scheduler SVG", "general", "template",
            IconBlob(hash, image.size, "image/svg+xml", 1, 1), null)
        val pack = IconPack("dayforge.icon-pack", 1, id(100), 1, "Scheduler style",
            listOf(asset), mapOf("metric.weight" to asset.assetId), null)
        val bytes = ByteArrayOutputStream().also { output -> java.util.zip.ZipOutputStream(output).use { zip ->
            for ((name, value) in listOf("manifest.json" to wire.encodeToString(pack).toByteArray(), "blobs/$hash" to image)) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value); zip.closeEntry()
            }
        } }.toByteArray()
        icons.install(AccountIconPackPreview(context, ValidatedIconPack.read { ByteArrayInputStream(bytes) }))
        assertTrue(icons.transferJobs(context).all { it.state == IconTransferState.PENDING })
        return context
    }

    @Test fun realScheduledMaterialConfirmationWakesWaitingBusinessWithoutManualSync() = journey<Unit> {
        val context = install()
        val originalJobs = icons.transferJobs(context)
        val metric = current { metrics.createMetric(MetricEntity(name = "scheduled fixed metric", unit = "kg", decimalPlaces = 0,
            iconResId = 0, colorHex = "#123456", appearance = ObjectAppearance(IconReference.Asset(id(10)), "#123456", "theme")),
            creationAuthority = creator.capture()) }
        val source = current { db.syncOutboxDao().getAll().single() }
        val origin = current { requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId)) }
        val sequence = business.completions.value.sequence
        businessCoordinator().start()
        await("business entered real backoff") {
            newWork(BUSINESS).any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        }
        assertEquals(listOf(source), current { db.syncOutboxDao().getAll() })
        assertNull(current { db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId) })
        assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
        assertEquals(sequence, business.completions.value.sequence)
        materialCoordinator().start()
        await("material and business background completion", 90_000) {
            newWork(BUSINESS).any { it.state == WorkInfo.State.SUCCEEDED } &&
                newWork(AccountIconWorkCoordinator.ONE_TIME).any {
                    it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(AccountIconWorker.STATUS) == "COMPLETE"
                } && current { db.syncOutboxDao().getAll().isEmpty() } &&
                icons.transferJobs(context).all { it.state == IconTransferState.COMPLETE }
        }
        assertEquals(origin, current { db.nextRequestDao().origin(NEXT_OPERATION, source.operationId) })
        assertNotNull(current { db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId) })
        assertTrue(business.completions.value.sequence > sequence)
        val confirmed = icons.transferJobs(context).associateBy { it.operationId }
        for (job in originalJobs) assertEquals(IconTransferState.COMPLETE, confirmed.getValue(job.operationId).state)
        assertNull(icons.library(context).selection.pack)
        assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertEquals(1, snapshot.changes.count { it.entityType == "metric" })
        assertEquals(current { db.metricDao().getMetricById(metric)!!.uuid },
            snapshot.changes.single { it.entityType == "metric" }.entityUuid)
    }

    @Test fun realReplacementCancelsAccountLockWaitAndKeepsOriginalMaterialIntentIds() = journey<Unit> {
        val context = install()
        val original = icons.transferJobs(context)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val locked = launch(Dispatchers.IO) { sessions.exclusive { acquired.complete(Unit); release.await() } }
        try {
            acquired.await()
            // Isolate network notifications only in this cancellation case. Real credential and
            // material wake flows, production debounce, policy, request and workers remain unchanged.
            val scheduler = AccountIconWorkCoordinator(app, tokens.iconAccessChanges, emptyFlow(), icons.workRequests, scope)
            scheduler.start()
            await("original worker running behind account lock", 15_000) {
                newWork(AccountIconWorkCoordinator.ONE_TIME).any { it.state == WorkInfo.State.RUNNING }
            }
            val first = newWork(AccountIconWorkCoordinator.ONE_TIME).single { it.state == WorkInfo.State.RUNNING }
            // Explicit real WorkManager replacement tests worker cancellation. Ordinary
            // material wakeups now coalesce and deliberately preserve a running owner.
            val replacement = OneTimeWorkRequestBuilder<AccountIconWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            withContext(Dispatchers.IO) {
                manager.enqueueUniqueWork(AccountIconWorkCoordinator.ONE_TIME,
                    ExistingWorkPolicy.REPLACE, replacement).result.get(5, TimeUnit.SECONDS)
            }
            await("replacement cancellation and successor", 15_000) {
                // WorkManager 2.9 REPLACE deletes the old WorkSpec. No retained row is required;
                // require the exact old schedule to disappear/be cancelled AND a different live
                // successor. Neither absence nor CANCELLED is a business/material acknowledgement.
                byId(first.id).let { it == null || it.state == WorkInfo.State.CANCELLED } &&
                    newWork(AccountIconWorkCoordinator.ONE_TIME).any { it.id != first.id && it.state == WorkInfo.State.RUNNING } &&
                    byId(replacement.id)?.state == WorkInfo.State.RUNNING
            }
        } finally { release.complete(Unit); locked.join() }
        await("successor confirms original durable material", 90_000) {
            icons.transferJobs(context).all { it.state == IconTransferState.COMPLETE } &&
                newWork(AccountIconWorkCoordinator.ONE_TIME).any {
                    it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(AccountIconWorker.STATUS) == "COMPLETE"
                }
        }
        val finalJobs = icons.transferJobs(context).associateBy { it.operationId }
        for (job in original) {
            val confirmed = finalJobs.getValue(job.operationId)
            assertEquals(IconTransferState.COMPLETE, confirmed.state)
            assertEquals(job.kind, confirmed.kind); assertEquals(job.targetId, confirmed.targetId)
            assertEquals(2L, confirmed.generation) // One claim + confirmation; cancelled waiter never claimed.
        }
        assertTrue(current { db.syncOutboxDao().getAll().isEmpty() })
        assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
        assertNull(icons.library(context).selection.pack)
        assertTrue(newWork(AccountIconWorkCoordinator.PERIODIC).any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun realOrdinaryMaterialWakeKeepsRunningOwnerAndOneSuccessor() = journey<Unit> {
        val context = install()
        val original = icons.transferJobs(context)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val locked = launch(Dispatchers.IO) { sessions.exclusive { acquired.complete(Unit); release.await() } }
        val firstId: UUID
        var successorId: UUID? = null
        try {
            acquired.await()
            val requestsReady = CompletableDeferred<Unit>()
            val scheduler = AccountIconWorkCoordinator(app, tokens.iconAccessChanges, emptyFlow(),
                icons.workRequests.onSubscription { requestsReady.complete(Unit) }, scope)
            scheduler.start()
            await("original coalesced worker and real request subscription ready", 15_000) {
                requestsReady.isCompleted &&
                    newWork(AccountIconWorkCoordinator.ONE_TIME).any { it.state == WorkInfo.State.RUNNING }
            }
            val first = newWork(AccountIconWorkCoordinator.ONE_TIME).single { it.state == WorkInfo.State.RUNNING }
            firstId = first.id
            repeat(20) { icons.requestMaterialSync() }
            await("ordinary wake retains owner and one blocked successor", 15_000) {
                val active = newWork(AccountIconWorkCoordinator.ONE_TIME).filterNot { it.state.isFinished }
                val blocked = active.filter { it.id != first.id && it.state == WorkInfo.State.BLOCKED }
                if (byId(first.id)?.state == WorkInfo.State.RUNNING && active.size == 2 && blocked.size == 1) {
                    successorId = blocked.single().id
                    true
                } else false
            }
            assertEquals(WorkInfo.State.RUNNING, byId(first.id)?.state)
            assertEquals(WorkInfo.State.BLOCKED, byId(requireNotNull(successorId))?.state)
            // This is the observed real burst. Exact repeated enqueue decisions are also
            // covered by the complete CoalescingSyncWorkTest, not inferred from this sample.
        } finally { release.complete(Unit); locked.join() }
        val first = firstId
        val successor = requireNotNull(successorId)
        assertNotEquals(first, successor)
        await("both exact schedules complete original durable material", 90_000) {
            listOf(first, successor).all { id ->
                byId(id)?.let { it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getString(AccountIconWorker.STATUS) == "COMPLETE" } == true
            } && icons.transferJobs(context).all { it.state == IconTransferState.COMPLETE }
        }
        val finalJobs = icons.transferJobs(context).associateBy { it.operationId }
        for (job in original) {
            val confirmed = finalJobs.getValue(job.operationId)
            assertEquals(IconTransferState.COMPLETE, confirmed.state)
            assertEquals(job.kind, confirmed.kind); assertEquals(job.targetId, confirmed.targetId)
            assertEquals(2L, confirmed.generation)
        }
        assertTrue(current { db.syncOutboxDao().getAll().isEmpty() })
        assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
        assertNull(icons.library(context).selection.pack)
        assertTrue(newWork(AccountIconWorkCoordinator.PERIODIC).any { it.state == WorkInfo.State.ENQUEUED })
    }

    private companion object {
        const val BUSINESS = "dayforge-auto-sync"
        const val BUSINESS_PERIODIC = "dayforge-periodic-sync"
    }
}
