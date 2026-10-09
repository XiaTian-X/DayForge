package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.util.DateTimeUtils
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real account coordinator and file Room; never a synthetic widget-cache authority. */
@RunWith(AndroidJUnit4::class)
class LegacyActivityRateRefresherTest : NextCoreRequestFixture() {
    private fun refresher() = LegacyActivityRateRefresher(db, tokens, sessions)
    private suspend fun proof(except: Long? = null) = db.withTransaction {
        nextRestartDatabaseProof(db, excludedHabitId = except)
    }
    private suspend fun legacy(name: String, created: Long = millis): HabitEntity {
        val row = HabitEntity(name = name, habitType = HabitType.CHECK_IN, iconResId = 1,
            colorHex = "#123456", schedule = HabitSchedule.Daily, createdAt = created,
            activityRate = 7, activityRateUpdatedAt = 1L)
        return row.copy(id = db.habitDao().insert(row))
    }

    @Test fun legacyRefreshPreservesTypedPlansFactsAndQueuesIncludingPartiallyInitializedRows() = runBlocking<Unit> {
        val row = legacy("Legacy cache")
        val day = DateTimeUtils.startOfDayMillis()
        db.completionDao().insert(CompletionEntity(habitId = row.id, date = day))
        val partial = habit.copy(id = 0, uuid = id(60), name = "Partial typed", appearance = null,
            activityRate = 4, activityRateUpdatedAt = 1L)
        val partialId = db.habitDao().insert(partial)
        val before = proof(row.id)
        val dates = db.completionDao().getByHabitOnce(row.id).map { it.businessDate }
        refresher().refresh()
        val saved = requireNotNull(db.habitDao().getHabitById(row.id))
        assertEquals(ActivityRateCalculator.calculate(row.schedule, row.createdAt, dates), saved.activityRate)
        assertTrue(saved.activityRateUpdatedAt > row.activityRateUpdatedAt)
        assertEquals(row, saved.copy(activityRate = row.activityRate, activityRateUpdatedAt = row.activityRateUpdatedAt))
        assertEquals(partial.copy(id = partialId), db.habitDao().getHabitById(partialId))
        assertEquals(before, proof(row.id)) // ALL other rows/tables, including original sources and queues
    }

    @Test fun loggedOutAndUnboundAuthenticationDoNotRefreshRetainedPersonalData() = runBlocking<Unit> {
        legacy("Retained account cache")
        tokens.clearTokens()
        val before = proof()
        refresher().refresh(); assertEquals(before, proof())
        // Deliberately no login/cache adoption: new authentication is not a local-data owner.
        tokens.saveTokens("synthetic-unbound", "synthetic-refresh", userId = id(50), isAdmin = false)
        refresher().refresh(); assertEquals(before, proof())
    }

    @Test fun lateCacheWriteFailureRollsBackEarlierUpdatesAndColdReopenRetainsOriginalState() = runBlocking<Unit> {
        val first = legacy("First refreshed", millis + 1)
        val second = legacy("Later failed", millis)
        val order = db.habitDao().getVisibleHabitsOnce().map { it.id }
        assertTrue(order.indexOf(first.id) < order.indexOf(second.id))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_late_activity BEFORE UPDATE OF activityRate ON habits " +
            "WHEN NEW.id=${second.id} BEGIN SELECT RAISE(ABORT,'late activity failure'); END")
        val before = proof()
        rejected { refresher().refresh() }
        assertEquals(before, proof())
        storage.reopen(); assertEquals(before, proof())
    }

    @Test fun cancelledRefreshDuringAccountTransitionDoesNotWriteOrLeakItsJob() = runBlocking<Unit> {
        legacy("Queued refresh")
        val before = proof()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) { sessions.exclusive { entered.complete(Unit); release.await() } }
        entered.await()
        val began = CompletableDeferred<Unit>()
        val update = launch(Dispatchers.IO) { began.complete(Unit); refresher().refresh() }
        try {
            began.await(); update.cancelAndJoin()
            assertEquals(before, proof())
        } finally { update.cancelAndJoin(); release.complete(Unit); holder.join() }
        assertTrue(update.isCancelled); assertTrue(holder.isCompleted)
    }
}
