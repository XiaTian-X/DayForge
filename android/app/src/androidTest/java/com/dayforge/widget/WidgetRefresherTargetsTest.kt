package com.dayforge.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidget
import androidx.test.core.app.ApplicationProvider
import com.dayforge.widget.checkin.CheckInWidget
import com.dayforge.widget.counting.CountingWidget
import com.dayforge.widget.focus.FocusWidget
import com.dayforge.widget.motivation.MotivationWidget
import com.dayforge.widget.progress.ProgressWidget
import com.dayforge.widget.timer.TimerWidget
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRefresherTargetsTest {
    private lateinit var context: Context
    private val id = mockk<GlanceId>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class,
            TimerWidget::class, ProgressWidget::class, MotivationWidget::class, FocusWidget::class)
        mockkObject(CheckInWidget.Companion, CountingWidget.Companion, TimerWidget.Companion,
            ProgressWidget.Companion, MotivationWidget.Companion, FocusWidget.Companion, FocusWidgetAlarmScheduler)
        coEvery { anyConstructed<GlanceAppWidgetManager>().getGlanceIds(any<Class<GlanceAppWidget>>()) } returns listOf(id)
        every { anyConstructed<GlanceAppWidgetManager>().getAppWidgetId(id) } returns 10
        bind(CheckInWidget.PREFS_NAME, CheckInWidget.PREF_HABIT_ID_PREFIX, 101)
        bind(CountingWidget.PREFS_NAME, CountingWidget.PREF_HABIT_ID_PREFIX, 102)
        bind(TimerWidget.PREFS_NAME, TimerWidget.PREF_HABIT_ID_PREFIX, 103)
        coEvery { CheckInWidget.refreshWidgetData(context, id, any()) } just Runs
        coEvery { CountingWidget.refreshWidgetData(context, id, any()) } just Runs
        coEvery { TimerWidget.refreshWidgetData(context, id, any()) } just Runs
        coEvery { ProgressWidget.refreshWidgetData(context, id) } just Runs
        coEvery { MotivationWidget.refreshWidgetData(context, id) } just Runs
        coEvery { FocusWidget.refreshWidgetData(context, id) } just Runs
        coEvery { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) } just Runs
        coEvery { anyConstructed<CheckInWidget>().update(context, id) } just Runs
        coEvery { anyConstructed<CountingWidget>().update(context, id) } just Runs
        coEvery { anyConstructed<TimerWidget>().update(context, id) } just Runs
        coEvery { anyConstructed<ProgressWidget>().update(context, id) } just Runs
        coEvery { anyConstructed<MotivationWidget>().update(context, id) } just Runs
        coEvery { anyConstructed<FocusWidget>().update(context, id) } just Runs
    }

    @After fun teardown() {
        listOf(CheckInWidget.PREFS_NAME, CountingWidget.PREFS_NAME, TimerWidget.PREFS_NAME).forEach { name ->
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove("habit_id_10").commit())
        }
        unmockkConstructor(GlanceAppWidgetManager::class, CheckInWidget::class, CountingWidget::class,
            TimerWidget::class, ProgressWidget::class, MotivationWidget::class, FocusWidget::class)
        unmockkObject(CheckInWidget.Companion, CountingWidget.Companion, TimerWidget.Companion,
            ProgressWidget.Companion, MotivationWidget.Companion, FocusWidget.Companion, FocusWidgetAlarmScheduler)
    }

    private fun bind(name: String, prefix: String, habit: Long) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putLong(prefix + 10, habit).commit()
    }

    @Test fun all_six_types_load_current_state_before_rendering_and_focus_alarm_is_retained() = runTest {
        assertEquals(0, WidgetRefresher(context).refresh())
        coVerifyOrder {
            CheckInWidget.refreshWidgetData(context, id, 101)
            anyConstructed<CheckInWidget>().update(context, id)
            CountingWidget.refreshWidgetData(context, id, 102)
            anyConstructed<CountingWidget>().update(context, id)
            TimerWidget.refreshWidgetData(context, id, 103)
            anyConstructed<TimerWidget>().update(context, id)
            ProgressWidget.refreshWidgetData(context, id)
            anyConstructed<ProgressWidget>().update(context, id)
            MotivationWidget.refreshWidgetData(context, id)
            anyConstructed<MotivationWidget>().update(context, id)
            FocusWidget.refreshWidgetData(context, id)
            anyConstructed<FocusWidget>().update(context, id)
            FocusWidgetAlarmScheduler.scheduleNextRefresh(context)
        }
    }

    @Test fun no_installed_widgets_avoids_loading_data_or_rendering_but_retains_alarm_reconciliation() = runTest {
        coEvery { anyConstructed<GlanceAppWidgetManager>().getGlanceIds(any<Class<GlanceAppWidget>>()) } returns emptyList()
        assertEquals(0, WidgetRefresher(context).refresh())
        coVerify(exactly = 0) { CheckInWidget.refreshWidgetData(any(), any(), any()) }
        coVerify(exactly = 0) { ProgressWidget.refreshWidgetData(any(), any()) }
        coVerify(exactly = 0) { anyConstructed<TimerWidget>().update(any(), any()) }
        coVerify(exactly = 1) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
    }

    @Test fun queued_refresh_resolves_latest_binding_and_renders_unconfigured_widgets() = runTest {
        val refresher = WidgetRefresher(context)
        bind(CheckInWidget.PREFS_NAME, CheckInWidget.PREF_HABIT_ID_PREFIX, 999)
        bind(CountingWidget.PREFS_NAME, CountingWidget.PREF_HABIT_ID_PREFIX, -1)
        assertEquals(0, refresher.refresh())
        // A now-deleted habit is still passed to the loader to write its deleted marker.
        coVerify(exactly = 1) { CheckInWidget.refreshWidgetData(context, id, 999) }
        coVerify(exactly = 0) { CountingWidget.refreshWidgetData(any(), any(), any()) }
        coVerify(exactly = 1) { anyConstructed<CountingWidget>().update(context, id) }
    }

    @Test fun a_data_load_failure_does_not_render_stale_state_or_block_later_types() = runTest {
        coEvery { ProgressWidget.refreshWidgetData(context, id) } throws IOException("database busy")
        assertEquals(1, WidgetRefresher(context).refresh())
        coVerify(exactly = 0) { anyConstructed<ProgressWidget>().update(context, id) }
        coVerify(exactly = 1) { anyConstructed<MotivationWidget>().update(context, id) }
        coVerify(exactly = 1) { anyConstructed<FocusWidget>().update(context, id) }
        coVerify(exactly = 1) { FocusWidgetAlarmScheduler.scheduleNextRefresh(context) }
    }
}
