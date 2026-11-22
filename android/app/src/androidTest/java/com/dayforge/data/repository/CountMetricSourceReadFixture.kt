package com.dayforge.data.repository

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.dayforge.data.local.TokenCipher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import org.junit.Assert.*

/** Reuses the actual formal COUNT/two-metric fixture, production native gate and Keystore.
 * Only fixed query categories and decrypt attempt counts are observed; no new timing probe.
 */
abstract class CountMetricSourceReadFixture : CountTrustFormalProfileFixture() {
    internal val sourceSpy = SourceWindowNativeSpy()
    internal override fun sourceWindowNativeSpy(): SourceWindowNativeSpy = sourceSpy
    internal val decryptCalls = AtomicInteger()
    internal val accessDecryptCalls = AtomicInteger()
    internal val refreshDecryptCalls = AtomicInteger()
    internal val unknownDecryptCalls = AtomicInteger()
    internal val failAccessAt = AtomicInteger()
    @Volatile private var observedAccessCiphertext: String? = null
    @Volatile private var observedRefreshCiphertext: String? = null

    /** Test observation only. Capture distinct current ciphertext purposes before measurement;
     * production guards still read/decrypt each current snapshot independently.
     */
    internal suspend fun resetCredentialCounts() {
        val values = dataStore.data.first()
        observedAccessCiphertext = requireNotNull(values[stringPreferencesKey("access_token")])
        observedRefreshCiphertext = requireNotNull(values[stringPreferencesKey("refresh_token")])
        check(!observedAccessCiphertext.isNullOrEmpty() && !observedRefreshCiphertext.isNullOrEmpty() &&
            observedAccessCiphertext != observedRefreshCiphertext)
        decryptCalls.set(0); accessDecryptCalls.set(0); refreshDecryptCalls.set(0); unknownDecryptCalls.set(0)
    }
    internal fun assertCredentialCounts(access: Int, refresh: Int) {
        assertEquals("Every original access proof still decrypts", access, accessDecryptCalls.get())
        assertEquals("Only credential consumers may decrypt refresh", refresh, refreshDecryptCalls.get())
        assertEquals("Every attempted ciphertext must have an explicit purpose", 0, unknownDecryptCalls.get())
        assertEquals(access + refresh, decryptCalls.get())
    }
    override fun tokenCipher(): TokenCipher {
        val original = super.tokenCipher()
        return object : TokenCipher {
            override fun encrypt(value: String) = original.encrypt(value)
            override fun decrypt(value: String): String? {
                decryptCalls.incrementAndGet()
                val accessAttempt = if (observedAccessCiphertext == null) 0 else when (value) {
                    observedAccessCiphertext -> accessDecryptCalls.incrementAndGet()
                    observedRefreshCiphertext -> { refreshDecryptCalls.incrementAndGet(); 0 }
                    else -> { unknownDecryptCalls.incrementAndGet(); 0 }
                }
                val actual = original.decrypt(value) // Always execute the same real Keystore work.
                return if (accessAttempt != 0 && accessAttempt == failAccessAt.get()) null else actual
            }
        }
    }
    internal fun widgets() = WidgetFactReader(db, tokens, sessions, preferences,
        CountHistoryReader(db, tokens, sessions), CheckHistoryReader(db, tokens, sessions))
    internal fun nativeImage() = CountConsumerReadFixtureAssertions(db, tokens, sessions)
    internal suspend fun schema() = db.withTransaction {
        listOf("main", "temp").associateWith { name ->
            db.openHelper.writableDatabase.query("SELECT type,name,tbl_name,sql FROM $name.sqlite_master ORDER BY type,name").use { raw ->
                buildList { while (raw.moveToNext()) add((0..3).map { if (raw.isNull(it)) null else raw.getString(it) }) }
            }
        }
    }
    internal data class ReadObservation(val view: WidgetFactSnapshot, val schemas: Int, val decrypts: Int, val accessDecrypts: Int, val refreshDecrypts: Int, val unknownDecrypts: Int)
    internal suspend fun readSource(inputs: MetricScopeInputs, reuse: Boolean): ReadObservation {
        val writer = producer(); val scope = writer.captureRounds()
        resetCredentialCounts(); sourceSpy.begin()
        val view: WidgetFactSnapshot
        val sample: SourceWindowNativeSpy.Snapshot
        try {
            view = if (reuse) writer.writeRoundsWithMetricSource(scope) {
                CountTrustKernel.withMetricSourceRead(db) { handle ->
                    assertNotNull("Warm real producer must issue its private pre-insert proof", handle)
                    widgets().requireCompletionInTransaction(inputs.claim, metricSourceRead = handle)
                }
            } else writer.writeRounds(scope) { widgets().requireCompletionInTransaction(inputs.claim) }
        } finally { sample = sourceSpy.end() }
        assertFalse(sample.overflow)
        return ReadObservation(view, sample.count(SourceWindowNativeSpy.Kind.SCHEMA_SNAPSHOT), decryptCalls.get(),
            accessDecryptCalls.get(), refreshDecryptCalls.get(), unknownDecryptCalls.get())
    }
    internal suspend fun insertKnown(inputs: MetricScopeInputs): List<Long> {
        val ticket = requireNotNull(CountTrustKernel.beginMetricBatch(db, inputs.logs, inputs.metrics,
            inputs.currentHabit, inputs.claim))
        val ids = db.metricLogDao().insertAll(inputs.logs)
        CountTrustKernel.bindMetricBatch(db, ticket, ids)
        return ids
    }
    /** Actual production API generates UUID/time. Expected business fields come from the
     * frozen caller inputs, never reconstructed from the journal or the stored row.
     */
    internal suspend fun assertActualRows(inputs: MetricScopeInputs, drafts: List<MetricValueDraft>, ids: List<Long>,
        recordedAt: Long, earliest: Long, latest: Long, capturedZone: String) {
        assertEquals(2, ids.size); assertEquals(2, ids.distinct().size)
        val rows = ids.map { requireNotNull(db.metricLogDao().getById(it)) }
        assertEquals(2, rows.map { it.uuid }.toSet().size)
        for ((row, draft) in rows.zip(drafts)) {
            val metric = inputs.metrics.getValue(draft.metricId)
            assertEquals(draft.metricId, row.metricId); assertEquals(recordedAt, row.date)
            assertEquals(draft.value, row.value, 0.0); assertEquals(draft.note, row.note)
            assertEquals(metric.unit, row.unit); assertEquals(capturedZone, row.recordedTimezone)
            assertEquals(Instant.ofEpochMilli(recordedAt).atZone(ZoneId.of(capturedZone)).toLocalDate().toString(), row.recordedLocalDate)
            assertEquals("captured", row.timeMetadataSource)
            assertTrue(com.dayforge.domain.model.isContractUuid(row.uuid))
            assertTrue(row.createdAt in earliest..latest); assertTrue(row.updatedAt in earliest..latest)
        }
        val sources = db.syncOutboxDao().getAll().filter { it.recordType == "metric_log" }
        assertEquals(rows.map { it.uuid }.toSet(), sources.map { it.entityUuid }.toSet())
        sources.forEach { source ->
            val origin = originalIntent(source)
            assertEquals(source.id, origin.queueId)
            val formal = requireNotNull(roundOperationIntent(origin.intentJson))
            assertEquals("metric_observation", formal.operation.entityType); assertNull(formal.context.head)
            assertEquals("Original mapper must reconstruct the same complete stored operation", producer().operation(source), formal.operation)
            assertNull(db.nextStructuralCausalDao().dependency(source.operationId))
        }
        val header = db.withTransaction { requireNotNull(CountTrustSeal.read(db.openHelper.writableDatabase)) }
        assertEquals(inputs.header.revision + 1, header.revision)
        assertEquals(inputs.header.generation + 6, header.generation)
        // The unchanged original COUNT proof remains independent of the trusted source result.
        val beforeOracle = nativeImage().capture()
        sessions.exclusive { db.withTransaction {
            val original = RecurringCompletionEvidenceReader(db, tokens, sessions).readInTransaction(
                inputs.currentHabit, LocalDate.parse(inputs.claim.date))
            assertEquals(inputs.claim.completionUuid, original.completions.single().uuid)
            assertEquals(1, original.completions.single().value)
        } }
        nativeImage().assertUnchanged(beforeOracle)
    }
}
