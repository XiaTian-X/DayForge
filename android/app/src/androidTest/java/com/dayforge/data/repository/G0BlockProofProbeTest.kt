package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Storage floor only. No fake Compose/frame, prompt, timer or ACK claims. */
@RunWith(AndroidJUnit4::class)
class G0BlockProofProbeTest : NextCoreRequestFixture() {
    private val alias = "dayforge.test.g0.${UUID.randomUUID()}"
    private val decrypts = AtomicLong()
    private val decryptNs = AtomicLong()
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? {
                val started = SystemClock.elapsedRealtimeNanos()
                return try { actual.decrypt(value) } finally {
                    decrypts.incrementAndGet()
                    decryptNs.addAndGet(SystemClock.elapsedRealtimeNanos() - started)
                }
            }
        }
    }
    @After fun removeKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private suspend fun probe(size: Int = 128): G0BlockProofProbe {
        register()
        return G0BlockProofProbe(db, tokens, sessions).also { it.initialize(size) }
    }
    private suspend fun benchmark(size: Int) {
        val started = SystemClock.elapsedRealtimeNanos()
        val probe = probe(size)
        Log.i("G0Probe", "kind=seed size=$size ns=${SystemClock.elapsedRealtimeNanos() - started}")
        // Two numbered rounds, 50 samples per kind per round. Unmixed storage operations;
        // this measures necessary costs, not the combined product acceptance threshold.
        repeat(2) { round ->
            repeat(50) { sample ->
                for (kind in listOf("read", "verify", "write")) {
                    val auth = decrypts.get(); val authNs = decryptNs.get()
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = when (kind) {
                        "read" -> probe.read()
                        "verify" -> probe.verifyBlock()
                        else -> probe.append("g0-$round-$sample")
                    }
                    val elapsed = SystemClock.elapsedRealtimeNanos() - start
                    val expected = size + round * 50L + sample + if (kind == "write") 1 else 0
                    assertEquals(expected, result.count); assertEquals(expected, result.sum)
                    val work = probe.work
                    Log.i("G0Probe", "kind=$kind size=$size round=$round sample=$sample ns=$elapsed " +
                        "decrypts=${decrypts.get() - auth} decrypt_ns=${decryptNs.get() - authNs} " +
                        "rows=${work.rows} nodes=${work.nodes} hash_bytes=${work.hashBytes}")
                    if (kind == "read") { assertEquals(0L, work.rows); assertEquals(0L, work.hashBytes) }
                    if (kind == "verify") assertTrue(work.rows <= 128)
                    if (kind == "write") assertTrue(work.rows <= 384)
                }
            }
            val auth = decrypts.get(); val authNs = decryptNs.get()
            val fullStart = SystemClock.elapsedRealtimeNanos()
            val full = probe.fullAudit()
            Log.i("G0Probe", "kind=full_audit size=$size round=$round ns=${SystemClock.elapsedRealtimeNanos() - fullStart} " +
                "decrypts=${decrypts.get() - auth} decrypt_ns=${decryptNs.get() - authNs} " +
                "rows=${probe.work.rows} nodes=${probe.work.nodes} hash_bytes=${probe.work.hashBytes}")
            assertEquals(size + (round + 1) * 50L, full.count)
        }
    }
    @Test(timeout = 300_000) fun storageFloorAtOneThousandFacts() = runBlocking { benchmark(1024) }
    @Test(timeout = 300_000) fun storageFloorAtTenThousandFacts() = runBlocking { benchmark(10240) }

    @Test fun committedCommandRetryDoesNotDuplicateFactOrOutbox() = runBlocking {
        val probe = probe()
        val first = probe.append("same-command")
        assertEquals(first, probe.append("same-command"))
        assertEquals(129L, first.count)
        db.withTransaction {
            for (table in listOf("g0_outbox", "g0_results")) {
                db.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                }
            }
        }
        assertEquals(first, probe.fullAudit())
    }
    @Test fun confirmedRollbackDoesNotPromoteRootAndSameCommandCanRetry() = runBlocking {
        val probe = probe()
        val before = probe.read()
        assertEquals("G0_INJECTED_FAILURE", rejected { probe.append("retry", failAfterFacts = true) }.message)
        assertEquals(before, probe.read())
        db.withTransaction {
            db.openHelper.writableDatabase.query("SELECT count(*) FROM g0_outbox").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
        assertEquals(129L, probe.append("retry").count)
        assertEquals(129L, probe.fullAudit().count)
    }
    @Test fun externalCommitInvalidatesEvenAnUnrelatedWarmRead() = runBlocking {
        val probe = probe()
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use {
            it.execSQL("UPDATE g0_nodes SET digest=? WHERE level=0 AND position=0", arrayOf("0".repeat(64)))
        }
        assertEquals("G0_EXTERNAL_CHANGE", rejected { probe.read() }.message)
    }
    @Test fun accountChangeCannotUseAnOldRoot() = runBlocking {
        val probe = probe()
        tokens.saveLoginSession("other-synthetic", "other-refresh", "member", id(999), false)
        assertEquals("G0_AUTHORITY_CHANGED", rejected { probe.read() }.message)
        assertEquals("G0_AUTHORITY_CHANGED", rejected { probe.append("wrong-account") }.message)
    }
    @Test fun corruptedTouchedSiblingCannotAuthorizeAppend() = runBlocking {
        val probe = probe(256)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE g0_nodes SET digest=? WHERE level=1 AND position=0", arrayOf("0".repeat(64)))
        }
        assertEquals("G0_UNCONSUMED_CHANGE", rejected { probe.append("bad-proof") }.message)
    }
    @Test fun droppedGuardInvalidatesBeforeRead() = runBlocking {
        val probe = probe()
        db.withTransaction { db.openHelper.writableDatabase.execSQL("DROP TRIGGER g0_no_update") }
        assertEquals("G0_SCHEMA_CHANGED", rejected { probe.read() }.message)
    }

    @Test fun unrelatedSameConnectionInsertCannotBeSkippedByNewWatermark() = runBlocking {
        val probe = probe()
        db.withTransaction { db.openHelper.writableDatabase.execSQL("INSERT INTO g0_facts VALUES(10000,?,1)", arrayOf(id(1))) }
        assertEquals("G0_UNCONSUMED_CHANGE", rejected { probe.read() }.message)
        assertEquals("G0_UNCONSUMED_CHANGE", rejected { probe.append("must-not-skip") }.message)
    }
    @Test fun lateOldOutboxDeletionRollsBackFactTreeAndResult() = runBlocking {
        val probe = probe()
        val first = probe.append("first")
        assertEquals("G0_UNEXPECTED_WRITE", rejected {
            probe.append("second", beforeFinal = {
                db.openHelper.writableDatabase.execSQL("DELETE FROM g0_outbox WHERE command='first'")
            })
        }.message)
        assertEquals(first, probe.read())
        assertEquals(first, probe.fullAudit())
        assertEquals(130L, probe.append("second").count)
    }
    @Test fun tempTriggerCannotDeleteAnOldOutboxRowDuringTheNextWrite() = runBlocking {
        val probe = probe()
        probe.append("first")
        db.withTransaction { db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER g0_late AFTER INSERT ON main.g0_results BEGIN DELETE FROM g0_outbox WHERE command='first'; END") }
        assertEquals("G0_TEMP_SCHEMA_CHANGED", rejected { probe.append("second") }.message)
    }
    @Test fun readOnlyRegistrationCannotAppend() = runBlocking {
        register(permissions = setOf("sync.read"))
        val probe = G0BlockProofProbe(db, tokens, sessions).also { it.initialize(128) }
        assertEquals("G0_WRITE_DENIED", rejected { probe.append("denied") }.message)
        assertEquals(128L, probe.read().count)
    }
}
