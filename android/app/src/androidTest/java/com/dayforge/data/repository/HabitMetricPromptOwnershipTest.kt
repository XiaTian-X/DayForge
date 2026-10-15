package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.ui.components.MetricValueInput
import com.dayforge.ui.metrics.LinkedMetricCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/session writers: regular prompt callbacks must retain their displayed identities. */
@RunWith(AndroidJUnit4::class)
class HabitMetricPromptOwnershipTest : NextObjectEditorFixture() {
    private fun preferenceRepository() = HabitPreferenceRepository(db, tokens, sessions, preferences,
        ReplicaPresentationRepository(db, tokens, sessions))

    private suspend fun prompt(legacy: Boolean, preferenceOwner: HabitPreferenceRepository = preferenceRepository()): LinkedMetricCoordinator {
        if (legacy) {
            habit = habit.copy(appearance = null, completionPolicy = null, planMetadata = null)
            metric = metric.copy(appearance = null)
        }
        db.withTransaction {
            db.habitDao().update(habit); db.metricDao().update(metric)
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = metric.id, metricUuid = metric.uuid, coefficient = 1.0,
                showInHabitDetail = true, promptOnComplete = true))
        }
        preferences.addPendingMetricHabit(local(), habit.uuid)
        return LinkedMetricCoordinator(app, preferences, editingMetrics(), editingHabits(),
            habitPreferences = preferenceOwner).also {
            it.showPromptIfNeeded(habit.id, habit.name)
            assertNotNull(it.postCheckInState.value?.habitPrompt)
        }
    }

    private suspend fun submit(coordinator: LinkedMetricCoordinator, value: Double = 3.0): Boolean =
        withContext(Dispatchers.Main) {
            coordinator.recordMetricValues(habit.id, listOf(MetricValueInput(metric.id, value)),
                expectedPreferenceClaim = requireNotNull(coordinator.postCheckInState.value?.preferenceClaim))
        }

    @Test fun countFollowUpKeepsOriginalAccountInsteadOfAdoptingCurrentNumericId() = runBlocking {
        val coordinator = prompt(legacy = false)
        val identity = requireNotNull(CountHistoryReader(db, tokens, sessions).read(
            requireNotNull(db.habitDao().getHabitById(habit.id))).actionIdentity)
        coordinator.showCountPrompt(identity, habit.name)
        assertEquals(identity.session, coordinator.postCheckInState.value?.preferenceClaim?.session)
        tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "other", id(888), false)
        val before = db.syncOutboxDao().getAll()
        try { coordinator.showCountPrompt(identity, habit.name); fail("Old follow-up cannot adopt a new account") }
        catch (expected: IllegalStateException) { assertNotNull(expected.message) }
        assertNull(coordinator.postCheckInState.value)
        assertEquals(before, db.syncOutboxDao().getAll()); assertEquals(0, db.metricLogDao().countAll())
    }

    @Test fun typedPromptCleanupFailureRetainsSavedIdentityAndRetryDoesNotAppend() = runBlocking {
        cleanupFailureAndRetry(legacy = false)
    }

    @Test fun multiMetricPromptKeepsOriginalBatchAndRepeatedConfirmationDoesNotAppendAgain() = runBlocking {
        val coordinator = prompt(legacy = false)
        val second = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val next = metric.copy(id = 0, uuid = id(299), name = "Second prompt metric", unit = "hour")
            val saved = next.copy(id = db.metricDao().insert(next))
            db.habitMetricLinkDao().insert(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
                metricId = saved.id, metricUuid = saved.uuid, coefficient = 1.0,
                showInHabitDetail = true, promptOnComplete = true))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            saved
        }
        coordinator.showPromptIfNeeded(habit.id, habit.name)
        val proof = requireNotNull(coordinator.postCheckInState.value?.habitPrompt)
        assertEquals(setOf(metric.uuid, second.uuid), proof.metrics.map { it.value!!.uuid }.toSet())
        assertTrue(proof.metrics.all { it.authority!!.session == proof.source.session })
        val values = listOf(MetricValueInput(metric.id, 3.0), MetricValueInput(second.id, 3.0))
        withContext(Dispatchers.Main) {
            repeat(2) { assertTrue(coordinator.recordMetricValues(habit.id, values, expectedPreferenceClaim = proof.source)) }
        }
        assertEquals(2, count("metric_logs"))
        assertEquals("hour", db.metricLogDao().getAllLogsForMetric(second.id).single().unit)
        assertEquals(2, db.syncOutboxDao().getAll().count { it.recordType == "metric_log" })
    }

    @Test fun legacyPromptCleanupFailureRetainsSavedIdentityAndRetryDoesNotAppend() = runBlocking {
        cleanupFailureAndRetry(legacy = true)
    }

    private suspend fun cleanupFailureAndRetry(legacy: Boolean) {
        val actual = preferenceRepository()
        val owner = io.mockk.spyk(actual)
        io.mockk.coEvery { owner.removePending(any()) } throws IllegalStateException("PREFERENCE_UNAVAILABLE")
        val coordinator = prompt(legacy, owner)
        val proof = requireNotNull(coordinator.postCheckInState.value?.habitPrompt)
        io.mockk.mockkStatic(android.widget.Toast::class)
        try {
            val messages = mutableListOf<CharSequence>()
            io.mockk.every { android.widget.Toast.makeText(app, capture(messages), android.widget.Toast.LENGTH_LONG) } returns io.mockk.mockk(relaxed = true)
            assertFalse(submit(coordinator))
            assertEquals(app.getString(com.dayforge.R.string.record_saved_refresh_failed), messages.single())
            assertEquals(1, db.metricLogDao().countAll())
            assertEquals(listOf(MetricValueInput(metric.id, 3.0)), proof.submitted)
            assertEquals(setOf(habit.id), actual.pendingHabitIds.first())
            val queued = db.syncOutboxDao().getAll()
            assertFalse(submit(coordinator, 4.0)) // Editing a submitted batch must not replay it.
            assertEquals(1, db.metricLogDao().countAll())
            io.mockk.coEvery { owner.removePending(any()) } coAnswers { actual.removePending(firstArg()) }
            assertTrue(submit(coordinator))
            assertEquals(1, db.metricLogDao().countAll())
            assertEquals(queued, db.syncOutboxDao().getAll())
            assertTrue(actual.pendingHabitIds.first().isEmpty())
        } finally { io.mockk.unmockkStatic(android.widget.Toast::class) }
    }

    @Test fun legacyPromptWritesUnderOriginalClaimAndDuplicateSubmitDoesNotAppendTwice() = runBlocking {
        val coordinator = prompt(legacy = true)
        withTimeout(20_000) { assertTrue(submit(coordinator)); assertTrue(submit(coordinator)) }
        assertEquals(1, db.metricLogDao().countAll())
        assertEquals(3.0, requireNotNull(db.metricLogDao().getLatestLog(metric.id)).value, 0.0)
        assertTrue(preferenceRepository().pendingHabitIds.first().isEmpty())
    }

    @Test fun legacyPromptRejectsReauthenticationBeforeWritingOrClearingHint() = runBlocking {
        register()
        val coordinator = prompt(legacy = true)
        tokens.saveLoginSession("renewed-access", "renewed-refresh", "member", id(1), false)
        assertFalse(submit(coordinator))
        assertEquals(0, db.metricLogDao().countAll())
        assertEquals(setOf(habit.id), preferenceRepository().pendingHabitIds.first())
    }

    @Test fun legacyPromptRejectsReusedMetricIdAndChangedLinkInOriginalTransaction() = runBlocking {
        val coordinator = prompt(legacy = true)
        db.metricDao().update(metric.copy(uuid = id(90), name = "Replacement"))
        assertFalse(submit(coordinator))
        assertEquals(0, db.metricLogDao().countAll())
        db.metricDao().update(metric)
        val link = requireNotNull(db.habitMetricLinkDao().getLink(habit.id, metric.id))
        db.habitMetricLinkDao().update(link.copy(promptOnComplete = false))
        assertFalse(submit(coordinator))
        assertEquals(0, db.metricLogDao().countAll())
        assertEquals(setOf(habit.id), preferenceRepository().pendingHabitIds.first())
    }

    @Test fun typedPromptUsesCapturedWriterAuthorityWithoutNestedAccountLockAndRejectsOldReplica() = runBlocking {
        val coordinator = prompt(legacy = false)
        withTimeout(20_000) { assertTrue(submit(coordinator)); assertTrue(submit(coordinator)) }
        assertEquals(1, db.metricLogDao().countAll())
        assertTrue(db.syncOutboxDao().getAll().any { it.recordType == "metric_log" })
        coordinator.showPromptIfNeeded(habit.id, habit.name)
        tokens.saveServerIdentity(id(2), id(93))
        assertFalse(submit(coordinator, 9.0))
        assertEquals(1, db.metricLogDao().countAll())
        assertEquals(3.0, requireNotNull(db.metricLogDao().getLatestLog(metric.id)).value, 0.0)
    }
}
