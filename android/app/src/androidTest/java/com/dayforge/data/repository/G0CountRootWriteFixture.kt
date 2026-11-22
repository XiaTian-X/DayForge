package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.api.dto.NextSyncPushResponse
import com.dayforge.domain.service.ActivityRateCalculator
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import org.junit.Assert.*

/** B3b only. Initial issuance still uses the original full validator; every tested cold
 * writer is a new actual Room owner and may not borrow that issuer's in-memory proof. */
abstract class G0CountRootWriteFixture : G0CountRootFixture() {
    override fun domainOpeningCallback(): RoomDatabase.Callback {
        val inherited = super.domainOpeningCallback()
        return object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                inherited.onCreate(db)
                G0CountSourceScopeSql.requireCapability(db)
                G0CountNativeRows.createIndexes.forEach(db::execSQL)
            }
            override fun onOpen(db: SupportSQLiteDatabase) = inherited.onOpen(db)
        }
    }
    internal suspend fun issueWriteRoot(http: NextSyncHttp,
        capacity:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128,
        install:Boolean=true): G0HouseholdCountStore {
        val reference = coldReference()
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true,
            countBlockScope = true, countRootScope = true, countRootWriteScope = true)
        storage.reopen()
        val expected = currentHousehold.map { requireNotNull(db.habitDao().getHabitById(it.id)) }
        return G0HouseholdCountStore(db, tokens, sessions, authority, domainGate, sender(http),
            maxFacts=capacity.limit,capacityProfile=capacity,jointAuthority = true, incrementalBlockScope = true, rootSeal = rootSeal,
            rootWriteScope = true).also { store ->
            store.initialize(expected, reference.schema)
            if(install)store.installIncrementalCountBlocks()
        }
    }
    internal data class WriterOpened(val store: G0CountRootStore, val owner: G0RoomColdOwnerFactory)
    internal suspend fun openWriter(configure: (G0CountRootStore) -> Unit = {}): WriterOpened {
        val reference = coldReference()
        val owner = G0RoomColdOwnerFactory(reference.main, reference.identity,
            delegateFactory = G0TimingFactory(endFaults), onFailure = { domainGate.invalidateConnection() })
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true,
            coldLifecycle = owner, countBlockScope = true, countRootScope = true, countRootWriteScope = true)
        storage.reopen()
        val writer = G0CountRootStore(db, tokens, sessions, authority, domainGate, owner, rootSeal)
        configure(writer)
        writer.initialize(reference.schema)
        return WriterOpened(writer, owner)
    }
    internal suspend fun writerDay(store: G0CountRootStore, uuid: String,
        date: LocalDate = LocalDate.now()): G0CountRootStore.View {
        var result: G0CountRootStore.View? = null
        store.readDay(uuid, date) { result = it }
        return requireNotNull(result).also { assertTrue(it.valid()) }
    }
    internal suspend fun writerAck(store: G0CountRootStore, http: NextSyncHttp,
        saved: G0CountRootStore.Saved): NextCoreDelivery<NextSyncPushResponse> {
        val delivery = requireNotNull(store.send(http, saved.requestId))
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery))
        return delivery
    }
    /** Ends use of this writer by reopening the independent original implementation. */
    internal suspend fun assertWriterMatchesOriginal(store: G0CountRootStore, today: LocalDate) {
        val results = currentHousehold.associate { it.uuid to writerDay(store, it.uuid, today).day }
        val before = pageNativeState()
        reopenReference()
        for (identity in currentHousehold) {
            val row = requireNotNull(db.habitDao().getHabitById(identity.id))
            val history = CountHistoryReader(db, tokens, sessions).read(row, today)
            assertRootDay(history, results.getValue(row.uuid))
            if (history.todayQuantity > 0L) {
                assertEquals(ActivityRateCalculator.calculate(row.schedule, row.createdAt,
                    history.quantities.keys.toList(), row.activityRateUpdatedAt, ZoneId.systemDefault()), row.activityRate)
            }
        }
        assertEquals("original full oracle must not mutate the signed terminal", before, pageNativeState())
        head()
    }
    internal suspend fun authorityAba() {
        val old = dataStore.data.first()
        dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" }
        dataStore.updateData { old }
        assertEquals(old, dataStore.data.first())
    }
    internal fun hasCause(error: Throwable, message: String): Boolean =
        generateSequence(error) { it.cause }.any { it.message?.contains(message) == true }
    internal fun assertNoFullRebuild(trace: G0PhaseTrace, ledger: G0ColdWorkLedger) {
        assertEquals(0, trace.count("count_complete_history"))
        assertEquals(0, trace.count("count_block_build"))
        assertEquals(0, trace.count("count_block_snapshot"))
        assertTrue("normal cold/write path must not use old full table capture: ${ledger.wholeTableQueries}",
            ledger.wholeTableQueries.isEmpty())
    }
}
