package com.dayforge.data.repository

import android.database.sqlite.SQLiteTransactionListener
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.validateNextSyncOperation
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.service.AccountSessionCoordinator
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive

/** Derived only from a separate locked Room/callback reference, never candidate SQL. */
internal data class G0RecoverySchema(val main:List<List<String?>>,val requiredTemp:Set<List<String?>>,
    val allowedTemp:Set<List<String?>>) {
    fun requireMainMatches(sql:SupportSQLiteDatabase) {
        val actual=g0Schema(sql).map {listOf("main",it.type,it.name,it.table,it.sql)}
        require(actual==main) {"G0_RECOVERY_SCHEMA_CHANGED"}
    }
    fun requireMatches(sql:SupportSQLiteDatabase) {
        val actual=G0DomainJournal.trustedSchema(sql)
        val temp=actual.filter {it.first()=="temp"}.toSet()
        require(actual.filter {it.first()=="main"}==main && temp.containsAll(requiredTemp) && allowedTemp.containsAll(temp)) {
            "G0_RECOVERY_SCHEMA_CHANGED"
        }
    }
}

/** Explicit G0 owner/connection recovery. Neither a persisted UI command nor a
 * production recovery service. A request-ID hint only locates current disk evidence;
 * it grants no authority, and no old delivery/certificate is accepted here.
 */
