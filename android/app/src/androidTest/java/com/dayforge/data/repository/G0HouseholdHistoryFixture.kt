package com.dayforge.data.repository

import android.database.Cursor
import android.util.Base64
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.decodeFrozenSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.NextSyncPushRequest
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.entity.*
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.CountDayPolicy
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*

/** A synthetic, locally sourced accepted-history FIXTURE, never a sender or recovery path.
 * The original producer creates every fact, source and day binding. Historical terminal
 * rows are constructed, not delivered/accepted by HTTP. Small same-image original-ACK
 * differentials and the separate complete original audit are required before use.
 * Only two plain count objects, one original count_snapshot(value=1) per past day,
 * without predecessors. No existing same-day quantity is changed by the fixture.
 * The 730 fixture ceiling does not change the runtime store's 128-fact admission limit.
 */
abstract class G0HouseholdHistoryFixture:G0HouseholdCountPageFixture() {
    internal data class HistoryEntry(val fact:CompletionEntity,val queue:SyncOutboxEntity,
        val origin:NextRequestOriginEntity,val day:CountDayEntity,val sourceHash:String,val originHash:String)
    internal data class HistorySeed(val habits:List<HabitEntity>,val today:LocalDate,val zone:ZoneId,
        val entries:List<HistoryEntry>,val core:LocalCoreWriteAccess,val registered:LocalSyncAccess,
        val unchanged:Map<String,List<String>>) {
        val label:String get()="constructed_local_accepted_fixture"
    }
    internal var historyAfterEntryForTest:suspend (Int)->Unit={}

