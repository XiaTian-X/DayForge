package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PlaintextTokenCipher
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.FailMode
import com.dayforge.data.model.HabitType
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room read plans and writers; publication is after the transaction, never a write grant. */
@RunWith(AndroidJUnit4::class)
class CompletionPromptReadPlanTest : NextObjectEditorFixture() {
    private val decryptions = AtomicInteger()
    override fun tokenCipher(): TokenCipher = object : TokenCipher {
        override fun encrypt(value: String) = PlaintextTokenCipher.encrypt(value)
        override fun decrypt(value: String): String? {
            decryptions.incrementAndGet()
            return PlaintextTokenCipher.decrypt(value)
        }
    }

    private fun reader() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }

    private suspend fun prepare(finite: Boolean = false, secondMetric: Boolean = false): WidgetFactSnapshot {
        register()
        habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1,
            targetCycles = if (finite) 1 else null, failMode = if (finite) FailMode.STRICT else FailMode.LOOSE)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            val metrics = listOf(metric) + if (secondMetric) {
                val row = metric.copy(id = 0, uuid = id(101), name = "Second metric", unit = "m")
                listOf(row.copy(id = db.metricDao().insert(row)))
            } else emptyList()
            metrics.forEach { target -> db.habitMetricLinkDao().insert(HabitMetricLinkEntity(
                habitId = habit.id, habitUuid = habit.uuid, metricId = target.id, metricUuid = target.uuid,
                promptOnComplete = true)) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val repo = creatingHabits()
        val captured = repo.prepareCompletion(habit.id)
        val recorded = repo.logCompletion(app, habit.id, authority = captured.edit.authority,
            checkIdentity = captured.evidence!!.checkHistory!!.actionIdentity)
        return repo.readCompletionAfter(captured, allowActivation = true, recordedCompletionId = recorded).followUp
    }

    private suspend fun prepared(repo: MetricRepository, source: WidgetFactSnapshot): CompletionMetricRead {
        var captured: CompletionMetricRead? = null
        repo.publishCompletionPrompt(source) {
            assertFalse("No UI publication inside the read transaction", db.inTransaction())
            assertSame(source, it.source)
            captured = it
        }
        return requireNotNull(captured)
    }

    private suspend fun record(repo: MetricRepository, prepared: CompletionMetricRead) = repo.recordValues(
        prepared.metrics.map { MetricValueDraft(requireNotNull(it.value).id, 3.0) },
        authority = prepared.metrics.first().authority,
        expectedMetrics = prepared.metrics.map { requireNotNull(it.value) },
        promptHabit = prepared.source.habit, promptFact = prepared.source.claim, promptCompletionSource = true)

    @Test fun onePlanMatchesSeparateReadsWithFewerAuthenticationReadsAndPublishesAfterTransaction() = runBlocking {
        val source = prepare(secondMetric = true)
        val repo = creatingMetrics()
        val ids = repo.getLinkedMetricSnapshots(habit.id).map { it.metricId }
        val before = proof()
        decryptions.set(0)
        val separate = repo.getMetricsForEditing(ids)
        creatingHabits().publishCompletion(source) { }
        val separateCalls = decryptions.get()
        decryptions.set(0)
        val combined = prepared(repo, source)
        val combinedCalls = decryptions.get()
        assertEquals(separate.map { it.value }, combined.metrics.map { it.value })
        separate.zip(combined.metrics).forEach { (old, current) ->
            assertEquals(old.authority!!.session, current.authority!!.session)
            assertEquals(old.authority.original, current.authority.original)
            assertEquals(old.authority.rounds, current.authority.rounds)
        }
        assertEquals(ids, combined.links.map { it.metricId })
        assertTrue("One plan must not repeat the separate metric-account read: $combinedCalls / $separateCalls",
            combinedCalls < separateCalls)
        assertEquals(before, proof())
    }

    @Test fun bothGoalSuccessorsPrepareAndSaveMetricsWithoutAdoptingTheOldPlan() = runBlocking {
        val source = prepare(finite = true)
        val habits = creatingHabits()
        val metrics = creatingMetrics()
        val continued = habits.applyCompletionGoal(app, source, complete = false)
        assertEquals(FailMode.LOOSE, continued.habit.failMode)
        record(metrics, prepared(metrics, continued))
        val completed = habits.applyCompletionGoal(app, continued, complete = true)
        assertFalse(completed.habit.isActive)
        record(metrics, prepared(metrics, completed))
        assertEquals(source.claim.completionUuid, completed.claim.completionUuid)
        assertEquals(2, count("metric_logs")); assertEquals(1, count("completions"))
        val before = proof()
        rejected { metrics.publishCompletionPrompt(source) { fail("Old goal plan cannot publish") } }
        rejected { metrics.publishCompletionPrompt(continued) { fail("Previous successor cannot publish") } }
        assertEquals(before, proof())
    }

    @Test fun originalPreviousDaySourceSurvivesLaterFactsButUndoRevokesThePrompt() = runBlocking {
        val initial = prepare()
        val habits = creatingHabits()
        val metrics = creatingMetrics()
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).minusDays(1)
        val timestamp = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
            date = day.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = timestamp,
            recordedTimezone = zone.id, recordedLocalDate = day.toString())
        val captured = habits.prepareCompletion(habit.id)
        val factId = producer().write(local()) { db.completionDao().insert(fact) }
        val source = habits.readCompletionAfter(captured, false, factId).followUp
        assertNotEquals(initial.claim.completionUuid, source.claim.completionUuid)
        habits.logCompletion(app, habit.id)
        val prompt = prepared(metrics, source)
        record(metrics, prompt)
        assertEquals(day.toString(), prompt.source.claim.date)
        assertEquals(timestamp, db.completionDao().getCompletionById(factId)!!.actualCompletedAt)
        assertEquals(1, count("metric_logs"))
        habits.undoCompletion(app, factId)
        val before = proof()
        rejected { metrics.publishCompletionPrompt(source) { fail("Removed source cannot publish") } }
        rejected { record(metrics, prompt) }
        assertEquals(before, proof())
    }

    @Test fun accountDevicePlanAndEpochChangesCannotReplaceTheOriginalSource() = runBlocking {
        var source = prepare()
        val metrics = creatingMetrics()
        register(device = id(80))
        rejected { metrics.publishCompletionPrompt(source) { fail("Old device cannot publish") } }
        source = reader().read(requireNotNull(db.habitDao().getHabitById(habit.id)))
        tokens.saveLoginSession("synthetic-renewed", "synthetic-refresh", "member", id(1), false)
        rejected { metrics.publishCompletionPrompt(source) { fail("Old authentication cannot publish") } }
        source = reader().read(requireNotNull(db.habitDao().getHabitById(habit.id)))
        producer().write(local()) { db.habitDao().update(habit.copy(name = "Changed plan")) }
        rejected { metrics.publishCompletionPrompt(source) { fail("Old plan cannot publish") } }
        source = reader().read(requireNotNull(db.habitDao().getHabitById(habit.id)))
        tokens.saveServerIdentity(id(2), id(81))
        rejected { metrics.publishCompletionPrompt(source) { fail("Old epoch cannot publish") } }
        assertEquals(1, count("completions")); assertEquals(0, count("metric_logs"))
    }

    @Test fun participantFailureCancellationAndLateAuthenticationChangeNeverPublish() = runBlocking {
        val source = prepare()
        val reader = reader()
        val before = proof()
        val failure = rejected {
            reader.readCompletionPlanAndPublish<Unit>(source, read = { _, _ -> error("PREPARE_FAILED") },
                publish = { fail("Failed preparation cannot publish") })
        }
        assertEquals("PREPARE_FAILED", failure.message)
        val cancelled = runCatching {
            reader.readCompletionPlanAndPublish<Unit>(source, read = { _, _ -> throw CancellationException("cancel read") },
                publish = { fail("Cancelled preparation cannot publish") })
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        rejected {
            reader.readCompletionPlanAndPublish(source, read = { _, _ ->
                tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(999), false)
            }, publish = { fail("Exit authentication must reject before publication") })
        }
        assertEquals(before, proof()); assertEquals(1, count("completions"))
    }

    @Test fun changedMetricAndUnlinkedPromptKeepWriterRevalidationAndDoNotWrite() = runBlocking {
        val source = prepare()
        val metrics = creatingMetrics()
        val prompt = prepared(metrics, source)
        metrics.updateMetric(metric.copy(unit = "g"), prompt.metrics.single().authority)
        var before = proof()
        rejected { record(metrics, prompt) }
        assertEquals(before, proof())
        val current = prepared(metrics, source)
        val link = requireNotNull(db.habitMetricLinkDao().getLink(habit.id, metric.id))
        metrics.unlinkHabit(link.id, link.uuid, current.metrics.single().authority)
        before = proof()
        metrics.publishCompletionPrompt(source) { fail("Unlinked metric must not be displayed") }
        rejected { record(metrics, current) }
        assertEquals(before, proof()); assertEquals(0, count("metric_logs"))
    }
}
