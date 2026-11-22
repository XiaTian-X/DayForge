package com.dayforge.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.CountHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Same previously signed P, then real disk damage. Both lookup settings must keep
 * the original refusal and leave every primary/optional row unchanged. No bad data
 * is used to recreate expected history or to issue a replacement certificate.
 */
@RunWith(AndroidJUnit4::class)
class G0HouseholdCertificateRejectionTest:G0HouseholdCertificateFixture() {
    private suspend fun rejects(statements:List<String>) {
        seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        prepareCertificateSchema();signInitial(http);reopenReference()
        val clean=storage.snapshotClosedDatabase()
        for(statement in statements) {
            val errors=mutableListOf<Pair<Class<*>,String?>>()
            for(lookup in listOf(false,true)) {
                freshGateForColdOpen();restoreForJourney(clean);mutateHouseholdFile(statement)
                val before=pageNativeState()
                val failure=runCatching {openCertificate(http,lookup=lookup)}.exceptionOrNull()
                assertNotNull("Original refusal was lost: $statement / $lookup",failure)
                errors+=requireNotNull(failure).javaClass to failure.message
                assertEquals(before,pageNativeState())
            }
            assertEquals("Lookup changed original refusal: $statement",errors.first(),errors.last())
        }
    }
    @Test fun missingFactOrDayCannotBorrowSignedMembership()=runBlocking<Unit> {rejects(listOf(
        "DELETE FROM completions WHERE id=(SELECT MIN(id) FROM completions)",
        "DELETE FROM count_days WHERE firstEventUuid=(SELECT MIN(firstEventUuid) FROM count_days)"))}
    @Test fun originAndTransmissionChangesCannotBorrowSignedSourceProof()=runBlocking<Unit> {rejects(listOf(
        "UPDATE next_request_origins SET intentJson='{}' WHERE requestId=(SELECT MIN(requestId) FROM next_request_origins)",
        "UPDATE next_transmissions SET wireBytes=x'00' WHERE requestId=(SELECT MIN(requestId) FROM next_transmissions)"))}
    @Test fun receiptAndShadowChangesCannotBorrowSignedAcceptanceProof()=runBlocking<Unit> {rejects(listOf(
        "UPDATE next_acceptances SET resultJson='{}' WHERE requestId=(SELECT MIN(requestId) FROM next_acceptances)",
        "UPDATE sync_entity_state SET payloadJson='{}' WHERE entityUuid=(SELECT MIN(entityUuid) FROM sync_entity_state)"))}
    @Test fun wrongDeviceOrOrphanReceiptCannotBorrowSignedAccountClosure()=runBlocking<Unit> {rejects(listOf(
        "UPDATE count_days SET capturedDeviceId='foreign-device' WHERE firstEventUuid=(SELECT MIN(firstEventUuid) FROM count_days)",
        "UPDATE next_acceptances SET requestId='orphan-request' WHERE requestId=(SELECT MIN(requestId) FROM next_acceptances)"))}
    @Test fun sequenceOrDisabledOutboxCannotBorrowSignedCommitPreconditions()=runBlocking<Unit> {rejects(listOf(
        "UPDATE sqlite_sequence SET seq=0 WHERE name='completions'",
        "UPDATE sync_control SET suppressOutbox=1 WHERE id=1"))}
    @Test fun unsupportedStructureCannotBorrowSignedPlainProfile()=runBlocking<Unit> {rejects(listOf(
        "UPDATE habits SET isActive=0 WHERE id=(SELECT MIN(id) FROM habits)",
        "UPDATE habits SET parentHabitId=uuid WHERE id=(SELECT MAX(id) FROM habits)"))}


