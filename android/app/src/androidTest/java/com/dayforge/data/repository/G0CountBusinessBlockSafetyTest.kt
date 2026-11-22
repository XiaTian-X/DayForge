package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real original-producer/ACK inputs plus the existing Store's complete cold proof.
 * This exercises a Store-owned in-memory block representation. It does not install a
 * durable trusted root, bypass cold auditing, or claim cold/UI latency improvement.
 * Exactly the existing two-object, plain ascending COUNT profile is supported here.
 */
@RunWith(AndroidJUnit4::class)
class G0CountBusinessBlockSafetyTest:G0HouseholdCountFixture() {
    private suspend fun blockDay(store:G0HouseholdCountStore,handle:G0HouseholdCountStore.CountBlocks,
        habitUuid:String,date:LocalDate):G0HouseholdCountStore.CountBlockView {
        var result:G0HouseholdCountStore.CountBlockView?=null
        store.readCountBlockDay(handle,habitUuid,date) {result=it}
        return requireNotNull(result)
    }
    private suspend fun rejectedUnchanged(body:suspend ()->Unit):Throwable {
        val before=pageNativeState();val failure=runCatching {body()}.exceptionOrNull()
        assertNotNull("Invalid block preparation/publication unexpectedly succeeded",failure)
        assertEquals(before,pageNativeState());return requireNotNull(failure)
    }
    private suspend fun aba() {
        val original=dataStore.data.first()
        dataStore.edit {it[stringPreferencesKey("sync_device_capability_revision")]="2"}
        dataStore.updateData {original};assertEquals(original,dataStore.data.first())
    }
    private fun assertDay(expected:CountHistory,actual:G0CountBusinessBlocks.DayRead) {
        val expectedFacts=expected.completions.filter {it.recordedLocalDate==actual.date.toString()}
        assertEquals(expected.quantities[actual.date] ?: 0L,actual.quantity)
        assertEquals(expected.policies[actual.date] ?: if(actual.date==expected.today)expected.todayPolicy else null,
            actual.effectivePolicy)
        assertEquals(expected.policies[actual.date],actual.storedPolicy)
        assertEquals(actual.date in expected.unknownDates,actual.unknown)
        assertEquals(expectedFacts.associateBy {it.uuid},actual.facts.associateBy {it.uuid})
        assertEquals(expectedFacts.size,actual.facts.size)
        assertEquals(expectedFacts,actual.facts)
        assertEquals(expected.today,actual.summary.today)
        assertEquals(expected.todayQuantity,actual.summary.todayQuantity)
        assertEquals(expected.completedToday,actual.summary.completedToday)
        assertEquals(expected.firstDate,actual.summary.firstDate)
        assertEquals(expected.qualifiedDates.size,actual.summary.qualifiedDayCount)
        assertEquals(expected.quantities.values.fold(0L) {sum,value->Math.addExact(sum,value)},actual.summary.totalQuantity)
        assertEquals(expectedFacts.size,actual.stats.decodedFacts)
        assertEquals(actual.sourceRequestIds.size,actual.stats.decodedSources)
        assertTrue(actual.stats.loadedNodes>0);assertTrue(actual.stats.loadedBytes>0L)
    }

