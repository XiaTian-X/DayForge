package com.dayforge.data.repository

import android.util.Base64
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.LoginRequest
import com.dayforge.data.appearance.*
import com.dayforge.data.local.*
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import com.dayforge.sync.AccountIconWorker
import com.dayforge.sync.AutoSyncWorker
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Production Hilt/Worker entry points and actual owned FastAPI. Worker parameters alone are fake;
 * no mock HTTP/repository/transfer callback. Not WorkManager scheduling or manual UI acceptance.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealBackendMaterialJointTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val epochs = PhysicalDatabaseEpochFixture().also { fixture ->
        fixture.beforeCleanup = { ownerFailure?.let { failure -> throw failure }; check(ownersClosed) { "MATERIAL_JOINT_OWNERS_NOT_JOINED" } }
        fixture.beforeDatabaseDeletion = {
            check(ownersClosed && fixture.host.lifecycleEnded)
            val owned = File(app.filesDir, "account-icons-v1")
            assertTrue(!owned.exists() || owned.deleteRecursively())
        }
    }
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 3) val testName = TestName()
    @Inject lateinit var icons: AccountIconController
    @Inject lateinit var themes: DeviceThemeController
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var auth: AuthApi
    @Inject lateinit var business: BusinessSyncRepository
    @Inject internal lateinit var materialHttp: AccountIconHttp
    @Inject internal lateinit var http: NextSyncHttp
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val graph get() = epochs.host.boundGraph()
    private val db get() = graph.database
    private val habits get() = graph.habitRepository
    private val metrics get() = graph.metricRepository
    private val creator get() = graph.nextObjectCreator
    private val exports get() = graph.nextConfigExportRepository
    private val imports get() = graph.nextConfigImportRepository
    private var connected = false
    private val methodOwnerMonitor = Any()
    private var methodClosing = false
    private var methodJob: Job? = null
    private var ownersClosed = false
    private var ownerFailure: Throwable? = null
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
    private val wire = Json { encodeDefaults = true }
    private var installedTheme: ThemeVersionRef? = null
    private suspend fun <T> step(name: String, timeout: Long = 30_000, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        android.util.Log.i("MaterialJoint", "Starting $name")
        var completed = false
        return try { withTimeout(timeout) { current { block() } }.also { completed = true } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Joint step timed out: $name", error)
        } finally {
            // Monotonic durations and fixture step names only, never credentials or payloads.
            android.util.Log.i("MaterialJoint", "${if (completed) "Completed" else "Failed"} $name in " +
                "${SystemClock.elapsedRealtime() - started}ms")
        }
    }
    private fun id(n: Int) = "ac370000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val light = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private val dark = """<svg width="1" height="1"><rect width="1" height="1" fill="#00ff00"/></svg>""".toByteArray()
    private val png by lazy {
        val text = instrumentation.context.assets.open("next/png.json").bufferedReader().use { it.readText() }
        Base64.decode(Json.parseToJsonElement(text).jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun blob(bytes: ByteArray, media: String = "image/svg+xml") = IconBlob(hash(bytes), bytes.size, media, 1, 1)
    private val pack get() = IconPack("dayforge.icon-pack", 1, id(100), 1, "Actual joint style",
        listOf(IconAsset(id(10), "Template SVG", "general", "template", blob(light), blob(dark)),
            IconAsset(id(11), "Original PNG", "general", "original", blob(png, "image/png"), null),
            IconAsset(id(12), "Task SVG", "task", "template", blob(light), null)),
        mapOf("habit.exercise" to id(10), "metric.weight" to id(11), "task.shopping" to id(12)), null)

    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("dayforgeJointPort")).toInt()
        require(port in 1024..65535)
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        hilt.inject(); epochs.connect(); connected = true
        sessions.exclusive { tokens.clearTokens() }
        epochs.clearAndAwaitReplacement()
        preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
        preferences.setServerUrl("http://127.0.0.1:$port/")
        login(if (testName.methodName.contains("Config")) "joint-config" else "joint-material")
    }

    private suspend fun login(name: String) {
        val reply = auth.login(LoginRequest(name, "TestPassword123!")) // Public synthetic seed-only fixture.
        sessions.exclusive { tokens.saveLoginSession(reply.accessToken, reply.refreshToken, name, reply.userId, false) }
        step("authenticated bootstrap", 60_000) { business.sync() }
        assertNotNull(tokens.localIconAccess())
    }

    @After fun cleanup() = runBlocking<Unit> {
        val ownedMethod = synchronized(methodOwnerMonitor) {
            methodClosing = true
            methodJob
        }
        try {
            withTimeout(5_000) { ownedMethod?.cancelAndJoin() }
            check(ownedMethod?.isCompleted != false)
            if (::icons.isInitialized) { icons.awaitImages(); icons.close() }
            if (::themes.isInitialized) {
                try {
                    installedTheme?.let { themes.delete(it, requireNotNull(themes.catalog()).revision) }
                } finally { withTimeout(5_000) { themes.close() } }
            }
            if (connected) { shutdownTestHost(epochs.host); check(epochs.host.lifecycleEnded) }
            if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
            if (::preferences.isInitialized) {
                preferences.setServerUrl(null); preferences.setActiveServerUrl(null); preferences.setRemoteServerUrl(null)
            }
            check(app.packageName == "com.dayforge.testbed")
            ownersClosed = true
            // EpochFixture deletes the closed icon/main DBs; owned files wait for its same known-close callback.
        } catch (failure: Throwable) { ownerFailure = failure; throw failure }
    }

    private suspend fun install(): AccountIconContext {
        val context = icons.capture()
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            val images = listOf(light, dark, png).distinctBy(::hash).map { "blobs/${hash(it)}" to it }
            for ((name, value) in listOf("manifest.json" to wire.encodeToString(pack).toByteArray()) + images) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
            }
        } }.toByteArray()
        val archive = ValidatedIconPack.read { ByteArrayInputStream(bytes) }
        val receipt = icons.install(AccountIconPackPreview(context, archive))
        assertEquals(IconPackVersion(pack.packId, 1), receipt.version)
        assertNull(icons.library(context).selection.pack)
        assertTrue(icons.transferJobs(context).any { it.state == IconTransferState.PENDING })
        icons.select(context, icons.library(context).selection.generation, receipt.version)
        return context
    }

    private suspend fun materialWorker() = AccountIconWorker(app, mockk<WorkerParameters>(relaxed = true)).doWork()
    private suspend fun businessWorker() = AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)).doWork()
    private val materialComplete get() = Result.success(workDataOf(AccountIconWorker.STATUS to "COMPLETE"))
    private suspend fun materialDone(context: AccountIconContext) {
        var outcome: Result = Result.retry()
        repeat(3) {
            if (outcome != materialComplete) {
                outcome = materialWorker()
                assertTrue(outcome == Result.retry() || outcome == materialComplete)
            }
        }
        assertEquals(materialComplete, outcome)
        val jobs = icons.transferJobs(context)
        assertTrue(jobs.isNotEmpty()); assertTrue(jobs.all { it.state == IconTransferState.COMPLETE })
    }

    @Test fun realMaterialWorkersUploadAndColdDownloadBothFormatsWithoutSharingAccountPixels() = journey<Unit> {
        val context = install()
        val metric = current { metrics.createMetric(MetricEntity(name = "pending material metric", unit = "kg", decimalPlaces = 0,
            iconResId = 0, colorHex = "#123456", appearance = ObjectAppearance(IconReference.Asset(id(11)), "#123456", "theme")),
            creationAuthority = creator.capture()) }
        assertEquals(Result.retry(), step("ordinary reference waits for declaration", 30_000) { businessWorker() })
        assertTrue(current { db.syncOutboxDao().getAll().isNotEmpty() })
        assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
        materialDone(context)
        assertEquals(Result.success(), step("ordinary reference acknowledgement", 30_000) { businessWorker() })
        assertNotNull(current { db.metricDao().getMetricById(metric) })
        // Replay stability belongs to the material scenario, not every configuration prerequisite.
        val jobs = icons.transferJobs(context)
        assertEquals(materialComplete, materialWorker())
        assertEquals(jobs, icons.transferJobs(context))
        val loaded = themes.current()
        val handle = icons.referenceImage()
        try {
            icons.loadReference(handle, requireNotNull(handle.requests.value), IconReference.Role("habit.exercise"), false,
                loaded.saved.selection.light, false, IconRasterSize(8, 8), loaded.light.material.getValue("primary"))
            val image = (handle.state.value as IconImageState.Ready).raster
            assertEquals(loaded.light.material.getValue("primary"), image.bitmap.getPixel(4, 4))

            // A second empty, on-disk material replica downloads only from the actual server.
            // Same production classes, explicit test-owned lifetime; not a second production Hilt graph.
            val directory = Files.createTempDirectory(app.filesDir.toPath(), "actual-material-replica-").toFile()
            val name = "actual_material_replica_${System.nanoTime()}"
            val replica = Room.databaseBuilder(app, AccountIconDatabase::class.java, name).build()
            try {
                val metadata = AccountIconRepository(replica, tokens, sessions)
                val store = AccountIconStore(metadata, AccountIconFiles(directory))
                val queue = AccountIconTransfers(replica, metadata, store)
                val producer = AccountIconImport(metadata, store, queue)
                val renderer = AccountIconRenderer(metadata, store)
                val runtime = AccountIconRuntime(metadata, store, renderer, producer,
                    AccountIconDocuments(producer, app.contentResolver), replica::close)
                assertTrue(metadata.library(context).packs.isEmpty())
                assertEquals(IconMaterialOutcome.RETRY, runtime.synchronize(context, materialHttp)) // catalog commits download intents
                assertEquals(pack, metadata.pack(context, pack.packId, 1))
                assertNull(metadata.selection(context).pack) // remote catalog never selects a style
                assertEquals(IconMaterialOutcome.COMPLETE, runtime.synchronize(context, materialHttp))
                assertTrue(queue.jobs(context).all { it.kind == IconTransferKind.DOWNLOAD && it.state == IconTransferState.COMPLETE })
                for (asset in pack.assets) for (variant in listOfNotNull(asset.light, asset.dark)) {
                    val expected = listOf(light, dark, png).single { hash(it) == variant.sha256 }
                    assertArrayEquals(expected, store.read(context, asset.assetId, variant.sha256))
                }
                store.select(context, 0, IconPackVersion(pack.packId, 1))
                val darkImage = renderer.renderReference(context, IconReference.Role("habit.exercise"), false,
                    loaded.saved.selection.dark, true, IconRasterSize(8, 8), loaded.dark.material.getValue("primary"))
                assertEquals(blob(dark), darkImage.raster!!.blob)
                assertEquals(loaded.dark.material.getValue("primary"), darkImage.raster.bitmap.getPixel(4, 4))
                val original = renderer.renderReference(context, IconReference.Role("metric.weight"), false,
                    loaded.saved.selection.light, false, IconRasterSize(8, 8), 0xff0000ff.toInt())
                assertEquals(0xffff0000.toInt(), original.raster!!.bitmap.getPixel(4, 4))
            } finally {
                replica.close()
                assertTrue(app.deleteDatabase(name) || !app.getDatabasePath(name).exists())
                assertTrue(directory.deleteRecursively())
            }
            sessions.exclusive { tokens.clearTokens() }
            assertEquals(IconImageState.Empty, handle.state.value)
            epochs.clearAndAwaitReplacement()
            login("joint-core")
            assertNotEquals(context.namespace.accountId, icons.capture().namespace.accountId)
            assertTrue(icons.library(icons.capture()).packs.isEmpty())
            assertTrue(runCatching { icons.library(context) }.isFailure)
            assertEquals(IconImageState.Empty, handle.state.value)
        } finally { handle.close() }
    }

    // User-approved complete-chain exception, measured local commit near 148s on MI6.
    // All other cases keep the runner default 150s; per-phase guards and batch budget stay bounded.
    @Test(timeout = 300_000) fun realConfigReplacementKeepsAppearanceDefinitionsButUsesNewIdentitiesAndActualBusinessAcks() = journey<Unit> {
        val context = step("source pack installation") { install() }
        step("source material acknowledgements", 60_000) { materialDone(context) }
        val themeBefore = themes.current().saved
        val metric = step("source metric creation") { metrics.createMetric(MetricEntity(name = "joint config metric", unit = "kg", decimalPlaces = 3,
            iconResId = 0, colorHex = "#123456", appearance = ObjectAppearance(IconReference.Asset(id(11)), "#123456", "object")),
            creationAuthority = creator.capture()) }
        val goal = HabitDraft(name = "joint config goal", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.default"), "#123456", "theme"))
        step("source goal creation") { habits.createGoal(goal, emptyList(), creationAuthority = creator.capture()) }
        val check = step("source habit creation") { habits.createHabit("joint config habit", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Daily,
            parentHabitId = goal.id, targetCycles = 3, completionPolicy = "recurring",
            appearance = ObjectAppearance(IconReference.Role("habit.exercise"), "#123456", "theme"),
            selectedMetricIds = setOf(metric), creationAuthority = creator.capture()) }
        val once = step("source one-time item creation") { habits.createHabit("joint config once", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Once(),
            failMode = FailMode.LOOSE, completionPolicy = "one_and_done",
            appearance = ObjectAppearance(IconReference.Role("task.shopping"), "#123456", "theme"), creationAuthority = creator.capture()) }
        assertEquals(Result.success(), step("source structure acknowledgements", 90_000) { businessWorker() })
        step("source historical facts") {
            habits.logCompletion(app, check); habits.logCompletion(app, once)
            metrics.recordValue(metric, 12.125, "excluded historical fact")
        }
        assertEquals(Result.success(), step("source fact acknowledgements", 90_000) { businessWorker() })
        val originalIds = current { db.habitDao().getAllHabitsOnce().map { it.uuid }.toSet() }
        val onceId = current { requireNotNull(habits.getHabitById(once)).uuid }
        val default = step("default configuration export") { exports.publish(exports.prepare()) { it } }
        assertFalse(default.manifest.nodes.any { it.name == "joint config once" })
        val source = step("explicit configuration export") {
            exports.publish(exports.prepare(setOf(onceId), listOf(themeBefore.selection.light))) { it }
        }
        assertEquals(3, source.manifest.nodes.size)
        assertEquals(1, source.manifest.metrics.size); assertEquals(1, source.manifest.links.size)
        val oldAssets = source.manifest.iconPack!!.assets.map { it.assetId }.toSet()
        val oldPackSelection = icons.library(context).selection
        val preview = step("replacement preview") { imports.previewReplacement(source) }
        assertTrue(preview.eligible)
        val importId = step("replacement confirmation") { imports.confirmReplacement(preview) }
        val themesToInstall = step("mapped theme preparation") {
            imports.prepareThemeInstall(importId, source, setOf(themeBefore.selection.light), context)
        }
        val mappedTheme = themesToInstall.single()
        assertNotEquals(themeBefore.selection.light.themeId, mappedTheme.definition.themeId)
        step("mapped theme installation") { themes.install(mappedTheme) }
        installedTheme = ThemeVersionRef(mappedTheme.definition.themeId, 1)
        val receipt = step("replacement local commit") {
            imports.resume(importId, ValidatedConfigBundle.parse(source.exportBytes()), context)
        }
        assertEquals(3, receipt.nodes); assertEquals(1, receipt.metrics); assertEquals(1, receipt.links)
        assertEquals(ConfigImportPhase.LOCAL_COMMITTED, current { imports.progress(importId)!!.phase })
        step("replacement delete waits for content") {
            val first = sessions.exclusive { db.withTransaction { NextConfigImportStore(db).network(importId).plan.steps.first() } }
            val sender = NextCoreRequestStore(db, tokens, sessions, http, AccountMaterialDependencyGate(icons))
            try {
                sender.sendOperation(requireNotNull(tokens.localSyncAccess()), first.operationId)
                fail("Delete must wait for the cloned material content")
            } catch (_: NextMaterialPending) { /* Local waiting, not a server rejection. */ }
            assertTrue(current { db.nextSyncStateDao().rejections().isEmpty() })
            val before = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
            assertEquals(originalIds, before.changes.filter { it.entityType == "plan_node" }.map { it.entityUuid }.toSet())
        }
        step("replacement material acknowledgements", 60_000) { materialDone(context) }
        // Multiple original requests plus profile validation, not one potentially stuck HTTP call.
        assertEquals(Result.success(), step("replacement business acknowledgements", 90_000) { businessWorker() })
        val accepted = step("replacement progress") { imports.progress(importId)!! }
        assertEquals(ConfigImportPhase.OPERATIONS_ACCEPTED, accepted.phase)
        assertEquals(accepted.total, accepted.accepted)
        assertTrue(current { db.syncOutboxDao().getAll().isEmpty() })
        val replaced = current { db.habitDao().getAllHabitsOnce() }
        assertEquals(3, replaced.size); assertTrue(replaced.none { it.uuid in originalIds })
        assertTrue(current { db.completionDao().getAllCompletionsOnce().isEmpty() })
        assertEquals(0, current { db.metricLogDao().countAll() })
        val importedOnce = replaced.single { it.completionPolicy == "one_and_done" }
        assertFalse(current { habits.getOneTimeStatus(importedOnce.id).completed })
        assertEquals(oldPackSelection, icons.library(context).selection)
        assertEquals(themeBefore, themes.current().saved)
        assertEquals(mappedTheme.definition, themes.export(requireNotNull(installedTheme)).definition)
        val snapshot = requireNotNull(http.session(requireNotNull(tokens.localSyncAccess())) { it.roundBootstrap() })
        assertEquals(replaced.map { it.uuid }.toSet(), snapshot.changes.filter { it.entityType == "plan_node" }.map { it.entityUuid }.toSet())
        assertTrue(snapshot.changes.none { it.entityType in setOf("activity_event", "metric_observation") })
        val nextExport = step("replaced configuration export") { exports.publish(exports.prepare()) { it } }
        assertEquals(source.manifest.nodes.map { it.name }.toSet(), nextExport.manifest.nodes.map { it.name }.toSet())
        val importedAsset = (nextExport.manifest.metrics.single().appearance.icon as IconReference.Asset).assetId
        assertTrue(importedAsset !in oldAssets)
        assertArrayEquals(png, nextExport.readBlob(nextExport.manifest.iconPack!!.assets.single { it.assetId == importedAsset }.light.sha256))
        assertEquals(receipt, step("replacement exact replay") { imports.resume(importId, source, context) })
        assertEquals(Result.success(), step("replacement repeated business sync", 90_000) { businessWorker() })
        assertEquals(replaced, current { db.habitDao().getAllHabitsOnce() })
        assertEquals(accepted, current { imports.progress(importId) })
    }
}
