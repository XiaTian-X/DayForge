package com.dayforge.data.repository

import android.database.Cursor
import android.database.CursorWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.sqlite.db.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Test-only aggregate driver timing. No SQL text, bindings, credentials or result values are
 * logged. Cursor steps count returned positions, NOT SQLite rows examined. Nested timings
 * cannot be added to transaction spans. Does not change connection ownership or transactions. */
internal class G0QueryProbe {
    private data class Cost(var calls:Long=0,var wall:Long=0,var cpu:Long=0,var rows:Long=0,var failed:Long=0)
    private val costs=linkedMapOf<String,Cost>()
    private fun now()=SystemClock.elapsedRealtimeNanos()
    fun <T> call(key:String,block:()->T):T {
        val start=now();val cpu=Debug.threadCpuTimeNanos();var ok=false;var value:Any?=null
        try { return block().also {value=it;ok=true} } finally {
            val end=now();val used=Debug.threadCpuTimeNanos()-cpu
            synchronized(costs) {val c=costs.getOrPut(key) {Cost()};c.calls++;c.wall+=end-start;c.cpu+=used.coerceAtLeast(0)
                if(key.endsWith(".move") && value==true) c.rows++;if(!ok)c.failed++}
        }
    }
    suspend fun <T> run(block:suspend ()->T):T {
        check(current.get()==null);return withContext(current.asContextElement(this)) {block()}
    }
    fun json():JsonObject=synchronized(costs) {buildJsonObject {costs.forEach {(key,c)->put(key,buildJsonObject {
        put("calls",c.calls);put("wall_ns",c.wall);put("cpu_ns",c.cpu);put("returned_positions",c.rows);put("failed",c.failed)
    })}}}
    companion object {
        val current=ThreadLocal<G0QueryProbe>()
        fun family(sql:String):String {
            val s=sql.lowercase()
            return when {
                "sqlite_master" in s || "sqlite_schema" in s || s.startsWith("pragma") -> "schema"
                "total_changes()" in s -> "change_counter"
                "room_table_modification_log" in s -> "room_invalidation"
                "next_request_origins" in s && " like " in s -> "origin_like"
                "sync_entity_state" in s && " like " in s -> "shadow_like"
                "completions" in s && "order by recordedlocaldate" in s -> "completion_sorted"
                "completions" in s && "recordedlocaldate >=" in s -> "completion_range"
                "completions" in s -> "completion_other"
                "sync_outbox" in s && "referenceuuid" in s -> "outbox_reference"
                "sync_outbox" in s -> "outbox_other"
                "count_days" in s -> "count_days"
                "next_request_origins" in s -> "origin_other"
                "next_acceptances" in s -> "acceptance"
                "next_transmissions" in s -> "transmission"
                "sync_entity_state" in s -> "shadow_other"
                "habits" in s -> "habit"
                else -> "other"
            }
        }
    }
}

internal class G0QueryProbeFactory(private val delegate:SupportSQLiteOpenHelper.Factory):SupportSQLiteOpenHelper.Factory {
    override fun create(configuration:SupportSQLiteOpenHelper.Configuration):SupportSQLiteOpenHelper {
        val helper=delegate.create(configuration)
        return object:SupportSQLiteOpenHelper by helper {
            private var previous:SupportSQLiteDatabase?=null;private var wrapped:SupportSQLiteDatabase?=null
            private fun wrap(raw:SupportSQLiteDatabase):SupportSQLiteDatabase=synchronized(this) {
                if(previous!==raw) {previous=raw;wrapped=proxy(SupportSQLiteDatabase::class.java,raw,null)}
                requireNotNull(wrapped)
            }
            override val readableDatabase get()=wrap(helper.readableDatabase)
            override val writableDatabase get()=wrap(helper.writableDatabase)
        }
    }
    private fun <T:Any> proxy(type:Class<T>,target:T,statement:String?):T=requireNotNull(type.cast(
        Proxy.newProxyInstance(type.classLoader,arrayOf(type)) {_,method,args->
            fun invoke():Any?=try {method.invoke(target,*(args ?: emptyArray()))} catch(e:InvocationTargetException) {throw requireNotNull(e.cause)}
            val probe=G0QueryProbe.current.get()
            val sql=statement ?: when(val arg=args?.firstOrNull()) {is String->arg;is SupportSQLiteQuery->arg.sql;else->null}
            val family=sql?.let(G0QueryProbe::family) ?: "other"
            val measured=method.name in setOf("query","execSQL","execute","executeInsert","executeUpdateDelete","simpleQueryForLong","simpleQueryForString")
            val result=if(probe!=null && measured) probe.call("$family.${if(method.name=="query") "query" else "write_or_scalar"}") {invoke()} else invoke()
            when {
                result is SupportSQLiteStatement -> proxy(SupportSQLiteStatement::class.java,result,sql)
                result is Cursor && probe!=null -> object:CursorWrapper(result) {
                    override fun moveToNext()=probe.call("$family.move") {result.moveToNext()}
                    override fun moveToFirst()=probe.call("$family.move") {result.moveToFirst()}
                    override fun moveToPosition(position:Int)=probe.call("$family.move") {result.moveToPosition(position)}
                    override fun getCount()=probe.call("$family.count") {result.count}
                    override fun close()=probe.call("$family.close") {result.close()}
                }
                else -> result
            }
        }))
}
