package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.domain.model.isContractUuid
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

/** Fixed test-only native actors below the real controlled factory. No SQL callback or handle escapes. */
internal class NextPlanDeletionNativeFault : SupportSQLiteOpenHelper.Factory {
    private enum class Actor(val statement: String, val table: String, val tag: String) {
        ORIGIN("INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)", "next_request_origins", "delete origin unavailable"),
        ACK("INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)", "next_acceptances", "delete ack unavailable")
    }
    private class Attempt(val actor: Actor, val entityUuid: String, val requestId: String?) {
        val armed = AtomicBoolean(true)
        val actors = AtomicInteger()
        val ends = AtomicInteger()
        val successfulMarks = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
        val endFailure = AtomicReference<Throwable?>()
        @Volatile var native: SupportSQLiteDatabase? = null
        @Volatile var boundRequestId: String? = null
        @Volatile var boundQueueId: Long? = null
        @Volatile var businessEffectVerified = false
    }
    private val installNext = AtomicBoolean()
    private val installations = AtomicInteger()
    private val selected = AtomicReference<Attempt?>()
    private val writer = ThreadLocal<Attempt?>()
    @Volatile private var opened: SupportSQLiteDatabase? = null

    fun installOnNextOpen() { check(installations.get() == 0 && installNext.compareAndSet(false, true)) }
    fun armOrigin(entityUuid: String) = arm(Actor.ORIGIN, entityUuid, null)
    fun armAck(requestId: String, entityUuid: String) = arm(Actor.ACK, entityUuid, requestId)
    private fun arm(actor: Actor, entityUuid: String, requestId: String?) {
        check(installations.get() == 1 && !installNext.get() && selected.get() == null)
        require(isContractUuid(entityUuid) && (requestId == null || isContractUuid(requestId)))
        selected.set(Attempt(actor, entityUuid, requestId))
    }
    fun requireTriggered(failure: Throwable) {
        val attempt = requireNotNull(selected.get())
        assertFalse(attempt.armed.get()); assertEquals("Exact original bound DAO actor executes once", 1, attempt.actors.get())
        assertTrue(isContractUuid(requireNotNull(attempt.boundRequestId)))
        assertTrue(requireNotNull(attempt.boundQueueId) > 0)
        assertTrue("Original delete business writes occurred before the failing insert", attempt.businessEffectVerified)
        val original = requireNotNull(attempt.originalConstraint.get())
        assertTrue("Keep the same SQLite RAISE exception", generateSequence(failure) { it.cause }.any { it === original })
        assertTrue(original.message?.contains(attempt.actor.tag) == true)
        assertEquals("No successful commit mark follows the failing insert", 0, attempt.successfulMarks.get())
        assertEquals("The real outer native rollback frame ended", 1, attempt.ends.get())
        assertNull("Native END or its observation must not replace the original engine exception", attempt.endFailure.get())
    }
    fun requireColdFaultAbsent() {
        check(installations.get() == 1 && !installNext.get())
        val raw = requireNotNull(opened); assertFalse(raw.inTransaction())
        assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'plan_delete_fault_*'"))
    }
    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) } }
    private fun schema(raw: SupportSQLiteDatabase) = raw.query(
        "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
        buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction()); val before = schema(raw)
        raw.execSQL("CREATE TEMP TABLE plan_delete_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,actor TEXT NOT NULL,requestId TEXT,entityUuid TEXT,queueId INTEGER)")
        raw.execSQL("INSERT INTO temp.plan_delete_fault_gate(id,enabled,actor) VALUES(1,0,'disabled')")
        for (actor in Actor.entries) {
            val queue = if (actor == Actor.ORIGIN) "NEW.queueId" else "(SELECT queueId FROM plan_delete_fault_gate WHERE id=1)"
            raw.execSQL("CREATE TEMP TRIGGER plan_delete_fault_${actor.name.lowercase(java.util.Locale.ROOT)} BEFORE INSERT ON main.${actor.table} " +
                "WHEN NEW.kind='$NEXT_OPERATION' AND (SELECT enabled FROM plan_delete_fault_gate WHERE id=1)=1 " +
                "AND (SELECT actor FROM plan_delete_fault_gate WHERE id=1)='${actor.name}' " +
                "AND NEW.requestId=(SELECT requestId FROM plan_delete_fault_gate WHERE id=1) " +
                "AND EXISTS(SELECT 1 FROM main.sync_outbox WHERE id=$queue AND operationId=NEW.requestId " +
                "AND recordType='habit' AND action='delete' AND entityUuid=(SELECT entityUuid FROM plan_delete_fault_gate WHERE id=1)) " +
                "BEGIN SELECT RAISE(ABORT,'${actor.tag}'); END")
        }
        check(schema(raw) == before) { "Fixed TEMP installation changed signed main schema" }
        check(installations.incrementAndGet() == 1)
    }
    private fun <T> execute(raw: SupportSQLiteDatabase, actor: Actor, keys: Map<Int, Any?>, action: () -> T): T {
        val attempt = selected.get()
        if (attempt == null || attempt.actor != actor || !attempt.armed.get() || keys[1] != NEXT_OPERATION) return action()
        val requestId = keys[2] as? String ?: return action()
        if (attempt.requestId != null && attempt.requestId != requestId) return action()
        val queueId = if (actor == Actor.ORIGIN) keys[3] as? Long ?: return action() else {
            raw.query("SELECT id FROM main.sync_outbox WHERE operationId=? AND recordType='habit' AND action='delete' AND entityUuid=?",
                arrayOf(requestId, attempt.entityUuid)).use { c ->
                if (!c.moveToFirst()) return action()
                check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                c.getLong(0).also { check(!c.moveToNext()) }
            }
        }
        if (scalar(raw, "SELECT count(*) FROM main.sync_outbox WHERE id=? AND operationId=? AND recordType='habit' AND action='delete' AND entityUuid=?",
                arrayOf<Any?>(queueId, requestId, attempt.entityUuid)) != 1L) return action()
        check(raw.inTransaction() && isContractUuid(requestId) && queueId > 0)
        check(attempt.armed.compareAndSet(true, false))
        check(scalar(raw, "SELECT enabled FROM temp.plan_delete_fault_gate WHERE id=1") == 0L)
        check(scalar(raw, "SELECT count(*) FROM main.${actor.table} WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
        if (actor == Actor.ORIGIN) {
            check(scalar(raw, "SELECT count(*) FROM main.habits WHERE uuid=? AND name=?",
                arrayOf(attempt.entityUuid, "pending-delete-${attempt.entityUuid}")) == 1L)
        } else {
            check(scalar(raw, "SELECT count(*) FROM main.next_request_origins WHERE kind=? AND requestId=? AND queueId=?",
                arrayOf<Any?>(NEXT_OPERATION, requestId, queueId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=? AND queueId=?",
                arrayOf<Any?>(NEXT_OPERATION, requestId, queueId)) == 1L)
            check(scalar(raw, "SELECT count(*) FROM main.habits WHERE uuid=?", arrayOf(attempt.entityUuid)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.sync_entity_state WHERE entityType='plan_node' AND entityUuid=? AND deleted=1",
                arrayOf(attempt.entityUuid)) == 1L)
        }
        attempt.businessEffectVerified = true
        raw.execSQL("UPDATE temp.plan_delete_fault_gate SET enabled=1,actor=?,requestId=?,entityUuid=?,queueId=? WHERE id=1",
            arrayOf<Any?>(actor.name, requestId, attempt.entityUuid, queueId))
        check(scalar(raw, "SELECT changes()") == 1L)
        attempt.native = raw; attempt.boundRequestId = requestId; attempt.boundQueueId = queueId
        writer.set(attempt); check(attempt.actors.incrementAndGet() == 1)
        try {
            action()
            error("Expected actual SQLite RAISE ABORT did not execute")
        } catch (failure: SQLiteConstraintException) {
            check(failure.message?.contains(actor.tag) == true)
            check(scalar(raw, "SELECT count(*) FROM main.${actor.table} WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, requestId)) == 0L)
            // ABORT rolls back the failing statement; its original tagged engine exception
            // on the exact bound actor is the witness, not a synthetic surviving TEMP hit.
            check(attempt.originalConstraint.compareAndSet(null, failure)); throw failure
        }
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
                        writer.get()?.takeIf { it.native === raw }?.successfulMarks?.incrementAndGet()
                    }
                    override fun endTransaction() {
                        val attempt = writer.get()?.takeIf { it.native === raw }
                        if (attempt == null) { raw.endTransaction(); return }
                        try { raw.endTransaction() }
                        catch (failure: Throwable) { attempt.endFailure.compareAndSet(null, failure); throw failure }
                        finally {
                            try { if (!raw.inTransaction()) { attempt.ends.incrementAndGet(); writer.remove() } }
                            catch (observation: Throwable) { attempt.endFailure.compareAndSet(null, observation) }
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
