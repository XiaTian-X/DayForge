package com.dayforge.data.repository

import android.database.Cursor
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same corrupted disk image and response go through the original ACK and candidate cold+ACK.
 * Every listed case must reject and preserve every persistent table; two rejections alone are
 * not a pass if the implementation has written a receipt, changed a source, or deleted evidence. */
@RunWith(AndroidJUnit4::class)
class G0PureColdRejectionMatrixTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)
    @Test(timeout=240_000) fun originAndTransmissionRejectionsMatchOriginalWithoutPersistentEffects() = matrix(listOf(
        "owner","origin-hash","origin-protocol","transmission-owner","transmission-device","transmission-hash","queue-source","queue-identity"))
    @Test(timeout=240_000) fun dependenciesAndStructureRejectionsMatchOriginalWithoutPersistentEffects() = matrix(listOf(
        "dependency-hash","dependency-order","dependency-device","dependency-cycle","uninitialized","duplicate-name","deleted-shadow","receipt-orphan-source"))
    @Test(timeout=240_000) fun malformedAndMisboundResponsesMatchOriginalWithoutPersistentEffects() = matrix(listOf(
        "response-id","response-entity","response-type","response-revision","response-missing","response-extra","response-error","response-proof"))

    private fun state(): Map<String,String> {
        val sql=db.openHelper.writableDatabase
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table -> sql.query("SELECT * FROM `$table`").use { c ->
            val rows=mutableListOf<String>()
            while(c.moveToNext()) rows += (0 until c.columnCount).joinToString("|") { i -> when(c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> "N"
                Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                Cursor.FIELD_TYPE_STRING -> "S${nextRequestHash(c.getString(i).toByteArray())}"
                else -> "B${nextRequestHash(c.getBlob(i))}"
            } }
            nextRequestHash((c.columnNames.toList().toString()+rows.sorted().joinToString("\n")).toByteArray())
        } }
    }
    private suspend fun failure(block:suspend ()->Unit):Throwable? = try { block();null } catch(e:Throwable) {
        if(e is CancellationException) throw e
        e
    }
    private fun matrix(cases:List<String>) = runBlocking<Unit> {
        register();val row=editMetric("Frozen target")
        val (http,_)=channel { successReply(it) }
        val original=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        val image=storage.snapshotClosedDatabase()
        for(case in cases) {
            storage.restoreClosedDatabase(image);storage.isolatedBuilder=null;storage.reopen()
            var delivery=original
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                when(case) {
                    "owner" -> sql.execSQL("UPDATE next_request_origins SET accountId=?",arrayOf(id(501)))
                    "origin-hash" -> sql.execSQL("UPDATE next_request_origins SET sourceHash='wrong'")
                    "origin-protocol" -> sql.execSQL("UPDATE next_request_origins SET protocol=4")
                    "transmission-owner" -> sql.execSQL("UPDATE next_transmissions SET accountId=?",arrayOf(id(501)))
                    "transmission-device" -> sql.execSQL("UPDATE next_transmissions SET deviceId=?",arrayOf(id(502)))
                    "transmission-hash" -> sql.execSQL("UPDATE next_transmissions SET wireHash='wrong'")
                    "queue-source" -> sql.execSQL("UPDATE sync_outbox SET createdAt=createdAt+1")
                    "queue-identity" -> sql.execSQL("UPDATE sync_outbox SET operationId=?",arrayOf(id(503)))
                    "dependency-hash" -> sql.execSQL("UPDATE next_structural_dependencies SET originHash='wrong'")
                    "dependency-order" -> sql.execSQL("UPDATE next_structural_dependencies SET logicalOrder=logicalOrder+100")
                    "dependency-device" -> sql.execSQL("UPDATE next_structural_dependencies SET capturedDeviceId=?",arrayOf(id(504)))
                    "dependency-cycle" -> sql.execSQL("UPDATE next_structural_dependencies SET predecessorId=operationId,predecessorOriginHash=originHash,predecessorDependencyHash='wrong'")
                    "uninitialized" -> sql.execSQL("UPDATE metrics SET appearance=NULL WHERE id=?",arrayOf(metric.id))
                    "duplicate-name" -> {
                        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                        db.metricDao().insert(metric.copy(id=0,uuid=id(505),name="Frozen target"))
                        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                    }
                    "deleted-shadow" -> db.syncOutboxDao().upsertState(com.dayforge.data.local.entity.SyncEntityStateEntity(
                        "metric",metric.uuid,1,true,null,null,millis))
                }
            }
            if(case=="receipt-orphan-source") {
                assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(original))
                db.syncOutboxDao().insert(row)
            }
            val result=original.result.results.single()
            val changed=when(case) {
                "response-id" -> result.copy(operationId=id(506))
                "response-entity" -> result.copy(entityUuid=id(507))
                "response-type" -> result.copy(entityType="plan_node")
                "response-revision" -> result.copy(revision=0)
                "response-missing" -> result.copy(entity=null)
                "response-extra" -> result.copy(entity=JsonObject(requireNotNull(result.entity)+("unexpected" to JsonPrimitive(true))))
                "response-error" -> result.copy(errorCode="UNEXPECTED")
                else -> result
            }
            delivery=delivery.copy(result=original.result.copy(results=listOf(changed)))
            if(case=="response-proof") delivery=delivery.copy(transmissionProof="wrong")
            val before=state();val corrupted=storage.snapshotClosedDatabase();storage.reopen()
            val reference=failure { sender(http).acceptOperation(delivery) }
            assertNotNull("$case original must reject",reference)
            assertEquals("$case original leaves all tables unchanged",before,state())
            storage.restoreClosedDatabase(corrupted)
            freshGateForColdOpen()
            storage.isolatedBuilder={ context -> androidx.room.Room.databaseBuilder(context,com.dayforge.data.local.HabitDatabase::class.java,"habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build() }
            storage.reopen()
            val candidate=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,prepareColdInputs=true)
            val actual=failure { candidate.initialize(access(),trustedDomainSchema());candidate.acknowledge(delivery) }
            assertNotNull("$case candidate must reject",actual)
            storage.isolatedBuilder=null;storage.reopen()
            assertEquals("$case candidate leaves all tables unchanged",before,state())
            Log.i("G0ColdReject","case=$case original=${reference!!.javaClass.simpleName} candidate=${actual!!.javaClass.simpleName} unchanged=true")
        }
    }
    @Test(timeout=240_000) fun acceptedHistoryRejectionMatrixMatchesOriginalProofWithoutWriting() = runBlocking<Unit> {
        register();val row=editMetric("Accepted target")
        val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        val image=storage.snapshotClosedDatabase()
        for(case in listOf("owner","native-protocol","wire","device","receipt-origin","receipt-result","normalized-result","reinserted-source")) {
            storage.restoreClosedDatabase(image);storage.isolatedBuilder=null;storage.reopen()
            db.withTransaction {
                val sql=db.openHelper.writableDatabase
                when(case) {
                    "owner" -> sql.execSQL("UPDATE next_request_origins SET accountId=?",arrayOf(id(521)))
                    "native-protocol" -> sql.execSQL("UPDATE next_request_origins SET protocol=5.5")
                    "wire" -> sql.execSQL("UPDATE next_transmissions SET wireHash='wrong'")
                    "device" -> sql.execSQL("UPDATE next_transmissions SET deviceId=?",arrayOf(id(522)))
                    "receipt-origin" -> sql.execSQL("UPDATE next_acceptances SET originHash='wrong'")
                    "receipt-result" -> sql.execSQL("UPDATE next_acceptances SET resultHash='wrong'")
                    "normalized-result" -> {
                        val bytes=com.dayforge.data.api.encodeSyncRequest(NextSyncOperationResult.serializer(),
                            delivery.result.results.single().copy(entity=null))
                        sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=?",arrayOf(bytes.toString(Charsets.UTF_8),nextRequestHash(bytes)))
                    }
                    "reinserted-source" -> db.syncOutboxDao().insert(row)
                }
            }
            val before=state();val corrupted=storage.snapshotClosedDatabase();storage.reopen()
            val current=access()
            assertNotNull("$case original accepted proof must reject",failure { db.withTransaction {
                NextStructuralCausalStore(db).acceptedOperationProof(row.operationId,current)
            } })
            assertEquals(before,state())
            storage.restoreClosedDatabase(corrupted);freshGateForColdOpen()
            storage.isolatedBuilder={ context -> androidx.room.Room.databaseBuilder(context,com.dayforge.data.local.HabitDatabase::class.java,"habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build() }
            storage.reopen()
            val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid,prepareColdInputs=true)
            assertNotNull("$case candidate accepted baseline must reject",failure { store.initialize(current,trustedDomainSchema()) })
            storage.isolatedBuilder=null;storage.reopen();assertEquals(before,state())
            Log.i("G0ColdReject","accepted_history=$case original_rejected=true candidate_rejected=true unchanged=true")
        }
    }

}
