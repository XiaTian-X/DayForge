package com.dayforge.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.HabitDatabase
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Existing-database-only lifecycle below G0DomainSqlGate. The gate owns SQL admission; this
 * owner keeps the native writer reservation from pre-Room inspection through Room's actual
 * outer END. No raw connection or caller-constructible successful-END ticket is published.
 */
internal class G0RoomColdOwnerFactory(
    expectedMain: List<G0SchemaRow>,
    expectedRoomIdentity: List<Pair<Long, String>>,
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    private val onFailure: () -> Unit = {},
    private val beforeRoomOpen: () -> Unit = {},
    private val beforeActualEnd: () -> Unit = {},
    private val afterActualEnd: () -> Unit = {}
) : SupportSQLiteOpenHelper.Factory {
    enum class Phase { NEW, OPENING, PREPARING, ACTIVE, FROZEN, VALIDATED, ENDING, ENDED, KNOWN_END, READY, FAILED, CLOSED }
    enum class Control { BEGIN, SUCCESS, END }
    class Completed<T : Any> internal constructor(val value: T, val end: KnownEnd)
    private class Run(val database: HabitDatabase) { @Volatile var active = true }
    class KnownEnd private constructor(
        private val owner: G0RoomColdOwnerFactory,
        private var candidate: Any?,
        private var snapshot: G0FrozenDomainInputs?
    ) {
        private val claimed = AtomicBoolean()
        fun requireCandidate(value: Any) {
            owner.requireKnownEnd(this)
            check(value === candidate) { "G0_COLD_END_CANDIDATE_CHANGED" }
        }
        fun requireSnapshot(input: G0FrozenDomainInputs) {
            owner.requireKnownEnd(this)
            check(input === snapshot) { "G0_COLD_END_SNAPSHOT_CHANGED" }
        }
        fun claimSnapshot(input: G0FrozenDomainInputs) {
            requireSnapshot(input)
            check(claimed.compareAndSet(false, true)) { "G0_COLD_END_SNAPSHOT_ALREADY_CLAIMED" }
        }
        internal fun release() { candidate = null; snapshot = null; claimed.set(true) }
        companion object {
            // Even another trusted-code caller invoking this cannot install the identity in
            // owner's private receipt slot. Every final method checks that exact issued object.
            internal fun issue(owner: G0RoomColdOwnerFactory, candidate: Any,
                snapshot: G0FrozenDomainInputs?) = KnownEnd(owner, candidate, snapshot)
        }
    }

    private val expected = expectedMain.toList()
    private val identities = expectedRoomIdentity.toList().sortedBy { it.first }
    private val current = ThreadLocal<Run?>()
    private val used = AtomicBoolean()
    private val failureReported = AtomicBoolean()
    private val monitor = Any()
    private val nonce = UUID.randomUUID().toString()
    @Volatile private var run: Run? = null
    @Volatile private var nativeThread: Thread? = null
    private val firstFailure = AtomicReference<Throwable?>()
    private val terminal: Throwable? get() = firstFailure.get()
    @Volatile private var frozen = false
    @Volatile private var ended = false
    @Volatile private var roomBody = false
    @Volatile private var reserved = false
    @Volatile private var adopted = false
    private var marked = false
    private var markAttempted = false
    private var completedEnd = false
    private var bodyFailure: Throwable? = null
    private var setupDepth = 0
    private var platformOpened = false
    private var native: SupportSQLiteDatabase? = null
    private var engine: SupportSQLiteDatabase? = null
    private var stable: SupportSQLiteDatabase? = null
    private var candidate: Any? = null
    private var snapshot: G0FrozenDomainInputs? = null
    private var receipt: KnownEnd? = null
    private var created = false
    @Volatile var phase: Phase = Phase.NEW; private set
    val isFrozen: Boolean get() = frozen
    val hasEnded: Boolean get() = ended
    val hasFailed: Boolean get() = terminal != null || phase == Phase.CLOSED
    @Volatile var knownRollback: Boolean = false; private set
    @Volatile var callbacks: Int = 0; private set
    @Volatile var adoptedBegins: Int = 0; private set
    @Volatile var actualEnds: Int = 0; private set
    @Volatile var reservationRequestedNs: Long = 0L; private set
    @Volatile var reservationStartedNs: Long = 0L; private set
    @Volatile var endedNs: Long = 0L; private set

    init {
        require(expected.isNotEmpty() && identities.isNotEmpty())
        require(identities.map { it.first }.distinct().size == identities.size)
    }

    private fun record(failure: Throwable) {
        // A foreign getter can be rejected while an OPENING callback is waiting for it.
        // Recording that refusal must never wait for the callback's lifecycle monitor.
        firstFailure.compareAndSet(null, failure)
        phase = Phase.FAILED
        if (failureReported.compareAndSet(false, true)) {
            try { onFailure() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
    }
    fun fail(failure: Throwable): Nothing { record(failure); throw failure }
    private fun rejected(reason: String): Nothing = fail(IllegalStateException(reason))

    /** Identity only; never calls Room/helper getters or performs SQL. */
    fun requireAccess() {
        terminal?.let { throw it }
        val token = run ?: rejected("G0_COLD_OWNER_NOT_ENTERED")
        if (!token.active || current.get() !== token || nativeThread?.let { it !== Thread.currentThread() } == true ||
            phase in setOf(Phase.CLOSED, Phase.READY)) rejected("G0_COLD_OWNER_ACCESS_DENIED")
    }
    fun requireActive(db: HabitDatabase) {
        requireAccess()
        if (run?.database !== db || !adopted || !roomBody ||
            phase !in setOf(Phase.ACTIVE, Phase.FROZEN, Phase.VALIDATED))
            rejected("G0_COLD_OWNER_TRANSACTION_REQUIRED")
    }
    fun requireTransactionControl(control: Control) {
        val token = run
        // Rollback must still finish on the native thread after a body failure or cancellation.
        if (control == Control.END && adopted && !roomBody && !ended &&
            token != null && current.get() === token && nativeThread === Thread.currentThread()) return
        requireAccess()
        if (phase == Phase.OPENING) {
            if (control == Control.BEGIN || setupDepth > 0) return
            rejected("G0_COLD_OWNER_SETUP_CANNOT_END_RESERVATION")
        }
        when (control) {
            Control.BEGIN -> if (phase != Phase.PREPARING || adopted || roomBody)
                rejected("G0_COLD_OWNER_NESTED_BEGIN")
            Control.SUCCESS -> if (!adopted || roomBody || candidate == null ||
                phase != Phase.VALIDATED || marked)
                rejected("G0_COLD_OWNER_PREMATURE_SUCCESS")
            Control.END -> rejected("G0_COLD_OWNER_PREMATURE_END")
        }
    }
    fun freeze() {
        requireActive(checkNotNull(run).database)
        if (phase != Phase.ACTIVE || frozen) rejected("G0_COLD_OWNER_ALREADY_FROZEN")
        frozen = true
        phase = Phase.FROZEN
    }
    fun validated(candidate: Any, snapshot: G0FrozenDomainInputs? = null) {
        requireActive(checkNotNull(run).database)
        if (phase != Phase.FROZEN || this.candidate != null) rejected("G0_COLD_OWNER_VALIDATION_ORDER")
        this.candidate = candidate
        this.snapshot = snapshot
        phase = Phase.VALIDATED
    }
    fun requireKnownEnd(value: KnownEnd) = synchronized(monitor) {
        terminal?.let { throw it }
        check(value === receipt && ended && marked &&
            phase in setOf(Phase.KNOWN_END, Phase.READY)) { "G0_COLD_OWNER_NO_KNOWN_END" }
    }
    fun handoff(value: KnownEnd) = synchronized(monitor) {
        requireKnownEnd(value)
        check(phase == Phase.KNOWN_END) { "G0_COLD_OWNER_HANDOFF_USED" }
        receipt?.release()
        receipt = null
        candidate = null
        snapshot = null
        run = null
        phase = Phase.READY
    }

    suspend fun <T : Any> transaction(db: HabitDatabase, block: suspend () -> T): Completed<T> {
        check(used.compareAndSet(false, true)) { "G0_COLD_OWNER_ALREADY_USED" }
        check(phase == Phase.NEW && native == null) { "G0_COLD_OWNER_OPENED_EARLY" }
        val token = Run(db)
        run = token
        try {
            return withContext(current.asContextElement(token)) {
                currentCoroutineContext().ensureActive()
                val value = db.withTransaction {
                    requireAccess()
                    check(adopted && phase == Phase.ACTIVE) { "G0_COLD_OWNER_BEGIN_NOT_ADOPTED" }
                    roomBody = true
                    try {
                        val result = block()
                        currentCoroutineContext().ensureActive()
                        requireActive(db)
                        if (candidate !== result || phase != Phase.VALIDATED)
                            rejected("G0_COLD_OWNER_RETURNED_UNVALIDATED_CANDIDATE")
                        result
                    } catch (failure: Throwable) {
                        bodyFailure = failure
                        record(failure)
                        throw failure
                    } finally { roomBody = false }
                }
                currentCoroutineContext().ensureActive()
                synchronized(monitor) {
                    terminal?.let { throw it }
                    check(ended && marked && phase == Phase.ENDED && candidate === value) {
                        "G0_COLD_OWNER_END_NOT_COMPLETED"
                    }
                    val known = KnownEnd.issue(this@G0RoomColdOwnerFactory, value, snapshot)
                    receipt = known
                    phase = Phase.KNOWN_END
                    Completed(value, known)
                }
            }
        } catch (failure: Throwable) {
            knownRollback = !markAttempted && ended && completedEnd &&
                bodyFailure === failure && terminal === failure && failure.suppressed.isEmpty()
            record(failure)
            throw failure
        } finally { token.active = false }
    }

    private fun sameBoundary() {
        requireAccess()
        val raw = checkNotNull(native)
        check(nativeThread === Thread.currentThread() && reserved && raw.inTransaction()) {
            "G0_COLD_OWNER_RESERVATION_LOST"
        }
        raw.query("SELECT value FROM temp.$NONCE_TABLE").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING &&
                it.getString(0) == nonce && !it.moveToNext()) { "G0_COLD_OWNER_CONNECTION_CHANGED" }
        }
    }
    private fun inspectBeforeRoom(raw: SupportSQLiteDatabase, version: Int) {
        check(g0Schema(raw) == expected) { "G0_COLD_OWNER_SCHEMA_REJECTED" }
        check(raw.version == version) { "G0_COLD_OWNER_VERSION_REJECTED" }
        val observed = raw.query("SELECT id,identity_hash FROM main.room_master_table ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                        cursor.getType(1) == Cursor.FIELD_TYPE_STRING) { "G0_COLD_OWNER_ROOM_IDENTITY_TYPE" }
                    add(cursor.getLong(0) to cursor.getString(1))
                }
            }
        }
        check(observed == identities) { "G0_COLD_OWNER_ROOM_IDENTITY_REJECTED" }
        raw.query("SELECT id,suppressOutbox FROM main.sync_control ORDER BY id").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                it.getType(1) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L &&
                it.getLong(1) == 0L && !it.moveToNext()) { "G0_COLD_OWNER_SYNC_CONTROL_REJECTED" }
        }
    }
    private fun abortOpening(failure: Throwable) {
        record(failure)
        if (reserved && nativeThread === Thread.currentThread()) {
            reserved = false
            try {
                checkNotNull(engine ?: native).endTransaction()
                endedNs = SystemClock.elapsedRealtimeNanos()
                ended = true
                actualEnds++
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        }
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(!created) { "G0_COLD_OWNER_FACTORY_REUSED" }
        created = true
        val callback = configuration.callback
        fun database(): SupportSQLiteDatabase = checkNotNull(stable)
        fun wrapNative(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            check(native == null || native === raw) { "G0_COLD_OWNER_CONNECTION_REPLACED" }
            if (native == null) {
                native = raw
                engine = raw
                stable = object : SupportSQLiteDatabase by raw {
                    private fun target() = checkNotNull(engine)
                    override fun query(query: String): Cursor = target().query(query)
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = target().query(query, bindArgs)
                    override fun query(query: SupportSQLiteQuery): Cursor = target().query(query)
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        target().query(query, cancellationSignal)
                    override fun execSQL(sql: String) = target().execSQL(sql)
                    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = target().execSQL(sql, bindArgs)
                    override fun compileStatement(sql: String): SupportSQLiteStatement = target().compileStatement(sql)
                    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
                        target().insert(table, conflictAlgorithm, values)
                    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues,
                        whereClause: String?, whereArgs: Array<out Any?>?): Int =
                        target().update(table, conflictAlgorithm, values, whereClause, whereArgs)
                    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
                        target().delete(table, whereClause, whereArgs)
                    override fun close() {
                        if (reserved || roomBody || phase == Phase.OPENING)
                            rejected("G0_COLD_OWNER_CLOSE_BEFORE_DRAIN")
                        synchronized(monitor) {
                            if (reserved || roomBody || phase == Phase.OPENING)
                                rejected("G0_COLD_OWNER_CLOSE_BEFORE_DRAIN")
                            target().close()
                            phase = Phase.CLOSED
                        }
                    }
                    private fun begin(body: (SupportSQLiteDatabase) -> Unit) {
                        if (ended) { body(target()); return }
                        if (phase == Phase.OPENING) {
                            requireTransactionControl(Control.BEGIN)
                            try { body(target()); setupDepth++ } catch (failure: Throwable) {
                                record(failure); throw failure
                            }
                            return
                        }
                        try {
                            requireTransactionControl(Control.BEGIN)
                            sameBoundary()
                            // Room's first BEGIN adopts the existing native reservation.
                            adopted = true
                            adoptedBegins++
                            phase = Phase.ACTIVE
                        } catch (failure: Throwable) {
                            abortOpening(failure)
                            throw failure
                        }
                    }
                    override fun beginTransaction() = begin { it.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { it.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly(): Unit =
                        rejected("G0_COLD_OWNER_READONLY_UNSUPPORTED")
                    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener): Unit =
                        rejected("G0_COLD_OWNER_LISTENER_UNSUPPORTED")
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener): Unit =
                        rejected("G0_COLD_OWNER_LISTENER_UNSUPPORTED")
                    override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener): Unit =
                        rejected("G0_COLD_OWNER_READONLY_UNSUPPORTED")
                    override fun setTransactionSuccessful() {
                        if (ended) { target().setTransactionSuccessful(); return }
                        if (phase == Phase.OPENING) {
                            requireTransactionControl(Control.SUCCESS)
                            try { target().setTransactionSuccessful() } catch (failure: Throwable) {
                                record(failure); throw failure
                            }
                            return
                        }
                        requireTransactionControl(Control.SUCCESS)
                        sameBoundary()
                        markAttempted = true
                        try { target().setTransactionSuccessful(); marked = true } catch (failure: Throwable) {
                            record(failure); throw failure
                        }
                    }
                    override fun endTransaction() {
                        if (ended) { target().endTransaction(); return }
                        if (phase == Phase.OPENING) {
                            requireTransactionControl(Control.END)
                            try { target().endTransaction(); setupDepth-- } catch (failure: Throwable) {
                                record(failure); throw failure
                            }
                            return
                        }
                        requireTransactionControl(Control.END)
                        var endFailure: Throwable? = null
                        try {
                            if (marked) {
                                terminal?.let { throw it }
                                beforeActualEnd()
                                terminal?.let { throw it }
                            }
                        } catch (failure: Throwable) {
                            // A successful marker is already native. A failed final hook is
                            // therefore UNKNOWN, never permission to claim a rollback.
                            record(failure)
                            endFailure = failure
                        }
                        // A failed hook still drains the engine. Keep the close barrier until
                        // END has returned or failed, and preserve both failures if cleanup fails.
                        phase = if (terminal == null) Phase.ENDING else Phase.FAILED
                        try {
                            target().endTransaction()
                            endedNs = SystemClock.elapsedRealtimeNanos()
                            ended = true
                            actualEnds++
                            if (terminal == null) phase = Phase.ENDED
                            afterActualEnd()
                            completedEnd = true
                        } catch (failure: Throwable) {
                            record(failure)
                            val first = endFailure
                            if (first == null) endFailure = failure
                            else if (first !== failure) first.addSuppressed(failure)
                        } finally { reserved = false }
                        endFailure?.let { throw it }
                    }
                }
            }
            return database()
        }
        val guarded = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                val raw = db
                terminal?.let { throw it }
                requireAccess()
                check(nativeThread == null && !reserved)
                nativeThread = Thread.currentThread()
                phase = Phase.OPENING
                val wrapped = wrapNative(raw)
                raw.setForeignKeyConstraintsEnabled(true)
                raw.execSQL("PRAGMA temp_store = MEMORY")
                raw.execSQL("PRAGMA recursive_triggers = 1")
                reservationRequestedNs = SystemClock.elapsedRealtimeNanos()
                raw.beginTransactionNonExclusive()
                reservationStartedNs = SystemClock.elapsedRealtimeNanos()
                reserved = true
                try {
                    inspectBeforeRoom(raw, callback.version)
                    raw.execSQL("CREATE TEMP TABLE $NONCE_TABLE(value TEXT NOT NULL)")
                    raw.execSQL("INSERT INTO temp.$NONCE_TABLE VALUES(?)", arrayOf(nonce))
                    callback.onConfigure(wrapped)
                    sameBoundary()
                } catch (failure: Throwable) {
                    abortOpening(failure)
                    throw failure
                }
            }
            override fun onCreate(db: SupportSQLiteDatabase): Unit = rejected("G0_COLD_OWNER_CREATE_UNSUPPORTED")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
                rejected("G0_COLD_OWNER_MIGRATION_UNSUPPORTED")
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
                rejected("G0_COLD_OWNER_MIGRATION_UNSUPPORTED")
            override fun onOpen(db: SupportSQLiteDatabase) {
                sameBoundary()
                check(db === native)
                platformOpened = true
            }
            override fun onCorruption(db: SupportSQLiteDatabase): Unit =
                rejected("G0_COLD_OWNER_CORRUPTION_PRESERVED")
        }
        val helper = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(guarded)
            .noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper {
            @Volatile private var opening = false
            @Volatile private var closed = false
            override val databaseName get() = helper.databaseName
            override val writableDatabase get() = open()
            override val readableDatabase get() = open()
            private fun open(): SupportSQLiteDatabase {
                // Room fetches its helper again from finally before calling END. A sticky
                // validation error must not strand the still-live native reservation.
                if (!closed && adopted && !roomBody && !ended &&
                    current.get() === run && nativeThread === Thread.currentThread()) return database()
                // Room schedules exact TEMP maintenance immediately after END. Returning the
                // same wrapper grants no SQL permission: the outer gate remains frozen.
                if (ended && !closed) return database()
                requireAccess()
                return synchronized(monitor) {
                    terminal?.let { throw it }
                    check(!closed) { "G0_COLD_OWNER_CLOSED" }
                    if (platformOpened && !opening) return@synchronized database()
                    check(!opening) { "G0_COLD_OWNER_REENTRANT_GETTER" }
                    opening = true
                    try {
                        val operated = helper.writableDatabase
                        check(platformOpened && operated.inTransaction())
                        // The delegate may add timing/fault wrappers. Preserve its operational
                        // END path while keeping the callback/getter wrapper identity stable.
                        engine = operated
                        sameBoundary()
                        beforeRoomOpen()
                        callback.onOpen(database())
                        callbacks++
                        sameBoundary()
                        check(setupDepth == 0) { "G0_COLD_OWNER_SETUP_TRANSACTION_LEAK" }
                        check(g0Schema(checkNotNull(native)) == expected) { "G0_COLD_OWNER_POST_ROOM_SCHEMA" }
                        check(g0Number(checkNotNull(native), "PRAGMA foreign_keys") == 1L)
                        check(g0Number(checkNotNull(native), "PRAGMA recursive_triggers") == 1L)
                        phase = Phase.PREPARING
                        database()
                    } catch (failure: Throwable) {
                        abortOpening(failure)
                        try { helper.close(); closed = true } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    } finally { opening = false }
                }
            }
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                check(phase == Phase.NEW && !closed && native == null) { "G0_COLD_OWNER_CONFIGURATION_CLOSED" }
                helper.setWriteAheadLoggingEnabled(enabled)
            }
            override fun close() {
                if (reserved || roomBody || opening) rejected("G0_COLD_OWNER_CLOSE_BEFORE_DRAIN")
                synchronized(monitor) {
                    if (reserved || roomBody || opening) rejected("G0_COLD_OWNER_CLOSE_BEFORE_DRAIN")
                    helper.close()
                    closed = true
                    phase = Phase.CLOSED
                }
            }
        }
    }
    companion object {
        private const val NONCE_TABLE = "g0_room_cold_owner_identity"
        val expectedTempSchemaRow: List<String?> = listOf("temp", "table", NONCE_TABLE, NONCE_TABLE,
            "CREATE TABLE $NONCE_TABLE(value TEXT NOT NULL)")
    }
}
