package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.HabitMetricLinkEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.AccountIconController
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real v1 COUNT parent and v1 linked child share B's v3 root with an old uninterpreted row. */
@RunWith(AndroidJUnit4::class)
class CountCommandRetainedLinkedDeliveryTest : CountCommandRetainedFixture() {
    private data class Group(val database: HabitDatabase, val metrics: MetricRepository,
        val parent: CountCommandReceipt, val child: CountCommandReceipt)
    private suspend fun group(fault: CountCommandRetainedNativeFault? = null): Group {
        seed(); val database = if (fault == null) openCurrent() else faultRoom(fault)
        val metric = metric(database)
        val value = HabitEntity(name = "Retained count", habitType = HabitType.COUNTING, iconResId = 0,
            colorHex = "#123456", schedule = HabitSchedule.Daily, uuid = id(95))
        val habit = value.copy(id = database.habitDao().insert(value))
        database.habitMetricLinkDao().insertOrIgnore(HabitMetricLinkEntity(habitId = habit.id, habitUuid = habit.uuid,
            metricId = metric.id, metricUuid = metric.uuid, promptOnComplete = true))
        val editor = NextObjectEditor(database, tokens, sessions, mockk<AccountIconController>())
        val count = CountHistoryReader(database, tokens, sessions)
        val widgets = WidgetFactReader(database, tokens, sessions, commandMigrationPreferences, count)
        val habits = HabitRepository(database.habitDao(), database.completionDao(), database.timeLogDao(), database,
            nextObjectEditor = editor, countHistoryReader = count, widgetFactReader = widgets,
            countActions = CountActionReader(database, tokens, sessions))
        val metrics = MetricRepository(database, database.metricDao(), database.metricLogDao(), database.habitDao(),
            database.habitMetricLinkDao(), nextObjectEditor = editor, widgetFactReader = widgets)
        val parent = requireNotNull(habits.logCompletionWithReceipt(context, habit.id,
            countIdentity = CountActionReader(database, tokens, sessions).legacy(habit)).command)
        val preferences = HabitPreferenceRepository(database, tokens, sessions, commandMigrationPreferences,
            ReplicaPresentationRepository(database, tokens, sessions))
        val current = requireNotNull(database.habitDao().getHabitById(habit.id)); val claim = preferences.read(current).claim
        val child = requireNotNull(metrics.recordLegacyPromptValuesWithReceipt(listOf(MetricValueDraft(metric.id, 5.0, "linked")),
            1_800_000_000_000L, claim, preferences, current, listOf(metric), parent).command)
        preferences.removePending(claim)
        assertEquals(CountCommandRecords.Delivery.METRICS_COMMITTED, store(database).resolve(parent.reference.native()).historical.status.delivery)
        assertEquals(3L, count(database, "count_command_results")); assertLegacy(database)
        return Group(database, metrics, parent, child)
    }
    @Test(timeout = 150_000) fun finalTwoStepDeliveryRetainsOldCommitmentAndConsumesOnlyNewParentAndChild() = runBlocking<Unit> {
        val group = group(); val before = root(group.database)
        group.metrics.acknowledgeMetricCommandDelivery(group.parent, group.child)
        val after = root(group.database)
        assertEquals(before.revision + 2L, after.revision)
        assertEquals(before.legacyCount, after.legacyCount); assertEquals(before.legacyHash, after.legacyHash)
        assertEquals(0L, after.pendingCount); assertEquals(1L, count(group.database, "metric_logs")); assertLegacy(group.database)
        val image = image(group.database); group.metrics.acknowledgeMetricCommandDelivery(group.parent, group.child)
        assertEquals(image, image(group.database))
        val cold = openCurrent(); assertEquals(image, image(cold)); assertLegacy(cold)
        for (ref in listOf(group.parent.reference, group.child.reference))
            assertEquals(CountCommandRecords.Delivery.DELIVERED, store(cold).resolve(ref.native()).historical.status.delivery)
    }
    @Test(timeout = 150_000) fun oldRowChangedAtActualFinalH2RejectsBothDeliveryStepsThenColdRetryIsSingle() = runBlocking<Unit> {
        val fault = CountCommandRetainedNativeFault(); val group = group(fault)
        val before = image(group.database); fault.arm(CountCommandRetainedNativeFault.Mode.EXTRA_LEGACY)
        assertNotNull(runCatching { group.metrics.acknowledgeMetricCommandDelivery(group.parent, group.child) }.exceptionOrNull())
        assertEquals(1, fault.hits); assertTrue(fault.actualRollback); assertFalse(fault.actualCommit)
        val cold = openCurrent(); assertEquals(before, image(cold)); assertLegacy(cold)
        repository(cold).acknowledgeMetricCommandDelivery(group.parent, group.child)
        assertEquals(0L, root(cold).pendingCount); assertEquals(1L, count(cold, "metric_logs")); assertLegacy(cold)
    }
}
