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

/** Four fixed native actors beneath the production controlled factory. All DDL is TEMP;
 * signed main schema is compared byte-for-byte. No handle, SQL or callback is exposed.
 */
internal class NextRestartWorkflowNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Actor(val statement: String) {
        TRANSMISSION("INSERT OR ABORT INTO `next_transmissions` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`deviceId`,`wireHash`,`wireBytes`) VALUES (?,?,?,?,?,?,?,?,?,?)"),
        ACCEPTANCE("INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)"),
        PLAN_PROOF("INSERT OR ABORT INTO `next_restart_plan_proofs` (`operationId`,`originHash`,`transmissionHash`,`revision`,`logSequence`,`deviceId`,`planJson`,`planHash`) VALUES (?,?,?,?,?,?,?,?)")
    }
    enum class Kind(val actor: Actor) {
        REPLACEMENT_DEFERRED_END(Actor.TRANSMISSION), RECEIPT_ABORT(Actor.ACCEPTANCE),
        FREEZE_ORIGIN(Actor.TRANSMISSION), PLAN_ORIGIN(Actor.PLAN_PROOF)
    }
    data class Targets(val restartId: String, val originalId: String? = null)
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
        require(isContractUuid(targets.restartId))
        if (kind == Kind.REPLACEMENT_DEFERRED_END)
            require(targets.originalId != null && isContractUuid(targets.originalId) && targets.originalId != targets.restartId)
        else require(targets.originalId == null)
        selected.set(Attempt(kind, targets))
    }
    fun requireTriggered(failure: Throwable) {
        val attempt = requireNotNull(selected.get())
        assertFalse(attempt.armed.get()); assertEquals("The exact original DAO actor executes once", 1, attempt.actors.get())
        assertEquals(if (attempt.kind == Kind.RECEIPT_ABORT) 0 else 1, attempt.returned.get())
        assertTrue("Actual engine fault or original main effect must be witnessed", attempt.effectVerified)
        assertNull("Observing END must not replace its engine outcome", attempt.endObservationFailure.get())
        assertEquals("The original outer native transaction frame has ended", 1, attempt.ends.get())
        if (attempt.kind in setOf(Kind.RECEIPT_ABORT, Kind.REPLACEMENT_DEFERRED_END)) {
            assertEquals(1, attempt.constraints.get())
            val original = requireNotNull(attempt.originalConstraint.get())
            assertTrue("Propagate the same SQLite engine exception", generateSequence(failure) { it.cause }.any { it === original })
            if (attempt.kind == Kind.REPLACEMENT_DEFERRED_END) assertTrue(attempt.successes.get() > 0)
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
        assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'restart_workflow_fault_*'"))
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
    private fun gate(column: String) = "(SELECT $column FROM restart_workflow_fault_gate WHERE id=1)"
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction()); val before = schema(raw)
        raw.execSQL("CREATE TEMP TABLE restart_workflow_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,mode TEXT NOT NULL,requestId TEXT)")
        raw.execSQL("INSERT INTO temp.restart_workflow_fault_gate(id,enabled,mode) VALUES(1,0,'disabled')")
        raw.execSQL("CREATE TEMP TABLE restart_workflow_fault_hits(id INTEGER PRIMARY KEY,mode TEXT NOT NULL,actorKey TEXT NOT NULL)")
        raw.execSQL("CREATE TEMP TABLE restart_workflow_fault_parent(id INTEGER PRIMARY KEY)")
        raw.execSQL("CREATE TEMP TABLE restart_workflow_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES restart_workflow_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        for (kind in Kind.entries) {
            val table = when (kind.actor) {
                Actor.TRANSMISSION -> "next_transmissions"
                Actor.ACCEPTANCE -> "next_acceptances"
                Actor.PLAN_PROOF -> "next_restart_plan_proofs"
            }
            val key = if (kind.actor == Actor.PLAN_PROOF) "NEW.operationId" else "NEW.requestId"
            val target = if (kind.actor == Actor.PLAN_PROOF) "$key=${gate("requestId")}" else
                "NEW.kind='$NEXT_OPERATION' AND $key=${gate("requestId")}"
            val effect = when (kind) {
                Kind.REPLACEMENT_DEFERRED_END -> "INSERT INTO restart_workflow_fault_child VALUES(1,99);"
                Kind.RECEIPT_ABORT -> "SELECT RAISE(ABORT,'restart_workflow_fault_receipt_abort');"
                Kind.FREEZE_ORIGIN, Kind.PLAN_ORIGIN -> "UPDATE next_request_origins SET intentJson='{}';"
            }
            raw.execSQL("CREATE TEMP TRIGGER restart_workflow_fault_${kind.name.lowercase(Locale.ROOT)} AFTER INSERT ON main.$table " +
                "WHEN ${gate("enabled")}=1 AND ${gate("mode")}='${kind.name}' AND $target BEGIN " +
                "INSERT INTO restart_workflow_fault_hits VALUES(1,'${kind.name}',$key); $effect END")
        }
        check(schema(raw) == before) { "Test TEMP installation altered signed main schema" }
        check(installations.incrementAndGet() == 1)
    }
    private fun matches(raw: SupportSQLiteDatabase, attempt: Attempt, actor: Actor, keys: Map<Int, Any?>): Boolean {
        if (actor == Actor.PLAN_PROOF) return keys[1] == attempt.targets.restartId
        if (keys[1] != NEXT_OPERATION) return false
        if (attempt.kind != Kind.REPLACEMENT_DEFERRED_END) return keys[2] == attempt.targets.restartId
        val replacementId = keys[2] as? String ?: return false
        if (!isContractUuid(replacementId) || replacementId == attempt.targets.restartId) return false
        return scalar(raw, "SELECT count(*) FROM main.next_structural_supersessions WHERE originalId=? AND replacementId=? AND replacementQueueId=? AND kind=?",
            arrayOf<Any?>(attempt.targets.originalId, replacementId, keys[3], NEXT_OPERATION)) == 1L
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, actor: Actor, keys: Map<Int, Any?>, action: () -> T): T {
        val attempt = selected.get()
        if (attempt == null || attempt.kind.actor != actor || !attempt.armed.get() || !matches(raw, attempt, actor, keys) ||
            !attempt.armed.compareAndSet(true, false)) return action()
        check(raw.inTransaction()); val target = attempt.targets
        val requestId = if (actor == Actor.PLAN_PROOF) keys[1] as String else keys[2] as String
        check(scalar(raw, "SELECT enabled FROM temp.restart_workflow_fault_gate WHERE id=1") == 0L)
        check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_hits") == 0L)
        check(text(raw, "SELECT intentJson FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, target.restartId)) != "{}")
        if (actor == Actor.TRANSMISSION) {
            check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=?", arrayOf<Any?>(keys[3], requestId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=? AND queueId=?",
                arrayOf<Any?>(NEXT_OPERATION, requestId, keys[3])) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.next_restart_materializations WHERE operationId=?", arrayOf(target.restartId)) == 1L)
        } else if (actor == Actor.ACCEPTANCE) {
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE operationId=?", arrayOf(requestId)) == 1L)
        } else {
            check(scalar(raw, "SELECT count(*) FROM main.next_restart_plan_proofs WHERE operationId=?", arrayOf(requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE operationId=?", arrayOf(requestId)) == 0L)
        }
        if (attempt.kind == Kind.REPLACEMENT_DEFERRED_END) {
            check(scalar(raw, "SELECT count(*) FROM main.next_restart_plan_proofs WHERE operationId=?", arrayOf(target.restartId)) == 1L)
            check(scalar(raw, "PRAGMA foreign_keys") == 1L)
            check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_parent") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_child") == 0L)
        }
        raw.execSQL("UPDATE temp.restart_workflow_fault_gate SET enabled=1,mode=?,requestId=? WHERE id=1",
            arrayOf<Any?>(attempt.kind.name, requestId))
        check(scalar(raw, "SELECT changes()") == 1L)
        attempt.native = raw; writer.set(attempt); check(attempt.actors.incrementAndGet() == 1)
        val result = try { action() } catch (failure: SQLiteConstraintException) {
            check(attempt.kind == Kind.RECEIPT_ABORT && failure.message?.contains("restart_workflow_fault_receipt_abort") == true)
            // AFTER ABORT undoes the receipt and TEMP hit in that same statement. Its
            // exact bound actor and unchanged native exception prove actual execution.
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_hits") == 0L)
            check(attempt.originalConstraint.compareAndSet(null, failure)); attempt.constraints.incrementAndGet()
            attempt.effectVerified = true; throw failure
        }
        check(raw.inTransaction()); check(attempt.returned.incrementAndGet() == 1)
        check(scalar(raw, "SELECT changes()") == 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_hits") == 1L)
        check(text(raw, "SELECT mode FROM temp.restart_workflow_fault_hits WHERE id=1") == attempt.kind.name)
        check(text(raw, "SELECT actorKey FROM temp.restart_workflow_fault_hits WHERE id=1") == requestId)
        if (actor == Actor.TRANSMISSION) check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 1L)
        else check(scalar(raw, "SELECT count(*) FROM main.next_restart_plan_proofs WHERE operationId=?", arrayOf(requestId)) == 1L)
        when (attempt.kind) {
            Kind.RECEIPT_ABORT -> error("Native ABORT returned")
            Kind.FREEZE_ORIGIN, Kind.PLAN_ORIGIN -> {
                check(text(raw, "SELECT intentJson FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, target.restartId)) == "{}")
                check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE intentJson!='{}'") == 0L)
            }
            Kind.REPLACEMENT_DEFERRED_END -> {
                check(scalar(raw, "SELECT count(*) FROM temp.restart_workflow_fault_child WHERE id=1 AND parent=99") == 1L)
                raw.query("PRAGMA temp.foreign_key_check(restart_workflow_fault_child)").use { c ->
                    check(c.moveToFirst() && c.getString(0) == "restart_workflow_fault_child" && c.getLong(1) == 1L &&
                        c.getString(2) == "restart_workflow_fault_parent" && !c.moveToNext())
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
                            private fun key(index: Int, value: Any?) { if (index <= 3) keys[index] = value }
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
                            bindArgs.take(3).mapIndexed { index, value -> index + 1 to value }.toMap()) { raw.execSQL(sql, bindArgs) }
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
                            check(attempt.kind == Kind.REPLACEMENT_DEFERRED_END && failure.message?.contains("FOREIGN KEY constraint failed") == true)
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
