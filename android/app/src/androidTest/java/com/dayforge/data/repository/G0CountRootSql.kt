package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec

import android.database.Cursor
import java.util.Collections
import java.util.Locale

/** Two optional B3a tables and a fixed permanent dirty-trigger set. Only trusted new
 * database/reference creation installs them. Opening an existing DB never repairs schema.
 * Dirty tracks normal controlled/unmaintained SQL writes; it cannot prove arbitrary P
 * bytes unchanged, resist selective marker replay, or grant a cold writer capability.
 */
internal object G0CountRootSql {
    const val sealTable="g0_count_root_seal"
    const val markerTable="g0_count_root_marker"
    val headerColumns:List<String> = Collections.unmodifiableList(listOf(
        "slot","format","validator","binding","height","members","digest","revision","generation","mac"))
    val markerColumns:List<String> = Collections.unmodifiableList(listOf("slot","generation","dirty"))
    val tables:Map<String,List<String>> = Collections.unmodifiableMap(linkedMapOf(
        sealTable to headerColumns,markerTable to markerColumns))
    const val createSeal="CREATE TABLE g0_count_root_seal (slot INTEGER NOT NULL PRIMARY KEY CHECK(slot=1), format INTEGER NOT NULL, validator TEXT NOT NULL, binding TEXT NOT NULL, height INTEGER NOT NULL, members INTEGER NOT NULL, digest TEXT NOT NULL, revision INTEGER NOT NULL, generation INTEGER NOT NULL, mac BLOB NOT NULL) WITHOUT ROWID"
    const val createMarker="CREATE TABLE g0_count_root_marker (slot INTEGER NOT NULL PRIMARY KEY CHECK(slot=1), generation INTEGER NOT NULL CHECK(generation>=0), dirty INTEGER NOT NULL CHECK(dirty IN (0,1))) WITHOUT ROWID"
    const val initializeMarker="INSERT OR ABORT INTO g0_count_root_marker (slot,generation,dirty) VALUES (1,0,1)"
    const val insertHeader="INSERT OR ABORT INTO g0_count_root_seal (slot,format,validator,binding,height,members,digest,revision,generation,mac) VALUES (?,?,?,?,?,?,?,?,?,?)"
    const val updateHeader="UPDATE g0_count_root_seal SET format=?,validator=?,binding=?,height=?,members=?,digest=?,revision=?,generation=?,mac=? WHERE slot=1 AND binding=? AND digest=? AND revision=? AND generation=?"
    const val clearMarker="UPDATE g0_count_root_marker SET dirty=0 WHERE slot=1 AND generation=? AND dirty=1"

    // A malformed/oversized field becomes NULL before it can cross the Cursor boundary.
    // LIMIT 2 is sufficient to reject extra rows and keeps the small head read bounded.
    private fun integer(column:String)="CASE WHEN typeof(`$column`)='integer' THEN `$column` ELSE NULL END AS `$column`"
    private fun text(column:String,maxBytes:Int)="CASE WHEN typeof(`$column`)='text' THEN CASE WHEN length(CAST(`$column` AS BLOB))<=$maxBytes THEN `$column` ELSE NULL END ELSE NULL END AS `$column`"
    private fun blob(column:String,maxBytes:Int)="CASE WHEN typeof(`$column`)='blob' THEN CASE WHEN length(`$column`)<=$maxBytes THEN `$column` ELSE NULL END ELSE NULL END AS `$column`"
    val readSeal:String="SELECT "+listOf(integer("slot"),integer("format"),text("validator",128),
        text("binding",64),integer("height"),integer("members"),text("digest",64),integer("revision"),
        integer("generation"),blob("mac",G0CountRootSeal.MAC_BYTES)).joinToString(",")+" FROM main.g0_count_root_seal LIMIT 2"
    val readMarker:String="SELECT "+markerColumns.joinToString(",",transform=::integer)+" FROM main.g0_count_root_marker LIMIT 2"
    val readBoundedRoot:String="SELECT "+listOf(integer("slot"),text("binding",64),integer("height"),
        integer("members"),text("digest",64),integer("revision")).joinToString(",")+
        " FROM main.g0_count_block_root LIMIT 2"
    const val readFencePresence="SELECT 1 FROM main.next_replica_fence LIMIT 1"
    val reads:Map<String,String> = mapOf(readSeal to "",readMarker to "",readBoundedRoot to "",readFencePresence to "")
    val writes:Map<String,String> = mapOf(insertHeader to "IISSIISIIV",updateHeader to "ISSIISIIVSSII",clearMarker to "I")

