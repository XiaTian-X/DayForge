package com.dayforge.data.repository

import android.database.Cursor
import android.os.SystemClock
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.*
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.dayforge.data.repository.CountTrustNodeCollector.Report
import com.dayforge.data.repository.CountTrustNodeCollector.Status

/** Bounded dedicated command-node maintenance. Every historical catalog member, Intent,
 * Status and Outcome remains live; only unreachable COW node bytes are candidates. No
 * command execution/delivery, root re-signing or optional COUNT authorization occurs.
 */
@Singleton
internal class CountCommandNodeCollector @Inject constructor(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    private enum class Phase { CATALOGUE, MARK, SWEEP }
    private class Changed : IllegalStateException("COMMAND_GC_ANCHOR_CHANGED")
    private class BusyReader(val owner: CountTrustRuntime, val epoch: Long) : IllegalStateException("COMMAND_GC_READER_PRESENT")
    private class UnavailableOwner : IllegalStateException("COMMAND_GC_OWNER_UNAVAILABLE")
    private class Cycle(val anchor: CountCommandGcRoots.Anchor, val lower: Long?) {
        val candidates = arrayListOf<CountCommandNodeGcStore.Candidate>()
        var frozen: List<CountCommandNodeGcStore.Candidate>? = null
        val marked = BitSet()
        var after = lower
        var exhausted = false
        var phase = Phase.CATALOGUE
        val traversal = CountCommandGcTraversal(anchor.header.catalog)
        var sweep = 0
        fun freeze() {
            frozen = java.util.Collections.unmodifiableList(candidates.toList())
            check(candidates.zipWithNext().all { (left, right) -> left.rowid < right.rowid } &&
                candidates.map { it.digest }.distinct().size == candidates.size)
            phase = Phase.MARK
        }
        fun mark(actual: CountCommandNodeGcStore.Candidate) {
            val index = requireNotNull(frozen).binarySearchBy(actual.rowid) { it.rowid }
            if (index >= 0) {
                check(requireNotNull(frozen)[index] == actual) { "COMMAND_GC_CATALOGUE_CHANGED" }
                marked.set(index)
            } else {
                val inRange = (lower == null || actual.rowid > lower) && after?.let { actual.rowid <= it } == true
                check(!inRange) { "COMMAND_GC_CATALOGUE_MISSING" }
            }
        }
    }
    private val running = AtomicBoolean(false)
    private var cycle: Cycle? = null
    private var scanAnchor: CountCommandGcRoots.Anchor? = null
    private var resumeAfter: Long? = null
    private var cohorts = 0L
    private var restarts = 0L
    private val inventory = CountCommandGcRoots(database, tokens, sessions)

    val requested: Boolean get() = CountTrustRuntime.forDatabase(database)?.commandNodeCollectionRequested == true

    /** One actual outer transaction, followed by a bounded frozen batch of original JSON parses.
     * All progress is private until END; cancellation/any failure abandons the whole cycle.
     */
    suspend fun collectSlice(): Report {
        currentCoroutineContext().ensureActive()
        if (!running.compareAndSet(false, true)) return Report(Status.BUSY, "ADMISSION")
        try {
            val owner = CountTrustRuntime.forDatabase(database)
                ?: return Report(Status.DEFERRED, "ADMISSION", reason = "NO_CONTROLLED_OWNER")
            if (!owner.commandNodeCollectionRequested) { discard(); return Report(Status.DEFERRED, "ADMISSION", reason = "NO_OPEN_WORK") }
            // Pure expected schema construction/close is outside the account and source TX.
            val reference = ReplicaBusinessReferenceSchema.read(owner.context)
            var held = 0L
            val attempt = sessions.tryMaintenance {
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    when (owner.nodeCollectionAdmission(database)) {
                        CountTrustRuntime.NodeGcAdmission.BUSY -> throw BusyReader(owner, owner.generation)
                        CountTrustRuntime.NodeGcAdmission.UNAVAILABLE -> throw UnavailableOwner()
                        CountTrustRuntime.NodeGcAdmission.READY -> Unit
                    }
                    val access = tokens.localCoreWriteAccess()
                        ?: throw CountCommandGcRoots.Deferred("COMMAND_GC_NO_AUTHORITY")
                    val completed = owner.primaryTransaction(database) {
                        owner.maintenance(database) {
                            val permit = when (val attempt = owner.captureNodeGcPermit(database)) {
                                CountTrustRuntime.NodeGcPermitAttempt.Busy -> throw BusyReader(owner, owner.generation)
                                CountTrustRuntime.NodeGcPermitAttempt.Unavailable -> throw UnavailableOwner()
                                is CountTrustRuntime.NodeGcPermitAttempt.Granted -> attempt.token
                            }
                            val commandBoundary = owner.captureCommandBoundary()
                            val businessBoundary = owner.capturePrimaryBusinessBoundary()
                            val anchor = inventory.capture(owner, access, reference, cycle?.anchor ?: scanAnchor)
                            val current = cycle ?: run {
                                if (scanAnchor != null && scanAnchor != anchor) { resumeAfter = null; restarts++ }
                                Cycle(anchor, resumeAfter).also { cycle = it; cohorts++ }
                            }
                            if (current.anchor != anchor) throw Changed()
                            val store = CountCommandNodeGcStore(database, owner, permit)
                            var deleted = 0; var bytes = 0L; var scanned = 0
                            val executedPhase = current.phase.name
                            val nodesBefore = current.traversal.loadedNodes; val bytesBefore = current.traversal.loadedBytes
                            when (current.phase) {
                                Phase.CATALOGUE -> {
                                    val remaining = COHORT_ROWS - current.candidates.size
                                    val rows = store.catalogue(current.after, minOf(CATALOGUE_ROWS, remaining))
                                    current.candidates.addAll(rows); scanned = rows.size
                                    rows.lastOrNull()?.let { current.after = it.rowid }
                                    if (rows.size < minOf(CATALOGUE_ROWS, remaining)) current.exhausted = true
                                    if (current.candidates.size == COHORT_ROWS)
                                        current.exhausted = !store.hasAfter(requireNotNull(current.after))
                                    if (current.exhausted || current.candidates.size == COHORT_ROWS) current.freeze()
                                }
                                Phase.MARK -> {
                                    current.traversal.step(store, current::mark)
                                    if (current.traversal.complete) current.phase = Phase.SWEEP
                                }
                                Phase.SWEEP -> {
                                    val candidates = requireNotNull(current.frozen)
                                    check(current.traversal.complete) { "COMMAND_GC_MARK_INCOMPLETE" }
                                    var examined = 0
                                    while (current.sweep < candidates.size && examined < SWEEP_ROWS) {
                                        currentCoroutineContext().ensureActive()
                                        val index = current.sweep
                                        val candidate = candidates[index]
                                        if (!current.marked[index]) {
                                            if (bytes + candidate.length > DELETE_BYTES) break
                                            store.delete(candidate); deleted++; bytes += candidate.length
                                        }
                                        current.sweep++; examined++
                                    }
                                }
                            }
                            check(owner.primaryBusinessChanges(businessBoundary).isEmpty()) { "COMMAND_GC_BUSINESS_WRITE" }
                            owner.verifyCommandBoundary(commandBoundary, store.expectedDeletes())
                            owner.requireNodeGcPermit(database, permit)
                            check(inventory.capture(owner, access, reference, anchor) == anchor) { "COMMAND_GC_ANCHOR_CHANGED" }
                            owner.sealCommandOperation()
                            check(tokens.localCoreWriteAccess() == access) { "COMMAND_GC_AUTHORITY_CHANGED" }
                            owner.requireNodeGcPermit(database, permit)
                            currentCoroutineContext().ensureActive()
                            Report(Status.PROGRESS, current.phase.name, current.candidates.size,
                                current.traversal.loadedNodes - nodesBefore, current.traversal.loadedBytes - bytesBefore, deleted, bytes,
                                cohort = cohorts, restarts = restarts, scannedRows = scanned, executedPhase = executedPhase)
                        }
                    }
                    completed.end.requireCandidate(completed.value)
                    currentCoroutineContext().ensureActive()
                    completed.value
                } finally { held = SystemClock.elapsedRealtimeNanos() - started }
            }
            if (attempt is AccountSessionCoordinator.MaintenanceAttempt.Busy) {
                currentCoroutineContext().ensureActive()
                return Report(Status.BUSY, "ACCOUNT")
            }
            var report = (attempt as AccountSessionCoordinator.MaintenanceAttempt.Completed<Report>).value.copy(accountAndEndNanos = held)
            val current = requireNotNull(cycle)
            // No SQL/account lock, root pointer or read token crosses into JSON work.
            if (current.traversal.needsValidation) {
                check(!database.inTransaction()) { "COMMAND_GC_PARSE_IN_TRANSACTION" }
                val began = SystemClock.elapsedRealtimeNanos()
                current.traversal.validateFrozenValue()
                report = report.copy(parseNanos = SystemClock.elapsedRealtimeNanos() - began)
            }
            if (current.phase == Phase.SWEEP && current.sweep == current.candidates.size) {
                scanAnchor = current.anchor
                resumeAfter = current.after
                cycle = null
                if (current.exhausted) {
                    owner.commandNodeCollectionFinished(current.anchor.allocationEpoch)
                    scanAnchor = null; resumeAfter = null
                    report = report.copy(status = Status.FINISHED)
                }
            }
            currentCoroutineContext().ensureActive()
            return report
        } catch (cancelled: CancellationException) {
            discard(); throw cancelled
        } catch (_: Changed) {
            discard(); currentCoroutineContext().ensureActive(); restarts++; return Report(Status.RESTARTED, "ANCHOR", restarts = restarts)
        } catch (busy: BusyReader) {
            // Both explicit BUSY classifications precede workset mutation. Unknown owner/END
            // or cancelled cleanup still discards, even if Room kept BUSY as primary exception.
            val keep = try {
                currentCoroutineContext().ensureActive()
                busy.owner.generation == busy.epoch &&
                    busy.owner.nodeCollectionAdmission(database) != CountTrustRuntime.NodeGcAdmission.UNAVAILABLE
            } catch (cancelled: CancellationException) { discard(); throw cancelled }
            catch (_: Exception) { discard(); currentCoroutineContext().ensureActive()
                return Report(Status.FAILED, "ADMISSION", reason = "ADMISSION_RECHECK_FAILED") }
            if (!keep) { discard(); return Report(Status.DEFERRED, "ADMISSION", reason = "OWNER_OR_CONNECTION_UNAVAILABLE") }
            return Report(Status.DEFERRED, "ADMISSION", reason = "READER_OR_OWNER_PRESENT")
        } catch (_: UnavailableOwner) {
            discard(); currentCoroutineContext().ensureActive()
            return Report(Status.DEFERRED, "ADMISSION", reason = "OWNER_OR_CONNECTION_UNAVAILABLE")
        } catch (deferred: CountCommandGcRoots.Deferred) {
            discard(); currentCoroutineContext().ensureActive(); return Report(Status.DEFERRED, "LAYOUT_OR_AUTHORITY", reason = deferred.reason)
        } catch (_: OutOfMemoryError) {
            discard(); currentCoroutineContext().ensureActive(); return Report(Status.DEFERRED, "MEMORY", reason = "WORKSPACE_UNAVAILABLE")
        } catch (failure: Exception) {
            discard(); currentCoroutineContext().ensureActive()
            if (failure.message == "COUNT_GC_NOT_QUIESCENT")
                return Report(Status.DEFERRED, "ADMISSION", reason = "READER_OR_OWNER_PRESENT")
            if (failure.message == "COUNT_GC_OWNER_UNAVAILABLE")
                return Report(Status.DEFERRED, "ADMISSION", reason = "OWNER_OR_CONNECTION_UNAVAILABLE")
            return Report(Status.FAILED, "ABANDONED", restarts = restarts, reason = failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,96}")) } ?: failure.javaClass.simpleName)
        } finally { running.set(false) }
    }
    private fun discard() { cycle = null; scanAnchor = null; resumeAfter = null }
    private companion object {
        const val COHORT_ROWS = 16_384
        const val CATALOGUE_ROWS = 64
        const val SWEEP_ROWS = 32
        const val DELETE_BYTES = 512L * 1024
    }
}



