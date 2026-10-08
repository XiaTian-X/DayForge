package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.domain.model.CountDayPolicy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real account preferences, file Room and loopback original-request/receipt boundary. */
@RunWith(AndroidJUnit4::class)
class NextCountDayWorkflowTest : NextCoreRequestFixture() {
    private suspend fun countAt(value: Int = 1, stamp: String = time, zone: String = "Asia/Shanghai"):
        com.dayforge.data.local.entity.SyncOutboxEntity {
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val instant = Instant.parse(stamp)
        val fact = CompletionEntity(habitId = current.id, habitUuid = current.uuid, uuid = UUID.randomUUID().toString(),
            date = instant.atZone(java.time.ZoneId.of(zone)).toLocalDate().atStartOfDay(java.time.ZoneId.of(zone)).toInstant().toEpochMilli(),
            actualCompletedAt = instant.toEpochMilli(), value = value, recordedTimezone = zone)
        producer().write(local()) {
            NextCountDayStore(db).capture(current, fact)
            db.completionDao().insertForSync(fact)
        }
        return db.syncOutboxDao().getAll().last { it.entityUuid == fact.uuid }
    }
    private suspend fun rule(date: String = "2026-10-06") = db.withTransaction {
        NextCountDayStore(db).read(requireNotNull(db.habitDao().getHabitById(habit.id)), date)
    }
    private fun accepted(input: MaterialSocketServer.Input): MaterialSocketServer.Reply =
        if (input.path.endsWith("/identity")) reply(input) else
            successReply(input, (wireOperation(input)["base_revision"]?.jsonPrimitive?.longOrNull ?: 0) + 1)

