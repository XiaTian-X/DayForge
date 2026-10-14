package com.dayforge.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.ui.screens.editgoal.EditGoalViewModel
import com.dayforge.ui.screens.edithabit.EditHabitViewModel
import com.dayforge.ui.screens.editmetric.EditMetricViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextObjectEditorTest : NextObjectEditorFixture() {
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "theme")
    private suspend fun metricTicket() = editingMetrics().getMetricForEditing(metric.id)
    private suspend fun habitTicket() = editingHabits().getHabitForEditing(habit.id)

    @Test fun typedMetricEditorSavesFrozenIntentAndRealHttpAcceptanceThenColdReopens() = runBlocking<Unit> {
        register()
        val model = withContext(Dispatchers.Main) { own(EditMetricViewModel(app, db.metricDao(), editingMetrics(),
            SavedStateHandle(mapOf("metricId" to metric.id)))) }
        withTimeout(5000) { model.uiState.first { it.isLoaded } }
        assertEquals(metric.appearance, model.uiState.value.appearance)
        assertEquals(metric.appearance!!.accentColor, model.uiState.value.colorHex)
        assertNotNull(model.uiState.value.editAuthority)
        withContext(Dispatchers.Main) {
            model.updateName("Native edited metric")
            model.updateAppearance(appearance("metric.changed"))
            model.saveMetric()
        }
        val saved = withTimeout(5000) { model.uiState.first { it.isSaved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.isSaved)
        val row = db.syncOutboxDao().getAll().single()
        val origin = originalIntent(row)
        assertEquals(5, origin.protocol); assertEquals(id(1), origin.accountId)
        assertEquals("metric.changed", Json.parseToJsonElement(origin.intentJson).jsonObject
            .getValue("payload").jsonObject.getValue("appearance").jsonObject.getValue("icon").jsonObject
            .getValue("role").jsonPrimitive.content)
        val (http, server) = channel { successReply(it) }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        storage.reopen()
        val reopened = requireNotNull(db.metricDao().getMetricById(metric.id))
        assertEquals("Native edited metric", reopened.name); assertEquals(appearance("metric.changed"), reopened.appearance)
        assertEquals(origin, db.nextRequestDao().origin(NEXT_OPERATION, row.operationId))
    }

    @Test fun typedHabitEditUpdatesPreferredTimeButPreservesUnseenMetadataAndDerivedState() = runBlocking<Unit> {
        val baseline = habit.copy(planMetadata = habit.planMetadata!!.copy(sortOrder = 37, startDate = "2026-10-01",
            targetUnit = "pages", preferredLocalTime = "08:05:12+08:00"), bestTime = 485)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(baseline)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val snapshot = habitTicket()
        val modified = requireNotNull(snapshot.value).copy(name = "Edited recurring", bestTime = 570,
            appearance = appearance("habit.exercise"), activityRate = 0,
            planMetadata = baseline.planMetadata!!.copy(sortOrder = 0, startDate = null, targetUnit = null))
        editingHabits().updateHabit(modified, editAuthority = snapshot.authority)
        val saved = requireNotNull(db.habitDao().getHabitById(habit.id))
        assertEquals(baseline.activityRate, saved.activityRate)
        assertEquals(baseline.activityRateUpdatedAt, saved.activityRateUpdatedAt)
        assertEquals(baseline.planMetadata!!.copy(preferredLocalTime = "09:30"), saved.planMetadata)
        assertEquals("09:30", Json.parseToJsonElement(originalIntent(db.syncOutboxDao().getAll().single()).intentJson)
            .jsonObject.getValue("payload").jsonObject.getValue("activity").jsonObject
            .getValue("preferred_local_time").jsonPrimitive.content)
    }

    @Test fun editBeforeFirstDeviceRegistrationRetainsNullableReplicaAndOriginalTime() = runBlocking<Unit> {
        val snapshot = habitTicket()
        editingHabits().updateHabit(habit.copy(description = "offline", appearance = appearance("habit.changed")),
            editAuthority = snapshot.authority)
        val origin = originalIntent(db.syncOutboxDao().getAll().single())
        assertNull(origin.serverInstanceId); assertNull(origin.syncEpoch)
        assertEquals(habit.createdAt, db.habitDao().getHabitById(habit.id)!!.createdAt)
        assertEquals(habit.planMetadata, db.habitDao().getHabitById(habit.id)!!.planMetadata)
    }

    @Test fun staleAuthenticationAndRemoteStructureChangesDoNotWriteOrFreezeWork() = runBlocking<Unit> {
        val first = metricTicket()
        sessions.exclusive { tokens.saveLoginSession("synthetic-new", "synthetic-refresh", "member", id(1), false) }
        rejected { editingMetrics().updateMetric(metric.copy(name = "Stale login"), first.authority) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("next_request_origins"))
        val second = metricTicket()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.metricDao().update(metric.copy(description = "Remote edit"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        assertEquals("OBJECT_EDIT_CHANGED_RELOAD_REQUIRED", rejected {
            editingMetrics().updateMetric(metric.copy(name = "Stale structure"), second.authority)
        }.message)
        assertEquals("Remote edit", db.metricDao().getMetricById(metric.id)!!.description)
        assertTrue(db.syncOutboxDao().getAll().isEmpty()); assertEquals(0, count("next_request_origins"))
    }

    @Test fun newFixedAssetRequiresOwnershipAndCorrectPurposeButExistingMissingReferenceIsRetained() = runBlocking<Unit> {
        register()
        val context = iconMetadata.capture()
        val blob = IconBlob("1".repeat(64), 1, "image/svg+xml", 1, 1)
        val task = IconAsset(id(60), "Task only", "task", "template", blob, null)
        val general = task.copy(assetId = id(61), name = "General", purpose = "general")
        iconMetadata.reserveAsset(context, task); iconMetadata.reserveAsset(context, general)
        val snapshot = metricTicket()
        for (asset in listOf(id(60), id(62))) {
            rejected { editingMetrics().updateMetric(metric.copy(appearance = ObjectAppearance(IconReference.Asset(asset),
                "#123456", "object")), snapshot.authority) }
            assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_outbox"))
        }
        editingMetrics().updateMetric(metric.copy(appearance = ObjectAppearance(IconReference.Asset(id(61)),
            "#123456", "object")), snapshot.authority)
        assertEquals(IconReference.Asset(id(61)), db.metricDao().getMetricById(metric.id)!!.appearance!!.icon)

        val missing = habit.copy(appearance = ObjectAppearance(IconReference.Asset(id(63)), "#123456", "theme"))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(missing)
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val retained = habitTicket()
        editingHabits().updateHabit(missing.copy(description = "Keep missing icon"), editAuthority = retained.authority)
        assertEquals(missing.appearance, db.habitDao().getHabitById(habit.id)!!.appearance)
    }

    @Test fun knownReadOnlyPermissionAndOriginFailureRollBackBusinessAndOutbox() = runBlocking<Unit> {
        register(permissions = setOf("sync.read"))
        val readonly = metricTicket()
        rejected { editingMetrics().updateMetric(metric.copy(name = "Forbidden"), readonly.authority) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id)); assertEquals(0, count("sync_outbox"))
        register()
        val snapshot = metricTicket()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_editor_origin BEFORE INSERT ON next_request_origins BEGIN SELECT RAISE(ABORT,'editor fixture'); END")
        rejected { editingMetrics().updateMetric(metric.copy(name = "Must rollback"), snapshot.authority) }
        assertEquals(metric, db.metricDao().getMetricById(metric.id))
        assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
        sql.execSQL("DROP TRIGGER fail_editor_origin")
        editingMetrics().updateMetric(metric.copy(name = "Retry"), snapshot.authority)
        assertEquals("Retry", db.metricDao().getMetricById(metric.id)!!.name)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
    }

    private suspend fun insertPlan(row: HabitEntity): HabitEntity = db.withTransaction {
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
        val inserted = row.copy(id = db.habitDao().insert(row))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        inserted
    }

    @Test fun actualGoalEditorKeepsDatesAndSortOrderAndFreezesTypedAppearance() = runBlocking<Unit> {
        val goal = insertPlan(habit.copy(id = 0, uuid = id(70), name = "Goal", habitType = HabitType.GOAL,
            targetValue = 1, completionPolicy = null, appearance = appearance("goal.project"),
            planMetadata = habit.planMetadata!!.copy(timezone = null, sortOrder = 81,
                startDate = "2026-10-01", goalDueDate = "2026-12-31")))
        val model = withContext(Dispatchers.Main) {
            own(EditGoalViewModel(app, db.habitDao(), editingHabits(), preferences)).also { it.loadGoal(goal.id) }
        }
        withTimeout(5000) { model.uiState.first { !it.isLoading } }
        assertEquals(goal.appearance!!.accentColor, model.uiState.value.colorHex)
        withContext(Dispatchers.Main) { model.updateAppearance(appearance("goal.changed")); model.saveGoal() }
        val saved = withTimeout(5000) { model.uiState.first { it.saved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.saved)
        val row = requireNotNull(db.habitDao().getHabitById(goal.id))
        assertEquals(goal.planMetadata, row.planMetadata); assertNull(row.completionPolicy)
        assertEquals(appearance("goal.changed"), row.appearance)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
        assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun actualOnceEditorDoesNotOfferRecurringCycleOrDailyReminderAndKeepsProjection() = runBlocking<Unit> {
        val once = insertPlan(habit.copy(id = 0, uuid = id(71), name = "Once", habitType = HabitType.CHECK_IN,
            schedule = HabitSchedule.Once("2026-10-10"), targetValue = 1, targetCycles = null,
            completionPolicy = "one_and_done", oneTimeConfirmedVersion = 0,
            appearance = appearance("task.shopping")))
        val model = withContext(Dispatchers.Main) { own(EditHabitViewModel(editingHabits(), db.habitDao(),
            db.timeLogDao(), db.completionDao(), db.metricDao(), db.habitMetricLinkDao(), preferences, app))
            .also { it.loadHabit(once.id) } }
        val loaded = withTimeout(5000) { model.uiState.first { !it.isLoading } }
        assertFalse(loaded.habitNotFound); assertEquals("one_and_done", loaded.completionPolicy)
        assertEquals(once.appearance!!.accentColor, loaded.colorHex)
        assertEquals(0, loaded.originalScheduleDays)
        withContext(Dispatchers.Main) {
            model.updateSchedule(HabitSchedule.Daily); model.updateTargetCycles(1); model.updateBestTime(600)
            model.updateAppearance(appearance("task.changed")); model.saveChanges()
        }
        val saved = withTimeout(5000) { model.uiState.first { it.saved || it.errorMessage != null } }
        assertNull(saved.errorMessage); assertTrue(saved.saved)
        val row = requireNotNull(db.habitDao().getHabitById(once.id))
        assertEquals(once.schedule, row.schedule); assertNull(row.targetCycles); assertNull(row.bestTime)
        assertEquals(0, row.oneTimeConfirmedVersion); assertEquals(appearance("task.changed"), row.appearance)
        assertNotNull(originalIntent(db.syncOutboxDao().getAll().single()))
        assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun habitBackgroundRefreshCannotEraseUnsavedFieldsOrSilentlyRebindTheirEditTicket() = runBlocking<Unit> {
        val model = withContext(Dispatchers.Main) { own(EditHabitViewModel(editingHabits(), db.habitDao(),
            db.timeLogDao(), db.completionDao(), db.metricDao(), db.habitMetricLinkDao(), preferences, app))
            .also { it.loadHabit(habit.id) } }
        withTimeout(5000) { model.uiState.first { !it.isLoading } }
        val originalAuthority = model.uiState.value.editAuthority
        withContext(Dispatchers.Main) { model.updateName("Unsaved local name") }
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitDao().update(habit.copy(description = "Remote edit"))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        withContext(Dispatchers.Main) { model.saveChanges() }
        val stopped = withTimeout(5000) { model.uiState.first { it.errorMessage != null } }
        assertFalse(stopped.saved); assertEquals("Unsaved local name", stopped.name)
        assertSame(originalAuthority, stopped.editAuthority)
        assertEquals("Remote edit", db.habitDao().getHabitById(habit.id)!!.description)
        assertEquals(habit.name, db.habitDao().getHabitById(habit.id)!!.name)
        assertEquals(0, count("next_request_origins")); assertEquals(0, count("sync_outbox"))
        assertEquals(0, widgetRefresh.requestCount)
    }
}
