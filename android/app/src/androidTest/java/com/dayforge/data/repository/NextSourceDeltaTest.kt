package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustJournal
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real source-contract gates, not a domain-validator substitute. Every NEW source starts
 * from a pre-insert frozen COUNT intent and the real completion/outbox trigger. Every journal
 * is issued/read by the actual owner at the same outer Room Job; no fabricated events/maps.
 */
@RunWith(AndroidJUnit4::class)
class NextSourceDeltaTest : NextCoreRequestFixture() {
    private val tables = listOf("sync_outbox", "timer_command_outbox")
    private class Rollback : RuntimeException()
    private data class Frame(val owner: CountTrustRuntime, val boundary: CountTrustJournal.Boundary,
        val before: Map<String, Map<Long, String>>, val watermarks: Map<String, Long>,
        val source: SyncOutboxEntity, val plan: CountLocalSourcePlan, val factId: Long)

    private suspend fun seedBefore() {
        register()
        completion(); start()
    }
    private fun native() = CountTrustRows(db.openHelper.writableDatabase)
    private suspend fun sources(): Map<String, Map<Long, String>> = tables.associateWith {
        NextRequestSql.sources(db.openHelper.writableDatabase, it)
    }
    private suspend fun image(): JsonObject = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun rollback(block: suspend () -> Unit) {
        try { db.withTransaction<Unit> { block(); throw Rollback() } }
        catch (_: Rollback) { /* Only this intentional rollback is consumed. */ }
    }
    private fun fact(uuid: String = id(64001)) = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
        uuid = uuid, value = 1, date = millis, actualCompletedAt = millis, createdAt = millis,
        recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")

    private suspend fun insertedFrame(): Frame {
        check(db.inTransaction())
        val sql = db.openHelper.writableDatabase
        val before = sources()
        val watermarks = tables.associateWith { NextRequestSql.watermark(sql, it) }
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val boundary = owner.maintenance(db) { owner.captureChangeBoundary() }
        val input = fact()
        val plan = requireNotNull(CountLocalSourcePlan.freeze(habit, input, watermarks.getValue("sync_outbox")))
        val id = db.completionDao().insertForSync(input)
        val rawFact = native().read("completions", "uuid=?", arrayOf(plan.uuid)).single()
        assertTrue(plan.matchesNativeFact(id, rawFact))
        assertEquals(input.copy(id = id), rawFact.completion())
        assertEquals(rawFact.completion(), plan.verifiedStoredFact(sql, id))
        // Select only the UUID frozen BEFORE insertion. Never discover new intent in NEW cells.
        val rawSource = native().read("sync_outbox", "recordType=? AND entityUuid=?", arrayOf("completion", plan.uuid)).single()
        val actual = requireNotNull(db.syncOutboxDao().getById(rawSource.long("id")))
        val source = requireNotNull(plan.bind(id, rawFact.completion(), actual))
        assertEquals(actual, source)
        assertEquals(NextRequestSql.sourceHash(source), NextRequestSql.rowHash(sql, "sync_outbox", "id=?", arrayOf(source.id)))
        return Frame(owner, boundary, before, watermarks, source, plan, id)
    }
    private suspend fun prepare(frame: Frame, before: Map<String, Map<Long, String>> = frame.before,
        expected: List<SyncOutboxEntity> = listOf(frame.source)): NextSourceDelta? = frame.owner.maintenance(db) {
        val window = frame.owner.sourceWindow(frame.boundary, expected.size) ?: return@maintenance null
        NextSourceDelta.prepare(frame.boundary, before, frame.watermarks, expected, window,
            frame.owner, db.openHelper.writableDatabase, native())
    }
    private suspend fun matches(frame: Frame, delta: NextSourceDelta): Boolean = frame.owner.maintenance(db) {
        val window = frame.owner.sourceWindow(frame.boundary, delta.sourceCount) ?: return@maintenance false
        delta.verifyWindow(frame.owner, window)
    }

