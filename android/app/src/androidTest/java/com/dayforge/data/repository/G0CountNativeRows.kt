package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import com.dayforge.data.local.entity.*
import java.util.Collections

/** B3b's finite native-P observations. This class owns no authority, root, database handle,
 * retry policy or business validator. The caller supplies a controlled transaction and compares
 * these observations with independently authenticated Q. Frozen witnesses retain exact native
 * OLD cells, not a reserialization of decoded JSON; they cannot authorize a commit by themselves.
 */
internal object G0CountNativeRows {
    const val DAY_ID_INDEX="g0_count_native_day_id"
    const val DAY_UUID_INDEX="g0_count_native_day_uuid"
    val createIndexes:List<String> = Collections.unmodifiableList(listOf(
        "CREATE INDEX $DAY_ID_INDEX ON completions(habitId,recordedLocalDate,id)",
        "CREATE INDEX $DAY_UUID_INDEX ON completions(habitUuid,recordedLocalDate,id)")+G0CountSourceScopeSql.createIndexes)
    private const val MAX_ROWS=4096
    private const val MAX_QUERIES=2048
    private const val MAX_BYTES=64L*1024*1024
    private const val MAX_ROW_BYTES=SYNC_REQUEST_LIMIT+4096
    internal data class Field(val name:String,val type:Int,val nullable:Boolean=false,
        val intRange:Boolean=false,val bool:Boolean=false)
    private fun i(name:String,nullable:Boolean=false,intRange:Boolean=false,bool:Boolean=false)=
        Field(name,Cursor.FIELD_TYPE_INTEGER,nullable,intRange,bool)
    private fun s(name:String,nullable:Boolean=false)=Field(name,Cursor.FIELD_TYPE_STRING,nullable)
    private fun b(name:String)=Field(name,Cursor.FIELD_TYPE_BLOB)
    private val layouts=mapOf(
        "habits" to listOf(i("id"),s("name"),s("description"),s("habitType"),i("iconResId",intRange=true),s("colorHex"),s("schedule"),i("targetValue",intRange=true),i("isCountdown",bool=true),i("isActive",bool=true),s("uuid"),s("parentHabitId",true),i("targetCycles",true,true),s("failMode"),i("goalSuccess",true,bool=true),i("activityRate",intRange=true),i("activityRateUpdatedAt"),i("bestTime",true),i("createdAt"),i("updatedAt"),s("completionPolicy",true),i("oneTimeConfirmedVersion",true,true),s("oneTimeConfirmedHeadEventUuid",true),s("oneTimeConfirmedCompletionEventUuid",true),s("appearance",true),s("planMetadata",true)),
        "completions" to listOf(i("id"),i("habitId"),i("date"),i("value",intRange=true),i("actualCompletedAt",true),s("uuid"),s("habitUuid",true),i("createdAt"),s("recordedTimezone"),s("recordedLocalDate"),s("timeMetadataSource"),s("oneTimeAction",true),i("oneTimeExpectedVersion",true,true),s("oneTimeExpectedHeadEventUuid",true),s("oneTimeRevertsEventUuid",true)),
        "count_days" to listOf(i("habitId"),s("habitUuid"),s("localDate"),i("targetValue",intRange=true),i("isCountdown",bool=true),s("firstEventUuid"),s("originRequestId",true),s("originHash",true),s("planPredecessorId",true),s("planPredecessorOriginHash",true),s("planPredecessorDependencyHash",true),i("planQueueWatermark",true),s("capturedDeviceId",true),s("originKind")),
        "sync_outbox" to listOf(i("id"),s("operationId"),s("recordType"),s("entityUuid"),s("wireEntityUuid"),s("action"),s("referenceUuid",true),s("payloadJson",true),i("baseRevision",true),s("basePayloadJson",true),i("attemptedAt",true),i("attemptCount",intRange=true),s("lastError",true),s("errorCode",true),i("deadLetteredAt",true),i("createdAt")),
        "next_request_origins" to listOf(s("kind"),s("requestId"),i("queueId"),i("protocol",intRange=true),s("accountId"),s("serverInstanceId",true),s("syncEpoch",true),s("sourceHash"),s("intentJson")),
        "next_transmissions" to listOf(s("kind"),s("requestId"),i("queueId"),i("protocol",intRange=true),s("accountId"),s("serverInstanceId"),s("syncEpoch"),s("deviceId"),s("wireHash"),b("wireBytes")),
        "next_acceptances" to listOf(s("kind"),s("requestId"),s("originHash"),s("transmissionHash"),s("resultHash"),s("resultJson")),
        "sync_entity_state" to listOf(s("entityType"),s("entityUuid"),i("revision"),i("deleted",bool=true),s("payloadJson",true),s("payloadHash",true),i("updatedAt")),
        "sync_control" to listOf(i("id"),i("suppressOutbox",bool=true)),
        "sqlite_sequence" to listOf(s("name"),i("seq")))

