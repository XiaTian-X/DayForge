package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the complete prior read/rejection suite against the ORIGINAL shared display scope. */
@RunWith(AndroidJUnit4::class)
class G0SharedRecurringReadTest : G0RecurringReadOwnerTest() {
    override fun owner()=G0RecurringReadOwner(db,tokens,sessions,authority,domainGate,sharedScope=true)
    private fun denied(block:()->Unit) { assertTrue(runCatching(block).exceptionOrNull() is G0DomainSqlGate.Denied) }
    @Test fun scopedReadAndPublishUseFourRealDecryptionsAndNoScopeEscapes()=runBlocking<Unit> {
        val ids=seed();sealDomain();val reader=owner();val before=decryptCount.get()
        val batch=reader.read(ids,ZonedDateTime.now());assertTrue(reader.publish(batch) { assertTrue(it.valid()) })
        assertEquals(4,decryptCount.get()-before)
    }
    @Test fun adoptedReadKeepsWriteNestedTransactionAndCursorBoundaries()=runBlocking<Unit> {
        seed();sealDomain();val sql=domainGate.sqlClient();lateinit var cursor:Cursor
        lateinit var escaped:RecurringDisplayReadScope
        sessions.exclusive {
            domainGate.adoptReadTransaction(db) {
                denied { sql.query("SELECT count(*) FROM sync_outbox").close() }
                RecurringDisplayReadScope.withSnapshotWhileAccountLocked(db,tokens,sessions) { display ->
                    escaped=display
                    cursor=sql.query("SELECT count(*) FROM sync_outbox");assertTrue(cursor.moveToFirst())
                    denied { sql.execSQL("DELETE FROM completions") }
                    denied { sql.endTransaction() }
                    denied { db.openHelper.writableDatabase.beginTransaction() }
                    withContext(Dispatchers.Default) { denied { cursor.getLong(0) } }
                }
            }
        }
        cursor.use { denied { it.getLong(0) } }
        assertEquals("DISPLAY_READ_SCOPE_CLOSED",rejected { escaped.borrow(db,tokens) }.message)
    }
    @Test fun missingAndRepeatedRootTransactionsCannotReturnAReadGrant()=runBlocking<Unit> {
        seed();sealDomain()
        assertEquals("G0_READ_TRANSACTION_NOT_COMPLETED",rejected { domainGate.adoptReadTransaction(db) { Unit } }.message)
        assertTrue(rejected { domainGate.adoptReadTransaction(db) {
            db.withTransaction { db.habitDao().getHabitById(habit.id) }
            db.withTransaction { fail("Second transaction must not enter") }
        } } is G0DomainSqlGate.Denied)
        denied { domainGate.requireSealedConnection() }
    }
    @Test fun unknownReadCommitInvalidatesWithoutDeliveringAPublication()=runBlocking<Unit> {
        val ids=seed();val before=state();sealDomain();val reader=owner()
        reader.afterReadForTest={ endFaults.failAfterNextEnd.set(true) }
        assertEquals("G0_COMMIT_RESULT_LOST",rejected { reader.read(ids,ZonedDateTime.now()) }.message)
        denied { domainGate.requireSealedConnection() };assertEquals(before,state())
    }

    @Test fun reservedAuthorityChangeRejectsEvenBeforeCredentialsCanPersist()=runBlocking<Unit> {
        val ids=seed();val before=state();sealDomain();val reader=owner()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val reserved=CompletableDeferred<Unit>()
        reader.afterReadForTest={ entered.complete(Unit);release.await() }
        authority.onTransitionForTest={ reserved.complete(Unit) }
        val reading=async { runCatching { reader.read(ids,ZonedDateTime.now()) } }
        withTimeout(3_000) { entered.await() }
        val changing=async { tokens.saveDeviceRegistration(id(4),setOf("sync.read"),true,2) }
        try {
            withTimeout(3_000) { reserved.await() };assertFalse(changing.isCompleted)
        } finally { release.complete(Unit) }
        assertTrue(reading.await().exceptionOrNull() is G0AuthorityOwner.Denied)
        withTimeout(3_000) { changing.await() };assertEquals(before,state())
    }
    @Test fun cancellationClosesOriginalScopeAndReleasesAccountAndEngine()=runBlocking<Unit> {
        val ids=seed();val before=state();sealDomain();val reader=owner();val entered=CompletableDeferred<Unit>()
        reader.afterReadForTest={ entered.complete(Unit);awaitCancellation() }
        val reading=launch { reader.read(ids,ZonedDateTime.now());fail("Cancelled read returned") }
        withTimeout(3_000) { entered.await() };withTimeout(3_000) { reading.cancelAndJoin() }
        withTimeout(3_000) { sessions.exclusive { assertFalse(db.inTransaction()) } }
        denied { domainGate.requireSealedConnection() };assertEquals(before,state())
    }
}
