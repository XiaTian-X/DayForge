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

/** Closed test-only faults beneath the unchanged controlled factory. All instrumentation
 * rows are TEMP; only each original fault body changes main. No raw handle/SQL callback escapes.
 * The exact generated original INSERT executes once. Actual trigger effects are inspected on
 * that writer before the original validator rolls back, never inferred from a generic refusal.
 */
internal class NextRoundOperationNativeFault : SupportSQLiteOpenHelper.Factory {
    enum class Kind(val actor: Actor) {
        LATE_BIRTH(Actor.ORIGIN), CREATION_FLAG(Actor.ORIGIN), ABSENT_RECEIPT(Actor.ORIGIN),
        ABSENT_TRANSMISSION(Actor.ORIGIN), WRONG_METRIC(Actor.BIRTH), BAD_METADATA(Actor.ACCEPTANCE),
        DEFERRED_END(Actor.ACCEPTANCE), COHERENT_CURSOR(Actor.ACCEPTANCE), ROOT_SOURCE(Actor.ORIGIN)
    }
    enum class Actor(val statement: String, val table: String, val key: String) {
        ORIGIN("INSERT OR ABORT INTO `next_request_origins` (`kind`,`requestId`,`queueId`,`protocol`,`accountId`,`serverInstanceId`,`syncEpoch`,`sourceHash`,`intentJson`) VALUES (?,?,?,?,?,?,?,?,?)", "next_request_origins", "requestId"),
        BIRTH("INSERT OR ABORT INTO `next_challenge_births` (`entityType`,`entityUuid`,`roundUuid`) VALUES (?,?,?)", "next_challenge_births", "entityUuid"),
        ACCEPTANCE("INSERT OR ABORT INTO `next_acceptances` (`kind`,`requestId`,`originHash`,`transmissionHash`,`resultHash`,`resultJson`) VALUES (?,?,?,?,?,?)", "next_acceptances", "requestId")
    }
    data class Targets(val factUuid: String, val requestId: String? = null, val queueId: Long? = null,
        val roundUuid: String? = null, val insertedBirthUuid: String? = null,
        val accountId: String? = null, val serverId: String? = null, val epoch: String? = null, val deviceId: String? = null)
    private class Attempt(val kind: Kind, val targets: Targets) {
        val armed = AtomicBoolean(true)
        val statementsReturned = AtomicInteger()
        val ends = AtomicInteger()
        val successes = AtomicInteger()
        val constraints = AtomicInteger()
        val originalConstraint = AtomicReference<SQLiteConstraintException?>()
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
        require(isContractUuid(targets.factUuid))
        listOfNotNull(targets.requestId, targets.roundUuid, targets.insertedBirthUuid,
            targets.accountId, targets.serverId, targets.epoch, targets.deviceId).forEach { require(isContractUuid(it)) }
        require(targets.queueId == null || targets.queueId > 0)
        if (kind == Kind.LATE_BIRTH) require(targets.roundUuid != null && targets.insertedBirthUuid != null)
        if (kind in setOf(Kind.CREATION_FLAG, Kind.ABSENT_RECEIPT, Kind.ABSENT_TRANSMISSION, Kind.ROOT_SOURCE))
            require(targets.requestId != null && targets.queueId != null)
        if (kind.actor == Actor.ACCEPTANCE) require(targets.requestId != null)
        if (kind == Kind.ABSENT_TRANSMISSION)
            require(targets.accountId != null && targets.serverId != null && targets.epoch != null && targets.deviceId != null)
        selected.set(Attempt(kind, targets))
    }
    fun requireTriggered(failure: Throwable) {
        val attempt = requireNotNull(selected.get())
        assertFalse(attempt.armed.get())
        assertEquals("The original generated actor INSERT returned exactly once", 1, attempt.statementsReturned.get())
        assertTrue("Its TEMP trigger performed the original intended main effect", attempt.effectVerified)
        if (attempt.kind == Kind.DEFERRED_END) {
            assertTrue("Original code reached native setTransactionSuccessful", attempt.successes.get() > 0)
            assertEquals("SQLite rejected the actual END, not a test throw", 1, attempt.constraints.get())
            val original = requireNotNull(attempt.originalConstraint.get())
            assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        } else {
            assertEquals("Failed actor transaction ended before rollback assertions", 1, attempt.ends.get())
            val early = setOf("COMMAND_PRIMARY_READ_EXPIRED", "COMMAND_PRIMARY_BINDING_CHANGED",
                "COMMAND_OLD_COUNT_SCHEMA_INVALID", "COUNT_TRUST_SCHEMA_CHANGED", "EVIDENCE_READ_SESSION_WROTE_DATA")
            assertFalse("A schema/lifetime/read-only-marker refusal is not the intended business counterexample",
                generateSequence(failure) { it.cause }.any { it.message in early })
        }
        attempt.reported = true
    }
    fun requireColdFaultAbsent() {
        assertEquals(1, installations.get()); assertFalse(installNext.get())
        val raw = requireNotNull(opened)
        assertFalse(raw.inTransaction())
        assertEquals(0L, scalar(raw, "SELECT count(*) FROM temp.sqlite_master WHERE name GLOB 'round_operation_fault_*'"))
    }

