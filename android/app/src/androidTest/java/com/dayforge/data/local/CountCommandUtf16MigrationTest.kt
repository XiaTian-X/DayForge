package com.dayforge.data.local

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountCommandUtf16MigrationTest : CountCommandMigrationFixture() {
    @Test(timeout = 150_000) fun utf16OldRootAndUnicodeRowsSurviveAdditiveMigrationAndExactBridge() = runBlocking<Unit> {
        val access = access(); var original: CountTrustSeal.Header? = null
        val before = source(utf16 = true) { sql ->
            sql.execSQL("UPDATE next_rejections SET resultJson='家庭/Ω/emoji😀' WHERE requestId='old-request'")
            original = oldCountRoot(sql, access, dirty = true)
        }
        val current = openCurrent(); val sql = current.openHelper.writableDatabase
        sql.query("PRAGMA encoding").use { assertTrue(it.moveToFirst()); assertEquals("UTF-16le", it.getString(0)); assertFalse(it.moveToNext()) }
        val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        CountCommandStore(current, tokens, sessions).bindEmptyNamespace()
        val transferred = requireNotNull(CountTrustSeal.read(sql)); val old = requireNotNull(original)
        assertEquals(old.root, transferred.root); assertEquals(old.binding, transferred.binding)
        assertEquals(old.generation, transferred.generation); assertEquals(old.revision + 1, transferred.revision)
        assertTrue(CountTrustSeal().matches(transferred, old.binding, schemaHash(sql)))
        sql.query("SELECT resultJson FROM next_rejections WHERE requestId='old-request'").use {
            assertTrue(it.moveToFirst()); assertEquals("家庭/Ω/emoji😀", it.getString(0)); assertFalse(it.moveToNext())
        }
    }
    @Test(timeout = 150_000) fun utf16LegacyOutcomeFingerprintAndRawOldViewRemainExactAcrossReopen() = runBlocking<Unit> {
        val before = source(utf16 = true) { it.execSQL("INSERT INTO count_command_results VALUES('legacy','输入','事实',NULL,'家庭/Ω/😀','committed')") }
        val current = openCurrent(); val after = current.withTransaction { NextReplicaStorageProof(current).readInTransaction() }
        assertEquals(before.contents, requireNotNull(NextReplicaV19StorageView.project(after)).contents)
        assertEquals("LEGACY_UNENROLLED", CountCommandNativeRows(current.openHelper.writableDatabase) {}.root().mode)
        val reopened = openCurrent()
        CountCommandStorageInitialization.verifyMandatory(reopened.openHelper.writableDatabase)
        assertEquals(after.contents, reopened.withTransaction { NextReplicaStorageProof(reopened).readInTransaction().contents })
    }
}
