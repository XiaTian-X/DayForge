package com.dayforge.data.repository

import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.domain.model.isContractUuid
import java.util.Collections

/** Only the local business-fact part of the future full plan.
 * This does NOT certify a prompt, parent, link, source, outbox or root. The caller performs all
 * original prompt/current-unit checks, constructs the logs, then freezes this BEFORE insertAll.
 * UUIDs and timestamps retain their current transaction-time construction semantics.
 */
internal class MetricObservationLocalPlan private constructor(val logs: List<MetricLogEntity>) {
    /** Bind only SQLite-allocated IDs from insertAll's actual return value. All other expected
     * values come from the pre-write plan. Do not rebuild this from a journal or SELECT result.
     */
    fun bindInserted(ids: List<Long>): List<MetricLogEntity> {
        require(ids.size == logs.size && ids.all { it > 0 } && ids.distinct().size == ids.size)
        return Collections.unmodifiableList(logs.zip(ids).map { (log, id) -> log.copy(id = id) })
    }

    companion object {
        fun freezeBeforeInsert(logs: List<MetricLogEntity>): MetricObservationLocalPlan {
            require(logs.isNotEmpty() && logs.all { it.id == 0L && it.metricId > 0 && it.value.isFinite() && isContractUuid(it.uuid) })
            require(logs.map { it.uuid }.distinct().size == logs.size)
            return MetricObservationLocalPlan(Collections.unmodifiableList(logs.map { it.copy() }))
        }
    }
}
