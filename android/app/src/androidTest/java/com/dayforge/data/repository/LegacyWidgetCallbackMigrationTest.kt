package com.dayforge.data.repository

import android.view.accessibility.AccessibilityEvent
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dayforge.data.local.LocalReplicaIdentity
import com.dayforge.widget.checkin.CheckInActionCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real installed callback, real platform Toast accessibility event, full native image.
 * Launcher scheduling alone is isolated by the inherited original widget refresh rule.
 * This does not prove the new task entry works; that is a separate required platform gate.
 */
@RunWith(AndroidJUnit4::class)
class LegacyWidgetCallbackMigrationTest : NextObjectEditorFixture() {
    private suspend fun oldInput(countdown: Boolean): LegacyWidgetFactClaim = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        habit = habit.copy(appearance = null, planMetadata = null, completionPolicy = null, isCountdown = countdown)
        timerHabit = timerHabit.copy(appearance = null, planMetadata = null, completionPolicy = null)
        metric = metric.copy(appearance = null)
        db.habitDao().update(habit); db.habitDao().update(timerHabit); db.metricDao().update(metric)
        sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        val identity = LegacyWidgetRestartClaim.displayed(habit, requireNotNull(tokens.localCoreWriteAccess()),
            LocalReplicaIdentity.readInTransaction(db))
        val zone = java.time.ZoneId.systemDefault()
        LegacyWidgetFactClaim(identity, java.time.LocalDate.now(zone).toString(), zone.id, null, 0,
            if (countdown) "decrement" else "increment")
    }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun runCase(countdown: Boolean) {
        val input = oldInput(countdown)
        val before = image(); val requested = widgetRefresh.requestCount
        val parameters = actionParametersOf(ActionParameters.Key<Long>("habitId") to habit.id,
            ActionParameters.Key<String>("action") to requireNotNull(input.action), CheckInActionCallback.LEGACY_PROOF to input.encode())
        val expected = app.getString(com.dayforge.R.string.widget_count_action_refresh_required)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Public library factory creates the Glance locator; no restricted AppWidgetId
        // constructor and no claim that a physical launcher widget is installed here.
        val glanceId = requireNotNull(GlanceAppWidgetManager(app).getGlanceIdBy(android.content.Intent()
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 73)))
        val event = automation.executeAndWaitForEvent({ runBlocking {
            CheckInActionCallback().onAction(app, glanceId, parameters)
        } }, { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && it.text.any { part -> part.toString() == expected } }, 5_000)
        try { assertTrue(event.text.any { it.toString() == expected }) } finally { releaseEvent(event) }
        assertEquals(before, image()); assertEquals(requested + 1, widgetRefresh.requestCount)
        // The exact old params remain reusable, but a second dispatch cannot mint a new ID.
        CheckInActionCallback().onAction(app, glanceId, parameters)
        assertEquals(before, image()); assertEquals(requested + 2, widgetRefresh.requestCount)
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        assertEquals(0, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
    }
    @Suppress("DEPRECATION") // API26-32 event pooling; SDK33+ retains this compatibility method.
    private fun releaseEvent(event: AccessibilityEvent) { event.recycle() }
    @Test(timeout = 150_000) fun oldIncrementCallbackIsVisiblyRejectedAndRepeatedDispatchHasNoNativeEffects() = runBlocking<Unit> { runCase(false) }
    @Test(timeout = 150_000) fun oldCountdownMinusCallbackCannotExecuteOrMintCommandIdentity() = runBlocking<Unit> { runCase(true) }
}
