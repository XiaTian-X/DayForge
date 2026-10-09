package com.dayforge.domain.service

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.PhysicalDatabaseRule
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.nextRestartDatabaseProof
import com.dayforge.widget.*
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.checkin.CheckInWidgetReceiver
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.counting.CountingWidgetReceiver
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.progress.ProgressWidgetReceiver
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

/** Actual Glance storage and cleanup/recovery, platform discovery/render submission only replaced. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WidgetCleanupRecoveryTest {
    @get:Rule(order = 0) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 1) val storage = PhysicalDatabaseRule()
    @get:Rule(order = 2) val hilt = HiltAndroidRule(this)
    @Inject lateinit var tokens: TokenManager
    @Inject lateinit var sessions: AccountSessionCoordinator
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val platform = mockk<AppWidgetManager>()
    private val ids = mutableMapOf<Int, GlanceId>()
    private val names = listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME, TimerWidget.PREFS_NAME)
    private fun account(n: Int) = "ab340000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"

    @Before fun setup() = runBlocking<Unit> {
        hilt.inject()
        sessions.exclusive { tokens.clearTokens(); tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", account(1), false) }
        val real = GlanceAppWidgetManager(app)
        for (n in listOf(10, 11, 12)) {
            ids[n] = requireNotNull(real.getGlanceIdBy(Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                (System.nanoTime() and 0x1fffffff).toInt() + 1)))
        }
        mockkStatic(AppWidgetManager::class)
        every { AppWidgetManager.getInstance(app) } returns platform
        every { platform.getAppWidgetIds(any()) } answers {
            when (firstArg<android.content.ComponentName>().className) {
                CountingWidgetReceiver::class.java.name -> intArrayOf(10, 11)
                ProgressWidgetReceiver::class.java.name -> intArrayOf(12)
                else -> intArrayOf()
            }
        }
        mockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class,
            TimerWidget::class, ProgressWidget::class, MotivationWidget::class, FocusWidget::class)
        every { anyConstructed<GlanceAppWidgetManager>().getGlanceIdBy(any<Int>()) } answers { requireNotNull(ids[firstArg()]) }
        every { anyConstructed<GlanceAppWidgetManager>().getAppWidgetId(any()) } answers { ids.entries.single { it.value == firstArg<GlanceId>() }.key }
        coEvery { anyConstructed<GlanceAppWidgetManager>().getGlanceIds(any<Class<GlanceAppWidget>>()) } returns emptyList()
        coEvery { anyConstructed<CountingWidget>().update(any(), any()) } just Runs
        coEvery { anyConstructed<ProgressWidget>().update(any(), any()) } just Runs
        mockkObject(FocusWidgetAlarmScheduler)
        every { FocusWidgetAlarmScheduler.cancelScheduledRefresh(any()) } just Runs
        coEvery { FocusWidgetAlarmScheduler.scheduleNextRefresh(any()) } just Runs
        names.forEach { assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putLong("habit_id_10", 42).commit()) }
        ids.values.forEach { updateAppWidgetState(app, it) { prefs -> prefs[CountingWidget.HABIT_NAME_KEY] = "old private name" } }
    }
    @After fun cleanup() = runBlocking<Unit> {
        unmockkStatic(AppWidgetManager::class)
        unmockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class,
            TimerWidget::class, ProgressWidget::class, MotivationWidget::class, FocusWidget::class)
        unmockkObject(FocusWidgetAlarmScheduler)
        ids.values.forEach { updateAppWidgetState(app, it) { prefs -> prefs.clear() } }
        names.forEach { assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()) }
        if (::tokens.isInitialized) sessions.exclusive { tokens.clearTokens() }
    }
    private suspend fun state(n: Int) = CountingWidget().getAppWidgetState<Preferences>(app, requireNotNull(ids[n]))

    @Test fun discoveryAndInstanceFailureAreIsolatedAndRecoveryIsRequestedAfterStoresAndBindingsClear() = runBlocking<Unit> {
        every { platform.getAppWidgetIds(match { it.className == CheckInWidgetReceiver::class.java.name }) } throws IOException("discovery unavailable")
        coEvery { anyConstructed<CountingWidget>().update(app, requireNotNull(ids[10])) } throws IOException("launcher unavailable")
        var cleared = false
        val before = storage.database.withTransaction { nextRestartDatabaseProof(storage.database) }
        sessions.exclusive { AccountLocalStateCleaner.clear(app) { cleared = true } }
        assertTrue(cleared); assertEquals(1, widgets.requestCount)
        names.forEach { assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        ids.keys.forEach { assertTrue(state(it).asMap().isEmpty()) }
        coVerify(exactly = 1) { anyConstructed<CountingWidget>().update(app, requireNotNull(ids[11])) }
        coVerify(exactly = 1) { anyConstructed<ProgressWidget>().update(app, requireNotNull(ids[12])) }
        assertEquals(before, storage.database.withTransaction { nextRestartDatabaseProof(storage.database) })
    }

    @Test fun cancellationPropagatesAndQueuedRecoveryClearsCurrentUnboundStateNotOldBindingsOrBusiness() = runBlocking<Unit> {
        val cancelled = CancellationException("launcher stopped")
        coEvery { anyConstructed<CountingWidget>().update(app, requireNotNull(ids[10])) } throws cancelled
        var caught: Exception? = null
        try { sessions.exclusive { AccountLocalStateCleaner.clear(app) { } } }
        catch (error: Exception) { caught = error }
        assertSame(cancelled, caught); assertEquals(1, widgets.requestCount)
        names.forEach { assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        // Cancellation prevented later host work, so there is genuinely stale state to recover.
        assertEquals("old private name", state(11)[CountingWidget.HABIT_NAME_KEY])
        sessions.exclusive { tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", account(50), false) }
        val before = storage.database.withTransaction { nextRestartDatabaseProof(storage.database) }
        coEvery { anyConstructed<GlanceAppWidgetManager>().getGlanceIds(CountingWidget::class.java) } returns listOf(requireNotNull(ids[10]), requireNotNull(ids[11]))
        coEvery { anyConstructed<CountingWidget>().update(app, any()) } coAnswers {
            val key = secondArg<GlanceId>()
            assertTrue(CountingWidget().getAppWidgetState<Preferences>(app, key).asMap().isEmpty())
        }
        assertEquals(0, WidgetRefresher(app).refresh())
        assertTrue(state(10).asMap().isEmpty()); assertTrue(state(11).asMap().isEmpty())
        assertEquals(account(50), tokens.authenticationSnapshot()!!.session.userId)
        assertEquals(before, storage.database.withTransaction { nextRestartDatabaseProof(storage.database) })
    }
}
