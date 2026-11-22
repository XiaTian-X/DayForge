package com.dayforge.data.repository

import android.app.PendingIntent
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.entity.TimeLogEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import com.dayforge.data.local.entity.TimerSegmentEntity
import com.dayforge.domain.model.TimerActionAuthority
import com.dayforge.domain.service.TimerService
import com.dayforge.domain.service.TimerServiceController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Room/original state machine. Namespace reset leaves account/UUID/sequence/business
 * bytes equal. No fake owner, service-dispatch success or whole-database rollback claim.
 * Tests intentionally avoid new claim-field references so the pre-fix source can compile
 * these rejection/URI counterexamples if the parent schedules a separate original run.
 */
@RunWith(AndroidJUnit4::class)
class TimerReplicaBoundaryTest : NextCoreRequestFixture() {
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun storageIdentity() = db.withTransaction {
        db.openHelper.writableDatabase.query("SELECT storageId FROM count_trust_control WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst()); cursor.getString(0).also { assertFalse(cursor.moveToNext()) }
        }
    }
    private suspend fun resetOnlyLocalNamespace() {
        val before = image(); val oldIdentity = storageIdentity()
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            CountTrustSchema.resetLocalInTransaction(db.openHelper.writableDatabase)
        } }
        val after = image()
        assertNotEquals(oldIdentity, storageIdentity())
        assertEquals(before.contents.getValue("schema"), after.contents.getValue("schema"))
        assertEquals(before.contents.getValue("user_version"), after.contents.getValue("user_version"))
        assertEquals(before.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES,
            after.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES)
    }
    private suspend fun pause(log: TimeLogEntity, ticket: TimerActionAuthority? = null,
        legacy: LegacyWidgetTimerClaim? = null) = writer().write(log.habitId, ticket, legacy) {
        db.timeLogDao().updatePauseAndQueue(log.id, true, millis + 1, 0, 3, millis + 1, 1, null, null,
            TimerCommandEntity(commandId = id(22), sessionUuid = log.uuid, sequence = 2, commandType = "pause",
                occurredAt = millis + 1, expectedControlGeneration = 1, activeElapsedMillis = 1))
    }
    private suspend fun legacyStart(): Pair<TimeLogEntity, LegacyWidgetTimerClaim> {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET appearance=NULL,planMetadata=NULL,completionPolicy=NULL")
            sql.execSQL("UPDATE metrics SET appearance=NULL")
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        timerHabit = requireNotNull(db.habitDao().getHabitById(timerHabit.id))
        val first = writer().captureLegacyForDisplay(timerHabit, null)
        val row = TimeLogEntity(habitId = timerHabit.id, uuid = id(80), startTime = millis, endTime = null,
            durationSeconds = 0, timerNextCommandSequence = 2, timerControlGeneration = 1,
            timerLastCommandAt = millis, timerTimezone = "UTC", date = millis)
        writer().write(timerHabit.id, null, first) {
            db.timeLogDao().insertSyncedTimer(row, TimerCommandEntity(commandId = id(81), sessionUuid = row.uuid,
                sequence = 1, commandType = "start", occurredAt = millis, expectedControlGeneration = 0,
                activityUuid = timerHabit.uuid, timezone = "UTC"),
                TimerSegmentEntity(sessionUuid = row.uuid, sequence = 1, startedAt = millis))
        }
        val running = requireNotNull(db.timeLogDao().getActiveTimeLog())
        return running to writer().captureLegacyConfirmation(timerHabit, running)
    }

    @Test fun normalSameFileReopenKeepsTheOriginalTypedSourceAndAllNativeValues() = runBlocking<Unit> {
        start(); val original = requireNotNull(writer().capture(timerHabit.id))
        val before = image(); val identity = storageIdentity()
        storage.reopen(); writer().requireAction(timerHabit.id, original)
        assertEquals(identity, storageIdentity()); assertEquals(original, writer().capture(timerHabit.id))
        val after = image(); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
    }
    @Test fun sameAccountUuidAndSequenceCannotAdoptAnotherExplicitLocalNamespace() = runBlocking<Unit> {
        start(); val original = requireNotNull(writer().capture(timerHabit.id))
        val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        resetOnlyLocalNamespace(); assertEquals(log, db.timeLogDao().getActiveTimeLog())
        val before = image(); var reached = false
        assertEquals("TIMER_REPLICA_CHANGED", rejected {
            writer().write(timerHabit.id, original) { reached = true }
        }.message)
        assertFalse(reached)
        val after = image(); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
    }
    @Test fun freshCaptureAfterExplicitResetStillCompletesOneOriginalTypedTransition() = runBlocking<Unit> {
        start(); resetOnlyLocalNamespace()
        val log = requireNotNull(db.timeLogDao().getActiveTimeLog()); val source = requireNotNull(writer().capture(timerHabit.id))
        writer().requireAction(timerHabit.id, source); pause(log, source)
        val actual = requireNotNull(db.timeLogDao().getActiveTimeLog())
        assertEquals(log.uuid, actual.uuid); assertTrue(actual.isPaused); assertEquals(3, actual.timerNextCommandSequence)
        assertEquals(listOf("start", "pause"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
        assertEquals(2, count("next_request_origins"))
        assertEquals("TIMER_ACTION_STALE_SESSION", rejected { pause(log, source) }.message)
    }
    @Test fun legacyOriginalClaimSurvivesReopenButNotResetAndFreshClaimCanPause() = runBlocking<Unit> {
        val (log, original) = legacyStart(); storage.reopen(); writer().requireLegacyWidgetAction(original)
        resetOnlyLocalNamespace(); val before = image(); var reached = false
        assertEquals("LEGACY_WIDGET_REPLICA_CHANGED", rejected {
            writer().write(timerHabit.id, null, original) { reached = true }
        }.message)
        assertFalse(reached); assertEquals(before.contents, image().contents)
        val fresh = writer().captureLegacyConfirmation(timerHabit, log)
        assertNotEquals(original, fresh); writer().requireLegacyWidgetAction(fresh); pause(log, legacy = fresh)
        assertTrue(requireNotNull(db.timeLogDao().getActiveTimeLog()).isPaused)
        assertEquals(listOf("start", "pause"), db.timeLogDao().getPendingTimerCommands().map { it.commandType })
        assertEquals(0, count("next_request_origins"))
    }
    @Test fun nativePendingIntentIdentityCannotUpdateAnOldButtonToTheNewReplica() = runBlocking<Unit> {
        start(); val old = requireNotNull(writer().capture(timerHabit.id))
        val first = TimerServiceController.commandIntent(app, TimerService.ACTION_PAUSE, timerHabit.id, 1, authority = old)
        resetOnlyLocalNamespace(); val fresh = requireNotNull(writer().capture(timerHabit.id))
        val second = TimerServiceController.commandIntent(app, TimerService.ACTION_PAUSE, timerHabit.id, 1, authority = fresh)
        assertNotEquals(first.data, second.data); assertFalse(first.filterEquals(second))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val oldPending = PendingIntent.getService(app, 78163, first, flags)
        val newPending = PendingIntent.getService(app, 78163, second, flags)
        try {
            assertNotEquals(oldPending, newPending)
            assertEquals("TIMER_REPLICA_CHANGED", rejected { writer().requireAction(timerHabit.id, TimerActionAuthority.read(first)) }.message)
            writer().requireAction(timerHabit.id, TimerActionAuthority.read(second))
        } finally { oldPending.cancel(); newPending.cancel() }
        assertEquals(1, count("timer_command_outbox")) // Neither PendingIntent was sent.
    }
    @Test fun missingOldFormatNamespaceRejectsItsActionWithoutReplayingItAsRecovery() = runBlocking<Unit> {
        start(); val fresh = requireNotNull(writer().capture(timerHabit.id))
        val oldBody = JsonObject(Json.parseToJsonElement(Json.encodeToString(fresh)).jsonObject - "storageId")
        val old = com.dayforge.data.api.decodeSyncReply(oldBody.toString().toByteArray(), 65_536,
            TimerActionAuthority.serializer(), {})
        val before = image(); var entered = false
        assertEquals("TIMER_REPLICA_CHANGED", rejected { writer().write(timerHabit.id, old) { entered = true } }.message)
        assertFalse(entered); assertEquals(before.contents, image().contents)
        val recovered = requireNotNull(writer().capture(timerHabit.id)); writer().requireAction(timerHabit.id, recovered)
        assertEquals(fresh, recovered); assertEquals(before.contents, image().contents)
        assertEquals(1, count("timer_command_outbox"))
    }
}
