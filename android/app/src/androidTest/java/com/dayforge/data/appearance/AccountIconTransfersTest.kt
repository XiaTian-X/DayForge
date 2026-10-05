package com.dayforge.data.appearance

import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real file Room/DataStore/native PNG and syscall store; no HTTP or Keystore claim. */
@RunWith(AndroidJUnit4::class)
class AccountIconTransfersTest {
    @get:Rule val business = PhysicalDatabaseRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions = AccountSessionCoordinator()
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var tokens: TokenManager
    private lateinit var database: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var queue: AccountIconTransfers
    private val theme = stringPreferencesKey("appearance_theme_selection_v1")
    private val json = Json { encodeDefaults = true }
    private val account get() = id(1)
    private val server get() = id(2)
    private val epoch get() = id(3)
    private val device get() = id(4)
    private fun id(value: Int) = "a8300000-0000-4000-8000-${value.toString(16).padStart(12, '0')}"
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try { bitmap.eraseColor(color); ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
        } } finally { bitmap.recycle() }
    }
    private val red by lazy { png(0xffff0000.toInt()) }
    private val blue by lazy { png(0xff0000ff.toInt()) }
    private fun blob(bytes: ByteArray) = IconBlob(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }, bytes.size, "image/png", 1, 1)
    private fun asset(value: Int = 10) = IconAsset(id(value), "icon $value", if (value == 11) "task" else "general",
        "original", blob(red), if (value == 10) blob(blue) else null)
    private fun pack() = IconPack("dayforge.icon-pack", 1, id(20), 1, "style", listOf(asset(), asset(11)),
        linkedMapOf("habit.exercise" to id(10), "task.shopping" to id(11)), id(10))
    private fun configure() {
        metadata = AccountIconRepository(database, tokens, sessions)
        store = AccountIconStore(metadata, AccountIconFiles(directory))
        queue = AccountIconTransfers(database, metadata, store)
    }
    private suspend fun login(owner: String = account, replica: String = server, generation: String = epoch,
        editing: Boolean = true, registeredDevice: String = device, permissionVersion: Int = 1) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(replica, generation)
        tokens.saveDeviceRegistration(registeredDevice, if (editing) setOf("sync.read", "structure.write") else setOf("sync.read"), true, permissionVersion)
    }
    @Before fun setup() = runBlocking {
        check(app.packageName == "com.dayforge.testbed"); assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        directory = Files.createTempDirectory(app.filesDir.toPath(), "icon-transfer-test-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "auth.preferences_pb") })
        tokens = TokenManager(preferences); database = AccountIconDatabase.open(app); configure(); login()
        preferences.edit { it[theme] = "preserve-global-theme" }
        val context = metadata.capture(); metadata.reserveAsset(context, asset()); metadata.reserveAsset(context, asset(11))
    }
    @After fun cleanup() = runBlocking {
        if (::database.isInitialized) database.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::directory.isInitialized) assertTrue(directory.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }
    private fun reopen() { database.close(); database = AccountIconDatabase.open(app); configure() }
    private fun sql(statement: String, vararg args: Any) = database.openHelper.writableDatabase.execSQL(statement, args)
    private fun durable(table: String = "icon_transfers") = database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) {
            when (c.getType(it)) {
                android.database.Cursor.FIELD_TYPE_NULL -> "null"
                android.database.Cursor.FIELD_TYPE_BLOB -> "blob:" + c.getBlob(it).joinToString("") { b -> "%02x".format(b) }
                else -> "${c.getType(it)}:${c.getString(it)}"
            }
        }) }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Must reject") } catch (error: Exception) { if (error is CancellationException) throw error }
    }
    private suspend fun install() {
        val context = metadata.capture(); metadata.reservePack(context, pack())
        store.installPack(context, pack()) { hash -> if (hash == blob(red).sha256) red else blue }
    }
    private suspend fun confirm(attempt: IconTransferAttempt): IconTransferJob = when (attempt.job.kind) {
        IconTransferKind.DECLARE_ASSET -> queue.confirmAsset(attempt, AssetRecord(attempt.binding, requireNotNull(attempt.asset), emptyList()))
        IconTransferKind.DECLARE_PACK -> queue.confirmPack(attempt, PackDeclaration(attempt.binding, requireNotNull(attempt.pack)))
        IconTransferKind.UPLOAD -> queue.confirmUpload(attempt, AssetTransferReceipt(attempt.binding, attempt.job.targetId,
            attempt.job.variant, if (attempt.job.variant == "light") requireNotNull(attempt.asset).light else requireNotNull(attempt.asset?.dark)))
        IconTransferKind.DOWNLOAD -> queue.confirmDownload(attempt)
    }

    @Test fun atomicNewAssetReservationAndIntentBatchRollbackTogetherAndReplayWithoutInstallingBytes() = runBlocking<Unit> {
        val context = metadata.capture()
        val original = listOf("icon_assets", "icon_blob_reservations", "icon_packs", "icon_blob_ready", "icon_pack_selection", "icon_transfers")
            .associateWith(::durable)
        val fresh = asset(30).copy(light = blob(png(0xff00ff00.toInt())))
        val constrained = AccountIconTransfers(database, metadata, store, maximumJobs = 1)
        rejected { constrained.reserveAndEnqueueAsset(context, fresh) }
        assertEquals(original, original.keys.associateWith(::durable)); assertNull(metadata.asset(context, fresh.assetId))
        assertFalse(File(directory, "account-icons-v1").exists())
        val jobs = queue.reserveAndEnqueueAsset(context, fresh)
        assertEquals(2, jobs.size); assertTrue(jobs.all { it.state == IconTransferState.PENDING && it.generation == 0L })
        val intents = metadata.reservations(context)
        assertEquals(fresh, metadata.asset(context, fresh.assetId)); assertEquals(jobs, queue.reserveAndEnqueueAsset(context, fresh))
        reopen(); assertEquals(jobs, queue.reserveAndEnqueueAsset(context, fresh))
        assertEquals(intents, metadata.reservations(context)); assertTrue(durable("icon_blob_ready").isEmpty())
        assertFalse(File(directory, "account-icons-v1").exists())
        assertTrue(business.database.syncOutboxDao().getAll().isEmpty())
        assertEquals("preserve-global-theme", preferences.data.first()[theme])
    }

    @Test fun realLocalReadyDoesNotConfirmServerAndPackBatchDependenciesReopenAndReplayWithoutBusinessChanges() = runBlocking<Unit> {
        install(); val context = metadata.capture(); val choice = metadata.selection(context)
        val outbox = business.database.syncOutboxDao().getAll()
        val core = listOf("icon_assets", "icon_packs", "icon_blob_reservations", "icon_blob_ready", "icon_pack_selection").associateWith(::durable)
        val jobs = queue.enqueuePack(context, IconPackVersion(id(20), 1))
        assertEquals(6, jobs.size); assertTrue(jobs.all { it.state == IconTransferState.PENDING && it.generation == 0L })
        assertEquals(jobs, queue.enqueuePack(context, IconPackVersion(id(20), 1)))
        reopen(); assertEquals(jobs, queue.enqueuePack(context, IconPackVersion(id(20), 1)))
        val declarations = mutableSetOf<String>()
        repeat(6) {
            val attempt = requireNotNull(queue.prepareNext(context))
            if (attempt.job.kind == IconTransferKind.UPLOAD) assertTrue(attempt.job.targetId in declarations)
            if (attempt.job.kind == IconTransferKind.DECLARE_PACK) assertEquals(setOf(id(10), id(11)), declarations)
            if (attempt.job.kind == IconTransferKind.DECLARE_PACK) {
                val before = durable()
                val forged = IconTransferAttempt(attempt.context, attempt.row, attempt.asset,
                    requireNotNull(attempt.pack).copy(name = "wrong pack capture"))
                rejected { queue.confirmPack(forged, PackDeclaration(forged.binding, requireNotNull(forged.pack))) }
                assertEquals(before, durable())
            }
            val complete = confirm(attempt); assertEquals(IconTransferState.COMPLETE, complete.state)
            assertEquals(complete, confirm(attempt))
            rejected { queue.retryCompletedDownload(context, complete.operationId, complete.generation) }
            assertEquals(complete, queue.jobs(context).single { it.operationId == complete.operationId })
            if (attempt.job.kind == IconTransferKind.DECLARE_ASSET) declarations += attempt.job.targetId
        }
        assertNull(queue.prepareNext(context)); assertTrue(queue.jobs(context).all { it.state == IconTransferState.COMPLETE })
        val final = queue.enqueuePack(context, IconPackVersion(id(20), 1)); reopen()
        assertEquals(final, queue.enqueuePack(context, IconPackVersion(id(20), 1)))
        assertEquals(core, core.keys.associateWith(::durable)); assertEquals(choice, metadata.selection(context))
        assertEquals(outbox, business.database.syncOutboxDao().getAll())
        assertEquals("preserve-global-theme", preferences.data.first()[theme])
    }

    @Test fun interruptedClaimReopensSameIdentityWithNewGenerationAndLateConfirmationCannotConsumeIt() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10))
        val old = requireNotNull(queue.prepareNext(context)); assertEquals(IconTransferKind.DECLARE_ASSET, old.job.kind)
        val ids = queue.jobs(context).map { it.operationId }.toSet(); reopen()
        assertEquals(1, queue.recoverInterrupted(context))
        val recovered = queue.jobs(context); rejected { confirm(old) }; rejected { queue.release(old) }
        assertEquals(recovered, queue.jobs(context))
        val current = requireNotNull(queue.prepareNext(context))
        assertEquals(old.job.operationId, current.job.operationId); assertTrue(current.job.generation > old.job.generation)
        confirm(current); assertEquals(ids, queue.jobs(context).map { it.operationId }.toSet())
        assertEquals(0, queue.recoverInterrupted(context))
    }

    @Test fun sameUuidAssetAndPackRemainIndependentWhenAnUnrelatedAssetArrivesDuringPackDeclaration() = runBlocking<Unit> {
        val context = metadata.capture(); metadata.reservePack(context, pack())
        val original = queue.enqueuePack(context, IconPackVersion(id(20), 1))
        var declaration: IconTransferAttempt? = null
        repeat(6) {
            if (declaration == null) {
                val attempt = requireNotNull(queue.prepareNext(context))
                if (attempt.job.kind == IconTransferKind.DECLARE_PACK) declaration = attempt else confirm(attempt)
            }
        }
        val attempt = requireNotNull(declaration)
        val unrelated = asset().copy(assetId = id(20), name = "Independent asset identity")
        metadata.reserveAsset(context, unrelated)
        val complete = confirm(attempt); assertEquals(IconTransferState.COMPLETE, complete.state)
        assertEquals(complete, confirm(attempt)); assertEquals(unrelated, metadata.asset(context, id(20)))
        assertEquals(original.map { it.operationId }.toSet(), queue.jobs(context).map { it.operationId }.toSet())
        reopen(); assertEquals(complete, queue.jobs(context).single { it.operationId == complete.operationId })
        assertEquals(unrelated, metadata.asset(context, id(20)))
    }

    @Test fun responseScopeContentVariantAndMutableReadyListsRejectWithoutChangingSendingIntent() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); val attempt = requireNotNull(queue.prepareNext(context))
        val before = durable()
        for (binding in listOf(attempt.binding.copy(deviceId = id(8)), attempt.binding.copy(serverInstanceId = id(9)), attempt.binding.copy(syncEpoch = id(7))))
            rejected { queue.confirmAsset(attempt, AssetRecord(binding, asset(), emptyList())) }
        rejected { queue.confirmAsset(attempt, AssetRecord(attempt.binding, asset().copy(name = "changed"), emptyList())) }
        val forged = IconTransferAttempt(attempt.context, attempt.row, asset().copy(name = "wrong capture"), attempt.pack)
        rejected { queue.confirmAsset(forged, AssetRecord(forged.binding, requireNotNull(forged.asset), emptyList())) }
        val mutable = mutableListOf("light"); val response = AssetRecord(attempt.binding, asset(), mutable); mutable += "light"
        rejected { queue.confirmAsset(attempt, response) }
        rejected { queue.confirmPack(attempt, PackDeclaration(attempt.binding, pack())) }
        assertEquals(before, durable())
        val complete = queue.confirmAsset(attempt, AssetRecord(attempt.binding, asset(), listOf("light", "dark")))
        assertEquals(complete, queue.confirmAsset(attempt, AssetRecord(attempt.binding, asset(), listOf("dark", "light"))))
        val upload = requireNotNull(queue.prepareNext(context)); assertEquals(IconTransferKind.UPLOAD, upload.job.kind)
        val frozen = durable()
        val correct = if (upload.job.variant == "light") asset().light else requireNotNull(asset().dark)
        rejected { queue.confirmUpload(upload, AssetTransferReceipt(upload.binding, id(11), upload.job.variant, correct)) }
        rejected { queue.confirmUpload(upload, AssetTransferReceipt(upload.binding, id(10), upload.job.variant, correct.copy(width = 2))) }
        rejected { queue.confirmUpload(upload, AssetTransferReceipt(upload.binding, id(10), if (upload.job.variant == "light") "dark" else "light", correct)) }
        val wrongAsset = requireNotNull(upload.asset).copy(name = "wrong upload capture")
        val forgedUpload = IconTransferAttempt(upload.context, upload.row, wrongAsset, upload.pack)
        rejected { queue.confirmUpload(forgedUpload, AssetTransferReceipt(upload.binding, id(10), upload.job.variant, correct)) }
        assertEquals(frozen, durable()); confirm(upload)
    }

    @Test fun readonlyDownloadNeedsActualFileAndExactRepeatedConfirmationCannotHideCorruption() = runBlocking<Unit> {
        install(); val writingContext = metadata.capture(); queue.enqueueAsset(writingContext, id(10))
        login(editing = false); val context = metadata.capture()
        rejected { queue.enqueueAsset(context, id(10)) }
        val job = queue.enqueueDownload(context, id(10), "light")
        val attempt = requireNotNull(queue.prepareNext(context)); assertEquals(job.operationId, attempt.job.operationId)
        assertEquals(IconTransferKind.DOWNLOAD, attempt.job.kind)
        val beforeConfirmation = durable()
        val wrong = IconTransferAttempt(attempt.context, attempt.row, asset(11), attempt.pack)
        rejected { queue.confirmDownload(wrong) }; assertEquals(beforeConfirmation, durable())
        val complete = queue.confirmDownload(attempt); assertEquals(complete, queue.confirmDownload(attempt))
        val ns = context.namespace
        val file = File(directory, "account-icons-v1/${ns.accountId}/${ns.serverInstanceId}/${ns.syncEpoch}/${blob(red).sha256}")
        file.writeBytes(byteArrayOf(0)); val before = durable()
        rejected { queue.confirmDownload(attempt) }
        assertEquals(before, durable()); assertArrayEquals(byteArrayOf(0), file.readBytes())
        assertTrue(queue.jobs(context).filter { it.kind != IconTransferKind.DOWNLOAD }.all { it.state == IconTransferState.PENDING })
        assertNull(queue.prepareNext(context))
        rejected { queue.retryCompletedDownload(context, complete.operationId, complete.generation - 1) }
        assertEquals(before, durable())
        val recheck = queue.retryCompletedDownload(context, complete.operationId, complete.generation)
        assertEquals(complete.operationId, recheck.operationId); assertEquals(IconTransferState.PENDING, recheck.state)
        assertEquals(complete.generation + 1, recheck.generation)
        val current = requireNotNull(queue.prepareNext(context))
        val retrying = durable()
        rejected { queue.confirmDownload(attempt) }; rejected { queue.confirmDownload(current) }
        assertEquals(retrying, durable()); assertArrayEquals(byteArrayOf(0), file.readBytes())
        val blocked = queue.block(current, IconTransferFailure.LOCAL_CONTENT)
        assertEquals(complete.operationId, blocked.operationId); assertEquals(IconTransferState.BLOCKED, blocked.state)
        reopen(); assertEquals(blocked, queue.jobs(metadata.capture()).single { it.operationId == complete.operationId })
    }

    @Test fun readyReceiptWithoutRealBytesCannotFinishAndFailureRetryPreservesIdentityAndBadFile() = runBlocking<Unit> {
        val context = metadata.capture(); val job = queue.enqueueDownload(context, id(10), "light")
        val attempt = requireNotNull(queue.prepareNext(context)); val before = durable()
        rejected { queue.confirmDownload(attempt) }; assertEquals(before, durable())
        val blocked = queue.block(attempt, IconTransferFailure.LOCAL_CONTENT)
        assertEquals(IconTransferState.BLOCKED, blocked.state); assertEquals(IconTransferFailure.LOCAL_CONTENT, blocked.failure)
        rejected { queue.retryBlocked(context, job.operationId, blocked.generation - 1) }
        assertEquals(job.operationId, queue.retryBlocked(context, job.operationId, blocked.generation).operationId)
        val current = requireNotNull(queue.prepareNext(context)); rejected { queue.confirmDownload(current) }
        queue.release(current); install()
        val restored = requireNotNull(queue.prepareNext(context)); assertEquals(job.operationId, restored.job.operationId)
        assertEquals(IconTransferState.COMPLETE, queue.confirmDownload(restored).state)
    }

    @Test fun replicaSwitchReauthenticationDeviceAndCapabilityChangesRejectOldResultsButKeepOriginalJournal() = runBlocking<Unit> {
        val original = metadata.capture(); queue.enqueueAsset(original, id(10)); val attempt = requireNotNull(queue.prepareNext(original))
        val saved = durable()
        for ((owner, replica, generation) in listOf(Triple(id(5), server, epoch), Triple(account, id(6), epoch), Triple(account, server, id(7)))) {
            login(owner, replica, generation)
            rejected { confirm(attempt) }; rejected { queue.jobs(original) }
            val current = metadata.capture(); assertTrue(queue.jobs(current).isEmpty())
            metadata.reserveAsset(current, asset()); queue.enqueueAsset(current, id(10))
            assertTrue(queue.jobs(current).none { it.operationId == attempt.job.operationId })
        }
        login(); rejected { confirm(attempt) }
        val current = metadata.capture()
        assertEquals(saved, run {
            // Compare the exact original namespace rather than all replica rows in durable().
            database.openHelper.readableDatabase.query("SELECT * FROM icon_transfers WHERE accountId=? AND serverInstanceId=? AND syncEpoch=? ORDER BY rowid",
                arrayOf(account, server, epoch)).use { c -> buildList { while (c.moveToNext()) add(List(c.columnCount) {
                    if (c.isNull(it)) "null" else "${c.getType(it)}:${c.getString(it)}"
                }) } }
        })
        assertEquals(1, queue.recoverInterrupted(current)); val fresh = requireNotNull(queue.prepareNext(current))
        login(registeredDevice = id(8), permissionVersion = 2); rejected { confirm(fresh) }
        val beforeRebinding = durable()
        val rebound = IconTransferAttempt(metadata.capture(), fresh.row, fresh.asset, fresh.pack)
        rejected { confirm(rebound) }; rejected { queue.release(rebound) }
        rejected { queue.block(rebound, IconTransferFailure.REMOTE_CAPABILITY) }
        assertEquals(beforeRebinding, durable())
        login(editing = false, registeredDevice = id(8), permissionVersion = 3); rejected { confirm(fresh) }
        assertNull(queue.prepareNext(metadata.capture()))
        assertEquals("preserve-global-theme", preferences.data.first()[theme])
    }

    @Test fun boundedQueueAndReusedOperationIdentityRollbackWholePackWhileLoweredQuotaAllowsReplay() = runBlocking<Unit> {
        val context = metadata.capture(); metadata.reservePack(context, pack())
        val small = AccountIconTransfers(database, metadata, store, maximumJobs = 5)
        rejected { small.enqueuePack(context, IconPackVersion(id(20), 1)) }; assertTrue(queue.jobs(context).isEmpty())
        val reused = AccountIconTransfers(database, metadata, store, operationId = { id(30) })
        rejected { reused.enqueuePack(context, IconPackVersion(id(20), 1)) }; assertTrue(queue.jobs(context).isEmpty())
        val jobs = queue.enqueuePack(context, IconPackVersion(id(20), 1))
        val lowered = AccountIconTransfers(database, metadata, store, maximumJobs = 0)
        assertEquals(jobs, lowered.enqueuePack(context, IconPackVersion(id(20), 1)))
        rejected { lowered.enqueueDownload(context, id(10), "light") }; assertEquals(jobs, queue.jobs(context))
    }

    @Test fun insertAbortIgnoreAndIdentityRewriteCannotLeavePartialIntentBatch() = runBlocking<Unit> {
        val context = metadata.capture(); metadata.reservePack(context, pack())
        val triggers = listOf(
            "CREATE TRIGGER injection BEFORE INSERT ON icon_transfers WHEN NEW.kind='upload' BEGIN SELECT RAISE(ABORT,'injected'); END",
            "CREATE TRIGGER injection BEFORE INSERT ON icon_transfers WHEN NEW.kind='upload' BEGIN SELECT RAISE(IGNORE); END",
            "CREATE TRIGGER injection AFTER INSERT ON icon_transfers BEGIN UPDATE icon_transfers SET operationId='${id(99)}' WHERE operationId=NEW.operationId; END"
        )
        val core = durable("icon_assets")
        for (trigger in triggers) {
            sql(trigger)
            try { rejected { queue.enqueuePack(context, IconPackVersion(id(20), 1)) }; assertTrue(queue.jobs(context).isEmpty()) }
            finally { sql("DROP TRIGGER injection") }
            assertEquals(core, durable("icon_assets"))
        }
        assertEquals(6, queue.enqueuePack(context, IconPackVersion(id(20), 1)).size)
    }

    @Test fun transitionAbortIgnoreRewriteAndMetadataSideEffectRollbackAndExactRetryWorks() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueAsset(context, id(10)); val attempt = requireNotNull(queue.prepareNext(context))
        val before = durable(); val core = durable("icon_assets")
        for (trigger in listOf(
            "CREATE TRIGGER injection BEFORE UPDATE ON icon_transfers BEGIN SELECT RAISE(ABORT,'injected'); END",
            "CREATE TRIGGER injection BEFORE UPDATE ON icon_transfers BEGIN SELECT RAISE(IGNORE); END",
            "CREATE TRIGGER injection AFTER UPDATE ON icon_transfers BEGIN UPDATE icon_transfers SET deviceId=NULL WHERE operationId=NEW.operationId; END",
            "CREATE TRIGGER injection AFTER UPDATE ON icon_transfers BEGIN UPDATE icon_assets SET metadataJson='{}'; END"
        )) {
            sql(trigger)
            try { rejected { confirm(attempt) }; assertEquals(before, durable()); assertEquals(core, durable("icon_assets")) }
            finally { sql("DROP TRIGGER injection") }
        }
        confirm(attempt)
    }

    @Test fun rawSqlTypesValuesAndSemanticMetadataMutationRejectBeforeCoercionWithoutRepair() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueDownload(context, id(10), "light")
        val mutations = listOf(
            "revision=4294967296", "revision=1.5", "generation=-1", "generation=1.5", "generation=CAST(generation AS BLOB)",
            "kind='unknown'", "targetId='not-uuid'", "metadataHash='wrong'", "metadataHash=CAST(metadataHash AS BLOB)",
            "state='complete'", "state='unknown'", "deviceId='${id(9)}'", "failureCode='unknown'", "confirmationHash='pretend'", "readyMask=4"
        )
        val original = durable()
        for (mutation in mutations) {
            try {
                database.withTransaction {
                    sql("UPDATE icon_transfers SET $mutation")
                    val bad = durable()
                    rejected { queue.jobs(context) }; rejected { queue.prepareNext(context) }
                    assertEquals(bad, durable())
                    // Roll back only this deliberately damaged test transaction.
                    throw TestRollback()
                }
                fail("Must roll back")
            } catch (_: TestRollback) { /* Intentional test-only rollback, not a production error. */ }
            assertEquals(original, durable())
        }
        val rawMetadata = database.icons().assets(account, server, epoch).single { it.assetId == id(10) }.metadataJson
        sql("UPDATE icon_assets SET metadataJson=? WHERE assetId=?", json.encodeToString(asset().copy(name = "legally changed")), id(10))
        assertEquals("legally changed", metadata.asset(context, id(10))!!.name)
        val changed = durable("icon_assets")
        rejected { queue.jobs(context) }; assertEquals(changed, durable("icon_assets")); assertEquals(original, durable())
        sql("UPDATE icon_assets SET metadataJson=? WHERE assetId=?", rawMetadata, id(10))
        assertEquals(1, queue.jobs(context).size)
    }

    @Test fun generationHeadroomAndRecoveryExhaustionDoNotMakePartialWrites() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueDownload(context, id(10), "light")
        val op = queue.jobs(context).single().operationId
        sql("UPDATE icon_transfers SET generation=?", Long.MAX_VALUE - 1)
        val before = durable(); rejected { queue.prepareNext(context) }; assertEquals(before, durable())
        sql("UPDATE icon_transfers SET generation=0")
        queue.prepareNext(context); queue.enqueueDownload(context, id(11), "light"); queue.prepareNext(context)
        sql("UPDATE icon_transfers SET generation=? WHERE operationId=?", Long.MAX_VALUE, op)
        val exhausted = durable(); rejected { queue.recoverInterrupted(context) }; assertEquals(exhausted, durable())
    }

    @Test fun concurrentInstancesClaimOnlyOnceAndQueuedCancellationCannotCreateIntents() = runBlocking<Unit> {
        val context = metadata.capture(); queue.enqueueDownload(context, id(10), "light")
        val independent = AccountIconDatabase.open(app)
        try {
            val otherMetadata = AccountIconRepository(independent, tokens, sessions)
            assertThrows(IllegalArgumentException::class.java) { AccountIconTransfers(independent, metadata, store) }
            assertThrows(IllegalArgumentException::class.java) { AccountIconTransfers(database, metadata, AccountIconStore(otherMetadata, AccountIconFiles(directory))) }
            val other = AccountIconTransfers(independent, otherMetadata, AccountIconStore(otherMetadata, AccountIconFiles(directory)))
            val claims = (0 until 8).map { index -> async(Dispatchers.IO) { if (index % 2 == 0) queue.prepareNext(context) else other.prepareNext(context) } }.awaitAll()
            assertEquals(1, claims.count { it != null }); assertEquals(1, queue.jobs(context).count { it.state == IconTransferState.SENDING })
        } finally { independent.close() }
        val before = durable(); val entered = CompletableDeferred<Unit>()
        sessions.exclusive {
            val cancelled = async(Dispatchers.IO) { entered.complete(Unit); queue.enqueueAsset(context, id(11)) }
            entered.await(); cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
        }
        assertEquals(before, durable())
    }

    @Test fun cancellationDuringRealInsertJoinsActualRoomWorkAndRollsBackTheWholeIntentBatch() = runBlocking<Unit> {
        val context = metadata.capture(); metadata.reservePack(context, pack())
        database.close()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        database = Room.databaseBuilder(app, AccountIconDatabase::class.java, AccountIconDatabase.NAME)
            .addMigrations(AccountIconDatabase.MIGRATION_1_2, AccountIconDatabase.MIGRATION_2_3, AccountIconDatabase.MIGRATION_3_4, AccountIconDatabase.MIGRATION_4_5)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("INSERT", ignoreCase = true) && sql.contains("icon_transfers")) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }, Executor { it.run() }).build()
        configure()
        val core = durable("icon_assets")
        val pending = async(Dispatchers.IO) { queue.enqueuePack(context, IconPackVersion(id(20), 1)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); pending.cancel()
        } finally {
            release.countDown(); withTimeout(5000) { pending.join() }
        }
        assertTrue(pending.isCancelled); assertTrue(queue.jobs(context).isEmpty()); assertEquals(core, durable("icon_assets"))
        reopen(); assertTrue(queue.jobs(context).isEmpty()); assertEquals(core, durable("icon_assets"))
        assertEquals(6, queue.enqueuePack(context, IconPackVersion(id(20), 1)).size)
    }

    private class TestRollback : RuntimeException()
}