    @Test fun originalCrossYearAndRepeatedDayFactsMatchDayRulesAndWholeSummaryWithoutReadingEveryFact()=runBlocking<Unit> {
        // Fixed completed-month dates cover two days in one D unit, a qualified A day,
        // both objects on the same date, sparse months and a prior year, even at month start.
        val today=LocalDate.now();val monthDay=today.withDayOfMonth(1).minusMonths(1).plusDays(4)
        val dates=listOf(monthDay,monthDay,monthDay,monthDay.plusDays(1),monthDay,
            monthDay.minusMonths(2),monthDay.minusYears(1),monthDay.minusYears(1))
        val seed=seedHouseholdAccepted(8,daysAgo=dates.map {java.time.temporal.ChronoUnit.DAYS.between(it,today)})
        val factsById=seed.factIds.associateWith {requireNotNull(db.completionDao().getCompletionById(it))}
        val requestForFact=seed.factIds.zip(seed.requestIds).associate {(fact,request)->factsById.getValue(fact).uuid to request}
        val originalDays=sessions.exclusive {db.withTransaction {
            seed.habits.flatMap {NextCountDayStore(db).readForHabit(it)}.associateBy {it.habitUuid to it.localDate}
        }}
        val (http,_)=channel {successReply(it)};val before=pageNativeState();val store=openHousehold(http)
        val handle=store.prepareCountBlocks()
        val seen=mutableListOf<G0HouseholdCountStore.CountBlockView>()
        for(row in seed.habits) {
            val expected=seed.references.getValue(row.uuid)
            val dates=expected.quantities.keys+seed.today+seed.today.minusDays(2)+seed.today.minusYears(5)
            for(date in dates) {
                val view=blockDay(store,handle,row.uuid,date);seen+=view;val actual=view.day
                assertTrue(view.valid());assertEquals(row.id,actual.habitId);assertEquals(row.uuid,actual.habitUuid)
                assertEquals(date,actual.date);assertDay(expected,actual)
                assertEquals(originalDays[row.uuid to date.toString()],actual.day)
                assertEquals(actual.facts.mapTo(linkedSetOf()) {requestForFact.getValue(it.uuid)},actual.sourceRequestIds)
                assertTrue("A day read must not decode the eight-fact lifetime",actual.stats.decodedFacts<8)
            }
        }
        val qualified=seen.single {it.day.habitUuid==seed.habits[0].uuid && it.day.date==monthDay}.day
        assertEquals(3L,qualified.quantity);assertEquals(1,qualified.summary.qualifiedDayCount)
        assertEquals(0L,qualified.summary.todayQuantity);assertFalse(qualified.summary.completedToday)
        assertEquals(before,pageNativeState())
        val current=householdView(store);reopenReference();sameHouseholdAsOriginal(current)
        assertEquals(before,pageNativeState())
    }

