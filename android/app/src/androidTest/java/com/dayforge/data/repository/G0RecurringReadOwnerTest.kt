package com.dayforge.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.dayforge.data.local.entity.CompletionEntity
import com.dayforge.data.local.toDisplayMillis
import com.dayforge.data.model.HabitType
import kotlinx.coroutines.*
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class G0RecurringReadOwnerTest : G0RecurringReadFixture() {
    @Test fun nonemptyCountAndCheckMatchOriginalReadersUnderSealedSql()=runBlocking<Unit> {
        val ids=seed();val now=ZonedDateTime.now()
        val count=CountHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[0])),now.toLocalDate())
        val check=CheckHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[1])),now.toLocalDate())
        assertEquals(5L,count.todayQuantity);assertEquals(1L,check.todayQuantity)
        val before=state()
        sealDomain();val candidate=owner()
        val batch=try { candidate.read(ids,now) } catch(error:Throwable) {
            generateSequence(error) { it.cause }.filterIsInstance<G0DomainSqlGate.Denied>().firstOrNull()?.statementHash?.let {
                android.util.Log.i("G0RecurringDenied","{\"statement_hash\":\"$it\"}")
            }
            throw error
        }
        assertEquals(count,batch.content.counts.getValue(ids[0]));assertEquals(check,batch.content.checks.getValue(ids[1]))
        var published=false
        assertTrue(candidate.publish(batch) { assertTrue(it.valid());published=true });assertTrue(published)
        assertEquals(before,state())
    }
    private suspend fun rejectedFactChange(sql:String,external:Boolean=false) {
        val ids=seed();val now=ZonedDateTime.now()
        if(external) SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READWRITE).use {
            // Deliberately corrupt only the isolated fixture. FK disabling is connection-local.
            it.execSQL("PRAGMA foreign_keys=OFF");it.execSQL(sql)
            it.rawQuery("SELECT changes()",null).use { c -> assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0)) }
        } else db.openHelper.writableDatabase.execSQL(sql)
        val before=state()
        val original=rejected { CountHistoryReader(db,tokens,sessions).read(requireNotNull(db.habitDao().getHabitById(ids[0])),now.toLocalDate()) }
        sealDomain();val candidate=rejected { owner().read(ids,now) }
        assertEquals(original.javaClass,candidate.javaClass);assertEquals(original.message,candidate.message)
        assertEquals(before,state())
    }
    @Test fun changedQuantityIsRejectedLikeOriginalWithoutPersistentChanges()=runBlocking<Unit> {
        rejectedFactChange("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
    }
    @Test fun missingSourceIsRejectedLikeOriginalWithoutPersistentChanges()=runBlocking<Unit> {
        rejectedFactChange("DELETE FROM next_request_origins WHERE requestId=(SELECT requestId FROM next_request_origins WHERE intentJson LIKE '%activity_event%' LIMIT 1)",external=true)
    }
    @Test fun finalReadAndFinalPublicationEachDecryptFreshCredentials()=runBlocking<Unit> {
        val ids=seed();sealDomain();val candidate=owner()
        val before=state();candidate.afterReadForTest={ failDecrypt=true }
        try { rejected { candidate.read(ids,ZonedDateTime.now()) } } finally { failDecrypt=false }
        assertEquals(before,state())
        freshGateForColdOpen(recurringReads=true);storage.reopen();sealDomain()
        val fresh=owner();val batch=fresh.read(ids,ZonedDateTime.now())
        val calls=decryptCount.get();failDecrypt=true;var delivered=false
        try { assertFalse(fresh.publish(batch) { delivered=true }) } finally { failDecrypt=false }
        assertFalse(delivered);assertTrue(decryptCount.get()>calls);assertEquals(before,state())
    }
    @Test fun publishedResultIsRevokedOnAuthorityChangeAndCannotBeRepublished()=runBlocking<Unit> {
        val ids=seed();sealDomain();val candidate=owner();val batch=candidate.read(ids,ZonedDateTime.now())
        lateinit var shown:G0RecurringReadOwner.Publication
        assertTrue(candidate.publish(batch) { shown=it });assertTrue(shown.valid())
        tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2)
        assertFalse(shown.valid());assertFalse(candidate.publish(batch) { fail("Stale account published") })
    }
    @Test fun closeWhileReadIsSuspendedPreventsLatePublication()=runBlocking<Unit> {
        val ids=seed();sealDomain();val candidate=owner();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        candidate.afterReadForTest={ entered.complete(Unit);release.await() }
        val task=async { runCatching { candidate.read(ids,ZonedDateTime.now()) } }
        withTimeout(3_000) { entered.await() };candidate.close();release.complete(Unit)
        assertEquals("G0_RECURRING_OWNER_CLOSED",task.await().exceptionOrNull()?.message)
    }
    @Test fun dateConnectionAndOwnerCloseRevokePublication()=runBlocking<Unit> {
        val ids=seed();sealDomain();val candidate=owner();val now=ZonedDateTime.now();val batch=candidate.read(ids,now)
        lateinit var shown:G0RecurringReadOwner.Publication
        assertTrue(candidate.publish(batch) { shown=it })
        candidate.nowForTest={ now.plusDays(1) };assertFalse(shown.valid())
        assertFalse(candidate.publish(batch) { fail("Old business date published") })
        candidate.nowForTest={ now };assertTrue(shown.valid());candidate.close();assertFalse(shown.valid())
        assertFalse(candidate.publish(batch) { fail("Closed owner published") })
        val second=owner();val other=second.read(ids,now)
        assertTrue(second.publish(other) { shown=it });domainGate.invalidateConnection();assertFalse(shown.valid())
        assertFalse(second.publish(other) { fail("Lost connection published") })
    }
    @Test fun externalMutationBetweenReadAndPublishIsRejected()=runBlocking<Unit> {
        val ids=seed();sealDomain();val candidate=owner();val batch=candidate.read(ids,ZonedDateTime.now())
        SQLiteDatabase.openDatabase(app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        }
        val before=state()
        assertEquals("G0_DOMAIN_EXTERNAL_CHANGE",rejected { candidate.publish(batch) { fail("External mutation published") } }.message)
        assertEquals(before,state())
    }


    @Test fun challengeProfileCannotBorrowPlainReadScope()=runBlocking<Unit> {
        val ids=seed();val a=access()
        db.nextSyncStateDao().insert(com.dayforge.data.local.entity.NextSyncStateEntity(
            a.session.authentication.userId,requireNotNull(a.session.serverInstanceId),requireNotNull(a.session.syncEpoch),requireNotNull(a.deviceId),1,0,"a".repeat(64),"b".repeat(64),challengeContract=1))
        val before=state();sealDomain()
        assertEquals("SYNC_CHALLENGE_PROFILE_REQUIRED",rejected { owner().read(ids,ZonedDateTime.now()) }.message)
        assertEquals(before,state())
    }
    @Test fun replicaFenceCannotBecomeAnOrdinaryReadGrant()=runBlocking<Unit> {
        val ids=seed();val a=access()
        db.nextReplicaFenceDao().insert(com.dayforge.data.local.entity.NextReplicaFenceEntity(
            id(6500),a.session.authentication.userId,requireNotNull(a.session.serverInstanceId),requireNotNull(a.session.syncEpoch),id(6501),
            id(6502),requireNotNull(a.deviceId),1,"a".repeat(64)))
        val before=state();sealDomain()
        assertEquals("G0_RECURRING_REPLICA_SCOPE_UNSUPPORTED",rejected { owner().read(ids,ZonedDateTime.now()) }.message)
        assertEquals(before,state())
    }
}
