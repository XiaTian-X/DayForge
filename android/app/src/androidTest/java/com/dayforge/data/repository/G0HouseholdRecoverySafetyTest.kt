package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.HabitDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

abstract class G0HouseholdRecoveryFixture:G0HouseholdHistoryFixture() {
    internal suspend fun trustedRecoverySchema():G0RecoverySchema=withContext(Dispatchers.IO) {
        val reference=Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java)
            .addCallback(domainOpeningCallback()).build()
        val observer=object:InvalidationTracker.Observer(G0DomainSqlCatalog.columns.keys.toTypedArray()) {
            override fun onInvalidated(tables:Set<String>) {}
        }
        var registered=false
        try {
            val sql=reference.openHelper.writableDatabase
            val baseline=reference.withTransaction {G0DomainJournal.trustedSchema(sql)}
            reference.invalidationTracker.addObserver(observer);registered=true
            val subscribed=reference.withTransaction {G0DomainJournal.trustedSchema(sql)}
            check(subscribed.filter {it.first()=="main"}==baseline.filter {it.first()=="main"})
            G0RecoverySchema(baseline.filter {it.first()=="main"},baseline.filter {it.first()=="temp"}.toSet(),
                (baseline+subscribed).filter {it.first()=="temp"}.toSet())
        } finally {
            if(registered)reference.invalidationTracker.removeObserver(observer)
            reference.close()
        }
    }
    internal suspend fun originalRecoveryConnection(
        roomContext:CoroutineContext?=null,
        decorate:(SupportSQLiteOpenHelper.Factory)->SupportSQLiteOpenHelper.Factory):HabitDatabase {
        freshGateForColdOpen()
        val previous=storage.isolatedBuilder
        storage.isolatedBuilder={context->Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
            .addCallback(domainOpeningCallback()).openHelperFactory(decorate(domainGate.factory))
            .apply {roomContext?.let {setQueryCoroutineContext(it)}}.build()}
        return try {storage.reopen()} finally {storage.isolatedBuilder=previous}
    }
    internal inner class RecoveryHarness(val http:NextSyncHttp,shared:G0CountConnectionOwner?=null) {
        val job=SupervisorJob();val control=CoroutineScope(job+Dispatchers.IO)
        val closes=AtomicInteger();var beforeClose:suspend ()->Unit={}
        val connection=shared ?: G0CountConnectionOwner {beforeClose();db.close();closes.incrementAndGet()}
        lateinit var store:G0HouseholdCountStore
        val runtime=G0HouseholdCountRuntime(control,connection,authority,tokens,{domainGate.changeSequence},http) {
            coldHousehold(http).also {store=it}
        }
        suspend fun open():G0HouseholdCountPage {
            assertTrue(runtime.openPage());val page=householdPage(runtime)
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            return page
        }
        suspend fun recovery()=G0HouseholdRecovery(runtime,connection,tokens,sessions,authority,http,
            trustedRecoverySchema(),::originalRecoveryConnection)
        suspend fun finish()=withContext(NonCancellable) {runtime.shutdown();job.cancelAndJoin()}
    }
    internal suspend fun save(page:G0HouseholdCountPage,id:String):G0HouseholdCountStore.Saved {
        assertTrue(page.increment(id));householdPagePhase(page,G0HouseholdCountPage.Phase.RESULT);page.settled()
        assertNull(page.error);return requireNotNull(page.saved)
    }
    internal suspend fun failed(runtime:G0HouseholdCountRuntime) {
        withTimeout(20_000) {runtime.state.first {it.phase==G0HouseholdCountRuntime.Phase.RECOVERY}}
    }
    internal suspend fun freshTerminal(http:NextSyncHttp,shared:G0CountConnectionOwner,total:Int) {
        val next=RecoveryHarness(http,shared)
        try {
            val page=next.open();withTimeout(30_000) {next.runtime.awaitSynced()}
            withTimeout(20_000) {page.state.first {it.view?.valid()==true}}
            val result=requireNotNull(page.state.value.view)
            assertEquals(total,result.cards.values.sumOf {it.history.completions.size})
            assertEquals(total,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
            val before=pageNativeState();assertTrue(next.runtime.shutdown());reopenReference()
            sameHouseholdAsOriginal(result);assertEquals(before,pageNativeState())
        } finally {next.finish()}
    }
}

