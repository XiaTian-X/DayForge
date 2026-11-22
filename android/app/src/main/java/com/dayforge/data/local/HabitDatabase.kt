package com.dayforge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.CompletionFollowUpDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.NextRecoveryDao
import com.dayforge.data.local.dao.NextSyncStateDao
import com.dayforge.data.local.dao.NextRequestDao
import com.dayforge.data.local.dao.NextStructuralCausalDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.OneTimeTransmissionEntity
import com.dayforge.data.local.entity.NextRecoveryStateEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.local.entity.NextRejectionEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.local.entity.NextAcceptanceEntity
import com.dayforge.data.local.entity.NextStructuralDependencyEntity
import com.dayforge.data.local.entity.NextStructuralSupersessionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.local.entity.HabitTypeConverter
import com.dayforge.data.local.entity.MetricEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.SyncControlEntity
import com.dayforge.data.local.entity.SyncEntityStateEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.TimeLogDayAllocationEntity
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

/**
 * Production local schema, incrementally migrated from the version-1 baseline.
 *
 * Pre-baseline development databases have no supported migration path. Even when a version
 * number overlaps, their schema identity must not be accepted or destructively rebuilt.
 */
@Database(
    entities = [
        HabitEntity::class,
        CompletionEntity::class,
        com.dayforge.data.local.entity.CountDayEntity::class,
        TimeLogEntity::class,
        MetricEntity::class,
        MetricLogEntity::class,
        HabitMetricLinkEntity::class,
        SyncOutboxEntity::class,
        SyncEntityStateEntity::class,
        SyncConflictEntity::class,
        SyncControlEntity::class,
        TimerCommandEntity::class,
        TimerSegmentEntity::class,
        TimeLogDayAllocationEntity::class,
        LocalFactSubmissionEntity::class,
        CompletionMetricPromptEntity::class,
        OneTimeTransmissionEntity::class,
        NextRecoveryStateEntity::class,
        NextRequestOriginEntity::class,
        NextTransmissionEntity::class,
        NextAcceptanceEntity::class,
        NextStructuralDependencyEntity::class,
        NextStructuralSupersessionEntity::class,
        NextSyncStateEntity::class,
        NextRejectionEntity::class,
        com.dayforge.data.local.entity.NextChallengeRoundEntity::class,
        com.dayforge.data.local.entity.NextChallengeBirthEntity::class,
        com.dayforge.data.local.entity.NextChallengeStateEntity::class,
        com.dayforge.data.local.entity.NextRestartMaterializationEntity::class,
        com.dayforge.data.local.entity.NextRestartPlanProofEntity::class,
        com.dayforge.data.local.entity.NextConfigImportEntity::class,
        com.dayforge.data.local.entity.NextConfigImportPayloadEntity::class,
        com.dayforge.data.local.entity.NextRecoveryAuditEntity::class,
        com.dayforge.data.local.entity.NextAbandonedRequestEntity::class,
        com.dayforge.data.local.entity.NextRecoveryAuditPayloadEntity::class,
        com.dayforge.data.local.entity.NextReplicaFenceEntity::class,
        com.dayforge.data.local.entity.CountTrustControlEntity::class,
        com.dayforge.data.local.entity.CountTrustRootEntity::class,
        com.dayforge.data.local.entity.CountTrustNodeEntity::class,
        com.dayforge.data.local.entity.CountTrustRouteEntity::class,
        com.dayforge.data.local.entity.CountCommandResultEntity::class,
        com.dayforge.data.local.entity.CountCommandIntentEntity::class,
        com.dayforge.data.local.entity.CountCommandStatusEntity::class,
        com.dayforge.data.local.entity.CountCommandRecoveryRootEntity::class,
        com.dayforge.data.local.entity.CountCommandNodeEntity::class
    ],
    version = 20,
    exportSchema = true
)
@TypeConverters(HabitTypeConverter::class)
abstract class HabitDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao

    abstract fun completionDao(): CompletionDao

    abstract fun countDayDao(): com.dayforge.data.local.dao.CountDayDao

    abstract fun completionFollowUpDao(): CompletionFollowUpDao

    abstract fun nextRecoveryDao(): NextRecoveryDao

    abstract fun nextReplicaFenceDao(): com.dayforge.data.local.dao.NextReplicaFenceDao

    abstract fun nextSyncStateDao(): NextSyncStateDao

    abstract fun nextChallengeDao(): com.dayforge.data.local.dao.NextChallengeDao

    abstract fun nextRestartDao(): com.dayforge.data.local.dao.NextRestartDao

    abstract fun nextRequestDao(): NextRequestDao

    abstract fun nextStructuralCausalDao(): NextStructuralCausalDao

    abstract fun timeLogDao(): TimeLogDao

    abstract fun metricDao(): MetricDao

    abstract fun metricLogDao(): MetricLogDao

    abstract fun habitMetricLinkDao(): HabitMetricLinkDao

    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncConflictDao(): SyncConflictDao

    suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            val owner = CountTrustRuntime.forDatabase(this@HabitDatabase)
            if (owner == null) {
                // Explicit unsealed reference/G0 builders own no persistent trust capability.
                clearAllTables()
                openHelper.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO sync_control(id, suppressOutbox) VALUES(1, 0)"
                )
            } else {
                // Existing callers may own an outer account lock; no lock-free claim is made.
                // Reference construction finishes before this source write transaction begins.
                val reference = ReplicaBusinessReferenceSchema.read(owner.context)
                withTransaction {
                    CountTrustRuntime.maintenance(this@HabitDatabase) {
                        val sql = openHelper.writableDatabase
                        ApprovedBusinessSchema.requireCleanupInTransaction(sql, reference)
                        val primaryReset = owner.preparePrimaryReset(this@HabitDatabase)
                        // Reset may commit even if subsequent compaction/credential cleanup fails.
                        // Permanently retire before its first DML; cached ordinary handles remain
                        // usable for this original control workflow until actual graph drain.
                        owner.retireFastPathForRecovery()
                        CountTrustSchema.resetLocalInTransaction(sql)
                        owner.initializePrimaryReset(primaryReset)
                        sql.execSQL("PRAGMA defer_foreign_keys=ON")
                        sql.query("PRAGMA defer_foreign_keys").use { check(it.moveToFirst() && it.getInt(0) == 1) }
                        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
                        // Keep the new untrusted control alive while every old row invalidation runs.
                        for (table in CountTrustSchema.BUSINESS_TABLES) sql.execSQL("DELETE FROM `$table`")
                        sql.execSQL("INSERT INTO sync_control(id,suppressOutbox) VALUES(1,0)")
                        sql.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
                        CountTrustSchema.requireControl(sql)
                        owner.finishPrimaryReset(primaryReset)
                    }
                }
                CountTrustRuntime.compactAfterClear(this@HabitDatabase)
            }
        }
    }
}
