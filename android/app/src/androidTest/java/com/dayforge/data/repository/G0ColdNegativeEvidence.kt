package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact negative equality probes inside ONE original read session. Presence never constitutes
 * a proof: callers must execute the unchanged whole-row/group audit. Fixed predicates use the
 * same SQLite comparison semantics as the original queries; no projected key coercion or
 * inferred absence from an incomplete list. At most 16 requests and five bounded SQL calls. */
internal class G0ColdNegativeEvidence private constructor(
    private val db: HabitDatabase,
    private val session: NextEvidenceReadSession,
    private val ids: Set<String>,
    private val queues: Map<String, Long>,
    private val missing: Map<Probe, Set<String>>
) {
    enum class Probe(val table: String, val predicate: String) {
        ORIGINAL("next_structural_supersessions", "originalId=?"),
        REPLACEMENT("next_structural_supersessions", "replacementId=?"),
        RECOVERY("next_abandoned_requests", "kind=? AND requestId=?"),
        SOURCE_OPERATION("sync_outbox", "operationId=?"),
        SOURCE_QUEUE("sync_outbox", "id=?")
    }

    suspend fun absent(probe: Probe, id: String, queueId: Long? = null): Boolean {
        check(NextEvidenceReadSession.current(db) === session && id in ids) { "G0_NEGATIVE_SCOPE" }
        if (probe == Probe.SOURCE_QUEUE) {
            if (id !in queues) return false // No audited DTO: unchanged original lookup.
            check(queueId == queues.getValue(id)) { "G0_NEGATIVE_QUEUE" }
        } else require(queueId == null)
        val result = id in missing.getValue(probe)
        if (result) G0PhaseTrace.current.get()?.call("cold_negative_hit") { Unit }
        return result
    }

    companion object {
        suspend fun load(db: HabitDatabase, ids: List<String>, queues: Map<String, Long>): G0ColdNegativeEvidence {
            val session = NextEvidenceReadSession.current(db)
            require(ids.size in 1..16 && ids.distinct().size == ids.size && queues.keys.all { it in ids })
            val sql = db.openHelper.writableDatabase
            val missing = mutableMapOf<Probe, Set<String>>()
            for (probe in Probe.entries) {
                currentCoroutineContext().ensureActive()
                val keys = if (probe == Probe.SOURCE_QUEUE) ids.filter { it in queues } else ids
                if (keys.isEmpty()) { missing[probe] = emptySet(); continue }
                val args = keys.flatMap { id -> when (probe) {
                    Probe.RECOVERY -> listOf(NEXT_OPERATION, id)
                    Probe.SOURCE_QUEUE -> listOf(queues.getValue(id))
                    else -> listOf(id)
                } }.toTypedArray<Any>()
                val query = "SELECT " + keys.joinToString(",") {
                    "EXISTS(SELECT 1 FROM ${probe.table} WHERE ${probe.predicate})"
                }
                val read = {
                    sql.query(query, args).use { c ->
                        check(c.columnCount == keys.size && c.moveToFirst())
                        val absent = keys.filterIndexed { index, _ ->
                            check(c.getType(index) == Cursor.FIELD_TYPE_INTEGER && c.getLong(index) in 0L..1L)
                            c.getLong(index) == 0L
                        }.toSet()
                        check(!c.moveToNext()); absent
                    }
                }
                val trace = G0PhaseTrace.current.get()
                missing[probe] = if (trace == null) read() else trace.call("cold_negative_query", read)
            }
            check(NextEvidenceReadSession.current(db) === session)
            return G0ColdNegativeEvidence(db, session, ids.toSet(), queues.toMap(), missing.toMap())
        }
    }
}
