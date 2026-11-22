package com.dayforge.ui.screens.settings

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.api.dto.DeviceResponse
import com.dayforge.data.api.dto.TimerStatusResponse
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.local.*
import com.dayforge.data.local.entity.*
import com.dayforge.data.model.*
import com.dayforge.domain.service.SyncManager
import com.dayforge.widget.IsolatedWidgetRefreshRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Real Hilt Host/Manager/legacy repos and original controlled SQL. The queue fixtures below
 * are existing rejected work; they do not claim to produce or bypass a timer state machine.
 * HTTP is real loopback, and every local-only case asserts no request escaped its row gate. */
@HiltAndroidTest @RunWith(AndroidJUnit4::class)
class SettingsSyncProblemHostPhysicalTest {
    private val native = RetryWriteProbe()
    @get:Rule(order = 0) val timeout = Timeout.seconds(150)
    @get:Rule(order = 1) val widgets = IsolatedWidgetRefreshRule()
    @get:Rule(order = 2) val epochs = PhysicalDatabaseEpochFixture().apply { nativeFactory = native }
    @get:Rule(order = 3) val hilt = HiltAndroidRule(this)
    @Inject lateinit var manager: SyncManager
    @Inject lateinit var preferences: PreferencesManager
    private val codec = Json { encodeDefaults = true }
    private lateinit var server: MaterialSocketServer
    private lateinit var originalUrls: Triple<String?, String?, String?>
    private val networkAllowed = AtomicBoolean()
    private val statusEntered = CountDownLatch(1)
    private val statusRelease = CountDownLatch(1)
    private val pauseFirstStatus = AtomicBoolean()
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun id(n: Int) = "dc190000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
    private val caps = setOf("sync.read", "structure.write", "facts.append", "timer.control")
    @Before fun setup() = runBlocking<Unit> {
        hilt.inject(); epochs.connect()
        originalUrls = Triple(preferences.serverUrl.first(), preferences.remoteServerUrl.first(), preferences.activeServerUrl.first())
        server = MaterialSocketServer { input, _ ->
            if (!networkAllowed.get()) MaterialSocketServer.Reply("Unexpected network".toByteArray(), 500)
            else when {
                input.method == "POST" && input.path == "/api/v2/devices/register" -> {
                    val request = codec.decodeFromString<DeviceRegisterRequest>(input.body.toString(Charsets.UTF_8))
                    MaterialSocketServer.Reply(codec.encodeToString(DeviceResponse(id(90), request.installationId,
                        "android", isPrimaryEditor = true, capabilityRevision = 1, capabilities = caps.toList())).toByteArray())
                }
                input.method == "GET" && input.path == "/api/v2/timers/${id(30)}" -> {
                    if (pauseFirstStatus.compareAndSet(true, false)) {
                        statusEntered.countDown()
                        check(statusRelease.await(5, TimeUnit.SECONDS)) { "Status response release timed out" }
                    }
                    MaterialSocketServer.Reply(codec.encodeToString(TimerStatusResponse(null, "2026-10-25T00:00:00Z")).toByteArray())
                }
                else -> MaterialSocketServer.Reply("Unexpected route".toByteArray(), 500)
            }
        }
        // The actual Hilt Retrofit interceptors are retained, but no failure can reach a NAS.
        preferences.setServerUrl(server.origin.toString()); preferences.setRemoteServerUrl(null)
        preferences.setActiveServerUrl(server.origin.toString())
    }
    @After fun teardown() = runBlocking<Unit> {
        statusRelease.countDown()
        var failure: Throwable? = null
        suspend fun finish(block: suspend () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        finish { withTimeout(5_000) { tasks.coroutineContext[Job]!!.cancelAndJoin() } }
        if (::server.isInitialized) {
            finish { server.close() }
            finish { if (!networkAllowed.get()) assertTrue("A local row gate reached HTTP", server.requests.isEmpty()) }
        }
        if (::originalUrls.isInitialized) finish {
            preferences.setServerUrl(originalUrls.first); preferences.setRemoteServerUrl(originalUrls.second)
            preferences.setActiveServerUrl(originalUrls.third)
        }
        failure?.let { throw it }
    }
    private fun change(salt: Int = 0) = SyncOutboxEntity(id = 101, operationId = id(10 + salt), recordType = "habit",
        entityUuid = id(20 + salt), wireEntityUuid = id(20 + salt), action = "upsert", payloadJson = "{}",
        baseRevision = 1, basePayloadJson = "{}", attemptedAt = 900, attemptCount = 2,
        lastError = "original", errorCode = "REJECTED", deadLetteredAt = 1_000, createdAt = 800)
    private fun conflict(salt: Int = 0, key: Long = 102) = SyncConflictEntity(id = key, operationId = id(40 + salt),
        recordType = "habit", localEntityUuid = id(80 + salt), wireEntityUuid = id(80 + salt),
        entityType = "plan_node", action = "delete", referenceUuid = null, baseRevision = 1,
        serverRevision = 2, basePayloadJson = "{}", localPayloadJson = "{}", serverPayloadJson = "{}",
        conflictingFieldsJson = "[\"title\"]", conflictKind = "field", errorCode = "CONFLICT",
        message = "original", createdAt = 1_000)
    private fun timer(salt: Int = 0) = TimerCommandEntity(id = 103, commandId = id(31 + salt), sessionUuid = id(30 + salt),
        sequence = 2, commandType = "pause", occurredAt = 1_000, expectedControlGeneration = 1,
        expectedRevision = 2, attemptCount = 2, lastError = "original", errorCode = "CONFLICT", deadLetteredAt = 1_000,
        createdAt = 800)
    private suspend fun seed(salt: Int = 0) = epochs.current { graph -> graph.database.withTransaction {
        graph.database.syncOutboxDao().insert(change(salt)); graph.database.syncConflictDao().insert(conflict(salt))
        graph.timeLogDao.insertTimerCommand(timer(salt))
    } }
    private suspend fun initialize() { epochs.login(); seed() }
    private suspend fun frame(): SyncProblemFrame = manager.readProblemFrame().also {
        assertTrue(it.isCurrent()); assertNotNull((it.value as SyncProblems.Legacy).session)
    }
    private fun SyncProblemFrame.rows() = value as SyncProblems.Legacy
    private suspend fun refused(code: String, block: suspend () -> Unit) {
        val before = epochs.exactMain(); val authority = epochs.tokens.localCoreWriteAccess()
        val bootstrapped = epochs.tokens.isSyncBootstrapped.first(); val requests = server.requests.size
        val failure = try { block(); null } catch (error: Exception) { error }
        assertNotNull("Expected $code", failure)
        assertTrue("Expected $code, received $failure", generateSequence<Throwable>(failure) { it.cause }.any { it.message == code })
        assertEquals(before, epochs.exactMain()); assertEquals(authority, epochs.tokens.localCoreWriteAccess())
        assertEquals(bootstrapped, epochs.tokens.isSyncBootstrapped.first()); assertEquals(requests, server.requests.size)
    }
    private suspend fun mutate(sql: String) = epochs.current { graph -> graph.database.withTransaction {
        graph.database.openHelper.writableDatabase.execSQL(sql)
    } }
    private fun storageId(proof: PhysicalDatabaseMainProof) = proof.contents.getValue("tables").jsonObject
        .getValue("count_trust_control").jsonArray.single().jsonObject.getValue("storageId").jsonObject.getValue("value").jsonPrimitive.content
    private fun onlyChanged(before: PhysicalDatabaseMainProof, after: PhysicalDatabaseMainProof, vararg expected: String) {
        assertNotEquals(before.hash, after.hash)
        assertEquals(before.contents["schema"], after.contents["schema"])
        assertEquals(before.contents["user_version"], after.contents["user_version"])
        val a = before.contents.getValue("tables").jsonObject; val b = after.contents.getValue("tables").jsonObject
        assertEquals(a.keys, b.keys)
        val changed = a.keys.filter { a[it] != b[it] }.toSet()
        assertTrue("Unexpected changed tables: $changed", changed.all { it in expected.toSet() + "count_trust_control" })
        val old = a.getValue("count_trust_control").jsonArray.single().jsonObject
        val new = b.getValue("count_trust_control").jsonArray.single().jsonObject
        assertEquals(old.filterKeys { it !in setOf("generation", "dirty") }, new.filterKeys { it !in setOf("generation", "dirty") })
        assertEquals(1L, new.getValue("dirty").jsonObject.getValue("value").jsonPrimitive.long)
    }
    private suspend fun requireRetry(original: SyncOutboxEntity) = epochs.current { graph ->
        val saved = requireNotNull(graph.database.syncOutboxDao().getById(original.id))
        assertNotEquals(original.operationId, saved.operationId); UUID.fromString(saved.operationId)
        assertEquals(original.copy(operationId = saved.operationId, wireEntityUuid = original.entityUuid,
            payloadJson = null, baseRevision = null, basePayloadJson = null, attemptedAt = null,
            errorCode = null, lastError = null, deadLetteredAt = null), saved)
    }

    @Test fun everyOldLongProblemApiRejectsBeforeColdHostCreatesAnyDatabase() = runBlocking<Unit> {
        assertEquals(0, epochs.realRoomCreates)
        val calls: List<suspend () -> Unit> = listOf(
            { manager.retryRejectedChange(101) }, { manager.discardRejectedChange(101) },
            { manager.resolveConflictUseServer(102) }, { manager.resolveConflictUseLocal(102) },
            { manager.retryRejectedTimerCommand(103) }, { manager.cancelRejectedTimerCommandAndUseServer(103) })
        for (call in calls) {
            val failure = try { call(); null } catch (error: Exception) { error }
            assertEquals("SYNC_PROBLEM_ORIGINAL_INPUT_REQUIRED", failure?.message)
            assertEquals(0, epochs.realRoomCreates)
        }
        assertTrue(epochs.createdRoomInspection().isEmpty()); assertTrue(server.requests.isEmpty())
    }

    @Test fun oldFrameCannotTargetReusedIdsAfterRealClearButFreshFrameMutatesOriginalNewRow() = runBlocking<Unit> {
        initialize(); val old = frame(); val rows = old.rows()
        val previousStorage = storageId(epochs.exactMain())
        epochs.clearAndAwaitReplacement(); seed(100)
        assertNotEquals(previousStorage, storageId(epochs.exactMain()))
        assertFalse(old.isCurrent()); var published = false
        assertFalse(old.publish { published = true }); assertFalse(published)
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.retryRejectedChange(old, rows.changes.single()) }
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.discardRejectedChange(old, rows.changes.single()) }
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.resolveConflictUseServer(old, rows.conflicts.single()) }
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.resolveConflictUseLocal(old, rows.conflicts.single()) }
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.retryRejectedTimerCommand(old, rows.timers.single()) }
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.cancelRejectedTimerCommandAndUseServer(old, rows.timers.single()) }
        val current = frame(); val row = current.rows().changes.single(); assertEquals(101L, row.id)
        val before = epochs.exactMain(); manager.retryRejectedChange(current, row); requireRetry(row)
        onlyChanged(before, epochs.exactMain(), "sync_outbox")
    }

    @Test fun fullOutboxRowAndOriginalMemberIdentityAreRequiredForRetryAndDiscard() = runBlocking<Unit> {
        initialize(); epochs.tokens.saveSyncCursor(7); val old = frame(); val row = old.rows().changes.single()
        refused("SYNC_PROBLEM_INPUT_CHANGED") { manager.retryRejectedChange(old, row.copy()) }
        mutate("UPDATE sync_outbox SET lastError='changed after capture' WHERE id=101")
        assertTrue(old.isCurrent()) // Exercise full-row equality, not an expired graph shortcut.
        refused("SYNC_PROBLEM_CHANGED") { manager.retryRejectedChange(old, row) }
        refused("SYNC_PROBLEM_CHANGED") { manager.discardRejectedChange(old, row) }
        val fresh = frame(); val retry = fresh.rows().changes.single(); val before = epochs.exactMain()
        manager.retryRejectedChange(fresh, retry); requireRetry(retry); onlyChanged(before, epochs.exactMain(), "sync_outbox")
        epochs.current { it.database.syncOutboxDao().markDeadLetter(101, "AGAIN", "new rejection", 2_000) }
        val discard = frame(); val discarded = discard.rows().changes.single(); val beforeDiscard = epochs.exactMain()
        assertTrue(epochs.tokens.isSyncBootstrapped.first())
        manager.discardRejectedChange(discard, discarded)
        assertFalse(epochs.tokens.isSyncBootstrapped.first())
        epochs.current { assertNull(it.database.syncOutboxDao().getById(101)) }
        onlyChanged(beforeDiscard, epochs.exactMain(), "sync_outbox")
    }

    @Test fun rewrittenConflictCannotBeResolvedByOldFrameAndFreshServerAndLocalPathsRemainAtomic() = runBlocking<Unit> {
        initialize(); val old = frame(); val row = old.rows().conflicts.single()
        mutate("UPDATE sync_conflicts SET message='changed after capture' WHERE id=102")
        assertTrue(old.isCurrent())
        refused("SYNC_PROBLEM_CHANGED") { manager.resolveConflictUseServer(old, row) }
        refused("SYNC_PROBLEM_CHANGED") { manager.resolveConflictUseLocal(old, row) }
        val serverFrame = frame(); val original = serverFrame.rows().conflicts.single(); val before = epochs.exactMain()
        manager.resolveConflictUseServer(serverFrame, original)
        epochs.current { graph ->
            assertNull(graph.database.syncConflictDao().getUnresolvedById(original.id))
            graph.database.openHelper.readableDatabase.query("SELECT resolution,resolvedAt FROM sync_conflicts WHERE id=102").use {
                assertTrue(it.moveToFirst()); assertEquals("server", it.getString(0)); assertFalse(it.isNull(1)); assertFalse(it.moveToNext())
            }
        }
        onlyChanged(before, epochs.exactMain(), "sync_conflicts")
        epochs.current { graph -> graph.database.withTransaction {
            val sql = graph.database.openHelper.writableDatabase
            sql.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            graph.habitDao.insert(HabitEntity(id = 200, uuid = id(81), name = "Original local conflict target",
                habitType = HabitType.CHECK_IN, iconResId = 0, colorHex = "#123456", schedule = HabitSchedule.Daily))
            sql.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            graph.database.syncConflictDao().insert(conflict(1, 104))
        } }
        val localFrame = frame(); val local = localFrame.rows().conflicts.single(); val beforeLocal = epochs.exactMain()
        manager.resolveConflictUseLocal(localFrame, local)
        epochs.current { graph ->
            assertNull(graph.habitDao.getHabitByUuid(local.localEntityUuid))
            assertNull(graph.database.syncConflictDao().getUnresolvedById(local.id))
            val queued = graph.database.syncOutboxDao().getAll().single()
            assertEquals(local.localEntityUuid, queued.entityUuid); assertEquals(local.wireEntityUuid, queued.wireEntityUuid)
            assertEquals("delete", queued.action); assertEquals(local.serverRevision, queued.baseRevision)
            assertEquals(local.localPayloadJson, queued.payloadJson)
            assertEquals(local.serverPayloadJson, graph.database.syncOutboxDao().getState(local.entityType, local.wireEntityUuid)?.payloadJson)
        }
        onlyChanged(beforeLocal, epochs.exactMain(), "habits", "sync_conflicts", "sync_outbox", "sync_entity_state", "sqlite_sequence")
    }

    @Test fun rewrittenTimerRowRejectsBothOldActionsBeforeHttpAndFreshRetryUsesItsExactSession() = runBlocking<Unit> {
        initialize(); val old = frame(); val row = old.rows().timers.single()
        mutate("UPDATE timer_command_outbox SET lastError='changed after capture' WHERE id=103")
        assertTrue(old.isCurrent())
        refused("SYNC_PROBLEM_CHANGED") { manager.retryRejectedTimerCommand(old, row) }
        refused("SYNC_PROBLEM_CHANGED") { manager.cancelRejectedTimerCommandAndUseServer(old, row) }
        val current = frame(); val original = current.rows().timers.single(); val before = epochs.exactMain()
        manager.retryRejectedTimerCommand(current, original)
        epochs.current { graph ->
            val saved = requireNotNull(graph.timeLogDao.getTimerCommand(original.id))
            assertNotEquals(original.commandId, saved.commandId); UUID.fromString(saved.commandId)
            assertEquals(original.copy(commandId = saved.commandId, attemptCount = 0, lastError = null,
                errorCode = null, deadLetteredAt = null), saved)
        }
        onlyChanged(before, epochs.exactMain(), "timer_command_outbox")
    }

    @Test fun sameDatabaseReloginRevokesOldFrameAndCannotPublishItsRowsUnderNewAuthority() = runBlocking<Unit> {
        initialize(); val old = frame(); val row = old.rows().changes.single(); val database = epochs.current { it.database }
        epochs.login(); epochs.current { assertSame(database, it.database) }
        assertFalse(old.isCurrent()); var published = false
        assertFalse(old.withSourceFence { old.publish { published = true } }); assertFalse(published)
        refused("DATABASE_EPOCH_INPUT_EXPIRED") { manager.retryRejectedChange(old, row) }
        val fresh = frame(); val freshRow = fresh.rows().changes.single(); val before = epochs.exactMain()
        manager.retryRejectedChange(fresh, freshRow); requireRetry(freshRow); onlyChanged(before, epochs.exactMain(), "sync_outbox")
    }

    @Test fun actualRetryUpdateFailureRollsBackEveryNativeValueBeforeFreshRetryCommits() = runBlocking<Unit> {
        initialize(); val current = frame(); val row = current.rows().changes.single(); val before = epochs.exactMain()
        native.failAfterRetry.set(true)
        refused(RETRY_FAULT) { manager.retryRejectedChange(current, row) }
        assertEquals(1, native.retryWritesBeforeFault.get()); assertFalse(native.failAfterRetry.get())
        assertNotEquals(row.operationId, requireNotNull(native.operationAfterNativeWrite.get()))
        assertEquals(before, epochs.exactMain())
        val fresh = frame(); manager.retryRejectedChange(fresh, fresh.rows().changes.single()); requireRetry(row)
        onlyChanged(before, epochs.exactMain(), "sync_outbox")
    }

    @Test fun realTimerHttpGapRechecksFullRowAndFreshStatusResolutionUsesOriginalTransaction() = runBlocking<Unit> {
        initialize()
        epochs.sessions.exclusive { epochs.tokens.saveDeviceRegistration(id(90), caps, true, 1); epochs.tokens.getOrCreateInstallationId() }
        val original = frame(); val row = original.rows().timers.single()
        networkAllowed.set(true); pauseFirstStatus.set(true)
        val attempt = tasks.async { manager.cancelRejectedTimerCommandAndUseServer(original, row) }
        try {
            withContext(Dispatchers.IO) { assertTrue(statusEntered.await(5, TimeUnit.SECONDS)) }
            assertTrue("Same registration must not re-sign or revoke the original frame", original.isCurrent())
            // Deliberate controlled fault injection: normal users retain the account lock.
            // HTTP holds no Room writer; the injected row replacement enters a real native TX.
            mutate("UPDATE timer_command_outbox SET lastError='changed during HTTP' WHERE id=103")
            val afterInjection = epochs.exactMain(); statusRelease.countDown()
            val failure = try { withTimeout(5_000) { attempt.await() }; null } catch (error: Exception) { error }
            assertTrue("Expected exact original-row refusal: $failure",
                generateSequence<Throwable>(failure) { it.cause }.any { it.message == "SYNC_PROBLEM_CHANGED" })
            assertEquals(afterInjection, epochs.exactMain())
            assertEquals(listOf("/api/v2/devices/register", "/api/v2/timers/${row.sessionUuid}"), server.requests.map { it.path })
            val current = frame(); val fresh = current.rows().timers.single(); val before = epochs.exactMain()
            manager.cancelRejectedTimerCommandAndUseServer(current, fresh)
            epochs.current { assertNull(it.timeLogDao.getTimerCommand(fresh.id)) }
            onlyChanged(before, epochs.exactMain(), "timer_command_outbox")
            assertEquals(4, server.requests.size)
            assertEquals(listOf("/api/v2/devices/register", "/api/v2/timers/${row.sessionUuid}"), server.requests.takeLast(2).map { it.path })
            assertTrue(server.requests.filter { it.method == "GET" }.all { it.target.contains("device_id=${id(90)}") })
        } finally { statusRelease.countDown(); attempt.cancel(); withTimeout(5_000) { attempt.join() } }
    }

    private class RetryWriteProbe : SupportSQLiteOpenHelper.Factory {
        val failAfterRetry = AtomicBoolean(); val retryWritesBeforeFault = AtomicInteger()
        val operationAfterNativeWrite = AtomicReference<String?>()
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            var selected: SupportSQLiteDatabase? = null; var sealed: SupportSQLiteDatabase? = null; val monitor = Any()
            fun wrap(raw: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(monitor) {
                if (selected !== raw) { selected = raw; sealed = object : SupportSQLiteDatabase by raw {
                    override fun compileStatement(sql: String): SupportSQLiteStatement {
                        val statement = raw.compileStatement(sql)
                        val retry = sql.trim().replace(Regex("\\s+"), " ").startsWith("UPDATE sync_outbox SET operationId =")
                        fun afterActualExecution() {
                            if (!retry || !failAfterRetry.compareAndSet(true, false)) return
                            // Room 2.8's compatibility statement.step() uses native execute().
                            // Prove the fixed original UPDATE changed its actual row in this
                            // SAME native TX; statement compilation is not write evidence.
                            check(raw.inTransaction())
                            raw.query("SELECT changes()").use { cursor ->
                                check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                                    cursor.getLong(0) == 1L && !cursor.moveToNext())
                            }
                            raw.query("SELECT id,operationId,deadLetteredAt FROM sync_outbox WHERE id=101").use { cursor ->
                                check(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER &&
                                    cursor.getLong(0) == 101L && cursor.getType(1) == Cursor.FIELD_TYPE_STRING &&
                                    cursor.getType(2) == Cursor.FIELD_TYPE_NULL)
                                val changedOperation = cursor.getString(1); UUID.fromString(changedOperation)
                                operationAfterNativeWrite.set(changedOperation); check(!cursor.moveToNext())
                            }
                            retryWritesBeforeFault.incrementAndGet(); throw SQLiteConstraintException(RETRY_FAULT)
                        }
                        return object : SupportSQLiteStatement by statement {
                            override fun execute() { statement.execute(); afterActualExecution() }
                            override fun executeUpdateDelete(): Int {
                                val changed = statement.executeUpdateDelete(); afterActualExecution(); return changed
                            }
                        }
                    }
                } }
                requireNotNull(sealed)
            }
            val parent = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(parent.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = parent.onConfigure(wrap(db))
                override fun onCreate(db: SupportSQLiteDatabase) = parent.onCreate(wrap(db))
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onUpgrade(wrap(db), oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = parent.onDowngrade(wrap(db), oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = parent.onOpen(wrap(db))
                override fun onCorruption(db: SupportSQLiteDatabase) = parent.onCorruption(wrap(db))
            }
            val actual = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name).callback(callback).noBackupDirectory(configuration.useNoBackupDirectory).build())
            return object : SupportSQLiteOpenHelper by actual {
                override val readableDatabase get() = wrap(actual.readableDatabase)
                override val writableDatabase get() = wrap(actual.writableDatabase)
            }
        }
    }
    private companion object { const val RETRY_FAULT = "SETTINGS_RETRY_AFTER_NATIVE_UPDATE" }
}
