package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.appearance.ConfigBundleOutput
import com.dayforge.data.appearance.ConfigFileFixture
import com.dayforge.data.appearance.ValidatedConfigBundle
import com.dayforge.data.local.entity.NextSyncStateEntity
import com.dayforge.data.appearance.*
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.AccountIconRuntime
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real persistent journal, controller/files and original business producer; no replacement or UI claim. */
@RunWith(AndroidJUnit4::class)
class NextConfigImportRepositoryTest : NextObjectEditorFixture() {
    private fun service() = NextConfigImportRepository(db, tokens, sessions, icons,
        NextObjectCreator(db, tokens, sessions, icons), app)
    private suspend fun source() = ConfigBundleOutput.create(ConfigFileFixture.manifest(), ConfigFileFixture::content)
    private suspend fun fresh() { register(); db.clearAllData() } // Only the isolated testbed's seeded fixture.
    private suspend fun deniedImport(block: suspend () -> Unit): Throwable =
        requireNotNull(runCatching { block() }.exceptionOrNull())

    @Test fun confirmationPersistsBeforeFilesAndColdRecoveryCommitsOriginalIdsExactlyOnceWithoutHistoryOrStyleApply() = runBlocking<Unit> {
        fresh(); val source = source(); val context = icons.capture(); val choice = iconMetadata.selection(context)
        val preview = service().preview(source)
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("habits"))
        val importId = service().confirm(preview)
        val plan = read(importId, source).plan
        assertFalse(read(importId, source).committed); assertTrue(iconMetadata.library(context).packs.isEmpty())
        assertEquals(0, count("sync_outbox")); assertTrue(db.syncOutboxDao().hasProtocolNextRequests())
        assertEquals("CONFIG_IMPORT_PENDING", deniedImport { service().confirm(preview) }.message)
        storage.reopen()
        assertEquals(importId, service().pendingImportId())
        val recoveredSource = ValidatedConfigBundle.parse(source.exportBytes())
        assertEquals(plan.identities, read(importId, recoveredSource).plan.identities)
        val receipt = service().resume(importId, recoveredSource)
        assertEquals(plan.habits.size, receipt.nodes); assertEquals(plan.metrics.size, receipt.metrics)
        val work = db.syncOutboxDao().getAll()
        assertEquals(plan.creationOperationIds.values.toSet(), work.map { it.operationId }.toSet())
        assertTrue(read(importId, recoveredSource).committed)
        assertNull(service().pendingImportId())
        assertEquals(plan.creationOperationIds.values.toSet(), read(importId, recoveredSource).sources.keys)
        val once = db.habitDao().getAllHabitsOnce().single { it.completionPolicy == "one_and_done" }
        assertFalse(OneTimeRepository(db, tokens, sessions, preferences).read(once.id).completed)
        assertEquals(0, count("completions")); assertEquals(0, count("timelogs")); assertEquals(0, count("metric_logs"))
        assertEquals(choice, iconMetadata.selection(context)); assertEquals(1, widgetRefresh.requestCount)
        val habits = db.habitDao().getAllHabitsOnce(); storage.reopen()
        assertEquals(receipt, service().resume(importId, recoveredSource))
        assertEquals(habits, db.habitDao().getAllHabitsOnce()); assertEquals(work, db.syncOutboxDao().getAll())
        assertEquals(1, widgetRefresh.requestCount)
        assertEquals("CONFIG_IMPORT_ALREADY_COMMITTED", deniedImport { service().cancelPrepared(importId, recoveredSource) }.message)
    }

    private suspend fun read(importId: String, source: ValidatedConfigBundle): NextConfigImportStore.Entry {
        val context = icons.capture()
        return sessions.exclusive { db.withTransaction { NextConfigImportStore(db).read(context, importId, source) } }
    }

    @Test fun materialFailureKeepsRealPreparedMappingAndColdRetryUsesSameAssetsAndCreateOperations() = runBlocking<Unit> {
        fresh(); val source = source(); val importId = service().confirm(service().preview(source))
        val plan = read(importId, source).plan
        iconDatabase.openHelper.writableDatabase.execSQL("CREATE TRIGGER config_material_fault BEFORE INSERT ON icon_blob_ready " +
            "BEGIN SELECT RAISE(ABORT,'file phase fault'); END")
        deniedImport { service().resume(importId, source) }
        assertFalse(read(importId, source).committed); assertEquals(0, count("habits")); assertEquals(0, count("next_request_origins"))
        assertEquals(plan.iconPack, iconMetadata.pack(icons.capture(), plan.iconPack!!.packId, 1))
        iconDatabase.openHelper.writableDatabase.execSQL("DROP TRIGGER config_material_fault")
        storage.reopen(); service().resume(importId, source)
        assertEquals(plan.identities, read(importId, source).plan.identities)
        assertEquals(plan.creationOperationIds.values.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
    }

    @Test fun cancellationJoinsActualFileReadWithoutHoldingBusinessLockAndLeavesDiscoverableOriginalMappingForColdRetry() = runBlocking<Unit> {
        fresh()
        val reached = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean(true)
        val fileIo = object : IconFileIo() {
            override fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, length: Int): Int {
                if (once.compareAndSet(true, false)) { reached.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                return super.read(fd, bytes, offset, length)
            }
        }
        icons.close() // Its lazy runtime has not been initialized in this case.
        val store = AccountIconStore(iconMetadata, AccountIconFiles(directory, fileIo))
        val imports = AccountIconImport(iconMetadata, store, AccountIconTransfers(iconDatabase, iconMetadata, store))
        icons = AccountIconController({ AccountIconRuntime(iconMetadata, store, AccountIconRenderer(iconMetadata, store), imports,
            AccountIconDocuments(imports, app.contentResolver), iconDatabase::close) }, tokens)
        val source = source(); val importId = service().confirm(service().preview(source)); val identities = read(importId, source).plan.identities
        val work = async(Dispatchers.IO) { service().resume(importId, source) }
        try {
            assertTrue(reached.await(10, TimeUnit.SECONDS))
            withTimeout(5000) { NextObjectCreator(db, tokens, sessions, icons).capture() }
            assertEquals(importId, withTimeout(5000) { service().pendingImportId() })
            work.cancel(); assertFalse(work.isCompleted)
        } finally { release.countDown(); withTimeout(5000) { work.join() } }
        assertTrue(work.isCancelled); assertFalse(read(importId, source).committed)
        assertEquals(0, count("habits")); assertEquals(0, count("sync_outbox"))
        storage.reopen(); assertEquals(importId, service().pendingImportId())
        assertEquals(identities, read(importId, source).plan.identities)
        service().resume(importId, source); assertTrue(read(importId, source).committed)
    }

    private suspend fun failedBusiness(statement: String, remove: String) {
        fresh(); val source = source(); val importId = service().confirm(service().preview(source))
        val plan = read(importId, source).plan
        db.openHelper.writableDatabase.execSQL(statement)
        deniedImport { service().resume(importId, source) }
        assertFalse(read(importId, source).committed)
        for (table in listOf("habits", "metrics", "habit_metric_links", "sync_outbox", "next_request_origins", "next_structural_dependencies"))
            assertEquals(table, 0, count(table))
        assertEquals(plan.iconPack, iconMetadata.pack(icons.capture(), plan.iconPack!!.packId, 1))
        db.openHelper.writableDatabase.execSQL(remove)
        storage.reopen(); service().resume(importId, source)
        assertTrue(read(importId, source).committed)
        assertEquals(plan.creationOperationIds.values.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
    }

    @Test fun businessFailureRollsBackGraphAndReceiptButNotInstalledMaterialThenReusesSavedMapping() = runBlocking<Unit> {
        failedBusiness("CREATE TRIGGER config_link_fault BEFORE INSERT ON habit_metric_links " +
            "BEGIN SELECT RAISE(ABORT,'business fault'); END", "DROP TRIGGER config_link_fault")
    }

    @Test fun originalProducerFailureAfterLocalReceiptWriteRollsBackThatReceiptToo() = runBlocking<Unit> {
        failedBusiness("CREATE TRIGGER config_origin_fault BEFORE INSERT ON next_request_origins " +
            "BEGIN SELECT RAISE(ABORT,'original intent fault'); END", "DROP TRIGGER config_origin_fault")
    }

    @Test fun deferredFinalCommitFailureRollsBackJournalBusinessAndOriginalIntentsForExactColdRetry() = runBlocking<Unit> {
        fresh(); val source = source(); val importId = service().confirm(service().preview(source))
        val plan = read(importId, source).plan; val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE config_parent(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TABLE config_deferred(id INTEGER, FOREIGN KEY(id) REFERENCES config_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER config_commit_fault AFTER UPDATE ON next_config_imports WHEN NEW.state='local_committed' " +
            "BEGIN INSERT INTO config_deferred VALUES(99); END")
        assertTrue(deniedImport { service().resume(importId, source) } is android.database.sqlite.SQLiteConstraintException)
        // Match the existing real final-COMMIT tests: the framework writer may retain a native
        // transaction after deferred failure. Close first; an old writer/WAL read is not durability proof.
        storage.reopen()
        assertFalse(read(importId, source).committed)
        for (table in listOf("habits", "metrics", "sync_outbox", "next_request_origins", "config_deferred")) assertEquals(0, count(table))
        val reopened = db.openHelper.writableDatabase
        reopened.execSQL("DROP TRIGGER config_commit_fault"); reopened.execSQL("DROP TABLE config_deferred"); reopened.execSQL("DROP TABLE config_parent")
        storage.reopen(); service().resume(importId, source)
        assertEquals(plan.creationOperationIds.values.toSet(), db.syncOutboxDao().getAll().map { it.operationId }.toSet())
    }

    @Test fun stalePreviewReadOnlyOtherAccountAndOtherDeviceCannotAdoptPreparedImportButFreshSameOwnerCanResume() = runBlocking<Unit> {
        fresh(); val source = source(); val preview = service().preview(source); val importId = service().confirm(preview)
        tokens.saveLoginSession("synthetic-again", "synthetic-refresh", "member", id(1), false); register()
        assertEquals("CONFIG_IMPORT_SESSION_CHANGED", deniedImport { service().confirm(preview) }.message)
        register(permissions = setOf("sync.read"))
        assertEquals("CONFIG_IMPORT_ACCESS_DENIED", deniedImport { service().resume(importId, source) }.message)
        tokens.saveLoginSession("synthetic-other", "synthetic-refresh", "other", id(99), false); register()
        assertEquals("CONFIG_IMPORT_TARGET_CHANGED", deniedImport { service().resume(importId, source) }.message)
        tokens.saveLoginSession("synthetic-return", "synthetic-refresh", "member", id(1), false); register(device = id(98))
        assertEquals("CONFIG_IMPORT_TARGET_CHANGED", deniedImport { service().resume(importId, source) }.message)
        register(); assertFalse(read(importId, source).committed); service().resume(importId, source)
        assertTrue(read(importId, source).committed)
    }

    @Test fun changedSourceAndCorruptedJournalAreRejectedWithoutRepairAllocationOrBusinessWrites() = runBlocking<Unit> {
        fresh(); val source = source(); val importId = service().confirm(service().preview(source))
        val changed = ConfigBundleOutput.create(source.manifest.copy(nodes = source.manifest.nodes.mapIndexed { i, node ->
            if (i == 0) node.copy(name = "Different source") else node }), ConfigFileFixture::content)
        assertEquals("CONFIG_IMPORT_SOURCE_CHANGED", deniedImport { service().resume(importId, changed) }.message)
        db.openHelper.writableDatabase.execSQL("UPDATE next_config_import_payloads SET bytes=X'007FFF' WHERE importId=?", arrayOf<Any>(importId))
        deniedImport { service().resume(importId, source) }
        assertEquals(0, count("habits")); assertEquals(0, count("sync_outbox")); assertEquals(1, count("next_config_imports"))
        assertTrue(iconMetadata.library(icons.capture()).packs.isEmpty())
    }

    @Test fun nonemptyReplicaAndBusinessChangesAfterConfirmationAreRefusedWithoutClearingOldData() = runBlocking<Unit> {
        register(); val source = source(); val before = db.habitDao().getAllHabitsOnce()
        assertEquals("CONFIG_IMPORT_REPLACEMENT_NOT_READY", deniedImport { service().preview(source) }.message)
        assertEquals(before, db.habitDao().getAllHabitsOnce()); assertEquals(0, count("next_config_imports"))
        db.clearAllData(); val importId = service().confirm(service().preview(source))
        producer().write(local()) { db.habitDao().insert(habit.copy(id = 0)) }
        val work = db.syncOutboxDao().getAll(); val rows = db.habitDao().getAllHabitsOnce()
        assertEquals("CONFIG_IMPORT_REPLACEMENT_NOT_READY", deniedImport { service().resume(importId, source) }.message)
        assertEquals(work, db.syncOutboxDao().getAll()); assertEquals(rows, db.habitDao().getAllHabitsOnce())
        assertFalse(read(importId, source).committed); assertTrue(iconMetadata.library(icons.capture()).packs.isEmpty())
    }

    @Test fun explicitPreparedCancellationRetainsOwnedMaterialAndDoesNotCancelOrClaimRemoteCommit() = runBlocking<Unit> {
        fresh(); val source = source(); val importId = service().confirm(service().preview(source)); val plan = read(importId, source).plan
        icons.installConfiguration(icons.capture(), plan)
        service().cancelPrepared(importId, source)
        assertEquals(0, count("next_config_imports")); assertEquals(0, count("next_config_import_payloads"))
        assertEquals(plan.iconPack, iconMetadata.pack(icons.capture(), plan.iconPack!!.packId, 1)); assertEquals(0, count("sync_outbox"))
        val nextId = service().confirm(service().preview(source)); assertNotEquals(importId, nextId)
        assertNotEquals(plan.identities, read(nextId, source).plan.identities)
    }

    @Test fun realChunkedMappingSurvivesReopenButGapOrReceiptOriginDamageCannotBeHidden() = runBlocking<Unit> {
        fresh(); val full = ConfigFileFixture.manifest(); val goal = full.nodes.first()
        val many = full.copy(nodes = List(400) { i -> goal.copy(key = "g" + i.toString().padStart(60, '0'), name = "Goal $i") },
            metrics = emptyList(), links = emptyList(), iconPack = null, themes = emptyList(), unresolvedRoles = listOf("goal.default"))
        val source = ConfigBundleOutput.create(many) { error("No material") }
        val importId = service().confirm(service().preview(source)); val plan = read(importId, source).plan
        assertTrue(count("next_config_import_payloads") > 1)
        db.openHelper.writableDatabase.query("SELECT MAX(length(bytes)) FROM next_config_import_payloads").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) <= 65_536)
        }
        storage.reopen(); assertEquals(plan.identities, read(importId, source).plan.identities)
        db.openHelper.writableDatabase.execSQL("DELETE FROM next_config_import_payloads WHERE part=1")
        deniedImport { service().resume(importId, source) }; assertEquals(0, count("habits"))
    }

    @Test fun acceptedChallengeProfileUsesOriginalRoundProducerAndCommittedReceiptRejectsOriginTampering() = runBlocking<Unit> {
        fresh(); val state = NextSyncStateEntity(id(1), id(2), id(3), id(4), 1, 0, "a".repeat(64), "b".repeat(64), challengeContract = 1)
        db.withTransaction {
            NextChallengeStore(db).mergeInTransaction(access(), null, state,
                com.dayforge.data.api.dto.ChallengeMetadata(1, emptyList(), emptyList()))
            db.nextSyncStateDao().insert(state)
        }
        val source = source(); val importId = service().confirm(service().preview(source)); service().resume(importId, source)
        assertTrue(read(importId, source).committed)
        assertEquals(0, count("next_challenge_births")) // No fabricated remote birth/ACK.
        val originals = db.syncOutboxDao().getAll().map { originalIntent(it) }
        assertTrue(originals.all { roundOperationIntent(it.intentJson) != null })
        assertTrue(originals.any { roundOperationIntent(it.intentJson)!!.initialCreation })
        val original = db.syncOutboxDao().getAll().first()
        db.openHelper.writableDatabase.execSQL("UPDATE next_request_origins SET sourceHash=? WHERE requestId=?",
            arrayOf<Any>("f".repeat(64), original.operationId))
        deniedImport { service().resume(importId, source) }
        assertEquals(original, db.syncOutboxDao().getById(original.id))
    }
}
