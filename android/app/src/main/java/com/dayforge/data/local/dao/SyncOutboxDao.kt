package com.dayforge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox WHERE deadLetteredAt IS NULL ORDER BY id")
    suspend fun getAll(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE operationId = :operationId")
    suspend fun getByOperationId(operationId: String): SyncOutboxEntity?

    @Query("SELECT * FROM sync_outbox WHERE id=:id")
    suspend fun getById(id: Long): SyncOutboxEntity?

    /** Include rejected predecessors: omitting them would turn a broken chain into new intent. */
    @Query("SELECT * FROM sync_outbox WHERE referenceUuid = :activityUuid AND recordType IN ('completion', 'one_time_completion') ORDER BY id")
    suspend fun getActivityIntents(activityUuid: String): List<SyncOutboxEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE recordType = 'one_time_completion') OR EXISTS(SELECT 1 FROM local_fact_submissions) OR EXISTS(SELECT 1 FROM completion_metric_prompts) OR EXISTS(SELECT 1 FROM one_time_transmissions)")
    suspend fun hasOneTimeIntents(): Boolean

    /** Even an orphaned/damaged staged checkpoint must block legacy recovery and mutation. */
    @Query("SELECT EXISTS(SELECT 1 FROM next_recovery_state) OR EXISTS(SELECT 1 FROM next_sync_state) OR EXISTS(SELECT 1 FROM next_rejections) OR EXISTS(SELECT 1 FROM next_challenge_state) OR EXISTS(SELECT 1 FROM next_challenge_rounds) OR EXISTS(SELECT 1 FROM next_challenge_births)")
    suspend fun hasProtocolNextRecovery(): Boolean

    /** Origins/journals block v4 even if their source was removed or malformed. */
    @Query("SELECT EXISTS(SELECT 1 FROM next_request_origins) OR EXISTS(SELECT 1 FROM next_transmissions) OR EXISTS(SELECT 1 FROM next_acceptances) OR EXISTS(SELECT 1 FROM next_structural_dependencies) OR EXISTS(SELECT 1 FROM next_structural_supersessions) OR EXISTS(SELECT 1 FROM next_restart_materializations) OR EXISTS(SELECT 1 FROM next_restart_plan_proofs) OR EXISTS(SELECT 1 FROM next_config_imports) OR EXISTS(SELECT 1 FROM next_config_import_payloads)")
    suspend fun hasProtocolNextRequests(): Boolean

    @Query("SELECT * FROM sync_outbox WHERE recordType=:recordType AND entityUuid=:entityUuid ORDER BY id LIMIT 10001")
    suspend fun getEntityIntents(recordType: String, entityUuid: String): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE deadLetteredAt IS NULL")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE deadLetteredAt IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE deadLetteredAt IS NOT NULL")
    suspend fun countDeadLetters(): Int

    @Query("SELECT * FROM sync_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY deadLetteredAt, id")
    suspend fun getDeadLetters(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE deadLetteredAt IS NOT NULL ORDER BY deadLetteredAt, id")
    fun observeDeadLetters(): Flow<List<SyncOutboxEntity>>

    @Query("DELETE FROM sync_outbox WHERE id = :id AND deadLetteredAt IS NOT NULL")
    suspend fun deleteDeadLetter(id: Long)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sync_outbox WHERE recordType = :recordType AND entityUuid = :entityUuid")
    suspend fun deleteByEntity(recordType: String, entityUuid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE recordType = :recordType AND entityUuid = :entityUuid AND id > :afterId)")
    suspend fun hasNewerPending(recordType: String, entityUuid: String, afterId: Long): Boolean

    @Query("""
        UPDATE sync_outbox
        SET wireEntityUuid = :wireEntityUuid,
            action = :action,
            payloadJson = :payloadJson,
            baseRevision = :baseRevision,
            basePayloadJson = :basePayloadJson,
            attemptedAt = :attemptedAt,
            attemptCount = attemptCount + 1,
            lastError = NULL,
            errorCode = NULL
        WHERE id = :id
    """)
    suspend fun markPrepared(
        id: Long,
        wireEntityUuid: String,
        action: String,
        payloadJson: String,
        baseRevision: Long?,
        basePayloadJson: String?,
        attemptedAt: Long
    )

    @Query("UPDATE sync_outbox SET lastError = :error WHERE id = :id")
    suspend fun markError(id: Long, error: String)

    @Query("UPDATE sync_outbox SET errorCode = :errorCode, lastError = :error, deadLetteredAt = :deadLetteredAt WHERE id = :id")
    suspend fun markDeadLetter(id: Long, errorCode: String?, error: String, deadLetteredAt: Long)

    @Query("""
        UPDATE sync_outbox
        SET operationId = :newOperationId,
            action = CASE
                WHEN recordType = 'completion'
                     AND wireEntityUuid != entityUuid
                THEN 'delete'
                ELSE action
            END,
            wireEntityUuid = entityUuid,
            payloadJson = NULL,
            baseRevision = NULL,
            basePayloadJson = NULL,
            attemptedAt = NULL,
            errorCode = NULL,
            lastError = NULL,
            deadLetteredAt = NULL
        WHERE id = :id AND deadLetteredAt IS NOT NULL
    """)
    suspend fun retryDeadLetter(id: Long, newOperationId: String)

    @Query("SELECT * FROM sync_entity_state WHERE entityType = :entityType AND entityUuid = :entityUuid")
    suspend fun getState(entityType: String, entityUuid: String): SyncEntityStateEntity?

    @Query("SELECT * FROM sync_entity_state WHERE entityType = :entityType")
    suspend fun getStatesForType(entityType: String): List<SyncEntityStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: SyncEntityStateEntity)

    @Query("DELETE FROM sync_entity_state WHERE entityType = :entityType AND entityUuid = :entityUuid")
    suspend fun deleteState(entityType: String, entityUuid: String)
}
