package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real list reader and Keystore, small history; not an end-to-end UI latency claim. */
@RunWith(AndroidJUnit4::class)
class TimerDisplayReadCostTest : NextCoreRequestFixture() {
    private val alias = "dayforge.test.timer-display.${UUID.randomUUID()}"
    private val calls = AtomicInteger()
    private var rejectAt = -1
    override fun tokenCipher(): TokenCipher {
        val delegate = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = delegate.encrypt(value)
            override fun decrypt(value: String): String? =
                if (calls.incrementAndGet() == rejectAt) null else delegate.decrypt(value)
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    private suspend fun compare(active: Boolean) {
        register()
        if (active) start()
        val writer = NextTimerWriter(db, tokens, sessions)
        val before = db.withTransactionProof()
        val authority = writer.capture(timerHabit.id, timerHabit)
        val history = TimerHistoryReader(db, tokens, sessions).read(timerHabit)
        calls.set(0)
        val begin = SystemClock.elapsedRealtime()
        val snapshot = requireNotNull(writer.statusSnapshot(timerHabit))
        val elapsed = SystemClock.elapsedRealtime() - begin
        Log.i("TimerDisplayCost", "active=$active elapsed_ms=$elapsed decrypts=${calls.get()}")
        assertEquals("Fresh entry and exit, not one check per participant", 2, calls.get())
        assertEquals(authority, snapshot.authority)
        assertEquals(history, snapshot.history)
        assertEquals(active, snapshot.activeLog != null)
        assertNull(snapshot.startGuard)
        if (active) assertEquals(60, snapshot.policy!!.targetSeconds)
        assertEquals(writer.widgetSnapshot(timerHabit)!!.copy(startGuard = null), snapshot)
        assertEquals(before, db.withTransactionProof())
    }
    private suspend fun com.dayforge.data.local.HabitDatabase.withTransactionProof() =
        withTransaction { nextRestartDatabaseProof(this) }

    @Test fun idleListReadPreservesAuthorityAndHistory() = runBlocking { compare(false) }
    @Test fun activeListReadPreservesOriginalPolicyAndHistory() = runBlocking { compare(true) }

    @Test fun entryAndExitCredentialFailureCannotPublishOrChangeData() = runBlocking<Unit> {
        register(); start()
        val writer = NextTimerWriter(db, tokens, sessions)
        val before = db.withTransactionProof()
        calls.set(0); rejectAt = 1
        assertNull(writer.statusSnapshot(timerHabit))
        calls.set(0); rejectAt = 2
        assertEquals("RECURRING_READ_SESSION_CHANGED", rejected { writer.statusSnapshot(timerHabit) }.message)
        rejectAt = -1
        assertEquals(before, db.withTransactionProof())
        assertNotNull(writer.statusSnapshot(timerHabit))
    }

    @Test fun editedActiveTargetKeepsStartPolicyAndStalePlanHasNoAction() = runBlocking<Unit> {
        register(); start()
        producer().write(local()) { db.habitDao().update(timerHabit.copy(targetValue = 3, isCountdown = true)) }
        val writer = NextTimerWriter(db, tokens, sessions)
        assertNull(writer.statusSnapshot(timerHabit))
        val current = requireNotNull(db.habitDao().getVisibleHabitById(timerHabit.id))
        val before = db.withTransactionProof()
        val read = requireNotNull(writer.statusSnapshot(current))
        assertEquals(60, read.policy!!.targetSeconds)
        assertFalse(read.policy.isCountdown)
        assertEquals(writer.widgetSnapshot(current)!!.copy(startGuard = null), read)
        assertEquals(before, db.withTransactionProof())
    }
}
