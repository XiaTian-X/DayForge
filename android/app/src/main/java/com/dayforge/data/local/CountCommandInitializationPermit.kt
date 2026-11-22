package com.dayforge.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Only the controlled factory's actual bootstrap/lifecycle objects implement this sealed
 * capability. It does not expose a raw handle or arbitrary SQL callback. No normal onOpen,
 * root miss, key alias or maintenance lease can manufacture initialization permission.
 */
internal sealed interface CountCommandInitializationPermit {
    enum class Source { NEW_DATABASE, MIGRATION_19_20, AUTHORIZED_CLEAR_REPLACE }
    fun begin(database: SupportSQLiteDatabase): Source
    fun previousSchema(): List<List<String?>>?
    fun previousContents(): String?
    fun expectedSchema(): List<List<String?>>?
    fun finish(database: SupportSQLiteDatabase, exactFinalSchema: List<List<String?>>)
}
