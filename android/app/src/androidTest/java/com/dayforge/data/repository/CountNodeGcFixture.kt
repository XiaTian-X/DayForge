package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.history.*
import com.dayforge.data.local.*
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.After

/** Real production root and closed-world native evidence; no deletion plan is supplied to GC. */
abstract class CountNodeGcFixture : NextCoreRequestFixture() {
    internal val cipherAlias = "dayforge.test.node-gc.${java.util.UUID.randomUUID()}"
    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher(cipherAlias)
    @After fun removeOwnedCipherKey() {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(cipherAlias) }
    }
    internal val spy = CountNodeGcNativeSpy()
    init { storage.isolatedBuilder = { spy.build(it) } }
    internal fun collector() = CountTrustNodeCollector(db, tokens, sessions)
    internal fun nativeAssertions() = CountConsumerReadFixtureAssertions(db, tokens, sessions)
    internal val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    internal val sql get() = db.openHelper.writableDatabase
    internal suspend fun ready() {
        register()
        CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today())
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            assertNotNull(it)
        } }
        // Safety wait tests isolate admission from the independently timed reference template.
        ReplicaBusinessReferenceSchema.read(app)
    }
    /** v20 retains a real committed command result rather than injecting an unauthenticated
     * row into the new mandatory EMPTY catalog. Original COUNT-only GC assertions stay full.
     */
    internal suspend fun retainedCommandResult(): String {
        tokens.getOrCreateInstallationId()
        val identity = requireNotNull(CountHistoryReader(db, tokens, sessions).readSummary(habit, DateTimeUtils.today()).actionIdentity)
        assertNull(db.completionDao().getCompletionById(Long.MAX_VALUE))
        val input = CountCommandInputs.explicitTargetAbsent(db, java.util.UUID.randomUUID().toString(), Long.MAX_VALUE, identity)
        val store = CountCommandStore(db, tokens, sessions)
        val registered = store.register(input)
        val committed = store.completeCapturedNoEffect(requireNotNull(registered.ticket))
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, committed.outcome.kind)
        return committed.outcome.text()
    }
    internal suspend fun orphan(number: Int, rowid: Long? = null, bytes: ByteArray = "unreachable-$number".toByteArray()): String {
        val digest = CountTrustIndex.hash(bytes)
        db.withTransaction { owner.maintenance(db) {
            if (rowid == null) sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf<Any>(digest, bytes))
            else sql.execSQL("INSERT INTO count_trust_nodes(rowid,digest,body) VALUES(?,?,?)", arrayOf<Any>(rowid, digest, bytes))
        } }
        return digest
    }
    internal suspend fun rootValue(name: String, value: JsonElement) = db.withTransaction { owner.maintenance(db) {
        val before = requireNotNull(CountTrustSeal.read(sql))
        val caller = currentCoroutineContext()
        val index = CountTrustIndex(sql, before.binding, before.root) { caller.ensureActive() }
        index.putAll(mapOf(name to value))
        CountTrustSeal.store(sql, CountTrustSeal().prepare(before.binding, before.schemaHash, index.root,
            before.revision + 1, before.generation, before.validator))
    } }
    internal suspend fun reachable(): Set<String> = db.withTransaction { owner.maintenance(db) {
        val head = CountTrustSeal.read(sql) ?: return@maintenance emptySet()
        val caller = currentCoroutineContext()
        CountTrustIndex(sql, head.binding, head.root) { caller.ensureActive() }.verifyContents()
        val result = linkedSetOf<String>(); val visitedTrees = hashSetOf<String>()
        val pending = ArrayDeque<HistoryBlockCodec.Ref>(); pending.addLast(head.root)
        while (pending.isNotEmpty()) {
            val ref = pending.removeLast(); result.add(ref.digest)
            if (!visitedTrees.add(ref.digest)) continue
            val bytes = sql.query("SELECT body FROM main.count_trust_nodes WHERE digest=?", arrayOf<Any>(ref.digest)).use {
                assertTrue(it.moveToFirst()); it.getBlob(0)
            }
            when (val node = HistoryBlockCodec.decode(ref, bytes)) {
                is HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                is HistoryBlockCodec.Leaf -> node.records.forEach { record ->
                    Json.parseToJsonElement(record.payload.toByteArray().toString(Charsets.UTF_8)).jsonObject
                        .getValue("chunks").jsonArray.forEach { result.add(it.jsonPrimitive.content) }
                }
            }
        }
        result
    } }
    internal suspend fun assertOnlyUnreachableRemoved(before: ConsumerNativeImage, live: Set<String>) {
        val expected = before + ("count_trust_nodes" to before.getValue("count_trust_nodes").filter {
            it.getValue("digest").value in live
        })
        assertEquals(expected, nativeAssertions().capture())
        assertEquals(live, reachable())
    }
    internal suspend fun compactNativeProof(): Map<String, List<List<Pair<Int, Any?>>>> = db.withTransaction {
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        tables.associateWith { table -> sql.query("SELECT rowid,* FROM main.`$table` ORDER BY rowid").use { c -> buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { column ->
                val type = c.getType(column)
                type to when (type) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(column)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(column).toRawBits()
                    android.database.Cursor.FIELD_TYPE_STRING -> c.getString(column)
                    android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(column).let { it.size to CountTrustIndex.hash(it) }
                    else -> error("Unknown native type")
                }
            })
        } } }
    }

    /** Only known transient admission is retried. Unknown layout/auth/corruption is returned
     * to the original assertion, never accepted as completed collection or fault coverage. */
    internal suspend fun nextSlice(gc: CountTrustNodeCollector): CountTrustNodeCollector.Report {
        repeat(10_000) {
            val report = gc.collectSlice()
            if (report.status == CountTrustNodeCollector.Status.BUSY ||
                report.status == CountTrustNodeCollector.Status.DEFERRED && report.reason == "READER_OR_OWNER_PRESENT") {
                kotlinx.coroutines.yield()
            } else return report
        }
        error("GC fixture never obtained quiescent admission")
    }

    internal suspend fun finish(gc: CountTrustNodeCollector = collector()): List<CountTrustNodeCollector.Report> {
        val reports = arrayListOf<CountTrustNodeCollector.Report>()
        repeat(10_000) {
            currentCoroutineContext().ensureActive()
            val report = gc.collectSlice(); reports += report
            when (report.status) {
                CountTrustNodeCollector.Status.FINISHED -> {
                    assertTrue(reports.all { it.catalogueRows <= 16384 && it.scannedRows <= 64 &&
                        it.graphBytes <= 512 * 1024 && it.deletedNodes <= 32 && it.deletedBytes <= 512 * 1024 })
                    android.util.Log.i("CountNodeGcSafety", "slices=${reports.size} cohorts=${reports.maxOf { it.cohort }} " +
                        "account_end_sum_ns=${reports.sumOf { it.accountAndEndNanos }} " +
                        "account_end_max_ns=${reports.maxOf { it.accountAndEndNanos }} " +
                        "parse_sum_ns=${reports.sumOf { it.parseNanos }} " +
                        "graph_nodes=${reports.sumOf { it.walkedNodes }} graph_bytes=${reports.sumOf { it.graphBytes }} " +
                        "deleted_nodes=${reports.sumOf { it.deletedNodes }} deleted_bytes=${reports.sumOf { it.deletedBytes }}")
                    return reports
                }
                CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.RESTARTED -> Unit
                CountTrustNodeCollector.Status.BUSY -> kotlinx.coroutines.yield()
                CountTrustNodeCollector.Status.DEFERRED -> {
                    assertEquals("Only a real transient reader may defer this owned fixture", "READER_OR_OWNER_PRESENT", report.reason)
                    kotlinx.coroutines.yield()
                }
                else -> fail("GC did not make supported progress: $report")
            }
        }
        error("GC fixture did not finish its bounded data")
    }
    internal suspend fun untilMark(gc: CountTrustNodeCollector) {
        repeat(10_000) {
            val report = nextSlice(gc)
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
            if (report.phase == "MARK") return
        }
        error("GC fixture did not finish cataloguing")
    }
    internal suspend fun untilSweep(gc: CountTrustNodeCollector): CountTrustNodeCollector.Report {
        repeat(10_000) {
            val report = nextSlice(gc)
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
            if (report.phase == "SWEEP") return report
        }
        error("GC fixture did not finish marking")
    }
}
