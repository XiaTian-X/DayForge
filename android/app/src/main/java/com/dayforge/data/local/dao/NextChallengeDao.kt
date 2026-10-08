package com.dayforge.data.local.dao

import androidx.room.*
import com.dayforge.data.local.entity.*

@Dao
interface NextChallengeDao {
    @Query("SELECT * FROM next_challenge_rounds ORDER BY activityUuid,generation")
    suspend fun rounds(): List<NextChallengeRoundEntity>
    @Query("SELECT * FROM next_challenge_births ORDER BY entityType,entityUuid")
    suspend fun births(): List<NextChallengeBirthEntity>
    @Query("SELECT * FROM next_challenge_state ORDER BY id")
    suspend fun states(): List<NextChallengeStateEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM next_challenge_state) OR EXISTS(SELECT 1 FROM next_challenge_rounds) OR EXISTS(SELECT 1 FROM next_challenge_births)")
    suspend fun hasAny(): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: NextChallengeRoundEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: NextChallengeBirthEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: NextChallengeStateEntity): Long
    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(row: NextChallengeStateEntity): Int
}
