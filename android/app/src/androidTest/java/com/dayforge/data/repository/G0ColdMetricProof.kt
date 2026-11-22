package com.dayforge.data.repository

import com.dayforge.data.api.*
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.isContractUuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** G0 ONLY, plain metric/upsert receipt audit. The explicit checks mirror NextStructuralCausalStore.accepted. Exact root dependencies
 * use a narrow equivalent path; ancestors/supersessions retain original resolve. Metric
 * normalization is fused; the unmodified original remains the differential reference.
 * Requires the caller's original SELECT-only evidence session in its reserved Room snapshot.
 * No SQL proof or authority survives here; returned rows become usable only after that session
 * and the outer cold transaction/fresh authorization have completed successfully.
 */
internal class G0ColdMetricProof(private val db: HabitDatabase, private val metricUuid: String,
    private val refineDependencies: Boolean = true,
    private val refineDecoding: Boolean = true,
    cacheCodecRules: Boolean = true, measureCodecStages: Boolean = true,
    private val rows: G0ColdEvidenceRows? = null,
    private val negatives: G0ColdNegativeEvidence? = null,
    reusePureValues: Boolean = true,
    private val reuseEnvelopeText: Boolean = true, predecoded: G0ColdPureInputs? = null) {
    private val normalization = G0ColdMetricNormalization(reusePureValues)
    private val decoder = G0ColdStrictDecoder(cacheCodecRules,measureCodecStages,reusePureValues,predecoded)
    data class Result(val operation: SyncV2Operation, val transmission: NextTransmissionEntity,
        val receipt: NextAcceptanceEntity, val originHash: String, val transmissionHash: String,
        val receiptHash: String, val strictPlain: Boolean = false, val dependency: NextStructuralDependencyEntity? = null)

    private suspend fun <T> phase(name: String, block: suspend () -> T): T {
        val trace = G0PhaseTrace.current.get()
        return if (trace == null) block() else trace.stage("cold_proof_$name",block)
    }

    suspend fun verify(id: String, access: LocalSyncAccess, origin: NextRequestOriginEntity,
        receipt: NextAcceptanceEntity, causal: NextStructuralCausalStore,
        memo: NextStructuralCausalStore.PureMemo): Result {
        val session = NextEvidenceReadSession.current(db)
        if ("structure.write" !in access.capabilities)
            rejectNextRequest(NextRequestException.Reason.PERMISSION_DENIED)
        val root = phase("lineage") {
            if (refineDependencies) rootDependency(id,access) else null
        }
        if (root == null) phase("lineage_fallback") { require(causal.resolve(id, access) == id) }
        // Original resolve audits ancestry; an exact-original legacy receipt can lack ancestry.
        // Therefore repeat the original explicit origin/context conditions in all cases.
        val originHash = phase("origin") {
            require(isContractUuid(id) && origin.kind == NEXT_OPERATION && origin.requestId == id &&
                origin.protocol == 5 && origin.queueId > 0 && isContractUuid(origin.accountId) &&
                (origin.serverInstanceId == null) == (origin.syncEpoch == null))
            origin.serverInstanceId?.let { require(isContractUuid(it) && isContractUuid(requireNotNull(origin.syncEpoch))) }
            if (origin.accountId != access.session.authentication.userId || origin.serverInstanceId != null &&
                (origin.serverInstanceId != access.session.serverInstanceId || origin.syncEpoch != access.session.syncEpoch))
                rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
            requireNotNull(NextRequestSql.rowHash(db,"next_request_origins","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id)))
        }
        val operation = phase("intent") {
            val value = if (refineDecoding) {
                // Strict plain DTO rejects both profile wrappers and unknown fields, so the
                // supported metric path does not need repeated discriminator-only parses.
                // The original discriminator parses the String before byte decoding and
                // rejects a leading BOM; the byte codec alone would strip and accept it.
                require(!origin.intentJson.startsWith("\uFEFF"))
                decoder.decode(origin.intentJson.toByteArray(Charsets.UTF_8),SyncV2Operation.serializer())
            } else {
                require(roundOperationIntent(origin.intentJson) == null) { "G0_SHORT_UNSUPPORTED_SCOPE" }
                memo.parsedIntents[origin.intentJson]?.also {
                    G0PhaseTrace.current.get()?.call("cold_proof_intent_reuse") { Unit }
                } ?: decodeNextOperationIntent(origin.intentJson)
            }
            require(value.operationId == origin.requestId && value.entityType == "metric" &&
                value.action == "upsert" && value.entityUuid == metricUuid) { "G0_SHORT_UNSUPPORTED_SCOPE" }
            value
        }
        if (root != null) phase("dependency_binding") {
            require(root.operationId == id && root.kind == NEXT_OPERATION && root.logicalOrder > 0 &&
                root.logicalOrder == origin.queueId && root.originHash == originHash &&
                root.predecessorOriginHash == null && root.predecessorDependencyHash == null)
            root.capturedDeviceId?.let { require(isContractUuid(it)) }
            if (root.capturedDeviceId != null && root.capturedDeviceId != access.deviceId)
                rejectNextRequest(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED)
        }
        if (!refineDecoding) phase("restart") { NextRestartBindingStore(db).requireReady(access,origin) }
        // A successful strict plain intent has no round/restart fields. The original
        // requireReady returns null for precisely this branch; no restart proof is omitted.
        val receiptHash = phase("receipt_hash") {
            NextRequestSql.rowHash(db,"next_acceptances","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id))
                ?: rejectNextRequest(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING)
        }
        val transmissionHash = phase("transmission_hash") {
            requireNotNull(NextRequestSql.rowHash(db,"next_transmissions","kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id)))
        }
        val transmission = phase("transmission_row") { requireNotNull(if(rows==null) db.nextRequestDao().transmission(NEXT_OPERATION,id) else rows.transmission(id)) }
        phase("wire") {
            require(transmission.kind == NEXT_OPERATION && transmission.requestId == id && transmission.protocol == 5 &&
                transmission.queueId == origin.queueId && transmission.accountId == origin.accountId &&
                transmission.serverInstanceId == access.session.serverInstanceId && transmission.syncEpoch == access.session.syncEpoch &&
                transmission.deviceId == access.deviceId && transmission.wireHash == nextRequestHash(transmission.wireBytes))
            // Same strict bounded PLAIN decoder as validateNextOperationEnvelope's plain branch.
            // Do NOT reuse decodeNextOperationEnvelope's round-to-plain projection: doing so would
            // permit an extra challenge profile in the frozen envelope of a plain source.
            val context = currentCoroutineContext(); context.ensureActive()
            val envelope = if (refineDecoding) {
                if(reuseEnvelopeText) decoder.decodeEnvelope(transmission.wireBytes,requireNotNull(access.deviceId))
                else decoder.decode(transmission.wireBytes,NextSyncPushRequest.serializer())
            }
            else if (transmission.wireBytes.size > 8192)
                decodeFrozenSyncRequest(transmission.wireBytes, NextSyncPushRequest.serializer())
            else try { decodeSyncReply(transmission.wireBytes,SYNC_REQUEST_LIMIT,NextSyncPushRequest.serializer(), { context.ensureActive() }) }
                catch (_: NextSyncReplyInvalid) { throw InvalidFrozenSyncRequest() }
            require(envelope.deviceId == transmission.deviceId && envelope.operations == listOf(operation))
        }
        phase("receipt_binding") {
            require(receipt.kind == NEXT_OPERATION && receipt.requestId == id && receipt.originHash == originHash &&
                receipt.transmissionHash == transmissionHash && receipt.resultHash == nextRequestHash(receipt.resultJson.toByteArray(Charsets.UTF_8)))
        }
        phase("normalized_result") {
            val result = if (!refineDependencies) causal.validateStructuralResult(receipt.resultJson) else {
                val value = phase("result_decode") {
                    val bytes=receipt.resultJson.toByteArray(Charsets.UTF_8)
                    val context=currentCoroutineContext();context.ensureActive()
                    if(refineDecoding) decoder.decode(bytes,NextSyncOperationResult.serializer())
                    else if(bytes.size>8192) decodeFrozenSyncRequest(bytes,NextSyncOperationResult.serializer())
                    else try { decodeSyncReply(bytes,SYNC_REQUEST_LIMIT,NextSyncOperationResult.serializer(),{ context.ensureActive() }) }
                        catch (_:NextSyncReplyInvalid) { throw InvalidFrozenSyncRequest() }
                }
                require(value.entityType=="metric")
                phase("result_entity") { normalization.read(requireNotNull(value.entity),value.entityUuid,requireNotNull(value.revision)) }
                value
            }
            validateTaskResultBinding(operation,result)
            require(result.status == "applied" && result.revision != null && result.revision > 0 &&
                result.revision >= (operation.baseRevision ?: 0) && result.entity != null && result.errorCode == null && result.message == null &&
                result.baseEntity == null && result.localEntity == null && result.conflictingFields.isEmpty() &&
                result.conflictKind == null && result.oneTimeConflict == null)
        }
        phase("source_absence") {
            if(negatives?.absent(G0ColdNegativeEvidence.Probe.SOURCE_QUEUE,id,origin.queueId)!=true)
                require(NextRequestSql.rowHash(db,"sync_outbox","id=?",arrayOf(origin.queueId)) == null)
            if(negatives?.absent(G0ColdNegativeEvidence.Probe.SOURCE_OPERATION,id)!=true)
                db.openHelper.writableDatabase.query("SELECT 1 FROM sync_outbox WHERE operationId=? LIMIT 1",arrayOf(id)).use { require(!it.moveToFirst()) }
        }
        check(NextEvidenceReadSession.current(db) === session)
        return Result(operation,transmission.copy(wireBytes=transmission.wireBytes.copyOf()),receipt,
            originHash,transmissionHash,receiptHash,refineDecoding,root)
    }
    /** Fast path only for an exact original with a real root dependency. Any ancestry or
     * supersession uses the original complete traversal; missing dependencies also fall back.
     * Every probe uses native raw auditing in the current evidence session. No absence inferred
     * from a typed row/cache, and recovery membership is still audited by the original store.
     */
    private suspend fun rootDependency(id:String,access:LocalSyncAccess):NextStructuralDependencyEntity? {
        require(isContractUuid(id))
        val superseded=phase("lineage_supersession") {
            val original=if(negatives?.absent(G0ColdNegativeEvidence.Probe.ORIGINAL,id)==true) null else NextRequestSql.rowHash(db,"next_structural_supersessions","originalId=?",arrayOf(id))
            val replacement=if(negatives?.absent(G0ColdNegativeEvidence.Probe.REPLACEMENT,id)==true) null else NextRequestSql.rowHash(db,"next_structural_supersessions","replacementId=?",arrayOf(id))
            original!=null || replacement!=null
        }
        if(superseded) return null
        val dependency=phase("lineage_dependency") {
            if(NextRequestSql.rowHash(db,"next_structural_dependencies","operationId=?",arrayOf(id))==null) null
            else requireNotNull(db.nextStructuralCausalDao().dependency(id))
        } ?: return null
        if(dependency.predecessorId!=null) return null
        phase("lineage_recovery") {
            if(negatives?.absent(G0ColdNegativeEvidence.Probe.RECOVERY,id)!=true &&
                NextRecoveryAuditStore(db).source(access,NextRecoveryKey(NEXT_OPERATION,id))!=null)
                rejectNextRequest(NextRequestException.Reason.LOCALLY_ABANDONED)
        }
        return dependency
    }

}
