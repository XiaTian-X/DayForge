package com.dayforge.data.repository

import android.database.Cursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0RecurringSqlBoundaryTest : G0SealedDomainFixture() {
    private val query="SELECT * FROM count_days WHERE habitId=? ORDER BY localDate"
    private fun denied(body:()->Unit) { assertTrue(runCatching(body).exceptionOrNull() is G0DomainSqlGate.Denied) }
    @Test fun ordinaryMetricProfileStillRejectsRecurringTemplates()=runBlocking<Unit> {
        sealDomain()
        domainGate.transaction(db,G0DomainSqlGate.Phase.READ) {
            denied { domainGate.sqlClient().query(query,arrayOf(habit.id)).close() }
        }
    }
    @Test fun optInRemainsReadOnlyWithExactBindingsAndOwnedCursor()=runBlocking<Unit> {
        freshGateForColdOpen(recurringReads=true);storage.reopen();sealDomain()
        val sql=domainGate.sqlClient()
        denied { sql.query(query,arrayOf(habit.id)).close() }
        lateinit var cursor:Cursor
        domainGate.transaction(db,G0DomainSqlGate.Phase.READ) {
            cursor=sql.query(query,arrayOf(habit.id));assertFalse(cursor.moveToFirst())
            denied { sql.query(query,arrayOf(habit.id.toString())).close() }
            denied { sql.query(query,emptyArray()).close() }
            denied { sql.query("$query LIMIT 1",arrayOf(habit.id)).close() }
            denied { sql.execSQL("DELETE FROM completions") }
            denied { sql.execSQL("CREATE TEMP TABLE bypass(id INTEGER)") }
            denied { sql.beginTransaction() }
        }
        cursor.use { denied { it.moveToFirst() } }
        domainGate.transaction(db,G0DomainSqlGate.Phase.BUSINESS) {
            denied { sql.query(query,arrayOf(habit.id)).close() }
            domainGate.finalRead { denied { sql.query(query,arrayOf(habit.id)).close() } }
        }
    }
}