    @Test fun actualCountSourceMatchesOriginalNonEmptyMapsAndOwnsBeforeInputs() = runBlocking<Unit> {
        seedBefore()
        db.withTransaction {
            val frame = insertedFrame()
            assertTrue(frame.before.values.all { it.isNotEmpty() })
            val before = frame.before.mapValues { LinkedHashMap(it.value) }
            val expected = before.mapValues { (table, old) -> old + if (table == "sync_outbox")
                mapOf(frame.source.id to NextRequestSql.sourceHash(frame.source)) else emptyMap() }
            val mutableBefore = before.mapValues { LinkedHashMap(it.value) }.toMutableMap()
            val inputSources = mutableListOf(frame.source)
            val delta = requireNotNull(prepare(frame, mutableBefore, inputSources))
            delta.requireCapture(frame.boundary)
            assertTrue(matches(frame, delta))
            delta.verifyCurrent(db.openHelper.writableDatabase, native())
            assertEquals(expected, sources()); assertEquals(expected, delta.completeExpected())
            assertEquals(mapOf(frame.source.id to NextRequestSql.sourceHash(frame.source)), delta.newSources("sync_outbox"))
            assertTrue(delta.newSources("timer_command_outbox").isEmpty())
            mutableBefore.values.forEach { it.clear() }; mutableBefore.clear(); inputSources.clear()
            assertEquals("Caller containers cannot erase certified before members", expected, delta.completeExpected())
            val leaked = delta.completeExpected().mapValues { it.value.toMutableMap() }.toMutableMap()
            leaked.values.forEach { it.clear() }; leaked.clear()
            assertEquals(expected, delta.completeExpected())
            val denied = rejected { (delta.newSources("sync_outbox") as MutableMap<Long, String>).clear() }
            assertTrue(denied is UnsupportedOperationException)
            assertEquals(expected, sources())
        }
    }

