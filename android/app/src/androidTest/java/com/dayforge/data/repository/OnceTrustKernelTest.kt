package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.MetricLogEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.OneTimeIntent
import com.dayforge.domain.model.OneTimeState
import com.dayforge.domain.model.PendingOneTimeIntent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical original CAS/immutable writes and full retained-fact oracle. No fake database,
 * cached authority, altered clock or typed COUNT receipt is used by ONCE qualification.
 */
@RunWith(AndroidJUnit4::class)
class OnceTrustKernelTest : NextCoreRequestFixture() {
    private val historicalReads = AtomicInteger()
    private val duplicateNativeWrite = AtomicBoolean()
    private val observeAutoIds = AtomicBoolean()
    private val nativeAutoIds = AtomicReference<List<NativeAutoId>>(emptyList())
    private data class NativeAutoId(val table: String, val sequence: Long, val maximum: Long,
        val tempRows: Long, val tempSequence: Long, val inserted: Long = 0)
    private val cancelRoot = AtomicBoolean()
    private val rootJob = AtomicReference<Job?>()
    init {
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val sql = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ")
                if (sql.startsWith("select * from completions where habitid = ? order by id")) historicalReads.incrementAndGet()
                if (sql.startsWith("insert or replace into count_trust_root(") && cancelRoot.compareAndSet(true, false))
                    requireNotNull(rootJob.get()).cancel(CancellationException("ONCE_TEST_CANCEL_ROOT"))
            }
        }, ExtraNativeWrite(duplicateNativeWrite, observeAutoIds, nativeAutoIds)) }
    }
    private fun store() = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
    private suspend fun onceOnly() {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().delete(timerHabit)
            habit = habit.copy(habitType = HabitType.CHECK_IN, schedule = HabitSchedule.Once(), targetValue = 1,
                isCountdown = false, targetCycles = null, bestTime = null, completionPolicy = "one_and_done",
                oneTimeConfirmedVersion = 0, oneTimeConfirmedHeadEventUuid = null, oneTimeConfirmedCompletionEventUuid = null)
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private fun command(state: OneTimeState) = OneTimeLocalCommand(habit.uuid,
        PendingOneTimeIntent(UUID.randomUUID().toString(), OneTimeIntent(UUID.randomUUID().toString(),
            if (state.completionEventUuid == null) "complete" else "undo", state.version, state.headEventUuid,
            state.completionEventUuid)), millis, "Asia/Shanghai")
    private suspend fun append() = store().read(habit.uuid).let { before -> store().append(before.session,
        command(before.queue.optimisticState)) }
    private suspend fun original() = sessions.exclusive { db.withTransaction {
        store().readOriginalInTransaction(habit.uuid, local())
    } }
    private suspend fun qualified(): Boolean = sessions.exclusive { db.withTransaction {
        CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess()))?.onceEligible(habit) == true
    } }
    private fun dirty(): Long = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private suspend fun exactMain() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected invalid ONCE state, got $failure", failure is OneTimeLocalException)
        assertEquals(OneTimeLocalException.Reason.INVALID_LOCAL_STATE, (failure as OneTimeLocalException).reason)
    }

    @Test fun onceOnlyCompleteUndoCompleteKeepsWarmCurrentStateAndColdReopen() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly()
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(requireNotNull(tokens.localCoreWriteAccess()), prepare = true) { }
        } }
        assertTrue(qualified())
        // Deleted high IDs remain in MAIN's AUTOINCREMENT sequence. The real TEMP journal
        // has a different sequence; MAX(id)+1 and an unqualified sqlite_sequence are wrong.
        val frozen = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val obsoleteFact = CompletionEntity(id = 700_000, habitId = habit.id, habitUuid = habit.uuid,
                uuid = id(430), date = millis, actualCompletedAt = millis, value = 1, createdAt = millis,
                recordedTimezone = "UTC", recordedLocalDate = "2026-10-06",
                oneTimeAction = "complete", oneTimeExpectedVersion = 0)
            val obsoleteQueue = SyncOutboxEntity(id = 800_000, operationId = id(431), recordType = "one_time_completion",
                entityUuid = obsoleteFact.uuid, wireEntityUuid = obsoleteFact.uuid, action = "upsert",
                referenceUuid = habit.uuid, createdAt = millis)
            val obsoleteLog = MetricLogEntity(id = 900_000, metricId = metric.id, date = millis, value = 1.0,
                unit = metric.unit, uuid = id(432), createdAt = millis, updatedAt = millis,
                recordedTimezone = "UTC", recordedLocalDate = "2026-10-06")
            assertEquals(obsoleteFact.id, db.completionDao().insertForSync(obsoleteFact))
            assertEquals(obsoleteQueue.id, db.syncOutboxDao().insert(obsoleteQueue))
            assertEquals(obsoleteLog.id, db.metricLogDao().insert(obsoleteLog))
            db.completionDao().delete(obsoleteFact)
            db.syncOutboxDao().deleteById(obsoleteQueue.id)
            db.metricLogDao().delete(obsoleteLog)
            val ids = listOf("completions", "sync_outbox", "metric_logs").associateWith {
                requireNotNull(OnceTrustEvidence.nextId(db, it))
            }
            assertEquals(mapOf("completions" to 700_001L, "sync_outbox" to 800_001L, "metric_logs" to 900_001L), ids)
            observeAutoIds.set(true)
            val actualLog = db.metricLogDao().insert(obsoleteLog.copy(id = 0, uuid = id(433)))
            assertEquals(ids.getValue("metric_logs"), actualLog)
            db.metricLogDao().delete(obsoleteLog.copy(id = actualLog, uuid = id(433)))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            ids
        }
        val first = append()
        observeAutoIds.set(false)
        assertEquals(frozen.getValue("completions"), first.factId)
        assertEquals(frozen.getValue("sync_outbox"), db.syncOutboxDao().getActivityIntents(habit.uuid).single().id)
        val native = nativeAutoIds.get()
        assertEquals(frozen.keys, native.map { it.table }.toSet())
        assertEquals(3, native.size)
        native.forEach { observed ->
            assertTrue("Deleted MAIN high-water mark must exceed remaining MAX(id)", observed.sequence > observed.maximum)
            assertTrue("Same physical TEMP journal must already contain original business writes", observed.tempRows > 0)
            assertTrue(observed.tempSequence > 0)
            assertEquals(frozen.getValue(observed.table), observed.inserted)
            assertEquals(Math.addExact(observed.sequence, 1L), observed.inserted)
        }
        assertTrue(qualified())
        repeat(2) { append(); assertTrue(qualified()) }
        assertEquals(listOf("complete", "undo", "complete"), db.completionDao().getByHabitOnce(habit.id).map { it.oneTimeAction })
        val queue = db.syncOutboxDao().getActivityIntents(habit.uuid)
        assertEquals(3, queue.size)
        historicalReads.set(0)
        val expected = original()
        assertTrue("Original oracle must calibrate the full-history query", historicalReads.get() > 0)
        val before = exactMain()
        historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid))
        assertEquals(0, historicalReads.get())
        assertEquals(before, exactMain())
        var closedScope: RecurringDisplayReadScope? = null
        historicalReads.set(0)
        val borrowed = RecurringDisplayReadScope.withSnapshot(db, tokens, sessions,
            prepareCountProof = false, openOnceProof = true) { scope ->
            closedScope = scope
            val first = store().readInTransaction(habit.uuid, expected.session, scope)
            val second = store().readInTransaction(habit.uuid, expected.session, scope)
            val childFailure = supervisorScope {
                val child = async { store().readInTransaction(habit.uuid, expected.session, scope) }
                runCatching { child.await() }.exceptionOrNull()
            }
            assertTrue(childFailure is IllegalStateException)
            assertEquals("DISPLAY_READ_SCOPE_CLOSED", childFailure?.message)
            first to second
        }
        assertEquals(expected to expected, borrowed); assertEquals(0, historicalReads.get())
        assertEquals(before, exactMain())
        val closed = runCatching { sessions.exclusive { db.withTransaction {
            store().readInTransaction(habit.uuid, expected.session, requireNotNull(closedScope))
        } } }.exceptionOrNull()
        assertTrue(closed is IllegalStateException); assertEquals("DISPLAY_READ_SCOPE_CLOSED", closed?.message)
        assertEquals(before, exactMain())
        storage.reopen()
        historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid))
        assertEquals(0, historicalReads.get())
        assertTrue(qualified())
        assertEquals(queue, db.syncOutboxDao().getActivityIntents(habit.uuid))
    }

    @Test fun olderInvalidFactCannotHideBehindCurrentHeadAndReadNeverRepairsRoot() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly(); repeat(3) { append() }; assertTrue(qualified())
        val first = db.completionDao().getByHabitOnce(habit.id).first()
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET oneTimeExpectedVersion=2 WHERE id=?", arrayOf(first.id))
        val damaged = exactMain()
        expectInvalid { store().read(habit.uuid) }
        assertEquals(damaged, exactMain())
        assertEquals(1L, dirty())
    }

    @Test fun validMissingOlderConfirmedHistoryQualifiesButMissingHeadFailsWithoutWrites() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly()
        val head = id(401); val previous = id(400)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.completionDao().insertForSync(CompletionEntity(habitId = habit.id, habitUuid = habit.uuid,
                uuid = head, date = Instant.parse("2026-10-05T16:00:00Z").toEpochMilli(), value = 1,
                actualCompletedAt = millis, createdAt = millis, recordedTimezone = "Asia/Shanghai", recordedLocalDate = "2026-10-06",
                oneTimeAction = "complete", oneTimeExpectedVersion = 2, oneTimeExpectedHeadEventUuid = previous))
            habit = habit.copy(oneTimeConfirmedVersion = 3, oneTimeConfirmedHeadEventUuid = head,
                oneTimeConfirmedCompletionEventUuid = head)
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val expected = original()
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(requireNotNull(tokens.localCoreWriteAccess()), prepare = true) { }
        } }
        assertTrue(qualified()); historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid)); assertEquals(0, historicalReads.get())
        db.openHelper.writableDatabase.execSQL("DELETE FROM completions WHERE uuid=?", arrayOf(head))
        val missing = exactMain()
        expectInvalid { store().read(habit.uuid) }
        assertEquals(missing, exactMain())
    }

    @Test fun actualSameValueNativeExtraEventKeepsWholeQualificationDirty() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly(); append(); assertTrue(qualified())
        duplicateNativeWrite.set(true)
        val saved = append()
        assertFalse(duplicateNativeWrite.get())
        assertEquals(1L, dirty())
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(2, db.syncOutboxDao().getActivityIntents(habit.uuid).size)
        historicalReads.set(0)
        assertEquals(saved.snapshot, store().read(habit.uuid))
        assertTrue(historicalReads.get() > 0)
        assertEquals(original(), saved.snapshot)
    }

    @Test fun oldCountRootNeedsIndependentOnceCoverageAndSignedOrphanCannotBeAdopted() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        // Existing COUNT root includes raw ONCE rows, but no ONCE business certificate.
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = habit.copy(id = 0, uuid = id(402), name = "Independent once", habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Once(), targetValue = 1, targetCycles = null, bestTime = null,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
            habit = row.copy(id = db.habitDao().insert(row))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).prepareInTransaction(requireNotNull(tokens.localCoreWriteAccess()))
        } }
        historicalReads.set(0)
        store().read(habit.uuid)
        assertTrue(historicalReads.get() > 0)
        assertFalse(qualified())
        sessions.exclusive { db.withTransaction {
            val kernel = CountTrustKernel(db, tokens, sessions)
            kernel.withOnceCapture(requireNotNull(tokens.localCoreWriteAccess()), prepare = true) { assertNotNull(it) }
        } }
        assertTrue(qualified())
        // Re-sign a deliberately inconsistent component using the actual test Keystore path.
        sessions.exclusive { db.withTransaction {
            val access = requireNotNull(tokens.localCoreWriteAccess())
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(access))
            val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
            owner.maintenance(db) {
                context.index.putAll(mapOf(OnceTrustEvidence.COVERAGE_KEY to null, OnceTrustEvidence.HABITS_KEY to null))
                CountTrustSeal.store(db.openHelper.writableDatabase, CountTrustSeal().prepare(context.header.binding,
                    context.header.schemaHash, context.index.root, context.header.revision + 1, context.header.generation))
            }
        } }
        val before = exactMain()
        val error = runCatching { sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(requireNotNull(tokens.localCoreWriteAccess()), prepare = true) { }
        } } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("ONCE_TRUST_ORPHAN_QUALIFICATION", error?.message)
        assertEquals(before, exactMain())
    }

    @Test fun originalFactAuthorityWithoutCoreQualificationKeepsItsLegalFallback() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly()
        // The old fact reader accepts this session shape, but a real append must still
        // fail the original persisted preference binding and roll back all business rows.
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-legacy", "synthetic-refresh", "member", "legacy-once-owner", false)
        }
        val invalidAccess = requireNotNull(tokens.localFactAccess())
        assertTrue(invalidAccess.canAppend)
        val invalidBefore = exactMain()
        val originalRead = sessions.exclusive { db.withTransaction {
            store().readOriginalInTransaction(habit.uuid, invalidAccess.session)
        } }
        val currentRead = store().read(habit.uuid)
        assertEquals(originalRead, currentRead)
        assertEquals(invalidBefore, exactMain())
        val appendRefusal = runCatching {
            store().append(currentRead.session, command(currentRead.queue.optimisticState))
        }.exceptionOrNull()
        assertTrue(appendRefusal is IllegalArgumentException)
        assertTrue(generateSequence(appendRefusal) { it.cause }.flatMap { it.stackTrace.asSequence() }
            .any { it.className == "com.dayforge.domain.model.StableHabitBinding" })
        assertEquals(invalidBefore, exactMain())
        // Valid account/session identity with the original legacy device representation:
        // facts and preferences accept it; optional core qualification still rejects it.
        sessions.exclusive {
            tokens.clearTokens()
            tokens.saveLoginSession("synthetic-legacy", "synthetic-refresh", "member", id(1), false)
            tokens.saveSyncDeviceId("legacy-once-device")
        }
        val factAccess = requireNotNull(tokens.localFactAccess())
        assertTrue(factAccess.canAppend)
        assertNull(tokens.localCoreWriteAccess())
        val before = exactMain()
        val expected = sessions.exclusive { db.withTransaction {
            store().readOriginalInTransaction(habit.uuid, factAccess.session)
        } }
        historicalReads.set(0)
        val initial = store().read(habit.uuid)
        assertEquals(expected, initial)
        assertEquals(factAccess.session, initial.session)
        assertTrue(historicalReads.get() > 0)
        assertEquals(before, exactMain())
        val saved = store().append(initial.session, command(initial.queue.optimisticState))
        assertFalse(saved.alreadyStored)
        historicalReads.set(0)
        assertEquals(saved.snapshot, store().read(habit.uuid))
        assertTrue(historicalReads.get() > 0)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test(timeout = 150_000) fun realKnownV7EnvelopeAuthenticatesBeforeColdOnceUpgradeAndKeepsNestedEmptyState() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val recurring = habit
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val row = habit.copy(id = 0, uuid = id(403), name = "Empty legacy once", habitType = HabitType.CHECK_IN,
                schedule = HabitSchedule.Once(), targetValue = 1, targetCycles = null, bestTime = null,
                completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0)
            habit = row.copy(id = db.habitDao().insert(row))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        // Initialize the existing schema/command envelope before installing a real old
        // certificate, exactly as an already-qualified v7 installation would have done.
        sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).prepareInTransaction(requireNotNull(tokens.localCoreWriteAccess()))
        } }
        val expected = original()
        suspend fun business() = db.withTransaction {
            val native = CountTrustRows(db.openHelper.writableDatabase)
            CountTrustSchema.BUSINESS_TABLES.associateWith { name -> native.read(name).map { it.value } }
        }
        val originalBusiness = business()
        val legacy = sessions.exclusive { db.withTransaction {
            val day = LocalDate.parse("2026-10-06")
            val recurringAudit = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(recurring, day)
            val timerAudit = TimerHistoryReader(db, tokens, sessions).trustInTransaction(timerHabit, day)
            // Exact v7 empty-session fixture: v7 changed only TIMER birth encoding and the
            // validator from the frozen v6 issuer. No birth exists to transform here, and no
            // ONCE fact/date membership is affected by this build's new routing rules.
            assertTrue(timerAudit.sessions.isEmpty())
            assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
            val healthy = CountTrustAck.globalCommonHealth(db)
            val access = requireNotNull(tokens.localCoreWriteAccess())
            requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
                val sql = db.openHelper.writableDatabase
                val control = sql.query("SELECT storageId,generation FROM count_trust_control WHERE id=1").use {
                    assertTrue(it.moveToFirst()); it.getString(0) to it.getLong(1)
                }
                val binding = CountTrustSeal.binding(access, control.first, CountTrustSeal.LEGACY_EXPLICIT_TIMER_VALIDATOR)
                val native = CountTrustRows(sql)
                val values = FrozenTimerV6Issuance.values(FrozenTimerV6Issuance.TABLES.flatMap { native.read(it) },
                    listOf(recurring), null, mapOf(recurring.uuid to recurringAudit), listOf(timerAudit),
                    requireNotNull(CountTrustValidationEnvironment.current()), healthy).toMutableMap()
                values["format"] = JsonPrimitive(CountTrustSeal.LEGACY_EXPLICIT_TIMER_VALIDATOR)
                val root = CountTrustIndex.build(sql, binding, values) { }
                val previousRoot = requireNotNull(CountTrustSeal.read(sql))
                CountTrustSeal().prepareWithExistingKey(binding, previousRoot.schemaHash, root,
                    previousRoot.revision + 1, control.second, CountTrustSeal.LEGACY_EXPLICIT_TIMER_VALIDATOR)
                    .also { CountTrustSeal.store(sql, it) }
            }
        } }
        storage.reopen()
        historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid)); assertTrue(historicalReads.get() > 0)
        assertEquals(legacy, CountTrustSeal.read(db.openHelper.writableDatabase))
        suspend fun maintenance(block: (SupportSQLiteDatabase) -> Unit) = db.withTransaction {
            requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) { block(db.openHelper.writableDatabase) }
        }
        maintenance { it.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(ByteArray(32))) }
        val damagedMac = exactMain(); historicalReads.set(0)
        assertNotNull(runCatching { store().read(habit.uuid) }.exceptionOrNull())
        assertEquals(0, historicalReads.get()); assertEquals(damagedMac, exactMain())
        maintenance { CountTrustSeal.store(it, legacy) }
        val body = db.openHelper.writableDatabase.query("SELECT body FROM count_trust_nodes WHERE digest=?",
            arrayOf(legacy.root.digest)).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
        val broken = body.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        maintenance { it.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(broken, legacy.root.digest)) }
        val damagedNode = exactMain(); historicalReads.set(0)
        assertNotNull(runCatching { store().read(habit.uuid) }.exceptionOrNull())
        assertEquals(0, historicalReads.get()); assertEquals(damagedNode, exactMain())
        maintenance { it.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(body, legacy.root.digest)) }
        suspend fun upgrade() = sessions.exclusive { db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOnceCapture(requireNotNull(tokens.localCoreWriteAccess()), prepare = true) {
                assertNotNull(it)
            }
        } }
        val beforeUpgrade = exactMain()
        historicalReads.set(0); cancelRoot.set(true)
        val interrupted = async(start = CoroutineStart.LAZY) { rootJob.set(currentCoroutineContext()[Job]); upgrade() }
        try {
            interrupted.start()
            assertTrue(runCatching { interrupted.await() }.exceptionOrNull() is CancellationException)
            assertFalse(cancelRoot.get()); assertTrue(historicalReads.get() > 0)
        } finally {
            rootJob.set(null); cancelRoot.set(false)
            withContext(NonCancellable) { withTimeout(5_000) { interrupted.cancelAndJoin() } }
        }
        assertEquals(beforeUpgrade, exactMain()); assertEquals(originalBusiness, business())
        upgrade()
        val current = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(CountTrustSeal.VALIDATOR, current.validator)
        assertTrue(current.revision > legacy.revision)
        assertEquals(originalBusiness, business())
        historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid)); assertEquals(0, historicalReads.get())
        sessions.exclusive { db.withTransaction {
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
            val envelope = context.index.get(OnceTrustEvidence.projectionKey(habit.uuid))
            assertTrue(envelope is JsonObject)
            val confirmed = requireNotNull(envelope).jsonObject.getValue("confirmed").jsonObject
            assertEquals(JsonNull, confirmed.getValue("head_event_uuid"))
            assertEquals(JsonNull, confirmed.getValue("completion_event_uuid"))
        } }
        storage.reopen(); historicalReads.set(0)
        assertEquals(expected, store().read(habit.uuid)); assertEquals(0, historicalReads.get())
    }

    @Test fun cancellationAtActualColdRootWriteRollsBackAndOriginalCommandRetries() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        onceOnly()
        val original = store().read(habit.uuid); val request = command(original.queue.optimisticState)
        val before = exactMain()
        cancelRoot.set(true)
        val attempt = async {
            rootJob.set(currentCoroutineContext()[Job])
            store().append(original.session, request)
        }
        assertTrue(runCatching { attempt.await() }.exceptionOrNull() is CancellationException)
        rootJob.set(null)
        assertFalse(cancelRoot.get())
        assertEquals(before, exactMain())
        val saved = store().append(original.session, request)
        assertFalse(saved.alreadyStored); assertTrue(qualified())
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        val replay = store().append(original.session, request)
        assertTrue(replay.alreadyStored); assertEquals(saved.factId, replay.factId)
    }

    /** Extra business event on the same physical connection, after original INSERT returned.
     * The same-value event must not disappear when final row images are coalesced.
     */
    private class ExtraNativeWrite(private val armed: AtomicBoolean, private val observeAutoIds: AtomicBoolean,
        private val nativeAutoIds: AtomicReference<List<NativeAutoId>>) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var original: SupportSQLiteDatabase? = null
            var wrapped: SupportSQLiteDatabase? = null
            val lock = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(lock) {
                if (original !== raw) {
                    original = raw
                    wrapped = object : SupportSQLiteDatabase by raw {
                        override fun compileStatement(sql: String): SupportSQLiteStatement {
                            val statement = raw.compileStatement(sql)
                            val normalized = sql.lowercase().replace("`", "")
                            val factInsert = normalized.contains("into completions")
                            val autoTable = listOf("completions", "sync_outbox", "metric_logs").singleOrNull {
                                normalized.contains("into $it")
                            }
                            return object : SupportSQLiteStatement by statement {
                                private fun <T> executeOriginal(action: () -> T): T {
                                    val observing = observeAutoIds.get() && autoTable != null
                                    val extra = factInsert && armed.get()
                                    if (!observing && !extra) return action()
                                    check(raw.inTransaction())
                                    fun scalar(query: String) = raw.query(query).use { cursor ->
                                        check(cursor.moveToFirst()); val value = if (cursor.isNull(0)) 0L else cursor.getLong(0)
                                        check(!cursor.moveToNext()); value
                                    }
                                    val observed = if (observing) NativeAutoId(requireNotNull(autoTable),
                                        scalar("SELECT seq FROM main.sqlite_sequence WHERE name='$autoTable'"),
                                        scalar("SELECT MAX(id) FROM main.`$autoTable`"),
                                        scalar("SELECT COUNT(*) FROM temp.count_trust_journal_events"),
                                        scalar("SELECT seq FROM temp.sqlite_sequence WHERE name='count_trust_journal_events'")) else null
                                    val result = action()
                                    check(scalar("SELECT changes()") == 1L)
                                    // Room's real step uses execute(), then queries the same native
                                    // last row ID. Preserve that dispatch and its original return.
                                    val inserted = scalar("SELECT last_insert_rowid()")
                                    check(inserted > 0)
                                    if (result is Long) check(result == inserted)
                                    observed?.let { nativeAutoIds.set(nativeAutoIds.get() + it.copy(inserted = inserted)) }
                                    if (factInsert && armed.compareAndSet(true, false)) {
                                        raw.execSQL("UPDATE completions SET value=value WHERE id=?", arrayOf(inserted))
                                        check(scalar("SELECT changes()") == 1L)
                                    }
                                    return result
                                }
                                override fun execute() = executeOriginal { statement.execute() }
                                override fun executeInsert(): Long = executeOriginal { statement.executeInsert() }
                                override fun executeUpdateDelete(): Int = executeOriginal { statement.executeUpdateDelete() }
                            }
                        }
                    }
                }
                requireNotNull(wrapped)
            }
            val parent = configuration.callback
            val callbacks = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callbacks).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }
}