private class CountCommandNodeGcStore(private val database: HabitDatabase, private val owner: CountTrustRuntime,
    private val permit: Any) {
    data class Candidate(val rowid: Long, val digest: String, val length: Int)
    private val sql get() = database.openHelper.writableDatabase
    private val deleted = arrayListOf<CountCommandJournal.Expected>()
    fun expectedDeletes(): List<CountCommandJournal.Expected> = deleted.toList()
    private fun live() = owner.requireNodeGcPermit(database, permit)
    fun catalogue(after: Long?, limit: Int): List<Candidate> {
        require(limit in 1..128); live()
        val where = if (after == null) "" else "WHERE rowid>?"
        val args = if (after == null) emptyArray<Any>() else arrayOf<Any>(after)
        val result = sql.query("SELECT rowid,CASE WHEN typeof(digest)='text' AND length(digest)=64 THEN digest ELSE NULL END," +
            "typeof(body),length(body) FROM main.count_command_nodes $where ORDER BY rowid LIMIT $limit", args).use { c -> buildList<Candidate> {
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    c.getString(2) == "blob" && c.getType(3) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(3) in 1..HistoryBlockCodec.MAX_NODE_BYTES.toLong()) { "COMMAND_GC_NODE_METADATA" }
                val row = Candidate(c.getLong(0), c.getString(1), c.getInt(3))
                check(CountTrustIndex.validHash(row.digest) && (lastOrNull()?.rowid ?: after)?.let { row.rowid > it } != false) {
                    "COMMAND_GC_CATALOGUE_ORDER"
                }
                add(row)
            }
        } }
        live(); return result
    }
    fun hasAfter(rowid: Long): Boolean {
        live()
        val result = sql.query("SELECT 1 FROM main.count_command_nodes WHERE rowid>? LIMIT 1", arrayOf<Any>(rowid)).use { it.moveToFirst() }
        live(); return result
    }
    fun node(digest: String, limit: Int): Pair<Candidate, ByteArray> {
        require(CountTrustIndex.validHash(digest)); live()
        val metadata = sql.query("SELECT rowid,typeof(digest),typeof(body),length(body) FROM main.count_command_nodes WHERE digest=?",
            arrayOf<Any>(digest)).use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getString(1) == "text" &&
                c.getString(2) == "blob" && c.getType(3) == Cursor.FIELD_TYPE_INTEGER && c.getLong(3) in 1..limit.toLong()) {
                "COMMAND_GC_NODE_MISSING_OR_OVERSIZE"
            }
            Candidate(c.getLong(0), digest, c.getInt(3)).also { check(!c.moveToNext()) }
        }
        return metadata to bytes(metadata)
    }
    fun bytes(candidate: Candidate): ByteArray {
        live()
        // Recheck this exact identity before materializing, including during sweep.
        sql.query("SELECT typeof(body),length(body) FROM main.count_command_nodes WHERE rowid=? AND digest=?",
            arrayOf<Any>(candidate.rowid, candidate.digest)).use {
            check(it.moveToFirst() && it.getString(0) == "blob" && it.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                it.getLong(1) == candidate.length.toLong() && !it.moveToNext()) { "COMMAND_GC_CANDIDATE_CHANGED" }
        }
        val body = sql.query("SELECT body FROM main.count_command_nodes WHERE rowid=? AND digest=?",
            arrayOf<Any>(candidate.rowid, candidate.digest)).use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_BLOB)
            requireNotNull(it.getBlob(0)).also { _ -> check(!it.moveToNext()) }
        }
        check(body.size == candidate.length && CountTrustIndex.hash(body) == candidate.digest) { "COMMAND_GC_NODE_CHANGED" }
        live(); return body
    }
    fun delete(candidate: Candidate) {
        bytes(candidate) // Damaged garbage is not silently repaired by deleting it.
        live()
        sql.compileStatement("DELETE FROM main.count_command_nodes WHERE rowid=? AND digest=?").use {
            it.bindLong(1, candidate.rowid); it.bindString(2, candidate.digest)
            check(it.executeUpdateDelete() == 1) { "COMMAND_GC_DELETE_COUNT" }
        }
        sql.query("SELECT 1 FROM main.count_command_nodes WHERE digest=? LIMIT 1", arrayOf(candidate.digest)).use {
            check(!it.moveToFirst()) { "COMMAND_GC_DELETE_REINSERTED" }
        }
        deleted += CountCommandJournal.Expected("count_command_nodes", "DELETE", candidate.digest)
        live()
    }
}


