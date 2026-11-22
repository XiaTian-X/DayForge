package com.dayforge.data.repository

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRetirementReason
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Full recovery proof boundaries. These are actual controlled SQLite/KeyStore operations,
 * not process-death or pixel evidence. The two metadata cases assert the new full ticket's
 * pre-producer rejection; they do not strengthen ordinary/plain late-rejection semantics. */
@RunWith(AndroidJUnit4::class)
class CountCommandCompleteProofBoundaryTest : NextObjectEditorFixture() {
    private val fault = CompleteProofNativeBoundary()
    init { storage.isolatedBuilder = { buildDiagnosticFaultDatabase(it, fault) } }
    private val sql get() = db.openHelper.writableDatabase
    private data class Prepared(val store: CountCommandStore, val input: CountCommandRecords.Intent,
        val reference: CountCommandReference)

    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun prepared(): Prepared {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority,
            Instant.now(), ZoneId.systemDefault(), 1)
        val store = CountCommandStore(db, tokens, sessions)
        val registered = store.register(input)
        store.releaseUnadmitted(requireNotNull(registered.ticket))
        db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        val fast = store.recover(registered.reference) as CountCommandStore.Recovery.Unresolved
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", fast.reason)
        return Prepared(store, input, CountCommandReference.from(registered.reference))
    }
    private fun reopen() {
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }
    private suspend fun requirePending(p: Prepared) {
        val row = CountCommandStore(db, tokens, sessions).resolve(p.reference.native()).historical
        assertEquals(p.input.text(), row.intent.text())
        assertEquals(CountCommandRecords.Execution.PREPARED, row.status.execution)
        assertNull(row.outcome)
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }
    private suspend fun commitSameInput(p: Prepared) {
        val recovered = editor.recoverCommandAfterCompleteProof(p.reference,
            setOf(CountCommandRecords.Action.COUNT_ADD)) as NextObjectEditor.RecoveryAttempt.Execute
        val beforeWrites = fault.completionWrites.get()
        val result = creatingHabits().logCompletionWithReceipt(app, habit.id,
            authority = recovered.command.authority, expectedHabitUuid = habit.uuid,
            preparedCommand = recovered.command)
        val receipt = requireNotNull(result.command)
        assertEquals(p.reference, receipt.reference)
        assertTrue(receipt.recorded)
        assertEquals(beforeWrites + 1, fault.completionWrites.get())
        val rows = db.completionDao().getAllCompletionsOnce()
        assertEquals(1, rows.size)
        val original = p.input.body().getValue("input").jsonObject
        assertEquals(CountCommandRecords.uuid(original, "reserved_fact_uuid"), rows.single().uuid)
        assertEquals(Instant.parse(CountCommandRecords.text(original, "occurred_at")).toEpochMilli(), rows.single().actualCompletedAt)
        assertEquals(CountCommandRecords.text(original, "timezone"), rows.single().recordedTimezone)
        assertEquals(1, db.syncOutboxDao().getAll().size)
        val current = CountCommandStore(db, tokens, sessions).resolve(p.reference.native()).historical
        assertEquals(p.input.text(), current.intent.text())
        assertEquals(CountCommandRecords.Execution.APPLIED, current.status.execution)
        assertNotNull(current.outcome)
    }
    private suspend fun exactRejection(code: String, block: suspend () -> Unit) {
        val error = try { block(); null } catch (cancel: CancellationException) { throw cancel }
            catch (failure: IllegalArgumentException) { failure }
            catch (failure: IllegalStateException) { failure }
        assertEquals(code, error?.message)
    }

    @Test(timeout = 150_000) fun cancellationAtActualFullDomainRowCannotIssueTicketOrRetainExecutionLease() = runBlocking<Unit> {
        val p = prepared(); val before = image()
        val returned = AtomicBoolean()
        supervisorScope {
            val attempt = async(start = CoroutineStart.LAZY) {
                withContext(fault.cohort.asContextElement(true)) {
                    val result = p.store.recoverAfterCompleteProof(p.reference.native())
                    returned.set(true)
                    if (result is CountCommandStore.Recovery.Ready) p.store.releaseUnadmitted(result.ticket)
                }
            }
            fault.cancelOnDomainRow(attempt)
            attempt.start()
            val failure = try { attempt.await(); null } catch (cancel: CancellationException) { cancel }
            assertNotNull(failure)
            withTimeout(5_000) { attempt.join() }
        }
        assertEquals(1, fault.domainRows.get())
        assertEquals(1, fault.cancelSignals.get())
        assertFalse(returned.get())
        assertEquals(0, fault.completionWrites.get())
        assertEquals(before, image())
        requirePending(p)
        // A fresh complete proof must acquire the released command lease and commit once.
        commitSameInput(p)
    }

    @Test(timeout = 150_000) fun actualFullReadEndOutcomeLostCannotIssueTicketAndColdOwnerProvesNoEffects() = runBlocking<Unit> {
        val p = prepared(); val before = image()
        val oldOwner = requireNotNull(CountTrustRuntime.forDatabase(db))
        var returned: CountCommandStore.Recovery? = null
        fault.loseReadEndOutcome()
        val failure = try {
            withContext(fault.cohort.asContextElement(true)) {
                returned = p.store.recoverAfterCompleteProof(p.reference.native())
                (returned as? CountCommandStore.Recovery.Ready)?.let { p.store.releaseUnadmitted(it.ticket) }
            }
            null
        } catch (cancel: CancellationException) { throw cancel }
            catch (error: IllegalStateException) { error }
        fault.requireLostNativeEnd(requireNotNull(failure))
        assertNull(returned)
        assertTrue(oldOwner.isRetired)
        assertTrue(CountTrustRetirementReason.UNKNOWN_END in oldOwner.retirementReasons)
        assertEquals(0, fault.completionWrites.get())
        reopen()
        assertNotSame(oldOwner, CountTrustRuntime.forDatabase(db))
        assertEquals(before, image())
        requirePending(p)
        commitSameInput(p)
    }

    @Test(timeout = 150_000) fun permissionChangeAfterFullProofRejectsBeforeProducerThenFreshProofCommitsSameInput() = runBlocking<Unit> {
        metadataChangeBeforeProducer(revokeAppend = true)
    }

    @Test(timeout = 150_000) fun revisionOnlyChangeAfterFullProofRejectsBeforeProducerThenFreshProofCommitsSameInput() = runBlocking<Unit> {
        metadataChangeBeforeProducer(revokeAppend = false)
    }

    private suspend fun metadataChangeBeforeProducer(revokeAppend: Boolean) {
        register(revision = 1)
        val p = prepared()
        val ready = p.store.recoverAfterCompleteProof(p.reference.native()) as CountCommandStore.Recovery.Ready
        val oldCore = requireNotNull(tokens.localCoreWriteAccess())
        val oldSync = requireNotNull(tokens.localSyncAccess())
        register(permissions = if (revokeAppend) caps - "facts.append" else caps, revision = 2)
        val currentCore = requireNotNull(tokens.localCoreWriteAccess())
        assertEquals(oldCore.session, currentCore.session)
        assertEquals(oldCore.capturedDeviceId, currentCore.capturedDeviceId)
        assertNotEquals(oldSync, requireNotNull(tokens.localSyncAccess()))
        if (!revokeAppend) assertEquals("Core equality alone must not certify capabilityRevision", oldCore, currentCore)
        val before = image(); val writes = fault.completionWrites.get()
        var enteredProducer = false
        try {
            exactRejection("COMMAND_COMPLETE_RECOVERY_REVALIDATION_REQUIRED") {
                p.store.producerTransaction<Unit>(ready.ticket, currentCore) {
                    enteredProducer = true
                    error("Complete proof expired before the original producer callback")
                }
            }
        } finally { p.store.releaseUnadmitted(ready.ticket) }
        assertFalse(enteredProducer)
        assertEquals(writes, fault.completionWrites.get())
        assertEquals(before, image())
        requirePending(p)
        // Keep revision 2, restore permission if necessary, and obtain a NEW full proof.
        register(revision = 2)
        commitSameInput(p)
    }

    @Test(timeout = 150_000) fun sameAccountReloginExpiresOldFullTicketWithoutRebindingOriginalIntent() = runBlocking<Unit> {
        register()
        val p = prepared()
        val ready = p.store.recoverAfterCompleteProof(p.reference.native()) as CountCommandStore.Recovery.Ready
        val original = requireNotNull(tokens.localCoreWriteAccess())
        tokens.saveLoginSession("synthetic-second", "synthetic-refresh-second", "member", id(1), false)
        register()
        val current = requireNotNull(tokens.localCoreWriteAccess())
        assertNotEquals(original.session.authentication.generation, current.session.authentication.generation)
        val before = image(); var enteredProducer = false
        try {
            exactRejection("COMMAND_TICKET_CALLER_EXPIRED") {
                p.store.producerTransaction<Unit>(ready.ticket, current) {
                    enteredProducer = true; error("An old session cannot enter the producer")
                }
            }
        } finally { p.store.releaseUnadmitted(ready.ticket) }
        assertFalse(enteredProducer)
        assertEquals(0, fault.completionWrites.get())
        assertEquals(before, image())
        val unresolved = p.store.recoverAfterCompleteProof(p.reference.native()) as CountCommandStore.Recovery.Unresolved
        assertEquals("ORIGINAL_EXECUTION_SESSION_REVALIDATION_REQUIRED", unresolved.reason)
        assertEquals(before, image())
        requirePending(p)
    }

    @Test(timeout = 150_000) fun legacyDeleteOldPreparedRootReplayCannotBeResignedByCompleteAbsenceProof() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, targetValue = 10)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        fun workflow() = LegacyWidgetFactWorkflow(db, tokens, sessions,
            LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)),
            creatingHabits(), StructuralEditGuard(tokens, mockk()))
        suspend fun claim(action: String): LegacyWidgetFactClaim {
            val identity = sessions.exclusive { db.withTransaction { LegacyWidgetRestartClaim.displayed(habit,
                requireNotNull(tokens.localCoreWriteAccess()), LocalReplicaIdentity.readInTransaction(db)) } }
            return workflow().capture(identity).forAction(action)
        }
        assertTrue(workflow().perform(app, claim("increment")).added)
        val target = db.completionDao().getAllCompletionsOnce().single()
        val originalClaim = claim("decrement")
        val executor = CountCommandLegacyWidgetExecutor(db, tokens, sessions)
        val pending = executor.begin(habit, originalClaim, UUID.randomUUID().toString())
        executor.release(pending.second)
        val store = CountCommandStore(db, tokens, sessions)
        val reference = requireNotNull(store.locateLegacyWidget(pending.first.commandId, originalClaim))
        val oldPrimary = primaryImage()
        val oldRoot = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        val oldControl = db.withTransaction { CountCommandRootAnchor.read(sql) }
        db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        val committed = workflow().resumeCountCommandAfterCompleteProof(app, originalClaim, pending.first.commandId) as WidgetCountCommandRecovery.Committed
        assertEquals(target.uuid, committed.receipt.factUuid)
        assertTrue(committed.receipt.recorded)
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        val result = store.resolve(reference).historical
        assertEquals(pending.first.text(), result.intent.text())
        CountCommandLegacyEvidence.verify(result.intent, requireNotNull(result.outcome))
        assertEquals(target.uuid, db.syncOutboxDao().getAll().single { it.action == "delete" }.entityUuid)
        val currentControl = db.withTransaction { CountCommandRootAnchor.read(sql) }
        assertNotEquals(oldControl.hash, currentControl.hash)
        restoreOnlyPrimary(oldPrimary)
        assertEquals(oldPrimary, primaryImage())
        assertEquals(currentControl, db.withTransaction { CountCommandRootAnchor.read(sql) })
        val replayed = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        assertTrue(CountCommandNativeRows.sameRoot(oldRoot, replayed))
        CountCommandSeal().authenticate(replayed, replayed.storageId, replayed.schemaHash) { oldControl.hash }
        // DELETE has no reserved new fact UUID. Its real completed deletion plus an authentic
        // old P/member must fail current continuity before absence can authorize execution.
        val before = image(); val domainReads = fault.domainRows.get()
        withContext(fault.cohort.asContextElement(true)) {
            exactRejection("COMMAND_PRIMARY_ANCHOR_MISMATCH") { store.recoverAfterCompleteProof(reference) }
        }
        assertEquals(domainReads, fault.domainRows.get())
        assertEquals(before, image())
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
    }

    private data class Cell(val type: Int, val value: Any?) {
        fun binding(): Any? = if (type == Cursor.FIELD_TYPE_BLOB) (value as List<*>).map { it as Byte }.toByteArray() else value
    }
    private data class Table(val name: String, val columns: List<String>, val rows: List<List<Cell>>)
    private val primaryTables get() = (CountCommandV20Schema.tables + "count_command_results").sorted()
    private suspend fun primaryImage(): List<Table> = db.withTransaction { primaryTables.map { name ->
        sql.query("SELECT * FROM main.`$name` ORDER BY rowid").use { c -> Table(name, c.columnNames.toList(), buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { i ->
                Cell(c.getType(i), when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                    else -> error("Unexpected native type")
                })
            })
        }) }
    } }
    private suspend fun restoreOnlyPrimary(snapshot: List<Table>) = db.withTransaction {
        require(snapshot.map { it.name } == primaryTables)
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            for (table in snapshot) sql.execSQL("DELETE FROM main.`${table.name}`")
            for (table in snapshot) for (row in table.rows) {
                val columns = table.columns.joinToString(",") { "`$it`" }
                val values = row.joinToString(",") { "?" }
                sql.execSQL("INSERT INTO main.`${table.name}` ($columns) VALUES ($values)", row.map { it.binding() }.toTypedArray())
            }
        }
    }
}

