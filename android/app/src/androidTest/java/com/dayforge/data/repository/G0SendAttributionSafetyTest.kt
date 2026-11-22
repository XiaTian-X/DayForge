package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.TokenCipher
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0SendAttributionSafetyTest : G0SendAttributionFixture() {
    @Test(timeout = 60_000) fun detachedThreadDecryptionsAreCountedWithoutInheritingLegacyTrace() = runBlocking<Unit> {
        register(); val context = access(); val legacy = G0PhaseTrace(driverTiming = false)
        val executor = Executors.newSingleThreadExecutor(); val before = decryptCount.get()
        try {
            legacy.run { sendTrace.capture {
                assertTrue(tokens.isCurrentSyncAuthority(context))
                val detached = executor.submit<Boolean> { runBlocking { tokens.syncAuthenticationSnapshot(context) != null } }
                assertTrue(withContext(Dispatchers.IO) { detached.get() })
            } }
            val sample = sendTrace.snapshot(); assertTrue(sample.complete); assertTrue(sample.returned)
            val cipher = sample.events.filter { it.kind.startsWith("decrypt_") }
            assertEquals(3, decryptCount.get() - before); assertEquals(3, cipher.size)
            assertEquals(2, cipher.count { it.kind == "decrypt_access" })
            assertEquals(1, cipher.count { it.kind == "decrypt_refresh" })
            assertEquals(1, cipher.count { it.legacyAttached })
            assertEquals(1, legacy.count("decrypt_access")); assertEquals(0, legacy.count("decrypt_refresh"))
            assertEquals(2, sample.events.count { it.kind == "datastore_first" })
            assertEquals(0, sendTrace.retainedRoleCount())
        } finally { executor.shutdownNow() }
    }

    @Test(timeout = 60_000) fun wrappedDataStoreKeepsContinuousFlowAndFirstTerminationIsNotFailure() = runBlocking<Unit> {
        val key = stringPreferencesKey("g0_diagnostic_probe")
        dataStore.edit { it[key] = "before" }
        sendTrace.capture {
            coroutineScope {
                val first = CompletableDeferred<Unit>()
                val values = async {
                    dataStore.data.onEach { first.complete(Unit) }.take(2).toList()
                }
                first.await(); dataStore.edit { it[key] = "after" }
                assertEquals(listOf("before", "after"), values.await().map { it[key] })
            }
        }
        val sample = sendTrace.snapshot(); assertTrue(sample.complete)
        assertEquals(1, sample.events.count { it.kind == "datastore_first" })
        assertTrue(sample.events.single().ok); assertEquals(0, sendTrace.retainedRoleCount())
    }

    @Test(timeout = 60_000) fun failedDecryptAndCancellationAreNotHiddenByTheObserver() = runBlocking<Unit> {
        register(); val context = access(); val before = pageNativeState()
        failDecrypt = true
        try { sendTrace.capture { assertNull(tokens.syncAuthenticationSnapshot(context)) } }
        finally { failDecrypt = false }
        val failed = sendTrace.snapshot(); assertTrue(failed.complete)
        assertEquals(1, failed.events.count { it.kind == "decrypt_access" && !it.ok })
        assertEquals(before, pageNativeState()); assertEquals(0, sendTrace.retainedRoleCount())
        val expected = CancellationException("G0_SEND_DIAGNOSTIC_CANCEL")
        val actual = runCatching { sendTrace.capture<Unit> { throw expected } }.exceptionOrNull()
        assertSame(expected, actual); assertFalse(sendTrace.snapshot().returned)
        assertTrue(sendTrace.snapshot().complete); assertEquals(0, sendTrace.retainedRoleCount())
    }

    @Test(timeout = 60_000) fun boundedOverflowInvalidatesMeasurementWithoutSkippingCipherCalls() = runBlocking<Unit> {
        val trace = G0SendWindowTrace(maxEvents = 2); var calls = 0
        // A deliberately tiny observer tests delegation, not authentication strength.
        val delegate = object : TokenCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String): String { calls++; return value }
        }
        val cipher = trace.wrap(delegate)
        trace.capture { repeat(3) { assertEquals("synthetic", cipher.decrypt("synthetic")) } }
        assertEquals(3, calls); assertEquals(2, trace.snapshot().events.size)
        assertEquals(1, trace.snapshot().dropped); assertFalse(trace.snapshot().complete)
        assertEquals(0, trace.retainedRoleCount())
    }

    @Test(timeout = 180_000) fun realSendCaptureCompletesBothHttpBodiesAndPreservesAckReplay() = runBlocking<Unit> {
        seedAccepted(1); val store = openCount(); val saved = store.increase()
        val (http, _) = channel { successReply(it) }; val before = decryptCount.get()
        val legacy = G0PhaseTrace(driverTiming = false)
        val delivery = legacy.run { sendTrace.capture { requireNotNull(store.send(http, saved.requestId)) } }
        val sample = sendTrace.snapshot(); assertTrue(sample.complete)
        assertEquals(decryptCount.get() - before, sample.events.count { it.kind.startsWith("decrypt_") })
        assertEquals(0, sample.events.count { it.kind == "decrypt_unclassified" })
        assertEquals(1, sample.events.count { it.kind == "http_identity" && it.ok })
        assertEquals(1, sample.events.count { it.kind == "http_push" && it.ok })
        assertEquals(0, sample.events.count { it.kind == "http_other" })
        assertTrue(sample.events.filter { it.kind.startsWith("http_") }.all { it.end <= sample.ended })
        assertEquals(3, legacy.count("count_account_hold")); assertEquals(3, legacy.count("count_account_wait"))
        assertEquals(1, legacy.count("count_send_session_ready"))
        assertEquals(1, legacy.count("count_send_prepare_commit"))
        assertEquals(1, legacy.count("count_send_push")); assertEquals(1, legacy.count("count_send_after_response"))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        val result = view(store, saved.factId); val state = pageNativeState()
        reopenReference(); sameAsOriginal(result)
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(state, pageNativeState()); assertEquals(0, pageRowCount("sync_outbox"))
    }

    @Test(timeout = 180_000) fun sendFinalAuthenticationStillRollsBackWithWindowClosed() = runBlocking<Unit> {
        seedAccepted(1); val store = openCount(); val saved = store.increase()
        val (http, _) = channel { successReply(it) }; val before = pageNativeState()
        store.beforeFinalForTest = { failDecrypt = true }
        try {
            assertTrue(runCatching { sendTrace.capture { store.send(http, saved.requestId) } }.isFailure)
            assertTrue(sendTrace.snapshot().complete); assertFalse(sendTrace.snapshot().returned)
            assertEquals(1, sendTrace.snapshot().events.count { it.kind == "http_identity" })
            assertEquals(0, sendTrace.snapshot().events.count { it.kind == "http_push" })
            assertEquals(before, pageNativeState()); assertEquals(1, pageRowCount("sync_outbox"))
        } finally { failDecrypt = false }
    }
}
