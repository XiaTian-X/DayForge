package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.model.*
import com.dayforge.domain.model.*
import com.dayforge.ui.screens.createhabit.CreateHabitViewModel
import com.dayforge.ui.screens.creategoal.CreateGoalViewModel
import com.dayforge.ui.screens.createmetric.CreateMetricViewModel
import com.dayforge.ui.screens.createtemptask.CreateTempTaskViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextObjectCreationTest : NextObjectEditorFixture() {
    private suspend fun activateNavigation() {
        register()
        val state = com.dayforge.data.local.entity.NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0,
            "a".repeat(64), "b".repeat(64), challengeContract = 1)
        db.withTransaction {
            NextChallengeStore(db).mergeInTransaction(access(), null, state,
                com.dayforge.data.api.dto.ChallengeMetadata(1, emptyList(), emptyList()))
            db.nextSyncStateDao().insert(state)
        }
    }

    @Test fun formalNavigationRequiresActualChallengeCheckpointAndProducesAllRecurringModes() = runBlocking<Unit> {
        db.clearAllData()
        assertNull(creator.captureForNavigation()) // No unrequested registration/guessing in navigation.
        activateNavigation()
        val authority = requireNotNull(creator.captureForNavigation())
        assertNotNull(authority.rounds)
        for ((n, mode) in listOf(HabitType.CHECK_IN to false, HabitType.COUNTING to false,
            HabitType.COUNTING to true, HabitType.TIMER to false, HabitType.TIMER to true).withIndex()) {
            save(draft(150 + n, mode.first).copy(isCountdown = mode.second), authority)
        }
        assertEquals(5, count("habits")); assertEquals(5, count("next_request_origins"))
        assertTrue(db.syncOutboxDao().getAll().all { roundOperationIntent(originalIntent(it).intentJson)?.initialCreation == true })
        storage.reopen()
        creator = NextObjectCreator(db, tokens, sessions, icons)
        assertNotNull(creator.captureForNavigation()!!.rounds)
    }

    @Test fun formalNavigationRejectsPartialAndPlainV5InsteadOfRenderingLegacyCreateForm() = runBlocking<Unit> {
        rejected { creator.captureForNavigation() } // Initialized shape alone is not activation proof.
        db.clearAllData(); register()
        db.nextSyncStateDao().insert(com.dayforge.data.local.entity.NextSyncStateEntity(id(1), id(2), id(3), id(4),
            1, 0, "a".repeat(64), "b".repeat(64)))
        rejected { creator.captureForNavigation() }
        assertEquals(0, count("habits")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, db.nextSyncStateDao().rows().single().challengeContract)
    }

    @Test fun legacyHabitGoalAndMetricFormsOpenedBeforeBootstrapCannotInsertAfterActivation() = runBlocking<Unit> {
        db.clearAllData(); assertNull(creator.captureForNavigation()); activateNavigation()
        rejected { creatingHabits().createHabit("Late habit", "", HabitType.CHECK_IN, 0, "#123456", HabitSchedule.Daily) }
        rejected { creatingHabits().createGoal(HabitDraft(name = "Late goal", habitType = HabitType.GOAL), emptyList()) }
        rejected { creatingMetrics().createMetric(metric.copy(id = 0, appearance = null)) }
        assertEquals(0, count("habits")); assertEquals(0, count("metrics")); assertEquals(0, count("sync_outbox"))
        assertEquals(1, db.nextSyncStateDao().rows().single().challengeContract)
    }

    @Test fun restoredChallengeGoalRebindsParentTicketBeforeAcceptingChildDraftWithoutLegacyConversion() = runBlocking<Unit> {
        db.clearAllData(); activateNavigation()
        val ticket = creator.captureForNavigation()!!
        val savedState = SavedStateHandle()
        val goal = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), savedState)) }
        withContext(Dispatchers.Main) {
            goal.beginCreation(ticket); goal.updateName("Restored goal"); goal.addChildHabit(draft(169))
        }
        val snapshot: String = requireNotNull(savedState[CreateGoalViewModel.DRAFT_KEY])
        val restored = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(),
            SavedStateHandle(mapOf(CreateGoalViewModel.DRAFT_KEY to snapshot)))) }
        assertNull(restored.uiState.value.creationAuthority)
        val rebound = creator.captureForNavigation()!!
        withContext(Dispatchers.Main) { restored.beginCreation(rebound) }
        assertSame(rebound, restored.uiState.value.creationAuthority)
        assertEquals(goal.uiState.value.children, restored.uiState.value.children)
        assertNull(restored.uiState.value.errorMessage)
        assertEquals(0, count("habits")); assertEquals(0, count("next_request_origins"))
    }
    private fun appearance(role: String) = ObjectAppearance(IconReference.Role(role), "#123456", "object")
    private fun draft(n: Int, type: HabitType = HabitType.CHECK_IN) = HabitDraft(id = id(n),
        name = "New $n", habitType = type, appearance = appearance(if (type == HabitType.GOAL) "goal.custom" else "habit.custom"),
        completionPolicy = if (type == HabitType.GOAL) null else "recurring")
    private suspend fun save(draft: HabitDraft, ticket: ObjectCreationAuthority) = creatingHabits().createHabit(
        draft.name, draft.description, draft.habitType, draft.iconResId, draft.colorHex, draft.schedule,
        draft.targetValue, draft.isCountdown, targetCycles = draft.targetCycles, failMode = draft.failMode,
        bestTime = draft.bestTime, predefinedUuid = draft.id, selectedMetricIds = draft.selectedMetricIds,
        appearance = draft.appearance, completionPolicy = draft.completionPolicy, creationAuthority = ticket)

    @Test fun formOpenedBeforeMidnightFreezesTimeOnlyAtFirstSaveAndKeepsItOnRetry() = runBlocking<Unit> {
        var now = java.time.Instant.parse("2026-10-07T15:59:59Z")
        var reads = 0
        val ticket = ObjectCreationAuthority(local()) { reads++; now to java.time.ZoneId.of("Asia/Shanghai") }
        assertEquals(0, reads)
        now = java.time.Instant.parse("2026-10-07T16:00:01Z")
        val goal = draft(130, HabitType.GOAL)
        val child = draft(131).copy(schedule = HabitSchedule.Custom(3))
        val saved = creatingHabits().createGoal(goal, listOf(child), creationAuthority = ticket)
        assertEquals(1, reads)
        val row = db.habitDao().getHabitByUuid(child.id)!!
        assertEquals("2026-10-08", row.planMetadata!!.startDate)
        assertEquals("2026-10-07T16:00:01Z", row.planMetadata!!.creationTimestamp)
        assertEquals(now.toEpochMilli(), row.createdAt)
        now = now.plusSeconds(86_400)
        assertEquals(saved, creatingHabits().createGoal(goal, listOf(child), creationAuthority = ticket))
        assertEquals(1, reads); assertEquals(2, count("next_request_origins"))
        assertEquals(row, db.habitDao().getHabitByUuid(child.id))
    }

    @Test fun realHabitCreatorsFreezeAllFiveRecurringModesOfflineBeforeRegistration() = runBlocking<Unit> {
        val ticket = creator.capture()
        val types = listOf(HabitType.CHECK_IN to false, HabitType.COUNTING to false, HabitType.COUNTING to true,
            HabitType.TIMER to false, HabitType.TIMER to true)
        for ((index, pair) in types.withIndex()) {
            val model = withContext(Dispatchers.Main) { own(CreateHabitViewModel(creatingHabits(), db.habitDao(),
                db.metricDao(), preferences, app)) }
            withContext(Dispatchers.Main) {
                model.beginCreation(ticket); model.updateName("Real recurring $index")
                model.updateHabitType(pair.first); model.updateIsCountdown(pair.second)
                model.updateSchedule(HabitSchedule.Custom(3)); model.updateBestTime(485)
                model.updateAppearance(appearance("habit.exercise")); model.saveHabit()
            }
            val state = withTimeout(5000) { model.uiState.first { it.savedHabitId != null || it.errorMessage != null } }
            assertNull(state.errorMessage)
            val saved = db.habitDao().getHabitById(requireNotNull(state.savedHabitId))!!
            assertEquals("recurring", saved.completionPolicy); assertEquals(pair.first, saved.habitType)
            assertEquals(pair.second, saved.isCountdown); assertEquals(485L, saved.bestTime)
            assertEquals("08:05", saved.planMetadata!!.preferredLocalTime)
            assertEquals(ticket.timezone.id, saved.planMetadata!!.timezone)
            assertEquals(ticket.instant.atZone(ticket.timezone).toLocalDate().toString(), saved.planMetadata!!.startDate)
            assertEquals(if (pair.first == HabitType.TIMER) "second" else null, saved.planMetadata!!.targetUnit)
            assertEquals(appearance("habit.exercise"), saved.appearance); assertNull(saved.oneTimeConfirmedVersion)
        }
        assertEquals(5, count("sync_outbox")); assertEquals(5, count("next_request_origins")); assertEquals(5, widgetRefresh.requestCount)
        for (row in db.syncOutboxDao().getAll()) {
            val original = originalIntent(row)
            assertNull(original.serverInstanceId); assertNull(original.syncEpoch); assertEquals(id(1), original.accountId)
        }
        storage.reopen()
        assertEquals(7, count("habits")); assertEquals(5, count("next_request_origins"))
    }

    @Test fun actualGoalChildDraftIsReadOnlyUntilOneAtomicGraphSaveAndColdRetry() = runBlocking<Unit> {
        val ticket = creator.capture()
        val handle = SavedStateHandle()
        val goal = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), handle)) }
        val child = withContext(Dispatchers.Main) { own(CreateHabitViewModel(creatingHabits(), db.habitDao(), db.metricDao(), preferences, app)) }
        withContext(Dispatchers.Main) {
            goal.beginCreation(ticket); goal.updateName("Actual graph")
            child.beginCreation(ticket); child.updateName("Actual child"); child.updateHabitType(HabitType.COUNTING)
            child.updateTargetValue(12); child.toggleMetricSelection(metric.id)
            child.saveHabit(onSaveDraft = goal::addChildHabit)
        }
        withTimeout(5000) { child.uiState.first { it.savedDraft } }
        assertEquals(2, count("habits")); assertEquals(0, count("sync_outbox")); assertEquals(0, widgetRefresh.requestCount)
        val snapshot = goal.uiState.value
        assertNotNull(snapshot.children.single().appearance); assertEquals("recurring", snapshot.children.single().completionPolicy)
        withContext(Dispatchers.Main) { goal.saveGoal() }
        val saved = withTimeout(5000) { goal.uiState.first { it.savedGoalId != null || it.errorMessage != null } }
        assertNull(saved.errorMessage)
        assertEquals(4, count("habits")); assertEquals(1, count("habit_metric_links"))
        assertEquals(3, count("sync_outbox")); assertEquals(3, count("next_request_origins")); assertEquals(1, widgetRefresh.requestCount)
        storage.reopen()
        creator = NextObjectCreator(db, tokens, sessions, icons)
        val draftGoal = HabitDraft(id = snapshot.parentUuid, name = snapshot.name, habitType = HabitType.GOAL,
            appearance = snapshot.appearance)
        assertEquals(saved.savedGoalId, creatingHabits().createGoal(draftGoal, snapshot.children, creationAuthority = ticket))
        assertEquals(3, count("next_request_origins"))
        rejected { creatingHabits().createGoal(draftGoal.copy(description = "Not the same operation"), snapshot.children, creationAuthority = ticket) }
        assertEquals(4, count("habits")); assertEquals(3, count("sync_outbox"))
    }

    @Test fun actualOnceCreatorUsesExplicitUnfinishedProjectionNotIcon53OrDailyCycle() = runBlocking<Unit> {
        val ticket = creator.capture()
        val model = withContext(Dispatchers.Main) { own(CreateTempTaskViewModel(creatingHabits(), db.habitDao(), db.metricDao(), app)) }
        withContext(Dispatchers.Main) {
            model.beginCreation(ticket); model.updateName("Retained once")
            model.toggleMetricSelection(metric.id); model.updateAppearance(appearance("task.reading")); model.saveTempTask()
        }
        val state = withTimeout(5000) { model.uiState.first { it.savedHabitId != null || it.errorMessage != null } }
        assertNull(state.errorMessage)
        val saved = db.habitDao().getHabitById(requireNotNull(state.savedHabitId))!!
        assertEquals(HabitType.CHECK_IN, saved.habitType); assertEquals("one_and_done", saved.completionPolicy)
        assertEquals(HabitSchedule.Once(null), saved.schedule); assertNull(saved.targetCycles)
        assertEquals(FailMode.LOOSE, saved.failMode); assertEquals(1, saved.targetValue); assertFalse(saved.isCountdown)
        assertEquals(0, saved.iconResId); assertEquals(0, saved.oneTimeConfirmedVersion)
        assertNull(saved.oneTimeConfirmedCompletionEventUuid); assertNull(saved.bestTime)
        assertEquals(appearance("task.reading"), saved.appearance)
        assertEquals(0, count("completions")); assertEquals(2, count("next_request_origins")); assertEquals(1, widgetRefresh.requestCount)
        storage.reopen(); assertEquals(saved, db.habitDao().getHabitById(saved.id))
    }

    @Test fun realMetricCreationKeepsRangeAggregationAndLinksThroughHttpAcceptance() = runBlocking<Unit> {
        register()
        val ticket = creator.capture()
        val model = withContext(Dispatchers.Main) { own(CreateMetricViewModel(app, creatingMetrics(), db.metricDao(), db.habitDao())) }
        withContext(Dispatchers.Main) {
            model.beginCreation(ticket); model.updateName("New metric"); model.updateUnit("kg")
            model.updateAggregationType("sum"); model.updateDecimalPlaces(2); model.updateTargetDirection("range")
            model.updateTargetValueInput("1.25"); model.updateTargetValueUpperInput("9.75")
            model.toggleHabitSelection(habit.id); model.updateAppearance(appearance("metric.weight")); model.saveMetric()
        }
        val state = withTimeout(5000) { model.uiState.first { it.savedMetricId != null || it.errorMessage != null } }
        assertNull(state.errorMessage)
        val saved = db.metricDao().getMetricById(requireNotNull(state.savedMetricId))!!
        assertEquals("sum", saved.aggregationType); assertEquals("range", saved.targetDirection)
        assertEquals(1.25, saved.targetValue!!, 0.0); assertEquals(9.75, saved.targetValueUpper!!, 0.0)
        assertEquals(2, saved.decimalPlaces); assertEquals(ticket.instant.toEpochMilli(), saved.createdAt)
        assertEquals(appearance("metric.weight"), saved.appearance)
        assertEquals(2, count("next_request_origins"))
        val (http, server) = channel { successReply(it) }
        val row = db.syncOutboxDao().getAll().first { it.recordType == "metric" }
        assertNotNull(sender(http).sendAndAcceptOperation(access(), row.operationId))
        assertEquals(1, server.requests.count { it.path.endsWith("/push") })
        storage.reopen()
        val accepted = db.metricDao().getMetricById(saved.id)!!
        // Metric creation time is server-owned in v5 (unlike plan creation_timestamp).
        assertEquals(saved.copy(createdAt = millis + 1000, updatedAt = millis + 1000), accepted)
        assertNotNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
    }

    @Test fun lateGraphOriginFailureRollsBackEverythingAndExactDraftRetryWorks() = runBlocking<Unit> {
        val ticket = creator.capture()
        val goal = draft(100, HabitType.GOAL)
        val children = listOf(draft(101), draft(102).copy(selectedMetricIds = setOf(metric.id)))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_graph_origin BEFORE INSERT ON next_request_origins WHEN NEW.queueId > 1 BEGIN SELECT RAISE(ABORT, 'synthetic late origin'); END")
        rejected { creatingHabits().createGoal(goal, children, app, ticket) }
        assertEquals(2, count("habits")); assertEquals(0, count("habit_metric_links")); assertEquals(0, count("next_request_origins"))
        assertEquals(0, count("sync_outbox")); assertEquals(0, widgetRefresh.requestCount)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_graph_origin")
        creatingHabits().createGoal(goal, children, app, ticket)
        assertEquals(5, count("habits")); assertEquals(4, count("next_request_origins")); assertEquals(1, widgetRefresh.requestCount)
    }

    @Test fun staleAccountAndRevokedPermissionsCannotCreateOrAdoptSavedGoalDraft() = runBlocking<Unit> {
        val ticket = creator.capture()
        val handle = SavedStateHandle()
        val goal = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), handle)) }
        withContext(Dispatchers.Main) { goal.beginCreation(ticket); goal.updateName("Bound draft") }
        sessions.exclusive { tokens.saveLoginSession("synthetic-second", "synthetic-refresh", "member", id(1), false) }
        rejected { save(draft(110), ticket) }
        val restored = withContext(Dispatchers.Main) { own(CreateGoalViewModel(app, creatingHabits(), handle)) }
        val fresh = creator.capture()
        withContext(Dispatchers.Main) { restored.beginCreation(fresh) }
        assertNull(restored.uiState.value.creationAuthority)
        assertTrue(restored.uiState.value.errorMessage!!.contains("OBJECT_CREATE_DRAFT_EXPIRED"))
        register(permissions = setOf("sync.read", "facts.append"))
        rejected { save(draft(111), fresh) }; rejected { creator.capture() }
        assertEquals(2, count("habits")); assertEquals(0, count("sync_outbox")); assertEquals(0, count("next_request_origins"))
    }

    @Test fun taskPurposeAndMixedLegacyGraphAreRejectedWithoutWrites() = runBlocking<Unit> {
        val ticket = creator.capture()
        rejected { save(draft(120).copy(appearance = appearance("task.not_a_habit")), ticket) }
        rejected { save(draft(121).copy(appearance = ObjectAppearance(IconReference.Asset(id(122)), "#123456", "theme")), ticket) }
        rejected { creatingHabits().createGoal(draft(123, HabitType.GOAL), listOf(HabitDraft(name = "Legacy child", habitType = HabitType.CHECK_IN)), creationAuthority = ticket) }
        assertEquals(2, count("habits")); assertEquals(0, count("next_request_origins"))
        rejected { creatingHabits().createHabit("No admission", "", HabitType.CHECK_IN, 53, "#123456",
            HabitSchedule.Once(null), appearance = appearance("task.read"), completionPolicy = "one_and_done") }
        assertEquals(0, count("sync_outbox"))
    }
}
