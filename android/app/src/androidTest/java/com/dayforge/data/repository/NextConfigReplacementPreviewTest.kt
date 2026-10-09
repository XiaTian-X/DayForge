package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.local.entity.CompletionMetricPromptEntity
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.HabitDraft
import com.dayforge.data.model.HabitSchedule
import com.dayforge.data.model.HabitType
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual account/Room/local producer and HTTP acceptance; preview never deletes or creates. */
@RunWith(AndroidJUnit4::class)
class NextConfigReplacementPreviewTest : NextObjectEditorFixture() {
    @Test fun queueAdmissionUsesExistingAuditBudgetWithoutOverflowOrSilentlyTruncatingObjects() {
        assertTrue(NextConfigReplacementInspector.fitsQueue(3000, 7000))
        assertFalse(NextConfigReplacementInspector.fitsQueue(3001, 7000))
        assertTrue(NextConfigReplacementInspector.fitsQueue(0, 0))
        assertFalse(NextConfigReplacementInspector.fitsQueue(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(runCatching { NextConfigReplacementInspector.fitsQueue(-1, 1) }.isFailure)
    }
    private fun service() = NextConfigImportRepository(db, tokens, sessions, icons,
        NextObjectCreator(db, tokens, sessions, icons), app)
    private suspend fun source() = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
    private suspend fun preview() = service().previewReplacement(source())
    private suspend fun cursor() {
        db.nextSyncStateDao().insert(NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0,
            "a".repeat(64), "b".repeat(64)))
    }
    private suspend fun acceptedGraph() {
        register(); db.clearAllData() // Isolated test fixture only, never the real application.
        val appearance = ObjectAppearance(IconReference.Role("habit.default"), "#123456", "theme")
        creatingHabits().createGoal(HabitDraft(id = id(510), name = "Existing goal", habitType = HabitType.GOAL,
            appearance = appearance.copy(icon = IconReference.Role("goal.default"))),
            listOf(HabitDraft(id = id(511), name = "Existing habit", habitType = HabitType.CHECK_IN,
                targetValue = 1, completionPolicy = "recurring", appearance = appearance)), creationAuthority = creator.capture())
        metric = metric.copy(id = 0)
        val metricId = creatingMetrics().createMetric(metric, creationAuthority = creator.capture())
        metric = requireNotNull(db.metricDao().getMetricById(metricId))
        val habitId = requireNotNull(db.habitDao().getHabitByUuid(id(511))).id
        creatingMetrics().linkHabits(metric, setOf(habitId))
        onceHabits(onceRepository()).createHabit("Existing item", "", HabitType.CHECK_IN, 0, "#123456",
            HabitSchedule.Once(), appearance = appearance.copy(icon = IconReference.Role("task.default")),
            failMode = com.dayforge.data.model.FailMode.LOOSE,
            completionPolicy = "one_and_done", creationAuthority = creator.capture())
        val (http, _) = channel { successReply(it) }
        while (db.syncOutboxDao().getAll().isNotEmpty()) {
            val row = db.syncOutboxDao().getAll().first()
            assertEquals(NextOperationAcceptance.COMMITTED, sender(http).sendAndAcceptOperation(access(), row.operationId))
        }
        cursor() // Local checkpoint fixture, not a claim of backend bootstrap integration.
    }
    private fun durable(): Map<String, List<List<String?>>> {
        val sql = db.openHelper.readableDatabase
        return sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { tables ->
            buildMap { while (tables.moveToNext()) {
                val table = tables.getString(0); require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                put(table, sql.query("SELECT * FROM $table ORDER BY rowid").use { rows -> buildList {
                    while (rows.moveToNext()) add((0 until rows.columnCount).map {
                        if (rows.isNull(it)) null else if (rows.getType(it) == android.database.Cursor.FIELD_TYPE_BLOB)
                            rows.getBlob(it).joinToString("") { byte -> "%02x".format(byte) }
                        else "${rows.getType(it)}:${rows.getString(it)}"
                    })
                } })
            } }
        }
    }

    @Test fun acceptedNonemptyGraphHasExactDestructiveCountsAndColdRecheckIsReadOnly() = runBlocking<Unit> {
        acceptedGraph(); val before = durable(); val value = preview()
        assertTrue(value.eligible); assertEquals(ConfigReplacementCounts(1, 1, 1, 1, 1, 0, 0, 0), value.counts)
        assertEquals(before, durable()); assertEquals(0, count("next_config_imports"))
        assertTrue(iconMetadata.library(icons.capture()).packs.isEmpty())
        service().recheckReplacement(value); storage.reopen(); service().recheckReplacement(value)
        assertEquals(before, durable()); assertEquals(0, widgetRefresh.requestCount)
        assertEquals("CONFIG_IMPORT_REPLACEMENT_NOT_READY", rejected { service().confirm(value.original) }.message)
        assertEquals(before, durable()) // A readiness preview does not enable destructive confirmation.
    }

    @Test fun incompleteReplicaAndUnacceptedStructuresAreReportedWithoutInventingAcks() = runBlocking<Unit> {
        register(); val before = durable(); val value = preview()
        assertEquals(setOf(ConfigReplacementBlocker.UNCONFIRMED_REPLICA,
            ConfigReplacementBlocker.UNCONFIRMED_STRUCTURE), value.blockers)
        assertEquals("CONFIG_REPLACEMENT_NOT_READY", rejected { service().recheckReplacement(value) }.message)
        assertEquals(before, durable())
        assertTrue(runCatching { (value.blockers as MutableSet<*>).clear() }.isFailure)
    }

    @Test fun pendingAndDeadLetteredMutationsRemainBlockingAndOldPreviewCannotAdoptAnEdit() = runBlocking<Unit> {
        acceptedGraph(); val value = preview()
        val row = requireNotNull(db.metricDao().getMetricById(metric.id))
        creatingMetrics().updateMetric(row.copy(description = "changed after preview"),
            creatingMetrics().getMetricForEditing(row.id).authority)
        val queued = db.syncOutboxDao().getAll().single()
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET deadLetteredAt=123,errorCode='TEST_REJECTION' WHERE id=?",
            arrayOf<Any>(queued.id))
        val before = durable(); val current = preview()
        assertTrue(ConfigReplacementBlocker.PENDING_WORK in current.blockers)
        assertTrue(ConfigReplacementBlocker.UNCONFIRMED_STRUCTURE in current.blockers)
        assertEquals("CONFIG_REPLACEMENT_PREVIEW_CHANGED", rejected { service().recheckReplacement(value) }.message)
        assertEquals(before, durable())
    }

