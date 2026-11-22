package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.TokenCipher
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executor half of the opaque-v4 contract. Real Host/source consumers are tested by the
 * separate graph/UI class; a repository test is not a substitute for that publication gate. */
@RunWith(AndroidJUnit4::class)
class IndependentMetricOpaqueWriteTest : NextObjectEditorFixture() {
    private val fault = IndependentMetricNativeFault()
    private val deny = AtomicBoolean()
    private val denyAfterValue = AtomicBoolean()
    init { storage.isolatedBuilder = { fault.build(it) } }
    override fun tokenCipher() = object : TokenCipher {
        override fun encrypt(value: String) = value
        override fun decrypt(value: String): String? = if (deny.get() || denyAfterValue.get() && fault.executeEntries > 0) null else value
    }
    private suspend fun opaque() {
        db.clearAllData(); storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        tokens.saveLoginSession("opaque-access", "opaque-refresh", "member", "account-a", false)
        tokens.prepareSyncAccount("account-a")
        tokens.saveServerIdentity("server-a", "epoch-a")
        tokens.getOrCreateInstallationId()
        tokens.saveDeviceRegistration("device-a", caps, true, 1)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            metric = metric.copy(id = 0, appearance = null)
            metric = metric.copy(id = db.metricDao().insert(metric))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertNull(tokens.localCoreWriteAccess()); assertNotNull(tokens.localOpaqueMetricAccess())
    }
    private suspend fun capture() = editingMetrics().beginIndependentValue(metric.id, 8.0, "opaque", millis, expectedMetricUuid = metric.uuid)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }

    @Test(timeout = 150_000) fun opaqueOriginalLegalRepositoryAndExplicitLanePreserveCurrentUnitWithNoCanonicalCommand() = runBlocking<Unit> {
        opaque()
        val attempt = capture()
        assertNull(attempt.intent); assertNull(attempt.reference)
        editingMetrics().updateMetric(metric.copy(unit = "lb"))
        val oldId = editingMetrics().recordValue(metric.id, 8.0, "opaque", millis, expectedMetricUuid = metric.uuid)
        val original = requireNotNull(db.metricLogDao().getById(oldId))
        val saved = editingMetrics().executeIndependentValue(attempt)
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(original.unit, actual.unit); assertEquals("lb", saved.unit)
        assertEquals(original.value, actual.value, 0.0); assertEquals(original.date, actual.date)
        assertNotEquals(original.uuid, actual.uuid); assertNull(saved.reference)
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        assertEquals(2, count("metric_logs"))
    }

    @Test(timeout = 150_000) fun opaqueBadCredentialRejectsButOriginalLegacyNoAppendPolicyStillAllowsBothWriters() = runBlocking<Unit> {
        opaque()
        val before = image()
        deny.set(true)
        assertNotNull(runCatching { capture() }.exceptionOrNull())
        deny.set(false)
        assertEquals(before, image()); assertEquals(0, count("metric_logs"))
        tokens.saveDeviceRegistration("device-a", caps - "facts.append", true, 2)
        assertFalse(requireNotNull(tokens.localOpaqueMetricAccess()).facts.canAppend)
        // The full old repository is the permission oracle; canAppend alone never was it.
        val oldId = editingMetrics().recordValue(metric.id, 8.0, "opaque", millis, expectedMetricUuid = metric.uuid)
        val old = requireNotNull(db.metricLogDao().getById(oldId))
        val saved = editingMetrics().executeIndependentValue(capture())
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(old.metricId, actual.metricId); assertEquals(old.value, actual.value, 0.0)
        assertEquals(old.date, actual.date); assertEquals(old.unit, actual.unit); assertEquals(old.note, actual.note)
        assertNotEquals(old.uuid, actual.uuid); assertNull(saved.reference)
        assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun canonicalLegacyHasTheSameOriginalNoAppendPolicyWithoutUsingOpaqueFallback() = runBlocking<Unit> {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            metric = metric.copy(appearance = null); db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        register(permissions = caps - "facts.append")
        assertFalse(requireNotNull(tokens.localFactAccess()).canAppend)
        assertNull(tokens.localOpaqueMetricAccess())
        val oldId = editingMetrics().recordValue(metric.id, 8.0, "opaque", millis, expectedMetricUuid = metric.uuid)
        val old = requireNotNull(db.metricLogDao().getById(oldId))
        val attempt = capture()
        assertNotNull(attempt.intent); assertNotNull(attempt.reference); assertNull(attempt.opaque)
        val saved = editingMetrics().executeIndependentValue(attempt)
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(old.value, actual.value, 0.0); assertEquals(old.unit, actual.unit)
        assertEquals(old.date, actual.date); assertEquals(old.note, actual.note); assertNotEquals(old.uuid, actual.uuid)
        assertNotNull(saved.reference); assertEquals(2, count("metric_logs")); assertEquals(2, count("sync_outbox"))
        assertEquals(1, count("count_command_intents")); assertEquals(1, count("count_command_results"))
    }

    @Test(timeout = 150_000) fun opaqueCaptureDoesNotSurviveAnotherCurrentAccountOrEpoch() = runBlocking<Unit> {
        opaque()
        val attempt = capture(); val before = image()
        tokens.saveServerIdentity("server-a", "epoch-b")
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(before, image())
        val next = capture()
        tokens.saveLoginSession("opaque-other-access", "opaque-other-refresh", "member", "account-b", false)
        tokens.prepareSyncAccount("account-b")
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(next) }.exceptionOrNull())
        assertEquals(before, image()); assertEquals(0, count("metric_logs")); assertEquals(0, count("count_command_intents"))
    }

    @Test(timeout = 150_000) fun actualOpaqueValueInsertStillRequiresFinalFreshDecryptAndRollsBackWholeFactOutbox() = runBlocking<Unit> {
        opaque()
        val attempt = capture(); val before = image()
        denyAfterValue.set(true)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertTrue(fault.executeEntries > 0)
        denyAfterValue.set(false)
        assertEquals(before, image()); assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox"))
        assertTrue(attempt.started); assertNull(attempt.reference)
        assertNotNull(runCatching { editingMetrics().executeIndependentValue(attempt) }.exceptionOrNull())
        assertEquals(before, image()) // UI cannot blindly replay an opaque UNKNOWN attempt
    }
}
