package com.dayforge.data.repository

import com.dayforge.data.history.HistoryBlockCodec
import com.dayforge.data.history.AuthenticatedHistoryDirectory

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.NextReplicaFenceSql
import com.dayforge.data.local.SyncSchemaCallback
import com.dayforge.data.history.HistoryBlockCodec.Bytes
import com.dayforge.data.history.HistoryBlockCodec.Ref
import com.dayforge.data.history.HistoryBlockPlanner.LeafRange
import com.dayforge.data.history.HistoryBlockPlanner.Partition
import com.dayforge.data.history.HistoryBlockPlanner.Scope
import com.dayforge.data.history.HistoryBlockPlanner.UnitKey
import com.dayforge.domain.model.CountHistory
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real original-produced facts with the explicit B2 P/Q transaction profile. These tests inspect
 * unsigned persisted Q independently; that observation never issues a cold/read/write capability.
 * Reopened owners still run the original full validator and cannot use disk Q as cold credentials.
 */
@RunWith(AndroidJUnit4::class)
class G0CountIncrementalStoreSafetyTest : G0HouseholdCountFixture() {
    override fun domainOpeningCallback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SyncSchemaCallback.onCreate(db)
            db.execSQL(G0CountBlockSql.createNodes)
            db.execSQL(G0CountBlockSql.createRoot)
            NextReplicaFenceSql.install(db)
        }
        override fun onOpen(db: SupportSQLiteDatabase) = SyncSchemaCallback.onOpen(db)
    }

    private suspend fun independentSchema(): List<List<String?>> {
        val reference = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java)
            .addCallback(domainOpeningCallback()).build()
        return try {
            reference.openHelper.writableDatabase
            reference.withTransaction { G0DomainJournal.trustedSchema(reference.openHelper.writableDatabase) }
        } finally { reference.close() }
    }

    private suspend fun openIncremental(http: NextSyncHttp, install: Boolean = true): G0HouseholdCountStore {
        val schema = independentSchema()
        freshGateForColdOpen(recurringReads = true, countWrites = true,
            householdCountWrites = true, countBlockScope = true)
        storage.reopen()
        val expected = currentHousehold.map { requireNotNull(db.habitDao().getHabitById(it.id)) }
        return G0HouseholdCountStore(db, tokens, sessions, authority, domainGate, sender(http),
            jointAuthority = true, incrementalBlockScope = true).also {
            it.initialize(expected, schema)
            if (install) it.installIncrementalCountBlocks()
        }
    }

    private suspend fun blockDay(store: G0HouseholdCountStore, uuid: String,
        date: LocalDate): G0HouseholdCountStore.IncrementalView {
        var view: G0HouseholdCountStore.IncrementalView? = null
        store.readIncrementalCountDay(uuid, date) { view = it }
        return requireNotNull(view)
    }
    private suspend fun rejectedUnchanged(body: suspend () -> Unit): Throwable {
        val before = pageNativeState()
        val failure = runCatching { body() }.exceptionOrNull()
        assertNotNull("Rejected operation unexpectedly succeeded", failure)
        assertEquals("P and Q must both retain their previous committed bytes", before, pageNativeState())
        return requireNotNull(failure)
    }
    private suspend fun aba() {
        val old = dataStore.data.first()
        dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" }
        dataStore.updateData { old }
        assertEquals(old, dataStore.data.first())
    }
    private data class Disk(val binding: String, val root: Ref, val revision: Long,
        val nodes: Map<String, Bytes>)

    /** Independent typed SQLite observer. Full Q checks belong to the test oracle only. */
    private fun disk(): Disk = SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,
        null, SQLiteDatabase.OPEN_READONLY).use { raw ->
        val nodes = linkedMapOf<String, Bytes>()
        raw.rawQuery("SELECT digest,body FROM g0_count_block_nodes ORDER BY digest", null).use { cursor ->
            while (cursor.moveToNext()) {
                assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(0))
                assertEquals(Cursor.FIELD_TYPE_BLOB, cursor.getType(1))
                val digest = cursor.getString(0); val bytes = Bytes.copyOf(cursor.getBlob(1))
                assertEquals(digest, HistoryBlockCodec.hash(bytes))
                assertNull(nodes.put(digest, bytes))
            }
        }
        raw.rawQuery("SELECT slot,binding,height,members,digest,revision FROM g0_count_block_root", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            for (index in listOf(0, 2, 3, 5)) assertEquals(Cursor.FIELD_TYPE_INTEGER, cursor.getType(index))
            for (index in listOf(1, 4)) assertEquals(Cursor.FIELD_TYPE_STRING, cursor.getType(index))
            assertEquals(1L, cursor.getLong(0))
            val binding = cursor.getString(1)
            val unit = UnitKey(Partition(Scope(binding), "count-incremental-index-v1", "scope", null), YearMonth.of(1970, 1))
            val root = Ref(LeafRange(unit, null, null), cursor.getInt(2), cursor.getLong(3), cursor.getString(4))
            val revision = cursor.getLong(5); assertTrue(revision > 0)
            assertFalse(cursor.moveToNext()); assertTrue(root.digest in nodes)
            AuthenticatedHistoryDirectory().readRange(root, null, null,
                AuthenticatedHistoryDirectory.Loader { digest, limit ->
                    nodes[digest]?.also { assertTrue(it.size <= limit) }?.toByteArray()
                })
            Disk(binding, root, revision, nodes)
        }
    }
    private suspend fun diskDay(state: Disk, uuid: String, date: LocalDate, today: LocalDate) =
        G0CountIncrementalBlocks.readDay(G0CountIncrementalBlocks.State(state.binding, state.root, state.nodes), uuid, date, today)

    private fun assertNoRebuild(store: G0HouseholdCountStore, updates: Int) {
        assertEquals(1, store.incrementalFullBuilds)
        assertEquals(1, store.incrementalImageCaptures)
        assertEquals(updates, store.incrementalUpdates)
        assertTrue(store.incrementalWrittenNodes > 0)
        assertTrue(store.incrementalWrittenBytes > 0)
    }
    private fun assertDay(expected: CountHistory, actual: G0CountBusinessBlocks.DayRead) {
        assertEquals(expected.quantities[actual.date] ?: 0L, actual.quantity)
        assertEquals(expected.policies[actual.date], actual.storedPolicy)
        assertEquals(expected.policies[actual.date] ?: if (actual.date == expected.today) expected.todayPolicy else null,
            actual.effectivePolicy)
        assertEquals(actual.date in expected.unknownDates, actual.unknown)
        assertEquals(expected.completions.filter { it.recordedLocalDate == actual.date.toString() }, actual.facts)
        assertEquals(expected.today, actual.summary.today)
        assertEquals(expected.todayQuantity, actual.summary.todayQuantity)
        assertEquals(expected.completedToday, actual.summary.completedToday)
        assertEquals(expected.firstDate, actual.summary.firstDate)
        assertEquals(expected.qualifiedDates.size, actual.summary.qualifiedDayCount)
        assertEquals(expected.quantities.values.fold(0L, Math::addExact), actual.summary.totalQuantity)
    }

    @Test fun threeRealCountSendAckChainsMaintainAtomicRootsAndCrossTheDailyTargetWithoutRebuilding() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(8)
        val canonical = "2026-10-06T00:00:02.000000Z"
        val (http, _) = channel { input -> successReply(input, edit = { payload ->
            JsonObject(payload + ("created_at" to JsonPrimitive(canonical)))
        }) }
        val before = pageNativeState()
        val store = openIncremental(http)
        assertEquals(before.filterKeys { it !in G0CountBlockSql.tables }, pageNativeState().filterKeys { it !in G0CountBlockSql.tables })
        var persisted = disk(); assertEquals(1L, persisted.revision)
        assertNull(store.incrementalLastUpdateStats); assertNoRebuild(store, 0)
        val deliveries = mutableListOf<NextCoreDelivery<NextSyncPushResponse>>()
        var last: G0HouseholdCountStore.IncrementalView? = null
        repeat(3) { index ->
            val previous = persisted
            val saved = store.increase(seed.habits[0].uuid)
            val pending = blockDay(store, saved.habitUuid, seed.today)
            persisted = disk()
            assertEquals(previous.revision + 1, persisted.revision)
            assertNotEquals(previous.root.digest, persisted.root.digest)
            assertEquals(persisted.root.digest, pending.rootDigest)
            assertEquals(pending.day, diskDay(persisted, saved.habitUuid, seed.today, seed.today))
            assertEquals((index + 1).toLong(), pending.day.quantity)
            assertEquals(index == 2, pending.day.summary.completedToday)
            assertEquals(setOf("F", "S", "D"), requireNotNull(store.incrementalLastUpdateStats).changedUnits)
            assertNoRebuild(store, index * 3 + 1)
            val delivery = requireNotNull(store.send(http, saved.requestId)); deliveries += delivery
            assertFalse(pending.valid())
            assertEquals(setOf("S"), requireNotNull(store.incrementalLastUpdateStats).changedUnits)
            assertNoRebuild(store, index * 3 + 2)
            val sending = blockDay(store, saved.habitUuid, seed.today)
            assertEquals(pending.day.facts, sending.day.facts)
            assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
            assertFalse(sending.valid())
            assertEquals(setOf("F", "S"), requireNotNull(store.incrementalLastUpdateStats).changedUnits)
            assertNoRebuild(store, index * 3 + 3)
            val accepted = blockDay(store, saved.habitUuid, seed.today)
            assertEquals(java.time.Instant.parse(canonical).toEpochMilli(), accepted.day.facts.single { it.uuid == saved.factUuid }.createdAt)
            assertEquals(pending.day.quantity, accepted.day.quantity)
            persisted = disk(); assertEquals(1L + (index + 1) * 3, persisted.revision)
            assertEquals(accepted.day, diskDay(persisted, saved.habitUuid, seed.today, seed.today))
            assertTrue(previous.nodes.all { (id, bytes) -> persisted.nodes[id] == bytes })
            last = accepted
        }
        assertEquals(11, pageRowCount("completions")); assertEquals(11, pageRowCount("next_acceptances"))
        assertEquals(0, pageRowCount("sync_outbox"))
        val settled = pageNativeState(); val stats = store.incrementalLastUpdateStats
        val nodes = store.incrementalWrittenNodes; val bytes = store.incrementalWrittenBytes
        for (delivery in deliveries) assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(settled, pageNativeState()); assertNoRebuild(store, 9)
        assertEquals(stats, store.incrementalLastUpdateStats)
        assertEquals(nodes, store.incrementalWrittenNodes); assertEquals(bytes, store.incrementalWrittenBytes)
        assertTrue(requireNotNull(last).valid())
        val shown = seed.habits.associate { row -> row.uuid to (seed.references.getValue(row.uuid).quantities.keys + seed.today)
            .associateWith { blockDay(store, row.uuid, it).day } }
        val household = householdView(store)
        reopenReference(); sameHouseholdAsOriginal(household)
        for (row in seed.habits) {
            val current = requireNotNull(db.habitDao().getHabitById(row.id))
            val reference = CountHistoryReader(db, tokens, sessions).read(current, seed.today)
            for ((date, observed) in shown.getValue(row.uuid)) {
                assertDay(reference, observed)
                assertEquals(observed, diskDay(persisted, row.uuid, date, seed.today))
            }
            // Pure next-day projection under the same persisted data root. This does not change
            // the device clock or claim that this first B2 Store supports cross-day writes.
            val tomorrow = seed.today.plusDays(1)
            val nextDayReference = CountHistoryReader(db, tokens, sessions).read(current, tomorrow)
            val nextDay = diskDay(persisted, row.uuid, tomorrow, tomorrow)
            assertDay(nextDayReference, nextDay); assertEquals(0L, nextDay.quantity)
            assertEquals(0L, nextDay.summary.todayQuantity); assertFalse(nextDay.summary.completedToday)
            val retainedDay = diskDay(persisted, row.uuid, seed.today, tomorrow)
            assertDay(nextDayReference, retainedDay)
            val capturedDay = shown.getValue(row.uuid).getValue(seed.today)
            assertEquals(capturedDay.quantity, retainedDay.quantity)
            assertEquals(capturedDay.storedPolicy, retainedDay.storedPolicy)
            if (row.uuid == seed.habits[0].uuid) assertEquals(3L, retainedDay.quantity)
        }
        assertEquals(persisted, disk())
        assertEquals(settled, pageNativeState())
    }

    @Test fun finalFreshReadAuthenticationRejectsWithoutChangingPrimaryOrBlocksAndRetiresOwner() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http); var published = false
        store.afterEntryAuthenticationForTest = { failDecrypt = true }
        try { rejectedUnchanged {
            store.readIncrementalCountDay(seed.habits[0].uuid, seed.today) { published = true }
        } } finally { failDecrypt = false; store.afterEntryAuthenticationForTest = {} }
        assertFalse(published)
        assertTrue(runCatching { blockDay(store, seed.habits[0].uuid, seed.today) }.isFailure)
        assertEquals(2, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        assertEquals(1L, disk().revision); assertNoRebuild(store, 0)
    }

    @Test fun finalCommitAuthenticationRollsBackBothPrimaryAndBlocksAndRetiresOwner() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http)
        store.beforeFinalForTest = { failDecrypt = true }
        try { rejectedUnchanged { store.increase(seed.habits[0].uuid) } }
        finally { failDecrypt = false; store.beforeFinalForTest = {} }
        assertTrue(runCatching { blockDay(store, seed.habits[0].uuid, seed.today) }.isFailure)
        assertEquals(2, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        assertEquals(1L, disk().revision); assertNoRebuild(store, 0)
    }

    @Test fun unexpectedActualRootWriteRollsBackTheEntirePrimaryAndBlockDelta() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http)
        store.beforeFinalForTest = {
            // An allowed statement is not permission to add an unplanned write, even a same-value one.
            val header = db.openHelper.writableDatabase.query(G0CountBlockSql.readRoot).use { cursor ->
                check(cursor.moveToFirst())
                listOf<Any>(cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4), cursor.getLong(5))
            }
            val cas = listOf(header[0], header[3], header[4])
            db.openHelper.writableDatabase.execSQL(G0CountBlockSql.updateRoot, (header + cas).toTypedArray())
        }
        val failure = rejectedUnchanged { store.increase(seed.habits[0].uuid) }
        assertEquals("G0_COUNT_UNEXPECTED_EFFECT", failure.message)
        assertTrue(runCatching { blockDay(store, seed.habits[0].uuid, seed.today) }.isFailure)
        assertEquals(1L, disk().revision); assertNoRebuild(store, 0)
    }

    @Test fun unknownIncreaseCommitsPrimaryAndRootTogetherButOnlyOriginalRecoveryFindsTheCommand() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http); val previous = disk()
        val command = store.newCommand(seed.habits[0].uuid); val trace = G0PhaseTrace(driverTiming = false)
        endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST", runCatching { trace.run { store.increase(command) } }.exceptionOrNull()?.message)
        assertEquals(3, pageRowCount("completions")); assertEquals(1, pageRowCount("sync_outbox"))
        val committed = disk(); assertEquals(previous.revision + 1, committed.revision)
        assertNotEquals(previous.root.digest, committed.root.digest)
        val observed = diskDay(committed, command.habitUuid, seed.today, seed.today)
        assertEquals(listOf(command.factUuid), observed.facts.map { it.uuid })
        assertEquals(setOf(command.requestId), observed.sourceRequestIds)
        assertTrue(runCatching { blockDay(store, command.habitUuid, seed.today) }.isFailure)
        assertTrue(runCatching { store.increase(command) }.isFailure)
        val saved = pageNativeState(); val recovered = openIncremental(http, install = false)
        val result = requireNotNull(recovered.recover(command))
        assertEquals(command.requestId, result.requestId); assertEquals(command.factUuid, result.factUuid)
        assertEquals(saved, pageNativeState())
        rejectedUnchanged { recovered.increase(seed.habits[1].uuid) }
        var published = false
        rejectedUnchanged { recovered.readIncrementalCountDay(command.habitUuid, seed.today) { published = true } }
        assertFalse(published)
        val cannotInstall = rejectedUnchanged { recovered.installIncrementalCountBlocks() }
        assertEquals("G0_BLOCK_COLD_ROOT_NOT_ADMITTED", cannotInstall.message)
    }

    @Test fun unknownAckPersistsReceiptAndMatchingBlocksAndOriginalReplayDoesNotReapplyIt() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http); val command = store.newCommand(seed.habits[0].uuid)
        val saved = store.increase(command); val delivery = requireNotNull(store.send(http, saved.requestId))
        val before = disk(); val trace = G0PhaseTrace(driverTiming = false)
        endFaults.failAfterNextEndIn(trace)
        assertEquals("G0_COMMIT_RESULT_LOST", runCatching { trace.run { store.acknowledge(delivery) } }.exceptionOrNull()?.message)
        assertEquals(0, pageRowCount("sync_outbox")); assertEquals(3, pageRowCount("next_acceptances"))
        val committed = disk(); assertEquals(before.revision + 1, committed.revision)
        assertEquals(listOf(saved.factUuid), diskDay(committed, saved.habitUuid, seed.today, seed.today).facts.map { it.uuid })
        assertTrue(runCatching { blockDay(store, saved.habitUuid, seed.today) }.isFailure)
        val durable = pageNativeState()
        reopenReference()
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(durable, pageNativeState())
        val recovered = openIncremental(http, install = false)
        assertEquals(saved, recovered.recover(command)); assertEquals(durable, pageNativeState())
        rejectedUnchanged { recovered.increase(seed.habits[1].uuid) }
    }

    @Test fun writeAfterBlockDecodeRejectsTheOldPublicationAndFreshReadUsesNewRoot() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http); val old = blockDay(store, seed.habits[0].uuid, seed.today)
        var saved: G0HouseholdCountStore.Saved? = null; var published = false
        store.beforeCountBlockPublishForTest = { saved = store.increase(seed.habits[1].uuid) }
        try { assertTrue(runCatching { store.readIncrementalCountDay(seed.habits[0].uuid, seed.today) { published = true } }.isFailure) }
        finally { store.beforeCountBlockPublishForTest = {} }
        assertFalse(published); assertNotNull(saved); assertFalse(old.valid())
        val current = blockDay(store, seed.habits[1].uuid, seed.today)
        assertEquals(1L, current.day.quantity); assertEquals(disk().root.digest, current.rootDigest)
        assertNoRebuild(store, 1)
        val view = householdView(store); reopenReference(); sameHouseholdAsOriginal(view)
    }

    @Test fun cancelledPreparationKeepsTheCommittedRootAndAuthorityAbaCannotPublishIt() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http); val before = pageNativeState()
        val retained = blockDay(store, seed.habits[0].uuid, seed.today)
        val entered = CompletableDeferred<Unit>()
        store.afterPrepareForTest = { entered.complete(Unit); awaitCancellation() }
        val operation = launch(Dispatchers.IO) { store.increase(seed.habits[0].uuid) }
        withTimeout(15_000) { entered.await() }; operation.cancelAndJoin(); store.afterPrepareForTest = {}
        assertEquals(before, pageNativeState()); assertTrue(retained.valid()); assertNoRebuild(store, 0)
        var published = false
        store.beforeCountBlockPublishForTest = { aba() }
        try { rejectedUnchanged { store.readIncrementalCountDay(seed.habits[0].uuid, seed.today) { published = true } } }
        finally { store.beforeCountBlockPublishForTest = {} }
        assertFalse(published); assertFalse(retained.valid()); assertEquals(before, pageNativeState())
    }

    @Test fun writesRequireInstalledBlocksAndTheOrdinaryProfileCannotSealAnExtraQSchema() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http, install = false)
        assertEquals(0, pageRowCount(G0CountBlockSql.root)); assertEquals(0, pageRowCount(G0CountBlockSql.nodes))
        assertEquals("G0_INCREMENTAL_BLOCKS_REQUIRED", rejectedUnchanged { store.increase(seed.habits[0].uuid) }.message)
        var published = false
        rejectedUnchanged { store.readIncrementalCountDay(seed.habits[0].uuid, seed.today) { published = true } }
        assertFalse(published)
        store.installIncrementalCountBlocks(); assertTrue(blockDay(store, seed.habits[0].uuid, seed.today).valid())
        rejectedUnchanged { db.openHelper.writableDatabase.execSQL(G0CountBlockSql.insertNode,
            arrayOf("0".repeat(64), byteArrayOf(1))) }
        val before = pageNativeState(); val schema = independentSchema()
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true)
        storage.reopen()
        val ordinary = G0HouseholdCountStore(db, tokens, sessions, authority, domainGate, sender(http), jointAuthority = true)
        assertEquals("G0_DOMAIN_TABLE_COVERAGE", runCatching { ordinary.initialize(currentHousehold, schema) }.exceptionOrNull()?.message)
        assertEquals(before, pageNativeState())
    }

    @Test fun externalQCommitIsDetectedAtTheFinalReadBoundaryBeforeAnyPublication() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIncremental(http)
        val previous = blockDay(store, seed.habits[0].uuid, seed.today)
        assertTrue(previous.valid())
        var afterFault: Map<String, List<String>>? = null
        var memoryOnlyStillCurrent = false
        var published = false
        store.beforeCountBlockPublishForTest = {
            SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { raw ->
                raw.execSQL("UPDATE g0_count_block_root SET revision=revision+1 WHERE slot=1")
            }
            // Deterministic counterexample premise: a memory epoch check alone has not observed
            // this external commit. The following controlled READ must detect data_version.
            memoryOnlyStillCurrent = previous.valid()
            afterFault = pageNativeState()
        }
        val failure = try {
            runCatching { store.readIncrementalCountDay(seed.habits[0].uuid, seed.today) { published = true } }.exceptionOrNull()
        } finally { store.beforeCountBlockPublishForTest = {} }
        assertTrue(memoryOnlyStillCurrent)
        assertNotNull(failure)
        assertTrue("Must detect the external SQLite commit, not an unrelated refusal",
            generateSequence(requireNotNull(failure)) { it.cause }
                .any { it.message?.contains("G0_DOMAIN_EXTERNAL_CHANGE") == true })
        assertFalse(published); assertFalse(previous.valid())
        assertEquals(requireNotNull(afterFault), pageNativeState())
        assertEquals(2, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox"))
        assertNoRebuild(store, 0)
    }

}
