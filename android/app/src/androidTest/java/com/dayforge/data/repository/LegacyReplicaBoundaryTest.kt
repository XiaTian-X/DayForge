package com.dayforge.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import com.dayforge.domain.service.StructuralEditGuard
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real controlled Room + original authenticated legacy writer. Only the unused structural
 * network collaborator is strict-mocked: these already-active +1 commands cannot invoke it.
 * No fake graph, current-ID recapture or permission cache. Device execution remains required.
 */
@RunWith(AndroidJUnit4::class)
class LegacyReplicaBoundaryTest : NextCoreRequestFixture() {
    private val structural = mockk<StructuralEditGuard>()
    private fun publisher() = WidgetDisplayPublisher(tokens, sessions, ReplicaPresentationRepository(db, tokens, sessions))
    private fun workflow() = LegacyWidgetFactWorkflow(db, tokens, sessions,
        LegacyWidgetIdentityVerifier(db, tokens, ReplicaPresentationRepository(db, tokens, sessions)), habits(), structural)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun plain() {
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habits SET appearance=NULL,planMetadata=NULL,completionPolicy=NULL")
            sql.execSQL("UPDATE metrics SET appearance=NULL")
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        habit = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
    }
    private suspend fun resetOnlyNamespace() {
        val before = image()
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            CountTrustSchema.resetLocalInTransaction(db.openHelper.writableDatabase)
        } }
        val after = image()
        assertEquals(before.contents.getValue("schema"), after.contents.getValue("schema"))
        assertEquals(before.contents.getValue("user_version"), after.contents.getValue("user_version"))
        assertEquals(before.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES,
            after.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES)
        assertEquals(habit, db.habitDao().getVisibleHabitById(habit.id))
    }
    private suspend fun original(): LegacyWidgetRestartClaim =
        requireNotNull(requireNotNull(publisher().capturePublication()).legacyRestartClaim(habit))

    @Test fun actualPublisherClaimSurvivesNormalSameFileReopenAndCommitsOneOriginalCount() = runBlocking<Unit> {
        plain(); val identity = original(); val action = workflow().capture(identity).forAction("increment")
        val before = image(); storage.reopen()
        assertEquals(before.contents, image().contents)
        assertEquals(identity, original()); assertEquals(identity, LegacyWidgetRestartClaim.decode(identity.encode()))
        val result = workflow().perform(app, action)
        assertTrue(result.added); assertEquals(identity, result.identity)
        assertEquals(1, db.completionDao().countAll())
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        coVerify(exactly = 0) { structural.requireAllowed() }
    }

    @Test fun oldCountWithIdenticalAccountUuidPlanAndDayCannotWriteTheResetReplica() = runBlocking<Unit> {
        plain(); val identity = original(); val action = workflow().capture(identity).forAction("increment")
        val session = local(); resetOnlyNamespace(); assertEquals(session, local())
        val before = image()
        assertEquals("LEGACY_WIDGET_REPLICA_CHANGED", rejected { workflow().perform(app, action) }.message)
        val after = image(); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        val fresh = original(); assertNotEquals(identity, fresh)
        val result = workflow().perform(app, workflow().capture(fresh).forAction("increment"))
        assertTrue(result.added); assertEquals(1, db.completionDao().countAll())
        assertEquals(1, db.syncOutboxDao().getAll().count { it.recordType == "completion" })
        coVerify(exactly = 0) { structural.requireAllowed() }
    }

    @Test fun persistedDisplayAndOldReadCannotPublishAfterOnlyNamespaceChanges() = runBlocking<Unit> {
        plain(); val source = publisher(); val old = requireNotNull(source.capturePublication())
        val preferences = mutablePreferencesOf(); old.stampFrame(preferences); assertTrue(old.ownsFrame(preferences))
        var calls = 0; assertTrue(old { calls++ }); assertEquals(1, calls)
        resetOnlyNamespace(); val before = image()
        assertFalse(old { calls++ }); assertEquals(1, calls)
        val fresh = requireNotNull(source.capturePublication()); assertNotEquals(old.frameOwner, fresh.frameOwner)
        assertFalse(fresh.ownsFrame(preferences)); assertTrue(fresh { calls++ }); assertEquals(2, calls)
        fresh.stampFrame(preferences); assertTrue(fresh.ownsFrame(preferences)); assertFalse(old.ownsFrame(preferences))
        assertEquals(before.contents, image().contents)
    }

    @Test fun oldSerializedIdentityWithoutNamespaceFailsAndReadOnlyRepublishDoesNotExecuteIt() = runBlocking<Unit> {
        plain(); val identity = original()
        val oldBody = JsonObject(Json.parseToJsonElement(identity.encode()).jsonObject - "storageId")
        val old = LegacyWidgetRestartClaim.decode(oldBody.toString()); val before = image()
        assertEquals("LEGACY_WIDGET_REPLICA_CHANGED", rejected { workflow().capture(old) }.message)
        assertEquals(before.contents, image().contents)
        val fresh = original(); workflow().capture(fresh)
        assertEquals(before.contents, image().contents); assertEquals(0, db.completionDao().countAll())
        coVerify(exactly = 0) { structural.requireAllowed() }
    }
}
