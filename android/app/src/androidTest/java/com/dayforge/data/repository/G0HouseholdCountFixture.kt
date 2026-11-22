package com.dayforge.data.repository

import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*

/** Two ordinary count objects, with all facts produced and accepted by the original
 * implementation. Only structure setup before any source exists is synthetic. */
abstract class G0HouseholdCountFixture:G0ShortCountFixture() {
    override val jointCountAuthority=true
    internal lateinit var householdHabits:List<HabitEntity>
    internal val currentHousehold:List<HabitEntity> get()=householdHabits.toList()
    /** Synthetic initial configuration only, before any fact/source exists. */
    internal open suspend fun beforeHouseholdFacts() = Unit
    internal data class HouseholdSeed(val habits:List<HabitEntity>,val today:LocalDate,val zone:ZoneId,
        val factIds:List<Long>,val requestIds:List<String>,val references:Map<String,CountHistory>,
        val datesByObject:Map<String,List<LocalDate>>,val factCounts:Map<String,Int>)

    internal suspend fun seedHouseholdAccepted(total:Int=8,skewed:Boolean=false,daysAgo:List<Long>?=null):HouseholdSeed {
        require(total in 0..128 && total%2==0)
        require(daysAgo==null || daysAgo.size==total && daysAgo.all {it>0})
        assertEquals(0,count("completions"));assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
        configureAction(target=3)
        val zone=ZoneId.systemDefault();val today=LocalDate.now(zone)
        val dates=List(total) {today.minusDays(daysAgo?.get(it) ?: (it/2L+1))}
        val owners=List(total) {if(skewed) {if(it==total-1)1 else 0} else it%2}
        val born=(dates.minOrNull() ?: today).minusDays(1).atStartOfDay(zone).toInstant()
        val first=habit.copy(name="Household A",createdAt=born.toEpochMilli(),updatedAt=born.toEpochMilli(),
            planMetadata=requireNotNull(habit.planMetadata).copy(creationTimestamp=born.toString(),timezone=zone.id))
        val second=first.copy(id=0,uuid=id(4801),name="Household B",targetValue=7)
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(first)
            householdHabits=listOf(first,second.copy(id=db.habitDao().insert(second)))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        habit=householdHabits.first()
        assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
        beforeHouseholdFacts()
        assertEquals(0,count("next_request_origins"));assertEquals(0,count("sync_outbox"))
        val (http,_)=channel {successReply(it)};val original=sender(http)
        val facts=mutableListOf<Long>();val requests=mutableListOf<String>()
        repeat(total) {index->
            check(LocalDate.now(zone)==today && ZoneId.systemDefault()==zone) {"G0_HOUSEHOLD_SEED_DATE_CHANGED"}
            val objectRow=householdHabits[owners[index]];val date=dates[index]
            val row=originalHouseholdFact(objectRow,date,zone)
            val queued=db.syncOutboxDao().getEntityIntents("completion",row.uuid).single()
            val delivered=requireNotNull(original.sendOperation(access(),queued.operationId))
            assertEquals(NextOperationAcceptance.COMMITTED,original.acceptOperation(delivered))
            facts+=requireNotNull(db.completionDao().getCompletionByUuid(row.uuid)).id;requests+=queued.operationId
        }
        householdHabits=householdHabits.map {requireNotNull(db.habitDao().getHabitById(it.id))};habit=householdHabits.first()
        val reader=CountHistoryReader(db,tokens,sessions)
        val reference=householdHabits.associate {it.uuid to reader.read(it,today)}
        val distribution=householdHabits.mapIndexed {index,row->row.uuid to dates.filterIndexed {i,_->owners[i]==index}}.toMap()
        val factCounts=distribution.mapValues {it.value.size}
        for(row in householdHabits) {
            val history=reference.getValue(row.uuid)
            assertEquals(factCounts.getValue(row.uuid),history.completions.size);assertEquals(0L,history.todayQuantity)
            assertFalse(today in history.policies);assertTrue(history.unknownDates.isEmpty())
            assertEquals(CountDayPolicy(row.targetValue,false),history.todayPolicy)
            assertEquals(distribution.getValue(row.uuid).groupingBy {it}.eachCount().mapValues {it.value.toLong()},history.quantities)
        }
        assertEquals(distribution.values.sumOf {it.distinct().size},count("count_days"));assertEquals(total,count("next_acceptances"))
        assertEquals(total,count("completions"));assertEquals(0,count("sync_outbox"))
        check(LocalDate.now(zone)==today && ZoneId.systemDefault()==zone) {"G0_HOUSEHOLD_SEED_DATE_CHANGED"}
        return HouseholdSeed(householdHabits.toList(),today,zone,facts.toList(),requests.toList(),reference,
            distribution.mapValues {it.value.toList()},factCounts)
    }

