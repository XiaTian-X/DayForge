package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.local.businessDate
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.domain.model.*
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical Room, real Keystore, original local producers and actual ACK acceptance.
 * The oracle preserves the previous grouping/addExact projection after the unchanged audit;
 * it does not call CountCalculationBlocks or a second candidate CountHistoryReader.
 * Corruption and actual-END faults exist only in this isolated test APK.
 */
@RunWith(AndroidJUnit4::class)
class CountCalculationReuseSafetyTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.count-calculation.${UUID.randomUUID()}"
    private val endFaults = G0CommitFaultFactory { !it.inTransaction() }
    private val day = LocalDate.parse("2026-10-06")

    init {
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(endFaults).build()
        }
    }

    override fun tokenCipher(): TokenCipher = AndroidKeystoreTokenCipher(alias)

    @After fun removeKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private suspend fun current() = requireNotNull(db.habitDao().getHabitById(habit.id))
    private fun reader() = CountHistoryReader(db, tokens, sessions)
    private suspend fun <T> display(block: suspend (RecurringDisplayReadScope) -> T): T =
        RecurringDisplayReadScope.withSnapshot(db, tokens, sessions, block = block)

    private suspend fun countAt(value: Int, date: LocalDate = day,
        rounds: NextRoundWriteScope? = null): CompletionEntity {
        val current = current()
        val zone = ZoneId.of("UTC")
        val fact = CompletionEntity(habitId = current.id, habitUuid = current.uuid,
            uuid = UUID.randomUUID().toString(), date = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            actualCompletedAt = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            recordedLocalDate = date.toString(), recordedTimezone = zone.id, value = value)
        val append: suspend () -> Unit = {
            NextCountDayStore(db).capture(current, fact)
            db.completionDao().insertForSync(fact)
        }
        // Plain and challenge profiles have distinct producer contracts. The caller supplies
        // the freshly captured displayed round rather than silently upgrading a plain write.
        if (rounds == null) producer().write(local(), append)
        else producer().writeRounds(rounds, append)
        return requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid))
    }

    private suspend fun fault(block: suspend () -> Unit) = db.withTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        block()
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
    }

    private suspend fun durable() = db.withTransaction { nextRestartDatabaseProof(db) }

    private suspend fun oracle(today: LocalDate = day): CountHistory {
        val habit = current()
        val source = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, today)
        val quantities = source.completions.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { total, row -> Math.addExact(total, row.value.toLong()) }
        }
        return CountHistory(source.today, source.policies, quantities,
            source.policies[source.today] ?: if (source.today in source.unknownDates) null
                else CountDayPolicy(habit.targetValue, habit.isCountdown),
            source.unknownDates, source.completions, source.roundHead, source.countIdentity)
    }

    private fun assertSummary(expected: CountHistory, actual: CountSummary) {
        assertEquals(expected.today, actual.today)
        assertEquals(expected.policies, actual.policies)
        assertEquals(expected.quantities, actual.quantities)
        assertEquals(expected.todayPolicy, actual.todayPolicy)
        assertEquals(expected.unknownDates, actual.unknownDates)
        assertEquals(expected.qualifiedDates, actual.qualifiedDates)
        assertEquals(expected.firstDate, actual.firstDate)
        assertEquals(expected.todayQuantity, actual.todayQuantity)
        assertEquals(expected.completedToday, actual.completedToday)
        assertEquals(expected.quantities.values.fold(0L, Math::addExact), actual.totalQuantity)
        assertEquals(expected.completions.mapTo(linkedSetOf()) { it.businessDate }, actual.participatingDates)
        assertEquals(expected.completions.filter { it.businessDate == expected.today }.maxOfOrNull { it.id },
            actual.todayLatestCompletionId)
        assertEquals(expected.roundHead, actual.roundHead)
        assertEquals(expected.actionIdentity, actual.actionIdentity)
    }

    private suspend fun rejectsWithoutPromotionOrWrites(candidate: CountHistoryReader) {
        val before = durable()
        val promotions = candidate.reuseDiagnostics.promotions
        assertNotNull(runCatching { candidate.readSummary(current(), day) }.exceptionOrNull())
        assertNotNull(runCatching { candidate.read(current(), day) }.exceptionOrNull())
        assertEquals(promotions, candidate.reuseDiagnostics.promotions)
        assertEquals(before, durable())
    }

    @Test fun unchangedSealedMonthIsReusedWhileActiveMonthStartsFromCommittedResult() = runBlocking<Unit> {
        val activeDay = LocalDate.parse("2026-11-06")
        countAt(2)
        countAt(3, activeDay)
        val candidate = reader()
        assertEquals(oracle(activeDay), candidate.read(current(), activeDay))
        val saved = durable()
        assertSummary(oracle(activeDay), candidate.readSummary(current(), activeDay))
        val stable = requireNotNull(candidate.reuseDiagnostics.lastCalculation)
        assertEquals(2, stable.reusedBlocks)
        assertEquals(1, stable.sealedReusedBlocks)
        assertEquals(0, stable.changedBlocks)
        assertEquals(0, stable.addedContributions)
        assertEquals(0, stable.removedContributions)
        assertEquals(saved, durable())

        countAt(4, activeDay)
        assertSummary(oracle(activeDay), candidate.readSummary(current(), activeDay))
        val updated = requireNotNull(candidate.reuseDiagnostics.lastCalculation)
        assertEquals(3, updated.inputFacts)
        assertEquals(1, updated.sealedReusedBlocks)
        assertEquals(1, updated.activeUpdatedBlocks)
        assertEquals(1, updated.addedContributions)
        assertEquals(0, updated.removedContributions)
        assertEquals(oracle(activeDay), candidate.read(current(), activeDay))
    }

    @Test fun cachedArithmeticNeverBypassesRawFactTypesMembershipOrOriginalAccountProof() = runBlocking<Unit> {
        countAt(2)
        val original = countAt(3)
        val candidate = reader()
        assertEquals(oracle(), candidate.read(current(), day))
        assertEquals(1, candidate.reuseDiagnostics.committedEntries)
        for (assignment in listOf("value=4", "value=value+0.5", "actualCompletedAt=actualCompletedAt+1",
            "recordedLocalDate='2026-10-07'", "oneTimeExpectedVersion=1")) {
            fault { db.openHelper.writableDatabase.execSQL("UPDATE completions SET $assignment WHERE id=?",
                arrayOf(original.id)) }
            rejectsWithoutPromotionOrWrites(candidate)
            fault { db.completionDao().upsert(original) }
            assertEquals(oracle(), candidate.read(current(), day))
        }
        var duplicate = 0L
        fault { duplicate = db.completionDao().insertForSync(original.copy(id = 0)) }
        rejectsWithoutPromotionOrWrites(candidate)
        fault { db.completionDao().delete(requireNotNull(db.completionDao().getCompletionById(duplicate))) }
        assertEquals(oracle(), candidate.read(current(), day))

        val outbox = db.syncOutboxDao().getAll().single { it.entityUuid == original.uuid }
        val origin = originalIntent(outbox)
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_request_origins WHERE kind=? AND requestId=?",
            arrayOf(NEXT_OPERATION, origin.requestId))
        rejectsWithoutPromotionOrWrites(candidate)
        db.nextRequestDao().insertOrigin(origin)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(id(99), NEXT_OPERATION, origin.requestId))
        rejectsWithoutPromotionOrWrites(candidate)
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(origin.accountId, NEXT_OPERATION, origin.requestId))
        assertEquals(oracle(), candidate.read(current(), day))
    }

    @Test fun equalTotalsCannotHideReplacedIdentityChangedValuesOrMovedBusinessDates() = runBlocking<Unit> {
        val first = countAt(2)
        val second = countAt(3)
        val candidate = reader()
        assertEquals(5L, candidate.read(current(), day).todayQuantity)
        val repo = creatingHabits()
        repo.undoCompletion(app, first.id)
        val replacement = countAt(2)
        val sameTotal = candidate.read(current(), day)
        assertEquals(oracle(), sameTotal)
        assertEquals(5L, sameTotal.todayQuantity)
        assertFalse(sameTotal.completions.any { it.uuid == first.uuid })
        assertTrue(sameTotal.completions.any { it.uuid == replacement.uuid })
        assertEquals(1, candidate.reuseDiagnostics.lastCalculation!!.addedContributions)
        assertEquals(1, candidate.reuseDiagnostics.lastCalculation!!.removedContributions)

        repo.undoCompletion(app, replacement.id)
        repo.undoCompletion(app, second.id)
        countAt(5, day.plusDays(1))
        val moved = candidate.read(current(), day)
        assertEquals(oracle(), moved)
        assertEquals(mapOf(day.plusDays(1) to 5L), moved.quantities)
        assertEquals(0L, moved.todayQuantity)
        assertEquals(day.plusDays(1), moved.firstDate)
        assertEquals(1, candidate.reuseDiagnostics.lastCalculation!!.addedContributions)
        assertEquals(2, candidate.reuseDiagnostics.lastCalculation!!.removedContributions)
    }

    @Test fun actualAckCanReuseArithmeticButMustDeliverFreshNormalizedDetailAndRejectOldQueueReplay() = runBlocking<Unit> {
        register()
        val fact = countAt(3)
        val row = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid }
        val candidate = reader()
        val before = candidate.read(current(), day)
        val normalized = "2026-10-06T00:00:00.000123Z"
        val (http, _) = channel { input -> successReply(input) { body ->
            JsonObject(body + ("created_at" to JsonPrimitive(normalized)))
        } }
        val core = sender(http)
        val delivery = requireNotNull(core.sendOperation(access(), row.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, core.acceptOperation(delivery))
        assertNull(db.syncOutboxDao().getById(row.id))
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        val summary = candidate.readSummary(current(), day)
        assertSummary(oracle(), summary)
        val reused = candidate.reuseDiagnostics.lastCalculation!!
        assertEquals(1, reused.reusedBlocks)
        assertEquals(0, reused.changedBlocks)
        assertEquals(0, reused.addedContributions)
        assertEquals(0, reused.removedContributions)
        val detail = candidate.read(current(), day)
        assertEquals(oracle(), detail)
        assertEquals(before.quantities, detail.quantities)
        assertNotEquals(before.completions.single().createdAt, detail.completions.single().createdAt)
        assertEquals(Instant.parse(normalized).toEpochMilli(), detail.completions.single().createdAt)
        val committed = durable()
        assertEquals(NextOperationAcceptance.REPLAYED, core.acceptOperation(delivery))
        assertEquals(committed, durable())

        // The original ACK gate, not numeric equality, owns terminal queue integrity.
        db.syncOutboxDao().insert(row)
        val damaged = durable()
        assertNotNull(runCatching { core.acceptOperation(delivery) }.exceptionOrNull())
        assertEquals(damaged, durable())
    }

    @Test fun undoToZeroDropsParticipationButRetainsOriginalDayRuleAcrossDirectionChange() = runBlocking<Unit> {
        val fact = countAt(3)
        val candidate = reader()
        assertEquals(CountDayPolicy(10, false), candidate.read(current(), day).todayPolicy)
        creatingHabits().undoCompletion(app, fact.id)
        val empty = candidate.read(current(), day)
        assertEquals(oracle(), empty)
        assertTrue(empty.quantities.isEmpty())
        assertTrue(empty.completions.isEmpty())
        assertTrue(empty.summary.participatingDates.isEmpty())
        assertTrue(empty.qualifiedDates.isEmpty())
        assertNull(empty.firstDate)
        assertEquals(CountDayPolicy(10, false), empty.policies[day])
        assertEquals(1, candidate.reuseDiagnostics.lastCalculation!!.removedContributions)
        producer().write(local()) { db.habitDao().update(current().copy(targetValue = 5, isCountdown = true)) }
        countAt(4)
        countAt(6, day.plusDays(1))
        val updated = candidate.read(current(), day.plusDays(1))
        assertEquals(oracle(day.plusDays(1)), updated)
        assertEquals(CountDayPolicy(10, false), updated.policies[day])
        assertEquals(CountDayPolicy(5, true), updated.policies[day.plusDays(1)])
        assertFalse(day in updated.qualifiedDates)
        assertTrue(day.plusDays(1) in updated.qualifiedDates)
    }

    @Test fun bareTransactionNeverPromotesAndOwnedScopePromotesOnlyAfterActualEnd() = runBlocking<Unit> {
        countAt(2)
        val candidate = reader()
        val expected = oracle()
        sessions.exclusive { db.withTransaction {
            assertEquals(expected, candidate.readInTransaction(current(), day))
            assertEquals(0L, candidate.reuseDiagnostics.promotions)
        } }
        assertEquals(0L, candidate.reuseDiagnostics.promotions)
        val rolledBack = durable()
        assertEquals("ROLL_BACK_CANDIDATE", rejected { sessions.exclusive { db.withTransaction {
            candidate.readInTransaction(current(), day)
            error("ROLL_BACK_CANDIDATE")
        } } }.message)
        assertEquals(0L, candidate.reuseDiagnostics.promotions)
        assertEquals(rolledBack, durable())

        val summary = display { scope ->
            candidate.readSummaryInTransaction(current(), day, scope).also {
                assertEquals(0L, candidate.reuseDiagnostics.promotions)
                assertEquals(0, candidate.reuseDiagnostics.committedEntries)
            }
        }
        assertSummary(expected, summary)
        assertEquals(1L, candidate.reuseDiagnostics.promotions)
        assertEquals(1, candidate.reuseDiagnostics.committedEntries)
        assertEquals(rolledBack, durable())
    }

    @Test fun finalFreshAuthFailureNeverPromotesAndRealAccountDeviceTransitionsClearReuse() = runBlocking<Unit> {
        register()
        countAt(2)
        val candidate = reader()
        candidate.read(current(), day)
        val original = dataStore.data.first()
        val promotions = candidate.reuseDiagnostics.promotions
        val before = durable()
        try {
            assertEquals("RECURRING_READ_SESSION_CHANGED", rejected { display { scope ->
                candidate.readSummaryInTransaction(current(), day, scope)
                dataStore.edit { it[stringPreferencesKey("access_token")] = "enc:v1:broken" }
            } }.message)
            assertEquals(promotions, candidate.reuseDiagnostics.promotions)
            assertEquals(before, durable())
        } finally { dataStore.updateData { original } }
        assertEquals(oracle(), candidate.read(current(), day))

        var invalidations = candidate.reuseDiagnostics.invalidations
        val oldIdentity = candidate.read(current(), day).actionIdentity
        tokens.saveLoginSession("replacement", "replacement-refresh", "member", id(1), false)
        assertEquals(0, candidate.reuseDiagnostics.committedEntries)
        assertTrue(candidate.reuseDiagnostics.invalidations > invalidations)
        val relogged = candidate.read(current(), day)
        assertNotEquals(oldIdentity, relogged.actionIdentity)
        assertEquals(0, candidate.reuseDiagnostics.lastCalculation!!.reusedBlocks)

        invalidations = candidate.reuseDiagnostics.invalidations
        tokens.saveDeviceRegistration(id(98), caps, true, 1)
        assertEquals(0, candidate.reuseDiagnostics.committedEntries)
        assertTrue(candidate.reuseDiagnostics.invalidations > invalidations)
        dataStore.updateData { original }
        candidate.read(current(), day)
        invalidations = candidate.reuseDiagnostics.invalidations
        // Deliberately violate the login caller's DB-clearing duty; the old origin must still reject.
        tokens.saveLoginSession("other", "other-refresh", "member", id(99), false)
        assertEquals(0, candidate.reuseDiagnostics.committedEntries)
        assertTrue(candidate.reuseDiagnostics.invalidations > invalidations)
        rejectsWithoutPromotionOrWrites(candidate)
        dataStore.updateData { original }
        assertEquals(oracle(), candidate.read(current(), day))
    }

    @Test fun cancelledScopeDiscardsPreparedStateAndReleasesAccountResource() = runBlocking<Unit> {
        countAt(2)
        val candidate = reader()
        candidate.read(current(), day)
        countAt(3)
        val promotions = candidate.reuseDiagnostics.promotions
        val before = durable()
        val prepared = CompletableDeferred<RecurringDisplayReadScope>()
        val job = launch { display { scope ->
            candidate.readSummaryInTransaction(current(), day, scope)
            prepared.complete(scope)
            awaitCancellation()
        } }
        val abandoned = withTimeout(10_000) { prepared.await() }
        job.cancelAndJoin()
        assertEquals(promotions, candidate.reuseDiagnostics.promotions)
        assertEquals(before, durable())
        val result = withTimeout(10_000) { display { scope ->
            assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { abandoned.borrow(db, tokens) }.message)
            candidate.readInTransaction(current(), day, scope)
        } }
        assertEquals(oracle(), result)
        assertEquals(promotions + 1, candidate.reuseDiagnostics.promotions)
    }

    @Test fun lostActualEndResultCannotPromoteAndReopenRequiresFreshAuditedCalculation() = runBlocking<Unit> {
        countAt(2)
        val candidate = reader()
        candidate.read(current(), day)
        countAt(3)
        val promotions = candidate.reuseDiagnostics.promotions
        val before = durable()
        val trace = G0PhaseTrace(driverTiming = false)
        val failure = rejected { trace.run { display { scope ->
            candidate.readSummaryInTransaction(current(), day, scope)
            endFaults.failAfterNextEndIn(trace)
        } } }
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "G0_COMMIT_RESULT_LOST" })
        assertFalse(endFaults.failAfterNextEnd.get())
        assertEquals(promotions, candidate.reuseDiagnostics.promotions)
        assertEquals(before, durable())
        storage.reopen()
        val reopened = reader()
        assertEquals(0, reopened.reuseDiagnostics.committedEntries)
        assertEquals(oracle(), reopened.read(current(), day))
        assertEquals(0, reopened.reuseDiagnostics.lastCalculation!!.reusedBlocks)
        assertEquals(1L, reopened.reuseDiagnostics.promotions)
    }

    private suspend fun challenge() {
        register()
        val checkpoints = listOf(habit, timerHabit).map {
            val head = initialChallengeRoundHead(it.uuid)
            ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null)))
        }
        fun change(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
            JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val changes = listOf(habit, timerHabit).map { change("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } +
            change("metric", metric.uuid, NextStructureMapper.writeMetric(metric))
        val http = channel().first
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once),
            NextTimerRequestStore(db, tokens, sessions, sender(http))).bootstrap(access(), null,
            RoundSyncBootstrapResponse(changes, 20, time, emptyList(), 1, checkpoints, emptyList()))
    }

    @Test fun restartUsesSeparateRoundStateAndStillRejectsCorruptionHiddenInOldRound() = runBlocking<Unit> {
        habit = habit.copy(targetCycles = 3, isActive = false)
        fault { db.habitDao().update(habit) }
        challenge()
        val beforeWrongProfile = durable()
        assertEquals("SYNC_CHALLENGE_PROFILE_REQUIRED", rejected { countAt(3) }.message)
        assertEquals(beforeWrongProfile, durable())
        val oldFact = countAt(3, rounds = producer().captureRounds())
        val candidate = reader()
        val old = candidate.read(current(), day)
        assertEquals(3L, old.todayQuantity)
        val repo = creatingHabits()
        val displayed = repo.getRecurringSnapshot(current())
        NextChallengeRestartRepository(db, tokens, sessions, channel().first)
            .restart(displayed.habit, displayed.authority)
        val afterRestart = durable()
        assertEquals("COUNT_ACTION_EXPIRED", rejected {
            repo.logCompletion(app, habit.id, countIdentity = requireNotNull(old.actionIdentity))
        }.message)
        assertEquals(afterRestart, durable())
        val fresh = candidate.read(current(), day)
        assertEquals(oracle(), fresh)
        assertNotEquals(old.roundHead, fresh.roundHead)
        assertEquals(0L, fresh.todayQuantity)
        assertTrue(fresh.completions.isEmpty())
        assertEquals(0, candidate.reuseDiagnostics.lastCalculation!!.reusedBlocks)
        countAt(2, rounds = producer().captureRounds())
        assertEquals(oracle(), candidate.read(current(), day))
        assertEquals(2L, candidate.readSummary(current(), day).todayQuantity)
        // Selection of the new round must not reduce the original retained-history rejection range.
        fault { db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=4 WHERE id=?", arrayOf(oldFact.id)) }
        rejectsWithoutPromotionOrWrites(candidate)
        fault { db.completionDao().upsert(oldFact) }
        assertEquals(oracle(), candidate.read(current(), day))
    }
}
