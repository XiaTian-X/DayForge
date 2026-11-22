package com.dayforge.data.repository

import android.database.Cursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Closed Room connection and a new runtime, same durable database/credentials. Not process death. */
@RunWith(AndroidJUnit4::class)
class G0RoomReentryTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    private lateinit var latestStore:G0ShortMetricStore
    private var failAfterNextDecryption=false
    private var pushes=0
    override fun tokenCipher():com.dayforge.data.local.TokenCipher {
        val real=super.tokenCipher()
        return object:com.dayforge.data.local.TokenCipher {
            override fun encrypt(value:String)=real.encrypt(value)
            override fun decrypt(value:String):String? {
                val result=real.decrypt(value)
                if(failAfterNextDecryption) { failAfterNextDecryption=false;failDecrypt=true }
                return result
            }
        }
    }
    private val owners=mutableListOf<CoroutineScope>()
    private val ports=mutableListOf<G0MetricInteractionAdapter>()
    private suspend fun port():G0MetricInteractionAdapter {
        val bound=access();val connection=db;val gate=domainGate
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO);owners+=owner
        val runtime=G0DeviceBoundMetricRuntime(bound,owner) {
            G0ShortMetricStore(connection,tokens,sessions,authority,gate,metric.uuid).also {
                it.initialize(bound,trustedDomainSchema(true));latestStore=it
            }
        }
        val (http,_)=channel { input ->
            if(input.path.endsWith("/push")) pushes++
            successReply(input,revision=(if(input.path.endsWith("/identity")) 0L else
            wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L)+1) }
        return G0MetricInteractionAdapter(tokens,bound,authority,gate,http,owner,runtime).also { ports+=it }
    }
    private suspend fun reopen() {
        ports.forEach { it.closePresentation() }
        owners.forEach { it.coroutineContext.job.cancelAndJoin() }
        val previous=db
        freshGateForColdOpen();storage.reopen()
        assertFalse(previous.isOpen);assertNotSame(previous,db);assertFalse(db.isOpen)
    }
    private fun original() { storage.isolatedBuilder=null;storage.reopen() }
    private fun state():Map<String,String> {
        val sql=db.openHelper.writableDatabase
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table -> sql.query("SELECT * FROM `$table`").use { c ->
            val rows=buildList { while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i -> when(c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> "N"
                Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                Cursor.FIELD_TYPE_STRING -> "S${nextRequestHash(c.getString(i).toByteArray())}"
                else -> "B${nextRequestHash(c.getBlob(i))}"
            } }) }
            nextRequestHash((c.columnNames.toList().toString()+rows.sorted().joinToString("\n")).toByteArray())
        } }
    }
    @After fun stopOwners()=runBlocking<Unit> {
        ports.forEach { it.closePresentation() };owners.forEach { it.coroutineContext.job.cancelAndJoin() }
    }
    @Test fun pendingPredecessorAndSuccessorResumeAfterConnectionAndOwnerClose()=runBlocking<Unit> {
        register();val first=editMetric("Pending before open")
        val old=port();val oldView=old.open();assertTrue(oldView.valid())
        old.save("Latest before close","",id(6101))
        val held=old.refresh();assertEquals(2,held.value.pending)
        reopen();assertFalse(held.valid())
        try { old.refresh();fail("Closed owner accepted a read") } catch(expected:CancellationException) { assertTrue(owners.first().coroutineContext.job.isCancelled) }
        val fresh=port();val recovered=fresh.open()
        assertTrue(recovered.valid());assertEquals("Latest before close",recovered.value.name);assertEquals(2,recovered.value.pending)
        val synced=fresh.sync();assertTrue(synced.valid());assertEquals(0,synced.value.pending);assertEquals("Latest before close",synced.value.name)
        original();assertEquals(3,count("next_request_origins"));assertEquals(2,count("next_acceptances"))
        assertEquals(1,count("next_structural_supersessions"));assertEquals(0,count("sync_outbox"))
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,first.operationId))
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id(6101)))
    }
    @Test fun lostCommitResultRecoversOnePendingCommandWithoutReplayingTheWrite()=runBlocking<Unit> {
        register();val old=port();old.open()
        latestStore.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        val failure=rejected { old.save("Unknown but durable","",id(6102)) }
        assertTrue(failure.toString(),generateSequence(failure) { it.cause }.any { it.message=="G0_COMMIT_RESULT_LOST" })
        reopen();val fresh=port();val recovered=fresh.open()
        assertEquals("Unknown but durable",recovered.value.name);assertEquals(1,recovered.value.pending)
        assertEquals(0,fresh.sync().value.pending)
        original();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
        assertEquals("Unknown but durable",db.metricDao().getMetricById(metric.id)!!.name)
    }
    @Test fun changedAcceptedSourceAfterCloseCannotReuseOldProofOrChangePersistentState()=runBlocking<Unit> {
        register();val row=editMetric("Accepted original");val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(sender(http).sendOperation(access(),row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED,sender(http).acceptOperation(delivery))
        val old=port();val held=old.open();assertTrue(held.valid());reopen();assertFalse(held.valid())
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET sourceHash='changed-after-close'")
        val before=state();val fresh=port();rejected { fresh.open() }
        original();assertEquals(before,state())
    }
    @Test fun reopenedOwnerDoesNotReuseCredentialAuthorityFromBeforeClose()=runBlocking<Unit> {
        register();editMetric("Pending original");val old=port();old.open();reopen()
        val before=state();val fresh=port()
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        rejected { fresh.open() }
        original();assertEquals(before,state());assertEquals(1,count("sync_outbox"))
    }
    @Test fun restoredSyncPlanRequiresEntryAndFinalRealDecryption()=runBlocking<Unit> {
        register();editMetric("Fresh auth required");reopen();val fresh=port();fresh.open()
        val trace=G0PhaseTrace(driverTiming=false)
        val current=access()
        val plan=trace.run { latestStore.pendingSyncPlan(current) }
        assertEquals(1,plan.size);assertEquals(2,trace.count("decrypt_access"))
        failAfterNextDecryption=true
        val before=decryptCount.get();rejected { fresh.sync() }
        assertEquals(2,decryptCount.get()-before);assertEquals(0,pushes)
        failDecrypt=false;original();assertEquals(1,count("sync_outbox"));assertEquals(0,count("next_transmissions"));assertEquals(0,count("next_acceptances"))
    }
    @Test fun authorityChangeAfterPlanCannotAuthorizeSend()=runBlocking<Unit> {
        register();editMetric("No cached send authority");reopen();val fresh=port();fresh.open()
        fresh.afterSyncPlanForTest={ tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        rejected { fresh.sync() };assertEquals(0,pushes)
        original();assertEquals(1,count("sync_outbox"));assertEquals(0,count("next_transmissions"));assertEquals(0,count("next_acceptances"))
    }
    @Test fun syncPlanDoesNotSilentlyConsumeNewWritesAfterCapture()=runBlocking<Unit> {
        register();editMetric("Captured pending");reopen();val fresh=port();fresh.open()
        fresh.afterSyncPlanForTest={ latestStore.edit("Arrived after capture","",id(6103)) }
        assertEquals("G0_INTERACTION_PENDING_REMAINS",rejected { fresh.sync() }.message)
        assertEquals(1,pushes)
        val remaining=fresh.refresh();assertEquals("Arrived after capture",remaining.value.name);assertEquals(1,remaining.value.pending)
        fresh.afterSyncPlanForTest={}
        assertEquals(0,fresh.sync().value.pending);assertEquals(2,pushes)
        original();assertEquals(3,count("next_request_origins"));assertEquals(2,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }

    @Test fun lostAckCommitResultReopensWithNoPendingAndDoesNotSendAgain()=runBlocking<Unit> {
        register();val old=port();old.open();old.save("Accepted but result lost","",id(6104))
        latestStore.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { old.sync() }.message)
        assertEquals(1,pushes);reopen();val fresh=port();assertEquals(0,fresh.open().value.pending)
        assertEquals(0,fresh.sync().value.pending);assertEquals(1,pushes)
        original();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }
    @Test fun reopenedSessionCanSyncSavedContentButCannotDiscardUnsavedDraft()=runBlocking<Unit> {
        register();editMetric("Restored saved value");reopen();val fresh=port()
        val session=G0InteractionSession(fresh,owners.last())
        suspend fun phase(value:G0InteractionSession.Phase)=withTimeout(10_000) {
            session.state.first { it.phase==value }
        }
        try {
            withContext(Dispatchers.Main) { session.open() };phase(G0InteractionSession.Phase.EDITING);session.awaitSettled()
            assertTrue(session.state.value.canSync())
            withContext(Dispatchers.Main) { session.draft("Uncommitted draft","");assertFalse(session.sync()) }
            assertFalse(session.state.value.canSync());assertEquals(0,pushes)
            withContext(Dispatchers.Main) { session.draft("Restored saved value",session.state.value.publication!!.value.description);assertTrue(session.sync()) }
            phase(G0InteractionSession.Phase.SYNCED);session.awaitSettled();assertEquals(1,pushes)
            assertTrue(session.events.none { it.kind=="committed" })
        } finally { withContext(Dispatchers.Main) { session.detach() };session.awaitSettled() }
        original();assertEquals(1,count("next_request_origins"));assertEquals(1,count("next_acceptances"));assertEquals(0,count("sync_outbox"))
    }

}