    /** SQL is code-owned and exact. Every bound is a sentinel (maxRows + 1); overflow fails. */
    class Query internal constructor(val table:String,val types:String,val maxRows:Int,
        where:String="",order:String="",index:String?=null) {
        internal val fields=layouts.getValue(table)
        private val suffix=" FROM main.`$table`"+(index?.let {" INDEXED BY `$it`"} ?: "")+
            (if(where.isEmpty())"" else " WHERE $where")+(if(order.isEmpty())"" else " ORDER BY $order")+" LIMIT ${maxRows+1}"
        private val length=fields.joinToString("+") {"COALESCE(length(CAST(`${it.name}` AS BLOB)),0)"}
        internal val metadata="SELECT "+fields.joinToString(",") {"typeof(`${it.name}`)"}+",($length)"+suffix
        internal val textFields=fields.withIndex().filter {it.value.type==Cursor.FIELD_TYPE_STRING}
        internal val select="SELECT "+fields.joinToString(",") {"`${it.name}`"}+
            textFields.joinToString("") {",CAST(`${it.value.name}` AS BLOB)"}+suffix
    }
    val habitId=Query("habits","I",1,"id=?")
    val habitUuid=Query("habits","S",1,"uuid=?")
    val currentHabits=Query("habits","",2,order="id")
    val factId=Query("completions","I",1,"id=?")
    val factUuid=Query("completions","S",1,"uuid=?")
    val factsDayId=Query("completions","IS",736,"habitId=? AND recordedLocalDate=?","id",DAY_ID_INDEX)
    val factsDayUuid=Query("completions","SS",736,"habitUuid=? AND recordedLocalDate=?","id",DAY_UUID_INDEX)
    val day=Query("count_days","IS",1,"habitId=? AND localDate=?")
    val queueId=Query("sync_outbox","I",1,"id=?")
    val queueOperation=Query("sync_outbox","S",1,"operationId=?")
    val queueEntity=Query("sync_outbox","SS",1,"recordType=? AND entityUuid=?")
    val currentQueues=Query("sync_outbox","",128,order="id")
    val origin=Query("next_request_origins","SS",1,"kind=? AND requestId=?")
    val originQueue=Query("next_request_origins","SI",1,"kind=? AND queueId=?")
    val transmission=Query("next_transmissions","SS",1,"kind=? AND requestId=?")
    val transmissionQueue=Query("next_transmissions","SI",1,"kind=? AND queueId=?")
    val receipt=Query("next_acceptances","SS",1,"kind=? AND requestId=?")
    val shadow=Query("sync_entity_state","SS",1,"entityType=? AND entityUuid=?")
    val originsDay=Query("next_request_origins","SS",736,G0CountSourceScopeSql.origins.where,
        "requestId",G0CountSourceScopeSql.ORIGIN_INDEX)
    val shadowsDay=Query("sync_entity_state","SS",736,G0CountSourceScopeSql.shadows.where,
        "entityUuid",G0CountSourceScopeSql.SHADOW_INDEX)
    val sequences=Query("sqlite_sequence","",64,order="name,rowid")
    val control=Query("sync_control","",1,order="id")
    private val queries=listOf(habitId,habitUuid,currentHabits,factId,factUuid,factsDayId,factsDayUuid,day,
        queueId,queueOperation,queueEntity,currentQueues,origin,originQueue,transmission,transmissionQueue,receipt,shadow,originsDay,shadowsDay,sequences,control)
    /** These tables are bounded by the already-issued plain profile. Reopening cannot silently
     * clear them. Physical schema/dirty protection remains owned by the root's cold owner. */
    val recoveryPresenceSql:List<String> = Collections.unmodifiableList(listOf("next_replica_fence","next_recovery_state","next_recovery_audits",
        "next_config_imports","next_abandoned_requests").map {"SELECT 1 FROM main.`$it` LIMIT 1"})
    val reads:Map<String,String> = Collections.unmodifiableMap(buildMap {
        queries.forEach {put(it.metadata,it.types);put(it.select,it.types)}
        recoveryPresenceSql.forEach {put(it,"")}
        G0CountSourceScopeSql.unsupportedReads.forEach {put(it,"")}
    })