internal class G0HouseholdRecovery(
    private val previous:G0HouseholdCountRuntime,
    private val connection:G0CountConnectionOwner,
    private val tokens:TokenManager,
    private val sessions:AccountSessionCoordinator,
    private val authority:G0AuthorityOwner,
    private val http:NextSyncHttp,
    private val schema:G0RecoverySchema,
    private val openOriginal:suspend (CoroutineContext,(SupportSQLiteOpenHelper.Factory)->SupportSQLiteOpenHelper.Factory)->HabitDatabase,
    private val profile:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128
) {
    data class Result(val requestId:String,val outcome:NextOperationAcceptance)
    data class Report(val results:List<Result>,val facts:Int,val accepted:Int)
    private data class Entry(val origin:NextRequestOriginEntity,val accepted:Boolean,val wire:List<Byte>?)
    private data class Manifest(val entries:List<Entry>,val facts:Set<String>)
    private val used=AtomicBoolean()
    @Volatile var opened=false;private set
    @Volatile var drained=false;private set
    @Volatile var error:Throwable?=null;private set
    var beforeRequestForTest:suspend (String)->Unit={}
    var beforeOuterSuccessForTest:()->Unit={}
    var beforeOuterEndForTest:()->Unit={}
    var beforeInvalidationQueryForTest:()->Unit={}
    var afterActualOuterEndForTest:()->Unit={}

    suspend fun run(requestIdHint:String?=null):Report {
        check(used.compareAndSet(false,true)) {"G0_RECOVERY_ALREADY_ATTEMPTED"}
        require(requestIdHint==null || isContractUuid(requestIdHint)) {"G0_RECOVERY_HINT_UNKNOWN"}
        // The coordinator itself joins the old lifetime and its actual close callback.
        check(previous.shutdown() && previous.drained) {"G0_RECOVERY_PREVIOUS_NOT_DRAINED"}
        currentCoroutineContext().ensureActive()
        val ticket=connection.acquire() ?: error("G0_RECOVERY_CONNECTION_BUSY")
        var subscription:AutoCloseable?=null
        val failures=CopyOnWriteArrayList<Throwable>()
        val business=AtomicReference<Job?>()
        // Room creates its own long-lived SupervisorJob under this owner. It cannot
        // be a child of the business scope, which must finish BEFORE Room is closed.
        val roomOwner=SupervisorJob()
        val roomContext=Dispatchers.IO+roomOwner+CoroutineExceptionHandler {_,problem->
            failures+=problem
            business.get()?.cancel(CancellationException("G0_RECOVERY_ROOM_FAILED"))
            roomOwner.cancel()
        }
        var report:Report?=null
        var businessFailure:Throwable?=null
        try {report=coroutineScope {
            val job=currentCoroutineContext().job
            business.set(job)
            subscription=authority.observeReservations {
                job.cancel(CancellationException("G0_RECOVERY_AUTHORITY_CHANGED"))
                roomOwner.cancel()
            }
            val lease=authority.lease()
            val core=requireNotNull(tokens.localCoreWriteAccess())
            val access=requireNotNull(tokens.localSyncAccess()).let {it.copy(capabilities=it.capabilities.toSet())}
            require(core.session==access.session && core.capturedDeviceId==access.deviceId && access.deviceId!=null &&
                core.capabilities==access.capabilities && "facts.append" in access.capabilities && "sync.read" in access.capabilities)
            authenticate(core,access,lease)
            val openingPermit=AtomicReference<AutoCloseable?>()
            val dataVersion=AtomicLong(-1L)
            val db=openOriginal(roomContext) {
                val expected=schema.main.map {G0SchemaRow(requireNotNull(it[1]),requireNotNull(it[2]),requireNotNull(it[3]),it[4])}
                val cold=G0ColdOpenFactory(expected,beforeRoomOpen={sql->
                    authority.checkLease(lease)
                    NextRequestSql.requireOutboxEnabled(sql)
                    check(openingPermit.get()==null)
                    openingPermit.set(authority.finalPermit(lease))
                    check(sql.inTransaction())
                    dataVersion.set(g0Number(sql,"PRAGMA main.data_version"))
                },baseline={sql->
                    NextRequestSql.requireOutboxEnabled(sql)
                    check(g0Number(sql,"PRAGMA main.data_version")==dataVersion.get()) {"G0_RECOVERY_EXTERNAL_CHANGE"}
                },afterActualEnd={openingPermit.getAndSet(null)?.close()})
                G0RecoveryCommitFactory(cold,authority,lease,schema,
                    beforeOuterSuccess={beforeOuterSuccessForTest()},dataVersion=dataVersion,
                    afterClosed={openingPermit.getAndSet(null)?.close()},removeColdNonce=true,
                    beforeOuterEnd={beforeOuterEndForTest()},beforeInvalidationQuery={beforeInvalidationQueryForTest()},
                    afterActualOuterEnd={afterActualOuterEndForTest()})
            }
            opened=true
            val original=NextCoreRequestStore(db,tokens,sessions,http)
            val before=manifest(db,original,core,access,lease)
            require(requestIdHint==null || before.entries.any {it.origin.requestId==requestIdHint}) {"G0_RECOVERY_HINT_UNKNOWN"}
            val selected=before.entries.filter {!it.accepted || it.origin.requestId==requestIdHint}
            val results=ArrayList<Result>()
            for(entry in selected) {
                currentCoroutineContext().ensureActive()
                beforeRequestForTest(entry.origin.requestId)
                authenticate(core,access,lease)
                // The original path obtains current credentials and validates live source,
                // frozen wire, or persisted receipt. HTTP is OUTSIDE every final permit.
                val outcome=requireNotNull(original.sendAndAcceptOperation(access,entry.origin.requestId))
                authenticate(core,access,lease)
                results+=Result(entry.origin.requestId,outcome)
            }
            val after=manifest(db,original,core,access,lease)
            require(after.facts==before.facts && after.entries.map {it.origin}==before.entries.map {it.origin})
            require(after.entries.all {it.accepted}) {"G0_RECOVERY_NOT_DRAINED"}
            val byId=after.entries.associateBy {it.origin.requestId}
            before.entries.forEach {entry->entry.wire?.let {require(byId.getValue(entry.origin.requestId).wire==it)}}
            authenticate(core,access,lease)
            Report(results.toList(),after.facts.size,after.entries.size)
        }} catch(problem:Throwable) {
            businessFailure=problem
            failures+=problem
        } finally {
            // Room may deliver caller cancellation before its transaction runBlocking
            // child finishes. The inner scope joins that actual child before close or
            // ticket release; its body-level finally would be too early.
            subscription?.close()
            withContext(NonCancellable) {
                // A Room invalidation launch has a separate supervisor. Join it too;
                // its exception handler records failures without escaping to Android.
                roomOwner.cancelAndJoin()
                try {ticket.drain();drained=true}
                catch(close:Throwable) {failures+=close}
            }
        }
        try {currentCoroutineContext().ensureActive()} catch(cancelled:CancellationException) {failures+=cancelled}
        // Even a completed business result cannot escape a failed Room background
        // transaction. Preserve original failures after all their owners have joined.
        val primary=businessFailure?.takeUnless {it is CancellationException} ?:
            failures.firstOrNull {it !is CancellationException} ?: businessFailure ?: failures.firstOrNull()
        if(primary!=null) {
            fun contains(root:Throwable,target:Throwable,seen:MutableSet<Throwable>):Boolean {
                if(root===target)return true
                if(!seen.add(root))return false
                return root.cause?.let {contains(it,target,seen)}==true || root.suppressed.any {contains(it,target,seen)}
            }
            failures.forEach {failure->
                if(failure!==primary && !contains(primary,failure,mutableSetOf()) &&
                    !contains(failure,primary,mutableSetOf()))primary.addSuppressed(failure)
            }
            error=primary
            throw primary
        }
        return checkNotNull(report)
    }

    private suspend fun authenticate(core:LocalCoreWriteAccess,access:LocalSyncAccess,lease:G0AuthorityOwner.Lease) {
        currentCoroutineContext().ensureActive();authority.checkLease(lease)
        require(!tokens.replicaSwitchPending() && tokens.localCoreWriteAccess()==core && tokens.isCurrentSyncAuthority(access)) {
            "G0_RECOVERY_AUTHORITY_CHANGED"
        }
        authority.checkLease(lease)
    }

    /** Complete current local-only count scope, including absent/extra members. Limits
     * match the selected Store experiment; no prefix LIMIT or 128-row truncation. */
    private suspend fun manifest(db:HabitDatabase,original:NextCoreRequestStore,core:LocalCoreWriteAccess,
        access:LocalSyncAccess,lease:G0AuthorityOwner.Lease):Manifest=sessions.exclusive {
        authenticate(core,access,lease)
        db.withTransaction {
            val sql=db.openHelper.writableDatabase
            NextRequestSql.requireOutboxEnabled(sql);NextChallengeStore(db).requirePlainInTransaction()
            val permitted=setOf("habits","metrics","completions","count_days","sync_outbox","sync_control",
                "next_request_origins","next_transmissions","next_acceptances","sync_entity_state")
            for(table in G0DomainSqlCatalog.columns.keys-permitted)
                require(sql.query("SELECT * FROM `$table`").use {!it.moveToFirst()}) {"G0_RECOVERY_UNSUPPORTED_SCOPE"}
            val habits=db.habitDao().getAllHabitsOnce()
            val metrics=db.metricDao().getAllMetricsOnce()
            require(metrics.map {it.uuid}.distinct().size==metrics.size && metrics.map {it.name}.distinct().size==metrics.size)
            require(habits.size==2 && habits.map {it.uuid}.distinct().size==2 && habits.map {it.name}.distinct().size==2 &&
                habits.all {it.habitType==com.dayforge.data.model.HabitType.COUNTING && it.completionPolicy=="recurring" &&
                    it.appearance!=null && it.isActive && !it.isCountdown && it.parentHabitId==null}) {"G0_RECOVERY_UNSUPPORTED_SCOPE"}
            val histories=habits.associate {it.uuid to CountHistoryReader(db,tokens,sessions).readInTransaction(it,LocalDate.now())}
            require(histories.values.all {it.roundHead==null && it.unknownDates.isEmpty() && it.todayPolicy!=null})
            val facts=histories.values.flatMap {it.completions}
            require(facts.size<=profile.limit) {"G0_COUNT_CAPACITY_UNSUPPORTED"}
            require(facts.map {it.uuid}.distinct().size==facts.size && facts.map {it.id}.distinct().size==facts.size &&
                facts.toSet()==db.completionDao().getAllCompletionsOnce().toSet())
            val byFact=facts.associateBy {it.uuid}
            val queue=db.syncOutboxDao().getAll()+db.syncOutboxDao().getDeadLetters()
            fun ids(table:String,column:String)=sql.query("SELECT `$column` FROM `$table`").use {c->
                buildList {while(c.moveToNext()) {require(c.getType(0)==android.database.Cursor.FIELD_TYPE_STRING);add(c.getString(0))}}
            }
            val seen=hashSetOf<String>();val pending=hashSetOf<String>();val accepted=hashSetOf<String>();val sent=hashSetOf<String>()
            val entries=ids("next_request_origins","requestId").map {id->
                val origin=requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION,id))
                require(origin.kind==NEXT_OPERATION && origin.protocol==5 && origin.queueId>0 && origin.accountId==core.session.authentication.userId &&
                    origin.serverInstanceId==core.session.serverInstanceId && origin.syncEpoch==core.session.syncEpoch)
                require(roundOperationIntent(origin.intentJson)==null && countPlanFrontier(origin.intentJson)==null)
                val op=decodeNextOperationIntent(origin.intentJson);validateNextSyncOperation(op)
                val fact=requireNotNull(byFact[op.entityUuid])
                require(op.operationId==id && op.entityType=="activity_event" && op.action=="upsert" &&
                    op.payload["event_type"]==JsonPrimitive("count_snapshot") && seen.add(fact.uuid) &&
                    op.payload["activity_uuid"]==JsonPrimitive(fact.habitUuid) && op.payload["local_date"]==JsonPrimitive(fact.recordedLocalDate))
                val receipt=db.nextRequestDao().acceptance(NEXT_OPERATION,id)
                val transmission=db.nextRequestDao().transmission(NEXT_OPERATION,id)
                if(transmission!=null)sent+=id
                if(receipt!=null) {
                    original.requireAcceptedCountInTransaction(access,id);accepted+=id
                    requireNotNull(db.syncOutboxDao().getState("activity_event",fact.uuid))
                } else {
                    val proven=NextOriginalRequestProofStore(db).liveOrigin(access,NEXT_OPERATION,id)
                    require(proven==origin);pending+=id
                    val source=queue.single {it.operationId==id}
                    require(source.id==origin.queueId && source.recordType=="completion" && source.action=="upsert" &&
                        source.entityUuid==fact.uuid && source.wireEntityUuid==fact.uuid && source.referenceUuid==fact.habitUuid &&
                        source.payloadJson==null && source.baseRevision==null && source.basePayloadJson==null &&
                        source.attemptedAt==null && source.attemptCount==0 && source.deadLetteredAt==null && source.lastError==null && source.errorCode==null)
                    transmission?.let {NextOriginalRequestProofStore(db).journal(origin,it,access)}
                }
                Entry(origin,receipt!=null,transmission?.wireBytes?.toList())
            }.sortedBy {it.origin.queueId}
            require(seen==byFact.keys && queue.map {it.operationId}.sorted()==pending.sorted())
            require(ids("next_transmissions","requestId").sorted()==sent.sorted() &&
                ids("next_acceptances","requestId").sorted()==accepted.sorted())
            val acceptedFacts=entries.filter {it.accepted}.map {decodeNextOperationIntent(it.origin.intentJson).entityUuid}
            require(ids("sync_entity_state","entityUuid").sorted()==acceptedFacts.sorted() &&
                db.syncOutboxDao().getStatesForType("activity_event").size==acceptedFacts.size)
            for(habit in habits) {
                val days=NextCountDayStore(db).readForHabit(habit)
                require(days.map {it.localDate}.toSet()==facts.filter {it.habitId==habit.id}.map {it.recordedLocalDate}.toSet())
                require(days.all {it.originRequestId!=null && it.capturedDeviceId==access.deviceId &&
                    it.planPredecessorId==null && it.planPredecessorOriginHash==null && it.planPredecessorDependencyHash==null})
                for(day in days) {
                    val first=entries.single {it.origin.requestId==day.originRequestId}
                    val op=decodeNextOperationIntent(first.origin.intentJson)
                    require(op.entityUuid==day.firstEventUuid && op.payload["activity_uuid"]==JsonPrimitive(habit.uuid) &&
                        op.payload["local_date"]==JsonPrimitive(day.localDate) &&
                        NextCommonFactMapper.countPolicy(op.payload)==day.policy &&
                        day.originHash==NextRequestSql.rowHash(sql,"next_request_origins","kind=? AND requestId=?",
                            arrayOf<Any>(NEXT_OPERATION,first.origin.requestId)))
                }
            }
            require(sql.query("SELECT COUNT(*) FROM count_days").use {it.moveToFirst();it.getInt(0)}==
                facts.map {it.habitId to it.recordedLocalDate}.distinct().size)
            if(profile==G0HouseholdCountStore.CapacityProfile.ANNUAL_730_PLUS_6) {
                require(pending.size<=6) {"G0_COUNT_ANNUAL_PENDING_LIMIT"}
                require(accepted.size>=730) {"G0_COUNT_ANNUAL_ACCEPTED_BASE_REQUIRED"}
            }
            authenticate(core,access,lease)
            Manifest(entries,byFact.keys.toSet())
        }
    }
}

