package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.domain.service.AccountSessionCoordinator
import java.util.UUID
import kotlinx.serialization.json.encodeToStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Narrow isolated plain-profile metric definition candidate. Private database and certificate;
 * preparation is outside the account/SQL scopes, final is revalidate + exact DML + fresh auth.
 * This component is not installed into any production repository or a generic write callback.
 */
internal class G0ShortMetricStore(
    private val db: HabitDatabase, private val tokens: TokenManager,
    private val sessions: AccountSessionCoordinator, private val authority: G0AuthorityOwner,
    private val gate: G0DomainSqlGate, private val metricUuid: String,
    private val queueClock: () -> Long = { System.currentTimeMillis() / 1000 * 1000 },
    private val shareColdEvidence: Boolean = true,
    private val blockColdEvidence: Boolean = shareColdEvidence,
    private val refineColdProof: Boolean = true,
    private val memoryPreparation: Boolean = true,
    private val innerColdProof: Boolean = blockColdEvidence && refineColdProof,
    private val refineColdDependencies: Boolean = true,
    private val refineColdDecoding: Boolean = true,
    private val cacheCodecRules: Boolean = true,
    private val measureCodecStages: Boolean = true,
    private val reuseColdRows: Boolean = true,
    private val batchColdNegatives: Boolean = true,
    private val reuseColdPureValues: Boolean = true,
    private val reuseColdEnvelopeText: Boolean = true,
    private val prepareColdInputs: Boolean = false,
    private val checkpoint: G0PersistentCheckpoint? = null,
    private val reuseCheckpointRows: Boolean = true,
    private val replacementIdentity: () -> String = { UUID.randomUUID().toString() },
    private val admitRecurringReads: Boolean = false,
    private val retainRecurringResults: Boolean = false
) {
    init { require(!admitRecurringReads || checkpoint==null) { "G0_MIXED_CHECKPOINT_NOT_SUPPORTED" } }
    init { require(!retainRecurringResults || admitRecurringReads && blockColdEvidence && shareColdEvidence) }
    private var recurringSeed:G0ColdRecurringProof.Seed?=null
    internal fun recurringReadSeed():G0ColdRecurringProof.Seed? { current();return recurringSeed }
    internal var coldCaptureForTest:()->G0ObservedReadProof.Capture={G0ObservedReadProof.Capture()}
    internal var coldReadCaptureFailure:String?=null;private set
    private var foreignRequests: Set<String> = emptySet()
    private lateinit var index: G0DomainProofIndex
    private lateinit var scope: LocalSyncAccess
    private lateinit var metric: MetricEntity
    private var shadow: SyncEntityStateEntity? = null
    private val names = mutableMapOf<String, MutableSet<String>>()
    private var queueWatermark = 0L
    private var epoch = 0L
    private var ready = false
    private var poison = false
    private val pending = linkedMapOf<Long, SyncOutboxEntity>()
    private val origins = linkedMapOf<String, NextRequestOriginEntity>()
    private val transmissions = linkedMapOf<String, NextTransmissionEntity>()
    private val transmissionHashes = linkedMapOf<String, String>()
    private val receipts = linkedMapOf<String, NextAcceptanceEntity>()
    private val receiptHashes = linkedMapOf<String, String>()
    private val supersessions = linkedMapOf<String, NextStructuralSupersessionEntity>()
    private val replacementOriginals = linkedMapOf<String, String>()
    private val originHashes = linkedMapOf<String, String>()
    private val dependencies = linkedMapOf<String, NextStructuralDependencyEntity>()
    private val dependencyHashes = linkedMapOf<String, String>()
    var beforeFinalForTest: suspend () -> Unit = {}
    var afterPrepareForTest: suspend () -> Unit = {}

    private var checkpointSchema: List<List<String?>> = emptyList()
    var afterCheckpointMatchForTest: suspend () -> Unit = {}
    var afterColdPreparationForTest: suspend () -> Unit = {}
    suspend fun initialize(access: LocalSyncAccess, expectedSchema: List<List<String?>>) {
        val frozen=access.copy(capabilities=access.capabilities.toSet())
        val schema=expectedSchema.map { it.toList() }
        val pure=if(prepareColdInputs) {
            check(!db.inTransaction() && currentCoroutineContext()[NextEvidenceReadSession]==null)
            val inputs=account {
                check(!ready && !poison)
                val lease=authority.lease()
                require(tokens.isCurrentSyncAuthority(frozen))
                val captured=coldStage("cold_pure_capture") {
                    db.withTransaction { NextEvidenceReadSession.read(db) { G0ColdPureInputs.capture(db) } }
                }
                authority.checkLease(lease)
                require(tokens.isCurrentSyncAuthority(frozen))
                captured
            }
            val prepared=coldStage("cold_pure_prepare") { withContext(Dispatchers.Default) { G0ColdPureInputs.build(inputs,frozen.deviceId) } }
            afterColdPreparationForTest()
            currentCoroutineContext().ensureActive()
            prepared
        } else null
        initializeOwned(frozen,schema,pure)
    }

    private suspend fun initializeOwned(access: LocalSyncAccess, expectedSchema: List<List<String?>>, predecoded: G0ColdPureInputs?) = account {
        check(!ready && !poison)
        coldStage("cold_entry_auth") { require(access.deviceId != null && tokens.isCurrentSyncAuthority(access)) }
        val lease = authority.lease()
        coldStage("cold_permit") { authority.finalPermit(lease) }.use {
            coldStage("cold_open") { db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers=ON") }
            try {
                val trace = G0PhaseTrace.current.get()
                val queued = trace?.now()
                var bodyEnded: Long? = null
                var checkpointDigest: ByteArray? = null
                var pendingRecurring:G0ColdRecurringProof?=null
                val checkpointContext=checkpoint?.context(access,metricUuid)
                coldStage("cold_transaction") { db.withTransaction {
                    queued?.let { trace.record("cold_room_wait",it,true) }
                    val sql = db.openHelper.writableDatabase
                    checkpointDigest=checkpoint?.let { coldStage("checkpoint_capture") { it.capture(sql,expectedSchema) } }
                    val checkpointHit=checkpoint?.let { coldStage("checkpoint_verify") {
                        it.matches(requireNotNull(checkpointDigest),requireNotNull(checkpointContext)) } } ?: false
                    afterCheckpointMatchForTest()
                    val recurring=if(retainRecurringResults) G0ColdRecurringProof.capture(db,tokens,sessions,coldCaptureForTest()).also {
                        coldReadCaptureFailure=it.failure
                    } else null
                    pendingRecurring=recurring
                    suspend fun prepareHistory(): List<String> {
                        NextChallengeStore(db).requirePlainInTransaction()
                        NextRecoveryAuditStore(db).validatedMembers(access)
                        val snapshot = G0MetricBlocks(db, metricUuid).capture()
                        // Imports, timer requests and rejection recovery remain outside this metric candidate.
                        for (table in listOf("next_config_imports", "timer_command_outbox", "next_rejections"))
                            sql.query("SELECT count(*) FROM $table").use { check(it.moveToFirst() && it.getLong(0) == 0L) { "G0_SHORT_UNSUPPORTED_SCOPE" } }
                        NextRequestSql.sources(sql, "sync_outbox"); NextRequestSql.sources(sql, "timer_command_outbox")
                        metric = requireNotNull(snapshot.metrics.singleOrNull { it.uuid == metricUuid })
                        snapshot.metrics.forEach { row ->
                            NextStructureMapper.writeMetric(row)
                            names.getOrPut(row.name) { linkedSetOf() }.add(row.uuid)
                        }
                        require(snapshot.conflicts.none { it.recordType == "metric" && (it.localEntityUuid == metricUuid || it.wireEntityUuid == metricUuid) }) { "G0_SHORT_CONFLICT_REQUIRES_STRICT" }
                        shadow = snapshot.shadow
                        shadow?.let { require(it.revision > 0 && !it.deleted && it.payloadHash == syncPayloadHash(requireNotNull(it.payloadJson))) }
                        foreignRequests=if(admitRecurringReads) G0RecurringReadAdmission(db,tokens,sessions).verify(access,snapshot,metricUuid,recurring) else emptySet()
                        snapshot.pending.filterNot { it.operationId in foreignRequests }.forEach { require(it.recordType == "metric" && it.action == "upsert" && it.entityUuid == metricUuid) { "G0_SHORT_UNSUPPORTED_SCOPE" }; pending[it.id] = it }
                        val ids = sql.query("SELECT kind,requestId FROM next_request_origins").use { c -> buildList {
                            while (c.moveToNext()) { require(c.getString(0) == NEXT_OPERATION) { "G0_SHORT_UNSUPPORTED_SCOPE" }; add(c.getString(1)) }
                        } }
                        sql.query("SELECT originalId FROM next_structural_supersessions").use { c ->
                            while (c.moveToNext()) {
                                val row = requireNotNull(db.nextStructuralCausalDao().supersession(c.getString(0)))
                                require(supersessions.put(row.originalId, row) == null)
                                require(replacementOriginals.put(row.replacementId, row.originalId) == null)
                            }
                        }
                        return ids.filterNot { it in foreignRequests }
                    }
                    var coldMemo = NextStructuralCausalStore.PureMemo()
                    var causal = NextStructuralCausalStore(db,coldMemo)
                    var blockRows: G0ColdEvidenceRows? = null
                    var metricProof = G0ColdMetricProof(db,metricUuid,refineColdDependencies,refineColdDecoding,cacheCodecRules,measureCodecStages,reusePureValues=reuseColdPureValues,reuseEnvelopeText=reuseColdEnvelopeText,predecoded=predecoded)
                    var checkpointDecoder=G0ColdStrictDecoder(cacheCodecRules,measureCodecStages,reuseColdPureValues)
                    val originals = NextOriginalRequestProofStore(db)
                    suspend fun captureOrigin(id: String) {
                        val origin = requireNotNull(if(blockRows==null) db.nextRequestDao().origin(NEXT_OPERATION, id) else blockRows!!.origin(id))
                        val receipt = if(blockRows==null) db.nextRequestDao().acceptance(NEXT_OPERATION, id) else blockRows!!.receipt(id)
                        val accepted = receipt != null
                        var exact: G0ColdMetricProof.Result? = null
                        val retired = supersessions[id]
                        suspend fun requireScope(operation: SyncV2Operation) = coldStage("cold_origin_scope") {
                            require((exact?.strictPlain == true || roundOperationIntent(origin.intentJson) == null) && operation.entityType == "metric" && operation.action == "upsert" && operation.entityUuid == metricUuid) { "G0_SHORT_UNSUPPORTED_SCOPE" }
                        }
                        if (!refineColdProof) coldStage("cold_origin_scope_decode") { requireScope(decodeNextOperationIntent(origin.intentJson)) }
                        val provenOperation = if(checkpointHit && accepted) coldStage("checkpoint_reuse") {
                            if("structure.write" !in access.capabilities) rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
                            if(reuseCheckpointRows) {
                                require(!origin.intentJson.startsWith("\uFEFF"))
                                checkpointDecoder.decode(origin.intentJson.toByteArray(Charsets.UTF_8),SyncV2Operation.serializer())
                            } else decodeNextOperationIntent(origin.intentJson)
                        } else coldStage("cold_origin_proof") {
                            if (!shareColdEvidence || !accepted) require(causal.resolve(id, access) == (retired?.replacementId ?: id))
                            if (accepted) {
                                if (refineColdProof) {
                                    // Recovery's complete proof keeps resolve + receipt in ONE read
                                    // scope. Preserve the original writer permission rejection.
                                    if ("structure.write" !in access.capabilities)
                                        rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
                                    if (innerColdProof) metricProof.verify(id,access,origin,requireNotNull(receipt),causal,coldMemo).also { exact = it }.operation
                                    else causal.recoveryAcceptedOperationProof(id, access).first
                                } else causal.acceptedOperationProof(id, access).first
                            } else {
                                if (retired == null) originals.liveOrigin(access, NEXT_OPERATION, id)
                                null
                            }
                        }
                        if (refineColdProof) coldStage("cold_origin_scope_decode") { requireScope(provenOperation ?: decodeNextOperationIntent(origin.intentJson)) }
                        coldStage("cold_origin_capture") {
                            val certifiedRows=if(checkpointHit && accepted && reuseCheckpointRows) blockRows else null
                            val capturedTransmission=exact?.transmission ?: if(certifiedRows!=null) {
                                coldStage("checkpoint_typed_row_reuse") { certifiedRows.transmission(id) }
                            } else db.nextRequestDao().transmission(NEXT_OPERATION,id)
                            capturedTransmission?.let { row ->
                                // acceptedOperationProof already performs the complete journal binding
                                // on this exact immutable transaction snapshot, including restart/owner.
                                if (!shareColdEvidence || !accepted) originals.journal(origin, row, access)
                                transmissions[id] = row.copy(wireBytes = row.wireBytes.copyOf())
                                transmissionHashes[id] = exact?.transmissionHash ?: requireNotNull(NextRequestSql.rowHash(db, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                            }
                            val capturedReceipt=exact?.receipt ?: if(certifiedRows!=null) {
                                coldStage("checkpoint_typed_row_reuse") { certifiedRows.receipt(id) }
                            } else db.nextRequestDao().acceptance(NEXT_OPERATION,id)
                            capturedReceipt?.let { row ->
                                require(pending[origin.queueId] == null)
                                receipts[id] = row
                                receiptHashes[id] = exact?.receiptHash ?: requireNotNull(NextRequestSql.rowHash(db, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                            }
                            origins[id] = origin
                            originHashes[id] = exact?.originHash ?: requireNotNull(NextRequestSql.rowHash(db, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                            val dep = exact?.dependency ?: db.nextStructuralCausalDao().dependency(id)
                            if (dep != null) {
                                require(id !in replacementOriginals)
                                dependencies[id] = dep
                                dependencyHashes[id] = requireNotNull(NextRequestSql.rowHash(db, "next_structural_dependencies", "operationId=?", arrayOf(id)))
                            } else require(id in replacementOriginals) { "G0_SHORT_MISSING_DEPENDENCY" }
                        }
                    }
                    suspend fun finishHistory() {
                        for (table in listOf("next_transmissions", "next_acceptances")) sql.query("SELECT kind,requestId FROM $table").use { c ->
                            while(c.moveToNext()) require(c.getString(0) == NEXT_OPERATION && c.getString(1) in origins) { "G0_SHORT_ORPHAN_HISTORY" }
                        }
                        require(supersessions.values.all { it.originalId in origins && it.replacementId in origins })
                        pending.values.forEach { require(origins[it.operationId]?.queueId == it.id) { "G0_SHORT_ORPHAN_SOURCE" } }
                        queueWatermark = NextRequestSql.watermark(sql, "sync_outbox")
                        index = coldStage("cold_index") { G0DomainProofIndex.captureVerifiedBaseline(sql) }
                    }
                    coldStage("cold_validation") {
                        if (blockColdEvidence) {
                            check(shareColdEvidence)
                            val ids = coldStage("cold_prepare_history") { NextEvidenceReadSession.read(db) { prepareHistory() } }
                            G0ColdEvidenceBlocks(db).read(ids,
                                beforeBlock = { blockRows=null; checkpointDecoder=G0ColdStrictDecoder(cacheCodecRules,measureCodecStages,reuseColdPureValues); if (refineColdProof) { coldMemo = NextStructuralCausalStore.PureMemo(); causal = NextStructuralCausalStore(db,coldMemo); metricProof = G0ColdMetricProof(db,metricUuid,refineColdDependencies,refineColdDecoding,cacheCodecRules,measureCodecStages,reusePureValues=reuseColdPureValues,reuseEnvelopeText=reuseColdEnvelopeText,predecoded=predecoded) } },
                                reuseRows=reuseColdRows,
                                onRows={ rows -> blockRows=rows;metricProof=G0ColdMetricProof(db,metricUuid,refineColdDependencies,refineColdDecoding,cacheCodecRules,measureCodecStages,rows,reusePureValues=reuseColdPureValues,reuseEnvelopeText=reuseColdEnvelopeText,predecoded=predecoded) },
                                batchNegatives=batchColdNegatives && !checkpointHit,
                                onNegatives={ negative -> metricProof=G0ColdMetricProof(db,metricUuid,refineColdDependencies,refineColdDecoding,cacheCodecRules,measureCodecStages,blockRows,negative,reusePureValues=reuseColdPureValues,reuseEnvelopeText=reuseColdEnvelopeText,predecoded=predecoded) },
                                validate = ::captureOrigin)
                            blockRows=null
                            coldStage("cold_finish_history") { NextEvidenceReadSession.read(db) { finishHistory() } }
                        } else {
                            suspend fun capture() { prepareHistory().forEach { captureOrigin(it) }; finishHistory() }
                            if (shareColdEvidence) NextEvidenceReadSession.read(db) { capture() } else capture()
                        }
                    }
                    if(checkpoint!=null) coldStage("checkpoint_recapture") {
                        val verified=checkpoint.capture(sql,expectedSchema)
                        check(java.security.MessageDigest.isEqual(requireNotNull(checkpointDigest),verified)) {
                            "G0_CHECKPOINT_SNAPSHOT_CHANGED"
                        }
                    }
                    recurring?.requireUnchangedWithinCold()
                    coldStage("cold_journal") { gate.installJournal(expectedSchema) }
                    coldStage("cold_final_auth") { require(tokens.isCurrentSyncAuthority(access)) }
                    bodyEnded = trace?.now()
                }
                    bodyEnded?.let { trace?.record("cold_tx_finish",it,true) }
                }
                coldStage("cold_publish") {
                    val initialStamp=gate.seal(); epoch = gate.revision
                    recurringSeed=pendingRecurring?.seal(authority,gate,lease,initialStamp)
                    authority.checkLease(lease)
                    scope = access.copy(capabilities = access.capabilities.toSet()); ready = true
                    checkpointSchema=expectedSchema.map { it.toList() }
                }
                if(checkpoint!=null && !checkpoint.hit) coldStage("checkpoint_publish") {
                    checkpoint.publish(requireNotNull(checkpointDigest),requireNotNull(checkpointContext))
                }
            } catch (failure: Throwable) { poison = true; throw failure }
        }
    }
    /** Optional post-commit maintenance. A lagging/missing sidecar merely forces full cold proof.
     * File authentication/persistence does not hold account or SQL resources. */
    suspend fun refreshCheckpoint(): Boolean {
        val target=requireNotNull(checkpoint)
        val captured=account {
            current();val access=scope;val lease=authority.lease()
            require(tokens.isCurrentSyncAuthority(access))
            authority.finalPermit(lease).use {
                val digest=gate.transaction(db,G0DomainSqlGate.Phase.READ) {
                    current()
                    val value=coldStage("checkpoint_capture") { target.capture(db.openHelper.writableDatabase,checkpointSchema) }
                    coldStage("checkpoint_final_auth") { require(tokens.isCurrentSyncAuthority(access)) }
                    value
                }
                authority.checkLease(lease)
                digest to target.context(access,metricUuid)
            }
        }
        currentCoroutineContext().ensureActive()
        return coldStage("checkpoint_publish") { target.publish(captured.first,captured.second) }
    }
    private suspend fun <T> coldStage(name: String, block: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        return if (trace == null) block() else trace.stage(name,block)
    }
    private fun current() {
        check(ready && !poison) { "G0_SHORT_RECOVERY_REQUIRED" }
        gate.requireSealedConnection()
        check(epoch == gate.revision) { "G0_SHORT_UNOBSERVED_COMMIT" }
    }
    private class PreparationChanged : IllegalStateException()
    class PreparationBusy : IllegalStateException("G0_SHORT_PREPARATION_BUSY")
    private suspend fun <T> retryPreparation(block: suspend () -> T): T {
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try { return block() } catch (_: PreparationChanged) {
                val trace = G0PhaseTrace.current.get()
                trace?.call("preparation_stale") { Unit }
                if (++attempts == 3) throw PreparationBusy()
                trace?.call("preparation_retry") { Unit }
            }
        }
    }
    /** Account owner only, BEFORE entering SQL. A known local dependency advancement is
     * retryable; invalid leases/connections, SQL failures and unknown COMMITs are never retried.
     * The normal transaction still checks connection continuity and the same witnesses. */
    private fun requirePreparation(lease: G0AuthorityOwner.Lease, vararg witnesses: G0DomainProofIndex.Witness) {
        current(); authority.checkLease(lease)
        if (witnesses.any { !index.isCurrent(it) }) throw PreparationChanged()
    }
    /** Speculative immutable input only: NEVER return it as a verified read. The caller holds
     * the account lock. Final SQL must still check connection/data_version, journal, dependency
     * versions and fresh authority before any accepted outcome, including no-op/replayed writes.
     */
    private suspend fun <T> preparationSnapshot(block: suspend () -> T): T =
        if (memoryPreparation) block() else gate.transaction(db, G0DomainSqlGate.Phase.READ, block = block)

    private data class EditSnapshot(val metric: MetricEntity, val shadow: SyncEntityStateEntity?, val witness: G0DomainProofIndex.Witness)
    suspend fun edit(name: String, description: String, operationId: String = UUID.randomUUID().toString(), at: Long = System.currentTimeMillis()): SyncOutboxEntity? =
        retryPreparation { editPrepared(name,description,operationId,at) }
    private suspend fun editPrepared(name: String, description: String, operationId: String, at: Long): SyncOutboxEntity? {
        current()
        val lease = authority.lease()
        val access = requireNotNull(tokens.localCoreWriteAccess())
        require(access.session == scope.session && access.capturedDeviceId == scope.deviceId && access.capabilities?.contains("structure.write") == true)
        val before = account {
            preparationSnapshot {
                current(); authority.checkLease(lease)
                require(names[name].orEmpty().all { it == metricUuid })
                EditSnapshot(metric, shadow, index.witness(setOf(
                    G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("metrics", cells(listOf(metric.id)))),
                    G0DomainProofIndex.Dependency.Range("metrics", "name", cell(name)),
                    G0DomainProofIndex.Dependency.Range("sync_outbox", "entityUuid", cell(metricUuid)),
                    G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("sync_entity_state", cells(listOf("metric", metricUuid)))))))
            }
        }
        val modified = name != before.metric.name || description != before.metric.description
        val next = before.metric.copy(name = name, description = description, updatedAt = at)
        val encoded = coldStage("warm_edit_compute") { withContext(Dispatchers.Default) {
            val operation = SyncV2Operation(operationId, "metric", metricUuid, "upsert", before.shadow?.revision, NextStructureMapper.writeMetric(next))
            validateNextSyncOperation(operation)
            val bytes = encodeSyncRequest(SyncV2Operation.serializer(), operation)
            decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))
            decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(requireNotNull(scope.deviceId), listOf(operation))), NextSyncPushRequest.serializer())
            bytes.toString(Charsets.UTF_8)
        } }
        afterPrepareForTest()
        return account(AccountSessionCoordinator.Work.NEXT_WRITE) {
            requirePreparation(lease,before.witness)
            authority.finalPermit(lease).use {
                lateinit var advancement: G0DomainProofIndex.Advancement
                lateinit var queue: SyncOutboxEntity
                lateinit var origin: NextRequestOriginEntity
                lateinit var dep: NextStructuralDependencyEntity
                var originHash = ""; var depHash = ""
                try {
                    val result = gate.transaction(db, G0DomainSqlGate.Phase.BUSINESS, committed = { changes ->
                        index.committed(advancement, changes); epoch = gate.revision
                        names[metric.name]?.remove(metricUuid); names.getOrPut(next.name) { linkedSetOf() }.add(metricUuid)
                        metric = next
                        if (modified) {
                            queueWatermark = queue.id; pending[queue.id] = queue
                            origins[operationId] = origin; originHashes[operationId] = originHash
                            dependencies[operationId] = dep; dependencyHashes[operationId] = depHash
                        }
                    }) {
                        val expected = mutableListOf<G0DomainJournal.Change>()
                        coldStage("warm_edit_apply") {
                            current(); index.requireCurrent(before.witness); authority.checkLease(lease)
                            require(operationId !in origins && operationId !in foreignRequests && pending.values.none { it.operationId == operationId }) { "G0_SHORT_REQUEST_REUSED" }
                            queue = SyncOutboxEntity(id = Math.addExact(queueWatermark, 1), operationId = operationId,
                                recordType = "metric", entityUuid = metricUuid, wireEntityUuid = metricUuid, action = "upsert", createdAt = queueClock())
                            val sourceHash = NextRequestSql.sourceHash(queue)
                            origin = NextRequestOriginEntity(NEXT_OPERATION, operationId, queue.id, 5, scope.session.authentication.userId,
                                scope.session.serverInstanceId, scope.session.syncEpoch, sourceHash, encoded)
                            val parent = pending.values.maxByOrNull { logicalDependency(it.operationId).logicalOrder }?.let { logicalDependency(it.operationId) }
                            val sql = db.openHelper.writableDatabase
                            fun change(table: String, op: String, old: List<Any?>?, new: List<Any?>?) { expected += G0DomainJournal.Change(0, table, op, old?.let(::cells), new?.let(::cells)) }
                            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); change("sync_control", "UPDATE", listOf(1L, 0L), listOf(1L, 1L))
                            sql.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>(name, description, at, metric.id))
                            change("metrics", "UPDATE", metricValues(metric), metricValues(next))
                            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1"); change("sync_control", "UPDATE", listOf(1L, 1L), listOf(1L, 0L))
                            if (modified) {
                            insert("sync_outbox", queueValues(queue)); change("sync_outbox", "INSERT", null, queueValues(queue))
                            insert("next_request_origins", originValues(origin)); change("next_request_origins", "INSERT", null, originValues(origin))
                            originHash = requireNotNull(NextRequestSql.rowHash(sql, "next_request_origins", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, operationId)))
                            dep = NextStructuralDependencyEntity(operationId, queue.id, originHash, parent?.operationId, parent?.originHash,
                                parent?.let { dependencyHashes.getValue(it.operationId) }, scope.deviceId)
                            insert("next_structural_dependencies", dependencyValues(dep)); change("next_structural_dependencies", "INSERT", null, dependencyValues(dep))
                            depHash = requireNotNull(NextRequestSql.rowHash(sql, "next_structural_dependencies", "operationId=?", arrayOf(operationId)))
                            }
                        }
                        beforeFinalForTest()
                        gate.certifyChanges { actual ->
                            check(actual.map { it.copy(sequence = 0) } == expected) { "G0_SHORT_UNEXPECTED_EFFECT" }
                            advancement = index.prepare(actual)
                        }
                        gate.finalRead { require(tokens.localCoreWriteAccess() == access) { "G0_SHORT_FINAL_AUTH" } }
                        if (modified) queue else null
                    }
                    authority.checkLease(lease)
                    result
                } catch (failure: Throwable) { poison = true; index.invalidate(); throw failure }
            }
        }
    }
    private data class RequestSnapshot(val origin: NextRequestOriginEntity, val transmission: NextTransmissionEntity?,
        val transmissionHash: String?, val receipt: NextAcceptanceEntity?, val queue: SyncOutboxEntity?,
        val metric: MetricEntity, val shadow: SyncEntityStateEntity?, val input: G0MetricMergePlan.Input,
        val witness: G0DomainProofIndex.Witness, val dependency: NextStructuralDependencyEntity, val originHash: String, val isHead: Boolean)
    private suspend fun authorize(access: LocalSyncAccess) {
        require(access.session == scope.session && access.deviceId == scope.deviceId && tokens.isCurrentSyncAuthority(access)) { "G0_SHORT_STALE_ACCESS" }
        require("structure.write" in access.capabilities)
    }
    private fun logicalDependency(id: String) = dependencies.getValue(replacementOriginals[id] ?: id)
    private fun actualId(id: String) = supersessions[id]?.replacementId ?: id
    private fun isHead(id: String) = pending.values.minByOrNull { logicalDependency(it.operationId).logicalOrder }?.operationId == id
    private fun requestSnapshot(id: String, named: String? = null): RequestSnapshot {
        current()
        val origin = requireNotNull(origins[id])
        val queue = pending[origin.queueId]
        val deps = mutableSetOf<G0DomainProofIndex.Dependency>(
            G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("metrics", cells(listOf(metric.id)))),
            G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("sync_entity_state", cells(listOf("metric", metricUuid)))),
            G0DomainProofIndex.Dependency.Range("sync_outbox", "entityUuid", cell(metricUuid)))
        for (table in listOf("next_request_origins", "next_transmissions", "next_acceptances"))
            deps += G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key(table, cells(listOf(NEXT_OPERATION, id))))
        deps += G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("next_structural_dependencies", cells(listOf(logicalDependency(id).operationId))))
        deps += G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("next_structural_supersessions", cells(listOf(replacementOriginals[id] ?: id))))
        named?.let { deps += G0DomainProofIndex.Dependency.Range("metrics", "name", cell(it)) }
        // Names are immutable except through this one metric writer. Changes to any metric
        // from another admitted owner change the gate epoch and fail current() before borrowing.
        return RequestSnapshot(origin, transmissions[id]?.let { it.copy(wireBytes = it.wireBytes.copyOf()) }, transmissionHashes[id], receipts[id], queue,
            metric, shadow, G0MetricMergePlan.Input(metric, shadow, pending.values.any { it.id != origin.queueId }, false,
                setOfNotNull(metric.name, named).associateWith { names[it].orEmpty().toSet() }), index.witness(deps), logicalDependency(id), originHashes.getValue(id), isHead(id))
    }
    private data class SuccessorSnapshot(val request: RequestSnapshot, val parentOriginal: NextRequestOriginEntity,
        val parentActual: NextRequestOriginEntity, val parentReceipt: NextAcceptanceEntity,
        val parentReceiptHash: String, val witness: G0DomainProofIndex.Witness, val replacementId: String)

    /** Called only inside the original HTTP exact-v5 session, after discovery. A new request
     * retires one never-transmitted source; it never edits the original intent or old receipt. */
    private suspend fun prepareSuccessor(access: LocalSyncAccess, requested: String): String =
        retryPreparation { prepareSuccessorOnce(access,requested) }
    private suspend fun prepareSuccessorOnce(access: LocalSyncAccess, requested: String): String {
        current(); val lease = authority.lease(); authorize(access)
        var resolved = requested
        val before = account { gate.transaction(db, G0DomainSqlGate.Phase.READ) {
            current(); authority.checkLease(lease)
            resolved = actualId(requested)
            val request = requestSnapshot(resolved)
            if (resolved != requested || resolved in replacementOriginals || request.transmission != null ||
                request.receipt != null || request.dependency.predecessorId == null) null
            else {
                require(request.isHead) { "G0_SHORT_PREDECESSOR_PENDING" }
                val source = requireNotNull(request.queue)
                require(source.entityUuid == source.wireEntityUuid && source.recordType == "metric" && source.action == "upsert" &&
                    source.payloadJson == null && source.baseRevision == null && source.basePayloadJson == null &&
                    source.attemptedAt == null && source.attemptCount == 0 && source.lastError == null &&
                    source.errorCode == null && source.deadLetteredAt == null &&
                    (source.referenceUuid == null || com.dayforge.domain.model.isContractUuid(source.referenceUuid)))
                val parentId = requireNotNull(request.dependency.predecessorId)
                val parentActualId = actualId(parentId)
                val receipt = requireNotNull(receipts[parentActualId]) { "G0_SHORT_PREDECESSOR_PENDING" }
                val replacementId = replacementIdentity()
                val keys = mutableSetOf<G0DomainProofIndex.Dependency>()
                for (id in setOf(parentId, parentActualId, requested, replacementId)) {
                    for (table in listOf("next_request_origins", "next_transmissions", "next_acceptances"))
                        keys += G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key(table,cells(listOf(NEXT_OPERATION,id))))
                    keys += G0DomainProofIndex.Dependency.Row(G0DomainProofIndex.Key("next_structural_supersessions",cells(listOf(id))))
                }
                SuccessorSnapshot(request,origins.getValue(parentId),origins.getValue(parentActualId),receipt,
                    receiptHashes.getValue(parentActualId),index.witness(keys),replacementId)
            }
        } }
        if (before == null) return resolved
        val replacementId = before.replacementId
        val source = requireNotNull(before.request.queue)
        val prepared = withContext(Dispatchers.Default) {
            val merged = NextStructuralRebase.merge(decodeNextOperationIntent(before.parentOriginal.intentJson),
                decodeNextOperationIntent(before.request.origin.intentJson),
                decodeFrozenSyncRequest(before.parentReceipt.resultJson.toByteArray(),NextSyncOperationResult.serializer()),
                replacementId,decodeNextOperationIntent(before.parentActual.intentJson))
            val bytes = encodeNextOperationIntent(merged,before.request.origin.intentJson)
            decodeNextOperationIntent(bytes.toString(Charsets.UTF_8))
            decodeFrozenSyncRequest(encodeSyncRequest(NextSyncPushRequest.serializer(),
                NextSyncPushRequest(requireNotNull(access.deviceId),listOf(merged))),NextSyncPushRequest.serializer())
            bytes.toString(Charsets.UTF_8) to encodeSyncRequest(SyncOutboxEntity.serializer(),source).toString(Charsets.UTF_8)
        }
        afterPrepareForTest()
        return account {
            requirePreparation(lease,before.request.witness,before.witness)
            authority.finalPermit(lease).use {
                lateinit var advancement: G0DomainProofIndex.Advancement
                lateinit var queue: SyncOutboxEntity
                lateinit var origin: NextRequestOriginEntity
                lateinit var supersession: NextStructuralSupersessionEntity
                var originHash = ""
                try {
                    val result = gate.transaction(db,G0DomainSqlGate.Phase.BUSINESS,committed = { changes ->
                        index.committed(advancement,changes); epoch = gate.revision
                        pending.remove(source.id); pending[queue.id] = queue; queueWatermark = queue.id
                        origins[replacementId] = origin; originHashes[replacementId] = originHash
                        supersessions[requested] = supersession; replacementOriginals[replacementId] = requested
                    }) {
                        current(); authority.checkLease(lease)
                        index.requireCurrent(before.request.witness); index.requireCurrent(before.witness)
                        require(actualId(requested) == requested && requested !in transmissions && requested !in receipts)
                        require(replacementId !in origins && replacementId !in foreignRequests && replacementId !in transmissions && replacementId !in receipts)
                        queue = source.copy(id=Math.addExact(queueWatermark,1),operationId=replacementId)
                        require(queue.id > source.id)
                        origin = before.request.origin.copy(requestId=replacementId,queueId=queue.id,
                            serverInstanceId=access.session.serverInstanceId,syncEpoch=access.session.syncEpoch,
                            sourceHash=NextRequestSql.sourceHash(queue),intentJson=prepared.first)
                        insert("sync_outbox",queueValues(queue))
                        insert("next_request_origins",originValues(origin))
                        val sql = db.openHelper.writableDatabase
                        originHash = requireNotNull(NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,replacementId)))
                        supersession = NextStructuralSupersessionEntity(requested,replacementId,source.id,queue.id,
                            before.request.originHash,dependencyHashes.getValue(requested),before.request.origin.sourceHash,
                            prepared.second,nextRequestHash(prepared.second.toByteArray()),before.parentActual.requestId,
                            before.parentReceiptHash,originHash,origin.accountId,requireNotNull(origin.serverInstanceId),
                            requireNotNull(origin.syncEpoch),requireNotNull(access.deviceId))
                        insert("next_structural_supersessions",supersessionValues(supersession))
                        sql.execSQL("DELETE FROM sync_outbox WHERE id=?",arrayOf(source.id))
                        val expected = listOf(
                            G0DomainJournal.Change(0,"sync_outbox","INSERT",null,cells(queueValues(queue))),
                            G0DomainJournal.Change(0,"next_request_origins","INSERT",null,cells(originValues(origin))),
                            G0DomainJournal.Change(0,"next_structural_supersessions","INSERT",null,cells(supersessionValues(supersession))),
                            G0DomainJournal.Change(0,"sync_outbox","DELETE",cells(queueValues(source)),null))
                        beforeFinalForTest()
                        gate.certifyChanges { actual ->
                            check(actual.map { it.copy(sequence=0) } == expected) { "G0_SHORT_UNEXPECTED_EFFECT" }
                            advancement = index.prepare(actual)
                        }
                        gate.finalRead { authorize(access) }
                        replacementId
                    }
                    authority.checkLease(lease); result
                } catch(failure: Throwable) { poison = true; index.invalidate(); throw failure }
            }
        }
    }
    private fun supersessionValues(r: NextStructuralSupersessionEntity): List<Any?> = listOf(r.originalId,r.replacementId,
        r.originalQueueId,r.replacementQueueId,r.originalOriginHash,r.originalDependencyHash,r.originalSourceHash,
        r.sourceSnapshotJson,r.sourceSnapshotHash,r.predecessorAcceptedRequestId,r.predecessorAcceptanceHash,
        r.replacementOriginHash,r.accountId,r.serverInstanceId,r.syncEpoch,r.deviceId,r.kind)

    suspend fun send(http: com.dayforge.data.api.NextSyncHttp, captured: LocalSyncAccess, requestId: String): NextCoreDelivery<NextSyncPushResponse>? {
        val access = captured.copy(capabilities = captured.capabilities.toSet())
        return http.session(access) { channel ->
        val id = prepareSuccessor(access, requestId)
        current(); val lease = authority.lease(); authorize(access)
        val before = account { gate.transaction(db, G0DomainSqlGate.Phase.READ) { requestSnapshot(id) } }
        require(before.receipt == null && before.queue != null)
        val operation = decodeNextOperationIntent(before.origin.intentJson)
        require(before.isHead) { "G0_SHORT_PREDECESSOR_PENDING" }
        val bytes = before.transmission?.wireBytes ?: encodeSyncRequest(NextSyncPushRequest.serializer(), NextSyncPushRequest(requireNotNull(access.deviceId), listOf(operation)))
        validateNextOperationEnvelope(before.origin.intentJson, bytes, requireNotNull(access.deviceId))
        val transmitted = before.transmission ?: NextTransmissionEntity(NEXT_OPERATION, id, before.origin.queueId, 5,
            before.origin.accountId, requireNotNull(access.session.serverInstanceId), requireNotNull(access.session.syncEpoch),
            access.deviceId, nextRequestHash(bytes), bytes)
        var proof = before.transmissionHash
        account {
            authority.finalPermit(lease).use {
                lateinit var advancement: G0DomainProofIndex.Advancement
                try {
                    gate.transaction(db, G0DomainSqlGate.Phase.BUSINESS, committed = { changes ->
                        index.committed(advancement, changes); epoch = gate.revision
                        transmissions[id] = transmitted.copy(wireBytes = transmitted.wireBytes.copyOf()); transmissionHashes[id] = requireNotNull(proof)
                    }) {
                        current(); index.requireCurrent(before.witness); authority.checkLease(lease)
                        if (before.transmission == null) {
                            insert("next_transmissions", transmissionValues(transmitted))
                            proof = requireNotNull(NextRequestSql.rowHash(db.openHelper.writableDatabase, "next_transmissions", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                        }
                        gate.certifyChanges { actual ->
                            val expected = if (before.transmission == null) listOf(G0DomainJournal.Change(0,"next_transmissions","INSERT",null,cells(transmissionValues(transmitted)))) else emptyList()
                            check(actual.map { it.copy(sequence = 0) } == expected) { "G0_SHORT_UNEXPECTED_EFFECT" }
                            advancement = index.prepare(actual)
                        }
                        gate.finalRead { authorize(access) }
                    }
                } catch(failure: Throwable) { poison = true; index.invalidate(); throw failure }
            }
        }
        UnavailableMaterialDependencies.requireReady(access, setOfNotNull(fixedMaterialAsset(operation)), emptySet())
        val response = channel.pushFrozen(transmitted.wireBytes.copyOf())
        authorize(access)
        account { gate.transaction(db, G0DomainSqlGate.Phase.READ) {
            current(); authority.checkLease(lease)
            val actual = requestSnapshot(id)
            require(actual.origin == before.origin && actual.queue == before.queue && actual.transmissionHash == proof && actual.receipt == null)
            authorize(access)
        } }
        NextCoreDelivery(access, id, response, requireNotNull(proof))
        }
    }
    suspend fun acknowledge(input: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance {
        // Freeze caller-owned collections synchronously before any credential or dispatcher wait.
        val frozenResponse = coldStage("warm_ack_freeze") { freeze(NextSyncPushResponse.serializer(), input.result) }
        val access = input.access.copy(capabilities = input.access.capabilities.toSet())
        require(input.challengeMetadata == null && input.configImportProof == null)
        val response = coldStage("warm_ack_response_decode") { decodeFrozenSyncRequest(frozenResponse, NextSyncPushResponse.serializer()) }
        val owned = input.copy(access=access,result=response)
        return retryPreparation { acknowledgePrepared(owned) }
    }
    private suspend fun acknowledgePrepared(input: NextCoreDelivery<NextSyncPushResponse>): NextOperationAcceptance {
        val access = input.access
        val response = input.result
        val named = (response.results.singleOrNull()?.entity?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
        current(); val lease = authority.lease(); authorize(access)
        val id = input.requestId
        val before = account { preparationSnapshot { current(); authority.checkLease(lease); requestSnapshot(id, named) } }
        require(before.transmissionHash == input.transmissionProof && before.transmission != null && response.results.size == 1)
        val operation = coldStage("warm_ack_envelope") { validateNextOperationEnvelope(before.origin.intentJson, before.transmission.wireBytes, requireNotNull(access.deviceId)) }
        val result = response.results.single()
        val normalized = result.copy(status = "applied")
        val bytes = encodeSyncRequest(NextSyncOperationResult.serializer(), normalized)
        val resultJson = bytes.toString(Charsets.UTF_8)
        // Same normalized metric-result predicates as the original causal validator. Its
        // private decode helper requires a Room transaction; the strict codec itself does not.
        val decoded = coldStage("warm_ack_result_decode") { decodeFrozenSyncRequest(bytes, NextSyncOperationResult.serializer()) }
        require(decoded.status == "applied" && decoded.revision != null && decoded.revision > 0 &&
            decoded.entity != null && decoded.errorCode == null && decoded.message == null &&
            decoded.baseEntity == null && decoded.localEntity == null && decoded.conflictingFields.isEmpty() &&
            decoded.conflictKind == null && decoded.oneTimeConflict == null && decoded.entityType == "metric")
        NextStructureMapper.readMetric(decoded.entity, decoded.entityUuid, decoded.revision)
        val change = NextOrdinaryResultMapper.validate(operation, result, requireNotNull(access.deviceId))
        val replay = before.receipt != null
        if (replay) {
            require(before.receipt!!.resultJson == resultJson || decodeFrozenSyncRequest(before.receipt.resultJson.toByteArray(), NextSyncOperationResult.serializer()) == normalized) { "G0_SHORT_RESULT_CHANGED" }
            require(before.queue == null)
        } else require(before.queue != null && before.isHead) { "G0_SHORT_PREDECESSOR_PENDING" }
        val plan = if (replay) null else coldStage("warm_ack_plan") { withContext(Dispatchers.Default) { G0MetricMergePlan.freeze(change, before.input, System.currentTimeMillis()) } }
        var committedReceiptHash = receiptHashes[id]
        val receipt = before.receipt ?: NextAcceptanceEntity(NEXT_OPERATION, id, before.originHash, requireNotNull(before.transmissionHash), nextRequestHash(bytes), resultJson)
        afterPrepareForTest()
        return account {
            requirePreparation(lease,before.witness)
            authority.finalPermit(lease).use {
                lateinit var advancement: G0DomainProofIndex.Advancement
                try {
                    val outcome = gate.transaction(db, G0DomainSqlGate.Phase.BUSINESS, committed = { actual ->
                        index.committed(advancement, actual); epoch = gate.revision
                        plan?.output?.metric?.let { next ->
                            names[metric.name]?.remove(metricUuid); names.getOrPut(next.name) { linkedSetOf() }.add(metricUuid); metric = next
                        }
                        plan?.output?.shadow?.let { shadow = it }
                        receipts[id] = receipt
                        receiptHashes[id] = requireNotNull(committedReceiptHash)
                        if (!replay) pending.remove(before.origin.queueId)
                    }) {
                        val expected = mutableListOf<G0DomainJournal.Change>()
                        coldStage("warm_ack_apply") {
                            current(); authority.checkLease(lease); index.requireCurrent(before.witness)
                            plan?.revalidate(requestSnapshot(id, named).input)
                            fun effect(table: String, kind: String, old: List<Any?>?, new: List<Any?>?) { expected += G0DomainJournal.Change(0,table,kind,old?.let(::cells),new?.let(::cells)) }
                            val sql = db.openHelper.writableDatabase
                            if (!replay) {
                                sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1"); effect("sync_control","UPDATE",listOf(1L,0L),listOf(1L,1L))
                                requireNotNull(plan).output.metric?.let { next ->
                                    val statement = G0DomainSqlCatalog.writes.keys.single { it.startsWith("UPDATE OR ABORT `metrics`") }
                                    sql.execSQL(statement, (metricValues(next) + next.id).toTypedArray())
                                    effect("metrics","UPDATE",metricValues(before.metric),metricValues(next))
                                }
                                plan.output.shadow?.let { next ->
                                    val statement = G0DomainSqlCatalog.writes.keys.single { it.startsWith("INSERT OR REPLACE INTO `sync_entity_state`") }
                                    sql.execSQL(statement, shadowValues(next).toTypedArray())
                                    before.shadow?.let { effect("sync_entity_state","DELETE",shadowValues(it),null) }
                                    effect("sync_entity_state","INSERT",null,shadowValues(next))
                                }
                                sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1"); effect("sync_control","UPDATE",listOf(1L,1L),listOf(1L,0L))
                                insert("next_acceptances", receiptValues(receipt)); effect("next_acceptances","INSERT",null,receiptValues(receipt))
                                sql.execSQL("DELETE FROM sync_outbox WHERE id=?", arrayOf(before.origin.queueId)); effect("sync_outbox","DELETE",queueValues(requireNotNull(before.queue)),null)
                            }
                            committedReceiptHash = requireNotNull(NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, id)))
                        }
                        beforeFinalForTest()
                        gate.certifyChanges { actual ->
                            check(actual.map { it.copy(sequence = 0) } == expected) { "G0_SHORT_UNEXPECTED_EFFECT" }
                            advancement = index.prepare(actual)
                        }
                        gate.finalRead { authorize(access) }
                        if (replay) NextOperationAcceptance.REPLAYED else NextOperationAcceptance.COMMITTED
                    }
                    authority.checkLease(lease); outcome
                } catch(failure: Throwable) { poison = true; index.invalidate(); throw failure }
            }
        }
    }
    private fun transmissionValues(row: NextTransmissionEntity): List<Any?> = listOf(row.kind,row.requestId,row.queueId,row.protocol,row.accountId,row.serverInstanceId,row.syncEpoch,row.deviceId,row.wireHash,row.wireBytes)
    private fun receiptValues(row: NextAcceptanceEntity): List<Any?> = listOf(row.kind,row.requestId,row.originHash,row.transmissionHash,row.resultHash,row.resultJson)
    private fun shadowValues(row: SyncEntityStateEntity): List<Any?> = listOf(row.entityType,row.entityUuid,row.revision,if(row.deleted) 1L else 0L,row.payloadJson,row.payloadHash,row.updatedAt)
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun <T> freeze(serializer: kotlinx.serialization.KSerializer<T>, value: T): ByteArray {
        val output = object : java.io.ByteArrayOutputStream(8192) {
            override fun write(value: Int) { require(size() < com.dayforge.data.api.SYNC_REQUEST_LIMIT); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { require(length <= com.dayforge.data.api.SYNC_REQUEST_LIMIT - size()); super.write(bytes,offset,length) }
        }
        return output.use { frozenJson.encodeToStream(serializer,value,it); it.toByteArray() }
    }
    data class View(val name: String, val description: String, val pending: Int, val serverRevision: Long?)
    suspend fun read(access: LocalSyncAccess): View = verifiedRead(access) {
        View(metric.name,metric.description,pending.size,shadow?.revision)
    }
    /** A frozen, finite plan from the certified dataset, not permission to send later.
     * Every send/ACK still revalidates its actual logical head, source and fresh authority.
     */
    suspend fun pendingSyncPlan(access: LocalSyncAccess): List<String> = verifiedRead(access) {
        pending.values.sortedBy { logicalDependency(it.operationId).logicalOrder }.map { it.operationId }
    }
    private suspend fun <T> verifiedRead(access: LocalSyncAccess, capture: () -> T): T {
        current() // Failed/cancelled cold initialization has no published scope to authorize.
        val lease = authority.lease()
        require(access.session == scope.session && access.deviceId == scope.deviceId && "sync.read" in access.capabilities && tokens.isCurrentSyncAuthority(access))
        return account {
            authority.finalPermit(lease).use {
                val view = gate.transaction(db, G0DomainSqlGate.Phase.READ) {
                    current(); authority.checkLease(lease)
                    val value = capture()
                    require(tokens.isCurrentSyncAuthority(access))
                    value
                }
                authority.checkLease(lease); view
            }
        }
    }
    private suspend fun <T> account(work: AccountSessionCoordinator.Work = AccountSessionCoordinator.Work.GENERAL, body: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        val queued = trace?.now()
        return sessions.exclusive(work) {
            if (queued != null) trace.record("short_account_wait",queued,true)
            if (trace == null) body() else trace.stage("short_account_hold",body)
        }
    }
    private fun insert(table: String, values: List<Any?>) {
        val sql = G0DomainSqlCatalog.writes.keys.single { it.startsWith("INSERT OR ABORT INTO `$table`") }
        db.openHelper.writableDatabase.execSQL(sql, values.toTypedArray())
    }
    private fun metricValues(row: MetricEntity): List<Any?> = listOf(row.id,row.name,row.description,row.unit,row.decimalPlaces,row.aggregationType,
        row.targetDirection,row.targetValue,row.targetValueUpper,row.iconResId,row.colorHex,if(row.isActive) 1L else 0L,row.uuid,row.createdAt,row.updatedAt,HabitTypeConverter().fromAppearance(row.appearance))
    private fun queueValues(row: SyncOutboxEntity): List<Any?> = listOf(row.id,row.operationId,row.recordType,row.entityUuid,row.wireEntityUuid,row.action,row.referenceUuid,row.payloadJson,row.baseRevision,row.basePayloadJson,row.attemptedAt,row.attemptCount,row.lastError,row.errorCode,row.deadLetteredAt,row.createdAt)
    private fun originValues(row: NextRequestOriginEntity): List<Any?> = listOf(row.kind,row.requestId,row.queueId,row.protocol,row.accountId,row.serverInstanceId,row.syncEpoch,row.sourceHash,row.intentJson)
    private fun dependencyValues(row: NextStructuralDependencyEntity): List<Any?> = listOf(row.operationId,row.logicalOrder,row.originHash,row.predecessorId,row.predecessorOriginHash,row.predecessorDependencyHash,row.capturedDeviceId,row.kind)
    private fun cell(value: Any?): G0DomainJournal.Cell = when(value) {
        null -> G0DomainJournal.Cell(0,null)
        is String -> G0DomainJournal.Cell(3,value)
        is Int -> G0DomainJournal.Cell(1,value.toLong())
        is Long -> G0DomainJournal.Cell(1,value)
        is Double -> G0DomainJournal.Cell(2,java.lang.Double.doubleToRawLongBits(value))
        is ByteArray -> G0DomainJournal.Cell(4,value.toList())
        else -> error("Unsupported native value")
    }
    private fun cells(values: List<Any?>) = values.map(::cell)
    private companion object { val frozenJson = kotlinx.serialization.json.Json { encodeDefaults = true } }
}
