package com.dayforge.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.businessDate
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.data.local.HabitDatabaseProvider
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.domain.service.ActivityRateCalculator
import com.dayforge.domain.service.FailureCheckerUtils
import com.dayforge.domain.service.StreakCalculator
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/** Actual physical producer/SEND/ACK and native root; the independent oracle is the original
 * full CHECK reader/merger. No typed CHECK command/receipt or fake detail history is introduced.
 */
@RunWith(AndroidJUnit4::class)
class CheckTrustKernelTest : NextObjectEditorFixture() {
    private val completeReads = AtomicInteger()
    private val cancelUpgrade = AtomicBoolean()
    private val upgradeCancelled = AtomicBoolean()
    private val upgradeJob = AtomicReference<Job?>()
    private val today = LocalDate.parse("2026-10-06")
    private val zone get() = ZoneId.systemDefault()
    private val cardAt get() = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    init {
        storage.isolatedBuilder = { context -> HabitDatabaseProvider.build(context, object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                val sql = sqlQuery.lowercase().replace("`", "").replace(Regex("\\s+"), " ")
                if (sql.startsWith("select value,habituuid,onetimeaction,recordedlocaldate,")) completeReads.incrementAndGet()
                if (sql.startsWith("insert or replace into count_trust_root(") && cancelUpgrade.compareAndSet(true, false)) {
                    upgradeCancelled.set(true)
                    requireNotNull(upgradeJob.get()).cancel(CancellationException("CHECK_TEST_CANCEL_REAL_UPGRADE"))
                }
            }
        }) }
    }
    @After fun drainOwnedMethodBeforeParentResources() = runBlocking<Unit> { storage.recoveryCleanup { } }

    private suspend fun configure() {
        register()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            habit = habit.copy(habitType = HabitType.CHECK_IN, targetValue = 1)
            db.habitDao().update(habit)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private suspend fun append(number: Int): CompletionEntity {
        val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(93_000 + number),
            date = millis, actualCompletedAt = millis, createdAt = millis, value = 1,
            recordedTimezone = "Etc/UTC", recordedLocalDate = today.toString())
        producer().write(local()) { db.completionDao().insertForSync(fact) }
        return requireNotNull(db.completionDao().getCompletionByUuid(fact.uuid))
    }
    private suspend fun original(): CheckHistory {
        val source = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, today)
        return CheckHistory(source.today, source.completions, source.roundHead, source.checkIdentity)
    }
    private suspend fun projected() = CheckHistoryReader(db, tokens, sessions).readProjection(habit, today, zone, cardAt)
    private fun dirty() = db.openHelper.writableDatabase.query("SELECT dirty FROM count_trust_control WHERE id=1").use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun cleanRoot(): CountTrustSeal.Header {
        assertEquals("Actual committed qualification must be clean", 0L, dirty())
        return requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase))
    }
    private suspend fun business(): Map<String, List<JsonObject>> = db.withTransaction {
        val native = CountTrustRows(db.openHelper.writableDatabase)
        CountTrustSchema.BUSINESS_TABLES.associateWith { table -> native.read(table).map { it.value } }
    }
    private fun assertOriginal(expected: CheckHistory, actual: CheckDisplayProjection) {
        assertEquals(expected.today, actual.today); assertEquals(zone.id, actual.zoneId)
        assertEquals(expected.todayQuantity, actual.todayQuantity)
        assertEquals(expected.todayCompletions.maxByOrNull { it.id }?.id, actual.todayLatestCompletionId)
        assertEquals(expected.completedToday, actual.completedToday)
        assertEquals(expected.qualifiedDates.size, actual.qualifiedDayCount)
        assertEquals(expected.actionIdentity, actual.actionIdentity); assertEquals(expected.roundHead, actual.roundHead)
        assertEquals(FailureCheckerUtils.checkHasFailed(habit, expected, zone), actual.hasFailed)
        val card = requireNotNull(actual.card)
        assertEquals(StreakCalculator.currentFromBusinessDates(expected.qualifiedDates, today), card.currentStreak)
        assertEquals(StreakCalculator.bestFromBusinessDates(expected.qualifiedDates), card.bestStreak)
        assertEquals(ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, expected.qualifiedDates.toList(), cardAt, zone), card.activityRate)
    }
    private suspend fun queue(fact: CompletionEntity) = db.syncOutboxDao().getAll().single {
        it.entityUuid == fact.uuid && it.action == "upsert"
    }
    private class OracleRollback(val value: Map<String, List<JsonObject>>) : RuntimeException()
    private fun factTables(): Map<String, List<JsonObject>> {
        val native = CountTrustRows(db.openHelper.writableDatabase)
        return listOf("completions", "count_days", "sync_entity_state").associateWith { table ->
            native.read(table).map { row -> if (table == "sync_entity_state") JsonObject(row.value - "updatedAt") else row.value }
        }
    }
    private suspend fun oldMergerOracle(row: SyncOutboxEntity, change: SyncV2Change, device: String): Map<String, List<JsonObject>> {
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val before = business()
        val expected = try {
            db.withTransaction {
                NextCommonFactStore(db).restoreInTransaction(listOf(change), device, row.id, operation)
                throw OracleRollback(factTables())
            }
        } catch (rollback: OracleRollback) { rollback.value }
        assertEquals("Original full-merger oracle must actually roll back", before, business())
        assertFalse(requireNotNull(CountTrustRuntime.forDatabase(db)).isRetired)
        return expected
    }

    /** Frozen v4 MAC framing from the original source, not the new seal's prepare(). */
    private fun signedV4(binding: String, schema: String, index: com.dayforge.data.history.HistoryBlockCodec.Ref,
        revision: Long, generation: Long): CountTrustSeal.Header {
        val version = "count-trust/round-recurring-v4/compact-calendar-and-native-rules/2026-10-25"
        val bytes = buildJsonObject {
            put("domain", "DayForge/count-trust-root"); put("format", 1); put("validator", version)
            put("binding", binding); put("schema", schema); put("index", "count-trust-v1")
            put("scope", "directory/1970-01/null-round/open-bounds")
            put("height", index.height); put("count", index.count); put("digest", index.digest)
            put("revision", revision); put("generation", generation)
        }.toString().toByteArray(Charsets.UTF_8)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("dayforge.count.trust.root.v1", null) as SecretKey
        val authentication = Mac.getInstance("HmacSHA256").run { init(key); doFinal(bytes) }
        return CountTrustSeal.Header(binding, schema, index, revision, generation, authentication.toList(), version)
    }

    @Test(timeout = 150_000) fun multipleFactsKeepMaxIdAndNormalAckUsesCurrentCheckRootAndOriginalMerger() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val first = append(1); val second = append(2)
        val baseline = original(); val beforeRead = business()
        assertOriginal(baseline, projected()); assertEquals(beforeRead, business())
        val issued = cleanRoot(); assertEquals(CountTrustSeal.VALIDATOR, issued.validator)
        completeReads.set(0)
        val third = append(3)
        assertEquals(issued.revision + 1, cleanRoot().revision)
        val afterAdd = projected(); assertEquals(3L, afterAdd.todayQuantity); assertEquals(third.id, afterAdd.todayLatestCompletionId)
        producer().write(local()) { db.completionDao().delete(third) }
        val afterUndo = projected(); assertEquals(2L, afterUndo.todayQuantity); assertEquals(second.id, afterUndo.todayLatestCompletionId)
        assertEquals(0, completeReads.get())
        val row = queue(first); val (store, delivery) = success(row)
        val operation = decodeNextOperationIntent(originalIntent(row).intentJson)
        val change = NextOrdinaryResultMapper.validate(operation, delivery.result.results.single(), requireNotNull(delivery.access.deviceId))
        val expected = oldMergerOracle(row, change, requireNotNull(delivery.access.deviceId))
        val beforeAck = cleanRoot(); completeReads.set(0)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertEquals(beforeAck.revision + 1, cleanRoot().revision)
        assertEquals(expected, db.withTransaction { factTables() })
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId)); assertNull(db.syncOutboxDao().getById(row.id))
        val beforeReplay = business(); val replayRoot = cleanRoot()
        assertEquals(NextOperationAcceptance.REPLAYED, store.acceptOperation(delivery))
        assertEquals(beforeReplay, business()); assertEquals(replayRoot, cleanRoot())
        projected(); assertEquals("No new full CHECK audit is needed for supported writer/ACK/read", 0, completeReads.get())
        assertOriginal(original(), projected())
    }

    @Test(timeout = 150_000) fun lateCheckAckCannotResurrectUndoAndRevertKeepsOriginalFullFallback() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val fact = append(4); projected(); val row = queue(fact)
        val (store, delivery) = success(row)
        producer().write(local()) { db.completionDao().delete(fact) }
        assertFalse(projected().completedToday); val beforeAck = cleanRoot(); completeReads.set(0)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acceptOperation(delivery))
        assertNull(db.completionDao().getCompletionByUuid(fact.uuid)); assertEquals(beforeAck.revision + 1, cleanRoot().revision)
        assertFalse(projected().completedToday); assertEquals(0, completeReads.get())
        val revert = db.syncOutboxDao().getAll().single { it.entityUuid == fact.uuid && it.action == "delete" }
        val (revertStore, reverted) = success(revert)
        assertEquals(NextOperationAcceptance.COMMITTED, revertStore.acceptOperation(reverted))
        assertEquals("Legal revert deliberately invalidates the optional root", 1L, dirty())
        assertNull(db.completionDao().getCompletionByUuid(fact.uuid)); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        completeReads.set(0); val current = projected()
        assertFalse(current.completedToday); assertNull(current.todayLatestCompletionId); assertEquals(0, current.qualifiedDayCount)
        assertTrue("Revert fallback must re-audit, never reuse dirty proof", completeReads.get() > 0)
        val after = cleanRoot(); val contents = business()
        storage.reopen(); completeReads.set(0)
        assertEquals(current, projected()); assertEquals(after, cleanRoot()); assertEquals(contents, business())
        assertEquals("Normal same-file reopen should reuse the newly committed CHECK proof", 0, completeReads.get())
        assertOriginal(original(), projected())
    }

    @Test(timeout = 150_000) fun authenticatedV4CountEligibilityCannotAuthorizeCheckAndNewRootSurvivesColdReopen() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); append(5)
        val countHabit = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val other = habit.copy(id = 0, uuid = id(93_999), name = "Retained COUNT compatibility", habitType = HabitType.COUNTING, targetValue = 10)
            val id = db.habitDao().insert(other)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            other.copy(id = id)
        }
        CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today); projected()
        val before = business()
        // Frozen original v4 plain-COUNT layout with every actual native row/member and its
        // independently validated COUNT input. This does not run an old APK or relabel v5 JSON.
        val old = db.withTransaction {
            val originalCount = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(countHabit, today)
            assertNull(originalCount.roundHead); assertTrue(originalCount.completions.isEmpty())
            assertTrue(originalCount.policies.isEmpty()); assertTrue(originalCount.unknownDates.isEmpty())
            val common = CountTrustAck.globalCommonHealth(db); assertTrue(common)
            val runtime = requireNotNull(CountTrustRuntime.forDatabase(db)); val access = requireNotNull(tokens.localCoreWriteAccess())
            runtime.maintenance(db) {
                val sql = db.openHelper.writableDatabase; val header = cleanRoot()
                val storageId = sql.query("SELECT storageId FROM count_trust_control WHERE id=1").use { assertTrue(it.moveToFirst()); it.getString(0) }
                val binding = CountTrustSeal.binding(access, storageId, CountTrustSeal.LEGACY_DISPLAY_VALIDATOR)
                val native = CountTrustRows(sql)
                val all = FrozenCountV4Issuance.TABLES.flatMap { native.read(it) }
                val environment = requireNotNull(CountTrustValidationEnvironment.current())
                val values = FrozenCountV4Issuance.values(all, listOf(countHabit), null,
                    mapOf(countHabit.uuid to originalCount), environment, common)
                assertEquals(environment, CountTrustValidationEnvironment.current())
                assertFalse(values.containsKey("eligible:${habit.uuid}"))
                assertFalse(values.keys.any { it.startsWith("check:") || it.startsWith("formal:check-") })
                val index = CountTrustIndex.build(sql, binding, values) {}
                signedV4(binding, header.schemaHash, index, header.revision + 1, header.generation)
                    .also { CountTrustSeal.store(sql, it) }
            }
        }
        storage.reopen(); completeReads.set(0)
        val interrupted = async(start = CoroutineStart.LAZY) {
            CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today)
        }
        upgradeJob.set(interrupted); cancelUpgrade.set(true)
        try {
            interrupted.start()
            assertTrue(runCatching { interrupted.await() }.exceptionOrNull() is CancellationException)
            assertTrue(upgradeCancelled.get()); assertTrue(interrupted.isCompleted)
        } finally {
            cancelUpgrade.set(false); upgradeJob.set(null)
            withContext(NonCancellable) { withTimeout(5_000) { interrupted.cancelAndJoin() } }
        }
        assertFalse(db.inTransaction()); assertEquals(old, cleanRoot()); assertEquals(before, business())
        completeReads.set(0)
        val count = CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today)
        assertEquals(0L, count.todayQuantity); assertTrue(completeReads.get() > 0)
        val upgraded = cleanRoot(); assertEquals(CountTrustSeal.VALIDATOR, upgraded.validator); assertTrue(upgraded.revision > old.revision)
        val check = projected(); assertEquals(1L, check.todayQuantity); assertTrue(check.completedToday)
        assertEquals(before, business())
        completeReads.set(0); CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today)
        assertEquals(check, projected()); assertEquals(upgraded, cleanRoot()); assertEquals(0, completeReads.get())
        storage.reopen(); completeReads.set(0)
        assertEquals(check, projected()); assertEquals(upgraded, cleanRoot()); assertEquals(before, business()); assertEquals(0, completeReads.get())
        assertOriginal(original(), projected())
    }

    @Test(timeout = 150_000) fun formalDisplayUsesCurrentRoundButPersistentActivityRateKeepsAllRetainedFacts() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure()
        val currentDay = LocalDate.now(zone)
        val initial = ChallengeRoundRecord(initialChallengeRoundHead(habit.uuid), null, null, null)
        val timer = ChallengeRoundRecord(initialChallengeRoundHead(timerHabit.uuid), null, null, null)
        val initialPoints = listOf(ChallengeCheckpoint(initial.head, listOf(initial)), ChallengeCheckpoint(timer.head, listOf(timer)))
        val metadata = AtomicReference(ChallengeMetadata(1, initialPoints, emptyList()))
        val results = ConcurrentHashMap<String, NextSyncOperationResult>()
        val codec = Json { encodeDefaults = true }
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else {
                assertEquals("/api/v2/sync/rounds/push", input.path)
                assertEquals("5", input.headers["x-dayforge-protocol"])
                assertEquals(id(2), input.headers["x-dayforge-server-instance"])
                assertEquals(id(3), input.headers["x-dayforge-sync-epoch"])
                val request = codec.decodeFromString<RoundSyncPushRequest>(input.body.toString(Charsets.UTF_8))
                val operation = request.operations.single(); val context = request.contexts.single()
                assertEquals("activity_event", operation.entityType)
                assertEquals(JsonPrimitive("check_in"), operation.payload["event_type"])
                assertEquals(JsonPrimitive(habit.uuid), operation.payload["activity_uuid"])
                assertTrue(metadata.get().checkpoints.single { it.head.activityUuid == habit.uuid }.records.any { it.head == context.head })
                val previous = results[operation.operationId]
                val result = previous?.copy(status = "already_applied") ?: run {
                    val received = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
                    val body = successReply(input) { value -> JsonObject(value + mapOf(
                        "created_at" to JsonPrimitive(received), "updated_at" to JsonPrimitive(received), "received_at" to JsonPrimitive(received))) }
                    codec.decodeFromString<NextSyncPushResponse>(body.bytes.toString(Charsets.UTF_8)).results.single()
                        .also { results[operation.operationId] = it }
                }
                val born = ChallengeBirth("activity_event", operation.entityUuid, requireNotNull(context.head))
                val meta = metadata.updateAndGet { old ->
                    val existing = old.births.singleOrNull { it.entityType == born.entityType && it.entityUuid == born.entityUuid }
                    if (existing != null) { assertEquals(existing, born); old } else old.copy(births = old.births + born)
                }
                MaterialSocketServer.Reply(codec.encodeToString(RoundSyncPushResponse(listOf(result), 1,
                    meta.checkpoints, meta.births)).toByteArray())
            }
        }
        val merger = NextSyncMergeStore(db, tokens, sessions,
            OneTimeAcceptedEventStore(db, tokens, sessions, OneTimeLocalIntentStore(db, tokens, sessions, preferences)),
            NextTimerRequestStore(db, tokens, sessions, sender(http)))
        fun canonical(row: HabitEntity, revision: Long = 1) = SyncV2Change(0, "plan_node", row.uuid, "upsert", revision,
            JsonObject(NextStructureMapper.writePlan(row) + mapOf("public_id" to JsonPrimitive(row.uuid),
                "revision" to JsonPrimitive(revision), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val canonicalMetric = SyncV2Change(0, "metric", metric.uuid, "upsert", 1,
            JsonObject(NextStructureMapper.writeMetric(metric) + mapOf("public_id" to JsonPrimitive(metric.uuid),
                "revision" to JsonPrimitive(1), "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        merger.bootstrap(access(), null, RoundSyncBootstrapResponse(listOf(canonical(habit), canonical(timerHabit), canonicalMetric),
            20, time, emptyList(), 1, initialPoints, emptyList()))
        habit = requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))
        val yesterday = currentDay.minusDays(1)
        val at = yesterday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val old = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(94_001), value = 1,
            date = yesterday.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = at, createdAt = at,
            recordedTimezone = zone.id, recordedLocalDate = yesterday.toString())
        val producer = producer()
        producer.writeRounds(producer.captureRounds()) { db.completionDao().insertForSync(old) }
        val requests = sender(http); val oldQueue = queue(old)
        val oldDelivery = requireNotNull(requests.sendOperation(access(), oldQueue.operationId))
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(oldDelivery))
        val next = ChallengeRoundRecord(ChallengeRoundHead(habit.uuid, id(94_002), 1), id(4), id(94_003),
            ChallengeRestartIntent(habit.uuid, id(94_002), initial.head.roundUuid, 0, 1))
        val nextMetadata = metadata.updateAndGet {
            it.copy(checkpoints = listOf(ChallengeCheckpoint(next.head, listOf(initial, next)), initialPoints[1]))
        }
        val retainedBeforeCatchup = db.completionDao().getByHabitOnce(habit.id)
        val originBeforeCatchup = originalIntent(oldQueue)
        val receiptBeforeCatchup = requireNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, oldQueue.operationId))
        val priorPlan = requireNotNull(db.syncOutboxDao().getState("plan_node", habit.uuid))
        assertEquals(1L, priorPlan.revision)
        // A new accepted round is not permission to write against an old Plan shadow.
        // Receive the matching revision through the original atomic PULL/sidecar merger.
        val advancedPlan = canonical(habit, 2).copy(sequence = 21)
        val caughtUp = merger.page(access(), requireNotNull(merger.state(access(), true)),
            RoundSyncPullResponse(listOf(advancedPlan), 21, false, time, 1, nextMetadata.checkpoints, nextMetadata.births))
        assertEquals(21L, caughtUp.cursor)
        val currentPlan = requireNotNull(db.syncOutboxDao().getState("plan_node", habit.uuid))
        assertEquals(2L, currentPlan.revision); assertFalse(currentPlan.deleted)
        assertEquals(advancedPlan.payload, Json.parseToJsonElement(requireNotNull(currentPlan.payloadJson)))
        assertEquals(syncPayloadHash(requireNotNull(currentPlan.payloadJson)), currentPlan.payloadHash)
        assertEquals(retainedBeforeCatchup, db.completionDao().getByHabitOnce(habit.id))
        assertEquals(originBeforeCatchup, originalIntent(oldQueue))
        assertEquals(receiptBeforeCatchup, db.nextRequestDao().acceptance(NEXT_OPERATION, oldQueue.operationId))
        assertEquals(initial.head, requireNotNull(merger.challengeMetadata(access())).requireBirth("activity_event", old.uuid, habit.uuid).head)
        habit = requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))
        val emptyRound = CheckHistoryReader(db, tokens, sessions).readProjection(habit, currentDay, zone)
        assertEquals(next.head, emptyRound.roundHead); assertEquals(0, emptyRound.qualifiedDayCount)
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size); cleanRoot(); completeReads.set(0)
        val repository = creatingHabits()
        val currentId = repository.logCompletion(app, habit.id, 1)
        habit = requireNotNull(db.habitDao().getHabitByUuid(habit.uuid))
        val current = requireNotNull(db.completionDao().getCompletionById(currentId))
        assertEquals(currentDay.toString(), current.recordedLocalDate)
        val actualAt = habit.activityRateUpdatedAt
        val retained = db.completionDao().getByHabitOnce(habit.id)
        val allRate = ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, retained.map { it.businessDate }, actualAt, zone)
        val roundRate = ActivityRateCalculator.calculate(habit.schedule, habit.createdAt, listOf(current.businessDate), actualAt, zone)
        assertNotEquals("The retained prior round must materially affect this fixture's rate", allRate, roundRate)
        assertEquals(allRate, habit.activityRate); cleanRoot()
        val view = CheckHistoryReader(db, tokens, sessions).readProjection(habit, currentDay, zone, actualAt)
        assertEquals(1, view.qualifiedDayCount); assertEquals(current.id, view.todayLatestCompletionId)
        assertEquals(roundRate, requireNotNull(view.card).activityRate); assertEquals(next.head, view.roundHead)
        assertEquals("Current-round display and whole-retained writer must both use their issued evidence", 0, completeReads.get())
        val currentQueue = queue(current)
        val delivery = requireNotNull(requests.sendOperation(access(), currentQueue.operationId))
        val beforeAck = cleanRoot(); completeReads.set(0)
        assertEquals(NextOperationAcceptance.COMMITTED, requests.acceptOperation(delivery))
        assertEquals(beforeAck.revision + 1, cleanRoot().revision)
        assertEquals(view, CheckHistoryReader(db, tokens, sessions).readProjection(habit, currentDay, zone, actualAt))
        assertEquals(0, completeReads.get()); assertTrue(db.syncOutboxDao().getAll().isEmpty())
        val independent = RecurringCompletionEvidenceReader(db, tokens, sessions).read(habit, currentDay)
        assertEquals(listOf(current.uuid), independent.completions.map { it.uuid })
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(0, count("count_command_intents")); assertFalse(db.inTransaction())
    }

    @Test(timeout = 150_000) fun sameValueAndRepeatedCheckEventsNeverSealAnyPartOfTheOriginalDirtyTransaction() = runBlocking<Unit> {
        storage.ownRecoveryMethod(requireNotNull(coroutineContext[Job]))
        configure(); val first = append(6)
        val countHabit = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val input = habit.copy(id = 0, uuid = id(95_001), name = "Mixed COUNT stays unsealed", habitType = HabitType.COUNTING, targetValue = 10)
            val assigned = db.habitDao().insert(input)
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            input.copy(id = assigned)
        }
        projected(); CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today)
        val initial = cleanRoot(); val unchanged = business()
        var updates = 0
        producer().write(local()) { assertEquals(1, db.completionDao().updateForSync(first)); updates++ }
        assertEquals(1, updates); assertFalse(db.inTransaction()); assertFalse(db.openHelper.writableDatabase.inTransaction())
        assertEquals(unchanged, business()); assertEquals(1L, dirty())
        assertEquals(initial, CountTrustSeal.read(db.openHelper.writableDatabase))
        completeReads.set(0); assertEquals(1L, projected().todayQuantity)
        assertTrue(completeReads.get() > 0); cleanRoot()

        // INSERT then UPDATE of that same identity must not disappear into a net INSERT.
        val beforeRepeated = cleanRoot()
        val added = first.copy(id = 0, uuid = id(95_002))
        producer().write(local()) {
            val assigned = db.completionDao().insertForSync(added)
            assertEquals(1, db.completionDao().updateForSync(added.copy(id = assigned))); updates++
        }
        assertEquals(2, updates); assertEquals(1L, dirty())
        assertEquals(beforeRepeated, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(2, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(1, db.syncOutboxDao().getAll().count { it.entityUuid == added.uuid })
        assertNotNull(db.nextRequestDao().origin(NEXT_OPERATION, db.syncOutboxDao().getAll().single { it.entityUuid == added.uuid }.operationId))
        completeReads.set(0); assertEquals(2L, projected().todayQuantity); assertTrue(completeReads.get() > 0); cleanRoot()

        // A legal COUNT addition cannot make a mixed unsupported CHECK transaction clean.
        val beforeMixed = cleanRoot()
        val countFact = first.copy(id = 0, habitId = countHabit.id, habitUuid = countHabit.uuid, uuid = id(95_003), value = 2)
        producer().write(local()) {
            NextCountDayStore(db).capture(countHabit, countFact)
            db.completionDao().insertForSync(countFact)
            assertEquals(1, db.completionDao().updateForSync(first)); updates++
        }
        assertEquals(3, updates); assertFalse(db.inTransaction()); assertFalse(db.openHelper.writableDatabase.inTransaction())
        assertEquals(1L, dirty()); assertEquals(beforeMixed, CountTrustSeal.read(db.openHelper.writableDatabase))
        assertEquals(2, requireNotNull(db.completionDao().getCompletionByUuid(countFact.uuid)).value)
        val committed = business(); completeReads.set(0)
        assertEquals(2L, CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today).todayQuantity)
        assertTrue(completeReads.get() > 0); assertEquals(2L, projected().todayQuantity); cleanRoot()
        assertEquals(committed, business()); completeReads.set(0)
        CountHistoryReader(db, tokens, sessions).readSummary(countHabit, today); projected()
        assertEquals(0, completeReads.get()); assertEquals(committed, business())
    }
}
