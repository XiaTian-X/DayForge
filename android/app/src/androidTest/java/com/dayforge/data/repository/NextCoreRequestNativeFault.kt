package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.domain.model.isContractUuid
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

/** Fixed native faults for CoreRequest tests, below the unchanged controlled factory.
 * Only TEMP schema is installed; no SQL, callback, or native handle leaves this helper. */
internal class NextCoreRequestNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Actor(val statement: String, val table: String) {
        ORIGIN("INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)", "next_request_origins"),
        JOURNAL("INSERT OR ABORT INTO `next_transmissions` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`wireHash`,`wireBytes`) VALUES (?,?,?,?,?,?,?,?,?,?)", "next_transmissions"),
        RECEIPT("INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)", "next_acceptances"),
        QUEUE("DELETE FROM sync_outbox WHERE id = ?", "sync_outbox")
    }
    enum class Kind(val actor: Actor) {
        ORIGIN_ABORT(Actor.ORIGIN), JOURNAL_ABORT(Actor.JOURNAL), JOURNAL_END(Actor.JOURNAL),
        RECEIPT_ABORT(Actor.RECEIPT), RECEIPT_IGNORE(Actor.RECEIPT), RECEIPT_HASH(Actor.RECEIPT),
        RECEIPT_QUEUE_ERROR(Actor.RECEIPT), QUEUE_ABORT(Actor.QUEUE), QUEUE_IGNORE(Actor.QUEUE),
        QUEUE_WIRE_HASH(Actor.QUEUE), QUEUE_DELETE_RECEIPT(Actor.QUEUE),
        QUEUE_RECEIPT_HASH(Actor.QUEUE), QUEUE_SHADOW_HASH(Actor.QUEUE), ACCEPTANCE_END(Actor.RECEIPT);
        val abort get() = this in setOf(ORIGIN_ABORT, JOURNAL_ABORT, RECEIPT_ABORT, QUEUE_ABORT)
        val ignored get() = this in setOf(RECEIPT_IGNORE, QUEUE_IGNORE)
        val deferred get() = this in setOf(JOURNAL_END, ACCEPTANCE_END)
    }
    data class Targets(val requestId: String? = null, val queueId: Long? = null,
        val metricId: Long? = null, val metricUuid: String? = null, val accountId: String? = null,
        val expectedDate: Long? = null, val expectedValue: Double? = null,
        val expectedNote: String? = null, val expectedUnit: String? = null)
    private data class Cell(val type: Int, val value: Any?)
    private data class Rows(val columns: List<String>, val rows: List<List<Cell>>)
    private data class MainImage(val version: Int, val schemaVersion: Long, val schema: Rows, val tables: Map<String, Rows>)
    private class Attempt(val kind: Kind, val target: Targets, val native: SupportSQLiteDatabase,
        val before: MainImage, val priorFacts: Long?) {
        val armed = AtomicBoolean(true)
        val ownerInvalidated = AtomicBoolean()
        val actors = AtomicInteger()
        val returned = AtomicInteger()
        val marks = AtomicInteger()
        val endAttempts = AtomicInteger()
        val ended = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
        val endObservationFailure = AtomicReference<Throwable?>()
        val retryExpected = AtomicBoolean()
        val retryActors = AtomicInteger()
        val retryReturned = AtomicInteger()
        val retryMarks = AtomicInteger()
        val retryEnds = AtomicInteger()
        @Volatile var effectVerified = false
        @Volatile var rollbackEqual = false
        @Volatile var reported = false
    }
    private data class Frame(val attempt: Attempt, val retry: Boolean)
    enum class ProducerCalibration { BEFORE_TARGET, AFTER_TARGET }
    private class ProducerObservation(val native: SupportSQLiteDatabase, val before: MainImage) {
        val ownerInvalidated = AtomicBoolean()
        val sequence = AtomicInteger()
        val target = AtomicReference<ProducerFrame?>()
        val beforeCalibration = AtomicReference<ProducerFrame?>()
        val afterCalibration = AtomicReference<ProducerFrame?>()
        val observationFailure = AtomicReference<Throwable?>()
        @Volatile var reported = false
    }
    private class ProducerFrame(val observation: ProducerObservation, val beginSequence: Int) {
        var depth = 1 // Only this real writer thread mutates its nested depth.
        val outerBegins = AtomicInteger()
        val callbacks = AtomicInteger()
        val outerMarks = AtomicInteger()
        val outerEnds = AtomicInteger()
        @Volatile var claimSequence = 0
        @Volatile var endSequence = 0
        @Volatile var endReturned = false
        @Volatile var rollbackEqual = false
        @Volatile var calibration: ProducerCalibration? = null
        @Volatile var calibrationImage: MainImage? = null
        @Volatile var calibrationUnchanged = false
    }
    private val producerObservation = AtomicReference<ProducerObservation?>()
    private val producerFrame = ThreadLocal<ProducerFrame?>()
    private val installNext = AtomicBoolean()
    private val installations = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Frame?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    // close() can run on a different thread from Room's writer. Invalidation revokes
    // the old frame immediately; its owning thread drops the ThreadLocal on next access.
    private fun activeWriter(): Frame? {
        val frame = writer.get() ?: return null
        if (frame.attempt.ownerInvalidated.get()) { writer.remove(); return null }
        return frame
    }
    private fun activeProducerFrame(): ProducerFrame? {
        val frame = producerFrame.get() ?: return null
        if (frame.observation.ownerInvalidated.get()) { producerFrame.remove(); return null }
        return frame
    }
    private fun closeOwner(raw: SupportSQLiteDatabase?, action: () -> Unit) {
        selected.get()?.takeIf { it.native === raw }?.ownerInvalidated?.set(true)
        producerObservation.get()?.takeIf { it.native === raw }?.ownerInvalidated?.set(true)
        // A failed close stays invalidated but is never evidence of rollback or closure.
        action()
    }

    fun beginProducerObservation() {
        val raw = requireNotNull(opened)
        check(!raw.inTransaction() && selected.get() == null)
        check(producerObservation.get()?.reported != false)
        producerObservation.set(ProducerObservation(raw, image(raw)))
    }
    fun observeProducerCallback() {
        val o = requireNotNull(producerObservation.get())
        try {
            val frame = requireNotNull(activeProducerFrame())
            check(!o.ownerInvalidated.get() && frame.observation === o && o.native === opened && o.native.inTransaction())
            check(frame.depth > 0 && frame.outerEnds.get() == 0 && frame.calibration == null)
            check(o.target.compareAndSet(null, frame))
            check(frame.callbacks.incrementAndGet() == 1)
            frame.claimSequence = o.sequence.incrementAndGet()
            check(scalar(o.native, "SELECT count(*) FROM main.metric_logs") > 0L)
        } catch (observation: Throwable) { o.observationFailure.compareAndSet(null, observation) }
    }
    fun observeProducerCalibration(phase: ProducerCalibration) {
        val o = requireNotNull(producerObservation.get())
        try {
            val frame = requireNotNull(activeProducerFrame())
            check(!o.ownerInvalidated.get() && frame.observation === o && o.native === opened && o.native.inTransaction())
            check(frame.depth > 0 && frame.outerEnds.get() == 0 && frame.callbacks.get() == 0 && frame.calibration == null)
            check(frame !== o.target.get())
            when (phase) {
                ProducerCalibration.BEFORE_TARGET -> {
                    check(o.target.get() == null && o.beforeCalibration.compareAndSet(null, frame))
                }
                ProducerCalibration.AFTER_TARGET -> {
                    val target = requireNotNull(o.target.get())
                    check(target.endReturned && target.outerEnds.get() == 1 && target.endSequence > 0)
                    check(o.afterCalibration.compareAndSet(null, frame))
                }
            }
            frame.calibration = phase
            // Only the two explicitly selected read-only calibrations take a MAIN image;
            // unrelated Room background transactions are not subjected to a full scan.
            frame.calibrationImage = image(o.native)
        } catch (observation: Throwable) { o.observationFailure.compareAndSet(null, observation) }
    }
    fun requireProducerCalibrations() {
        val o = requireNotNull(producerObservation.get())
        assertNull("Native observation never replaces a background transaction outcome", o.observationFailure.get())
        val target = requireNotNull(o.target.get())
        val before = requireNotNull(o.beforeCalibration.get())
        val after = requireNotNull(o.afterCalibration.get())
        assertNotSame(before, target); assertNotSame(after, target); assertNotSame(before, after)
        assertEquals(ProducerCalibration.BEFORE_TARGET, before.calibration)
        assertEquals(ProducerCalibration.AFTER_TARGET, after.calibration)
        for (frame in listOf(before, after)) {
            assertSame(o, frame.observation); assertEquals(1, frame.outerBegins.get())
            assertEquals(0, frame.callbacks.get()); assertEquals(0, frame.claimSequence)
            assertEquals(1, frame.outerMarks.get()); assertEquals(1, frame.outerEnds.get())
            assertTrue(frame.endReturned); assertNotNull(frame.calibrationImage); assertTrue(frame.calibrationUnchanged)
        }
        assertTrue(before.beginSequence < before.endSequence && before.endSequence < target.beginSequence)
        assertTrue(target.beginSequence < target.claimSequence && target.claimSequence < target.endSequence)
        assertTrue(target.endSequence < after.beginSequence && after.beginSequence < after.endSequence)
    }
    fun requireProducerCommitted() {
        val o = requireNotNull(producerObservation.get())
        val frame = requireNotNull(o.target.get())
        assertFalse(o.ownerInvalidated.get()); assertSame(o.native, opened); assertFalse(o.native.inTransaction())
        assertSame(o, frame.observation); assertEquals(1, frame.outerBegins.get())
        assertTrue(frame.beginSequence > 0); assertEquals(1, frame.callbacks.get())
        assertEquals("Only the callback's actual outer native transaction was marked successful", 1, frame.outerMarks.get())
        assertEquals(1, frame.outerEnds.get()); assertTrue(frame.endReturned); assertNull(o.observationFailure.get())
        assertTrue(frame.beginSequence < frame.claimSequence && frame.claimSequence < frame.endSequence)
        assertFalse("The original callback committed real MAIN changes", o.before == image(o.native))
        o.reported = true
    }
    fun requireProducerRolledBack() {
        val o = requireNotNull(producerObservation.get())
        val frame = requireNotNull(o.target.get())
        assertFalse(o.ownerInvalidated.get()); assertSame(o.native, opened); assertFalse(o.native.inTransaction())
        assertSame(o, frame.observation); assertEquals(1, frame.outerBegins.get())
        assertTrue(frame.beginSequence > 0); assertEquals(1, frame.callbacks.get())
        assertEquals(0, frame.outerMarks.get()); assertEquals(1, frame.outerEnds.get()); assertTrue(frame.endReturned)
        assertTrue(frame.beginSequence < frame.claimSequence && frame.claimSequence < frame.endSequence)
        assertNull(o.observationFailure.get()); assertTrue(frame.rollbackEqual)
        assertEquals(o.before, image(o.native)); o.reported = true
    }
    private fun begin(raw: SupportSQLiteDatabase, action: () -> Unit) {
        val pending = producerObservation.get()?.takeUnless { it.reported || it.ownerInvalidated.get() }
            ?: activeProducerFrame()?.observation
        if (pending == null) { action(); return }
        var outer: Boolean? = null
        try { outer = !raw.inTransaction() }
        catch (observation: Throwable) { pending.observationFailure.compareAndSet(null, observation) }
        action() // Original native BEGIN/listener; its Throwable always escapes unchanged.
        try {
            val frame = activeProducerFrame()
            check(pending.native === raw && raw === opened && raw.inTransaction())
            if (requireNotNull(outer)) {
                check(frame == null)
                val started = ProducerFrame(pending, pending.sequence.incrementAndGet())
                check(started.outerBegins.incrementAndGet() == 1)
                producerFrame.set(started)
            } else if (frame != null) {
                // An outer frame from a prior observation window still owns its own
                // nested depth. It cannot be relabelled as this window's target.
                frame.depth++
            }
            // If an unrelated transaction began before this window, there is no
            // witnessed outer BEGIN: leave it unclaimed and transparently finish it.
        } catch (observation: Throwable) { pending.observationFailure.compareAndSet(null, observation) }
    }
    private fun markProducer(raw: SupportSQLiteDatabase) {
        val frame = activeProducerFrame()?.takeIf { it.observation.native === raw } ?: return
        try {
            check(frame.depth > 0 && frame.outerEnds.get() == 0)
            if (frame.depth == 1) check(frame.outerMarks.incrementAndGet() == 1)
        } catch (observation: Throwable) { frame.observation.observationFailure.compareAndSet(null, observation) }
    }
    private fun endProducer(raw: SupportSQLiteDatabase, returned: Boolean) {
        val frame = activeProducerFrame()?.takeIf { it.observation.native === raw } ?: return
        val o = frame.observation
        try {
            check(frame.depth > 0)
            frame.depth--
            if (frame.depth == 0) {
                try {
                    check(!raw.inTransaction())
                    check(frame.outerEnds.incrementAndGet() == 1)
                    frame.endReturned = returned
                    frame.endSequence = o.sequence.incrementAndGet()
                    if (o.target.get() === frame) frame.rollbackEqual = image(raw) == o.before
                    frame.calibrationImage?.let { frame.calibrationUnchanged = image(raw) == it }
                } finally { producerFrame.remove() }
            }
        } catch (observation: Throwable) { o.observationFailure.compareAndSet(null, observation) }
    }

    fun installOnNextOpen() {
        check(selected.get()?.let { it.reported && (it.rollbackEqual || it.kind.deferred) } != false)
        check(installNext.compareAndSet(false, true))
    }
    fun arm(kind: Kind, target: Targets) {
        val raw = requireNotNull(opened)
        check(installations.get() > 0 && !installNext.get() && !raw.inTransaction())
        check(selected.get()?.let { it.reported && it.rollbackEqual && !it.retryExpected.get() } != false)
        if (kind == Kind.ORIGIN_ABORT) {
            require(target.requestId == null && target.queueId == null && requireNotNull(target.metricId) > 0 &&
                isContractUuid(requireNotNull(target.metricUuid)) && isContractUuid(requireNotNull(target.accountId)))
            requireNotNull(target.expectedDate); require(requireNotNull(target.expectedValue).isFinite())
            requireNotNull(target.expectedNote); requireNotNull(target.expectedUnit)
        } else require(isContractUuid(requireNotNull(target.requestId)) && requireNotNull(target.queueId) > 0)
        val next = writerDiagnostic(raw) {
            check(scalar(raw, "SELECT enabled FROM temp.core_request_fault_gate WHERE id=1") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_hits") == 0L)
            Attempt(kind, target, raw, imageInTransaction(raw), if (kind == Kind.ORIGIN_ABORT)
                scalar(raw, "SELECT count(*) FROM main.metric_logs WHERE metricId=?", arrayOf(target.metricId)) else null)
        }
        // Publish the preimage/zero-state observation only after diagnostic END returned.
        selected.set(next)
    }
    fun requireTriggered(failure: Throwable) {
        val a = requireNotNull(selected.get())
        assertFalse(a.armed.get()); assertEquals("Exact bound original DAO actor", 1, a.actors.get())
        assertEquals(if (a.kind.abort) 0 else 1, a.returned.get())
        assertTrue("Real SQLite fault/effect occurred", a.effectVerified)
        assertNull("Observation cannot replace native END", a.endObservationFailure.get())
        if (a.kind.abort || a.kind.deferred) {
            val original = requireNotNull(a.originalConstraint.get())
            assertTrue("Same native SQLite exception propagated", generateSequence(failure) { it.cause }.any { it === original })
            if (a.kind.deferred) {
                assertTrue(original.message.orEmpty().contains("FOREIGN KEY constraint failed"))
                assertTrue(a.marks.get() > 0); assertTrue(a.endAttempts.get() > 0)
            } else { assertTrue(original.message.orEmpty().contains(tag(a.kind))); assertEquals(0, a.marks.get()) }
        } else {
            assertNull(a.originalConstraint.get())
            val early = setOf("COMMAND_PRIMARY_READ_EXPIRED", "COMMAND_PRIMARY_BINDING_CHANGED",
                "COMMAND_OLD_COUNT_SCHEMA_INVALID", "COUNT_TRUST_SCHEMA_CHANGED", "EVIDENCE_READ_SESSION_WROTE_DATA")
            assertFalse("Early schema/lifetime refusal is not this counterexample",
                generateSequence(failure) { it.cause }.any { it.message in early })
        }
        if (!a.kind.deferred) { assertEquals(1, a.ended.get()); assertTrue(a.rollbackEqual) }
        a.reported = true
    }
    fun requireWarmRollback() {
        val a = requireNotNull(selected.get())
        assertFalse(a.kind.deferred); assertTrue(a.reported); assertFalse(a.ownerInvalidated.get()); assertSame(a.native, opened)
        assertEquals(1, a.ended.get()); assertTrue(a.rollbackEqual); assertFalse(a.native.inTransaction())
        writerDiagnostic(a.native) {
            assertEquals(a.before, imageInTransaction(a.native))
            assertEquals(0L, scalar(a.native, "SELECT enabled FROM temp.core_request_fault_gate WHERE id=1"))
            assertEquals(0L, scalar(a.native, "SELECT count(*) FROM temp.core_request_fault_hits"))
        }
    }
    fun expectWarmRetry() {
        requireWarmRollback()
        check(requireNotNull(selected.get()).retryExpected.compareAndSet(false, true))
    }
    fun requireWarmRetryCommitted() {
        val a = requireNotNull(selected.get())
        assertFalse(a.ownerInvalidated.get()); assertSame(a.native, opened); assertFalse(a.retryExpected.get())
        assertEquals(1, a.retryActors.get()); assertEquals(1, a.retryReturned.get())
        assertTrue(a.retryMarks.get() > 0); assertEquals(1, a.retryEnds.get())
        assertNull(a.endObservationFailure.get()); assertFalse(a.native.inTransaction())
    }
    fun requireColdFaultAbsent() {
        val a = requireNotNull(selected.get()); val raw = requireNotNull(opened)
        assertTrue(a.reported); assertNotSame("Cold recovery must use a new native owner", a.native, raw)
        assertTrue("The former owner was explicitly invalidated by close", a.ownerInvalidated.get())
        assertFalse("Invalidation alone is not completed native close", a.native.isOpen)
        assertFalse(raw.inTransaction()); assertFalse(installNext.get())
        writerDiagnostic(raw) {
            assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'core_request_fault_*'"))
            assertEquals("Cold durable rollback includes every MAIN typed cell and schema", a.before, imageInTransaction(raw))
        }
        a.rollbackEqual = true
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun text(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): String =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING)
            c.getString(0).also { check(!c.moveToNext()) } }
    private fun rows(raw: SupportSQLiteDatabase, sql: String): Rows = raw.query(sql).use { c ->
        val columns = c.columnNames.toList()
        Rows(columns, buildList { while (c.moveToNext()) add(columns.indices.map { i ->
            val type = c.getType(i)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                else -> error("Unknown SQLite cell type")
            })
        }) })
    }
    /** Fixed synchronous diagnostics below this observer, never a business transaction.
     * IMMEDIATE pins the primary writer; a read-only transaction may choose a WAL reader.
     * The private callers expose neither SQL nor this native handle outside the helper. */
    private fun <T> writerDiagnostic(raw: SupportSQLiteDatabase, block: () -> T): T {
        check(raw === opened && raw.isOpen && !raw.inTransaction())
        raw.beginTransactionNonExclusive()
        var failure: Throwable? = null
        try {
            check(raw.inTransaction())
            val value = block()
            raw.setTransactionSuccessful()
            return value
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { raw.endTransaction(); check(!raw.inTransaction()) }
            catch (ending: Throwable) {
                val original = failure
                if (original == null) throw ending
                if (original !== ending) original.addSuppressed(ending)
            }
        }
    }
    private fun image(raw: SupportSQLiteDatabase): MainImage =
        if (raw.inTransaction()) imageInTransaction(raw)
        else writerDiagnostic(raw) { imageInTransaction(raw) }

    private fun imageInTransaction(raw: SupportSQLiteDatabase): MainImage {
        check(raw.inTransaction())
        val schema = rows(raw, "SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name")
        val tables = raw.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) } }
        }
        return MainImage(raw.version, scalar(raw, "PRAGMA main.schema_version"), schema, tables.associateWith { table ->
            rows(raw, "SELECT rowid AS __core_fault_rowid,* FROM main.\"${table.replace("\"", "\"\"")}\" ORDER BY rowid")
        })
    }
    private fun tag(kind: Kind) = "core_request_fault_${kind.name.lowercase(Locale.ROOT)}"
    private fun gate(column: String) = "(SELECT $column FROM core_request_fault_gate WHERE id=1)"
    private fun install(raw: SupportSQLiteDatabase) {
        writerDiagnostic(raw) {
            val before = imageInTransaction(raw)
            raw.execSQL("CREATE TEMP TABLE core_request_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,mode TEXT NOT NULL,requestId TEXT,queueId INTEGER)")
            raw.execSQL("INSERT INTO temp.core_request_fault_gate VALUES(1,0,'disabled',NULL,NULL)")
            raw.execSQL("CREATE TEMP TABLE core_request_fault_hits(id INTEGER PRIMARY KEY,mode TEXT NOT NULL,actorKey TEXT NOT NULL)")
            raw.execSQL("CREATE TEMP TABLE core_request_fault_parent(id INTEGER PRIMARY KEY)")
            raw.execSQL("CREATE TEMP TABLE core_request_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES core_request_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
            for (kind in Kind.entries) {
                val beforeActor = kind.abort || kind.ignored
                val actor = "${if (beforeActor) "BEFORE" else "AFTER"} ${if (kind.actor == Actor.QUEUE) "DELETE" else "INSERT"} ON main.${kind.actor.table}"
                val key = if (kind.actor == Actor.QUEUE) "CAST(OLD.id AS TEXT)" else "NEW.requestId"
                val target = if (kind.actor == Actor.QUEUE) "OLD.id=${gate("queueId")}" else
                    "NEW.kind='$NEXT_OPERATION' AND NEW.requestId=${gate("requestId")}"
                val effect = when (kind) {
                    Kind.ORIGIN_ABORT, Kind.JOURNAL_ABORT, Kind.RECEIPT_ABORT, Kind.QUEUE_ABORT -> "SELECT RAISE(ABORT,'${tag(kind)}');"
                    Kind.RECEIPT_IGNORE, Kind.QUEUE_IGNORE -> "SELECT RAISE(IGNORE);"
                    Kind.JOURNAL_END, Kind.ACCEPTANCE_END -> "INSERT INTO core_request_fault_child VALUES(1,99);"
                    Kind.RECEIPT_HASH, Kind.QUEUE_RECEIPT_HASH -> "UPDATE next_acceptances SET resultHash='changed';"
                    Kind.RECEIPT_QUEUE_ERROR -> "UPDATE sync_outbox SET lastError='changed';"
                    Kind.QUEUE_WIRE_HASH -> "UPDATE next_transmissions SET wireHash='changed';"
                    Kind.QUEUE_DELETE_RECEIPT -> "DELETE FROM next_acceptances;"
                    Kind.QUEUE_SHADOW_HASH -> "UPDATE sync_entity_state SET payloadHash='changed';"
                }
                raw.execSQL("CREATE TEMP TRIGGER ${tag(kind)} $actor WHEN ${gate("enabled")}=1 AND ${gate("mode")}='${kind.name}' AND $target BEGIN " +
                    "INSERT INTO core_request_fault_hits VALUES(1,'${kind.name}',$key); $effect END")
            }
            check(before == imageInTransaction(raw)) { "Fixed TEMP faults changed MAIN" }
            check(scalar(raw, "SELECT enabled FROM temp.core_request_fault_gate WHERE id=1") == 0L)
            for (table in listOf("core_request_fault_hits", "core_request_fault_parent", "core_request_fault_child")) {
                check(scalar(raw, "SELECT count(*) FROM temp.$table") == 0L)
            }
        }
        installations.incrementAndGet()
    }
    private fun matches(raw: SupportSQLiteDatabase, a: Attempt, actor: Actor, keys: Map<Int, Any?>): Boolean {
        if (actor == Actor.QUEUE) return keys[1] == a.target.queueId
        if (keys[1] != NEXT_OPERATION) return false
        if (actor != Actor.ORIGIN) return keys[2] == a.target.requestId
        val request = keys[2] as? String ?: return false
        val queue = keys[3] as? Long ?: return false
        if (!isContractUuid(request) || queue <= 0 || keys[4] != 5L || keys[5] != a.target.accountId) return false
        return scalar(raw, "SELECT count(*) FROM main.sync_outbox q JOIN main.metric_logs l ON l.uuid=q.entityUuid " +
            "JOIN main.metrics m ON m.id=l.metricId WHERE q.id=? AND q.operationId=? AND q.recordType='metric_log' " +
            "AND q.action='upsert' AND m.id=? AND m.uuid=?", arrayOf<Any?>(queue, request, a.target.metricId, a.target.metricUuid)) == 1L
    }
    private fun requireOriginalMetricEffect(raw: SupportSQLiteDatabase, target: Targets, requestId: String, queueId: Long) {
        raw.query("SELECT q.id,q.operationId,q.recordType,q.action,q.entityUuid,q.wireEntityUuid,q.referenceUuid," +
            "l.id,l.metricId,l.uuid,l.date,l.value,l.unit,l.note,m.uuid FROM main.sync_outbox q " +
            "JOIN main.metric_logs l ON l.uuid=q.entityUuid JOIN main.metrics m ON m.id=l.metricId WHERE q.id=?",
            arrayOf(queueId)).use { c ->
            check(c.moveToFirst())
            for (index in listOf(0,7,8,10)) check(c.getType(index) == Cursor.FIELD_TYPE_INTEGER)
            for (index in listOf(1,2,3,4,5,6,9,12,13,14)) check(c.getType(index) == Cursor.FIELD_TYPE_STRING)
            check(c.getType(11) == Cursor.FIELD_TYPE_FLOAT)
            val uuid = c.getString(9)
            check(isContractUuid(uuid) && c.getLong(0) == queueId && c.getString(1) == requestId &&
                c.getString(2) == "metric_log" && c.getString(3) == "upsert" && c.getString(4) == uuid &&
                c.getString(5) == uuid && c.getString(6) == target.metricUuid && c.getLong(7) > 0 &&
                c.getLong(8) == target.metricId && c.getLong(10) == target.expectedDate &&
                c.getDouble(11).toRawBits() == requireNotNull(target.expectedValue).toRawBits() &&
                c.getString(12) == target.expectedUnit && c.getString(13) == target.expectedNote &&
                c.getString(14) == target.metricUuid)
            check(!c.moveToNext())
        }
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, actor: Actor, keys: Map<Int, Any?>, action: () -> T): T {
        val currentFrame = activeWriter()
        val a = selected.get()
        if (a == null || a.ownerInvalidated.get() || a.kind.actor != actor || (!a.armed.get() && !a.retryExpected.get()) || !matches(raw, a, actor, keys)) return action()
        check(raw === a.native && raw === opened && raw.inTransaction() && currentFrame == null)
        val retry = !a.armed.get()
        val requestId = if (actor == Actor.QUEUE) requireNotNull(a.target.requestId) else keys[2] as String
        val queueId = if (actor in setOf(Actor.ORIGIN, Actor.JOURNAL)) keys[3] as Long else requireNotNull(a.target.queueId)
        check(scalar(raw, "SELECT enabled FROM temp.core_request_fault_gate WHERE id=1") == 0L)
        check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_hits") == 0L)
        check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=?", arrayOf<Any?>(queueId, requestId)) == 1L)
        if (actor == Actor.ORIGIN) {
            requireOriginalMetricEffect(raw, a.target, requestId, queueId)
            check(scalar(raw, "SELECT count(*) FROM main.metric_logs WHERE metricId=?", arrayOf(a.target.metricId)) == requireNotNull(a.priorFacts) + 1L)
        } else {
            check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=? AND queueId=?",
                arrayOf<Any?>(NEXT_OPERATION, requestId, queueId)) == 1L)
        }
        if (actor == Actor.RECEIPT || actor == Actor.QUEUE) {
            check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state") > 0L)
        }
        if (actor == Actor.QUEUE) {
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
        } else check(scalar(raw, "SELECT count(*) FROM main.${actor.table} WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
        if (retry) {
            check(a.reported && a.rollbackEqual && a.retryExpected.compareAndSet(true, false))
            check(a.retryActors.incrementAndGet() == 1); writer.set(Frame(a, true))
            return action().also { check(a.retryReturned.incrementAndGet() == 1) }
        }
        check(a.armed.compareAndSet(true, false))
        when (a.kind) {
            Kind.RECEIPT_HASH, Kind.QUEUE_RECEIPT_HASH, Kind.QUEUE_DELETE_RECEIPT ->
                check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE resultHash='changed'") == 0L)
            Kind.RECEIPT_QUEUE_ERROR -> check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE lastError='changed'") == 0L)
            Kind.QUEUE_WIRE_HASH -> check(text(raw, "SELECT wireHash FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) != "changed")
            Kind.QUEUE_SHADOW_HASH -> check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE payloadHash='changed'") == 0L)
            else -> Unit
        }
        if (a.kind.deferred) {
            check(scalar(raw, "PRAGMA foreign_keys") == 1L)
            check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_parent") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_child") == 0L)
        }
        raw.execSQL("UPDATE temp.core_request_fault_gate SET enabled=1,mode=?,requestId=?,queueId=? WHERE id=1",
            arrayOf<Any?>(a.kind.name, requestId, queueId))
        check(scalar(raw, "SELECT changes()") == 1L)
        check(a.actors.incrementAndGet() == 1); writer.set(Frame(a, false))
        val result = try { action() } catch (failure: SQLiteConstraintException) {
            try {
                check(a.originalConstraint.compareAndSet(null, failure))
                check(a.kind.abort && failure.message.orEmpty().contains(tag(a.kind)))
                check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_hits") == 0L)
                if (actor == Actor.QUEUE) check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=?", arrayOf(queueId)) == 1L)
                else check(scalar(raw, "SELECT count(*) FROM main.${actor.table} WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
                a.effectVerified = true
            } catch (observation: Throwable) { a.endObservationFailure.compareAndSet(null, observation) }
            throw failure // Even an unexpected native constraint retains its original identity.
        }
        check(!a.kind.abort); check(raw.inTransaction()); check(a.returned.incrementAndGet() == 1)
        check(scalar(raw, "SELECT changes()") == if (a.kind.ignored) 0L else 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_hits") == 1L)
        check(text(raw, "SELECT mode FROM temp.core_request_fault_hits WHERE id=1") == a.kind.name)
        check(text(raw, "SELECT actorKey FROM temp.core_request_fault_hits WHERE id=1") == if (actor == Actor.QUEUE) queueId.toString() else requestId)
        when (actor) {
            Actor.ORIGIN -> error("Native origin ABORT returned")
            Actor.JOURNAL -> check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
            Actor.RECEIPT -> check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == if (a.kind.ignored) 0L else 1L)
            Actor.QUEUE -> check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=?", arrayOf(queueId)) == if (a.kind.ignored) 1L else 0L)
        }
        when (a.kind) {
            Kind.RECEIPT_HASH, Kind.QUEUE_RECEIPT_HASH -> check(text(raw, "SELECT resultHash FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == "changed")
            Kind.RECEIPT_QUEUE_ERROR -> check(text(raw, "SELECT lastError FROM main.sync_outbox WHERE id=?", arrayOf(queueId)) == "changed")
            Kind.QUEUE_WIRE_HASH -> check(text(raw, "SELECT wireHash FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == "changed")
            Kind.QUEUE_DELETE_RECEIPT -> check(scalar(raw, "SELECT count(*) FROM main.next_acceptances") == 0L)
            Kind.QUEUE_SHADOW_HASH -> check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE payloadHash='changed'") > 0L && scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE payloadHash<>'changed'") == 0L)
            Kind.JOURNAL_END, Kind.ACCEPTANCE_END -> {
                check(scalar(raw, "SELECT count(*) FROM temp.core_request_fault_child WHERE id=1 AND parent=99") == 1L)
                raw.query("PRAGMA temp.foreign_key_check(core_request_fault_child)").use { c ->
                    check(c.moveToFirst() && c.getString(0) == "core_request_fault_child" && c.getLong(1) == 1L &&
                        c.getString(2) == "core_request_fault_parent" && !c.moveToNext())
                }
            }
            else -> Unit
        }
        a.effectVerified = true
        return result
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun close() = closeOwner(raw) { raw.close() }
                    override fun beginTransaction() = begin(raw) { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin(raw) { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin(raw) { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
                        begin(raw) { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
                        begin(raw) { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
                        begin(raw) { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val actor = Actor.entries.singleOrNull { it.statement == sql } ?: return statement
                        return object : SupportSQLiteStatement by statement {
                            private val keys = hashMapOf<Int, Any?>()
                            override fun clearBindings() { keys.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { keys[index] = null; statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { keys[index] = value; statement.bindLong(index, value) }
                            override fun bindDouble(index: Int, value: Double) { keys[index] = value; statement.bindDouble(index, value) }
                            override fun bindString(index: Int, value: String) { keys[index] = value; statement.bindString(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { keys[index] = value.copyOf(); statement.bindBlob(index, value) }
                            override fun execute() = execute(raw, actor, keys.toMap()) { statement.execute() }
                            override fun executeInsert() = execute(raw, actor, keys.toMap()) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, actor, keys.toMap()) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        val actor = Actor.entries.singleOrNull { it.statement == sql }
                        if (actor == null) raw.execSQL(sql, bindArgs) else execute(raw, actor,
                            bindArgs.mapIndexed { index, value -> index + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        markProducer(raw)
                        activeWriter()?.takeIf { it.attempt.native === raw }?.let {
                            (if (it.retry) it.attempt.retryMarks else it.attempt.marks).incrementAndGet()
                        }
                    }
                    override fun endTransaction() {
                        val frame = activeWriter()?.takeIf { it.attempt.native === raw }
                        if (frame == null) {
                            var returned = false
                            try { raw.endTransaction(); returned = true }
                            finally { endProducer(raw, returned) }
                            return
                        }
                        val a = frame.attempt
                        if (!frame.retry) a.endAttempts.incrementAndGet()
                        var returned = false
                        try { raw.endTransaction(); returned = true }
                        catch (failure: SQLiteConstraintException) {
                            try {
                                check(a.originalConstraint.compareAndSet(null, failure))
                                check(!frame.retry && a.kind.deferred && a.marks.get() > 0 && failure.message.orEmpty().contains("FOREIGN KEY constraint failed"))
                            } catch (observation: Throwable) { a.endObservationFailure.compareAndSet(null, observation) }
                            throw failure // Observation failure must not mask the original END error.
                        } finally {
                            try { if (!raw.inTransaction()) {
                                try {
                                    if (frame.retry) { check(returned); a.retryEnds.incrementAndGet() }
                                    else {
                                        a.ended.incrementAndGet()
                                        if (!a.kind.deferred) { check(returned); a.rollbackEqual = image(raw) == a.before }
                                    }
                                } finally { writer.remove() }
                            } } catch (observation: Throwable) { a.endObservationFailure.compareAndSet(null, observation) }
                        }
                    }
                }
            }
            requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) { parent.onOpen(wrap(db)); if (installNext.compareAndSet(true, false)) install(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override fun close() = closeOwner(native) { helper.close() }
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
