package com.dayforge.data.repository

import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Bounded, process-local pure computation cache. Caller supplies complete immutable keys.
 * Weight is a conservative admission estimate, not a measurement of JVM heap consumption.
 * No failed/cancelled computation, permission or database membership is stored here. */
internal class G0FactPureMemo(private val maxEntries:Int,private val maxBytes:Long) {
    private data class Entry(val value:Any?,val weight:Long)
    private val values=LinkedHashMap<Any,Entry>(16,.75f,true)
    private var generation=0L
    private var closed=false
    var hits=0L; private set
    var retainedBytes=0L; private set
    val entries:Int @Synchronized get()=values.size
    init { require(maxEntries>=0 && maxBytes>=0) }
    @Synchronized fun clear() { values.clear();retainedBytes=0;generation++ }
    @Synchronized fun close() { closed=true;clear() }
    suspend fun <T> value(key:Any,weight:Long,retain:(T)->Boolean={true},compute:suspend ()->T):T {
        require(weight>0)
        currentCoroutineContext().ensureActive()
        val epoch:Long
        synchronized(this) {
            check(!closed) { "G0_FACT_MEMO_CLOSED" }
            val entry=values[key]
            if(entry!=null) {
                hits++
                @Suppress("UNCHECKED_CAST")
                return entry.value as T
            }
            epoch=generation
        }
        val result=compute()
        currentCoroutineContext().ensureActive()
        if(maxEntries==0 || weight>maxBytes || !retain(result)) return result
        synchronized(this) {
            if(epoch!=generation) return result
            values.remove(key)?.let { retainedBytes-=it.weight }
            while(values.size>=maxEntries || retainedBytes>maxBytes-weight) {
                val first=values.entries.iterator();val removed=first.next();retainedBytes-=removed.value.weight;first.remove()
            }
            values[key]=Entry(result,weight);retainedBytes+=weight
        }
        return result
    }
    companion object {
        fun freeze(value:JsonObject):JsonObject=freezeElement(value) as JsonObject
        private fun freezeElement(value:JsonElement):JsonElement=when(value) {
            is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { freezeElement(it.value) }))
            is JsonArray -> JsonArray(Collections.unmodifiableList(value.map { freezeElement(it) }))
            else -> value
        }
    }
}
