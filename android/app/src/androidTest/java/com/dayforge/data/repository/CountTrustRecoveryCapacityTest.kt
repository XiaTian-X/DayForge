package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.AccountIconNamespace
import com.dayforge.data.local.*
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.domain.service.AccountIconController
import java.util.concurrent.atomic.AtomicBoolean
import java.io.DataOutputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete physical class. The large-cache counterexample is deliberately retained: valid
 * node-sized blobs alone exhaust the old physical preview, without increasing business rows.
 * Archive fixtures below prove framing/capacity only, never business acceptance authority.
 */
@RunWith(AndroidJUnit4::class)
class CountTrustRecoveryCapacityTest : CountTrustRecoveryFixture() {
    @Test(timeout = 600_000) fun validSizedAccumulatedCacheCannotPermanentlyConsumeBusinessRecoveryCapacity() = runBlocking<Unit> {
        register(); seedDeliveredCommand(); val access = access(); val before = raw()
        val commands = commandRows()
        assertEquals(commandTables, commands.keys)
        for (table in commandTables) assertTrue(table, commands.getValue(table).isNotEmpty())
        assertEquals(1, commands.getValue("count_command_results").size)
        seedOptionalCache(130, 128 * 1024)
        assertEquals(commands, commandRows())
        val beforeLocal = localRows()
        val commandResults = beforeLocal.getValue("count_command_results")
        val failed = runCatching { raw() }.exceptionOrNull()
        assertNotNull("Retained capacity counterexample must reproduce before cleanup", failed)
        assertTrue(generateSequence(failed) { it.cause }.any { it.message?.contains("SYNC_RECOVERY_PREVIEW_TOO_LARGE") == true })
        assertTrue(preparation().discardCache(access))
        assertTrue(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        val after = raw()
        val oldTables = before.contents.getValue("tables").jsonObject
        val newTables = after.contents.getValue("tables").jsonObject
        for (table in CountTrustSchema.BUSINESS_TABLES) assertEquals(table, oldTables.getValue(table), newTables.getValue(table))
        assertEquals(before.contents.getValue("schema"), after.contents.getValue("schema"))
        assertEquals(NextReplicaSourceClosure.storage(before, null), NextReplicaSourceClosure.storage(after, null))
        val retained = localRows()
        assertTrue(retained.getValue("count_trust_nodes").isEmpty()); assertTrue(retained.getValue("count_trust_routes").isEmpty())
        assertEquals(commandResults, retained.getValue("count_command_results"))
        assertEquals(commands, commandRows())
        assertEquals(beforeLocal.getValue("count_trust_control").single()[1], retained.getValue("count_trust_control").single()[1])
        assertEquals("1:1", retained.getValue("count_trust_control").single()[3])
        val oldControl = beforeLocal.getValue("count_trust_control").single()
        val newControl = retained.getValue("count_trust_control").single()
        assertEquals(5, oldControl.size); assertEquals(5, newControl.size)
        assertEquals(oldControl[0], newControl[0]); assertEquals(oldControl[4], newControl[4])
        assertEquals("1:${Math.addExact(oldControl[2].removePrefix("1:").toLong(), 1L)}", newControl[2])
        assertFalse(preparation().discardCache(access))
        assertEquals(after.hash, raw().hash)
        assertEquals(commands, commandRows())
    }

    // Private positive fixture only; shared malformed-cache fault inputs stay unchanged.
    private suspend fun seedDeliveredCommand() {
        tokens.getOrCreateInstallationId()
        // Actual metric-value commands do not edit appearance or open material storage.
        // Unexpected material access fails, rather than receiving fabricated permission.
        val materialRequested = AtomicBoolean()
        val icons = AccountIconController({
            materialRequested.set(true); error("RECOVERY_FIXTURE_UNEXPECTED_MATERIAL_ACCESS")
        }, tokens)
        try {
            val editor = NextObjectEditor(db, tokens, sessions, icons)
            val repository = MetricRepository(db, db.metricDao(), db.metricLogDao(), db.habitDao(),
                db.habitMetricLinkDao(), nextObjectEditor = editor)
            val selected = repository.getMetricForEditing(metric.id)
            val attempt = repository.beginIndependentValue(metric.id, 12.5, "recovery preservation",
                recordedAt = millis, authority = requireNotNull(selected.authority), expectedMetricUuid = metric.uuid)
            val reference = requireNotNull(attempt.reference)
            assertTrue(repository.resolveIndependentAttempt(attempt) is IndependentMetricResolution.Unregistered)
            assertEquals(0, count("count_command_results")); assertEquals(0, count("metric_logs"))
            val saved = repository.executeIndependentValue(attempt) // Original real T0 and T1.
            assertEquals(reference, saved.reference)
            val commands = CountCommandStore(db, tokens, sessions)
            val committed = commands.resolve(reference.native()).historical
            assertEquals(CountCommandRecords.Action.METRIC_APPEND, committed.intent.action)
            assertEquals(CountCommandRecords.Execution.APPLIED, committed.status.execution)
            assertEquals(CountCommandRecords.Delivery.RESULT_PENDING, committed.status.delivery)
            assertNotNull(committed.outcome)
            assertEquals(1L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
            repository.acknowledgeIndependentValue(saved) { true } // Original local result T2, not network ACK.
            val delivered = commands.resolve(reference.native()).historical
            assertEquals(CountCommandRecords.Delivery.DELIVERED, delivered.status.delivery)
            assertEquals(committed.intent.text(), delivered.intent.text())
            assertEquals(requireNotNull(committed.outcome).text(), requireNotNull(delivered.outcome).text())
            assertEquals(1, count("count_command_results")); assertEquals(1, count("metric_logs"))
            assertEquals(1, count("sync_outbox"))
            assertEquals(0L, db.withTransaction { CountCommandRecoveryInventory(db, tokens).pendingInTransaction(access()) })
        } finally {
            withTimeout(5_000) { icons.awaitImages() }
            icons.close()
        }
        assertFalse(materialRequested.get())
    }

    private val commandTables = setOf("count_command_intents", "count_command_status", "count_command_results",
        "count_command_nodes", "count_command_recovery_roots")
    private suspend fun commandRows(): ConsumerNativeImage = db.withTransaction {
        commandTables.associateWith { table ->
            db.openHelper.readableDatabase.query("SELECT rowid AS __recovery_rowid,* FROM main.`$table` ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add(buildMap {
                    for (column in 0 until cursor.columnCount) {
                        val type = cursor.getType(column)
                        val value: Any? = when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).toRawBits()
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> error("Unexpected command native type")
                        }
                        put(cursor.getColumnName(column), ConsumerNativeCell(type, value))
                    }
                }) }
            }
        }
    }

    private suspend fun seedOptionalCache(nodes: Int, bytes: Int) = db.withTransaction { CountTrustRuntime.maintenance(db) {
        val sql = db.openHelper.writableDatabase
        repeat(nodes) { index ->
            val value = ByteArray(bytes) { 65 }.also {
                it[0] = (index and 255).toByte(); it[1] = (index ushr 8).toByte()
            }
            sql.execSQL("INSERT INTO count_trust_nodes(digest,body) VALUES(?,?)", arrayOf(CountTrustIndex.hash(value), value))
        }
        sql.execSQL("INSERT INTO count_trust_routes VALUES('completions','key',NULL,NULL,NULL,NULL,'derived','hash')")
    } }

    /** Shape-only budget inputs, never old-source identity or recovery permission. */
    private fun accounting19(contents: JsonObject): JsonObject = when (contents.getValue("user_version")) {
        JsonPrimitive(20) -> requireNotNull(NextReplicaV19StorageView.accountingContents(ReplicaStorageProof(contents, "")))
        JsonPrimitive(19) -> contents
        else -> error("Unexpected budget fixture version")
    }

    /** Typed storage budget fixture, not a valid source/receipt graph. Many individually bounded
     * rows ensure that only aggregate bytes distinguish the two neighboring cases.
     */
    private fun image(base: JsonObject, target: Long): JsonObject {
        fun cell(type: Int, value: JsonElement) = buildJsonObject { put("sqlite_type", type); put("value", value) }
        fun row(index: Int, payload: String) = buildJsonObject {
            put("__recovery_rowid", cell(Cursor.FIELD_TYPE_INTEGER, JsonPrimitive(index + 1)))
            for ((key, text) in mapOf("kind" to NEXT_OPERATION, "requestId" to id(1000 + index),
                "originHash" to "a".repeat(64), "transmissionHash" to "b".repeat(64),
                "resultHash" to "c".repeat(64), "resultJson" to payload)) put(key, cell(Cursor.FIELD_TYPE_STRING, JsonPrimitive(text)))
        }
        fun withRows(rows: List<JsonObject>) = JsonObject(base + ("tables" to JsonObject(base.getValue("tables").jsonObject +
            ("next_rejections" to JsonArray(rows)))))
        val count = 64
        val empty = withRows(List(count) { row(it, "") })
        val previous = requireNotNull(NextReplicaV18StorageView.projectContents(ReplicaStorageProof(accounting19(empty), "")))
        val padding = (target - previous.toString().toByteArray(Charsets.UTF_8).size).toInt()
        require(padding > 0)
        val full = "p".repeat(padding / count)
        val longer = full + "p"
        return withRows(List(count) { row(it, if (it < padding % count) longer else full) })
    }

    @Test(timeout = 600_000) fun fixedMetadataAllowanceKeepsExactOldDataLimitAndCannotExemptCommandRows() = runBlocking<Unit> {
        register(); val current = raw().contents
        assertEquals(JsonPrimitive(20), current.getValue("user_version"))
        for (base in listOf(accounting19(current), current)) {
            val version = base.getValue("user_version").jsonPrimitive.int
            val atLimit = image(base, ReplicaStorageBudget.BUSINESS_LIMIT)
            assertEquals(base.getValue("user_version"), atLimit.getValue("user_version"))
            val rawBytes = atLimit.toString().toByteArray(Charsets.UTF_8).size.toLong()
            assertTrue(rawBytes > ReplicaStorageBudget.BUSINESS_LIMIT)
            assertTrue(rawBytes - ReplicaStorageBudget.BUSINESS_LIMIT in 1L..ReplicaStorageBudget.OWNED_METADATA_LIMIT)
            assertEquals(ReplicaStorageBudget.BUSINESS_LIMIT, ReplicaStorageBudget.requireWithin(atLimit))
            val plusOne = image(base, ReplicaStorageBudget.BUSINESS_LIMIT + 1)
            assertTrue(plusOne.toString().toByteArray(Charsets.UTF_8).size <= ReplicaStorageBudget.RAW_LIMIT)
            assertEquals("SYNC_RECOVERY_PREVIEW_TOO_LARGE",
                runCatching { ReplicaStorageBudget.requireWithin(plusOne) }.exceptionOrNull()?.message)
            val tables = atLimit.getValue("tables").jsonObject
            val dataTables = if (version == 20) commandTables - "count_command_recovery_roots" else setOf("count_command_results")
            for (table in dataTables) {
                val command = JsonObject(atLimit + ("tables" to JsonObject(tables +
                    (table to JsonArray(listOf(buildJsonObject { put("retained_command", "must_count") }))))))
                assertEquals(table, "SYNC_RECOVERY_PREVIEW_TOO_LARGE",
                    runCatching { ReplicaStorageBudget.requireWithin(command) }.exceptionOrNull()?.message)
            }
            val schema = atLimit.getValue("schema").jsonArray
            val incomplete = JsonObject(atLimit + ("schema" to JsonArray(schema.filterNot { entry ->
                entry.jsonObject["name"]?.jsonObject?.get("value") == JsonPrimitive("count_trust_dirty_completions_insert")
            })))
            assertNotNull(runCatching { ReplicaStorageBudget.requireWithin(incomplete) }.exceptionOrNull())
            val hiddenData = JsonObject(atLimit + ("schema" to JsonArray(schema.map { entry ->
                val row = entry.jsonObject
                if (row["name"]?.jsonObject?.get("value") == JsonPrimitive("count_trust_dirty_completions_insert"))
                    JsonObject(row + ("hidden_payload" to JsonPrimitive("must-not-be-exempt"))) else row
            })))
            assertTrue(hiddenData.toString().toByteArray(Charsets.UTF_8).size <= ReplicaStorageBudget.RAW_LIMIT)
            assertEquals(if (version == 19) "SYNC_REPLICA_V18_VIEW_INVALID" else "SYNC_REPLICA_V19_VIEW_INVALID",
                runCatching { ReplicaStorageBudget.requireWithin(hiddenData) }.exceptionOrNull()?.message)
        }
    }

    private fun archive(body: JsonObject): ReplicaArchiveReceipt {
        val namespace = AccountIconNamespace(id(1), id(2), id(3)); val archiveId = UUID.randomUUID().toString()
        val root = File(directory, "replica-archives-v1/${id(1)}/${id(2)}/${id(3)}").apply { check(isDirectory || mkdirs()) }
        val file = File(root, "$archiveId.candidate")
        val contents = listOf("input.json" to "{}", "business.json" to body.toString(), "icons.json" to "{}")
        val entries = contents.map { (name, text) -> ReplicaArchiveEntry(name, text.toByteArray(Charsets.UTF_8).size.toLong(), syncPayloadHash(text)) }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write("DayForge private replica candidate\n1\n".toByteArray(Charsets.US_ASCII))
            contents.zip(entries).forEach { (value, entry) ->
                val name = entry.name.toByteArray(Charsets.US_ASCII)
                out.writeInt(name.size); out.write(name); out.writeLong(entry.byteLength); out.write(value.second.toByteArray(Charsets.UTF_8))
            }
            out.writeInt(0)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { stream -> val buffer = ByteArray(65536); while (stream.read(buffer) != -1) { } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return ReplicaArchiveReceipt(namespace, archiveId, entries.first().contentHash, file.length(), hash, entries)
    }

    @Test(timeout = 600_000) fun coldArchiveReadbackRechecksDataBudgetRatherThanOnlyLargerPhysicalLimit() = runBlocking<Unit> {
        register(); val expected = access(); val files = ReplicaPrivateArchiveStore(directory); val current = raw().contents
        val old = archive(buildJsonObject { put("old_frame", true) })
        files.verify(old) { check(tokens.isCurrentSyncAuthority(expected)) }
        for (base in listOf(accounting19(current), current)) {
            val valid = archive(image(base, ReplicaStorageBudget.BUSINESS_LIMIT))
            ReplicaStageArchive(valid.archiveId, valid.inputHash, valid.byteLength, valid.contentHash,
                valid.entries.map { ReplicaStageArchiveEntry(it.name, it.byteLength, it.contentHash) })
            files.verify(valid) { check(tokens.isCurrentSyncAuthority(expected)) }
            val oversizedBusiness = archive(image(base, ReplicaStorageBudget.BUSINESS_LIMIT + 1))
            assertTrue(oversizedBusiness.entries[1].byteLength <= ReplicaStorageBudget.RAW_LIMIT)
            assertEquals("SYNC_RECOVERY_PREVIEW_TOO_LARGE",
                runCatching { files.verify(oversizedBusiness) { check(tokens.isCurrentSyncAuthority(expected)) } }.exceptionOrNull()?.message)
        }
        files.verify(old) { check(tokens.isCurrentSyncAuthority(expected)) }
    }

}