/** One authenticated current root owns every retained command catalog member. A pending
 * discovery cursor is not a second persisted live root: Store requires exact current root
 * identity on every continuation, and no primary SQL token survives actual END.
 */
private class CountCommandGcRoots(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    class Deferred(val reason: String) : IllegalStateException(reason)
    data class Control(val storage: String, val generation: Long, val dirty: Long, val commandRootHash: String?)
    data class Anchor(val owner: CountTrustRuntime, val epoch: Long, val schemaVersion: Long,
        val dataVersion: Long, val schemaHash: String, val control: Control,
        val header: CountCommandRootCodec.Header, val access: LocalCoreWriteAccess,
        val recovery: ReplicaPresentationWitness, val installation: String?, val allocationEpoch: Long)

    suspend fun capture(owner: CountTrustRuntime, access: LocalCoreWriteAccess,
        reference: ReplicaReferenceSchema, previous: Anchor? = null): Anchor {
        check(database.inTransaction()); owner.requireUsable()
        val sql = database.openHelper.writableDatabase
        if (sql.version != CountCommandV20Schema.VERSION || reference.version != CountCommandV20Schema.VERSION)
            throw Deferred("COMMAND_GC_UNSUPPORTED_LAYOUT")
        val expected = CountTrustSchema.BUSINESS_TABLES + CountCommandV20Schema.currentLocalTables
        val optional = ApprovedBusinessSchema.optionalStatistics
        val names = sql.query("SELECT CASE WHEN length(CAST(name AS BLOB))<=256 THEN name END FROM main.sqlite_master " +
            "WHERE type='table' ORDER BY name LIMIT ${expected.size + 6}").use { c -> buildSet {
            while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) }
        } }
        if (names - optional != expected + setOf("room_master_table", "android_metadata", "sqlite_sequence"))
            throw Deferred("COMMAND_GC_UNKNOWN_ROOT_PROVIDER")
        sql.query("SELECT 1 FROM temp.sqlite_master WHERE lower(name) IN (${expected.joinToString(",") { "?" }}) LIMIT 1",
            expected.toTypedArray()).use { check(!it.moveToFirst()) { "COMMAND_GC_SHADOWED_MAIN" } }
        sql.query("PRAGMA database_list").use { c -> while (c.moveToNext()) {
            check(c.getString(1) in setOf("main", "temp")) { "COMMAND_GC_ATTACHED_DATABASE" }
        } }
        CountTrustSchema.validate(sql)
        val control = CountCommandRootAnchor.read(sql).let { Control(it.storage, it.generation, it.dirty, it.hash) }
        val recovery = tokens.replicaPresentationWitness()
        if (ReplicaPresentationRepository(database, tokens, sessions).inspectInTransactionWhileAccountLocked().problem != null ||
            owner.recoveryPreviewSuppressed(control.storage)) throw Deferred("COMMAND_GC_RECOVERY_PENDING")
        check(recovery == tokens.replicaPresentationWitness()) { "COMMAND_GC_RECOVERY_CHANGED" }
        fun scalar(query: String) = sql.query(query).use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            c.getLong(0).also { check(!c.moveToNext()) }
        }
        val dataVersion = scalar("PRAGMA main.data_version")
        val schemaVersion = scalar("PRAGMA main.schema_version")
        val schema = if (previous?.owner === owner && previous.epoch == owner.generation &&
            previous.dataVersion == dataVersion && previous.schemaVersion == schemaVersion) previous.schemaHash else {
            val actual = ReplicaSqlSchema.read(sql)
            if (!ApprovedBusinessSchema.matches(actual, reference.objects)) throw Deferred("COMMAND_GC_UNSUPPORTED_SCHEMA")
            val rows = owner.schemaSnapshot(); CountCommandV20Schema.previousSchema(rows)
            CountCommandPrimarySchema.hash(rows)
        }
        val token = owner.capturePrimaryReadToken(database)
        val caller = currentCoroutineContext()
        val header = CountCommandGcOwnership.read(sql, control.storage, schema, access) {
            caller.ensureActive(); owner.requirePrimaryReadToken(database, token); owner.requireUsable()
        }.header
        val installation = tokens.registeredInstallationId()
        header.registration?.let { check(it.installationId == installation) { "COMMAND_GC_INSTALLATION_CHANGED" } }
        if (header.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED) throw Deferred("COMMAND_GC_LEGACY_ENROLLMENT_PENDING")
        return Anchor(owner, owner.generation, schemaVersion, dataVersion, schema, control, header,
            access.copy(capabilities = access.capabilities?.toSet()), recovery, installation, owner.commandNodeAllocationEpoch)
    }
}

