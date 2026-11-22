package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.history.*
import com.dayforge.data.local.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*

/** Real dedicated primary rows and actual COW allocations; no caller deletion plan. */
abstract class CountCommandNodeGcFixture : NextObjectEditorFixture() {
    internal val native = CountCommandNodeGcNativeSpy()
    init { storage.isolatedBuilder = { native.build(it) } }
    internal val owner get() = requireNotNull(CountTrustRuntime.forDatabase(db))
    internal val sql get() = db.openHelper.writableDatabase
    internal fun collector() = CountCommandNodeCollector(db, tokens, sessions)
    internal fun commands() = CountCommandStore(db, tokens, sessions)
    internal suspend fun prepared(): CountCommandReference {
        tokens.getOrCreateInstallationId(); register()
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val attempt = editor.beginCountAdd(habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val input = attempt.input; editor.releaseUnexecuted(attempt)
        ReplicaBusinessReferenceSchema.read(app)
        return CountCommandReference(input.commandId, input.inputHash, input.namespace.hash, input.namespace.dataset)
    }
    internal suspend fun retainedNoEffect(): CountCommandStore.Reference {
        tokens.getOrCreateInstallationId(); register()
        val summary = CountHistoryReader(db, tokens, sessions).readSummary(habit, com.dayforge.util.DateTimeUtils.today())
        val identity = requireNotNull(summary.actionIdentity)
        assertNull(db.completionDao().getCompletionById(Long.MAX_VALUE))
        val input = CountCommandInputs.explicitTargetAbsent(db, java.util.UUID.randomUUID().toString(), Long.MAX_VALUE, identity)
        val store = commands(); val registered = store.register(input)
        val committed = store.completeCapturedNoEffect(requireNotNull(registered.ticket))
        assertEquals(CountCommandOutcome.Kind.NO_EFFECT, committed.outcome.kind)
        store.markDelivered(registered.reference)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, store.resolve(registered.reference).historical.status.delivery)
        ReplicaBusinessReferenceSchema.read(app)
        return registered.reference
    }
    internal fun reopen() {
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons); creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    internal suspend fun garbage(id: Int): String {
        val bytes = "command-unreachable-$id".toByteArray(); val hash = CountTrustIndex.hash(bytes)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(hash, bytes))
        } }
        return hash
    }
    internal suspend fun image(): Map<String, List<List<Pair<Int, Any?>>>> = db.withTransaction {
        val names = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        names.associateWith { table -> sql.query("SELECT rowid,* FROM main.`$table` ORDER BY rowid").use { c -> buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { i -> c.getType(i) to when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Native type")
            } })
        } } }
    }
    internal suspend fun live(): Set<String> = db.withTransaction { owner.maintenance(db) {
        val rows = owner.schemaSnapshot(); val storage = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use { c -> check(c.moveToFirst()); c.getString(0) }
        val root = CountCommandSeal().authenticate(CountCommandNativeRows(sql) {}.root(), storage, CountCommandPrimarySchema.hash(rows)) { CountCommandRootAnchor.read(sql).hash }.header
        val pending = ArrayDeque<HistoryBlockCodec.Ref>().apply { root.catalog?.let(::addLast) }
        val result = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive(); val ref = pending.removeLast(); assertTrue(result.add(ref.digest))
            val bytes = sql.query("SELECT body FROM count_command_nodes WHERE digest=?", arrayOf(ref.digest)).use { c -> check(c.moveToFirst()); c.getBlob(0) }
            when (val node = HistoryBlockCodec.decode(ref, bytes)) {
                is HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                is HistoryBlockCodec.Leaf -> node.records.forEach { CountCommandRecords.CatalogMember.decode(it.payload.toByteArray()) {} }
            }
        }
        result
    } }
    internal suspend fun next(gc: CountCommandNodeCollector): CountTrustNodeCollector.Report {
        repeat(1024) {
            currentCoroutineContext().ensureActive(); val report = gc.collectSlice()
            if (report.status == CountTrustNodeCollector.Status.BUSY || report.status == CountTrustNodeCollector.Status.DEFERRED &&
                report.reason == "READER_OR_OWNER_PRESENT") kotlinx.coroutines.yield() else return report
        }
        error("Command fixture did not obtain real quiescent admission")
    }
    internal suspend fun untilSweep(gc: CountCommandNodeCollector) {
        repeat(1024) {
            val report = next(gc); assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
            if (report.phase == "SWEEP") return
        }
        error("Command fixture never reached complete MARK")
    }
    internal suspend fun finish(gc: CountCommandNodeCollector = collector()): List<CountTrustNodeCollector.Report> {
        val reports = arrayListOf<CountTrustNodeCollector.Report>()
        repeat(1024) {
            val report = next(gc); reports += report
            assertTrue(report.catalogueRows <= 16384 && report.scannedRows <= 64 && report.graphBytes <= 512 * 1024 &&
                report.deletedNodes <= 32 && report.deletedBytes <= 512 * 1024)
            when (report.status) {
                CountTrustNodeCollector.Status.FINISHED -> return reports
                CountTrustNodeCollector.Status.PROGRESS, CountTrustNodeCollector.Status.RESTARTED -> Unit
                else -> fail("Command collection did not finish: $report")
            }
        }
        error("Command fixture bounded slice count exhausted")
    }
    internal suspend fun assertOnlyOrphansRemoved(before: Map<String, List<List<Pair<Int, Any?>>>>, live: Set<String>) {
        val after = image()
        assertEquals(before - "count_command_nodes", after - "count_command_nodes")
        // Columns are rowid,digest,body; every retained body/type and rowid must be identical.
        assertEquals(before.getValue("count_command_nodes").filter { it[1].second in live }, after.getValue("count_command_nodes"))
        assertEquals(live, live())
    }
}