    @Test fun activeTimerAndPendingPromptAreDistinctButSavedPromptIsNotAnUnresolvedDraft() = runBlocking<Unit> {
        register(); start()
        db.completionFollowUpDao().insertPrompt(CompletionMetricPromptEntity(id(512), habit.uuid, entriesJson = "[]"))
        val before = durable(); val value = preview()
        assertTrue(ConfigReplacementBlocker.ACTIVE_TIMER in value.blockers)
        assertTrue(ConfigReplacementBlocker.PENDING_WORK in value.blockers)
        assertTrue(ConfigReplacementBlocker.COMPLETION_PROMPT in value.blockers)
        assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE completion_metric_prompts SET state='saved'")
        assertFalse(ConfigReplacementBlocker.COMPLETION_PROMPT in preview().blockers)
    }

    @Test fun factOnlyChangesAndChangesOutsideVisibleDefinitionsInvalidateTheOriginalPreview() = runBlocking<Unit> {
        acceptedGraph(); val value = preview()
        val row = requireNotNull(db.habitDao().getHabitByUuid(id(511)))
        creatingHabits().logCompletion(app, row.id)
        val current = preview(); assertEquals(1L, current.counts.checkAndCountRecords)
        assertEquals("CONFIG_REPLACEMENT_PREVIEW_CHANGED", rejected { service().recheckReplacement(value) }.message)
        val frozen = current.fingerprint
        val fact = db.completionDao().getByHabitOnce(row.id).single()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.openHelper.writableDatabase.execSQL("UPDATE completions SET actualCompletedAt=actualCompletedAt+1 WHERE id=?",
                arrayOf<Any>(fact.id))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = durable(); assertNotEquals(frozen, preview().fingerprint)
        assertEquals(before, durable())
    }

