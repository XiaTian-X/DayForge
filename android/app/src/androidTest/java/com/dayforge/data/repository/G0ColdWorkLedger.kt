package com.dayforge.data.repository

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Counts attempted application SQL at the gate, never binds/rows or private driver work.
 * Reference/schema/journal/preflight costs remain in whole-operation elapsed measurements.
 */
internal class G0ColdWorkLedger {
    var blockNodeQueries=0; private set
    var queries=0; private set
    var writes=0; private set
    val wholeTableQueries=linkedMapOf<String,Int>()
    private val exact=G0PersistentCheckpoint.columns.keys.associateBy {G0PersistentCheckpoint.select(it)}
    fun record(sql:String,query:Boolean) {
        if(query) {
            queries++
            if(sql==G0CountBlockSql.readNode)blockNodeQueries++
            exact[sql]?.let { wholeTableQueries[it]=(wholeTableQueries[it] ?: 0)+1 }
        } else writes++
    }
    suspend fun <T> run(block:suspend ()->T):T {
        check(current.get()==null)
        return withContext(current.asContextElement(this)) {block()}
    }
    companion object { val current=ThreadLocal<G0ColdWorkLedger?>() }
}
