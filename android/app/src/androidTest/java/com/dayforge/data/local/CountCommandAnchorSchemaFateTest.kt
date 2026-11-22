package com.dayforge.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Genuine old parsed schema hash is captured before the actual production19→20 edge.
 * Archive values are original contract-shaped sentinels, not archive-file validation.
 * These tests cover schema/fate interpretation, not restore authorization or process death. */
@RunWith(AndroidJUnit4::class)
class CountCommandAnchorSchemaFateTest : CountCommandMigrationFixture() {
    private class Rollback : RuntimeException()
    private fun upgrade(): Pair<HabitDatabase, String> {
        var oldHash: String? = null
        source(19) { sql -> oldHash = runBlocking { ReplicaProjection.schemaHash(19, ReplicaSqlSchema.read(sql)) } }
        return openCurrent() to requireNotNull(oldHash)
    }
    private suspend fun image(db: HabitDatabase) = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private fun unowned(): HabitDatabase {
        room?.close(); HabitDatabaseProvider.clearInstanceForTesting()
        return Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
            .addCallback(SyncSchemaCallback).build().also { room = it; assertNull(CountTrustRuntime.forDatabase(it)); it.openHelper.writableDatabase }
    }
    private suspend fun stage(db: HabitDatabase, schema: String): ReplicaSwitchStage {
        var value = tokens.replicaStageFixture()
        db.nextSyncStateDao().insert(NextSyncStateEntity(value.accountId, value.serverInstanceId, value.newEpoch,
            value.deviceId, 1, value.candidateCursor, value.candidateHash, value.candidateHash, challengeContract = 1))
        value = value.copy(sourceHash = NextReplicaSourceClosure.storage(image(db), null))
        val target = ReplicaStageTarget(ReplicaProjection.capture(db).hash, schema, value.deviceId,
            value.capabilityRevision, value.capabilities, true, false)
        tokens.persistReplicaStage(null, value)
        NextReplicaWriteFence(db).acquire(value.fence)
        value = tokens.persistReplicaStage(value, value.transition(ReplicaSwitchPhase.FROZEN))
        val archive = ReplicaStageArchive(id(930), "b".repeat(64), 3, "e".repeat(64),
            listOf("input.json", "business.json", "icons.json").map { ReplicaStageArchiveEntry(it, 1, "b".repeat(64)) })
        value = tokens.persistReplicaStage(value, value.transition(ReplicaSwitchPhase.ARCHIVE_VERIFIED, receipt = archive))
        return tokens.persistReplicaStage(value, value.transition(ReplicaSwitchPhase.COMMITTING, destination = target))
    }
    private suspend fun checkFate(db: HabitDatabase, expected: String) {
        assertNotEquals(expected, ReplicaProjection.schemaHash(db))
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, expected))
        val committing = stage(db, expected); val before = image(db)
        val store = NextReplicaCommitStore(db, tokens, sessions)
        assertEquals(ReplicaCommitFate.COMMITTED, store.fate(committing))
        val committed = tokens.persistReplicaStage(committing, committing.transition(ReplicaSwitchPhase.SQL_COMMITTED))
        assertEquals(ReplicaCommitFate.COMMITTED, store.fate(committed))
        assertEquals(before.contents, image(db).contents)
    }
    @Test(timeout = 150_000) fun controlledUpgradeMatchesReal19SchemaAndFrozenCommittedFate() = runBlocking<Unit> {
        val (db, oldHash) = upgrade(); assertNotNull(CountTrustRuntime.forDatabase(db)); checkFate(db, oldHash)
    }
    @Test(timeout = 150_000) fun unownedIndependentReadsStillMatchReal19SchemaAndFate() = runBlocking<Unit> {
        val (_, oldHash) = upgrade(); val db = unowned(); checkFate(db, oldHash)
    }
    @Test(timeout = 150_000) fun retainedSchemaDamageCannotBeHiddenByTheCompatibilityProjection() = runBlocking<Unit> {
        val (db, oldHash) = upgrade(); val sql = db.openHelper.writableDatabase
        assertTrue(NextReplicaCompatibilityViews.matchesSchema(db, oldHash))
        sql.execSQL("CREATE INDEX command_anchor_schema_probe ON next_transmissions(requestId)")
        val before = image(db)
        val failure = runCatching { NextReplicaCompatibilityViews.matchesSchema(db, oldHash) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it.message == "COMMAND_PRIMARY_BINDING_CHANGED" })
        assertEquals(before.contents, image(db).contents)
    }
    @Test(timeout = 150_000) fun freshParsedReplacementObjectsCannotBeSubstitutedFromAnOlderProof() = runBlocking<Unit> {
        upgrade(); val db = unowned(); val sql = db.openHelper.writableDatabase
        val captured = image(db); val old = requireNotNull(NextReplicaV19StorageView.project(captured))
        assertNotNull(db.withTransaction { NextReplicaV19StorageView.parsedPrevious(captured, old, ReplicaSqlSchema.read(sql)) })
        for (table in listOf(true, false)) {
            try {
                db.withTransaction<Unit> {
                    if (table) sql.execSQL("ALTER TABLE count_trust_control ADD COLUMN incompatible TEXT")
                    else {
                        sql.execSQL("DROP TRIGGER `${CountCommandControlSchema.GUARD}`")
                        sql.execSQL("CREATE TRIGGER `${CountCommandControlSchema.GUARD}` BEFORE UPDATE ON count_trust_control BEGIN SELECT 1; END")
                    }
                    val fresh = ReplicaSqlSchema.read(sql)
                    assertNull(NextReplicaV19StorageView.parsedPrevious(captured, old, fresh))
                    throw Rollback()
                }
            } catch (_: Rollback) { }
            assertEquals(captured.contents, image(db).contents)
        }
    }
}
