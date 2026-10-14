package com.dayforge.data.appearance

import android.util.Base64
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconImportTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var db: AccountIconDatabase
    private lateinit var repo: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var imports: AccountIconImport
    private lateinit var transfers: AccountIconTransfers
    private val sessions = AccountSessionCoordinator()
    private fun id(n: Int) = "9a000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val red = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private val green = """<svg width="1" height="1"><rect width="1" height="1" fill="#00ff00"/></svg>""".toByteArray()
    private val png by lazy {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("next/png.json").bufferedReader().use { it.readText() }
        Base64.decode(Json.parseToJsonElement(raw).jsonArray.first().jsonObject.getValue("png").jsonPrimitive.content, Base64.DEFAULT)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun blob(bytes: ByteArray, media: String = "image/svg+xml") =
        """{"sha256":"${hash(bytes)}","byte_length":${bytes.size},"media_type":"$media","width":1,"height":1}"""
    // Independent raw manifest/standard ZIP writer: no production DTO/codec creates the input.
    private fun manifest(pack: Int = 100, name: String = "风格", baseline: Boolean = false): String {
        val assets = if (baseline) """{"asset_id":"${id(10)}","name":"red","purpose":"general","color_mode":"original","light":${blob(red)},"dark":null}""" else """
            {"asset_id":"${id(20)}","name":"red/green","purpose":"general","color_mode":"template","light":${blob(red)},"dark":${blob(green)}},
            {"asset_id":"${id(21)}","name":"task","purpose":"task","color_mode":"original","light":${blob(red)},"dark":null},
            {"asset_id":"${id(22)}","name":"unused PNG","purpose":"general","color_mode":"original","light":${blob(png, "image/png")},"dark":null}
        """
        val roles = if (baseline) """"habit.exercise":"${id(10)}"""" else """"habit.exercise":"${id(20)}","task.default":"${id(21)}""""
        return """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${id(pack)}","revision":1,"name":"$name",
            "assets":[$assets],"roles":{$roles},"placeholder_asset_id":null}"""
    }
    private fun archive(text: String = manifest(), images: List<ByteArray> = listOf(red, green, png)): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to text.toByteArray()) + images.distinctBy(::hash).map { "blobs/${hash(it)}" to it }) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private suspend fun preview(bytes: ByteArray = archive()) = imports.preview { ByteArrayInputStream(bytes) }
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3), readOnly: Boolean = false) =
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
            tokens.saveServerIdentity(server, epoch)
            tokens.saveDeviceRegistration(id(4), if (readOnly) setOf("sync.read") else setOf("sync.read", "structure.write"), true, 1)
        }
    private fun configure(io: IconFileIo = IconFileIo(), limits: AccountIconLimits = AccountIconLimits(),
        maximumJobs: Int = AccountIconTransfers.MAX_JOBS) {
        repo = AccountIconRepository(db, tokens, sessions, limits)
        store = AccountIconStore(repo, AccountIconFiles(parent, io))
        transfers = AccountIconTransfers(db, repo, store, maximumJobs)
        imports = AccountIconImport(repo, store, transfers)
    }
    private fun ready() = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM icon_blob_ready").use { it.moveToFirst(); it.getInt(0) }
    private fun directory(context: AccountIconContext) = File(parent,
        "account-icons-v1/${context.namespace.accountId}/${context.namespace.serverInstanceId}/${context.namespace.syncEpoch}")
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        val error = try { block(); null } catch (e: Exception) { if (e is CancellationException) throw e; e }
        assertNotNull("Must reject", error); return requireNotNull(error)
    }
    private suspend fun assertEmpty() {
        val context = repo.capture()
        assertTrue(transfers.jobs(context).isEmpty())
        assertEquals(emptyList<IconReservation>(), repo.reservations(context))
        assertNull(repo.pack(context, id(100), 1)); assertEquals(0, ready())
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        assertFalse(directory(context).exists())
    }
    private suspend fun baseline(): AccountIconSelection {
        val old = preview(archive(manifest(200, baseline = true), listOf(red)))
        imports.confirm(old)
        return store.select(repo.capture(), 0, IconPackVersion(id(200), 1))
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-import-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        tokens = TokenManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") }))
        db = AccountIconDatabase.open(app); configure(); login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::db.isInitialized) db.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun previewOpensAndClosesOnceFreezesSourceAndDoesNotInstallOrChoose() = runBlocking<Unit> {
        val source = archive(); var opened = 0; var closed = 0
        val value = imports.preview {
            opened++; object : ByteArrayInputStream(source) { override fun close() { closed++; super.close() } }
        }
        assertEquals(1, opened); assertEquals(1, closed); assertEmpty()
        source.fill(0)
        assertThrows(UnsupportedOperationException::class.java) { (value.manifest.roles as MutableMap)["habit.bad"] = id(20) }
        val receipt = imports.confirm(value)
        assertEquals(IconPackVersion(id(100), 1), receipt.version); assertEquals(1, opened)
        assertArrayEquals(red, store.read(repo.capture(), id(20), hash(red)))
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
    }

    @Test fun confirmIncludesDarkUnusedPngAndSharedTaskBlobWithoutDuplicatePublication() = runBlocking<Unit> {
        val published = AtomicInteger()
        configure(object : IconFileIo() { override fun rename(source: String, target: String) { super.rename(source, target); published.incrementAndGet() } })
        val value = preview(); val receipt = imports.confirm(value); val context = repo.capture()
        assertEquals(listOf(hash(red), hash(green), hash(png)), receipt.verifiedHashes)
        assertEquals(context.namespace, receipt.namespace); assertEquals(3, published.get()); assertEquals(3, ready())
        assertEquals(setOf(hash(red), hash(green), hash(png)), directory(context).list()!!.toSet())
        assertArrayEquals(green, store.read(context, id(20), hash(green)))
        assertArrayEquals(red, store.read(context, id(21), hash(red)))
        assertArrayEquals(png, store.read(context, id(22), hash(png)))
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        val jobs = transfers.jobs(context)
        assertEquals(8, jobs.size)
        assertEquals(3, jobs.count { it.kind == IconTransferKind.DECLARE_ASSET })
        assertEquals(4, jobs.count { it.kind == IconTransferKind.UPLOAD })
        assertEquals(1, jobs.count { it.kind == IconTransferKind.DECLARE_PACK })
        assertTrue(jobs.all { it.state == IconTransferState.PENDING && it.generation == 0L })
        assertThrows(UnsupportedOperationException::class.java) { (receipt.verifiedHashes as MutableList).clear() }
    }

    @Test fun exactRetryAfterColdReopenPreservesEveryInstallationIntentAndFile() = runBlocking<Unit> {
        val value = preview(); val first = imports.confirm(value); val before = repo.reservations(repo.capture())
        val jobs = transfers.jobs(repo.capture())
        db.close(); db = AccountIconDatabase.open(app); configure()
        assertEquals(first, imports.confirm(value)); assertEquals(before, repo.reservations(repo.capture()))
        assertEquals(first, imports.confirm(preview())); assertEquals(before, repo.reservations(repo.capture()))
        assertEquals(3, ready()); assertEquals(3, directory(repo.capture()).list()!!.size)
        assertEquals(jobs, transfers.jobs(repo.capture()))
    }

    @Test fun configurationCopiesNewOwnedIdentitiesAndColdMaterialRetryKeepsAllOriginalInstallWorkAndChoice() = runBlocking<Unit> {
        val oldChoice = baseline(); val context = repo.capture()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val ids = com.dayforge.data.export.NextConfigImportPlan.allocate(source,
            com.dayforge.data.export.ConfigImportTarget.from(context), java.time.Instant.parse("2026-10-09T00:00:00Z"))
        val plan = com.dayforge.data.export.NextConfigImportPlan(source, ids)
        val receipt = imports.confirmConfiguration(context, plan)
        assertEquals(plan.iconPack, repo.pack(context, plan.iconPack!!.packId, 1))
        assertNull(repo.pack(context, source.manifest.iconPack!!.packId, 1))
        for (asset in plan.iconPack!!.assets) {
            assertEquals(asset, repo.asset(context, asset.assetId))
            for (blob in listOfNotNull(asset.light, asset.dark))
                assertArrayEquals(source.readBlob(blob.sha256), store.read(context, asset.assetId, blob.sha256))
        }
        val jobs = transfers.jobs(context); val reservations = repo.reservations(context)
        assertEquals(oldChoice, repo.selection(context))
        db.close(); db = AccountIconDatabase.open(app); configure()
        val restored = com.dayforge.data.export.NextConfigImportPlan(ValidatedConfigBundle.parse(source.exportBytes()),
            com.dayforge.data.export.ConfigImportIdentities.decode(ids.encode().toByteArray()))
        assertEquals(receipt, imports.confirmConfiguration(repo.capture(), restored))
        assertEquals(jobs, transfers.jobs(repo.capture())); assertEquals(reservations, repo.reservations(repo.capture()))
        assertEquals(oldChoice, repo.selection(repo.capture()))
        // Allocation serialization + actual material cold reopen, not a business import journal.
    }

    @Test fun configurationFileFailureRetainsOriginalTransferIntentsAndUsesSameMappingOnRetryWithoutApplyingStyle() = runBlocking<Unit> {
        val oldChoice = baseline(); val context = repo.capture()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val plan = com.dayforge.data.export.NextConfigImportPlan(source,
            com.dayforge.data.export.NextConfigImportPlan.allocate(source,
                com.dayforge.data.export.ConfigImportTarget.from(context), java.time.Instant.parse("2026-10-09T00:00:00Z")))
        val lastHash = plan.iconPack!!.assets.last().light.sha256
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER config_ready_failure BEFORE INSERT ON icon_blob_ready " +
            "WHEN NEW.sha256='$lastHash' BEGIN SELECT RAISE(ABORT,'configuration install fault'); END")
        rejected { imports.confirmConfiguration(context, plan) }
        val jobs = transfers.jobs(context); val reservations = repo.reservations(context)
        assertTrue(jobs.isNotEmpty()); assertEquals(oldChoice, repo.selection(context))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER config_ready_failure")
        imports.confirmConfiguration(context, plan)
        assertEquals(jobs, transfers.jobs(context)); assertEquals(reservations, repo.reservations(context))
        assertEquals(oldChoice, repo.selection(context))
        for (asset in plan.iconPack!!.assets) for (blob in listOfNotNull(asset.light, asset.dark))
            assertArrayEquals(source.readBlob(blob.sha256), store.read(context, asset.assetId, blob.sha256))
    }

    @Test fun configurationTargetReadOnlyAndOldSessionCannotCreateAdoptedMaterial() = runBlocking<Unit> {
        val context = repo.capture()
        val source = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
        val plan = com.dayforge.data.export.NextConfigImportPlan(source,
            com.dayforge.data.export.NextConfigImportPlan.allocate(source,
                com.dayforge.data.export.ConfigImportTarget.from(context), java.time.Instant.parse("2026-10-09T00:00:00Z")))
        login(readOnly = true)
        assertEquals("ICON_DECLARATION_DENIED", rejected { imports.confirmConfiguration(repo.capture(), plan) }.message)
        assertEmpty()
        login(owner = id(9))
        assertEquals("CONFIG_IMPORT_TARGET_CHANGED", rejected { imports.confirmConfiguration(repo.capture(), plan) }.message)
        assertEquals("ICON_SESSION_CHANGED", rejected { imports.confirmConfiguration(context, plan) }.message)
        assertEmpty()
    }

    @Test fun reusedPackVersionWithDifferentContentCannotInstallAnotherVersion() = runBlocking<Unit> {
        imports.confirm(preview()); val before = repo.reservations(repo.capture())
        val error = rejected { imports.confirm(preview(archive(manifest(name = "different")))) }
        assertEquals("PACK_VERSION_REUSED", error.message); assertEquals(before, repo.reservations(repo.capture()))
        assertEquals("风格", repo.pack(repo.capture(), id(100), 1)!!.name); assertEquals(3, ready())
    }

    @Test fun reusedAssetIdentityRejectsWholeNewPackWithoutPartialReservation() = runBlocking<Unit> {
        imports.confirm(preview()); val before = repo.reservations(repo.capture())
        val changed = manifest(101).replace("\"color_mode\":\"template\"", "\"color_mode\":\"original\"")
        assertEquals("ASSET_ID_REUSED", rejected { imports.confirm(preview(archive(changed))) }.message)
        assertNull(repo.pack(repo.capture(), id(101), 1)); assertEquals(before, repo.reservations(repo.capture())); assertEquals(3, ready())
    }

    @Test fun quotaFailureMakesNoMetadataFileOrChoiceMutation() = runBlocking<Unit> {
        configure(limits = AccountIconLimits(assets = 2))
        assertEquals("ICON_QUOTA_EXCEEDED", rejected { imports.confirm(preview()) }.message); assertEmpty()
    }

    @Test fun queueQuotaRollsBackNewMetadataAndInstallReservationsBeforeAnyFileIo() = runBlocking<Unit> {
        configure(maximumJobs = 7)
        val value = preview()
        assertEquals("ICON_TRANSFER_QUOTA", rejected { imports.confirm(value) }.message)
        assertEmpty()
        configure(); imports.confirm(value)
        assertEquals(8, transfers.jobs(repo.capture()).size); assertEquals(3, ready())
    }

    @Test fun queueAbortIgnoreRewrittenIdentityAndMetadataDamageRollBackWholeProductionImport() = runBlocking<Unit> {
        val value = preview()
        val triggers = listOf(
            "BEFORE INSERT ON icon_transfers WHEN NEW.kind='upload' BEGIN SELECT RAISE(ABORT,'injected'); END",
            "BEFORE INSERT ON icon_transfers WHEN NEW.kind='upload' BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON icon_transfers BEGIN UPDATE icon_transfers SET operationId='${id(999)}' WHERE operationId=NEW.operationId; END",
            "AFTER INSERT ON icon_transfers BEGIN UPDATE icon_assets SET metadataJson='{}'; END"
        )
        for (trigger in triggers) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER import_fault $trigger")
            try { rejected { imports.confirm(value) } }
            finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER import_fault") }
            assertEmpty()
        }
        // Reopen also proves rollback independently of warm parsed metadata caches.
        db.close(); db = AccountIconDatabase.open(app); configure(); assertEmpty()
        imports.confirm(value); assertEquals(8, transfers.jobs(repo.capture()).size); assertEquals(3, ready())
    }

    @Test fun transferFailureKeepsPreviouslyInstalledSelectedAndAcknowledgedPackUnchanged() = runBlocking<Unit> {
        val choice = baseline(); val context = repo.capture()
        val attempt = requireNotNull(transfers.prepareNext(context))
        assertEquals(IconTransferKind.DECLARE_ASSET, attempt.job.kind)
        transfers.confirmAsset(attempt, com.dayforge.domain.model.AssetRecord(attempt.binding, requireNotNull(attempt.asset), emptyList()))
        val jobs = transfers.jobs(context); val reservations = repo.reservations(context)
        val files = directory(context).list()!!.toSet()
        configure(maximumJobs = 10) // Existing 3 + new 8 cannot commit.
        assertEquals("ICON_TRANSFER_QUOTA", rejected { imports.confirm(preview()) }.message)
        assertEquals(jobs, transfers.jobs(context)); assertEquals(reservations, repo.reservations(context))
        assertEquals(files, directory(context).list()!!.toSet()); assertEquals(choice, repo.selection(context))
        assertNull(repo.pack(context, id(100), 1)); assertNull(repo.asset(context, id(20))); assertEquals(1, ready())
        configure(); imports.confirm(preview())
        assertEquals(jobs, transfers.jobs(context).filter { it.operationId in jobs.map { job -> job.operationId } })
        assertEquals(11, transfers.jobs(context).size); assertEquals(choice, repo.selection(context))
    }

    @Test fun repeatImportPreservesCompletedBlockedSendingAndPendingTransferStates() = runBlocking<Unit> {
        val value = preview(); val receipt = imports.confirm(value); val context = repo.capture()
        val completed = requireNotNull(transfers.prepareNext(context))
        transfers.confirmAsset(completed, com.dayforge.domain.model.AssetRecord(completed.binding, requireNotNull(completed.asset), emptyList()))
        transfers.block(requireNotNull(transfers.prepareNext(context)), IconTransferFailure.LOCAL_CONTENT)
        val sending = requireNotNull(transfers.prepareNext(context))
        val before = db.transfers().rows(context.namespace.accountId, context.namespace.serverInstanceId, context.namespace.syncEpoch)
        assertEquals(setOf("pending", "sending", "blocked", "complete"), before.map { it.state }.toSet())
        db.close(); db = AccountIconDatabase.open(app); configure()
        assertEquals(receipt, imports.confirm(value))
        assertEquals(before, db.transfers().rows(context.namespace.accountId, context.namespace.serverInstanceId, context.namespace.syncEpoch))
        assertEquals(sending.job, transfers.jobs(context).single { it.operationId == sending.job.operationId })
        assertEquals(3, ready())
    }

    @Test fun cancellationDuringQueueInsertRollsBackDeclarationReservationsAndQueueBeforeFiles() = runBlocking<Unit> {
        val value = preview(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        db.close()
        db = Room.databaseBuilder(app, AccountIconDatabase::class.java, AccountIconDatabase.NAME)
            .addMigrations(AccountIconDatabase.MIGRATION_1_2, AccountIconDatabase.MIGRATION_2_3, AccountIconDatabase.MIGRATION_3_4, AccountIconDatabase.MIGRATION_4_5)
            .setQueryCallback({ statement, _ ->
                if (statement.startsWith("INSERT", ignoreCase = true) && statement.contains("icon_transfers")) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }, Executor { it.run() }).build()
        configure()
        val pending = async(Dispatchers.IO) { imports.confirm(value) }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); pending.cancel() }
        finally { release.countDown(); withTimeout(5_000) { pending.join() } }
        assertTrue(pending.isCancelled); assertEmpty()
        db.close(); db = AccountIconDatabase.open(app); configure(); assertEmpty()
        imports.confirm(value); assertEquals(8, transfers.jobs(repo.capture()).size); assertEquals(3, ready())
    }

    @Test fun mismatchedImportMetadataFilesOrQueueCannotCreateProductionBoundary() = runBlocking<Unit> {
        val otherRepo = AccountIconRepository(db, tokens, sessions)
        val otherStore = AccountIconStore(otherRepo, AccountIconFiles(parent))
        assertThrows(IllegalArgumentException::class.java) { AccountIconImport(repo, otherStore, transfers) }
        assertThrows(IllegalArgumentException::class.java) {
            AccountIconImport(repo, store, AccountIconTransfers(db, otherRepo, otherStore))
        }
        assertEmpty()
    }

    @Test fun abortAndIgnorePackReservationRollbackAllAssetsAndJournalsBeforeIo() = runBlocking<Unit> {
        val value = preview()
        for (mode in listOf("ABORT", "IGNORE")) {
            val raise = if (mode == "ABORT") "RAISE(ABORT,'injected')" else "RAISE(IGNORE)"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER import_fault BEFORE INSERT ON icon_packs BEGIN SELECT $raise; END")
            try { rejected { imports.confirm(value) } } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER import_fault") }
            assertEmpty()
        }
        imports.confirm(value); assertEquals(3, ready())
    }

    private suspend fun readyFailure(mode: String) {
        val oldChoice = baseline(); val value = preview()
        val raise = if (mode == "ABORT") "RAISE(ABORT,'injected')" else "RAISE(IGNORE)"
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER import_fault BEFORE INSERT ON icon_blob_ready WHEN NEW.sha256='${hash(green)}' BEGIN SELECT $raise; END")
        try { rejected { imports.confirm(value) } } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER import_fault") }
        val context = repo.capture(); val intents = repo.reservations(context)
        val jobs = transfers.jobs(context)
        assertEquals(11, jobs.size); assertTrue(jobs.all { it.state == IconTransferState.PENDING })
        assertEquals(1, ready()); assertEquals(oldChoice, repo.selection(context))
        assertArrayEquals(green, File(directory(context), hash(green)).readBytes())
        assertFalse(File(directory(context), hash(png)).exists())
        imports.confirm(value); assertEquals(intents, repo.reservations(context)); assertEquals(3, ready())
        assertEquals(jobs, transfers.jobs(context))
        assertEquals(oldChoice, repo.selection(context))
    }
    @Test fun lateReadyAbortRetainsPartialFilesAndOldStyleThenReplaysOriginalIntents() = runBlocking<Unit> { readyFailure("ABORT") }
    @Test fun silentlyIgnoredReadyCannotReportPackInstalledAndRetryDoesNotOverwriteFiles() = runBlocking<Unit> { readyFailure("IGNORE") }

    @Test fun partialWriteRetryCleansOnlyItsOwnProvenTemporary() = runBlocking<Unit> {
        val writes = AtomicInteger()
        configure(object : IconFileIo() {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int =
                if (writes.incrementAndGet() == 2) 0 else super.write(fd, bytes, offset, length)
        })
        val value = preview(); rejected { imports.confirm(value) }; val context = repo.capture()
        val intents = repo.reservations(context); assertEquals(1, ready())
        val pending = intents.single { it.blob.sha256 == hash(green) }
        val temporary = File(directory(context), ".install-${pending.operationId}.part"); assertTrue(temporary.exists())
        val unknown = File(directory(context), ".install-${id(999)}.part").apply { writeText("unknown") }
        // A separately reserved import intent must not be swept by this import's retry.
        val blue = String(red).replace("#ff0000", "#0000ff").toByteArray()
        val other = preview(archive(manifest(300, baseline = true).replace(blob(red), blob(blue)), listOf(blue)))
        repo.reservePack(context, other.manifest)
        val allIntents = repo.reservations(context)
        val otherTemporary = File(directory(context), ".install-${allIntents.single { it.blob.sha256 == hash(blue) }.operationId}.part").apply { writeText("proven") }
        configure(); imports.confirm(value)
        assertFalse(temporary.exists()); assertEquals("proven", otherTemporary.readText()); assertEquals("unknown", unknown.readText())
        assertEquals(allIntents, repo.reservations(context)); assertEquals(3, ready())
    }

    @Test fun corruptReadyFileIsNotSilentlyRebuiltFromValidPreview() = runBlocking<Unit> {
        val value = preview(); imports.confirm(value); val context = repo.capture()
        val choice = store.select(context, 0, IconPackVersion(id(100), 1))
        val file = File(directory(context), hash(red)); file.writeText("bad")
        rejected { imports.confirm(value) }; assertEquals("bad", file.readText()); assertEquals(3, ready())
        assertEquals(choice, repo.selection(context))
    }

    @Test fun corruptionOfEarlierReadyDuringLastInstallCannotProduceWholePackSuccess() = runBlocking<Unit> {
        val oldChoice = baseline(); val context = repo.capture()
        configure(object : IconFileIo() {
            override fun rename(source: String, target: String) {
                super.rename(source, target)
                if (File(target).name == hash(png)) File(directory(context), hash(red)).writeText("late corruption")
            }
        })
        val value = preview(); rejected { imports.confirm(value) }
        // All individual installations committed, but the final whole-pack verification rejected.
        assertEquals(3, ready()); assertEquals(oldChoice, repo.selection(context))
        assertEquals("late corruption", File(directory(context), hash(red)).readText())
        val intents = repo.reservations(context); configure(); rejected { imports.confirm(value) }
        assertEquals(intents, repo.reservations(context)); assertEquals(oldChoice, repo.selection(context))
    }

    @Test fun maximumAssetAndRolePackInstallsAllRealFilesWithinNormalGate() = runBlocking<Unit> { withTimeout(140_000) {
        // Cancel/join structured Room and file work before the unchanged 150s runner deadline;
        // never let the runner tear down the database while a timed-out worker still owns it.
        val syncs = AtomicInteger()
        configure(object : IconFileIo() {
            override fun sync(fd: FileDescriptor) { super.sync(fd); syncs.incrementAndGet() }
        })
        val images = (0 until 128).map { n ->
            """<svg width="1" height="1"><rect width="1" height="1" fill="#${(n + 1).toString(16).padStart(6, '0')}"/></svg>""".toByteArray()
        }
        val assets = images.mapIndexed { n, image ->
            """{"asset_id":"${id(1000 + n)}","name":"icon$n","purpose":"general","color_mode":"original","light":${blob(image)},"dark":null}"""
        }.joinToString(",")
        val roles = (0 until 256).joinToString(",") { n -> "\"habit.role_$n\":\"${id(1000 + n % 128)}\"" }
        val raw = """{"format":"dayforge.icon-pack","format_version":1,"pack_id":"${id(400)}","revision":1,"name":"full pack",
            "assets":[$assets],"roles":{$roles},"placeholder_asset_id":null}"""
        val started = android.os.SystemClock.elapsedRealtime()
        val value = preview(archive(raw, images))
        android.util.Log.i("IconPackBoundary", "128 assets: preview ${android.os.SystemClock.elapsedRealtime() - started} ms")
        val receipt = imports.confirm(value); val context = repo.capture()
        android.util.Log.i("IconPackBoundary", "128 assets: installed/verified ${android.os.SystemClock.elapsedRealtime() - started} ms")
        assertEquals(128, ready()); assertEquals(128, receipt.verifiedHashes.size)
        assertEquals(images.map(::hash).toSet(), directory(context).list()!!.toSet())
        assertEquals(256, repo.pack(context, id(400), 1)!!.roles.size)
        assertEquals(128, repo.reservations(context).map { it.operationId }.toSet().size)
        assertEquals(257, transfers.jobs(context).size)
        assertTrue(transfers.jobs(context).all { it.state == IconTransferState.PENDING })
        assertEquals(AccountIconSelection(0, null), repo.selection(context))
        assertArrayEquals(images.last(), store.read(context, id(1127), hash(images.last())))
        // All four parents once; each image retains cleanup-directory, real-file, and publication-
        // directory fsync. Cleanup sync is required even on replay after an interrupted unlink.
        // No skipped durability or 128-fold redundant parent syncs on the same held handles.
        assertEquals(4 + 128 * 3, syncs.get())
    } }

    @Test fun packRetryPreflightsAllKnownFinalFilesBeforeCleaningAnyOwnTemporary() = runBlocking<Unit> {
        val value = preview(); imports.confirm(value); val context = repo.capture()
        val intents = repo.reservations(context)
        val temporary = File(directory(context), ".install-${intents.single { it.blob.sha256 == hash(red) }.operationId}.part")
            .apply { writeText("proven temporary") }
        val damaged = File(directory(context), hash(green)).apply { writeText("bad") }
        rejected { imports.confirm(value) }
        assertEquals("proven temporary", temporary.readText()); assertEquals("bad", damaged.readText())
        assertEquals(intents, repo.reservations(context)); assertEquals(3, ready())
        // Repair only the deliberately corrupted test fixture, then verify the exact retry.
        damaged.writeBytes(green); imports.confirm(value)
        assertFalse(temporary.exists()); assertEquals(intents, repo.reservations(context)); assertEquals(3, ready())
    }

    @Test fun missingReadyNamespaceCannotBeRecreatedByAValidPackRetry() = runBlocking<Unit> {
        val value = preview(); imports.confirm(value); val context = repo.capture()
        val choice = store.select(context, 0, IconPackVersion(id(100), 1))
        val path = directory(context); assertTrue(path.deleteRecursively())
        assertEquals("ICON_FILES_MISSING", rejected { imports.confirm(value) }.message)
        assertFalse(path.exists()); assertEquals(3, ready()); assertEquals(choice, repo.selection(context))
    }

    @Test fun wholePackVerificationReauditsRealReadyRowsAfterAllFileReads() = runBlocking<Unit> {
        val value = preview(); imports.confirm(value); val context = repo.capture()
        val original = repo.reservations(context)
        val changed = AtomicInteger()
        configure(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(fd, bytes, offset, length)
                if (changed.incrementAndGet() == 1) db.openHelper.writableDatabase.execSQL(
                    "UPDATE icon_blob_ready SET operationId='${id(999)}' WHERE sha256='${hash(red)}'")
                return count
            }
        })
        assertEquals("ICON_STORE_CORRUPT", rejected { store.verifyPack(context, value.manifest) }.message)
        assertEquals(3, ready())
        // Explicitly repair only the injected test row, not a production recovery bypass.
        db.openHelper.writableDatabase.execSQL("UPDATE icon_blob_ready SET operationId=? WHERE sha256=?",
            arrayOf(original.single { it.blob.sha256 == hash(red) }.operationId, hash(red)))
        configure(); store.verifyPack(context, value.manifest)
        assertEquals(original, repo.reservations(context))
    }

    @Test fun wholePackBlockedReadAllowsAccountSwitchButCannotReturnOldVerification() = runBlocking<Unit> {
        val value = preview(); imports.confirm(value); val original = value.context
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        configure(object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return super.read(fd, bytes, offset, length)
            }
        })
        val result = async(Dispatchers.IO) { rejected { store.verifyPack(original, value.manifest) } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login(owner = id(5)) } }
        finally { release.countDown() }
        assertEquals("ICON_SESSION_CHANGED", result.await().message)
        val current = repo.capture()
        assertTrue(repo.reservations(current).isEmpty()); assertNull(repo.pack(current, id(100), 1))
        assertEquals(AccountIconSelection(0, null), repo.selection(current)); assertFalse(directory(current).exists())
        assertEquals(3, ready()) // Original namespace retained, not exposed to the replacement account.
        login(); configure(); store.verifyPack(repo.capture(), value.manifest); assertEquals(3, ready())
    }

    @Test fun invalidArchiveAndProviderIoFailureCloseOnceWithoutAnyPartialPreviewWrite() = runBlocking<Unit> {
        var closed = 0
        rejected { imports.preview { object : ByteArrayInputStream(byteArrayOf(0)) {
            override fun close() { closed++; super.close() }
        } } }; assertEquals(1, closed); assertEmpty()
        val failure = IOException("synthetic provider")
        assertSame(failure, rejected { imports.preview { object : ByteArrayInputStream(archive()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw failure
            override fun close() { closed++; super.close() }
        } } }); assertEquals(2, closed); assertEmpty()
    }

    @Test fun readOnlyMayPreviewButCannotConfirmEvenAnAlreadyReadyPack() = runBlocking<Unit> {
        imports.confirm(preview()); val intents = repo.reservations(repo.capture())
        login(readOnly = true); val value = preview()
        assertEquals("ICON_DECLARATION_DENIED", rejected { imports.confirm(value) }.message)
        assertEquals(intents, repo.reservations(repo.capture())); assertEquals(3, ready())
        assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
    }

    @Test fun missingAccountCannotOpenProviderOrReturnUnboundPreview() = runBlocking<Unit> {
        sessions.exclusive { tokens.clearTokens() }; var opened = false
        rejected { imports.preview { opened = true; ByteArrayInputStream(archive()) } }; assertFalse(opened)
        assertEquals(0, ready()); assertFalse(File(parent, "account-icons-v1").exists())
    }

    @Test fun previewCannotBeConfirmedAfterAuthenticationReplicaOrPermissionChanges() = runBlocking<Unit> {
        for (change in listOf<suspend () -> Unit>({ login() }, { login(owner = id(5)) },
            { login(server = id(6)) }, { login(epoch = id(7)) }, { login(readOnly = true) })) {
            login(); val value = preview(); change(); rejected { imports.confirm(value) }
            assertEquals(0, ready()); assertFalse(File(parent, "account-icons-v1").exists())
        }
    }

    @Test fun sameGenerationTokenRefreshKeepsFrozenPreviewValid() = runBlocking<Unit> {
        val value = preview()
        sessions.exclusive {
            assertTrue(tokens.saveRefreshedTokens(requireNotNull(tokens.authenticationSnapshot()),
                "synthetic-refreshed", "synthetic-refreshed-refresh", "member", id(1), false))
        }
        imports.confirm(value); assertEquals(3, ready())
    }

    @Test fun reauthenticationDuringSourceReadClosesSourceAndRejectsOldPreview() = runBlocking<Unit> {
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val bytes = archive()
        val result = async(Dispatchers.IO) { rejected {
            imports.preview {
                object : ByteArrayInputStream(bytes) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reached.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.read(b, off, len)
                    }
                    override fun close() { closed.incrementAndGet(); super.close() }
                }
            }
        } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login() } }
        finally { release.countDown() }
        assertEquals("ICON_SESSION_CHANGED", result.await().message); assertEquals(1, closed.get()); assertEmpty()
    }

    @Test fun accountSwitchDuringPublicationRejectsOldInstallAndFreshImportResumesOriginalNamespace() = runBlocking<Unit> {
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        configure(object : IconFileIo() {
            override fun rename(source: String, target: String) { super.rename(source, target); reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        })
        val value = preview(); val original = value.context
        val result = async(Dispatchers.IO) { rejected { imports.confirm(value) } }
        try { assertTrue(reached.await(5, TimeUnit.SECONDS)); withTimeout(2_000) { login(owner = id(5)) } }
        finally { release.countDown() }
        assertNotNull(result.await()); assertEquals(0, ready()); assertEmpty()
        assertArrayEquals(red, File(directory(original), hash(red)).readBytes())
        login(); val originalIntents = repo.reservations(repo.capture()); configure(); imports.confirm(preview())
        assertEquals(originalIntents, repo.reservations(repo.capture())); assertEquals(3, ready())
    }

    @Test fun cancellingBlockedPublicationWaitsForIoAndRetryPreservesOriginalIntent() = runBlocking<Unit> {
        val reached = CountDownLatch(1); val release = CountDownLatch(1)
        configure(object : IconFileIo() {
            override fun rename(source: String, target: String) { super.rename(source, target); reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        })
        val value = preview(); val result = async(Dispatchers.IO) { imports.confirm(value) }
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS)); result.cancel(); assertFalse(result.isCompleted)
            assertEquals(0, ready())
        } finally { release.countDown() }
        result.join(); assertTrue(result.isCancelled)
        val intents = repo.reservations(repo.capture()); assertEquals(3, intents.size)
        val jobs = transfers.jobs(repo.capture()); assertEquals(8, jobs.size)
        configure(); imports.confirm(value); assertEquals(intents, repo.reservations(repo.capture())); assertEquals(3, ready())
        assertEquals(jobs, transfers.jobs(repo.capture()))
    }

    @Test fun concurrentConfirmationsReturnSameLocalReceiptAndNeverChooseStyle() = runBlocking<Unit> {
        val value = preview()
        val secondDb = AccountIconDatabase.open(app)
        try {
            val secondRepo = AccountIconRepository(secondDb, tokens, sessions)
            val secondStore = AccountIconStore(secondRepo, AccountIconFiles(parent))
            val second = AccountIconImport(secondRepo, secondStore, AccountIconTransfers(secondDb, secondRepo, secondStore))
            val first = async(Dispatchers.IO) { imports.confirm(value) }; val other = async(Dispatchers.IO) { second.confirm(value) }
            assertEquals(first.await(), other.await()); assertEquals(3, ready())
            assertEquals(3, repo.reservations(repo.capture()).size); assertEquals(3, directory(repo.capture()).list()!!.size)
            assertEquals(AccountIconSelection(0, null), repo.selection(repo.capture()))
            assertEquals(8, transfers.jobs(repo.capture()).size)
            assertEquals(transfers.jobs(repo.capture()), second.transfers.jobs(secondRepo.capture()))
        } finally { secondDb.close() }
    }
}
