package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.local.entity.NextAcceptanceEntity

@Dao
interface NextRequestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrigin(row: NextRequestOriginEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransmission(row: NextTransmissionEntity)

    @Query("SELECT * FROM next_request_origins WHERE kind=:kind AND requestId=:requestId")
    suspend fun origin(kind: String, requestId: String): NextRequestOriginEntity?

    @Query("SELECT * FROM next_request_origins WHERE kind=:kind AND requestId IN (:ids)")
    suspend fun origins(kind: String, ids: List<String>): List<NextRequestOriginEntity>

    @Query("SELECT * FROM next_transmissions WHERE kind=:kind AND requestId=:requestId")
    suspend fun transmission(kind: String, requestId: String): NextTransmissionEntity?

    @Query("SELECT * FROM next_transmissions WHERE kind=:kind AND requestId IN (:ids)")
    suspend fun transmissions(kind: String, ids: List<String>): List<NextTransmissionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAcceptance(row: NextAcceptanceEntity)

    @Query("SELECT * FROM next_acceptances WHERE kind=:kind AND requestId=:requestId")
    suspend fun acceptance(kind: String, requestId: String): NextAcceptanceEntity?

    @Query("SELECT * FROM next_acceptances WHERE kind=:kind AND requestId IN (:ids)")
    suspend fun acceptances(kind: String, ids: List<String>): List<NextAcceptanceEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM next_request_origins) OR EXISTS(SELECT 1 FROM next_transmissions) OR EXISTS(SELECT 1 FROM next_acceptances) OR EXISTS(SELECT 1 FROM next_structural_dependencies) OR EXISTS(SELECT 1 FROM next_structural_supersessions) OR EXISTS(SELECT 1 FROM next_restart_materializations) OR EXISTS(SELECT 1 FROM next_restart_plan_proofs)")
    suspend fun hasAny(): Boolean
}
