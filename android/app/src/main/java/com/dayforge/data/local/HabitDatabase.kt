package com.dayforge.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.entity.CompletionEntity
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
 * The first production-ready local schema.
 *
 * Pre-baseline development databases used versions 3 through 24. They contain
 * test data only and are intentionally rebuilt through the downgrade fallback.
 */
@Database(
    entities = [
        HabitEntity::class,
        CompletionEntity::class,
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
        TimeLogDayAllocationEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(HabitTypeConverter::class)
abstract class HabitDatabase : RoomDatabase() {

    abstract fun habitDao(): HabitDao

    abstract fun completionDao(): CompletionDao

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
