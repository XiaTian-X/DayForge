package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real formal bootstrap/sender without creating a COUNT parent or required prompt. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricFormalProjectionTest : CountTrustFormalProfileFixture() {
    @Test(timeout = 150_000) fun independentFormalObservationAckMaintainsDirectoryWithoutCountParent() = runBlocking<Unit> {
        openCountAddPlanInputs(); tokens.getOrCreateInstallationId()
        val selected = db.metricDao().getVisibleMetricsOnce().first()
        val repo = editingMetrics(); val editor = repo.getMetricForEditing(selected.id)
        val saved = repo.executeIndependentValue(repo.beginIndependentValue(selected.id, 13.125, "no count parent",
            authority = editor.authority, expectedMetricUuid = selected.uuid))
        val reference = requireNotNull(saved.reference)
        val intent = CountCommandStore(db, tokens, sessions).resolve(reference.native()).historical
        assertNull(intent.status.parent); assertNull(intent.status.child)
        assertEquals(CountCommandRecords.Action.METRIC_APPEND, intent.intent.action)
        assertFalse(intent.intent.body().getValue("input").toString().contains("source_fact_uuid"))
        val queue = db.syncOutboxDao().getAll().single { it.entityUuid == saved.factUuid }
        val sender = countAddPlanSender()
        val delivery = requireNotNull(sender.sendOperation(access(), queue.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, sender.acceptOperation(delivery))
        val result = MetricDisplayReader(db, tokens, sessions, this@IndependentMetricFormalProjectionTest.editor).overview().single { it.metric.uuid == selected.uuid }
        assertEquals(db.metricLogDao().getLatestLog(selected.id)?.value, result.latestValue)
        assertTrue(result.logs.any { it.uuid == saved.factUuid })
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { context ->
            assertNotNull(context)
            requireNotNull(context).index.withReadScope {
                val row = context.rows.read("metrics", "id=?", arrayOf(selected.id)).single()
                assertNotNull(MetricTrustDirectory.Reader(context.index::get).manifest(row))
            }
        } }
        assertTrue(repo.resolveIndependentValue(reference) is IndependentMetricResolution.Saved)
    }
    @Test(timeout = 150_000) fun normalFormalPullUsesOriginalInvalidationWhenOnceCoverageWasNotRequested() = runBlocking<Unit> {
        openCountAddPlanInputs(); tokens.getOrCreateInstallationId()
        val read = MetricDisplayReader(db, tokens, sessions, editor)
        val before = read.overview()
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val merger = NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once),
            NextTimerRequestStore(db, tokens, sessions, countAddPlanSender()))
        val state = requireNotNull(merger.state(access(), challengeProfile = true))
        val metadata = requireNotNull(merger.challengeMetadata(access()))
        val next = merger.page(access(), state, com.dayforge.data.api.dto.RoundSyncPullResponse(emptyList(), state.cursor,
            false, java.time.Instant.now().toString(), 1, metadata.checkpoints, metadata.births))
        assertEquals(state.cursor, next.cursor)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            // METRIC reads do not issue unrelated ONCE certificates to keep this PULL warm.
            assertNull("Original unsupported PULL safely invalidates optional metric qualification", it)
        } }
        assertEquals(before, read.overview())
        val selected = requireNotNull(db.metricDao().getVisibleMetricsOnce().firstOrNull())
        val ticket = editingMetrics().getMetricForEditing(selected.id)
        editingMetrics().updateMetric(selected.copy(description = "legitimate exact invalidator"), ticket.authority)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { assertNull(it) } }
        assertEquals("legitimate exact invalidator", read.overview().single { it.metric.uuid == selected.uuid }.metric.description)
    }

}
