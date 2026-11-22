package com.dayforge.data.appearance

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.local.ReplicaReferenceSchema
import com.dayforge.data.local.ReplicaReferenceSchemaCache
import com.dayforge.data.local.ReplicaSqlSchema

/** This process's generated Room5/SQLite template only, never an observed icon database.
 * The shared cache owns/finalizes the reference before publishing deeply frozen values.
 * Context/Room/Job/source proof/authority are not retained; every actual read still validates
 * complete current schema, identity, types, integrity, foreign keys and owned raw contents.
 */
internal object ReplicaIconReferenceSchema {
    private val cache = ReplicaReferenceSchemaCache()

    suspend fun read(context: Context): ReplicaReferenceSchema = cache.get {
        val reference = Room.inMemoryDatabaseBuilder(context.applicationContext, AccountIconDatabase::class.java).build()
        object : ReplicaReferenceSchemaCache.Reference {
            override suspend fun readCommitted(): ReplicaReferenceSchema = reference.withTransaction {
                val sql = reference.openHelper.writableDatabase
                sql.execSQL("ANALYZE")
                val objects = ReplicaSqlSchema.read(sql)
                val version = sql.version
                val identity = sql.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                    require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING)
                    it.getString(0)
                }
                ReplicaReferenceSchema(objects, version, identity)
            }
            override fun close() = reference.close()
        }
    }
}