    @Test fun pendingSendingAndAcceptedSourcesInvalidateEarlierHandlesAndPreserveActualFactNormalization()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2)
        val canonicalCreated="2026-10-06T00:00:02.000000Z"
        val (http,_)=channel {input->successReply(input,edit={payload->JsonObject(payload+(
            "created_at" to JsonPrimitive(canonicalCreated)))})}
        val store=openHousehold(http);val saved=store.increase(seed.habits[0].uuid)
        val pending=store.prepareCountBlocks();val pendingView=blockDay(store,pending,saved.habitUuid,seed.today)
        val beforeFact=pendingView.day.facts.single()
        assertEquals(setOf(saved.requestId),pendingView.day.sourceRequestIds)
        assertEquals(1,pageRowCount("sync_outbox"))
        val delivered=requireNotNull(store.send(http,saved.requestId))
        var oldPublished=false
        rejectedUnchanged {store.readCountBlockDay(pending,saved.habitUuid,seed.today) {oldPublished=true}}
        assertFalse(oldPublished);assertFalse(pendingView.valid())
        val sending=store.prepareCountBlocks();val sentView=blockDay(store,sending,saved.habitUuid,seed.today)
        assertEquals(beforeFact,sentView.day.facts.single())
        // A real ACK commits while another block build owns only its frozen input.
        // The saved ACK survives; that older candidate must never replace its source view.
        var acceptedDuringPreparation:NextOperationAcceptance?=null
        store.afterCountBlockSnapshotForTest={acceptedDuringPreparation=store.acknowledge(delivered)}
        try {assertTrue(runCatching {store.prepareCountBlocks()}.isFailure)}
        finally {store.afterCountBlockSnapshotForTest={}}
        assertEquals(NextOperationAcceptance.COMMITTED,acceptedDuringPreparation)
        rejectedUnchanged {store.readCountBlockDay(sending,saved.habitUuid,seed.today) {oldPublished=true}}
        assertFalse(oldPublished);assertFalse(sentView.valid())
        val accepted=store.prepareCountBlocks();val acceptedView=blockDay(store,accepted,saved.habitUuid,seed.today)
        val afterFact=acceptedView.day.facts.single()
        assertEquals(beforeFact.copy(createdAt=afterFact.createdAt),afterFact)
        assertEquals(java.time.Instant.parse(canonicalCreated).toEpochMilli(),afterFact.createdAt)
        assertNotEquals(beforeFact.createdAt,afterFact.createdAt)
        assertEquals(pendingView.day.quantity,acceptedView.day.quantity)
        assertEquals(pendingView.day.effectivePolicy,acceptedView.day.effectivePolicy)
        assertEquals(pendingView.day.sourceRequestIds,acceptedView.day.sourceRequestIds)
        assertEquals(0,pageRowCount("sync_outbox"));assertEquals(3,pageRowCount("next_acceptances"))
        val durable=pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(delivered));assertEquals(durable,pageNativeState())
        val view=householdView(store);reopenReference();sameHouseholdAsOriginal(view)
        val row=requireNotNull(db.habitDao().getHabitById(seed.habits[0].id))
        assertDay(CountHistoryReader(db,tokens,sessions).read(row),acceptedView.day)
        assertEquals(durable,pageNativeState())
    }

    @Test fun legitimateWriteAfterFreezeOrDecodeRejectsStaleCandidateWithoutPoisoningCommittedStore()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        var saved:G0HouseholdCountStore.Saved?=null
        store.afterCountBlockSnapshotForTest={saved=store.increase(seed.habits[1].uuid)}
        try {assertTrue(runCatching {store.prepareCountBlocks()}.isFailure)}
        finally {store.afterCountBlockSnapshotForTest={}}
        val committed=requireNotNull(saved)
        assertEquals(3,pageRowCount("completions"));assertEquals(1,pageRowCount("sync_outbox"))
        assertEquals(listOf(committed.requestId),store.pendingRequestIds())
        val current=householdView(store);assertTrue(current.valid())
        val fresh=store.prepareCountBlocks();val actual=blockDay(store,fresh,committed.habitUuid,seed.today)
        assertDay(current.cards.getValue(committed.habitUuid).history,actual.day)
        assertEquals(setOf(committed.requestId),actual.day.sourceRequestIds);assertEquals(1L,actual.day.quantity)

        // A separate deterministic interleaving changes the owner after this read
        // decoded its day and before the final publication lock/authentication.
        var lateSaved:G0HouseholdCountStore.Saved?=null;var published=false
        store.beforeCountBlockPublishForTest={lateSaved=store.increase(seed.habits[0].uuid)}
        try {assertTrue(runCatching {
            store.readCountBlockDay(fresh,committed.habitUuid,seed.today) {published=true}
        }.isFailure)} finally {store.beforeCountBlockPublishForTest={}}
        assertNotNull(lateSaved);assertFalse(published);assertFalse(actual.valid())
        assertEquals(4,pageRowCount("completions"));assertEquals(2,pageRowCount("sync_outbox"))
        val finalView=householdView(store);assertTrue(finalView.valid())
        assertEquals(listOf(1L,1L),seed.habits.map {finalView.cards.getValue(it.uuid).history.todayQuantity})
        val newest=store.prepareCountBlocks()
        assertDay(finalView.cards.getValue(committed.habitUuid).history,
            blockDay(store,newest,committed.habitUuid,seed.today).day)
        val durable=pageNativeState();reopenReference();sameHouseholdAsOriginal(finalView);assertEquals(durable,pageNativeState())
    }

    @Test fun cancelledPreparationLeavesNoNewHandleAndRetainsExistingQualifiedRead()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val old=store.prepareCountBlocks();val oldView=blockDay(store,old,seed.habits[0].uuid,seed.today.minusDays(1))
        val before=pageNativeState();val entered=CompletableDeferred<Unit>();var issued=false
        store.afterCountBlockSnapshotForTest={entered.complete(Unit);awaitCancellation()}
        val work=launch(Dispatchers.IO) {store.prepareCountBlocks();issued=true}
        try {withTimeout(15_000) {entered.await()};work.cancelAndJoin()}
        finally {work.cancelAndJoin();store.afterCountBlockSnapshotForTest={}}
        assertFalse(issued);assertEquals(before,pageNativeState());assertTrue(oldView.valid())
        val again=blockDay(store,old,seed.habits[0].uuid,seed.today.minusDays(1))
        assertEquals(oldView.day.facts,again.day.facts);assertTrue(again.valid())
        val fresh=store.prepareCountBlocks();assertFalse(oldView.valid())
        assertTrue(blockDay(store,fresh,seed.habits[0].uuid,seed.today).valid());assertEquals(before,pageNativeState())
    }

    @Test fun authorityAbaDuringLockFreeConstructionCannotIssueOrPublishOldBlocks()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val old=store.prepareCountBlocks();val oldView=blockDay(store,old,seed.habits[0].uuid,seed.today)
        store.afterCountBlockSnapshotForTest={aba()}
        try {rejectedUnchanged {store.prepareCountBlocks()}}
        finally {store.afterCountBlockSnapshotForTest={}}
        var published=false
        rejectedUnchanged {store.readCountBlockDay(old,seed.habits[0].uuid,seed.today) {published=true}}
        assertFalse(published);assertFalse(oldView.valid())
    }

    @Test fun finalFreshAuthenticationStillRunsBeforeBlockPublication()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val handle=store.prepareCountBlocks();var published=false
        store.afterEntryAuthenticationForTest={failDecrypt=true}
        try {rejectedUnchanged {store.readCountBlockDay(handle,seed.habits[0].uuid,seed.today) {published=true}}}
        finally {failDecrypt=false;store.afterEntryAuthenticationForTest={}}
        assertFalse(published)
    }

    @Test fun originalFactSourceDayAndReceiptDamageCannotBecomeAQualifiedBlockSet()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val clean=storage.snapshotClosedDatabase()
        val cases=listOf(
            "UPDATE completions SET value=value+1 WHERE id=?" to arrayOf<Any>(seed.factIds[0]),
            "UPDATE next_request_origins SET sourceHash=? WHERE requestId=?" to arrayOf<Any>("0".repeat(64),seed.requestIds[0]),
            "UPDATE count_days SET targetValue=targetValue+1 WHERE habitId=?" to arrayOf<Any>(seed.habits[0].id),
            "UPDATE next_acceptances SET resultHash=? WHERE requestId=?" to arrayOf<Any>("0".repeat(64),seed.requestIds[0]))
        for((statement,args) in cases) {
            freshGateForColdOpen();restoreForJourney(clean);mutateHouseholdFile(statement,args)
            val (http,_)=channel {successReply(it)};var issued=false
            rejectedUnchanged {val store=openHousehold(http);store.prepareCountBlocks();issued=true}
            assertFalse(issued)
        }
    }

    @Test fun handlesAreOwnerBoundAndDeliveredFactsCannotMutatePrivateBlocks()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(2);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val handle=store.prepareCountBlocks();val view=blockDay(store,handle,seed.habits[0].uuid,seed.today.minusDays(1))
        val expected=view.day.facts.toList();val sources=view.day.sourceRequestIds.toSet();val before=pageNativeState()
        assertTrue(runCatching {(view.day.facts as MutableList<CompletionEntity>).clear()}.isFailure)
        assertTrue(runCatching {(view.day.sourceRequestIds as MutableSet<String>).clear()}.isFailure)
        val again=blockDay(store,handle,seed.habits[0].uuid,seed.today.minusDays(1))
        assertEquals(expected,again.day.facts);assertEquals(sources,again.day.sourceRequestIds)
        var published=false
        rejectedUnchanged {store.readCountBlockDay(G0HouseholdCountStore.CountBlocks(),seed.habits[0].uuid,seed.today) {published=true}}
        assertFalse(published);assertEquals(before,pageNativeState())
        reopenReference();val next=openHousehold(http)
        rejectedUnchanged {next.readCountBlockDay(handle,seed.habits[0].uuid,seed.today) {published=true}}
        assertFalse(published);val current=next.prepareCountBlocks()
        assertTrue(blockDay(next,current,seed.habits[0].uuid,seed.today).valid());assertEquals(before,pageNativeState())
    }

    @Test fun externalPrimaryCommitCannotPublishAnOlderInMemoryRoot()=runBlocking<Unit> {
        val seed=seedHouseholdAccepted(0);val (http,_)=channel {successReply(it)};val store=openHousehold(http)
        val handle=store.prepareCountBlocks();val prior=blockDay(store,handle,seed.habits[0].uuid,seed.today)
        assertTrue(prior.valid())
        store.beforeCountBlockPublishForTest={
            mutateHouseholdFile("UPDATE habits SET targetValue=targetValue+1 WHERE id=?",arrayOf(seed.habits[0].id))
            // Negative control: memory generation alone cannot observe this external commit.
            assertTrue(prior.valid())
        }
        var published=false
        val failure=runCatching {store.readCountBlockDay(handle,seed.habits[0].uuid,seed.today) {published=true}}.exceptionOrNull()
        assertNotNull(failure);assertFalse(published);assertFalse(prior.valid())
        assertTrue(generateSequence(failure){it.cause}.any {it.message?.contains("G0_DOMAIN_EXTERNAL_CHANGE")==true})
    }
}
