package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance gates, not expected-failure demonstrations. A failure prevents expanding G0. */
@RunWith(AndroidJUnit4::class)
class G0TrustBoundaryAcceptanceTest : G0GuardedFixture() {
    private suspend fun probe() = guardedProbe()

    @Test fun temporarilyDisabledCaptureMustNotMakeWarmReadWeakerThanFullAudit() = runBlocking<Unit> {
        val probe = probe()
        // All SQL uses the actual Room handle; no file edits, reflection, root or other process.
        // Restore the exact trigger definitions and schema cookie, leaving only old-row damage.
        val before = probe.read()
        val attack = runCatching { sqlGate.transaction(db, G0SqlGate.Phase.READ) {
            val sql = db.openHelper.writableDatabase
            val version = sql.query("PRAGMA main.schema_version").use { check(it.moveToFirst()); it.getLong(0) }
            val definitions = sql.query("SELECT name,sql FROM sqlite_master WHERE name IN ('g0_no_update','g0_capture_facts_UPDATE') ORDER BY name").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
            }
            assertEquals(2, definitions.size)
            definitions.forEach { (name, _) -> sql.execSQL("DROP TRIGGER $name") }
            sql.execSQL("UPDATE g0_facts SET owner=? WHERE seq=1", arrayOf(id(999)))
            definitions.forEach { (_, definition) -> sql.execSQL(definition) }
            sql.execSQL("PRAGMA main.schema_version=$version")
        } }.exceptionOrNull()
        if (attack != null) {
            assertTrue("Only explicit adapter rejection counts as prevention", attack is G0SqlGate.Denied)
            assertEquals(before, probe.fullAudit())
            assertEquals(before, probe.read())
            assertEquals(before.count + 1, probe.append("after-blocked-attack").count)
            return@runBlocking
        }
        assertTrue("The complete raw-row audit must detect the foreign-owner fact",
            runCatching { probe.fullAudit() }.exceptionOrNull() is IllegalStateException)
        val fastFailure = runCatching { probe.read() }.exceptionOrNull()
        assertTrue("G0_UNSAFE: restored schema cookie hid same-connection old-row damage from warm read",
            fastFailure is IllegalStateException)
    }

    @Test fun replaceWithRecursiveTriggersOffStillInvalidatesTheCertificate() = runBlocking<Unit> {
        val probe = probe()
        val before = probe.read()
        val attack = runCatching { db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("PRAGMA recursive_triggers=OFF")
            sql.execSQL("INSERT OR REPLACE INTO g0_facts VALUES(1,?,1)", arrayOf(id(999)))
        } }.exceptionOrNull()
        if (attack != null) {
            assertTrue(attack is G0SqlGate.Denied)
            assertEquals(before, probe.fullAudit()); assertEquals(before, probe.read())
            return@runBlocking
        }
        assertEquals("G0_UNCONSUMED_CHANGE", rejected { probe.read() }.message)
    }

    @Test fun malformedPersistedResultCannotMakeRetrySucceed() = runBlocking<Unit> {
        val probe = probe()
        probe.append("first")
        val before = probe.read()
        val attack = runCatching { db.withTransaction { db.openHelper.writableDatabase.execSQL("UPDATE g0_results SET seq=129.5 WHERE command='first'") } }.exceptionOrNull()
        if (attack != null) {
            assertTrue(attack is G0SqlGate.Denied)
            assertEquals(before, probe.fullAudit()); assertEquals(before, probe.append("first"))
            return@runBlocking
        }
        assertEquals("G0_UNCONSUMED_CHANGE", rejected { probe.append("first") }.message)
    }
}
