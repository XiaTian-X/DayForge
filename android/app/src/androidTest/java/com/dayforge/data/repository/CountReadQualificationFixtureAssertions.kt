package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.LocalCoreWriteAccess
import com.dayforge.data.local.TokenManager
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Test-only exact original COUNT read issuance. The Widget repair entry retains its strict
 * real repair preconditions; Alarm uses only beginBeforeFirstRead before its original read.
 * Every difference must be a fully authenticated root/node/dirty transition, with all other
 * full native/schema/rowid values exact. No rejected publication gets an issuance allowance.
 */
internal class CountReadQualificationFixtureAssertions(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    data class Image(val native: ConsumerNativeImage, val whole: JsonObject)
    private data class Authenticated(val header: CountTrustSeal.Header, val access: LocalCoreWriteAccess,
        val environment: CountTrustValidationEnvironment.Snapshot, val reachable: Set<String>)
    private val images = CountConsumerReadFixtureAssertions(database, tokens, sessions)
    private var prepared: Image? = null
    private var previousRoot: Authenticated? = null

    suspend fun capture(): Image = database.withTransaction {
        Image(images.capture(), NextReplicaStorageProof(database).readInTransaction().contents)
    }
    suspend fun assertUnchanged(expected: Image) { assertEquals(expected, capture()) }

    /** Actual original COUNT preparation before any display publication/account switch.
     * Cold issuance starts from no root/nodes and dirty1; a retained root must authenticate
     * before the real reader. This does not warm or mutate the production source.
     */
    suspend fun beginBeforeFirstRead(): Image {
        check(prepared == null)
        val before = capture()
        val current = control(before.native)
        if (before.native.getValue("count_trust_root").isEmpty()) {
            assertTrue(before.native.getValue("count_trust_nodes").isEmpty())
            assertEquals(cell(1L), current.getValue("dirty"))
        } else {
            previousRoot = authenticate(before).also { old ->
                assertTrue(old.header.generation <= generation(current))
                if (current.getValue("dirty") == cell(0L)) assertEquals(generation(current), old.header.generation)
                assertTrue(nodes(before.native).keys.containsAll(old.reachable))
            }
        }
        prepared = before
        return before
    }

    suspend fun beginAfterActualRepair(beforeCorruption: Image): Image {
        check(prepared == null)
        val before = capture()
        val previous = control(beforeCorruption.native)
        val current = control(before.native)
        assertEquals(previous.getValue("storageId"), current.getValue("storageId"))
        assertEquals(previous.getValue("commandRootHash"), current.getValue("commandRootHash"))
        assertTrue(generation(current) > generation(previous))
        assertEquals(cell(1L), current.getValue("dirty"))
        // The exact fixture restored every damaged completion to native INTEGER 1.
        assertTrue(before.native.getValue("completions").isNotEmpty())
        before.native.getValue("completions").forEach { assertEquals(cell(1L), it.getValue("value")) }
        for (table in listOf("count_trust_root", "count_trust_nodes", "count_trust_routes", "count_command_results",
            "count_command_intents", "count_command_status", "count_command_recovery_roots", "count_command_nodes"))
            assertEquals(beforeCorruption.native.getValue(table), before.native.getValue(table))
        assertEquals(beforeCorruption.whole.getValue("schema"), before.whole.getValue("schema"))
        if (before.native.getValue("count_trust_root").isEmpty()) {
            assertTrue(before.native.getValue("count_trust_nodes").isEmpty())
        } else {
            assertEquals(cell(0L), previous.getValue("dirty"))
            previousRoot = authenticate(before).also { old ->
                assertEquals(generation(previous), old.header.generation)
                assertTrue(nodes(before.native).keys.containsAll(old.reachable))
            }
        }
        prepared = before
        return before
    }

    suspend fun verifyFirstRetry(before: Image): Image {
        check(prepared === before)
        val issued = capture()
        // A legitimate original-path fallback may remain completely read-only.
        if (issued == before) return issued
        val beforeControl = control(before.native)
        assertEquals("A clean authenticated read cannot rewrite its root", cell(1L), beforeControl.getValue("dirty"))
        val cleared = beforeControl + ("dirty" to cell(0L))
        assertEquals(listOf(cleared), issued.native.getValue("count_trust_control"))
        assertEquals(before.native + mapOf(
            "count_trust_control" to listOf(cleared),
            "count_trust_root" to issued.native.getValue("count_trust_root"),
            "count_trust_nodes" to issued.native.getValue("count_trust_nodes")), issued.native)
        val oldTables = before.whole.getValue("tables").jsonObject
        val newTables = issued.whole.getValue("tables").jsonObject
        val oldControl = oldTables.getValue("count_trust_control").jsonArray.single().jsonObject
        val zero = buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", 0L) }
        val exactControl = JsonArray(listOf(JsonObject(oldControl + ("dirty" to zero))))
        // Includes all business/native values, rowids, sqlite_sequence, schema/rootpages,
        // command evidence, routes and identity. No table is filtered out of this equality.
        val expectedTables = JsonObject(oldTables + mapOf(
            "count_trust_control" to exactControl,
            "count_trust_root" to newTables.getValue("count_trust_root"),
            "count_trust_nodes" to newTables.getValue("count_trust_nodes")))
        assertEquals(JsonObject(before.whole + ("tables" to expectedTables)), issued.whole)
        val fresh = authenticate(issued)
        val old = previousRoot
        assertEquals(generation(beforeControl), fresh.header.generation)
        if (old == null) {
            assertEquals(1L, fresh.header.revision)
        } else {
            assertEquals(old.access, fresh.access)
            assertEquals(old.environment, fresh.environment)
            assertEquals(old.header.binding, fresh.header.binding)
            assertEquals(old.header.schemaHash, fresh.header.schemaHash)
            assertEquals(Math.addExact(old.header.revision, 1L), fresh.header.revision)
        }
        val oldNodes = nodes(before.native)
        val freshNodes = nodes(issued.native)
        oldNodes.forEach { (digest, row) -> assertEquals("Previously retained node changed: $digest", row, freshNodes[digest]) }
        assertEquals(oldNodes.keys + fresh.reachable, freshNodes.keys)
        fun rawNodes(tables: JsonObject) = tables.getValue("count_trust_nodes").jsonArray.associateBy {
            it.jsonObject.getValue("digest")
        }
        val retained = rawNodes(oldTables); val currentNodes = rawNodes(newTables)
        retained.forEach { (digest, row) -> assertEquals("Retained node native rowid/bytes changed", row, currentNodes[digest]) }
        assertEquals(buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", 1L) },
            newTables.getValue("count_trust_root").jsonArray.single().jsonObject.getValue("__recovery_rowid"))
        assertUnchanged(issued)
        return issued
    }
    private fun cell(value: Long) = ConsumerNativeCell(Cursor.FIELD_TYPE_INTEGER, value)
    private fun generation(row: Map<String, ConsumerNativeCell>): Long =
        (row.getValue("generation").value as Long).also { assertTrue(it >= 0L) }

    private fun control(image: ConsumerNativeImage): Map<String, ConsumerNativeCell> =
        image.getValue("count_trust_control").single().also { row ->
            assertEquals(setOf("id", "storageId", "generation", "dirty", "commandRootHash"), row.keys)
            assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("commandRootHash").type)
            assertTrue((row.getValue("commandRootHash").value as String).matches(Regex("[0-9a-f]{64}")))
            assertEquals(cell(1L), row.getValue("id"))
            assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("storageId").type)
            assertTrue(com.dayforge.domain.model.isContractUuid(row.getValue("storageId").value as String))
            assertEquals(Cursor.FIELD_TYPE_INTEGER, row.getValue("generation").type)
            assertTrue(generation(row) >= 0L)
            assertTrue(row.getValue("dirty") in setOf(cell(0L), cell(1L)))
        }

    private fun nodes(image: ConsumerNativeImage): Map<String, Map<String, ConsumerNativeCell>> {
        val rows = image.getValue("count_trust_nodes")
        return rows.associateBy { row ->
            assertEquals(setOf("digest", "body"), row.keys)
            assertEquals(Cursor.FIELD_TYPE_STRING, row.getValue("digest").type)
            assertEquals(Cursor.FIELD_TYPE_BLOB, row.getValue("body").type)
            row.getValue("digest").value as String
        }.also { assertEquals(rows.size, it.size) }
    }

    private suspend fun authenticate(snapshot: Image): Authenticated {
        assertUnchanged(snapshot)
        val image = snapshot.native
        val result = sessions.exclusive { database.withTransaction {
            val authority = requireNotNull(tokens.localCoreWriteAccess())
            val owner = requireNotNull(CountTrustRuntime.forDatabase(database))
            val authenticated = owner.maintenance(database) {
                val sql = database.openHelper.writableDatabase
                CountTrustSchema.validate(sql)
                val primary = CountCommandStorageInitialization.verifyRootAnchor(sql)
                assertEquals(ConsumerNativeCell(Cursor.FIELD_TYPE_STRING, CountCommandRootAnchor.digest(primary.native())),
                    control(image).getValue("commandRootHash"))
                val header = requireNotNull(CountTrustSeal.read(sql))
                assertEquals(CountTrustSeal.VALIDATOR, header.validator)
                val binding = CountTrustSeal.binding(authority, control(image).getValue("storageId").value as String)
                val schemaHash = CountTrustIndex.hash(JsonArray(owner.schemaSnapshot().map { row ->
                    JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull })
                }).toString().toByteArray(Charsets.UTF_8))
                assertTrue("Retained root must authenticate actual scope/schema",
                    CountTrustSeal().matches(header, binding, schemaHash))
                val index = CountTrustIndex(sql, binding, header.root) {}
                index.verifyContents()
                val environment = CountTrustValidationEnvironment.Snapshot.decode(
                    requireNotNull(index.get(CountTrustValidationEnvironment.KEY)))
                assertEquals(requireNotNull(CountTrustValidationEnvironment.current()), environment)
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
                Authenticated(header, authority, environment, reachable.toSet())
            }
            assertEquals(authority, tokens.localCoreWriteAccess())
            authenticated
        } }
        assertUnchanged(snapshot)
        return result
    }
}
