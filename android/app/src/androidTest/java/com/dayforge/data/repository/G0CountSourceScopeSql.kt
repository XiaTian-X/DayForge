package com.dayforge.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.SYNC_REQUEST_LIMIT
import java.util.Collections

/** G0-only native-source routing index. Indexable is a conservative representation class,
 * not business validity. Values are parsed with SQLite JSON, never extracted by substrings.
 * Non-indexable rows occupy an indexed rejection bucket; no row is silently skipped.
 * Sources and shadows retain their original bytes and their existing protocol semantics.
 */
internal object G0CountSourceScopeSql {
    const val ORIGIN_INDEX="g0_count_native_origin_day"
    const val SHADOW_INDEX="g0_count_native_shadow_day"
    class Shape internal constructor(val table:String,val index:String,val jsonColumn:String,
        val keyColumn:String,kindColumn:String,kindValue:String,parent:String?) {
        private val value="`$jsonColumn`"
        private val ownerPath=if(parent==null)"$.activity_uuid" else "$.payload.activity_uuid"
        private val datePath=if(parent==null)"$.local_date" else "$.payload.local_date"
        private val tokens=listOfNotNull(parent,"activity_uuid","local_date")
        // With no backslash or NUL and strict valid ASCII JSON, these globally unique
        // quoted tokens cannot hide escaped labels. A token used as a value may cause
        // conservative rejection, never a false unique path. The parent label must be
        // unique too: duplicate payload ancestors could otherwise select a different day.
        private fun once(token:String):String {
            val quoted="\"$token\""
            return "(length($value)-length(replace($value,'$quoted','')))=${quoted.length}"
        }
        private val ascii="replace(replace(replace($value,char(9),''),char(10),''),char(13),'') NOT GLOB '*[^ -~]*'"
        // CASE's documented lazy evaluation guards every JSON operation. AND/OR are
        // not used as a speculative guard around json_type/json_extract on invalid text.
        val eligible:String="CASE "+
            "WHEN typeof(`$kindColumn`)<>'text' THEN 0 "+
            "WHEN `$kindColumn`<>'$kindValue' THEN 0 "+
            "WHEN typeof($value)<>'text' THEN 0 "+
            "WHEN length(CAST($value AS BLOB))>$SYNC_REQUEST_LIMIT THEN 0 "+
            "WHEN instr(CAST($value AS BLOB),X'00')<>0 THEN 0 "+
            "WHEN instr(CAST($value AS BLOB),X'5c')<>0 THEN 0 "+
            "WHEN NOT ($ascii) THEN 0 "+
            "WHEN json_valid($value)<>1 THEN 0 "+
            "WHEN json_type($value,'$') IS NOT 'object' THEN 0 "+
            (if(parent==null)"" else "WHEN json_type($value,'$.payload') IS NOT 'object' THEN 0 ")+
            tokens.joinToString("") {"WHEN NOT (${once(it)}) THEN 0 "}+
            "WHEN json_type($value,'$ownerPath') IS NOT 'text' THEN 0 "+
            "WHEN json_type($value,'$datePath') IS NOT 'text' THEN 0 ELSE 1 END"
        val owner:String="CASE WHEN ($eligible)=1 THEN json_extract($value,'$ownerPath') ELSE NULL END"
        val date:String="CASE WHEN ($eligible)=1 THEN json_extract($value,'$datePath') ELSE NULL END"
        val create:String="CREATE INDEX `$index` ON `$table` (($eligible),($owner),($date),`$keyColumn`)"
        val where:String="($eligible)=1 AND ($owner)=? AND ($date)=?"
        val unsupported:String="SELECT 1 FROM main.`$table` INDEXED BY `$index` WHERE ($eligible)=0 LIMIT 1"
    }
    val origins=Shape("next_request_origins",ORIGIN_INDEX,"intentJson","requestId","kind",NEXT_OPERATION,"payload")
    val shadows=Shape("sync_entity_state",SHADOW_INDEX,"payloadJson","entityUuid","entityType","activity_event",null)
    val shapes:List<Shape> = Collections.unmodifiableList(listOf(origins,shadows))
    val createIndexes:List<String> = Collections.unmodifiableList(shapes.map {it.create})
    val unsupportedReads:List<String> = Collections.unmodifiableList(shapes.map {it.unsupported})
    const val CAPABILITY_SQL="SELECT sqlite_version(),json_valid('{\"a\":\"b\"}'),json_type('{\"a\":\"b\"}','$.a'),json_extract('{\"a\":\"b\"}','$.a')"
    /** Only new/reference G0 schema creation calls this, never an existing-file repair.
     * JSON support varies by SQLite build. This probe does not raise production minSdk.
     */
    fun requireCapability(sql:SupportSQLiteDatabase):String {
        try {
            return sql.query(CAPABILITY_SQL).use {c->
                check(c.moveToFirst() && c.getLong(1)==1L && c.getString(2)=="text" && c.getString(3)=="b")
                c.getString(0).also {check(!c.moveToNext())}
            }
        } catch(failure:Exception) {
            throw IllegalStateException("G0_COUNT_JSON_INDEX_UNSUPPORTED",failure)
        }
    }
}
