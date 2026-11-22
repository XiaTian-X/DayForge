package com.dayforge.data.repository

import android.util.Log
import androidx.room.Room
import com.dayforge.data.local.HabitDatabase
import java.time.ZonedDateTime
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Original unsealed fact reference with an outer diagnostic wrapper, never a new SQL grant. */
abstract class G0IndexFixture:G0CountIndependentFixture() {
    init {storage.isolatedBuilder={context->Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
        .addCallback(domainOpeningCallback()).openHelperFactory(G0QueryProbeFactory(domainGate.factory)).build()}}
    internal data class Measurement<T>(val value:T,val elapsed:Long,val trace:G0PhaseTrace)
    internal suspend fun <T> measure(label:String,diagnostic:Boolean=true,block:suspend ()->T):Measurement<T> {
        val trace=G0PhaseTrace(driverTiming=false);val probe=G0QueryProbe()
        val start=android.os.SystemClock.elapsedRealtimeNanos()
        Log.i("G0IndexBoundary","start=$label")
        val value=trace.run {if(diagnostic)probe.run(block) else block()}
        val end=android.os.SystemClock.elapsedRealtimeNanos()
        val sql=probe.json()
        Log.i("G0IndexMeasure",buildJsonObject {
            put("label",label);put("diagnostic",diagnostic);put("elapsed_ns",end-start)
            put("transaction_max_ns",transactionHold(trace));put("outer_transactions",trace.count("tx_end_outer"))
            put("decrypt_access",trace.count("decrypt_access"));put("decrypt_refresh",trace.count("decrypt_refresh"))
            put("decrypt_ns",trace.nanos("decrypt_access"));put("datastore_ns",trace.nanos("datastore_read"))
            put("begin_ns",trace.nanos("tx_begin_outer"));put("end_ns",trace.nanos("tx_end_outer"));put("sql_groups",sql.size)
        }.toString())
        sql.forEach {(key,value)->Log.i("G0IndexSql",buildJsonObject {put("label",label);put("family",key);put("cost",value)}.toString())}
        Log.i("G0IndexBoundary","end=$label")
        return Measurement(value,end-start,trace)
    }
    internal suspend fun readHome()=dashboardRepository().let {repo->repo.read(ZonedDateTime.now()).also {assertTrue(repo.publish(it) {})}}
    internal fun plans(label:String) {
        val queries=listOf(
            Triple("completion_sorted","SELECT * FROM completions WHERE habitId=? ORDER BY recordedLocalDate DESC, COALESCE(actualCompletedAt, date) DESC, id DESC",arrayOf<Any>(habit.id)),
            Triple("completion_range","SELECT * FROM completions WHERE habitId=? AND recordedLocalDate>=? AND recordedLocalDate<?",arrayOf<Any>(habit.id,"2000-01-01","2100-01-01")),
            Triple("outbox_reference","SELECT * FROM sync_outbox WHERE referenceUuid=? AND recordType IN ('completion','one_time_completion') ORDER BY id",arrayOf<Any>(habit.uuid)),
            Triple("origin_like","SELECT requestId FROM next_request_origins WHERE kind=? AND intentJson LIKE ? AND intentJson LIKE ?",arrayOf<Any>(NEXT_OPERATION,"%${habit.uuid}%","%\"entity_type\"%:%\"activity_event\"%")))
        for((name,sql,args) in queries) {
            val details=db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql",args).use {c->buildList {while(c.moveToNext())add(c.getString(3))}}
            Log.i("G0IndexPlan",buildJsonObject {put("label",label);put("query",name);put("detail",JsonArray(details.map(::JsonPrimitive)))}.toString())
        }
    }
    internal suspend fun seedAccepted(history:Int) {
        configureAction(target=1_000_000);val (http,_)=channel {successReply(it)};val request=sender(http)
        repeat(history) {val fact=seedFact();val row=db.syncOutboxDao().getEntityIntents("completion",fact.uuid).single()
            val sent=requireNotNull(request.sendOperation(access(),row.operationId));assertEquals(NextOperationAcceptance.COMMITTED,request.acceptOperation(sent))}
    }
}
