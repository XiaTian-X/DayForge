package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.domain.model.*
import com.dayforge.domain.service.FailureChecker
import com.dayforge.domain.service.HabitStatusCalculator
import com.dayforge.data.model.HabitType
import com.dayforge.ui.screens.dashboard.DashboardHabitListBuilder
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Accepted formal metadata, two real COUNT consumers, original producer and exact prompt source. */
@RunWith(AndroidJUnit4::class)
class CountDisplayFormalBatchTest : NextObjectEditorFixture() {
    private val spy = CountDisplayNativeSpy()
    private val calls = AtomicInteger()
    private val alias = "dayforge.test.display-formal.${UUID.randomUUID()}"
    init { storage.isolatedBuilder = { spy.build(it) } }
    override fun tokenCipher(): TokenCipher {
        val real = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = real.encrypt(value)
            override fun decrypt(value: String): String? { calls.incrementAndGet(); return real.decrypt(value) }
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private suspend fun seed(): Pair<HabitEntity, CompletionEntity> {
        register()
        val second = timerHabit.copy(name = "Second formal count", habitType = HabitType.COUNTING, targetValue = 7,
            planMetadata = requireNotNull(timerHabit.planMetadata).copy(targetUnit = null))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(second)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val activities = listOf(habit, second)
        val checkpoints = activities.map { row -> val head = initialChallengeRoundHead(row.uuid)
            ChallengeCheckpoint(head, listOf(ChallengeRoundRecord(head, null, null, null))) }
        fun change(type: String, uuid: String, body: JsonObject) = SyncV2Change(0, type, uuid, "upsert", 1,
            JsonObject(body + mapOf("public_id" to JsonPrimitive(uuid), "revision" to JsonPrimitive(1),
                "created_at" to JsonPrimitive(time), "updated_at" to JsonPrimitive(time), "deleted_at" to JsonNull)), time)
        val changes = activities.map { change("plan_node", it.uuid, NextStructureMapper.writePlan(it)) } +
            change("metric", metric.uuid, NextStructureMapper.writeMetric(metric))
        val http = channel().first
        val once = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        NextSyncMergeStore(db, tokens, sessions, OneTimeAcceptedEventStore(db, tokens, sessions, once),
            NextTimerRequestStore(db, tokens, sessions, sender(http))).bootstrap(access(), null,
            RoundSyncBootstrapResponse(changes, 20, time, emptyList(), 1, checkpoints, emptyList()))
        val today = DateTimeUtils.today(); val zone = ZoneId.systemDefault()
        val at = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val input = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, uuid = id(91_003), value = 2,
            date = today.atStartOfDay(zone).toInstant().toEpochMilli(), actualCompletedAt = at, createdAt = at,
            recordedLocalDate = today.toString(), recordedTimezone = zone.id)
        val producer = producer()
        producer.writeRounds(producer.captureRounds()) {
            NextCountDayStore(db).capture(habit, input); db.completionDao().insertForSync(input)
        }
        CountHistoryReader(db, tokens, sessions).readSummary(habit, today)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(requireNotNull(tokens.localCoreWriteAccess())) {
            assertTrue(requireNotNull(it).formal); assertTrue(it.eligible(habit)); assertTrue(it.eligible(second))
        } }
        return second to requireNotNull(db.completionDao().getCompletionByUuid(input.uuid))
    }
    private suspend fun original(row: HabitEntity): CountSummary {
        val source = RecurringCompletionEvidenceReader(db, tokens, sessions).read(row, DateTimeUtils.today())
        val quantities = source.completions.groupBy { it.businessDate }.mapValues { (_, rows) ->
            rows.fold(0L) { total, fact -> Math.addExact(total, fact.value.toLong()) } }
        return CountHistory(source.today, source.policies, quantities,
            source.policies[source.today] ?: if (source.today in source.unknownDates) null else CountDayPolicy(row.targetValue, row.isCountdown),
            source.unknownDates, source.completions, source.roundHead, source.countIdentity).summary
    }

    @Test fun actualHomeAndExactPromptShareOneAuthenticatedRootPerBatchKeepFreshGuardsAndMatchOriginal() = runBlocking<Unit> {
        val (second, fact) = seed()
        val counts = CountHistoryReader(db, tokens, sessions)
        val expected = mapOf(habit.id to original(habit), second.id to original(second))
        val assertion = CountConsumerReadFixtureAssertions(db, tokens, sessions); val image = assertion.capture()
        val dashboard = DashboardReadRepository(db, tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions),
            counts, CheckHistoryReader(db, tokens, sessions))
        calls.set(0); spy.begin()
        val batch = try { dashboard.read(ZonedDateTime.now()).also { assertTrue(dashboard.publish(it) { }) } }
            finally { assertEquals(1, spy.end()) }
        assertEquals(8, calls.get()) // Original entry, round guards, final display and publication.
        assertEquals(expected, batch.counts)
        val checks = CheckHistoryReader(db, tokens, sessions)
        val status = HabitStatusCalculator(FailureChecker(db.completionDao(), db.timeLogDao(), counts, checks),
            db.completionDao(), db.timeLogDao(), countHistoryReader = counts, checkHistoryReader = checks)
        val cards = DashboardHabitListBuilder(status).project(batch)
        cards.filter { it.habit.id in expected.keys }.forEach {
            assertNull(it.countHistory); assertSame(batch.counts.getValue(it.habit.id), it.countSummary)
        }
        assertEquals(2, cards.count { it.habit.id in expected.keys })
        assertion.assertUnchanged(image)
        val widgets = WidgetFactReader(db, tokens, sessions, preferences, counts)
        val snapshot = widgets.read(habit)
        assertEquals(fact.uuid, snapshot.claim.completionUuid)
        calls.set(0); spy.begin()
        var published: WidgetFactSnapshot? = null
        try { widgets.readCompletionPlanAndPublish(snapshot, read = { current, _ -> current }) { published = it } }
        finally { assertEquals(1, spy.end()) }
        assertEquals(7, calls.get()) // This is the actual prompt reader; coordinator preference reads are separate.
        assertEquals(snapshot.claim, requireNotNull(published).claim)
        assertEquals(expected.getValue(habit.id), requireNotNull(published).count)
        assertion.assertUnchanged(image)
    }
}