    private data class DuplicateSequence(val low:Long,val high:Long,val queue:Long)
    private fun packetState()=pageNativeState().filterKeys {it in G0CountCertificateSql.tables}
    private fun externalLong(sql:String,args:Array<String> = emptyArray()):Long = SQLiteDatabase.openDatabase(
        app.getDatabasePath("habit_database").path,null,SQLiteDatabase.OPEN_READONLY
    ).use {db->db.rawQuery(sql,args).use {cursor->
        assertTrue(cursor.moveToFirst());assertEquals(Cursor.FIELD_TYPE_INTEGER,cursor.getType(0))
        cursor.getLong(0).also {assertFalse(cursor.moveToNext())}
    }}
    private fun duplicateCompletionSequence():DuplicateSequence {
        val maximum=externalLong("SELECT MAX(id) FROM completions")
        val low=Math.addExact(maximum,100L);val high=Math.addExact(maximum,200L)
        val queue=externalLong("SELECT seq FROM sqlite_sequence WHERE name='sync_outbox'")
        // Deliberately preserve duplicate names. The unchanged full validator accepts
        // this ascending input; only the optional certificate eligibility may narrow.
        mutateHouseholdFile("DELETE FROM sqlite_sequence WHERE name='completions'")
        mutateHouseholdFile("INSERT INTO sqlite_sequence(name,seq) VALUES('completions',?)",arrayOf<Any>(low))
        mutateHouseholdFile("INSERT INTO sqlite_sequence(name,seq) VALUES('completions',?)",arrayOf<Any>(high))
        return DuplicateSequence(low,high,queue)
    }
    /** Comparison data only: this observed schema never qualifies a Store/owner or
     * issues a certificate. openCertificate still constructs its independent reference.
     */
    private suspend fun comparisonInput():G0FrozenDomainInputs {
        freshGateForColdOpen(recurringReads=true,countWrites=true,householdCountWrites=true,countCertificateScope=true)
        storage.reopen()
        return db.withTransaction {
            val schema=G0DomainJournal.trustedSchema(db.openHelper.writableDatabase)
            domainGate.coldRead(db) {G0FrozenDomainInputs.capture(db,domainGate,schema,countCertificateScope=true)}
        }
    }
    private fun sequenceOrder(input:G0FrozenDomainInputs):List<Long> =
        input.tables.getValue("sqlite_sequence").rows.filter {it.cells[0].text=="completions"}.map {row->
            assertEquals(Cursor.FIELD_TYPE_STRING,row.cells[0].type)
            assertEquals(Cursor.FIELD_TYPE_INTEGER,row.cells[1].type)
            requireNotNull(row.cells[1].number)
        }
    private fun signedRoots(input:G0FrozenDomainInputs) {
        val table=input.tables.getValue(G0CountCertificateSql.table)
        val row=table.rows.single().cells
        assertEquals(input.digestHex,row[table.columns.indexOf("inputHash")].text)
        assertEquals(input.textDigestHex,row[table.columns.indexOf("textHash")].text)
    }
    private fun completeHistoryCalls(trace:G0PhaseTrace)=synchronized(trace.spans) {
        trace.spans.count {it.phase=="count_complete_history"}
    }

