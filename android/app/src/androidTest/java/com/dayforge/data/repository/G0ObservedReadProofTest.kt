package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Query-observation differential only; mutations run BEFORE sealing. This grants no SQL/writes. */
@RunWith(AndroidJUnit4::class)
class G0ObservedReadProofTest : G0RecurringReadFixture() {
    private suspend fun capture():G0ObservedReadProof {
        val recording=G0ObservedReadProof.Capture()
        G0ObservedReadProof.capture(recording) {
            CountHistoryReader(db,tokens,sessions).read(habit,ZonedDateTime.now().toLocalDate())
        }
        return requireNotNull(recording.finish()) { recording.reason ?: "Incomplete recording" }
    }
    private suspend fun replay(proof:G0ObservedReadProof,table:String)=db.withTransaction {
        proof.unchanged(db.openHelper.writableDatabase,setOf(table))
    }
    @Test fun changedNativeQuantityAndDeletedRowCannotMatchRecordedReads()=runBlocking<Unit> {
        seed();val proof=capture();val sql=db.openHelper.writableDatabase
        assertTrue(replay(proof,"completions"))
        sql.execSQL("UPDATE completions SET value=99 WHERE id=(SELECT min(id) FROM completions)")
        assertFalse(replay(proof,"completions"))
        sql.execSQL("DELETE FROM completions WHERE id=(SELECT min(id) FROM completions)")
        assertFalse(replay(proof,"completions"))
    }
    @Test fun newMatchingSourceAndPreviouslyAbsentRevertInvalidate()=runBlocking<Unit> {
        seed();val proof=capture();val sql=db.openHelper.writableDatabase
        sql.execSQL("""INSERT INTO next_request_origins(kind,requestId,queueId,protocol,accountId,serverInstanceId,syncEpoch,sourceHash,intentJson)
            SELECT kind,?,queueId+100000,protocol,accountId,serverInstanceId,syncEpoch,sourceHash,intentJson
            FROM next_request_origins WHERE intentJson LIKE '%activity_event%' LIMIT 1""",arrayOf(id(7200)))
        assertFalse(replay(proof,"next_request_origins"))
        sql.execSQL("INSERT INTO sync_entity_state(entityType,entityUuid,revision,deleted,payloadJson,payloadHash,updatedAt) VALUES(?,?,1,0,?,?,?)",
            arrayOf<Any>("activity_event",id(7201),"{\"activity_uuid\":\"${habit.uuid}\",\"event_type\":\"revert\"}","invalid",millis))
        assertFalse(replay(proof,"sync_entity_state"))
    }
    @Test fun unrelatedShadowReplaysAbsenceAndKeepsSameOriginalResult()=runBlocking<Unit> {
        seed();val now=ZonedDateTime.now();val reader=CountHistoryReader(db,tokens,sessions);val expected=reader.read(habit,now.toLocalDate());val proof=capture()
        db.openHelper.writableDatabase.execSQL("INSERT INTO sync_entity_state(entityType,entityUuid,revision,deleted,payloadJson,payloadHash,updatedAt) VALUES(?,?,1,0,?,?,?)",
            arrayOf<Any>("metric",id(7202),"{}","unrelated",millis))
        assertTrue(replay(proof,"sync_entity_state"));assertEquals(expected,reader.read(habit,now.toLocalDate()))
    }

    @Test fun authorizedButUndeclaredQueryDisablesReuseInsteadOfInferringDependencies()=runBlocking<Unit> {
        seed();val recording=G0ObservedReadProof.Capture()
        G0ObservedReadProof.capture(recording) {
            db.withTransaction { db.openHelper.writableDatabase.query("SELECT * FROM metrics").use { assertTrue(it.moveToFirst());it.getString(1) } }
        }
        assertNull(recording.finish());assertTrue(requireNotNull(recording.reason).startsWith("query:"))
        assertTrue(G0RecurringQueryDependencies.tables.keys.all { it in G0DomainSqlCatalog.reads || it in G0RecurringSqlCatalog.reads })
    }
    @Test fun negativeMembershipQueryDeclaresEveryChallengeTable()=runBlocking<Unit> {
        val query="SELECT EXISTS(SELECT 1 FROM next_challenge_state) OR EXISTS(SELECT 1 FROM next_challenge_rounds) OR EXISTS(SELECT 1 FROM next_challenge_births)"
        assertEquals(setOf("next_challenge_state","next_challenge_rounds","next_challenge_births"),G0RecurringQueryDependencies.tables.getValue(query))
    }
}