    internal data class Observation(val query:Query,val args:List<Any>,val rows:List<List<G0DomainJournal.Cell>>)
    class Witness private constructor(private val observed:List<Observation>,private val recoveryEmpty:Boolean,private val sourceScoped:Boolean) {
        fun verify(sql:SupportSQLiteDatabase,checkCancelled:()->Unit={}) {
            check(sql.inTransaction()) {"G0_COUNT_NATIVE_TRANSACTION_REQUIRED"};checkCancelled()
            val budget=Budget(checkCancelled)
            observed.forEach { check(read(sql,it.query,it.args,budget)==it.rows) {"G0_COUNT_NATIVE_CHANGED"} }
            if(recoveryEmpty)requireNoRecovery(sql)
            if(sourceScoped)requireSourceScope(sql,checkCancelled)
        }
        companion object {
            internal fun of(observed:List<Observation>,recoveryEmpty:Boolean,sourceScoped:Boolean)=Witness(frozen(observed),recoveryEmpty,sourceScoped)
        }
    }
    class Session(private val sql:SupportSQLiteDatabase,checkCancelled:()->Unit={}) {
        private val observed=mutableListOf<Observation>()
        private val budget=Budget(checkCancelled)
        private var closed=false
        private var recoveryEmpty=false
        private var sourceScoped=false
        fun rows(query:Query,vararg args:Any):List<List<G0DomainJournal.Cell>> {
            check(!closed);check(sql.inTransaction()) {"G0_COUNT_NATIVE_TRANSACTION_REQUIRED"};budget.poll()
            val frozenArgs=frozen(args.toList())
            observed.singleOrNull {it.query===query && it.args==frozenArgs}?.let {return it.rows}
            val result=read(sql,query,frozenArgs,budget)
            observed+=Observation(query,frozenArgs,result)
            return result
        }
        private fun expect(query:Query,expected:List<List<G0DomainJournal.Cell>>,vararg args:Any) {
            check(rows(query,*args)==expected) {"G0_COUNT_NATIVE_MISMATCH:${query.table}"}
        }
        fun requireHabit(expected:HabitEntity):List<G0DomainJournal.Cell> {
            val actual=rows(habitId,expected.id).singleOrNull() ?: error("G0_COUNT_NATIVE_HABIT_MISSING")
            check(decodeHabit(actual)==expected) {"G0_COUNT_NATIVE_HABIT_CHANGED"}
            expect(habitUuid,listOf(actual),expected.uuid)
            return actual
        }
        fun requireCurrent(habits:List<HabitEntity>,pending:List<SyncOutboxEntity>,lastFactId:Long,
            lastQueueId:Long,maxPending:Int=128) {
            require(lastFactId>=0 && lastQueueId>=0)
            require(habits.size==2 && habits.map {it.id}.distinct().size==2 && habits.map {it.uuid}.distinct().size==2)
            require(maxPending in 0..128 && pending.size<=maxPending &&
                pending.map {it.id}.distinct().size==pending.size && pending.map {it.operationId}.distinct().size==pending.size)
            val expectedHabits=habits.sortedBy {it.id}.map(::requireHabit)
            expect(currentHabits,expectedHabits)
            expect(currentQueues,pending.sortedBy {it.id}.map {cells(queueValues(it))})
            check(rows(currentQueues).size<=maxPending) {"G0_COUNT_PENDING_BUDGET"}
            expect(control,listOf(cells(listOf(1L,0L))))
            val sequenceRows=rows(sequences)
            val names=hashSetOf<String>();val values=linkedMapOf<String,Long>()
            sequenceRows.forEach { row->
                val name=row[0].value as String;val value=row[1].value as Long
                check(names.add(name) && value>=0) {"G0_COUNT_NATIVE_SEQUENCE"};values[name]=value
            }
            check((values["completions"] ?: 0L)==lastFactId &&
                (values["sync_outbox"] ?: 0L)==lastQueueId) {"G0_COUNT_SEQUENCE_CHANGED"}
            requireNoRecovery(sql);recoveryEmpty=true
            sourceScope()
        }
        /** Full members of the touched day under BOTH ID and UUID ownership dimensions.
         * An authenticated absence must match a physically absent row/range. */
        fun requireDay(habitId:Long,habitUuid:String,date:String,expected:CountDayEntity?,facts:List<CompletionEntity>) {
            require(facts.size<=736 && facts.all {it.habitId==habitId && it.habitUuid==habitUuid && it.recordedLocalDate==date})
            require(facts.map {it.id}.distinct().size==facts.size && facts.map {it.uuid}.distinct().size==facts.size)
            require(expected==null || expected.habitId==habitId && expected.habitUuid==habitUuid && expected.localDate==date)
            expect(day,listOfNotNull(expected?.let {cells(dayValues(it))}),habitId,date)
            val wanted=facts.sortedBy {it.id}.map {cells(factValues(it))}
            expect(factsDayId,wanted,habitId,date);expect(factsDayUuid,wanted,habitUuid,date)
            // UUID uniqueness is global rather than limited to this date's range.
            facts.forEach {expect(factUuid,listOf(cells(factValues(it))),it.uuid)}
        }
        private fun sourceScope() {
            check(!closed);check(sql.inTransaction());budget.poll()
            if(!sourceScoped) {requireSourceScope(sql,budget.poll);sourceScoped=true}
        }
        /** Complete source membership for this object/day, including an authenticated empty
         * day. Existing days receive the same check: extra or orphan P must not be ignored.
         * Unindexable JSON is rejected in its own global indexed bucket, never filtered out.
         */
        fun requireDaySources(habitUuid:String,date:String,sources:List<G0CountBusinessBlockCodec.Source>) {
            require(sources.size<=736 && sources.map {it.origin.requestId}.distinct().size==sources.size)
            sourceScope()
            val expectedOrigins=sources.map {it.origin}.sortedBy {it.requestId}.map {cells(originValues(it))}
            val expectedShadows=sources.mapNotNull {it.shadow}.sortedBy {it.entityUuid}.map {cells(shadowValues(it))}
            expect(originsDay,expectedOrigins,habitUuid,date)
            expect(shadowsDay,expectedShadows,habitUuid,date)
        }
        fun requireDayRow(expected:CountDayEntity) {
            expect(day,listOf(cells(dayValues(expected))),expected.habitId,expected.localDate)
        }
        fun requireSource(source:G0CountBusinessBlockCodec.Source,fact:CompletionEntity) {
            val o=source.origin
            expect(factId,listOf(cells(factValues(fact))),fact.id)
            expect(factUuid,listOf(cells(factValues(fact))),fact.uuid)
            expect(origin,listOf(cells(originValues(o))),o.kind,o.requestId)
            expect(originQueue,listOf(cells(originValues(o))),o.kind,o.queueId)
            val q=source.pending?.let {cells(queueValues(it))}
            expect(queueId,listOfNotNull(q),o.queueId)
            expect(queueOperation,listOfNotNull(q),o.requestId)
            expect(queueEntity,listOfNotNull(q),"completion",fact.uuid)
            val t=source.transmission?.let {cells(transmissionValues(it))}
            expect(transmission,listOfNotNull(t),o.kind,o.requestId)
            expect(transmissionQueue,listOfNotNull(t),o.kind,o.queueId)
            expect(receipt,listOfNotNull(source.receipt?.let {cells(receiptValues(it))}),o.kind,o.requestId)
            expect(shadow,listOfNotNull(source.shadow?.let {cells(shadowValues(it))}),"activity_event",fact.uuid)
        }
        fun requireAbsent(factId:Long,factUuid:String,queueId:Long,requestId:String) {
            expect(G0CountNativeRows.factId,emptyList(),factId)
            expect(G0CountNativeRows.factUuid,emptyList(),factUuid)
            expect(G0CountNativeRows.queueId,emptyList(),queueId)
            expect(queueOperation,emptyList(),requestId)
            expect(queueEntity,emptyList(),"completion",factUuid)
            expect(origin,emptyList(),NEXT_OPERATION,requestId)
            expect(originQueue,emptyList(),NEXT_OPERATION,queueId)
            expect(transmission,emptyList(),NEXT_OPERATION,requestId)
            expect(transmissionQueue,emptyList(),NEXT_OPERATION,queueId)
            expect(receipt,emptyList(),NEXT_OPERATION,requestId)
            expect(shadow,emptyList(),"activity_event",factUuid)
        }
        fun freeze():Witness {
            check(!closed);check(sql.inTransaction()) {"G0_COUNT_NATIVE_TRANSACTION_REQUIRED"};budget.poll();closed=true
            return Witness.of(observed,recoveryEmpty,sourceScoped)
        }
    }
    private class Budget(val poll:()->Unit) {
        var rows=0;var queries=0;var bytes=0L
        fun reserve(size:Long) {
            check(size>=0 && size<=MAX_BYTES-bytes) {"G0_COUNT_NATIVE_BYTE_BUDGET"};bytes+=size
        }
    }
    /** Preserves the issued Current profile after NEW native P is written and before
     * signing a replacement H (also before the first root-write issuance). Read-only:
     * failure must escape the owning transaction, never repair/clear or close the database.
     */
    internal fun requireIndexableSources(sql:SupportSQLiteDatabase,checkCancelled:()->Unit={}) {
        requireSourceScope(sql,checkCancelled)
    }
    private fun requireSourceScope(sql:SupportSQLiteDatabase,poll:()->Unit) {
        check(sql.inTransaction())
        G0CountSourceScopeSql.unsupportedReads.forEach {statement->
            poll();sql.query(statement).use {check(!it.moveToFirst()) {"G0_COUNT_SOURCE_SCOPE_UNSUPPORTED"}}
        }
    }
    private fun requireNoRecovery(sql:SupportSQLiteDatabase) {
        check(sql.inTransaction())
        recoveryPresenceSql.forEach {statement->sql.query(statement).use {
            check(!it.moveToFirst()) {"G0_COUNT_NATIVE_RECOVERY"}
        }}
    }
    private fun read(sql:SupportSQLiteDatabase,query:Query,args:List<Any>,budget:Budget):List<List<G0DomainJournal.Cell>> {
        check(sql.inTransaction()) {"G0_COUNT_NATIVE_TRANSACTION_REQUIRED"}
        require(query in queries && args.size==query.types.length)
        args.forEachIndexed {n,v->require(if(query.types[n]=='I')v is Long else v is String)}
        budget.poll()
        check(++budget.queries<=MAX_QUERIES) {"G0_COUNT_NATIVE_QUERY_BUDGET"}
        val nativeArgs=args.toTypedArray()
        val metadata=sql.query(query.metadata,nativeArgs).use {c->buildList {
            while(c.moveToNext()) {
                budget.poll()
                check(size<query.maxRows) {"G0_COUNT_NATIVE_ROW_LIMIT"}
                check(++budget.rows<=MAX_ROWS) {"G0_COUNT_NATIVE_ROW_BUDGET"}
                val types=query.fields.mapIndexed {index,field->
                    check(c.getType(index)==Cursor.FIELD_TYPE_STRING)
                    val observed=c.getString(index)
                    val type=when(observed) {"null"->Cursor.FIELD_TYPE_NULL;"integer"->Cursor.FIELD_TYPE_INTEGER
                        "text"->Cursor.FIELD_TYPE_STRING;"blob"->Cursor.FIELD_TYPE_BLOB;"real"->Cursor.FIELD_TYPE_FLOAT
                        else->error("G0_COUNT_NATIVE_TYPE")}
                    check(type==field.type || type==Cursor.FIELD_TYPE_NULL && field.nullable) {"G0_COUNT_NATIVE_TYPE"}
                    type
                }
                check(c.getType(query.fields.size)==Cursor.FIELD_TYPE_INTEGER)
                val bytes=c.getLong(query.fields.size)
                check(bytes in 0..MAX_ROW_BYTES.toLong()) {"G0_COUNT_NATIVE_ROW_BYTES"}
                // Text is retained once as native bytes and once as String observations.
                budget.reserve(Math.addExact(Math.multiplyExact(bytes,3L),query.fields.size*24L))
                add(types)
            }
        }}
        if(metadata.isEmpty())return emptyList()
        val result=sql.query(query.select,nativeArgs).use {c->buildList {
            check(c.columnCount==query.fields.size+query.textFields.size)
            while(c.moveToNext()) {
                budget.poll()
                check(size<metadata.size) {"G0_COUNT_NATIVE_MEMBERS_CHANGED"}
                val expectedTypes=metadata[size]
                val row=query.fields.mapIndexed {index,field->
                    budget.poll()
                    val type=c.getType(index);check(type==expectedTypes[index]) {"G0_COUNT_NATIVE_TYPE_CHANGED"}
                    val value:Any?=when(type) {
                        Cursor.FIELD_TYPE_NULL->null
                        Cursor.FIELD_TYPE_INTEGER->c.getLong(index).also {
                            if(field.intRange)check(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {"G0_COUNT_NATIVE_INT_RANGE"}
                            if(field.bool)check(it in 0L..1L) {"G0_COUNT_NATIVE_BOOL"}
                        }
                        Cursor.FIELD_TYPE_STRING->c.getString(index)
                        Cursor.FIELD_TYPE_BLOB->frozen(c.getBlob(index).toList())
                        else->error("G0_COUNT_NATIVE_TYPE")
                    }
                    G0DomainJournal.Cell(type,value)
                }
                query.textFields.forEachIndexed {ordinal,field->
                    val index=query.fields.size+ordinal
                    if(row[field.index].type==Cursor.FIELD_TYPE_NULL)check(c.isNull(index))
                    else {
                        check(c.getType(index)==Cursor.FIELD_TYPE_BLOB)
                        check((row[field.index].value as String).toByteArray(Charsets.UTF_8).contentEquals(c.getBlob(index))) {
                            "G0_COUNT_NATIVE_TEXT_BYTES"
                        }
                    }
                }
                add(frozen(row))
            }
            check(size==metadata.size) {"G0_COUNT_NATIVE_MEMBERS_CHANGED"}
        }}
        return frozen(result)
    }
    fun decodeHabit(row:List<G0DomainJournal.Cell>):HabitEntity {
        require(row.size==26)
        fun long(n:Int)=row[n].value as Long
        fun int(n:Int)=long(n).also {require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()
        fun nullableLong(n:Int)=row[n].value as Long?
        fun nullableInt(n:Int)=nullableLong(n)?.also {require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}?.toInt()
        fun str(n:Int)=row[n].value as String
        fun nullableStr(n:Int)=row[n].value as String?
        fun bool(n:Int)=long(n).also {require(it in 0L..1L)}==1L
        val converter=HabitTypeConverter()
        val parsed=HabitEntity(long(0),str(1),str(2),converter.toHabitType(str(3)),int(4),str(5),
            converter.toSchedule(str(6)),int(7),bool(8),bool(9),str(10),nullableStr(11),nullableInt(12),
            converter.toFailMode(str(13)),nullableLong(14)?.also {require(it in 0L..1L)}?.let {it==1L},
            int(15),long(16),nullableLong(17),long(18),long(19),nullableStr(20),nullableInt(21),
            nullableStr(22),nullableStr(23),converter.toAppearance(nullableStr(24)),converter.toPlanMetadata(nullableStr(25)))
        return parsed.copy(schedule=when(val schedule=parsed.schedule) {
            is com.dayforge.data.model.HabitSchedule.Weekly->schedule.copy(daysOfWeek=Collections.unmodifiableList(schedule.daysOfWeek.toList()))
            else->schedule
        })
    }
    fun cells(values:List<Any?>):List<G0DomainJournal.Cell> = frozen(values.map {when(it) {
        null->G0DomainJournal.Cell(0,null)
        is String->G0DomainJournal.Cell(3,it)
        is Int->G0DomainJournal.Cell(1,it.toLong())
        is Long->G0DomainJournal.Cell(1,it)
        is Double->G0DomainJournal.Cell(2,java.lang.Double.doubleToRawLongBits(it))
        is ByteArray->G0DomainJournal.Cell(4,frozen(it.toList()))
        else->error("G0_COUNT_NATIVE_TYPE")
    }})
    fun native(cell:G0DomainJournal.Cell):Any?=when(cell.type) {
        2->java.lang.Double.longBitsToDouble(cell.value as Long)
        4->(cell.value as List<*>).map {it as Byte}.toByteArray()
        else->cell.value
    }
    fun effect(effects:MutableList<G0DomainJournal.Change>,table:String,old:List<Any?>?,new:List<Any?>?) {
        effects+=G0DomainJournal.Change(0,table,if(old==null)"INSERT" else if(new==null)"DELETE" else "UPDATE",
            old?.let(::cells),new?.let(::cells))
    }
    fun dayValues(r:CountDayEntity)=listOf(r.habitId,r.habitUuid,r.localDate,r.targetValue,if(r.isCountdown)1L else 0L,
        r.firstEventUuid,r.originRequestId,r.originHash,r.planPredecessorId,r.planPredecessorOriginHash,
        r.planPredecessorDependencyHash,r.planQueueWatermark,r.capturedDeviceId,r.originKind)
    fun factValues(r:CompletionEntity)=listOf(r.id,r.habitId,r.date,r.value,r.actualCompletedAt,r.uuid,r.habitUuid,r.createdAt,r.recordedTimezone,r.recordedLocalDate,r.timeMetadataSource,r.oneTimeAction,r.oneTimeExpectedVersion,r.oneTimeExpectedHeadEventUuid,r.oneTimeRevertsEventUuid)
    fun queueValues(r:SyncOutboxEntity)=listOf(r.id,r.operationId,r.recordType,r.entityUuid,r.wireEntityUuid,r.action,r.referenceUuid,r.payloadJson,r.baseRevision,r.basePayloadJson,r.attemptedAt,r.attemptCount,r.lastError,r.errorCode,r.deadLetteredAt,r.createdAt)
    fun originValues(r:NextRequestOriginEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.sourceHash,r.intentJson)
    fun transmissionValues(r:NextTransmissionEntity)=listOf(r.kind,r.requestId,r.queueId,r.protocol,r.accountId,r.serverInstanceId,r.syncEpoch,r.deviceId,r.wireHash,r.wireBytes)
    fun receiptValues(r:NextAcceptanceEntity)=listOf(r.kind,r.requestId,r.originHash,r.transmissionHash,r.resultHash,r.resultJson)
    fun shadowValues(r:SyncEntityStateEntity)=listOf(r.entityType,r.entityUuid,r.revision,if(r.deleted)1L else 0L,r.payloadJson,r.payloadHash,r.updatedAt)
    private fun <T> frozen(value:List<T>):List<T> = Collections.unmodifiableList(ArrayList(value))
}
