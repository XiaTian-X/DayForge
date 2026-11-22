package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.*
import org.junit.Assert.*

internal data class ConsumerNativeCell(val type: Int, val value: Any?)
internal typealias ConsumerNativeImage = Map<String, List<Map<String, ConsumerNativeCell>>>

/** Test-only evidence for a successful first display read. It grants no production capability.
 * Rejection/cancellation/publication cases keep their complete zero-write assertions.
 */
internal class CountConsumerReadFixtureAssertions(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {

    suspend fun capture(): ConsumerNativeImage = database.withTransaction {
        val sql = database.openHelper.readableDatabase
        val tables = sql.query("SELECT name FROM main.sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) {
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
                add(cursor.getString(0).also { require(it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) })
            } }
        }
        tables.associateWith { table -> sql.query("SELECT * FROM main.`$table` ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(buildMap {
                for (column in 0 until cursor.columnCount) {
                    val type = cursor.getType(column)
                    val value: Any? = when (type) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> error("Unexpected SQLite native type: $type")
                    }
                    put(cursor.getColumnName(column), ConsumerNativeCell(type, value))
                }
            }) }
        } }
    }

    suspend fun assertUnchanged(expected: ConsumerNativeImage) {
        assertEquals(expected, capture())
    }

    /** A genuine original-path fallback is still entirely read-only. Any actual issuance must
     * start from NO root and NO retained nodes, clear only dirty, retain generation/storage,
     * authenticate the full issued directory and have no orphan additions. No generic old-root
     * replacement allowance is provided; that needs a separate, explicitly invalidated fixture.
     */
    suspend fun assertFirstReadOnly(before: ConsumerNativeImage): ConsumerNativeImage {
        val issued = capture()
        if (issued == before) return issued
        assertTrue(before.getValue("count_trust_root").isEmpty())
        assertTrue(before.getValue("count_trust_nodes").isEmpty())
        val oldControl = before.getValue("count_trust_control").single()
        assertEquals(setOf("id", "storageId", "generation", "dirty", "commandRootHash"), oldControl.keys)
        assertEquals(Cursor.FIELD_TYPE_STRING, oldControl.getValue("commandRootHash").type)
        assertTrue((oldControl.getValue("commandRootHash").value as String).matches(Regex("[0-9a-f]{64}")))
        assertEquals(ConsumerNativeCell(Cursor.FIELD_TYPE_INTEGER, 1L), oldControl.getValue("id"))
        assertEquals(Cursor.FIELD_TYPE_STRING, oldControl.getValue("storageId").type)
        val storageId = oldControl.getValue("storageId").value as String
        assertTrue(com.dayforge.domain.model.isContractUuid(storageId))
        assertEquals(Cursor.FIELD_TYPE_INTEGER, oldControl.getValue("generation").type)
        val generation = oldControl.getValue("generation").value as Long
        assertTrue(generation >= 0L)
        assertEquals(ConsumerNativeCell(Cursor.FIELD_TYPE_INTEGER, 1L), oldControl.getValue("dirty"))
        val cleared = oldControl + ("dirty" to ConsumerNativeCell(Cursor.FIELD_TYPE_INTEGER, 0L))
        assertEquals(listOf(cleared), issued.getValue("count_trust_control"))
        assertEquals(before + mapOf(
            "count_trust_control" to listOf(cleared),
            "count_trust_root" to issued.getValue("count_trust_root"),
            "count_trust_nodes" to issued.getValue("count_trust_nodes")), issued)
        sessions.exclusive { database.withTransaction {
            val authority = requireNotNull(tokens.localCoreWriteAccess())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
            owner.maintenance(database) {
                val sql = database.openHelper.writableDatabase
                CountTrustSchema.validate(sql)
                val primary = CountCommandStorageInitialization.verifyRootAnchor(sql)
                assertEquals(ConsumerNativeCell(Cursor.FIELD_TYPE_STRING, CountCommandRootAnchor.digest(primary.native())),
                    issued.getValue("count_trust_control").single().getValue("commandRootHash"))
                val header = requireNotNull(CountTrustSeal.read(sql))
                assertEquals(CountTrustSeal.VALIDATOR, header.validator)
                assertEquals(1L, header.revision)
                assertEquals(generation, header.generation)
                val binding = CountTrustSeal.binding(authority, storageId)
                val schemaHash = CountTrustIndex.hash(JsonArray(owner.schemaSnapshot().map { row ->
                    JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull })
                }).toString().toByteArray(Charsets.UTF_8))
                assertTrue("Issued root must authenticate actual scope/schema",
                    CountTrustSeal().matches(header, binding, schemaHash))
                val index = CountTrustIndex(sql, binding, header.root) {}
                index.verifyContents()
                assertEquals(requireNotNull(CountTrustValidationEnvironment.current()),
                    CountTrustValidationEnvironment.Snapshot.decode(requireNotNull(index.get(CountTrustValidationEnvironment.KEY))))
                val reachable = linkedSetOf<String>()
                val visitedTrees = hashSetOf<String>()
                val pending = ArrayDeque<HistoryBlockCodec.Ref>()
                pending.addLast(header.root)
                while (pending.isNotEmpty()) {
                    val ref = pending.removeLast()
                    if (!visitedTrees.add(ref.digest)) continue
                    reachable.add(ref.digest)
                    val bytes = sql.query("SELECT body FROM main.count_trust_nodes WHERE digest=?", arrayOf(ref.digest)).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(0))
                        cursor.getBlob(0).also { assertFalse(cursor.moveToNext()) }
                    }
                    when (val node = HistoryBlockCodec.decode(ref, bytes)) {
                        is HistoryBlockCodec.Branch -> node.children.forEach(pending::addLast)
                        is HistoryBlockCodec.Leaf -> node.records.forEach { record ->
                            val pointer = Json.parseToJsonElement(record.payload.toByteArray().toString(Charsets.UTF_8)).jsonObject
                            pointer.getValue("chunks").jsonArray.forEach { reachable.add(it.jsonPrimitive.content) }
                        }
                    }
                }
                val retained = issued.getValue("count_trust_nodes").map { row ->
                    assertEquals(setOf("digest", "body"), row.keys)
                    assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("digest").type)
                    assertEquals(Cursor.FIELD_TYPE_BLOB, row.getValue("body").type)
                    row.getValue("digest").value as String
                }
                assertEquals(retained.size, retained.toSet().size)
                assertEquals(reachable, retained.toSet())
            }
            assertEquals(authority, tokens.localCoreWriteAccess())
        } }
        assertUnchanged(issued)
        return issued
    }
}
