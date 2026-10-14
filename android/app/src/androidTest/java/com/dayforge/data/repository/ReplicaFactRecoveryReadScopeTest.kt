package com.dayforge.data.repository

import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.*
import com.dayforge.data.local.*
import com.dayforge.domain.model.*
import java.security.KeyStore
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/DataStore and, for the bounded cost comparison, real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class ReplicaFactRecoveryReadScopeTest : NextCoreRequestFixture() {
    private val today = LocalDate.of(2000, 1, 1)
    private var keyAlias: String? = null

    @After fun removeOwnedKey() {
        keyAlias?.let { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(it) }
    }

    private suspend fun initialize() {
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

    @Test fun sharedAndIndependentFactAuditsAreEqualAndRealCipherCostDoesNotScaleWithReaders() = runBlocking<Unit> {
        val alias = "dayforge.test.recovery-read.${UUID.randomUUID()}".also { keyAlias = it }
        val cipher = MeasuredCipher(AndroidKeystoreTokenCipher(alias))
        tokens = TokenManager(dataStore, cipher)
        preferences = PreferencesManager(dataStore, tokens = tokens)
        tokens.saveLoginSession("synthetic-encrypted", "synthetic-refresh", "member", id(1), false)
        initialize()
        val original = access()
        val recurring = RecurringCompletionEvidenceReader(db, tokens, sessions)
        val timers = TimerHistoryReader(db, tokens, sessions)
        cipher.reset()
        val independentStart = SystemClock.elapsedRealtime()
        val independent = sessions.exclusive { db.withTransaction { listOf(habit, timerHabit).map { row ->
            recurring.recoveryInTransaction(row, today) to timers.recoveryInTransaction(row, today)
        } } }
        val independentMillis = SystemClock.elapsedRealtime() - independentStart
        val independentCalls = cipher.calls.get()
        val independentCipherNanos = cipher.nanos.get()
        cipher.reset()
        val sharedStart = SystemClock.elapsedRealtime()
        val shared = ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) { borrowed ->
            val capturedCalls = cipher.calls.get()
            listOf(habit, timerHabit).map { row ->
                (recurring.recoveryInTransaction(row, today, borrowed) to
                    timers.recoveryInTransaction(row, today, borrowed)).also { assertEquals(capturedCalls, cipher.calls.get()) }
            }
        }
        assertEquals(independent.map { it.first }, shared.map { it.first })
        for (index in independent.indices) {
            assertEquals(independent[index].second.logs, shared[index].second.logs)
            assertEquals(independent[index].second.completedMillis, shared[index].second.completedMillis)
            assertEquals(independent[index].second.hasUnfinishedTimer, shared[index].second.hasUnfinishedTimer)
        }
        assertTrue("Transaction-local proof must avoid per-reader decryptions", cipher.calls.get() < independentCalls)
        android.util.Log.i("RecoveryReadCost", "independentMs=$independentMillis sharedMs=${SystemClock.elapsedRealtime() - sharedStart} " +
            "independentDecrypts=$independentCalls sharedDecrypts=${cipher.calls.get()} " +
            "independentCipherNs=$independentCipherNanos sharedCipherNs=${cipher.nanos.get()}")
        // A second transaction must authenticate again, even when the ciphertext is unchanged.
        cipher.reset()
        ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) { it.borrow(db, tokens) }
        assertTrue(cipher.calls.get() > 0)
        cipher.unreadable = true
        assertEquals("SYNC_ACCOUNT_CHANGED", rejected {
            ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) { error("Must not enter") }
        }.message)
    }

    @Test fun everyRegisteredAuthorityFieldIsRequiredAtEntryNotOnlyTheWeakerCoreSession() = runBlocking<Unit> {
        initialize()
        val original = access()
        val wrong = listOf(
            original.copy(session = original.session.copy(authentication = original.session.authentication.copy(userId = id(60)))),
            original.copy(session = original.session.copy(authentication = original.session.authentication.copy(generation = id(61)))),
            original.copy(session = original.session.copy(serverInstanceId = id(62))),
            original.copy(session = original.session.copy(syncEpoch = id(63))),
            original.copy(deviceId = id(64)), original.copy(capabilityRevision = 2),
            original.copy(capabilities = setOf("sync.read"))
        )
        for (expected in wrong) assertEquals("SYNC_ACCOUNT_CHANGED", rejected {
            ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, expected) { error("Must not enter") }
        }.message)
        assertEquals("SYNC_RECOVERY_READ_SCOPE_UNREGISTERED", rejected {
            ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, LocalSyncAccess(original.session)) { error("Must not enter") }
        }.message)
    }

    @Test fun scopeChangesDuringTheTransactionRejectItsResultAndCloseItsBorrowedEvidence() = runBlocking<Unit> {
        initialize()
        val original = access()
        val originalPreferences = dataStore.data.first()
        val mutations: List<suspend () -> Unit> = listOf(
            { dataStore.edit { it[stringPreferencesKey("user_public_id")] = id(60) } },
            { dataStore.edit { it[stringPreferencesKey("auth_session_generation")] = id(61) } },
            { dataStore.edit { it[stringPreferencesKey("sync_server_instance_id")] = id(62) } },
            { dataStore.edit { it[stringPreferencesKey("sync_epoch")] = id(63) } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_id")] = id(64) } },
            { dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" } },
            { dataStore.edit { it[stringSetPreferencesKey("sync_device_capabilities")] = setOf("sync.read") } },
            { dataStore.edit { it.remove(stringPreferencesKey("access_token")) } }
        )
        for (mutate in mutations) {
            lateinit var saved: ReplicaFactRecoveryReadScope
            try {
                assertEquals("SYNC_ACCOUNT_CHANGED", rejected {
                    ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) {
                        saved = it; it.borrow(db, tokens); mutate(); Unit
                    }
                }.message)
                assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { saved.borrow(db, tokens) }.message)
            } finally { dataStore.updateData { originalPreferences } }
        }
    }

    @Test fun evidenceCannotBeBorrowedByAnotherJobStoreOrAfterItsCallbackEvenInsideANewTransaction() = runBlocking<Unit> {
        initialize()
        val original = access()
        val foreign = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java).build()
        lateinit var saved: ReplicaFactRecoveryReadScope
        try {
            ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) { borrowed ->
                saved = borrowed
                assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { borrowed.borrow(foreign, tokens) }.message)
                assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { borrowed.borrow(db, TokenManager(dataStore)) }.message)
                val childFailure = coroutineScope { async { rejected { borrowed.borrow(db, tokens) } }.await() }
                assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", childFailure.message)
                borrowed.borrow(db, tokens)
            }
            assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { saved.borrow(db, tokens) }.message)
            sessions.exclusive { db.withTransaction {
                assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { saved.borrow(db, tokens) }.message)
            } }
        } finally { foreign.close() }
    }

    @Test fun cancellationNeverReturnsEvidenceAndClosesAnAlreadyCapturedScope() = runBlocking<Unit> {
        initialize()
        val original = access()
        lateinit var saved: ReplicaFactRecoveryReadScope
        val failure = runCatching {
            ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) {
                saved = it; throw CancellationException("synthetic cancellation")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("SYNC_RECOVERY_READ_SCOPE_CLOSED", rejected { saved.borrow(db, tokens) }.message)
        var entered = false
        val cancelled = Job().apply { cancel() }
        try {
            assertTrue(runCatching { withContext(cancelled) {
                ReplicaFactRecoveryReadScope.withSnapshot(db, tokens, sessions, original) { entered = true }
            } }.exceptionOrNull() is CancellationException)
            assertFalse(entered)
        } finally { cancelled.cancel() }
    }

    private class MeasuredCipher(private val delegate: TokenCipher) : TokenCipher {
        val calls = AtomicInteger()
        val nanos = AtomicLong()
        @Volatile var unreadable = false
        override fun encrypt(value: String): String = delegate.encrypt(value)
        override fun decrypt(value: String): String? {
            calls.incrementAndGet()
            val start = SystemClock.elapsedRealtimeNanos()
            return try { if (unreadable) null else delegate.decrypt(value) }
            finally { nanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start) }
        }
        fun reset() { calls.set(0); nanos.set(0) }
    }
}
