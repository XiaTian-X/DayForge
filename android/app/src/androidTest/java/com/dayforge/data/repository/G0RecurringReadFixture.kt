package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitType
import java.time.ZonedDateTime

/** Real producers and original COUNT day capture; no synthetic summary rows. */
abstract class G0RecurringReadFixture : G0SealedDomainFixture() {
    override fun domainOpeningCallback()=G0SchemaOpeningCallback(true)
    internal suspend fun seed(history:Int=2):List<Long> {
        register();freshGateForColdOpen(recurringReads=true);storage.reopen()
        val now=ZonedDateTime.now();val date=now.toLocalDate()
        var checkId=0L
        producer().write(local()) { checkId=db.habitDao().insert(habit.copy(id=0,uuid=id(6201),name="Real check",habitType=HabitType.CHECK_IN)) }
        for((id,value) in (0 until history).map { habit.id to (it+2) } + listOf(checkId to 1)) {
            val h=requireNotNull(db.habitDao().getHabitById(id))
            producer().write(local()) {
                val fact=CompletionEntity(habitId=h.id,habitUuid=h.uuid,date=date.toDisplayMillis(),actualCompletedAt=now.toInstant().toEpochMilli(),value=value)
                if(h.habitType==HabitType.COUNTING) NextCountDayStore(db).capture(h,fact)
                db.completionDao().insertForSync(fact)
            }
        }
        return listOf(habit.id,checkId)
    }
    internal open fun owner()=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate)
    // Independent native read-only connection, types and all persistent tables, no normalization.
    internal fun state():Map<String,List<String>> = SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
        null,SQLiteDatabase.OPEN_READONLY).use { raw ->
        val tables=raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",null).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        }
        tables.associateWith { table -> raw.rawQuery("SELECT * FROM `$table`",null).use { c -> buildList {
            while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i -> when(c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> "N"
                Cursor.FIELD_TYPE_INTEGER -> "I${c.getLong(i)}"
                Cursor.FIELD_TYPE_FLOAT -> "F${java.lang.Double.doubleToRawLongBits(c.getDouble(i))}"
                Cursor.FIELD_TYPE_BLOB -> "B${android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP)}"
                else -> "S${android.util.Base64.encodeToString(c.getString(i).toByteArray(),android.util.Base64.NO_WRAP)}"
            } })
        }.sorted() } }
    }
}
