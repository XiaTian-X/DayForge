package com.dayforge.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.AuthApi
import com.dayforge.data.local.DataStoreProvider
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.dao.CompletionDao
import com.dayforge.data.local.dao.HabitDao
import com.dayforge.data.local.dao.HabitMetricLinkDao
import com.dayforge.data.local.dao.MetricDao
import com.dayforge.data.local.dao.MetricLogDao
import com.dayforge.data.local.dao.TimeLogDao
import com.dayforge.data.local.dao.SyncConflictDao
import com.dayforge.data.local.dao.SyncOutboxDao
import com.dayforge.data.repository.HabitRepository
import com.dayforge.domain.service.StructuralEditGuard
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.SyncV2Merger
import com.dayforge.domain.service.ConfigImportService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    companion object {
        @Provides
        @Singleton
        fun provideHabitDatabase(@ApplicationContext context: Context): HabitDatabase {
            return HabitDatabaseProvider.getInstance(context)
        }

        @Provides
        @Singleton
        fun provideHabitDao(database: HabitDatabase): HabitDao {
            return database.habitDao()
        }

        @Provides
        @Singleton
        fun provideCompletionDao(database: HabitDatabase): CompletionDao {
            return database.completionDao()
        }

        @Provides
        @Singleton
        fun provideTimeLogDao(database: HabitDatabase): TimeLogDao {
            return database.timeLogDao()
        }

        @Provides
        @Singleton
        fun provideMetricDao(database: HabitDatabase): MetricDao {
            return database.metricDao()
        }

        @Provides
        @Singleton
        fun provideMetricLogDao(database: HabitDatabase): MetricLogDao {
            return database.metricLogDao()
        }

        @Provides
        @Singleton
        fun provideHabitMetricLinkDao(database: HabitDatabase): HabitMetricLinkDao {
            return database.habitMetricLinkDao()
        }

        @Provides
        @Singleton
        fun provideSyncOutboxDao(database: HabitDatabase): SyncOutboxDao {
            return database.syncOutboxDao()
        }

        @Provides
        @Singleton
        fun provideSyncConflictDao(database: HabitDatabase): SyncConflictDao {
            return database.syncConflictDao()
        }

        @Provides
        @Singleton
        fun provideHabitRepository(
            habitDao: HabitDao,
            completionDao: CompletionDao,
            timeLogDao: TimeLogDao,
            database: HabitDatabase,
            structuralEditGuard: StructuralEditGuard
        ): HabitRepository {
            return HabitRepository(habitDao, completionDao, timeLogDao, database, structuralEditGuard)
        }

        @Provides
        @Singleton
        fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
            // Use singleton DataStoreProvider to avoid "multiple DataStores active" error
            return DataStoreProvider.get(context.applicationContext)
        }

        @Provides
        @Singleton
        fun provideTokenCipher(cipher: AndroidKeystoreTokenCipher): TokenCipher = cipher

        @Provides
        @Singleton
        fun providePreferencesManager(dataStore: DataStore<Preferences>): PreferencesManager {
            return PreferencesManager(dataStore)
        }

        @Provides
        @Singleton
        fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager {
            return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }

        @Provides
        @Singleton
        fun provideSyncV2Merger(
            database: HabitDatabase,
            habitDao: HabitDao,
            completionDao: CompletionDao,
            timeLogDao: TimeLogDao,
            metricDao: MetricDao,
            metricLogDao: MetricLogDao,
            habitMetricLinkDao: HabitMetricLinkDao,
            outboxDao: SyncOutboxDao,
            conflictDao: SyncConflictDao
        ): SyncV2Merger = SyncV2Merger(
            database,
            habitDao,
            completionDao,
            timeLogDao,
            metricDao,
            metricLogDao,
            habitMetricLinkDao,
            outboxDao,
            conflictDao
        )

        @Provides
        @Singleton
        fun provideIncrementalSyncRepository(
            api: SyncV2Api,
            endpointResolver: com.dayforge.data.api.EndpointResolver,
            authApi: AuthApi,
            tokenManager: TokenManager,
            preferencesManager: PreferencesManager,
            habitDao: HabitDao,
            completionDao: CompletionDao,
            timeLogDao: TimeLogDao,
            metricDao: MetricDao,
            metricLogDao: MetricLogDao,
            habitMetricLinkDao: HabitMetricLinkDao,
            outboxDao: SyncOutboxDao,
            conflictDao: SyncConflictDao,
            timerSyncRepository: com.dayforge.data.repository.TimerSyncRepository,
            merger: SyncV2Merger,
            json: Json,
            accountSessionCoordinator: com.dayforge.domain.service.AccountSessionCoordinator
        ): IncrementalSyncRepository = IncrementalSyncRepository(
            api,
            endpointResolver,
            authApi,
            tokenManager,
            preferencesManager,
            habitDao,
            completionDao,
            timeLogDao,
            metricDao,
            metricLogDao,
            habitMetricLinkDao,
            outboxDao,
            conflictDao,
            timerSyncRepository,
            merger,
            json,
            accountSessionCoordinator
        )

        @Provides
        @Singleton
        fun provideConfigImportService(
            habitDao: HabitDao,
            metricDao: MetricDao,
            habitMetricLinkDao: HabitMetricLinkDao,
            database: HabitDatabase,
            structuralEditGuard: StructuralEditGuard
        ): ConfigImportService {
            return ConfigImportService(
                habitDao,
                metricDao,
                habitMetricLinkDao,
                database,
                structuralEditGuard
            )
        }
    }
}