/** A private recovery connection's actual SQLite commit boundary. Never holds a
 * permit over HTTP: a 401 authenticator may write the same authority DataStore.
 * Nested success is not outer success. Unknown engine-end failures retain their
 * permit until the SAME end thread proves no transaction, or actual helper close.
 */
internal class G0RecoveryCommitFactory(private val delegate:SupportSQLiteOpenHelper.Factory,
    private val authority:G0AuthorityOwner,private val lease:G0AuthorityOwner.Lease,
    private val schema:G0RecoverySchema,
    private val beforeOuterSuccess:()->Unit={},
    private val dataVersion:AtomicLong = AtomicLong(-1L),
    private val afterClosed:()->Unit={},private val removeColdNonce:Boolean=false,
    private val beforeOuterEnd:()->Unit={},private val beforeInvalidationQuery:()->Unit={},
    private val afterActualOuterEnd:()->Unit={}):SupportSQLiteOpenHelper.Factory {
    override fun create(configuration:SupportSQLiteOpenHelper.Configuration):SupportSQLiteOpenHelper {
        val helper=delegate.create(configuration)
        class Frame(var depth:Int=1,var permit:AutoCloseable?=null)
        val frames=ConcurrentHashMap<Thread,Frame>()
        val poisoned=AtomicBoolean()
        var previous:SupportSQLiteDatabase?=null;var wrapped:SupportSQLiteDatabase?=null
        var closed=false
        fun wrap(raw:SupportSQLiteDatabase):SupportSQLiteDatabase=synchronized(frames) {
            check(!closed)
            if(previous!==raw) {
                check(previous==null) {"G0_RECOVERY_CONNECTION_REPLACED"};previous=raw
                if(removeColdNonce)raw.execSQL("DROP TABLE temp.g0_open_nonce")
                schema.requireMainMatches(raw)
                wrapped=object:SupportSQLiteDatabase by raw {
                    override fun query(query:SupportSQLiteQuery):Cursor {
                        if(query.sql=="SELECT * FROM room_table_modification_log WHERE invalidated = 1") {
                            check(raw.inTransaction())
                            beforeInvalidationQuery()
                        }
                        return raw.query(query)
                    }
                    private fun continuity() {
                        check(raw.inTransaction())
                        val observed=g0Number(raw,"PRAGMA main.data_version")
                        check(observed>=0L) {"G0_RECOVERY_DATA_VERSION_INVALID"}
                        val expected=dataVersion.get()
                        if(expected==-1L)check(dataVersion.compareAndSet(-1L,observed))
                        else check(observed==expected) {"G0_RECOVERY_EXTERNAL_CHANGE"}
                    }
                    private fun begin(body:()->Unit) {
                        check(!poisoned.get()) {"G0_RECOVERY_TRANSACTION_UNKNOWN"}
                        authority.checkLease(lease);check(!closed);schema.requireMainMatches(raw)
                        val thread=Thread.currentThread();val frame=frames[thread]
                        if(frame==null) {
                            check(!raw.inTransaction());frames[thread]=Frame()
                        } else {check(raw.inTransaction());frame.depth++}
                        var began=false
                        try {body();began=true;schema.requireMatches(raw);continuity()} catch(failure:Throwable) {
                            poisoned.set(true)
                            // Room has not observed a successful begin if our post-BEGIN
                            // schema check fails. Roll that actual, unmarked transaction
                            // back here instead of leaving an ownerless engine transaction.
                            if(began)try {raw.endTransaction()} catch(cleanup:Throwable) {failure.addSuppressed(cleanup)}
                            try {if(!raw.inTransaction())frames.remove(thread)?.permit?.close()}
                            catch(inspection:Throwable) {failure.addSuppressed(inspection)}
                            throw failure
                        }
                    }
                    override fun beginTransaction()=begin {raw.beginTransaction()}
                    override fun beginTransactionNonExclusive()=begin {raw.beginTransactionNonExclusive()}
                    override fun beginTransactionReadOnly():Unit=error("G0_RECOVERY_READONLY_TRANSACTION_UNSUPPORTED")
                    override fun beginTransactionWithListener(transactionListener:SQLiteTransactionListener)=begin {raw.beginTransactionWithListener(transactionListener)}
                    override fun beginTransactionWithListenerNonExclusive(transactionListener:SQLiteTransactionListener)=begin {raw.beginTransactionWithListenerNonExclusive(transactionListener)}
                    override fun beginTransactionWithListenerReadOnly(transactionListener:SQLiteTransactionListener):Unit=
                        error("G0_RECOVERY_READONLY_TRANSACTION_UNSUPPORTED")
                    override fun setTransactionSuccessful() {
                        check(!poisoned.get()) {"G0_RECOVERY_TRANSACTION_UNKNOWN"}
                        val frame=checkNotNull(frames[Thread.currentThread()])
                        if(frame.depth==1 && frame.permit==null) {
                            beforeOuterSuccess()
                            schema.requireMatches(raw)
                            continuity()
                            frame.permit=authority.finalPermit(lease)
                        }
                        raw.setTransactionSuccessful()
                    }
                    override fun endTransaction() {
                        val thread=Thread.currentThread();val frame=checkNotNull(frames[thread])
                        var ended=false;var failure:Throwable?=null
                        try {
                            if(frame.depth==1 && frame.permit!=null)beforeOuterEnd()
                            raw.endTransaction();ended=true
                        }
                        catch(problem:Throwable) {poisoned.set(true);failure=problem;throw problem}
                        finally {
                            // This is the original native transaction thread, not a later
                            // dispatcher where inTransaction() would be a false witness.
                            try {
                                if(!raw.inTransaction()) {
                                    frames.remove(thread)?.permit?.close()
                                    afterActualOuterEnd()
                                }
                                else if(ended) {check(frame.depth>1);frame.depth--}
                            } catch(inspection:Throwable) {
                                poisoned.set(true)
                                if(failure!=null)failure!!.addSuppressed(inspection) else throw inspection
                            }
                        }
                    }
                }
            }
            checkNotNull(wrapped)
        }
        fun closeOwned() {
                helper.close()
                synchronized(frames) {
                    check(previous?.isOpen!=true) {"G0_RECOVERY_CLOSE_INCOMPLETE"}
                    closed=true
                    frames.values.forEach {it.permit?.close()};frames.clear()
                    afterClosed()
                }
        }
        fun open():SupportSQLiteDatabase = try {wrap(helper.writableDatabase)} catch(failure:Throwable) {
            poisoned.set(true)
            try {closeOwned()} catch(cleanup:Throwable) {failure.addSuppressed(cleanup)}
            throw failure
        }
        return object:SupportSQLiteOpenHelper by helper {
            override val writableDatabase get()=open()
            override val readableDatabase get()=open()
            override fun close() {
                closeOwned()
            }
        }
    }
}
