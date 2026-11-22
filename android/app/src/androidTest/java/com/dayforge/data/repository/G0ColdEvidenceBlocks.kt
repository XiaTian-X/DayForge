package com.dayforge.data.repository

import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded raw evidence only. Every business validator still runs. A block owns a fresh original
 * read session inside the caller's SAME reserved Room transaction; nothing escapes that session.
 * The original native batch auditor checks types, lengths, duplicate keys and exact absence.
 */
internal class G0ColdEvidenceBlocks(private val db: HabitDatabase) {
    suspend fun read(ids: List<String>, beforeBlock: () -> Unit = {}, reuseRows: Boolean = false, onRows: (G0ColdEvidenceRows) -> Unit = {}, batchNegatives: Boolean = false, onNegatives: (G0ColdNegativeEvidence) -> Unit = {}, validate: suspend (String) -> Unit) {
        check(db.inTransaction())
        check(currentCoroutineContext()[NextEvidenceReadSession] == null) { "G0_BLOCK_NESTED_SESSION" }
        require(ids.distinct().size == ids.size)
        for (block in ids.chunked(16)) {
            currentCoroutineContext().ensureActive()
            NextEvidenceReadSession.read(db) {
                beforeBlock()
                val sql = db.openHelper.writableDatabase
                val audited=mutableMapOf<String,Map<Any,String?>>()
                for (table in listOf("next_request_origins", "next_transmissions", "next_acceptances", "next_structural_dependencies")) {
                    val request = table != "next_structural_dependencies"
                    val column = if (request) "requestId" else "operationId"
                    val kind = if (request) NEXT_OPERATION else null
                    // Aggregate size > 2 MiB falls back to the unchanged per-row auditor. Do not
                    // cache a partial batch, a failed audit, or a manufactured missing row.
                    val trace=G0PhaseTrace.current.get()
                    val hashes = (if(trace==null) NextRequestSql.boundedRowHashes(sql,table,column,block,kind)
                        else trace.stage("cold_batch_audit") { NextRequestSql.boundedRowHashes(sql,table,column,block,kind) }) ?: continue
                    if(reuseRows) audited[table]=hashes
                    val where = if (request) "kind=? AND requestId=?" else "operationId=?"
                    for (id in block) {
                        val args: Array<Any> = if (request) arrayOf(NEXT_OPERATION, id) else arrayOf(id)
                        NextEvidenceReadSession.rowHash(db, table, where, args,
                            // Same length expression as NextRequestSql; cached layout may also
                            // serve an unprefetched ancestor. Never supply a dummy expression.
                            length = { handle ->
                                val columns = handle.query("PRAGMA table_info($table)").use { c ->
                                    buildList { while (c.moveToNext()) add(c.getString(1)) }
                                }
                                require(columns.isNotEmpty())
                                columns.joinToString(" + ") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
                            }, audit = { _, _ -> check(hashes.containsKey(id)); hashes[id] })
                    }
                    G0PhaseTrace.current.get()?.call("cold_evidence_batch") { Unit }
                }
                var loadedRows:G0ColdEvidenceRows?=null
                if(reuseRows) {
                    val trace=G0PhaseTrace.current.get()
                    val rows=if(trace==null) G0ColdEvidenceRows.load(db,block,audited)
                        else trace.stage("cold_rows_load") { G0ColdEvidenceRows.load(db,block,audited) }
                    loadedRows=rows
                }
                val negatives=if(batchNegatives) {
                    val trace=G0PhaseTrace.current.get()
                    suspend fun load()=G0ColdNegativeEvidence.load(db,block,loadedRows?.cachedQueueIds().orEmpty())
                    if(trace==null) load() else trace.stage("cold_negative_load") { load() }
                } else null
                loadedRows?.let(onRows)
                negatives?.let(onNegatives)
                for (id in block) { currentCoroutineContext().ensureActive(); validate(id) }
                G0PhaseTrace.current.get()?.call("cold_evidence_block") { Unit }
            }
        }
    }
}
