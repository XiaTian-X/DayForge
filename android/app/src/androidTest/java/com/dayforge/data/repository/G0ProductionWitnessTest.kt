package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ProductionWitnessTest : G0SealedDomainFixture() {
    private fun sql() = db.openHelper.writableDatabase

    /** Supply the changed schema ONLY as a counterexample against the independent table checks. */
    private suspend fun rejectSuppliedSchema(): String? {
        val handle = sql()
        handle.execSQL("PRAGMA recursive_triggers=ON")
        return rejected { db.withTransaction { domainGate.installJournal(G0DomainJournal.trustedSchema(handle)) } }.message
    }

    @Test fun unknownTableAndExtraColumnStillFailEvenWithMatchingCallerSchema() = runBlocking<Unit> {
        sql().execSQL("CREATE TABLE unexpected_g0_table(id INTEGER)")
        assertEquals("G0_DOMAIN_TABLE_COVERAGE", rejectSuppliedSchema())
        sql().execSQL("DROP TABLE unexpected_g0_table")
        sql().execSQL("ALTER TABLE count_command_nodes ADD COLUMN unapproved TEXT")
        assertEquals("G0_DOMAIN_PRODUCTION_SCHEMA", rejectSuppliedSchema())
    }

    @Test fun existingProductionRowsRequireTheirOriginalValidator() = runBlocking<Unit> {
        sql().execSQL("INSERT INTO count_command_nodes(digest,body) VALUES('unvalidated',X'0102')")
        assertEquals("G0_DOMAIN_PRODUCTION_STATE_REQUIRES_VALIDATOR", rejectSuppliedSchema())
    }

    @Test fun noDirectWriteOrReadIsGrantedForAnyWitnessTable() = runBlocking<Unit> {
        sealDomain()
        domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
            for (table in G0ProductionWitnessColumns.tableNames) {
                for (statement in listOf("INSERT INTO `$table` DEFAULT VALUES", "UPDATE `$table` SET rowid=rowid", "DELETE FROM `$table`")) {
                    assertTrue(rejected { sql().execSQL(statement) } is G0DomainSqlGate.Denied)
                }
                assertTrue(rejected { sql().query("SELECT * FROM `$table`").close() } is G0DomainSqlGate.Denied)
            }
            assertTrue(domainGate.changes().isEmpty())
        }
    }

    private data class Field(val name: String, val type: String, val required: Boolean, val primary: Boolean) {
        fun sample(second: Boolean): String = when {
            !required && !second -> "NULL"
            type == "INTEGER" -> if (second) "2" else "1"
            type == "TEXT" -> if (second) "'changed'" else "'original'"
            type == "BLOB" -> if (second) "X'0304'" else "X'0102'"
            else -> error("Missing explicit fixture for $type")
        }
    }

    @Test fun triggerSideEffectsOnAllNineTablesAreCapturedAndCannotBeCertified() = runBlocking<Unit> {
        val originalMetric = requireNotNull(db.metricDao().getMetricById(metric.id))
        val originalOutbox = db.syncOutboxDao().getAll()
        val tables = G0ProductionWitnessColumns.tableNames
        assertEquals(9, tables.size)
        val handle = sql()
        val fields = tables.associateWith { table ->
            handle.query("PRAGMA main.table_info(`$table`)").use { cursor -> buildList {
                while (cursor.moveToNext()) add(Field(cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(5) != 0))
            } }
        }
        val body = fields.flatMap { (table, columns) ->
            val names = columns.joinToString { "`${it.name}`" }
            val values = columns.joinToString { it.sample(false) }
            val updates = columns.filterNot { it.primary }.joinToString { "`${it.name}`=${it.sample(true)}" }
            listOf("INSERT INTO `$table` ($names) VALUES ($values)", "UPDATE `$table` SET $updates", "DELETE FROM `$table`")
        }
        val trigger = "g0_witness_side_effect"
        val declaration = "CREATE TRIGGER $trigger AFTER UPDATE OF name ON metrics BEGIN ${body.joinToString("; ")}; END"
        // Deliberately admit this exact test-only trigger to exercise the independent native write-set defense.
        val expected = (trustedDomainSchema() + listOf(listOf("main", "trigger", trigger, "metrics", declaration)))
            .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        handle.execSQL(declaration)
        handle.execSQL("PRAGMA recursive_triggers=ON")
        db.withTransaction { domainGate.installJournal(expected) }
        domainGate.seal()
        var callbackEntered = false
        val failed = rejected {
            domainGate.transaction(db, G0DomainSqlGate.Phase.BUSINESS) {
                handle.execSQL("UPDATE metrics SET name=?,description=?,updatedAt=? WHERE id=?", arrayOf<Any>("Injected", "", millis, metric.id))
                val grouped = domainGate.changes().filter { it.table in tables }.groupBy { it.table }
                assertEquals(tables, grouped.keys)
                for ((table, events) in grouped) {
                    assertEquals(listOf("INSERT", "UPDATE", "DELETE"), events.map { it.operation })
                    assertNull(events.first().old)
                    assertEquals(fields.getValue(table).size, requireNotNull(events.first().new).size)
                    assertEquals(events.first().new, events[1].old)
                    assertNotEquals(events[1].old, events[1].new)
                    assertEquals(events[1].new, events.last().old)
                    assertNull(events.last().new)
                }
                val blob = requireNotNull(grouped.getValue("count_command_nodes").first().new)[1]
                assertEquals(Cursor.FIELD_TYPE_BLOB, blob.type)
                assertEquals(listOf(1.toByte(), 2.toByte()), blob.value)
                domainGate.certifyChanges { callbackEntered = true }
            }
        }
        assertEquals("G0_DOMAIN_PRODUCTION_STATE_CHANGED", failed.message)
        assertFalse(callbackEntered)
        freshGateForColdOpen()
        storage.reopen()
        assertEquals(originalMetric, db.metricDao().getMetricById(metric.id))
        assertEquals(originalOutbox, db.syncOutboxDao().getAll())
        assertTrue(tables.all { count(it) == 0 })
    }
}
