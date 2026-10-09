package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.LocalFactSubmissionEntity
import com.dayforge.data.local.entity.OneTimeTransmissionEntity
import com.dayforge.data.local.entity.NextRecoveryStateEntity
import com.dayforge.data.local.entity.NextRequestOriginEntity
import com.dayforge.data.local.entity.NextTransmissionEntity
import com.dayforge.data.local.entity.NextAcceptanceEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolNextActivationBarrierTest : SyncPersistenceFixture() {
    @Test fun activeCursorAndOrphanedRejectionBlockAllLegacyWritesAndAccountClearIncludesThem() = runBlocking {
        database.nextSyncStateDao().insert(com.dayforge.data.local.entity.NextSyncStateEntity(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), 1, 20, "a".repeat(64), "b".repeat(64)))
        reopen(); assertAllLegacyWritesRefused()
        database.clearAllData(); assertFalse(database.syncOutboxDao().hasProtocolNextRecovery())
        database.nextSyncStateDao().insertRejection(com.dayforge.data.local.entity.NextRejectionEntity(
            "unknown-kind", "orphan", "bad", "bad", "bad", "bad-json"))
        reopen(); assertAllLegacyWritesRefused()
        assertEquals(1, database.nextSyncStateDao().rejections().size)
        database.clearAllData(); reopen()
        assertTrue(database.nextSyncStateDao().rows().isEmpty()); assertTrue(database.nextSyncStateDao().rejections().isEmpty())
    }

    private suspend fun orphanedCausalMetadata(statement: String, table: String) {
        assertEquals(16, database.openHelper.writableDatabase.version) // Materialize the lazy file before closing it.
        database.close()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "com.dayforge.testbed")
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.setForeignKeyConstraintsEnabled(false) // Test-only corruption; cannot be inserted through production DAO.
            raw.execSQL(statement)
        }
        reopen()
        assertTrue(database.nextRequestDao().hasAny())
        assertTrue(database.syncOutboxDao().hasProtocolNextRequests())
        assertAllLegacyWritesRefused()
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        database.clearAllData()
        reopen()
        assertFalse(database.nextRequestDao().hasAny()); assertFalse(database.syncOutboxDao().hasProtocolNextRequests())
    }

    @Test fun orphanedCausalDependencyBlocksAllElevenLegacyWritesAndAccountClearIncludesIt() = runBlocking {
        orphanedCausalMetadata("INSERT INTO next_structural_dependencies(operationId,logicalOrder,originHash,kind) VALUES('orphan',1,'bad','sync_operation')",
            "next_structural_dependencies")
    }

    @Test fun orphanedCausalRetirementIsNotAnAckAndBlocksAllElevenLegacyWrites() = runBlocking {
        orphanedCausalMetadata("INSERT INTO next_structural_supersessions VALUES('orphan','replacement',1,2,'bad','bad','bad','bad-json','bad','parent','bad','bad','account','server','epoch','device','sync_operation')",
            "next_structural_supersessions")
    }

    @Test fun preparedAndMalformedConfigurationJournalBlockAllLegacyWritesWithoutAnOriginAndClearIncludesThem() = runBlocking<Unit> {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO next_config_imports VALUES('import','account','server','epoch','device','bad','bad','empty_replica','prepared',NULL)")
        sql.execSQL("INSERT INTO next_config_import_payloads VALUES('import','identities',0,X'007FFF')")
        reopen(); assertAllLegacyWritesRefused()
        assertTrue(database.syncOutboxDao().hasProtocolNextRequests())
        assertFalse(database.nextRequestDao().hasAny())
        database.clearAllData(); reopen()
        assertFalse(database.syncOutboxDao().hasProtocolNextRequests())
        database.openHelper.writableDatabase.execSQL("INSERT INTO next_config_imports VALUES('damaged','bad','bad','bad','bad','bad','bad','bad','bad',NULL)")
        assertAllLegacyWritesRefused()
    }

    @Test fun orphanedConfigurationPayloadAloneBlocksEveryLegacyWrite() = runBlocking<Unit> {
        database.openHelper.writableDatabase
        database.close()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("habit_database").path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.setForeignKeyConstraintsEnabled(false)
            it.execSQL("INSERT INTO next_config_import_payloads VALUES('orphan','identities',0,X'007FFF')")
        }
        reopen(); assertAllLegacyWritesRefused()
        assertTrue(database.syncOutboxDao().hasProtocolNextRequests())
        database.clearAllData(); reopen()
        assertFalse(database.syncOutboxDao().hasProtocolNextRequests())
    }
    @Test fun orphanedMalformedAcceptanceBlocksEveryLegacyMutationAfterReopen() = runBlocking {
        val row = NextAcceptanceEntity("unknown-kind", "orphan", "bad", "bad", "bad", "bad-json")
        database.nextRequestDao().insertAcceptance(row)
        reopen(); assertAllLegacyWritesRefused()
        assertEquals(row, database.nextRequestDao().acceptance(row.kind, row.requestId))
    }

    @Test fun orphanedMalformedCoreOriginsBlockEveryLegacyMutationAfterReopen() = runBlocking {
        val row = NextRequestOriginEntity("unknown-kind", "orphan", 999, 99, "account", null, "partial-epoch", "bad", "bad-json")
        database.nextRequestDao().insertOrigin(row)
        reopen(); assertAllLegacyWritesRefused()
        assertEquals(row, database.nextRequestDao().origin(row.kind, row.requestId))
    }

    @Test fun orphanedMalformedCoreTransmissionBlocksEveryLegacyMutationAfterReopen() = runBlocking {
        val row = NextTransmissionEntity("timer_command", "orphan", 999, 99, "account", "server", "epoch", "device", "bad", byteArrayOf(0))
        database.nextRequestDao().insertTransmission(row)
        reopen(); assertAllLegacyWritesRefused()
        assertArrayEquals(row.wireBytes, database.nextRequestDao().transmission(row.kind, row.requestId)!!.wireBytes)
    }

    private suspend fun assertAllLegacyWritesRefused() {
        val queue = database.syncOutboxDao().getAll()
        val rejected = database.syncOutboxDao().getDeadLetters()
        val facts = database.completionDao().getAllCompletionsOnce()
        val habits = database.habitDao().getAllHabitsOnce()
        val preferences = tokenStore.data.first()
        var afterSyncCalled = false
        val actions: List<suspend () -> Unit> = listOf(
            { repository.sync() },
            { repository.syncAndThen { afterSyncCalled = true } },
            { repository.retryDeadLetter(rejected.firstOrNull()?.id ?: 1) },
            { repository.discardDeadLetter(rejected.firstOrNull()?.id ?: 1) },
            { repository.retryAllDeadLetters() },
            { repository.resolveConflictUseLocal(1) },
            { repository.resolveConflictUseServer(1) },
            { repository.retryRejectedTimerCommand(1) },
            { repository.cancelRejectedTimerCommandAndUseServer(1) },
            { repository.makeCurrentDevicePrimary(); Unit },
            { repository.setCurrentDeviceStructuralEditing(true); Unit }
        )
        actions.forEachIndexed { index, action ->
            val failure = runCatching { action() }.exceptionOrNull()
            assertTrue("entry $index: $failure", failure is ProtocolNextDataRequiresUpgradeException)
        }
        assertFalse(afterSyncCalled)
        assertTrue(paths.isEmpty())
        assertEquals(preferences, tokenStore.data.first())
        assertEquals(queue, database.syncOutboxDao().getAll())
        assertEquals(rejected, database.syncOutboxDao().getDeadLetters())
        assertEquals(facts, database.completionDao().getAllCompletionsOnce())
        assertEquals(habits, database.habitDao().getAllHabitsOnce())
    }

    @Test fun stagedPolicyOrPartialProjectionBlocksEveryLegacyMutationBeforeNetwork() = runBlocking {
        val habit = insertGoal("staged")
        for (row in listOf(habit.copy(completionPolicy = "recurring"),
            habit.copy(oneTimeConfirmedVersion = 0),
            habit.copy(oneTimeConfirmedHeadEventUuid = UUID.randomUUID().toString()),
            habit.copy(oneTimeConfirmedCompletionEventUuid = UUID.randomUUID().toString()))) {
            database.habitDao().update(row)
            assertAllLegacyWritesRefused()
        }
        reopen()
        assertAllLegacyWritesRefused()
    }

    @Test fun partialIntentCannotLeakIntoLegacyCompletionMapper() = runBlocking {
        val habit = insertGoal("unclassified")
        val fact = CompletionEntity(habitId = habit.id, habitUuid = habit.uuid, date = 0, value = 1)
        for (row in listOf(fact.copy(oneTimeAction = "complete"),
            fact.copy(oneTimeExpectedVersion = 0),
            fact.copy(oneTimeExpectedHeadEventUuid = UUID.randomUUID().toString()),
            fact.copy(oneTimeRevertsEventUuid = UUID.randomUUID().toString()))) {
            database.completionDao().deleteByHabitId(habit.id)
            database.completionDao().insertForSync(row)
            assertAllLegacyWritesRefused()
        }
    }

    @Test fun orphanedRejectedOperationCannotBeDiscardedRekeyedOrRestoredByV4() = runBlocking {
        val event = UUID.randomUUID().toString()
        val id = database.syncOutboxDao().insert(SyncOutboxEntity(
            operationId = UUID.randomUUID().toString(), recordType = "one_time_completion",
            entityUuid = event, wireEntityUuid = event, action = "upsert",
            referenceUuid = UUID.randomUUID().toString(), payloadJson = "{}"
        ))
        database.syncOutboxDao().markDeadLetter(id, "TASK_STATE_CONFLICT", "synthetic", 1)
        reopen()
        assertAllLegacyWritesRefused()
    }

    @Test fun orphanedLocalReceiptOrPromptStillBlocksLegacyRecovery() = runBlocking {
        database.completionFollowUpDao().insertSubmission(LocalFactSubmissionEntity(
            UUID.randomUUID().toString(), "metric_observation", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "{}"))
        assertAllLegacyWritesRefused()
        database.clearAllData()
        database.completionFollowUpDao().insertPrompt(CompletionMetricPromptEntity(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), entriesJson = "[]"))
        assertAllLegacyWritesRefused()
    }

    @Test fun orphanedTransmissionStillBlocksEveryLegacyMutationAfterReopen() = runBlocking {
        val row = OneTimeTransmissionEntity(UUID.randomUUID().toString(), "account", "server", "epoch",
            UUID.randomUUID().toString(), "{\"frozen\":true}", "{\"rejected\":true}")
        database.completionFollowUpDao().insertTransmission(row)
        reopen()
        assertAllLegacyWritesRefused()
        assertEquals(row, database.completionFollowUpDao().transmission(row.operationId))
    }

    @Test fun appearanceOrBareOnceScheduleCannotBeDowngradedByLegacyEngine() = runBlocking {
        val goal = insertGoal("appearance")
        val appearance = com.dayforge.domain.model.ObjectAppearance(
            com.dayforge.domain.model.IconReference.Role("goal.custom"), "#123456", "object")
        database.habitDao().update(goal.copy(appearance = appearance))
        reopen(); assertAllLegacyWritesRefused()
        database.habitDao().update(goal.copy(schedule = com.dayforge.data.model.HabitSchedule.Once("2026-12-31")))
        reopen(); assertAllLegacyWritesRefused()
        database.habitDao().update(goal)
        database.metricDao().insert(com.dayforge.data.local.entity.MetricEntity(
            name = "appearance metric", unit = "kg", iconResId = 0, colorHex = "#000000", appearance = appearance))
        reopen(); assertAllLegacyWritesRefused()
    }

    @Test fun planningMetadataAloneBlocksLegacySyncAndRecoveryAfterReopen() = runBlocking {
        val goal = insertGoal("planning metadata")
        database.habitDao().update(goal.copy(planMetadata = com.dayforge.data.model.PlanStructureMetadata(
            "2026-09-27T00:00:00Z", 17, "2026-01-01", "2026-12-31", null, null, null, null)))
        reopen()
        assertAllLegacyWritesRefused()
    }

    @Test fun orphanedRecoveryPhasesAndDamagedRowsBlockV4AndAccountClearIncludesTheCheckpoint() = runBlocking {
        val stage = NextRecoveryStateEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, NextRecoveryStateEntity.AWAITING_SNAPSHOT)
        for (row in listOf(stage, stage.copy(phase = NextRecoveryStateEntity.ACCEPTED_DATA,
            candidateCursor = 20, snapshotHash = "a".repeat(64)))) {
            assertFalse(database.syncOutboxDao().hasProtocolNextRecovery())
            database.nextRecoveryDao().insert(row)
            reopen()
            assertTrue(database.syncOutboxDao().hasProtocolNextRecovery())
            assertAllLegacyWritesRefused()
            assertEquals(row, database.nextRecoveryDao().state())
            database.clearAllData()
            reopen()
            assertFalse(database.syncOutboxDao().hasProtocolNextRecovery())
            assertNull(database.nextRecoveryDao().state())
        }
        database.nextRecoveryDao().insert(stage)
        database.openHelper.writableDatabase.execSQL("UPDATE next_recovery_state SET id=2,phase='invalid'")
        reopen()
        // SQL existence is intentional: malformed or non-singleton rows must not look uninitialized.
        assertAllLegacyWritesRefused()
        database.openHelper.readableDatabase.query("SELECT id,phase FROM next_recovery_state").use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals("invalid", it.getString(1))
            assertFalse(it.moveToNext())
        }
        database.clearAllData()
        assertFalse(database.syncOutboxDao().hasProtocolNextRecovery())
    }
}
