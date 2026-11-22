package com.dayforge.data.repository

import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.ZonedDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive

/** Cold-only admission of immutable, unsent plain COUNT/CHECK facts beside the metric writer.
 * Original readers prove facts; original source/causal validators prove each foreign request.
 * No capability escapes. The sealed writer subsequently admits only its exact metric write set.
 * This is not a generic foreign-row exemption or authority to send/ACK these other requests.
 */
internal class G0RecurringReadAdmission(private val db:HabitDatabase,private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator) {
    suspend fun verify(access:LocalSyncAccess,snapshot:G0MetricBlocks.Snapshot,metricUuid:String,retained:G0ColdRecurringProof?=null):Set<String> {
        NextEvidenceReadSession.current(db)
        val sql=db.openHelper.writableDatabase
        val plans=snapshot.plans.filter { it.habitType in setOf(HabitType.COUNTING,HabitType.CHECK_IN) && it.completionPolicy=="recurring" }
        val byUuid=plans.associateBy { it.uuid };val byId=plans.associateBy { it.id }
        val witness=tokens.replicaPresentationWitness()
        require(!witness.pointerPresent && witness.history.isEmpty() && ReplicaPresentationRepository(db,tokens,sessions).readFenceInTransaction()==null) { "G0_RECURRING_REPLICA_SCOPE_UNSUPPORTED" }
        // Other fact domains require their own admission, never silently inherit this proof.
        for(table in listOf("timelogs","metric_logs","habit_metric_links")) sql.query("SELECT 1 FROM $table LIMIT 1").use {
            require(!it.moveToFirst()) { "G0_MIXED_UNSUPPORTED_FACT_DOMAIN" }
        }
        for(table in listOf("completions","count_days")) sql.query("SELECT DISTINCT habitId FROM $table").use { c ->
            while(c.moveToNext()) require(c.getLong(0) in byId) { "G0_MIXED_UNSUPPORTED_FACT_DOMAIN" }
        }
        val today=ZonedDateTime.now().toLocalDate()
        if(retained!=null) retained.requireMatches(plans,today) else for(row in plans) {
            currentCoroutineContext().ensureActive()
            if(row.habitType==HabitType.COUNTING) CountHistoryReader(db,tokens,sessions).readInTransaction(row,today)
            else CheckHistoryReader(db,tokens,sessions).readInTransaction(row,today)
        }
        val foreignQueues=snapshot.pending.filterNot { it.recordType=="metric" && it.entityUuid==metricUuid }
        val queues=foreignQueues.associateBy { it.operationId }
        require(queues.size==foreignQueues.size)
        val admitted=linkedSetOf<String>();val originals=NextOriginalRequestProofStore(db);val causal=NextStructuralCausalStore(db)
        sql.query("SELECT kind,requestId FROM next_request_origins").use { cursor ->
            while(cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(cursor.getString(0)==NEXT_OPERATION) { "G0_SHORT_UNSUPPORTED_SCOPE" }
                val id=cursor.getString(1);val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id))
                require(roundOperationIntent(origin.intentJson)==null) { "G0_SHORT_UNSUPPORTED_SCOPE" }
                val operation=decodeNextOperationIntent(origin.intentJson)
                if(operation.entityType=="metric" && operation.entityUuid==metricUuid) continue
                validateNextSyncOperation(operation)
                require(operation.action=="upsert" && operation.baseRevision==null) { "G0_MIXED_UNSUPPORTED_FOREIGN_HISTORY" }
                val queue=requireNotNull(queues[id]) { "G0_MIXED_FOREIGN_SOURCE_MISSING" }
                require(originals.liveOrigin(access,NEXT_OPERATION,id)==origin)
                require(queue.payloadJson==null && queue.baseRevision==null && queue.basePayloadJson==null && queue.attemptedAt==null &&
                    queue.attemptCount==0 && queue.lastError==null && queue.errorCode==null && queue.deadLetteredAt==null)
                for(table in listOf("next_transmissions","next_acceptances"))
                    require(NextRequestSql.rowHash(db,table,"kind=? AND requestId=?",arrayOf(NEXT_OPERATION,id))==null) { "G0_MIXED_UNSUPPORTED_FOREIGN_HISTORY" }
                require(NextRequestSql.rowHash(db,"one_time_transmissions","operationId=?",arrayOf(id))==null)
                when(operation.entityType) {
                    "plan_node" -> {
                        val plan=requireNotNull(byUuid[operation.entityUuid]) { "G0_MIXED_UNSUPPORTED_FACT_DOMAIN" }
                        require(operation.payload==NextStructureMapper.writePlan(plan)) { "G0_MIXED_FOREIGN_PLAN_CHANGED" }
                        require(causal.resolve(id,access)==id)
                    }
                    "activity_event" -> {
                        require(operation.payload["event_type"] in setOf(JsonPrimitive("check_in"),JsonPrimitive("count_delta"),JsonPrimitive("count_snapshot")))
                        // The original complete readers checked the exact source/body/day policy.
                        sql.query("SELECT habitId FROM completions WHERE uuid=?",arrayOf(operation.entityUuid)).use { c ->
                            require(c.moveToFirst() && c.getLong(0) in byId && !c.moveToNext()) { "G0_MIXED_FOREIGN_FACT_MISSING" }
                        }
                    }
                    else -> error("G0_MIXED_UNSUPPORTED_FACT_DOMAIN")
                }
                require(admitted.add(id))
            }
        }
        require(admitted==queues.keys) { "G0_MIXED_FOREIGN_ORPHAN_SOURCE" }
        return admitted.toSet()
    }
}
