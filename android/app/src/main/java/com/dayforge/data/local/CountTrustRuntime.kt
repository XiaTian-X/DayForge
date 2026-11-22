package com.dayforge.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Connection ownership and maintenance capabilities only; never business or login authority. */
internal class CountTrustRuntime internal constructor(
    private val factory: CountTrustOpenHelperFactory,
    val applicationContext: Context
) {
    val context: Context get() = applicationContext
    // Wake-up only; an arbitrary signal never grants qualification or proves physical drain.
    internal val qualificationChanges = kotlinx.coroutines.flow.MutableStateFlow<Any>(Any())
    internal fun qualificationReleased() { qualificationChanges.value = Any() }
    // Identity only. A sealed value snapshot does not retain this runtime or any SQL permit.
    internal val countAddIncarnation: Any = Any()
    val isRetired: Boolean get() = factory.isRetired
    val generation: Long get() = factory.generation
    val retirementReasons: Set<CountTrustRetirementReason> get() = factory.retirementSnapshot()
    // Read-only DEBUG diagnostics from the outer BEGIN's existing journal cleanup catch.
    // write is the requested BEGIN mode, not a physical SQLite connection identity.
    internal enum class BeginJournalInvariant {
        COUNT_TRUST_JOURNAL_TRANSACTION_REQUIRED, COUNT_TRUST_CONNECTION_CHANGED,
        COUNT_TRUST_EXTERNAL_CHANGE, COUNT_TRUST_SCHEMA_CHANGED,
        COUNT_TRUST_REPLACE_CAPTURE_DISABLED, OTHER
    }
    internal data class BeginJournalFailureDiagnostic(val write: Boolean, val retiredTrim: Boolean,
        val exceptionClass: String, val invariant: BeginJournalInvariant)
    val beginJournalFailureDiagnostic: BeginJournalFailureDiagnostic? get() = factory.beginJournalFailureSnapshot()
    internal fun newRecoveryPreview(): CountTrustRecoveryPreview = factory.newRecoveryPreview()
    internal fun bindRecoveryPreview(preview: CountTrustRecoveryPreview, storageId: String) =
        factory.bindRecoveryPreview(preview, storageId)
    internal fun recoveryPreviewSuppressed(storageId: String): Boolean = factory.recoveryPreviewSuppressed(storageId)

    internal fun captureGraphDrain(database: HabitDatabase): CountTrustOpenHelperFactory.GraphDrain {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.captureGraphDrain(database)
    }
    internal fun trySealGraphClose(database: HabitDatabase, witness: Any, knownOnly: Boolean = false): Boolean {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.trySealGraphClose(database, witness, knownOnly)
    }
    internal fun requireGraphClosed(database: HabitDatabase, witness: Any) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        factory.requireGraphClosed(database, witness)
    }

    internal fun isGraphCloseConfirmed(database: HabitDatabase): Boolean {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.isGraphCloseConfirmed(database)
    }
    internal fun hasUnknownGraphBoundary(): Boolean = factory.hasUnknownGraphBoundary()
    internal fun trySealUnopenedGraphClose(database: HabitDatabase): Any? {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.trySealUnopenedGraphClose(database)
    }
    internal fun requireUnopenedGraphClosed(database: HabitDatabase, witness: Any) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        factory.requireUnopenedGraphClosed(database, witness)
    }

    internal fun captureGraphSource(database: HabitDatabase): Any = factory.captureGraphSource(database)

    fun requireUsable() = factory.requireUsable()
    fun captureChangeBoundary(): CountTrustJournal.Boundary = factory.captureChangeBoundary()
    fun pendingChanges(boundary: CountTrustJournal.Boundary): List<CountTrustJournal.Change> =
        factory.pendingChanges(boundary)
    fun hasPendingChanges(boundary: CountTrustJournal.Boundary): Boolean = factory.hasPendingChanges(boundary)
    internal fun unchangedChallengeWindow(boundary: CountTrustJournal.Boundary): CountTrustJournal.ChallengeWindow? =
        factory.unchangedChallengeWindow(boundary)
    fun readCountAddEffects(boundary: CountTrustJournal.Boundary,
        expected: List<CountTrustJournal.CountAddEffect>): List<CountTrustJournal.Change>? =
        factory.readCountAddEffects(boundary, expected)
    fun sourceWindow(boundary: CountTrustJournal.Boundary, expectedSources: Int): CountTrustJournal.SourceWindow? =
        factory.sourceWindow(boundary, expectedSources)
    fun verifySourceWindow(window: CountTrustJournal.SourceWindow, rows: List<List<CountTrustJournal.Cell>>): Boolean =
        factory.verifySourceWindow(window, rows)
    fun schemaSnapshot(): List<List<String?>> = factory.schemaSnapshot()
    /** Expiration guard for retained read evidence; cannot acquire maintenance or authorize writes. */
    fun captureReadToken(database: HabitDatabase): Any {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.captureReadToken(database)
    }
    fun requireReadToken(database: HabitDatabase, token: Any) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        factory.requireReadToken(database, token)
    }
    internal val nodeCollectionRequested: Boolean get() = factory.nodeCollectionRequested()
    internal val nodeAllocationEpoch: Long get() = factory.nodeAllocationEpoch()
    internal fun nodeCollectionFinished(observedEpoch: Long) = factory.nodeCollectionFinished(observedEpoch)
    internal val commandNodeCollectionRequested: Boolean get() = factory.commandNodeCollectionRequested()
    internal val commandNodeAllocationEpoch: Long get() = factory.commandNodeAllocationEpoch()
    internal fun commandNodeCollectionFinished(observedEpoch: Long) = factory.commandNodeCollectionFinished(observedEpoch)
    internal enum class NodeGcAdmission { READY, BUSY, UNAVAILABLE }
    internal sealed interface NodeGcPermitAttempt {
        data object Busy : NodeGcPermitAttempt
        data object Unavailable : NodeGcPermitAttempt
        class Granted internal constructor(val token: Any) : NodeGcPermitAttempt
    }
    internal fun nodeCollectionAdmission(database: HabitDatabase): NodeGcAdmission {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.nodeCollectionAdmission(database)
    }
    internal fun captureNodeGcPermit(database: HabitDatabase): NodeGcPermitAttempt {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.captureNodeGcPermit(database)
    }
    internal fun requireNodeGcPermit(database: HabitDatabase, permit: Any) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        factory.requireNodeGcPermit(database, permit)
    }
    /** Recovery deliberately abandons optional fast qualification for this opened owner.
     * Only an existing maintenance capability may do this; DAO/restore handles remain valid.
     */
    fun retireFastPathForRecovery() = factory.retireFastPathForRecovery()
    suspend fun compactAfterClear(database: HabitDatabase) = factory.compactAfterClear(database)

    /** Fixed preparation in an already authorized write transaction. This is not account
     * authority: each caller first validates its original state/ticket, before capturing
     * new business evidence. The native lease ends before any suspend Room DAO is called. */
    internal suspend fun prepareOutboxAllocatorsForWrite(database: HabitDatabase) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        com.dayforge.data.repository.NextEvidenceReadSession.requireWritePhase()
        factory.maintenance(database) { factory.prepareEmptyOutboxAllocators(database) }
    }

    suspend fun <T> maintenance(database: HabitDatabase, block: suspend () -> T): T {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.maintenance(database, block)
    }

    /** One existing actual outer write transaction, including a soft-retired recovery owner. No writes,
     * transaction control or proof qualification may occur until this scope has returned.
     */
    suspend fun <T> withReadSnapshot(database: HabitDatabase, block: suspend () -> T): T {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.withReadSnapshot(database, block)
    }

    sealed interface EndReceipt { fun requireCandidate(candidate: Any?) }
    class Completed<T> internal constructor(val value: T, val end: EndReceipt)

    /** This convenience owner supplies a receipt only after Room's actual outer END returns. */
    suspend fun <T> transaction(database: HabitDatabase, block: suspend () -> T): Completed<T> {
        check(forDatabase(database) === this && !database.inTransaction()) { "COUNT_TRUST_OUTER_TRANSACTION_REQUIRED" }
        var token: Any? = null
        val value = database.withTransaction {
            token = factory.currentTransactionToken()
            block()
        }
        currentCoroutineContext().ensureActive()
        return Completed(value, factory.completed(requireNotNull(token), value))
    }

    suspend fun preparePrimaryReset(database: HabitDatabase): Any? {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        com.dayforge.data.repository.NextEvidenceReadSession.requireWritePhase()
        return factory.preparePrimaryReset(database)
    }
    fun initializePrimaryReset(token: Any?) = factory.initializePrimaryReset(token)
    fun finishPrimaryReset(token: Any?) = factory.finishPrimaryReset(token)

    /** Primary business evidence shares native collection, but never optional COUNT authority.
     * Only the exact current private command transaction/caller can issue/use this boundary.
     */
    sealed interface PrimaryBusinessBoundary
    fun capturePrimaryBusinessBoundary(): PrimaryBusinessBoundary = factory.capturePrimaryBusinessBoundary()
    fun primaryBusinessChanges(boundary: PrimaryBusinessBoundary): List<CountTrustJournal.Change> =
        factory.primaryBusinessChanges(boundary)

    fun beginCommandBusiness(boundary: CountCommandJournal.Boundary) = factory.beginCommandBusiness(boundary)
    fun sealCommandOperation() = factory.sealCommandOperation()
    fun captureCommandBoundary(): CountCommandJournal.Boundary = factory.captureCommandBoundary()
    fun verifyCommandBoundary(boundary: CountCommandJournal.Boundary, expected: List<CountCommandJournal.Expected>) =
        factory.verifyCommandBoundary(boundary, expected)

    /** Primary command transaction lifetime is independent of optional COUNT qualification.
     * It is NOT command authorization. Only the private command participant may turn its own
     * validated native candidate into a publishable result after fresh authority revalidation.
     */
    sealed interface PrimaryEndReceipt {
        fun requireCandidate(candidate: Any?)
        fun requireEvidenceToken(token: Any)
    }
    class PrimaryCompleted<T> internal constructor(val value: T, val end: PrimaryEndReceipt)
    fun capturePrimaryReadToken(database: HabitDatabase): Any {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        return factory.capturePrimaryReadToken(database)
    }
    fun requirePrimaryReadToken(database: HabitDatabase, token: Any) {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        factory.requirePrimaryReadToken(database, token)
    }
    suspend fun <T> primaryTransaction(database: HabitDatabase, block: suspend () -> T): PrimaryCompleted<T> {
        check(forDatabase(database) === this && !database.inTransaction()) { "COMMAND_PRIMARY_OUTER_TRANSACTION_REQUIRED" }
        var token: Any? = null
        val value = database.withTransaction {
            token = factory.currentTransactionToken()
            factory.maintenance(database) { factory.captureCommandBoundary() }
            block()
        }
        currentCoroutineContext().ensureActive()
        return PrimaryCompleted(value, factory.primaryCompleted(database, requireNotNull(token), value))
    }

    /** Closed COUNT preparation's A transaction. Null discards preparation if Room joined a
     * known live outer transaction, or if exact optional qualification suppression returned
     * no value. Neither starts B. Actual END failure/cancellation/unknown owner still throws;
     * no writer success is inferred. A receipt binds the same exact candidate/actual Tx as C.
     */
    internal suspend fun <T> countAddPreparation(database: HabitDatabase, block: suspend () -> T): Completed<T>? {
        check(forDatabase(database) === this) { "COUNT_TRUST_FOREIGN_DATABASE" }
        var token: Any? = null
        val value = database.withTransaction {
            token = factory.countAddPreparationToken(database)
            block()
        }
        currentCoroutineContext().ensureActive()
        val end = factory.countAddPreparationEnd(requireNotNull(token), value) ?: return null
        return Completed(value, end)
    }

    /** Private typed C selection: only the caller's null/no-capture/pre-apply branch may
     * discard optional qualification at a known actual END. Applied/constructed/non-null
     * candidates never use that exception and must receive the original strict receipt.
     */
    internal suspend fun <T> countAddSelection(database: HabitDatabase, block: suspend () -> T?): Completed<T?>? {
        check(forDatabase(database) === this && !database.inTransaction()) { "COUNT_TRUST_OUTER_TRANSACTION_REQUIRED" }
        var token: Any? = null
        val value = database.withTransaction {
            token = factory.countAddPreparationToken(database)
            block()
        }
        currentCoroutineContext().ensureActive()
        val end = factory.countAddSelectionEnd(requireNotNull(token), value) ?: return null
        return Completed(value, end)
    }

    companion object {
        private val owners = WeakHashMap<HabitDatabase, WeakReference<CountTrustRuntime>>()

        fun attach(database: HabitDatabase, factory: CountTrustOpenHelperFactory) {
            synchronized(owners) {
                val existing = owners[database]?.get()
                check(existing == null || existing === factory.runtime) { "COUNT_TRUST_OWNER_ALREADY_ATTACHED" }
                factory.attach(database)
                owners[database] = WeakReference(factory.runtime)
            }
        }

        fun forDatabase(database: HabitDatabase): CountTrustRuntime? = synchronized(owners) { owners[database]?.get() }
        fun enlistCommandMigration(sql: SupportSQLiteDatabase) = CountTrustOpenHelperFactory.enlistCommandMigration(sql)
        fun isControlledDatabase(sql: SupportSQLiteDatabase): Boolean = CountTrustOpenHelperFactory.isControlled(sql)

        suspend fun compactAfterClear(database: HabitDatabase) =
            requireNotNull(forDatabase(database)) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }.compactAfterClear(database)

        suspend fun <T> maintenance(database: HabitDatabase, block: suspend () -> T): T =
            requireNotNull(forDatabase(database)) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }.maintenance(database, block)

        suspend fun <T> withReadSnapshot(database: HabitDatabase, block: suspend () -> T): T =
            requireNotNull(forDatabase(database)) { "COUNT_TRUST_CONTROLLED_DATABASE_REQUIRED" }.withReadSnapshot(database, block)
    }
}
