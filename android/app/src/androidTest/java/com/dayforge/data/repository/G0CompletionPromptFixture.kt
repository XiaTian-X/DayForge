package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDevicePreferences
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.domain.model.StableHabitBinding
import com.dayforge.domain.service.AccountIconController
import com.dayforge.ui.components.LinkedMetricInfo
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*

/** One initial static link, real original count sources and actual original prompt oracle.
 * Reference coordinator runs only after the candidate connection/lifetime is retired. */
abstract class G0CompletionPromptFixture : G0HouseholdHistoryFixture() {
    internal var neverAsk = false
    internal var prepareFormalPreference = true
    internal lateinit var promptLink:HabitMetricLinkEntity
    private val promptIcons=mutableListOf<AccountIconController>()

    override suspend fun beforeHouseholdFacts() {
        assertEquals(0,count("completions"));assertEquals(0,count("next_request_origins"))
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val link=HabitMetricLinkEntity(habitId=habit.id,habitUuid=habit.uuid,metricId=metric.id,
                metricUuid=metric.uuid,promptOnComplete=true,uuid=id(9800),createdAt=millis,updatedAt=millis)
            promptLink=link.copy(id=db.habitMetricLinkDao().insert(link))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        if(prepareFormalPreference) preferences.setNeverAskAgain(local(),habit.uuid,neverAsk)
    }

    internal data class Seed(val history:Int,val http:NextSyncHttp)
    internal suspend fun seedPrompt(history:Int=2):Seed {
        if(history<=8) {
            seedHouseholdAccepted(history)
            return Seed(history,channel {successReply(it)}.first)
        }
        val pending=seedPendingHouseholdHistory(history)
        val http=channel {successReply(it)}.first
        constructAcceptedHouseholdHistory(pending,http)
        verifyAcceptedHouseholdHistory(pending,http)
        return Seed(history,http)
    }
    internal fun promptProfile()=G0CompletionPromptProof.StaticOneLink(dataStore,habit.uuid,metric.uuid)
    internal suspend fun openPromptStore(seed:Seed,enabled:Boolean=true):G0HouseholdCountStore {
        val schema=trustedDomainSchema(batchedOpening=true)
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true);storage.reopen()
        val expected=currentHousehold.map {requireNotNull(db.habitDao().getHabitById(it.id))}
        val capacity=if(seed.history>=730)G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6
            else G0HouseholdCountStore.CapacityProfile.DEFAULT_128
        return G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(seed.http),capacity.limit,
            jointAuthority=true,capacityProfile=capacity,completionPromptProfile=if(enabled)promptProfile() else null)
            .also {it.initialize(expected,schema)}
    }
    internal suspend fun saveSource(store:G0HouseholdCountStore,uuid:String=habit.uuid):Pair<G0HouseholdCountStore.Saved,WidgetFactSnapshot> {
        val saved=store.increase(uuid)
        val source=householdView(store,mapOf(uuid to saved.factId)).cards.getValue(uuid).followUp
        assertEquals(saved.factUuid,source.claim.completionUuid)
        return saved to source
    }
    internal suspend fun prepared(store:G0HouseholdCountStore,saved:G0HouseholdCountStore.Saved,
        source:WidgetFactSnapshot):G0CompletionPromptProof.Prepared? {
        var published=false;var value:G0CompletionPromptProof.Prepared?=null
        store.prepareCompletionPrompt(saved,source) {published=true;value=it}
        assertTrue("A verified suppression still completes the publication boundary",published)
        return value
    }
    internal suspend fun rejectPrompt(store:G0HouseholdCountStore,saved:G0HouseholdCountStore.Saved,
        source:WidgetFactSnapshot) {
        val native=pageNativeState();val prefs=dataStore.data.first();var published=false
        assertTrue(runCatching {store.prepareCompletionPrompt(saved,source) {published=true}}.isFailure)
        assertFalse(published);assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
    }
    internal suspend fun formalKey()=HabitDevicePreferences.key(StableHabitBinding.capture(local(),habit.uuid))

    internal fun originalPromptCoordinator():LinkedMetricCoordinator {
        val icons=AccountIconController({error("G0_PROMPT_UNEXPECTED_ICON_ACCESS")},tokens)
        promptIcons+=icons
        val reader=WidgetFactReader(db,tokens,sessions,preferences,CountHistoryReader(db,tokens,sessions),CheckHistoryReader(db,tokens,sessions))
        val repository=MetricRepository(db,db.metricDao(),db.metricLogDao(),db.habitDao(),db.habitMetricLinkDao(),
            nextObjectEditor=NextObjectEditor(db,tokens,sessions,icons),widgetFactReader=reader)
        return LinkedMetricCoordinator(app,preferences,repository,actions().repository)
    }
    /** Caller must close all candidate jobs before invoking this independent original chain. */
    internal suspend fun originalPrompt(source:WidgetFactSnapshot,expected:List<LinkedMetricInfo>?,
        exerciseClose:Boolean=true):Long {
        val native=pageNativeState();val prefs=dataStore.data.first()
        val coordinator=originalPromptCoordinator()
        val started=android.os.SystemClock.elapsedRealtimeNanos()
        coordinator.showCompletionPrompt(source,coordinator.captureCompletionPrompt())
        val ended=android.os.SystemClock.elapsedRealtimeNanos()
        val first=coordinator.postCheckInState.value
        if(expected==null) assertNull(first) else {
            val shown=requireNotNull(first)
            assertEquals(source.habit.name,shown.habitName);assertEquals(expected,shown.linkedMetrics)
            assertSame(source,shown.factPrompt!!.completion)
            if(exerciseClose) {
                coordinator.closePrompt(shown);assertNull(coordinator.postCheckInState.value)
                coordinator.showCompletionPrompt(source,coordinator.captureCompletionPrompt())
                val newer=requireNotNull(coordinator.postCheckInState.value)
                assertNotSame(shown,newer);coordinator.closePrompt(shown);assertSame(newer,coordinator.postCheckInState.value)
                coordinator.closePrompt(newer);assertNull(coordinator.postCheckInState.value)
            }
        }
        assertEquals(native,pageNativeState());assertEquals(prefs,dataStore.data.first())
        return ended-started
    }
    @After fun closePromptIcons() {promptIcons.forEach {it.close()}}
}
