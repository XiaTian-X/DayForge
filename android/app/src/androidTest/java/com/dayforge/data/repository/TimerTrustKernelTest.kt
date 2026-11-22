package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.FailMode
import com.dayforge.domain.model.TimerHistory
import com.dayforge.domain.service.FailureCheckerUtils
import java.security.KeyStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical original producer/actual END and independent full TIMER reader; no diagnostic
 * grant or typed COUNT commands. Fixed historical times do not change Service clock rules.
 */
@RunWith(AndroidJUnit4::class)
class TimerTrustKernelTest : NextCoreRequestFixture() {
    private val completeReads = AtomicInteger()
    private val cancelUpgrade = AtomicBoolean()
    private val cancelledUpgrade = AtomicBoolean()
    private val upgradeJob = AtomicReference<Job?>()
    private val zone get() = ZoneId.systemDefault()
    private val date get() = LocalDate.parse("2026-10-06")
    private val at get() = date.atTime(12, 0).atZone(zone)
    init { storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, object : RoomDatabase.QueryCallback {
        override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            val sql = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ")
            if (sql.startsWith("select * from timelogs where habitid") && "order by starttime desc" in sql) completeReads.incrementAndGet()
            if (sql.startsWith("insert or replace into count_trust_root(") && cancelUpgrade.compareAndSet(true, false)) {
                cancelledUpgrade.set(true); requireNotNull(upgradeJob.get()).cancel(CancellationException("TIMER_TEST_CANCEL_REAL_UPGRADE"))
            }
        }
    }) } }
    @After fun drainOwnedMethodBeforeParentResources() = runBlocking<Unit> { storage.recoveryCleanup { } }
    private fun writer() = NextTimerWriter(db, tokens, sessions)
    private suspend fun projection() = TimerHistoryReader(db, tokens, sessions).readProjection(timerHabit, at)
    private fun dirty() = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun clean(): CountTrustSeal.Header { assertEquals(0L, dirty()); return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)) }
    private suspend fun business() = db.withTransaction { val native = CountTrustRows(db.openHelper.writableDatabase)
        CountTrustSchema.BUSINESS_TABLES.associateWith { table -> native.read(table).map { it.value } } }
    private suspend fun startExact(session: Int = 50, command: Int = 51) {
        val w = writer(); val ticket = w.capture(timerHabit.id)
        w.write(timerHabit.id, ticket) { w.applyTransition(TimerTransition.Start(
            TimeLogEntity(habitId = timerHabit.id, uuid = id(session), startTime = millis, date = millis, endTime = null,
                durationSeconds = 0, timerNextCommandSequence = 2, timerControlGeneration = 1,
                timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai", createdAt = millis, updatedAt = millis),
            TimerCommandEntity(commandId = id(command), sessionUuid = id(session), sequence = 1, commandType = "start",
                occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai", createdAt = millis),
            TimerSegmentEntity(sessionUuid = id(session), sequence = 1, startedAt = millis))) }
    }
    private suspend fun apply(transition: TimerTransition) {
        val w = writer(); w.write(timerHabit.id, w.capture(timerHabit.id)) { w.applyTransition(transition) }
    }
    private suspend fun oracle() {
        val full = TimerHistoryReader(db, tokens, sessions).read(timerHabit, date)
        val expected = projectTimerDisplay(timerHabit, full, zone, at.toInstant().toEpochMilli())
        assertEquals(expected, projection())
    }

    @Test(timeout = 150_000) fun exactStartPauseResumeStopCancelMaintainOneRootAndFiniteDisplayKeepsFullOracle() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register()
        TimerHistoryReader(db, tokens, sessions).read(timerHabit, date)
        assertNull("Ordinary full read cannot issue TIMER coverage", CountTrustSeal.read(db.openHelper.writableDatabase))
        projection(); var previous = clean(); completeReads.set(0)
        startExact(); assertEquals(previous.revision + 1, clean().revision); previous = clean()
        var log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        apply(TimerTransition.Pause(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
            TimerCommandEntity(commandId = id(52), sessionUuid = log.uuid, sequence = 2, commandType = "pause",
                occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000)))
        assertEquals(previous.revision + 1, clean().revision); previous = clean()
        apply(TimerTransition.Pause(log.id, false, null, 10_000, 4, millis + 40_000, 30_000, null, null,
            TimerCommandEntity(commandId = id(53), sessionUuid = log.uuid, sequence = 3, commandType = "resume",
                occurredAt = millis + 40_000, expectedControlGeneration = 1),
            TimerSegmentEntity(sessionUuid = log.uuid, sequence = 3, startedAt = millis + 40_000)))
        assertEquals(previous.revision + 1, clean().revision); previous = clean()
        apply(TimerTransition.Stop(log.id, millis + 70_000, 60, 10_000, 5, 60_000,
            TimerCommandEntity(commandId = id(54), sessionUuid = log.uuid, sequence = 4, commandType = "stop",
                occurredAt = millis + 70_000, expectedControlGeneration = 1, activeElapsedMillis = 60_000), false))
        assertEquals(previous.revision + 1, clean().revision)
        assertEquals(60_000L, projection().todayMillis)
        assertEquals(0, completeReads.get()); assertFalse(db.inTransaction()); assertFalse(db.openHelper.writableDatabase.inTransaction())
        oracle(); completeReads.set(0); previous = clean()
        startExact(60, 61); log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        apply(TimerTransition.Cancel(log, TimerCommandEntity(commandId = id(62), sessionUuid = log.uuid, sequence = 2,
            commandType = "cancel", occurredAt = millis + 1, expectedControlGeneration = 1)))
        assertNull(db.timeLogDao().getTimeLogByUuid(log.uuid)); assertTrue(db.timeLogDao().getTimerSegments(log.uuid).isEmpty())
        assertEquals(previous.revision + 2, clean().revision); assertEquals(60_000L, projection().todayMillis)
        assertEquals(0, completeReads.get()); oracle()
        val root = clean(); storage.reopen(); completeReads.set(0)
        assertEquals(60_000L, projection().todayMillis); assertEquals(root, clean()); assertEquals(0, completeReads.get())
        assertEquals(0, count("count_command_intents"))
    }

    @Test(timeout = 150_000) fun extraSameValueWriteDirtiesWholeTransitionAndCorruptSegmentCannotQualify() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register(); projection(); startExact()
        val root = clean(); val w = writer(); val log = requireNotNull(db.timeLogDao().getActiveTimeLog())
        w.write(timerHabit.id, w.capture(timerHabit.id)) {
            w.applyTransition(TimerTransition.Pause(log.id, true, millis + 30_000, 0, 3, millis + 30_000, 30_000, null, null,
                TimerCommandEntity(commandId = id(52), sessionUuid = log.uuid, sequence = 2, commandType = "pause",
                    occurredAt = millis + 30_000, expectedControlGeneration = 1, activeElapsedMillis = 30_000)))
            db.openHelper.writableDatabase.execSQL("UPDATE timer_segments SET endedAt=endedAt WHERE sessionUuid=?", arrayOf(log.uuid))
        }
        assertEquals(1L, dirty()); assertEquals(root, CountTrustSeal.read(db.openHelper.writableDatabase))
        completeReads.set(0); projection(); assertTrue(completeReads.get() > 0); val rebuilt = clean(); oracle()
        db.openHelper.writableDatabase.execSQL("UPDATE timer_segments SET endedAt=endedAt+1 WHERE sessionUuid=?", arrayOf(log.uuid))
        val corrupted = business()
        rejected { TimerHistoryReader(db, tokens, sessions).read(timerHabit, date) }
        rejected { projection() }
        assertEquals(corrupted, business()); assertEquals(rebuilt, CountTrustSeal.read(db.openHelper.writableDatabase)); assertEquals(1L, dirty())
    }

    @Test(timeout = 150_000) fun cancelledOriginalProducerRollsBackBusinessQueueAndRootTogether() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register(); projection()
        val root = clean(); val original = business(); val w = writer(); val ticket = w.capture(timerHabit.id)
        try {
            w.write(timerHabit.id, ticket) {
                w.applyTransition(TimerTransition.Start(TimeLogEntity(habitId = timerHabit.id, uuid = id(70), startTime = millis,
                    date = millis, endTime = null, durationSeconds = 0, timerNextCommandSequence = 2,
                    timerControlGeneration = 1, timerLastCommandAt = millis, timerTimezone = "Asia/Shanghai"),
                    TimerCommandEntity(commandId = id(71), sessionUuid = id(70), sequence = 1, commandType = "start",
                        occurredAt = millis, expectedControlGeneration = 0, activityUuid = timerHabit.uuid, timezone = "Asia/Shanghai"),
                    TimerSegmentEntity(sessionUuid = id(70), sequence = 1, startedAt = millis)))
                throw CancellationException("TIMER_TEST_ROLLBACK_AFTER_REAL_BUSINESS")
            }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
        assertFalse(db.inTransaction()); assertEquals(original, business()); assertEquals(root, clean())
        assertNull(db.timeLogDao().getActiveTimeLog()); startExact(); assertEquals(root.revision + 1, clean().revision)
    }

    @Test(timeout = 150_000) fun realV5RootAuthenticatesBeforeColdTimerUpgradeAndCancellationLeavesItIntact() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register(); start(); projection()
        val contents = business()
        val old = db.withTransaction {
            val source = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(habit, date)
            val healthy = CountTrustAck.globalCommonHealth(db)
            val access = requireNotNull(tokens.localCoreWriteAccess()); val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
            runtime.maintenance(db) {
                val sql = db.openHelper.writableDatabase; val header = clean()
                val storageId = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use { assertTrue(it.moveToFirst()); it.getString(0) }
                val binding = CountTrustSeal.binding(access, storageId, FrozenTimerV5Issuance.VALIDATOR)
                val native = CountTrustRows(sql)
                val values = FrozenTimerV5Issuance.values(FrozenTimerV5Issuance.TABLES.flatMap { native.read(it) },
                    listOf(habit), null, mapOf(habit.uuid to source), requireNotNull(CountTrustValidationEnvironment.current()), healthy)
                assertFalse(values.containsKey(TimerTrustEvidence.COVERAGE_KEY)); assertFalse(values.keys.any { it.startsWith("timer:") })
                val index = CountTrustIndex.build(sql, binding, values) { }
                signedV5(binding, header.schemaHash, index, header.revision + 1, header.generation).also { CountTrustSeal.store(sql, it) }
            }
        }
        storage.reopen(); completeReads.set(0)
        val interrupted = async(start = CoroutineStart.LAZY) { projection() }; upgradeJob.set(interrupted); cancelUpgrade.set(true)
        try { interrupted.start(); assertTrue(runCatching { interrupted.await() }.exceptionOrNull() is CancellationException)
            assertTrue(cancelledUpgrade.get())
        } finally { cancelUpgrade.set(false); upgradeJob.set(null)
            withContext(NonCancellable) { withTimeout(5_000) { interrupted.cancelAndJoin() } } }
        assertFalse(db.inTransaction()); assertEquals(old, clean()); assertEquals(contents, business())
        completeReads.set(0); projection(); assertTrue(completeReads.get() > 0)
        assertEquals(CountTrustSeal.VALIDATOR, clean().validator); assertTrue(clean().revision > old.revision)
        assertEquals(contents, business()); completeReads.set(0); projection(); assertEquals(0, completeReads.get()); oracle()
    }

    @Test(timeout = 150_000) fun realV6PlainBirthRequiresAuthenticatedColdUpgradeAndRejectsDamagedOldEvidence() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register(); start(); projection()
        val contents = business()
        val old = db.withTransaction {
            val source = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(habit, date)
            val timer = TimerHistoryReader(db, tokens, sessions).trustInTransaction(timerHabit, date)
            assertEquals(1, timer.sessions.size); assertNull(timer.sessions.single().head)
            val healthy = CountTrustAck.globalCommonHealth(db)
            val access = requireNotNull(tokens.localCoreWriteAccess())
            requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
                val sql = db.openHelper.writableDatabase; val header = clean()
                val storageId = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
                val binding = CountTrustSeal.binding(access, storageId, FrozenTimerV6Issuance.VALIDATOR)
                val native = CountTrustRows(sql)
                val values = FrozenTimerV6Issuance.values(FrozenTimerV6Issuance.TABLES.flatMap { native.read(it) },
                    listOf(habit), null, mapOf(habit.uuid to source), listOf(timer),
                    requireNotNull(CountTrustValidationEnvironment.current()), healthy)
                assertEquals(JsonNull, values[TimerTrustEvidence.birthKey(timer.sessions.single().log.uuid)])
                val root = CountTrustIndex.build(sql, binding, values) { }
                signedV6(binding, header.schemaHash, root, header.revision + 1, header.generation)
                    .also { CountTrustSeal.store(sql, it) }
            }
        }
        storage.reopen()
        suspend fun maintenance(block: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) = db.withTransaction {
            requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) { block(db.openHelper.writableDatabase) }
        }
        val nodeCount = count("count_trust_nodes")
        maintenance { it.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(ByteArray(32))) }
        val damagedMac = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
        completeReads.set(0); assertNotNull(runCatching { projection() }.exceptionOrNull())
        assertEquals(0, completeReads.get()); assertEquals(damagedMac, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(nodeCount, count("count_trust_nodes")); assertEquals(contents, business()); assertEquals(0L, dirty())
        maintenance { CountTrustSeal.store(it, old) }

        val body = db.openHelper.writableDatabase.query("SELECT body FROM count_trust_nodes WHERE digest=?",
            arrayOf(old.root.digest)).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
        val broken = body.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        maintenance { it.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(broken, old.root.digest)) }
        completeReads.set(0); assertNotNull(runCatching { projection() }.exceptionOrNull())
        assertEquals(0, completeReads.get()); assertEquals(old, clean())
        assertEquals(nodeCount, count("count_trust_nodes")); assertEquals(contents, business())
        maintenance { it.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf(body, old.root.digest)) }

        completeReads.set(0)
        val interrupted = async(start = CoroutineStart.LAZY) { projection() }
        upgradeJob.set(interrupted); cancelUpgrade.set(true)
        try {
            interrupted.start(); assertTrue(runCatching { interrupted.await() }.exceptionOrNull() is CancellationException)
            assertTrue(cancelledUpgrade.get()); assertTrue(completeReads.get() > 0)
        } finally {
            cancelUpgrade.set(false); upgradeJob.set(null)
            withContext(NonCancellable) { withTimeout(5_000) { interrupted.cancelAndJoin() } }
        }
        assertFalse(db.inTransaction()); assertEquals(old, clean()); assertEquals(contents, business())
        assertEquals(nodeCount, count("count_trust_nodes"))
        completeReads.set(0); projection(); assertTrue(completeReads.get() > 0)
        val upgraded = clean(); assertEquals(CountTrustSeal.VALIDATOR, upgraded.validator)
        assertTrue(upgraded.revision > old.revision); assertEquals(contents, business())
        storage.reopen(); completeReads.set(0); projection()
        assertEquals(0, completeReads.get()); assertEquals(upgraded, clean()); oracle()
    }

    @Test(timeout = 150_000) fun remoteCompletedDurationUsesOriginalAcceptedMapperWithoutInventingLocalStart() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register()
        val (http, _) = channel()
        val merger = NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
            NextTimerRequestStore(db, tokens, sessions, sender(http)))
        fun canonical(type: String, uuid: String, payload: JsonObject) = com.dayforge.data.api.dto.SyncV2Change(
            0, type, uuid, "upsert", 1, JsonObject(payload + mapOf("public_id" to JsonPrimitive(uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time),
                "deleted_at" to JsonNull)), time)
        val checkpoints = listOf(habit.uuid, timerHabit.uuid).map { uuid ->
            val head = com.dayforge.domain.model.initialChallengeRoundHead(uuid)
            com.dayforge.data.api.dto.ChallengeCheckpoint(head,
                listOf(com.dayforge.domain.model.ChallengeRoundRecord(head, null, null, null)))
        }
        val head = checkpoints.single { it.head.activityUuid == timerHabit.uuid }.head
        val remote = id(90); val end = java.time.Instant.ofEpochMilli(millis + 60_000).toString()
        val duration = canonical("activity_event", remote, buildJsonObject {
            put("activity_uuid", timerHabit.uuid); put("event_type", "duration_session"); put("value", JsonNull)
            put("duration_seconds", 60); put("duration_milliseconds", 60_000); put("started_at", time); put("ended_at", end)
            put("occurred_at", end); put("received_at", time); put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai")
            put("source_type", "app"); put("source_device_id", id(4)); put("external_event_id", remote); put("note", "")
            put("reverts_event_uuid", JsonNull); put("metadata", buildJsonObject { put("timer_session_id", remote) })
            put("day_allocations", buildJsonArray { add(buildJsonObject {
                put("local_date", "2026-10-06"); put("timezone", "Asia/Shanghai"); put("duration_milliseconds", 60_000)
            }) })
        })
        val changes = listOf(canonical("plan_node", habit.uuid, NextStructureMapper.writePlan(habit)),
            canonical("plan_node", timerHabit.uuid, NextStructureMapper.writePlan(timerHabit)),
            canonical("metric", metric.uuid, NextStructureMapper.writeMetric(metric)), duration)
        val births = listOf("timer_session", "activity_event").map { com.dayforge.data.api.dto.ChallengeBirth(it, remote, head) }
        merger.bootstrap(access(), null, com.dayforge.data.api.dto.RoundSyncBootstrapResponse(changes, 20, time, emptyList(), 1,
            checkpoints, births))
        timerHabit = requireNotNull(db.habitDao().getHabitByUuid(timerHabit.uuid))
        val original = TimerHistoryReader(db, tokens, sessions).read(timerHabit, date)
        assertEquals(listOf(remote), original.logs.map { it.uuid }); assertNull(original.activeLog)
        assertTrue(db.timeLogDao().getTimerSegments(remote).isEmpty())
        assertEquals(0, count("timer_command_outbox")); assertEquals(0, count("next_request_origins"))
        val contents = business(); completeReads.set(0)
        val actual = projection(); assertEquals(projectTimerDisplay(timerHabit, original, zone, at.toInstant().toEpochMilli()), actual)
        assertTrue(completeReads.get() > 0); val root = clean(); assertEquals(contents, business())
        completeReads.set(0); assertEquals(actual, projection()); assertEquals(0, completeReads.get()); assertEquals(root, clean())
        db.openHelper.writableDatabase.execSQL("UPDATE timelog_day_allocations SET durationMillis=durationMillis+1 WHERE sessionUuid=?", arrayOf(remote))
        val damaged = business(); rejected { TimerHistoryReader(db, tokens, sessions).read(timerHabit, date) }; rejected { projection() }
        assertEquals(damaged, business()); assertEquals(root, CountTrustSeal.read(db.openHelper.writableDatabase)); assertEquals(1L, dirty())
    }

    @Test(timeout = 150_000) fun authenticatedTimerCoverageCorruptionRejectsWithoutFallbackPublicationOrReissuance() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job])); register()
        // A genuine COUNT-only root is a legal TIMER miss. Its full reader stays read-only;
        // only the explicit TIMER entry subsequently performs complete issuance.
        CountHistoryReader(db, tokens, sessions).readSummary(habit, date)
        val countOnly = clean()
        db.withTransaction {
            val context = requireNotNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
            assertFalse(context.timers); assertFalse(context.timerEligible(timerHabit))
        }
        TimerHistoryReader(db, tokens, sessions).read(timerHabit, date); assertEquals(countOnly, clean())
        projection(); assertTrue(clean().revision > countOnly.revision); startExact()
        val good = clean(); val runtime = requireNotNull(CountTrustRuntime.forDatabase(db))
        val scope = TimerDisplayBlocks.Scope(timerHabit.uuid, null)
        val mutations = listOf(
            // All global declarations absent but the selected habit's eligible leaf remains:
            // preparation itself must reject before attempting cold reissuance.
            mapOf(TimerTrustEvidence.COVERAGE_KEY to null, TimerDisplayBlocks.COVERAGE_KEY to null,
                TimerTrustEvidence.HABITS_KEY to null),
            mapOf(TimerTrustEvidence.COVERAGE_KEY to null),
            mapOf(TimerTrustEvidence.COVERAGE_KEY to JsonPrimitive("unknown-coverage")),
            mapOf(TimerDisplayBlocks.COVERAGE_KEY to null),
            mapOf(TimerTrustEvidence.HABITS_KEY to null),
            mapOf(TimerTrustEvidence.HABITS_KEY to JsonArray(listOf(JsonPrimitive(timerHabit.uuid), JsonPrimitive(timerHabit.uuid)))),
            mapOf(TimerTrustEvidence.eligibleKey(timerHabit.uuid) to null),
            mapOf(TimerTrustEvidence.eligibleKey(timerHabit.uuid) to JsonPrimitive("wrong-eligible")),
            mapOf(scope.globalKey() to null),
            mapOf(TimerTrustEvidence.birthKey(id(50)) to null),
            mapOf(TimerTrustEvidence.birthKey(id(50)) to buildJsonObject { put("kind", "unknown") }),
            mapOf(TimerTrustEvidence.birthKey(id(50)) to buildJsonObject { put("kind", "plain"); put("head", JsonNull) }),
            mapOf(TimerTrustEvidence.birthKey(id(50)) to TimerTrustEvidence.encodeBirth(
                com.dayforge.domain.model.initialChallengeRoundHead(habit.uuid))),
            mapOf(CountTrustMembership.key("timer.active", timerHabit.id.toString()) to JsonArray(emptyList()))
        )
        suspend fun restore() = db.withTransaction { runtime.maintenance(db) { CountTrustSeal.store(db.openHelper.writableDatabase, good) } }
        for (edit in mutations) {
            val forged = db.withTransaction { runtime.maintenance(db) {
                val sql = db.openHelper.writableDatabase
                val index = CountTrustIndex(sql, good.binding, good.root) { }
                index.putAll(edit)
                CountTrustSeal().prepare(good.binding, good.schemaHash, index.root, good.revision + 1, good.generation)
                    .also { CountTrustSeal.store(sql, it) }
            } }
            val before = business(); val nodes = count("count_trust_nodes"); completeReads.set(0)
            var published = false
            val error = runCatching { projection(); published = true }.exceptionOrNull()
            assertNotNull("Authenticated coverage inconsistency must reject: ${edit.keys}", error)
            assertFalse(published); assertEquals(0, completeReads.get())
            assertEquals(forged, clean()); assertEquals(nodes, count("count_trust_nodes")); assertEquals(before, business())
            restore()
        }
        db.withTransaction { runtime.maintenance(db) {
            db.openHelper.writableDatabase.execSQL("UPDATE count_trust_root SET mac=? WHERE id=1", arrayOf(ByteArray(32)))
        } }
        val invalid = requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)); val before = business()
        completeReads.set(0); assertNotNull(runCatching { projection() }.exceptionOrNull())
        assertEquals(0, completeReads.get()); assertEquals(invalid, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(before, business()); assertEquals(0L, dirty())
        restore(); oracle()
    }

    private fun signedV5(binding: String, schema: String, root: com.dayforge.data.history.HistoryBlockCodec.Ref,
        revision: Long, generation: Long): CountTrustSeal.Header {
        val version = FrozenTimerV5Issuance.VALIDATOR
        val bytes = buildJsonObject {
            put("domain", "DayForge/count-trust-root"); put("format", 1); put("validator", version)
            put("binding", binding); put("schema", schema); put("index", "count-trust-v1")
            put("scope", "directory/1970-01/null-round/open-bounds"); put("height", root.height); put("count", root.count)
            put("digest", root.digest); put("revision", revision); put("generation", generation)
        }.toString().toByteArray(Charsets.UTF_8)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("dayforge.count.trust.root.v1", null) as SecretKey
        val tag = Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes) }
        return CountTrustSeal.Header(binding, schema, root, revision, generation, tag.toList(), version)
    }

    private fun signedV6(binding: String, schema: String, root: com.dayforge.data.history.HistoryBlockCodec.Ref,
        revision: Long, generation: Long): CountTrustSeal.Header {
        val version = FrozenTimerV6Issuance.VALIDATOR
        val bytes = buildJsonObject {
            put("domain", "DayForge/count-trust-root"); put("format", 1); put("validator", version)
            put("binding", binding); put("schema", schema); put("index", "count-trust-v1")
            put("scope", "directory/1970-01/null-round/open-bounds"); put("height", root.height); put("count", root.count)
            put("digest", root.digest); put("revision", revision); put("generation", generation)
        }.toString().toByteArray(Charsets.UTF_8)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("dayforge.count.trust.root.v1", null) as SecretKey
        val tag = Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes) }
        return CountTrustSeal.Header(binding, schema, root, revision, generation, tag.toList(), version)
    }

    @Test(timeout = 150_000) fun strictFoldKeepsFirstCompletedDateFutureAndTailWhileSkippingHealthyYears() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        val previousZone = java.util.TimeZone.getDefault()
        val z = ZoneId.of("America/New_York")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(z))
        try {
        val first = LocalDate.parse("2000-01-01"); val today = LocalDate.parse("2026-11-02")
        val h = timerHabit.copy(failMode = FailMode.STRICT, targetCycles = 20_000, createdAt = first.atStartOfDay(z).toInstant().toEpochMilli())
        val time = today.atTime(12, 0).atZone(z).toInstant().toEpochMilli()
        val healthy = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= today }.associateWith { 60_000L }
        fun assertCase(values: Map<LocalDate, Long>, atDay: LocalDate = today, atMillis: Long = time, bounded: Boolean = false,
            active: TimeLogEntity? = null, segments: List<TimerSegmentEntity> = emptyList(),
            expectedFailure: com.dayforge.domain.service.StrictFailureState? = null) {
            val issued = TimerDisplayBlocks.issue(h, null, values, z); val scope = TimerDisplayBlocks.Scope(h.uuid, null)
            val global = TimerDisplayBlocks.global(requireNotNull(issued[scope.globalKey()]).jsonObject, scope, h)
            val reads = linkedSetOf<String>()
            val actual = TimerDisplayBlocks.project(global, h, atDay, z, atMillis, active, segments) { key -> reads += key; issued[key] }
            val history = TimerHistory(atDay, 60L, values, emptyList(), active, segments, null)
            assertEquals(projectTimerDisplay(h, history, z, atMillis), actual)
            assertEquals(values.keys.minOrNull(), actual.firstDate)
            expectedFailure?.let { assertEquals(it, actual.failure) }
            if (bounded) assertTrue("Healthy strict prefix must not load historical year blocks", (2000 until today.year - 1).none {
                scope.yearKey(it) in reads })
        }
        assertCase(healthy, bounded = true)
        assertCase(mapOf(first to 59_999L)) // completed but zero qualified dates still starts strict failure
        assertCase(mapOf(today.plusDays(1) to 60_000L)) // future completion does not start an earlier strict failure
        val lastDay = LocalDate.parse("2025-12-31")
        assertCase(healthy.filterKeys { it <= lastDay }) // unrepresented year tail is not "healthy"
        assertCase(mapOf(LocalDate.parse("2026-03-08") to 59_999L, LocalDate.parse("2026-11-01") to 60_001L))
        val yesterday = today.minusDays(1)
        val startAt = today.atStartOfDay(z).toInstant().toEpochMilli() - 1
        val active = TimeLogEntity(id = 987, habitId = h.id, uuid = id(987), startTime = startAt, date = startAt,
            endTime = null, durationSeconds = 0, timerTimezone = z.id)
        val segment = TimerSegmentEntity(id = 987, sessionUuid = active.uuid, sequence = 1, startedAt = startAt)
        assertCase(mapOf(yesterday to 59_999L), active = active, segments = listOf(segment),
            expectedFailure = com.dayforge.domain.service.StrictFailureState.AWAITING_TIMER_SETTLEMENT)
        assertCase(mapOf(today.minusDays(2) to 1L, yesterday to 59_999L), active = active, segments = listOf(segment),
            expectedFailure = com.dayforge.domain.service.StrictFailureState.FAILED)
        assertCase(mapOf(yesterday to 59_999L), active = active.copy(isPaused = true),
            segments = listOf(segment.copy(endedAt = startAt)), expectedFailure = com.dayforge.domain.service.StrictFailureState.FAILED)
        } finally { java.util.TimeZone.setDefault(previousZone) }
    }
}
