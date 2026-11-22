package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.history.*
import com.dayforge.data.local.*
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Actual catalogue queries and actual returned native END only. No result, clock or authority is supplied. */
internal class CountDualMaintenanceNative(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    internal class EndBarrier(val pool: CountNodeCollectionSchedule.Pool) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
    }
    val cataloguePools = CopyOnWriteArrayList<CountNodeCollectionSchedule.Pool>()
    @Volatile var barrier: EndBarrier? = null
    private val held = ThreadLocal<EndBarrier?>()
    private val monitor = Any()
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val wrappers = IdentityHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(wrappers) {
            wrappers[raw] ?: object : SupportSQLiteDatabase by raw {
                private fun observe(sql: String) {
                    if (!sql.startsWith("SELECT rowid,CASE WHEN typeof(digest)='text' AND length(digest)=64 THEN digest ELSE NULL END,")) return
                    val pool = when {
                        sql.contains("typeof(body),length(body) FROM main.count_trust_nodes ") -> CountNodeCollectionSchedule.Pool.COUNT
                        sql.contains("typeof(body),length(body) FROM main.count_command_nodes ") -> CountNodeCollectionSchedule.Pool.COMMAND
                        else -> return
                    }
                    cataloguePools += pool
                    synchronized(monitor) {
                        barrier?.takeIf { it.pool == pool }?.let { held.set(it); barrier = null }
                    }
                }
                override fun query(query: String): Cursor { observe(query); return raw.query(query) }
                override fun query(query: String, bindArgs: Array<out Any?>): Cursor { observe(query); return raw.query(query, bindArgs) }
                override fun query(query: SupportSQLiteQuery): Cursor { observe(query.sql); return raw.query(query) }
                override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                    observe(query.sql); return raw.query(query, cancellationSignal)
                }
                override fun endTransaction() {
                    raw.endTransaction()
                    if (!raw.inTransaction()) held.get()?.let { gate ->
                        held.remove(); gate.entered.countDown()
                        try { check(gate.release.await(5, TimeUnit.SECONDS)) { "DUAL_GC_TEST_END_RELEASE_TIMEOUT" } }
                        finally { gate.exited.countDown() }
                    }
                }
            }.also { wrappers[raw] = it }
        }
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
        }
        val helper = delegate.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    fun build(context: Context): HabitDatabase {
        val owner = CountTrustOpenHelperFactory(context.applicationContext, this)
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration,
                ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration,
                NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration,
                NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
            .openHelperFactory(owner).addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, owner) }
    }
}

/** Reuses real command registration/result and native fault fixture; adds the real COUNT root. */
abstract class CountDualNodeMaintenanceFixture : CountCommandNodeGcFixture() {
    internal val dualNative = CountDualMaintenanceNative(native)
    init { storage.isolatedBuilder = { dualNative.build(it) } }
    internal suspend fun readyBoth(): CountCommandStore.Reference {
        val reference = retainedNoEffect()
        CountHistoryReader(db, tokens, sessions).readSummary(habit)
        db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { assertNotNull(it) }
        }
        ReplicaBusinessReferenceSchema.read(app)
        return reference
    }
    internal suspend fun countGarbage(id: Int): String {
        val bytes = "dual-count-orphan-$id".toByteArray(); val digest = CountTrustIndex.hash(bytes)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any?>(digest, bytes))
        } }
        return digest
    }
    internal suspend fun countLive(): Set<String> = db.withTransaction { owner.maintenance(db) {
        val header = requireNotNull(CountTrustSeal.read(sql)); val caller = currentCoroutineContext()
        CountTrustIndex(sql, header.binding, header.root) { caller.ensureActive() }.verifyContents()
        val live = linkedSetOf<String>(); val seen = hashSetOf<String>()
        val pending = ArrayDeque<HistoryBlockCodec.Ref>().apply { add(header.root) }
        while (pending.isNotEmpty()) {
            caller.ensureActive(); val ref = pending.removeLast(); live.add(ref.digest)
            if (!seen.add(ref.digest)) continue
            val bytes = sql.query("SELECT body FROM main.count_trust_nodes WHERE digest=?", arrayOf<Any?>(ref.digest)).use { c -> check(c.moveToFirst()); c.getBlob(0) }
            when (val node = HistoryBlockCodec.decode(ref, bytes)) {
                is HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                is HistoryBlockCodec.Leaf -> node.records.forEach { record ->
                    Json.parseToJsonElement(record.payload.toByteArray().toString(Charsets.UTF_8)).jsonObject.getValue("chunks")
                        .jsonArray.forEach { live.add(it.jsonPrimitive.content) }
                }
            }
        }
        live
    } }
    internal suspend fun schemaImage(): List<List<String?>> = db.withTransaction { owner.maintenance(db) { owner.schemaSnapshot() } }
    internal suspend fun assertBothOnlyOrphansRemoved(before: Map<String, List<List<Pair<Int, Any?>>>>,
        countReachable: Set<String>, commandReachable: Set<String>, schema: List<List<String?>>) {
        val after = image(); val pools = setOf("count_trust_nodes", "count_command_nodes")
        assertEquals(before.filterKeys { it !in pools }, after.filterKeys { it !in pools })
        for ((table, live) in listOf("count_trust_nodes" to countReachable, "count_command_nodes" to commandReachable)) {
            assertEquals(before.getValue(table).filter { it[1].second in live }, after.getValue(table))
        }
        assertEquals(countReachable, countLive()); assertEquals(commandReachable, live()); assertEquals(schema, schemaImage())
    }
    internal suspend fun finishSchedule(schedule: CountNodeCollectionSchedule,
        count: CountTrustNodeCollector, command: CountCommandNodeCollector): List<CountNodeCollectionSchedule.Slice> = withTimeout(45_000) {
        val slices = mutableListOf<CountNodeCollectionSchedule.Slice>()
        while (count.requested || command.requested) {
            val slice = schedule.next(); slices += slice
            slice.report?.let { report ->
                assertTrue("Only legitimate progress or transient admission is allowed: $report", report.status in setOf(
                    CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.FINISHED,
                    CountTrustNodeCollector.Status.RESTARTED, CountTrustNodeCollector.Status.BUSY) ||
                    report.status == CountTrustNodeCollector.Status.DEFERRED && report.reason == "READER_OR_OWNER_PRESENT")
                assertTrue(report.scannedRows <= 64 && report.deletedNodes <= 32 && report.deletedBytes <= 512 * 1024)
            }
            if (count.requested || command.requested) delay(slice.pauseMillis)
        }
        slices
    }
}
