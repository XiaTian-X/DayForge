package com.dayforge.data.repository

import android.database.Cursor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same closed file image, original versus closed-SQL candidate. All 39 persistent tables;
 * only actual generated shadow clocks are normalized, with type and execution-window checks.
 */
@RunWith(AndroidJUnit4::class)
open class G0ShortMetricDifferentialTest : G0SealedDomainFixture() {
    protected open val admitRecurringReads=false
    protected open val retainRecurringResults=false
    protected open suspend fun prepareComparison() { register() }
    private var initialOriginCount=0
    private var clockWindow: LongRange? = null
    private var replacementFromOriginal: String? = null
    private fun state(): Map<String,String> {
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            sql.query("SELECT * FROM `$table`").use { c ->
                val rows = mutableListOf<String>()
                while(c.moveToNext()) rows += (0 until c.columnCount).joinToString("|") { column ->
                    if(table == "sync_entity_state" && c.columnNames[column] == "updatedAt" &&
                        c.getString(c.getColumnIndexOrThrow("entityType")) == "metric" &&
                        c.getString(c.getColumnIndexOrThrow("entityUuid")) == metric.uuid && clockWindow != null) {
                        assertEquals(Cursor.FIELD_TYPE_INTEGER,c.getType(column)); assertTrue(c.getLong(column) in requireNotNull(clockWindow)); "SHADOW_CLOCK"
                    } else when(c.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> "N"
                        Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(column)}"
                        Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(column))}"
                        Cursor.FIELD_TYPE_STRING -> "S${nextRequestHash(c.getString(column).toByteArray())}"
                        else -> "B${nextRequestHash(c.getBlob(column))}"
                    }
                }
                nextRequestHash((c.columnNames.toList().toString()+rows.sorted().joinToString("\n")).toByteArray())
            }
        }
    }
    private suspend fun compare(label: String, operation: suspend (G0ShortMetricStore?) -> Unit) {
        prepareComparison()
        initialOriginCount=count("next_request_origins")
        val image = storage.snapshotClosedDatabase(); storage.reopen()
        val initial = state()
        val start = System.currentTimeMillis(); operation(null); clockWindow = start..System.currentTimeMillis()
        val reference = state()
        storage.restoreClosedDatabase(image); storage.reopen(); clockWindow = null
        assertEquals(initial,state())
        val candidate = G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,queueClock={millis},replacementIdentity={requireNotNull(replacementFromOriginal)},admitRecurringReads=admitRecurringReads,retainRecurringResults=retainRecurringResults)
        candidate.initialize(access(), trustedDomainSchema())
        val candidateStart = System.currentTimeMillis(); operation(candidate); clockWindow = candidateStart..System.currentTimeMillis()
        // Read final persistent output through an independent cold original connection, not a
        // privileged escape hatch in the sealed candidate. No further candidate use is allowed.
        storage.isolatedBuilder = null; storage.reopen()
        val actual = state(); assertEquals(39,actual.size); assertEquals(reference,actual)
        Log.i("G0ShortDifferential","case=$label tables=${actual.size} equal=true")
    }
    private suspend fun edit(candidate: G0ShortMetricStore?, name: String, operation: Int, at: Long) {
        if(candidate != null) candidate.edit(name,"",id(operation),at)
        else producer().write(local()) {
            val priorIds=db.syncOutboxDao().getAll().map { it.id }.toSet()
            db.metricDao().update(requireNotNull(db.metricDao().getMetricById(metric.id)).copy(name=name,description="",updatedAt=at))
            val created=db.syncOutboxDao().getAll().filter { it.id !in priorIds }
            assertTrue(created.size<=1)
            created.singleOrNull()?.let { queue ->
                assertEquals("metric",queue.recordType);assertEquals(metric.uuid,queue.entityUuid)
                db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET operationId=?,createdAt=? WHERE id=?",arrayOf<Any>(id(operation),millis,queue.id))
            }
        }
    }
    @Test fun originalAndCandidateWriteSendAckAndReplayHaveIdenticalPersistentOutput() = runBlocking<Unit> {
        val (http,_) = channel { successReply(it) }
        compare("write-send-ack-replay") { candidate ->
            edit(candidate,"Same definition",980,millis)
            val delivery = requireNotNull(if(candidate == null) sender(http).sendOperation(access(),id(980)) else candidate.send(http,access(),id(980)))
            val result = if(candidate == null) sender(http).acceptOperation(delivery) else candidate.acknowledge(delivery)
            assertEquals(NextOperationAcceptance.COMMITTED,result)
            assertEquals(NextOperationAcceptance.REPLAYED,if(candidate == null) sender(http).acceptOperation(delivery) else candidate.acknowledge(delivery))
        }
    }
    @Test fun noOpDefinitionPreservesOriginalNoOriginSemantics() = runBlocking<Unit> {
        compare("no-op-definition") { edit(it,metric.name,981,millis+1) }
        assertEquals(initialOriginCount,count("next_request_origins"))
    }
    @Test fun predecessorAckAndUnsentSuccessorHaveIdenticalOriginsAndPendingProjection() = runBlocking<Unit> {
        val (http,_) = channel { successReply(it) }
        compare("old-ack-newer-local") { candidate ->
            edit(candidate,"Earlier",982,millis)
            val delivery = requireNotNull(if(candidate == null) sender(http).sendOperation(access(),id(982)) else candidate.send(http,access(),id(982)))
            edit(candidate,"Later",983,millis+1)
            assertEquals(NextOperationAcceptance.COMMITTED,if(candidate == null) sender(http).acceptOperation(delivery) else candidate.acknowledge(delivery))
        }
    }
    @Test(timeout=180_000) fun successorRebaseAndAckMatchAllOriginalPersistentTables() = runBlocking<Unit> {
        val (http,_) = channel { input -> successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        compare("successor-rebase-ack") { candidate ->
            edit(candidate,"First",984,millis)
            val first=requireNotNull(if(candidate==null) sender(http).sendOperation(access(),id(984)) else candidate.send(http,access(),id(984)))
            edit(candidate,"Second",985,millis+1)
            assertEquals(NextOperationAcceptance.COMMITTED,if(candidate==null) sender(http).acceptOperation(first) else candidate.acknowledge(first))
            val second=requireNotNull(if(candidate==null) sender(http).sendOperation(access(),id(985)) else candidate.send(http,access(),id(985)))
            if(candidate==null) replacementFromOriginal=second.requestId
            assertEquals(replacementFromOriginal,second.requestId)
            assertEquals(NextOperationAcceptance.COMMITTED,if(candidate==null) sender(http).acceptOperation(second) else candidate.acknowledge(second))
        }
    }

}