/** Fixed test-only native observation. No raw handle, callback authority, arbitrary mutation,
 * suspended business spy, or production hook escapes. Read faults are armed only in the test
 * coroutine's inherited cohort; background invalidation cannot consume them. */
private class CompleteProofNativeBoundary : SupportSQLiteOpenHelper.Factory {
    val cohort = ThreadLocal<Boolean>()
    val domainRows = AtomicInteger()
    val cancelSignals = AtomicInteger()
    val completionWrites = AtomicInteger()
    private val cancelJob = AtomicReference<Job?>()
    private val loseEnd = AtomicBoolean()
    private val endThread = ThreadLocal<SupportSQLiteDatabase?>()
    private val successful = AtomicInteger()
    private val lostEnds = AtomicInteger()
    private val originalEndFailure = AtomicReference<IllegalStateException?>()

    fun cancelOnDomainRow(job: Job) { check(cancelJob.compareAndSet(null, job) && !loseEnd.get()) }
    fun loseReadEndOutcome() { check(cancelJob.get() == null && loseEnd.compareAndSet(false, true)) }
    fun requireLostNativeEnd(error: Throwable) {
        assertEquals(1, domainRows.get()); assertTrue(successful.get() > 0); assertEquals(1, lostEnds.get())
        val original = requireNotNull(originalEndFailure.get())
        assertTrue(generateSequence(error) { it.cause }.any { it === original })
        assertEquals("COMMAND_COMPLETE_PROOF_TEST_END_OUTCOME_LOST", original.message)
        assertFalse(loseEnd.get())
    }
    private fun query(raw: SupportSQLiteDatabase, sql: String, run: () -> Cursor): Cursor {
        val c = run()
        if (cohort.get() != true || sql != DOMAIN_ROW) return c
        return object : Cursor by c {
            private var selected = false
            override fun moveToNext(): Boolean {
                val moved = c.moveToNext()
                if (moved && !selected) {
                    selected = true
                    check(raw.inTransaction())
                    domainRows.incrementAndGet()
                    cancelJob.getAndSet(null)?.let { job ->
                        check(job.isActive); cancelSignals.incrementAndGet()
                        job.cancel(CancellationException("COMMAND_COMPLETE_PROOF_TEST_CANCELLED_AT_NATIVE_ROW"))
                    }
                    if (loseEnd.compareAndSet(true, false)) {
                        check(endThread.get() == null); endThread.set(raw)
                    }
                }
                return moved
            }
        }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(configuration.context.packageName == "com.dayforge.testbed")
        var native: SupportSQLiteDatabase? = null
        var wrapped: SupportSQLiteDatabase? = null
        val monitor = Any()
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
            if (native !== raw) {
                native = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun query(query: String): Cursor = this@CompleteProofNativeBoundary.query(raw, query) { raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = this@CompleteProofNativeBoundary.query(raw, query) { raw.query(query, bindArgs) }
                    override fun query(query: SupportSQLiteQuery): Cursor = this@CompleteProofNativeBoundary.query(raw, query.sql) { raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                        this@CompleteProofNativeBoundary.query(raw, query.sql) { raw.query(query, cancellationSignal) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val selected = sql.trimStart().let { it.startsWith("INSERT OR ABORT INTO `completions`") || it.startsWith("INSERT OR REPLACE INTO `completions`") }
                        if (!selected) return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { statement.execute(); completionWrites.incrementAndGet() }
                            override fun executeInsert(): Long = statement.executeInsert().also { check(it > 0); completionWrites.incrementAndGet() }
                            override fun executeUpdateDelete(): Int = statement.executeUpdateDelete().also { check(it > 0); completionWrites.incrementAndGet() }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (endThread.get() === raw) successful.incrementAndGet()
                    }
                    override fun endTransaction() {
                        if (endThread.get() !== raw) { raw.endTransaction(); return }
                        check(raw.inTransaction())
                        raw.endTransaction()
                        check(!raw.inTransaction())
                        endThread.remove()
                        check(successful.get() > 0)
                        check(lostEnds.incrementAndGet() == 1)
                        val failure = IllegalStateException("COMMAND_COMPLETE_PROOF_TEST_END_OUTCOME_LOST")
                        check(originalEndFailure.compareAndSet(null, failure))
                        throw failure
                    }
                }
            }
            requireNotNull(wrapped)
        }
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(wrap(db))
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase get() = wrap(helper.writableDatabase)
            override val readableDatabase get() = wrap(helper.readableDatabase)
        }
    }
    private companion object {
        const val DOMAIN_ROW = "SELECT rowid AS __recovery_rowid,* FROM main.`count_command_intents` WHERE rowid<=? ORDER BY rowid"
    }
}
