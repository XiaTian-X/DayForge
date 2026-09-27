package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.OneTimeTransmissionEntity

@Dao
interface CompletionFollowUpDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransmission(row: OneTimeTransmissionEntity)

    @Query("SELECT * FROM one_time_transmissions WHERE operationId = :operationId")
    suspend fun transmission(operationId: String): OneTimeTransmissionEntity?

    @Query("UPDATE one_time_transmissions SET rejectionJson = :result WHERE operationId = :operationId AND rejectionJson IS NULL")
    suspend fun recordRejection(operationId: String, result: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmission(row: LocalFactSubmissionEntity)

    @Query("SELECT * FROM local_fact_submissions WHERE operationId = :operationId")
    suspend fun submission(operationId: String): LocalFactSubmissionEntity?

    @Query("SELECT * FROM local_fact_submissions WHERE entityType = :type AND entityUuid = :uuid")
    suspend fun submissionForEntity(type: String, uuid: String): LocalFactSubmissionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrompt(row: CompletionMetricPromptEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updatePrompt(row: CompletionMetricPromptEntity): Int

    @Query("SELECT * FROM completion_metric_prompts WHERE eventUuid = :eventUuid")
    suspend fun prompt(eventUuid: String): CompletionMetricPromptEntity?

    @Query("SELECT * FROM completion_metric_prompts WHERE state = 'pending' ORDER BY rowid")
    suspend fun pendingPrompts(): List<CompletionMetricPromptEntity>
}
