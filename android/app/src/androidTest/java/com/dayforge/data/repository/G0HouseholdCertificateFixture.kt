package com.dayforge.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import com.dayforge.data.api.NextSyncHttp
import com.dayforge.data.local.HabitDatabase
import com.dayforge.data.local.NextReplicaFenceSql
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*

/** Explicit fixture-only Q/H schema, never a production migration or open-time repair. */
abstract class G0HouseholdCertificateFixture:G0HouseholdColdOwnerFixture() {
    private val certificateAlias="dayforge.test.g0.count.${UUID.randomUUID()}"
    internal val countCertificate:G0CountCertificate get()=G0CountCertificate(certificateAlias)
    @After fun removeCountCertificateKey() {KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(certificateAlias)}}
    internal suspend fun prepareCertificateSchema() {
        val before=primaryState()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL(G0CountCertificateSql.create)
            db.openHelper.writableDatabase.execSQL(G0CountCertificateSql.chunkCreate)
            NextReplicaFenceSql.install(db.openHelper.writableDatabase)
        }
        assertEquals(before,primaryState())
    }
    internal fun primaryState()=pageNativeState().filterKeys {it !in G0CountCertificateSql.tables}
    private suspend fun certificateReference():Reference {
        val reference=Room.inMemoryDatabaseBuilder(app,HabitDatabase::class.java).addCallback(domainOpeningCallback()).build()
        return try {
            reference.openHelper.writableDatabase
            reference.withTransaction {
                val sql=reference.openHelper.writableDatabase
                sql.execSQL(G0CountCertificateSql.create);sql.execSQL(G0CountCertificateSql.chunkCreate)
                NextReplicaFenceSql.install(sql)
                Reference(G0DomainJournal.trustedSchema(sql),sql.query("SELECT id,identity_hash FROM room_master_table ORDER BY id").use {c->
                    buildList {while(c.moveToNext())add(c.getLong(0) to c.getString(1))}
                })
            }
        } finally {reference.close()}
    }
    internal suspend fun openCertificate(http:NextSyncHttp,lookup:Boolean=true,
        capacity:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128,
        shareInput:Boolean=true,beforeActualEnd:()->Unit={},afterActualEnd:()->Unit={},
        maxInputBytes:Long=G0FrozenDomainInputs.DEFAULT_MAX_BYTES,
        configure:(G0HouseholdCountStore)->Unit={}):Opened {
        val trace=G0PhaseTrace.current.get()
        val reference=if(trace==null)certificateReference() else trace.stage("cold_reference") {certificateReference()}
        val owner=G0RoomColdOwnerFactory(reference.main,reference.identity,delegateFactory=G0TimingFactory(endFaults),
            onFailure={domainGate.invalidateConnection()},beforeActualEnd=beforeActualEnd,afterActualEnd=afterActualEnd)
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,coldLifecycle=owner,
            countCertificateScope=true)
        storage.reopen()
        val store=G0HouseholdCountStore(db,tokens,sessions,authority,domainGate,sender(http),capacity.limit,
            jointAuthority=true,capacityProfile=capacity,certificate=countCertificate,certificateLookup=lookup)
        configure(store)
        return try {
            store.initializeOwned(currentHousehold,reference.schema,owner,shareInput,maxInputBytes)
            Opened(store,owner)
        } catch(failure:G0FrozenDomainInputs.BudgetExceeded) {
            // Optional acceleration capacity cannot remove an originally valid use.
            // A new protected owner may run the unchanged full validator only after
            // positively known rollback; no SQL/unknown-END/auth/cancellation fallback.
            if(!shareInput || !owner.knownRollback || failure.suppressed.isNotEmpty())throw failure
            openCertificate(http,lookup=false,capacity=capacity,shareInput=false,
                beforeActualEnd=beforeActualEnd,afterActualEnd=afterActualEnd,configure=configure)
                .copy(budgetFallback=true)
        }
    }
    internal suspend fun signInitial(http:NextSyncHttp,
        capacity:G0HouseholdCountStore.CapacityProfile=G0HouseholdCountStore.CapacityProfile.DEFAULT_128):G0HouseholdCountStore {
        val store=openCertificate(http,lookup=false,capacity=capacity).store
        val primary=primaryState()
        assertTrue(store.persistColdCertificate())
        assertEquals(primary,primaryState());assertEquals(1,pageRowCount(G0CountCertificateSql.table))
        assertTrue(pageRowCount(G0CountCertificateSql.chunkTable)>0)
        return store
    }
}
