package com.dayforge.data.appearance

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconRecoveryDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var tokens: TokenManager
    private lateinit var database: AccountIconDatabase
    private lateinit var metadata: AccountIconRepository
    private lateinit var store: AccountIconStore
    private lateinit var transfers: AccountIconTransfers
    private val sessions = AccountSessionCoordinator()
    private val json = Json { encodeDefaults = true }
    private val svg = """<svg width="1" height="1"><rect width="1" height="1" fill="#ff0000"/></svg>""".toByteArray()
    private fun id(n: Int) = "ab000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private fun blob(bytes: ByteArray = svg) = IconBlob(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }, bytes.size, "image/svg+xml", 1, 1)
    private fun asset(n: Int = 10) = IconAsset(id(n), "icon$n", "general", "template", blob(), blob())
    private fun pack() = IconPack("dayforge.icon-pack", 1, id(20), 1, "style", listOf(asset()),
        linkedMapOf("metric.test" to id(10), "habit.test" to id(10)), id(10))
    private fun configure() {
        metadata = AccountIconRepository(database, tokens, sessions)
        store = AccountIconStore(metadata, AccountIconFiles(parent))
        transfers = AccountIconTransfers(database, metadata, store)
    }
    private fun reader() = AccountIconRecoveryDatabase(app, database, metadata, transfers)
    private suspend fun login(owner: String = id(1), server: String = id(2), epoch: String = id(3),
        device: String = id(4), revision: Int = 1, editing: Boolean = true) = sessions.exclusive {
        tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
        tokens.saveServerIdentity(server, epoch)
        tokens.saveDeviceRegistration(device, if (editing) setOf("sync.read", "structure.write") else setOf("sync.read"), true, revision)
    }
    private fun sql(value: String, vararg args: Any) = database.openHelper.writableDatabase.execSQL(value, args)
    private fun rows(): Map<String, List<List<String?>>> {
        val tables = database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table -> database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        } }
    }
    private fun reopen() { database.close(); database = AccountIconDatabase.open(app); configure() }
    private fun image(): ByteArray {
        database.close()
        val bytes = app.getDatabasePath(AccountIconDatabase.NAME).readBytes()
        database = AccountIconDatabase.open(app); configure(); database.openHelper.writableDatabase
        return bytes
    }
    private suspend fun reserve(): AccountIconContext = metadata.capture().also { metadata.reserveAsset(it, asset()) }
    private suspend fun rejected(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            return error
        }
        error("Operation must be rejected")
    }
    private suspend fun confirm(attempt: IconTransferAttempt) = when (attempt.job.kind) {
        IconTransferKind.DECLARE_ASSET -> transfers.confirmAsset(attempt,
            AssetRecord(attempt.binding, requireNotNull(attempt.asset), listOf("light", "dark")))
        IconTransferKind.DECLARE_PACK -> transfers.confirmPack(attempt, PackDeclaration(attempt.binding, requireNotNull(attempt.pack)))
        IconTransferKind.UPLOAD -> transfers.confirmUpload(attempt, AssetTransferReceipt(attempt.binding, attempt.job.targetId,
            attempt.job.variant, if (attempt.job.variant == "light") requireNotNull(attempt.asset).light else requireNotNull(attempt.asset?.dark)))
        IconTransferKind.DOWNLOAD -> transfers.confirmDownload(attempt)
    }
    private suspend fun complete(): AccountIconContext {
        val context = reserve(); metadata.reservePack(context, pack())
        store.install(context, id(10), blob().sha256, svg)
        store.select(context, 0, IconPackVersion(id(20), 1))
        transfers.enqueuePack(context, IconPackVersion(id(20), 1)); transfers.enqueueDownload(context, id(10), "light")
        while (true) confirm(transfers.prepareNext(context) ?: break)
        assertTrue(transfers.jobs(context).all { it.state == IconTransferState.COMPLETE })
        return context
    }
    @Before fun setup() = runBlocking<Unit> {
        check(app.packageName == "com.dayforge.testbed")
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "icon-db-archive-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") })
        tokens = TokenManager(preferences); database = AccountIconDatabase.open(app); configure(); login()
    }
    @After fun cleanup() = runBlocking<Unit> {
        if (::database.isInitialized) database.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
    }

    @Test fun freshEmptyReferenceAndAnalyzeAreReadOnlyAndAllEightCollectionsRemainExplicit() = runBlocking<Unit> {
        val context = metadata.capture(); database.openHelper.writableDatabase
        val before = image(); val values = preferences.data.first()
        val proof = reader().read(context)
        assertFalse(proof.activationAuthorized); assertFalse(proof.hasUnresolvedTransfers)
        assertEquals(5, proof.contents.getValue("user_version").jsonPrimitive.int)
        assertEquals(8, proof.contents.getValue("tables").jsonObject.size)
        assertTrue(proof.contents.getValue("tables").jsonObject.values.all { it.jsonArray.isEmpty() })
        assertEquals(64, proof.hash.length); reader().revalidate(proof)
        assertArrayEquals(before, image()); assertEquals(values, preferences.data.first())
        sql("ANALYZE")
        val analyzed = image(); reader().read(context)
        assertArrayEquals(analyzed, image())
    }

    @Test fun lazyAndClosedSourceNeverCreatesReopensOrMigratesItsDatabase() = runBlocking<Unit> {
        val context = metadata.capture(); val values = preferences.data.first()
        assertFalse(database.isOpen); assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        assertEquals("ICON_RECOVERY_DATABASE_NOT_OPEN", rejected { reader().read(context) }.message)
        assertFalse(database.isOpen); assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        assertEquals(5, database.openHelper.writableDatabase.version)
        reader().read(context)
        database.close()
        val file = app.getDatabasePath(AccountIconDatabase.NAME); val before = file.readBytes()
        assertEquals("ICON_RECOVERY_DATABASE_NOT_OPEN", rejected { reader().read(context) }.message)
        assertFalse(database.isOpen); assertArrayEquals(before, file.readBytes()); assertEquals(values, preferences.data.first())
    }

    @Test fun completeOriginalRowsAllKindsAndNoPackIntentSurviveColdReopenWithoutWrites() = runBlocking<Unit> {
        val context = complete()
        val before = image(); val values = preferences.data.first()
        val proof = reader().read(context)
        assertFalse(proof.hasUnresolvedTransfers); assertFalse(proof.activationAuthorized)
        assertEquals(setOf("declare_asset", "declare_pack", "upload", "download"), proof.remote.transfers.map { it.kind }.toSet())
        assertEquals(AccountIconSelection(1, IconPackVersion(id(20), 1)), proof.metadata.selection)
        assertEquals(proof.metadata, metadata.recoveryMetadata(context))
        assertEquals(5, proof.contents.getValue("tables").jsonObject.getValue("icon_transfers").jsonArray.size)
        assertArrayEquals(before, image()); assertEquals(values, preferences.data.first()); reader().revalidate(proof)
        store.select(context, 1, null)
        val noPack = reader().read(context)
        assertEquals(AccountIconSelection(2, null), noPack.metadata.selection)
        assertEquals(1, noPack.contents.getValue("tables").jsonObject.getValue("icon_pack_selection").jsonArray.size)
        rejected { reader().revalidate(proof) }
    }

    @Test fun pendingSendingBlockedAndCompletedLocalReceiptAreNotRelabelledOrReplayed() = runBlocking<Unit> {
        val context = reserve(); transfers.enqueueAsset(context, id(10))
        val pending = reader().read(context)
        assertTrue(pending.hasUnresolvedTransfers); assertTrue(pending.remote.transfers.all { it.state == "pending" })
        val attempt = requireNotNull(transfers.prepareNext(context))
        val sending = reader().read(context)
        assertTrue(sending.remote.transfers.any { it.state == "sending" }); assertTrue(sending.hasUnresolvedTransfers)
        transfers.block(attempt, IconTransferFailure.REMOTE_NOT_FOUND)
        val before = rows(); val blocked = reader().read(context)
        assertTrue(blocked.remote.transfers.any { it.state == "blocked" }); assertEquals(before, rows())
        assertTrue(blocked.hasUnresolvedTransfers); assertFalse(blocked.activationAuthorized)
    }

    @Test fun allCompletedKindsRejectWellShapedButUnrelatedConfirmationDigests() = runBlocking<Unit> {
        val context = complete()
        val original = database.transfers().rows(id(1), id(2), id(3))
        for (row in original) {
            sql("UPDATE icon_transfers SET confirmationHash=? WHERE operationId=?", "0".repeat(64), row.operationId)
            val before = rows()
            assertEquals("ICON_RECOVERY_CONFIRMATION_INVALID", rejected { reader().read(context) }.message)
            assertEquals(before, rows())
            sql("UPDATE icon_transfers SET confirmationHash=? WHERE operationId=?", requireNotNull(row.confirmationHash), row.operationId)
        }
        reader().read(context)
    }

    @Test fun historicalDeviceBindingIsKeptAndForgedMaskOrDeviceCannotReuseAReceipt() = runBlocking<Unit> {
        val context = complete(); val declaration = database.transfers().rows(id(1), id(2), id(3)).single { it.kind == "declare_asset" }
        sql("UPDATE icon_transfers SET readyMask=1 WHERE operationId=?", declaration.operationId)
        rejected { reader().read(context) }
        sql("UPDATE icon_transfers SET readyMask=? WHERE operationId=?", declaration.readyMask, declaration.operationId)
        sql("UPDATE icon_transfers SET deviceId=? WHERE operationId=?", id(9), declaration.operationId)
        rejected { reader().read(context) }
        sql("UPDATE icon_transfers SET deviceId=? WHERE operationId=?", requireNotNull(declaration.deviceId), declaration.operationId)
        sessions.exclusive { tokens.saveDeviceRegistration(id(5), setOf("sync.read"), true, 2) }
        val current = metadata.capture(); val proof = reader().read(current)
        assertEquals(id(5), proof.original.deviceId)
        assertTrue(proof.remote.transfers.all { it.deviceId == id(4) })
        rejected { reader().read(context) }
    }

    @Test fun remoteHistoryAndPendingDownloadsAreAuditedWithoutSynthesizingServerAcceptance() = runBlocking<Unit> {
        val context = metadata.capture(); val catalog = AccountIconRemoteCatalog(database, metadata, transfers)
        val entries = listOf(AppearanceCatalogEntry.Asset(1, asset()), AppearanceCatalogEntry.Pack(2, pack()))
        val page = AppearanceCatalogPage(AssetSyncContext(id(2), id(3), id(4)), entries, 2, 2, false)
        catalog.accept(catalog.next(context), page)
        val before = image(); val proof = reader().read(context)
        assertEquals(2, proof.remote.entries.size); assertEquals(2L, proof.remote.state!!.cursor)
        assertTrue(proof.remote.transfers.all { it.state == "pending" && it.kind == "download" })
        assertTrue(proof.hasUnresolvedTransfers); assertArrayEquals(before, image())
        sql("UPDATE icon_catalog_entries SET metadataHash=? WHERE sequence=2", "0".repeat(64))
        val changed = rows(); rejected { reader().read(context) }; assertEquals(changed, rows())
    }

    @Test fun otherAccountServerAndEpochRowsAndPixelsAreNeverExported() = runBlocking<Unit> {
        val first = complete()
        for ((owner, server, epoch) in listOf(Triple(id(5), id(2), id(3)), Triple(id(1), id(6), id(3)), Triple(id(1), id(2), id(7)))) {
            login(owner, server, epoch)
            val current = complete()
            val proof = reader().read(current)
            for (table in proof.contents.getValue("tables").jsonObject.values) for (raw in table.jsonArray) {
                fun field(name: String) = raw.jsonObject.getValue(name).jsonObject.getValue("value").jsonPrimitive.content
                assertEquals(owner, field("accountId")); assertEquals(server, field("serverInstanceId")); assertEquals(epoch, field("syncEpoch"))
            }
            assertEquals(1, proof.metadata.assets.size); assertFalse(proof.hasUnresolvedTransfers)
            rejected { reader().read(first) }
        }
        assertEquals(4, rows().getValue("icon_assets").size)
    }

    @Test fun sameCountMetadataAndOriginalRawWhitespaceChangesInvalidateOldProof() = runBlocking<Unit> {
        val context = reserve(); val proof = reader().read(context)
        sql("UPDATE icon_assets SET metadataJson=? WHERE assetId=?", json.encodeToString(asset().copy(name = "changed")), id(10))
        assertEquals("ICON_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(proof) }.message)
        val changed = reader().read(context)
        val actual = database.icons().assets(id(1), id(2), id(3)).single().metadataJson
        sql("UPDATE icon_assets SET metadataJson=? WHERE assetId=?", " " + actual, id(10))
        assertEquals("ICON_RECOVERY_PREVIEW_CHANGED", rejected { reader().revalidate(changed) }.message)
        assertEquals(changed.metadata, reader().read(context).metadata)
    }

    @Test fun rawIntegerOverflowAndBlobTypedMetadataAreRejectedBeforeRoomCoercion() = runBlocking<Unit> {
        val context = reserve()
        sql("UPDATE icon_blob_reservations SET width=4294967297")
        rejected { reader().read(context) }
        sql("UPDATE icon_blob_reservations SET width=1")
        sql("UPDATE icon_assets SET metadataJson=CAST(metadataJson AS BLOB)")
        rejected { reader().read(context) }
        sql("UPDATE icon_assets SET metadataJson=CAST(metadataJson AS TEXT)")
        reader().read(context)
    }

    @Test fun identityVersionTriggersViewsAndIndexDirectionCannotHideBehindRoomHash() = runBlocking<Unit> {
        val context = reserve(); val identity = database.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        for (mutation in listOf("UPDATE room_master_table SET identity_hash='forged'", "INSERT INTO room_master_table VALUES(17,'extra')")) {
            sql(mutation); rejected { reader().read(context) }
            sql("DELETE FROM room_master_table WHERE id<>42"); sql("UPDATE room_master_table SET identity_hash=? WHERE id=42", identity)
        }
        sql("PRAGMA user_version=4"); rejected { reader().read(context) }; sql("PRAGMA user_version=5")
        val changes = listOf(
            "CREATE TRIGGER foreign_trigger AFTER INSERT ON icon_assets BEGIN SELECT 1; END" to "DROP TRIGGER foreign_trigger",
            "CREATE VIEW foreign_view AS SELECT assetId FROM icon_assets" to "DROP VIEW foreign_view"
        )
        for ((create, remove) in changes) { sql(create); rejected { reader().read(context) }; sql(remove) }
        val index = "index_icon_blob_reservations_accountId_serverInstanceId_syncEpoch_operationId"
        sql("DROP INDEX $index")
        sql("CREATE UNIQUE INDEX $index ON icon_blob_reservations(accountId,serverInstanceId,syncEpoch,operationId DESC)")
        rejected { reader().read(context) }
        sql("DROP INDEX $index")
        sql("CREATE UNIQUE INDEX $index ON icon_blob_reservations(accountId,serverInstanceId,syncEpoch,operationId)")
        reader().read(context)
    }

    @Test fun alteredColumnConstraintAndForeignKeyRowsAreRejectedWithoutRepairs() = runBlocking<Unit> {
        val context = reserve()
        val create = database.openHelper.writableDatabase.query("SELECT sql FROM sqlite_master WHERE name='icon_assets'").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertTrue(create.contains("`metadataJson` TEXT NOT NULL"))
        sql("PRAGMA writable_schema=ON")
        sql("UPDATE sqlite_master SET sql=? WHERE name='icon_assets'", create.replace("`metadataJson` TEXT NOT NULL", "`metadataJson` TEXT"))
        sql("PRAGMA writable_schema=OFF")
        rejected { reader().read(context) }
        sql("PRAGMA writable_schema=ON"); sql("UPDATE sqlite_master SET sql=? WHERE name='icon_assets'", create); sql("PRAGMA writable_schema=OFF")
        database.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath(AccountIconDatabase.NAME).path, null, 0).use {
            it.execSQL("PRAGMA foreign_keys=OFF")
            it.execSQL("INSERT INTO icon_blob_ready VALUES(?,?,?,?,?,?)", arrayOf(id(99), id(2), id(3), blob().sha256, id(98), "svg-v1"))
        }
        database = AccountIconDatabase.open(app); configure()
        val before = rows(); rejected { reader().read(context) }; assertEquals(before, rows())
    }

    @Test fun existingDatabaseMigrationFromFrozenV1IsAdmittedWithActualRoom5Structure() = runBlocking<Unit> {
        assertEquals(5, database.openHelper.writableDatabase.version)
        database.close(); assertTrue(app.deleteDatabase(AccountIconDatabase.NAME))
        val schema = instrumentation.context.assets.open("com.dayforge.data.appearance.AccountIconDatabase/1.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app).name(AccountIconDatabase.NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                        val table = entity.getValue("tableName").jsonPrimitive.content
                        fun statement(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                        db.execSQL(statement(entity.getValue("createSql")))
                        entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(statement(it.jsonObject.getValue("createSql"))) }
                    }
                    schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected migration")
            }).build()).use { assertEquals(1, it.writableDatabase.version) }
        database = AccountIconDatabase.open(app); configure()
        assertEquals(5, database.openHelper.writableDatabase.version)
        val before = image(); reader().read(metadata.capture()); assertArrayEquals(before, image())
    }

    @Test fun readonlyAuthChangesAndImmutableResultsNeverExportCredentials() = runBlocking<Unit> {
        val context = complete(); val proof = reader().read(context)
        assertFalse(proof.contents.toString().contains("synthetic-access")); assertFalse(proof.contents.toString().contains("synthetic-refresh"))
        assertThrows(UnsupportedOperationException::class.java) { (proof.remote.transfers as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (proof.metadata.assets as MutableList).clear() }
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2) }
        rejected { reader().revalidate(proof) }
        val readonly = reader().read(metadata.capture()); assertFalse(readonly.hasUnresolvedTransfers)
        login(); rejected { reader().revalidate(readonly) }
    }

    @Test fun realTcpMaterialProcessorConfirmationsAndFileProofAreCombinedWithoutNewNetworkReads() = runBlocking<Unit> {
        val context = reserve(); metadata.reservePack(context, pack()); store.install(context, id(10), blob().sha256, svg)
        transfers.enqueuePack(context, IconPackVersion(id(20), 1)); transfers.enqueueDownload(context, id(10), "light")
        val binding = AssetSyncContext(id(2), id(3), id(4))
        val preferencesManager = PreferencesManager(preferences)
        val client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(preferencesManager)).addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, preferencesManager, SelectedNetworkTransport())).build()
        try {
            MaterialSocketServer { input, _ ->
                if (input.path.endsWith("/identity")) MaterialSocketServer.Reply(
                    """{"server_instance_id":"${id(2)}","sync_epoch":"${id(3)}","protocol_version":5,"capabilities":["sync.read","structure.write"],"server_time":"2026-10-12T00:00:00Z"}""".toByteArray())
                else {
                    assertEquals("Bearer synthetic-access", input.headers["authorization"])
                    when {
                        input.path.contains("/content/") && input.method == "GET" -> MaterialSocketServer.Reply(svg, type = "image/svg+xml",
                            headers = mapOf("Cache-Control" to "private, no-store", "X-Content-Type-Options" to "nosniff"))
                        input.path.contains("/content/") -> MaterialSocketServer.Reply(json.encodeToString(
                            AssetTransferReceipt(binding, id(10), input.path.substringAfterLast('/'), blob())).toByteArray())
                        input.path.contains("/appearance/packs/") -> MaterialSocketServer.Reply(input.body)
                        else -> MaterialSocketServer.Reply(json.encodeToString(AssetRecord(binding, asset(), listOf("dark", "light"))).toByteArray())
                    }
                }
            }.use { server ->
                preferencesManager.setServerUrl(server.origin.toString())
                val processor = AccountIconTransferProcessor(metadata, store, transfers)
                val batch = processor.run(context, AccountIconHttp(client, tokens, server.origin))
                assertTrue(batch.supported); assertEquals(5, batch.completed); assertFalse(batch.stopped)
                val calls = server.requests.size
                val proof = reader().read(context)
                AccountIconRecoveryFiles(metadata, AccountIconFiles(parent)).inspect(context) { fileProof, access ->
                    assertEquals(proof.metadata, fileProof.metadata)
                    assertArrayEquals(svg, access.read(blob().sha256))
                    reader().revalidate(proof)
                }
                assertFalse(proof.hasUnresolvedTransfers); assertEquals(calls, server.requests.size)
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
