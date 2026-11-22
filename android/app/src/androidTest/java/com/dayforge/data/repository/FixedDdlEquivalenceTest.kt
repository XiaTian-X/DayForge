package com.dayforge.data.repository

import android.database.Cursor
import android.database.MatrixCursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal object FixedDdlTestInputs {
    val countDeclarations get() = CountTrustSchema.ownedDefinitions().mapValues { it.value.second }
    val v18Declarations get() = countDeclarations + NextReplicaFenceSql.localTrustDefinitions()
    fun stored(sql: String): String {
        val prefix = "CREATE TRIGGER IF NOT EXISTS "
        return if (sql.startsWith(prefix)) "CREATE TRIGGER " + sql.removePrefix(prefix) else sql
    }
    fun countRows(sql: SupportSQLiteDatabase): List<List<Any?>> {
        val names = countDeclarations.keys
        return sql.query("SELECT type,name,sql FROM main.sqlite_master ORDER BY type,name").use { c -> buildList {
            while (c.moveToNext()) if (c.getString(1) in names || c.getString(1).startsWith(CountTrustSchema.DIRTY_PREFIX))
                add((0..2).map { c.getString(it) })
        } }
    }
    /** Substitute only captured schema rows for the pure checker matrix; actual control SQL
     * still runs. Native corruption/opening is independently covered by the migration class.
     */
    fun rows(sql: SupportSQLiteDatabase, selected: List<List<Any?>>): SupportSQLiteDatabase =
        object : SupportSQLiteDatabase by sql {
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                if (!query.startsWith("SELECT type,CASE WHEN length(CAST(name AS BLOB))<=256")) return sql.query(query, bindArgs)
                return MatrixCursor(arrayOf("type", "name", "sql")).apply { selected.forEach { addRow(it.toTypedArray()) } }
            }
        }
    fun cell(type: Int, value: JsonElement) = buildJsonObject { put("sqlite_type", type); put("value", value) }
    fun schema(proof: ReplicaStorageProof, transform: (List<JsonObject>) -> List<JsonObject>): ReplicaStorageProof {
        val next = JsonObject(proof.contents + ("schema" to JsonArray(transform(proof.contents.getValue("schema").jsonArray.map { it.jsonObject }))))
        return ReplicaStorageProof(next, syncPayloadHash(next.toString()))
    }
    fun name(row: JsonObject) = row.getValue("name").jsonObject.getValue("value").jsonPrimitive.content
    fun actualSql(row: JsonObject) = row.getValue("sql").jsonObject.getValue("value").jsonPrimitive.content
}

/** Frozen old normalizers are independent of the new fixed matcher. No cache reset or input
 * registry is installed; public validators still receive complete actual/captured inputs.
 */
@RunWith(AndroidJUnit4::class)
class FixedDdlEquivalenceTest : NextCoreRequestFixture() {
    private fun countAgreement(sql: SupportSQLiteDatabase, rows: List<List<Any?>>, accepts: Boolean) {
        val source = FixedDdlTestInputs.rows(sql, rows)
        val old = runCatching { FixedDdlCountOriginal.validate(source) }.exceptionOrNull()
        val candidate = runCatching { CountTrustSchema.validate(source) }.exceptionOrNull()
        assertEquals("Original acceptance", accepts, old == null)
        assertEquals("Candidate acceptance", accepts, candidate == null)
        if (!accepts) assertEquals(old?.message, candidate?.message)
    }
    private fun viewAgreement(proof: ReplicaStorageProof, accepts: Boolean) {
        val old = runCatching { FixedDdlV18Original.projectContents(proof) }
        val candidate = runCatching { NextReplicaV18StorageView.projectContents(proof) }
        assertEquals(accepts, old.isSuccess); assertEquals(accepts, candidate.isSuccess)
        if (accepts) {
            assertEquals(old.getOrThrow(), candidate.getOrThrow())
            assertEquals(FixedDdlV18Original.project(proof)?.hash, NextReplicaV18StorageView.project(proof)?.hash)
        } else assertEquals(old.exceptionOrNull()?.message, candidate.exceptionOrNull()?.message)
    }

