package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandStorageInitialization
import com.dayforge.data.local.entity.MetricLogEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndependentMetricCompatibilityTest : NextObjectEditorFixture() {
    private suspend fun legacy(nativeUuid: String = metric.uuid) {
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            metric = metric.copy(appearance = null, uuid = nativeUuid)
            db.metricDao().update(metric)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
    }
    private suspend fun capture() = editingMetrics().beginIndependentValue(metric.id, 1.5, "compat", millis,
        if (metric.appearance == null) null else editingMetrics().getMetricForEditing(metric.id).authority, metric.uuid)
    private fun decode(body: JsonObject): CountCommandRecords.Intent {
        val bytes = CountCommandRecords.canonical(body).toByteArray()
        return CountCommandRecords.Intent.decode(bytes, bytes.size) {}
    }

    @Test(timeout = 150_000) fun nativeLegacyObjectIdentityAndUnrelatedOldFactIdentityRetainOriginalLegalWrite() = runBlocking<Unit> {
        legacy("legacy metric native identity")
        db.metricLogDao().insert(MetricLogEntity(metricId = metric.id, date = millis - 1, value = 2.0,
            unit = metric.unit, uuid = "legacy fact native identity"))
        val old = editingMetrics().recordValue(metric.id, 1.5, "compat", millis, expectedMetricUuid = metric.uuid)
        val original = requireNotNull(db.metricLogDao().getById(old))
        val attempt = capture()
        assertEquals("legacy metric native identity", IndependentMetricInput.input(requireNotNull(attempt.intent))["metric_uuid"]!!.jsonPrimitive.content)
        val saved = editingMetrics().executeIndependentValue(attempt)
        val actual = requireNotNull(db.metricLogDao().getById(saved.factId))
        assertEquals(original.value, actual.value, 0.0); assertEquals(original.unit, actual.unit)
        assertEquals(original.metricId, actual.metricId); assertEquals(original.date, actual.date)
        assertNotEquals(original.uuid, actual.uuid); assertEquals(3, count("metric_logs"))
        val outcome = CountCommandStore(db, tokens, sessions).resolve(saved.reference!!.native()).historical.outcome!!
        assertEquals(metric.uuid, outcome.effects.single().objectUuid)
        assertTrue(outcome.effects.single().legacyNativeMetricIdentity)
        assertTrue(editingMetrics().resolveIndependentValue(saved.reference!!) is IndependentMetricResolution.Saved)
    }

    @Test(timeout = 150_000) fun newV2ActionCannotBeEncodedAsV1OrMasqueradeAsCountOrTypedNativeIdentity() = runBlocking<Unit> {
        register()
        val intent = requireNotNull(capture().intent)
        assertEquals(intent.text(), decode(intent.body()).text())
        assertNotNull(runCatching { decode(JsonObject(intent.body() + ("format" to JsonPrimitive(1)))) }.exceptionOrNull())
        assertNotNull(runCatching { decode(JsonObject(intent.body() + ("action" to JsonPrimitive("COUNT_ADD")))) }.exceptionOrNull())
        val input = IndependentMetricInput.input(intent)
        assertNotNull(runCatching { decode(JsonObject(intent.body() + ("input" to JsonObject(input +
            ("metric_uuid" to JsonPrimitive("native typed identity")))))) }.exceptionOrNull())
    }

    @Test(timeout = 150_000) fun independentTerminalMatrixRejectsParentLinksClosedAndUnprovedReasons() = runBlocking<Unit> {
        register()
        val intent = requireNotNull(capture().intent)
        val prepared = CountCommandTransitionRules.prepared(intent, 0)
        CountCommandTransitionRules.requireAggregate(intent, prepared, null)
        for ((execution, reason) in listOf(CountCommandRecords.Execution.CANCELLED to IndependentMetricInput.CANCELLED,
            CountCommandRecords.Execution.INVALIDATED to IndependentMetricInput.INVALIDATED)) {
            val result = prepared.copy(revision = 2, execution = execution,
                delivery = CountCommandRecords.Delivery.RESULT_PENDING, reason = reason)
            CountCommandTransitionRules.requireAggregate(intent, result, null)
            CountCommandTransitionRules.requireAggregate(intent, result.copy(delivery = CountCommandRecords.Delivery.DELIVERED), null)
            assertNotNull(runCatching { CountCommandTransitionRules.requireAggregate(intent,
                result.copy(reason = "UNPROVED"), null) }.exceptionOrNull())
            assertNotNull(runCatching { CountCommandTransitionRules.requireAggregate(intent,
                result.copy(delivery = CountCommandRecords.Delivery.CLOSED), null) }.exceptionOrNull())
        }
        assertNotNull(runCatching { CountCommandTransitionRules.requireAggregate(intent, prepared.copy(parent = id(701)), null) }.exceptionOrNull())
        assertNotNull(runCatching { CountCommandTransitionRules.requireAggregate(intent, prepared.copy(format = 1), null) }.exceptionOrNull())
    }

    @Test(timeout = 150_000) fun rootUpgradeOnlyAdmitsBoundV1ToV2AndNeverGeneralDowngradeOrEmptyShortcut() = runBlocking<Unit> {
        register()
        val empty = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase).header }
        assertEquals(CountCommandRootCodec.Mode.EMPTY, empty.mode)
        val emptyV2 = empty.copy(format = 2, validator = CountCommandRecords.METRIC_VALIDATOR, revision = empty.revision + 1)
        assertNotNull(runCatching { CountCommandRootCodec.requireMetricUpgrade(empty, emptyV2) }.exceptionOrNull())
        val store = CountCommandStore(db, tokens, sessions)
        store.prepareIndependentRoot()
        val bound = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase).header }
        assertEquals(CountCommandRootCodec.Mode.ACCOUNT_BOUND, bound.mode); assertEquals(2, bound.format)
        val v1 = bound.copy(format = 1, validator = CountCommandRecords.VALIDATOR, revision = bound.revision + 1)
        assertNotNull(runCatching { CountCommandRootCodec.requireNext(bound, v1) }.exceptionOrNull())
        assertNotNull(runCatching { CountCommandRootCodec.requireMetricUpgrade(bound, v1) }.exceptionOrNull())
        assertNotNull(runCatching { bound.copy(validator = CountCommandRecords.VALIDATOR) }.exceptionOrNull())
    }

    @Test(timeout = 150_000) fun v2RootMacStillRequiresExistingKeyTagAndNeverAuthenticatesAlteredEnvelope() = runBlocking<Unit> {
        register()
        CountCommandStore(db, tokens, sessions).prepareIndependentRoot()
        val root = db.withTransaction { CountCommandStorageInitialization.verifyRootAnchor(db.openHelper.writableDatabase) }
        val native = root.native()
        assertNotNull(runCatching { CountCommandSeal().authenticate(native.copy(mac = ByteArray(32)),
            native.storageId, native.schemaHash) { CountCommandRootAnchor.digest(native) } }.exceptionOrNull())
        assertEquals(root.header, CountCommandSeal().authenticate(native, native.storageId, native.schemaHash) {
            CountCommandRootAnchor.digest(native)
        }.header)
        assertEquals(0, count("metric_logs")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("count_command_intents"))
    }
}
