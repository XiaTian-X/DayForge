package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.CheckInResult
import com.dayforge.domain.service.CheckInService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original r3 partial-replay counterexample, now targeting the control-root commitment.
 * The entire current control row is retained. Old Root MAC authenticity is separately shown;
 * it never authorizes current absence. No whole-control rollback protection is claimed.
 * Candidate only: not compiled or run. */
@RunWith(AndroidJUnit4::class)
class CountCommandParentReplayTest : NextObjectEditorFixture() {
    private val sql get() = db.openHelper.writableDatabase
    private val primaryTables = (CountCommandV20Schema.tables + "count_command_results").sorted()
    private data class Cell(val type: Int, val value: Any?) {
        fun binding(): Any? = if (type == Cursor.FIELD_TYPE_BLOB) (value as List<*>).map { it as Byte }.toByteArray() else value
    }
    private data class Table(val name: String, val columns: List<String>, val rows: List<List<Cell>>)
    private suspend fun primaryImage(): List<Table> = db.withTransaction {
        primaryTables.map { table -> sql.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            Table(table, c.columnNames.toList(), buildList {
                while (c.moveToNext()) add((0 until c.columnCount).map { column ->
                    val type = c.getType(column)
                    Cell(type, when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(column)
                        Cursor.FIELD_TYPE_STRING -> c.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(column).toList()
                        else -> error("Unknown native type")
                    })
                })
            })
        } }
    }
    private suspend fun restoreOnlyPrimary(snapshot: List<Table>) = db.withTransaction {
        require(snapshot.map { it.name } == primaryTables)
        requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            for (table in snapshot) sql.execSQL("DELETE FROM `${table.name}`")
            for (table in snapshot) for (row in table.rows) {
                val columns = table.columns.joinToString(",") { "`$it`" }
                val placeholders = row.joinToString(",") { "?" }
                sql.execSQL("INSERT INTO `${table.name}` ($columns) VALUES ($placeholders)", row.map { it.binding() }.toTypedArray())
            }
        }
    }
    private suspend fun generation(): Long = db.withTransaction {
        sql.query("SELECT generation FROM count_trust_control WHERE id=1").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
    }
    private fun nativeImage(query: (String) -> Cursor): List<Table> {
        val schema = query("SELECT type,name,tbl_name,rootpage,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
            Table("@schema", c.columnNames.toList(), cells(c))
        }
        val names = query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return listOf(schema) + names.map { name -> query("SELECT rowid,* FROM main.`$name` ORDER BY rowid").use { c ->
            Table(name, c.columnNames.toList(), cells(c))
        } }
    }
    private fun cells(c: Cursor): List<List<Cell>> = buildList {
        while (c.moveToNext()) add((0 until c.columnCount).map { at ->
            val type = c.getType(at)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(at)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(at).toRawBits()
                Cursor.FIELD_TYPE_STRING -> c.getString(at)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(at).toList()
                else -> error("Native type")
            })
        })
    }
    private suspend fun fullImage() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private fun preferencesOwner() = HabitPreferenceRepository(db, tokens, sessions, preferences,
        ReplicaPresentationRepository(db, tokens, sessions))
    private suspend fun parent(): CountCommandReceipt {
        tokens.getOrCreateInstallationId()
        db.withTransaction {
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
            metric = metric.copy(appearance = null)
            db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val current = CountActionReader(db, tokens, sessions).legacy(habit)
        val result = CheckInService(creatingHabits(), db.completionDao(), db.timeLogDao()).incrementCount(app, habit.id, current)
        assertTrue(result is CheckInResult.Success)
        val parent = requireNotNull((result as CheckInResult.Success).command)
        assertTrue(parent.recorded); assertEquals(1, count("completions"))
        return parent
    }
    private suspend fun save(parent: CountCommandReceipt): CountCommandReceipt {
        val owner = preferencesOwner()
        val selectedHabit = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
        val selectedMetric = requireNotNull(db.metricDao().getVisibleMetricById(metric.id))
        val claim = owner.read(selectedHabit).claim
        val result = creatingMetrics().recordLegacyPromptValuesWithReceipt(
            listOf(MetricValueDraft(metric.id, 3.0, "original explicit metric Save")),
            System.currentTimeMillis(), claim, owner, selectedHabit, listOf(selectedMetric), parent)
        assertEquals(1, result.value.size)
        return requireNotNull(result.command)
    }

    @Test(timeout = 150_000) fun oldAuthenticatedChildAbsentDirectoryCannotAuthorizeASecondMetricGroup() = runBlocking<Unit> {
        val parent = parent()
        val old = primaryImage(); val oldGeneration = generation()
        val oldAnchor = db.withTransaction { CountCommandRootAnchor.read(sql) }
        val oldRoot = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        val child = save(parent)
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_results"))
        val committedGeneration = generation(); assertTrue(committedGeneration > oldGeneration)
        val oldChild = CountCommandStore(db, tokens, sessions).resolve(child.committed.reference)
        CountCommandLegacyEvidence.verify(oldChild.historical.intent, requireNotNull(oldChild.historical.outcome))
        val currentControl = db.withTransaction { CountCommandRootAnchor.read(sql) }
        assertNotEquals(oldAnchor.hash, currentControl.hash)
        restoreOnlyPrimary(old)
        assertEquals(currentControl, db.withTransaction { CountCommandRootAnchor.read(sql) })
        assertTrue(CountCommandNativeRows.sameRoot(oldRoot, db.withTransaction { CountCommandNativeRows(sql) {}.root() }))
        // Cryptographic authenticity of the saved earlier envelope is not current continuity.
        CountCommandSeal().authenticate(oldRoot, oldRoot.storageId, oldRoot.schemaHash) { oldAnchor.hash }
        assertEquals(old, primaryImage()); assertEquals(committedGeneration, generation())
        assertEquals(1, count("metric_logs")); assertEquals(1, count("count_command_results"))
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        val damaged = fullImage()
        val failure = try { save(parent); null }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { error }
            catch (error: IllegalStateException) { error }
        assertEquals("COMMAND_PRIMARY_ANCHOR_MISMATCH", failure?.message)
        assertEquals(damaged, fullImage())
        assertEquals(1, count("metric_logs"))
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
        val beforeCold = db.withTransaction {
            val image = nativeImage { sql.query(it) }
            val temporarySequence = sql.query("SELECT rowid,* FROM temp.sqlite_sequence ORDER BY rowid").use { cells(it) }
            assertTrue("Real command journals must exercise the TEMP shadow counterexample", temporarySequence.isNotEmpty())
            assertNotEquals("The complete image must read main.sqlite_sequence despite a TEMP namesake",
                temporarySequence, image.single { it.name == "sqlite_sequence" }.rows)
            image
        }
        storage.reopen()
        val coldFailure: Throwable? = try { db.openHelper.writableDatabase; null }
            catch (error: IllegalArgumentException) { error }
            catch (error: IllegalStateException) { error }
        assertTrue(generateSequence(coldFailure) { it.cause }.any { it.message == "COMMAND_PRIMARY_ANCHOR_MISMATCH" })
        db.close()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(20, raw.version)
            val afterCold = nativeImage { raw.rawQuery(it, null) }
            val changed = (beforeCold.map { it.name } + afterCold.map { it.name }).distinct().mapNotNull { name ->
                val before = beforeCold.singleOrNull { it.name == name }
                val after = afterCold.singleOrNull { it.name == name }
                if (before == after) null else {
                    val cells = if (before == null || after == null) "missing table" else
                        (0 until maxOf(before.rows.size, after.rows.size)).flatMap { row ->
                            val a = before.rows.getOrNull(row)
                            val b = after.rows.getOrNull(row)
                            if (a == b) emptyList() else if (a == null || b == null) listOf("row[$row] missing") else
                                (0 until maxOf(a.size, b.size)).filter { a.getOrNull(it) != b.getOrNull(it) }
                                    .map { "row[$row].${before.columns.getOrNull(it)}" }
                        }.take(12).joinToString(",")
                    "$name rows=${before?.rows?.size}->${after?.rows?.size} columnsChanged=${before?.columns != after?.columns} cells=$cells"
                }
            }
            assertTrue("Cold rejection must preserve the whole damaged file image: ${changed.joinToString("; ")}",
                beforeCold == afterCold)
        }
    }

    @Test(timeout = 150_000) fun unrelatedCommittedBusinessChangeDoesNotBlockTheFirstRealMetricGroup() = runBlocking<Unit> {
        val parent = parent(); val before = generation()
        val oldControl = db.withTransaction { CountCommandRootAnchor.read(sql) }
        val oldRoot = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        db.withTransaction {
            val lastId = sql.query("SELECT last_insert_rowid()").use { assertTrue(it.moveToFirst()); it.getLong(0) }
            sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id))
            sql.query("SELECT changes(),last_insert_rowid()").use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals(lastId, it.getLong(1)); assertFalse(it.moveToNext())
            }
        }
        val freshControl = db.withTransaction { CountCommandRootAnchor.read(sql) }
        assertEquals(oldControl.generation + 1, freshControl.generation); assertEquals(oldControl.hash, freshControl.hash)
        assertTrue(CountCommandNativeRows.sameRoot(oldRoot, db.withTransaction { CountCommandNativeRows(sql) {}.root() }))
        assertTrue(generation() > before)
        storage.reopen()
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertTrue(generation() > before)
        val child = save(parent)
        assertEquals(1, count("metric_logs")); assertEquals(2, count("count_command_results"))
        val stored = CountCommandStore(db, tokens, sessions).resolve(child.committed.reference)
        CountCommandLegacyEvidence.verify(stored.historical.intent, requireNotNull(stored.historical.outcome))
        assertEquals(child.commandId, CountCommandStore(db, tokens, sessions).resolve(parent.committed.reference).historical.status.child)
        val beforeReopen = fullImage(); storage.reopen()
        assertEquals(beforeReopen, fullImage())
        assertEquals(1, count("metric_logs"))
    }
}
