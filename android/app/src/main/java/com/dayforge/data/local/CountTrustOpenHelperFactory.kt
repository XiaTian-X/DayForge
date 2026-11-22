package com.dayforge.data.local

import com.dayforge.domain.service.CountCommandCostTiming
import com.dayforge.domain.service.CountCommandCostTiming.Step

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Production connection boundary. Ordinary business SQL stays available and is durably dirty;
 * only the controlled kernel may maintain proof rows. No SQL-registration or raw-handle API.
 */
internal class CountTrustOpenHelperFactory(
    context: Context,
    private val delegateFactory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    internal val runtime = CountTrustRuntime(this, context.applicationContext)
    @Volatile var generation: Long = 0L
        private set
    @Volatile var isRetired: Boolean = false
        private set
    // Primary command receipts must survive ONLY optional COUNT qualification retirement.
    // Hard owner/connection/unknown-END failures remain permanently fatal for this factory.
    @Volatile private var primaryRetired: Boolean = false
    @Volatile private var nativeDatabase: SupportSQLiteDatabase? = null
    private var wrapped: Database? = null
    // A private incarnation changes even if a delegate later returns a previously seen object.
    @Volatile private var nativeIncarnation: Any = Any()
    @Volatile private var graphClosing = false
    private var graphCloseConfirmed = false
    private class CloseCompletion(val native: SupportSQLiteDatabase?, val incarnation: Any, val epoch: Long)
    private var closeCompletion: CloseCompletion? = null
    // Helper access is registered before a lazy native open starts. A null native pointer
    // alone cannot distinguish an unopened owner from an in-flight first connection.
    private var helperOpening = 0
    private var owner = WeakReference<HabitDatabase>(null)
    private val bootstrap = ThreadLocal.withInitial { 0 }
    private val observing = ThreadLocal.withInitial { 0 }
    private val transaction = ThreadLocal<Tx?>()
    private val beginning = ThreadLocal<Tx?>()
    private val sourceObservationDepth = ThreadLocal.withInitial { 0 }
    private var journal: CountTrustJournal? = null
    private var commandJournal: CountCommandJournal? = null
    private var openingSchema: List<List<String?>>? = null
    private class CommandBootstrap(val native: SupportSQLiteDatabase, val oldVersion: Int, val targetVersion: Int,
        val source: CountCommandInitializationPermit.Source) {
        var previousSchema: List<List<String?>>? = null
        var previousContents: String? = null
        var migratedControlGuard: String? = null
        var enlisted = false
        var begun = false
        var finished = false
        var active = true
    }
    private val commandBootstrap = ThreadLocal<CommandBootstrap?>()
    @Volatile private var expectedMigrationReopen: List<List<String?>>? = null

    private data class PrimaryControl(val storage: String, val generation: Long, val dirty: Long)
    private enum class PrimaryPhase { PREPARING, BUSINESS, SEALED }
    private class CommandOperation(val boundary: CountCommandJournal.Boundary, val caller: Job?, val epoch: Long,
        val start: PrimaryControl) {
        var phase = PrimaryPhase.PREPARING
        var expected: List<CountCommandJournal.Expected>? = null
        var verifiedControl: PrimaryControl? = null
    }
    private var gcPath: String? = null
    private var gcInMemory = false
    private val gcTransactions = java.util.IdentityHashMap<Tx, Boolean>() // false before native BEGIN
    private var gcUnknownEnd = false
    @Volatile private var gcRequested = true
    @Volatile private var gcAllocationEpoch = 0L
    @Volatile private var commandGcRequested = true
    @Volatile private var commandGcAllocationEpoch = 0L
    private class Tx(val epoch: Long, val write: Boolean, val native: SupportSQLiteDatabase,
        val incarnation: Any, val thread: Thread) {
        // Pure diagnostic frame captured before this original native BEGIN. Never a grant.
        val costs = CountCommandCostTiming.capture()
        var physicalEnd: PhysicalEnd? = null
        val successes = mutableListOf(false)
        var failedChild = false
        var ended = false
        var committed = false
        var installedJournal: CountTrustJournal? = null
        var installedCommandJournal: CountCommandJournal? = null
        var primaryResetPending = false
        var primaryResetFinished = false
        var resetCommandJournal: CountCommandJournal? = null
        var resetBusinessJournal: CountTrustJournal? = null
        var command: CommandOperation? = null
        var proofAllocations = false
        var commandProofAllocations = false
        var nodeCollection = false
        var sourceObservation: SourceObservation = SourceObservation.Missing
        var sourceObservationDirty = false
        var allocatorFailure: Throwable? = null
        val sources = java.util.IdentityHashMap<DatabaseOperationSource.Frame, Unit>()
        var sourceEndFailure: Throwable? = null
    }
    private sealed interface SourceObservation {
        data class Valid(val storageId: String) : SourceObservation
        data object Missing : SourceObservation
        data object Invalid : SourceObservation
    }
    private class GraphSourceIdentity(val factory: CountTrustOpenHelperFactory, val database: HabitDatabase,
        val native: SupportSQLiteDatabase, val incarnation: Any, val epoch: Long, val storageId: String)

    /** One bounded non-authoritative observation. Missing/invalid control is not a new
     * global health gate; cancellation and actual query/connection failures still propagate.
     * Used inside native onBegin before its framework logical stack is installed.
     */
    private fun observeSource(db: SupportSQLiteDatabase): SourceObservation {
        val depth = sourceObservationDepth.get() ?: 0
        sourceObservationDepth.set(depth + 1)
        return try { observeSourceRows(db) } finally { sourceObservationDepth.set(depth) }
    }
    private fun observeSourceRows(db: SupportSQLiteDatabase): SourceObservation {
        val shape = db.query("SELECT CASE WHEN typeof(type)<>'text' THEN NULL WHEN type='table' THEN 'table' ELSE NULL END," +
            "CASE WHEN typeof(sql)<>'text' THEN NULL WHEN length(CAST(sql AS BLOB))>16384 THEN NULL ELSE sql END " +
            "FROM main.sqlite_master WHERE name='count_trust_control' LIMIT 2").use { c ->
            if (!c.moveToFirst()) return SourceObservation.Missing
            val valid = c.getType(0) == Cursor.FIELD_TYPE_STRING && c.getString(0) == "table" &&
                c.getType(1) == Cursor.FIELD_TYPE_STRING && CountTrustSchema.matchesControlDefinition(c.getString(1), db.version)
            valid && !c.moveToNext()
        }
        if (!shape) return SourceObservation.Invalid
        return db.query("SELECT CASE WHEN typeof(id)='integer' THEN id ELSE NULL END," +
            "CASE WHEN typeof(storageId)<>'text' THEN NULL WHEN length(CAST(storageId AS BLOB))<>36 THEN NULL ELSE storageId END," +
            "CASE WHEN typeof(generation)='integer' THEN generation ELSE NULL END," +
            "CASE WHEN typeof(dirty)='integer' THEN dirty ELSE NULL END FROM main.count_trust_control LIMIT 2").use { c ->
            if (!c.moveToFirst() || c.getType(0) != Cursor.FIELD_TYPE_INTEGER || c.getLong(0) != 1L ||
                c.getType(1) != Cursor.FIELD_TYPE_STRING || c.getType(2) != Cursor.FIELD_TYPE_INTEGER ||
                c.getLong(2) < 0L || c.getType(3) != Cursor.FIELD_TYPE_INTEGER || c.getLong(3) !in 0L..1L)
                return@use SourceObservation.Invalid
            val storage = c.getString(1)
            if (!com.dayforge.domain.model.isContractUuid(storage) || c.moveToNext()) SourceObservation.Invalid
            else SourceObservation.Valid(storage)
        }
    }
    internal fun captureGraphSource(database: HabitDatabase): Any {
        val tx = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
        val db = native()
        check(owner.get() === database && tx.native === db && tx.incarnation === nativeIncarnation &&
            tx.epoch == generation && !tx.ended && database.inTransaction() && db.inTransaction()) {
            "COUNT_TRUST_SOURCE_CAPTURE_EXPIRED"
        }
        val storage = observeSource(db) as? SourceObservation.Valid ?: error("COUNT_TRUST_CONTROL_INVALID")
        return GraphSourceIdentity(this, database, db, nativeIncarnation, generation, storage.storageId)
    }
    private fun requireSourceIdentity(source: DatabaseOperationSource.Frame, db: SupportSQLiteDatabase): GraphSourceIdentity {
        val identity = source.identity as? GraphSourceIdentity ?: error("DATABASE_EPOCH_INPUT_REQUIRED")
        check(identity.factory === this && identity.database === owner.get() && identity.native === db &&
            identity.incarnation === nativeIncarnation && identity.epoch == generation && db.isOpen) {
            "DATABASE_EPOCH_INPUT_EXPIRED"
        }
        return identity
    }
    private fun requireSource(tx: Tx, source: DatabaseOperationSource.Frame, first: Boolean,
        currentUse: Boolean = true) {
        if (currentUse) source.requireLive() else source.requireCurrent()
        val identity = requireSourceIdentity(source, tx.native)
        check(!tx.ended && tx.native === nativeDatabase && tx.incarnation === nativeIncarnation) {
            "DATABASE_EPOCH_INPUT_EXPIRED"
        }
        if (first && tx.sourceObservationDirty) {
            tx.sourceObservation = observeSource(tx.native); tx.sourceObservationDirty = false
        }
        check((tx.sourceObservation as? SourceObservation.Valid)?.storageId == identity.storageId) {
            "DATABASE_EPOCH_REPLICA_CHANGED"
        }
        tx.sources[source] = Unit
    }
    private fun checkSourceUse(read: Boolean) {
        // An existing query observer may itself read while the fixed native metadata read
        // is being observed. Preserve that original read-only observer contract without
        // recursively inspecting an observation which has not yet been captured. Its
        // write guard runs first and it receives neither a source nor maintenance grant.
        if (read && (observing.get() ?: 0) > 0 && (sourceObservationDepth.get() ?: 0) > 0) return
        val source = DatabaseOperationSource.current() ?: return
        source.requireLive(); requireSourceIdentity(source, native())
        val tx = transaction.get() ?: beginning.get()
        if (tx == null) {
            // A preliminary read is not a snapshot/publication grant. Source-dependent writes
            // must use their existing real Room transaction; original domain readers still
            // own the complete snapshot and final qualification before any display.
            check(read) { "DATABASE_EPOCH_SOURCE_TRANSACTION_REQUIRED" }
            return
        }
        if (!tx.sources.containsKey(source)) requireSource(tx, source, first = true)
    }
    private fun invalidateSourceObservation() {
        (transaction.get() ?: beginning.get())?.sourceObservationDirty = true
    }
    private fun checkFinalSources(tx: Tx) {
        if (tx.sources.isEmpty()) return
        if (tx.sourceObservationDirty) {
            tx.sourceObservation = observeSource(tx.native); tx.sourceObservationDirty = false
        }
        tx.sources.keys.toList().forEach { requireSource(tx, it, first = false, currentUse = false) }
    }
    /** This is physical closure evidence only. It never makes an unknown outcome known. */
    private class PhysicalEnd(val native: SupportSQLiteDatabase, val incarnation: Any,
        val txEpoch: Long, val observedEpoch: Long, val thread: Thread)
    private class GraphCloseWitness(val factory: CountTrustOpenHelperFactory, val database: HabitDatabase,
        val native: SupportSQLiteDatabase, val incarnation: Any, val epoch: Long,
        val unknown: Map<Tx, PhysicalEnd>)
    internal sealed interface GraphDrain {
        data object Busy : GraphDrain
        data object Unknown : GraphDrain
        class Ready internal constructor(internal val witness: Any) : GraphDrain
    }
    /** Pure owner bookkeeping. The host must separately drain every application operation.
     * No later connection/thread's inTransaction() is consulted as a substitute for END.
     */
    internal fun captureGraphDrain(database: HabitDatabase): GraphDrain = synchronized(this) ownerState@ {
        check(owner.get() === database) { "COUNT_TRUST_FOREIGN_DATABASE" }
        if (helperOpening != 0) return@ownerState GraphDrain.Busy
        if (graphClosing || nativeDatabase == null) return@ownerState GraphDrain.Unknown
        synchronized(gcRegistry) registry@ {
            if (readLease != null) return@registry GraphDrain.Busy
            val entries = gcTransactions.keys.toList()
            if (entries.any { it.physicalEnd == null } ) {
                return@registry if (gcUnknownEnd) GraphDrain.Unknown else GraphDrain.Busy
            }
            // Unknown BEGIN or an unowned cleanup cannot disappear merely because the map is empty.
            if (gcUnknownEnd && entries.isEmpty()) return@registry GraphDrain.Unknown
            val raw = requireNotNull(nativeDatabase)
            val evidence = java.util.IdentityHashMap<Tx, PhysicalEnd>()
            for (tx in entries) {
                val end = requireNotNull(tx.physicalEnd)
                if (end.native !== raw || end.incarnation !== nativeIncarnation || tx.native !== raw ||
                    tx.incarnation !== nativeIncarnation || end.txEpoch != tx.epoch ||
                    end.thread !== tx.thread || end.observedEpoch < tx.epoch || !tx.successes.isEmpty())
                    return@registry GraphDrain.Unknown
                evidence[tx] = end
            }
            GraphDrain.Ready(GraphCloseWitness(this, database, raw, nativeIncarnation, generation, evidence))
        }
    }
    internal fun isGraphCloseConfirmed(database: HabitDatabase): Boolean = synchronized(this) {
        check(owner.get() === database) { "COUNT_TRUST_FOREIGN_DATABASE" }
        graphCloseConfirmed
    }
    private class UnopenedGraphClose(val factory: CountTrustOpenHelperFactory, val database: HabitDatabase)
    /** No native connection ever existed, and none can start after this atomic seal. This
     * witness is only closure evidence, not an END receipt or successful initialization. */
    internal fun trySealUnopenedGraphClose(database: HabitDatabase): Any? = synchronized(this) {
        check(owner.get() === database) { "COUNT_TRUST_FOREIGN_DATABASE" }
        if (graphClosing || nativeDatabase != null || generation != 0L || helperOpening != 0) return@synchronized null
        synchronized(gcRegistry) {
            if (gcUnknownEnd || gcTransactions.isNotEmpty() || readLease != null) null
            else UnopenedGraphClose(this, database).also { graphClosing = true }
        }
    }
    internal fun requireUnopenedGraphClosed(database: HabitDatabase, witness: Any) = synchronized(this) {
        val issued = witness as? UnopenedGraphClose ?: error("COUNT_TRUST_FOREIGN_GRAPH_CLOSE")
        check(issued.factory === this && issued.database === database && owner.get() === database &&
            graphClosing && nativeDatabase == null && helperOpening == 0 && generation in 0L..1L &&
            closeCompletion?.let { it.native == null && it.incarnation === nativeIncarnation && it.epoch == generation } == true) {
            "COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED"
        }
        synchronized(gcRegistry) {
            check(!gcUnknownEnd && gcTransactions.isEmpty() && readLease == null) { "COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED" }
        }
        graphCloseConfirmed = true
    }
    private fun openHelperDatabase(open: () -> SupportSQLiteDatabase): SupportSQLiteDatabase {
        synchronized(this) { requireGraphAdmission(); helperOpening = Math.addExact(helperOpening, 1) }
        return try { readAccess(); wrap(open()) }
        finally { synchronized(this) { helperOpening--; check(helperOpening >= 0) } }
    }

    /** Atomically close native BEGIN admission against the exact physical-drain witness.
     * A Room invalidation job may have queued since capture; losing that race is Busy, never
     * permission to close its live transaction. No SQL or callback executes under monitors.
     */
    internal fun hasUnknownGraphBoundary(): Boolean = synchronized(this) { synchronized(gcRegistry) {
        gcUnknownEnd || retirementReasons.any { it in setOf(CountTrustRetirementReason.UNKNOWN_BEGIN,
            CountTrustRetirementReason.UNKNOWN_END, CountTrustRetirementReason.TRANSACTION_STATE_UNKNOWN) }
    } }
    internal fun trySealGraphClose(database: HabitDatabase, witness: Any,
        knownOnly: Boolean = false): Boolean = synchronized(this) { synchronized(gcRegistry) seal@ {
        val issued = witness as? GraphCloseWitness ?: error("COUNT_TRUST_FOREIGN_GRAPH_CLOSE")
        check(issued.factory === this && issued.database === database && owner.get() === database) {
            "COUNT_TRUST_FOREIGN_GRAPH_CLOSE"
        }
        if (knownOnly && hasUnknownGraphBoundary()) return@seal false
        val fresh = captureGraphDrain(database) as? GraphDrain.Ready ?: return@seal false
        val now = fresh.witness as GraphCloseWitness
        if (now.native !== issued.native || now.incarnation !== issued.incarnation || now.epoch != issued.epoch ||
            now.unknown.size != issued.unknown.size || now.unknown.any { (tx, end) -> issued.unknown[tx] !== end } ||
            knownOnly && now.unknown.isNotEmpty()) return@seal false
        // Keep the native registry locked through sealing. A concurrent END cannot become
        // unknown between the known-only check and closing future BEGIN/helper admission.
        graphClosing = true
        true
    } }

    /** Called only after Room.close() returns. Room may drain its own invalidation work while
     * closing; a new unknown END or changed native/epoch is not covered by this exact witness.
     */
    internal fun requireGraphClosed(database: HabitDatabase, witness: Any) {
        val issued = witness as? GraphCloseWitness ?: error("COUNT_TRUST_FOREIGN_GRAPH_CLOSE")
        val physicallyClosed = !issued.native.isOpen
        synchronized(this) {
            check(issued.factory === this && issued.database === database && owner.get() === database &&
                nativeDatabase === issued.native && nativeIncarnation === issued.incarnation &&
                graphClosing && physicallyClosed && generation == Math.addExact(issued.epoch, 1L) &&
                closeCompletion?.let { it.native === issued.native && it.incarnation === issued.incarnation &&
                    it.epoch == generation } == true) {
                "COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED"
            }
            synchronized(gcRegistry) {
                check(readLease == null && gcTransactions.size == issued.unknown.size &&
                    gcTransactions.keys.all { tx -> issued.unknown[tx] === tx.physicalEnd }) {
                    "COUNT_TRUST_GRAPH_CLOSE_UNCONFIRMED"
                }
            }
            graphCloseConfirmed = true
        }
    }
    /** Room's initialized support pool closes Database first, then its helper. Both real
     * delegates must execute. Only a completed close of this exact native incarnation and
     * epoch can suppress the second CONNECTION_CLOSED retirement. Any intervening retirement,
     * failed delegate/inspection or different native invalidates that completion instead.
     */
    private fun closeNative(expected: SupportSQLiteDatabase?, close: () -> Unit) {
        requireNotObserving(); requireNoReadSnapshot()
        val completion = synchronized(this) {
            val prior = closeCompletion
            val repeated = expected != null && prior != null && prior.native === expected && nativeDatabase === expected &&
                prior.incarnation === nativeIncarnation && prior.epoch == generation
            graphClosing = true
            graphCloseConfirmed = false
            closeCompletion = null
            if (!repeated) retire(CountTrustRetirementReason.CONNECTION_CLOSED)
            CloseCompletion(expected, nativeIncarnation, generation)
        }
        close() // Never swallow an original close failure or publish completion in finally.
        val physicallyClosed = expected == null || !expected.isOpen
        synchronized(this) {
            if (physicallyClosed && nativeDatabase === completion.native && nativeIncarnation === completion.incarnation &&
                generation == completion.epoch && helperOpening == 0) closeCompletion = completion
        }
    }
    private class ClosingCancellation : CancellationException("COUNT_TRUST_CONNECTION_EXPIRED")
    private fun requireGraphAdmission() {
        // A queued Room invalidation may reach BEGIN after this owner has sealed admission.
        // Expiration is cancellation before any native work, not an unhandled background
        // failure. Unknown native/transaction errors elsewhere keep their original types.
        if (graphClosing) throw ClosingCancellation()
    }
    private class Lease(val caller: Job?, val epoch: Long, val tx: Tx) {
        var job: Job? = null
        @Volatile var active = true
    }
    private data class Frame(val lease: Lease, val job: Job?)
    private val current = ThreadLocal<Frame?>()
    private class ReadLease(val database: HabitDatabase, val native: SupportSQLiteDatabase,
        val tx: Tx, val epoch: Long, val caller: Job?) {
        var job: Job? = null
        @Volatile var active = true
    }
    private data class ReadFrame(val lease: ReadLease)
    private val reading = ThreadLocal<ReadFrame?>()
    // The connection-wide restriction also covers callbacks/threads that do not inherit
    // the coroutine element. They cannot write while another caller freezes this snapshot.
    @Volatile private var readLease: ReadLease? = null
    private inner class ReadElement(private val lease: ReadLease) :
        ThreadContextElement<ReadFrame?>, AbstractCoroutineContextElement(ReadKey) {
        override fun updateThreadContext(context: CoroutineContext): ReadFrame? = reading.get().also {
            reading.set(ReadFrame(lease))
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: ReadFrame?) { reading.set(oldState) }
    }
    private inner class MaintenanceElement(private val lease: Lease) :
        ThreadContextElement<Frame?>, AbstractCoroutineContextElement(MaintenanceKey) {
        override fun updateThreadContext(context: CoroutineContext): Frame? = current.get().also {
            current.set(Frame(lease, context[Job]))
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Frame?) { current.set(oldState) }
    }

    internal fun attach(database: HabitDatabase) = synchronized(this) {
        requireNoReadSnapshot()
        check(owner.get() == null || owner.get() === database) { "COUNT_TRUST_OWNER_ALREADY_ATTACHED" }
        owner = WeakReference(database)
    }
    fun requireUsable() {
        check(!isRetired && nativeDatabase?.isOpen == true) { "COUNT_TRUST_RUNTIME_RETIRED" }
    }
    private fun native(): SupportSQLiteDatabase = requireNotNull(nativeDatabase) { "COUNT_TRUST_DATABASE_NOT_OPEN" }

    internal fun newRecoveryPreview(): CountTrustRecoveryPreview {
        requireNotObserving()
        return CountTrustRecoveryPreview(runtime, generation)
    }
    /** Storage identity is supplied by an already-authorized, typed control read in this actual
     * transaction. Never read SQL or the file system while holding the preview registry lock. */
    private fun recoveryStorageKey(storageId: String): CountTrustRecoveryPreview.Storage? {
        activeLease()
        // Only an explicitly in-memory database has an owner-local storage identity.
        // An unknown path may still alias another owner of a real file.
        val file = gcPath ?: if (gcInMemory) this else return null
        return CountTrustRecoveryPreview.Storage(file, storageId)
    }
    internal fun bindRecoveryPreview(preview: CountTrustRecoveryPreview, storageId: String) {
        preview.bind(runtime, generation, storageId, recoveryStorageKey(storageId))
    }
    internal fun recoveryPreviewSuppressed(storageId: String): Boolean {
        val key = recoveryStorageKey(storageId)
        val blocked = key == null || CountTrustRecoveryPreview.suppressed(key)
        if (blocked) retire(if (key == null) CountTrustRetirementReason.CONNECTION_IDENTITY_UNKNOWN
            else CountTrustRetirementReason.RECOVERY_PREVIEW, retireHandles = false)
        return blocked
    }
    private fun requireNoReadSnapshot() {
        check(readLease == null && reading.get() == null) { "COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED" }
    }
    private fun readAccess(): ReadLease? {
        val frame = reading.get() ?: return null
        val lease = frame.lease
        check(readLease === lease && lease.active && owner.get() === lease.database &&
            nativeDatabase === lease.native && lease.native.isOpen && generation == lease.epoch &&
            transaction.get() === lease.tx && !lease.tx.ended && lease.native.inTransaction() &&
            // ScopeCoroutine can resume its parent before restoring a completed child's
            // thread context. The stable scope/caller Jobs govern this no-write lifetime;
            // synchronous reads are not cancellation points for every inherited child.
            lease.caller?.isActive != false && lease.job?.isActive != false) { "COUNT_TRUST_READ_SNAPSHOT_EXPIRED" }
        return lease
    }
    /** A restriction on this actual transaction, not business authority or a reusable proof.
     * Room's same-transaction read adapters may use child Jobs; every Job remains unable to
     * mutate. Scoped cursors/statements and inherited frames expire when this call returns.
     */
    internal suspend fun <T> withReadSnapshot(database: HabitDatabase, block: suspend () -> T): T {
        requireNotObserving()
        requireNoReadSnapshot()
        check(current.get() == null) { "COUNT_TRUST_READ_SNAPSHOT_MAINTENANCE_DENIED" }
        currentCoroutineContext().ensureActive()
        val db = native()
        val tx = transaction.get()
        check(owner.get() === database && database.inTransaction() && db.inTransaction() &&
            tx != null && tx.write && !tx.ended && tx.successes.size == 1) { "COUNT_TRUST_READ_SNAPSHOT_TRANSACTION_REQUIRED" }
        val lease = ReadLease(database, db, tx, generation, currentCoroutineContext()[Job])
        synchronized(this) {
            requireNoReadSnapshot()
            readLease = lease
        }
        try {
            val result = withContext(ReadElement(lease)) {
                lease.job = currentCoroutineContext()[Job]
                check(readAccess() === lease)
                block().also {
                    currentCoroutineContext().ensureActive()
                    check(currentCoroutineContext()[Job] === lease.job && readAccess() === lease &&
                        database.inTransaction()) { "COUNT_TRUST_READ_SNAPSHOT_EXPIRED" }
                }
            }
            currentCoroutineContext().ensureActive()
            return result
        } finally {
            synchronized(this) {
                lease.active = false
                if (readLease === lease) readLease = null
            }
        }
    }
    private fun activeLease(): Lease {
        requireNoReadSnapshot()
        val frame = current.get() ?: error("COUNT_TRUST_MAINTENANCE_REQUIRED")
        val lease = frame.lease
        check(lease.active && lease.epoch == generation && lease.job === frame.job && frame.job?.isActive != false &&
            transaction.get() === lease.tx && native().inTransaction()) { "COUNT_TRUST_MAINTENANCE_EXPIRED" }
        return lease
    }
    internal suspend fun <T> maintenance(database: HabitDatabase, block: suspend () -> T): T {
        requireNotObserving()
        requireNoReadSnapshot()
        check(owner.get() === database && database.inTransaction() && native().inTransaction()) {
            "COUNT_TRUST_MAINTENANCE_TRANSACTION_REQUIRED"
        }
        current.get()?.let {
            activeLease()
            currentCoroutineContext().ensureActive()
            return block()
        }
        val tx = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
        val lease = Lease(currentCoroutineContext()[Job], generation, tx)
        try {
            return withContext(MaintenanceElement(lease)) {
                lease.job = currentCoroutineContext()[Job]
                activeLease()
                currentCoroutineContext().ensureActive()
                block().also { currentCoroutineContext().ensureActive(); activeLease() }
            }
        } finally { lease.active = false }
    }
    private fun allocatorTempSchema(db: SupportSQLiteDatabase): List<List<String?>> =
        db.query("SELECT type,name,tbl_name,rootpage,sql FROM temp.sqlite_master ORDER BY type,name").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                check((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_STRING } &&
                    cursor.getType(3) == Cursor.FIELD_TYPE_INTEGER &&
                    cursor.getType(4) in setOf(Cursor.FIELD_TYPE_NULL, Cursor.FIELD_TYPE_STRING)) {
                    "COUNT_TRUST_ALLOCATOR_TEMP_SCHEMA"
                }
                add((0..4).map { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }

    /** Only a wholly empty MAIN sequence needs the older SQLite AUTOINCREMENT workaround.
     * Never normalize a present row or make a partial historical sequence look certified.
     * No raw handle/SQL/table argument escapes this synchronous private-native operation. */
    internal fun prepareEmptyOutboxAllocators(database: HabitDatabase) {
        requireNotObserving()
        requireNoReadSnapshot()
        val lease = activeLease()
        val tx = lease.tx
        val db = native()
        check(owner.get() === database && tx.native === db && tx.incarnation === nativeIncarnation &&
            tx.epoch == generation && tx.write && !tx.ended && db.inTransaction() && database.inTransaction() &&
            !primaryRetired && tx.command == null && !tx.primaryResetPending && !tx.nodeCollection &&
            tx.successes.none { it } && tx.allocatorFailure == null && db.version == CountCommandV20Schema.VERSION) { "COUNT_TRUST_ALLOCATOR_WRITE_CONTEXT" }
        checkSourceUse(read = true)
        // A no-op is not validation. Original source/type/duplicate guards still run at
        // their original entry points; even a malformed nonempty sequence stays untouched.
        if (db.query("SELECT 1 FROM main.sqlite_sequence LIMIT 1").use { it.moveToFirst() }) return
        CountTrustSchema.validate(db, CountCommandV20Schema.VERSION)
        CountCommandStorageInitialization.verifyMandatory(db)
        val beforeSchema = readSchema(db)
        val beforeTempSchema = allocatorTempSchema(db)
        fun cookie(name: String): Long = db.query("PRAGMA $name.schema_version").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            it.getLong(0).also { _ -> check(!it.moveToNext()) }
        }
        val mainCookie = cookie("main"); val tempCookie = cookie("temp")
        val before = com.dayforge.data.repository.CountCommandRootAnchor.read(db)
        db.query("SELECT rowid FROM main.count_trust_control ORDER BY rowid").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 1L && !it.moveToNext()) {
                "COUNT_TRUST_CONTROL_INVALID"
            }
        }
        fun changes(): Long = db.query("SELECT total_changes()").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            it.getLong(0).also { value -> check(value >= 0 && !it.moveToNext()) }
        }
        // Full before-image: no rows at all. Same real write transaction; no suspended
        // DAO or caller callback is allowed between this observation and fixed writes.
        check(!db.query("SELECT rowid,name,seq FROM main.sqlite_sequence").use { it.moveToFirst() }) {
            "COUNT_TRUST_ALLOCATOR_CHANGED"
        }
        val startChanges = changes()
        checkSourceUse(read = false)
        invalidateSourceObservation()
        try {
            db.execSQL("INSERT INTO main.sqlite_sequence(rowid,name,seq) VALUES(1,'sync_outbox',0),(2,'timer_command_outbox',0)")
            db.query("SELECT rowid,name,seq FROM main.sqlite_sequence ORDER BY rowid").use { cursor ->
                for ((index, name) in listOf("sync_outbox", "timer_command_outbox").withIndex()) {
                    check(cursor.moveToNext() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                        cursor.getLong(0) == index.toLong() + 1 && cursor.getType(1) == Cursor.FIELD_TYPE_STRING &&
                        cursor.getString(1) == name && cursor.getType(2) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(2) == 0L) {
                        "COUNT_TRUST_ALLOCATOR_WRITE_CHANGED"
                    }
                }
                check(!cursor.moveToNext()) { "COUNT_TRUST_ALLOCATOR_EXTRA_WRITE" }
            }
            check(com.dayforge.data.repository.CountCommandRootAnchor.read(db) == before) {
                "COUNT_TRUST_ALLOCATOR_CONTROL_CHANGED"
            }
            check(readSchema(db) == beforeSchema && cookie("main") == mainCookie && cookie("temp") == tempCookie &&
                allocatorTempSchema(db) == beforeTempSchema &&
                Math.subtractExact(changes(), startChanges) == 2L) {
                "COUNT_TRUST_ALLOCATOR_EXTRA_WRITE"
            }
            activeLease()
        } catch (failure: Throwable) {
            // Even if a caller catches a failed preparation, a partial native write must
            // not be committed. The original rollback/END path remains responsible for
            // physically ending the transaction; no fabricated successful outcome.
            if (tx.allocatorFailure == null) tx.allocatorFailure = failure
            throw failure
        }
    }

    internal fun captureChangeBoundary(): CountTrustJournal.Boundary {
        requireUsable()
        val lease = activeLease()
        return observedJournal().capture(lease.tx, lease.caller, generation)
    }
    internal fun pendingChanges(boundary: CountTrustJournal.Boundary): List<CountTrustJournal.Change> {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().pending(boundary, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun hasPendingChanges(boundary: CountTrustJournal.Boundary): Boolean {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().hasPendingChanges(boundary, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun unchangedChallengeWindow(boundary: CountTrustJournal.Boundary): CountTrustJournal.ChallengeWindow? {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().unchangedChallengeWindow(boundary, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun sourceWindow(boundary: CountTrustJournal.Boundary, expectedSources: Int): CountTrustJournal.SourceWindow? {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().sourceWindow(boundary, lease.tx, lease.caller, generation, expectedSources) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun verifySourceWindow(window: CountTrustJournal.SourceWindow, rows: List<List<CountTrustJournal.Cell>>): Boolean {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().verifySourceWindow(window, rows, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun readCountAddEffects(boundary: CountTrustJournal.Boundary,
        expected: List<CountTrustJournal.CountAddEffect>): List<CountTrustJournal.Change>? {
        requireUsable()
        val lease = activeLease()
        return try { observedJournal().readCountAddEffects(boundary, expected, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    private fun observedJournal(): CountTrustJournal {
        return try { journal ?: CountTrustJournal(native()).also {
            it.install()
            checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }.installedJournal = it
            journal = it
        } }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    private fun primaryControl(): PrimaryControl {
        CountTrustSchema.requireControl(native())
        return native().query("SELECT storageId,generation,dirty FROM count_trust_control WHERE id=1 LIMIT 2").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getType(1) == Cursor.FIELD_TYPE_INTEGER && it.getType(2) == Cursor.FIELD_TYPE_INTEGER)
            PrimaryControl(it.getString(0), it.getLong(1), it.getLong(2)).also { _ -> check(!it.moveToNext()) }
        }
    }
    internal fun captureCommandBoundary(): CountCommandJournal.Boundary {
        check(!primaryRetired) { "COMMAND_PRIMARY_RETIRED" }
        val lease = activeLease()
        check(!lease.tx.primaryResetPending) { "COMMAND_CAPTURE_DURING_RESET" }
        lease.tx.command?.let { check(it.caller === lease.caller && it.epoch == generation); return it.boundary }
        return try {
            val boundary = primaryJournal().capture(lease.tx, lease.caller, generation)
            lease.tx.command = CommandOperation(boundary, lease.caller, generation, primaryControl())
            boundary
        } catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    private class PrimaryBusinessBoundary(val factory: CountTrustOpenHelperFactory,
        val operation: CommandOperation, val tx: Tx, val caller: Job?, val epoch: Long,
        val nativeBoundary: CountTrustJournal.Boundary) : CountTrustRuntime.PrimaryBusinessBoundary

    internal fun capturePrimaryBusinessBoundary(): CountTrustRuntime.PrimaryBusinessBoundary {
        requireNotObserving()
        val lease = activeLease()
        val operation = requireNotNull(lease.tx.command) { "COMMAND_PRIMARY_OPERATION_REQUIRED" }
        check(!primaryRetired && operation.phase == PrimaryPhase.PREPARING &&
            operation.caller === lease.caller && operation.epoch == generation &&
            !lease.tx.ended && !lease.tx.primaryResetPending) { "COMMAND_BUSINESS_CAPTURE_EXPIRED" }
        // No second TEMP collector and no weakening of captureChangeBoundary.requireUsable.
        // An old collector whose schema/data/nonce continuity broke still fails hard here.
        return try { PrimaryBusinessBoundary(this, operation, lease.tx, lease.caller, generation,
            observedJournal().capture(lease.tx, lease.caller, generation)) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }

    internal fun primaryBusinessChanges(boundary: CountTrustRuntime.PrimaryBusinessBoundary): List<CountTrustJournal.Change> {
        requireNotObserving()
        val lease = activeLease()
        val issued = boundary as? PrimaryBusinessBoundary ?: error("COMMAND_FOREIGN_BUSINESS_BOUNDARY")
        check(!primaryRetired && issued.factory === this && issued.tx === lease.tx &&
            issued.operation === lease.tx.command && issued.caller === lease.caller && issued.epoch == generation &&
            !lease.tx.ended && !lease.tx.primaryResetPending) { "COMMAND_BUSINESS_BOUNDARY_EXPIRED" }
        return try { observedJournal().pending(issued.nativeBoundary, lease.tx, lease.caller, generation) }
        catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }

    internal fun beginCommandBusiness(boundary: CountCommandJournal.Boundary) {
        val lease = activeLease(); val operation = requireNotNull(lease.tx.command)
        check(operation.boundary === boundary && operation.phase == PrimaryPhase.PREPARING && operation.caller === lease.caller)
        verifyCommandBoundary(boundary, emptyList())
        operation.phase = PrimaryPhase.BUSINESS; operation.verifiedControl = null
    }
    internal fun verifyCommandBoundary(boundary: CountCommandJournal.Boundary, expected: List<CountCommandJournal.Expected>) {
        check(!primaryRetired) { "COMMAND_PRIMARY_RETIRED" }
        val lease = activeLease(); val operation = requireNotNull(lease.tx.command)
        check(operation.boundary === boundary && operation.caller === lease.caller && operation.epoch == generation)
        try {
            primaryJournal().verify(boundary, lease.tx, lease.caller, generation, expected)
            val actual = primaryControl()
            if (operation.phase == PrimaryPhase.PREPARING) check(actual == operation.start) { "COMMAND_METADATA_WROTE_BUSINESS" }
            if (operation.phase == PrimaryPhase.SEALED) check(actual == operation.verifiedControl && expected == operation.expected)
            operation.expected = expected.toList(); operation.verifiedControl = actual
        } catch (failure: Throwable) { retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE); throw failure }
    }
    internal fun sealCommandOperation() = CountCommandCostTiming.measure(Step.SEAL) {
        val lease = activeLease(); val operation = requireNotNull(lease.tx.command)
        val last = operation.verifiedControl
        if (last != null) check(primaryControl() == last) { "COMMAND_WRITE_AFTER_VALIDATION" }
        verifyCommandBoundary(operation.boundary, operation.expected.orEmpty())
        operation.phase = PrimaryPhase.SEALED
    }
    private fun verifyCommandAtOuterCommit(tx: Tx, finalNative: Boolean = false) {
        val operation = tx.command ?: return
        check(operation.phase == PrimaryPhase.SEALED && operation.epoch == generation &&
            primaryControl() == operation.verifiedControl) { "COMMAND_COMMIT_NOT_SEALED" }
        CountCommandCostTiming.measure(tx.costs, if (finalNative) Step.NATIVE_FINAL_JOURNAL else Step.SET_SUCCESS_CHECK) {
            primaryJournal().verify(operation.boundary, tx, operation.caller, generation, requireNotNull(operation.expected))
        }
        if (finalNative) CountCommandStorageInitialization.verifyRootAnchor(native(), tx.costs)
    }
    private fun primaryJournal(): CountCommandJournal = commandJournal ?: CountCommandJournal(native()).also {
        it.install()
        checkNotNull(transaction.get()) { "COMMAND_UNOWNED_TRANSACTION" }.installedCommandJournal = it
        commandJournal = it
    }

    internal fun schemaSnapshot(): List<List<String?>> {
        activeLease()
        return readSchema(native())
    }
    internal fun retireFastPathForRecovery() {
        requireNotObserving()
        activeLease()
        // Retiring trust is permanent for this owner, including if the surrounding SQL rolls
        // back. Recovery still needs the connection and ordinary cached DAO statements.
        retire(CountTrustRetirementReason.RECOVERY_PREPARATION, retireHandles = false)
    }
    private fun readSchema(db: SupportSQLiteDatabase): List<List<String?>> =
        db.query("SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { cursor ->
            java.util.Collections.unmodifiableList(buildList { while (cursor.moveToNext()) add(
                java.util.Collections.unmodifiableList((0..3).map { if (cursor.isNull(it)) null else cursor.getString(it) })) })
        }
    internal suspend fun compactAfterClear(database: HabitDatabase) {
        requireNotObserving()
        requireNoReadSnapshot()
        withContext(Dispatchers.IO) {
            val db = native()
            check(owner.get() === database && !database.inTransaction() && !db.inTransaction() && current.get() == null) {
                "COUNT_TRUST_CLEAR_COMPACTION_CONTEXT"
            }
            CountTrustSchema.requireControl(db)
            db.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
                check(it.moveToFirst() && it.getLong(0) == 1L) { "COUNT_TRUST_CLEAR_COMPACTION_NOT_DIRTY" }
            }
            for (table in CountTrustSchema.BUSINESS_TABLES + CountTrustSchema.LOCAL_TABLES - setOf("sync_control", "count_trust_control")) {
                db.query("SELECT 1 FROM `$table` LIMIT 1").use { check(!it.moveToFirst()) { "COUNT_TRUST_CLEAR_COMPACTION_NOT_EMPTY" } }
            }
            db.query("SELECT id,suppressOutbox FROM sync_control").use {
                check(it.moveToFirst() && it.getLong(0) == 1L && it.getLong(1) == 0L && !it.moveToNext()) {
                    "COUNT_TRUST_CLEAR_COMPACTION_CONTROL"
                }
            }
            // A cleared device-local certificate cannot be reused. Ordinary cached DAO statements
            // still belong to this connection; only reopening may establish a new fast epoch.
            retire(CountTrustRetirementReason.ACCOUNT_CLEARED, retireHandles = false)
            db.query("PRAGMA wal_checkpoint(FULL)").use { while (it.moveToNext()) { /* drain native checkpoint */ } }
            db.execSQL("VACUUM")
        }
    }
    /** A read lifetime only, never maintenance, account, signing or publication authority.
     * No Job identity: legal Room children may read within the SAME actual transaction.
     */
    private class ReadToken(val owner: HabitDatabase, val native: SupportSQLiteDatabase, val tx: Tx, val epoch: Long)
    internal fun captureReadToken(database: HabitDatabase): Any {
        val lease = activeLease()
        check(owner.get() === database && !isRetired && !lease.tx.ended) { "COUNT_TRUST_READ_TOKEN_EXPIRED" }
        return ReadToken(database, native(), lease.tx, generation)
    }
    internal fun requireReadToken(database: HabitDatabase, token: Any) {
        val issued = token as? ReadToken ?: error("COUNT_TRUST_FOREIGN_READ_TOKEN")
        check(!isRetired && issued.owner === database && owner.get() === database &&
            nativeDatabase === issued.native && issued.native.isOpen && issued.epoch == generation &&
            transaction.get() === issued.tx && !issued.tx.ended && issued.native.inTransaction() && database.inTransaction()) {
            "COUNT_TRUST_READ_TOKEN_EXPIRED"
        }
    }

    /** Lifetime only: the command owner separately verifies schema, namespace, MAC and
     * complete native rows. This token grants no SQL/signing/publishing capability. Existing
     * COUNT ReadToken remains unchanged and still rejects any isRetired owner.
     */
    private class PrimaryReadToken(val owner: HabitDatabase, val native: SupportSQLiteDatabase, val tx: Tx, val epoch: Long)
    internal fun capturePrimaryReadToken(database: HabitDatabase): Any {
        val lease = activeLease()
        check(owner.get() === database && !primaryRetired && !lease.tx.ended) { "COMMAND_PRIMARY_READ_EXPIRED" }
        return PrimaryReadToken(database, native(), lease.tx, generation)
    }
    internal fun requirePrimaryReadToken(database: HabitDatabase, token: Any) {
        val issued = token as? PrimaryReadToken ?: error("COMMAND_FOREIGN_PRIMARY_TOKEN")
        check(!primaryRetired && issued.owner === database && owner.get() === database &&
            nativeDatabase === issued.native && issued.native.isOpen && issued.epoch == generation &&
            transaction.get() === issued.tx && !issued.tx.ended && issued.native.inTransaction() && database.inTransaction()) {
            "COMMAND_PRIMARY_READ_EXPIRED"
        }
    }
    internal fun primaryCompleted(database: HabitDatabase, token: Any, value: Any?): CountTrustRuntime.PrimaryEndReceipt {
        val tx = token as? Tx ?: error("COMMAND_FOREIGN_TRANSACTION")
        check(owner.get() === database && !primaryRetired && tx.ended && tx.committed && tx.epoch == generation &&
            nativeDatabase?.isOpen == true && !native().inTransaction() && !database.inTransaction()) { "COMMAND_PRIMARY_END_NOT_CONFIRMED" }
        return PrimaryReceipt(database, native(), tx, value)
    }
    private inner class PrimaryReceipt(private val database: HabitDatabase, private val db: SupportSQLiteDatabase,
        private val tx: Tx, private val value: Any?) : CountTrustRuntime.PrimaryEndReceipt {
        override fun requireEvidenceToken(token: Any) {
            val issued = token as? PrimaryReadToken ?: error("COMMAND_FOREIGN_PRIMARY_TOKEN")
            check(!primaryRetired && issued.owner === database && issued.native === db && issued.tx === tx &&
                issued.epoch == generation && owner.get() === database && nativeDatabase === db && db.isOpen &&
                tx.ended && tx.committed && tx.epoch == generation) { "COMMAND_PRIMARY_END_EVIDENCE_CHANGED" }
        }
        override fun requireCandidate(candidate: Any?) {
            check(!primaryRetired && owner.get() === database && nativeDatabase === db && db.isOpen &&
                tx.ended && tx.committed && tx.epoch == generation && candidate === value) { "COMMAND_PRIMARY_END_RECEIPT_EXPIRED" }
        }
    }

    /** This permit is a quiescence witness only, not SQL or root-signing authority. */
    private class NodeGcPermit(val factory: CountTrustOpenHelperFactory, val native: SupportSQLiteDatabase,
        val tx: Tx, val epoch: Long)
    internal fun nodeCollectionRequested(): Boolean = gcRequested && !isRetired && nativeDatabase?.isOpen == true
    internal fun nodeAllocationEpoch(): Long = gcAllocationEpoch
    internal fun nodeCollectionFinished(observedEpoch: Long) {
        requireNotObserving()
        synchronized(gcRegistry) { if (gcAllocationEpoch == observedEpoch) gcRequested = false }
    }
    internal fun commandNodeCollectionRequested(): Boolean = commandGcRequested && !isRetired && nativeDatabase?.isOpen == true
    internal fun commandNodeAllocationEpoch(): Long = commandGcAllocationEpoch
    internal fun commandNodeCollectionFinished(observedEpoch: Long) {
        requireNotObserving()
        synchronized(gcRegistry) { if (commandGcAllocationEpoch == observedEpoch) commandGcRequested = false }
    }
    /** Under gcRegistry only. Unknown identity/lifetime is never classified as a retryable reader. */
    private fun gcAdmission(own: Tx?): CountTrustRuntime.NodeGcAdmission {
        if (isRetired || nativeDatabase?.isOpen != true || gcPath == null || gcUnknownEnd ||
            own != null && gcTransactions[own] != true) return CountTrustRuntime.NodeGcAdmission.UNAVAILABLE
        val others = gcOwners.keys.filter { it !== this && it.nativeDatabase?.isOpen == true }
        if (others.any { it.gcPath == null && !it.gcInMemory ||
                it.gcPath == gcPath && (it.gcUnknownEnd || it.isRetired) }) return CountTrustRuntime.NodeGcAdmission.UNAVAILABLE
        return if (gcTransactions.keys.any { it !== own } || others.any { it.gcPath == gcPath })
            CountTrustRuntime.NodeGcAdmission.BUSY else CountTrustRuntime.NodeGcAdmission.READY
    }
    internal fun nodeCollectionAdmission(database: HabitDatabase): CountTrustRuntime.NodeGcAdmission {
        requireNotObserving()
        if (owner.get() !== database) return CountTrustRuntime.NodeGcAdmission.UNAVAILABLE
        return synchronized(gcRegistry) { gcAdmission(null) }
    }
    internal fun captureNodeGcPermit(database: HabitDatabase): CountTrustRuntime.NodeGcPermitAttempt {
        requireNotObserving(); requireUsable()
        val lease = activeLease()
        check(owner.get() === database && lease.tx.write && lease.tx.successes.size == 1 && !lease.tx.ended)
        return synchronized(gcRegistry) {
            when (gcAdmission(lease.tx)) {
                CountTrustRuntime.NodeGcAdmission.BUSY -> CountTrustRuntime.NodeGcPermitAttempt.Busy
                CountTrustRuntime.NodeGcAdmission.UNAVAILABLE -> CountTrustRuntime.NodeGcPermitAttempt.Unavailable
                CountTrustRuntime.NodeGcAdmission.READY -> CountTrustRuntime.NodeGcPermitAttempt.Granted(
                    NodeGcPermit(this, native(), lease.tx, generation).also { lease.tx.nodeCollection = true })
            }
        }
    }
    internal fun requireNodeGcPermit(database: HabitDatabase, token: Any) {
        requireNotObserving(); requireUsable()
        val permit = token as? NodeGcPermit ?: error("COUNT_GC_FOREIGN_PERMIT")
        val lease = activeLease()
        synchronized(gcRegistry) {
            check(permit.factory === this && owner.get() === database && permit.native === nativeDatabase &&
                permit.tx === lease.tx && permit.epoch == generation && !permit.tx.ended) { "COUNT_GC_PERMIT_EXPIRED" }
            when (gcAdmission(permit.tx)) {
                CountTrustRuntime.NodeGcAdmission.READY -> Unit
                CountTrustRuntime.NodeGcAdmission.BUSY -> error("COUNT_GC_NOT_QUIESCENT")
                CountTrustRuntime.NodeGcAdmission.UNAVAILABLE -> error("COUNT_GC_OWNER_UNAVAILABLE")
            }
        }
    }
    private fun noteProofAllocation(statement: String) {
        val tx = transaction.get() ?: return
        val names = tokens(statement)
        if (!tx.nodeCollection && names.firstOrNull() in setOf("insert", "replace", "update", "delete", "with")) {
            if ("count_trust_nodes" in names) tx.proofAllocations = true
            if ("count_command_nodes" in names) tx.commandProofAllocations = true
        }
    }

    internal fun currentTransactionToken(): Any = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
    private class CountAddPreparationToken(val factory: CountTrustOpenHelperFactory,
        val owner: HabitDatabase, val native: SupportSQLiteDatabase, val tx: Tx, val epoch: Long)

    /** Capture only inside the real registered Room/native transaction, before A reads. */
    internal fun countAddPreparationToken(database: HabitDatabase): Any {
        requireNotObserving()
        val tx = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
        val db = native()
        synchronized(gcRegistry) {
            check(owner.get() === database && !isRetired && db.isOpen && db.inTransaction() &&
                !gcUnknownEnd && tx.epoch == generation && !tx.ended && gcTransactions[tx] == true) {
                "COUNT_ADD_PREPARATION_LIFETIME_CHANGED"
            }
        }
        return CountAddPreparationToken(this, database, db, tx, generation)
    }

    /** Called under this factory's monitor. It performs no SQL and does not acquire any
     * maintenance/read capability. Soft suppression is considered only by the A-end caller. */
    private fun requireCountAddPreparationIdentity(issued: CountAddPreparationToken) {
        check(issued.factory === this && issued.owner === owner.get() && issued.native === nativeDatabase &&
            issued.native.isOpen && issued.epoch == generation && issued.tx.epoch == generation) {
            "COUNT_ADD_PREPARATION_LIFETIME_CHANGED"
        }
    }

    /** Only these two private qualification paths can suppress a newly opened optional owner
     * without invalidating native handles. Recovery cleanup, account clear, unknown END,
     * external changes and every other retirement reason are deliberately excluded. */
    private fun optionalCountAddSuppression(): Boolean = isRetired && retirementReasons.isNotEmpty() &&
        retirementReasons.all { it == CountTrustRetirementReason.RECOVERY_PREVIEW ||
            it == CountTrustRetirementReason.CONNECTION_IDENTITY_UNKNOWN }

    /** Two distinct applicability misses: a known still-active inherited Room transaction
     * discards its frozen values; an exactly identified optional qualification suppression
     * may discard ONLY a null result. Neither branch can certify a business result. Actual
     * rollback, unknown BEGIN/END, lifetime change and hard retirement always throw.
     */
    internal fun countAddPreparationEnd(token: Any, value: Any?): CountTrustRuntime.EndReceipt? =
        observedCountAddEnd(token, value, allowKnownOuter = true)

    /** C may discard only an explicit null pre-apply selection and only after actual END.
     * Every non-null candidate, including constructed-fact fallback, keeps the strict receipt. */
    internal fun countAddSelectionEnd(token: Any, value: Any?): CountTrustRuntime.EndReceipt? =
        observedCountAddEnd(token, value, allowKnownOuter = false)

    private fun observedCountAddEnd(token: Any, value: Any?, allowKnownOuter: Boolean): CountTrustRuntime.EndReceipt? {
        requireNotObserving()
        val issued = token as? CountAddPreparationToken ?: error("COUNT_TRUST_FOREIGN_TRANSACTION")
        // Match existing opening order: factory monitor -> GC registry. No callback, SQL,
        // file operation, credential access or suspension occurs under either monitor.
        return synchronized(this) {
            requireCountAddPreparationIdentity(issued)
            val suppressedWithoutValues = isRetired && value == null && optionalCountAddSuppression()
            check(!isRetired || suppressedWithoutValues) { "COUNT_ADD_PREPARATION_LIFETIME_CHANGED" }
            val tx = issued.tx
            val stillActive = synchronized(gcRegistry) {
                check(!gcUnknownEnd) { "COUNT_ADD_PREPARATION_END_UNKNOWN" }
                if (tx.ended) {
                    check(gcTransactions[tx] == null && tx.committed) { "COUNT_TRUST_END_NOT_CONFIRMED" }
                    false
                } else {
                    check(!tx.committed && tx.successes.isNotEmpty() && gcTransactions[tx] == true) {
                        "COUNT_ADD_PREPARATION_END_UNKNOWN"
                    }
                    true
                }
            }
            check(allowKnownOuter || !stillActive) { "COUNT_TRUST_END_NOT_CONFIRMED" }
            if (stillActive || suppressedWithoutValues) null else {
                val completed = completed(tx, value)
                CountAddReceipt(issued, completed)
            }
        }
    }

    private inner class CountAddReceipt(private val issued: CountAddPreparationToken,
        private val completed: CountTrustRuntime.EndReceipt) : CountTrustRuntime.EndReceipt {
        override fun requireCandidate(candidate: Any?) {
            synchronized(this@CountTrustOpenHelperFactory) {
                requireCountAddPreparationIdentity(issued)
                check(!isRetired) { "COUNT_ADD_PREPARATION_LIFETIME_CHANGED" }
                synchronized(gcRegistry) { check(!gcUnknownEnd && gcTransactions[issued.tx] == null) {
                    "COUNT_ADD_PREPARATION_END_UNKNOWN"
                } }
                completed.requireCandidate(candidate)
            }
        }
    }

    internal fun completed(token: Any, value: Any?): CountTrustRuntime.EndReceipt {
        val tx = token as? Tx ?: error("COUNT_TRUST_FOREIGN_TRANSACTION")
        check(tx.ended && tx.committed && tx.epoch == generation && !isRetired) { "COUNT_TRUST_END_NOT_CONFIRMED" }
        return Receipt(tx, value)
    }
    private inner class Receipt(private val tx: Tx, private val value: Any?) : CountTrustRuntime.EndReceipt {
        override fun requireCandidate(candidate: Any?) {
            check(tx.ended && tx.committed && tx.epoch == generation && !isRetired && candidate === value) {
                "COUNT_TRUST_END_RECEIPT_EXPIRED"
            }
        }
    }
    // A single DEBUG value snapshot; no Throwable, connection or callback is retained.
    private val beginJournalFailure = if (com.dayforge.BuildConfig.DEBUG)
        java.util.concurrent.atomic.AtomicReference<CountTrustRuntime.BeginJournalFailureDiagnostic?>() else null
    internal fun beginJournalFailureSnapshot(): CountTrustRuntime.BeginJournalFailureDiagnostic? =
        beginJournalFailure?.get()
    private fun recordBeginJournalFailure(write: Boolean, retiredTrim: Boolean, failure: Throwable) {
        if (!com.dayforge.BuildConfig.DEBUG) return
        try {
            val sink = beginJournalFailure ?: return
            if (sink.get() != null) return
            // Only the exact JDK class can supply these fixed invariant messages. Never call
            // an arbitrary Throwable subclass's message getter or retain native SQL messages.
            val invariant = if (failure.javaClass === IllegalStateException::class.java) when (failure.message) {
                "COUNT_TRUST_JOURNAL_TRANSACTION_REQUIRED" -> CountTrustRuntime.BeginJournalInvariant.COUNT_TRUST_JOURNAL_TRANSACTION_REQUIRED
                "COUNT_TRUST_CONNECTION_CHANGED" -> CountTrustRuntime.BeginJournalInvariant.COUNT_TRUST_CONNECTION_CHANGED
                "COUNT_TRUST_EXTERNAL_CHANGE" -> CountTrustRuntime.BeginJournalInvariant.COUNT_TRUST_EXTERNAL_CHANGE
                "COUNT_TRUST_SCHEMA_CHANGED" -> CountTrustRuntime.BeginJournalInvariant.COUNT_TRUST_SCHEMA_CHANGED
                "COUNT_TRUST_REPLACE_CAPTURE_DISABLED" -> CountTrustRuntime.BeginJournalInvariant.COUNT_TRUST_REPLACE_CAPTURE_DISABLED
                else -> CountTrustRuntime.BeginJournalInvariant.OTHER
            } else CountTrustRuntime.BeginJournalInvariant.OTHER
            // First successful publication, not a claim about global retirement ordering.
            sink.compareAndSet(null, CountTrustRuntime.BeginJournalFailureDiagnostic(
                write, retiredTrim, failure.javaClass.name, invariant))
        } catch (_: Throwable) {
            // Best-effort diagnostics must not change the original retirement/continue path.
        }
    }
    private val retirementReasons = linkedSetOf<CountTrustRetirementReason>()
    internal fun retirementSnapshot(): Set<CountTrustRetirementReason> = synchronized(this) {
        java.util.Collections.unmodifiableSet(LinkedHashSet(retirementReasons))
    }
    private fun retire(reason: CountTrustRetirementReason, retireHandles: Boolean = true) = synchronized(this) {
        retirementReasons += reason
        isRetired = true
        if (retireHandles) { primaryRetired = true; generation = Math.addExact(generation, 1L) }
    }
    private fun dirtyBeforeUnownedSchemaChange(retireHandles: Boolean = true) {
        requireNoReadSnapshot()
        val db = native()
        CountTrustSchema.requireControl(db)
        db.query("SELECT generation FROM count_trust_control WHERE id=1").use {
            check(it.moveToFirst() && it.getLong(0) < Long.MAX_VALUE) { "COUNT_TRUST_CONTROL_INVALID" }
        }
        db.execSQL("UPDATE count_trust_control SET generation=generation+1,dirty=1 WHERE id=1")
        retire(CountTrustRetirementReason.UNOWNED_SCHEMA_CHANGE, retireHandles)
    }
    private fun requireNotObserving() {
        check((observing.get() ?: 0) == 0) { "COUNT_TRUST_OBSERVER_CONTROL_DENIED" }
    }
    /** Synchronous SQL diagnostics receive values, never the caller's bootstrap/maintenance
     * authority. Reentry cannot mint a new capability or end the transaction being observed.
     */
    internal fun <T> observeWithoutCapabilities(block: () -> T): T {
        val previous = current.get()
        val previousBoot = bootDepth()
        val previousObservation = observing.get() ?: 0
        current.remove(); bootstrap.set(0); observing.set(previousObservation + 1)
        return try { block() } finally {
            current.set(previous); bootstrap.set(previousBoot); observing.set(previousObservation)
        }
    }
    private fun bootDepth(): Int = bootstrap.get() ?: 0
    private fun <T> boot(block: () -> T): T {
        bootstrap.set(bootDepth() + 1)
        return try { block() } finally { bootstrap.set(bootDepth() - 1) }
    }
    private inner class BootstrapInitialization(private val context: CommandBootstrap) : CountCommandInitializationPermit {
        private fun active(sql: SupportSQLiteDatabase) {
            check(commandBootstrap.get() === context && context.active && bootDepth() > 0 &&
                context.native === sql && nativeDatabase === sql && sql.inTransaction() && transaction.get() == null &&
                current.get() == null && readLease == null && context.targetVersion == CountCommandV20Schema.VERSION) {
                "COMMAND_INITIALIZATION_CONTEXT_EXPIRED"
            }
        }
        override fun begin(database: SupportSQLiteDatabase): CountCommandInitializationPermit.Source {
            active(database); check(context.enlisted && !context.begun && !context.finished)
            context.begun = true; return context.source
        }
        override fun previousSchema(): List<List<String?>>? = context.previousSchema?.map { it.toList() }
        override fun previousContents(): String? = context.previousContents
        override fun expectedSchema(): List<List<String?>>? {
            val guard = context.migratedControlGuard ?: return null
            val actual = readSchema(context.native)
            check(actual.none { it[1] == CountCommandControlSchema.GUARD }) { "COMMAND_MIGRATION_CONTROL_GUARD_REAPPEARED" }
            val additions = CountCommandV20Schema.definitions.filter { (name, definition) ->
                definition.type == "trigger" && actual.none { it[1] == name }
            }.map { (name, definition) -> listOf("trigger", name, definition.owner,
                requireNotNull(definition.declaredSql).replaceFirst("CREATE TRIGGER IF NOT EXISTS ", "CREATE TRIGGER ")) }
            return (actual + additions + listOf(listOf("trigger", CountCommandControlSchema.GUARD,
                CountCommandControlSchema.TABLE, guard))).sortedWith(compareBy({ it[0] }, { it[1] }))
        }
        override fun finish(database: SupportSQLiteDatabase, exactFinalSchema: List<List<String?>>) {
            active(database); check(context.begun && !context.finished && readSchema(database) == exactFinalSchema)
            context.finished = true
        }
    }
    private fun <T> commandBoot(db: SupportSQLiteDatabase, oldVersion: Int, targetVersion: Int,
        source: CountCommandInitializationPermit.Source, callback: () -> T): T {
        check(commandBootstrap.get() == null && transaction.get() == null && current.get() == null && readLease == null)
        val context = CommandBootstrap(db, oldVersion, targetVersion, source)
        commandBootstrap.set(context)
        try {
            return boot {
                val result = callback()
                if (source == CountCommandInitializationPermit.Source.NEW_DATABASE) context.enlisted = true
                check(context.enlisted) { "COMMAND_REQUIRED_MIGRATION_NOT_ENLISTED" }
                CountCommandStorageInitialization.initialize(db, BootstrapInitialization(context))
                check(context.finished)
                if (source == CountCommandInitializationPermit.Source.MIGRATION_19_20) expectedMigrationReopen = readSchema(db)
                result
            }
        } finally { context.active = false; commandBootstrap.remove() }
    }
    private fun enlistCommandMigration(safe: Database) {
        requireNotObserving()
        val context = checkNotNull(commandBootstrap.get()) { "COMMAND_MIGRATION_CONTEXT_REQUIRED" }
        check(context.active && context.native === nativeDatabase && safe === wrapped && bootDepth() > 0 &&
            context.oldVersion in 1..19 && context.targetVersion == 20 && !context.enlisted &&
            context.source == CountCommandInitializationPermit.Source.MIGRATION_19_20 && native().inTransaction())
        CountTrustSchema.validate(native(), 19) // Frozen v19 definitions; earlier migration edges already ran.
        // Earlier edges intentionally defer ordinary sync trigger restoration to onOpen.
        // Complete that same callback before capturing the v19 image and signing the first
        // primary root, while the original upgrade transaction can still roll back.
        if (context.oldVersion < 19) SyncSchemaCallback.completeLegacyUpgradeBeforeCommandEnrollment(safe)
        if (context.oldVersion == 19) native().query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                it.getType(1) == Cursor.FIELD_TYPE_STRING && it.getString(1) == "0461f88592d1a5af33ac34f052cd4f83" && !it.moveToNext())
        }
        val before = readSchema(native())
        check(before.none { it[1] in CountCommandV20Schema.definitions.keys || it[2] in CountCommandV20Schema.tables }) { "COMMAND_MIGRATION_NAME_COLLISION" }
        val oldGuard = before.single { it[1] == CountCommandControlSchema.GUARD }
        check(oldGuard[0] == "trigger" && oldGuard[2] == CountCommandControlSchema.TABLE)
        context.migratedControlGuard = CountCommandControlSchema.upgradeGuard(requireNotNull(oldGuard[3]))
        context.previousSchema = before
        context.previousContents = CountCommandMigrationRows.fingerprint(native(), before)
        context.enlisted = true
    }

    private inner class PrimaryReset(private val database: HabitDatabase, private val db: SupportSQLiteDatabase,
        private val tx: Tx, private val epoch: Long, private val caller: Job?, private val oldStorage: String, private val oldDataset: String,
        private val finalSchema: List<List<String?>>) : CountCommandInitializationPermit {
        val issuer: CountTrustOpenHelperFactory = this@CountTrustOpenHelperFactory
        private var began = false
        private var finished = false
        private var initializedRoot: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity? = null
        private fun active(sql: SupportSQLiteDatabase) {
            val lease = activeLease()
            check(!primaryRetired && owner.get() === database && nativeDatabase === db && sql === db && db.isOpen &&
                epoch == generation && transaction.get() === tx && lease.tx === tx && lease.caller === caller &&
                !tx.ended && tx.primaryResetPending && !finished) { "COMMAND_RESET_EXPIRED" }
        }
        override fun begin(database: SupportSQLiteDatabase): CountCommandInitializationPermit.Source {
            active(database); check(!began)
            CountTrustSchema.requireControl(database)
            database.query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
                check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) != oldStorage && !it.moveToNext())
            }
            began = true; return CountCommandInitializationPermit.Source.AUTHORIZED_CLEAR_REPLACE
        }
        override fun previousSchema(): List<List<String?>>? = null
        override fun previousContents(): String? = null
        override fun expectedSchema(): List<List<String?>> = finalSchema.map { it.toList() }
        override fun finish(database: SupportSQLiteDatabase, exactFinalSchema: List<List<String?>>) {
            active(database); check(began && exactFinalSchema == finalSchema && readSchema(db) == finalSchema)
            CountCommandStorageInitialization.verifyMandatory(db)
            val actual = com.dayforge.data.repository.CountCommandNativeRows(db) { active(db) }.root()
            check(com.dayforge.data.repository.CountCommandNativeRows.sameRoot(requireNotNull(initializedRoot), actual)) { "COMMAND_RESET_ROOT_CHANGED" }
            finished = true; tx.primaryResetFinished = true
        }
        fun initialize() {
            active(db)
            // Original whole-storage lifecycle owns this fixed deletion, not a generic cache
            // helper. The existing Result table was cleared by the frozen reset helper.
            CountCommandV20Schema.tables.forEach { db.execSQL("DELETE FROM `$it`") }
            CountCommandStorageInitialization.initialize(db, this)
            val actual = com.dayforge.data.repository.CountCommandNativeRows(db) { active(db) }.root()
            val header = com.dayforge.data.repository.CountCommandRootCodec.fromEntity(actual)
            check(header.mode == com.dayforge.data.repository.CountCommandRootCodec.Mode.EMPTY &&
                header.source == com.dayforge.data.repository.CountCommandRootCodec.Source.AUTHORIZED_CLEAR_REPLACE &&
                header.datasetId != oldDataset && header.storageId != oldStorage && header.revision == 1L) { "COMMAND_RESET_DATASET_NOT_REPLACED" }
            initializedRoot = actual
        }
        fun complete() = finish(db, readSchema(db))
    }
    internal fun preparePrimaryReset(database: HabitDatabase): Any? {
        requireNotObserving()
        val lease = activeLease()
        check(owner.get() === database) { "COMMAND_FOREIGN_DATABASE" }
        if (native().version < 20) return null
        check(!primaryRetired && owner.get() === database && !lease.tx.primaryResetPending && native().version == 20)
        CountCommandStorageInitialization.verifyMandatory(native())
        check(lease.tx.command == null) { "COMMAND_RESET_INSIDE_COMMAND_DENIED" }
        val oldDataset = com.dayforge.data.repository.CountCommandNativeRows(native()) { check(!primaryRetired) }.root().datasetId
        val storage = native().query("SELECT storageId FROM count_trust_control WHERE id=1 LIMIT 2").use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING)
            it.getString(0).also { _ -> check(!it.moveToNext()) }
        }
        // The old source/root has already been verified. Initialize only the fixed
        // empty allocator representation before resetting any business table; migration
        // and ordinary opening still preserve their exact historical bytes.
        prepareEmptyOutboxAllocators(database)
        // A known whole-storage reset may drop/recreate permanent guards. Remove the
        // old TEMP command journal under this exact transaction, never refresh its nonce or
        // schema cookie in place. ROLLBACK restores it; actual COMMIT retires its object.
        journal?.let { old ->
            old.dropForAuthorizedReset(); lease.tx.resetBusinessJournal = old
        }
        commandJournal?.let { old ->
            old.dropForAuthorizedReset(); lease.tx.resetCommandJournal = old
        }
        lease.tx.primaryResetPending = true
        retire(CountTrustRetirementReason.ACCOUNT_CLEARED, retireHandles = false) // Existing COUNT proof cannot survive an authorized dataset reset.
        return PrimaryReset(database, native(), lease.tx, generation, lease.caller, storage, oldDataset, readSchema(native()))
    }
    internal fun initializePrimaryReset(token: Any?) {
        if (token == null) { check(native().version < 20); return }
        val issued = token as? CountTrustOpenHelperFactory.PrimaryReset ?: error("COMMAND_FOREIGN_RESET")
        check(issued.issuer === this) { "COMMAND_FOREIGN_RESET" }; issued.initialize()
    }
    internal fun finishPrimaryReset(token: Any?) {
        if (token == null) { check(native().version < 20); return }
        val issued = token as? CountTrustOpenHelperFactory.PrimaryReset ?: error("COMMAND_FOREIGN_RESET")
        check(issued.issuer === this) { "COMMAND_FOREIGN_RESET" }; issued.complete()
    }

    private fun wrap(db: SupportSQLiteDatabase): Database {
        // Native file/schema/Keystore work stays outside owner/GC state monitors. The one
        // initialization-only reopen consumes the exact pending migration handoff once;
        // ordinary replacement keeps1487's permanent retirement behavior.
        val previous = nativeDatabase
        val path = if (previous !== db) db.path else null
        val canonical = path?.takeIf { it.isNotBlank() && it != ":memory:" }?.let { java.io.File(it).canonicalPath }
        val expected = synchronized(this) { if (nativeDatabase === previous && previous !== db) expectedMigrationReopen else null }
        val previousPath = if (expected != null) previous?.path else null
        val initializationOnly = expected != null && previous != null && !previous.isOpen &&
            !path.isNullOrBlank() && path == previousPath && !isRetired && !primaryRetired &&
            transaction.get() == null && current.get() == null && readLease == null &&
            db.version == 20 && readSchema(db) == expected
        if (initializationOnly) try { CountCommandStorageInitialization.verifyMandatory(db) }
            catch (failure: Throwable) { retire(CountTrustRetirementReason.CORRUPTION); throw failure }
        return synchronized(this) {
            if (nativeDatabase !== db) {
                val knownMigration = initializationOnly && nativeDatabase === previous && expectedMigrationReopen === expected
                expectedMigrationReopen = null
                if (nativeDatabase != null && !knownMigration) retire(CountTrustRetirementReason.CONNECTION_REPLACED)
                generation = Math.addExact(generation, 1L)
                nativeDatabase = db
                nativeIncarnation = Any()
                // A concurrent replacement with no prepared identity is already retired;
                // keep it unknown, never guess that it is a distinct/in-memory owner.
                synchronized(gcRegistry) { gcOwners[this] = Unit; gcPath = canonical; gcInMemory = path == ":memory:" }
                wrapped = Database(db)
            }
            checkNotNull(wrapped)
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                val safe = wrap(db)
                // Before Room validation/callbacks can install missing objects in an existing v19 file.
                check(db.version <= CountCommandV20Schema.VERSION) { "COMMAND_UNKNOWN_DATABASE_VERSION" }
                CountTrustSchema.preOpen(db)
                if (db.version == CountCommandV20Schema.VERSION) CountCommandStorageInitialization.verifyMandatory(db)
                openingSchema = if (db.version >= CountTrustSchema.VERSION) readSchema(db) else null
                boot { callback.onConfigure(safe); db.execSQL("PRAGMA recursive_triggers=ON") }
            }
            override fun onCreate(db: SupportSQLiteDatabase) {
                if (callback.version == 20) commandBoot(db, 0, 20, CountCommandInitializationPermit.Source.NEW_DATABASE) { callback.onCreate(wrap(db)) }
                else boot { callback.onCreate(wrap(db)) }
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (newVersion == 20) commandBoot(db, oldVersion, newVersion, CountCommandInitializationPermit.Source.MIGRATION_19_20) {
                    callback.onUpgrade(wrap(db), oldVersion, newVersion)
                } else boot { callback.onUpgrade(wrap(db), oldVersion, newVersion) }
            }
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                boot { callback.onDowngrade(wrap(db), oldVersion, newVersion) }
            override fun onOpen(db: SupportSQLiteDatabase) {
                CountTrustSchema.preOpen(db)
                boot { callback.onOpen(wrap(db)) }
                CountTrustSchema.validate(db)
                if (db.version == 20) CountCommandStorageInitialization.verifyMandatory(db)
                // Record before callbacks can repair legacy sync/fence objects. A repair does
                // not make an old root reusable: invalidate before returning the opened DB.
                if (openingSchema?.let { it != readSchema(db) } == true) dirtyBeforeUnownedSchemaChange()
            }
            override fun onCorruption(db: SupportSQLiteDatabase) {
                retire(CountTrustRetirementReason.CORRUPTION)
                error("COUNT_TRUST_CORRUPTION_PRESERVED")
            }
        }
        val helper = delegateFactory.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(guarded).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = helper.databaseName
            override val writableDatabase: SupportSQLiteDatabase get() = openHelperDatabase { helper.writableDatabase }
            override val readableDatabase: SupportSQLiteDatabase get() = openHelperDatabase { helper.readableDatabase }
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                requireNotObserving()
                requireNoReadSnapshot()
                if (nativeDatabase != null && bootDepth() == 0) dirtyBeforeUnownedSchemaChange()
                helper.setWriteAheadLoggingEnabled(enabled)
            }
            override fun close() = closeNative(nativeDatabase) { helper.close() }
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
        fun snapshot(): Array<Any?> {
            check(values.keys.toList() == (1..values.size).toList()) { "COUNT_TRUST_BINDING_GAP" }
            return values.values.map { if (it is ByteArray) it.copyOf() else it }.toTypedArray()
        }
    }

    private fun protected(name: String): Boolean = name.startsWith("count_trust_") || name.startsWith("count_command_")
    private fun checkSql(sql: String, query: Boolean) {
        readAccess()
        if (bootDepth() > 0) return
        var tokens = tokens(sql)
        check(tokens.isNotEmpty()) { "COUNT_TRUST_SQL_DENIED" }
        if (tokens.first() == "explain") {
            val prefix = if (tokens.getOrNull(1) == "query" && tokens.getOrNull(2) == "plan") 3 else 1
            tokens = tokens.drop(prefix)
            // EXPLAIN changes stepping, not preparation. PRAGMA can mutate connection state
            // during prepare, so inspect the underlying statement before touching SQLite.
            check(tokens.firstOrNull() == "select" || tokens.firstOrNull() == "with" &&
                tokens.none { it in explainedWriteTokens }) { "COUNT_TRUST_EXPLAIN_WRITE_DENIED" }
        }
        val first = tokens.first()
        check(";" !in tokens || first == "create" && "trigger" in tokens) { "COUNT_TRUST_MULTIPLE_SQL_DENIED" }
        val snapshotReadOnly = readLease != null || reading.get() != null
        val observingReadOnly = (observing.get() ?: 0) > 0
        if (snapshotReadOnly || observingReadOnly) {
            val read = first == "select" || first == "with" && tokens.none { it in explainedWriteTokens }
            val pragma = if (first == "pragma") tokens.drop(1).let {
                if (it.size > 1 && it[1] == ".") it.drop(2) else it
            } else emptyList()
            check(read || pragma.isNotEmpty() && "=" !in pragma && pragma.first() in snapshotReadPragmas &&
                (pragma.first() !in scalarPragmas || pragma.size == 1)) {
                if (snapshotReadOnly) "COUNT_TRUST_READ_SNAPSHOT_WRITE_DENIED" else "COUNT_TRUST_OBSERVER_WRITE_DENIED"
            }
        }
        val sourceRead = first == "select" || first == "with" && tokens.none { it in explainedWriteTokens } ||
            first == "pragma" && "=" !in tokens
        checkSourceUse(sourceRead)
        // All potential actual writes invalidate, including writes whose trigger/cascade
        // changes main control. No lexical target-name assumption and no per-SQL metadata scan.
        if (!sourceRead) invalidateSourceObservation()
        val exact = tokens(sql, exactKey = true)
        if (exact in frameworkSql) return
        transaction.get()?.command?.let { operation ->
            val reading = first == "select" || first == "with" && tokens.none { it in explainedWriteTokens }
            if (operation.phase == PrimaryPhase.SEALED) {
                val readPragma = first == "pragma" && "=" !in tokens && tokens.drop(1).any { it in snapshotReadPragmas }
                check(reading || readPragma) { "COMMAND_WRITE_AFTER_VALIDATION" }
            } else if (operation.phase == PrimaryPhase.PREPARING && !reading && first != "pragma") {
                check(first in setOf("insert", "update", "delete", "replace") && tokens.any(::protected) &&
                    tokens.none { it in CountTrustSchema.BUSINESS_TABLES }) { "COMMAND_PREPARATION_WRITE_DENIED" }
            }
        }
        check("load_extension" !in tokens && tokens.none { it.startsWith("pragma_") } && first !in setOf("attach", "detach", "begin", "end", "commit", "rollback", "savepoint", "release")) {
            "COUNT_TRUST_SQL_DENIED"
        }
        if (first == "pragma") {
            val tail = tokens.drop(1).let { if (it.size > 1 && it[1] == ".") it.drop(2) else it }
            val name = tail.firstOrNull() ?: error("COUNT_TRUST_SQL_DENIED")
            val assignment = "=" in tail
            if (assignment) {
                check(name == "defer_foreign_keys" && tail.drop(1) in listOf(listOf("=", "on"), listOf("=", "1"), listOf("=", "true"))) {
                    "COUNT_TRUST_PRAGMA_DENIED"
                }
                activeLease()
            } else check(name in readPragmas && (name !in scalarPragmas || tail.size == 1)) { "COUNT_TRUST_PRAGMA_DENIED" }
            return
        }
        val isDdl = first in setOf("create", "drop", "alter", "reindex", "analyze", "vacuum")
        if (isDdl) {
            check(!query) { "COUNT_TRUST_QUERY_WRITE_DENIED" }
            if (current.get() != null && exact in replicaMaintenanceSql.getValue(if (native().version == CountCommandV20Schema.VERSION) 20 else 19)) {
                activeLease(); dirtyBeforeUnownedSchemaChange(retireHandles = false); return
            }
            check(tokens.none(::protected) && tokens.none { it.startsWith("room_") || it.startsWith("sqlite_") || it == "android_metadata" } &&
                first != "vacuum" && "temp" !in tokens && "temporary" !in tokens) { "COUNT_TRUST_SCHEMA_DDL_DENIED" }
            dirtyBeforeUnownedSchemaChange()
            return
        }
        val read = first == "select" || first == "with" && tokens.none { it in setOf("insert", "update", "delete", "replace") }
        if (query) check(read) { "COUNT_TRUST_QUERY_WRITE_DENIED" }
        if (read) return
        check(first in setOf("insert", "replace", "update", "delete", "with")) { "COUNT_TRUST_SQL_DENIED" }
        check(tokens.none { it in setOf("sqlite_sequence", "sqlite_master", "sqlite_schema", "room_master_table", "android_metadata") }) {
            "COUNT_TRUST_SYSTEM_WRITE_DENIED"
        }
        if (tokens.any(::protected)) activeLease()
        check(tokens.none { it.startsWith("count_trust_journal_") || it.startsWith("count_command_journal_") }) { "COUNT_TRUST_JOURNAL_WRITE_DENIED" }
    }

    private inner class Database(private val db: SupportSQLiteDatabase) : SupportSQLiteDatabase by db {
        fun enlistCommandMigration() = this@CountTrustOpenHelperFactory.enlistCommandMigration(this)
        private fun connection() { readAccess(); check(nativeDatabase === db && wrapped === this && db.isOpen) { "COUNT_TRUST_CONNECTION_EXPIRED" } }
        private fun control() { connection(); requireNoReadSnapshot(); check(bootDepth() > 0) { "COUNT_TRUST_DATABASE_CONTROL_DENIED" } }
        override fun query(query: String): Cursor = query(SimpleSQLiteQuery(query))
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = query(SimpleSQLiteQuery(query, bindArgs))
        override fun query(query: SupportSQLiteQuery): Cursor = query(query, null)
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
            connection()
            val text = query.sql
            val bindings = Bindings().also(query::bindTo).snapshot()
            checkSql(text, true)
            val frozen = SimpleSQLiteQuery(text, bindings)
            val raw = if (cancellationSignal == null) db.query(frozen) else db.query(frozen, cancellationSignal)
            return cursor(raw, db, generation, current.get()?.lease, transaction.get(), readAccess())
        }
        override fun execSQL(sql: String) = execSQL(sql, emptyArray())
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            connection(); checkSql(sql, false)
            db.execSQL(sql, bindArgs.map { if (it is ByteArray) it.copyOf() else it }.toTypedArray())
            noteProofAllocation(sql)
        }
        override fun compileStatement(sql: String): SupportSQLiteStatement {
            connection()
            val epoch = generation
            val capability = if (tokens(sql).any(::protected)) current.get()?.lease else null
            val snapshot = readAccess()
            val bindings = Bindings()
            return object : SupportSQLiteStatement {
                private var closed = false
                private fun valid() {
                    connection()
                    check(!closed && generation == epoch) { "COUNT_TRUST_STATEMENT_EXPIRED" }
                    if (snapshot != null) check(readAccess() === snapshot) { "COUNT_TRUST_READ_SNAPSHOT_EXPIRED" }
                    if (capability != null) check(activeLease() === capability) { "COUNT_TRUST_MAINTENANCE_EXPIRED" }
                }
                private fun <T> execute(read: Boolean, body: (SupportSQLiteStatement) -> T): T {
                    valid(); checkSql(sql, read)
                    return db.compileStatement(sql).use { native -> SimpleSQLiteQuery.bind(native, bindings.snapshot()); body(native) }
                        .also { if (!read) noteProofAllocation(sql) }
                }
                override fun execute() { execute(false) { it.execute() } }
                override fun executeInsert(): Long = execute(false) { it.executeInsert() }
                override fun executeUpdateDelete(): Int = execute(false) { it.executeUpdateDelete() }
                override fun simpleQueryForLong(): Long = execute(true) { it.simpleQueryForLong() }
                override fun simpleQueryForString(): String? = execute(true) { it.simpleQueryForString() }
                override fun bindNull(index: Int) { valid(); bindings.bindNull(index) }
                override fun bindLong(index: Int, value: Long) { valid(); bindings.bindLong(index, value) }
                override fun bindDouble(index: Int, value: Double) { valid(); bindings.bindDouble(index, value) }
                override fun bindString(index: Int, value: String) { valid(); bindings.bindString(index, value) }
                override fun bindBlob(index: Int, value: ByteArray) { valid(); bindings.bindBlob(index, value) }
                override fun clearBindings() { valid(); bindings.clearBindings() }
                override fun close() { closed = true; bindings.close() }
            }
        }
        private fun frozen(values: ContentValues): ContentValues = ContentValues(values).also { copy ->
            for (key in values.keySet()) (values[key] as? ByteArray)?.let { copy.put(key, it.copyOf()) }
        }
        private fun direct(table: String, values: ContentValues? = null) {
            check(table in CountTrustSchema.BUSINESS_TABLES || table in CountCommandV20Schema.localTables(db.version)) { "COUNT_TRUST_DIRECT_TABLE_DENIED" }
            check(values == null || values.keySet().all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }) { "COUNT_TRUST_DIRECT_COLUMN_DENIED" }
        }
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
            connection(); direct(table, values); checkSql("INSERT INTO `$table`", false); return db.insert(table, conflictAlgorithm, frozen(values)).also { noteProofAllocation("INSERT INTO `$table`") }
        }
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            connection(); direct(table, values); checkSql("UPDATE `$table` SET value=1 ${whereClause.orEmpty()}", false)
            return db.update(table, conflictAlgorithm, frozen(values), whereClause, whereArgs?.map { if (it is ByteArray) it.copyOf() else it }?.toTypedArray())
                .also { noteProofAllocation("UPDATE `$table`") }
        }
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            connection(); direct(table); checkSql("DELETE FROM `$table` ${whereClause.orEmpty()}", false)
            return db.delete(table, whereClause, whereArgs?.map { if (it is ByteArray) it.copyOf() else it }?.toTypedArray())
                .also { noteProofAllocation("DELETE FROM `$table`") }
        }
        private fun begin(write: Boolean = true, listener: SQLiteTransactionListener? = null,
            body: () -> Unit, withListener: (SQLiteTransactionListener) -> Unit) {
            requireNotObserving(); requireGraphAdmission(); connection()
            val snapshot = readLease
            val queuedWriter = snapshot != null && snapshot.tx.write && snapshot.native === db && write &&
                reading.get() == null && transaction.get() == null && bootDepth() == 0
            if (!queuedWriter) requireNoReadSnapshot()
            if (bootDepth() > 0) { body(); return }
            check(current.get() == null) { "COUNT_TRUST_MAINTENANCE_TRANSACTION_CONTROL" }
            val previous = transaction.get()
            if (previous != null) {
                // Original nested listener semantics: a failed onBegin must not create a
                // nested logical frame or newly poison an outer transaction caught by caller.
                DatabaseOperationSource.current()?.let { requireSource(previous, it, first = true) }
                body(); previous.successes += false
                return
            }
            val opening = Tx(generation, write, db, nativeIncarnation, Thread.currentThread())
            synchronized(this@CountTrustOpenHelperFactory) {
                requireGraphAdmission()
                synchronized(gcRegistry) { gcTransactions[opening] = false }
            }
            var entered = false
            var userBeginCompleted = false
            var deferred: Throwable? = null
            val wrappedListener = object : SQLiteTransactionListener {
                override fun onBegin() {
                    entered = true
                    beginning.set(opening)
                    try {
                        opening.sourceObservation = observeSource(db)
                        if (queuedWriter) requireNoReadSnapshot()
                        DatabaseOperationSource.current()?.let { requireSource(opening, it, first = false) }
                        listener?.onBegin()
                        userBeginCompleted = true
                    } catch (failure: Throwable) {
                        // Let native BEGIN return normally and establish its logical frame.
                        // The public entry still throws after explicit actual rollback below.
                        deferred = failure
                    } finally { beginning.remove() }
                }
                override fun onCommit() {
                    check(userBeginCompleted && deferred == null) { "COUNT_TRUST_SOURCE_BEGIN_NOT_COMPLETED" }
                    opening.sourceEndFailure?.let { throw it }
                    opening.allocatorFailure?.let { throw it }
                    listener?.onCommit()
                    // A user onCommit can write before physical COMMIT. Its controlled DML,
                    // triggers and cascades dirty the observation and must be checked here.
                    if (opening.command != null) CountCommandCostTiming.measure(opening.costs, Step.NATIVE_FINAL_SOURCES) { checkFinalSources(opening) }
                    else checkFinalSources(opening)
                    // Listener/trigger work precedes physical COMMIT. Recheck the same sealed
                    // command journal, original business control and current Root+anchor here;
                    // setTransactionSuccessful alone is not actual-END evidence.
                    verifyCommandAtOuterCommit(opening, finalNative = true)
                }
                override fun onRollback() {
                    // Native original failed-onBegin behavior never invoked user.onRollback.
                    if (userBeginCompleted) listener?.onRollback()
                }
            }
            try { withListener(wrappedListener) } catch (failure: Throwable) {
                if (entered) {
                    synchronized(gcRegistry) { gcUnknownEnd = true }
                    retire(CountTrustRetirementReason.UNKNOWN_BEGIN)
                } else {
                    val uncertain = try { db.inTransaction() } catch (inspection: Throwable) {
                        failure.addSuppressed(inspection); true
                    }
                    synchronized(gcRegistry) { if (!uncertain) gcTransactions.remove(opening) else gcUnknownEnd = true }
                    if (uncertain) retire(CountTrustRetirementReason.UNKNOWN_BEGIN)
                }
                deferred?.takeIf { it !== failure }?.let(failure::addSuppressed)
                throw failure
            }
            if (!entered) deferred = IllegalStateException("COUNT_TRUST_SOURCE_BEGIN_LISTENER_MISSING")
            val rejected = deferred
            if (rejected != null) {
                try {
                    db.endTransaction()
                    check(!db.inTransaction()) { "COUNT_TRUST_OUTER_END_NOT_OBSERVED" }
                    opening.successes.clear(); opening.ended = true; opening.committed = false
                    synchronized(gcRegistry) { gcTransactions.remove(opening) }
                } catch (cleanup: Throwable) {
                    synchronized(gcRegistry) { gcUnknownEnd = true }
                    retire(CountTrustRetirementReason.UNKNOWN_END)
                    rejected.addSuppressed(cleanup)
                }
                throw rejected
            }
            synchronized(gcRegistry) { gcTransactions[opening] = true }
            transaction.set(opening)
            var retiredTrim = false
            try { journal?.let {
                if (isRetired) { retiredTrim = true; it.trimRetired() } else it.trim()
            } } catch (failure: Throwable) {
                retire(CountTrustRetirementReason.JOURNAL_OR_EXTERNAL_CHANGE)
                recordBeginJournalFailure(write, retiredTrim, failure)
            }
        }
        override fun beginTransaction() = begin(body = { db.beginTransaction() },
            withListener = { db.beginTransactionWithListener(it) })
        override fun beginTransactionNonExclusive() = begin(body = { db.beginTransactionNonExclusive() },
            withListener = { db.beginTransactionWithListenerNonExclusive(it) })
        override fun beginTransactionReadOnly() = begin(write = false, body = { db.beginTransactionReadOnly() },
            withListener = { db.beginTransactionWithListenerReadOnly(it) })
        override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) = begin(listener = transactionListener,
            body = { db.beginTransactionWithListener(transactionListener) }, withListener = { db.beginTransactionWithListener(it) })
        override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) = begin(listener = transactionListener,
            body = { db.beginTransactionWithListenerNonExclusive(transactionListener) }, withListener = { db.beginTransactionWithListenerNonExclusive(it) })
        override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) = begin(write = false, listener = transactionListener,
            body = { db.beginTransactionWithListenerReadOnly(transactionListener) }, withListener = { db.beginTransactionWithListenerReadOnly(it) })
        override fun setTransactionSuccessful() {
            connection(); requireNotObserving()
            requireNoReadSnapshot()
            if (bootDepth() > 0) { db.setTransactionSuccessful(); return }
            check(current.get() == null) { "COUNT_TRUST_MAINTENANCE_TRANSACTION_CONTROL" }
            val tx = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
            tx.allocatorFailure?.let { throw it }
            DatabaseOperationSource.current()?.let { if (!tx.sources.containsKey(it)) requireSource(tx, it, first = true) }
            checkFinalSources(tx)
            try {
                if (tx.successes.size == 1) {
                    check(!tx.primaryResetPending || tx.primaryResetFinished) { "COMMAND_RESET_NOT_FINALIZED" }
                    if (tx.primaryResetPending) CountCommandStorageInitialization.verifyMandatory(db)
                    verifyCommandAtOuterCommit(tx)
                }
                db.setTransactionSuccessful()
                tx.successes[tx.successes.lastIndex] = true
            } catch (failure: Throwable) { retire(CountTrustRetirementReason.TRANSACTION_STATE_UNKNOWN); throw failure }
        }
        override fun endTransaction() {
            connection(); requireNotObserving()
            requireNoReadSnapshot()
            if (bootDepth() > 0) { db.endTransaction(); return }
            check(current.get() == null) { "COUNT_TRUST_MAINTENANCE_TRANSACTION_CONTROL" }
            val tx = checkNotNull(transaction.get()) { "COUNT_TRUST_UNOWNED_TRANSACTION" }
            val successful = tx.successes.removeAt(tx.successes.lastIndex)
            // Never block rollback/cleanup. A successful outer END must still execute its
            // native onCommit gate; that gate forces rollback on a latched stale source.
            if (successful && tx.successes.isEmpty()) try { checkFinalSources(tx) }
            catch (failure: Throwable) { tx.sourceEndFailure = failure }
            try {
                // A cold generated-reference transaction may inherit diagnostics but is not
                // the primary command. Only original command T0/T1 contributes this END span.
                if (tx.successes.isEmpty() && tx.command != null) CountCommandCostTiming.measure(tx.costs, Step.NATIVE_END_CALL) { db.endTransaction() }
                else db.endTransaction()
                if (!successful) tx.failedChild = true
                if (tx.successes.isEmpty()) {
                    check(!db.inTransaction()) { "COUNT_TRUST_OUTER_END_NOT_OBSERVED" }
                    tx.ended = true; tx.committed = successful && !tx.failedChild
                    synchronized(gcRegistry) {
                        gcTransactions.remove(tx)
                        if (tx.committed && tx.proofAllocations) {
                            gcAllocationEpoch = if (gcAllocationEpoch == Long.MAX_VALUE) 0 else gcAllocationEpoch + 1
                            gcRequested = true
                        }
                        if (tx.committed && tx.commandProofAllocations) {
                            commandGcAllocationEpoch = if (commandGcAllocationEpoch == Long.MAX_VALUE) 0 else commandGcAllocationEpoch + 1
                            commandGcRequested = true
                        }
                    }
                    // TEMP DDL follows the same known rollback. Never retain an in-memory
                    // nonce whose native tables were rolled back. A fresh install must still
                    // prove the complete schema and fail if any unexpected TEMP object exists.
                    if (!tx.committed && tx.installedJournal != null && journal === tx.installedJournal) journal = null
                    if (!tx.committed && tx.installedCommandJournal != null && commandJournal === tx.installedCommandJournal) commandJournal = null
                    if (tx.committed && tx.resetCommandJournal != null) {
                        check(commandJournal === tx.resetCommandJournal) { "COMMAND_RESET_JOURNAL_CHANGED" }
                        commandJournal = null
                    }
                    if (tx.committed && tx.resetBusinessJournal != null) {
                        check(journal === tx.resetBusinessJournal) { "COMMAND_RESET_BUSINESS_JOURNAL_CHANGED" }
                        journal = null
                    }
                }
            } catch (failure: Throwable) {
                // Inspect exactly the failing outer END's native handle on its original thread,
                // before its ThreadLocal ownership is removed. This cannot certify commit or
                // rollback, and must never be substituted for the original EndReceipt.
                val observedEpoch = generation
                val sameAttempt = tx.successes.isEmpty() && tx.native === db &&
                    tx.incarnation === nativeIncarnation && nativeDatabase === db &&
                    tx.epoch == observedEpoch && transaction.get() === tx &&
                    tx.thread === Thread.currentThread()
                val closed = if (sameAttempt) try { !db.inTransaction() }
                    catch (inspection: Throwable) { failure.addSuppressed(inspection); false } else false
                synchronized(gcRegistry) {
                    gcUnknownEnd = true
                    if (closed && nativeDatabase === db && nativeIncarnation === tx.incarnation &&
                        generation == observedEpoch && gcTransactions[tx] == true)
                        tx.physicalEnd = PhysicalEnd(db, tx.incarnation, tx.epoch, observedEpoch, tx.thread)
                }
                retire(CountTrustRetirementReason.UNKNOWN_END); throw failure
            } finally { if (tx.successes.isEmpty()) transaction.remove() }
        }
        override fun yieldIfContendedSafely(): Boolean = error("COUNT_TRUST_YIELD_DENIED")
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = error("COUNT_TRUST_YIELD_DENIED")
        override fun execPerConnectionSQL(sql: String, bindArgs: Array<out Any?>?) { control(); db.execPerConnectionSQL(sql, bindArgs) }
        override var version: Int
            get() { connection(); return db.version }
            set(value) { control(); db.version = value }
        override val path: String? get() { connection(); return db.path }
        override val maximumSize: Long get() { connection(); return db.maximumSize }
        override fun needUpgrade(newVersion: Int): Boolean { connection(); return db.needUpgrade(newVersion) }
        override val attachedDbs: List<android.util.Pair<String, String>>? get() { connection(); return db.attachedDbs?.toList() }
        override val isDatabaseIntegrityOk: Boolean get() { connection(); return db.isDatabaseIntegrityOk }
        override fun setMaximumSize(numBytes: Long): Long { control(); return db.setMaximumSize(numBytes) }
        override var pageSize: Long
            get() { connection(); return db.pageSize }
            set(value) { control(); db.pageSize = value }
        override fun setLocale(locale: Locale) { control(); db.setLocale(locale) }
        override fun setMaxSqlCacheSize(cacheSize: Int) { control(); db.setMaxSqlCacheSize(cacheSize) }
        override fun setForeignKeyConstraintsEnabled(enabled: Boolean) { control(); db.setForeignKeyConstraintsEnabled(enabled) }
        override fun enableWriteAheadLogging(): Boolean { control(); return db.enableWriteAheadLogging() }
        override fun disableWriteAheadLogging() { control(); db.disableWriteAheadLogging() }
        override val isOpen: Boolean get() = nativeDatabase === db && db.isOpen
        override fun inTransaction(): Boolean { connection(); return db.inTransaction() }
        override val isReadOnly: Boolean get() { connection(); return db.isReadOnly }
        override val isWriteAheadLoggingEnabled: Boolean get() { connection(); return db.isWriteAheadLoggingEnabled }
        override fun close() = closeNative(db) { db.close() }
    }

    /** A plain interface wrapper, never CursorWrapper/SQLiteCursor/CrossProcessCursor. */
    private fun cursor(value: Cursor, db: SupportSQLiteDatabase, epoch: Long, lease: Lease?, tx: Tx?, snapshot: ReadLease?): Cursor {
        fun valid() {
            readAccess()
            if (snapshot != null) check(readAccess() === snapshot) { "COUNT_TRUST_READ_SNAPSHOT_EXPIRED" }
            check(nativeDatabase === db && db.isOpen && generation == epoch && !value.isClosed) { "COUNT_TRUST_CURSOR_EXPIRED" }
            if (lease != null) check(activeLease() === lease) { "COUNT_TRUST_MAINTENANCE_EXPIRED" }
            if (tx != null) check(transaction.get() === tx && !tx.ended && db.inTransaction()) { "COUNT_TRUST_CURSOR_TRANSACTION_ENDED" }
        }
        return object : Cursor {
            override fun close() = value.close()
            override fun isClosed(): Boolean = value.isClosed
            override fun getCount(): Int { valid(); return value.count }
            override fun getPosition(): Int { valid(); return value.position }
            override fun getColumnCount(): Int { valid(); return value.columnCount }
            override fun getColumnNames(): Array<String> { valid(); return value.columnNames.copyOf() }
            override fun getColumnName(columnIndex: Int): String { valid(); return value.getColumnName(columnIndex) }
            override fun getColumnIndex(columnName: String): Int { valid(); return value.getColumnIndex(columnName) }
            override fun getColumnIndexOrThrow(columnName: String): Int { valid(); return value.getColumnIndexOrThrow(columnName) }
            override fun isBeforeFirst(): Boolean { valid(); return value.isBeforeFirst }
            override fun isAfterLast(): Boolean { valid(); return value.isAfterLast }
            override fun isFirst(): Boolean { valid(); return value.isFirst }
            override fun isLast(): Boolean { valid(); return value.isLast }
            override fun copyStringToBuffer(columnIndex: Int, buffer: android.database.CharArrayBuffer) { valid(); value.copyStringToBuffer(columnIndex, buffer) }
            override fun moveToPosition(position: Int): Boolean { valid(); return value.moveToPosition(position) }
            override fun move(offset: Int): Boolean { valid(); return value.move(offset) }
            override fun moveToFirst(): Boolean { valid(); return value.moveToFirst() }
            override fun moveToLast(): Boolean { valid(); return value.moveToLast() }
            override fun moveToNext(): Boolean { valid(); return value.moveToNext() }
            override fun moveToPrevious(): Boolean { valid(); return value.moveToPrevious() }
            override fun getString(columnIndex: Int): String? { valid(); return value.getString(columnIndex) }
            override fun getBlob(columnIndex: Int): ByteArray? { valid(); return value.getBlob(columnIndex)?.copyOf() }
            override fun getLong(columnIndex: Int): Long { valid(); return value.getLong(columnIndex) }
            override fun getInt(columnIndex: Int): Int { valid(); return value.getInt(columnIndex) }
            override fun getShort(columnIndex: Int): Short { valid(); return value.getShort(columnIndex) }
            override fun getFloat(columnIndex: Int): Float { valid(); return value.getFloat(columnIndex) }
            override fun getDouble(columnIndex: Int): Double { valid(); return value.getDouble(columnIndex) }
            override fun getType(columnIndex: Int): Int { valid(); return value.getType(columnIndex) }
            override fun isNull(columnIndex: Int): Boolean { valid(); return value.isNull(columnIndex) }
            override fun getWantsAllOnMoveCalls(): Boolean { valid(); return value.wantsAllOnMoveCalls }
            override fun getExtras(): android.os.Bundle { valid(); return android.os.Bundle(value.extras) }
            override fun getNotificationUri(): android.net.Uri? { valid(); return value.notificationUri }
            override fun getNotificationUris(): List<android.net.Uri>? {
                valid()
                return if (android.os.Build.VERSION.SDK_INT >= 29) value.notificationUris?.toList()
                else value.notificationUri?.let { listOf(it) }
            }
            override fun registerContentObserver(observer: android.database.ContentObserver) { valid(); value.registerContentObserver(observer) }
            override fun unregisterContentObserver(observer: android.database.ContentObserver) { valid(); value.unregisterContentObserver(observer) }
            override fun registerDataSetObserver(observer: android.database.DataSetObserver) { valid(); value.registerDataSetObserver(observer) }
            override fun unregisterDataSetObserver(observer: android.database.DataSetObserver) { valid(); value.unregisterDataSetObserver(observer) }
            override fun setNotificationUri(cr: android.content.ContentResolver, uri: android.net.Uri) { valid(); value.setNotificationUri(cr, uri) }
            override fun setNotificationUris(cr: android.content.ContentResolver, uris: List<android.net.Uri>) {
                valid()
                if (android.os.Build.VERSION.SDK_INT >= 29) value.setNotificationUris(cr, uris)
                else {
                    check(uris.size == 1) { "COUNT_TRUST_CURSOR_MULTI_NOTIFY_UNAVAILABLE" }
                    value.setNotificationUri(cr, uris.single())
                }
            }
            override fun setExtras(extras: android.os.Bundle) { valid(); value.extras = android.os.Bundle(extras) }
            override fun respond(extras: android.os.Bundle): android.os.Bundle { valid(); return android.os.Bundle(value.respond(android.os.Bundle(extras))) }
            @Suppress("OVERRIDE_DEPRECATION") override fun requery(): Boolean = error("COUNT_TRUST_CURSOR_REQUERY_DENIED")
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") override fun deactivate() { valid(); value.deactivate() }
        }
    }

    companion object {
        // No SQL, coroutine wait, BEGIN/END or Keystore call is made while holding this monitor.
        private val gcRegistry = Any()
        private val gcOwners = java.util.WeakHashMap<CountTrustOpenHelperFactory, Unit>()
        private object MaintenanceKey : CoroutineContext.Key<MaintenanceElement>
        private object ReadKey : CoroutineContext.Key<ReadElement>
        internal fun enlistCommandMigration(sql: SupportSQLiteDatabase) {
            val controlled = sql as? Database ?: error("COMMAND_CONTROLLED_MIGRATION_REQUIRED")
            controlled.enlistCommandMigration()
        }
        internal fun isApprovedReplicaGuard(name: String, sql: String): Boolean {
            val definition = NextReplicaFenceSql.businessAndLocalDefinitions()[name] ?: return false
            val declared = tokens(definition, exactKey = true)
            val actual = tokens(sql, exactKey = true)
            return actual == declared || actual == declared.take(2) + declared.drop(5)
        }
        internal fun isControlled(database: SupportSQLiteDatabase): Boolean = database is Database
        private val explainedWriteTokens = setOf("insert", "update", "delete", "replace", "pragma",
            "create", "drop", "alter", "reindex", "analyze", "vacuum", "attach", "detach",
            "begin", "end", "commit", "rollback", "savepoint", "release")
        private val scalarPragmas = setOf("query_only", "foreign_keys", "defer_foreign_keys", "recursive_triggers", "user_version", "schema_version", "data_version", "journal_mode", "synchronous", "page_count", "page_size", "freelist_count", "encoding", "database_list", "compile_options")
        private val readPragmas = scalarPragmas + setOf("table_info", "table_xinfo", "index_list", "index_info", "index_xinfo", "foreign_key_list", "foreign_key_check", "integrity_check", "quick_check", "wal_checkpoint")
        private val snapshotReadPragmas = readPragmas - "wal_checkpoint"
        private val frameworkSql: Set<List<String>> by lazy {
            buildSet {
                add("SELECT * FROM room_table_modification_log WHERE invalidated = 1")
                add("UPDATE room_table_modification_log SET invalidated = 0 WHERE invalidated = 1")
                // Frozen Room entity order, not a caller-controlled SQL registry.
                (CountTrustSchema.BUSINESS_TABLES + CountCommandV20Schema.currentLocalTables).forEachIndexed { index, table ->
                    add("INSERT OR IGNORE INTO room_table_modification_log VALUES($index, 0)")
                    for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                        val name = "room_table_modification_trigger_${table}_$operation"
                        add("CREATE TEMP TRIGGER IF NOT EXISTS `$name` AFTER $operation ON `$table` BEGIN UPDATE room_table_modification_log SET invalidated = 1 WHERE table_id = $index AND invalidated = 0; END")
                        add("DROP TRIGGER IF EXISTS `$name`")
                    }
                }
            }.map { tokens(it, exactKey = true) }.toSet()
        }
        private val replicaMaintenanceSql: Map<Int, Set<List<String>>> by lazy {
            listOf(19, 20).associateWith { version -> NextReplicaFenceSql.businessAndLocalDefinitions(version).flatMap { (name, definition) ->
                val declared = tokens(definition, exactKey = true)
                check(declared.take(5) == tokens("CREATE TRIGGER IF NOT EXISTS", exactKey = true))
                // SQLite removes IF NOT EXISTS from main.sqlite_master.sql. Replacement
                // restores that stored SQL, so admit this exact second spelling of each
                // compiled guard, without removing tokens from arbitrary caller input.
                listOf(declared, declared.take(2) + declared.drop(5),
                    tokens("DROP TRIGGER IF EXISTS `$name`", exactKey = true),
                    tokens("DROP TRIGGER `$name`", exactKey = true))
            }.toSet() }
        }

        /** SQLite lexical tokens, including quoted identifiers. Comments cannot hide a write target. */
        private fun tokens(sql: String, exactKey: Boolean = false): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c.isWhitespace() -> i++
                    c == '-' && sql.getOrNull(i + 1) == '-' -> { i += 2; while (i < sql.length && sql[i] != '\n') i++ }
                    c == '/' && sql.getOrNull(i + 1) == '*' -> {
                        val end = sql.indexOf("*/", i + 2)
                        check(end >= 0) { "COUNT_TRUST_SQL_UNTERMINATED_COMMENT" }; i = end + 2
                    }
                    c == '\'' || c == '"' || c == '`' || c == '[' -> {
                        val end = if (c == '[') ']' else c
                        i++; val value = StringBuilder(); var closed = false
                        while (i < sql.length) {
                            if (sql[i] == end) {
                                i++
                                if (c != '[' && sql.getOrNull(i) == end) { value.append(end); i++ }
                                else { closed = true; break }
                            } else value.append(sql[i++])
                        }
                        check(closed) { "COUNT_TRUST_SQL_UNTERMINATED_QUOTE" }
                        // Exact allowlists preserve both lexical kind and token boundaries:
                        // a quoted identifier containing a literal or several words cannot
                        // impersonate that literal or those words. SQL string case matters.
                        result += if (exactKey) {
                            if (c == '\'') "string:$value" else "identifier:${asciiFold(value.toString())}"
                        } else value.toString().lowercase(Locale.ROOT)
                    }
                    c.isLetterOrDigit() || c == '_' -> {
                        val start = i++
                        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_' || sql[i] == '$')) i++
                        val value = sql.substring(start, i)
                        result += if (exactKey) "word:${asciiFold(value)}" else value.lowercase(Locale.ROOT)
                    }
                    else -> { result += if (exactKey) "symbol:$c" else c.toString(); i++ }
                }
            }
            val terminator = if (exactKey) "symbol:;" else ";"
            while (result.lastOrNull() == terminator) result.removeAt(result.lastIndex)
            return result
        }
        // SQLite identifiers/keywords fold ASCII case; non-ASCII characters must not turn
        // into a different approved ASCII name through Unicode case normalization.
        private fun asciiFold(value: String): String = buildString(value.length) {
            value.forEach { append(if (it in 'A'..'Z') it.lowercaseChar() else it) }
        }
    }
}