    internal suspend fun originalHouseholdFact(row:HabitEntity,date:LocalDate=LocalDate.now(),
        zone:ZoneId=ZoneId.systemDefault()):CompletionEntity {
        val occurred=if(date==LocalDate.now(zone))java.time.Instant.now() else date.atTime(12,0).atZone(zone).toInstant()
        val fact=CompletionEntity(habitId=row.id,habitUuid=row.uuid,date=date.toDisplayMillis(zone),value=1,
            actualCompletedAt=occurred.toEpochMilli(),createdAt=occurred.toEpochMilli(),
            recordedTimezone=zone.id,recordedLocalDate=date.toString())
        producer().write(local()) {
            NextCountDayStore(db).capture(row,fact)
            db.completionDao().insertForSync(fact)
        }
        return requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid))
    }

    /** HTTP/account setup must happen before the owner takes its original lease. */
    internal suspend fun openHousehold(http:NextSyncHttp,maxFacts:Int?=null,
        capacityProfile:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128):G0HouseholdCountStore {
        val schema=trustedDomainSchema(batchedOpening=true)
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true);storage.reopen()
        val expected=householdHabits.map {requireNotNull(db.habitDao().getHabitById(it.id))}
        return G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(http),maxFacts ?: capacityProfile.limit,
            jointAuthority=true,capacityProfile=capacityProfile).also {it.initialize(expected,schema)}
    }

    internal suspend fun coldHousehold(http:NextSyncHttp,
        capacityProfile:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128)=
        openHousehold(http,capacityProfile=capacityProfile)

    internal suspend fun householdView(store:G0HouseholdCountStore,sourceIds:Map<String,Long> = emptyMap()):G0HouseholdCountStore.View {
        var result:G0HouseholdCountStore.View?=null
        store.readAndPublish(sourceIds) {result=it};return requireNotNull(result)
    }

    /** Call after reopening the original unsealed reference connection. */
    internal suspend fun sameHouseholdAsOriginal(result:G0HouseholdCountStore.View) {
        val home=readHome();assertEquals(householdHabits.map {it.uuid}.toSet(),result.cards.keys)
        assertEquals(householdHabits.map {it.uuid}.toSet(),home.habits.map {it.uuid}.toSet())
        val counts=CountHistoryReader(db,tokens,sessions)
        val widget=WidgetFactReader(db,tokens,sessions,preferences,counts,CheckHistoryReader(db,tokens,sessions))
        for(expected in householdHabits) {
            val card=result.cards.getValue(expected.uuid)
            val row=requireNotNull(db.habitDao().getHabitById(expected.id))
            assertEquals(row,card.habit);assertEquals(home.counts.getValue(row.id),card.history)
            assertEquals(counts.read(row),card.history)
            val original=widget.read(row)
            assertEquals(original.count,card.followUp.count)
            assertEquals(original.claim.completionUuid,card.followUp.claim.completionUuid)
        }
        assertEquals(home.completions.toSet(),result.cards.values.flatMap {it.history.completions}.toSet())
    }

    internal fun mutateHouseholdFile(sql:String,args:Array<Any> = emptyArray()) {
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {it.execSQL(sql,args)}
    }
}
