package com.dayforge.data.repository

import androidx.room.Room
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.After

abstract class G0GuardedFixture : NextCoreRequestFixture() {
    internal val commitFaults = G0CommitFaultFactory()
    internal val sqlGate = G0SqlGate(commitFaults)
    private val alias = "dayforge.test.g0.guarded.${UUID.randomUUID()}"
    internal val guardedQueries = AtomicLong()
    internal val guardedDecrypts = AtomicLong()
    internal val guardedDecryptNs = AtomicLong()
    init {
        storage.isolatedBuilder = { context ->
            check(context.packageName == "com.dayforge.testbed")
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(sqlGate.factory)
                .setQueryCallback(object : androidx.room.RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) { guardedQueries.incrementAndGet() }
                }, java.util.concurrent.Executor { it.run() }).build()
        }
    }
    override fun tokenCipher(): TokenCipher {
        val actual = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = actual.encrypt(value)
            override fun decrypt(value: String): String? {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                return try { actual.decrypt(value) } finally {
                    guardedDecrypts.incrementAndGet()
                    guardedDecryptNs.addAndGet(android.os.SystemClock.elapsedRealtimeNanos() - start)
                }
            }
        }
    }
    @After fun removeGuardedKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    internal suspend fun guardedProbe(size: Int = 128): G0BlockProofProbe {
        register()
        return G0BlockProofProbe(db, tokens, sessions, sqlGate).also { it.initialize(size) }
    }
}
