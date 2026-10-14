package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.SyncOutboxEntity
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite chunks and source-row bytes. Not a simulated proof of a usable recovery operation. */
@RunWith(AndroidJUnit4::class)
class NextRecoveryPayloadStoreTest : SyncPersistenceFixture() {
    private val recoveryId = UUID.randomUUID().toString()
    private val requestId = UUID.randomUUID().toString()
    private val sourceKey get() = "source:sync_operation:$requestId"
    private val store get() = NextRecoveryPayloadStore(database)
    private fun header() {
        database.openHelper.writableDatabase.execSQL("INSERT INTO next_recovery_audits VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(recoveryId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), NEXT_OPERATION, requestId, "a".repeat(64), "b".repeat(64), "c".repeat(64), 1))
    }
    private suspend fun fixture(size: Int = NEXT_RECOVERY_CHUNK_BYTES * 2 + 3): Pair<ByteArray, NextRecoveryPayloadReference> {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val reference = database.withTransaction { header(); store.write(recoveryId, sourceKey, bytes) }
        return bytes to reference
    }
    private suspend fun requireInvalid(reference: NextRecoveryPayloadReference) {
        assertNotNull(runCatching { database.withTransaction { store.read(recoveryId, reference) } }.exceptionOrNull())
    }
    @Test fun coldReopenRetainsExactBinaryChunksAndNeverCreatesAnAcceptanceOrQueue() = runBlocking {
        val (bytes, reference) = fixture()
        reopen()
        database.withTransaction {
            assertArrayEquals(bytes, store.read(recoveryId, reference))
            assertEquals(setOf(sourceKey), store.keys(recoveryId))
        }
        assertTrue(database.syncOutboxDao().getAll().isEmpty()); assertTrue(paths.isEmpty())
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM next_acceptances").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }
    @Test fun absentGapAndAdditionalPartAreRejectedNotTruncated() = runBlocking {
        val (_, reference) = fixture()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("DELETE FROM next_recovery_audit_payloads WHERE part=1")
        requireInvalid(reference)
        sql.execSQL("UPDATE next_recovery_audit_payloads SET part=1 WHERE part=2")
        requireInvalid(reference)
        sql.execSQL("INSERT INTO next_recovery_audit_payloads VALUES(?,?,4,X'00')", arrayOf(recoveryId, sourceKey))
        requireInvalid(reference)
    }
    @Test fun changedContentAndDescriptorCannotPassOriginalHashOrLength() = runBlocking {
        val (_, reference) = fixture()
        requireInvalid(reference.copy(hash = "d".repeat(64)))
        requireInvalid(reference.copy(byteCount = reference.byteCount - 1))
        database.openHelper.writableDatabase.execSQL("UPDATE next_recovery_audit_payloads SET bytes=X'000000' WHERE part=2")
        requireInvalid(reference)
    }
    @Test fun textBlobAndOversizedSingleRowAreRejectedBeforeFullAllocation() = runBlocking {
        val (_, reference) = fixture()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE next_recovery_audit_payloads SET bytes='same-length-text' WHERE part=2")
        requireInvalid(reference)
        sql.execSQL("UPDATE next_recovery_audit_payloads SET bytes=zeroblob(8388608) WHERE part=0")
        requireInvalid(reference)
    }
    @Test fun invalidPartTypeAndUnknownOversizedKeyAreRejectedWithoutCoercion() = runBlocking {
        val (_, reference) = fixture()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE next_recovery_audit_payloads SET part=1.5 WHERE part=1")
        requireInvalid(reference)
        sql.execSQL("INSERT INTO next_recovery_audit_payloads VALUES(?,'unknown',0,X'00')", arrayOf(recoveryId))
        assertNotNull(runCatching { database.withTransaction { store.keys(recoveryId) } }.exceptionOrNull())
        sql.execSQL("UPDATE next_recovery_audit_payloads SET payloadKey=hex(zeroblob(1048576)) WHERE payloadKey='unknown'")
        assertNotNull(runCatching { database.withTransaction { store.keys(recoveryId) } }.exceptionOrNull())
    }
    @Test fun duplicateWritesAndMissingParentCannotCreatePartialOrOverwrittenAudit() = runBlocking {
        val (bytes, reference) = fixture()
        assertNotNull(runCatching { database.withTransaction { store.write(recoveryId, sourceKey, bytes) } }.exceptionOrNull())
        database.withTransaction { assertArrayEquals(bytes, store.read(recoveryId, reference)) }
        val unknown = UUID.randomUUID().toString()
        assertNotNull(runCatching { database.withTransaction { store.write(unknown, "manifest", byteArrayOf(1)) } }.exceptionOrNull())
        database.withTransaction { assertTrue(store.keys(unknown).isEmpty()) }
    }
    @Test fun writeTriggerDamageRollsBackAllChunksAndHeader() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER damage_recovery AFTER INSERT ON next_recovery_audit_payloads " +
            "BEGIN UPDATE next_recovery_audit_payloads SET bytes=X'00' WHERE recoveryId=NEW.recoveryId AND part=NEW.part; END")
        assertNotNull(runCatching { database.withTransaction { header(); store.write(recoveryId, sourceKey, ByteArray(70_000)) } }.exceptionOrNull())
        sql.query("SELECT COUNT(*) FROM next_recovery_audits").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        database.withTransaction { assertTrue(store.keys(recoveryId).isEmpty()) }
    }
    @Test fun cancellationPropagatesAndRollsBackHeaderInsteadOfPublishingAnEmptyReceipt() = runBlocking {
        var entered = false
        val attempt = launch {
            database.withTransaction {
                header(); entered = true; currentCoroutineContext().cancel()
                store.write(recoveryId, sourceKey, byteArrayOf(1))
                fail("Cancelled write returned successfully")
            }
        }
        attempt.join(); assertTrue(entered); assertTrue(attempt.isCancelled)
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM next_recovery_audits").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        database.withTransaction { assertTrue(store.keys(recoveryId).isEmpty()) }
    }
    @Test fun boundsAndTransactionAreRequiredNoSilentEmptyOrLargePayload() = runBlocking {
        database.withTransaction { header() }
        for (value in listOf(byteArrayOf(), ByteArray(NEXT_RECOVERY_SOURCE_BYTES + 1))) {
            assertNotNull(runCatching { database.withTransaction { store.write(recoveryId, sourceKey, value) } }.exceptionOrNull())
        }
        assertNotNull(runCatching { store.write(recoveryId, sourceKey, byteArrayOf(1)) }.exceptionOrNull())
        database.withTransaction { assertTrue(store.keys(recoveryId).isEmpty()) }
    }
    @Test fun sourceSnapshotBindsEveryOriginalSqlFieldAndKeepsNullAndWideIntegerValues() = runBlocking {
        val row = SyncOutboxEntity(id = 1, operationId = requestId, recordType = "habit", entityUuid = UUID.randomUUID().toString(),
            wireEntityUuid = UUID.randomUUID().toString(), action = "upsert", payloadJson = "{\"frozen\":true}",
            baseRevision = Int.MAX_VALUE.toLong() + 1, attemptedAt = Long.MAX_VALUE, attemptCount = 8,
            errorCode = "CONFLICT", deadLetteredAt = 123, createdAt = Long.MIN_VALUE)
        database.syncOutboxDao().insert(row)
        val hash = database.withTransaction { requireNotNull(NextRequestSql.rowHash(database.openHelper.writableDatabase,
            "sync_outbox", "id=?", arrayOf(1L))) }
        assertEquals(hash, NextRequestSql.sourceHash(row))
        val bytes = NextRecoverySourceSnapshot.encode(row, hash)
        assertEquals(row, NextRecoverySourceSnapshot.decode(bytes, hash))
        assertNotNull(runCatching { NextRecoverySourceSnapshot.encode(row.copy(attemptCount = 9), hash) }.exceptionOrNull())
        assertNotNull(runCatching { NextRecoverySourceSnapshot.decode(bytes, "f".repeat(64)) }.exceptionOrNull())
    }
    @Test fun escapedLargeValidSourceExceedsCursorWindowButRoundTripsInBoundedChunks() = runBlocking {
        val row = SyncOutboxEntity(id = 1, operationId = requestId, recordType = "habit", entityUuid = requestId,
            wireEntityUuid = requestId, action = "upsert", payloadJson = "\u0000".repeat(400_000), createdAt = 10)
        database.syncOutboxDao().insert(row)
        val hash = database.withTransaction { requireNotNull(NextRequestSql.rowHash(database.openHelper.writableDatabase,
            "sync_outbox", "id=?", arrayOf(1L))) }
        val bytes = NextRecoverySourceSnapshot.encode(row, hash)
        assertTrue(bytes.size > 2_097_152)
        val reference = database.withTransaction { header(); store.write(recoveryId, sourceKey, bytes) }
        reopen()
        val recovered = database.withTransaction { store.read(recoveryId, reference) }
        assertEquals(row, NextRecoverySourceSnapshot.decode(recovered, hash))
    }
    @Test fun sourceSnapshotsRejectMissingDefaultFieldsDuplicateKeysCoercionMalformedUtf8AndExtraFields() = runBlocking {
        val row = SyncOutboxEntity(id = 1, operationId = requestId, recordType = "habit", entityUuid = requestId,
            wireEntityUuid = requestId, action = "upsert", createdAt = 10)
        val hash = NextRequestSql.sourceHash(row)
        val text = NextRecoverySourceSnapshot.encode(row, hash).toString(Charsets.UTF_8)
        val invalid = listOf(text.replace("\"attemptCount\":0,", ""), text.replace("\"id\":1", "\"id\":\"1\""),
            text.replace("\"id\":1", "\"id\":1,\"id\":1"), text.dropLast(1) + ",\"extra\":0}")
        for (value in invalid) assertNotNull(runCatching { NextRecoverySourceSnapshot.decode(value.toByteArray(), hash) }.exceptionOrNull())
        assertNotNull(runCatching { NextRecoverySourceSnapshot.decode(byteArrayOf(0xC3.toByte(), 0x28), hash) }.exceptionOrNull())
    }
}
