package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity

@Dao
interface NextRequestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrigin(row: NextRequestOriginEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransmission(row: NextTransmissionEntity)

    @Query("SELECT * FROM next_request_origins WHERE kind=:kind AND requestId=:requestId")
    suspend fun origin(kind: String, requestId: String): NextRequestOriginEntity?

    @Query("SELECT * FROM next_transmissions WHERE kind=:kind AND requestId=:requestId")
    suspend fun transmission(kind: String, requestId: String): NextTransmissionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM next_request_origins) OR EXISTS(SELECT 1 FROM next_transmissions)")
    suspend fun hasAny(): Boolean
}
