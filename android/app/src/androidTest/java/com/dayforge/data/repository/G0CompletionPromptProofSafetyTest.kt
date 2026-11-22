package com.dayforge.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every negative branch gets its own restored image/new owner. A failed READ
 * deliberately retires that owner; later cases must never pass merely because it died.
 */
@RunWith(AndroidJUnit4::class)
class G0CompletionPromptProofSafetyTest:G0CompletionPromptFixture() {
    private data class Base(val seed:Seed,val image:ByteArray,val prefs:Preferences,
        val saved:G0HouseholdCountStore.Saved,val source:WidgetFactSnapshot)

    private suspend fun base():Base {
        val seed=seedPrompt();val store=openPromptStore(seed);val (saved,source)=saveSource(store)
        assertNotNull(prepared(store,saved,source))
        val prefs=dataStore.data.first();reopenReference()
        return Base(seed,storage.snapshotClosedDatabase(),prefs,saved,source)
    }
    private suspend fun restore(image:ByteArray,prefs:Preferences) {
        failDecrypt=false;authority.onTransitionForTest={}
        freshGateForColdOpen();restoreForJourney(image)
        dataStore.updateData {prefs}
    }
    private suspend fun fresh(base:Base):G0HouseholdCountStore {
        restore(base.image,base.prefs)
        return openPromptStore(base.seed).also {assertNotNull(prepared(it,base.saved,base.source))}
    }
    private suspend fun unchanged(body:suspend ()->Unit):Throwable {
        val native=pageNativeState();val prefs=dataStore.data.first()
        val error=runCatching {body()}.exceptionOrNull()
        assertNotNull("Expected the selected boundary to refuse",error)
        assertNotEquals("A dead owner is not evidence for another rejection", "G0_COUNT_RECOVERY_REQUIRED",error?.message)
        assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        return requireNotNull(error)
    }
    private suspend fun reject(store:G0HouseholdCountStore,saved:G0HouseholdCountStore.Saved,source:WidgetFactSnapshot):Throwable {
        var called=false
        return unchanged {store.prepareCompletionPrompt(saved,source) {called=true}}
            .also {assertFalse("No rejected input may publish, including null suppression",called)}
    }
    private suspend fun setupMutation(body:suspend ()->Unit)=db.withTransaction {
        val sql=db.openHelper.writableDatabase;sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        body();sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }
    private suspend fun accept(store:G0HouseholdCountStore,seed:Seed,saved:G0HouseholdCountStore.Saved) {
        val delivery=requireNotNull(store.send(seed.http,saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(delivery))
        val native=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivery));assertEquals(native,pageNativeState())
    }

    @Test fun ordinaryNonLatestUnqualifiedSourceAndUnlinkedCardMatchOriginalCoordinator()=runBlocking<Unit> {
        val seed=seedPrompt();val store=openPromptStore(seed)
        val (first,old)=saveSource(store);assertFalse(old.completed)
        val firstPrepared=requireNotNull(prepared(store,first,old));assertTrue(firstPrepared.valid())
        val (second,latest)=saveSource(store);assertFalse(latest.completed)
        assertNotEquals(old.claim.completionUuid,latest.claim.completionUuid);assertFalse(firstPrepared.valid())
        val native=pageNativeState();val prefs=dataStore.data.first()
        val retained=requireNotNull(prepared(store,first,old))
        assertEquals(old.claim,retained.source.claim);assertFalse(retained.source.completed)
        assertEquals(2L,requireNotNull(retained.source.count).todayQuantity)
        val current=requireNotNull(prepared(store,second,latest));assertEquals(retained.metrics,current.metrics)
        assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        val (other,otherSource)=saveSource(store,currentHousehold[1].uuid)
        assertNull(prepared(store,other,otherSource))
        accept(store,seed,first);assertFalse(retained.valid())
        val renewed=requireNotNull(prepared(store,first,old));assertEquals(old.claim,renewed.source.claim)
        accept(store,seed,second);accept(store,seed,other)
        assertEquals(5,pageRowCount("completions"));assertEquals(5,pageRowCount("next_acceptances"));assertEquals(0,pageRowCount("sync_outbox"))
        reopenReference()
        originalPrompt(old,retained.metrics);originalPrompt(latest,current.metrics);originalPrompt(otherSource,null)
    }

    @Test fun formalNeverAskSuppressesWithoutWritesAndMatchesOriginalCoordinator()=runBlocking<Unit> {
        neverAsk=true
        val seed=seedPrompt();val store=openPromptStore(seed);val (saved,source)=saveSource(store)
        val native=pageNativeState();val prefs=dataStore.data.first()
        assertNull(prepared(store,saved,source));assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        accept(store,seed,saved);reopenReference();originalPrompt(source,null)
    }

    @Test fun everySavedIdentityFieldMustBindTheSameLiveFactAndRequest()=runBlocking<Unit> {
        val base=base()
        val variants=listOf(base.saved.copy(factId=base.saved.factId+10_000),
            base.saved.copy(factUuid=id(9810)),base.saved.copy(requestId=id(9811)),
            base.saved.copy(habitUuid=currentHousehold[1].uuid))
        for(saved in variants)reject(fresh(base),saved,base.source)
    }

    @Test fun completeClaimFieldsCannotBeReboundToCurrentAccountPlanOrLatestFact()=runBlocking<Unit> {
        val base=base();val source=base.source;val c=source.claim
        val planHabit=source.habit.copy(name="Different captured plan")
        val variants=listOf(
            source.copy(claim=c.copy(accountId=id(9820))),source.copy(claim=c.copy(generation=id(9821))),
            source.copy(claim=c.copy(serverInstanceId=id(9822))),source.copy(claim=c.copy(syncEpoch=id(9823))),
            source.copy(claim=c.copy(deviceId=id(9824))),
            source.copy(habit=source.habit.copy(id=source.habit.id+10_000),claim=c.copy(habitId=c.habitId+10_000)),
            source.copy(habit=source.habit.copy(uuid=id(9825)),claim=c.copy(habitUuid=id(9825))),
            source.copy(habit=planHabit,claim=c.copy(plan=NextStructureMapper.writePlan(planHabit))),
            source.copy(claim=c.copy(date=java.time.LocalDate.parse(c.date).minusDays(1).toString())),
            source.copy(claim=c.copy(timezone=if(c.timezone=="UTC") "Asia/Shanghai" else "UTC")),
            source.copy(claim=c.copy(completionUuid=id(9826))),source.copy(claim=c.copy(completionUuid=null)),
            source.copy(claim=c.copy(countPolicy=CountDayPolicy(99,false).toJson())),
            source.copy(claim=c.copy(oneTimeState=OneTimeState(0,null,null),countPolicy=null)),
            source.copy(claim=c.copy(challengeContract=1)),
            source.copy(claim=c.copy(challengeContract=1,roundHead=ChallengeRoundHead(c.habitUuid,initialChallengeRoundUuid(c.habitUuid),0))))
        for(changed in variants)reject(fresh(base),base.saved,changed)
    }

    @Test fun defaultProfileAndMissingExtraMismatchedOrMalformedLinksRemainRejected()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(kind in listOf("disabled","missing","extra","habit_uuid","metric_uuid","inactive","not_prompt","native_boolean")) {
            restore(image,prefs)
            if(kind!="disabled")setupMutation {
                val sql=db.openHelper.writableDatabase
                when(kind) {
                    "missing"->db.habitMetricLinkDao().delete(promptLink)
                    "extra"->db.habitMetricLinkDao().insert(promptLink.copy(id=0,uuid=id(9830),habitId=currentHousehold[1].id,habitUuid=currentHousehold[1].uuid))
                    "habit_uuid"->db.habitMetricLinkDao().update(promptLink.copy(habitUuid=currentHousehold[1].uuid))
                    "metric_uuid"->db.habitMetricLinkDao().update(promptLink.copy(metricUuid=id(9831)))
                    "inactive"->db.habitMetricLinkDao().update(promptLink.copy(isActive=false))
                    "not_prompt"->db.habitMetricLinkDao().update(promptLink.copy(promptOnComplete=false))
                    else->sql.execSQL("UPDATE habit_metric_links SET promptOnComplete=2 WHERE id=?",arrayOf(promptLink.id))
                }
            }
            val failure=unchanged {openPromptStore(seed,enabled=kind!="disabled")}
            if(kind=="disabled")assertEquals("G0_COUNT_UNSUPPORTED_SCOPE:habit_metric_links",failure.message)
            else assertTrue("$kind: $failure",failure.message?.startsWith("G0_PROMPT_")==true)
        }
    }

    @Test fun fullMetricMetadataNativeTypesAndExcludedHistoriesCannotHideBehindVisibleFields()=runBlocking<Unit> {
        val seed=seedPrompt();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        for(kind in listOf("extra_metric","no_appearance","precision","empty_unit","range_without_bounds","native_boolean","metric_log","durable_prompt")) {
            restore(image,prefs)
            setupMutation {
                val sql=db.openHelper.writableDatabase
                when(kind) {
                    "extra_metric"->db.metricDao().insert(metric.copy(id=0,uuid=id(9840),name="Extra metric"))
                    "no_appearance"->db.metricDao().update(metric.copy(appearance=null))
                    "precision"->db.metricDao().update(metric.copy(decimalPlaces=7))
                    "empty_unit"->db.metricDao().update(metric.copy(unit=""))
                    "range_without_bounds"->db.metricDao().update(metric.copy(targetDirection="range",targetValue=null,targetValueUpper=null))
                    "native_boolean"->sql.execSQL("UPDATE metrics SET isActive=2 WHERE id=?",arrayOf(metric.id))
                    "metric_log"->db.metricLogDao().insert(MetricLogEntity(metricId=metric.id,date=millis,value=1.0,unit=metric.unit))
                    else->sql.execSQL("INSERT INTO completion_metric_prompts(eventUuid,activityUuid,state,revision,entriesJson,recordedAtMillis,timezone) VALUES(?,?,'pending',0,'[]',NULL,NULL)",arrayOf(id(9841),habit.uuid))
                }
            }
            val failure=unchanged {openPromptStore(seed)}
            if(kind in setOf("metric_log","durable_prompt"))assertTrue(requireNotNull(failure.message).startsWith("G0_COUNT_UNSUPPORTED_SCOPE:"))
        }
    }

    @Test fun missingMalformedForeignBindingAndProvisionalPreferencesCannotDefaultToAsk()=runBlocking<Unit> {
        val seed=seedPrompt();val session=local();val key=formalKey();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        val record=requireNotNull(HabitDevicePreferences.decode(prefs[key]))
        for(kind in listOf("missing","bad","binding","provisional")) {
            restore(image,prefs)
            dataStore.edit {values->when(kind) {
                "missing"->values.remove(key)
                "bad"->values[key]="not-json"
                "binding"->values[key]=record.copy(binding=StableHabitBinding.capture(session,currentHousehold[1].uuid)).encode()
                else->{val binding=StableHabitBinding.capture(session.copy(serverInstanceId=null,syncEpoch=null),habit.uuid)
                    values[HabitDevicePreferences.key(binding)]=record.copy(binding=binding).encode()}
            }}
            val failure=unchanged {openPromptStore(seed)}
            assertTrue("$kind: $failure",failure.message?.startsWith("G0_PROMPT_")==true)
        }
    }

    @Test fun orphanMalformedRecoveryHistoryAndSqlFenceDoNotLookLikeAnOpenReplica()=runBlocking<Unit> {
        val seed=seedPrompt();val access=access();val session=local();val prefs=dataStore.data.first();val image=storage.snapshotClosedDatabase()
        val stage=ReplicaSwitchStage(id(9850),session.authentication.userId,requireNotNull(session.serverInstanceId),
            requireNotNull(session.syncEpoch),id(9851),session.authentication.generation,requireNotNull(access.deviceId),
            requireNotNull(access.capabilityRevision),access.capabilities.sorted(),id(9852),"0".repeat(64),"1".repeat(64),"2".repeat(64),"3".repeat(64),0L)
        for(kind in listOf("orphan","malformed","fence")) {
            restore(image,prefs)
            if(kind=="fence")db.nextReplicaFenceDao().insert(stage.fence)
            else dataStore.edit {it[ReplicaSwitchStage.key(stage.recoveryId)]=if(kind=="orphan")ReplicaSwitchStage.encode(stage) else "broken-stage"}
            assertFalse(ReplicaSwitchGuard.blocked(dataStore.data.first()))
            if(kind=="orphan") {
                val observed=tokens.replicaPresentationWitness()
                assertFalse(observed.pointerPresent);assertEquals(listOf(stage),observed.history)
            }
            assertFalse(ReplicaPresentationRepository(db,tokens,sessions).isOrdinaryOpen())
            val failure=unchanged {openPromptStore(seed)}
            if(kind=="orphan")assertEquals("G0_PROMPT_REPLICA_SCOPE_UNSUPPORTED",failure.message)
            if(kind=="fence")assertEquals("G0_COUNT_UNSUPPORTED_SCOPE:next_replica_fence",failure.message)
        }
    }

    @Test fun warmExternalChangesLostSourceOldConnectionAndSqlWritePhasesCannotReuseProof()=runBlocking<Unit> {
        val base=base()
        for(kind in listOf("metric","link","source_removed","origin_changed","connection","no_owner_write","read_write","final_write")) {
            val store=fresh(base);val prior=requireNotNull(prepared(store,base.saved,base.source))
            when(kind) {
                "metric"->mutateHouseholdFile("UPDATE metrics SET updatedAt=updatedAt+1 WHERE id=?",arrayOf(metric.id))
                "link"->mutateHouseholdFile("UPDATE habit_metric_links SET coefficient=coefficient+1 WHERE id=?",arrayOf(promptLink.id))
                "source_removed"->mutateHouseholdFile("DELETE FROM completions WHERE id=?",arrayOf(base.saved.factId))
                "origin_changed"->mutateHouseholdFile("UPDATE next_request_origins SET sourceHash=? WHERE requestId=?",arrayOf("broken-source",base.saved.requestId))
                "connection"->{
                    val native=pageNativeState();val prefs=dataStore.data.first()
                    assertTrue(runCatching {storage.reopen()}.exceptionOrNull() is G0DomainSqlGate.Denied)
                    assertTrue(runCatching {prepared(store,base.saved,base.source)}.isFailure)
                    assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
                    assertFalse(prior.valid());continue
                }
                else->{
                    val failure=unchanged {
                        fun write()=db.openHelper.writableDatabase.execSQL("UPDATE habit_metric_links SET promptOnComplete=0 WHERE id=?",arrayOf(promptLink.id))
                        when(kind) {
                            "no_owner_write"->write()
                            "read_write"->domainGate.transaction(db,G0DomainSqlGate.Phase.READ) {write()}
                            else->domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {domainGate.finalRead {write()}}
                        }
                    }
                    assertTrue("$kind: $failure",failure is G0DomainSqlGate.Denied);continue
                }
            }
            val failure=reject(store,base.saved,base.source)
            assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",failure.message);assertFalse(prior.valid())
        }
    }

    @Test fun entryAndFinalActualCredentialDecryptionRemainRequired()=runBlocking<Unit> {
        val base=base()
        for(final in listOf(false,true)) {
            val store=fresh(base);var reached=false
            if(final)store.beforeCompletionPromptFinalForTest={reached=true;failDecrypt=true} else failDecrypt=true
            val before=decryptCount.get()
            try {
                reject(store,base.saved,base.source)
                assertTrue(decryptCount.get()>before);assertEquals(final,reached)
            } finally {failDecrypt=false;store.beforeCompletionPromptFinalForTest={}}
        }
    }

    @Test fun entryAbaAndFinalOrAfterTransactionReservationCannotPublish()=runBlocking<Unit> {
        val base=base()
        for(boundary in listOf("entry","final","after_read")) {
            val store=fresh(base);val prefs=dataStore.data.first();val native=pageNativeState()
            val trace=G0PhaseTrace(driverTiming=false);var called=false;var mutation:Job?=null
            val reserved=CompletableDeferred<Unit>()
            suspend fun aba() {
                dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
                dataStore.updateData {prefs}
            }
            if(boundary=="entry")store.afterEntryAuthenticationForTest={aba()}
            else {
                authority.onTransitionForTest={reserved.complete(Unit)}
                val hook:suspend ()->Unit={
                    if(boundary=="after_read")assertEquals(1,trace.count("tx_end_outer"))
                    mutation=launch(Dispatchers.IO) {aba()}
                    withTimeout(15_000) {reserved.await()}
                }
                if(boundary=="final")store.beforeCompletionPromptFinalForTest=hook else store.beforeCompletionPromptPublishForTest=hook
            }
            try {
                val failure=runCatching {trace.run {store.prepareCompletionPrompt(base.saved,base.source) {called=true}}}.exceptionOrNull()
                withTimeout(15_000) {mutation?.join()}
                assertTrue("$boundary: $failure",failure is G0AuthorityOwner.Denied)
                assertFalse(called);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
            } finally {authority.onTransitionForTest={};mutation?.cancelAndJoin()}
        }
    }

    @Test fun realReadCancellationAndUnknownReadEndNeverDeliverACompletionPrompt()=runBlocking<Unit> {
        val base=base()
        for(cancel in listOf(true,false)) {
            val store=fresh(base);val before=pageNativeState();val prefs=dataStore.data.first()
            var called=false;val trace=G0PhaseTrace(driverTiming=false)
            if(cancel) {
                val entered=CompletableDeferred<Unit>()
                store.beforeCompletionPromptFinalForTest={entered.complete(Unit);awaitCancellation()}
                val job=launch(Dispatchers.IO) {trace.run {store.prepareCompletionPrompt(base.saved,base.source) {called=true}}}
                withTimeout(15_000) {entered.await()};job.cancelAndJoin()
                assertTrue(job.isCancelled)
            } else {
                endFaults.failAfterNextEndIn(trace)
                val failure=runCatching {trace.run {store.prepareCompletionPrompt(base.saved,base.source) {called=true}}}.exceptionOrNull()
                assertEquals("G0_COMMIT_RESULT_LOST",failure?.message)
            }
            assertEquals(1,trace.count("tx_begin_outer"));assertEquals(1,trace.count("tx_end_outer"))
            assertFalse(called);assertEquals(before,pageNativeState());assertEquals(prefs,dataStore.data.first())
            assertTrue(runCatching {prepared(store,base.saved,base.source)}.isFailure)
        }
    }
}
