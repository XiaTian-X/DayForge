package com.dayforge.data.appearance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.model.IconBlob
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountIconRepositoryTest {
    @get:Rule val business = PhysicalDatabaseRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private lateinit var parent: File
    private lateinit var scope: CoroutineScope
    private lateinit var tokens: TokenManager
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var database: AccountIconDatabase
    private val sessions = AccountSessionCoordinator()
    private val account = id(1)
    private val server = id(2)
    private val epoch = id(3)
    private val device = id(4)
    private val json = Json { encodeDefaults = true }
    private val theme = stringPreferencesKey("appearance_theme_selection_v1")
    private fun id(value: Int) = "96000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}"
    private fun blob(value: Int = 1, length: Int = 10) = IconBlob(value.toString(16).padStart(64, '0'), length, "image/png", 1, 1)
    private fun asset(value: Int = 10, bytes: IconBlob = blob(), name: String = "icon") =
        IconAsset(id(value), name, "general", "template", bytes, null)
    private fun pack(assets: List<IconAsset> = listOf(asset()), revision: Int = 1) = IconPack(
        "dayforge.icon-pack", 1, id(20), revision, "pack", assets,
        linkedMapOf("habit.test" to assets.first().assetId, "metric.test" to assets.first().assetId), assets.first().assetId
    )
    private fun repository(limits: AccountIconLimits = AccountIconLimits(), coordinator: AccountSessionCoordinator = sessions) =
        AccountIconRepository(database, tokens, coordinator, limits)
    private suspend fun login(owner: String = account, replica: String = server, generation: String = epoch) {
        sessions.exclusive {
            tokens.saveLoginSession("synthetic-access", "synthetic-refresh", "member", owner, false)
            tokens.saveServerIdentity(replica, generation)
            tokens.saveDeviceRegistration(device, setOf("sync.read", "structure.write"), true, 1)
        }
    }
    @Before fun setup() = runBlocking {
        check(app.packageName == "com.dayforge.testbed") { "Isolated testbed required" }
        assertFalse(app.getDatabasePath(AccountIconDatabase.NAME).exists())
        parent = Files.createTempDirectory(app.filesDir.toPath(), "account-icon-test-").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(parent, "auth.preferences_pb") })
        tokens = TokenManager(preferences)
        database = AccountIconDatabase.open(app)
        preferences.edit { it[theme] = "preserve-global-theme" }
        login()
    }
    @After fun cleanup() = runBlocking {
        if (::database.isInitialized) database.close()
        if (::scope.isInitialized) scope.coroutineContext[Job]!!.cancelAndJoin()
        if (::parent.isInitialized) assertTrue(parent.deleteRecursively())
        if (app.packageName == "com.dayforge.testbed") {
            assertTrue(app.deleteDatabase(AccountIconDatabase.NAME) || !app.getDatabasePath(AccountIconDatabase.NAME).exists())
        }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            failed = true
        }
        assertTrue("Operation must fail closed", failed)
    }
    private fun durable(): Map<String, List<List<String?>>> = listOf("icon_assets", "icon_packs", "icon_blob_reservations").associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                val type = cursor.getType(it)
                when (type) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_BLOB -> "$type:" + cursor.getBlob(it).joinToString("") { byte -> "%02x".format(byte) }
                    else -> "$type:" + cursor.getString(it)
                }
            }) }
        }
    }
    private fun reopen() { database.close(); database = AccountIconDatabase.open(app) }

    @Test fun freshSchemaMatchesExportAndSurvivesColdReopenWithoutPreferencesOrBusinessChanges() = runBlocking<Unit> {
        val before = business.database.openHelper.readableDatabase.version
        val repo = repository(); val context = repo.capture(); repo.reservePack(context, pack())
        val saved = durable()
        val schema = instrumentation.context.assets.open("com.dayforge.data.appearance.AccountIconDatabase/2.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject }
        database.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst()); assertEquals(schema.getValue("identityHash").jsonPrimitive.content, it.getString(0))
        }
        assertEquals(2, database.openHelper.readableDatabase.version)
        reopen()
        assertEquals(saved, durable()); assertEquals(pack(), repository().pack(context, id(20), 1))
        assertEquals(before, business.database.openHelper.readableDatabase.version)
        assertEquals("preserve-global-theme", preferences.data.first()[theme])
    }

    @Test fun accountServerAndEpochNamespacesDoNotAuthorizeTheSameHashOrIdentity() = runBlocking<Unit> {
        val repo = repository(); val original = repo.capture(); repo.reserveAsset(original, asset(name = "original"))
        val originalReservation = repo.reservations(original).single()
        for ((owner, replica, generation) in listOf(Triple(id(5), server, epoch), Triple(account, id(6), epoch), Triple(account, server, id(7)))) {
            login(owner, replica, generation)
            val current = repo.capture()
            assertNull(repo.asset(current, id(10))); assertTrue(repo.reservations(current).isEmpty())
            val before = durable(); rejected { repo.reserveAsset(original, asset(11)) }; assertEquals(before, durable())
            repo.reserveAsset(current, asset(name = "different", bytes = blob(length = 20)))
            assertEquals("different", repo.asset(current, id(10))!!.name)
            assertNotEquals(originalReservation.operationId, repo.reservations(current).single().operationId)
        }
        login(); val current = repo.capture()
        assertEquals("original", repo.asset(current, id(10))!!.name)
        assertEquals(originalReservation, repo.reservations(current).single())
    }

    @Test fun deviceRevisionPermissionsAndReauthenticationInvalidateCapturedCallbacks() = runBlocking<Unit> {
        val repo = repository(); var old = repo.capture(); repo.reserveAsset(old, asset())
        suspend fun stale(change: suspend () -> Unit) {
            val before = durable(); sessions.exclusive { change() }
            rejected { repo.asset(old, id(10)) }; rejected { repo.reserveAsset(old, asset(11)) }
            assertEquals(before, durable()); old = repo.capture()
        }
        stale { tokens.saveDeviceRegistration(id(8), setOf("sync.read", "structure.write"), true, 1) }
        stale { tokens.saveDeviceRegistration(id(8), setOf("sync.read", "structure.write"), true, 2) }
        stale { tokens.markStructuralEditingDenied() }
        val before = durable(); rejected { repo.reserveAsset(old, asset(11)) }; assertEquals(before, durable())
        login(); rejected { repo.asset(old, id(10)) }
        assertEquals(asset(), repo.asset(repo.capture(), id(10)))
    }

    @Test fun refreshedCredentialsKeepTheCapturedGenerationUsable() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture()
        val auth = tokens.authenticationSnapshot()!!
        assertTrue(tokens.saveRefreshedTokens(auth, "new-access", "new-refresh", "member", account, false))
        repo.reserveAsset(context, asset()); assertEquals(asset(), repo.asset(context, id(10)))
    }

    @Test fun unknownPermissionsMissingReplicaUnownedAndNonCanonicalIdentitiesFailClosed() = runBlocking<Unit> {
        val repo = repository(); val empty = durable()
        tokens.clearTokens(); rejected { repo.capture() }
        tokens.saveTokens("a", "r", userId = account, isAdmin = false); rejected { repo.capture() }
        tokens.prepareSyncAccount(account); rejected { repo.capture() }
        tokens.saveServerIdentity(server, epoch); tokens.saveSyncDeviceId(device); rejected { repo.capture() }
        tokens.saveDeviceRegistration(device, setOf("facts.append"), false, 1); rejected { repo.capture() }
        tokens.saveDeviceRegistration(device, setOf("sync.read"), false, 0); rejected { repo.capture() }
        tokens.saveDeviceRegistration("NOT-A-UUID", setOf("sync.read"), false, 1); rejected { repo.capture() }
        login()
        tokens.saveServerIdentity("96000000-0000-4000-8000-00000000000A", epoch); rejected { repo.capture() }
        assertEquals(empty, durable())
    }

    @Test fun readOnlyDeviceCanResolveButCannotDeclareOrSpendQuota() = runBlocking<Unit> {
        val repo = repository(); repo.reserveAsset(repo.capture(), asset())
        tokens.saveDeviceRegistration(device, setOf("sync.read", "facts.append"), false, 2)
        val context = repo.capture(); val before = durable()
        assertEquals(asset(), repo.asset(context, id(10)))
        rejected { repo.reservePack(context, pack()) }; assertEquals(before, durable())
    }

    @Test fun emptyRegistrationOrBareDeviceReplacementCannotInheritEarlierIconPermissions() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); repo.reserveAsset(context, asset()); val saved = durable()
        tokens.saveDeviceRegistration(device, emptySet(), false, 2)
        rejected { repo.capture() }; rejected { repo.reservations(context) }
        login(); tokens.saveSyncDeviceId(id(8)); rejected { repo.capture() }
        assertEquals(saved, durable())
        tokens.saveDeviceRegistration(id(8), setOf("sync.read"), false, 3)
        assertEquals(asset(), repo.asset(repo.capture(), id(10)))
    }

    @Test fun exactReplayMapOrderAndConcurrentRepositoriesKeepOneJournalPerScopedHash() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); val value = pack()
        repo.reservePack(context, value); val saved = durable()
        val reversed = value.copy(roles = value.roles.toList().reversed().toMap())
        (1..8).map { async { repository(coordinator = AccountSessionCoordinator()).reservePack(context, reversed) } }.awaitAll()
        assertEquals(saved, durable()); assertEquals(1, repo.reservations(context).size)
        repo.reserveAsset(context, asset(11)); assertEquals(1, repo.reservations(context).size)
        assertEquals(2, durable().getValue("icon_assets").size)
    }

    @Test fun assetPackAndBlobConflictsLeaveAllRowsUnchanged() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); repo.reservePack(context, pack()); val saved = durable()
        rejected { repo.reserveAsset(context, asset(name = "changed")) }
        rejected { repo.reservePack(context, pack().copy(name = "changed")) }
        rejected { repo.reserveAsset(context, asset(11, blob(length = 11))) }
        rejected { repo.reservePack(context, pack(listOf(asset(11, blob(2)), asset(name = "changed")), 2)) }
        assertEquals(saved, durable())
    }

    @Test fun callerCollectionsAndReturnedCollectionsCannotRewriteAnImmutablePack() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture()
        val assets = mutableListOf(asset()); val roles = mutableMapOf("habit.test" to id(10))
        val value = pack().copy(assets = assets, roles = roles)
        repo.reservePack(context, value); assets.clear(); roles.clear()
        val saved = repo.pack(context, id(20), 1)!!
        assertEquals(1, saved.assets.size); assertEquals(id(10), saved.roles["habit.test"])
        rejected { (saved.assets as MutableList<IconAsset>).clear() }
        rejected { (saved.roles as MutableMap<String, String>).clear() }
        assertEquals(saved, repo.pack(context, id(20), 1))
        val before = durable()
        rejected { repo.reservePack(context, value) }
        assertEquals(before, durable())
    }

    @Test fun separatePhysicalConnectionsSerializeImmutableReplayWithoutDuplicateReservations() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture()
        val otherDatabase = AccountIconDatabase.open(app)
        try {
            val other = AccountIconRepository(otherDatabase, tokens, AccountSessionCoordinator())
            listOf(async { repo.reservePack(context, pack()) }, async { other.reservePack(context, pack()) }).awaitAll()
            assertEquals(1, durable().getValue("icon_packs").size)
            assertEquals(1, durable().getValue("icon_assets").size)
            assertEquals(repo.reservations(context), other.reservations(context))
        } finally { otherDatabase.close() }
    }

    @Test fun lateSecondAssetFailureRollsBackTheFirstAssetAndAllBlobReservations() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); val before = durable()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON icon_assets WHEN NEW.assetId='${id(11)}' BEGIN SELECT RAISE(ABORT, 'second asset failure'); END")
        rejected { repo.reservePack(context, pack(listOf(asset(), asset(11, blob(2))))) }
        assertEquals(before, durable())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second")
        repo.reservePack(context, pack(listOf(asset(), asset(11, blob(2)))))
        assertEquals(2, repo.reservations(context).size)
    }

    @Test fun quotasCountDistinctHashesAndAllVersionsAndAllowLoweredLimitReplayOnly() = runBlocking<Unit> {
        val repo = repository(AccountIconLimits(assets = 2, bytes = 10)); val context = repo.capture()
        repo.reservePack(context, pack()); repo.reserveAsset(context, asset(11)); val saved = durable()
        rejected { repo.reserveAsset(context, asset(12)) }
        rejected { repo.reserveAsset(context, asset(12, blob(2))) }
        assertEquals(saved, durable())
        val lowered = repository(AccountIconLimits(assets = 0, bytes = 0, metadataBytes = 0))
        lowered.reservePack(context, pack()); lowered.reserveAsset(context, asset(11))
        rejected { lowered.reservePack(context, pack(revision = 2)) }; assertEquals(saved, durable())
        repo.reservePack(context, pack(revision = 2)) // Only metadata grows; existing over-limit dimensions do not grow.
        assertEquals(2, durable().getValue("icon_packs").size)
    }

    @Test fun utf8MetadataBudgetIncludesAssetsAndEveryPackVersionAtExactBoundary() = runBlocking<Unit> {
        val value = pack(listOf(asset(name = "饮水")))
        val size = json.encodeToString(value.assets.single()).toByteArray().size + json.encodeToString(value).toByteArray().size
        val context = repository().capture()
        rejected { repository(AccountIconLimits(metadataBytes = size.toLong() - 1)).reservePack(context, value) }
        assertTrue(durable().values.all { it.isEmpty() })
        val repo = repository(AccountIconLimits(metadataBytes = size.toLong()))
        repo.reservePack(context, value); val saved = durable()
        repo.reservePack(context, value)
        rejected { repo.reservePack(context, value.copy(revision = 2)) }; assertEquals(saved, durable())
    }

    @Test fun byteQuotaRejectsIndependentGrowthButAllowsSharedLightDarkAndNewPackVersions() = runBlocking<Unit> {
        val repo = repository(AccountIconLimits(bytes = 10)); val context = repo.capture()
        val shared = asset().copy(dark = blob())
        repo.reserveAsset(context, shared); val saved = durable()
        rejected { repo.reserveAsset(context, asset(11, blob(2, length = 1))) }; assertEquals(saved, durable())
        val lowered = repository(AccountIconLimits(bytes = 0))
        lowered.reservePack(context, pack(listOf(shared)))
        assertEquals(1, repo.reservations(context).size)
        assertEquals(1, durable().getValue("icon_packs").size)
    }

    @Test fun queuedStaleCallbackAndCancelledDeclarationNeverWriteAfterAccountSwitch() = runBlocking<Unit> {
        val repo = repository(); val old = repo.capture(); val saved = durable()
        val queued = sessions.exclusive {
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                rejected { repo.reservePack(old, pack()) }
            }
            tokens.saveLoginSession("new-a", "new-r", "member", id(5), false)
            tokens.saveServerIdentity(server, epoch)
            tokens.saveDeviceRegistration(device, setOf("sync.read", "structure.write"), true, 1)
            task
        }
        queued.await(); assertEquals(saved, durable())
        val current = repo.capture()
        val cancelled = sessions.exclusive {
            async(start = CoroutineStart.UNDISPATCHED) { repo.reservePack(current, pack()) }.also { it.cancel() }
        }
        cancelled.cancelAndJoin(); assertTrue(cancelled.isCancelled); assertEquals(saved, durable())
    }

    @Test fun finalPackAbortAndIgnoreRollBackAssetsAndJournalsAndPermitWholeRetry() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture()
        for (action in listOf("ABORT, 'synthetic failure'", "IGNORE")) {
            val before = durable()
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_pack BEFORE INSERT ON icon_packs BEGIN SELECT RAISE($action); END")
            rejected { repo.reservePack(context, pack(listOf(asset(), asset(11, blob(2))))) }
            assertEquals(before, durable())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_pack")
        }
        repo.reservePack(context, pack(listOf(asset(), asset(11, blob(2)))))
        assertEquals(2, repo.reservations(context).size); val saved = durable(); reopen(); assertEquals(saved, durable())
    }

    @Test fun ignoredAssetOrJournalAndRewrittenOperationIdentityNeverCommitPartialInstallation() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture()
        for (table in listOf("icon_assets", "icon_blob_reservations")) {
            val saved = durable()
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER ignore_row BEFORE INSERT ON $table BEGIN SELECT RAISE(IGNORE); END")
            rejected { repo.reserveAsset(context, asset()) }; assertEquals(saved, durable())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_row")
        }
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER rewrite_operation AFTER INSERT ON icon_blob_reservations BEGIN UPDATE icon_blob_reservations SET operationId='${id(99)}'; END")
        rejected { repo.reserveAsset(context, asset()) }; assertTrue(durable().values.all { it.isEmpty() })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER rewrite_operation")
        repo.reserveAsset(context, asset()); assertEquals(1, repo.reservations(context).size)
    }

    @Test fun damagedJsonIdentityDescriptorsAndMissingJournalAreRejectedWithoutRepair() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); repo.reservePack(context, pack())
        val original = json.encodeToString(asset())
        for (bad in listOf("{}", original.replaceFirst("{", "{\"name\":\"duplicate\","),
            json.encodeToString(asset(11)), original.dropLast(1) + ",\"unknown\":true}")) {
            database.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=?", arrayOf(bad))
            val damaged = durable(); rejected { repo.asset(context, id(10)) }; rejected { repo.reservePack(context, pack()) }
            assertEquals(damaged, durable())
        }
        database.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=?", arrayOf(original))
        database.openHelper.writableDatabase.execSQL("UPDATE icon_blob_reservations SET byteLength=11")
        var damaged = durable(); rejected { repo.reservations(context) }; assertEquals(damaged, durable())
        database.openHelper.writableDatabase.execSQL("DELETE FROM icon_blob_reservations")
        damaged = durable(); rejected { repo.reserveAsset(context, asset()) }; assertEquals(damaged, durable())
    }

    @Test fun businessCacheClearLogoutAndFreshLoginRetainButDoNotExposeOldNamespaceJournals() = runBlocking<Unit> {
        val repo = repository(); val old = repo.capture(); repo.reservePack(old, pack()); val saved = durable()
        preferences.edit { it.remove(theme) }
        val themes = DeviceThemeRepository(preferences, ThemeFileRepository(parent), BuiltInThemes(app.assets))
        val selected = themes.initialize().saved
        business.database.clearAllData(); assertEquals(saved, durable())
        tokens.clearTokens(); rejected { repo.reservations(old) }; assertEquals(saved, durable())
        login(id(5)); assertTrue(repo.reservations(repo.capture()).isEmpty())
        login(); assertEquals(1, repo.reservations(repo.capture()).size)
        assertEquals(saved, durable()); assertEquals(selected, themes.savedSelection())
    }

    @Test fun oversizedIntegersFractionalValuesAndBlobTypedMetadataCannotHideBehindRoomCoercion() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); repo.reservePack(context, pack())
        for ((column, original) in listOf("byteLength" to 10, "width" to 1, "height" to 1)) {
            for (wrong in listOf<Number>(4_294_967_296L + original, 1.5)) {
                database.openHelper.writableDatabase.execSQL("UPDATE icon_blob_reservations SET $column=?", arrayOf(wrong))
                val damaged = durable()
                rejected { repo.asset(context, id(10)) }; rejected { repo.reservePack(context, pack()) }
                assertEquals(damaged, durable())
                database.openHelper.writableDatabase.execSQL("UPDATE icon_blob_reservations SET $column=?", arrayOf(original))
            }
        }
        for (wrong in listOf<Number>(4_294_967_297L, 1.5)) {
            database.openHelper.writableDatabase.execSQL("UPDATE icon_packs SET revision=?", arrayOf(wrong))
            val damaged = durable(); rejected { repo.pack(context, id(20), 1) }; assertEquals(damaged, durable())
            database.openHelper.writableDatabase.execSQL("UPDATE icon_packs SET revision=1")
        }
        database.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=CAST(metadataJson AS BLOB)")
        val damaged = durable(); rejected { repo.asset(context, id(10)) }; assertEquals(damaged, durable())
        database.openHelper.writableDatabase.execSQL("UPDATE icon_assets SET metadataJson=CAST(metadataJson AS TEXT)")
        assertEquals(pack(), repo.pack(context, id(20), 1))
    }

    @Test fun unsupportedDatabaseVersionAndForgedSchemaIdentityNeverDestructivelyReset() = runBlocking<Unit> {
        val repo = repository(); val context = repo.capture(); repo.reserveAsset(context, asset())
        val saved = durable(); database.openHelper.writableDatabase.version = 3; reopen()
        rejected { repository().asset(context, id(10)) }
        database.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(AccountIconDatabase.NAME).path, null, 0).use {
            assertEquals(3, it.version); it.version = 2
        }
        database = AccountIconDatabase.open(app); assertEquals(saved, durable())
        database.openHelper.writableDatabase.execSQL("UPDATE room_master_table SET identity_hash='forged'")
        reopen(); rejected { repository().asset(context, id(10)) }
        database.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(AccountIconDatabase.NAME).path, null, 0).use {
            it.rawQuery("SELECT COUNT(*) FROM icon_assets", null).use { rows -> assertTrue(rows.moveToFirst()); assertEquals(1, rows.getInt(0)) }
        }
    }
}
