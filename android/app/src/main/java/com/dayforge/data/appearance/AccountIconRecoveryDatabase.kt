package com.dayforge.data.appearance

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.repository.ReplicaSqlSchema
import com.dayforge.data.repository.syncPayloadHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Exact owned rows and verified metadata/receipts, not new-scope readiness or a durable archive. */
internal class IconRecoveryDatabaseProof internal constructor(
    val original: LocalIconAccess, val contents: JsonObject, val hash: String,
    val metadata: AccountIconRecoveryMetadata, val remote: AccountIconRemoteRecovery
) {
    val hasUnresolvedTransfers: Boolean get() = remote.transfers.any { it.state != "complete" }
    val activationAuthorized: Boolean get() = false
}

/**
 * Read the eight actual old-namespace tables, including unused packs/retained transfer history.
 * Compare the whole physical schema with this build's private Room5 reference, but NEVER export
 * another account's rows or copy the whole database file. All source access is SELECT/PRAGMA only;
 * ANALYZE runs solely in the owned in-memory reference. File and business closures are separate.
 */
internal class AccountIconRecoveryDatabase(
    private val context: Context, private val database: AccountIconDatabase,
    private val metadata: AccountIconRepository, private val transfers: AccountIconTransfers
) {
    init { require(metadata.usesDatabase(database) && transfers.usesMetadata(metadata)) { "ICON_RECOVERY_STORE_MISMATCH" } }
    private val remote = AccountIconRemoteCatalog(database, metadata, transfers)

    suspend fun read(original: AccountIconContext): IconRecoveryDatabaseProof = withContext(Dispatchers.IO) {
        metadata.reauthorize(original)
        // Only inspect the live, already initialized provider. Opening a lazy/closed Room handle
        // here could create or migrate the source before we get a chance to reject its schema.
        // The enclosing recovery workflow must establish its normal storage baseline first.
        check(database.isOpen) { "ICON_RECOVERY_DATABASE_NOT_OPEN" }
        val reference = Room.inMemoryDatabaseBuilder(context.applicationContext, AccountIconDatabase::class.java).build()
        var failure: Throwable? = null
        try {
            val expected = reference.withTransaction {
                val sql = reference.openHelper.writableDatabase
                sql.execSQL("ANALYZE")
                ReplicaSqlSchema.read(sql)
            }
            val proof = metadata.recoveryTransaction(original) { catalog, transferMetadata ->
                check(database.inTransaction())
                val sql = database.openHelper.writableDatabase
                val actual = ReplicaSqlSchema.read(sql)
                val optional = setOf("table.sqlite_stat1", "table.sqlite_stat4", "table.android_metadata")
                require(actual.keys - optional == expected.keys - optional &&
                    actual.all { (key, value) -> expected[key] == value }) { "ICON_RECOVERY_SCHEMA_INVALID" }
                require(sql.version == reference.openHelper.writableDatabase.version) { "ICON_RECOVERY_SCHEMA_INVALID" }
                val expectedIdentity = reference.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                    require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING); it.getString(0)
                }
                sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {
                    require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER && it.getLong(0) == 42L &&
                        it.getType(1) == Cursor.FIELD_TYPE_STRING && it.getString(1) == expectedIdentity && !it.moveToNext()) {
                        "ICON_RECOVERY_SCHEMA_INVALID"
                    }
                }
                for (table in actual.values.filter { it.type == "table" }) {
                    currentCoroutineContext().ensureActive()
                    ReplicaSqlSchema.verifyTypes(sql, table.name)
                }
                sql.query("PRAGMA integrity_check").use {
                    require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_STRING && it.getString(0) == "ok" && !it.moveToNext()) {
                        "ICON_RECOVERY_STORAGE_INVALID"
                    }
                }
                sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "ICON_RECOVERY_FOREIGN_KEY_INVALID" } }
                val receipts = remote.recoveryInTransaction(original, transferMetadata)
                val tables = actual.values.filter { it.type == "table" && it.name.startsWith("icon_") }.map { it.name }.sorted()
                require(tables.toSet() == TABLE_LIMITS.keys) { "ICON_RECOVERY_SCHEMA_INVALID" }
                val raw = contents(sql, original.namespace, tables)
                val rows = raw.getValue("tables").jsonObject
                fun size(table: String) = rows.getValue(table).jsonArray.size
                require(size("icon_assets") == catalog.assets.size && size("icon_packs") == catalog.packs.size &&
                    size("icon_blob_reservations") == catalog.installations.size &&
                    size("icon_blob_ready") == catalog.installations.count { it.validationProfile != null } &&
                    size("icon_transfers") == receipts.transfers.size && size("icon_catalog_entries") == receipts.entries.size &&
                    size("icon_catalog_state") == (if (receipts.state == null) 0 else 1)) { "ICON_RECOVERY_ROWS_INVALID" }
                // The raw selection distinguishes an absent default from a persisted no-pack intent.
                require(size("icon_pack_selection") == (if (catalog.selection.generation == 0L) 0 else 1)) { "ICON_RECOVERY_ROWS_INVALID" }
                IconRecoveryDatabaseProof(original.access, raw, syncPayloadHash(raw.toString()), catalog, receipts)
            }
            metadata.reauthorize(original)
            proof
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { reference.close() } catch (close: Throwable) {
                if (failure == null) throw close else failure.addSuppressed(close)
            }
        }
    }

    /** Same canonical scope/rows/hash, including same-count changes. Never repairs a stale proof. */
    suspend fun revalidate(proof: IconRecoveryDatabaseProof) {
        val current = read(AccountIconContext(proof.original))
        check(current.hash == proof.hash && current.metadata == proof.metadata && current.remote == proof.remote) {
            "ICON_RECOVERY_PREVIEW_CHANGED"
        }
    }

    private suspend fun contents(sql: SupportSQLiteDatabase, namespace: AccountIconNamespace,
        tables: List<String>): JsonObject {
        val coroutine = currentCoroutineContext()
        var retained = 0L
        fun row(cursor: Cursor): JsonObject = buildJsonObject {
            for (column in 0 until cursor.columnCount) {
                coroutine.ensureActive()
                put(cursor.getColumnName(column), buildJsonObject {
                    val type = cursor.getType(column)
                    put("sqlite_type", type)
                    put("value", when (type) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
                        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(column))
                        // Owned icon columns have no REAL or BLOB affinity. Do not coerce them.
                        else -> error("ICON_RECOVERY_SQL_TYPE_INVALID")
                    })
                })
            }
        }.also {
            retained += it.toString().toByteArray(Charsets.UTF_8).size
            require(retained <= PREVIEW_LIMIT) { "ICON_RECOVERY_PREVIEW_TOO_LARGE" }
        }
        val schema = sql.query("SELECT type,name,tbl_name,rootpage,sql FROM sqlite_master ORDER BY type,name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
        val scope = "accountId=? AND serverInstanceId=? AND syncEpoch=?"
        val scopeArgs = arrayOf<Any>(namespace.accountId, namespace.serverInstanceId, namespace.syncEpoch)
        val contents = buildJsonObject {
            put("user_version", sql.version)
            put("room_identity", sql.query("SELECT id,identity_hash FROM room_master_table").use { cursor ->
                require(cursor.moveToFirst()); row(cursor)
            })
            put("schema", JsonArray(schema))
            put("namespace", buildJsonObject {
                put("account_id", namespace.accountId); put("server_instance_id", namespace.serverInstanceId); put("sync_epoch", namespace.syncEpoch)
            })
            put("tables", buildJsonObject {
                for (table in tables) {
                    coroutine.ensureActive()
                    val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor -> buildList {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    } }
                    require(columns.isNotEmpty() && "__recovery_rowid" !in columns)
                    val length = columns.joinToString("+") { "COALESCE(length(CAST(`$it` AS BLOB)),0)" }
                    var last: Long? = null
                    val rows = buildList {
                        while (true) {
                            coroutine.ensureActive()
                            val boundary = last
                            val after = if (boundary == null) "" else " AND rowid>?"
                            val args = if (boundary == null) scopeArgs else scopeArgs + boundary
                            val ids = sql.query("SELECT rowid,($length) FROM `$table` WHERE $scope$after ORDER BY rowid LIMIT 128", args).use { cursor ->
                                buildList {
                                    var bytes = 0L
                                    while (cursor.moveToNext()) {
                                        coroutine.ensureActive()
                                        require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                                            (lastOrNull() ?: boundary)?.let { cursor.getLong(0) > it } != false &&
                                            cursor.getType(1) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(1) in 0..ROW_LIMIT) {
                                            "ICON_RECOVERY_ROWS_INVALID"
                                        }
                                        val size = cursor.getLong(1)
                                        if (isNotEmpty() && bytes + size > ROW_LIMIT) break
                                        bytes += size; add(cursor.getLong(0))
                                    }
                                }
                            }
                            if (ids.isEmpty()) break
                            require(size + ids.size <= TABLE_LIMITS.getValue(table)) { "ICON_RECOVERY_ROWS_INVALID" }
                            sql.query("SELECT rowid AS __recovery_rowid,* FROM `$table` WHERE $scope$after AND rowid<=? ORDER BY rowid", args + ids.last()).use { cursor ->
                                require(cursor.columnCount == columns.size + 1)
                                var count = 0
                                while (cursor.moveToNext()) {
                                    require(count < ids.size && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == ids[count])
                                    add(row(cursor)); count++
                                }
                                require(count == ids.size)
                            }
                            last = ids.last()
                        }
                    }
                    put(table, JsonArray(rows))
                }
            })
        }
        coroutine.ensureActive()
        require(contents.toString().toByteArray(Charsets.UTF_8).size <= PREVIEW_LIMIT) { "ICON_RECOVERY_PREVIEW_TOO_LARGE" }
        return contents
    }

    companion object {
        private const val ROW_LIMIT = 1_048_576L + 4096
        private const val PREVIEW_LIMIT = 32 * 1024 * 1024L
        private val TABLE_LIMITS = mapOf("icon_assets" to 1000, "icon_packs" to 32768,
            "icon_blob_reservations" to 2000, "icon_blob_ready" to 2000, "icon_pack_selection" to 1,
            "icon_transfers" to AccountIconTransfers.MAX_JOBS, "icon_catalog_entries" to 33768, "icon_catalog_state" to 1)
    }
}
