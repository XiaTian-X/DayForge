package com.dayforge.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Singleton object to provide a single DataStore instance across the app.
 * This ensures consistent access whether from Hilt-injected components or widget context.
 *
 * IMPORTANT: DataStore must be a singleton per file. Using multiple instances
 * will cause "There are multiple DataStores active for the same file" error.
 */
object DataStoreProvider {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

    /**
     * Get the DataStore instance from any context.
     * Thread-safe singleton pattern ensures only one instance exists.
     */
    fun get(context: Context): DataStore<Preferences> {
        return instance ?: synchronized(this) {
            instance ?: context.applicationContext.appDataStore.also { instance = it }
        }
    }
}