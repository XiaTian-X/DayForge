package com.dayforge.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.encodeSyncRequest
import com.dayforge.data.api.dto.NextSyncOperationResult
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** D-015 file Room/DataStore + actual HTTP causal-successor regressions. */
@RunWith(AndroidJUnit4::class)
class NextStructuralCausalStoreTest : NextCoreRequestFixture() {
    @Test fun digestAndSourceSnapshotKeepTheOriginalLowercaseSqliteHashEncoding() = runBlocking<Unit> {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            nextRequestHash("abc".toByteArray(Charsets.UTF_8)))
        for (bytes in listOf(byteArrayOf(), ByteArray(256) { it.toByte() }, "中文\u0000😄".toByteArray(Charsets.UTF_8))) {
            val originalEncoding = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(originalEncoding, nextRequestHash(bytes))
        }
        val row = SyncOutboxEntity(id = 19, operationId = id(90), recordType = "habit", entityUuid = habit.uuid,
            wireEntityUuid = habit.uuid, action = "upsert", referenceUuid = HabitType.COUNTING.name, createdAt = 42)
        db.syncOutboxDao().insert(row)
        // Frozen original row encoding: column names/order, SQLite storage types and big-endian lengths/integers.
        val expected = "c2fc0c0c132d6e5d51ae189e850e1ca8245fd5e41e0148edc189f5f4d5d8e96c"
        assertEquals(expected, NextRequestSql.sourceHash(row))
        assertEquals(expected, NextRequestSql.rowHash(db.openHelper.writableDatabase, "sync_outbox", "id=?", arrayOf(row.id)))
        db.withTransaction {
            assertEquals(mapOf<Any, String?>(row.id to expected, 20L to null), NextRequestSql.boundedRowHashes(
                db.openHelper.writableDatabase, "sync_outbox", "id", listOf(row.id, 20L)))
        }
    }

    @Test fun boundedAncestorProofMatchesEveryOriginalRowAndAbsenceIncludingBlobJournals() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, _) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), a.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), b.operationId))
        val replacement = db.nextStructuralCausalDao().supersession(b.operationId)!!.replacementId
        // The optimization must not broaden auditing to unrelated, damaged historical work.
        db.nextRequestDao().insertOrigin(NextRequestOriginEntity(NEXT_OPERATION, id(914), 99999, 5,
            id(1), null, null, "unrelated-source", "unrelated-intent"))
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET protocol='bad' WHERE requestId=?", arrayOf<Any>(id(914)))
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val ids = listOf<Any>(a.operationId, b.operationId, replacement, id(909))
            for ((table, column, kind) in listOf(
                Triple("next_request_origins", "requestId", NEXT_OPERATION),
                Triple("next_transmissions", "requestId", NEXT_OPERATION),
                Triple("next_acceptances", "requestId", NEXT_OPERATION),
                Triple("next_structural_dependencies", "operationId", null),
                Triple("next_structural_supersessions", "originalId", null))) {
                val batch = requireNotNull(NextRequestSql.boundedRowHashes(sql, table, column, ids, kind))
                val expected = ids.associateWith { id ->
                    NextRequestSql.rowHash(sql, table, if (kind == null) "$column=?" else "kind=? AND requestId=?",
                        if (kind == null) arrayOf(id) else arrayOf(kind, id))
                }
                assertEquals(table, expected, batch)
            }
            sql.execSQL("UPDATE next_transmissions SET protocol='bad' WHERE requestId=?", arrayOf<Any>(replacement))
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { NextRequestSql.boundedRowHashes(sql, "next_transmissions", "requestId", ids, NEXT_OPERATION) }
                    as NextRequestException).reason)
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { NextStructuralCausalStore(db).resolve(b.operationId, access()) } as NextRequestException).reason)
            sql.execSQL("UPDATE next_transmissions SET protocol=5 WHERE requestId=?", arrayOf<Any>(replacement))
            val receipt = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, replacement))
            sql.execSQL("UPDATE next_acceptances SET resultJson=CAST(resultJson AS BLOB) WHERE requestId=?", arrayOf<Any>(replacement))
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { NextStructuralCausalStore(db).resolve(b.operationId, access()) } as NextRequestException).reason)
            sql.execSQL("UPDATE next_acceptances SET resultJson=? WHERE requestId=?", arrayOf<Any>(receipt.resultJson, replacement))
            // A consumed original queue ID does not prove that the operation has no other source.
            val alias = db.syncOutboxDao().insert(b.copy(id = 0, operationId = replacement))
            assertTrue(rejected { NextStructuralCausalStore(db).resolve(b.operationId, access()) } is IllegalArgumentException)
            assertNotNull(db.syncOutboxDao().getById(alias))
            db.syncOutboxDao().deleteById(alias)
            assertEquals(replacement, NextStructuralCausalStore(db).resolve(b.operationId, access()))
            sql.query("SELECT protocol,typeof(protocol) FROM next_request_origins WHERE requestId=?", arrayOf(id(914))).use {
                assertTrue(it.moveToFirst()); assertEquals("bad", it.getString(0)); assertEquals("text", it.getString(1))
            }
        }
    }

    @Test fun boundedProofByteBudgetFallsBackToCompleteSingleRowAuditWithoutChangingData() = runBlocking<Unit> {
        val payload = "x".repeat(750_000)
        val rows = (910..912).map { number ->
            val row = SyncOutboxEntity(operationId = id(number), recordType = "metric", entityUuid = metric.uuid,
                wireEntityUuid = metric.uuid, action = "upsert", payloadJson = payload)
            row.copy(id = db.syncOutboxDao().insert(row))
        }
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            assertNull(NextRequestSql.boundedRowHashes(sql, "sync_outbox", "id", rows.map { it.id }))
            val expected = rows.associate { it.id to NextRequestSql.sourceHash(it) }
            assertEquals(expected, NextRequestSql.sources(sql, "sync_outbox"))
            rows.forEach { row ->
                assertEquals(NextRequestSql.sourceHash(row), NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(row.id)))
            }
            sql.execSQL("UPDATE sync_outbox SET payloadJson=CAST(zeroblob(2097153) AS TEXT) WHERE id=?", arrayOf<Any>(rows[1].id))
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { NextRequestSql.boundedRowHashes(sql, "sync_outbox", "id", rows.map { it.id }) } as NextRequestException).reason)
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { NextRequestSql.sources(sql, "sync_outbox") } as NextRequestException).reason)
            sql.execSQL("UPDATE sync_outbox SET payloadJson=? WHERE id=?", arrayOf<Any>(payload, rows[1].id))
            assertEquals(expected, NextRequestSql.sources(sql, "sync_outbox"))
        }
        assertEquals(rows, db.syncOutboxDao().getAll())
    }

    @Test fun completeSourceAuditsCoverMultipleChunksAndFreshWritesForBothQueues() = runBlocking<Unit> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            for (table in listOf("sync_outbox", "timer_command_outbox")) {
                val ids = (0..128).map { index ->
                    if (table == "sync_outbox") db.syncOutboxDao().insert(SyncOutboxEntity(
                        operationId = id(1200 + index), recordType = "metric", entityUuid = metric.uuid,
                        wireEntityUuid = metric.uuid, action = "upsert", lastError = "原始 $index"))
                    else db.timeLogDao().insertTimerCommand(TimerCommandEntity(commandId = id(1500 + index),
                        sessionUuid = id(1400), sequence = index + 1, commandType = "pause",
                        occurredAt = millis, expectedControlGeneration = 1, lastError = "原始 $index"))
                }
                val expected = ids.associateWith { requireNotNull(NextRequestSql.rowHash(sql, table, "id=?", arrayOf(it))) }
                assertEquals(expected, NextRequestSql.sources(sql, table))
                assertEquals(ids, NextRequestSql.sources(sql, table).keys.toList())
                // The second chunk is still audited before Room can coerce a damaged integer.
                val last = ids.last()
                sql.execSQL("UPDATE $table SET attemptCount='bad' WHERE id=?", arrayOf<Any>(last))
                assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                    (rejected { NextRequestSql.sources(sql, table) } as NextRequestException).reason)
                sql.query("SELECT typeof(attemptCount) FROM $table WHERE id=?", arrayOf(last)).use {
                    assertTrue(it.moveToFirst()); assertEquals("text", it.getString(0))
                }
                sql.execSQL("UPDATE $table SET attemptCount=0,lastError=? WHERE id=?", arrayOf<Any>("写后变化", last))
                val changed = requireNotNull(NextRequestSql.rowHash(sql, table, "id=?", arrayOf(last)))
                assertNotEquals(expected.getValue(last), changed)
                assertEquals(expected + (last to changed), NextRequestSql.sources(sql, table))
                sql.execSQL("DELETE FROM $table WHERE id=?", arrayOf<Any>(last))
                assertEquals(expected.minus(last), NextRequestSql.sources(sql, table))
                assertEquals(last, NextRequestSql.watermark(sql, table))
            }
        }
        storage.reopen()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            assertEquals(128, NextRequestSql.sources(sql, "sync_outbox").size)
            assertEquals(128, NextRequestSql.sources(sql, "timer_command_outbox").size)
        }
        assertEquals(0, count("next_transmissions")); assertEquals(0, count("next_acceptances"))
    }

    @Test fun threeOfflineSameFieldSuccessorsUseOriginalLocalBaselinesAndCanonicalRemoteOnlyFields() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); val c = editMetric("C")
        // The real metric trigger correctly emits nothing for identical editable fields. Allocate
        // an explicit NEW no-op source through the actual producer, never reuse/relabel c's identity.
        producer().write(local()) {
            db.syncOutboxDao().insert(c.copy(id = 0, operationId = java.util.UUID.randomUUID().toString()))
        }
        val d = db.syncOutboxDao().getAll().last()
        assertEquals(4, listOf(a, b, c, d).map { it.operationId }.toSet().size)
        val originals = listOf(a, b, c, d).associateWith { originalIntent(it) }
        register()
        var revision = 0L
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val op = wireOperation(input)
                if (revision == 0L) assertEquals(JsonNull, op["base_revision"])
                else assertEquals(revision, op.getValue("base_revision").jsonPrimitive.long)
                successReply(input, ++revision) { JsonObject(it + ("description" to JsonPrimitive("Remote-only description"))) }
            }
        }
        for (row in listOf(a, b, c, d)) {
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
            assertEquals("C", db.metricDao().getMetricById(metric.id)!!.name)
            assertEquals(originals.getValue(row), originalIntent(row))
            storage.reopen()
        }
        assertEquals("Remote-only description", db.metricDao().getMetricById(metric.id)!!.description)
        assertEquals(0, count("sync_outbox")); assertEquals(4, count("next_acceptances")); assertEquals(3, count("next_structural_supersessions"))
        for (row in listOf(b, c, d)) {
            assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId))
            assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
            val replacement = db.nextStructuralCausalDao().supersession(row.operationId)!!
            assertNotEquals(row.operationId, replacement.replacementId)
            assertEquals(row.id, replacement.originalQueueId)
            assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, replacement.replacementId))
            assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
        assertEquals(4, server.requests.count { it.path.endsWith("/push") })
        assertEquals(0L, tokens.syncCursor.first())
        val second = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val third = db.nextStructuralCausalDao().supersession(c.operationId)!!
        val last = db.nextStructuralCausalDao().supersession(d.operationId)!!
        val secondReceipt = db.nextRequestDao().acceptance(NEXT_OPERATION, second.replacementId)!!
        val captured = access()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            val coordinator = NextStructuralCausalStore(db)
            assertEquals(last.replacementId, coordinator.resolve(d.operationId, captured))
            val result = Json.parseToJsonElement(secondReceipt.resultJson).jsonObject
            val damaged = JsonObject(result + ("entity" to JsonObject(result.getValue("entity").jsonObject +
                ("name" to JsonPrimitive(""))))).toString()
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(damaged, nextRequestHash(damaged.toByteArray(Charsets.UTF_8)), NEXT_OPERATION, second.replacementId))
            val changedHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
                arrayOf(NEXT_OPERATION, second.replacementId))!!
            sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                arrayOf<Any>(changedHash, c.operationId))
            // The next traversal must reach the damaged intermediate receipt, even after a full
            // warm traversal shared adjacent parent receipts. A descendant's valid ACK is insufficient.
            assertNotNull(rejected { coordinator.resolve(d.operationId, captured) })
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(secondReceipt.resultJson, secondReceipt.resultHash, NEXT_OPERATION, second.replacementId))
            sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                arrayOf<Any>(third.predecessorAcceptanceHash, c.operationId))
            assertEquals(last.replacementId, coordinator.resolve(d.operationId, captured))
        }
        assertEquals(secondReceipt, db.nextRequestDao().acceptance(NEXT_OPERATION, second.replacementId))
        assertEquals(third, db.nextStructuralCausalDao().supersession(c.operationId))
        assertEquals(4, server.requests.count { it.path.endsWith("/push") })
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun logicalOrderSurvivesPhysicalReplacementAndNewEditDuringHttp() = runBlocking<Unit> {
        register()
        val a = editMetric("A"); val b = editMetric("B"); val c = editMetric("C")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var revision = 0L
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else {
                val rev = ++revision
                if (rev == 2L) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                successReply(input, rev)
            }
        }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId)
        coroutineScope {
            val pending = async(Dispatchers.IO) { store.sendAndAcceptOperation(access(), b.operationId) }
            val d: SyncOutboxEntity
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val replacement = db.nextStructuralCausalDao().supersession(b.operationId)!!
                assertTrue(replacement.replacementQueueId > c.id)
                d = withTimeout(3000) { editMetric("D") }
                assertEquals(c.operationId, db.nextStructuralCausalDao().dependency(d.operationId)!!.predecessorId)
                assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
                    (rejected { store.sendOperation(access(), c.operationId) } as NextRequestException).reason)
            } finally { release.countDown() }
            assertEquals(NextOperationAcceptance.COMMITTED, pending.await())
            assertEquals("D", db.metricDao().getMetricById(metric.id)!!.name)
            store.sendAndAcceptOperation(access(), c.operationId)
            assertEquals("D", db.metricDao().getMetricById(metric.id)!!.name)
            store.sendAndAcceptOperation(access(), d.operationId)
        }
        assertEquals("D", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0, count("sync_outbox")); assertEquals(4, count("next_acceptances"))
    }

    @Test fun genuineOverlapKeepsOriginalAndDoesNotBlockUnrelatedFact() = runBlocking<Unit> {
        val a = editMetric("Local A"); val b = editMetric("Local B"); val originB = originalIntent(b)
        register()
        val (http, server) = channel { input -> successReply(input) { body ->
            if (body["name"] != null) JsonObject(body + ("name" to JsonPrimitive("Server name"))) else body
        } }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId)
        val error = rejected { store.sendAndAcceptOperation(access(), b.operationId) }
        assertEquals(listOf("name"), (error as NextStructuralCausalConflict).fields)
        assertEquals(b, db.syncOutboxDao().getById(b.id)); assertEquals(originB, originalIntent(b))
        assertEquals("Local B", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0, count("next_structural_supersessions")); assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, b.operationId))
        val fact = observation()
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), fact.operationId))
        assertEquals(2, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun concurrentFirstSendCreatesOneReplacementAndColdOriginalReceiptNeedsNoNetwork() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId)
        val deliveries = coroutineScope { List(2) { async(Dispatchers.IO) { requireNotNull(store.sendOperation(access(), b.operationId)) } }.awaitAll() }
        assertEquals(1, deliveries.map { it.requestId }.distinct().size)
        assertNotEquals(b.operationId, deliveries.first().requestId)
        assertEquals(1, count("next_structural_supersessions"))
        val pushes = server.requests.filter { it.path.endsWith("/push") }
        assertArrayEquals(pushes[1].body, pushes[2].body)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(deliveries.first()))
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(deliveries.last()))
        storage.reopen()
        val countBefore = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        assertEquals(countBefore, server.requests.size)
    }

    @Test fun replacementResponseLossAndColdRetryKeepActualIdAndExactBytes() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); val c = editMetric("C"); register()
        var dropped = false
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else if (wireOrIdentity(input) == a.operationId) successReply(input)
            else if (!dropped) { dropped = true; null } else successReply(input, 2)
        }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId)
        assertTrue(rejected { store.sendAndAcceptOperation(access(), b.operationId) } is IOException)
        val replacement = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val originalBytes = transmission(NEXT_OPERATION, replacement.replacementId).wireBytes.copyOf()
        assertEquals(0, db.syncOutboxDao().getById(replacement.replacementQueueId)!!.attemptCount)
        storage.reopen()
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendOperation(access(), c.operationId) } as NextRequestException).reason)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        assertEquals(replacement, db.nextStructuralCausalDao().supersession(b.operationId))
        assertArrayEquals(originalBytes, transmission(NEXT_OPERATION, replacement.replacementId).wireBytes)
        val pushes = server.requests.filter { it.path.endsWith("/push") }
        assertEquals(3, pushes.size); assertArrayEquals(pushes[1].body, pushes[2].body)
        assertEquals("C", db.metricDao().getMetricById(metric.id)!!.name)
    }

    @Test fun knownDeviceAndReplicaChangesNeverRebindOfflineSuccessor() = runBlocking<Unit> {
        register(); val a = editMetric("A"); val b = editMetric("B")
        val (http, server) = channel { successReply(it) }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId)
        register(device = id(99))
        assertEquals(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED,
            (rejected { store.sendAndAcceptOperation(access(), b.operationId) } as NextRequestException).reason)
        assertEquals(b, db.syncOutboxDao().getById(b.id)); assertEquals(0, count("next_structural_supersessions"))
        register()
        tokens.saveServerIdentity(id(2), id(99))
        assertEquals(NextRequestException.Reason.TRANSMISSION_CONTEXT_CHANGED,
            (rejected { store.sendAndAcceptOperation(access(), b.operationId) } as NextRequestException).reason)
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun causalCaptureFaultsRollbackBusinessSourceOriginAndDependency() = runBlocking<Unit> {
        val faults = listOf("BEFORE INSERT ON next_structural_dependencies BEGIN SELECT RAISE(ABORT,'capture failed'); END",
            "BEFORE INSERT ON next_structural_dependencies BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_structural_dependencies BEGIN UPDATE sync_outbox SET lastError='rewritten'; END",
            "AFTER INSERT ON next_structural_dependencies BEGIN UPDATE next_request_origins SET sourceHash='rewritten'; END",
            "AFTER INSERT ON next_structural_dependencies BEGIN UPDATE next_structural_dependencies SET originHash='rewritten'; END")
        for (fault in faults) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER causal_capture_fault $fault")
            assertNotNull(rejected { editMetric("must rollback") })
            assertEquals(metric, db.metricDao().getMetricById(metric.id))
            assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins")); assertEquals(0, count("next_structural_dependencies"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER causal_capture_fault")
        }
        editMetric("real edit"); assertEquals(1, count("next_structural_dependencies"))
    }

    @Test fun replacementInsertRetirementAndLateTransmissionFaultsRollbackWithoutBusinessHttp() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http); store.sendAndAcceptOperation(access(), a.operationId)
        val origin = originalIntent(b); val parentOrigin = originalIntent(a)
        val parentReceipt = db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId)
        val faults = listOf("BEFORE INSERT ON next_structural_supersessions BEGIN SELECT RAISE(ABORT,'replacement failed'); END",
            "BEFORE INSERT ON next_structural_supersessions BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_structural_supersessions BEGIN UPDATE next_structural_supersessions SET originalSourceHash='rewritten'; END",
            "AFTER INSERT ON next_structural_supersessions BEGIN UPDATE next_acceptances SET resultHash='rewritten'; END",
            "BEFORE DELETE ON sync_outbox BEGIN SELECT RAISE(IGNORE); END",
            "AFTER INSERT ON next_transmissions BEGIN UPDATE next_structural_supersessions SET deviceId='rewritten'; END",
            "AFTER INSERT ON next_transmissions BEGIN UPDATE next_request_origins SET intentJson=replace(intentJson,'\"name\":\"B\"','\"name\":\"Changed\"'); END",
            "AFTER INSERT ON next_transmissions BEGIN UPDATE next_request_origins SET intentJson=replace(intentJson,'\"name\":\"A\"','\"name\":\"Changed\"'); END")
        for (fault in faults) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER causal_replace_fault $fault")
            assertNotNull(rejected { store.sendAndAcceptOperation(access(), b.operationId) })
            assertEquals(b, db.syncOutboxDao().getById(b.id)); assertEquals(origin, originalIntent(b))
            assertEquals(parentOrigin, originalIntent(a))
            assertEquals(parentReceipt, db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
            assertEquals(0, count("next_structural_supersessions")); assertEquals(1, count("next_transmissions"))
            assertEquals(1, server.requests.count { it.path.endsWith("/push") })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER causal_replace_fault")
        }
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), b.operationId))
    }

    @Test fun replacementFinalCommitFailureColdReopenRetainsOriginalAndAllowsRealRetry() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http); store.sendAndAcceptOperation(access(), a.operationId)
        val origin = originalIntent(b)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE causal_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE causal_child(id INTEGER REFERENCES causal_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER causal_commit_fault AFTER INSERT ON next_structural_supersessions BEGIN INSERT INTO causal_child VALUES(1); END")
        assertTrue(rejected { store.sendAndAcceptOperation(access(), b.operationId) } is SQLiteConstraintException)
        storage.reopen()
        assertEquals(b, db.syncOutboxDao().getById(b.id)); assertEquals(origin, originalIntent(b))
        assertEquals(0, count("causal_child")); assertEquals(0, count("next_structural_supersessions")); assertEquals(1, count("next_transmissions"))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER causal_commit_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        assertEquals(2, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun unprovenSameEntityPredecessorIsNotAdoptedAndLocalEditRollsBack() = runBlocking<Unit> {
        val old = SyncOutboxEntity(operationId = id(901), recordType = "metric", entityUuid = metric.uuid,
            wireEntityUuid = metric.uuid, action = "upsert")
        val row = old.copy(id = db.syncOutboxDao().insert(old))
        assertNotNull(rejected { editMetric("must rollback") })
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(listOf(row), db.syncOutboxDao().getAll())
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("next_structural_dependencies"))
    }

    @Test fun existingZeroAttemptJournalAlwaysReplaysOriginalEvenAfterParentConfirmation() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val captured = access()
        val original = originalIntent(b)
        val operation = com.dayforge.data.api.decodeFrozenSyncRequest(original.intentJson.toByteArray(),
            com.dayforge.data.api.dto.SyncV2Operation.serializer())
        val bytes = com.dayforge.data.api.encodeSyncRequest(com.dayforge.data.api.dto.NextSyncPushRequest.serializer(),
            com.dayforge.data.api.dto.NextSyncPushRequest(captured.deviceId!!, listOf(operation)))
        // A persisted historical/unknown transmission is evidence even with zero queue attempts.
        db.nextRequestDao().insertTransmission(NextTransmissionEntity(NEXT_OPERATION, b.operationId, b.id, 5,
            captured.session.authentication.userId, captured.session.serverInstanceId!!, captured.session.syncEpoch!!,
            captured.deviceId, nextRequestHash(bytes), bytes))
        val (http, server) = channel { input -> if (wireOrIdentity(input) == a.operationId || input.path.endsWith("/identity"))
            successReply(input) else reply(input) }
        val store = sender(http); store.sendAndAcceptOperation(captured, a.operationId)
        val delivery = requireNotNull(store.sendOperation(captured, b.operationId))
        assertEquals(b.operationId, delivery.requestId)
        assertEquals("rejected", delivery.result.results.single().status)
        assertNull(db.nextStructuralCausalDao().supersession(b.operationId)); assertEquals(b, db.syncOutboxDao().getById(b.id))
        assertArrayEquals(bytes, server.requests.last { it.path.endsWith("/push") }.body)
        storage.reopen()
        sender(http).sendOperation(captured, b.operationId)
        assertArrayEquals(bytes, server.requests.last { it.path.endsWith("/push") }.body)
        assertEquals(0, count("next_structural_supersessions"))
    }

    @Test fun corruptSupersessionOrDependencyCannotAuthorizeColdReceiptReplay() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http)
        store.sendAndAcceptOperation(access(), a.operationId); store.sendAndAcceptOperation(access(), b.operationId)
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val dep = db.nextStructuralCausalDao().dependency(b.operationId)!!
        val countBefore = server.requests.size
        for ((field, value) in listOf("sourceSnapshotHash" to sup.sourceSnapshotHash, "originalSourceHash" to sup.originalSourceHash,
            "predecessorAcceptanceHash" to sup.predecessorAcceptanceHash, "replacementOriginHash" to sup.replacementOriginHash,
            "deviceId" to sup.deviceId, "originalDependencyHash" to sup.originalDependencyHash)) {
            db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET $field=CAST($field AS BLOB) WHERE originalId=?", arrayOf<Any>(b.operationId))
            storage.reopen()
            assertNotNull(rejected { sender(http).sendAndAcceptOperation(access(), b.operationId) })
            assertEquals(countBefore, server.requests.size)
            db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET $field=? WHERE originalId=?", arrayOf<Any>(value, b.operationId))
        }
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_dependencies SET predecessorOriginHash='bad' WHERE operationId=?", arrayOf<Any>(b.operationId))
        assertNotNull(rejected { sender(http).sendAndAcceptOperation(access(), b.operationId) })
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_dependencies SET predecessorOriginHash=? WHERE operationId=?",
            arrayOf<Any>(dep.predecessorOriginHash!!, b.operationId))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        assertEquals(countBefore, server.requests.size)
        db.clearAllData(); storage.reopen()
        assertEquals(0, count("next_structural_supersessions")); assertEquals(0, count("next_structural_dependencies"))
        assertFalse(db.nextRequestDao().hasAny())
    }

    @Test fun replacementAcceptanceFaultRollsBackReceiptAndKeepsExactPreparedReplacement() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, _) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http); store.sendAndAcceptOperation(access(), a.operationId)
        val delivery = requireNotNull(store.sendOperation(access(), b.operationId))
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val source = db.syncOutboxDao().getById(sup.replacementQueueId)
        val bytes = transmission(NEXT_OPERATION, delivery.requestId).wireBytes.copyOf()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER causal_accept_fault AFTER INSERT ON next_acceptances BEGIN UPDATE next_structural_supersessions SET originalSourceHash='bad'; END")
        assertNotNull(rejected { store.acceptOperation(delivery) })
        storage.reopen()
        assertEquals(sup, db.nextStructuralCausalDao().supersession(b.operationId)); assertEquals(source, db.syncOutboxDao().getById(sup.replacementQueueId))
        assertArrayEquals(bytes, transmission(NEXT_OPERATION, delivery.requestId).wireBytes)
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, delivery.requestId))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER causal_accept_fault")
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
    }

    @Test fun oneTransactionCapturesThirtyTwoOrderedOriginsWithoutGuessingIntermediateProjection() = runBlocking<Unit> {
        val started = System.nanoTime()
        producer().write(local()) {
            repeat(32) { index -> metrics().updateMetric(db.metricDao().getMetricById(metric.id)!!.copy(name = "Batch $index")) }
        }
        val rows = db.syncOutboxDao().getAll()
        assertEquals(32, rows.size)
        rows.forEachIndexed { index, row ->
            val dep = db.nextStructuralCausalDao().dependency(row.operationId)!!
            assertEquals(row.id, dep.logicalOrder)
            assertEquals(rows.getOrNull(index - 1)?.operationId, dep.predecessorId)
            val original = originalIntent(row)
            assertEquals(JsonPrimitive("Batch 31"), Json.parseToJsonElement(original.intentJson).jsonObject.getValue("payload").jsonObject["name"])
        }
        storage.reopen(); register()
        var revision = 0L
        val (http, server) = channel { input -> if (input.path.endsWith("/identity")) reply(input) else successReply(input, ++revision) }
        val store = sender(http)
        for ((index, row) in rows.withIndex()) {
            assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), row.operationId))
            android.util.Log.i("DayForgeCausalRegression", "Confirmed ${index + 1}/32 after ${(System.nanoTime() - started) / 1_000_000}ms")
        }
        assertEquals("Batch 31", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(32, count("next_acceptances")); assertEquals(31, count("next_structural_supersessions")); assertEquals(0, count("sync_outbox"))
        assertEquals(32, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun sameCoordinatorNeverReusesProofRowsOrChangedJsonAcrossWrites() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), a.operationId))
        val delivery = requireNotNull(store.sendOperation(access(), b.operationId))
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val origin = originalIntent(b)
        val parentReceipt = db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId)!!
        val captured = access()
        val before = server.requests.size
        db.withTransaction {
            val coordinator = NextStructuralCausalStore(db)
            val sql = db.openHelper.writableDatabase
            assertEquals(sup.replacementId, coordinator.resolve(b.operationId, captured))
            // A warm pure-result/merge memo cannot certify changed authority or changed receipt bytes.
            assertNotNull(rejected { coordinator.resolve(b.operationId, captured.copy(deviceId = id(999))) })
            val parentResult = Json.parseToJsonElement(parentReceipt.resultJson).jsonObject
            val parentEntity = parentResult.getValue("entity").jsonObject
            for (changedEntity in listOf(
                JsonObject(parentEntity + ("decimal_places" to JsonPrimitive("3"))),
                JsonObject(parentEntity + ("description" to JsonPrimitive("New remote description"))))) {
                val changedJson = JsonObject(parentResult + ("entity" to changedEntity)).toString()
                assertNotEquals(parentReceipt.resultJson, changedJson)
                sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                    arrayOf<Any>(changedJson, nextRequestHash(changedJson.toByteArray(Charsets.UTF_8)), NEXT_OPERATION, a.operationId))
                val changedHash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?",
                    arrayOf(NEXT_OPERATION, a.operationId))!!
                // Keep the raw binding coherent, so rejection must reach the changed semantic input.
                sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                    arrayOf<Any>(changedHash, b.operationId))
                assertNotNull(rejected { coordinator.resolve(b.operationId, captured) })
                sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                    arrayOf<Any>(parentReceipt.resultJson, parentReceipt.resultHash, NEXT_OPERATION, a.operationId))
                sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                    arrayOf<Any>(sup.predecessorAcceptanceHash, b.operationId))
                assertEquals(sup.replacementId, coordinator.resolve(b.operationId, captured))
            }
            // Warm strict parsing, then change valid raw content on the SAME coordinator/transaction.
            // Coherent snapshot hashing must not turn a typed-string attempt count into valid input.
            val invalidSnapshot = sup.sourceSnapshotJson.replace("\"attemptCount\":0", "\"attemptCount\":\"0\"")
            assertNotEquals(sup.sourceSnapshotJson, invalidSnapshot)
            sql.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotJson=?,sourceSnapshotHash=? WHERE originalId=?",
                arrayOf<Any>(invalidSnapshot, nextRequestHash(invalidSnapshot.toByteArray(Charsets.UTF_8)), b.operationId))
            assertNotNull(rejected { coordinator.resolve(b.operationId, captured) })
            sql.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotJson=?,sourceSnapshotHash=? WHERE originalId=?",
                arrayOf<Any>(sup.sourceSnapshotJson, sup.sourceSnapshotHash, b.operationId))
            assertEquals(sup.replacementId, coordinator.resolve(b.operationId, captured))
            sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(origin.intentJson.replace("\"name\":\"B\"", "\"name\":\"Changed\""), NEXT_OPERATION, b.operationId))
            assertNotNull(rejected { coordinator.resolve(b.operationId, captured) })
            sql.execSQL("UPDATE next_request_origins SET intentJson=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(origin.intentJson, NEXT_OPERATION, b.operationId))
            assertEquals(sup.replacementId, coordinator.resolve(b.operationId, captured))
            sql.execSQL("UPDATE next_structural_dependencies SET logicalOrder='bad' WHERE operationId=?", arrayOf<Any>(b.operationId))
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
                (rejected { coordinator.resolve(b.operationId, captured) } as NextRequestException).reason)
            sql.execSQL("UPDATE next_structural_dependencies SET logicalOrder=? WHERE operationId=?", arrayOf<Any>(b.id, b.operationId))
            assertEquals(sup.replacementId, coordinator.resolve(b.operationId, captured))
        }
        assertEquals(before, server.requests.size)
        assertEquals(sup, db.nextStructuralCausalDao().supersession(b.operationId))
        assertEquals(origin, originalIntent(b))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
    }

    @Test fun pureMemoNeverReusesDatabaseProofsAcrossTransactions() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http)
        assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), a.operationId))
        val delivery = requireNotNull(store.sendOperation(access(), b.operationId))
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId)!!
        val captured = access()
        val memo = NextStructuralCausalStore.PureMemo()
        val networkBefore = server.requests.size
        suspend fun resolve(context: LocalSyncAccess = captured) = db.withTransaction {
            NextStructuralCausalStore(db, memo).resolve(b.operationId, context)
        }
        assertEquals(sup.replacementId, resolve())
        db.withTransaction {
            val result = Json.parseToJsonElement(receipt.resultJson).jsonObject
            val changed = JsonObject(result + ("entity" to JsonObject(result.getValue("entity").jsonObject +
                ("description" to JsonPrimitive("Changed between transactions"))))).toString()
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(changed, nextRequestHash(changed.toByteArray(Charsets.UTF_8)), NEXT_OPERATION, a.operationId))
            val hash = NextRequestSql.rowHash(sql, "next_acceptances", "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, a.operationId))!!
            sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                arrayOf<Any>(hash, b.operationId))
        }
        assertNotNull(rejected { resolve() })
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(receipt.resultJson, receipt.resultHash, NEXT_OPERATION, a.operationId))
            sql.execSQL("UPDATE next_structural_supersessions SET predecessorAcceptanceHash=? WHERE originalId=?",
                arrayOf<Any>(sup.predecessorAcceptanceHash, b.operationId))
        }
        assertEquals(NextRequestException.Reason.PERMISSION_DENIED,
            (rejected { resolve(captured.copy(capabilities = setOf("sync.read"))) } as NextRequestException).reason)
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET originalQueueId='bad' WHERE originalId=?",
            arrayOf<Any>(b.operationId))
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (rejected { resolve() } as NextRequestException).reason)
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET originalQueueId=? WHERE originalId=?",
            arrayOf<Any>(sup.originalQueueId, b.operationId))
        assertEquals(sup.replacementId, resolve())
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, a.operationId))
        assertEquals(sup, db.nextStructuralCausalDao().supersession(b.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(networkBefore, server.requests.size)
        assertEquals(0, count("sync_outbox"))
    }

    @Test fun overLimitCausalSnapshotIsRejectedBeforeRoomMaterializesItOrSendsBusiness() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val (http, server) = channel { successReply(it, if (wireOrIdentity(it) == a.operationId) 1 else 2) }
        val store = sender(http); store.sendAndAcceptOperation(access(), a.operationId)
        val delivery = requireNotNull(store.sendOperation(access(), b.operationId))
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val before = server.requests.size
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotJson=CAST(zeroblob(2097153) AS TEXT) WHERE originalId=?", arrayOf<Any>(b.operationId))
        storage.reopen()
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
            (rejected { sender(http).sendAndAcceptOperation(access(), b.operationId) } as NextRequestException).reason)
        assertEquals(before, server.requests.size)
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_supersessions SET sourceSnapshotJson=? WHERE originalId=?",
            arrayOf<Any>(sup.sourceSnapshotJson, b.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertEquals(sup, db.nextStructuralCausalDao().supersession(b.operationId))
    }

    @Test fun rawCausalIntegerAndSourceLengthChecksRejectBeforeRoomCoercion() = runBlocking<Unit> {
        val a = editMetric("A"); register()
        val (http, server) = channel { successReply(it) }
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_dependencies SET logicalOrder='not-an-integer' WHERE operationId=?", arrayOf<Any>(a.operationId))
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
            (rejected { sender(http).sendAndAcceptOperation(access(), a.operationId) } as NextRequestException).reason)
        db.openHelper.writableDatabase.execSQL("UPDATE next_structural_dependencies SET logicalOrder=? WHERE operationId=?", arrayOf<Any>(a.id, a.operationId))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET payloadJson=CAST(zeroblob(2097153) AS TEXT) WHERE id=?", arrayOf<Any>(a.id))
        assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE,
            (rejected { sender(http).sendAndAcceptOperation(access(), a.operationId) } as NextRequestException).reason)
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET payloadJson=NULL WHERE id=?", arrayOf<Any>(a.id))
        assertTrue(server.requests.all { it.path.endsWith("/identity") })
        assertEquals(0, count("next_transmissions")); assertEquals(a, db.syncOutboxDao().getById(a.id))
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), a.operationId))
    }

    @Test fun cancellingUnknownReplacementKeepsItsFrozenBytesForColdRetry() = runBlocking<Unit> {
        val a = editMetric("A"); val b = editMetric("B"); register()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blockFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else if (wireOrIdentity(input) == a.operationId) successReply(input)
            else {
                val result = successReply(input, 2)
                if (blockFirst.getAndSet(false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                result.copy(allowClientClose = true)
            }
        }
        val store = sender(http); store.sendAndAcceptOperation(access(), a.operationId)
        val captured = access()
        val job = launch(Dispatchers.IO) { store.sendAndAcceptOperation(captured, b.operationId) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled && job.isCompleted)
        } finally { release.countDown(); job.cancelAndJoin() }
        val sup = db.nextStructuralCausalDao().supersession(b.operationId)!!
        val bytes = transmission(NEXT_OPERATION, sup.replacementId).wireBytes.copyOf()
        assertNotNull(db.syncOutboxDao().getById(sup.replacementQueueId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, sup.replacementId))
        storage.reopen()
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), b.operationId))
        assertEquals(sup, db.nextStructuralCausalDao().supersession(b.operationId))
        val pushes = server.requests.filter { it.path.endsWith("/push") }
        assertEquals(3, pushes.size); assertArrayEquals(bytes, pushes[1].body); assertArrayEquals(bytes, pushes[2].body)
        assertEquals(0L, tokens.syncCursor.first())
    }

    @Test fun habitAndLinkSuccessorsUseTheSameDurableReplacementAndCanonicalEndpointRules() = runBlocking<Unit> {
        producer().write(local()) { habits().updateHabit(habit.copy(name = "Habit A")) }
        val habitA = db.syncOutboxDao().getAll().single()
        producer().write(local()) { habits().updateHabit(db.habitDao().getHabitById(habit.id)!!.copy(name = "Habit B")) }
        val habitB = db.syncOutboxDao().getAll().last()
        producer().write(local()) { metrics().linkHabits(metric, setOf(habit.id)) }
        val linkA = db.syncOutboxDao().getAll().last()
        val link = db.habitMetricLinkDao().getLinkByUuid(linkA.entityUuid)!!
        producer().write(local()) { db.habitMetricLinkDao().upsert(link.copy(promptOnComplete = false)) }
        val linkB = db.syncOutboxDao().getAll().last()
        register()
        val (http, _) = channel { input -> successReply(input,
            if (wireOrIdentity(input) in setOf(habitA.operationId, linkA.operationId)) 1 else 2) }
        val store = sender(http)
        for (row in listOf(habitA, habitB, linkA, linkB))
            assertEquals(NextOperationAcceptance.COMMITTED, store.sendAndAcceptOperation(access(), row.operationId))
        assertEquals("Habit B", db.habitDao().getHabitById(habit.id)!!.name)
        val saved = db.habitMetricLinkDao().getLinkByUuid(link.uuid)!!
        assertFalse(saved.promptOnComplete); assertEquals(habit.uuid, saved.habitUuid); assertEquals(metric.uuid, saved.metricUuid)
        assertEquals(habit.id, saved.habitId); assertEquals(metric.id, saved.metricId)
        assertEquals(2, count("next_structural_supersessions")); assertEquals(4, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun coherentlyHashedRootStructuralReceiptStillRequiresACompleteValidCanonicalEntity() = runBlocking<Unit> {
        val row = editMetric("Root"); register()
        val (http, server) = channel { successReply(it) }
        val store = sender(http)
        val delivery = requireNotNull(store.sendOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(0, count("next_structural_supersessions"))
        val receipt = db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!
        val result = Json.decodeFromString<NextSyncOperationResult>(receipt.resultJson)
        val entity = requireNotNull(result.entity)
        val shadow = db.syncOutboxDao().getState("metric", metric.uuid)
        val projection = db.metricDao().getMetricById(metric.id)
        val requestsBefore = server.requests.size
        for (invalid in listOf(JsonObject(entity + ("decimal_places" to JsonPrimitive("3"))),
            JsonObject(entity.minus("updated_at")))) {
            val badResult = result.copy(entity = invalid)
            val badBytes = encodeSyncRequest(NextSyncOperationResult.serializer(), badResult)
            val badJson = badBytes.toString(Charsets.UTF_8)
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(badJson, nextRequestHash(badBytes), NEXT_OPERATION, row.operationId))
            storage.reopen()
            val replayStore = sender(http)
            // No replacement/child can incidentally validate the root; both actual replay entries
            // must reject the complete-but-invalid entity even when its stored digest is coherent.
            assertTrue(rejected { replayStore.sendAndAcceptOperation(access(), row.operationId) } is IllegalArgumentException)
            assertTrue(rejected {
                replayStore.acceptOperation(delivery.copy(result = NextSyncPushResponse(listOf(badResult))))
            } is IllegalArgumentException)
            assertEquals(badJson, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)!!.resultJson)
            assertEquals(projection, db.metricDao().getMetricById(metric.id))
            assertEquals(shadow, db.syncOutboxDao().getState("metric", metric.uuid))
            assertEquals(0, count("sync_outbox")); assertEquals(0L, tokens.syncCursor.first())
            assertEquals(requestsBefore, server.requests.size)
            db.openHelper.writableDatabase.execSQL("UPDATE next_acceptances SET resultJson=?,resultHash=? WHERE kind=? AND requestId=?",
                arrayOf<Any>(receipt.resultJson, receipt.resultHash, NEXT_OPERATION, row.operationId))
        }
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(requestsBefore, server.requests.size)
        assertEquals(receipt, db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
    }
}