    data class RootRow(val binding:String,val root:HistoryBlockCodec.Ref,val revision:Long) {
        fun values():List<Any?> = listOf(1L,binding,root.height.toLong(),root.count,root.digest,revision)
    }
    fun captureRootRow(cursor:Cursor):RootRow? {
        if(cursor.columnCount!=6 || !cursor.moveToFirst())return null
        if(listOf(0,2,3,5).any {cursor.getType(it)!=Cursor.FIELD_TYPE_INTEGER} ||
            listOf(1,4).any {cursor.getType(it)!=Cursor.FIELD_TYPE_STRING})return null
        val slot=cursor.getLong(0);val binding=cursor.getString(1);val height=cursor.getLong(2)
        val members=cursor.getLong(3);val digest=cursor.getString(4);val revision=cursor.getLong(5)
        if(slot!=1L || !G0CountRootSeal.validHash(binding) || !G0CountRootSeal.validHash(digest) ||
            !G0CountRootSeal.validMetadata(height,members) || revision<=0 || cursor.moveToNext())return null
        return RootRow(binding,G0CountRootSeal.fixedRoot(binding,height.toInt(),members,digest),revision)
    }
    data class Marker(val generation:Long,val dirty:Boolean) {
        init {require(generation>=0)}
        fun values():List<Any?> = listOf(1L,generation,if(dirty)1L else 0L)
    }
    fun captureMarker(cursor:Cursor):Marker? {
        if(cursor.columnCount!=markerColumns.size || !cursor.moveToFirst())return null
        if((0..2).any {cursor.getType(it)!=Cursor.FIELD_TYPE_INTEGER})return null
        val slot=cursor.getLong(0);val generation=cursor.getLong(1);val dirty=cursor.getLong(2)
        if(slot!=1L || generation<0 || dirty !in 0L..1L || cursor.moveToNext())return null
        return Marker(generation,dirty==1L)
    }
    fun captureSeal(cursor:Cursor):G0CountRootSeal.Packet? {
        if(cursor.columnCount!=headerColumns.size || !cursor.moveToFirst())return null
        if(listOf(0,1,4,5,7,8).any {cursor.getType(it)!=Cursor.FIELD_TYPE_INTEGER} ||
            listOf(2,3,6).any {cursor.getType(it)!=Cursor.FIELD_TYPE_STRING} ||
            cursor.getType(9)!=Cursor.FIELD_TYPE_BLOB)return null
        if(cursor.getLong(0)!=1L)return null
        val result=G0CountRootSeal.fromStored(cursor.getLong(1),cursor.getString(2),cursor.getString(3),
            cursor.getLong(4),cursor.getLong(5),cursor.getString(6),cursor.getLong(7),cursor.getLong(8),cursor.getBlob(9))
        if(cursor.moveToNext())return null
        return result
    }
    /** Exact old-head CAS arguments after the new nine SET values. Final journal validation
     * additionally compares the COMPLETE old and new header, not only these CAS predicates.
     */
    fun updateValues(next:G0CountRootSeal.Packet,previous:G0CountRootSeal.Packet):List<Any?> =
        next.headerValues().drop(1)+listOf(previous.binding,previous.root.digest,previous.revision,previous.generation)

    val dirtySources:Set<String> = Collections.unmodifiableSet(linkedSetOf<String>().apply {
        addAll(G0DomainSqlCatalog.columns.keys);addAll(G0CountBlockSql.tables.keys)
    })
    /** Every affected physical P/Q row increments exactly once; cascading/trigger writes
     * contribute their own increments. Header and marker are excluded to avoid recursion.
     */
    val dirtyTriggers:Map<String,String> = Collections.unmodifiableMap(linkedMapOf<String,String>().apply {
        for(table in dirtySources)for(action in listOf("INSERT","UPDATE","DELETE")) {
            val name="g0_count_root_dirty_${table}_${action.lowercase(Locale.ROOT)}"
            put(name,"CREATE TRIGGER `$name` AFTER $action ON `$table` BEGIN "+
                "SELECT CASE WHEN (SELECT count(*) FROM g0_count_root_marker WHERE slot=1 AND typeof(slot)='integer' AND typeof(generation)='integer' AND generation>=0 AND generation<9223372036854775807 AND typeof(dirty)='integer' AND dirty IN (0,1))<>1 THEN RAISE(ABORT,'G0_COUNT_ROOT_MARKER_INVALID') END; "+
                "UPDATE g0_count_root_marker SET generation=generation+1,dirty=1 WHERE slot=1; END")
        }
    })
}
