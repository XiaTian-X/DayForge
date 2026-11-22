package com.dayforge.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

/** Original source restriction, never permission. Only Host issues it from an already captured
 * graph identity and original publication. Actual transaction participants retain the original
 * publication predicate through END, even when a nested lexical source block returned earlier.
 */
internal object DatabaseOperationSource {
    internal class Frame(val identity: Any, private val stillCurrent: () -> Boolean) {
        @Volatile private var active = true
        fun requireLive() { check(active) { "DATABASE_EPOCH_OPERATION_EXPIRED" }; requireCurrent() }
        fun requireCurrent() { check(stillCurrent()) { "DATABASE_EPOCH_INPUT_EXPIRED" } }
        fun finish() { active = false }
    }
    private val current = ThreadLocal<Frame?>()
    internal fun current(): Frame? = current.get()
    private class Element(val frame: Frame?) : ThreadContextElement<Frame?>,
        AbstractCoroutineContextElement(Key) {
        override fun updateThreadContext(context: CoroutineContext): Frame? = current.get().also { current.set(frame) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Frame?) { current.set(oldState) }
        companion object Key : CoroutineContext.Key<Element>
    }
    internal suspend fun <T> run(identity: Any, stillCurrent: () -> Boolean, block: suspend () -> T): T {
        val frame = Frame(identity, stillCurrent)
        return try { withContext(Element(frame)) { frame.requireLive(); block() } }
        finally { frame.finish() }
    }

    /** Fixed schema metadata only, never a caller-controlled source exemption. Every reader
     * owns a private cache and cannot accept SQL, a database, factory, loader or callback.
     * The sole null source element is scoped to this independent generated in-memory DB.
     * No caller Job is detached or overridden: cancellation propagates, and return/throw restores the
     * same original Frame. Cache publication still follows actual END, close and deep freeze.
     */
    internal class GeneratedReferenceSchemaReader {
        private val cache = ReplicaReferenceSchemaCache()

        suspend fun read(context: Context): ReplicaReferenceSchema = withContext(Element(null)) {
            cache.get {
                val factory = CountTrustOpenHelperFactory(context.applicationContext)
                val reference = Room.inMemoryDatabaseBuilder(context.applicationContext, HabitDatabase::class.java)
                    .openHelperFactory(factory).addCallback(SyncSchemaCallback).build()
                try {
                    CountTrustRuntime.attach(reference, factory)
                    object : ReplicaReferenceSchemaCache.Reference {
                        override suspend fun readCommitted(): ReplicaReferenceSchema = reference.withTransaction {
                            val sql = reference.openHelper.writableDatabase
                            // Statistics are optional in the actual source, but their definitions
                            // must come from this same SQLite build rather than a guessed schema.
                            sql.execSQL("ANALYZE")
                            val objects = ReplicaSqlSchema.read(sql)
                            val version = sql.version
                            val identity = sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                                require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                                    it.getType(1) == Cursor.FIELD_TYPE_STRING) { "SYNC_RECOVERY_REFERENCE_SCHEMA_INVALID" }
                                it.getString(1).also { _ -> require(!it.moveToNext()) { "SYNC_RECOVERY_REFERENCE_SCHEMA_INVALID" } }
                            }
                            ReplicaReferenceSchema(objects, version, identity)
                        }

                        override fun close() = reference.close()
                    }
                } catch (failure: Throwable) {
                    try { reference.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                    throw failure
                }
            }
        }
    }
}
