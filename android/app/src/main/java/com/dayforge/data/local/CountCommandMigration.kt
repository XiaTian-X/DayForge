package com.dayforge.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Only the additive edge. Root initialization and guards are completed by the controlled
 * factory after Room's migration/identity callbacks, still before framework actual COMMIT.
 * Historical18->19 definitions and installation behavior are not changed by this object.
 */
object CountCommandMigration : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        CountTrustRuntime.enlistCommandMigration(db)
        // Private enlistment captured and checked the exact old rows and guard first.
        db.execSQL("DROP TRIGGER `${CountCommandControlSchema.GUARD}`")
        db.execSQL("ALTER TABLE count_trust_control ADD COLUMN commandRootHash TEXT DEFAULT NULL")
        CountCommandV20Schema.definitions.values.filter { it.type == "table" }.forEach {
            db.execSQL(requireNotNull(it.declaredSql))
        }
    }
}