/** No chunk or alternate-pool references exist in this exact catalog format. Every branch
 * and leaf is authenticated against its parent's exact ref. All inline members are decoded
 * after that slice's actual END before MARK can finish; damaged garbage is also retained.
 */
private class CountCommandGcTraversal(root: HistoryBlockCodec.Ref?) {
    private val expectedCount = root?.count ?: 0L
    private val frontier = ArrayDeque<HistoryBlockCodec.Ref>().apply { root?.let(::addLast) }
    private val pending = ArrayDeque<HistoryBlockCodec.Record>()
    private var records = 0L
    var complete = false; private set
    var loadedNodes = 0L; private set
    var loadedBytes = 0L; private set
    val needsValidation: Boolean get() = pending.isNotEmpty()

    suspend fun step(store: CountCommandNodeGcStore, mark: (CountCommandNodeGcStore.Candidate) -> Unit) {
        // This exact format has no external value chunks. At most one256KiB node and
        //128inline records are frozen per actual transaction, then decoded outside it.
        check(pending.isEmpty()) { "COMMAND_GC_MEMBER_VALIDATION_PENDING" }
        val caller = currentCoroutineContext()
        if (frontier.isEmpty()) {
            check(records == expectedCount) { "COMMAND_GC_ROOT_COUNT" }; complete = true; return
        }
        val expected = frontier.removeLast()
        val (candidate, bytes) = store.node(expected.digest, HistoryBlockCodec.MAX_NODE_BYTES)
        val node = HistoryBlockCodec.decode(expected, bytes) { caller.ensureActive() }
        mark(candidate); loadedNodes = Math.addExact(loadedNodes, 1L); loadedBytes = Math.addExact(loadedBytes, bytes.size.toLong())
        when (node) {
            is HistoryBlockCodec.Branch -> node.children.asReversed().forEach(frontier::addLast)
            is HistoryBlockCodec.Leaf -> node.records.forEach(pending::addLast)
        }
        if (frontier.isEmpty() && pending.isEmpty()) {
            check(records == expectedCount) { "COMMAND_GC_ROOT_COUNT" }; complete = true
        }
    }

    suspend fun validateFrozenValue() {
        val caller = currentCoroutineContext()
        while (pending.isNotEmpty()) {
            caller.ensureActive()
            val record = pending.first()
            val member = CountCommandRecords.CatalogMember.decode(record.payload.toByteArray()) { caller.ensureActive() }
            check(record.key.recordedBusinessDate == java.time.LocalDate.of(1970, 1, 1) &&
                record.key.stableFactId == member.commandId && record.sourceId == member.commandId) { "COMMAND_GC_MEMBER_CONTEXT" }
            pending.removeFirst(); records = Math.addExact(records, 1L)
        }
        if (frontier.isEmpty()) { check(records == expectedCount) { "COMMAND_GC_ROOT_COUNT" }; complete = true }
    }
}
