package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.HabitDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class G0SchemaOpeningTest : G0SealedDomainFixture() {
    private fun reopen(callback: G0SchemaOpeningCallback) {
        freshGateForColdOpen()
        storage.isolatedBuilder={ context -> Room.databaseBuilder(context,HabitDatabase::class.java,"habit_database")
            .addCallback(callback).openHelperFactory(domainGate.factory).build() }
        storage.reopen()
    }
    private fun triggerExists(): Boolean = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use { raw -> raw.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='sync_metrics_update'",null).use {
        assertTrue(it.moveToFirst());it.getInt(0)==1
    } }
    @Test fun batchedReferenceHasExactlyOriginalMainAndTempSchema() = runBlocking<Unit> {
        val original=trustedDomainSchema()
        assertEquals(original,trustedDomainSchema(batchedOpening=true))
        assertEquals(original,trustedDomainSchema(batchedOpening=true))
    }
    @Test fun allOriginalStatementsRunAndSchemaRemainsUnchangedOnReopen() = runBlocking<Unit> {
        register();val current=access();val expected=trustedDomainSchema()
        val callback=G0SchemaOpeningCallback(true);reopen(callback)
        val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        val trace=G0PhaseTrace(driverTiming=false)
        trace.run { store.initialize(current,expected) }
        assertEquals(1,callback.bodies)
        assertEquals(1,trace.count("schema_callback_begin"));assertEquals(1,trace.count("schema_callback_end"))
        val triggers=expected.count { it[0]=="main" && it[1]=="trigger" }
        assertEquals(triggers,trace.count("schema_callback_trigger"))
        assertEquals(1,trace.count("schema_callback_control"))
        assertEquals(2,trace.count("decrypt_access"))
        assertEquals(metric.name,store.read(current).name)
        storage.isolatedBuilder=null;storage.reopen()
        val unpinned=G0DomainJournal.trustedSchema(db.openHelper.writableDatabase)
        val pinned=db.withTransaction { G0DomainJournal.trustedSchema(db.openHelper.writableDatabase) }
        // TEMP schema is connection-local. Record the original faulty observation without
        // removing TEMP from the full equality assertion on the reserved primary connection.
        android.util.Log.i("G0SchemaObservation","outside_missing="+(expected-unpinned.toSet()).map { it.take(4) }+
            " outside_extra="+(unpinned-expected.toSet()).map { it.take(4) }+
            " pinned_equal="+(expected==pinned))
        assertEquals(expected,pinned)
    }
    @Test fun sameNamedChangedTriggerIsNotOverwrittenOrTrusted() = runBlocking<Unit> {
        register();val current=access();val expected=trustedDomainSchema()
        val sql=db.openHelper.writableDatabase
        sql.execSQL("DROP TRIGGER sync_metrics_update")
        sql.execSQL("CREATE TRIGGER sync_metrics_update AFTER UPDATE ON metrics BEGIN SELECT 1; END")
        val changed=G0DomainJournal.trustedSchema(sql)
        reopen(G0SchemaOpeningCallback(true))
        val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        assertEquals("G0_DOMAIN_SCHEMA_NOT_TRUSTED",runCatching { store.initialize(current,expected) }.exceptionOrNull()?.message)
        assertNotNull(runCatching { store.read(current) }.exceptionOrNull())
        storage.isolatedBuilder=null;storage.reopen()
        assertEquals(changed,G0DomainJournal.trustedSchema(db.openHelper.writableDatabase))
        assertEquals(0,count("next_request_origins"));assertEquals(0,count("next_acceptances"))
    }
    private suspend fun failingOpen(afterCommit: Boolean, cancelled: Boolean=false) {
        register();val current=access();val expected=trustedDomainSchema()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER sync_metrics_update")
        val fault=if(cancelled) CancellationException("G0_SCHEMA_CANCELLED") else IllegalStateException("G0_SCHEMA_INJECTED")
        val callback=G0SchemaOpeningCallback(true,
            beforeSuccess={if(!afterCommit) throw fault},afterEnd={if(afterCommit) throw fault})
        reopen(callback)
        val store=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        assertNotNull(runCatching { store.initialize(current,expected) }.exceptionOrNull())
        assertNotNull(runCatching { store.read(current) }.exceptionOrNull())
        assertNotNull(runCatching { db.openHelper.writableDatabase }.exceptionOrNull())
        assertEquals(1,callback.bodies) // Framework reopen retries cannot rerun an uncertain callback.
        assertEquals(afterCommit,triggerExists())
        storage.isolatedBuilder=null;storage.reopen()
        reopen(G0SchemaOpeningCallback(true))
        val recovered=G0ShortMetricStore(db,tokens,sessions,authority,domainGate,metric.uuid)
        recovered.initialize(current,expected)
        assertEquals(metric.name,recovered.read(current).name)
    }
    @Test fun callbackFailureRollsBackAddedTriggerAndNeverPublishesHandle() = runBlocking<Unit> { failingOpen(false) }
    @Test fun actualCallbackCommitWithLostResultRequiresNewOwnerAndFullProof() = runBlocking<Unit> { failingOpen(true) }
    @Test fun callbackCancellationDoesNotPublishOrRerunBody() = runBlocking<Unit> { failingOpen(false,true) }
}
