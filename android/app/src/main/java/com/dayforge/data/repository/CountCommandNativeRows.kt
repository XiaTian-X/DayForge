package com.dayforge.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.entity.CountCommandRecoveryRootEntity
import com.dayforge.data.local.entity.CountCommandResultEntity
import com.dayforge.data.repository.CountCommandRowPlan.Cell
import com.dayforge.data.repository.CountCommandRowPlan.Row
import com.dayforge.data.repository.CountCommandRowPlan.Table

/** Fixed-shape scalar preflight precedes every full native row. Size allowances for enrolled
 * records come from their authenticated catalog, not untrusted JSON or global history count.
 * SQLite UTF16 can use twice the canonical UTF8 bytes; no UTF8 storage assumption is made.
 */
internal class CountCommandNativeRows(private val sql: SupportSQLiteDatabase, private val lifetime: () -> Unit) {
    fun requireAbsent(table: Table, id: String) {
        lifetime()
        sql.query("SELECT 1 FROM `${table.sqlName}` WHERE commandId=? LIMIT 1", arrayOf(id)).use {
            require(!it.moveToFirst()) { "COMMAND_UNBOUND_NATIVE_ROW" }
        }
    }

    fun read(table: Table, id: String, trustedBytes: Long): Row {
        lifetime(); require(trustedBytes in 1..Int.MAX_VALUE.toLong())
        val types = table.columns.joinToString(",") { "typeof(`$it`),length(CAST(`$it` AS BLOB))" }
        val allowedNull = when (table) { Table.INTENT -> emptySet(); Table.STATUS -> setOf(5, 6, 7, 8); Table.OUTCOME -> setOf(3) }
        val integer = when (table) { Table.INTENT -> setOf(3); Table.STATUS -> setOf(2, 10); Table.OUTCOME -> emptySet() }
        sql.query("SELECT $types FROM `${table.sqlName}` WHERE commandId=? LIMIT 2", arrayOf(id)).use { c ->
            require(c.moveToFirst()) { "COMMAND_NATIVE_MEMBER_MISSING" }
            var total = 0L
            for (i in table.columns.indices) {
                require(c.getType(i * 2) == Cursor.FIELD_TYPE_STRING)
                val kind = c.getString(i * 2)
                require(kind == (if (i in integer) "integer" else "text") || kind == "null" && i in allowedNull)
                if (kind != "null") {
                    require(c.getType(i * 2 + 1) == Cursor.FIELD_TYPE_INTEGER)
                    total = Math.addExact(total, c.getLong(i * 2 + 1).also { require(it >= 0) })
                }
            }
            require(total <= Math.addExact(Math.multiplyExact(trustedBytes, 2L), 1024L) && !c.moveToNext()) {
                "COMMAND_NATIVE_SIZE_CHANGED"
            }
        }
        return sql.query("SELECT ${table.columns.joinToString(",") { "`$it`" }} FROM `${table.sqlName}` WHERE commandId=? LIMIT 2", arrayOf(id)).use { c ->
            require(c.moveToFirst())
            val cells = table.columns.indices.map { i -> when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> Cell.Null
                Cursor.FIELD_TYPE_STRING -> Cell.Text(c.getString(i))
                Cursor.FIELD_TYPE_INTEGER -> Cell.Integer(c.getLong(i))
                else -> error("COMMAND_NATIVE_TYPE_CHANGED")
            } }
            require(!c.moveToNext()); Row(table, cells).also { require(it.commandId == id && it.utf8Bytes == trustedBytes) }
        }
    }

    fun aggregate(member: CountCommandRecords.CatalogMember, poll: () -> Unit): CountCommandRowPlan.Aggregate =
        aggregate(member, poll, projectStatus = false)

    /** Closed T2 input path: all original raw/type/size/absence/codec/member checks still run.
     * Only the returned immutable values retain projections for the later Status-only plan. */
    internal fun aggregateForMetricDelivery(member: CountCommandRecords.CatalogMember,
        poll: () -> Unit): CountCommandRowPlan.Aggregate = aggregate(member, poll, projectStatus = true)

    private fun aggregate(member: CountCommandRecords.CatalogMember, poll: () -> Unit,
        projectStatus: Boolean): CountCommandRowPlan.Aggregate {
        val intentRow = read(Table.INTENT, member.commandId, member.intentBytes)
        val statusRow = read(Table.STATUS, member.commandId, member.statusBytes)
        require(intentRow.hash == member.intentRowHash && statusRow.hash == member.statusRowHash)
        val intent = CountCommandRecords.Intent.decode(text(intentRow, 5).toByteArray(Charsets.UTF_8), member.intentBytes.toInt(), poll)
        val status = CountCommandRecords.Status.decode(text(statusRow, 9).toByteArray(Charsets.UTF_8), member.statusBytes.toInt(), poll)
        require(CountCommandRowPlan.intent(intent) == intentRow && CountCommandRowPlan.status(status) == statusRow)
        val outcome = if (member.outcomeRowHash == null) {
            requireAbsent(Table.OUTCOME, member.commandId); null
        } else {
            val row = read(Table.OUTCOME, member.commandId, requireNotNull(member.outcomeBytes))
            require(row.hash == member.outcomeRowHash)
            CountCommandOutcome.fromRow(CountCommandResultEntity(text(row, 0), text(row, 1), text(row, 2),
                nullableText(row, 3), text(row, 4), text(row, 5)), requireNotNull(member.outcomeBytes).toInt(), poll)
        }
        val aggregate = if (projectStatus) CountCommandRowPlan.Aggregate.forStatusChanges(intent, status, outcome)
            else CountCommandRowPlan.Aggregate(intent, status, outcome)
        return aggregate.also { require(it.member() == member) }
    }

    fun apply(mutation: CountCommandRowPlan.Mutation) {
        lifetime()
        val before = mutation.before; val row = mutation.after
        if (before == null) requireAbsent(row.table, row.commandId)
        else require(read(before.table, before.commandId, before.utf8Bytes) == before) { "COMMAND_NATIVE_CAS_CHANGED" }
        val args = row.cells.map(::binding).toTypedArray()
        if (before == null) sql.execSQL("INSERT INTO `${row.table.sqlName}` (${row.table.columns.joinToString(",") { "`$it`" }}) " +
            "VALUES(${row.cells.joinToString(",") { "?" }})", args)
        else sql.execSQL("UPDATE `${row.table.sqlName}` SET ${row.table.columns.drop(1).joinToString(",") { "`$it`=?" }} WHERE commandId=?",
            (args.drop(1) + row.commandId).toTypedArray())
        require(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext() })
        require(read(row.table, row.commandId, row.utf8Bytes) == row) { "COMMAND_NATIVE_WRITE_CHANGED" }
    }

    fun root(): CountCommandRecoveryRootEntity {
        lifetime()
        sql.query("SELECT ${ROOT_COLUMNS.joinToString(",") { "typeof(`$it`),length(CAST(`$it` AS BLOB))" }} " +
            "FROM count_command_recovery_roots LIMIT 2").use { c ->
            require(c.moveToFirst()) { "COMMAND_PRIMARY_ROOT_MISSING" }
            for (i in ROOT_COLUMNS.indices) {
                require(c.getType(i * 2) == Cursor.FIELD_TYPE_STRING)
                val kind = c.getString(i * 2)
                require(kind == when { ROOT_COLUMNS[i] == "mac" -> "blob"; ROOT_COLUMNS[i] in ROOT_INTEGERS -> "integer"; else -> "text" } ||
                    kind == "null" && ROOT_COLUMNS[i] in ROOT_NULLABLE) { "COMMAND_PRIMARY_ROOT_TYPE" }
                if (kind != "null") require(c.getType(i * 2 + 1) == Cursor.FIELD_TYPE_INTEGER &&
                    c.getLong(i * 2 + 1) in 0..1024 && (ROOT_COLUMNS[i] != "mac" || c.getLong(i * 2 + 1) == 32L)) { "COMMAND_PRIMARY_ROOT_SIZE" }
            }
            require(!c.moveToNext()) { "COMMAND_PRIMARY_ROOT_DUPLICATE" }
        }
        return sql.query("SELECT ${ROOT_COLUMNS.joinToString(",")} FROM count_command_recovery_roots LIMIT 2").use { c ->
            require(c.moveToFirst())
            fun text(i: Int) = if (c.isNull(i)) null else c.getString(i)
            fun number(i: Int) = if (c.isNull(i)) null else c.getLong(i)
            require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER && c.getLong(0) == 1L && c.getType(ROOT_COLUMNS.indexOf("format")) == Cursor.FIELD_TYPE_INTEGER && c.getLong(ROOT_COLUMNS.indexOf("format")) in 1L..CountCommandRootCodec.RETAINED_FORMAT.toLong())
            fun at(name: String) = ROOT_COLUMNS.indexOf(name).also { require(it >= 0) }
            fun t(name: String) = text(at(name))
            fun n(name: String) = number(at(name))
            val row = CountCommandRecoveryRootEntity(1, requireNotNull(t("mode")), requireNotNull(t("storageId")), requireNotNull(t("datasetId")), requireNotNull(t("initializationSource")),
                t("namespaceHash"), t("namespaceJson"), t("accountBindingHash"), t("registrationPreviousHash"), t("registrationInstallationId"),
                Math.toIntExact(requireNotNull(n("format"))), requireNotNull(t("validator")), requireNotNull(t("schemaHash")),
                t("catalogHash"), n("catalogHeight")?.let(Math::toIntExact), n("catalogCount"), t("pendingHeadId"), t("pendingTailId"),
                requireNotNull(n("pendingCount")), n("legacyCount"), t("legacyHash"), requireNotNull(n("revision")), c.getBlob(at("mac")))
            require(!c.moveToNext()); CountCommandRootCodec.fromEntity(row); row
        }
    }

    fun replaceRoot(old: CountCommandRecoveryRootEntity, fresh: CountCommandRecoveryRootEntity) {
        require(sameRoot(root(), old)) { "COMMAND_PRIMARY_ROOT_CAS_CHANGED" }
        val values = rootBindings(fresh)
        sql.execSQL("UPDATE count_command_recovery_roots SET ${ROOT_COLUMNS.drop(1).joinToString(",") { "`$it`=?" }} WHERE id=1", values.drop(1).toTypedArray())
        require(sql.query("SELECT changes()").use { it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext() })
        require(sameRoot(root(), fresh)) { "COMMAND_PRIMARY_ROOT_WRITE_CHANGED" }
    }

    companion object {
        val ROOT_COLUMNS = listOf("id", "mode", "storageId", "datasetId", "initializationSource", "namespaceHash", "namespaceJson", "accountBindingHash",
            "registrationPreviousHash", "registrationInstallationId", "format", "validator", "schemaHash", "catalogHash", "catalogHeight", "catalogCount",
            "pendingHeadId", "pendingTailId", "pendingCount", "legacyCount", "legacyHash", "revision", "mac")
        private val ROOT_INTEGERS = setOf("id", "format", "catalogHeight", "catalogCount", "pendingCount", "legacyCount", "revision")
        private val ROOT_NULLABLE = setOf("namespaceHash", "namespaceJson", "accountBindingHash", "registrationPreviousHash", "registrationInstallationId",
            "catalogHash", "catalogHeight", "catalogCount", "pendingHeadId", "pendingTailId", "legacyCount", "legacyHash")
        fun sameRoot(a: CountCommandRecoveryRootEntity, b: CountCommandRecoveryRootEntity): Boolean =
            rootBindings(a).dropLast(1) == rootBindings(b).dropLast(1) && a.mac.contentEquals(b.mac)
        fun rootBindings(r: CountCommandRecoveryRootEntity): List<Any?> = listOf(r.id, r.mode, r.storageId, r.datasetId, r.initializationSource,
            r.namespaceHash, r.namespaceJson, r.accountBindingHash, r.registrationPreviousHash, r.registrationInstallationId,
            r.format, r.validator, r.schemaHash, r.catalogHash, r.catalogHeight, r.catalogCount,
            r.pendingHeadId, r.pendingTailId, r.pendingCount, r.legacyCount, r.legacyHash, r.revision, r.mac.copyOf())
        private fun text(row: Row, i: Int) = (row.cells[i] as Cell.Text).value
        private fun nullableText(row: Row, i: Int) = if (row.cells[i] == Cell.Null) null else text(row, i)
        private fun binding(cell: Cell): Any? = when (cell) { Cell.Null -> null; is Cell.Text -> cell.value; is Cell.Integer -> cell.value }
    }
}
