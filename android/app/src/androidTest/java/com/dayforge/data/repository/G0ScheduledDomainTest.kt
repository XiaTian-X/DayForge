package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0ScheduledDomainTest : G0DomainFixture() {
    @Test fun originalWarmWritesAndActualAckShareFairAdmissionAndCommitBeforeHandoff() = runBlocking<Unit> {
        register(); val row = observation(); val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId)); val session = local()
        val runtime = bridge(); val release = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.READ) { release.await() } }
        val commands = (1..4).map { n -> launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.write(session) { db.metricDao().update(metric.copy(name = "Warm $n", updatedAt = millis + n)) }
            assertFalse(db.inTransaction()); order.add("write$n")
        } }
        val ack = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(NextOperationAcceptance.COMMITTED, runtime.acknowledge(http, delivery))
            assertFalse(db.inTransaction()); assertNull(db.syncOutboxDao().getById(row.id)); order.add("ack")
        }
        val requests = server.requests.size
        release.complete(Unit); holder.join(); commands.joinAll(); ack.join()
        assertEquals(listOf("write1", "write2", "ack", "write3", "write4"), order)
        assertEquals(4, count("sync_outbox")); assertEquals(1, count("next_acceptances")); assertEquals(requests, server.requests.size)
        assertEquals("Warm 4", db.metricDao().getMetricById(metric.id)!!.name)
    }

    @Test fun cancelInsideOriginalRoomWriteRollsBackBeforeSchedulerHandoff() = runBlocking<Unit> {
        register(); val session = local(); val runtime = bridge(); val entered = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            runtime.write(session) {
                db.metricDao().update(metric.copy(name = "Canceled"))
                entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await(); writer.cancelAndJoin()
        assertEquals(metric.name, db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { runtime.write(session) { fail("Poisoned runtime") } }.message)
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { bridge().write(session) { fail("New wrapper bypassed quarantine") } }.message)
    }

    @Test fun unknownActualAckCommitClosesAdmissionThenOriginalColdReplayRetiresExactlyOnce() = runBlocking<Unit> {
        register(); val row = observation(); val (http, server) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId)); val runtime = bridge()
        faultTargetAck = row.operationId
        faults.failAfterNextEnd.set(true)
        assertEquals("G0_COMMIT_RESULT_LOST", rejected { runtime.acknowledge(http, delivery) }.message)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)); assertNull(db.syncOutboxDao().getById(row.id))
        assertEquals("G0_DOMAIN_RECOVERY_REQUIRED", rejected { runtime.acknowledge(http, delivery) }.message)
        val requests = server.requests.size
        storage.reopen()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(requests, server.requests.size); assertEquals(1, count("next_acceptances")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun queuedAccountLeaseIsRevokedBeforeAnyBusinessSql() = runBlocking<Unit> {
        register(); val session = local(); val runtime = bridge(); val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.READ) { release.await() } }
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { runtime.write(session) { fail("Revoked queued write reached business") } }.exceptionOrNull()
        }
        tokens.clearAuthenticationTokens()
        writes.clear(); release.complete(Unit); holder.join()
        assertTrue(write.await() is G0AuthorityOwner.Denied)
        assertTrue(writes.isEmpty()); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun responseCollectionsAreFrozenBeforeWaitingAndRoomContinuationDoesNotReadmit() = runBlocking<Unit> {
        register(); val row = observation(); val (http, _) = channel { successReply(it) }
        val delivery = requireNotNull(sender(http).sendOperation(access(), row.operationId))
        val mutable = delivery.result.results.toMutableList()
        val input = delivery.copy(result = delivery.result.copy(results = mutable))
        val runtime = bridge(); val release = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.run(G0CommitScheduler.Kind.READ) { release.await() } }
        val ack = async(start = CoroutineStart.UNDISPATCHED) { runtime.acknowledge(http, input) }
        mutable.clear() // Immediately after the first suspension, even before admission.
        withTimeout(1000) { while (scheduler.pendingForTest() != 1) yield() }
        release.complete(Unit); holder.join(); assertEquals(NextOperationAcceptance.COMMITTED, ack.await())
        val session = local()
        runtime.write(session) {
            db.withTransaction {
                withContext(Dispatchers.Default) {
                    db.metricDao().update(metric.copy(name = "Nested continuation", updatedAt = millis))
                }
            }
        }
        assertEquals("Nested continuation", db.metricDao().getMetricById(metric.id)!!.name)
        assertEquals(1, count("sync_outbox")); assertEquals(1, count("next_acceptances"))
    }
}
