package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.CountDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CountDayDao {
    @Query("SELECT * FROM count_days WHERE habitId=:habitId AND localDate=:localDate")
    suspend fun get(habitId: Long, localDate: String): CountDayEntity?

    @Query("SELECT * FROM count_days WHERE habitId=:habitId ORDER BY localDate")
    suspend fun forHabit(habitId: Long): List<CountDayEntity>

    @Query("SELECT * FROM count_days ORDER BY habitId,localDate")
    fun observeAll(): Flow<List<CountDayEntity>>

    /** Invalidation pulse, not an authority/cache. A head may change without changing any count row. */
    @Query("""SELECT (SELECT COUNT(*) FROM count_days) + (SELECT COUNT(*) FROM completions) +
        (SELECT COUNT(*) FROM habits) + (SELECT COUNT(*) FROM next_sync_state) +
        (SELECT COUNT(*) FROM next_challenge_state) + (SELECT COUNT(*) FROM next_challenge_rounds) +
        (SELECT COUNT(*) FROM next_challenge_births) + (SELECT COUNT(*) FROM next_request_origins) +
        (SELECT COUNT(*) FROM next_transmissions) + (SELECT COUNT(*) FROM next_acceptances) +
        (SELECT COUNT(*) FROM next_restart_materializations) + (SELECT COUNT(*) FROM next_restart_plan_proofs) +
        (SELECT COUNT(*) FROM sync_outbox) + (SELECT COUNT(*) FROM sync_entity_state)""")
    fun observeReadEvidenceChanges(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(day: CountDayEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(day: CountDayEntity): Int
}