@RunWith(AndroidJUnit4::class)
class G0HouseholdRecoverySafetyTest:G0HouseholdRecoveryFixture() {
    @Test fun nineUnsentRequestsAreDiscoveredFromDiskByNewRuntimeInEightThenOneBatches()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)}
        val old=RecoveryHarness(http)
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();val saved=mutableListOf<G0HouseholdCountStore.Saved>()
            repeat(9) {saved+=save(page,seed.habits[it%2].uuid)}
            assertEquals(9,pageRowCount("sync_outbox"));assertEquals(0,server.requests.count {it.path.endsWith("/push")})
            assertTrue(old.runtime.shutdown());assertFalse(old.connection.busy())
            val next=RecoveryHarness(http,old.connection);val batches=mutableListOf<List<String>>()
            next.runtime.beforeSyncBatchForTest={synchronized(batches) {batches+=it.toList()}}
            try {
                next.open();withTimeout(40_000) {next.runtime.awaitSynced()}
                assertEquals(listOf(8,1),synchronized(batches) {batches.map {it.size}})
                assertEquals(saved.map {it.requestId},next.runtime.workerAttempts.map {it.requestId})
                assertEquals(9,server.requests.count {it.path.endsWith("/push")})
                assertEquals(17,pageRowCount("completions"));assertEquals(17,pageRowCount("next_acceptances"))
                assertEquals(0,pageRowCount("sync_outbox"));assertTrue(next.runtime.knownPending.isEmpty())
                assertTrue(next.runtime.shutdown())
            } finally {next.finish()}
            freshTerminal(http,old.connection,17)
        } finally {old.finish()}
    }

    @Test fun frozenBeforeHttpCannotColdOpenButExplicitRecoveryUsesOriginalDiskIdentityAndBytes()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};val old=RecoveryHarness(http)
        try {
            old.runtime.beforeSendForTest={endFaults.failAfterNextEndIn(it.send)}
            val page=old.open();assertTrue(page.increment(seed.habits[0].uuid));failed(old.runtime)
            val id=requireNotNull(page.command).requestId
            assertTrue(old.runtime.shutdown());reopenReference()
            val frozen=pageNativeState()
            assertEquals("G0_COUNT_COLD_INFLIGHT_UNSUPPORTED",runCatching {openHousehold(http)}.exceptionOrNull()?.message)
            assertEquals(frozen,pageNativeState())
            val recovery=old.recovery();val result=recovery.run()
            assertEquals("G0_COMMIT_RESULT_LOST",old.runtime.error?.message)
            assertTrue(old.runtime.drained && recovery.drained);assertFalse(old.connection.busy())
            assertEquals(listOf(G0HouseholdRecovery.Result(id,NextOperationAcceptance.COMMITTED)),result.results)
            assertEquals(9,result.facts);assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            reopenReference()
            val wire=requireNotNull(db.nextRequestDao().transmission(NEXT_OPERATION,id)).wireBytes
            assertArrayEquals(wire,server.requests.single {it.path.endsWith("/push")}.body)
            assertEquals(id,wireOperation(server.requests.single {it.path.endsWith("/push")}).getValue("operation_id").jsonPrimitive.content)
            freshTerminal(http,old.connection,9)
        } finally {old.finish()}
    }

    @Test fun lostHttpResponseReplaysExactRequestAndIdempotentServerAppliesOnlyOnce()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val applied=ConcurrentHashMap<String,List<Byte>>()
        val applications=AtomicInteger()
        val (http,server)=channel {input->
            if(input.path.endsWith("/identity"))reply(input) else {
                val id=wireOperation(input).getValue("operation_id").jsonPrimitive.content
                val prior=applied.putIfAbsent(id,input.body.toList())
                if(prior==null) {
                    applications.incrementAndGet()
                    val response=successReply(input)
                    response.copy(length=response.bytes.size+1,allowClientClose=true)
                } else {
                    check(prior==input.body.toList())
                    val json=Json.parseToJsonElement(successReply(input).bytes.toString(Charsets.UTF_8)).jsonObject
                    val result=json.getValue("results").jsonArray.single().jsonObject
                    MaterialSocketServer.Reply(buildJsonObject {put("results",JsonArray(listOf(
                        JsonObject(result+("status" to JsonPrimitive("already_applied"))))))}.toString().toByteArray())
                }
            }
        }
        val old=RecoveryHarness(http)
        try {
            val page=old.open();assertTrue(page.increment(seed.habits[1].uuid));failed(old.runtime)
            assertEquals(1,applications.get());assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            val id=requireNotNull(page.command).requestId
            val result=old.recovery().run()
            assertEquals(listOf(G0HouseholdRecovery.Result(id,NextOperationAcceptance.COMMITTED)),result.results)
            val pushes=server.requests.filter {it.path.endsWith("/push")}
            assertEquals(2,pushes.size);assertArrayEquals(pushes[0].body,pushes[1].body);assertEquals(1,applications.get())
            freshTerminal(http,old.connection,9)
        } finally {old.finish()}
    }

    @Test fun unknownAckUsesOnlyRequestHintAndDiskReceiptWithoutAnotherHttp()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};val old=RecoveryHarness(http)
        try {
            old.runtime.beforeAcceptForTest={endFaults.failAfterNextEndIn(it.ack)}
            val page=old.open();assertTrue(page.increment(seed.habits[0].uuid));failed(old.runtime)
            val id=requireNotNull(page.command).requestId
            val before=pageNativeState();val result=old.recovery().run(id)
            assertEquals("G0_COMMIT_RESULT_LOST",old.runtime.error?.message)
            assertEquals(listOf(G0HouseholdRecovery.Result(id,NextOperationAcceptance.REPLAYED)),result.results)
            assertEquals(before,pageNativeState());assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            freshTerminal(http,old.connection,9)
        } finally {old.finish()}
    }

    @Test fun wrongHintAndChangedUnsentSourceCannotAuthorizeRecovery()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};val old=RecoveryHarness(http)
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();val saved=save(page,seed.habits[0].uuid)
            val before=pageNativeState();val missing=old.recovery()
            assertEquals("G0_RECOVERY_HINT_UNKNOWN",runCatching {missing.run(id(49921))}.exceptionOrNull()?.message)
            assertEquals(before,pageNativeState());assertTrue(missing.drained);assertTrue(server.requests.none {it.path.endsWith("/push")})
            reopenReference()
            mutateHouseholdFile("UPDATE sync_outbox SET createdAt=createdAt+1 WHERE operationId=?",arrayOf(saved.requestId))
            val damaged=pageNativeState();val changed=old.recovery()
            val failure=runCatching {changed.run()}.exceptionOrNull()
            assertTrue(failure is NextRequestException)
            assertEquals(NextRequestException.Reason.SOURCE_CHANGED,(failure as NextRequestException).reason)
            assertEquals(damaged,pageNativeState());assertTrue(changed.drained)
            assertTrue(server.requests.none {it.path.endsWith("/push")})
        } finally {old.finish()}
    }

    @Test fun recoveryWaitsForActualOldTaskAndCloseFailureKeepsConnectionQuarantined()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val old=RecoveryHarness(http)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            old.runtime.beforeSyncBatchForTest={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
            val page=old.open();save(page,seed.habits[0].uuid);withTimeout(15_000) {entered.await()}
            val recovery=old.recovery();val work=async(Dispatchers.IO) {recovery.run()}
            try {
                yield();delay(50)
                assertFalse(work.isCompleted);assertFalse(recovery.opened);assertTrue(old.connection.busy())
            } finally {release.complete(Unit)}
            withTimeout(30_000) {work.await()};assertTrue(recovery.drained)
            val next=RecoveryHarness(http,old.connection)
            try {
                next.open()
                old.beforeClose={error("G0_RECOVERY_TEST_CLOSE_FAILED")}
                val blocked=next.recovery()
                assertEquals("G0_RECOVERY_PREVIOUS_NOT_DRAINED",runCatching {blocked.run()}.exceptionOrNull()?.message)
                assertFalse(blocked.opened);assertTrue(old.connection.busy())
            } finally {next.finish()}
        } finally {release.complete(Unit);old.beforeClose={};old.finish();db.close()}
    }

    @Test fun original401RefreshRevokesRecoveryWithoutWaitingForItsOwnCommitPermit()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val refreshes=AtomicInteger()
        val (http,server)=channel {input->when {
            input.path.endsWith("/identity")->reply(input)
            input.path.endsWith("/auth/refresh")->{refreshes.incrementAndGet();MaterialSocketServer.Reply(
                """{"access_token":"fresh-access","refresh_token":"fresh-refresh","user_id":"${id(1)}","username":"member","is_admin":false}""".toByteArray())}
            else->MaterialSocketServer.Reply("{}".toByteArray(),status=401)
        }}
        val old=RecoveryHarness(http)
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();val saved=save(page,seed.habits[0].uuid)
            val recovery=old.recovery()
            val outcome=withTimeout(20_000) {runCatching {recovery.run()}}
            assertTrue(outcome.isFailure);assertEquals(1,refreshes.get())
            assertTrue(recovery.drained && old.runtime.drained);assertFalse(old.connection.busy())
            assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            assertEquals(8,pageRowCount("next_acceptances"))
            assertTrue(server.requests.filter {it.path.endsWith("/push")}.all {
                wireOperation(it).getValue("operation_id").jsonPrimitive.content==saved.requestId
            })
            assertEquals("G0_RECOVERY_ALREADY_ATTEMPTED",runCatching {recovery.run()}.exceptionOrNull()?.message)
        } finally {old.finish()}
    }

    @Test fun reservationBeforeActualOuterSuccessRollsBackOriginalAck()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};val old=RecoveryHarness(http)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val armed=AtomicBoolean(true)
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();save(page,seed.habits[1].uuid)
            val recovery=old.recovery()
            recovery.beforeOuterSuccessForTest={
                if(count("next_acceptances")==9 && armed.compareAndSet(true,false)) {
                    entered.countDown();check(release.await(15,TimeUnit.SECONDS))
                }
            }
            val work=async(Dispatchers.IO) {runCatching {recovery.run()}}
            try {
                assertTrue(withContext(Dispatchers.IO) {entered.await(15,TimeUnit.SECONDS)})
                withTimeout(10_000) {tokens.saveDeviceRegistration(id(4),caps,true,2)}
                repeat(2) {observation->
                    assertEquals("Only the previous owner may have closed while the real transaction is blocked",1,old.closes.get())
                    assertFalse("Recovery must await the actual transaction child",work.isCompleted)
                    assertFalse("Cancellation delivery cannot drain the recovery connection",recovery.drained)
                    assertTrue("The connection ticket must remain owned until actual rollback",old.connection.busy())
                    if(observation==0)delay(50)
                }
            } finally {release.countDown()}
            assertTrue(withTimeout(20_000) {work.await()}.isFailure)
            assertTrue(recovery.drained)
            assertEquals("Old owner and recovered connection must each close once",2,old.closes.get())
            assertFalse(old.connection.busy())
            assertEquals(9,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
            assertEquals(8,pageRowCount("next_acceptances"));assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            val denial=recovery.error
            // Reservation explicitly cancels the caller before the native final
            // permit rejects. Room may deliver that exact cancellation first; the
            // real rollback/close assertions above remain required in either case.
            val authorityCancelled=denial is CancellationException &&
                denial.javaClass==CancellationException::class.java && denial.message=="G0_RECOVERY_AUTHORITY_CHANGED"
            assertTrue("Expected authority denial or its explicit reservation cancellation, got ${denial?.javaClass?.name}: ${denial?.message}; "+
                "cause=${denial?.cause?.javaClass?.name}: ${denial?.cause?.message}",denial is G0AuthorityOwner.Denied || authorityCancelled)
        } finally {release.countDown();old.finish()}
    }

    @Test fun caughtNestedEndFailureCannotBypassTheOuterCommitPermit()=runBlocking<Unit> {
        seedHouseholdAccepted()
        val fault=G0CommitFaultFactory {it.inTransaction()}
        val schema=trustedRecoverySchema()
        originalRecoveryConnection {G0RecoveryCommitFactory(G0TimingFactory(fault),authority,authority.lease(),schema)}
        val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false)
        withContext(Dispatchers.IO) {trace.run {
            val sql=db.openHelper.writableDatabase
            sql.beginTransaction()
            try {
                sql.execSQL("UPDATE habits SET name='uncommitted nested fixture' WHERE id=?",arrayOf<Any>(habit.id))
                sql.beginTransaction();sql.setTransactionSuccessful();fault.failAfterNextEndIn(trace)
                assertEquals("G0_COMMIT_RESULT_LOST",runCatching {sql.endTransaction()}.exceptionOrNull()?.message)
                assertEquals("G0_RECOVERY_TRANSACTION_UNKNOWN",runCatching {sql.setTransactionSuccessful()}.exceptionOrNull()?.message)
            } finally {sql.endTransaction()}
        }}
        db.close();assertEquals(before,pageNativeState())
        withTimeout(5_000) {tokens.saveDeviceRegistration(id(4),caps,true,2)}
    }

    @Test fun permitAlreadyTakenOrdersActualAckEndBeforeCredentialPersistence()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,_)=channel {successReply(it)};val old=RecoveryHarness(http)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val armed=AtomicBoolean(true)
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();save(page,seed.habits[0].uuid)
            val recovery=old.recovery()
            recovery.beforeOuterEndForTest={
                if(count("next_acceptances")==9 && armed.compareAndSet(true,false)) {
                    entered.countDown();check(release.await(15,TimeUnit.SECONDS))
                }
            }
            val work=async(Dispatchers.IO) {runCatching {recovery.run()}}
            try {
                assertTrue(withContext(Dispatchers.IO) {entered.await(15,TimeUnit.SECONDS)})
                val reserved=CompletableDeferred<Unit>()
                authority.onTransitionForTest={reserved.complete(Unit)}
                val mutation=async(Dispatchers.IO) {tokens.saveDeviceRegistration(id(4),caps,true,2)}
                try {
                    withTimeout(10_000) {reserved.await()}
                    assertFalse(mutation.isCompleted);assertFalse(work.isCompleted)
                    release.countDown();withTimeout(20_000) {mutation.await()}
                } finally {release.countDown();authority.onTransitionForTest={}}
            } finally {release.countDown()}
            assertTrue(withTimeout(20_000) {work.await()}.isFailure)
            assertTrue(recovery.drained);assertEquals(9,pageRowCount("next_acceptances"))
            assertEquals(0,pageRowCount("sync_outbox"));assertEquals(9,pageRowCount("completions"))
        } finally {release.countDown();authority.onTransitionForTest={};old.finish()}
    }

    @Test fun realRoomInvalidationFailureIsReturnedOnlyAfterItsTransactionEndsAndConnectionCloses()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val (http,server)=channel {successReply(it)};val old=RecoveryHarness(http)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val armed=AtomicBoolean();val injected=AtomicBoolean();val ended=AtomicBoolean()
        val fault=IllegalStateException("G0_RECOVERY_INVALIDATION_FAILURE")
        try {
            old.runtime.beforeSyncBatchForTest={awaitCancellation()}
            val page=old.open();save(page,seed.habits[0].uuid)
            val before=pageNativeState();val recovery=old.recovery()
            recovery.beforeRequestForTest={
                armed.set(true)
                db.invalidationTracker.refreshAsync()
                awaitCancellation()
            }
            recovery.beforeInvalidationQueryForTest={
                if(armed.compareAndSet(true,false)) {
                    // This callback is on the actual Room writer thread, unlike
                    // Keystore calls that can switch to a separate IO dispatcher.
                    assertTrue(db.inTransaction())
                    entered.countDown();check(release.await(15,TimeUnit.SECONDS))
                    injected.set(true)
                    throw fault
                }
            }
            recovery.afterActualOuterEndForTest={if(injected.get())ended.set(true)}
            old.beforeClose={if(old.closes.get()==1)assertTrue("Actual invalidation rollback must precede close",ended.get())}
            val work=async(Dispatchers.IO) {runCatching {recovery.run()}}
            try {
                assertTrue(withContext(Dispatchers.IO) {entered.await(15,TimeUnit.SECONDS)})
                assertEquals(1,old.closes.get());assertFalse(work.isCompleted)
                assertFalse(recovery.drained);assertTrue(old.connection.busy())
            } finally {release.countDown()}
            val outcome=withTimeout(20_000) {work.await()}
            assertTrue(outcome.isFailure)
            assertTrue(injected.get());assertTrue(ended.get())
            assertTrue(recovery.drained);assertFalse(old.connection.busy());assertEquals(2,old.closes.get())
            val returned=requireNotNull(outcome.exceptionOrNull())
            assertSame(returned,recovery.error)
            assertEquals(fault.javaClass,returned.javaClass);assertEquals(fault.message,returned.message)
            fun containsOriginal(problem:Throwable,seen:MutableSet<Throwable>):Boolean {
                if(problem===fault)return true
                if(!seen.add(problem))return false
                return problem.cause?.let {containsOriginal(it,seen)}==true || problem.suppressed.any {containsOriginal(it,seen)}
            }
            // Coroutine stacktrace recovery may copy a throwable, but must retain
            // the original object in its cause graph rather than inventing a reason.
            assertTrue("The actual Room failure object must survive propagation",containsOriginal(returned,mutableSetOf()))
            assertEquals(before,pageNativeState())
            assertEquals(9,pageRowCount("completions"));assertEquals(8,pageRowCount("next_acceptances"))
            assertEquals(1,pageRowCount("sync_outbox"));assertEquals(0,server.requests.count {it.path.endsWith("/push")})
        } finally {release.countDown();old.finish()}
    }

    @Test fun unknownOpeningTriggerAndHttpGapSchemaOrContentChangesFailBeforeAnyAck()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted();val image=storage.snapshotClosedDatabase()
        for(mode in listOf("opening_trigger","http_trigger","http_content")) {
            freshGateForColdOpen();restoreForJourney(image)
            val injected=AtomicBoolean()
            fun corrupt() {
                if(mode=="http_content")mutateHouseholdFile("UPDATE habits SET updatedAt=updatedAt+1 WHERE id=?",
                    arrayOf(seed.habits[1].id))
                else mutateHouseholdFile("CREATE TRIGGER g0_unknown_recovery_trigger AFTER UPDATE ON sync_control BEGIN "+
                    "UPDATE habits SET name='must not execute' WHERE id=${seed.habits[1].id}; END")
            }
            val (http,server)=channel {input->
                if(input.path.endsWith("/identity"))reply(input)
                else {
                    if(mode.startsWith("http_") && injected.compareAndSet(false,true))corrupt()
                    successReply(input)
                }
            }
            val old=RecoveryHarness(http)
            try {
                old.runtime.beforeSyncBatchForTest={awaitCancellation()}
                val page=old.open();save(page,seed.habits[0].uuid)
                val recovery=old.recovery()
                if(mode=="opening_trigger")corrupt()
                val before=pageNativeState()
                val failure=runCatching {recovery.run()}.exceptionOrNull()
                assertEquals(when(mode) {
                    "opening_trigger"->"G0_COLD_SCHEMA_REJECTED"
                    "http_trigger"->"G0_RECOVERY_SCHEMA_CHANGED"
                    else->"G0_RECOVERY_EXTERNAL_CHANGE"
                },failure?.message)
                assertTrue(recovery.drained);assertFalse(old.connection.busy())
                assertEquals(9,pageRowCount("completions"));assertEquals(8,pageRowCount("next_acceptances"))
                assertEquals(1,pageRowCount("sync_outbox"))
                if(mode=="opening_trigger") {
                    assertEquals(before,pageNativeState());assertTrue(server.requests.isEmpty())
                } else assertEquals(1,server.requests.count {it.path.endsWith("/push")})
            } finally {old.finish()}
        }
    }
}
