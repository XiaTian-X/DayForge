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
        fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
            // Use singleton DataStoreProvider to avoid "multiple DataStores active" error
            return DataStoreProvider.get(context.applicationContext)
        }

        @Provides
        @Singleton
        fun provideTokenCipher(cipher: AndroidKeystoreTokenCipher): TokenCipher = cipher

        @Provides
        @Singleton
        fun providePreferencesManager(dataStore: DataStore<Preferences>, calendar: com.dayforge.domain.service.DeviceCalendar,
            tokens: com.dayforge.data.local.TokenManager): PreferencesManager {
            return PreferencesManager(dataStore, calendar, tokens)
        }

        @Provides
        @Singleton
        fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager {
            return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }

    }
}
