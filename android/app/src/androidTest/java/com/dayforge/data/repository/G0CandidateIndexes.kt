package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Isolated non-unique physical indexes only. No new authority, source relation, constraint,
 * SQL permission or production migration. Both original validators run unchanged. */
internal object G0CandidateIndexes {
    enum class Mode { NONE, DATE, REFERENCE, BOTH }
    fun apply(db:SupportSQLiteDatabase,mode:Mode) {
        check(!db.inTransaction())
        db.beginTransactionNonExclusive()
        try {
            db.execSQL("DROP INDEX IF EXISTS g0_completion_habit_day")
            db.execSQL("DROP INDEX IF EXISTS g0_outbox_reference")
            if(mode in setOf(Mode.DATE,Mode.BOTH)) db.execSQL("CREATE INDEX g0_completion_habit_day ON completions(habitId,recordedLocalDate)")
            if(mode in setOf(Mode.REFERENCE,Mode.BOTH)) db.execSQL("CREATE INDEX g0_outbox_reference ON sync_outbox(referenceUuid)")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun opening():RoomDatabase.Callback=object:RoomDatabase.Callback() {
        private val original=G0SchemaOpeningCallback(true)
        override fun onCreate(db:SupportSQLiteDatabase)=original.onCreate(db)
        override fun onOpen(db:SupportSQLiteDatabase) {original.onOpen(db);apply(db,Mode.BOTH)}
    }
}