    private fun scalar(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER); c.getLong(0).also { check(!c.moveToNext()) } }
    private fun text(raw: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): String =
        raw.query(sql, args).use { c -> check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_STRING); c.getString(0).also { check(!c.moveToNext()) } }
    private fun schema(raw: SupportSQLiteDatabase) = raw.query(
        "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
        buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun gate(column: String) = "(SELECT $column FROM round_operation_fault_gate WHERE id=1)"
    private fun install(raw: SupportSQLiteDatabase) {
        check(!raw.inTransaction())
        val before = schema(raw)
        raw.execSQL("CREATE TEMP TABLE round_operation_fault_gate(id INTEGER PRIMARY KEY,enabled INTEGER NOT NULL,mode TEXT NOT NULL," +
            "factUuid TEXT,requestId TEXT,queueId INTEGER,roundUuid TEXT,insertedBirthUuid TEXT,accountId TEXT,serverId TEXT,epoch TEXT,deviceId TEXT)")
        raw.execSQL("INSERT INTO temp.round_operation_fault_gate(id,enabled,mode) VALUES(1,0,'disabled')")
        raw.execSQL("CREATE TEMP TABLE round_operation_fault_hits(id INTEGER PRIMARY KEY,actor TEXT NOT NULL,actorKey TEXT NOT NULL," +
            "beforeCursor INTEGER,beforeGeneration INTEGER,beforeChallengeCursor INTEGER,beforeChallengeGeneration INTEGER)")
        raw.execSQL("CREATE TEMP TABLE round_operation_fault_parent(id INTEGER PRIMARY KEY)")
        raw.execSQL("CREATE TEMP TABLE round_operation_fault_child(id INTEGER PRIMARY KEY,parent INTEGER REFERENCES round_operation_fault_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        for (kind in Kind.values()) {
            val actor = kind.actor
            val condition = if (kind in setOf(Kind.CREATION_FLAG, Kind.ABSENT_RECEIPT, Kind.ABSENT_TRANSMISSION))
                " AND NEW.requestId <> ${gate("requestId")}" else ""
            val effect = when (kind) {
                Kind.LATE_BIRTH -> "INSERT INTO next_challenge_births(entityType,entityUuid,roundUuid) VALUES('activity_event',${gate("insertedBirthUuid")},${gate("roundUuid")});"
                Kind.CREATION_FLAG -> "UPDATE next_request_origins SET intentJson=replace(intentJson,'\"initial_creation\":true','\"initial_creation\":false') WHERE requestId=${gate("requestId")};"
                Kind.ABSENT_RECEIPT -> "INSERT INTO next_acceptances(kind,requestId,originHash,transmissionHash,resultHash,resultJson) VALUES('$NEXT_OPERATION',${gate("requestId")},'late-origin','late-wire','late-result','{}');"
                Kind.ABSENT_TRANSMISSION -> "INSERT INTO next_transmissions(kind,requestId,queueId,protocol,accountId,serverInstanceId,syncEpoch,deviceId,wireHash,wireBytes) VALUES('$NEXT_OPERATION',${gate("requestId")},${gate("queueId")},5,${gate("accountId")},${gate("serverId")},${gate("epoch")},${gate("deviceId")},'${"0".repeat(64)}',X'7b7d');"
                Kind.WRONG_METRIC -> "UPDATE metrics SET name='wrong side effect';"
                Kind.BAD_METADATA -> "UPDATE next_challenge_state SET metadataHash='bad';"
                Kind.DEFERRED_END -> "INSERT INTO round_operation_fault_child VALUES(1,99);"
                Kind.COHERENT_CURSOR -> "UPDATE next_sync_state SET cursor=cursor+1,generation=generation+1; UPDATE next_challenge_state SET cursor=cursor+1,generation=generation+1;"
                Kind.ROOT_SOURCE -> "UPDATE sync_outbox SET referenceUuid='TIMER' WHERE id=${gate("queueId")};"
            }
            raw.execSQL("CREATE TEMP TRIGGER round_operation_fault_${kind.name.lowercase(Locale.ROOT)} AFTER INSERT ON main.${actor.table} " +
                "WHEN ${gate("enabled")}=1 AND ${gate("mode")}='${kind.name}'$condition BEGIN " +
                "INSERT INTO round_operation_fault_hits VALUES(1,'${actor.name}',NEW.${actor.key}," +
                "(SELECT cursor FROM next_sync_state WHERE id=1),(SELECT generation FROM next_sync_state WHERE id=1)," +
                "(SELECT cursor FROM next_challenge_state WHERE id=1),(SELECT generation FROM next_challenge_state WHERE id=1)); " + effect + " END")
        }
        check(schema(raw) == before) { "Test TEMP installation altered signed main schema" }
        check(installations.incrementAndGet() == 1)
    }

    private fun <T> execute(raw: SupportSQLiteDatabase, actor: Actor, action: () -> T): T {
        val attempt = selected.get()
        if (attempt == null || attempt.kind.actor != actor || !attempt.armed.compareAndSet(true, false)) return action()
        check(raw === opened && raw.inTransaction() && writer.get() == null)
        check(scalar(raw, "SELECT count(*) FROM temp.round_operation_fault_hits") == 0L)
        check(scalar(raw, "SELECT enabled FROM temp.round_operation_fault_gate WHERE id=1") == 0L)
        val targets = attempt.targets
        val originalText = when (attempt.kind) {
            Kind.CREATION_FLAG -> text(raw, "SELECT intentJson FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, targets.requestId)).also {
                check("\"initial_creation\":true" in it)
            }
            Kind.ROOT_SOURCE -> text(raw, "SELECT referenceUuid FROM main.sync_outbox WHERE id=?", arrayOf(targets.queueId)).also { check(it != "TIMER") }
            Kind.BAD_METADATA -> text(raw, "SELECT metadataHash FROM main.next_challenge_state WHERE id=1").also { check(it != "bad") }
            else -> null
        }
        if (attempt.kind in setOf(Kind.ABSENT_RECEIPT, Kind.ABSENT_TRANSMISSION)) {
            check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, targets.requestId)) == 0L)
            check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, targets.requestId)) == 0L)
        }
        if (attempt.kind == Kind.WRONG_METRIC) {
            check(scalar(raw, "SELECT count(*) FROM main.metrics") > 0L)
            check(scalar(raw, "SELECT count(*) FROM main.metrics WHERE name='wrong side effect'") == 0L)
        }
        if (attempt.kind == Kind.DEFERRED_END) {
            check(scalar(raw, "PRAGMA foreign_keys") == 1L)
            check(scalar(raw, "SELECT count(*) FROM temp.round_operation_fault_parent") == 0L)
            check(scalar(raw, "SELECT count(*) FROM temp.round_operation_fault_child") == 0L)
        }
        raw.execSQL("UPDATE temp.round_operation_fault_gate SET enabled=1,mode=?,factUuid=?,requestId=?,queueId=?,roundUuid=?,insertedBirthUuid=?,accountId=?,serverId=?,epoch=?,deviceId=? WHERE id=1",
            arrayOf<Any?>(attempt.kind.name, targets.factUuid, targets.requestId, targets.queueId, targets.roundUuid,
                targets.insertedBirthUuid, targets.accountId, targets.serverId, targets.epoch, targets.deviceId))
        check(scalar(raw, "SELECT changes()") == 1L)
        attempt.native = raw; writer.set(attempt)
        val result = action() // Original SQLite statement and all real TEMP/main triggers, exactly once.
        check(raw.inTransaction() && scalar(raw, "SELECT changes()") == 1L)
        check(scalar(raw, "SELECT count(*) FROM temp.round_operation_fault_hits") == 1L)
        val actorKey = text(raw, "SELECT actorKey FROM temp.round_operation_fault_hits WHERE id=1")
        check(text(raw, "SELECT actor FROM temp.round_operation_fault_hits WHERE id=1") == actor.name)
        when (actor) {
            Actor.ORIGIN -> check(scalar(raw, "SELECT count(*) FROM main.next_request_origins o JOIN main.sync_outbox q ON q.id=o.queueId WHERE o.kind=? AND o.requestId=? AND q.entityUuid=?",
                arrayOf(NEXT_OPERATION, actorKey, targets.factUuid)) == 1L)
            Actor.BIRTH -> check(actorKey == targets.factUuid && scalar(raw, "SELECT count(*) FROM main.next_challenge_births WHERE entityType='activity_event' AND entityUuid=?", arrayOf(actorKey)) == 1L)
            Actor.ACCEPTANCE -> check(actorKey == targets.requestId && scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, actorKey)) == 1L)
        }
        when (attempt.kind) {
            Kind.LATE_BIRTH -> check(scalar(raw, "SELECT count(*) FROM main.next_challenge_births WHERE entityType='activity_event' AND entityUuid=? AND roundUuid=?", arrayOf(targets.insertedBirthUuid, targets.roundUuid)) == 1L)
            Kind.CREATION_FLAG -> check(text(raw, "SELECT intentJson FROM main.next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, targets.requestId)) == requireNotNull(originalText).replace("\"initial_creation\":true", "\"initial_creation\":false"))
            Kind.ABSENT_RECEIPT -> check(scalar(raw, "SELECT count(*) FROM main.next_acceptances WHERE kind=? AND requestId=? AND originHash='late-origin' AND transmissionHash='late-wire' AND resultHash='late-result' AND resultJson='{}'", arrayOf(NEXT_OPERATION, targets.requestId)) == 1L)
            Kind.ABSENT_TRANSMISSION -> check(scalar(raw, "SELECT count(*) FROM main.next_transmissions WHERE kind=? AND requestId=? AND queueId=? AND protocol=5 AND accountId=? AND serverInstanceId=? AND syncEpoch=? AND deviceId=? AND wireHash=? AND wireBytes=X'7b7d'",
                arrayOf<Any?>(NEXT_OPERATION, targets.requestId, targets.queueId, targets.accountId, targets.serverId, targets.epoch, targets.deviceId, "0".repeat(64))) == 1L)
            Kind.WRONG_METRIC -> check(scalar(raw, "SELECT count(*) FROM main.metrics WHERE name='wrong side effect'") > 0L &&
                scalar(raw, "SELECT count(*) FROM main.metrics WHERE name<>'wrong side effect'") == 0L)
            Kind.BAD_METADATA -> check(text(raw, "SELECT metadataHash FROM main.next_challenge_state WHERE id=1") == "bad")
            Kind.DEFERRED_END -> {
                check(scalar(raw, "SELECT count(*) FROM temp.round_operation_fault_child WHERE id=1 AND parent=99") == 1L)
                raw.query("PRAGMA temp.foreign_key_check(round_operation_fault_child)").use { c ->
                    check(c.moveToFirst() && c.getString(0) == "round_operation_fault_child" && c.getLong(1) == 1L &&
                        c.getString(2) == "round_operation_fault_parent" && !c.moveToNext())
                }
            }
            Kind.COHERENT_CURSOR -> {
                check(scalar(raw, "SELECT count(*) FROM main.next_sync_state s JOIN temp.round_operation_fault_hits h ON h.id=s.id WHERE s.cursor=h.beforeCursor+1 AND s.generation=h.beforeGeneration+1") == 1L)
                check(scalar(raw, "SELECT count(*) FROM main.next_challenge_state s JOIN temp.round_operation_fault_hits h ON h.id=s.id WHERE s.cursor=h.beforeChallengeCursor+1 AND s.generation=h.beforeChallengeGeneration+1") == 1L)
            }
            Kind.ROOT_SOURCE -> check(text(raw, "SELECT referenceUuid FROM main.sync_outbox WHERE id=?", arrayOf(targets.queueId)) == "TIMER")
        }
        attempt.effectVerified = true
        check(attempt.statementsReturned.incrementAndGet() == 1)
        return result
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null
        var adapted: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw; opened = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val actor = Actor.values().singleOrNull { it.statement == sql } ?: return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() = execute(raw, actor) { statement.execute() }
                            override fun executeInsert() = execute(raw, actor) { statement.executeInsert() }
                            override fun executeUpdateDelete() = execute(raw, actor) { statement.executeUpdateDelete() }
                        }
                    }
                    override fun execSQL(sql: String) {
                        val actor = Actor.values().singleOrNull { it.statement == sql }
                        if (actor == null) raw.execSQL(sql) else execute(raw, actor) { raw.execSQL(sql) }
                    }
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
                        val actor = Actor.values().singleOrNull { it.statement == sql }
                        if (actor == null) raw.execSQL(sql, bindArgs) else execute(raw, actor) { raw.execSQL(sql, bindArgs) }
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
                            attempt.constraints.incrementAndGet()
                            throw failure // The very same engine exception; never a synthetic failure.
                        } finally {
                            if (!raw.inTransaction()) { attempt.ends.incrementAndGet(); writer.remove() }
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