    @Test fun staleReauthenticationPermissionDeviceAndReplicaCannotAuthorizeAnOldPreview() = runBlocking<Unit> {
        acceptedGraph(); val value = preview(); val before = durable()
        tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false); register()
        assertEquals("CONFIG_IMPORT_SESSION_CHANGED", rejected { service().recheckReplacement(value) }.message)
        register(permissions = setOf("sync.read"))
        assertEquals("CONFIG_IMPORT_ACCESS_DENIED", rejected { preview() }.message)
        register(device = id(900))
        assertEquals("CONFIG_IMPORT_REPLICA_CHANGED", rejected { preview() }.message)
        register(); db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET syncEpoch=?", arrayOf(id(901)))
        val damaged = durable(); assertEquals("CONFIG_IMPORT_REPLICA_CHANGED", rejected { preview() }.message)
        assertEquals(damaged, durable()); assertEquals(before.getValue("habits"), durable().getValue("habits"))
    }

    @Test fun rawCheckpointAndShadowDamageAreRejectedWithoutRepairOrClear() = runBlocking<Unit> {
        acceptedGraph()
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET generation=1.5")
        var before = durable(); rejected { preview() }; assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET generation=1")
        db.openHelper.writableDatabase.execSQL("UPDATE sync_entity_state SET payloadHash=?", arrayOf("f".repeat(64)))
        before = durable(); assertEquals("CONFIG_REPLACEMENT_INVALID_SHADOW", rejected { preview() }.message)
        assertEquals(before, durable())
    }

    @Test fun oversizedRetainedJournalIsBoundedBeforeFullCursorReadAndNeverRepaired() = runBlocking<Unit> {
        acceptedGraph()
        val sql = db.openHelper.writableDatabase
        val id = db.nextRequestDao().acceptance(NEXT_OPERATION,
            db.openHelper.readableDatabase.query("SELECT requestId FROM next_acceptances LIMIT 1").use {
                check(it.moveToFirst()); it.getString(0)
            })!!.requestId
        sql.execSQL("UPDATE next_transmissions SET wireBytes=zeroblob(?) WHERE requestId=?",
            arrayOf<Any>(com.dayforge.data.api.SYNC_REQUEST_LIMIT + 8192, id))
        assertEquals("CONFIG_REPLACEMENT_INVALID_STORAGE", rejected { preview() }.message)
        sql.query("SELECT length(wireBytes) FROM next_transmissions WHERE requestId=?", arrayOf(id)).use {
            check(it.moveToFirst()); assertEquals(com.dayforge.data.api.SYNC_REQUEST_LIMIT + 8192, it.getInt(0))
        }
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("sync_outbox"))
    }

    @Test fun orphanLinksAndLegacyRowsAreNotSilentlyExcludedFromDestructiveCounts() = runBlocking<Unit> {
        acceptedGraph(); val link = db.habitMetricLinkDao().getAllLinksOnce().single()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habit_metric_links SET habitUuid=? WHERE id=?", arrayOf<Any>(id(999), link.id))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = durable(); assertEquals("CONFIG_REPLACEMENT_INVALID_GRAPH", rejected { preview() }.message)
        assertEquals(before, durable())
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE habit_metric_links SET habitUuid=? WHERE id=?", arrayOf<Any>(id(511), link.id))
            sql.execSQL("UPDATE habits SET appearance=NULL WHERE uuid=?", arrayOf(id(511)))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val legacy = durable(); rejected { preview() }; assertEquals(legacy, durable())
    }

    @Test fun historyBeyondOnePageIsCountedAndSameCountValueChangesInvalidateThePreview() = runBlocking<Unit> {
        acceptedGraph(); val habitId = requireNotNull(db.habitDao().getHabitByUuid(id(511))).id
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            repeat(130) { index -> db.completionDao().insert(com.dayforge.data.local.entity.CompletionEntity(
                habitId = habitId, habitUuid = id(511), uuid = id(2000 + index), value = 1,
                date = millis, actualCompletedAt = millis, recordedTimezone = "Etc/UTC", recordedLocalDate = "2026-10-06")) }
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val before = durable(); val value = preview(); assertEquals(130L, value.counts.checkAndCountRecords)
        service().recheckReplacement(value); assertEquals(before, durable())
        db.withTransaction {
            val sql = db.openHelper.writableDatabase; sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            sql.execSQL("UPDATE completions SET value=2 WHERE uuid=?", arrayOf(id(2129)))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals(130L, preview().counts.checkAndCountRecords)
        assertEquals("CONFIG_REPLACEMENT_PREVIEW_CHANGED", rejected { service().recheckReplacement(value) }.message)
    }

    @Test fun signedMinimumImplicitRowIdIsNotOmittedFromTheCompleteStoredValueFingerprint() = runBlocking<Unit> {
        acceptedGraph()
        val sql = db.openHelper.writableDatabase
        val requestId = sql.query("SELECT requestId FROM next_acceptances LIMIT 1").use {
            check(it.moveToFirst()); it.getString(0)
        }
        sql.execSQL("UPDATE next_acceptances SET rowid=? WHERE requestId=?", arrayOf<Any>(Long.MIN_VALUE, requestId))
        val value = preview(); assertTrue(value.eligible)
        sql.execSQL("UPDATE next_acceptances SET resultHash=? WHERE requestId=?", arrayOf("f".repeat(64), requestId))
        val before = durable()
        assertNotEquals(value.fingerprint, preview().fingerprint)
        assertEquals("CONFIG_REPLACEMENT_PREVIEW_CHANGED", rejected { service().recheckReplacement(value) }.message)
        assertEquals(before, durable()) // Value/type binding only, not proof that the altered ACK is valid.
    }

    @Test fun resolvedConflictReceiptsDoNotBlockButUnresolvedOrUnknownConflictStatesDo() = runBlocking<Unit> {
        acceptedGraph()
        val shadow = requireNotNull(db.syncOutboxDao().getState("metric", metric.uuid))
        val conflictId = db.syncConflictDao().insert(com.dayforge.data.local.entity.SyncConflictEntity(
            operationId = id(950), recordType = "metric", localEntityUuid = metric.uuid, wireEntityUuid = metric.uuid,
            entityType = "metric", action = "upsert", referenceUuid = null, baseRevision = 1, serverRevision = 1,
            basePayloadJson = shadow.payloadJson, localPayloadJson = requireNotNull(shadow.payloadJson),
            serverPayloadJson = shadow.payloadJson, conflictingFieldsJson = "[]", conflictKind = null, errorCode = null,
            message = null)) // Status-storage boundary fixture, not a fabricated server acceptance.
        assertTrue(ConfigReplacementBlocker.CONFLICT_OR_RECOVERY in preview().blockers)
        db.syncConflictDao().markResolved(conflictId, "server", millis)
        val before = durable(); val value = preview(); assertTrue(value.eligible)
        service().recheckReplacement(value); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE sync_conflicts SET status='unknown' WHERE id=?", arrayOf<Any>(conflictId))
        assertTrue(ConfigReplacementBlocker.CONFLICT_OR_RECOVERY in preview().blockers)
    }
}
