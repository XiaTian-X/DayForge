package com.dayforge.data.repository

import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0MetricBlockTest : G0DomainFixture() {
    @Test fun editOutboxAckAndReplayPublishOnlyCommittedCalculationBlocks() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Block metric", updatedAt = millis)) }
        val localView = requireNotNull(blocks.publishedForTest())
        assertFalse(db.inTransaction()); assertEquals("Block metric", localView.definition.value.name)
        assertEquals(1, localView.sync.value.pending); assertEquals(1, count("next_request_origins"))
        val row = db.syncOutboxDao().getAll().single()
        val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val trace = G0PhaseTrace(driverTiming = false)
        trace.run { assertEquals(NextOperationAcceptance.COMMITTED, runtime.acknowledge(delivery)) }
        assertEquals(2, trace.count("decrypt_access")); assertEquals(0, trace.count("decrypt_refresh"))
        assertEquals(2, trace.count("datastore_read")); assertEquals(1, trace.count("tx_end_outer"))
        val committed = requireNotNull(blocks.publishedForTest())
        assertSame("ACK only changes server timestamps, not definition display", localView.definition, committed.definition)
        assertEquals(0, committed.sync.value.pending); assertEquals(1L, committed.sync.value.revision)
        assertEquals(localView.sync.revision + 1, committed.sync.revision)
        assertEquals(committed, runtime.read(access()))
        val requests = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, runtime.acknowledge(delivery))
        assertSame(committed.definition, blocks.publishedForTest()!!.definition)
        assertSame(committed.sync, blocks.publishedForTest()!!.sync)
        assertEquals(1, count("next_acceptances")); assertEquals(requests, server.requests.size)
        Log.i("G0MetricBlocks", "case=edit-outbox-ack-replay committed=true fresh_auth=2 definition_reused=true")
    }

    @Test fun successorDefinitionSurvivesOldAckAndBothDependenciesRemainExact() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "First", updatedAt = millis)) }
        val row = db.syncOutboxDao().getAll().single(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        runtime.write(local()) { db.metricDao().update(db.metricDao().getMetricById(metric.id)!!.copy(name = "Later", updatedAt = millis + 1)) }
        val later = blocks.publishedForTest()!!
        assertEquals(2, later.sync.value.pending)
        assertEquals(NextOperationAcceptance.COMMITTED, runtime.acknowledge(delivery))
        val actual = runtime.read(access())
        assertSame(later.definition, actual.definition); assertEquals("Later", actual.definition.value.name)
        assertEquals(1, actual.sync.value.pending); assertEquals(2, count("next_request_origins"))
        assertEquals(1, count("next_acceptances")); assertEquals(1, count("sync_outbox"))
    }

    @Test fun rawSuccessorRewriteCannotHideBehindTruePendingBoolean() = runBlocking<Unit> {
        register(); val row = editMetric("Sent"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val successor = editMetric("Successor")
        val change = NextOrdinaryResultMapper.validate(decodeNextOperationIntent(originalIntent(row).intentJson),
            delivery.result.results.single(), delivery.access.deviceId!!)
        val blocks = G0MetricBlocks(db, metric.uuid)
        db.withTransaction {
            val request = G0MetricBlocks.Request(row.id, row.operationId)
            val before = blocks.capture(request)
            val legacyPredicate = G0MetricMergePlan.freeze(change, before.input(metric.uuid, row.id), millis)
            val prepared = blocks.prepare(change, request, millis)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET referenceUuid=? WHERE id=?", arrayOf<Any>(id(911), successor.id))
            val after = blocks.capture(request)
            // Retain the counterexample: Boolean true -> true misses an actual changed source.
            legacyPredicate.revalidate(after.input(metric.uuid, row.id))
            assertEquals("G0_BLOCK_REQUEST_DEPENDENCIES_CHANGED", rejected { prepared.revalidate(after) }.message)
            Log.i("G0MetricBlocks", "case=successor-rewrite legacy_predicate_missed=true exact_block_rejected=true")
        }
    }

    @Test fun nameAbsenceSourceReceiptAndUnrelatedRangesAreDistinguished() = runBlocking<Unit> {
        register(); val row = editMetric("Sent"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val base = NextOrdinaryResultMapper.validate(decodeNextOperationIntent(originalIntent(row).intentJson),
            delivery.result.results.single(), delivery.access.deviceId!!)
        val change = base.copy(payload = JsonObject(base.payload + ("name" to JsonPrimitive("Proposed"))))
        val image = storage.snapshotClosedDatabase()
        for (case in listOf("unrelated", "name", "source", "transmission", "receipt", "queue-membership", "queue-removal", "shadow")) {
            storage.restoreClosedDatabase(image); storage.reopen()
            val blocks = G0MetricBlocks(db, metric.uuid)
            db.withTransaction {
                val key = G0MetricBlocks.Request(row.id, row.operationId)
                val prepared = blocks.prepare(change, key, millis)
                val sql = db.openHelper.writableDatabase
                when (case) {
                    "unrelated" -> db.metricDao().insert(metric.copy(id = 0, uuid = id(912), name = "Unrelated"))
                    "name" -> db.metricDao().insert(metric.copy(id = 0, uuid = id(912), name = "Proposed"))
                    "source" -> sql.execSQL("UPDATE next_request_origins SET intentJson='{}' WHERE requestId=?", arrayOf<Any>(row.operationId))
                    "transmission" -> sql.execSQL("DELETE FROM next_transmissions WHERE requestId=?", arrayOf<Any>(row.operationId))
                    "receipt" -> db.nextRequestDao().insertAcceptance(com.dayforge.data.local.entity.NextAcceptanceEntity(
                        NEXT_OPERATION, row.operationId, "0".repeat(64), "0".repeat(64), nextRequestHash("{}".toByteArray()), "{}"))
                    "queue-membership" -> db.syncOutboxDao().insert(row.copy(id = 0, operationId = id(913)))
                    "queue-removal" -> db.syncOutboxDao().deleteById(row.id)
                    "shadow" -> db.syncOutboxDao().upsertState(com.dayforge.data.local.entity.SyncEntityStateEntity(
                        "metric", metric.uuid, 1, payloadJson = base.payload.toString(), payloadHash = syncPayloadHash(base.payload.toString()), updatedAt = millis))
                }
                val failure = try { prepared.revalidate(blocks.capture(key)); null } catch (e: IllegalArgumentException) { e }
                assertEquals(case, case == "unrelated", failure == null)
                Log.i("G0MetricBlocks", "case=dependency-$case accepted=${failure == null}")
            }
        }
    }

    @Test fun lateAckTriggerAndFinalAccessFailureRollBackAndPublishNothing() = runBlocking<Unit> {
        register(); val row = editMetric("Sent"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val image = storage.snapshotClosedDatabase()
        for (case in listOf("late-metric", "late-unrelated", "final-auth")) {
            storage.restoreClosedDatabase(image); storage.reopen(); unreadableAccess = false
            val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks, G0CommitScheduler())
            if (case != "final-auth") {
                val table = if (case == "late-metric") "metrics" else "habits"
                val targetId = if (case == "late-metric") metric.id else habit.id
                // Mutate exactly one valid row, avoiding an earlier unique-name SQL failure.
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER g0_block_late AFTER INSERT ON next_acceptances BEGIN UPDATE sync_control SET suppressOutbox=1 WHERE id=1; UPDATE $table SET name='Late mutation' WHERE id=$targetId; UPDATE sync_control SET suppressOutbox=0 WHERE id=1; END")
            }
            val trace = G0PhaseTrace(driverTiming = false).apply { if (case == "final-auth") beforeFinal = { unreadableAccess = true } }
            val failure = rejected { trace.run { runtime.acknowledge(delivery) } }
            if (case == "final-auth") assertTrue(failure is NextRequestException)
            else assertEquals(case, "G0_BLOCK_LATE_DEPENDENCY_WRITE", failure.message)
            assertUnaccepted(row); assertNull(db.syncOutboxDao().getState("metric", metric.uuid))
            assertNull(blocks.publishedForTest()); assertEquals("Sent", db.metricDao().getMetricById(metric.id)!!.name)
            assertEquals(habit.name, db.habitDao().getHabitById(habit.id)!!.name)
            Log.i("G0MetricBlocks", "case=$case rollback=true publication=false")
        }
        unreadableAccess = false
    }

    @Test fun finalAuthFailureCannotPublishCandidate() = runBlocking<Unit> {
        register(); val row = editMetric("Sent"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        val trace = G0PhaseTrace(driverTiming = false).apply { beforeFinal = { unreadableAccess = true } }
        assertTrue(rejected { trace.run { runtime.acknowledge(delivery) } } is NextRequestException)
        assertUnaccepted(row); assertNull(blocks.publishedForTest()); assertNull(db.syncOutboxDao().getState("metric", metric.uuid))
        assertEquals(2, trace.count("decrypt_access")); unreadableAccess = false
    }

    @Test fun unknownCommitDiscardsBlocksAndColdOriginalReceiptRecoversExactlyOnce() = runBlocking<Unit> {
        register(); val row = editMetric("Sent"); val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId)); val requests = server.requests.size
        val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        faultTargetAck = row.operationId; faults.failAfterNextEnd.set(true)
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { runtime.acknowledge(delivery) }.message)
        assertNull(blocks.publishedForTest()); assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(db.syncOutboxDao().getById(row.id)); storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(1, count("next_acceptances")); assertEquals(requests, server.requests.size)
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { runtime.acknowledge(delivery) }.message)
    }

    @Test fun cancelledLocalWriteCannotPublishOrLeaveOutbox() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        val entered = CompletableDeferred<Unit>(); val session = local()
        val job = launch(Dispatchers.IO) { runtime.write(session) {
            db.metricDao().update(metric.copy(name = "Cancelled")); entered.complete(Unit); awaitCancellation()
        } }
        entered.await(); job.cancelAndJoin()
        assertNull(blocks.publishedForTest()); assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun changedAccountCannotReadOldCalculationBlocks() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Old account")) }
        val old = access()
        tokens.saveLoginSession("synthetic-other", "synthetic-other-refresh", "member", id(999), false)
        register()
        assertNotNull(rejected { runtime.read(old) })
        assertEquals("G0_BLOCK_SCOPE_CHANGED", rejected { runtime.read(access()) }.message)
    }
    @Test fun sameHandleMutationCannotBeReadThroughOldComputedBlock() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Committed")) }
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE metrics SET name='Unobserved change' WHERE uuid=?", arrayOf<Any>(metric.uuid))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("G0_BLOCK_REBUILD_REQUIRED", rejected { runtime.read(access()) }.message)
        assertEquals("Committed", blocks.publishedForTest()!!.definition.value.name)
    }

    @Test fun reopenCannotReuseOldBlockOrConnection() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Before close")) }
        storage.reopen()
        assertNotNull(rejected { runtime.read(access()) })
        assertEquals("Before close", db.metricDao().getMetricById(metric.id)!!.name)
    }

    @Test fun identityTransitionDuringFinalCannotPublishOldCandidate() = runBlocking<Unit> {
        register(); val row = editMetric("Transition"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        val reserved = CompletableDeferred<Unit>()
        authority.onTransitionForTest = { reserved.complete(Unit) }
        var transition: Job? = null
        val trace = G0PhaseTrace(driverTiming = false).apply { beforeFinal = {
            transition = launch(start = CoroutineStart.UNDISPATCHED) { tokens.clearAuthenticationTokens() }
            withTimeout(2_000) { reserved.await() }
        } }
        val failure = rejected { trace.run { runtime.acknowledge(delivery) } }
        requireNotNull(transition).join()
        assertTrue(failure is G0AuthorityOwner.Denied)
        assertNull(blocks.publishedForTest())
        // Final permit won first: old-scope commit may finish, but publication is revoked.
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(db.syncOutboxDao().getById(row.id))
        assertEquals(2, trace.count("decrypt_access"))
    }

    @Test fun unrelatedLegalDefinitionDoesNotInvalidateTargetCalculationVersions() = runBlocking<Unit> {
        register(); val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Target")) }
        val before = blocks.publishedForTest()!!
        producer().write(local()) { db.metricDao().insert(metric.copy(id = 0, uuid = id(980), name = "Independent")) }
        val after = runtime.read(access())
        assertSame(before.definition, after.definition); assertSame(before.sync, after.sync)
        assertEquals(2, count("next_request_origins")); assertEquals(2, count("sync_outbox"))
    }

    @Test fun retainedMultiRowFaultFixtureFailsAtUniqueConstraintBeforeBlockFinal() = runBlocking<Unit> {
        register(); val row = editMetric("Original failing fixture"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val blocks = G0MetricBlocks(db, metric.uuid)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER g0_retained_bad_fixture AFTER INSERT ON next_acceptances BEGIN UPDATE habits SET name='Late mutation'; END")
        val failure = rejected { blockRuntime(blocks).acknowledge(delivery) }
        assertTrue(failure.javaClass.name, failure is android.database.sqlite.SQLiteConstraintException)
        assertUnaccepted(row); assertNull(blocks.publishedForTest())
        Log.i("G0MetricBlocks", "case=retained-multirow-fixture failure=SQLiteConstraintException early=true rollback=true")
    }

    @Test fun lazyNewConnectionMustBuildStrictBlocksInsteadOfAssumingItIsClosedForever() = runBlocking<Unit> {
        register(); storage.reopen(); assertFalse(db.isOpen)
        val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        runtime.write(local()) { db.metricDao().update(metric.copy(name = "Lazy open")) }
        assertTrue(blocks.captures > 0)
        assertEquals("Lazy open", runtime.read(access()).definition.value.name)
        assertEquals(1, count("next_request_origins"))
    }

    @Test fun ignoredOldRevisionDoesNotDependOnItsUnusedIncomingName() = runBlocking<Unit> {
        register(); val first = editMetric("First"); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), first.operationId))
        sender(http).acceptOperation(delivery)
        val row = editMetric("Local latest")
        val original = NextOrdinaryResultMapper.validate(decodeNextOperationIntent(originalIntent(first).intentJson),
            delivery.result.results.single(), delivery.access.deviceId!!)
        val change = original.copy(payload = JsonObject(original.payload + ("name" to JsonPrimitive("Unused incoming"))))
        db.withTransaction {
            val shadow = requireNotNull(db.syncOutboxDao().getState("metric", metric.uuid))
            val body = JsonObject(Json.parseToJsonElement(shadow.payloadJson!!).jsonObject + ("revision" to JsonPrimitive(2)))
            db.syncOutboxDao().upsertState(shadow.copy(revision = 2, payloadJson = body.toString(), payloadHash = syncPayloadHash(body.toString())))
            val blocks = G0MetricBlocks(db, metric.uuid); val key = G0MetricBlocks.Request(row.id, row.operationId)
            val prepared = blocks.prepare(change, key, millis)
            assertNull(prepared.plan.output.metric); assertNull(prepared.plan.output.shadow)
            db.metricDao().insert(metric.copy(id = 0, uuid = id(981), name = "Unused incoming"))
            prepared.revalidate(blocks.capture(key))
            val before = db.metricDao().getMetricById(metric.id)
            NextStructureStore(db).restoreInTransaction(listOf(change), row.id)
            assertEquals(before, db.metricDao().getMetricById(metric.id))
        }
    }

    @Test fun lostLocalCommitKeepsOriginalOutboxIdentityAndNeverBlindlyRepeatsBusiness() = runBlocking<Unit> {
        register(); val first = editMetric("Confirmed before fault")
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) successReply(input)
            else successReply(input, revision = (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0L) + 1)
        }
        sender(http).sendAndAcceptOperation(access(), first.operationId)
        val blocks = G0MetricBlocks(db, metric.uuid); val runtime = blockRuntime(blocks)
        // Existing receipt is only the injector's eligibility anchor. Arm INSIDE the local
        // business transaction, so nested DAO ends cannot consume the actual outer-end fault.
        faultTargetAck = first.operationId
        val stableId = id(982); val session = local(); val trace = G0PhaseTrace(driverTiming = false)
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { trace.run { runtime.write(session) {
            db.metricDao().update(db.metricDao().getMetricById(metric.id)!!.copy(name = "Unknown local result"))
            val row = db.syncOutboxDao().getAll().single()
            db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET operationId=? WHERE id=?", arrayOf<Any>(stableId, row.id))
            faults.failAfterNextEnd.set(true)
        } } }.message)
        assertEquals(1, trace.count("tx_end_outer")); assertFalse(trace.spans.single { it.phase == "tx_end_outer" }.ok)
        assertNull(blocks.publishedForTest()); assertEquals(1, count("sync_outbox")); assertEquals(2, count("next_request_origins"))
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { runtime.write(session) { fail("Must not repeat business") } }.message)
        storage.reopen()
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, stableId))
        assertEquals("Unknown local result", db.metricDao().getMetricById(metric.id)!!.name)
        val requests = server.requests.size
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), stableId))
        val sent = server.requests.drop(requests)
        assertEquals("One public identity check plus exactly one business push", 2, sent.size)
        assertEquals(1, sent.count { it.path.endsWith("/identity") })
        assertEquals(1, sent.count { it.path.endsWith("/push") })
        val afterSend = server.requests.size
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), stableId))
        assertEquals("Receipt replay performs no further HTTP", afterSend, server.requests.size)
        assertEquals(2, count("next_acceptances")); assertEquals(2, count("next_request_origins")); assertEquals(0, count("sync_outbox"))
    }

}
