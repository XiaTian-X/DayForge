package com.dayforge.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Presence, not successful parsing, closes ordinary work. A lost/corrupt journal never opens it. */
internal object ReplicaSwitchGuard {
    val POINTER = stringPreferencesKey("replica_switch_active_v1")
    const val PENDING = "SYNC_REPLICA_SWITCH_PENDING"

    fun blocked(values: Preferences): Boolean = values.asMap().keys.any { it.name == POINTER.name }
    fun requireOpen(values: Preferences) { check(!blocked(values)) { PENDING } }
    suspend fun requireOpen(store: DataStore<Preferences>) = requireOpen(store.data.first())
}