    @Test fun oldSourceModificationAndDeletionTakeOriginalFullFallback() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        for (delete in listOf(false, true)) {
            rollback {
                val frame = insertedFrame(); val id = frame.before.getValue("sync_outbox").keys.first()
                if (delete) db.syncOutboxDao().deleteById(id)
                else db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET lastError='changed' WHERE id=?", arrayOf(id))
                assertNull(prepare(frame))
                val actual = sources()
                assertTrue(frame.before.getValue("sync_outbox").any { (key, hash) -> actual.getValue("sync_outbox")[key] != hash })
            }
            assertEquals(stable, image())
        }
    }

    @Test fun timerUpdateDeleteOrInsertCannotUseOrdinaryCountDelta() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        for (mutation in listOf("update", "delete", "insert")) {
            rollback {
                val frame = insertedFrame(); val oldId = frame.before.getValue("timer_command_outbox").keys.first()
                when (mutation) {
                    "delete" -> db.timeLogDao().deleteTimerCommand(oldId)
                    "update" -> db.openHelper.writableDatabase.execSQL("UPDATE timer_command_outbox SET lastError='changed' WHERE id=?", arrayOf(oldId))
                    else -> {
                        val old = requireNotNull(db.timeLogDao().getTimerCommand(oldId))
                        db.timeLogDao().insertTimerCommand(old.copy(id = 0, commandId = id(64009), sequence = 2,
                            commandType = "pause", occurredAt = millis + 1, expectedControlGeneration = 1))
                    }
                }
                assertNull(prepare(frame))
                assertNotEquals(frame.before.getValue("timer_command_outbox"), sources().getValue("timer_command_outbox"))
            }
            assertEquals(stable, image())
        }
    }

    @Test fun lateChangeUsesOriginalFooterWhileNoopIsOnlyAnOptimizationMiss() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        for (noop in listOf(false, true)) {
            rollback {
                val frame = insertedFrame(); val delta = requireNotNull(prepare(frame))
                val expected = delta.completeExpected()
                assertEquals(expected, sources())
                val sql = if (noop) "UPDATE sync_outbox SET attemptCount=attemptCount WHERE id=?"
                    else "UPDATE sync_outbox SET lastError='late' WHERE id=?"
                db.openHelper.writableDatabase.execSQL(sql, arrayOf(frame.source.id))
                assertFalse(matches(frame, delta))
                assertEquals("The retained footer expectation must not adopt the changed DB", expected, delta.completeExpected())
                // This is the original footer's fresh full-map comparison. A noop does not
                // become a new COUNT business rejection just because delta reuse is unavailable.
                if (noop) assertEquals(expected, sources()) else assertNotEquals(expected, sources())
            }
            assertEquals(stable, image())
        }
    }

    @Test fun lateTypeDamageStillReachesOriginalRawTypeRefusal() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        rollback {
            val frame = insertedFrame(); val delta = requireNotNull(prepare(frame))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET attemptCount='bad' WHERE id=?", arrayOf(frame.source.id))
            assertFalse(matches(frame, delta))
            val original = rejected { sources() }
            assertTrue(original is NextRequestException)
            assertEquals(NextRequestException.Reason.INVALID_LOCAL_STATE, (original as NextRequestException).reason)
        }
        assertEquals(stable, image())
    }

    @Test fun nativeFactTypeCannotBeHiddenByRoomIntegerCoercion() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        rollback {
            val frame = insertedFrame()
            val expected = requireNotNull(db.completionDao().getCompletionByUuid(frame.plan.uuid))
            db.openHelper.writableDatabase.execSQL("UPDATE completions SET createdAt=CAST(createdAt AS REAL)+0.5 WHERE id=?", arrayOf(frame.factId))
            val raw = native().read("completions", "uuid=?", arrayOf(frame.plan.uuid)).single()
            assertEquals(expected, db.completionDao().getCompletionByUuid(frame.plan.uuid))
            assertFalse("The production caller must reject reuse before trusting the coerced DTO", frame.plan.matchesNativeFact(frame.factId, raw))
            assertNull(frame.plan.verifiedStoredFact(db.openHelper.writableDatabase, frame.factId))
        }
        assertEquals(stable, image())
    }

    @Test fun realDeletedAllocationAtOrBelowCurrentWatermarkCannotBecomeNewIntent() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        rollback {
            val frame = insertedFrame(); requireNotNull(prepare(frame))
            db.syncOutboxDao().deleteById(frame.source.id)
            val before = sources(); val sql = db.openHelper.writableDatabase
            val watermarks = tables.associateWith { NextRequestSql.watermark(sql, it) }
            assertFalse(frame.source.id in before.getValue("sync_outbox"))
            assertTrue(watermarks.getValue("sync_outbox") >= frame.source.id)
            val freshBoundary = frame.owner.maintenance(db) { frame.owner.captureChangeBoundary() }
            // Ordinary business INSERT of the exact previously allocated row: the historical
            // watermark stays high. This exercises real ID reuse, never sqlite_sequence edits.
            assertEquals(frame.source.id, db.syncOutboxDao().insert(frame.source))
            val fullAfter = sources()
            assertEquals(NextRequestSql.sourceHash(frame.source), fullAfter.getValue("sync_outbox")[frame.source.id])
            assertTrue(frame.source.id <= watermarks.getValue("sync_outbox"))
            assertEquals(frame.source, db.syncOutboxDao().getById(frame.source.id))
            val actual = frame.owner.maintenance(db) { frame.owner.pendingChanges(freshBoundary) }
            val queueInsert = actual.filter { it.table == "sync_outbox" }
            assertEquals(1, queueInsert.size)
            assertEquals("INSERT", queueInsert.single().operation)
            assertNull(queueInsert.single().old)
            assertNotNull(queueInsert.single().new)
            val failure = rejected { frame.owner.maintenance(db) {
                val window = requireNotNull(frame.owner.sourceWindow(freshBoundary, 1))
                NextSourceDelta.prepare(freshBoundary, before, watermarks, listOf(frame.source), window,
                    frame.owner, sql, native())
            } }
            assertEquals(NextRequestException.Reason.OLD_INTENT, (failure as NextRequestException).reason)
        }
        assertEquals(stable, image())
    }

    @Test fun distinctActualCaptureAndExpiredOriginalBoundaryAreNotTransferable() = runBlocking<Unit> {
        seedBefore()
        val retained = db.withTransaction { val frame = insertedFrame(); frame to requireNotNull(prepare(frame)) }
        val stable = image()
        db.withTransaction {
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            val fresh = owner.maintenance(db) { owner.captureChangeBoundary() }
            val captureFailure = rejected { retained.second.requireCapture(fresh) }
            assertEquals("SOURCE_DELTA_CAPTURE_CHANGED", captureFailure.message)
            val expired = rejected { owner.maintenance(db) { owner.sourceWindow(retained.first.boundary, 1) } }
            assertEquals("COUNT_TRUST_BOUNDARY_EXPIRED", expired.message)
        }
        assertEquals(stable, image())
    }

    @Test fun realCancellationDuringCurrentVerificationRollsBackAndFreshCaptureCanRetry() = runBlocking<Unit> {
        seedBefore(); val stable = image()
        var prepared = false; var verificationReturned = false; var transactionReturned = false
        supervisorScope {
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                db.withTransaction {
                    val frame = insertedFrame(); val delta = requireNotNull(prepare(frame)); prepared = true
                    currentCoroutineContext().cancel(CancellationException("SOURCE_DELTA_VERIFY_CANCELLED"))
                    delta.verifyCurrent(db.openHelper.writableDatabase, native())
                    verificationReturned = true
                }
                transactionReturned = true
            }
            try { child.await(); fail("Cancellation must propagate") }
            catch (cancelled: CancellationException) {
                assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { it.message.orEmpty().contains("SOURCE_DELTA_VERIFY_CANCELLED") })
            }
            assertTrue(child.isCancelled)
        }
        currentCoroutineContext().ensureActive()
        assertTrue(prepared); assertFalse(verificationReturned); assertFalse(transactionReturned)
        assertEquals(stable, image())
        db.withTransaction {
            val frame = insertedFrame(); val delta = requireNotNull(prepare(frame))
            delta.requireCapture(frame.boundary); delta.verifyCurrent(db.openHelper.writableDatabase, native())
            assertTrue(matches(frame, delta))
            assertEquals(delta.completeExpected(), sources())
        }
    }
}
