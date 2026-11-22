package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import com.dayforge.data.local.CountTrustSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual typed COUNT capture/writer and controlled replica namespace. Explicit fixture
 * reconstruction after cold reopen is not a claim of automatic Host/Hilt graph replacement.
 */
@RunWith(AndroidJUnit4::class)
class TypedWidgetReplicaBoundaryTest : NextObjectEditorFixture() {
    private fun reader() = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions))
    private suspend fun row() = requireNotNull(db.habitDao().getVisibleHabitById(habit.id))
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction() }
    private suspend fun resetOnlyNamespace() {
        val before = image()
        db.withTransaction { requireNotNull(CountTrustRuntime.forDatabase(db)).maintenance(db) {
            CountTrustSchema.resetLocalInTransaction(db.openHelper.writableDatabase)
        } }
        val after = image()
        assertEquals(before.contents.getValue("schema"), after.contents.getValue("schema"))
        assertEquals(before.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES,
            after.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES)
    }
    private fun assertBusinessUnchanged(before: ReplicaStorageProof, after: ReplicaStorageProof) {
        assertEquals(before.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES,
            after.contents.getValue("tables").jsonObject - CountTrustSchema.LOCAL_TABLES)
    }

    @Test fun originalTypedClaimSurvivesColdReopenAndPerformsExactlyOneRealIncrement() = runBlocking<Unit> {
        val original = reader().read(row()).claim
        assertEquals(original, WidgetFactClaim.decode(original.encode()))
        storage.reopen(); editor = NextObjectEditor(db, tokens, sessions, icons)
        assertEquals(original, reader().read(row()).claim)
        val prior = db.syncOutboxDao().getAll().size
        creatingHabits().performWidgetFact(app, original, "increment")
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
        assertEquals(prior + 1, db.syncOutboxDao().getAll().size)
    }

    @Test fun oldTypedClaimCannotAdoptSameAccountObjectPlanAndDayAfterReset() = runBlocking<Unit> {
        val original = reader().read(row()).claim; val session = local(); val expected = row()
        resetOnlyNamespace(); assertEquals(session, local()); assertEquals(expected, row())
        val before = image()
        assertEquals("FACT_WIDGET_REPLICA_CHANGED", rejected {
            creatingHabits().performWidgetFact(app, original, "increment")
        }.message)
        val after = image(); assertEquals(before.contents, after.contents); assertEquals(before.hash, after.hash)
        val fresh = reader().read(row()).claim
        assertNotEquals(original, fresh)
        creatingHabits().performWidgetFact(app, fresh, "increment")
        assertEquals(1, db.completionDao().getByHabitOnce(habit.id).size)
    }

    @Test fun oldFormatIsRejectedAndFreshReadReissuesNoHistoricalAction() = runBlocking<Unit> {
        val current = reader().read(row()).claim
        val old = WidgetFactClaim.decode(JsonObject(Json.parseToJsonElement(current.encode()).jsonObject - "storageId").toString())
        val before = image()
        assertEquals("FACT_WIDGET_REPLICA_CHANGED", rejected {
            creatingHabits().performWidgetFact(app, old, "increment")
        }.message)
        assertEquals(before.contents, image().contents)
        reader().read(row())
        assertBusinessUnchanged(before, image())
        assertTrue(db.completionDao().getByHabitOnce(habit.id).isEmpty())
    }
}
