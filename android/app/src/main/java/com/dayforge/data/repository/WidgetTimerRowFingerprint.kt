package com.dayforge.data.repository

import android.database.Cursor
import com.dayforge.data.local.HabitDatabase
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Display invalidation only: exact SQLite values, before Room can wrap Int/boolean fields. */
internal suspend fun widgetTimerRowFingerprint(database: HabitDatabase, id: Long): String? {
    check(database.inTransaction())
    val sql = database.openHelper.writableDatabase
    // Bound the only text fields before loading them; this is not the request-journal hash format.
    sql.query("SELECT length(CAST(uuid AS BLOB)), length(CAST(timerTimezone AS BLOB)) FROM timelogs WHERE id=?",
        arrayOf(id)).use { c ->
        if (!c.moveToFirst()) return null
        require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) in 1..36)
        require(c.isNull(1) || c.getType(1) == Cursor.FIELD_TYPE_INTEGER && c.getLong(1) in 1..128)
        check(!c.moveToNext())
    }
    val caller = currentCoroutineContext()
    return sql.query("SELECT * FROM timelogs WHERE id=?", arrayOf(id)).use { c ->
        check(c.moveToFirst())
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (column in 0 until c.columnCount) {
                caller.ensureActive()
                val name = c.getColumnName(column)
                out.writeUTF(name)
                val type = c.getType(column)
                out.writeByte(type)
                when (type) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> out.writeLong(c.getLong(column))
                    Cursor.FIELD_TYPE_STRING -> {
                        require(name in setOf("uuid", "timerTimezone"))
                        val value = c.getString(column).toByteArray(Charsets.UTF_8)
                        out.writeInt(value.size); out.write(value)
                    }
                    else -> error("TIMER_WIDGET_ROW_TYPE_INVALID")
                }
            }
        }
        check(!c.moveToNext())
        "v1:" + nextRequestHash(bytes.toByteArray())
    }
}
