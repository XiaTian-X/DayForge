package com.dayforge.data.repository

import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.*
import com.dayforge.util.DateTimeUtils
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical Room + real Keystore. No authority, SQL, history or outbox stand-ins. */
@RunWith(AndroidJUnit4::class)
class RecurringDisplayReadScopeTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.display-read.${UUID.randomUUID()}"
    private val calls = AtomicInteger()
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? { calls.incrementAndGet(); return actual.decrypt(value) }
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private suspend fun proof() = db.withTransaction { nextRestartDatabaseProof(db) }
    private suspend fun <T> display(block: suspend (RecurringDisplayReadScope) -> T): T =
        RecurringDisplayReadScope.withSnapshot(db, tokens, sessions, block = block)

    @Test fun realPlainProjectionUsesTwoFreshDecryptsAndMatchesIndependentReaders() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        repo.logCompletion(app, habit.id, 3)
        val current = requireNotNull(repo.getHabitById(habit.id))
        val before = proof()
        val standalone = CountHistoryReader(db, tokens, sessions).read(current)
        calls.set(0)
        val result = repo.getRecurringSnapshot(current)
        assertEquals(2, calls.get())
        assertEquals(standalone, result.countHistory)
        assertEquals(NextStructureMapper.writePlan(current), result.authority.original)
        assertEquals(before, proof())
        repo.getRecurringSnapshot(current)
        assertEquals(4, calls.get()) // No cross-operation proof cache.
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

    @Test fun challengeProjectionMatchesIndependentCountAndTimerEvidenceWithFewerDecrypts() = runBlocking<Unit> {
        challenge()
        val repo = creatingHabits()
        for (row in listOf(habit, timerHabit)) {
            val current = requireNotNull(repo.getHabitById(row.id))
            val before = proof()
            calls.set(0)
            val start = SystemClock.elapsedRealtime()
            val independent = sessions.exclusive { db.withTransaction {
                val edit = editor.habitInTransaction(row.id)
                val history: Any = if (row.habitType == HabitType.COUNTING)
                    CountHistoryReader(db, tokens, sessions).readInTransaction(current, DateTimeUtils.today())
                else TimerHistoryReader(db, tokens, sessions).readInTransaction(current, DateTimeUtils.today())
                edit to history
            } }
            val oldMillis = SystemClock.elapsedRealtime() - start
            val oldCalls = calls.get()
            calls.set(0)
            val sharedStart = SystemClock.elapsedRealtime()
            val view = repo.getRecurringSnapshot(current)
            assertEquals(independent.first.authority!!.original, view.authority.original)
            assertEquals(independent.first.authority!!.rounds!!.metadataJson, view.authority.rounds!!.metadataJson)
            assertEquals(independent.second, view.countHistory ?: view.timerHistory)
            assertTrue("Shared proof must reduce real decryptions", calls.get() < oldCalls)
            android.util.Log.i("DisplayReadCost", "type=${row.habitType} independent_ms=$oldMillis shared_ms=${SystemClock.elapsedRealtime() - sharedStart} independent_decrypts=$oldCalls shared_decrypts=${calls.get()}")
            assertEquals(before, proof())
        }
        val batchReader = DashboardReadRepository(db, tokens, sessions,
            ReplicaPresentationRepository(db, tokens, sessions), CountHistoryReader(db, tokens, sessions),
            CheckHistoryReader(db, tokens, sessions))
        val batch = batchReader.read(java.time.ZonedDateTime.now())
        assertEquals(CountHistoryReader(db, tokens, sessions).read(requireNotNull(repo.getHabitById(habit.id))),
            batch.counts.getValue(habit.id))
        assertTrue(batchReader.publish(batch) {})
    }

    @Test fun countDisplayIdentityCannotAdoptRestartedRoundAndNewRoundRemainsWritable() = runBlocking<Unit> {
        habit = habit.copy(targetCycles = 3, isActive = false)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        challenge()
        val repo = creatingHabits()
        val before = repo.getRecurringSnapshot(requireNotNull(repo.getHabitById(habit.id)))
        val identity = requireNotNull(before.countHistory?.actionIdentity)
        val http = channel().first
        NextChallengeRestartRepository(db, tokens, sessions, http).restart(before.habit, before.authority)
        val pending = proof()
        try {
            repo.logCompletion(app, habit.id, countIdentity = identity)
            fail("Old display must not write into the new round")
        } catch (expected: IllegalStateException) { assertEquals("COUNT_ACTION_EXPIRED", expected.message) }
        assertEquals(pending, proof())
        val fresh = repo.getRecurringSnapshot(requireNotNull(repo.getHabitById(habit.id)))
        assertNotEquals(identity.roundHead, fresh.countHistory!!.roundHead)
        repo.logCompletion(app, habit.id, countIdentity = requireNotNull(fresh.countHistory.actionIdentity))
        assertEquals(1L, repo.getCountHistory(requireNotNull(repo.getHabitById(habit.id))).todayQuantity)
    }

    @Test fun checkProjectionRetainsFactsAndHistoryWithoutExtraParticipantDecryptions() = runBlocking<Unit> {
        register()
        val repo = creatingHabits()
        val id = repo.createHabit("Check", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Daily,
            appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
            completionPolicy = "recurring", creationAuthority = creator.capture())
        repo.logCompletion(app, id, 1)
        val current = requireNotNull(repo.getHabitById(id))
        val independent = CheckHistoryReader(db, tokens, sessions).read(current)
        calls.set(0)
        assertEquals(independent, repo.getRecurringSnapshot(current).checkHistory)
        assertEquals(2, calls.get())
    }

    @Test fun scopeCannotEscapeItsJobTransactionOrTokenManager() = runBlocking<Unit> {
        register()
        lateinit var escaped: RecurringDisplayReadScope
        display { borrowed ->
            escaped = borrowed
            assertNotNull(borrowed.borrow(db, tokens))
            val foreign = TokenManager(dataStore, tokenCipher())
            assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { borrowed.borrow(db, foreign) }.message)
            val otherDatabase = androidx.room.Room.databaseBuilder(app, HabitDatabase::class.java,
                "unused-display-scope-${UUID.randomUUID()}").build()
            try {
                assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { borrowed.borrow(otherDatabase, tokens) }.message)
            } finally { otherDatabase.close() } // Rejected before opening the foreign database.
            coroutineScope {
                assertEquals("DISPLAY_READ_SCOPE_CLOSED", async {
                    rejected { borrowed.borrow(db, tokens) }.message
                }.await())
            }
        }
        assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { escaped.borrow(db, tokens) }.message)
        display { assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { escaped.borrow(db, tokens) }.message) }
        assertEquals("DISPLAY_READ_SCOPE_NESTED", rejected {
            db.withTransaction { display { error("Nested scope must not enter") } }
        }.message)
    }

    @Test fun accountPermissionsReplicaAndDeviceChangesDuringReadRejectPublication() = runBlocking<Unit> {
        challenge()
        val original = dataStore.data.first()
        val changes: List<suspend () -> Unit> = listOf(
            { tokens.saveLoginSession("new", "refresh", "member", id(1), false) },
            { dataStore.edit { it[stringPreferencesKey("user_public_id")] = id(99) } },
            { dataStore.edit { it[stringPreferencesKey("sync_epoch")] = id(99) } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_id")] = id(99) } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" } },
            { dataStore.edit { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") } },
            { dataStore.edit { it[stringPreferencesKey("access_token")] = "enc:v1:broken" } },
            { dataStore.edit { it[ReplicaSwitchGuard.POINTER] = "test-fence" } }
        )
        val before = proof()
        for (change in changes) {
            dataStore.updateData { original }
            assertEquals("RECURRING_READ_SESSION_CHANGED", rejected { display { change() } }.message)
            assertEquals(before, proof())
        }
        dataStore.updateData { original }
        assertNotNull(display { it.borrow(db, tokens) })
    }

    @Test fun deletedWarmKeystoreKeyDuringReadFailsExitAndNextEntry() = runBlocking<Unit> {
        register()
        assertEquals("RECURRING_READ_SESSION_CHANGED", rejected { display {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        } }.message)
        assertEquals("RECURRING_READ_ACCESS_REQUIRED", rejected { display { error("Must not enter") } }.message)
    }

    @Test fun cancelledReadClosesScopeAndReleasesAccountLock() = runBlocking<Unit> {
        register()
        val entered = CompletableDeferred<RecurringDisplayReadScope>()
        val job = launch { display { entered.complete(it); awaitCancellation() } }
        val abandoned = entered.await()
        job.cancelAndJoin()
        withTimeout(5_000) { display {
            assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { abandoned.borrow(db, tokens) }.message)
            assertNotNull(it.borrow(db, tokens))
        } }
    }

    @Test fun accidentalWriteRollsBackRowsOutboxAndClosesScope() = runBlocking<Unit> {
        register()
        val before = proof()
        lateinit var escaped: RecurringDisplayReadScope
        assertEquals("DISPLAY_READ_SCOPE_WROTE_DATA", rejected { display {
            escaped = it
            db.habitDao().updateActivityRate(habit.id, 8)
        } }.message)
        assertEquals(before, proof())
        assertEquals("DISPLAY_READ_SCOPE_CLOSED", rejected { escaped.borrow(db, tokens) }.message)
    }

    @Test fun sharedReadersStillAuditRealTimerAndCountCorruptionWithoutRepair() = runBlocking<Unit> {
        register()
        start()
        val repo = creatingHabits()
        val timer = requireNotNull(repo.getHabitById(timerHabit.id))
        val independent = TimerHistoryReader(db, tokens, sessions).read(timer)
        assertNotNull(independent.activeLog)
        assertEquals(independent, repo.getRecurringSnapshot(timer).timerHistory)
        db.openHelper.writableDatabase.execSQL("UPDATE timer_segments SET sequence=4294967297 WHERE sessionUuid=?", arrayOf(id(20)))
        val timerBad = proof()
        assertEquals("TIMER_STORAGE_INVALID", rejected { repo.getRecurringSnapshot(timer) }.message)
        assertEquals(timerBad, proof())

        repo.logCompletion(app, habit.id, 2)
        val current = requireNotNull(repo.getHabitById(habit.id))
        db.openHelper.writableDatabase.execSQL("UPDATE completions SET value=4294967297 WHERE habitId=?", arrayOf(habit.id))
        val countBad = proof()
        assertEquals("COUNT_FACT_INVALID", rejected { repo.getRecurringSnapshot(current) }.message)
        assertEquals(countBad, proof())
    }
}
