package com.dayforge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.repository.NextObjectEditorFixture
import com.dayforge.domain.service.DeviceCalendar
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform registration and real Activity start/stop; clock events are controlled without changing the phone. */
@RunWith(AndroidJUnit4::class)
class CalendarRefreshEffectTest : NextObjectEditorFixture() {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class TrackingContext(base: Context) : ContextWrapper(base) {
        val registrations = AtomicInteger(); val removals = AtomicInteger()
        val receiver = AtomicReference<BroadcastReceiver>()
        var filter: IntentFilter? = null
        var flags = 0
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter,
            broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? {
            val result = super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
            this.receiver.set(receiver); this.filter = filter; this.flags = flags; registrations.incrementAndGet()
            return result
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            super.unregisterReceiver(receiver); removals.incrementAndGet()
        }
    }
    private suspend fun await(reason: String, predicate: () -> Boolean) {
        try { withTimeout(5000) { while (!predicate()) delay(10) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(reason, error) }
    }

    @Test fun protectedClockEventsReadActualSourceIgnoreExtrasAndAlwaysReleaseThePlatformReceiver() {
        val context = TrackingContext(app)
        val now = AtomicReference(ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneId.of("UTC")))
        val calendar = DeviceCalendar { now.get() }
        CalendarObservation(context, calendar).use {
            assertEquals(1, context.registrations.get()); assertEquals(ContextCompat.RECEIVER_EXPORTED, context.flags)
            assertEquals(3, requireNotNull(context.filter).countActions())
            for (action in listOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
                assertTrue(context.filter!!.hasAction(action))
            now.set(now.get().withZoneSameInstant(ZoneId.of("Pacific/Honolulu")))
            context.receiver.get().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra("time-zone", "invalid-zone"))
            assertEquals(ZoneId.of("Pacific/Honolulu"), calendar.changes.value.zone)
            val revision = calendar.changes.value.revision
            context.receiver.get().onReceive(context, Intent("untrusted-action"))
            assertEquals(revision, calendar.changes.value.revision)
        }
        assertEquals(1, context.removals.get())
    }

    @Test fun stoppedActivityUnregistersAndResumeRecoversMissedCivilChangesWithNoBusinessWrites() = runBlocking<Unit> {
        val context = TrackingContext(app)
        val now = AtomicReference(ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneId.of("UTC")))
        val calendar = DeviceCalendar { now.get() }; val prefs = PreferencesManager(dataStore, calendar)
        val habits = db.habitDao().getAllHabitsOnce(); val queue = db.syncOutboxDao().getAll()
        val enabled = mutableStateOf(true)
        compose.setContent { if (enabled.value) CompositionLocalProvider(LocalContext provides context) { CalendarRefreshEffect(prefs) } }
        try {
            await("STARTED must register and refresh") { context.registrations.get() == 1 && calendar.changes.value.revision > 0 }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await("STOPPED must release its receiver") { context.removals.get() == 1 }
            val revision = calendar.changes.value.revision
            now.set(now.get().plusDays(1).withZoneSameInstant(ZoneId.of("Pacific/Honolulu")))
            assertEquals(revision, calendar.changes.value.revision)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await("RESUMED must register and refresh again") { context.registrations.get() == 2 && calendar.changes.value.revision > revision }
            assertEquals(now.get(), calendar.changes.value.time)
            assertEquals(habits, db.habitDao().getAllHabitsOnce()); assertEquals(queue, db.syncOutboxDao().getAll())
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { enabled.value = false }
            compose.waitForIdle() // Advance the Compose clock so removal actually leaves composition.
            await("Leaving composition must release every receiver") { context.removals.get() == context.registrations.get() }
        }
    }

    @Test fun preferenceCheckpointFailureDoesNotCrashOrStopLaterClockObservation() = runBlocking<Unit> {
        val context = TrackingContext(app)
        val now = AtomicReference(ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, ZoneId.of("UTC")))
        val calendar = DeviceCalendar { now.get() }
        val prefs = mockk<PreferencesManager>()
        val attempts = AtomicInteger()
        every { prefs.calendar } returns calendar
        coEvery { prefs.updateLastSeenDate(any()) } answers {
            if (attempts.incrementAndGet() == 1) throw java.io.IOException("SYNTHETIC_CHECKPOINT_FAILURE")
            false
        }
        val enabled = mutableStateOf(true)
        compose.setContent { if (enabled.value) CompositionLocalProvider(LocalContext provides context) { CalendarRefreshEffect(prefs) } }
        try {
            await("Initial checkpoint must be attempted") { attempts.get() == 1 }
            now.set(now.get().plusMinutes(1)); calendar.refresh()
            await("Observation must continue after the failed checkpoint") { attempts.get() == 2 }
            assertEquals(1, context.registrations.get()); assertEquals(0, context.removals.get())
        } finally {
            compose.runOnIdle { enabled.value = false }
            compose.waitForIdle()
            await("Leaving composition must release the fault-test receiver") { context.removals.get() == context.registrations.get() }
        }
    }
}
