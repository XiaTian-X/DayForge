package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
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

/** Private fixed native actors beneath the production controlled factory. TEMP installation
 * leaves the signed main schema exact. No raw handle, arbitrary SQL or result callback escapes.
 */
internal class NextTimerRequestNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Actor(val statement: String) {
        ACCEPTANCE("INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)"),
        QUEUE("DELETE FROM timer_command_outbox WHERE id = ?"),
        SHADOW("INSERT OR REPLACE INTO `sync_entity_state` (`entityType`,`entityUuid`,`revision`,`deleted`,`payloadJson`,`payloadHash`,`updatedAt`) VALUES (?,?,?,?,?,?,?)")
    }
    enum class Kind(val actor: Actor) {
        RECEIPT_ABORT(Actor.ACCEPTANCE), RECEIPT_IGNORE(Actor.ACCEPTANCE), QUEUE_RECEIPT(Actor.QUEUE),
        RECEIPT_TIMER(Actor.ACCEPTANCE), DEFERRED_END(Actor.ACCEPTANCE), LATE_RECOVERY_RECEIPT(Actor.SHADOW)
    }
    data class Targets(val requestId: String, val sessionUuid: String, val queueId: Long? = null)
    private class Attempt(val kind: Kind, val targets: Targets) {
        val armed = AtomicBoolean(true)
        val actors = AtomicInteger()
        val returned = AtomicInteger()
        val ends = AtomicInteger()
        val successes = AtomicInteger()
        val constraints = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
        val endObservationFailure = AtomicReference<Throwable?>()
        @Volatile var native: SupportSQLiteDatabase? = null
        @Volatile var effectVerified = false
        @Volatile var reported = false
    }
    private val installNext = AtomicBoolean()
    private val installations = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Attempt?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun installOnNextOpen() {
        check(installations.get() == 0 && installNext.compareAndSet(false, true))
    }
    fun arm(kind: Kind, targets: Targets) {
        assertEquals(1, installations.get()); assertFalse(installNext.get())
        check(selected.get()?.reported != false)
        require(isContractUuid(targets.requestId) && isContractUuid(targets.sessionUuid))
        require(targets.queueId == null || targets.queueId > 0)
        if (kind != Kind.LATE_RECOVERY_RECEIPT) require(targets.queueId != null)
        selected.set(Attempt(kind, targets))
    }
    fun requireTriggered(failure: Throwable) {
        val attempt = requireNotNull(selected.get())
        assertFalse(attempt.armed.get()); assertEquals("The exact original DAO actor executes once", 1, attempt.actors.get())
        assertEquals(if (attempt.kind == Kind.RECEIPT_ABORT) 0 else 1, attempt.returned.get())
        assertTrue("Actual engine fault or original main effect must be witnessed", attempt.effectVerified)
        assertNull("Observing END must not replace its engine outcome", attempt.endObservationFailure.get())
        assertEquals("The original outer native transaction frame has ended", 1, attempt.ends.get())
        if (attempt.kind in setOf(Kind.RECEIPT_ABORT, Kind.DEFERRED_END)) {
            assertEquals(1, attempt.constraints.get())
            val original = requireNotNull(attempt.originalConstraint.get())
            assertTrue("Propagate the same SQLite engine exception", generateSequence(failure) { it.cause }.any { it === original })
            if (attempt.kind == Kind.DEFERRED_END) assertTrue(attempt.successes.get() > 0)
            else assertEquals(0, attempt.successes.get())
        } else {
            assertEquals(0, attempt.constraints.get())
            val early = setOf("COMMAND_PRIMARY_READ_EXPIRED", "COMMAND_PRIMARY_BINDING_CHANGED",
                "COMMAND_OLD_COUNT_SCHEMA_INVALID", "COUNT_TRUST_SCHEMA_CHANGED", "EVIDENCE_READ_SESSION_WROTE_DATA")
            assertFalse("Early schema/lifetime refusal is not this counterexample",
                generateSequence(failure) { it.cause }.any { it.message in early })
        }
        attempt.reported = true
    }
    fun requireColdFaultAbsent() {
        assertEquals(1, installations.get()); assertFalse(installNext.get())
        val raw = requireNotNull(opened); assertFalse(raw.inTransaction())
        assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'timer_request_fault_*'"))
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun text(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): String =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING)
            c.getString(0).also { check(!c.moveToNext()) } }
    private fun schema(raw: SupportSQLiteDatabase) = raw.query(
        "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
        buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun gate(column: String) = "(SELECT $column FROM timer_request_fault_gate WHERE id=1)"
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction()); val before = schema(raw)
        raw.execSQL("CREATE TEMP TABLE timer_request_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,mode TEXT NOT NULL,requestId TEXT,sessionUuid TEXT,queueId INTEGER)")
        raw.execSQL("INSERT INTO temp.timer_request_fault_gate(id,enabled,mode) VALUES(1,0,'disabled')")
        raw.execSQL("CREATE TEMP TABLE timer_request_fault_hits(id INTEGER PRIMARY KEY,mode TEXT NOT NULL,actorKey TEXT NOT NULL)")
        raw.execSQL("CREATE TEMP TABLE timer_request_fault_parent(id INTEGER PRIMARY KEY)")
        raw.execSQL("CREATE TEMP TABLE timer_request_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES timer_request_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        for (kind in Kind.entries) {
            val actor = when (kind.actor) {
                Actor.ACCEPTANCE -> "${if (kind in setOf(Kind.RECEIPT_ABORT, Kind.RECEIPT_IGNORE)) "BEFORE" else "AFTER"} INSERT ON main.next_acceptances"
                Actor.QUEUE -> "AFTER DELETE ON main.timer_command_outbox"
                Actor.SHADOW -> "AFTER INSERT ON main.sync_entity_state"
            }
            val target = when (kind.actor) {
                Actor.ACCEPTANCE -> "NEW.kind='$NEXT_TIMER' AND NEW.requestId=${gate("requestId")}"
                Actor.QUEUE -> "OLD.id=${gate("queueId")}"
                Actor.SHADOW -> "NEW.entityType='activity_event' AND NEW.entityUuid=${gate("sessionUuid")}"
            }
            val key = when (kind.actor) {
                Actor.ACCEPTANCE -> "NEW.requestId"
                Actor.QUEUE -> "CAST(OLD.id AS TEXT)"
                Actor.SHADOW -> "NEW.entityUuid"
            }
            val effect = when (kind) {
                Kind.RECEIPT_ABORT -> "SELECT RAISE(ABORT,'timer_request_fault_receipt_abort');"
                Kind.RECEIPT_IGNORE -> "SELECT RAISE(IGNORE);"
                Kind.QUEUE_RECEIPT -> "UPDATE next_acceptances SET resultHash='broken';"
                Kind.RECEIPT_TIMER -> "UPDATE timelogs SET durationSeconds=77;"
                Kind.DEFERRED_END -> "INSERT INTO timer_request_fault_child VALUES(1,99);"
                Kind.LATE_RECOVERY_RECEIPT -> "UPDATE next_acceptances SET resultHash='broken' WHERE requestId=${gate("requestId")};"
            }
            raw.execSQL("CREATE TEMP TRIGGER timer_request_fault_${kind.name.lowercase(Locale.ROOT)} $actor " +
                "WHEN ${gate("enabled")}=1 AND ${gate("mode")}='${kind.name}' AND $target BEGIN " +
                "INSERT INTO timer_request_fault_hits VALUES(1,'${kind.name}',$key); $effect END")
        }
        check(schema(raw) == before) { "Test TEMP installation altered signed main schema" }
        check(installations.incrementAndGet() == 1)
    }
    private fun matches(attempt: Attempt, actor: Actor, keys: Map<Int, Any?>): Boolean = when (actor) {
        Actor.ACCEPTANCE -> keys[1] == NEXT_TIMER && keys[2] == attempt.targets.requestId
        Actor.QUEUE -> keys[1] == attempt.targets.queueId
        Actor.SHADOW -> keys[1] == "activity_event" && keys[2] == attempt.targets.sessionUuid
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, actor: Actor, keys: Map<Int, Any?>, action: () -> T): T {
        val attempt = selected.get()
        if (attempt == null || attempt.kind.actor != actor || !matches(attempt, actor, keys) ||
            !attempt.armed.compareAndSet(true, false)) return action()
        check(raw.inTransaction()); val target = attempt.targets
        check(scalar(raw, "SELECT enabled FROM temp.timer_request_fault_gate WHERE id=1") == 0L)
        check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_hits") == 0L)
        if (actor == Actor.ACCEPTANCE) {
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_TIMER, target.requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.timer_command_outbox WHERE id=? AND commandId=? AND sessionUuid=?",
                arrayOf<Any?>(target.queueId, target.requestId, target.sessionUuid)) == 1L)
        } else {
            check(text(raw, "SELECT resultHash FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_TIMER, target.requestId)) != "broken")
            if (actor == Actor.QUEUE) check(scalar(raw, "SELECT count(*) FROM main.timer_command_outbox WHERE id=? AND commandId=? AND sessionUuid=?",
                arrayOf<Any?>(target.queueId, target.requestId, target.sessionUuid)) == 1L)
            else check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE entityType='activity_event' AND entityUuid=?", arrayOf(target.sessionUuid)) == 0L)
        }
        if (attempt.kind == Kind.RECEIPT_TIMER) {
            check(scalar(raw, "SELECT count(*) FROM main.timelogs WHERE uuid=?", arrayOf(target.sessionUuid)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.timelogs WHERE durationSeconds=77") == 0L)
        }
        if (attempt.kind == Kind.DEFERRED_END) {
            check(scalar(raw, "PRAGMA foreign_keys") == 1L)
            check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_parent") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_child") == 0L)
        }
        raw.execSQL("UPDATE temp.timer_request_fault_gate SET enabled=1,mode=?,requestId=?,sessionUuid=?,queueId=? WHERE id=1",
            arrayOf<Any?>(attempt.kind.name, target.requestId, target.sessionUuid, target.queueId))
        check(scalar(raw, "SELECT changes()") == 1L)
        attempt.native = raw; writer.set(attempt); check(attempt.actors.incrementAndGet() == 1)
        val result = try { action() } catch (failure: SQLiteConstraintException) {
            check(attempt.kind == Kind.RECEIPT_ABORT && failure.message?.contains("timer_request_fault_receipt_abort") == true)
            // ABORT rolls back its statement's TEMP hit as well. The exact bound actor and
            // original engine RAISE exception are its witness, never a fabricated counter.
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_TIMER, target.requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_hits") == 0L)
            check(attempt.originalConstraint.compareAndSet(null, failure)); attempt.constraints.incrementAndGet()
            attempt.effectVerified = true; throw failure
        }
        check(raw.inTransaction()); check(attempt.returned.incrementAndGet() == 1)
        check(scalar(raw, "SELECT changes()") == if (attempt.kind == Kind.RECEIPT_IGNORE) 0L else 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_hits") == 1L)
        check(text(raw, "SELECT mode FROM temp.timer_request_fault_hits WHERE id=1") == attempt.kind.name)
        val expectedKey = if (actor == Actor.QUEUE) target.queueId.toString() else if (actor == Actor.SHADOW) target.sessionUuid else target.requestId
        check(text(raw, "SELECT actorKey FROM temp.timer_request_fault_hits WHERE id=1") == expectedKey)
        when (actor) {
            Actor.ACCEPTANCE -> check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_TIMER, target.requestId)) == if (attempt.kind == Kind.RECEIPT_IGNORE) 0L else 1L)
            Actor.QUEUE -> check(scalar(raw, "SELECT count(*) FROM main.timer_command_outbox WHERE id=?", arrayOf(target.queueId)) == 0L)
            Actor.SHADOW -> check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE entityType='activity_event' AND entityUuid=?", arrayOf(target.sessionUuid)) == 1L)
        }
        when (attempt.kind) {
            Kind.RECEIPT_ABORT -> error("Native ABORT returned")
            Kind.RECEIPT_IGNORE -> Unit // Actual hit, zero changes and absence were checked above.
            Kind.QUEUE_RECEIPT, Kind.LATE_RECOVERY_RECEIPT -> check(text(raw, "SELECT resultHash FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_TIMER, target.requestId)) == "broken")
            Kind.RECEIPT_TIMER -> check(scalar(raw, "SELECT count(*) FROM main.timelogs WHERE uuid=? AND durationSeconds=77", arrayOf(target.sessionUuid)) == 1L)
            Kind.DEFERRED_END -> {
                check(scalar(raw, "SELECT count(*) FROM temp.timer_request_fault_child WHERE id=1 AND parent=99") == 1L)
                raw.query("PRAGMA temp.foreign_key_check(timer_request_fault_child)").use { c ->
                    check(c.moveToFirst() && c.getString(0) == "timer_request_fault_child" && c.getLong(1) == 1L &&
                        c.getString(2) == "timer_request_fault_parent" && !c.moveToNext())
                }
            }
        }
        attempt.effectVerified = true
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
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val actor = Actor.entries.singleOrNull { it.statement == sql } ?: return statement
                        return object : SupportSQLiteStatement by statement {
                            private val keys = hashMapOf<Int, Any?>()
                            private fun key(index: Int, value: Any?) { if (index <= 2) keys[index] = value }
                            override fun clearBindings() { keys.clear(); statement.clearBindings() }
                            override fun bindNull(index: Int) { key(index, null); statement.bindNull(index) }
                            override fun bindLong(index: Int, value: Long) { key(index, value); statement.bindLong(index, value) }
                            override fun bindDouble(index: Int, value: Double) { key(index, value); statement.bindDouble(index, value) }
                            override fun bindString(index: Int, value: String) { key(index, value); statement.bindString(index, value) }
                            override fun bindBlob(index: Int, value: ByteArray) { key(index, value.copyOf()); statement.bindBlob(index, value) }
                            override fun execute() = execute(raw, actor, keys) { statement.execute() }
                            override fun executeInsert() = execute(raw, actor, keys) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, actor, keys) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        val actor = Actor.entries.singleOrNull { it.statement == sql }
                        if (actor == null) raw.execSQL(sql, bindArgs) else execute(raw, actor,
                            bindArgs.take(2).mapIndexed { index, value -> index + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        writer.get()?.takeIf { it.native === raw }?.successes?.incrementAndGet()
                    }
                    override fun endTransaction() {
                        val attempt = writer.get()?.takeIf { it.native === raw }
                        if (attempt == null) { raw.endTransaction(); return }
                        try { raw.endTransaction() }
                        catch (failure: SQLiteConstraintException) {
                            check(attempt.kind == Kind.DEFERRED_END && failure.message?.contains("FOREIGN KEY constraint failed") == true)
                            check(attempt.successes.get() > 0 && attempt.originalConstraint.compareAndSet(null, failure))
                            attempt.constraints.incrementAndGet(); throw failure
                        } finally {
                            // An engine END failure is not proof of rollback. Never query TEMP
                            // or attempt another transaction on this now-unknown owner.
                            try {
                                if (!raw.inTransaction()) { attempt.ends.incrementAndGet(); writer.remove() }
                            } catch (observation: Throwable) { attempt.endObservationFailure.compareAndSet(null, observation) }
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
            override fun onOpen(db: SupportSQLiteDatabase) {
                parent.onOpen(wrap(db))
                if (installNext.compareAndSet(true, false)) install(db)
            }
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
}