    @Test fun reorderedDuplicateSequenceCannotBorrowIdenticalSignedMultisetRoots()=runBlocking<Unit> {
        seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        prepareCertificateSchema();val sequence=duplicateCompletionSequence()
        assertEquals(listOf(sequence.low,sequence.high),sequenceOrder(comparisonInput()))
        // This must first succeed through the unchanged full validator. The test does
        // not sign an image invented from the subsequently rejected input.
        signInitial(http);val packet=packetState()
        val ascending=comparisonInput();signedRoots(ascending)
        assertEquals(listOf(sequence.low,sequence.high),sequenceOrder(ascending))
        val clean=storage.snapshotClosedDatabase()
        val errors=mutableListOf<Pair<Class<*>,String?>>()
        for(lookup in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(clean)
            // Swap values in the actual existing rows, leaving row visitation order,
            // all other native cells, both authenticated hashes and Q/H unchanged.
            mutateHouseholdFile("UPDATE sqlite_sequence SET seq=CASE seq WHEN ? THEN ? WHEN ? THEN ? ELSE seq END WHERE name='completions'",
                arrayOf<Any>(sequence.low,sequence.high,sequence.high,sequence.low))
            val descending=comparisonInput()
            assertEquals(listOf(sequence.high,sequence.low),sequenceOrder(descending))
            assertArrayEquals(ascending.digest(),descending.digest())
            assertArrayEquals(ascending.textDigest(),descending.textDigest())
            assertEquals(ascending.schema,descending.schema);signedRoots(descending)
            assertEquals(packet,packetState())
            val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false)
            val failure=runCatching {trace.run {openCertificate(http,lookup=lookup)}}.exceptionOrNull()
            assertTrue("Descending sequence must retain C17 refusal, lookup=$lookup: $failure",failure is IllegalArgumentException)
            // Both original readers completed; refusal is later, in C17's monotone
            // traversal. A certificate hit must not skip that original rejection.
            assertEquals(2,completeHistoryCalls(trace))
            errors+=requireNotNull(failure).javaClass to failure.message
            assertEquals(before,pageNativeState());assertEquals(packet,packetState())
        }
        assertEquals(errors.first(),errors.last())
    }

    @Test fun acceptedDuplicateSequenceMissesCertificateAndKeepsFullValidatorAllocation()=runBlocking<Unit> {
        seedHouseholdAccepted(8);val (http,_)=channel {successReply(it)}
        prepareCertificateSchema();val sequence=duplicateCompletionSequence()
        signInitial(http);val packet=packetState()
        val ascending=comparisonInput();signedRoots(ascending)
        assertEquals(listOf(sequence.low,sequence.high),sequenceOrder(ascending))
        val clean=storage.snapshotClosedDatabase()
        var original:Map<String,CountHistory>?=null
        val allocations=mutableListOf<Pair<Long,Long>>()
        for(lookup in listOf(false,true)) {
            freshGateForColdOpen();restoreForJourney(clean)
            val before=pageNativeState();val trace=G0PhaseTrace(driverTiming=false)
            val store=trace.run {openCertificate(http,lookup=lookup)}.store
            assertFalse("Duplicate names remove acceleration, not originally valid business state",store.certificateHit)
            assertEquals(2,completeHistoryCalls(trace));assertEquals(before,pageNativeState())
            val view=householdView(store);val histories=view.cards.mapValues {it.value.history}
            assertTrue(view.valid());assertEquals(8,histories.values.sumOf {it.completions.size})
            if(original==null)original=histories else assertEquals(original,histories)
            val saved=store.increase(currentHousehold.first().uuid)
            val queue=externalLong("SELECT id FROM sync_outbox WHERE operationId=?",arrayOf(saved.requestId))
            assertEquals(Math.addExact(sequence.high,1L),saved.factId)
            assertEquals(Math.addExact(sequence.queue,1L),queue)
            allocations+=saved.factId to queue
            val sent=requireNotNull(store.send(http,saved.requestId))
            assertEquals(NextOperationAcceptance.COMMITTED,store.acknowledge(sent))
            val result=householdView(store,mapOf(saved.habitUuid to saved.factId))
            assertEquals(1L,result.cards.getValue(saved.habitUuid).history.todayQuantity)
            assertEquals(9,result.cards.values.sumOf {it.history.completions.size})
            assertEquals(0,pageRowCount("sync_outbox"));assertEquals(packet,packetState())
            val terminal=pageNativeState()
            assertEquals(NextOperationAcceptance.REPLAYED,store.acknowledge(sent))
            reopenReference();sameHouseholdAsOriginal(result)
            assertEquals(NextOperationAcceptance.REPLAYED,sender(http).acceptOperation(sent))
            assertEquals(terminal,pageNativeState())
        }
        assertEquals(allocations.first(),allocations.last())
    }
}
