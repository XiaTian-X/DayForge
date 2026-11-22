package com.dayforge.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real existing ANALYZE/cold-open contract, plus bounded strict-metadata counterexamples.
 * Metadata-only forged rows below test the same pure comparison used for raw archives;
 * they are not reported as an executed corrupt-file migration or a business grant.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandStatisticsSchemaTest : CountCommandMigrationFixture() {
    private fun rows(db: HabitDatabase): List<List<String?>> = db.openHelper.writableDatabase.query(
        "SELECT type,name,tbl_name,sql FROM main.sqlite_master ORDER BY type,name").use { c ->
        buildList { while (c.moveToNext()) add((0..3).map { if (c.isNull(it)) null else c.getString(it) }) }
    }
    private fun root(db: HabitDatabase) = CountCommandNativeRows(db.openHelper.writableDatabase) {}.root()
    private fun samePrimary(before: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity,
        after: com.dayforge.data.local.entity.CountCommandRecoveryRootEntity) {
        val omittedMac = ByteArray(0)
        assertEquals(before.copy(mac = omittedMac), after.copy(mac = omittedMac))
        assertArrayEquals(before.mac, after.mac)
    }

    @Test(timeout = 150_000) fun legitimateAnalyzeAfterInitializationKeepsPrimaryMacAcrossColdReopen() = runBlocking<Unit> {
        source(); val opened = openCurrent(); val before = root(opened)
        val original = CountCommandPrimarySchema.hash(rows(opened))
        opened.openHelper.writableDatabase.execSQL("ANALYZE")
        assertTrue(rows(opened).any { it[0] == "table" && it[1] == "sqlite_stat1" })
        assertEquals(original, CountCommandPrimarySchema.hash(rows(opened)))
        val current = openCurrent(); samePrimary(before, root(current))
        val sourceHash = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().hash }
        NextReplicaSchemaValidator(context, current, tokens, sessions).validate(requireNotNull(tokens.localSyncAccess()), sourceHash)
        samePrimary(before, root(current))
        CountCommandStore(current, tokens, sessions).bindEmptyNamespace()
        assertEquals("ACCOUNT_BOUND", root(current).mode)
    }

    @Test(timeout = 150_000) fun removingOnlyApprovedStatisticsKeepsPrimaryMacAcrossColdReopen() = runBlocking<Unit> {
        source { it.execSQL("ANALYZE") }; val opened = openCurrent(); val before = root(opened)
        val statistics = rows(opened).filter { it[0] == "table" && it[1] in ApprovedBusinessSchema.optionalStatistics }
        assertTrue(statistics.isNotEmpty())
        val unchanged = snapshot(opened.openHelper.writableDatabase)
        for (row in statistics) {
            val denied = runCatching { opened.openHelper.writableDatabase.execSQL("DROP TABLE `${row[1]}`") }.exceptionOrNull()
            assertEquals("COUNT_TRUST_SCHEMA_DDL_DENIED", denied?.message)
        }
        assertEquals(unchanged.contents, snapshot(opened.openHelper.writableDatabase).contents)
        // Source-shape fixture only. Production keeps its original reserved-DDL rejection;
        // one closed, isolated testbed file constructs the allowed optional-table absence.
        opened.close(); HabitDatabaseProvider.clearInstanceForTesting()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use { native ->
            for (row in statistics) {
                require(row[1] in setOf("sqlite_stat1", "sqlite_stat4"))
                native.execSQL("DROP TABLE `${row[1]}`")
            }
        }
        val current = openCurrent(); samePrimary(before, root(current))
        assertFalse(rows(current).any { it[1] in ApprovedBusinessSchema.optionalStatistics })
        CountCommandStore(current, tokens, sessions).bindEmptyNamespace()
    }

    @Test(timeout = 150_000) fun presentStatisticsDefinitionTypeOwnerAndBudgetAreNeverSkipped() {
        source { it.execSQL("ANALYZE") }; val current = openCurrent(); val original = rows(current); val before = root(current)
        val statistic = original.single { it[0] == "table" && it[1] == "sqlite_stat1" }
        val mutations = listOf(
            statistic.toMutableList().also { it[3] = "CREATE TABLE sqlite_stat1(tbl,idx,stat,unexpected)" },
            statistic.toMutableList().also { it[0] = "trigger" },
            statistic.toMutableList().also { it[2] = "habits" },
            statistic.toMutableList().also { it[3] = "CREATE TABLE sqlite_stat1(" + "x".repeat(262_145) + ")" }
        )
        for (changed in mutations) assertNotNull(runCatching {
            CountCommandPrimarySchema.hash(original.map { if (it === statistic) changed else it })
        }.exceptionOrNull())
        val disguised = listOf("index", "ordinary_name", "sqlite_stat1", "CREATE INDEX ordinary_name ON sqlite_stat1(tbl)")
        assertNotNull(runCatching { CountCommandPrimarySchema.hash(original + listOf(disguised)) }.exceptionOrNull())
        assertNotNull(runCatching { CountCommandPrimarySchema.hash(original + listOf(statistic)) }.exceptionOrNull())
        samePrimary(before, root(current)); assertEquals(original, rows(current))
    }

    @Test(timeout = 150_000) fun unknownSqliteNamedObjectsRemainBoundInsteadOfBroadlyFiltered() {
        source { it.execSQL("ANALYZE") }; val current = openCurrent(); val original = rows(current)
        val before = CountCommandPrimarySchema.hash(original)
        val unknown = listOf("table", "sqlite_unapproved", "sqlite_unapproved", "CREATE TABLE sqlite_unapproved(value TEXT)")
        assertNotEquals(before, CountCommandPrimarySchema.hash(original + listOf(unknown)))
        val android = original.singleOrNull { it[1] == "android_metadata" }
        if (android != null) assertNotEquals(before, CountCommandPrimarySchema.hash(original - listOf(android)))
        assertEquals(before, CountCommandPrimarySchema.hash(original))
    }

    @Test(timeout = 150_000) fun legalStatisticsDoNotMakeBadMandatoryMacIntoAnInitializationMiss() {
        source(); val current = openCurrent(); current.openHelper.writableDatabase.execSQL("ANALYZE")
        current.close(); HabitDatabaseProvider.clearInstanceForTesting()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
        }
        assertNotNull(runCatching { openCurrent() }.exceptionOrNull()); room?.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT mac,revision FROM count_command_recovery_roots", null).use { c ->
                assertTrue(c.moveToFirst()); assertArrayEquals(ByteArray(32), c.getBlob(0)); assertEquals(1L, c.getLong(1)); assertFalse(c.moveToNext())
            }
        }
    }

    @Test(timeout = 150_000) fun trackedAnalyzeRequiresOldCountAuthenticationWithoutResigningItsProof() = runBlocking<Unit> {
        val access = access(); var retained: CountTrustSeal.Header? = null
        source { retained = oldCountRoot(it, access, dirty = false) }
        val first = openCurrent(); first.openHelper.writableDatabase.execSQL("ANALYZE")
        val current = openCurrent(); val before = root(current); val sql = current.openHelper.writableDatabase
        val control = sql.query("SELECT dirty,generation FROM count_trust_control WHERE id=1").use { c ->
            assertTrue(c.moveToFirst()); c.getLong(0) to c.getLong(1)
        }
        assertEquals(1L, control.first); assertTrue(control.second > requireNotNull(retained).generation)
        current.withTransaction {
            assertFalse(CountCommandSchemaBridge.pending(current, access))
            CountCommandSchemaBridge.advance(current, access)
            assertNull(CountTrustKernel(current, tokens, sessions).open(access))
        }
        assertEquals(retained, CountTrustSeal.read(sql)); samePrimary(before, root(current))
        // No business qualification is fabricated for this retained directory-only fixture.
        CountCommandStore(current, tokens, sessions).bindEmptyNamespace()
        assertEquals(retained, CountTrustSeal.read(sql))
    }

    @Test(timeout = 150_000) fun untrackedCleanOldStatisticsMismatchIsStillRejected() = runBlocking<Unit> {
        val access = access()
        source { oldCountRoot(it, access, dirty = false); it.execSQL("ANALYZE") }
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        assertTrue(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull() is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }

    @Test(timeout = 150_000) fun trackedStatisticsCannotHideBadOldCountMac() = runBlocking<Unit> {
        val access = access()
        source { oldCountRoot(it, access, dirty = true); it.execSQL("UPDATE count_trust_root SET mac=zeroblob(32) WHERE id=1") }
        val first = openCurrent(); first.openHelper.writableDatabase.execSQL("ANALYZE")
        val current = openCurrent(); val before = current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents }
        assertTrue(runCatching { CountCommandStore(current, tokens, sessions).bindEmptyNamespace() }.exceptionOrNull() is CountCommandSchemaBridge.Invalid)
        assertEquals(before, current.withTransaction { NextReplicaStorageProof(current).readInTransaction().contents })
    }
}
