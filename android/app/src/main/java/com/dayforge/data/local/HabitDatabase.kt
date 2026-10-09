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
        com.dayforge.data.local.entity.NextConfigImportPayloadEntity::class
    ],
    version = 16,
    exportSchema = true
)
@TypeConverters(HabitTypeConverter::class)
abstract class HabitDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao

    abstract fun completionDao(): CompletionDao

    abstract fun countDayDao(): com.dayforge.data.local.dao.CountDayDao

    abstract fun completionFollowUpDao(): CompletionFollowUpDao

    abstract fun nextRecoveryDao(): NextRecoveryDao

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
            clearAllTables()
            openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO sync_control(id, suppressOutbox) VALUES(1, 0)"
            )
        }
    }
}
