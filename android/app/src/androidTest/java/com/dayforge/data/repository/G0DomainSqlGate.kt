package com.dayforge.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.HabitDatabase
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Isolated real-domain SQL adapter. This is a separate boundary from the model gate. The trusted owner retains this capability; SQL clients receive only
 * wrapped interfaces. No runtime SQL registration, SQL prefix parser, reflection or delegate API.
 * Boot is one-way: after sealing, opening again cannot restore unrestricted initialization.
 */
internal class G0DomainSqlGate(
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    private val compactJournal: Boolean = true,
    private val recurringReads: Boolean = false,
    private val countWrites: Boolean = false,
    private val householdCountWrites: Boolean = false,
    private val coldLifecycle: G0RoomColdOwnerFactory? = null,
    private val countCertificateScope:Boolean = false,
    internal val countBlockScope:Boolean = false,
    internal val countRootScope:Boolean = false,
    internal val countRootWriteScope:Boolean = false
) {
    init {require(!householdCountWrites || countWrites);require(!countCertificateScope || householdCountWrites && recurringReads)
        require(!countBlockScope || householdCountWrites && recurringReads && !countCertificateScope && (coldLifecycle==null || countRootScope))
        require(!countRootScope || countBlockScope)
        require(!countRootWriteScope || countRootScope)}
    enum class Phase { READ, BUSINESS, FINAL, MAINTENANCE }
    class Denied(val statementHash:String?=null) : IllegalStateException("G0_SQL_DENIED")
    private class Lease(val phase: Phase, val generation: Long, var thread: Thread?, val adoptedRead:Boolean=false) {
        var opened=false
        var ended=false
        var active = true
        var writesClosed = false
    }
    private class ColdRead(
        val database: SupportSQLiteDatabase,
        val generation: Long,
        val thread: Thread
    ) {
        @Volatile var active = true
        @Volatile var failure: Throwable? = null
        fun failed(value: Throwable) = synchronized(this) {
            if (active && failure == null) failure = value
        }
    }
    private val lease = ThreadLocal<Lease?>()
    private val coldOwner = ThreadLocal<ColdRead?>()
    @Volatile private var coldBoundary: ColdRead? = null
    @Volatile private var sealed = false
    @Volatile private var invalidated = false
    @Volatile private var generation = 0L
    private var sealedGeneration = -1L
    private var wrapped: Database? = null
    private val reads = G0DomainSqlCatalog.reads + G0DomainSqlCatalog.checkpointReads +
        (if(countBlockScope)G0CountBlockSql.reads else emptyMap()) +
        (if(countRootScope)G0CountRootSql.reads else emptyMap()) +
        (if(countRootWriteScope)G0CountNativeRows.reads else emptyMap())
    private val coldCertificateReads = if(countCertificateScope)
        mapOf(G0CountCertificateSql.select to "",G0CountCertificateSql.chunkSelect to "") else emptyMap()
    // Ordinary accepted-count proof only. These are not added to any warm READ profile.
    private val coldRecurringProofReads = mapOf(
        "SELECT 1 FROM next_abandoned_requests WHERE kind=? AND requestId=? LIMIT 1" to "SS",
        "SELECT * FROM sync_outbox WHERE operationId = ?" to "S",
        "SELECT * FROM metrics ORDER BY createdAt DESC" to "",
        "SELECT * FROM habits ORDER BY createdAt DESC" to "",
        "SELECT * FROM completions ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC" to "",
        "SELECT * FROM sync_outbox WHERE deadLetteredAt IS NULL ORDER BY id" to "",
        "SELECT * FROM sync_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY deadLetteredAt, id" to "",
        "SELECT * FROM sync_entity_state WHERE entityType = ?" to "S"
    )
    private val writes = (G0DomainSqlCatalog.writes +
        (if (countWrites) G0CountSql.writes else emptyMap()) +
        (if (householdCountWrites) G0HouseholdCountSql.writes else emptyMap()) +
        (if (countBlockScope) G0CountBlockSql.writes else emptyMap()) +
        (if (countRootScope) G0CountRootSql.writes else emptyMap()))
        .mapValues { Phase.BUSINESS to it.value } +
        (if(countCertificateScope)G0CountCertificateSql.writes.mapValues {Phase.MAINTENANCE to it.value} else emptyMap())
    // TEMP maintenance is not business authority. Only these exact side-effect-free-to-main
    // templates are accepted without an owner; no table name or trigger body comes from callers.
    private val frameworkReads = setOf("PRAGMA query_only",
        "SELECT * FROM room_table_modification_log WHERE invalidated = 1")
    private val frameworkWrites = buildSet {
        add("UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1")
        // Frozen mapping from the locked HabitDatabase_Impl invalidation tracker. The prototype
        // only subscribes to metrics; all other observer DDL remains denied until covered.
        val table = "metrics"
        val index = 4
        add("INSERT OR IGNORE INTO room_table_modification_log VALUES($index, 0)")
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            val trigger = "room_table_modification_trigger_${table}_$operation"
            add("CREATE TEMP TRIGGER IF NOT EXISTS `$trigger` AFTER $operation ON `$table` BEGIN UPDATE room_table_modification_log SET invalidated = 1 WHERE table_id = $index AND invalidated = 0; END")
            add("DROP TRIGGER IF EXISTS `$trigger`")
        }
    }

    private val serial = Mutex()
    private val mutableChanges = MutableStateFlow(0L)
    // Wake-up hint only; consumers must still obtain a fresh validated publication.
    val changeSequence = mutableChanges.asStateFlow()
    @Volatile var revision: Long = 0L
        private set
    private class PromotionProbe(val trace:G0PhaseTrace,val block:()->Unit)
    private val promotionProbe=AtomicReference<PromotionProbe?>()
    /** A one-shot diagnostic barrier AFTER successful outer END and revision advance,
     * BEFORE the trusted owner's promotion. It grants no SQL or publication capability.
     * Only the exact operation trace may consume it; unknown END never reaches it.
     */
    internal fun beforeOwnerPromotionForTest(trace:G0PhaseTrace,block:()->Unit):AutoCloseable {
        val probe=PromotionProbe(trace,block)
        check(promotionProbe.compareAndSet(null,probe)) {"G0_PROMOTION_PROBE_ALREADY_INSTALLED"}
        return AutoCloseable {promotionProbe.compareAndSet(probe,null)}
    }
    sealed interface ReadStamp
    private data class Stamp(val issuer:G0DomainSqlGate,val generation:Long,val versions:Map<String,Long>):ReadStamp
    private val tableVersions=(G0DomainSqlCatalog.columns.keys +
        (if(countCertificateScope)G0CountCertificateSql.tables.keys else emptySet()) +
        (if(countBlockScope)G0CountBlockSql.tables.keys else emptySet()) +
        (if(countRootScope)G0CountRootSql.tables.keys else emptySet())).associateWith {0L}.toMutableMap()
    fun readStamp():ReadStamp {
        check(active().phase==Phase.READ);requireSealedConnection()
        return Stamp(this,generation,tableVersions.toMap())
    }
    fun changedTables(stamp:ReadStamp):Set<String> {
        check(active().phase==Phase.READ);requireSealedConnection()
        val prior=stamp as? Stamp ?: throw Denied()
        if(prior.issuer!==this || prior.generation!=generation) throw Denied()
        return tableVersions.keys.filterTo(linkedSetOf()) { tableVersions[it]!=prior.versions[it] }
    }
    private var journal: G0DomainJournal? = null
    private var candidate: List<G0DomainJournal.Change>? = null
    /**
     * Narrow boot-time read capability inside an ALREADY owned Room transaction. It neither
     * opens/ends that transaction nor certifies business validity. The global boundary rejects
     * other threads and retained handles too; the thread-local alone would not close boot SQL.
     * Same-thread structured reads may inherit this capability; original evidence-session Job
     * ownership is separate and is never granted or extended by this primitive.
     */
    suspend fun <T> coldRead(db: HabitDatabase, block: suspend () -> T): T {
        val token = synchronized(this) {
            if (coldBoundary != null || sealed || invalidated || lease.get() != null ||
                coldOwner.get() != null) deny()
            val connection = wrapped ?: deny()
            if (!db.inTransaction() || db.openHelper.writableDatabase !== connection ||
                !connection.ownerDatabase().inTransaction()) deny()
            ColdRead(connection.ownerDatabase(), generation, Thread.currentThread()).also {
                coldBoundary = it
            }
        }
        try {
            return withContext(coldOwner.asContextElement(token)) {
                requireCold(token, token.database)
                val result = block()
                currentCoroutineContext().ensureActive()
                // Linearize successful completion against a concurrently rejected access.
                // Otherwise another thread could taint between the final check and expiry.
                synchronized(this@G0DomainSqlGate) {
                    synchronized(token) {
                        requireCold(token, token.database)
                        token.failure?.let { throw it }
                        token.active = false
                        coldBoundary = null
                    }
                }
                result
            }
        } catch (failure: Throwable) {
            token.failed(failure)
            throw failure
        } finally {
            synchronized(this) {
                token.active = false
                if (coldBoundary === token) coldBoundary = null
            }
        }
    }
    fun requireColdRead(db: HabitDatabase) = observeCold {
        val token = coldBoundary ?: deny()
        requireCold(token, token.database)
        if (!db.inTransaction() || db.openHelper.writableDatabase !== wrapped) deny()
    }
    /** Fixed bytes-source lifetime only; not root/business authority. Captures the actual
     * private lease, never a caller generation or the weaker inTransaction boolean alone. */
    internal fun captureBlockReadBoundary():()->Unit {
        if(!countRootScope)deny()
        val connection=wrapped ?: deny()
        val cold=coldBoundary
        if(cold!=null) {
            requireCold(cold,connection.ownerDatabase())
            return { if(wrapped!==connection)deny();requireCold(cold,connection.ownerDatabase()) }
        }
        val captured=active()
        if(captured.phase!=Phase.READ && !(countRootWriteScope && captured.phase==Phase.BUSINESS))deny()
        requireSealedConnection()
        if(!connection.ownerDatabase().inTransaction())deny()
        return {
            if(wrapped!==connection || lease.get()!==captured || active()!==captured ||
                (captured.phase!=Phase.READ && !(countRootWriteScope && captured.phase==Phase.BUSINESS)) ||
                !connection.ownerDatabase().inTransaction())deny()
            requireSealedConnection()
        }
    }
    fun failColdRead(failure: Throwable): Nothing {
        coldBoundary?.failed(failure)
        coldOwner.get()?.failed(failure)
        if (coldLifecycle != null && !sealed) coldLifecycle.fail(failure)
        throw failure
    }
    private fun deny(statementHash: String? = null): Nothing {
        failColdRead(Denied(statementHash))
    }
    private fun requireCold(token: ColdRead, database: SupportSQLiteDatabase) {
        if (!token.active || coldBoundary !== token || coldOwner.get() !== token ||
            token.database !== database || token.generation != generation ||
            token.thread !== Thread.currentThread() || invalidated || sealed ||
            wrapped?.ownerDatabase() !== database || !database.inTransaction()) deny()
        token.failure?.let { throw it }
    }
    private inline fun <T> observeCold(block: () -> T): T {
        val token = coldBoundary
        return try { block() } catch (failure: Throwable) {
            token?.failed(failure)
            coldOwner.get()?.failed(failure)
            throw failure
        }
    }
    private fun rejectColdControl() {
        if (coldBoundary != null || coldOwner.get() != null) deny()
        if (!sealed && coldLifecycle != null) {
            if (coldLifecycle.isFrozen) deny()
            coldLifecycle.requireAccess()
        }
    }
    /** Existing DB only. Initialization remains private until the actual owner END is known. */
    suspend fun <T : Any> ownedColdTransaction(
        db: HabitDatabase,
        schema: List<List<String?>>,
        block: suspend () -> T
    ): G0RoomColdOwnerFactory.Completed<T> {
        val owner = requireNotNull(coldLifecycle)
        check(!sealed && !invalidated && journal == null)
        return try {
            owner.transaction(db) {
                val withNonce = (schema + listOf(G0RoomColdOwnerFactory.expectedTempSchemaRow))
                    .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
                installJournal(withNonce)
                owner.freeze()
                coldRead(db) {
                    val result = block()
                    requireNotNull(journal).requireNoPendingChanges()
                    result
                }
            }
        } catch (failure: Throwable) {
            invalidateConnection()
            throw failure
        }
    }
    fun sealCold(end: G0RoomColdOwnerFactory.KnownEnd): ReadStamp {
        val owner = requireNotNull(coldLifecycle)
        owner.requireKnownEnd(end)
        check(!sealed && !invalidated && coldBoundary == null)
        requireNotNull(journal).committed(emptyList())
        val stamp = sealCore()
        owner.handoff(end)
        return stamp
    }
    suspend fun installJournal(expected: List<List<String?>>) {
        rejectColdControl()
        check(!sealed && journal == null)
        journal = G0DomainJournal(checkNotNull(wrapped).ownerDatabase(),compactJournal,countCertificateScope,countBlockScope,countRootScope).also { it.install(expected) }
    }
    suspend fun changes(): List<G0DomainJournal.Change> {
        active(); requireSealedConnection()
        return requireNotNull(journal).pending()
    }
    private suspend fun <T> stage(name: String, block: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        return if (trace == null) block() else trace.stage("domain_$name", block)
    }
    suspend fun certifyChanges(validate: (List<G0DomainJournal.Change>) -> Unit) {
        check((active().phase == Phase.BUSINESS || countCertificateScope && active().phase == Phase.MAINTENANCE) && !active().writesClosed)
        val changes = stage("certify_capture") { changes() }
        G0ProductionWitnessColumns.rejectChanges(changes)
        if(active().phase==Phase.MAINTENANCE)check(changes.all {it.table in G0CountCertificateSql.tables}) {"G0_CERTIFICATE_CHANGED_PRIMARY"}
        stage("certify_validate") { validate(changes) }
        candidate = changes
    }
    fun seal():ReadStamp {
        if (coldLifecycle != null) deny()
        rejectColdControl()
        return sealCore()
    }
    private fun sealCore():ReadStamp {
        check(!sealed && !invalidated); sealedGeneration = generation; sealed = true
        // The trusted cold owner calls this only after its successful validation transaction.
        return Stamp(this,generation,tableVersions.toMap())
    }
    fun invalidateConnection() {
        coldBoundary?.failed(Denied())
        invalidated = true; generation++
        mutableChanges.update { Math.addExact(it,1L) }
    }
    // Only the trusted owner receives Room/helper/factory and phase capabilities. SQL consumers
    // receive this narrower interface, never the Room helper (which owns TEMP maintenance).
    fun sqlClient(): SupportSQLiteDatabase {
        val current = checkNotNull(wrapped)
        coldBoundary?.let { requireCold(it, current.ownerDatabase()) }
        return current.client()
    }
    fun requireSealedConnection() { if (invalidated || !sealed || generation != sealedGeneration) deny() }
    suspend fun <T> transaction(db: HabitDatabase, phase: Phase, committed: (List<G0DomainJournal.Change>) -> Unit = {}, block: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        val queued = trace?.now()
        return serial.withLock {
            if (queued != null) trace.record("domain_sql_wait",queued,true)
            if (trace == null) transactionOwned(db,phase,committed,block)
            else trace.stage("domain_sql_hold") { transactionOwned(db,phase,committed,block) }
        }
    }
    /** Trusted caller already owns the account lock. The original read scope owns exactly one
     * Room transaction; this bridge owns SQL admission and serialization, never a borrowed scope.
     * No grant is usable before begin or after end. Nested/repeated transactions stay forbidden.
     */
    suspend fun <T> adoptReadTransaction(db:HabitDatabase,block:suspend ()->T):T {
        val trace=G0PhaseTrace.current.get();val queued=trace?.now()
        return serial.withLock {
            if(queued!=null) trace.record("domain_sql_wait",queued,true)
            stage("sql_hold") { adoptReadOwned(db,block) }
        }
    }
    private suspend fun <T> adoptReadOwned(db:HabitDatabase,block:suspend ()->T):T {
        requireSealedConnection();check(!db.inTransaction() && lease.get()==null)
        val token=Lease(Phase.READ,generation,null,adoptedRead=true)
        try {
            val result=withContext(lease.asContextElement(token)) { block() }
            check(token.opened && token.ended && !db.inTransaction()) { "G0_READ_TRANSACTION_NOT_COMPLETED" }
            requireSealedConnection();requireNotNull(journal).committed(emptyList())
            return result
        } catch(failure:Throwable) {
            if(token.opened) invalidateConnection()
            throw failure
        } finally { token.active=false }
    }
    private suspend fun <T> transactionOwned(db: HabitDatabase, phase: Phase, committed: (List<G0DomainJournal.Change>) -> Unit, block: suspend () -> T): T {
        requireSealedConnection()
        candidate = null
        var entered = false
        try {
            val result = db.withTransaction {
                check(lease.get() == null)
                val token = Lease(phase, generation, Thread.currentThread())
                entered = true
                try { withContext(lease.asContextElement(token)) {
                    stage("entry_audit") {
                        requireNotNull(journal).checkConnection()
                        check(requireNotNull(journal).pending().isEmpty()) { "G0_DOMAIN_UNADMITTED_CHANGE" }
                    }
                    val result = stage("business") { block() }
                    stage("exit_audit") {
                        val actual = requireNotNull(journal).pending()
                        check(actual == (candidate ?: emptyList<G0DomainJournal.Change>())) { "G0_DOMAIN_UNVALIDATED_CHANGE" }
                    }
                    result
                } } finally { token.active = false }
            }
            stage("post_commit") {
                requireSealedConnection()
                requireNotNull(journal).committed(candidate.orEmpty())
                if (!candidate.isNullOrEmpty()) {
                    revision++
                    candidate.orEmpty().map { it.table }.toSet().forEach { table ->
                        tableVersions[table]=Math.addExact(tableVersions.getValue(table),1L)
                    }
                    val probe=promotionProbe.get()
                    if(probe!=null && G0PhaseTrace.current.get()===probe.trace &&
                        promotionProbe.compareAndSet(probe,null)) {
                        check(!db.inTransaction()) {"G0_PROMOTION_BEFORE_ACTUAL_END"}
                        probe.block()
                    }
                }
                committed(candidate.orEmpty())
                // Notify only after the trusted owner's proof/read model has advanced.
                if (candidate.orEmpty().any {it.table in G0DomainSqlCatalog.columns}) mutableChanges.update { Math.addExact(it,1L) }
            }
            return result
        } catch (failure: Throwable) {
            // Fail closed even for a known rollback until scoped recovery is independently proven.
            if (entered) invalidateConnection()
            throw failure
        } finally { candidate = null }
    }
    suspend fun <T> finalRead(block: suspend () -> T): T {
        val parent = active()
        check((parent.phase==Phase.BUSINESS || countCertificateScope && parent.phase==Phase.MAINTENANCE) && !parent.writesClosed)
        parent.writesClosed = true
        val token = Lease(Phase.FINAL, parent.generation, parent.thread)
        try { return withContext(lease.asContextElement(token)) { stage("final_auth") { block() } } }
        finally { token.active = false }
    }
    private fun active(): Lease {
        val token = lease.get() ?: deny()
        if (!token.active || token.generation != generation || token.thread !== Thread.currentThread() || token.adoptedRead && (!token.opened || token.ended)) deny()
        return token
    }
    private fun checkSql(sql: String, args: List<Any?>, query: Boolean, db: SupportSQLiteDatabase, framework: Boolean) {
        G0ColdWorkLedger.current.get()?.record(sql,query)
        val cold = coldBoundary
        if (cold != null || coldOwner.get() != null) {
            requireCold(cold ?: deny(), db)
            if (!query) deny()
            val types = reads[sql] ?: coldCertificateReads[sql] ?: if (recurringReads)
                G0RecurringSqlCatalog.reads[sql] ?: coldRecurringProofReads[sql] else null
            if (types == null || args.size != types.length) deny(nextRequestHash(sql.toByteArray()))
            args.zip(types.toList()).forEach { (arg, type) ->
                if (when (type) { 'I' -> arg !is Long && arg !is Int; 'S' -> arg !is String; 'V' -> arg != null && arg !is String && arg !is Long && arg !is Int && arg !is Double && arg !is ByteArray; else -> true }) deny()
            }
            return
        }
        if (coldLifecycle != null && !sealed) {
            // Room schedules this exact TEMP-only cleanup after actual END. It grants no
            // main-table access and must not be mistaken for the cold owner's transaction.
            if (coldLifecycle.hasEnded && framework && args.isEmpty() &&
                (if (query) sql in frameworkReads else sql in frameworkWrites)) return
            coldLifecycle.requireAccess()
            if (coldLifecycle.isFrozen) deny(nextRequestHash(sql.toByteArray()))
        }
        if (invalidated && !(framework && lease.get() == null && args.isEmpty() &&
            (if (query) sql in frameworkReads else sql in frameworkWrites))) throw Denied()
        if (!sealed) { if (!framework) throw Denied(); return }
        // These exact TEMP-only operations remain legal for the private Room owner during
        // invalidation cleanup after an uncertain COMMIT. They cannot renew any certificate.
        val beforeAdoptedBegin=lease.get()?.let { it.adoptedRead && !it.opened }==true
        if (framework && (lease.get() == null || beforeAdoptedBegin) && args.isEmpty() &&
            (if (query) sql in frameworkReads else sql in frameworkWrites)) return
        requireSealedConnection()
        val token = active()
        if (!db.inTransaction()) throw Denied()
        if (!query && token.writesClosed) throw Denied()
        val types = if (query) reads[sql] ?: if(recurringReads && token.phase==Phase.READ) G0RecurringSqlCatalog.reads[sql] else null else writes[sql]?.takeIf { it.first == token.phase }?.second
        if (types == null || args.size != types.length) throw Denied(nextRequestHash(sql.toByteArray()))
        args.zip(types.toList()).forEach { (arg, type) ->
            if (when (type) { 'I' -> arg !is Long && arg !is Int; 'S' -> arg !is String; 'V' -> arg != null && arg !is String && arg !is Long && arg !is Int && arg !is Double && arg !is ByteArray; else -> true }) throw Denied()
        }
    }
    private fun control() { rejectColdControl(); if (sealed || invalidated) throw Denied() }
    private fun transactionControl(control: G0RoomColdOwnerFactory.Control) {
        if (coldBoundary != null || coldOwner.get() != null) deny()
        if (coldLifecycle != null && !sealed) {
            if (coldLifecycle.hasEnded) return // only precise framework TEMP SQL can follow
            coldLifecycle.requireTransactionControl(control)
            return
        }
        if (sealed && lease.get() != null) throw Denied()
    }
    private fun cursor(value: Cursor, db: SupportSQLiteDatabase, framework: Boolean): Cursor {
        val owner = lease.get()?.takeUnless { framework && it.adoptedRead && !it.opened }
        val cold = coldBoundary
        fun checkCursor() {
            if (cold != null) {
                requireCold(cold, db)
                return
            }
            if (coldBoundary != null || coldOwner.get() != null) deny()
            if (!sealed && coldLifecycle != null && !framework) {
                coldLifecycle.requireAccess()
                if (coldLifecycle.isFrozen) deny()
            }
            if (invalidated && !(framework && owner == null)) throw Denied()
            if (!sealed) return
            if (framework && owner == null) return
            if (owner == null || lease.get() !== owner || active() !== owner || !db.inTransaction()) throw Denied()
        }
        // Do not subclass CursorWrapper (getWrappedCursor) or return SQLiteCursor (getDatabase).
        return object : Cursor by value {
            private fun <T> read(block: () -> T): T = observeCold { checkCursor(); block() }
            override fun getPosition(): Int = read { value.position }
            override fun getColumnIndex(columnName: String): Int = read { value.getColumnIndex(columnName) }
            override fun getColumnIndexOrThrow(columnName: String): Int = read { value.getColumnIndexOrThrow(columnName) }
            override fun getCount(): Int = read { value.count }
            override fun getColumnCount(): Int = read { value.columnCount }
            override fun getColumnNames(): Array<String> = read { value.columnNames }
            override fun getColumnName(columnIndex: Int): String = read { value.getColumnName(columnIndex) }
            override fun isBeforeFirst(): Boolean = read { value.isBeforeFirst }
            override fun isAfterLast(): Boolean = read { value.isAfterLast }
            override fun isFirst(): Boolean = read { value.isFirst }
            override fun isLast(): Boolean = read { value.isLast }
            override fun copyStringToBuffer(columnIndex: Int, buffer: android.database.CharArrayBuffer) = read { value.copyStringToBuffer(columnIndex, buffer) }
            override fun moveToPosition(position: Int): Boolean = read { value.moveToPosition(position) }
            override fun move(offset: Int) = read { value.moveToPosition(value.position + offset) }
            override fun moveToFirst() = moveToPosition(0)
            override fun moveToLast() = moveToPosition(count - 1)
            override fun moveToNext() = move(1)
            override fun moveToPrevious() = move(-1)
            override fun getString(columnIndex: Int): String? = read { value.getString(columnIndex) }
            override fun getBlob(columnIndex: Int): ByteArray? = read { value.getBlob(columnIndex) }
            override fun getLong(columnIndex: Int): Long = read { value.getLong(columnIndex) }
            override fun getInt(columnIndex: Int): Int = read { value.getInt(columnIndex) }
            override fun getShort(columnIndex: Int): Short = read { value.getShort(columnIndex) }
            override fun getFloat(columnIndex: Int): Float = read { value.getFloat(columnIndex) }
            override fun getDouble(columnIndex: Int): Double = read { value.getDouble(columnIndex) }
            override fun getType(columnIndex: Int): Int = read { value.getType(columnIndex) }
            override fun isNull(columnIndex: Int): Boolean = read { value.isNull(columnIndex) }
            override fun getExtras(): android.os.Bundle = read { value.extras }
            override fun respond(extras: android.os.Bundle): android.os.Bundle = read { value.respond(extras) }
            override fun getNotificationUri(): android.net.Uri? = read { value.notificationUri }
            override fun getNotificationUris(): MutableList<android.net.Uri>? = read {
                if (android.os.Build.VERSION.SDK_INT >= 29) value.notificationUris
                else value.notificationUri?.let { mutableListOf(it) }
            }
            override fun getWantsAllOnMoveCalls(): Boolean = read { value.wantsAllOnMoveCalls }
            override fun close() = observeCold { value.close() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun requery(): Boolean = deny()
        }
    }
    private fun wrap(raw: SupportSQLiteDatabase): Database = synchronized(this) {
        coldBoundary?.let { requireCold(it, raw) }
        wrapped?.takeIf { it.same(raw) } ?: Database(raw).also { wrapped = it; generation++ }
    }
    val factory = SupportSQLiteOpenHelper.Factory { configuration ->
        val callback = configuration.callback
        val guardedCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Framework's default callback may delete the file. Never destroy the only
                // durable evidence of an unknown result or a damaged history during G0 recovery.
                invalidateConnection()
                error("G0_DOMAIN_CORRUPTION_PRESERVED")
            }
        }
        val raw = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(guardedCallback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        object : SupportSQLiteOpenHelper {
            override val databaseName get() = raw.databaseName
            override val writableDatabase get() = wrap(raw.writableDatabase)
            override val readableDatabase get() = wrap(raw.readableDatabase)
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                // Room configures the unopened helper while building its database instance.
                if (coldLifecycle == null || wrapped != null) control()
                raw.setWriteAheadLoggingEnabled(enabled)
            }
            override fun close() {
                if (coldLifecycle?.hasFailed != true) rejectColdControl()
                generation++
                raw.close()
            }
        }
    }

    private class Bindings : SupportSQLiteProgram {
        private val values = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { values[index] = null }
        override fun bindLong(index: Int, value: Long) { values[index] = value }
        override fun bindDouble(index: Int, value: Double) { values[index] = value }
        override fun bindString(index: Int, value: String) { values[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { values[index] = value.copyOf() }
        override fun clearBindings() = values.clear()
        override fun close() = clearBindings()
        fun snapshot(): List<Any?> {
            if (values.keys.toList() != (1..values.size).toList()) throw Denied()
            return values.values.toList()
        }
    }
    private inner class Database(private val raw: SupportSQLiteDatabase, private val framework: Boolean = true) : SupportSQLiteDatabase by raw {
        fun client(): SupportSQLiteDatabase = Database(raw, false)
        private fun control() { rejectColdControl(); if (!framework) throw Denied(); this@G0DomainSqlGate.control() }
        private fun checkClientTransactionControl(control: G0RoomColdOwnerFactory.Control) {
            if (!framework) throw Denied()
            transactionControl(control)
        }
        fun ownerDatabase(): SupportSQLiteDatabase = raw
        fun same(other: SupportSQLiteDatabase) = other === raw
        override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = query(SimpleSQLiteQuery(query, bindArgs))
        override fun query(query: SupportSQLiteQuery): Cursor = query(query, null)
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor = observeCold {
            val text = query.sql // Read caller-owned SQL once; never forward mutable callbacks.
            val bindings = Bindings().also(query::bindTo).snapshot()
            checkSql(text, bindings, true, raw, framework)
            val frozen = SimpleSQLiteQuery(text, bindings.toTypedArray())
            val maintenance=framework && text in frameworkReads && bindings.isEmpty()
            val guarded=cursor(if (cancellationSignal == null) raw.query(frozen) else raw.query(frozen, cancellationSignal),raw,maintenance)
            if(maintenance) guarded else G0ObservedReadProof.current.get()?.cursor(text,bindings,guarded) ?: guarded
        }
        override fun execSQL(sql: String) = execSQL(sql, emptyArray())
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            val owned = bindArgs.map { if (it is ByteArray) it.copyOf() else it }
            checkSql(sql, owned, false, raw, framework); raw.execSQL(sql, owned.toTypedArray())
        }
        override fun compileStatement(sql: String): SupportSQLiteStatement {
            val cold = coldBoundary
            if (cold != null || coldOwner.get() != null) requireCold(cold ?: deny(), raw)
            // Delay native compilation until execution. Even statements prepared before seal or
            // in another phase must pass the current execution check, with copied current binds.
            return object : SupportSQLiteStatement {
                private val bindings = Bindings()
                private var closed = false
                private fun <T> execute(query: Boolean, action: (SupportSQLiteStatement) -> T): T = observeCold {
                    if (cold != null) requireCold(cold, raw)
                    check(!closed)
                    val owned = bindings.snapshot(); checkSql(sql, owned, query, raw, framework)
                    G0ObservedReadProof.current.get()?.invalidate() // scalar statements are not a cursor observation contract
                    raw.compileStatement(sql).use { statement ->
                        SimpleSQLiteQuery.bind(statement, owned.toTypedArray()); action(statement)
                    }
                }
                override fun execute() { execute(false) { it.execute() } }
                override fun executeInsert() = execute(false) { it.executeInsert() }
                override fun executeUpdateDelete() = execute(false) { it.executeUpdateDelete() }
                override fun simpleQueryForLong() = execute(true) { it.simpleQueryForLong() }
                override fun simpleQueryForString() = execute(true) { it.simpleQueryForString() }
                override fun bindNull(index: Int) = bindings.bindNull(index)
                override fun bindLong(index: Int, value: Long) = bindings.bindLong(index, value)
                override fun bindDouble(index: Int, value: Double) = bindings.bindDouble(index, value)
                override fun bindString(index: Int, value: String) = bindings.bindString(index, value)
                override fun bindBlob(index: Int, value: ByteArray) = bindings.bindBlob(index, value)
                override fun clearBindings() = bindings.clearBindings()
                override fun close() { closed = true; bindings.close() }
            }
        }
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long { control(); return raw.insert(table, conflictAlgorithm, values) }
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int { control(); return raw.update(table, conflictAlgorithm, values, whereClause, whereArgs) }
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int { control(); return raw.delete(table, whereClause, whereArgs) }
        private fun beginOwned(action:()->Unit) {
            val token=lease.get()?.takeIf { it.adoptedRead }
            if(token==null) { checkClientTransactionControl(G0RoomColdOwnerFactory.Control.BEGIN);action();return }
            if(!framework || token.opened || token.ended || !token.active || raw.inTransaction()) throw Denied()
            requireSealedConnection()
            if(token.generation!=generation) throw Denied()
            // opened also records attempted begin: any unknown engine outcome invalidates reuse.
            token.opened=true;token.thread=Thread.currentThread()
            try {
                action();requireNotNull(journal).requireNoPendingChanges()
            } catch(failure:Throwable) {
                invalidateConnection();token.active=false
                if(raw.inTransaction()) try { raw.endTransaction() } catch(cleanup:Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        override fun beginTransaction() = beginOwned { raw.beginTransaction() }
        override fun beginTransactionNonExclusive() = beginOwned { raw.beginTransactionNonExclusive() }
        override fun beginTransactionReadOnly() = beginOwned { raw.beginTransactionReadOnly() }
        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListener(transactionListener) }
        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListenerNonExclusive(transactionListener) }
        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) { control(); raw.beginTransactionWithListenerReadOnly(transactionListener) }
        private fun adoptedControl():Lease? {
            val token=lease.get()?.takeIf { it.adoptedRead } ?: return null
            if(!framework) throw Denied()
            requireSealedConnection();check(active()===token)
            if(!raw.inTransaction()) throw Denied()
            return token
        }
        override fun setTransactionSuccessful() {
            if(adoptedControl()==null) checkClientTransactionControl(G0RoomColdOwnerFactory.Control.SUCCESS)
            else requireNotNull(journal).requireNoPendingChanges()
            raw.setTransactionSuccessful()
        }
        override fun endTransaction() {
            val adopted=adoptedControl()
            if(adopted==null) checkClientTransactionControl(G0RoomColdOwnerFactory.Control.END)
            // Always end the engine transaction even if the read-only audit fails.
            var auditFailure:Throwable?=null
            if(adopted!=null) try { requireNotNull(journal).requireNoPendingChanges() } catch(failure:Throwable) { auditFailure=failure }
            try {
                raw.endTransaction()
                if(adopted!=null) { adopted.ended=true;adopted.active=false }
                auditFailure?.let { throw it }
            } catch (failure: Throwable) {
                // An exception can follow a real COMMIT. Never retain a warm certificate when
                // the engine did not confirm the boundary; ordinary confirmed rollback is safe.
                generation++
                throw failure
            }
        }
        override fun yieldIfContendedSafely(): Boolean { control(); return raw.yieldIfContendedSafely() }
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean { control(); return raw.yieldIfContendedSafely(sleepAfterYieldDelayMillis) }
        override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) { control(); raw.execPerConnectionSQL(sql, bindArgs) }
        override var version: Int
            get() { rejectColdControl(); return raw.version }
            set(value) { control(); raw.version = value }
        override val maximumSize: Long
            get() { rejectColdControl(); return raw.maximumSize }
        override fun needUpgrade(newVersion: Int): Boolean { rejectColdControl(); return raw.needUpgrade(newVersion) }
        override val attachedDbs: List<android.util.Pair<String, String>>?
            get() { rejectColdControl(); return raw.attachedDbs }
        override val isDatabaseIntegrityOk: Boolean
            get() { rejectColdControl(); return raw.isDatabaseIntegrityOk }
        override fun setMaximumSize(numBytes: Long): Long { control(); return raw.setMaximumSize(numBytes) }
        override var pageSize: Long
            get() { rejectColdControl(); return raw.pageSize }
            set(value) { control(); raw.pageSize = value }
        override fun setLocale(locale: Locale) { control(); raw.setLocale(locale) }
        override fun setMaxSqlCacheSize(cacheSize: Int) { control(); raw.setMaxSqlCacheSize(cacheSize) }
        override fun setForeignKeyConstraintsEnabled(enabled: Boolean) { control(); raw.setForeignKeyConstraintsEnabled(enabled) }
        override fun enableWriteAheadLogging(): Boolean { control(); return raw.enableWriteAheadLogging() }
        override fun disableWriteAheadLogging() { control(); raw.disableWriteAheadLogging() }
        override fun close() {
            if (coldLifecycle?.hasFailed != true) rejectColdControl()
            if (!framework) throw Denied()
            generation++; raw.close()
        }
    }
}
