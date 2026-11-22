package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.local.AndroidKeystoreTokenCipher
import com.dayforge.data.local.TokenCipher
import java.security.KeyStore
import java.util.UUID
import org.junit.After

/** Separate real-domain database owner. Reference schema is built from the locked Room and
 * callback implementations in a fresh, private file, never learned from the candidate database.
 */
abstract class G0SealedDomainFixture : NextCoreRequestFixture() {
    internal val authority = G0AuthorityOwner()
    override fun isolatedDataStore(data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) = G0PhaseTrace.wrap(authority.wrap(data))
    internal val endFaults = G0CommitFaultFactory { !it.inTransaction() }
    internal var domainGate = G0DomainSqlGate(G0TimingFactory(endFaults))
        private set
    internal fun freshGateForColdOpen(compactJournal: Boolean = true, recurringReads: Boolean = false,
        countWrites: Boolean = false, householdCountWrites: Boolean = false,
        coldLifecycle: G0RoomColdOwnerFactory? = null, countCertificateScope:Boolean = false,
        countBlockScope:Boolean = false,countRootScope:Boolean = false,countRootWriteScope:Boolean = false) {
        check(!db.inTransaction())
        domainGate = G0DomainSqlGate(coldLifecycle ?: G0TimingFactory(endFaults),compactJournal,
            recurringReads,countWrites,householdCountWrites,coldLifecycle,countCertificateScope,countBlockScope,countRootScope,countRootWriteScope)
    }
    internal open fun domainOpeningCallback(): androidx.room.RoomDatabase.Callback = SyncSchemaCallback
    private val alias = "dayforge.test.g0.sealed.${UUID.randomUUID()}"
    init {
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build()
        }
    }
    internal var failDecrypt = false
    internal val decryptCount = java.util.concurrent.atomic.AtomicInteger()
    override fun tokenCipher(): TokenCipher {
        val real = AndroidKeystoreTokenCipher(alias)
        return object : TokenCipher {
            override fun encrypt(value: String) = real.encrypt(value)
            override fun decrypt(value: String): String? {
                decryptCount.incrementAndGet()
                val trace = G0PhaseTrace.current.get()
                return if (trace == null) { if (failDecrypt) null else real.decrypt(value) }
                    else trace.call("decrypt_${trace.role(value)}") { if (failDecrypt) null else real.decrypt(value) }
            }
        }
    }
    @After fun removeSealedKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    internal suspend fun trustedDomainSchema(batchedOpening: Boolean = false, inMemory: Boolean = true): List<List<String?>> {
        val trace=G0PhaseTrace.current.get()
        fun <T> timed(name:String,body:()->T):T = if(trace==null) body() else trace.call("reference_$name",body)
        val name = "g0-trusted-schema-${UUID.randomUUID()}"
        val reference = timed("builder") {
            (if(inMemory) Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java)
            else Room.databaseBuilder(app,HabitDatabase::class.java,name))
                .addCallback(if (batchedOpening) G0SchemaOpeningCallback(true) else SyncSchemaCallback).build()
        }
        return try {
            timed("open") { reference.openHelper.writableDatabase }
            reference.withTransaction { timed("snapshot") { G0DomainJournal.trustedSchema(reference.openHelper.writableDatabase) } }
        } finally {
            timed("close") { reference.close() }
            if(!inMemory) timed("delete") { check(app.deleteDatabase(name)) }
        }
    }
    internal suspend fun sealDomain() {
        val expected = trustedDomainSchema()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA recursive_triggers=ON")
        db.withTransaction { domainGate.installJournal(expected) }
        domainGate.seal()
    }
}
