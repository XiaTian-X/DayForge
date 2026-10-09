package com.dayforge.data.repository

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.*
import com.dayforge.data.model.*
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountSessionCoordinator
import com.dayforge.widget.IsolatedWidgetRefreshRule
import com.dayforge.widget.timer.TimerWidget
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production graph/Room/Glance storage. Source spy gates actual DAO reads only. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetTimerLifecycleTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var habits: HabitRepository
    @Inject lateinit var creator: NextObjectCreator
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = storage.database
    private fun id(n: Int) = "ab320000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val ids = mutableListOf<GlanceId>()
    private fun glanceId(): GlanceId = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, (System.nanoTime() and 0x1fffffff).toInt() + 1))).also { ids.add(it) }
    private suspend fun state(key: GlanceId) = TimerWidget().getAppWidgetState<Preferences>(app, key)
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun create(typed: Boolean = true): Long = habits.createHabit("Lifecycle timer $typed", "", HabitType.TIMER,
        0, "#123456", HabitSchedule.Daily, targetValue = 1, isCountdown = true, completionPolicy = if (typed) "recurring" else null,
        appearance = if (typed) ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "theme") else null,
        creationAuthority = if (typed) creator.capture() else null)

    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
    }
    @After fun cleanup() = runBlocking<Unit> {
        HabitDatabaseProvider.setInstanceForTesting(db)
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
        ids.forEach { updateAppWidgetState(app, it) { prefs -> prefs.clear() } }
    }

    private suspend fun seedOldProof(key: GlanceId) = updateAppWidgetState(app, key) {
        it[TimerWidget.ACTION_PROOF_KEY] = "old proof"
        it[TimerWidget.TICK_PLAN_KEY] = "old plan"; it[TimerWidget.TICK_LOG_HASH_KEY] = "old hash"
        it[TimerWidget.TICK_DATE_KEY] = "old date"; it[TimerWidget.TICK_ZONE_KEY] = "old zone"
        it[TimerWidget.TICK_COMPLETED_SECONDS_KEY] = 99; it[TimerWidget.READ_FAILED_KEY] = true
    }
    private fun assertNoActionOrTick(value: Preferences) {
        assertNull(value[TimerWidget.ACTION_PROOF_KEY]); assertNull(value[TimerWidget.TICK_PLAN_KEY])
        assertNull(value[TimerWidget.TICK_LOG_HASH_KEY]); assertNull(value[TimerWidget.TICK_DATE_KEY])
        assertNull(value[TimerWidget.TICK_ZONE_KEY]); assertNull(value[TimerWidget.TICK_COMPLETED_SECONDS_KEY])
    }

    @Test fun deletionClearsOldErrorActionAndTickMarkersAndLegacyHealthyReadRemainsUsable() = runBlocking<Unit> {
        val key = glanceId(); seedOldProof(key)
        val before = proof()
        TimerWidget.refreshWidgetData(app, key, Long.MAX_VALUE)
        val deleted = state(key)
        assertEquals(true, deleted[TimerWidget.IS_DELETED_KEY]); assertEquals(true, deleted[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, deleted[TimerWidget.READ_FAILED_KEY]); assertNoActionOrTick(deleted)
        assertEquals(before, proof())
        val habitId = create(typed = false)
        val current = proof()
        TimerWidget.refreshWidgetData(app, key, habitId)
        val loaded = state(key)
        assertEquals(false, loaded[TimerWidget.IS_DELETED_KEY]); assertEquals(true, loaded[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, loaded[TimerWidget.READ_FAILED_KEY]); assertEquals(1, loaded[TimerWidget.TARGET_MINUTES_KEY])
        assertEquals(true, loaded[TimerWidget.IS_COUNTDOWN_KEY]); assertNoActionOrTick(loaded)
        assertEquals(current, proof())
    }

    @Test fun realSourceFailureHidesActionsAndColdRetryRestoresDisplayWithoutChangingFacts() = runBlocking<Unit> {
        val habitId = create(); val key = glanceId(); val row = requireNotNull(db.habitDao().getVisibleHabitById(habitId))
        val raw = db.habitDao()
        val gated = spyk(raw)
        val proxy = spyk(db)
        every { proxy.habitDao() } returns gated
        HabitDatabaseProvider.setInstanceForTesting(proxy)
        coEvery { gated.getVisibleHabitById(habitId) } coAnswers { raw.getVisibleHabitById(habitId); throw IOException("source failed") }
        seedOldProof(key); val before = proof()
        TimerWidget.refreshWidgetData(app, key, habitId)
        val failed = state(key)
        assertEquals(true, failed[TimerWidget.READ_FAILED_KEY]); assertEquals(false, failed[TimerWidget.DATA_LOADED_KEY])
        assertEquals(false, failed[TimerWidget.IS_DELETED_KEY]); assertNoActionOrTick(failed)
        assertEquals(before, proof())
        HabitDatabaseProvider.setInstanceForTesting(db)
        TimerWidget.refreshWidgetData(app, key, habitId)
        assertEquals(false, state(key)[TimerWidget.READ_FAILED_KEY]); assertEquals(row.name, state(key)[TimerWidget.HABIT_NAME_KEY])
        assertNotNull(state(key)[TimerWidget.ACTION_PROOF_KEY]); assertEquals(before, proof())
    }

    @Test fun oldDeletedLegacyHealthyAndSourceErrorCannotOverwriteReplacementAccount() = runBlocking<Unit> {
        val legacy = create(typed = false)
        val typed = create()
        val raw = db.habitDao(); val gated = spyk(raw); val proxy = spyk(db)
        every { proxy.habitDao() } returns gated
        HabitDatabaseProvider.setInstanceForTesting(proxy)
        for (mode in listOf("deleted", "legacy", "error", "typed")) {
            sessions.exclusive { tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false) }
            val key = glanceId(); val before = proof()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val habitId = when (mode) { "deleted" -> Long.MAX_VALUE; "typed" -> typed; else -> legacy }
            coEvery { gated.getVisibleHabitById(habitId) } coAnswers {
                val value = raw.getVisibleHabitById(habitId)
                entered.complete(Unit); release.await()
                if (mode == "error") throw IOException("old source failed")
                value
            }
            val reading = async(Dispatchers.IO) { TimerWidget.refreshWidgetData(app, key, habitId) }
            try {
                withTimeout(5000) { entered.await() }
                sessions.exclusive {
                    tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
                    updateAppWidgetState(app, key) { it.clear(); it[TimerWidget.HABIT_NAME_KEY] = "new account" }
                }
                val replacement = state(key)
                release.complete(Unit); withTimeout(5000) { reading.await() }
                assertEquals(replacement, state(key)); assertEquals(before, proof())
            } finally { release.complete(Unit); reading.cancelAndJoin() }
        }
    }

    @Test fun sourceCancellationDoesNotPublishErrorAndReleasesAccountLock() = runBlocking<Unit> {
        val habitId = create(); val key = glanceId()
        val raw = db.habitDao(); val gated = spyk(raw); val proxy = spyk(db)
        every { proxy.habitDao() } returns gated
        HabitDatabaseProvider.setInstanceForTesting(proxy)
        val entered = CompletableDeferred<Unit>()
        coEvery { gated.getVisibleHabitById(habitId) } coAnswers { raw.getVisibleHabitById(habitId); entered.complete(Unit); awaitCancellation() }
        val original = state(key); val before = proof()
        val reading = launch(Dispatchers.IO) { TimerWidget.refreshWidgetData(app, key, habitId) }
        try {
            withTimeout(5000) { entered.await(); reading.cancelAndJoin() }
            assertEquals(original, state(key)); withTimeout(5000) { sessions.exclusive { assertEquals(before, proof()) } }
        } finally { reading.cancelAndJoin() }
    }
}
