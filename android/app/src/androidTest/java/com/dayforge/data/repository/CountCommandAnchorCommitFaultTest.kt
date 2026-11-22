package com.dayforge.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed native faults below the production controller, never an arbitrary SQL callback.
 * One gate hits real Store Root success -> anchor failure; the other changes Root after
 * native setSuccessful and requires the actual native onCommit footer to reject it. */
@RunWith(AndroidJUnit4::class)
class CountCommandAnchorCommitFaultTest : NextObjectEditorFixture() {
    private val fault = NativeFault()
    init { storage.isolatedBuilder = { fault.build(it) } }
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun input(): CountCommandRecords.Intent {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        return CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
    }
    private suspend fun gate(mode: NativeFault.Mode, expected: String) {
        val input = input(); val before = image()
        val revision = db.withTransaction { CountCommandNativeRows(db.openHelper.writableDatabase) {}.root().revision }
        fault.arm(mode, revision)
        val failure: Throwable? = try { CountCommandStore(db, tokens, sessions).register(input); null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error }
        assertEquals(1, fault.hits); assertTrue(fault.rootAdvancedBeforeFault)
        assertTrue("Expected exact fixed refusal $expected", generateSequence(failure) { it.cause }.any { it.message == expected })
        assertFalse(db.openHelper.writableDatabase.inTransaction())
        storage.reopen()
        assertTrue("Actual rollback must retain all original main cells", before == image())
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_results"))
        assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
        val store = CountCommandStore(db, tokens, sessions)
        val retry = store.register(input)
        assertFalse(retry.alreadyRegistered); assertEquals(input.commandId, retry.reference.commandId)
        store.releaseUnadmitted(requireNotNull(retry.ticket))
        assertEquals(CountCommandRecords.Execution.PREPARED, store.resolve(retry.reference).historical.status.execution)
        assertEquals(1, count("count_command_intents")); assertEquals(0, count("completions")); assertEquals(0, count("sync_outbox"))
    }
    @Test(timeout = 150_000) fun actualStoreRootSuccessThenAnchorFailureRollsBackAndSameInputCanRetry() = runBlocking<Unit> {
        gate(NativeFault.Mode.BEFORE_ANCHOR, "FIXED_COMMAND_ANCHOR_UPDATE_FAILED")
    }
    @Test(timeout = 150_000) fun nativeSuccessMarkerCannotHideLateBadRootFromActualOnCommit() = runBlocking<Unit> {
        gate(NativeFault.Mode.AFTER_SUCCESS, "COMMAND_JOURNAL_EXTRA_OR_GAP")
    }

    private class NativeFault : SupportSQLiteOpenHelper.Factory {
        enum class Mode { BEFORE_ANCHOR, AFTER_SUCCESS }
        @Volatile private var armed: Mode? = null
        private var beforeRevision = -1L
        @Volatile var hits = 0; private set
        @Volatile var rootAdvancedBeforeFault = false; private set
        private var rawIdentity: SupportSQLiteDatabase? = null
        private var wrapped: SupportSQLiteDatabase? = null
        fun arm(mode: Mode, revision: Long) { check(armed == null && hits == 0); beforeRevision = revision; armed = mode }
        fun build(context: Context) = buildDiagnosticFaultDatabase(context, this)
        private fun advanced(raw: SupportSQLiteDatabase): Boolean = raw.inTransaction() && raw.query(
            "SELECT revision FROM count_command_recovery_roots WHERE id=1 LIMIT 2").use {
            it.moveToFirst() && it.getLong(0) > beforeRevision && !it.moveToNext()
        }
        private fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase {
            if (rawIdentity !== raw) {
                rawIdentity = raw
                wrapped = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val anchor = sql.trim().replace("`", "").lowercase(java.util.Locale.ROOT)
                            .startsWith("update count_trust_control set commandroothash=")
                        return object : SupportSQLiteStatement by statement {
                            private fun before() {
                                if (anchor && armed == Mode.BEFORE_ANCHOR) {
                                    check(advanced(raw)); rootAdvancedBeforeFault = true; armed = null; hits++
                                    error("FIXED_COMMAND_ANCHOR_UPDATE_FAILED")
                                }
                            }
                            override fun execute() { before(); statement.execute() }
                            override fun executeInsert(): Long { before(); return statement.executeInsert() }
                            override fun executeUpdateDelete(): Int { before(); return statement.executeUpdateDelete() }
                        }
                    }
                    override fun setTransactionSuccessful() {
                        raw.setTransactionSuccessful()
                        if (armed == Mode.AFTER_SUCCESS && advanced(raw)) {
                            rootAdvancedBeforeFault = true; armed = null
                            raw.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
                            raw.query("SELECT changes()").use { check(it.moveToFirst() && it.getLong(0) == 1L && !it.moveToNext()) }
                            hits++
                        }
                    }
                }
            }
            return requireNotNull(wrapped)
        }
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val callback = configuration.callback
            val wrappedCallback = object : SupportSQLiteOpenHelper.Callback(callback.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = callback.onCorruption(wrap(db))
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(wrappedCallback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by helper {
                override val readableDatabase get() = wrap(helper.readableDatabase)
                override val writableDatabase get() = wrap(helper.writableDatabase)
            }
        }
    }
}
