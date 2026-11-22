package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.ui.InFlightActions
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated interaction experiment. Active state is admission feedback, never a saved receipt.
 * Preparation belongs to the caller's lifetime; no credential or unverified DTO cache is added.
 */
@RunWith(AndroidJUnit4::class)
class G0PreparedInteractionTest : G0SealedDomainFixture() {
    override fun domainOpeningCallback() = G0SchemaOpeningCallback(true)

    private suspend fun prepare(): G0ShortMetricStore = withContext(Dispatchers.IO) {
        G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid).also {
            it.initialize(access(),trustedDomainSchema(true))
        }
    }
    private fun original() { storage.isolatedBuilder=null;storage.reopen() }

    @Test fun preparedPageStillRevalidatesUnderAckAndRejectsDuplicateClicks() = runBlocking<Unit> {
        register()
        var builds=0
        val preparation=async(Dispatchers.IO) { builds++;prepare() }
        val store=preparation.await()
        assertSame(store,preparation.await());assertEquals(1,builds)
        val first=requireNotNull(store.edit("First","",id(1800),millis))
        val (http,_)=channel { successReply(it) }
        val delivery=requireNotNull(store.send(http,access(),first.operationId))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        store.afterPrepareForTest={ if(currentCoroutineContext()[CoroutineName]?.name=="page-save") {
            entered.complete(Unit);release.await()
        } }
        val actions=InFlightActions();val saved=CompletableDeferred<G0ShortMetricStore.View>()
        val writeTrace=G0PhaseTrace(false);val ackTrace=G0PhaseTrace(false)
        val pageScope=CoroutineScope(currentCoroutineContext()+Dispatchers.IO+CoroutineName("page-save"))
        withContext(Dispatchers.Main) {
            actions.launch(pageScope,metric.id) {
                writeTrace.run { requireNotNull(preparation.await().edit("Second","",id(1801),millis+1)) }
                saved.complete(store.read(access()))
            }
            assertEquals(setOf(metric.id),actions.active.value)
            repeat(10) { actions.launch(pageScope,metric.id) { fail("Duplicate must not execute or queue") } }
        }
        entered.await();assertFalse(saved.isCompleted)
        assertEquals(NextOperationAcceptance.COMMITTED,ackTrace.run { store.acknowledge(delivery) })
        assertFalse(saved.isCompleted);release.complete(Unit)
        val view=saved.await();assertEquals("Second",view.name);assertEquals(1,view.pending)
        assertEquals(1,writeTrace.count("preparation_retry"))
        assertEquals(3,writeTrace.count("decrypt_access"));assertEquals(2,ackTrace.count("decrypt_access"))
        original();assertEquals(1,count("next_acceptances"));assertEquals(1,count("sync_outbox"))
        assertEquals(2,count("next_request_origins"))
    }

    @Test fun clickBeforePreparationCompletesShowsBusyAndCancellationDoesNotWrite() = runBlocking<Unit> {
        register()
        val owner=Job(currentCoroutineContext()[Job])
        val pageScope=CoroutineScope(Dispatchers.IO+owner)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val actions=InFlightActions();val saved=CompletableDeferred<Unit>()
        try {
            val preparation=pageScope.async { entered.complete(Unit);release.await();prepare() }
            entered.await()
            withContext(Dispatchers.Main) {
                actions.launch(pageScope,metric.id) {
                    preparation.await().edit("Cancelled","",id(1802),millis)
                    saved.complete(Unit)
                }
                assertEquals(setOf(metric.id),actions.active.value)
            }
            assertFalse(saved.isCompleted)
            owner.cancelAndJoin();release.complete(Unit)
            assertTrue(actions.active.value.isEmpty());assertFalse(saved.isCompleted)
            assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"))
            assertEquals(metric.name,db.metricDao().getMetricById(metric.id)!!.name)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun preparedStateCannotReplaceFreshFinalAuthentication() = runBlocking<Unit> {
        register();val store=prepare()
        store.beforeFinalForTest={ failDecrypt=true }
        val actions=InFlightActions();val result=CompletableDeferred<Result<Unit>>()
        var success=false
        actions.launch(this,metric.id) {
            result.complete(runCatching { store.edit("Rejected","",id(1803),millis);success=true })
        }
        assertTrue(result.await().isFailure);assertFalse(success)
        failDecrypt=false;original()
        assertEquals(metric.name,db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"))
    }

    @Test fun lostCommitResultNeverPublishesSuccessOrAutomaticallyRepeatsWrite() = runBlocking<Unit> {
        register();val store=prepare()
        store.beforeFinalForTest={ endFaults.failAfterNextEnd.set(true) }
        val actions=InFlightActions();val result=CompletableDeferred<Result<Unit>>()
        var success=false
        actions.launch(this,metric.id) {
            result.complete(runCatching { store.edit("Unknown","",id(1804),millis);success=true })
        }
        assertEquals("G0_COMMIT_RESULT_LOST",result.await().exceptionOrNull()?.message)
        assertFalse(success)
        assertEquals("G0_SHORT_RECOVERY_REQUIRED",rejected { store.edit("Retry","",id(1805),millis) }.message)
        original();assertEquals("Unknown",db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(1,count("sync_outbox"));assertEquals(1,count("next_request_origins"))
        assertEquals(id(1804),db.syncOutboxDao().getAll().single().operationId)
    }

    @Test fun authorityRevokedAfterPreparationPreventsLaterSubmission() = runBlocking<Unit> {
        register();val store=prepare()
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        rejected { store.edit("Stale preparation","",id(1806),millis) }
        original();assertEquals(metric.name,db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0,count("sync_outbox"));assertEquals(0,count("next_request_origins"))
    }
}
