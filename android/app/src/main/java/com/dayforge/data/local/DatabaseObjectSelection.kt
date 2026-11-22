package com.dayforge.data.local

import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.MetricEntity

/** Opaque memory-only route/edit input from an original rendered value. Numeric ID alone is
 * never sufficient after a graph replacement. This is not account or business authority.
 */
class DatabaseObjectSelection private constructor(
    val kind: Kind,
    val id: Long,
    val uuid: String,
    internal val publication: DatabaseEpochPublication
) {
    enum class Kind { HABIT, METRIC }

    internal fun requireHabit(value: HabitEntity) {
        check(kind == Kind.HABIT && value.id == id && value.uuid == uuid) { "DATABASE_EPOCH_SELECTION_CHANGED" }
    }
    internal fun requireMetric(value: MetricEntity) {
        check(kind == Kind.METRIC && value.id == id && value.uuid == uuid) { "DATABASE_EPOCH_SELECTION_CHANGED" }
    }
    companion object {
        /** Caller must pass the publication paired with this exact original rendered row. */
        internal fun habit(value: HabitEntity, publication: DatabaseEpochPublication) =
            DatabaseObjectSelection(Kind.HABIT, value.id, value.uuid, publication)
        internal fun habit(id: Long, uuid: String, publication: DatabaseEpochPublication) =
            DatabaseObjectSelection(Kind.HABIT, id, uuid, publication)
        internal fun metric(value: MetricEntity, publication: DatabaseEpochPublication) =
            DatabaseObjectSelection(Kind.METRIC, value.id, value.uuid, publication)
        /** UUID must originate from the same already-rendered joined projection as id. */
        internal fun metric(id: Long, uuid: String, publication: DatabaseEpochPublication) =
            DatabaseObjectSelection(Kind.METRIC, id, uuid, publication)
    }
}
