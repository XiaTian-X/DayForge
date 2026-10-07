package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.NextStructuralDependencyEntity
import com.dayforge.data.local.entity.NextStructuralSupersessionEntity

@Dao
interface NextStructuralCausalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDependency(row: NextStructuralDependencyEntity)

    @Query("SELECT * FROM next_structural_dependencies WHERE operationId=:operationId")
    suspend fun dependency(operationId: String): NextStructuralDependencyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSupersession(row: NextStructuralSupersessionEntity)

    @Query("SELECT * FROM next_structural_supersessions WHERE originalId=:operationId")
    suspend fun supersession(operationId: String): NextStructuralSupersessionEntity?

    @Query("SELECT * FROM next_structural_supersessions WHERE replacementId=:operationId")
    suspend fun supersessionByReplacement(operationId: String): NextStructuralSupersessionEntity?

    @Query("SELECT * FROM next_structural_supersessions WHERE originalId IN (:ids)")
    suspend fun supersessions(ids: List<String>): List<NextStructuralSupersessionEntity>
}
