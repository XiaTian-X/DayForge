package com.dayforge.data.repository

import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountCommandV20Schema
import com.dayforge.data.local.CountTrustSchema
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete physical-native recovery issuer gates; no process-death/UI/latency claim.
 * Not admitted until the WHOLE v20 primary/schema/view/entry candidate is reviewable.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandRecoveryIssuerTest : NextObjectEditorFixture() {
    private data class Prepared(val store: CountCommandStore, val input: CountCommandRecords.Intent,
        val reference: CountCommandReference, val ticket: CountCommandStore.ExecutionTicket)
    private val sql get() = db.openHelper.writableDatabase

    private suspend fun prepared(): Prepared {
        val authority = requireNotNull(editor.habit(habit.id).authority)
        val input = CountCommandInputs.add(db, UUID.randomUUID().toString(), habit, authority, Instant.now(), ZoneId.systemDefault(), 1)
        val store = CountCommandStore(db, tokens, sessions)
        val registered = store.register(input)
        assertFalse(registered.alreadyRegistered)
        return Prepared(store, input, CountCommandReference.from(registered.reference), requireNotNull(registered.ticket))
    }
    private fun reopenedEditor() {
        editor = NextObjectEditor(db, tokens, sessions, icons)
        creator = NextObjectCreator(db, tokens, sessions, icons)
    }

    @Test(timeout = 150_000) fun sameOwnerOriginalLiveInvocationCannotBeReplacedByANewStore() = runBlocking<Unit> {
        val captured = prepared(); val before = image()
        val result = CountCommandStore(db, tokens, sessions).recover(captured.reference.native())
        assertTrue(result is CountCommandStore.Recovery.Unresolved)
        assertEquals("ORIGINAL_OPERATION_STILL_RUNNING", (result as CountCommandStore.Recovery.Unresolved).reason)
        assertTrue(before == image()); assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        captured.store.releaseUnadmitted(captured.ticket)
        val resumed = creatingHabits().resumeCountCommand(app, captured.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(captured.reference, resumed.receipt.reference)
        assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
    }

    @Test(timeout = 150_000) fun actualReopenResumesTheSameInputAndSecondResolutionDoesNotReapply() = runBlocking<Unit> {
        val captured = prepared(); val oldInput = captured.input.text()
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        storage.reopen(); reopenedEditor()
        val resumed = creatingHabits().resumeCountCommand(app, captured.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(captured.reference, resumed.receipt.reference); assertTrue(resumed.receipt.recorded)
        val facts = db.completionDao().getAllCompletionsOnce(); assertEquals(1, facts.size)
        val input = captured.input.body().getValue("input") as kotlinx.serialization.json.JsonObject
        assertEquals(CountCommandRecords.uuid(input, "reserved_fact_uuid"), facts.single().uuid)
        assertEquals(Instant.parse(CountCommandRecords.text(input, "occurred_at")).toEpochMilli(), facts.single().actualCompletedAt)
        assertEquals(CountCommandRecords.text(input, "timezone"), facts.single().recordedTimezone)
        assertEquals(1, db.syncOutboxDao().getAll().size)
        val terminal = CountCommandStore(db, tokens, sessions).resolve(captured.reference.native())
        assertEquals(oldInput, terminal.historical.intent.text())
        val before = imageWithTemporarySequenceWitness()
        storage.reopen(); reopenedEditor()
        val again = creatingHabits().resumeCountCommand(app, captured.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(resumed.receipt.reference, again.receipt.reference)
        val after = image()
        val beforeTables = before.toMap(); val afterTables = after.toMap()
        val changed = (beforeTables.keys + afterTables.keys).filter { beforeTables[it] != afterTables[it] }
        assertTrue("Known committed outcome is read-only, not an automatic replay; changed main tables=$changed", before == after)
        assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
    }

    @Test(timeout = 150_000) fun businessChangeAfterNoEffectsObservationIsRecheckedBeforeAnyDomainCallback() = runBlocking<Unit> {
        val captured = prepared()
        captured.store.releaseUnadmitted(captured.ticket)
        val ready = captured.store.recover(captured.reference.native()) as CountCommandStore.Recovery.Ready
        val anchor = db.withTransaction { CountCommandRootAnchor.read(sql) }
        val rootBefore = db.withTransaction { CountCommandNativeRows(sql) {}.root() }
        db.withTransaction { sql.execSQL("UPDATE metrics SET name=name WHERE id=?", arrayOf(metric.id)) }
        val advanced = db.withTransaction { CountCommandRootAnchor.read(sql) }
        assertEquals(anchor.generation + 1, advanced.generation)
        assertEquals(anchor.hash, advanced.hash)
        assertTrue(CountCommandNativeRows.sameRoot(rootBefore, db.withTransaction { CountCommandNativeRows(sql) {}.root() }))
        db.withTransaction { com.dayforge.data.local.CountCommandStorageInitialization.verifyRootAnchor(sql) }
        // Root and anchor remain valid/matching: the following exact refusal must still
        // come from the original advanced-generation guard, never an earlier anchor fault.
        val before = image(); var entered = false
        val failure = try {
            captured.store.producerTransaction<Unit>(ready.ticket, requireNotNull(tokens.localCoreWriteAccess())) {
                entered = true; error("Domain callback must not be entered")
            }
            null
        } catch (error: CancellationException) { throw error }
        catch (error: IllegalArgumentException) { error }
        assertEquals("COMMAND_RECOVERY_REVALIDATION_REQUIRED", failure?.message)
        assertFalse(entered); assertTrue(before == image())
        assertTrue(db.completionDao().getAllCompletionsOnce().isEmpty())
        val retry = captured.store.recover(captured.reference.native())
        assertTrue(retry is CountCommandStore.Recovery.Unresolved)
        assertEquals("ADVANCED_BUSINESS_REQUIRES_COMPLETE_NO_EFFECTS_PROOF", (retry as CountCommandStore.Recovery.Unresolved).reason)
    }

    @Test(timeout = 150_000) fun recoveredTicketCannotBeDelegatedToAChildJobButOriginalRetryRemainsPossible() = runBlocking<Unit> {
        val captured = prepared()
        captured.store.releaseUnadmitted(captured.ticket)
        val ready = captured.store.recover(captured.reference.native()) as CountCommandStore.Recovery.Ready
        val before = image(); var entered = false
        val failure = supervisorScope {
            async {
                try {
                    captured.store.producerTransaction<Unit>(ready.ticket, requireNotNull(tokens.localCoreWriteAccess())) {
                        entered = true; error("Foreign caller cannot enter Room business")
                    }
                    null
                } catch (error: CancellationException) { throw error }
                catch (error: IllegalArgumentException) { error }
            }.await()
        }
        assertEquals("COMMAND_TICKET_CALLER_EXPIRED", failure?.message)
        assertFalse(entered); assertTrue(before == image())
        captured.store.releaseUnadmitted(ready.ticket)
        val result = creatingHabits().resumeCountCommand(app, captured.reference) as CountCommandRecovery.HistoricalCommitted
        assertEquals(captured.reference, result.receipt.reference)
        assertEquals(1, db.completionDao().getAllCompletionsOnce().size)
    }

    private suspend fun imageWithTemporarySequenceWitness(): List<Pair<String, List<List<Pair<Int, String?>>>>> = db.withTransaction {
        val main = image()
        val temporary = rows("SELECT * FROM temp.sqlite_sequence ORDER BY rowid")
        assertTrue("Actual primary T1 must retain its own TEMP journal sequence",
            temporary.any { it.first().second == "count_command_journal_events" })
        assertEquals("Unqualified sqlite_sequence really resolves to the TEMP namesake", temporary,
            rows("SELECT * FROM sqlite_sequence ORDER BY rowid"))
        assertNotEquals("The retained image must bind main.sqlite_sequence, not the live TEMP journal", temporary,
            main.single { it.first == "sqlite_sequence" }.second)
        main
    }

    private suspend fun image(): List<Pair<String, List<List<Pair<Int, String?>>>>> = db.withTransaction {
        (CountTrustSchema.BUSINESS_TABLES + CountCommandV20Schema.currentLocalTables + "sqlite_sequence").sorted().map { table ->
            table to rows("SELECT * FROM main.`$table` ORDER BY rowid")
        }
    }

    private fun rows(query: String): List<List<Pair<Int, String?>>> = sql.query(query).use { cursor -> buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
            val type = cursor.getType(index)
            type to when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toRawBits().toString()
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it.toInt() and 255) }
                else -> cursor.getString(index)
            }
        })
    } }
}
