package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.OneTimeTransmissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompletionFollowUpDao {
    /** Constant value deliberately still emits on every relevant Room invalidation. */
    @Query("SELECT (SELECT COUNT(*) FROM sync_outbox) + (SELECT COUNT(*) FROM completions) + (SELECT COUNT(*) FROM habits) + (SELECT COUNT(*) FROM completion_metric_prompts) + (SELECT COUNT(*) FROM one_time_transmissions)")
    fun observeOneTimeChanges(): Flow<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM local_fact_submissions WHERE entityType = 'activity_event' AND referenceUuid = :activityUuid) OR EXISTS(SELECT 1 FROM completion_metric_prompts WHERE activityUuid = :activityUuid)")
    suspend fun hasActivityHistory(activityUuid: String): Boolean

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
