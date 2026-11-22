package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Original repository/DAO and real HTTP are independent oracles; not a release P95 claim. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricProjectionChainTest : NextObjectEditorFixture() {
    private fun reader() = MetricDisplayReader(db, tokens, sessions, editor)
    private suspend fun dirty() = db.withTransaction { db.openHelper.writableDatabase.query(
        "SELECT dirty FROM count_trust_control WHERE id=1").use { assertTrue(it.moveToFirst()); it.getLong(0) } }
    private suspend fun save(value: Double): IndependentMetricSaved {
        val repo = editingMetrics(); val captured = repo.getMetricForEditing(metric.id)
        return repo.executeIndependentValue(repo.beginIndependentValue(metric.id, value, "independent", millis,
            requireNotNull(captured.authority), metric.uuid))
    }
    @Test(timeout = 150_000) fun plainActualAppendSendAckAndSavedReplayKeepFiniteCurrentResult() = runBlocking<Unit> {
        register(); tokens.getOrCreateInstallationId(); reader().overview()
        val saved = save(7.125); assertNotNull(saved.reference); assertEquals(0L, dirty())
        assertEquals("COUNT_BUTTON_COMMAND_REQUIRED", runCatching {
            com.dayforge.ui.CountButtonModel.committed(CountCommandReceipt(requireNotNull(saved.commit)))
        }.exceptionOrNull()?.message)
        val original = requireNotNull(db.metricLogDao().getById(saved.factId))
        val overview = reader().overview().single { it.metric.uuid == metric.uuid }
        assertEquals(original.value, overview.latestValue); assertEquals(original.date, overview.latestLogDate)
        val queue = db.syncOutboxDao().getAll().single { it.entityUuid == saved.factUuid }
        val (store, delivery) = success(queue)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery)); assertEquals(0L, dirty())
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val resolved = editingMetrics().resolveIndependentValue(requireNotNull(saved.reference))
        assertTrue(resolved is IndependentMetricResolution.Saved)
        assertEquals(saved.factUuid, (resolved as IndependentMetricResolution.Saved).value.factUuid)
        assertEquals(1, db.metricLogDao().getAllLogsForMetric(metric.id).size)
        val final = reader().overview().single { it.metric.uuid == metric.uuid }
        assertEquals(db.metricLogDao().getLatestLog(metric.id)?.value, final.latestValue)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }
    @Test(timeout = 150_000) fun explicitSecondEqualInputIsNewFactAndDefinitionWriteInvalidatesOldReadPreparation() = runBlocking<Unit> {
        register(); tokens.getOrCreateInstallationId(); reader().overview()
        val first = save(3.5); val second = save(3.5)
        assertNotEquals(first.reference, second.reference); assertNotEquals(first.factUuid, second.factUuid)
        assertEquals(2, db.metricLogDao().getAllLogsForMetric(metric.id).size)
        val repo = editingMetrics(); val old = repo.getMetricForEditing(metric.id)
        repo.updateMetric(requireNotNull(old.value).copy(unit = "lb"), old.authority)
        assertEquals(1L, dirty())
        rejected { repo.executeIndependentValue(repo.beginIndependentValue(metric.id, 4.0, "old", millis,
            requireNotNull(old.authority), metric.uuid)) }
        assertEquals(listOf("kg", "kg"), db.metricLogDao().getAllLogsForMetric(metric.id).map { it.unit })
        assertEquals("lb", reader().overview().single { it.metric.uuid == metric.uuid }.metric.unit)
    }
    @Test(timeout = 150_000) fun explicitMetricPreparationDoesNotCertifyUnrelatedCountTimerOrOnceHistory() = runBlocking<Unit> {
        register(); tokens.getOrCreateInstallationId(); reader().overview()
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { value ->
            val context = requireNotNull(value)
            assertTrue(context.metricOnly); assertTrue(context.metricRanges)
            assertFalse(context.eligible(habit)); assertFalse(context.timers); assertFalse(context.once)
        } }
        CountHistoryReader(db, tokens, sessions).readSummary(habit, com.dayforge.util.DateTimeUtils.today())
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) { value ->
            val context = requireNotNull(value)
            assertFalse(context.metricOnly); assertTrue(context.metricRanges); assertTrue(context.eligible(habit))
        } }
    }

    @Test(timeout = 150_000) fun warmScopeRemainsReadOnlyAndPermissionFailureCannotPublishValues() = runBlocking<Unit> {
        register(); tokens.getOrCreateInstallationId(); reader().overview(); save(5.0)
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        val current = reader().overview().single { it.metric.uuid == metric.uuid }
        assertEquals(5.0, current.latestValue)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        tokens.clearTokens()
        rejected { reader().overview() }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
    }
}
