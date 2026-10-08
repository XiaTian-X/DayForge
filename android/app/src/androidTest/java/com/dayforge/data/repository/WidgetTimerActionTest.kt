package com.dayforge.data.repository

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.TimerStartGuard
import com.dayforge.widget.timer.WidgetTimerAction
import com.dayforge.widget.timer.WidgetTimerActionActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Persisted original proofs and account/Room CAS, not a launcher acceptance substitute. */
@RunWith(AndroidJUnit4::class)
class WidgetTimerActionTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun claim() = WidgetTimerAction.from(requireNotNull(writer().widgetSnapshot(timerHabit)))

    @Test fun emptyStartIsExplicitAndPlanEditChangesPendingIntentIdentity() = runBlocking<Unit> {
        val before = claim()
        writer().requireWidgetStart(before.habitId, before.authority, requireNotNull(before.startGuard))
        val intent = before.intent(app, "start")
        assertEquals(WidgetTimerActionActivity::class.java.name, intent.component!!.className)
        assertEquals(before, WidgetTimerAction.read(intent))
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 2)) }
        rejected { writer().requireWidgetStart(before.habitId, before.authority, before.startGuard!!) }
        timerHabit = requireNotNull(db.habitDao().getHabitById(timerHabit.id))
        assertFalse(intent.filterEquals(claim().intent(app, "start")))
        assertNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(0, count("timer_command_outbox"))
    }

    @Test fun startObservedEmptyCannotInterruptTimerCreatedAfterRendering() = runBlocking<Unit> {
        val before = claim()
        start()
        val log = db.timeLogDao().getActiveTimeLog()
        val queued = db.timeLogDao().getPendingTimerCommands()
        rejected { writer().requireWidgetStart(before.habitId, before.authority, before.startGuard!!) }
        assertEquals(log, db.timeLogDao().getActiveTimeLog())
        assertEquals(queued, db.timeLogDao().getPendingTimerCommands())
    }

    @Test fun switchRetainsExactIncumbentAcrossColdReopenAndRejectsChangedSequenceAndAccount() = runBlocking<Unit> {
        start()
        val incumbent = timerHabit
        val second = timerHabit.copy(id = 0, uuid = id(60), name = "Second timer")
        producer().write(local()) { timerHabit = second.copy(id = db.habitDao().insert(second)) }
        val switch = claim()
        assertEquals(incumbent.id, switch.startGuard!!.incumbentHabitId)
        assertEquals(id(20), switch.startGuard.incumbent!!.sessionUuid)
        storage.reopen()
        val display = writer().requireWidgetStart(switch.habitId, switch.authority, switch.startGuard)
        assertEquals(incumbent.name, display.incumbentName)
        assertEquals(second.name, display.requestedName)
        val original = requireNotNull(db.timeLogDao().getActiveTimeLog())
        // Simulates a persisted concurrent projection; source proof is not replaced or re-captured.
        db.openHelper.writableDatabase.execSQL("UPDATE timelogs SET timerNextCommandSequence=3 WHERE id=?", arrayOf(original.id))
        rejected { writer().requireWidgetStart(switch.habitId, switch.authority, switch.startGuard) }
        db.openHelper.writableDatabase.execSQL("UPDATE timelogs SET timerNextCommandSequence=? WHERE id=?", arrayOf<Any>(original.timerNextCommandSequence, original.id))
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(50), false)
        rejected { writer().requireWidgetStart(switch.habitId, switch.authority, switch.startGuard) }
        assertEquals(original, db.timeLogDao().getActiveTimeLog())
        assertEquals(1, count("timer_command_outbox"))
    }

    @Test fun strictClaimRejectsDuplicateFieldsWrongTypesAndMissingGuardWithoutMutation() = runBlocking<Unit> {
        val proof = claim()
        assertEquals(proof, WidgetTimerAction.decode(proof.encode()))
        rejected { WidgetTimerAction.decode(proof.encode().replaceFirst("\"habitId\":", "\"habitId\":\"${proof.habitId}\",\"habitId\":")) }
        rejected { WidgetTimerAction.decode(proof.copy(startGuard = null).encode()) }
        rejected { WidgetTimerAction.decode(proof.encode().replaceFirst("\"habitId\":${proof.habitId}", "\"habitId\":\"${proof.habitId}\"")) }
        rejected { WidgetTimerAction.decode(" ".repeat(65_537)) }
        val intent = Intent().apply { TimerStartGuard(null, null).attach(this) }
        assertEquals(TimerStartGuard(null, null), TimerStartGuard.read(intent))
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
    }
}
