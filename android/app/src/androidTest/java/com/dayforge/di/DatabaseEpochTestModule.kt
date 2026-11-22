package com.dayforge.di

import android.content.Context
import com.dayforge.data.local.DatabaseEpochRoomFactory
import com.dayforge.data.local.DatabaseEpochTestRooms
import com.dayforge.data.local.HabitDatabaseProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module @TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseEpochModule::class])
internal object DatabaseEpochTestModule {
    @Provides @Singleton fun rooms(@ApplicationContext context: Context): DatabaseEpochRoomFactory {
        val original = DatabaseEpochTestRooms.snapshot()
        return object : DatabaseEpochRoomFactory {
            override fun create() = HabitDatabaseProvider.build(context, original.query,
                original.native ?: androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()).also { original.createdRooms += it; original.creates.incrementAndGet() }
        }
    }
}
