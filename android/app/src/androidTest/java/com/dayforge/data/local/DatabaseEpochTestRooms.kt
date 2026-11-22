package com.dayforge.data.local

import androidx.room.RoomDatabase
import java.util.concurrent.atomic.AtomicInteger

/** Test-only immutable per-method query observation configuration. It never supplies a DB,
 * factory replacement, migrations, credentials or writer capability. Each create still calls
 * the production controlled builder for the real file, including after actual Host rebuild. */
internal object DatabaseEpochTestRooms {
    internal class Configuration(val query: RoomDatabase.QueryCallback?,
        val native: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? = null) {
        val creates = AtomicInteger()
        // Read-only record of actual new-builder output, never a supplied replacement DB.
        val createdRooms = java.util.concurrent.CopyOnWriteArrayList<HabitDatabase>()
    }
    private val monitor = Any()
    private var current = Configuration(null)
    fun snapshot(): Configuration = synchronized(monitor) { current }
    fun install(query: RoomDatabase.QueryCallback?, native: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? = null): Configuration = synchronized(monitor) {
        check(DatabaseRuntimeHosts.snapshot().all { it.lifecycleEnded }) { "Prior Host owns native work" }
        Configuration(query, native).also { current = it }
    }
    fun release(expected: Configuration) = synchronized(monitor) {
        check(current === expected) { "Test Room configuration was replaced while still owned" }
        current = Configuration(null)
    }
}
