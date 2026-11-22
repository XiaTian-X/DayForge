package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real stage participant and real T0/T1/T2. Passing this participant never authorizes
 * full recovery: the owning workflow still requires all business/material/archive proof. */
@RunWith(AndroidJUnit4::class)
class ReplicaCommandObligationStageTest : NextObjectEditorFixture() {
    private suspend fun raw() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun stage() = tokens.replicaStageFixture(raw().hash)
    private suspend fun refuse(expected: ReplicaSwitchStage) {
        val before = raw().contents; val prefs = dataStore.data.first().asMap()
        val error = try { NextReplicaStageStore(db, tokens, sessions).begin(expected); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failure }
        assertEquals("SYNC_RECOVERY_REVIEW_REQUIRED", error?.message)
        assertNull(tokens.activeReplicaStage()); assertNull(db.nextReplicaFenceDao().state())
        assertEquals(before, raw().contents)
        assertTrue("Rejected stage changed complete preferences", prefs == dataStore.data.first().asMap())
    }
    @Test(timeout = 150_000) fun realT0AfterStageCaptureIsRejectedAtActualBeginBeforeFreezingPersists() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId(); register()
        val captured = stage()
        val attempt = editor.beginCountAdd(habit, requireNotNull(editor.habit(habit.id).authority),
            Instant.now(), ZoneId.systemDefault(), 1)
        val input = attempt.input
        editor.releaseUnexecuted(attempt)
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        assertEquals(0, count("count_command_results")); assertEquals(1, count("count_command_intents"))
        refuse(captured) // A deliberately interleaved legitimate command, not a mock race/hook.
        val fresh = CountCommandStore(db, tokens, sessions).discover(8)
        assertEquals(listOf(input.commandId), fresh.commands.map { it.reference.commandId })
        assertEquals(CountCommandRecords.Execution.PREPARED, fresh.commands.single().historical.status.execution)
        assertTrue(fresh.commands.single().historical.status.pending)
    }
    @Test(timeout = 150_000) fun actualSavedUndeliveredMetricRefusesStageThenRealDeliveryAndAckRetainTerminalHistory() = runBlocking<Unit> {
        tokens.getOrCreateInstallationId(); register()
        val repository = editingMetrics(); val original = repository.getMetricForEditing(metric.id)
        val attempt = repository.beginIndependentValue(metric.id, 7.25, "replica obligation", millis,
            original.authority, metric.uuid)
        val saved = repository.executeIndependentValue(attempt)
        val reference = requireNotNull(saved.reference)
        val commands = CountCommandStore(db, tokens, sessions)
        val pending = commands.resolve(reference.native()).historical
        assertEquals(CountCommandRecords.Execution.APPLIED, pending.status.execution)
        assertTrue(pending.status.pending); assertEquals(1, count("metric_logs"))
        refuse(stage())
        repository.acknowledgeIndependentValue(saved) { true }
        val operation = db.syncOutboxDao().getAll().single()
        val (http, server) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), operation.operationId))
        assertTrue(server.requests.isNotEmpty()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val terminal = commands.resolve(reference.native()).historical
        assertFalse(terminal.status.pending)
        assertEquals(CountCommandRecords.Delivery.DELIVERED, terminal.status.delivery)
        assertEquals(pending.intent.text(), terminal.intent.text())
        assertEquals(requireNotNull(pending.outcome).text(), requireNotNull(terminal.outcome).text())
        val expected = stage(); val before = raw().contents
        val actual = NextReplicaStageStore(db, tokens, sessions).begin(expected)
        assertEquals(expected, actual); assertEquals(before, raw().contents)
        assertEquals(ReplicaSwitchPhase.ABORTED, NextReplicaStageStore(db, tokens, sessions).cancel(actual).phase)
        assertEquals(1, count("metric_logs")); assertEquals(1, count("count_command_results"))
        val remaining = commands.resolve(reference.native()).historical
        assertEquals(terminal.member(), remaining.member())
        assertFalse(remaining.status.pending); assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }
}