    @Test fun actualCurrentSqliteInputsAgreeAndExposeOnlyAggregateExactHitCounts() = runBlocking<Unit> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            FixedDdlCountOriginal.validate(sql); CountTrustSchema.validate(sql)
            val rows = FixedDdlTestInputs.countRows(sql); assertEquals(116, rows.size)
            val countHits = rows.count { it[2] == FixedDdlTestInputs.countDeclarations.getValue(it[1] as String) }
            val proof = NextReplicaStorageProof(db).readInTransaction(); viewAgreement(proof, true)
            val declarations = FixedDdlTestInputs.v18Declarations
            val actual = proof.contents.getValue("schema").jsonArray.map { it.jsonObject }.filter { FixedDdlTestInputs.name(it) in declarations }
            assertEquals(131, actual.size)
            val viewHits = actual.count {
                val expected = declarations.getValue(FixedDdlTestInputs.name(it)); val value = FixedDdlTestInputs.actualSql(it)
                value == expected || value == FixedDdlTestInputs.stored(expected)
            }
            assertTrue("The real SQLite fixture must exercise the exact branch", countHits > 0 && viewHits > 0)
            android.util.Log.i("FixedDdlInputs", "count_objects=${rows.size} count_exact=$countHits count_fallback=${rows.size-countHits} " +
                "v18_objects=${actual.size} v18_exact=$viewHits v18_fallback=${actual.size-viewHits}")
        }
    }

    @Test fun acceptedFormattingAndBothFixedTriggerSpellingsKeepOldResults() = runBlocking<Unit> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; val rows = FixedDdlTestInputs.countRows(sql)
            val proof = NextReplicaStorageProof(db).readInTransaction()
            val selected = listOf("count_trust_control", "index_count_trust_routes_entityUuid", "count_trust_dirty_completions_insert")
            for (name in selected) {
                val original = rows.single { it[1] == name }[2] as String
                val formatted = original.replaceFirst("CREATE ", "CREATE  ")
                assertNotEquals(FixedDdlTestInputs.countDeclarations.getValue(name), formatted)
                countAgreement(sql, rows.map { if (it[1] == name) listOf(it[0], it[1], formatted) else it }, true)
                viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.map {
                    if (FixedDdlTestInputs.name(it) == name) JsonObject(it + ("sql" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(formatted)))) else it
                } }, true)
            }
            val name = "replica_fence_count_trust_nodes_insert"
            val declared = FixedDdlTestInputs.v18Declarations.getValue(name)
            assertNotEquals(declared, FixedDdlTestInputs.stored(declared))
            for (value in listOf(declared, FixedDdlTestInputs.stored(declared))) viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.map {
                if (FixedDdlTestInputs.name(it) == name) JsonObject(it + ("sql" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(value)))) else it
            } }, true)
        }
    }

    @Test fun changedTextAndMembershipKeepOldRefusalsAndCannotPoisonLaterExactMatch() = runBlocking<Unit> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; val rows = FixedDdlTestInputs.countRows(sql)
            val proof = NextReplicaStorageProof(db).readInTransaction()
            val name = "count_trust_dirty_completions_insert"; val original = rows.single { it[1] == name }[2] as String
            for (value in listOf(original.replace("COUNT_TRUST_CONTROL_INVALID", "count_trust_control_invalid"),
                original.replace("generation<", "generation<="), original + " SELECT 1")) {
                assertNotEquals(original, value)
                countAgreement(sql, rows.map { if (it[1] == name) listOf(it[0], it[1], value) else it }, false)
                viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.map {
                    if (FixedDdlTestInputs.name(it) == name) JsonObject(it + ("sql" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(value)))) else it
                } }, false)
                countAgreement(sql, rows, true); viewAgreement(proof, true)
            }
            val table = "count_trust_root"
            val tableSql = rows.single { it[1] == table }[2] as String
            val missingConstraint = tableSql.replaceFirst(" NOT NULL", "")
            assertNotEquals(tableSql, missingConstraint)
            countAgreement(sql, rows.map { if (it[1] == table) listOf(it[0], it[1], missingConstraint) else it }, false)
            viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.map {
                if (FixedDdlTestInputs.name(it) == table) JsonObject(it + ("sql" to
                    FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(missingConstraint)))) else it
            } }, false)
            countAgreement(sql, rows.drop(1), false)
            countAgreement(sql, rows + listOf(rows.first()), false)
            countAgreement(sql, rows + listOf(listOf("trigger", "count_trust_dirty_unexpected_insert", original)), false)
            countAgreement(sql, rows.map { if (it[1] == name) listOf("view", it[1], it[2]) else it }, false)
            countAgreement(sql, rows.map { if (it[1] == name) listOf(it[0], it[1], 7) else it }, false)
            viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.filterNot { FixedDdlTestInputs.name(it) == name } }, false)
            viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list + list.single { FixedDdlTestInputs.name(it) == name } }, false)
            viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list + JsonObject(
                list.single { FixedDdlTestInputs.name(it) == name } + ("name" to
                    FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive("count_trust_dirty_unexpected_insert")))) }, false)
        }
    }

    @Test fun exactSqlDoesNotBypassOwnerNativeTypeRootpageOrRoomIdentity() = runBlocking<Unit> {
        val proof = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        val name = "count_trust_dirty_completions_insert"
        for ((column, value) in listOf(
            "tbl_name" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive("metrics")),
            "type" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive("view")),
            "rootpage" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_INTEGER, JsonPrimitive(7)),
            "sql" to FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_INTEGER, JsonPrimitive(7)))) {
            viewAgreement(FixedDdlTestInputs.schema(proof) { list -> list.map {
                if (FixedDdlTestInputs.name(it) == name) JsonObject(it + (column to value)) else it
            } }, false)
        }
        val tables = proof.contents.getValue("tables").jsonObject
        val master = tables.getValue("room_master_table").jsonArray.single().jsonObject
        val changedMaster = JsonObject(master + ("identity_hash" to
            FixedDdlTestInputs.cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive("wrong"))))
        val changedTables = JsonObject(tables + ("room_master_table" to JsonArray(listOf(changedMaster))))
        val changed = JsonObject(proof.contents + ("tables" to changedTables))
        viewAgreement(ReplicaStorageProof(changed, syncPayloadHash(changed.toString())), false)
    }

    @Test fun actualControlDamageStillRejectsWhenEveryFixedDdlRemainsUnchanged() = runBlocking<Unit> {
        val before = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
        val reached = booleanArrayOf(false)
        val rollback = runCatching { db.withTransaction { CountTrustRuntime.maintenance(db) {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE count_trust_control SET dirty=2 WHERE id=1")
            sql.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
                assertTrue(it.moveToFirst()); assertEquals(Cursor.FIELD_TYPE_INTEGER, it.getType(0)); assertEquals(2, it.getInt(0))
            }
            reached[0] = true
            assertEquals("COUNT_TRUST_CONTROL_INVALID", runCatching { FixedDdlCountOriginal.validate(sql) }.exceptionOrNull()?.message)
            assertEquals("COUNT_TRUST_CONTROL_INVALID", runCatching { CountTrustSchema.validate(sql) }.exceptionOrNull()?.message)
            error("fixed-ddl-control-rollback")
        } } }.exceptionOrNull()
        assertTrue(reached[0]); assertEquals("fixed-ddl-control-rollback", rollback?.message)
        assertEquals(before.contents, db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }.contents)
        db.withTransaction { CountTrustSchema.validate(db.openHelper.writableDatabase) }
    }
}
