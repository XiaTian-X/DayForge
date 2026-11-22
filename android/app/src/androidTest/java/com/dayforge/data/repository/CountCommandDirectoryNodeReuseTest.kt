package com.dayforge.data.repository

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.AuthenticatedHistoryDirectory
import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.HistoryBlockPlanner.OrderKey
import com.dayforge.data.history.HistoryBlockUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Actual native SQLite checks for the conditional directory, not an authenticated root issuer. */
@RunWith(AndroidJUnit4::class)
class CountCommandDirectoryNodeReuseTest {
    private val binding = "a".repeat(64)
    private val date = LocalDate.of(1970, 1, 1)
    private fun member(index: Int) = CountCommandRecords.CatalogMember(
        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
        "b".repeat(64), "c".repeat(64), null, "d".repeat(64), 1L, 1L, null)
    private fun replacement(value: CountCommandRecords.CatalogMember) = value.copy(statusRowHash = "e".repeat(64))
    private fun record(value: CountCommandRecords.CatalogMember) = HistoryBlockCodec.Record(
        OrderKey(date, value.commandId), value.commandId,
        HistoryBlockCodec.Bytes.copyOf(CountCommandRecords.canonical(value.body()).toByteArray(Charsets.UTF_8)))
    private fun rejected(block: () -> Unit): Throwable {
        try { block() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected refusal")
    }
    private fun assertCode(code: HistoryBlockCodec.Code, failure: Throwable) {
        assertTrue("Expected $code, got $failure", failure is HistoryBlockCodec.Rejected)
        assertEquals(code, (failure as HistoryBlockCodec.Rejected).code)
    }

    private inner class Fixture(val native: SupportSQLiteDatabase, val built: AuthenticatedHistoryDirectory.Built) {
        var nodeLoads = 0
        val sql = object : SupportSQLiteDatabase by native {
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
                if (query.contains("FROM count_command_nodes WHERE digest=?")) nodeLoads++
                return native.query(query, bindArgs)
            }
        }
        private val lifetime = { check(native.inTransaction()); Unit }
        val directory = CountCommandDirectory(sql, binding, built.root, lifetime, lifetime)
        fun originalPatch(old: CountCommandRecords.CatalogMember, fresh: CountCommandRecords.CatalogMember) =
            HistoryBlockUpdater.apply(built.root,
                listOf(HistoryBlockUpdater.Edit(record(old).key, record(old), record(fresh))),
                AuthenticatedHistoryDirectory.Loader { digest, _ -> built.blocks[digest]?.toByteArray() })
        fun replaceRootBytes(bytes: ByteArray) {
            native.execSQL("UPDATE count_command_nodes SET body=? WHERE digest=?", arrayOf(bytes, built.root.digest))
        }
    }

    private suspend fun withCatalog(members: List<CountCommandRecords.CatalogMember>, block: suspend (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE count_command_nodes(digest TEXT PRIMARY KEY NOT NULL,body BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("This isolated native fixture has no upgrade")
            }).build())
        try {
            val native = helper.writableDatabase
            val built = AuthenticatedHistoryDirectory().build(CountCommandDirectory.unit(binding), members.map(::record))
            for ((digest, bytes) in built.blocks) native.execSQL(
                "INSERT INTO count_command_nodes(digest,body) VALUES(?,?)", arrayOf(digest, bytes.toByteArray()))
            native.beginTransaction()
            try { block(Fixture(native, built)) } finally { native.endTransaction() }
        } finally { helper.close() }
    }

    @Test(timeout = 150_000) fun sameReadReusesCodecButStillLoadsNativeBytesForTheUpdaterAndEscapedReaderFails() = runBlocking<Unit> {
        val old = member(1); val fresh = replacement(old)
        withCatalog(listOf(old)) { fixture ->
            val expected = fixture.originalPatch(old, fresh)
            val trace = G0PhaseTrace(false)
            var escaped: CountCommandDirectory.Reader? = null
            val actual = trace.run { fixture.directory.read { reader ->
                escaped = reader
                assertEquals(old, reader.member(old.commandId))
                reader.replace(listOf(old to fresh))
            } }
            assertEquals(expected, actual)
            assertEquals(2, fixture.nodeLoads) // First member plus original update Loader, even on a cache hit.
            assertEquals(1, trace.count("count_block_node_decode"))
            assertCode(HistoryBlockCodec.Code.CONTEXT, rejected { requireNotNull(escaped).member(old.commandId) })
            assertCode(HistoryBlockCodec.Code.CONTEXT, rejected { requireNotNull(escaped).replace(listOf(old to fresh)) })
        }
    }

    @Test(timeout = 150_000) fun originalUpdateBudgetRefusalDoesNotPoisonTheReaderOrPreventASmallerValidUpdate() = runBlocking<Unit> {
        val values = List(HistoryBlockUpdater.Budget().maxEdits + 1, ::member)
        withCatalog(values) { fixture ->
            val old = values.first(); val fresh = replacement(old)
            val expected = fixture.originalPatch(old, fresh)
            fixture.directory.read { reader ->
                assertCode(HistoryBlockCodec.Code.BUDGET,
                    rejected { reader.replace(values.map { it to replacement(it) }) })
                assertEquals(old, reader.member(old.commandId))
                assertEquals(expected, reader.replace(listOf(old to fresh)))
            }
        }
    }

    @Test(timeout = 150_000) fun cachedMemberCannotHideChangedNativeBytesAndAnOriginalLoaderFailureRemainsCatchable() = runBlocking<Unit> {
        val old = member(1); val fresh = replacement(old)
        withCatalog(listOf(old)) { fixture ->
            val expected = fixture.originalPatch(old, fresh)
            fixture.directory.read { reader ->
                assertEquals(old, reader.member(old.commandId))
                fixture.replaceRootBytes(byteArrayOf(0))
                val failure = rejected { reader.replace(listOf(old to fresh)) }
                assertEquals("COMMAND_NODE_CHANGED", failure.message)
                fixture.replaceRootBytes(fixture.built.blocks.getValue(fixture.built.root.digest).toByteArray())
                assertEquals(old, reader.member(old.commandId))
                assertEquals(expected, reader.replace(listOf(old to fresh)))
            }
            assertEquals(3, fixture.nodeLoads) // Warm member, rejected original load, repaired original load.
        }
    }
}
