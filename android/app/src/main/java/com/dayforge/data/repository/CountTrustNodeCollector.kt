package com.dayforge.data.repository

import android.database.Cursor
import android.os.SystemClock
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.CountTrustValueCodec
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
import kotlinx.serialization.json.*

/** Optional bounded COUNT-only maintenance. This never signs a root, publishes business
 * results, clears command evidence or calls the recovery-preview cache disposal path.
 */
@Singleton
internal class CountTrustNodeCollector @Inject constructor(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    enum class Status { BUSY, DEFERRED, PROGRESS, RESTARTED, FINISHED, FAILED }
    data class Report(val status: Status, val phase: String, val catalogueRows: Int = 0,
        val walkedNodes: Long = 0, val graphBytes: Long = 0, val deletedNodes: Int = 0,
        val deletedBytes: Long = 0, val accountAndEndNanos: Long = 0, val parseNanos: Long = 0,
        val cohort: Long = 0, val restarts: Long = 0, val reason: String? = null,
        val scannedRows: Int = 0, val executedPhase: String? = null)
    private enum class Phase { CATALOGUE, MARK, SWEEP }
    private class Changed : IllegalStateException("COUNT_GC_ANCHOR_CHANGED")
    private class BusyReader(val owner: CountTrustRuntime, val epoch: Long) : IllegalStateException("COUNT_GC_READER_PRESENT")
    private class UnavailableOwner : IllegalStateException("COUNT_GC_OWNER_UNAVAILABLE")
    private class Cycle(val anchor: CountTrustGcRoots.Anchor, val lower: Long?) {
        val candidates = arrayListOf<CountTrustNodeGcStore.Candidate>()
        var frozen: List<CountTrustNodeGcStore.Candidate>? = null
        val marked = BitSet()
        var after = lower
        var exhausted = false
        var phase = Phase.CATALOGUE
        val traversal = CountTrustGcTraversal(anchor.header?.root)
        var sweep = 0
        fun freeze() {
            frozen = java.util.Collections.unmodifiableList(candidates.toList())
            check(candidates.zipWithNext().all { (left, right) -> left.rowid < right.rowid } &&
                candidates.map { it.digest }.distinct().size == candidates.size)
            phase = Phase.MARK
        }
        fun mark(actual: CountTrustNodeGcStore.Candidate) {
            val index = requireNotNull(frozen).binarySearchBy(actual.rowid) { it.rowid }
            if (index >= 0) {
                check(requireNotNull(frozen)[index] == actual) { "COUNT_GC_CATALOGUE_CHANGED" }
                marked.set(index)
            } else {
                val inRange = (lower == null || actual.rowid > lower) && after?.let { actual.rowid <= it } == true
                check(!inRange) { "COUNT_GC_CATALOGUE_MISSING" }
            }
        }
    }
    private val running = AtomicBoolean(false)
    private var cycle: Cycle? = null
    private var scanAnchor: CountTrustGcRoots.Anchor? = null
    private var resumeAfter: Long? = null
    private var cohorts = 0L
    private var restarts = 0L
    private val inventory = CountTrustGcRoots(database, tokens, sessions)

    val requested: Boolean get() = CountTrustRuntime.forDatabase(database)?.nodeCollectionRequested == true

    /** One actual outer transaction, followed by a bounded frozen batch of original JSON parses.
     * All progress is private until END; cancellation/any failure abandons the whole cycle.
     */
    suspend fun collectSlice(): Report {
        currentCoroutineContext().ensureActive()
        if (!running.compareAndSet(false, true)) return Report(Status.BUSY, "ADMISSION")
        try {
            val owner = CountTrustRuntime.forDatabase(database)
                ?: return Report(Status.DEFERRED, "ADMISSION", reason = "NO_CONTROLLED_OWNER")
            if (!owner.nodeCollectionRequested) { discard(); return Report(Status.DEFERRED, "ADMISSION", reason = "NO_OPEN_WORK") }
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
                        ?: throw CountTrustGcRoots.Deferred("COUNT_GC_NO_AUTHORITY")
                    val completed = owner.transaction(database) {
                        owner.maintenance(database) {
                            val permit = when (val attempt = owner.captureNodeGcPermit(database)) {
                                CountTrustRuntime.NodeGcPermitAttempt.Busy -> throw BusyReader(owner, owner.generation)
                                CountTrustRuntime.NodeGcPermitAttempt.Unavailable -> throw UnavailableOwner()
                                is CountTrustRuntime.NodeGcPermitAttempt.Granted -> attempt.token
                            }
                            val anchor = inventory.capture(owner, access, reference, cycle?.anchor ?: scanAnchor)
                            val current = cycle ?: run {
                                if (scanAnchor != null && scanAnchor != anchor) { resumeAfter = null; restarts++ }
                                Cycle(anchor, resumeAfter).also { cycle = it; cohorts++ }
                            }
                            if (current.anchor != anchor) throw Changed()
                            val store = CountTrustNodeGcStore(database, owner, permit)
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
                                    current.traversal.step(store, current::mark, 1, 16)
                                    if (current.traversal.complete) current.phase = Phase.SWEEP
                                }
                                Phase.SWEEP -> {
                                    val candidates = requireNotNull(current.frozen)
                                    check(current.traversal.complete) { "COUNT_GC_MARK_INCOMPLETE" }
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
                            owner.requireNodeGcPermit(database, permit)
                            check(inventory.capture(owner, access, reference, anchor) == anchor) { "COUNT_GC_ANCHOR_CHANGED" }
                            check(tokens.localCoreWriteAccess() == access) { "COUNT_GC_AUTHORITY_CHANGED" }
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
                check(!database.inTransaction()) { "COUNT_GC_PARSE_IN_TRANSACTION" }
                val began = SystemClock.elapsedRealtimeNanos()
                current.traversal.validateFrozenValue()
                report = report.copy(parseNanos = SystemClock.elapsedRealtimeNanos() - began)
            }
            if (current.phase == Phase.SWEEP && current.sweep == current.candidates.size) {
                scanAnchor = current.anchor
                resumeAfter = current.after
                cycle = null
                if (current.exhausted) {
                    owner.nodeCollectionFinished(current.anchor.allocationEpoch)
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
        } catch (deferred: CountTrustGcRoots.Deferred) {
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


/** Closed physical-pool inventory. v19 has exactly one persisted COUNT root provider.
 * v20's authenticated command layout has its own BLOB pool and no COUNT-node references.
 * Unknown layouts never mean an empty pool; this collector never sweeps command nodes.
 */
private class CountTrustGcRoots(private val database: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator) {
    class Deferred(val reason: String) : IllegalStateException(reason)
    data class Control(val storage: String, val generation: Long, val dirty: Long)
    data class Anchor(val owner: CountTrustRuntime, val epoch: Long, val version: Int,
        val dataVersion: Long, val schemaVersion: Long, val schemaHash: String,
        val control: Control, val header: CountTrustSeal.Header?, val access: LocalCoreWriteAccess,
        val recovery: ReplicaPresentationWitness, val allocationEpoch: Long,
        val previousSchemaHash: String?, val primarySchemaHash: String?, val commandOwnership: CountCommandGcOwnership.Anchor?)

    suspend fun capture(owner: CountTrustRuntime, access: LocalCoreWriteAccess,
        reference: ReplicaReferenceSchema, previous: Anchor? = null): Anchor {
        val sql = database.openHelper.writableDatabase
        check(database.inTransaction()); owner.requireUsable()
        val version = sql.version
        if (version !in setOf(19, CountCommandV20Schema.VERSION)) throw Deferred("COUNT_GC_UNSUPPORTED_LAYOUT")
        val localTables = if (version == 19) CountTrustSchema.LOCAL_TABLES else CountCommandV20Schema.currentLocalTables
        val expectedTables = CountTrustSchema.BUSINESS_TABLES + localTables +
            setOf("android_metadata", "room_master_table", "sqlite_sequence")
        val optional = setOf("sqlite_stat1", "sqlite_stat4")
        val tables = sql.query("SELECT CASE WHEN length(name)<=128 THEN name ELSE NULL END FROM main.sqlite_master " +
            "WHERE type='table' ORDER BY name LIMIT ${expectedTables.size + optional.size + 1}").use { c -> buildSet {
            while (c.moveToNext()) { check(c.getType(0) == Cursor.FIELD_TYPE_STRING); add(c.getString(0)) }
        } }
        if (tables - optional != expectedTables) throw Deferred("COUNT_GC_UNKNOWN_ROOT_PROVIDER")
        val names = (CountTrustSchema.BUSINESS_TABLES + localTables).toList()
        sql.query("SELECT 1 FROM temp.sqlite_master WHERE lower(name) IN (${names.joinToString(",") { "?" }}) LIMIT 1",
            names.toTypedArray()).use { check(!it.moveToFirst()) { "COUNT_GC_SHADOWED_MAIN" } }
        sql.query("PRAGMA database_list").use { c -> while (c.moveToNext()) {
            check(c.getString(1) in setOf("main", "temp")) { "COUNT_GC_ATTACHED_DATABASE" }
        } }
        CountTrustSchema.validate(sql)
        val recovery = tokens.replicaPresentationWitness()
        if (ReplicaPresentationRepository(database, tokens, sessions).inspectInTransactionWhileAccountLocked().problem != null)
            throw Deferred("COUNT_GC_RECOVERY_PENDING")
        check(recovery == tokens.replicaPresentationWitness()) { "COUNT_GC_RECOVERY_CHANGED" }
        val control = sql.query("SELECT storageId,generation,dirty FROM main.count_trust_control WHERE id=1").use {
            check(it.moveToFirst())
            Control(it.getString(0), it.getLong(1), it.getLong(2)).also { _ -> check(!it.moveToNext()) }
        }
        if (owner.recoveryPreviewSuppressed(control.storage)) throw Deferred("COUNT_GC_RECOVERY_PENDING")
        // Preflight precedes any potentially oversized header value / MAC materialization.
        sql.query("SELECT (SELECT count(*) FROM (SELECT id FROM main.count_trust_root LIMIT 2))," +
            "(SELECT count(*) FROM (SELECT * FROM main.count_trust_root LIMIT 2) WHERE typeof(binding)='text' AND length(binding)=64 " +
            "AND typeof(schemaHash)='text' AND length(schemaHash)=64 AND typeof(rootHash)='text' AND length(rootHash)=64 " +
            "AND typeof(validator)='text' AND length(validator)<=160 AND typeof(mac)='blob' AND length(mac)=32)").use {
            check(it.moveToFirst()); check(it.getLong(0) in 0..1 && it.getLong(0) == it.getLong(1)) { "COUNT_GC_HEADER_INVALID" }
        }
        val header = CountTrustSeal.read(sql)
        fun scalar(statement: String) = sql.query(statement).use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER); it.getLong(0)
        }
        val dataVersion = scalar("PRAGMA main.data_version")
        val schemaVersion = scalar("PRAGMA main.schema_version")
        fun schemaHash(rows: List<List<String?>>) = CountTrustIndex.hash(JsonArray(rows.map { row ->
            JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }).toString().toByteArray(Charsets.UTF_8))
        val schema = if (previous?.owner === owner && previous.epoch == owner.generation && previous.version == version &&
            previous.schemaVersion == schemaVersion && previous.dataVersion == dataVersion) {
            Triple(previous.schemaHash, previous.previousSchemaHash, previous.primarySchemaHash)
        } else {
            val actual = ReplicaSqlSchema.read(sql)
            if (reference.version != version || !ApprovedBusinessSchema.matches(actual, reference.objects))
                throw Deferred("COUNT_GC_UNSUPPORTED_SCHEMA")
            val rows = owner.schemaSnapshot()
            Triple(schemaHash(rows), if (version == CountCommandV20Schema.VERSION)
                schemaHash(CountCommandV20Schema.previousSchema(rows)) else null,
                if (version == CountCommandV20Schema.VERSION) com.dayforge.data.local.CountCommandPrimarySchema.hash(rows) else null)
        }
        val hash = schema.first
        val command = if (version == CountCommandV20Schema.VERSION) {
            val token = owner.capturePrimaryReadToken(database)
            val caller = currentCoroutineContext()
            CountCommandGcOwnership.read(sql, control.storage, requireNotNull(schema.third), access) {
                caller.ensureActive(); owner.requirePrimaryReadToken(database, token); owner.requireUsable()
            }
        } else null
        command?.header?.registration?.let {
            check(tokens.registeredInstallationId() == it.installationId) { "COUNT_GC_COMMAND_INSTALLATION_CHANGED" }
        }
        if (header != null) {
            check(header.generation <= control.generation && (control.dirty == 1L || header.generation == control.generation)) {
                "COUNT_GC_GENERATION_INVALID"
            }
            if (header.schemaHash != hash && command != null) {
                val primary = command.header
                com.dayforge.data.local.CountCommandPrimarySchema.requireStatisticsPreflight(sql)
                val currentRows = owner.schemaSnapshot()
                check(schemaHash(currentRows) == hash) { "COUNT_GC_SCHEMA_CHANGED" }
                val statisticsChanged = control.dirty == 1L &&
                    (header.schemaHash in com.dayforge.data.local.CountCommandPrimarySchema.completeSchemaVariants(currentRows) ||
                    primary.source == CountCommandRootCodec.Source.MIGRATION_19_20 && header.schemaHash != schema.second &&
                    header.schemaHash in com.dayforge.data.local.CountCommandPrimarySchema.completeSchemaVariants(CountCommandV20Schema.previousSchema(currentRows)))
                val exactMigration = header.schemaHash == schema.second && primary.source == CountCommandRootCodec.Source.MIGRATION_19_20 &&
                    primary.mode in setOf(CountCommandRootCodec.Mode.EMPTY, CountCommandRootCodec.Mode.LEGACY_UNENROLLED) &&
                    primary.revision == 1L
                check(exactMigration || statisticsChanged) { "COUNT_GC_SEAL_INVALID" }
                // Same permitted initial-registration namespaces as the one-time schema
                // bridge. This only classifies an authenticated envelope as pending: no
                // node traversal, signing, business grant or deletion occurs here.
                val bindings = listOf(access,
                    access.copy(session = access.session.copy(serverInstanceId = null, syncEpoch = null)),
                    access.copy(session = access.session.copy(serverInstanceId = null, syncEpoch = null), capturedDeviceId = null))
                    .map { CountTrustSeal.binding(it, control.storage, header.validator) }.toSet()
                check(header.binding in bindings && CountTrustSeal().matches(header, header.binding, header.schemaHash)) {
                    "COUNT_GC_SEAL_INVALID"
                }
                throw Deferred(if (statisticsChanged && !exactMigration) "COUNT_GC_DERIVED_STATISTICS_REBUILD_REQUIRED" else "COUNT_GC_COMMAND_SCHEMA_BRIDGE_PENDING")
            }
            val binding = CountTrustSeal.binding(access, control.storage, header.validator)
            check(header.binding == binding && header.schemaHash == hash && CountTrustSeal().matches(header, binding, hash)) {
                "COUNT_GC_SEAL_INVALID"
            }
        }
        if (command?.header?.mode == CountCommandRootCodec.Mode.LEGACY_UNENROLLED)
            throw Deferred("COUNT_GC_COMMAND_LEGACY_ENROLLMENT_PENDING")
        // A dirty authenticated old root remains a LIVE storage root, never a business grant.
        return Anchor(owner, owner.generation, version, dataVersion, schemaVersion, hash, control, header,
            access.copy(capabilities = access.capabilities?.toSet()), recovery, owner.nodeAllocationEpoch,
            schema.second, schema.third, command?.anchor)
    }
}


/** Fixed operations only. Caller owns one real maintenance transaction and its private permit.
 * Every BLOB has an independent scalar preflight; no TEMP table or persistent mark can grant a delete.
 */
private class CountTrustNodeGcStore(private val database: HabitDatabase, private val owner: CountTrustRuntime,
    private val permit: Any) {
    data class Candidate(val rowid: Long, val digest: String, val length: Int)
    private val sql get() = database.openHelper.writableDatabase
    private fun live() = owner.requireNodeGcPermit(database, permit)
    fun catalogue(after: Long?, limit: Int): List<Candidate> {
        require(limit in 1..128); live()
        val where = if (after == null) "" else "WHERE rowid>?"
        val args = if (after == null) emptyArray<Any>() else arrayOf<Any>(after)
        val result = sql.query("SELECT rowid,CASE WHEN typeof(digest)='text' AND length(digest)=64 THEN digest ELSE NULL END," +
            "typeof(body),length(body) FROM main.count_trust_nodes $where ORDER BY rowid LIMIT $limit", args).use { c -> buildList<Candidate> {
            while (c.moveToNext()) {
                check(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getType(1) == Cursor.FIELD_TYPE_STRING &&
                    c.getString(2) == "blob" && c.getType(3) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(3) in 1..HistoryBlockCodec.MAX_NODE_BYTES.toLong()) { "COUNT_GC_NODE_METADATA" }
                val row = Candidate(c.getLong(0), c.getString(1), c.getInt(3))
                check(CountTrustIndex.validHash(row.digest) && (lastOrNull()?.rowid ?: after)?.let { row.rowid > it } != false) {
                    "COUNT_GC_CATALOGUE_ORDER"
                }
                add(row)
            }
        } }
        live(); return result
    }
    fun hasAfter(rowid: Long): Boolean {
        live()
        val result = sql.query("SELECT 1 FROM main.count_trust_nodes WHERE rowid>? LIMIT 1", arrayOf<Any>(rowid)).use { it.moveToFirst() }
        live(); return result
    }
    fun node(digest: String, limit: Int): Pair<Candidate, ByteArray> {
        require(CountTrustIndex.validHash(digest)); live()
        val metadata = sql.query("SELECT rowid,typeof(digest),typeof(body),length(body) FROM main.count_trust_nodes WHERE digest=?",
            arrayOf<Any>(digest)).use { c ->
            check(c.moveToFirst() && c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getString(1) == "text" &&
                c.getString(2) == "blob" && c.getType(3) == Cursor.FIELD_TYPE_INTEGER && c.getLong(3) in 1..limit.toLong()) {
                "COUNT_GC_NODE_MISSING_OR_OVERSIZE"
            }
            Candidate(c.getLong(0), digest, c.getInt(3)).also { check(!c.moveToNext()) }
        }
        return metadata to bytes(metadata)
    }
    fun bytes(candidate: Candidate): ByteArray {
        live()
        // Recheck this exact identity before materializing, including during sweep.
        sql.query("SELECT typeof(body),length(body) FROM main.count_trust_nodes WHERE rowid=? AND digest=?",
            arrayOf<Any>(candidate.rowid, candidate.digest)).use {
            check(it.moveToFirst() && it.getString(0) == "blob" && it.getType(1) == Cursor.FIELD_TYPE_INTEGER &&
                it.getLong(1) == candidate.length.toLong() && !it.moveToNext()) { "COUNT_GC_CANDIDATE_CHANGED" }
        }
        val body = sql.query("SELECT body FROM main.count_trust_nodes WHERE rowid=? AND digest=?",
            arrayOf<Any>(candidate.rowid, candidate.digest)).use {
            check(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_BLOB)
            requireNotNull(it.getBlob(0)).also { _ -> check(!it.moveToNext()) }
        }
        check(body.size == candidate.length && CountTrustIndex.hash(body) == candidate.digest) { "COUNT_GC_NODE_CHANGED" }
        live(); return body
    }
    fun delete(candidate: Candidate) {
        bytes(candidate) // Damaged garbage is not silently repaired by deleting it.
        live()
        sql.compileStatement("DELETE FROM main.count_trust_nodes WHERE rowid=? AND digest=?").use {
            it.bindLong(1, candidate.rowid); it.bindString(2, candidate.digest)
            check(it.executeUpdateDelete() == 1) { "COUNT_GC_DELETE_COUNT" }
        }
        live()
    }
}


/** One privately owned incremental walk. No caller-supplied complete bit or trusted JSON node.
 * Mutable work is tentative until its owning real transaction END; any failure discards the cycle.
 */
private class CountTrustGcTraversal(root: HistoryBlockCodec.Ref?) {
    private val expectedCount = root?.count ?: 0L
    private val frontier = ArrayDeque<HistoryBlockCodec.Ref>().apply { root?.let(::addLast) }
    private var leaf: List<HistoryBlockCodec.Record>? = null
    private var recordIndex = 0
    private var pointer: CountTrustValueCodec.Pointer? = null
    private var bytes: ByteArray? = null
    private var chunkIndex = 0
    private val frozenForValidation = ArrayDeque<ByteArray>()
    private var frozenBytes = 0L
    private var records = 0L
    var complete = false; private set
    var loadedNodes = 0L; private set
    var loadedBytes = 0L; private set
    val needsValidation: Boolean get() = frozenForValidation.isNotEmpty()

    suspend fun step(store: CountTrustNodeGcStore, mark: (CountTrustNodeGcStore.Candidate) -> Unit,
        maxNodes: Int, maxChunks: Int) {
        require(maxNodes in 1..4 && maxChunks in 1..16)
        var nodes = 0; var chunks = 0; var sliceBytes = 0L
        val caller = currentCoroutineContext()
        while (!complete) {
            caller.ensureActive()
            if (frozenBytes >= 512L * 1024) return
            val value = pointer
            if (value != null) {
                if (chunks == maxChunks || sliceBytes + value.chunkLength(chunkIndex) > 512L * 1024) return
                val (candidate, chunk) = store.node(value.chunks[chunkIndex], CountTrustIndex.CHUNK_BYTES)
                check(chunk.size == value.chunkLength(chunkIndex)) { "COUNT_GC_CHUNK_LENGTH" }
                mark(candidate); chunk.copyInto(requireNotNull(bytes), chunkIndex * CountTrustIndex.CHUNK_BYTES)
                chunkIndex++; chunks++; loadedNodes++; loadedBytes += chunk.size; sliceBytes += chunk.size
                if (chunkIndex == value.chunks.size) {
                    val frozen = requireNotNull(bytes)
                    frozenForValidation.addLast(frozen); frozenBytes += frozen.size
                    bytes = null; pointer = null; recordIndex++
                }
                continue
            }
            val currentLeaf = leaf
            if (currentLeaf != null) {
                if (recordIndex < currentLeaf.size) {
                    val plan = CountTrustValueCodec.pointer(currentLeaf[recordIndex])
                    if (frozenForValidation.isNotEmpty() && frozenBytes + plan.length > 512L * 1024) return
                    pointer = plan; bytes = ByteArray(plan.length); chunkIndex = 0
                    continue
                }
                leaf = null
            }
            if (frontier.isEmpty()) {
                if (frozenForValidation.isNotEmpty()) return
                check(records == expectedCount) { "COUNT_GC_ROOT_COUNT" }
                complete = true; return
            }
            if (nodes == maxNodes || sliceBytes + HistoryBlockCodec.MAX_NODE_BYTES > 512L * 1024) return
            val expected = frontier.removeLast()
            val (candidate, nodeBytes) = store.node(expected.digest, HistoryBlockCodec.MAX_NODE_BYTES)
            val node = HistoryBlockCodec.decode(expected, nodeBytes) { caller.ensureActive() }
            mark(candidate); nodes++; loadedNodes++; loadedBytes += nodeBytes.size; sliceBytes += nodeBytes.size
            when (node) {
                is HistoryBlockCodec.Branch -> node.children.asReversed().forEach(frontier::addLast)
                is HistoryBlockCodec.Leaf -> { leaf = node.records; recordIndex = 0 }
            }
        }
    }

    /** Called only after actual END, outside account/Room resources. The original full JSON
     * parser runs on owned frozen bytes. Cancellation cannot publish a partially checked value.
     */
    suspend fun validateFrozenValue() {
        while (frozenForValidation.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val value = frozenForValidation.first()
            CountTrustValueCodec.value(value)
            currentCoroutineContext().ensureActive()
            frozenForValidation.removeFirst(); frozenBytes -= value.size
            records = Math.addExact(records, 1)
        }
    }
}
