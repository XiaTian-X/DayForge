package com.dayforge.data.repository

import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import com.dayforge.domain.model.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Representative batches using real Keystore, not a capacity or whole-button latency claim. */
@RunWith(AndroidJUnit4::class)
class SubmissionBatchReadTest : NextObjectEditorFixture() {
    private val alias = "dayforge.test.submission.${UUID.randomUUID()}"
    private val decryptions = AtomicInteger()
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? {
                decryptions.incrementAndGet()
                return actual.decrypt(value)
            }
        }
    }
    @After fun deleteTestKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    @Test fun metricPromptBatchMatchesScalarTicketsAndReauthenticatesAtSubmission() = runBlocking<Unit> {
        register()
        val ids = db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            val result = listOf(metric.id) + (1..3).map { n -> db.metricDao().insert(
                metric.copy(id = 0, uuid = id(200 + n), name = "Batch $n")) }
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            result
        }
        val repo = editingMetrics()
        val before = db.withTransaction { nextRestartDatabaseProof(db) }
        decryptions.set(0)
        val scalarStart = SystemClock.elapsedRealtime()
        val scalar = ids.map { repo.getMetricForEditing(it) }
        val scalarMs = SystemClock.elapsedRealtime() - scalarStart
        val scalarCalls = decryptions.get()
        decryptions.set(0)
        val batchStart = SystemClock.elapsedRealtime()
        val batch = repo.getMetricsForEditing(ids)
        val batchMs = SystemClock.elapsedRealtime() - batchStart
        val batchCalls = decryptions.get()
        assertEquals(scalar.map { it.value }, batch.map { it.value })
        scalar.zip(batch).forEach { (a, b) ->
            assertEquals(a.authority!!.session, b.authority!!.session)
            assertEquals(a.authority.original, b.authority.original)
            assertEquals(a.authority.rounds, b.authority.rounds)
        }
        assertTrue("Batch must not reauthenticate once per metric", batchCalls < scalarCalls)
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        android.util.Log.i("SubmissionBatchCost", "stage=metric_prepare items=4 scalar_ms=$scalarMs batch_ms=$batchMs scalar_decrypts=$scalarCalls batch_decrypts=$batchCalls")
        deleteTestKey()
        rejected { repo.recordValues(listOf(MetricValueDraft(ids.first(), 2.0)), authority = batch.first().authority,
            expectedMetrics = listOf(batch.first().value!!)) }
        assertEquals(before, db.withTransaction { nextRestartDatabaseProof(db) })
        assertEquals(0, count("metric_logs"))
    }

    @Test fun creationIconBatchSharesFreshMetadataAndDoesNotRetainAuthorityForAnotherOperation() = runBlocking<Unit> {
        register()
        val owner = local()
        val context = iconMetadata.capture()
        val assets = (1..4).map { n -> IconAsset(id(220 + n), "Asset $n", "general", "template",
            IconBlob(n.toString().repeat(64), 1, "image/svg+xml", 1, 1), null) }
        assets.forEach { iconMetadata.reserveAsset(context, it) }
        val references = assets.map { IconReference.Asset(it.assetId) to false }
        decryptions.set(0)
        val scalarStart = SystemClock.elapsedRealtime()
        references.forEach { (reference, once) -> icons.authorizeEditReference(owner, reference, IconReference.Role("habit.default"), once) }
        val scalarMs = SystemClock.elapsedRealtime() - scalarStart
        val scalarCalls = decryptions.get()
        decryptions.set(0)
        val batchStart = SystemClock.elapsedRealtime()
        icons.authorizeCreationReferences(owner, references)
        val batchMs = SystemClock.elapsedRealtime() - batchStart
        val batchCalls = decryptions.get()
        assertTrue("Batch must not reauthenticate once per asset", batchCalls < scalarCalls)
        android.util.Log.i("SubmissionBatchCost", "stage=creation_icons items=4 scalar_ms=$scalarMs batch_ms=$batchMs scalar_decrypts=$scalarCalls batch_decrypts=$batchCalls")
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(999), false)
        rejected { icons.authorizeCreationReferences(owner, references) }
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }
}
