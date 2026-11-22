package com.dayforge.data.repository

import androidx.room.Room
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import kotlinx.coroutines.asContextElement

/** Real original domain tables/validators. Deliberately NOT behind the model-only SQL gate. */
abstract class G0DomainFixture : NextCoreRequestFixture() {
    internal val authority = G0AuthorityOwner()
    private val commitOwner = ThreadLocal<Boolean>()
    internal var faultTargetAck: String? = null
    internal val faults = G0CommitFaultFactory { raw ->
        commitOwner.get() == true && !raw.inTransaction() && faultTargetAck?.let { id ->
            // Fault injection is BELOW the boundary. Select the real outer ACK COMMIT,
            // not Room TEMP maintenance or a DAO's nested transaction end.
            raw.query("SELECT count(*) FROM next_acceptances WHERE kind=? AND requestId=?", arrayOf<Any>(NEXT_OPERATION, id))
                .use { it.moveToFirst() && it.getLong(0) == 1L }
        } == true
    }
    internal val scheduler = G0CommitScheduler()
    internal val decrypts = AtomicLong()
    internal val decryptNs = AtomicLong()
    internal val writes = CopyOnWriteArrayList<String>()
    internal var captureWriteTrace = true
    internal var unreadableAccess = false
    internal var shadowClockKey: Pair<String, String>? = null
    internal val shadowClockValues = CopyOnWriteArrayList<Long>()
    private val alias = "dayforge.test.g0.domain.${UUID.randomUUID()}"
    init {
        storage.isolatedBuilder = { context ->
            check(context.packageName == "com.dayforge.testbed")
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(SyncSchemaCallback).openHelperFactory(G0TimingFactory(faults))
                .setQueryCallback(object : androidx.room.RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        if (!captureWriteTrace) return
                        val sql = sqlQuery.trim()
                        if (Regex("^(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) &&
                            !sql.contains("room_table_modification_log", ignoreCase = true)) {
                            val clockWrite = shadowClockKey?.let { key -> sql == SHADOW_INSERT && bindArgs.size == 7 &&
                                bindArgs[0] == key.first && bindArgs[1] == key.second } == true
                            val bindings = bindArgs.mapIndexed { index, value ->
                                if (clockWrite && index == 6) {
                                    shadowClockValues.add(value as Long); "GENERATED_SHADOW_CLOCK"
                                } else if (value is ByteArray) value.toList().toString() else "${value?.javaClass?.name}:$value"
                            }
                            writes.add(digest(sql + bindings.toString()))
                        }
                    }
                }, java.util.concurrent.Executor { it.run() }).build()
        }
    }
    override fun isolatedDataStore(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) = G0PhaseTrace.wrap(authority.wrap(data))
    override fun tokenCipher(): TokenCipher {
        val cipher = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = cipher.encrypt(value)
            override fun decrypt(value: String): String? {
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                return try {
                    val trace = G0PhaseTrace.current.get()
                    if (trace == null) cipher.decrypt(value) else trace.call("decrypt_${trace.role(value)}") {
                        if (unreadableAccess && trace.role(value) == "access") null else cipher.decrypt(value)
                    }
                } finally {
                    decrypts.incrementAndGet(); decryptNs.addAndGet(android.os.SystemClock.elapsedRealtimeNanos() - start)
                }
            }
        }
    }
    internal fun bridge(splitAck: Boolean? = null) = G0ScheduledDomainProbe(db, tokens, sessions, authority, scheduler, commitOwner.asContextElement(true), splitAck)
    internal fun blockRuntime(blocks: G0MetricBlocks, queue: G0CommitScheduler = scheduler) = G0MetricBlockRuntime(db, tokens, sessions, authority, queue, commitOwner.asContextElement(true), blocks)
    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    @After fun removeDomainKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    private companion object {
        const val SHADOW_INSERT = "INSERT OR REPLACE INTO `sync_entity_state` (`entityType`,`entityUuid`,`revision`,`deleted`,`payloadJson`,`payloadHash`,`updatedAt`) VALUES (?,?,?,?,?,?,?)"
    }
}
