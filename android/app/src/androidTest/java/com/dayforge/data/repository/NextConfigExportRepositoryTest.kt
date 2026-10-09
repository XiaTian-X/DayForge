package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.*
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.model.*
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.*
import com.dayforge.domain.service.*
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual authenticated Room -> current once projection -> owned files -> native validated ZIP. */
@RunWith(AndroidJUnit4::class)
class NextConfigExportRepositoryTest : NextObjectEditorFixture() {
    private lateinit var themeController: DeviceThemeController
    private fun exporter() = NextConfigExportRepository(db, tokens, sessions, preferences, icons, themeController)
    @Before fun themes() = runBlocking<Unit> {
        register()
        themeController = DeviceThemeController(DeviceThemeRepository(dataStore, ThemeFileRepository(directory),
            BuiltInThemes(app.assets)), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        themeController.current()
    }
    @After fun closeThemes() = runBlocking<Unit> {
        if (::themeController.isInitialized) themeController.close()
    }
    private suspend fun once(name: String = "One item", parent: String? = null, linked: Boolean = true): Long =
        onceHabits(onceRepository()).createHabit(name, "Preserve description", HabitType.CHECK_IN, 0, "#123456",
            HabitSchedule.Once("2028-02-29"), parentHabitId = parent, failMode = FailMode.LOOSE,
            appearance = ObjectAppearance(IconReference.Role("task.reading"), "#80123456", "object"),
            completionPolicy = "one_and_done", creationAuthority = creator.capture(),
            selectedMetricIds = if (linked) setOf(metric.id) else emptySet())
    private fun durable(): Map<String, List<List<String?>>> {
        val sql = db.openHelper.readableDatabase
        return sql.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { tables ->
            buildMap { while (tables.moveToNext()) {
                val table = tables.getString(0)
                require(table.matches(Regex("[a-zA-Z_][a-zA-Z_0-9]*")))
                put(table, sql.query("SELECT * FROM $table ORDER BY rowid").use { rows ->
                    buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map {
                        when (rows.getType(it)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_BLOB -> rows.getBlob(it).joinToString("") { b -> "%02x".format(b) }
                            else -> "${rows.getType(it)}:${rows.getString(it)}"
                        }
                    }) }
                })
            } }
        }
    }
    private suspend fun bundle(selected: Set<String> = emptySet(), themes: List<ThemeVersionRef> = emptyList()): ConfigBundle {
        val repo = exporter(); val preview = repo.prepare(selected, themes)
        return repo.publish(preview) { it.manifest }
    }

    @Test fun completeGraphKeepsInactivePlansParentsTimersMetricLinksAndNeverExportsHistoryOrAuthority() = runBlocking<Unit> {
        val draft = HabitDraft(id = id(100), name = "Top goal", habitType = HabitType.GOAL,
            appearance = ObjectAppearance(IconReference.Role("goal.custom"), "#123456", "theme"))
        creatingHabits().createGoal(draft, emptyList(), creationAuthority = creator.capture())
        val item = once(parent = draft.id)
        producer().write(local()) {
            db.habitDao().update(habit.copy(isActive = false))
            db.habitMetricLinkDao().update(db.habitMetricLinkDao().getAllLinksForHabit(item).single().copy(isActive = false,
                coefficient = 1.25, showInHabitDetail = false))
        }
        val before = durable(); val selection = themeController.current().saved
        val result = bundle()
        assertEquals(4, result.nodes.size)
        val parent = result.nodes.single { it.name == draft.name }
        assertEquals(parent.key, result.nodes.single { it.name == "One item" }.parentKey)
        assertFalse(result.nodes.single { it.name == habit.name }.isActive)
        assertEquals(60, result.nodes.single { it.name == timerHabit.name }.activity!!.targetValue)
        assertEquals("one_and_done", result.nodes.single { it.name == "One item" }.activity!!.completionPolicy)
        assertEquals(ConfigSchedule.Once("2028-02-29"), result.nodes.single { it.name == "One item" }.activity!!.schedule)
        assertEquals(metric.aggregationType, result.metrics.single().aggregationType)
        assertFalse(result.links.single().isActive); assertFalse(result.links.single().showInDetail)
        assertEquals(1.25, result.links.single().coefficient, 0.0)
        assertTrue(result.themes.isEmpty()); assertNull(result.iconPack)
        assertEquals(before, durable()); assertEquals(selection, themeController.current().saved)
        val json = Json.encodeToString(result)
        for (row in db.habitDao().getAllHabitsOnce()) assertFalse(json.contains(row.uuid))
        for (value in listOf(metric.uuid, id(1), id(2), id(3), id(4), "one_time_state", "created_at", "outbox", "completion_event"))
            assertFalse("Excluded identity/history $value", json.contains(value))
    }

    @Test fun pendingCompletionIsExcludedByDefaultAndExplicitTemplateIncludesItsLinkWithoutChangingFacts() = runBlocking<Unit> {
        val item = once(); onceHabits(onceRepository()).logCompletion(app, item)
        val row = db.habitDao().getHabitById(item)!!
        assertEquals(0, row.oneTimeConfirmedVersion)
        assertTrue(onceRepository().read(item).completed)
        val before = durable()
        val default = bundle(); assertFalse(default.nodes.any { it.name == row.name }); assertTrue(default.links.isEmpty())
        val explicit = bundle(setOf(row.uuid))
        assertTrue(explicit.nodes.any { it.name == row.name }); assertEquals(1, explicit.links.size)
        assertEquals(before, durable())
        assertEquals("CONFIG_TEMPLATE_NOT_AVAILABLE", rejected { bundle(setOf(habit.uuid)) }.message)
        assertEquals("CONFIG_TEMPLATE_NOT_AVAILABLE", rejected { bundle(setOf(id(999))) }.message)
    }

    @Test fun pendingUndoOfConfirmedCompletionUsesRealQueueProjectionNotTheStoredCompletedHeader() = runBlocking<Unit> {
        val item = once(); val repo = onceHabits(onceRepository()); repo.logCompletion(app, item)
        val row = db.habitDao().getHabitById(item)!!
        val queue = db.syncOutboxDao().getActivityIntents(row.uuid).single()
        val (http, _) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input) else successReply(input) { entity ->
                val intent = entity.getValue("one_time").jsonObject
                JsonObject(entity + ("one_time_state_after" to buildJsonObject {
                    put("version", intent.getValue("expected_version").jsonPrimitive.int + 1)
                    put("head_event_uuid", intent.getValue("event_uuid")); put("completion_event_uuid", intent.getValue("event_uuid"))
                }))
            }
        }
        val local = OneTimeLocalIntentStore(db, tokens, sessions, preferences)
        val core = sender(http)
        val timers = NextTimerRequestStore(db, tokens, sessions, core)
        val facts = OneTimeAcceptedEventStore(db, tokens, sessions, local, timerRequests = timers)
        assertTrue(NextOneTimeRequestStore(db, tokens, sessions, http, facts, core)
            .sendAndAccept(access(), queue.operationId) is NextOneTimeOutcome.Accepted)
        assertFalse(bundle().nodes.any { it.name == row.name })
        repo.undoCompletion(app, db.completionDao().getByHabitOnce(item).single().id)
        val confirmed = db.habitDao().getHabitById(item)!!
        assertNotNull(confirmed.oneTimeConfirmedCompletionEventUuid)
        assertFalse(onceRepository().read(item).completed)
        val before = durable()
        assertTrue(bundle().nodes.any { it.name == row.name }); assertEquals(before, durable())
        storage.reopen()
        assertTrue(bundle().nodes.any { it.name == row.name }); assertEquals(before, durable())
    }

    @Test fun completeUndoCompleteOfflineChainAlwaysMatchesDisplayAndKeepsAllOriginalWork() = runBlocking<Unit> {
        val item = once(); val repo = onceHabits(onceRepository())
        repeat(3) { step ->
            if (step == 1) repo.undoCompletion(app, db.completionDao().getByHabitOnce(item).first().id) else repo.logCompletion(app, item)
            val before = durable(); val status = onceRepository().read(item)
            assertEquals(!status.completed, bundle().nodes.any { it.name == "One item" })
            assertEquals(before, durable())
        }
        assertEquals(3, db.completionDao().getByHabitOnce(item).size)
        assertEquals(3, db.syncOutboxDao().getActivityIntents(db.habitDao().getHabitById(item)!!.uuid).size)
    }

    @Test fun retainedDeleteAndSameNameNewIdentityExportOnlyVisibleObjectAndNeverRetainedLinks() = runBlocking<Unit> {
        val item = once(); val row = db.habitDao().getHabitById(item)!!
        editor.deleteHabit(row, null)
        val replacement = once()
        val before = durable()
        val result = bundle()
        assertEquals(1, result.nodes.count { it.name == row.name }); assertEquals(1, result.links.size)
        assertEquals(before, durable())
        assertNotNull(db.habitDao().getHabitById(item)); assertNotEquals(row.uuid, db.habitDao().getHabitById(replacement)!!.uuid)
        assertEquals("CONFIG_TEMPLATE_NOT_AVAILABLE", rejected { bundle(setOf(row.uuid)) }.message)
    }

    @Test fun foreignOrChangedDeleteProofFailsInsteadOfHidingDataOrRepairingTheQueue() = runBlocking<Unit> {
        val item = once(); editor.deleteHabit(db.habitDao().getHabitById(item)!!, null)
        val source = db.syncOutboxDao().getAll().single { it.recordType == "habit" && it.action == "delete" }
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(id(999), NEXT_OPERATION, source.operationId))
        val before = durable()
        assertEquals("CONFIG_DELETE_CONTEXT_CHANGED", rejected { bundle() }.message)
        assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET accountId=? WHERE kind=? AND requestId=?",
            arrayOf(id(1), NEXT_OPERATION, source.operationId))
        db.openHelper.writableDatabase.execSQL("UPDATE sync_outbox SET entityUuid=? WHERE id=?", arrayOf<Any>(id(998), source.id))
        val damaged = durable()
        assertEquals("CONFIG_DELETE_SOURCE_CHANGED", rejected { bundle() }.message)
        assertEquals(damaged, durable())
    }

    @Test fun malformedVisibleLinksAndLegacyRowsFailWithoutPartialExportOrMutation() = runBlocking<Unit> {
        val item = once(); val link = db.habitMetricLinkDao().getAllLinksForHabit(item).single()
        // Deliberate damaged-storage fixture, not a legitimate producer operation.
        db.openHelper.writableDatabase.execSQL("UPDATE habit_metric_links SET habitUuid=? WHERE id=?", arrayOf<Any>(habit.uuid, link.id))
        val before = durable()
        assertEquals("CONFIG_LINK_IDENTITY_CHANGED", rejected { bundle() }.message)
        assertEquals(before, durable())
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            db.habitMetricLinkDao().update(link)
            db.habitDao().update(habit.copy(appearance = null))
            db.openHelper.writableDatabase.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
        }
        val legacy = durable(); rejected { bundle() }; assertEquals(legacy, durable())
    }

    @Test fun differentReplicaCheckpointCannotExportAndRawInvalidCursorNeverCoercesIntoAnOwnedSnapshot() = runBlocking<Unit> {
        db.nextSyncStateDao().insert(NextSyncStateEntity(id(1), id(2), id(999), id(4), 1, 0, "a".repeat(64), "b".repeat(64)))
        val before = durable()
        assertEquals("CONFIG_REPLICA_CHANGED", rejected { bundle() }.message); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_sync_state SET syncEpoch=?,generation=1.5", arrayOf(id(3)))
        val damaged = durable(); rejected { bundle() }; assertEquals(damaged, durable())
    }

    @Test fun themesAreExplicitCompleteDependenciesAndNeverAutomaticallyApplied() = runBlocking<Unit> {
        val original = themeController.current().saved
        assertTrue(bundle().themes.isEmpty())
        val ref = ThemeVersionRef(BuiltInTheme.NATURE.themeId, 1)
        assertEquals(listOf(themeController.export(ref).definition), bundle(themes = listOf(ref)).themes)
        assertEquals(original, themeController.current().saved)
        assertTrue(rejected { bundle(themes = listOf(ref, ref)) } is IllegalArgumentException)
        rejected { bundle(themes = listOf(ThemeVersionRef(id(999), 1))) }
        assertEquals(original, themeController.current().saved)
    }

    @Test fun acceptedChallengeCheckpointExportsDefinitionsButDamagedProfileIsNotGuessedOrRepaired() = runBlocking<Unit> {
        val state = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0, "a".repeat(64), "b".repeat(64), challengeContract = 1)
        db.withTransaction {
            NextChallengeStore(db).mergeInTransaction(access(), null, state,
                com.dayforge.data.api.dto.ChallengeMetadata(1, emptyList(), emptyList()))
            db.nextSyncStateDao().insert(state)
        }
        val before = durable(); assertEquals(2, bundle().nodes.size); assertEquals(before, durable())
        db.openHelper.writableDatabase.execSQL("UPDATE next_challenge_state SET metadataHash=?", arrayOf("f".repeat(64)))
        val damaged = durable(); rejected { bundle() }; assertEquals(damaged, durable())
    }

    @Test fun readOnlyExportAndSameGenerationRefreshSucceedButReauthenticationRejectsLatePublication() = runBlocking<Unit> {
        val item = once(); onceHabits(onceRepository()).logCompletion(app, item)
        sessions.exclusive { tokens.saveDeviceRegistration(id(4), setOf("sync.read"), true, 2) }
        val service = exporter(); val before = durable(); val prepared = service.prepare()
        assertFalse(service.publish(prepared) { it.manifest.nodes.any { node -> node.name == "One item" } })
        sessions.exclusive {
            assertTrue(tokens.saveRefreshedTokens(requireNotNull(tokens.authenticationSnapshot()),
                "synthetic-new-access", "synthetic-new-refresh", "member", id(1), false))
        }
        assertEquals(2, service.publish(prepared) { it.manifest.nodes.size })
        sessions.exclusive { tokens.saveLoginSession("synthetic-next", "synthetic-refresh", "member", id(1), false) }
        assertEquals("ICON_SESSION_CHANGED", rejected { service.publish(prepared) { error("Late publication") } }.message)
        assertEquals(before, durable())
    }

    private class ReadBarrier : IconFileIo() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); private val once = AtomicBoolean(true)
        override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
            if (once.compareAndSet(true, false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            return super.read(fd, bytes, offset, length)
        }
    }
    private suspend fun fixed(barrier: ReadBarrier) {
        val asset = ConfigFileFixture.manifest().iconPack!!.assets.first()
        val context = iconMetadata.capture()
        val normal = AccountIconStore(iconMetadata, AccountIconFiles(directory))
        iconMetadata.reserveAsset(context, asset)
        for (blob in listOfNotNull(asset.light, asset.dark)) normal.install(context, asset.assetId, blob.sha256, ConfigFileFixture.content(blob))
        producer().write(local()) { db.habitDao().update(habit.copy(appearance = ObjectAppearance(
            IconReference.Asset(asset.assetId), "#123456", "theme"))) }
        icons = AccountIconController({
            val store = AccountIconStore(iconMetadata, AccountIconFiles(directory, barrier))
            val imports = AccountIconImport(iconMetadata, store, AccountIconTransfers(iconDatabase, iconMetadata, store))
            AccountIconRuntime(iconMetadata, store, AccountIconRenderer(iconMetadata, store), imports,
                AccountIconDocuments(imports, app.contentResolver), iconDatabase::close)
        }, tokens)
    }

    @Test fun actualFileReadAllowsAtomicBusinessEditsButFrozenExportContainsOnlyTheCapturedGraph() = runBlocking<Unit> {
        val barrier = ReadBarrier(); fixed(barrier)
        val service = exporter(); val work = async(Dispatchers.IO) { service.prepare() }
        try {
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { producer().write(local()) {
                db.habitDao().update(db.habitDao().getHabitById(habit.id)!!.copy(name = "Later count"))
                db.metricDao().update(metric.copy(name = "Later metric"))
            } }
        } finally { barrier.release.countDown() }
        val result = service.publish(work.await()) { it.manifest }
        assertTrue(result.nodes.any { it.name == habit.name }); assertFalse(result.nodes.any { it.name == "Later count" })
        assertEquals(metric.name, result.metrics.single().name)
        assertEquals("Later count", db.habitDao().getHabitById(habit.id)!!.name)
        assertEquals("Later metric", db.metricDao().getMetricById(metric.id)!!.name)
    }

    @Test fun accountChangeDuringFilesAndCancellationCannotPublishPartialOrAdoptedBusinessData() = runBlocking<Unit> {
        val barrier = ReadBarrier(); fixed(barrier)
        val before = durable(); val service = exporter()
        val work = async(Dispatchers.IO) { runCatching { service.prepare() }.exceptionOrNull() }
        try {
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { sessions.exclusive { tokens.saveLoginSession("new-synthetic", "new-refresh", "member", id(9), false) } }
        } finally { barrier.release.countDown() }
        assertEquals("ICON_SESSION_CHANGED", work.await()?.message); assertEquals(before, durable())
        sessions.exclusive { tokens.saveLoginSession("first-synthetic", "refresh", "member", id(1), false); register() }
        val again = ReadBarrier(); fixed(again)
        val cancellationBefore = durable()
        val task = async(Dispatchers.IO) { exporter().prepare() }
        try { assertTrue(again.entered.await(10, TimeUnit.SECONDS)); task.cancel(); assertFalse(task.isCompleted) }
        finally { again.release.countDown() }
        task.join(); assertTrue(task.isCancelled); assertFalse(task.children.any())
        assertEquals(cancellationBefore, durable())
        assertEquals(2, withTimeout(5000) { bundle().nodes.size })
    }
}
