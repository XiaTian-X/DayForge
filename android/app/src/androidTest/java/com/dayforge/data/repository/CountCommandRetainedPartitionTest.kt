package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction
import com.dayforge.data.local.CountCommandLegacyNative
import java.nio.ByteBuffer
import java.security.MessageDigest
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandRetainedPartitionTest : CountCommandRetainedFixture() {
    @Test(timeout = 150_000) fun utf16NewOutcomeIsExcludedButOldBlobLargeUnicodeAndNativeCellsRetainOriginalBytes() = runBlocking<Unit> {
        val blob = id(70).toByteArray(Charsets.UTF_8)
        seed(utf16 = true) { sql ->
            sql.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)", arrayOf<Any?>(blob, byteArrayOf(0, 1), 2.5, null, "家庭/Ω/😀", "committed"))
            sql.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)", arrayOf<Any?>("k".repeat(131_072), "input", "fact", null, "v".repeat(131_073), "committed"))
        }
        val database = openCurrent(); val before = root(database)
        val metric = metric(database); val repo = repository(database)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(metric.id, 4.0, "UTF16", expectedMetricUuid = metric.uuid))
        assertEquals(before.legacyHash, root(database).legacyHash); assertEquals(3L, root(database).legacyCount)
        assertEquals(4L, count(database, "count_command_results")); assertEquals(1L, count(database, "metric_logs"))
        repo.acknowledgeIndependentValue(saved) { true }
        val cold = openCurrent(); assertLegacy(cold)
        cold.openHelper.writableDatabase.query("PRAGMA encoding").use { assertTrue(it.moveToFirst()); assertEquals("UTF-16le", it.getString(0)) }
        assertEquals(3L, store(cold).discover(32).retainedCount)
        assertTrue(repository(cold).resolveIndependentValue(requireNotNull(saved.reference)) is IndependentMetricResolution.Saved)
        assertEquals(before.legacyHash, root(cold).legacyHash)
    }
    @Test(timeout = 150_000) fun utf16BeOldFileMigratesAndNewCommandPreservesRawPartitionThroughColdReopen() = runBlocking<Unit> {
        val oldTextId = id(606)
        val oldBlobId = id(607).toByteArray(Charsets.UTF_8)
        val unicode = "家庭/Ω/😀"
        lateinit var originalFingerprint: Pair<Long, String>
        lateinit var originalRows: List<List<String?>>
        // The fixture selects BE before the first table in a new real file. The following
        // old19 cells are inserted only AFTER that encoding is fixed; no existing file is recoded.
        sourceUtf16Be(19) { sql ->
            assertEquals(19, sql.version)
            sql.execSQL("DELETE FROM next_transmissions"); sql.execSQL("DELETE FROM next_rejections")
            sql.execSQL("INSERT INTO count_command_results VALUES('legacy-native-id','old-input','old-fact',NULL,'retained old result','committed')")
            sql.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(oldTextId, "input", "fact", null, unicode, "committed"))
            sql.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>(oldBlobId, byteArrayOf(0, 255.toByte()), "old-blob-fact", null, unicode, "committed"))
            assertBeTextBytes(sql, oldTextId, unicode)
            originalFingerprint = frozenV1Fingerprint(sql)
            assertEquals(3L, originalFingerprint.first)
            assertEquals(originalFingerprint, CountCommandLegacyNative.fingerprint(sql))
            originalRows = retainedNativeImage(sql, oldTextId, oldBlobId)
            assertEquals(3, originalRows.size)
        }
        // Use the actual production Room20 provider and original19->20 migration.
        val database = openCurrent(); val sql = database.openHelper.writableDatabase
        assertEquals(20, sql.version)
        val migrated = root(database)
        assertEquals("LEGACY_UNENROLLED", migrated.mode)
        assertEquals("MIGRATION_19_20", migrated.initializationSource)
        assertEquals(originalFingerprint.first, migrated.legacyCount)
        assertEquals(originalFingerprint.second, migrated.legacyHash)
        assertEquals(originalFingerprint, frozenV1Fingerprint(sql))
        assertEquals(originalRows, retainedNativeImage(sql, oldTextId, oldBlobId))
        assertBeTextBytes(sql, oldTextId, unicode)
        val metric = metric(database); val repo = repository(database)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(metric.id, 4.0, "UTF16BE", expectedMetricUuid = metric.uuid))
        val ref = requireNotNull(saved.reference)
        assertBeTextBytes(sql, ref.commandId)
        assertEquals("ACCOUNT_BOUND_RETAINED", root(database).mode)
        assertEquals(3, root(database).format)
        assertEquals(migrated.storageId, root(database).storageId)
        assertEquals(migrated.datasetId, root(database).datasetId)
        assertEquals(originalFingerprint.first, root(database).legacyCount)
        assertEquals(originalFingerprint.second, root(database).legacyHash)
        assertEquals(originalRows, retainedNativeImage(sql, oldTextId, oldBlobId))
        assertEquals(4L, count(database, "count_command_results")); assertEquals(1L, count(database, "metric_logs"))
        val pending = store(database).discover(32) // Real mandatory P = E + L verification.
        assertTrue(pending.complete); assertEquals(3L, pending.retainedCount)
        assertEquals(listOf(ref.commandId), pending.commands.map { it.reference.commandId })
        repo.acknowledgeIndependentValue(saved) { true } // Local result delivery, not a network ACK.
        assertTrue(store(database).discover(32).commands.isEmpty())
        val committed = image(database)
        val raw = database.withTransaction { NextReplicaStorageProof(database).readInTransaction() }
        assertEquals(committed, raw.contents); assertEquals(committed, image(database))
        assertNull(NextReplicaV19StorageView.project(raw))
        val cold = openCurrent(); assertLegacy(cold)
        val coldSql = cold.openHelper.writableDatabase
        assertBeTextBytes(coldSql, oldTextId, unicode); assertBeTextBytes(coldSql, ref.commandId)
        assertEquals(originalRows, retainedNativeImage(coldSql, oldTextId, oldBlobId))
        assertEquals(originalFingerprint.second, root(cold).legacyHash)
        val coldPending = store(cold).discover(32)
        assertEquals(3L, coldPending.retainedCount); assertTrue(coldPending.complete); assertTrue(coldPending.commands.isEmpty())
        val resolved = repository(cold).resolveIndependentValue(ref)
        assertTrue(resolved is IndependentMetricResolution.Saved)
        assertEquals(saved.factUuid, (resolved as IndependentMetricResolution.Saved).value.factUuid)
        assertEquals(1L, count(cold, "metric_logs")); assertEquals(4L, count(cold, "count_command_results"))
        val coldRaw = cold.withTransaction { NextReplicaStorageProof(cold).readInTransaction() }
        assertEquals(raw.contents, coldRaw.contents); assertEquals(raw.hash, coldRaw.hash)
        assertEquals(committed, image(cold)) // Open, discovery, resolve and RAW cannot re-sign/repair old rows.
    }
    @Test(timeout = 150_000) fun sameCountOldByteReplacementIsRejectedAtColdMandatoryOpenAndNeverResigned() = runBlocking<Unit> {
        seed(); val database = openCurrent(); store(database).ensureForwardEnrollment(); val original = root(database)
        external("UPDATE count_command_results SET resultJson='different same count' WHERE commandId='legacy-native-id'")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull())
        room?.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT legacyHash,revision,mac FROM count_command_recovery_roots", null).use {
                assertTrue(it.moveToFirst()); assertEquals(original.legacyHash, it.getString(0)); assertEquals(original.revision, it.getLong(1)); assertArrayEquals(original.mac, it.getBlob(2))
            }
            raw.rawQuery("SELECT resultJson FROM count_command_results", null).use { assertTrue(it.moveToFirst()); assertEquals("different same count", it.getString(0)) }
        }
    }
    @Test(timeout = 150_000) fun extraNativeOutcomeCannotBeClassifiedAsLegacyEvenWithValidLookingCommandId() = runBlocking<Unit> {
        seed(); val database = openCurrent(); store(database).ensureForwardEnrollment()
        external("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)", arrayOf(id(80), "input", "fact", null, "{}", "committed"))
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull())
    }
    @Test(timeout = 150_000) fun missingOldRowCannotBecomeAnEmptyRetainedSet() = runBlocking<Unit> {
        seed(); val database = openCurrent(); store(database).ensureForwardEnrollment()
        external("DELETE FROM count_command_results WHERE commandId='legacy-native-id'")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull())
    }
    @Test(timeout = 150_000) fun originalCanonicalOldKeyCollisionNeverBecomesAnUnregisteredNewCommand() = runBlocking<Unit> {
        seed { it.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)", arrayOf(id(81), "input", "fact", null, "{}", "committed")) }
        val database = openCurrent(); val before = image(database)
        val state = store(database).independentRoot()
        val ref = CountCommandStore.Reference(id(81), "a".repeat(64), state.namespace.hash, state.namespace.dataset)
        assertNotNull(runCatching { store(database).independentRegistration(ref) }.exceptionOrNull())
        assertEquals(before, image(database))
        store(database).ensureForwardEnrollment(); val enrolled = image(database)
        assertNotNull(runCatching { store(database).independentRegistration(ref) }.exceptionOrNull())
        assertEquals(enrolled, image(database)); assertEquals(0L, count(database, "count_command_intents")); assertLegacy(database)
    }
    @Test(timeout = 150_000) fun emptyCatalogMustHaveAnActualValidNodeAndCannotUseAnAbsentSentinel() = runBlocking<Unit> {
        seed { it.execSQL("INSERT INTO count_command_results VALUES('00000000-0000-4000-8000-000000000000','input','fact',NULL,'{}','committed')") }
        val database = openCurrent(); store(database).ensureForwardEnrollment()
        assertEquals(2L, store(database).discover(32).retainedCount); assertLegacy(database)
        external("DELETE FROM count_command_nodes")
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull())
    }
    private suspend fun frozenCodec(utf16: Boolean) {
        seed(utf16) { sql ->
            sql.execSQL("INSERT INTO count_command_results VALUES(?,?,?,?,?,?)", arrayOf<Any?>(id(99).toByteArray(),
                byteArrayOf(0, 255.toByte()), "家庭/Ω/😀", null, "a".repeat(131_073), "committed"))
        }
        val database = openCurrent(); val sql = database.openHelper.writableDatabase
        val original = frozenV1Fingerprint(sql)
        assertEquals(original, CountCommandLegacyNative.fingerprint(sql))
        assertEquals(original.first, root(database).legacyCount); assertEquals(original.second, root(database).legacyHash)
        store(database).ensureForwardEnrollment(); assertLegacy(database)
        assertEquals(original.second, root(database).legacyHash)
    }
    @Test(timeout = 150_000) fun nativeStreamMatchesFrozenOriginalV1CodecForUtf8() = runBlocking<Unit> { frozenCodec(false) }
    @Test(timeout = 150_000) fun nativeStreamMatchesFrozenOriginalV1CodecForUtf16() = runBlocking<Unit> { frozenCodec(true) }

    // Frozen original initialization implementation, independent of the extracted codec.
    private fun frozenV1Fingerprint(sql: SupportSQLiteDatabase): Pair<Long, String> {
        val columns = listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("DayForge/count-command-unenrolled-native/v1\u0000".toByteArray(Charsets.UTF_8))
        var count = 0L
        // commandId's original BINARY primary-key order is stable across compaction. rowid
        // is only a current-snapshot locator and is not part of the fingerprint or identity.
        sql.query("SELECT rowid,${columns.joinToString(",") { "typeof(`$it`),length(CAST(`$it` AS BLOB))" }} " +
            "FROM count_command_results ORDER BY commandId").use { c ->
            while (c.moveToNext()) {
                poll(); count = Math.addExact(count, 1L)
                require(c.getType(0) == Cursor.FIELD_TYPE_INTEGER); val rowid = c.getLong(0)
                for ((index, column) in columns.withIndex()) {
                    val typeColumn = 1 + index * 2
                    require(c.getType(typeColumn) == Cursor.FIELD_TYPE_STRING)
                    val kind = c.getString(typeColumn)
                    val type = when (kind) {
                        "null" -> Cursor.FIELD_TYPE_NULL
                        "integer" -> Cursor.FIELD_TYPE_INTEGER
                        "real" -> Cursor.FIELD_TYPE_FLOAT
                        "text" -> Cursor.FIELD_TYPE_STRING
                        "blob" -> Cursor.FIELD_TYPE_BLOB
                        else -> error("COMMAND_LEGACY_NATIVE_TYPE")
                    }
                    val bytes = when (type) {
                        Cursor.FIELD_TYPE_NULL -> { require(c.getType(typeColumn + 1) == Cursor.FIELD_TYPE_NULL); 0L }
                        Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> 8L
                        else -> {
                            require(c.getType(typeColumn + 1) == Cursor.FIELD_TYPE_INTEGER)
                            c.getLong(typeColumn + 1).also { require(it >= 0) }
                        }
                    }
                    digest.update(type.toByte()); digest.update(ByteBuffer.allocate(8).putLong(bytes).array())
                    if (type in setOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT)) {
                        sql.query("SELECT `$column` FROM count_command_results WHERE rowid=? LIMIT 2", arrayOf(rowid)).use { value ->
                            require(value.moveToFirst() && value.getType(0) == type)
                            val bits = if (type == Cursor.FIELD_TYPE_INTEGER) value.getLong(0) else value.getDouble(0).toRawBits()
                            digest.update(ByteBuffer.allocate(8).putLong(bits).array()); require(!value.moveToNext())
                        }
                    } else if (type in setOf(Cursor.FIELD_TYPE_STRING, Cursor.FIELD_TYPE_BLOB)) {
                        var offset = 0L
                        while (offset < bytes) {
                            poll(); val size = minOf(65_536L, bytes - offset)
                            sql.query("SELECT substr(CAST(`$column` AS BLOB),?,?) FROM count_command_results WHERE rowid=? LIMIT 2",
                                arrayOf(Math.addExact(offset, 1L), size, rowid)).use { value ->
                                require(value.moveToFirst() && value.getType(0) == Cursor.FIELD_TYPE_BLOB)
                                val chunk = value.getBlob(0); require(chunk.size.toLong() == size && !value.moveToNext())
                                digest.update(chunk)
                            }
                            offset = Math.addExact(offset, size)
                        }
                    }
                }
            }
        }
        digest.update(ByteBuffer.allocate(8).putLong(count).array())
        return count to digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertBeTextBytes(sql: SupportSQLiteDatabase, commandId: String, result: String? = null) {
        sql.query("PRAGMA encoding").use {
            assertTrue(it.moveToFirst()); assertEquals("UTF-16be", it.getString(0)); assertFalse(it.moveToNext())
        }
        sql.query("SELECT typeof(commandId),length(CAST(commandId AS BLOB)),CAST(commandId AS BLOB),CAST(resultJson AS BLOB) " +
            "FROM count_command_results WHERE commandId=? LIMIT 2", arrayOf(commandId)).use {
            assertTrue(it.moveToFirst()); assertEquals("text", it.getString(0)); assertEquals(72L, it.getLong(1))
            assertArrayEquals(commandId.toByteArray(Charsets.UTF_16BE), it.getBlob(2))
            result?.let { value -> assertArrayEquals(value.toByteArray(Charsets.UTF_16BE), it.getBlob(3)) }
            assertFalse(it.moveToNext())
        }
    }
    // Fixed old keys and every native cell. Type tags plus actual SQLite BLOB bytes protect
    // endian-sensitive TEXT as well as uninterpreted BLOB/NULL; no decoded-string-only oracle.
    private fun retainedNativeImage(sql: SupportSQLiteDatabase, textId: String, blobId: ByteArray): List<List<String?>> {
        val columns = listOf("commandId", "inputHash", "factUuid", "requestId", "resultJson", "state")
        return sql.query("SELECT " + columns.joinToString(",") { "typeof(`$it`),hex(CAST(`$it` AS BLOB))" } +
            " FROM count_command_results WHERE commandId IN (?,?,?) ORDER BY commandId",
            arrayOf<Any>("legacy-native-id", textId, blobId)).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        }
    }
    private fun poll() { if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Command initialization interrupted") }

}
