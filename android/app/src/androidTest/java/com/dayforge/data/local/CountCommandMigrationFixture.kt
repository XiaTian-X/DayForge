package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.repository.CountTrustSeal
import com.dayforge.data.repository.ReplicaStorageProof
import com.dayforge.data.repository.syncPayloadHash
import com.dayforge.domain.service.AccountSessionCoordinator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Old source files are made from committed KSP18/19 assets and original frozen migrations.
 * Current20 always opens through the real production provider. No generated schema is mocked.
 */
abstract class CountCommandMigrationFixture internal constructor(
    private val cleanupFence: PhysicalDatabaseRule.CleanupFence?,
) {
    constructor() : this(null)
    protected lateinit var context: Context
    protected var room: HabitDatabase? = null
    protected lateinit var tokens: TokenManager
    protected lateinit var commandMigrationPreferences: PreferencesManager
    protected val sessions = AccountSessionCoordinator()
    private lateinit var authScope: CoroutineScope
    private lateinit var authDirectory: File
    private var admitted = false
    private var evaluationStarted = false
    private var afterRequested = false
    private var cleanupFailure: Throwable? = null
    private var previousHosts: List<DatabaseRuntimeHost> = emptyList()
    internal var migrationCleanupCompleted = false
        private set
    internal val migrationAuthenticationJob: Job get() = authScope.coroutineContext[Job]!!
    internal val migrationAuthenticationDirectory: File get() = authDirectory

    /** Outside JUnit's original Before -> timed method -> After statement. After alone
     * cannot know that FailOnTimeout returned while the original method still owns work.
     * Any unconsumed failure preserves the scene; this rule never reruns a failed close.
     * Widget isolation stays outside this rule (its existing order is -2).
     */
    @get:Rule(order = -1) val migrationLifecycle: TestRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                check(!evaluationStarted) { "MIGRATION_FIXTURE_ALREADY_EVALUATED" }
                evaluationStarted = true
                try {
                    base.evaluate()
                    if (admitted) {
                        check(afterRequested) { "MIGRATION_FIXTURE_AFTER_NOT_REACHED" }
                        runBlocking { finishMigrationCase() }
                    }
                } catch (error: Throwable) {
                    if (admitted) preserve(error)
                    throw error
                }
            }
        }
    }

    private fun requireCleanupClear() {
        // A private negative-case fence cannot bypass a real failed outer disposal.
        PhysicalDatabaseRule.requireProcessCleanupClear()
        cleanupFence?.requireClear()
        cleanupFailure?.let { throw it }
    }
    private fun preserve(error: Throwable) {
        if (cleanupFailure == null) cleanupFailure = error
        if (cleanupFence == null) PhysicalDatabaseRule.preserveProcessCleanupFailure(error)
        else cleanupFence.preserve(error)
    }
    protected fun id(n: Int) = "aa420000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    @Before fun prepareMigrationCase() = runBlocking<Unit> {
        requireCleanupClear() // Before context, Provider, DB or preferences file side effects.
        check(evaluationStarted && !admitted) { "MIGRATION_FIXTURE_NOT_ADMITTED_BY_RULE" }
        context = ApplicationProvider.getApplicationContext()
        check(context.packageName == "com.dayforge.testbed")
        previousHosts = DatabaseRuntimeHosts.snapshot()
        check(previousHosts.all { it.lifecycleEnded }) { "A prior test left an active Host; refusing to delete its database" }
        admitted = true
        HabitDatabaseProvider.clearInstanceForTesting()
        check(context.deleteDatabase("habit_database") || !context.getDatabasePath("habit_database").exists())
        authDirectory = Files.createTempDirectory(context.filesDir.toPath(), "command-migration-").toFile()
        authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = authScope, produceFile = { File(authDirectory, "auth.preferences_pb") })
        tokens = TokenManager(data, PlaintextTokenCipher)
        commandMigrationPreferences = PreferencesManager(data, tokens = tokens)
        tokens.saveLoginSession("synthetic-migration-access", "synthetic-migration-refresh", "member", id(1), false)
        tokens.saveServerIdentity(id(2), id(3)); tokens.getOrCreateInstallationId()
        tokens.saveDeviceRegistration(id(4), setOf("sync.read", "structure.write", "facts.append", "timer.control"), true, 1)
    }
    @After fun closeMigrationCase() {
        // JUnit runs this even when Before was refused. Without admission we own nothing.
        if (admitted) afterRequested = true
    }
    private suspend fun finishMigrationCase() {
        requireCleanupClear()
        // No detached method owner is manufactured. Reaching this point means the original
        // method and all Afters returned without timeout, interruption or any other failure.
        val created = DatabaseRuntimeHosts.snapshot().filter { candidate -> previousHosts.none { it === candidate } }
        created.forEach { shutdownTestHost(it) } // Original per-Host 30s, never the actor's 5s.
        room?.let { database ->
            check(CountTrustRuntime.forDatabase(database)?.hasUnknownGraphBoundary() != true) {
                "MIGRATION_FIXTURE_NATIVE_END_UNKNOWN"
            }
        }
        if (::authScope.isInitialized) {
            val actor = migrationAuthenticationJob
            withTimeout(5_000) { actor.cancelAndJoin() }
            check(actor.isCompleted) { "MIGRATION_FIXTURE_AUTH_ACTOR_NOT_DRAINED" }
        }
        requireCleanupClear()
        room?.let { closeMigrationDatabase(it) }
        HabitDatabaseProvider.clearInstanceForTesting()
        check(context.deleteDatabase("habit_database") || !context.getDatabasePath("habit_database").exists())
        if (::authDirectory.isInitialized) check(authDirectory.deleteRecursively())
        migrationCleanupCompleted = true
    }
    private suspend fun closeMigrationDatabase(database: HabitDatabase) {
        val runtime = CountTrustRuntime.forDatabase(database)
        check(runtime?.hasUnknownGraphBoundary() != true) { "MIGRATION_FIXTURE_NATIVE_END_UNKNOWN" }
        if (runtime?.isGraphCloseConfirmed(database) == true) return
        if (runtime != null && database.isOpen) {
            val proof = withTimeout(5_000) {
                var sealed: CountTrustOpenHelperFactory.GraphDrain.Ready? = null
                while (sealed == null) {
                    check(!runtime.hasUnknownGraphBoundary()) { "MIGRATION_FIXTURE_NATIVE_END_UNKNOWN" }
                    when (val observed = runtime.captureGraphDrain(database)) {
                        is CountTrustOpenHelperFactory.GraphDrain.Ready ->
                            if (runtime.trySealGraphClose(database, observed.witness, knownOnly = true)) sealed = observed
                        CountTrustOpenHelperFactory.GraphDrain.Unknown -> error("MIGRATION_FIXTURE_NATIVE_END_UNKNOWN")
                        CountTrustOpenHelperFactory.GraphDrain.Busy -> Unit
                    }
                    if (sealed == null) delay(10)
                }
                requireNotNull(sealed)
            }
            database.close()
            runtime.requireGraphClosed(database, proof.witness)
        } else {
            // Original schema/migration cases deliberately use unowned Room or close a
            // failed-open graph in the method. Never open SQL to manufacture a new witness.
            // The method succeeded; both SAME owner closes must now return without error.
            database.close()
            database.openHelper.close()
            check(!database.isOpen) { "MIGRATION_FIXTURE_DATABASE_NOT_CLOSED" }
            check(runtime?.hasUnknownGraphBoundary() != true) { "MIGRATION_FIXTURE_NATIVE_END_UNKNOWN" }
        }
    }
    protected fun openCurrent(): HabitDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        return HabitDatabaseProvider.getInstance(context).also { room = it; check(it.openHelper.writableDatabase.isOpen) }
    }
    // The existing Boolean API retains its original default UTF8 / true UTF16LE meaning.
    // Endianness is a closed fixture choice, never caller-provided SQL or a production hook.
    private enum class SourceEncoding(val pragma: String?) {
        DEFAULT(null), UTF16_LE("UTF-16le"), UTF16_BE("UTF-16be")
    }
    internal fun source(version: Int = 19, utf16: Boolean = false, body: (SupportSQLiteDatabase) -> Unit = {}): ReplicaStorageProof =
        sourceWithEncoding(version, if (utf16) SourceEncoding.UTF16_LE else SourceEncoding.DEFAULT, body)
    internal fun sourceUtf16Be(version: Int = 19, body: (SupportSQLiteDatabase) -> Unit = {}): ReplicaStorageProof =
        sourceWithEncoding(version, SourceEncoding.UTF16_BE, body)
    private fun sourceWithEncoding(version: Int, encoding: SourceEncoding, body: (SupportSQLiteDatabase) -> Unit): ReplicaStorageProof {
        check(version in setOf(18, 19)); val schema = asset(version)
        val expectedEncoding = encoding.pragma
        if (expectedEncoding != null) {
            val file = context.getDatabasePath("habit_database")
            check(!file.exists()) { "UTF16_SOURCE_REQUIRES_A_NEW_ISOLATED_FILE" }
            val directory = requireNotNull(file.parentFile)
            check(directory.isDirectory || directory.mkdirs())
            // Framework's first localized Android metadata can fix UTF8 before onConfigure.
            // Set encoding before any table, then persist it in this new testbed file.
            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { empty ->
                empty.execSQL("PRAGMA encoding='$expectedEncoding'")
                empty.execSQL("CREATE TABLE android_metadata(locale TEXT)")
                empty.query("android_metadata", arrayOf("locale"), null, null, null, null, null).use { check(!it.moveToFirst()) }
                empty.rawQuery("PRAGMA encoding", null).use {
                    check(it.moveToFirst() && it.getString(0) == expectedEncoding && !it.moveToNext())
                }
            }
        }
        var captured: ReplicaStorageProof? = null
        FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    if (expectedEncoding != null) db.query("PRAGMA encoding").use {
                        check(it.moveToFirst() && it.getString(0) == expectedEncoding && !it.moveToNext())
                    }
                }
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createAsset(db, schema)
                    if (version == 19) CountTrustSchema.install(db)
                    SyncSchemaCallback.onOpen(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected source upgrade")
            }).build()).use { helper ->
            val db = helper.writableDatabase
            if (expectedEncoding != null) db.query("PRAGMA encoding").use {
                check(it.moveToFirst() && it.getString(0) == expectedEncoding && !it.moveToNext())
            }
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO next_transmissions VALUES('sync_operation','old-request',1,5,'account','server','epoch','device','wire',X'007FFF')")
                db.execSQL("INSERT INTO next_rejections VALUES('sync_operation','old-request','origin','transmission','result','unchanged')")
                body(db); captured = snapshot(db); db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return requireNotNull(captured)
    }
    internal fun oldCountRoot(sql: SupportSQLiteDatabase, access: LocalCoreWriteAccess, dirty: Boolean,
        validator: String = CountTrustSeal.VALIDATOR): CountTrustSeal.Header {
        val control = sql.query("SELECT storageId,generation FROM count_trust_control WHERE id=1").use {
            check(it.moveToFirst()); it.getString(0) to it.getLong(1)
        }
        val binding = CountTrustSeal.binding(access, control.first, validator)
        val retained = CountTrustIndex.build(sql, binding, mapOf("migration:retained" to JsonPrimitive("家庭/retained native proof"))) {}
        val hash = schemaHash(sql)
        val root = CountTrustSeal().prepare(binding, hash, retained, 7, control.second, validator)
        CountTrustSeal.store(sql, root)
        sql.execSQL("UPDATE count_trust_control SET dirty=? WHERE id=1", arrayOf(if (dirty) 1 else 0))
        return root
    }
    internal suspend fun access() = requireNotNull(tokens.localCoreWriteAccess())
    companion object {
        internal fun asset(version: Int): JsonObject = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dayforge.data.local.HabitDatabase/$version.json").bufferedReader().use { it.readText() })
            .jsonObject.getValue("database").jsonObject
        private fun createAsset(db: SupportSQLiteDatabase, schema: JsonObject) {
            schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
                val name = entity.getValue("tableName").jsonPrimitive.content
                fun ddl(value: JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
                db.execSQL(ddl(entity.getValue("createSql")))
                entity["indices"]?.jsonArray.orEmpty().forEach { db.execSQL(ddl(it.jsonObject.getValue("createSql"))) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        }
        /** Historical test helper only: execute unchanged18→19 before the current20 provider. */
        internal fun upgradeFrozen18To19(context: Context, after: (SupportSQLiteDatabase) -> Unit) {
            FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("habit_database")
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing18 source")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        check(oldVersion == 18 && newVersion == 19)
                        CountTrustMigration.migrate(db)
                        db.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42", arrayOf(asset(19).getValue("identityHash").jsonPrimitive.content))
                        after(db)
                    }
                }).build()).use { check(it.writableDatabase.version == 19) }
        }
        internal fun schemaHash(sql: SupportSQLiteDatabase): String = sql.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            val rows = buildJsonArray { while (c.moveToNext()) add(buildJsonArray { for (i in 0..3) add(if (c.isNull(i)) JsonNull else JsonPrimitive(c.getString(i))) }) }
            CountTrustIndex.hash(rows.toString().toByteArray(Charsets.UTF_8))
        }
        /** Exact typed five-field schema and every native main cell, matching original proof framing. */
        internal fun snapshot(sql: SupportSQLiteDatabase): ReplicaStorageProof {
            fun row(c: Cursor): JsonObject = buildJsonObject { for (i in 0 until c.columnCount) put(c.getColumnName(i), buildJsonObject {
                put("sqlite_type", c.getType(i)); put("value", when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i).toRawBits().toString(16))
                    Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
                    Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(c.getBlob(i).joinToString("") { "%02x".format(it.toInt() and 255) })
                    else -> error("Unknown native cell")
                })
            }) }
            val schema = sql.query("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name").use { c -> buildJsonArray { while (c.moveToNext()) add(row(c)) } }
            val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val body = buildJsonObject {
                put("user_version", sql.version); put("schema", schema)
                put("tables", buildJsonObject { for (table in names) {
                    require(table.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                    put(table, sql.query("SELECT rowid AS __recovery_rowid,* FROM main.`$table` ORDER BY rowid").use { c -> buildJsonArray { while (c.moveToNext()) add(row(c)) } })
                } })
            }
            return ReplicaStorageProof(body, syncPayloadHash(body.toString()))
        }
    }
}
