package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.ChallengeMetadata
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.entity.NextSyncStateEntity
import io.mockk.confirmVerified
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** RAW is observation, never approval. Real protocol readers construct every proof; no
 * mocked schema/request/fact proof or relaxed original rejection is used by these cases. */
@RunWith(AndroidJUnit4::class)
class CountCommandRetainedRawBoundaryTest : CountCommandRetainedFixture() {
    private suspend fun healthy(): HabitDatabase {
        seed(); val database = openCurrent()
        val access = requireNotNull(tokens.localSyncAccess())
        // Empty but real protocol state. The original challenge merger creates and then
        // reads its sidecar under the original deferred cursor FK, not a fabricated proof.
        database.withTransaction {
            val state = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1L, 0L,
                "a".repeat(64), "b".repeat(64), challengeContract = 1)
            NextChallengeStore(database).mergeInTransaction(access, null, state, ChallengeMetadata(1, emptyList(), emptyList()))
            database.nextSyncStateDao().insert(state)
            assertEquals(state, NextChallengeStore(database).activeInTransaction(access).first)
        }
        store(database).ensureForwardEnrollment()
        assertLegacy(database)
        return database
    }
    private suspend fun raw(database: HabitDatabase): ReplicaStorageProof =
        database.withTransaction { NextReplicaStorageProof(database).readInTransaction() }
    private suspend fun mutate(database: HabitDatabase, statement: String) = database.withTransaction {
        CountTrustRuntime.maintenance(database) { database.openHelper.writableDatabase.execSQL(statement) }
    }
    private suspend fun business(database: HabitDatabase, proof: ReplicaStorageProof): ReplicaBusinessProof {
        val http = mockk<NextSyncHttp>()
        val result = NextReplicaLocalProofPipeline(database, tokens, sessions, commandMigrationPreferences, http)
            .readValidated(context, requireNotNull(tokens.localSyncAccess()), proof.hash)
        confirmVerified(http)
        return result
    }
    private fun reachedPartition(failure: Throwable?) = generateSequence(failure) { it.cause }.any { error ->
        error.stackTrace.any { it.className.startsWith(CountCommandRetainedPartition::class.java.name) }
    }

    @Test(timeout = 150_000) fun healthyRetainedSnapshotPassesWholeBusinessPipelineAndKeepsUnknownObligation() = runBlocking<Unit> {
        val database = healthy(); val before = raw(database)
        val proof = business(database, before)
        assertEquals(before.hash, proof.localFacts.configuration.requests.storageHash)
        assertTrue(proof.records.isEmpty()); assertTrue(proof.unconfirmed.isEmpty()); assertTrue(proof.projectionGaps.isEmpty())
        assertFalse(proof.activationAuthorized); assertEquals(before.contents, raw(database).contents)
        val access = requireNotNull(tokens.localSyncAccess())
        val assessment = NextReplicaRecoveryReader(database, tokens, sessions).read(access,
            ServerIdentityResponse(id(2), id(9), 5, access.capabilities.toList(), "2026-11-03T00:00:00Z"))
        assertEquals(1L, assessment.reviewCounts[ReplicaRecoveryReview.LOCAL_COMMAND_RECOVERY])
        assertFalse(assessment.activationAuthorized)
        assertEquals(before.contents, raw(database).contents); assertLegacy(database)
        val cold = openCurrent(); val reopened = raw(cold)
        assertEquals(before.contents, reopened.contents)
        assertEquals(reopened.hash, business(cold, reopened).localFacts.configuration.requests.storageHash)
    }
    @Test(timeout = 150_000) fun damagedRetainedMacCanBeCapturedButBusinessAndInventoryRefuseWithoutRepair() = runBlocking<Unit> {
        val database = healthy()
        mutate(database, "UPDATE count_command_recovery_roots SET mac=zeroblob(32) WHERE id=1")
        val damaged = raw(database)
        val failure = runCatching { business(database, damaged) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("COMMAND_PRIMARY_MAC_INVALID") })
        assertNotNull(runCatching { database.withTransaction {
            CountCommandRecoveryInventory(database, tokens).pendingInTransaction(requireNotNull(tokens.localSyncAccess()))
        } }.exceptionOrNull())
        assertNotNull(runCatching { NextReplicaV19StorageView.project(damaged) }.exceptionOrNull())
        assertEquals(damaged.contents, raw(database).contents); assertEquals(damaged.hash, raw(database).hash)
    }
    @Test(timeout = 150_000) fun alteredOldPartitionStillHasRawEvidenceButCannotBecomeCurrentBusinessProof() = runBlocking<Unit> {
        val database = healthy()
        mutate(database, "UPDATE count_command_results SET resultJson='changed old raw' WHERE commandId='legacy-native-id'")
        val damaged = raw(database)
        val failure = runCatching { business(database, damaged) }.exceptionOrNull()
        assertTrue("Rejection must reach the real retained verifier: $failure", reachedPartition(failure))
        assertNotNull(runCatching { store(database).discover(32) }.exceptionOrNull())
        assertNotNull(runCatching { database.withTransaction {
            CountCommandRecoveryInventory(database, tokens).pendingInTransaction(requireNotNull(tokens.localSyncAccess()))
        } }.exceptionOrNull())
        assertNull(NextReplicaV19StorageView.project(damaged)) // A valid B envelope never strips the bad old partition to19.
        assertEquals(damaged.contents, raw(database).contents)
    }
    @Test(timeout = 150_000) fun missingRetainedCatalogNodeRemainsVisibleInRawAndFailsTrustedProofBoundary() = runBlocking<Unit> {
        val database = healthy()
        mutate(database, "DELETE FROM count_command_nodes")
        val damaged = raw(database)
        val failure = runCatching { business(database, damaged) }.exceptionOrNull()
        assertTrue("Rejection must reach actual catalog traversal: $failure", reachedPartition(failure))
        assertEquals(damaged.contents, raw(database).contents)
        assertTrue(damaged.contents.getValue("tables").jsonObject.containsKey("count_command_nodes"))
        assertNotNull(runCatching { store(database).discover(32) }.exceptionOrNull())
        assertEquals(damaged.contents, raw(database).contents)
    }
}
