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
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.domain.service.AccountSessionCoordinator
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Only the original RoundTimer fixture's actual start -> display comparison uses this helper.
 * No old-root replacement allowance is added to the general first-read helper or production.
 */
internal class CountTimerStartReadFixtureAssertions(private val database: HabitDatabase,
    private val tokens: TokenManager, private val sessions: AccountSessionCoordinator) {
    private val images = CountConsumerReadFixtureAssertions(database, tokens, sessions)
    private data class Authenticated(val header: CountTrustSeal.Header, val access: LocalCoreWriteAccess,
        val environment: CountTrustValidationEnvironment.Snapshot, val reachable: Set<String>)

    suspend fun verifyAfterKnownStart(previousRead: ConsumerNativeImage, command: TimerCommandEntity,
        compareOriginalReaders: suspend () -> Unit) {
        // The caller just ran the real startRound fixture. Never use this allowance for a
        // rejected write, receipt replay, arbitrary corruption, restart or recovery source.
        assertEquals("start", command.commandType)
        assertEquals(1, command.sequence)
        assertNotNull(command.activityUuid)
        assertEquals(command, database.timeLogDao().getTimerCommand(command.id))
        val before = images.capture()
        val beforeWhole = whole()
        assertEquals(previousRead.getValue("count_trust_root"), before.getValue("count_trust_root"))
        assertEquals(previousRead.getValue("count_trust_nodes"), before.getValue("count_trust_nodes"))
        assertEquals(previousRead.getValue("count_trust_routes"), before.getValue("count_trust_routes"))
        assertEquals(previousRead.getValue("count_command_results"), before.getValue("count_command_results"))
        for (table in listOf("count_command_intents", "count_command_status", "count_command_recovery_roots", "count_command_nodes"))
            assertEquals(previousRead.getValue(table), before.getValue(table))
        val oldControl = control(previousRead)
        val beforeControl = control(before)
        assertEquals(oldControl.getValue("storageId"), beforeControl.getValue("storageId"))
        assertEquals(oldControl.getValue("commandRootHash"), beforeControl.getValue("commandRootHash"))
        assertTrue("The real start must invalidate the prior generation",
            generation(beforeControl) > generation(oldControl))
        assertEquals(cell(1L), beforeControl.getValue("dirty"))
        if (previousRead.getValue("count_trust_root").isEmpty()) {
            // A prior normal original-path fallback is not invented into a dirty-root case.
            assertTrue(previousRead.getValue("count_trust_nodes").isEmpty())
            compareOriginalReaders()
            val issued = images.assertFirstReadOnly(before)
            val issuedWhole = whole()
            assertWholeTransition(beforeWhole, issuedWhole)
            compareOriginalReaders()
            assertEquals(issuedWhole, whole())
            images.assertUnchanged(issued)
            return
        }
        assertEquals(cell(0L), oldControl.getValue("dirty"))
        // Authenticate OLD reachable contents while the dirty old root still exists. A bad
        // MAC/node/environment is a fixture failure, never treated as missing or replaceable.
        val old = authenticate(before)
        assertEquals(generation(oldControl), old.header.generation)
        assertEquals(nodes(before).keys, old.reachable)
        compareOriginalReaders()
        val issued = images.capture()
        val cleared = beforeControl + ("dirty" to cell(0L))
        assertEquals(listOf(cleared), issued.getValue("count_trust_control"))
        assertEquals(before + mapOf(
            "count_trust_control" to listOf(cleared),
            "count_trust_root" to issued.getValue("count_trust_root"),
            "count_trust_nodes" to issued.getValue("count_trust_nodes")), issued)
        val issuedWhole = whole()
        assertWholeTransition(beforeWhole, issuedWhole)
        val fresh = authenticate(issued)
        assertEquals(old.access, fresh.access)
        assertEquals(old.environment, fresh.environment)
        assertEquals(old.header.binding, fresh.header.binding)
        assertEquals(old.header.schemaHash, fresh.header.schemaHash)
        assertEquals(Math.addExact(old.header.revision, 1L), fresh.header.revision)
        assertEquals(generation(beforeControl), fresh.header.generation)
        val oldNodes = nodes(before)
        val freshNodes = nodes(issued)
        oldNodes.forEach { (digest, row) -> assertEquals("Previously retained node changed: $digest", row, freshNodes[digest]) }
        // Old nodes are retained byte-for-byte. Every new node must be reachable from the
        // complete authenticated new root; no unrelated cache additions or deletions allowed.
        assertEquals(oldNodes.keys + fresh.reachable, freshNodes.keys)
        compareOriginalReaders()
        images.assertUnchanged(issued)
        assertEquals(issuedWhole, whole())
    }

    private suspend fun whole(): JsonObject = database.withTransaction {
        NextReplicaStorageProof(database).readInTransaction().contents
    }

    /** Full typed image also retains schema/rootpages and each native rowid. This is a
     * test oracle, not a new display authorization or a production cache exception. */
    private fun assertWholeTransition(before: JsonObject, after: JsonObject) {
        if (before == after) return
        val oldTables = before.getValue("tables").jsonObject
        val newTables = after.getValue("tables").jsonObject
        val oldControl = oldTables.getValue("count_trust_control").jsonArray.single().jsonObject
        val zero = buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", 0L) }
        val exactControl = JsonArray(listOf(JsonObject(oldControl + ("dirty" to zero))))
        val expected = JsonObject(oldTables + mapOf(
            "count_trust_control" to exactControl,
            "count_trust_root" to newTables.getValue("count_trust_root"),
            "count_trust_nodes" to newTables.getValue("count_trust_nodes")))
        assertEquals(JsonObject(before + ("tables" to expected)), after)
        val oldNodes = oldTables.getValue("count_trust_nodes").jsonArray.associateBy { it.jsonObject.getValue("digest") }
        val newNodes = newTables.getValue("count_trust_nodes").jsonArray.associateBy { it.jsonObject.getValue("digest") }
        oldNodes.forEach { (digest, row) -> assertEquals("Retained node rowid/bytes changed", row, newNodes[digest]) }
        assertEquals(buildJsonObject { put("sqlite_type", Cursor.FIELD_TYPE_INTEGER); put("value", 1L) },
            newTables.getValue("count_trust_root").jsonArray.single().jsonObject.getValue("__recovery_rowid"))
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

    private suspend fun authenticate(image: ConsumerNativeImage): Authenticated {
        images.assertUnchanged(image)
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
        images.assertUnchanged(image)
        return result
    }
}
