package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDatabase

/** Real household facts, with one explicit existing-file cold owner. No production builder. */
abstract class G0HouseholdColdOwnerFixture : G0HouseholdHistoryFixture() {
    init {
        // Gate stays outermost. Timing is below the owner; an outer observation wrapper would
        // change the actual Room handle identity and cannot borrow this cold capability.
        storage.isolatedBuilder = { context ->
            Room.databaseBuilder(context, HabitDatabase::class.java, "habit_database")
                .addCallback(domainOpeningCallback()).openHelperFactory(domainGate.factory).build()
        }
    }
    internal data class Reference(val schema: List<List<String?>>, val identity: List<Pair<Long, String>>) {
        val main get() = schema.filter { it[0] == "main" }.map {
            G0SchemaRow(requireNotNull(it[1]), requireNotNull(it[2]), requireNotNull(it[3]), it[4])
        }
    }
    internal suspend fun coldReference(): Reference {
        val reference = Room.inMemoryDatabaseBuilder(app, HabitDatabase::class.java)
            .addCallback(domainOpeningCallback()).build()
        return try {
            reference.openHelper.writableDatabase
            reference.withTransaction {
                val sql = reference.openHelper.writableDatabase
                Reference(G0DomainJournal.trustedSchema(sql),
                    sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
                    })
            }
        } finally { reference.close() }
    }
    internal data class Opened(val store: G0HouseholdCountStore, val owner: G0RoomColdOwnerFactory,
        val budgetFallback: Boolean = false)
    internal suspend fun openOwnedHousehold(
        http: NextSyncHttp,
        shareInput: Boolean,
        capacity: G0HouseholdCountStore.CapacityProfile = G0HouseholdCountStore.CapacityProfile.DEFAULT_128,
        beforeActualEnd: () -> Unit = {},
        afterActualEnd: () -> Unit = {},
        maxInputBytes: Long = G0FrozenDomainInputs.DEFAULT_MAX_BYTES,
        configure: (G0HouseholdCountStore) -> Unit = {}
    ): Opened {
        val trace = G0PhaseTrace.current.get()
        val reference = if (trace == null) coldReference() else trace.stage("cold_reference") { coldReference() }
        val owner = G0RoomColdOwnerFactory(reference.main, reference.identity,
            delegateFactory = G0TimingFactory(endFaults),
            onFailure = { domainGate.invalidateConnection() },
            beforeActualEnd = beforeActualEnd, afterActualEnd = afterActualEnd)
        freshGateForColdOpen(recurringReads = true, countWrites = true, householdCountWrites = true,
            coldLifecycle = owner)
        storage.reopen() // build only: no target getter or DAO is permitted before owned admission.
        val store = G0HouseholdCountStore(db, tokens, sessions, authority, domainGate, sender(http),
            capacity.limit, jointAuthority = true, capacityProfile = capacity)
        configure(store)
        return try {
            store.initializeOwned(currentHousehold, reference.schema, owner, shareInput,maxInputBytes)
            Opened(store, owner)
        } catch (failure: G0FrozenDomainInputs.BudgetExceeded) {
            // A pure acceleration miss may restart in a NEW fully protected owner. Never
            // retry an unknown END, cancellation, corrupted content or a failed cleanup.
            if (!shareInput || !owner.knownRollback || failure.suppressed.isNotEmpty()) throw failure
            openOwnedHousehold(http,false,capacity,beforeActualEnd,afterActualEnd,
                G0FrozenDomainInputs.DEFAULT_MAX_BYTES,configure).copy(budgetFallback=true)
        }
    }
}