    /** One bounded original write, not N repeats of the growing pending-source scan. */
    internal suspend fun seedPendingHouseholdHistory(total:Int=8):HistorySeed {
        require(total in 2..730 && total%2==0) {"G0_HISTORY_FIXTURE_SHAPE"}
        val empty=seedHouseholdAccepted(0)
        val born=empty.today.minusDays(total/2L+1).atStartOfDay(empty.zone).toInstant()
        db.withTransaction {
            check(count("completions")==0 && count("next_request_origins")==0 && count("sync_outbox")==0)
            val sql=db.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            householdHabits=householdHabits.map {row->
                row.copy(createdAt=born.toEpochMilli(),updatedAt=born.toEpochMilli(),
                    planMetadata=requireNotNull(row.planMetadata).copy(creationTimestamp=born.toString(),timezone=empty.zone.id))
                    .also {db.habitDao().update(it)}
            }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        habit=householdHabits.first()
        val currentCore=requireNotNull(tokens.localCoreWriteAccess())
        val core=currentCore.copy(capabilities=currentCore.capabilities?.toSet())
        val currentRegistered=requireNotNull(tokens.localSyncAccess())
        val registered=currentRegistered.copy(capabilities=currentRegistered.capabilities.toSet())
        check(core.session==registered.session && core.capturedDeviceId==registered.deviceId && registered.deviceId!=null)
        val facts=producer().write(core.session) {
            List(total) {index->
                currentCoroutineContext().ensureActive()
                requireDate(empty.today,empty.zone)
                val row=householdHabits[index%2]
                val date=empty.today.minusDays(index/2L+1)
                val occurred=date.atTime(12,0).atZone(empty.zone).toInstant()
                val fact=CompletionEntity(habitId=row.id,habitUuid=row.uuid,date=date.toDisplayMillis(empty.zone),value=1,
                    actualCompletedAt=occurred.toEpochMilli(),createdAt=occurred.toEpochMilli(),
                    recordedTimezone=empty.zone.id,recordedLocalDate=date.toString())
                NextCountDayStore(db).capture(row,fact)
                fact.copy(id=db.completionDao().insertForSync(fact))
            }
        }
        return sessions.exclusive {
            require(tokens.localCoreWriteAccess()==core && tokens.isCurrentSyncAuthority(registered))
            db.withTransaction {
                val entries=facts.map {captureEntryInTransaction(it)}
                val seed=HistorySeed(immutable(householdHabits),empty.today,empty.zone,immutable(entries),core,registered,
                    stableRows())
                requireFootprint(seed,accepted=false)
                authenticate(seed)
                seed
            }
        }
    }

    /** For rejection tests only: capture a real extra original-producer row, without adopting it. */
    internal suspend fun captureHouseholdHistoryEntry(fact:CompletionEntity):HistoryEntry= db.withTransaction {
        captureEntryInTransaction(fact)
    }

    private suspend fun captureEntryInTransaction(fact:CompletionEntity):HistoryEntry {
        check(db.inTransaction())
        val actual=requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid))
        require(actual==fact)
        val queue=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
        val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,queue.operationId))
        val sourceHash=requireNotNull(NextRequestSql.rowHash(db,"sync_outbox","id=?",arrayOf(queue.id)))
        require(sourceHash==NextRequestSql.sourceHash(queue) && sourceHash==origin.sourceHash)
        val row=requireNotNull(db.habitDao().getHabitById(fact.habitId))
        val day=requireNotNull(NextCountDayStore(db).read(row,fact.recordedLocalDate))
        val originHash=requireNotNull(NextRequestSql.rowHash(db,"next_request_origins","kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION,queue.operationId)))
        return HistoryEntry(actual,queue,origin,day,sourceHash,originHash)
    }

    /** All constructed terminal rows and source deletions commit atomically. No HTTP is made.
     * Optional clocks are the ONLY original-terminal input permitted by the differential:
     * requestId -> actual shadow.updatedAt, checked by the test against its real ACK wall window.
     * Payloads, sources, wire bytes, hashes and receipts are never imported from that arm.
     */
    internal suspend fun constructAcceptedHouseholdHistory(seed:HistorySeed,http:NextSyncHttp,
        shadowUpdatedAt:Map<String,Long>?=null) {
        val clocks=shadowUpdatedAt?.toMap()
        require(clocks==null || clocks.keys==seed.entries.map {it.origin.requestId}.toSet() && clocks.values.all {it>=0}) {
            "G0_HISTORY_FIXTURE_CLOCKS"
        }
        sessions.exclusive {
            authenticate(seed)
            db.withTransaction {
                requireFootprint(seed,accepted=false)
                val sql=db.openHelper.writableDatabase
                val dao=db.nextRequestDao()
                val expectedFacts=linkedMapOf<String,CompletionEntity>()
                val expectedShadows=linkedMapOf<String,SyncEntityStateEntity>()
                for((index,entry) in seed.entries.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val operation=decodeNextOperationIntent(entry.origin.intentJson)
                    val device=requireNotNull(seed.registered.deviceId)
                    val wire=encodeSyncRequest(NextSyncPushRequest.serializer(),NextSyncPushRequest(device,listOf(operation)))
                    require(validateNextOperationEnvelope(entry.origin.intentJson,wire,device)==operation)
                    val sent=NextTransmissionEntity(NEXT_OPERATION,entry.origin.requestId,entry.queue.id,5,
                        entry.origin.accountId,requireNotNull(seed.registered.session.serverInstanceId),
                        requireNotNull(seed.registered.session.syncEpoch),device,nextRequestHash(wire),wire)
                    dao.insertTransmission(sent)
                    val response=decodeFrozenSyncRequest(successReply(MaterialSocketServer.Input("POST",
                        "/api/v2/sync/push",emptyMap(),wire)).bytes,NextSyncPushResponse.serializer())
                    val result=response.results.single().copy(status="applied")
                    val change=NextOrdinaryResultMapper.validate(operation,result,device)
                    val row=seed.habits.single {it.uuid==entry.fact.habitUuid}
                    val mapped=NextCommonFactMapper.completion(change,row,entry.fact)
                    check(entry.fact.copy(createdAt=mapped.createdAt)==mapped)
                    val shadow=SyncEntityStateEntity(change.entityType,change.entityUuid,change.revision,
                        payloadJson=change.payload.toString(),payloadHash=syncPayloadHash(change.payload.toString()),
                        updatedAt=clocks?.getValue(entry.origin.requestId) ?: System.currentTimeMillis())
                    NextRequestSql.requireOutboxEnabled(sql)
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                    check(db.completionDao().upsert(mapped)==entry.fact.id)
                    db.syncOutboxDao().upsertState(shadow)
                    sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
                    check(db.completionDao().getCompletionByUuid(entry.fact.uuid)==mapped)
                    check(db.syncOutboxDao().getState("activity_event",entry.fact.uuid)==shadow)
                    expectedFacts[entry.fact.uuid]=mapped
                    expectedShadows[entry.fact.uuid]=shadow
                    val sentHash=requireNotNull(NextRequestSql.rowHash(db,"next_transmissions","kind=? AND requestId=?",
                        arrayOf(NEXT_OPERATION,entry.origin.requestId)))
                    val bytes=encodeSyncRequest(NextSyncOperationResult.serializer(),result)
                    decodeFrozenSyncRequest(bytes,NextSyncOperationResult.serializer())
                    dao.insertAcceptance(NextAcceptanceEntity(NEXT_OPERATION,entry.origin.requestId,entry.originHash,
                        sentHash,nextRequestHash(bytes),bytes.toString(Charsets.UTF_8)))
                    check(NextRequestSql.rowHash(db,"sync_outbox","id=?",arrayOf(entry.queue.id))==entry.sourceHash)
                    db.syncOutboxDao().deleteById(entry.queue.id)
                    historyAfterEntryForTest(index)
                }
                requireFootprint(seed,accepted=true)
                // Final originals, not just the last constructed row, must remain provable.
                val original=sender(http)
                for(entry in seed.entries) {
                    check(db.completionDao().getCompletionByUuid(entry.fact.uuid)==expectedFacts.getValue(entry.fact.uuid))
                    check(db.syncOutboxDao().getState("activity_event",entry.fact.uuid)==expectedShadows.getValue(entry.fact.uuid))
                    original.requireAcceptedCountInTransaction(seed.registered,entry.origin.requestId)
                }
                authenticate(seed)
            }
        }
    }

    /** Independent acceptance proof and full original readers; no candidate store is involved.
     * Original queue snapshots remain necessary: retired-source proof cannot reconstruct them.
     * This validates a complete initial history image, not an image with subsequent new writes.
     */
    internal suspend fun verifyAcceptedHouseholdHistory(seed:HistorySeed,http:NextSyncHttp):Map<String,CountHistory> {
        val histories=sessions.exclusive {
            authenticate(seed)
            db.withTransaction {
                requireFootprint(seed,accepted=true)
                val original=sender(http)
                for(entry in seed.entries) {
                    currentCoroutineContext().ensureActive()
                    original.requireAcceptedCountInTransaction(seed.registered,entry.origin.requestId)
                }
                val reader=CountHistoryReader(db,tokens,sessions)
                val result=seed.habits.associate {row->row.uuid to reader.readInTransaction(row,seed.today)}
                for(row in seed.habits) {
                    val expected=seed.entries.filter {it.fact.habitUuid==row.uuid}
                    val actual=result.getValue(row.uuid)
                    val dates=expected.map {LocalDate.parse(it.fact.recordedLocalDate)}.toSet()
                    assertEquals(expected.map {it.fact.uuid}.toSet(),actual.completions.map {it.uuid}.toSet())
                    assertEquals(expected.size,actual.completions.size)
                    assertEquals(dates.associateWith {1L},actual.quantities)
                    assertEquals(dates.associateWith {CountDayPolicy(row.targetValue,false)},actual.policies)
                    assertEquals(0L,actual.todayQuantity);assertTrue(actual.unknownDates.isEmpty())
                    assertEquals(CountDayPolicy(row.targetValue,false),actual.todayPolicy)
                    assertNull(actual.roundHead)
                }
                authenticate(seed)
                result
            }
        }
        val home=readHome()
        assertEquals(seed.habits.map {it.uuid}.toSet(),home.habits.map {it.uuid}.toSet())
        val reader=CountHistoryReader(db,tokens,sessions)
        val widget=WidgetFactReader(db,tokens,sessions,preferences,reader,CheckHistoryReader(db,tokens,sessions))
        for(row in seed.habits) {
            assertEquals(histories.getValue(row.uuid),home.counts.getValue(row.id))
            assertEquals(histories.getValue(row.uuid),reader.read(row,seed.today))
            val followUp=widget.read(row)
            assertEquals(histories.getValue(row.uuid),followUp.count)
            assertNull(followUp.claim.completionUuid)
        }
        assertEquals(histories.values.flatMap {it.completions}.toSet(),home.completions.toSet())
        sessions.exclusive {authenticate(seed);db.withTransaction {requireFootprint(seed,accepted=true);authenticate(seed)}}
        return histories
    }

    private suspend fun authenticate(seed:HistorySeed) {
        currentCoroutineContext().ensureActive()
        requireDate(seed.today,seed.zone)
        require(!tokens.replicaSwitchPending() && tokens.localCoreWriteAccess()==seed.core &&
            tokens.isCurrentSyncAuthority(seed.registered)) {"G0_HISTORY_FIXTURE_AUTHORITY_CHANGED"}
    }

    private suspend fun requireFootprint(seed:HistorySeed,accepted:Boolean) {
        check(db.inTransaction())
        requireShape(seed)
        NextRequestSql.requireOutboxEnabled(db.openHelper.writableDatabase)
        check(stableRows()==seed.unchanged) {"G0_HISTORY_FIXTURE_UNEXPECTED_TABLE_CHANGE"}
        val n=seed.entries.size
        for(table in listOf("completions","count_days","next_request_origins"))require(count(table)==n)
        require(count("sync_outbox")==if(accepted)0 else n)
        for(table in listOf("next_transmissions","next_acceptances","sync_entity_state"))
            require(count(table)==if(accepted)n else 0)
        require(db.habitDao().getAllHabitsOnce().toSet()==seed.habits.toSet())
        require(db.completionDao().getAllCompletionsOnce().map {it.uuid}.toSet()==seed.entries.map {it.fact.uuid}.toSet())
        val dao=db.nextRequestDao()
        for(entry in seed.entries) {
            currentCoroutineContext().ensureActive()
            require(NextRequestSql.sourceHash(entry.queue)==entry.sourceHash && entry.origin.sourceHash==entry.sourceHash)
            require(dao.origin(NEXT_OPERATION,entry.origin.requestId)==entry.origin)
            require(NextRequestSql.rowHash(db,"next_request_origins","kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION,entry.origin.requestId))==entry.originHash)
            val row=seed.habits.single {it.uuid==entry.fact.habitUuid}
            require(NextCountDayStore(db).read(row,entry.fact.recordedLocalDate)==entry.day)
            if(accepted) {
                val actual=requireNotNull(db.completionDao().getCompletionByUuid(entry.fact.uuid))
                require(actual.copy(createdAt=entry.fact.createdAt)==entry.fact)
                require(dao.transmission(NEXT_OPERATION,entry.origin.requestId)!=null &&
                    dao.acceptance(NEXT_OPERATION,entry.origin.requestId)!=null)
                require(db.syncOutboxDao().getById(entry.queue.id)==null &&
                    db.syncOutboxDao().getByOperationId(entry.origin.requestId)==null)
                require(db.syncOutboxDao().getState("activity_event",entry.fact.uuid)!=null)
            } else {
                require(db.completionDao().getCompletionByUuid(entry.fact.uuid)==entry.fact)
                require(db.syncOutboxDao().getById(entry.queue.id)==entry.queue)
                require(NextRequestSql.rowHash(db,"sync_outbox","id=?",arrayOf(entry.queue.id))==entry.sourceHash)
            }
        }
    }

    private suspend fun requireShape(seed:HistorySeed) {
        requireDate(seed.today,seed.zone)
        require(seed.entries.size in 2..730 && seed.entries.size%2==0 && seed.habits.size==2)
        require(seed.habits.map {it.id}.distinct().size==2 && seed.habits.map {it.uuid}.distinct().size==2)
        require(seed.core.session==seed.registered.session && seed.core.capturedDeviceId==seed.registered.deviceId &&
            seed.registered.deviceId!=null && "facts.append" in seed.registered.capabilities)
        require(seed.habits.all {it.habitType==HabitType.COUNTING && it.completionPolicy=="recurring" &&
            it.appearance!=null && it.planMetadata!=null && it.schedule==HabitSchedule.Daily && !it.isCountdown &&
            it.isActive && it.parentHabitId==null && it.targetCycles==null})
        require(seed.entries.map {it.fact.uuid}.distinct().size==seed.entries.size &&
            seed.entries.map {it.fact.id}.distinct().size==seed.entries.size &&
            seed.entries.map {it.queue.id}.distinct().size==seed.entries.size &&
            seed.entries.map {it.origin.requestId}.distinct().size==seed.entries.size)
        for((index,entry) in seed.entries.withIndex()) {
            val row=seed.habits[index%2];val date=seed.today.minusDays(index/2L+1)
            val fact=entry.fact;val queue=entry.queue;val origin=entry.origin;val day=entry.day
            require(row.createdAt<date.atStartOfDay(seed.zone).toInstant().toEpochMilli())
            require(fact.id>0 && fact.habitId==row.id && fact.habitUuid==row.uuid && fact.value==1 &&
                fact.recordedLocalDate==date.toString() && fact.recordedTimezone==seed.zone.id &&
                fact.actualCompletedAt==date.atTime(12,0).atZone(seed.zone).toInstant().toEpochMilli() &&
                fact.oneTimeAction==null && fact.oneTimeExpectedVersion==null &&
                fact.oneTimeExpectedHeadEventUuid==null && fact.oneTimeRevertsEventUuid==null)
            require(queue.id>0 && queue.recordType=="completion" && queue.action=="upsert" &&
                queue.entityUuid==fact.uuid && queue.wireEntityUuid==fact.uuid && queue.referenceUuid==row.uuid &&
                queue.payloadJson==null && queue.baseRevision==null && queue.basePayloadJson==null &&
                queue.attemptedAt==null && queue.attemptCount==0 && queue.deadLetteredAt==null &&
                queue.lastError==null && queue.errorCode==null)
            require(origin.kind==NEXT_OPERATION && origin.protocol==5 && origin.requestId==queue.operationId &&
                origin.queueId==queue.id && origin.accountId==seed.core.session.authentication.userId &&
                origin.serverInstanceId==seed.core.session.serverInstanceId && origin.syncEpoch==seed.core.session.syncEpoch)
            require(roundOperationIntent(origin.intentJson)==null && countPlanFrontier(origin.intentJson)==null)
            val operation=decodeNextOperationIntent(origin.intentJson)
            require(operation.operationId==origin.requestId && operation.entityType=="activity_event" &&
                operation.entityUuid==fact.uuid && operation.action=="upsert" && operation.baseRevision==null &&
                operation.payload["event_type"]==JsonPrimitive("count_snapshot") &&
                operation.payload["activity_uuid"]==JsonPrimitive(row.uuid) &&
                operation.payload["local_date"]==JsonPrimitive(date.toString()) &&
                NextCommonFactMapper.countPolicy(operation.payload)==CountDayPolicy(row.targetValue,false))
            require(day.habitId==row.id && day.habitUuid==row.uuid && day.localDate==date.toString() &&
                day.firstEventUuid==fact.uuid && day.originKind==NEXT_OPERATION && day.originRequestId==origin.requestId &&
                day.originHash==entry.originHash && day.policy==CountDayPolicy(row.targetValue,false) &&
                day.capturedDeviceId==seed.registered.deviceId && day.planQueueWatermark==queue.id-1 &&
                day.planPredecessorId==null && day.planPredecessorOriginHash==null && day.planPredecessorDependencyHash==null)
        }
    }

    /** Includes sqlite_sequence and every unrelated table, preserving their complete SQL cells. */
    private fun stableRows():Map<String,List<String>> {
        val sql=db.openHelper.writableDatabase
        val mutable=setOf("completions","count_days","sync_outbox","next_request_origins",
            "next_transmissions","next_acceptances","sync_entity_state")
        val tables=sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use {c->
            buildList {while(c.moveToNext())add(c.getString(0))}
        }
        return tables.filter {it !in mutable}.associateWith {table->
            sql.query("SELECT * FROM `${table.replace("`","``")}`").use {c->buildList {
                while(c.moveToNext())add((0 until c.columnCount).joinToString("|") {i->when(c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL->"N"
                    Cursor.FIELD_TYPE_INTEGER->"I${c.getLong(i)}"
                    Cursor.FIELD_TYPE_FLOAT->"F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                    Cursor.FIELD_TYPE_BLOB->"B${Base64.encodeToString(c.getBlob(i),Base64.NO_WRAP)}"
                    else->"S${Base64.encodeToString(c.getString(i).toByteArray(Charsets.UTF_8),Base64.NO_WRAP)}"
                }})
            }.sorted()}
        }
    }
    private fun requireDate(today:LocalDate,zone:ZoneId) {
        require(LocalDate.now(zone)==today && ZoneId.systemDefault()==zone) {"G0_HOUSEHOLD_SEED_DATE_CHANGED"}
    }
    private fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(values.toList())
}
