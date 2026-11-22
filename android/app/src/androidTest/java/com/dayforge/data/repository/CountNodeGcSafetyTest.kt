package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.CountTrustIndex
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.ReplicaBusinessReferenceSchema
import com.dayforge.data.local.ReplicaSqlSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountNodeGcSafetyTest : CountNodeGcFixture() {
    @Test fun currentRootKeepsEveryDirectoryAndValueAndOnlyUnreachableNodesDisappear() = runBlocking<Unit> {
        ready(); orphan(1); orphan(2, -100); orphan(3, Long.MAX_VALUE - 1)
        val retained = retainedCommandResult()
        val live = reachable(); val before = nativeAssertions().capture()
        val reports = finish()
        assertTrue(reports.sumOf { it.deletedNodes } >= 3)
        assertOnlyUnreachableRemoved(before, live)
        assertEquals(retained, sql.query("SELECT resultJson FROM main.count_command_results").use { assertTrue(it.moveToFirst()); it.getString(0) })
    }

    @Test fun absentCountProviderAllowsOnlyItsKnownPoolAndNeverDropsDurableCommandResults() = runBlocking<Unit> {
        register(); orphan(5, -7); orphan(6, 7)
        retainedCommandResult()
        // The result was produced by the actual primary T0/T1. Explicitly remove only the
        // optional COUNT root so this original test still exercises the absent-provider case.
        db.withTransaction { owner.maintenance(db) { sql.execSQL("DELETE FROM count_trust_root") } }
        val before = nativeAssertions().capture(); assertTrue(before.getValue("count_trust_root").isEmpty())
        finish(); assertOnlyUnreachableRemoved(before, emptySet())
    }

    @Test fun dirtyAuthenticatedOldRootIsPreservedWithoutBecomingBusinessQualified() = runBlocking<Unit> {
        ready(); orphan(7)
        db.withTransaction { db.metricDao().update(metric.copy(description = "unrelated legitimate invalidation")) }
        val live = reachable(); val before = nativeAssertions().capture()
        assertEquals(1L, before.getValue("count_trust_control").single().getValue("dirty").value)
        finish(); assertOnlyUnreachableRemoved(before, live)
        db.withTransaction {
            assertNull(CountTrustKernel(db, tokens, sessions).open(requireNotNull(tokens.localCoreWriteAccess())))
        }
        assertEquals(1L, nativeAssertions().capture().getValue("count_trust_control").single().getValue("dirty").value)
    }

    @Test fun badMacIsNotACollectableCacheMiss() = runBlocking<Unit> {
        ready(); orphan(8)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_root SET mac=zeroblob(32) WHERE id=1")
        } }
        val before = nativeAssertions().capture()
        assertEquals(CountTrustNodeCollector.Status.FAILED, nextSlice(collector()).status)
        nativeAssertions().assertUnchanged(before)
    }

    @Test fun malformedUnreachableBodyIsNotSilentlyRepairedByDeletion() = runBlocking<Unit> {
        register(); val unused = orphan(8)
        db.withTransaction { owner.maintenance(db) {
            sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf<Any>("wrong digest".toByteArray(), unused))
        } }
        val before = nativeAssertions().capture(); val gc = collector()
        var failed = false
        for (attempt in 0 until 10000) {
            val report = nextSlice(gc)
            assertEquals(0, report.deletedNodes)
            if (report.status == CountTrustNodeCollector.Status.FAILED) { failed = true; break }
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
        }
        assertTrue(failed); nativeAssertions().assertUnchanged(before)
    }

    @Test fun corruptLiveDirectoryAbandonsCompleteMarkBeforeAnyGarbageCanBeSwept() = runBlocking<Unit> {
        ready(); orphan(80)
        db.withTransaction { owner.maintenance(db) {
            val root = requireNotNull(CountTrustSeal.read(sql))
            sql.execSQL("UPDATE count_trust_nodes SET body=? WHERE digest=?", arrayOf<Any>("bad live node".toByteArray(), root.root.digest))
        } }
        val before = nativeAssertions().capture(); val gc = collector(); var failed = false
        for (attempt in 0 until 10000) {
            val report = nextSlice(gc); assertEquals(0, report.deletedNodes)
            if (report.status == CountTrustNodeCollector.Status.FAILED) { failed = true; break }
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
        }
        assertTrue(failed); nativeAssertions().assertUnchanged(before)
    }

    @Test fun anEmptyNodePoolWithAuthenticatedRootIsNeverAnEmptyLiveRootSet() = runBlocking<Unit> {
        ready()
        db.withTransaction { owner.maintenance(db) { sql.execSQL("DELETE FROM count_trust_nodes") } }
        val before = nativeAssertions().capture(); assertFalse(before.getValue("count_trust_root").isEmpty())
        assertTrue(before.getValue("count_trust_nodes").isEmpty())
        val gc = collector(); var failed = false
        for (step in 0 until 100) {
            val report = nextSlice(gc); assertEquals(0, report.deletedNodes)
            if (report.status == CountTrustNodeCollector.Status.FAILED) { failed = true; break }
            assertEquals(CountTrustNodeCollector.Status.PROGRESS, report.status)
        }
        assertTrue(failed); nativeAssertions().assertUnchanged(before)
    }

    @Test fun completeMarkCannotDeleteCandidateReferencedByAChangedRoot() = runBlocking<Unit> {
        ready()
        val value = JsonPrimitive("retained after mark")
        val digest = orphan(9, bytes = value.toString().toByteArray())
        val gc = collector(); untilSweep(gc)
        rootValue("gc_fixture_new_reference", value)
        val live = reachable(); assertTrue(digest in live)
        val before = nativeAssertions().capture()
        assertEquals(CountTrustNodeCollector.Status.RESTARTED, nextSlice(gc).status)
        nativeAssertions().assertUnchanged(before)
        finish(gc); assertOnlyUnreachableRemoved(before, live)
    }

    @Test fun unknownOtherRootProviderAndFutureLayoutDeferInsteadOfAssumingWholePoolEmpty() = runBlocking<Unit> {
        register(); val digest = orphan(10)
        val originalVersion = sql.version
        assertEquals(com.dayforge.data.local.CountCommandV20Schema.VERSION, originalVersion)
        val originalOwner = owner; val originalGeneration = owner.generation
        // Fixed external layout corruption in the already-open native connection. Normal
        // controlled DDL retires this owner, while cold20 must reject its old primary MAC
        // binding before the COUNT-only provider classifier can run.
        spy.raw("CREATE TABLE extra_count_root(digest TEXT NOT NULL)")
        spy.raw("INSERT INTO extra_count_root VALUES(?)", arrayOf<Any>(digest))
        val active = "EXISTS(SELECT 1 FROM next_replica_fence WHERE id IS NOT 1 OR mode IS NOT 'closed')"
        for (action in listOf("INSERT", "UPDATE", "DELETE")) spy.raw(
            "CREATE TRIGGER replica_fence_extra_count_root_${action.lowercase(java.util.Locale.ROOT)} BEFORE $action ON extra_count_root " +
                "WHEN $active BEGIN SELECT RAISE(ABORT,'SYNC_REPLICA_SWITCH_FENCED'); END")
        assertSame(originalOwner, owner); assertEquals(originalGeneration, owner.generation); assertFalse(owner.isRetired)
        sql.query("SELECT digest FROM main.extra_count_root").use {
            assertTrue(it.moveToFirst()); assertEquals(digest, it.getString(0)); assertFalse(it.moveToNext())
        }
        val before = nativeAssertions().capture()
        val whole = com.dayforge.data.local.CountCommandMigrationFixture.snapshot(sql).contents
        val report = nextSlice(collector())
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, report.status)
        assertEquals("COUNT_GC_UNKNOWN_ROOT_PROVIDER", report.reason)
        nativeAssertions().assertUnchanged(before)
        assertEquals(whole, com.dayforge.data.local.CountCommandMigrationFixture.snapshot(sql).contents)
        spy.raw("PRAGMA user_version=${originalVersion + 1}")
        try {
            assertEquals(originalVersion + 1, sql.version)
            val futureWhole = com.dayforge.data.local.CountCommandMigrationFixture.snapshot(sql).contents
            val future = nextSlice(collector())
            assertEquals(CountTrustNodeCollector.Status.DEFERRED, future.status)
            assertEquals("COUNT_GC_UNSUPPORTED_LAYOUT", future.reason)
            nativeAssertions().assertUnchanged(before)
            assertEquals(futureWhole, com.dayforge.data.local.CountCommandMigrationFixture.snapshot(sql).contents)
        } finally { spy.raw("PRAGMA user_version=$originalVersion") }
        assertEquals(originalVersion, sql.version)
        nativeAssertions().assertUnchanged(before)
        assertEquals(whole, com.dayforge.data.local.CountCommandMigrationFixture.snapshot(sql).contents)

        // Independent cold admission keeps the stronger mandatory20 rejection. Do not
        // re-sign the primary Root just to reach the older collector-specific diagnostic.
        val closed = storage.snapshotClosedDatabase()
        val failure = rejected { db.openHelper.writableDatabase }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it is IllegalArgumentException && it.message == "COMMAND_PRIMARY_BINDING_CHANGED"
        })
        db.close()
        val file = app.getDatabasePath("habit_database")
        val wal = java.io.File(file.path + "-wal")
        assertTrue(!wal.exists() || wal.length() == 0L)
        assertArrayEquals("Cold rejected open must preserve the complete closed main image", closed, file.readBytes())
    }
    @Test fun frozenCandidateCannotBeSubstitutedOrDisappearBeforeItsExactDelete() = runBlocking<Unit> {
        ready()
        val original = "frozen-unreachable".toByteArray(); val digest = orphan(500, -2000, original)
        for (missing in listOf(true, false)) {
            val gc = collector(); untilSweep(gc)
            if (missing) spy.raw("DELETE FROM count_trust_nodes WHERE rowid=-2000")
            else {
                val other = "substituted".toByteArray()
                spy.raw("UPDATE count_trust_nodes SET digest=?,body=? WHERE rowid=-2000",
                    arrayOf<Any>(CountTrustIndex.hash(other), other))
            }
            val before = nativeAssertions().capture()
            val result = nextSlice(gc)
            assertEquals(CountTrustNodeCollector.Status.FAILED, result.status)
            assertEquals("COUNT_GC_CANDIDATE_CHANGED", result.reason); assertEquals(0, result.deletedNodes)
            nativeAssertions().assertUnchanged(before)
            db.withTransaction { owner.maintenance(db) {
                if (!missing) sql.execSQL("DELETE FROM count_trust_nodes WHERE rowid=-2000")
                sql.execSQL("INSERT INTO count_trust_nodes(rowid,digest,body) VALUES(-2000,?,?)", arrayOf<Any>(digest, original))
            } }
        }
        finish()
    }

    private class CascadeOracleRollback : RuntimeException()

    @Test fun optionalMetadataWithIncomingCascadeCannotTurnNodeGcIntoAnotherTableWrite() = runBlocking<Unit> {
        register(); val digest = orphan(601)
        val expected = ReplicaBusinessReferenceSchema.read(app)
        assertNotNull(expected.objects["table.android_metadata"])
        assertNull(db.withTransaction { CountTrustSeal.read(sql) })
        val outsideFk = sql.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst()); assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, it.getType(0))
            it.getInt(0).also { value -> assertTrue(value in 0..1) }
        }
        android.util.Log.i("CountNodeGcFk", "phase=connection outside_transaction_fk=$outsideFk")
        // Native fixture corruption keeps the original optional name but introduces an
        // incoming FK action. This is not a new production schema or SQL capability.
        spy.raw("ALTER TABLE android_metadata ADD COLUMN gcRef TEXT REFERENCES count_trust_nodes(digest) ON DELETE CASCADE")
        spy.raw("INSERT INTO android_metadata(locale,gcRef) VALUES('gc-side-effect',?)", arrayOf<Any>(digest))
        val actual = db.withTransaction { ReplicaSqlSchema.read(sql) }
        val optional = setOf("table.sqlite_stat1", "table.sqlite_stat4", "table.android_metadata")
        assertEquals(expected.objects.keys - optional, actual.keys - optional)
        assertTrue("The preserved r2 predicate skipped this present altered definition",
            actual.filterKeys { it !in optional }.all { (key, value) -> expected.objects[key] == value })
        assertNotEquals(expected.objects["table.android_metadata"], actual["table.android_metadata"])
        val before = nativeAssertions().capture()
        val rows = sql.query("SELECT count(*) FROM main.android_metadata").use { assertTrue(it.moveToFirst()); it.getLong(0) }
        val oracle = rejected { db.withTransaction<Unit> {
            assertTrue(db.inTransaction()); assertTrue(sql.inTransaction())
            val writerFk = sql.query("PRAGMA foreign_keys").use {
                assertTrue(it.moveToFirst()); assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, it.getType(0)); it.getInt(0)
            }
            android.util.Log.i("CountNodeGcFk", "phase=connection oracle_writer_fk=$writerFk actual_write_transaction=true")
            assertEquals("The actual writer connection must already enforce foreign keys", 1, writerFk)
            spy.raw("DELETE FROM count_trust_nodes WHERE digest=?", arrayOf<Any>(digest))
            sql.query("SELECT count(*) FROM main.android_metadata WHERE gcRef=?", arrayOf<Any>(digest)).use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
            }
            sql.query("SELECT count(*) FROM main.android_metadata").use {
                assertTrue(it.moveToFirst()); assertEquals(rows - 1, it.getLong(0))
            }
            throw CascadeOracleRollback()
        } }
        assertTrue("The real native cascade must execute then roll back", oracle is CascadeOracleRollback)
        nativeAssertions().assertUnchanged(before)
        spy.resetCounts()
        val result = nextSlice(collector())
        assertEquals(CountTrustNodeCollector.Status.DEFERRED, result.status)
        assertEquals("COUNT_GC_UNSUPPORTED_SCHEMA", result.reason)
        assertEquals(0, result.deletedNodes); assertEquals(0, spy.deletes)
        nativeAssertions().assertUnchanged(before)
    }

}
