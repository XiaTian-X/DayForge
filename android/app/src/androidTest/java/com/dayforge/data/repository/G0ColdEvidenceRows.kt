package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Typed rows from the SAME bounded, natively audited block. Never a proof or cross-session
 * cache. The original raw hashes and every business check still execute. Null maps fall back
 * to original DAO reads; a map's missing entry is exact audited absence. */
internal class G0ColdEvidenceRows private constructor(
    private val db:HabitDatabase, private val session:NextEvidenceReadSession,
    private val ids:Set<String>, private val origins:Map<String,NextRequestOriginEntity>?,
    private val transmissions:Map<String,NextTransmissionEntity>?, private val receipts:Map<String,NextAcceptanceEntity>?
) {
    private suspend fun owner(id:String) { check(NextEvidenceReadSession.current(db)===session && id in ids) { "G0_COLD_ROWS_SCOPE" } }
    suspend fun origin(id:String):NextRequestOriginEntity? { owner(id);return if(origins==null) db.nextRequestDao().origin(NEXT_OPERATION,id) else origins[id] }
    suspend fun transmission(id:String):NextTransmissionEntity? { owner(id);return if(transmissions==null) db.nextRequestDao().transmission(NEXT_OPERATION,id) else transmissions[id]?.let { it.copy(wireBytes=it.wireBytes.copyOf()) } }
    suspend fun receipt(id:String):NextAcceptanceEntity? { owner(id);return if(receipts==null) db.nextRequestDao().acceptance(NEXT_OPERATION,id) else receipts[id] }
    suspend fun cachedQueueIds():Map<String,Long> {
        check(NextEvidenceReadSession.current(db)===session) { "G0_COLD_ROWS_SCOPE" }
        return origins?.mapValues { it.value.queueId } ?: emptyMap()
    }
    companion object {
        suspend fun load(db:HabitDatabase,ids:List<String>,audited:Map<String,Map<Any,String?>>):G0ColdEvidenceRows {
            val session=NextEvidenceReadSession.current(db);require(ids.size in 1..16 && ids.distinct().size==ids.size)
            val sql=db.openHelper.writableDatabase
            val tables=listOf("next_request_origins","next_transmissions","next_acceptances")
            var total=0L
            val eligible=mutableSetOf<String>()
            for(table in tables) {
                currentCoroutineContext().ensureActive()
                if(table !in audited) continue
                // Aggregate raw-byte budget across ALL retained DTO tables. The native batch
                // already checked each full row, including any unexpected schema column.
                val columns=sql.query("PRAGMA table_info($table)").use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }
                require(columns.isNotEmpty())
                val length=columns.joinToString(" + ") { "COALESCE(length(CAST(\"${it.replace("\"","\"\"")}\" AS BLOB)),0)" }
                val args=(listOf(NEXT_OPERATION)+ids).toTypedArray()
                val bytes=sql.query("SELECT COALESCE(SUM($length),0) FROM $table WHERE kind=? AND requestId IN (${ids.joinToString(",") { "?" }})",args).use { c ->
                    check(c.moveToFirst() && c.getType(0)==android.database.Cursor.FIELD_TYPE_INTEGER);c.getLong(0)
                }
                require(bytes>=0)
                if(bytes<=2_097_152L-total) { total+=bytes;eligible+=table }
            }
            fun <T> checked(table:String,rows:List<T>,key:(T)->String):Map<String,T> {
                val result=rows.associateBy(key)
                check(result.size==rows.size && result.keys==audited.getValue(table).filterValues { it!=null }.keys) { "G0_COLD_ROWS_MEMBERSHIP" }
                return result
            }
            val dao=db.nextRequestDao()
            val origins=if("next_request_origins" in eligible) checked("next_request_origins",dao.origins(NEXT_OPERATION,ids)) { it.requestId } else null
            val transmissions=if("next_transmissions" in eligible) checked("next_transmissions",dao.transmissions(NEXT_OPERATION,ids)) { it.requestId } else null
            val receipts=if("next_acceptances" in eligible) checked("next_acceptances",dao.acceptances(NEXT_OPERATION,ids)) { it.requestId } else null
            check(NextEvidenceReadSession.current(db)===session)
            repeat(eligible.size) { G0PhaseTrace.current.get()?.call("cold_rows_table") { Unit } }
            return G0ColdEvidenceRows(db,session,ids.toSet(),origins,transmissions,receipts)
        }
    }
}
