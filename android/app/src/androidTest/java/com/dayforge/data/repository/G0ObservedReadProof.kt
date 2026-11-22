package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** In-memory observations of an ORIGINAL successful validator. No SQL permission or credentials.
 * Replay preserves cursor moves, values, native types, nulls and absence. Unknown operations or
 * bounded recording exhaustion disable reuse; they never truncate the original validation.
 */
internal class G0ObservedReadProof private constructor(private val queries:List<Query>) {
    private data class Step(val method:Method,val args:List<Any?>,val moved:Boolean?=null,val position:Int?=null)
    private data class Query(val order:Int,val sql:String,val bindings:List<Any?>,val tables:Set<String>,val steps:List<Step>,val digest:ByteArray)
    val queryCount get()=queries.size
    suspend fun unchanged(sql:SupportSQLiteDatabase,changed:Set<String>):Boolean {
        check(current.get()==null)
        var bytes=0L
        for(q in queries) {
            currentCoroutineContext().ensureActive()
            if(q.tables.none { it in changed }) continue
            sql.query(q.sql,q.bindings.toTypedArray()).use { cursor ->
                val hash=Hash { n -> bytes+=n;bytes<=8L*1024*1024 }
                for(step in q.steps) {
                    currentCoroutineContext().ensureActive()
                    val value=invoke(step.method,cursor,step.args)
                    if(step.moved!=null && (value!=step.moved || cursor.position!=step.position)) return false
                    observe(hash,step.method.name,value,cursor)
                    if(!hash.valid) return false
                }
                if(!MessageDigest.isEqual(q.digest,hash.finish())) return false
            }
        }
        return true
    }
    class Capture(private val maxSteps:Int=16_384,private val maxBytes:Long=8L*1024*1024) {
        private val completed=mutableListOf<Query>()
        private var steps=0;private var bytes=0L;private var opened=0;private var issued=0;private var valid=true
        var reason:String?=null;private set
        fun invalidate(why:String="recording_limit_or_unsupported_statement") { if(valid) reason=why;valid=false;completed.clear() }
        fun finish():G0ObservedReadProof? = if(valid && opened==0 && completed.isNotEmpty()) G0ObservedReadProof(completed.sortedBy { it.order }) else null
        internal fun cursor(sql:String,bindings:List<Any?>,target:Cursor):Cursor {
            if(!valid || sql in metadata) return target
            val tables=dependencies[sql]
            if(tables.isNullOrEmpty() || completed.size+opened>=512) { invalidate("query:"+nextRequestHash(sql.toByteArray()));return target }
            val order=issued++;opened++;val trace=mutableListOf<Step>();var closed=false
            val hash=Hash { n -> bytes+=n;if(bytes>maxBytes) invalidate();valid }
            return Proxy.newProxyInstance(Cursor::class.java.classLoader,arrayOf(Cursor::class.java)) { _,method,args ->
                val values=args?.toList().orEmpty()
                try {
                    val result=invoke(method,target,values)
                    if(method.name=="close") {
                        if(!closed) { closed=true;opened--;if(valid) completed+=Query(order,sql,bindings.map { if(it is ByteArray) it.copyOf() else it },tables,trace.toList(),hash.finish()) }
                    } else if(valid) {
                        if(closed || method.name !in replayable || values.any { it !is Int && it !is String }) invalidate("cursor:"+method.name)
                        else {
                            steps++;if(steps>maxSteps) invalidate()
                            else { trace+=Step(method,values,if(method.name.startsWith("move")) result as Boolean else null,if(method.name.startsWith("move")) target.position else null);observe(hash,method.name,result,target) }
                        }
                    }
                    result
                } catch(error:Throwable) { invalidate();throw error }
            } as Cursor
        }
    }
    private class Hash(private val budget:(Int)->Boolean={ true }) {
        private val digest=MessageDigest.getInstance("SHA-256")
        var valid=true;private set
        private val buffer=ByteArray(8)
        private fun allow(size:Int):Boolean { if(!valid || !budget(size)) { valid=false;return false };return true }
        private fun number(value:Long) { for(i in 0..7) buffer[7-i]=(value ushr (i*8)).toByte();digest.update(buffer) }
        fun value(value:Any?) {
            when(value) {
                null -> number(0)
                is Boolean -> { number(1);number(if(value) 1 else 0) }
                is Int -> { number(2);number(value.toLong()) }
                is Long -> { number(3);number(value) }
                is Short -> { number(4);number(value.toLong()) }
                is Float -> { number(5);number(java.lang.Float.floatToRawIntBits(value).toLong()) }
                is Double -> { number(6);number(java.lang.Double.doubleToRawLongBits(value)) }
                is String -> {
                    number(7)
                    // Stop before allocating a second large value; original cursor still proceeds.
                    if(!allow(value.length)) return
                    val bytes=value.toByteArray(Charsets.UTF_8);if(!allow(bytes.size)) return
                    number(bytes.size.toLong());digest.update(bytes)
                }
                is ByteArray -> { number(8);if(!allow(value.size)) return;number(value.size.toLong());digest.update(value) }
                is Array<*> -> { number(9);number(value.size.toLong());value.forEach(::value) }
                else -> error("G0_UNSUPPORTED_CURSOR_OBSERVATION")
            }
        }
        fun finish():ByteArray=digest.digest()
    }
    companion object {
        internal val current=ThreadLocal<Capture?>()
        suspend fun <T> capture(recording:Capture,block:suspend ()->T):T {
            check(current.get()==null);return withContext(current.asContextElement(recording)) { block() }
        }
        private val replayable=setOf("getCount","getPosition","getColumnCount","getColumnNames","getColumnName",
            "getColumnIndex","getColumnIndexOrThrow","isBeforeFirst","isAfterLast","isFirst","isLast",
            "moveToPosition","move","moveToFirst","moveToLast","moveToNext","moveToPrevious",
            "getString","getBlob","getLong","getInt","getShort","getFloat","getDouble","getType","isNull")
        private val metadata=setOf("SELECT total_changes()","PRAGMA database_list","PRAGMA \"main\".schema_version","PRAGMA \"temp\".schema_version") +
            G0DomainSqlCatalog.columns.keys.map { "PRAGMA table_info($it)" }
        private val dependencies=G0RecurringQueryDependencies.tables
        private fun invoke(method:Method,target:Cursor,args:List<Any?>):Any? = try { method.invoke(target,*args.toTypedArray()) }
            catch(error:InvocationTargetException) { throw requireNotNull(error.cause) }
        private fun observe(hash:Hash,name:String,value:Any?,cursor:Cursor) {
            hash.value(value)
            if(name.startsWith("move")) hash.value(cursor.position)
        }
    }
}
