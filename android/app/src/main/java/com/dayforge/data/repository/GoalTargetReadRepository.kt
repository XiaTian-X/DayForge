package com.dayforge.data.repository

import com.dayforge.data.local.dao.HabitDao

/** Exact original Goal editor reads from one graph. No cache, authority, write, new query
 * shape or transaction is introduced by this adapter. Fixed constructors use the same calls. */
internal class GoalTargetReadRepository(private val habits: HabitDao) {
    suspend fun habit(id: Long) = habits.getHabitById(id)
    fun children(uuid: String) = habits.getChildrenByParentUuid(uuid)
}
