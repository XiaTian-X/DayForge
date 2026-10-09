package com.dayforge.widget

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual receiver dispatch; owned work execution is covered by worker/target suites. */
@RunWith(AndroidJUnit4::class)
class FocusWindowReceiverTest {
    @get:Rule val widgets = IsolatedWidgetRefreshRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun windowAlarmOnlyEnqueuesOneCurrentDataRefresh() {
        FocusWindowReceiver().onReceive(app, Intent(FocusWindowReceiver.ACTION_FOCUS_WINDOW_UPDATE))
        assertEquals(1, widgets.requestCount)
    }

    @Test fun unrelatedBroadcastDoesNotEnqueueWidgetWork() {
        FocusWindowReceiver().onReceive(app, Intent("com.dayforge.UNRELATED"))
        assertEquals(0, widgets.requestCount)
    }
}
