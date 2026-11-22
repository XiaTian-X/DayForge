package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.dayforge.data.local.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job

/** Fixed native faults below the real gate; never installed by production. */
internal class CountCommandNodeGcNativeSpy : SupportSQLiteOpenHelper.Factory {
    enum class EndFault { COMMITTED_THEN_THROW, ROLLED_BACK_THEN_THROW }
    var failDeleteAt: Int? = null
    var endFault: EndFault? = null
    var cancelDelete: Job? = null
    var cancelRead: Job? = null
    var cancelNodeRead: Job? = null
    var failNodeRead = false
    var deletes = 0; private set
    var faultTouched = false; private set
    var sameValueStatusAfterDelete = false
    var statusFaultRows = 0; private set
    private val deletedThisTransaction = ThreadLocal.withInitial { false }
    @Volatile var pauseBegin: Pair<CountDownLatch, CountDownLatch>? = null
    @Volatile var pauseBeginEnded: CountDownLatch? = null
    private val pauseMonitor = Any()
    private val heldBeginEnd = ThreadLocal<CountDownLatch?>()
    fun resetCounts() { deletes = 0; faultTouched = false; statusFaultRows = 0 }
    fun build(context: Context): HabitDatabase {
        val factory = CountTrustOpenHelperFactory(context.applicationContext, this)
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addMigrations(FactTimeMigration, OneTimePreparationMigration, CompletionFollowUpMigration, OneTimeTransmissionMigration,
                ObjectAppearanceMigration, PlanMetadataMigration, NextRecoveryMigration, NextRequestMigration, NextAcceptanceMigration,
                NextStructuralCausalMigration, NextSyncStateMigration, CountDayMigration, NextChallengeMigration, NextRestartMigration,
                NextConfigImportMigration, NextAbandonmentMigration, NextReplicaFenceMigration, CountTrustMigration, CountCommandMigration)
            .openHelperFactory(factory).addCallback(SyncSchemaCallback).build().also { CountTrustRuntime.attach(it, factory) }
    }
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        var original: SupportSQLiteDatabase? = null; var adapted: SupportSQLiteDatabase? = null
        fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (original !== raw) {
                original = raw
                adapted = object : SupportSQLiteDatabase by raw {
                    private fun observe(text: String) {
                        if (text == "SELECT body FROM main.count_command_nodes WHERE rowid=? AND digest=?") {
                            if (failNodeRead) { failNodeRead = false; faultTouched = true
                                throw android.database.sqlite.SQLiteDiskIOException("GC fixed node read IOERR") }
                            cancelNodeRead?.let { cancelNodeRead = null; faultTouched = true; it.cancel() }
                        }
                        if (text == "PRAGMA main.data_version") cancelRead?.let {
                            cancelRead = null; faultTouched = true; it.cancel()
                        }
                    }
                    override fun query(query: String): Cursor { observe(query); return raw.query(query) }
                    override fun query(query: String, bindArgs: Array<out Any?>): Cursor { observe(query); return raw.query(query, bindArgs) }
                    override fun query(query: SupportSQLiteQuery): Cursor { observe(query.sql); return raw.query(query) }
                    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
                        observe(query.sql); return raw.query(query, cancellationSignal)
                    }
                    private fun begin(body: () -> Unit) {
                        val pause = synchronized(pauseMonitor) {
                            pauseBegin?.let { pair ->
                                pauseBegin = null
                                (pair to pauseBeginEnded).also { pauseBeginEnded = null }
                            }
                        }
                        if (pause != null) {
                            heldBeginEnd.set(pause.second)
                            pause.first.first.countDown()
                            check(pause.first.second.await(10, TimeUnit.SECONDS))
                        }
                        deletedThisTransaction.set(false); body()
                    }
                    override fun beginTransaction() = begin { raw.beginTransaction() }
                    override fun beginTransactionNonExclusive() = begin { raw.beginTransactionNonExclusive() }
                    override fun beginTransactionReadOnly() = begin { raw.beginTransactionReadOnly() }
                    override fun beginTransactionWithListener(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListener(transactionListener) }
                    override fun beginTransactionWithListenerNonExclusive(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerNonExclusive(transactionListener) }
                    override fun beginTransactionWithListenerReadOnly(transactionListener: android.database.sqlite.SQLiteTransactionListener) =
                        begin { raw.beginTransactionWithListenerReadOnly(transactionListener) }
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        if (sql != "DELETE FROM main.count_command_nodes WHERE rowid=? AND digest=?") return statement
                        return object : SupportSQLiteStatement by statement {
                            override fun executeUpdateDelete(): Int {
                                deletes++
                                if (failDeleteAt == deletes) { faultTouched = true; throw android.database.sqlite.SQLiteFullException("GC fixed DELETE failure") }
                                val count = statement.executeUpdateDelete(); check(count == 1)
                                deletedThisTransaction.set(true)
                                if (sameValueStatusAfterDelete) {
                                    sameValueStatusAfterDelete = false
                                    raw.execSQL("UPDATE count_command_status SET stateJson=stateJson WHERE commandId=(SELECT min(commandId) FROM count_command_status)")
                                    raw.query("SELECT changes()").use { c -> check(c.moveToFirst()); statusFaultRows = c.getInt(0) }
                                    check(statusFaultRows == 1); faultTouched = true
                                }
                                cancelDelete?.let { cancelDelete = null; faultTouched = true; it.cancel() }
                                return count
                            }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        if (requireNotNull(deletedThisTransaction.get()) && endFault == EndFault.ROLLED_BACK_THEN_THROW) return
                        raw.setTransactionSuccessful()
                    }
                    override fun endTransaction() {
                        val fault = endFault.takeIf { requireNotNull(deletedThisTransaction.get()) }
                        raw.endTransaction()
                        heldBeginEnd.get()?.let { signal ->
                            if (!raw.inTransaction()) { heldBeginEnd.remove(); signal.countDown() }
                        }
                        if (fault != null) {
                            endFault = null; deletedThisTransaction.set(false); faultTouched = true
                            throw android.database.sqlite.SQLiteException("GC fixed unknown END: $fault")
                        }
                    }
                }
            }
            return requireNotNull(adapted)
        }
        val parent = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
            override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
            override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
        }
        val delegate = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
        return object : SupportSQLiteOpenHelper by delegate {
            override val writableDatabase get() = wrap(delegate.writableDatabase)
            override val readableDatabase get() = wrap(delegate.readableDatabase)
        }
    }
}
