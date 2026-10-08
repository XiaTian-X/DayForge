package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.NextRestartMaterializationEntity
import com.dayforge.data.local.entity.NextRestartPlanProofEntity

@Dao
interface NextRestartDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun materialize(row: NextRestartMaterializationEntity)

    @Query("SELECT * FROM next_restart_materializations WHERE operationId=:id")
    suspend fun materialization(id: String): NextRestartMaterializationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlanProof(row: NextRestartPlanProofEntity)

    @Query("SELECT * FROM next_restart_plan_proofs WHERE operationId=:id")
    suspend fun planProof(id: String): NextRestartPlanProofEntity?
}
