package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.*

/** Shared physical storage/auth setup only; intentionally contains no test methods. */
abstract class CountTrustRecoveryFixture : NextCoreRequestFixture() {
    internal var decryptCalls = 0
    internal var failDecryptAt = Int.MAX_VALUE
    override fun tokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = PlaintextTokenCipher.encrypt(value)
        override fun decrypt(value: String): String? =
            if (++decryptCalls == failDecryptAt) null else PlaintextTokenCipher.decrypt(value)
    }
    internal fun preparation() = CountTrustRecoveryPreparation(db, tokens, sessions)
    internal suspend fun raw() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    internal suspend fun localRows() = db.withTransaction { CountTrustRuntime.maintenance(db) {
        CountTrustSchema.LOCAL_TABLES.associateWith { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM main.`$table` ORDER BY rowid").use { c -> buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { column -> when (c.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> "null"
                    Cursor.FIELD_TYPE_BLOB -> "blob:" + CountTrustIndex.hash(c.getBlob(column))
                    else -> "${c.getType(column)}:${c.getString(column)}"
                } })
            } }
        }
    } }
    internal suspend fun seedCache(nodes: Int = 1, bytes: Int = 64) = db.withTransaction { CountTrustRuntime.maintenance(db) {
        val sql = db.openHelper.writableDatabase
        repeat(nodes) { index ->
            val value = ByteArray(bytes) { 65 }.also {
                it[0] = (index and 255).toByte(); it[1] = (index ushr 8).toByte()
            }
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf(CountTrustIndex.hash(value), value))
        }
        sql.execSQL("INSERT INTO count_trust_routes VALUES('completions','key',NULL,NULL,NULL,NULL,'derived','hash')")
        sql.execSQL("INSERT INTO count_command_results VALUES('command','input','fact','request','result','unknown')")
    } }
}
