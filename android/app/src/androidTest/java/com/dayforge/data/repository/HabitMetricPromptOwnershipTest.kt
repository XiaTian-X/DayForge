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

    private suspend fun prompt(legacy: Boolean): LinkedMetricCoordinator {
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
            habitPreferences = preferenceRepository()).also {
            it.showPromptIfNeeded(habit.id, habit.name)
            assertNotNull(it.postCheckInState.value?.habitPrompt)
        }
    }

    private suspend fun submit(coordinator: LinkedMetricCoordinator, value: Double = 3.0): Boolean =
        withContext(Dispatchers.Main) {
            coordinator.recordMetricValues(habit.id, listOf(MetricValueInput(metric.id, value)),
                expectedPreferenceClaim = requireNotNull(coordinator.postCheckInState.value?.preferenceClaim))
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