    @Test fun originalDaySurvivesGoalIncreaseDecreaseDirectionChangeUndoAllAndColdReopen() = runBlocking<Unit> {
        val first = countAt(6)
        val original = requireNotNull(rule())
        assertEquals(CountDayPolicy(10, false), original.policy)
        for ((target, direction) in listOf(15 to true, 5 to false)) {
            producer().write(local()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(
                targetValue = target, isCountdown = direction)) }
            val row = countAt(1)
            assertEquals(original, rule())
            assertEquals(original.policy.toJson(), Json.parseToJsonElement(originalIntent(row).intentJson).jsonObject
                .getValue("payload").jsonObject["count_policy"])
        }
        producer().write(local()) { db.completionDao().deleteByHabitId(habit.id) }
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
        assertEquals(original, rule())
        val again = countAt(2)
        val next = countAt(3, stamp = "2026-10-07T00:00:00Z")
        assertEquals(CountDayPolicy(10, false), rule()!!.policy)
        assertEquals(CountDayPolicy(5, false), rule("2026-10-07")!!.policy)
        assertEquals(listOf(2, 3), db.completionDao().getByHabitOnce(habit.id).map { it.value })
        val origins = listOf(first, again, next).map { originalIntent(it) }
        storage.reopen()
        assertEquals(original, rule())
        assertEquals(origins, listOf(first, again, next).map { originalIntent(it) })
    }

    @Test fun firstCountAndFutureEditRequireExactPriorAcksInBothSendEntrypoints() = runBlocking<Unit> {
        producer().write(local()) { db.habitDao().update(habit.copy(name = "Before counting")) }
        val before = db.syncOutboxDao().getAll().single()
        val first = countAt(6)
        producer().write(local()) { db.habitDao().update(requireNotNull(db.habitDao().getHabitById(habit.id)).copy(targetValue = 15)) }
        val after = db.syncOutboxDao().getAll().last()
        val second = countAt(1)
        register()
        val (http, server) = channel(::accepted)
        val core = sender(http)
        for (request in listOf(first, second)) {
            assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
                (rejected { core.sendOperation(access(), request.operationId) } as NextRequestException).reason)
            assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, request.operationId))
        }
        core.sendAndAcceptOperation(access(), before.operationId)
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { core.sendAndAcceptOperation(access(), after.operationId) } as NextRequestException).reason)
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, after.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, core.sendAndAcceptOperation(access(), first.operationId))
        core.sendAndAcceptOperation(access(), after.operationId)
        assertEquals(NextOperationAcceptance.COMMITTED, core.sendAndAcceptOperation(access(), second.operationId))
        assertEquals(15, db.habitDao().getHabitById(habit.id)!!.targetValue)
        assertEquals(10, rule()!!.targetValue)
        assertEquals(7, db.completionDao().getByHabitOnce(habit.id).sumOf { it.value })
        assertEquals(4, server.requests.count { it.path.endsWith("/push") })
    }

    @Test fun deliveredUnacceptedFactStillBlocksEditAndColdReplayKeepsOriginalBytes() = runBlocking<Unit> {
        val first = countAt(3)
        val origin = originalIntent(first)
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue = 5, isCountdown = true)) }
        val edit = db.syncOutboxDao().getAll().last()
        register()
        val (http, server) = channel(::accepted)
        sender(http).sendOperation(access(), first.operationId)
        val bytes = transmission(NEXT_OPERATION, first.operationId).wireBytes.copyOf()
        storage.reopen()
        assertEquals(NextRequestException.Reason.CAUSAL_PREDECESSOR_PENDING,
            (rejected { sender(http).sendAndAcceptOperation(access(), edit.operationId) } as NextRequestException).reason)
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), first.operationId))
        assertArrayEquals(bytes, transmission(NEXT_OPERATION, first.operationId).wireBytes)
        assertEquals(origin, originalIntent(first))
        sender(http).sendAndAcceptOperation(access(), edit.operationId)
        assertEquals(CountDayPolicy(10, false), rule()!!.policy)
        assertEquals(2, server.requests.count { it.path.endsWith("/push") && wireOrIdentity(it) == first.operationId })
    }

    @Test fun dayFactOutboxAndOriginalProofRollbackOnCancellationOriginAndLateDayDamage() = runBlocking<Unit> {
        val sql = db.openHelper.writableDatabase
        for (trigger in listOf(
            "BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(ABORT,'original fault'); END",
            "AFTER INSERT ON next_request_origins BEGIN UPDATE count_days SET targetValue=9; END"
        )) {
            sql.execSQL("CREATE TRIGGER fail_count $trigger")
            rejected { countAt(2) }
            assertEquals(0, count("count_days")); assertEquals(0, count("completions"))
            assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
            sql.execSQL("DROP TRIGGER fail_count")
        }
        try {
            producer().write(local()) {
                val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, date = millis, actualCompletedAt = millis,
                    recordedTimezone = "Asia/Shanghai")
                NextCountDayStore(db).capture(habit, fact)
                db.completionDao().insertForSync(fact)
                throw CancellationException("cancel before commit")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, count("count_days")); assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        assertNotNull(countAt(2))
    }

    @Test fun sameLiteralBusinessDayInDifferentZonesRetainsOneOriginalRule() = runBlocking<Unit> {
        countAt(2, "2026-10-05T16:30:00Z", "Asia/Shanghai")
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue = 15, isCountdown = true)) }
        countAt(3, "2026-10-06T20:00:00Z", "UTC")
        assertEquals(1, count("count_days")); assertEquals(CountDayPolicy(10, false), rule()!!.policy)
        assertEquals(listOf("2026-10-06", "2026-10-06"), db.completionDao().getByHabitOnce(habit.id).map { it.recordedLocalDate })
        assertEquals(5, db.completionDao().getByHabitOnce(habit.id).sumOf { it.value })
    }

    @Test fun damagedBirthRuleCannotBeReplacedByCurrentConfigurationOrRetargetAnotherAccountDevice() = runBlocking<Unit> {
        countAt(2)
        val original = requireNotNull(rule())
        db.openHelper.writableDatabase.execSQL("UPDATE count_days SET targetValue=11")
        rejected { rule() }
        rejected { countAt(1) }
        assertEquals(1, count("completions")); assertEquals(1, count("sync_outbox"))
        db.openHelper.writableDatabase.execSQL("UPDATE count_days SET targetValue=10")
        assertEquals(original, rule())
        register(id(4))
        val first = db.syncOutboxDao().getAll().single()
        // Registration captured before the first count binds device identity. This day was
        // born before registration, so account context still cannot be replaced on send.
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(90), false)
        tokens.saveServerIdentity(id(2), id(3)); tokens.saveDeviceRegistration(id(4), caps, true, 2)
        val (http, server) = channel(::accepted)
        rejected { sender(http).sendOperation(access(), first.operationId) }
        assertTrue(server.requests.none { it.path.endsWith("/push") })
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, first.operationId))
    }

    @Test fun legacyUnknownCountIsNeverAssignedCurrentGoalWhenItsOriginalProofIsAbsent() = runBlocking<Unit> {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                date = millis, actualCompletedAt = millis, value = 6, recordedTimezone = "Asia/Shanghai"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("COUNT_DAY_POLICY_UNKNOWN", rejected { countAt(2) }.message)
        assertEquals(0, count("count_days")); assertEquals(1, count("completions")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun missingDayAfterUndoAllCannotRecaptureEditedRuleFromCurrentConfiguration() = runBlocking<Unit> {
        val first = countAt(6)
        val original = originalIntent(first)
        producer().write(local()) { db.completionDao().deleteByHabitId(habit.id) }
        producer().write(local()) { db.habitDao().update(habit.copy(targetValue = 5, isCountdown = true)) }
        val queue = db.syncOutboxDao().getAll()
        val origins = queue.map { originalIntent(it) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM count_days")
        storage.reopen()
        assertEquals("COUNT_DAY_INVALID", rejected { countAt(2) }.message)
        assertEquals(0, count("count_days")); assertEquals(0, count("completions"))
        assertEquals(queue, db.syncOutboxDao().getAll())
        assertEquals(origins, queue.map { originalIntent(it) })
        assertEquals(original, originalIntent(first))
        assertNull(db.nextRequestDao().transmission(NEXT_OPERATION, first.operationId))
        countAt(2, stamp = "2026-10-07T00:00:00Z")
        assertEquals(CountDayPolicy(5, true), rule("2026-10-07")!!.policy)
        assertNull(rule())
        assertEquals(original, originalIntent(first))
    }

    @Test fun lateReceiptAndQueueTriggersCannotCommitAChangedOrMissingDayRule() = runBlocking<Unit> {
        val first = countAt(3)
        val day = requireNotNull(rule())
        val origin = originalIntent(first)
        register(); val (http, _) = channel(::accepted)
        val delivery = requireNotNull(sender(http).sendOperation(access(), first.operationId))
        val wire = transmission(NEXT_OPERATION, first.operationId).wireBytes.copyOf()
        for (fault in listOf(
            "AFTER INSERT ON next_acceptances BEGIN UPDATE count_days SET targetValue=11; END",
            "AFTER DELETE ON sync_outbox BEGIN UPDATE count_days SET isCountdown=1; END",
            "AFTER INSERT ON next_acceptances BEGIN DELETE FROM count_days; END"
        )) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER damage_day $fault")
            rejected { sender(http).acceptOperation(delivery) }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER damage_day")
            storage.reopen()
            assertEquals(day, rule()); assertEquals(origin, originalIntent(first))
            assertEquals(first, db.syncOutboxDao().getById(first.id))
            assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, first.operationId))
            assertNull(db.syncOutboxDao().getState("activity_event", first.entityUuid))
            assertArrayEquals(wire, transmission(NEXT_OPERATION, first.operationId).wireBytes)
        }
        assertEquals(NextOperationAcceptance.COMMITTED, sender(http).acceptOperation(delivery))
        assertEquals(day, rule()); assertNull(db.syncOutboxDao().getById(first.id))
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).sendAndAcceptOperation(access(), first.operationId))
    }
}
