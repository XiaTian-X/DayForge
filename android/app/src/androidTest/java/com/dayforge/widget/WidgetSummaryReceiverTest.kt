package com.dayforge.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.widget.motivation.MotivationWidgetReceiver
import com.dayforge.widget.progress.ProgressWidgetReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual platform receiver entry; fake installed IDs are never submitted to a host. */
@RunWith(AndroidJUnit4::class)
class WidgetSummaryReceiverTest {
    @get:Rule val widgets = IsolatedWidgetRefreshRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @OptIn(ExperimentalCoroutinesApi::class, androidx.glance.ExperimentalGlanceApi::class)
    @Test fun eachSummaryPlatformUpdateEnqueuesOnceWithoutDoingItsLoaderInTheBroadcast() = runTest {
        // Give superclass platform work an owned, paused dispatcher. These actual overrides
        // must enqueue before that work runs. Cancel/join it, never publish fake installed IDs.
        val owner = Job()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val progress = spyk(ProgressWidgetReceiver())
        val motivation = spyk(MotivationWidgetReceiver())
        every { progress.coroutineContext } returns (owner + dispatcher)
        every { motivation.coroutineContext } returns (owner + dispatcher)
        every { progress.goAsync() } returns mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        every { motivation.goAsync() } returns mockk<BroadcastReceiver.PendingResult>(relaxed = true)
        try {
            progress.onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(1, 2))
            assertEquals(1, widgets.requestCount)
            motivation.onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf(3))
            assertEquals(2, widgets.requestCount)
            progress.onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf())
            motivation.onUpdate(app, AppWidgetManager.getInstance(app), intArrayOf())
            assertEquals(2, widgets.requestCount)
        } finally { owner.cancel(); runCurrent(); owner.join() }
    }
}
