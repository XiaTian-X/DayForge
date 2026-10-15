package com.dayforge.data.repository

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Small real Room + platform Keystore contention regression, not a UI frame benchmark. */
@RunWith(AndroidJUnit4::class)
class LocalOperationLatencyTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.latency.${UUID.randomUUID()}"
    private val decryptCalls = java.util.concurrent.atomic.AtomicLong()
    private val decryptMillis = java.util.concurrent.atomic.AtomicLong()
    override fun tokenCipher(): TokenCipher {
        val delegate = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = delegate.encrypt(value)
            override fun decrypt(value: String): String? {
                val start = SystemClock.elapsedRealtime()
                return try { delegate.decrypt(value) } finally {
                    decryptCalls.incrementAndGet()
                    decryptMillis.addAndGet(SystemClock.elapsedRealtime() - start)
                }
            }
        }
    }

    @After fun removeTestKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun localCreationCountAndMetricWritesRemainBoundedWithAuthenticatedReaders() = runBlocking<Unit> {
        register()
        val habits = creatingHabits()
        val metrics = creatingMetrics()
        val checkIns = com.dayforge.domain.service.CheckInService(habits, db.completionDao(), db.timeLogDao())
        val durations = mutableListOf<Long>()
        suspend fun measure(name: String, action: suspend () -> Unit) {
            val start = SystemClock.elapsedRealtime()
            val calls = decryptCalls.get()
            val cipherTime = decryptMillis.get()
            action()
            val elapsed = SystemClock.elapsedRealtime() - start
            durations += elapsed
            Log.i("LocalLatencyTest", "operation=$name completion_ms=$elapsed " +
                "concurrent_decrypt_calls=${decryptCalls.get() - calls} " +
                "concurrent_decrypt_ms=${decryptMillis.get() - cipherTime}")
        }
        coroutineScope {
            // A visible list, detail read, and icon/authority reader; no artificial capacity load.
            val readers = (0..2).map { index -> launch(Dispatchers.IO) {
                while (isActive) {
                    when (index) {
                        0 -> com.dayforge.domain.service.readCurrentHabitSnapshot {
                            val current = requireNotNull(habits.getHabitById(habit.id))
                            check(current.uuid == habit.uuid)
                            CountHistoryReader(db, tokens, sessions).read(current)
                        }
                        1 -> editor.metric(metric.id)
                        else -> assertNotNull(tokens.localIconAccess())
                    }
                    delay(100)
                }
            } }
            try {
                repeat(3) { n ->
                    measure("create") {
                        val preparationStart = SystemClock.elapsedRealtime()
                        val ticket = creator.capture()
                        Log.i("LocalLatencyTest", "stage=create_prepare completion_ms=${SystemClock.elapsedRealtime() - preparationStart}")
                        val submitStart = SystemClock.elapsedRealtime()
                        habits.createHabit("Latency $n", "", HabitType.CHECK_IN, 0, "#123456",
                            HabitSchedule.Daily, appearance = ObjectAppearance(IconReference.Role("habit.custom"), "#123456", "object"),
                            completionPolicy = "recurring", creationAuthority = ticket)
                        Log.i("LocalLatencyTest", "stage=create_submit completion_ms=${SystemClock.elapsedRealtime() - submitStart}")
                    }
                    measure("count") {
                        assertTrue(checkIns.incrementCount(app, habit.id) is com.dayforge.data.model.CheckInResult.Success)
                    }
                    measure("metric") {
                        val preparationStart = SystemClock.elapsedRealtime()
                        val snapshot = metrics.getMetricForEditing(metric.id)
                        Log.i("LocalLatencyTest", "stage=metric_prepare completion_ms=${SystemClock.elapsedRealtime() - preparationStart}")
                        val submitStart = SystemClock.elapsedRealtime()
                        metrics.recordValue(metric.id, n.toDouble(), "", authority = requireNotNull(snapshot.authority),
                            expectedMetricUuid = metric.uuid)
                        Log.i("LocalLatencyTest", "stage=metric_submit completion_ms=${SystemClock.elapsedRealtime() - submitStart}")
                    }
                }
            } finally { readers.forEach { it.cancel() }; readers.joinAll() }
        }
        assertEquals(5, count("habits"))
        assertEquals(3, count("completions"))
        assertEquals(3, count("metric_logs"))
        assertEquals(9, count("sync_outbox"))
        assertEquals(9, count("next_request_origins"))
        val sorted = durations.sorted()
        Log.i("LocalLatencyTest", "samples=${sorted.size} median_ms=${sorted[sorted.size / 2]} max_ms=${sorted.last()}")
        assertTrue("Local operation exceeded 2 seconds: $durations", sorted.last() <= 2_000)
        assertTrue("Median local operation exceeded 1 second: $durations", sorted[sorted.size / 2] <= 1_000)
    }
}
