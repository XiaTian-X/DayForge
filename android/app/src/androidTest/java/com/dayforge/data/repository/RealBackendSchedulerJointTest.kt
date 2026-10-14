package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.dayforge.data.local.PhysicalDatabaseRule
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
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
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val testName = TestName()
    @Inject lateinit var icons: AccountIconController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var auth: AuthApi
    @Inject lateinit var business: BusinessSyncRepository
    @Inject lateinit var metrics: MetricRepository
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var network: NetworkMonitor
    @Inject internal lateinit var http: NextSyncHttp
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
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
        hilt.inject()
        manager = WorkManager.getInstance(app)
        for (name in workNames) {
            assertTrue("A prior case left live work: $name", work(name).all { it.state.isFinished })
            baseline.addAll(work(name).map { it.id })
        }
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        sessions.exclusive { tokens.clearTokens() }
        habits.clearAllData(app)
        preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        preferences.setServerUrl("http://127.0.0.1:$port/")
        val name = if (testName.methodName.contains("Replacement")) "joint-scheduler-replace" else "joint-scheduler"
        val reply = auth.login(LoginRequest(name, "TestPassword123!")) // Synthetic seed-only fixture.
        sessions.exclusive { tokens.saveLoginSession(reply.accessToken, reply.refreshToken, name, reply.userId, false) }
        // Only initial registration/bootstrap is foreground. All work under test uses real schedules.
        business.sync()
        assertNotNull(tokens.localIconAccess())
    }

    @After fun cleanup() = runBlocking<Unit> {
        // Stop/join owned observation first so no late flow can enqueue after cancellation.
        owner.cancelAndJoin()
        if (::manager.isInitialized) {
            for (name in workNames) withContext(Dispatchers.IO) {
                manager.cancelUniqueWork(name).result.get(5, TimeUnit.SECONDS)
            }
            await("owned schedule termination", 15_000) {
                workNames.all { name -> work(name).all { it.state.isFinished } }
            }
        }
        // Successful cases join real network work before reaching here. In failed cases revoke
        // authority before closing stores; cancelled/late callbacks cannot acquire write authority.
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
        if (::preferences.isInitialized) {
            preferences.setServerUrl(null); preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
        }
        check(app.packageName == "com.dayforge.testbed")
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
        val owned = File(app.filesDir, "account-icons-v1")
        assertTrue(!owned.exists() || owned.deleteRecursively())
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
    private fun businessCoordinator() = AutoSyncCoordinator(app, db.syncOutboxDao(), db.timeLogDao(),
        network, scope, icons.businessRequests)
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

    @Test fun realScheduledMaterialConfirmationWakesWaitingBusinessWithoutManualSync() = runBlocking<Unit> {
        val context = install()
        val originalJobs = icons.transferJobs(context)
        val metric = metrics.createMetric(MetricEntity(name = "scheduled fixed metric", unit = "kg", decimalPlaces = 0,
            iconResId = 0, colorHex = "#123456", appearance = ObjectAppearance(IconReference.Asset(id(10)), "#123456", "theme")),
            creationAuthority = creator.capture())
        val source = db.syncOutboxDao().getAll().single()
        val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        val sequence = business.completions.value.sequence
        businessCoordinator().start()
        await("business entered real backoff") {
            newWork(BUSINESS).any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
        }
        assertEquals(listOf(source), db.syncOutboxDao().getAll())
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertTrue(db.nextSyncStateDao().rejections().isEmpty())
        assertEquals(sequence, business.completions.value.sequence)
        materialCoordinator().start()
        await("material and business background completion", 90_000) {
            newWork(BUSINESS).any { it.state == WorkInfo.State.SUCCEEDED } &&
                newWork(AccountIconWorkCoordinator.ONE_TIME).any {
                    it.state == WorkInfo.State.SUCCEEDED && it.outputData.getString(AccountIconWorker.STATUS) == "COMPLETE"
                } && db.syncOutboxDao().getAll().isEmpty() &&
                icons.transferJobs(context).all { it.state == IconTransferState.COMPLETE }
        }
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, source.operationId))
        assertTrue(business.completions.value.sequence > sequence)
        val confirmed = icons.transferJobs(context).associateBy { it.operationId }
        for (job in originalJobs) assertEquals(IconTransferState.COMPLETE, confirmed.getValue(job.operationId).state)
        assertNull(icons.library(context).selection.pack)
        assertTrue(db.nextSyncStateDao().rejections().isEmpty())
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertEquals(1, snapshot.changes.count { it.entityType == "metric" })
        assertEquals(db.metricDao().getMetricById(metric)!!.uuid,
            snapshot.changes.single { it.entityType == "metric" }.entityUuid)
    }

    @Test fun realReplacementCancelsAccountLockWaitAndKeepsOriginalMaterialIntentIds() = runBlocking<Unit> {
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
            icons.requestMaterialSync()
            await("replacement cancellation and successor", 15_000) {
                // WorkManager 2.9 REPLACE deletes the old WorkSpec. No retained row is required;
                // require the exact old schedule to disappear/be cancelled AND a different live
                // successor. Neither absence nor CANCELLED is a business/material acknowledgement.
                byId(first.id).let { it == null || it.state == WorkInfo.State.CANCELLED } &&
                    newWork(AccountIconWorkCoordinator.ONE_TIME).any { it.id != first.id && it.state == WorkInfo.State.RUNNING }
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
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertTrue(db.nextSyncStateDao().rejections().isEmpty())
        assertNull(icons.library(context).selection.pack)
        assertTrue(newWork(AccountIconWorkCoordinator.PERIODIC).any { it.state == WorkInfo.State.ENQUEUED })
    }

    private companion object {
        const val BUSINESS = "dayforge-auto-sync"
        const val BUSINESS_PERIODIC = "dayforge-periodic-sync"
    }
}
