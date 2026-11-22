package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real source claim + actual T0. No source permission or current replica is fabricated by
 * making a fresh Intent; invalid capture must leave the entire native main image unchanged.
 */
@RunWith(AndroidJUnit4::class)
class CountCommandRegistrationClaimTest : NextObjectEditorFixture() {
    private suspend fun widget(): Pair<ObjectEditAuthority, WidgetFactClaim> {
        tokens.getOrCreateInstallationId(); register()
        val current = requireNotNull(db.habitDao().getHabitById(habit.id))
        val claim = WidgetFactReader(db, tokens, sessions, preferences, CountHistoryReader(db, tokens, sessions)).read(current).claim
        assertNotNull(claim.storageId)
        return requireNotNull(editor.habit(habit.id).authority) to claim
    }
    private suspend fun input(authority: ObjectEditAuthority, claim: WidgetFactClaim, commandId: String = UUID.randomUUID().toString()) =
        CountCommandInputs.add(db, commandId, habit, authority, Instant.now(), ZoneId.systemDefault(), 1, claim)
    private suspend fun image() = db.withTransaction { NextReplicaStorageProof(db).readInTransaction().contents }
    private suspend fun refuses(intent: CountCommandRecords.Intent, reason: String) {
        val before = image(); val failure = rejected { CountCommandStore(db, tokens, sessions).register(intent) }
        assertEquals(reason, failure.message); assertEquals(before, image())
    }
    @Test(timeout = 150_000) fun missingOrOtherStorageCannotBeCopiedIntoCurrentIntentAndSameCallerMayRetry() = runBlocking<Unit> {
        val (authority, claim) = widget(); val commandId = UUID.randomUUID().toString()
        refuses(input(authority, claim.copy(storageId = null), commandId), "COMMAND_WIDGET_REPLICA_CHANGED")
        refuses(input(authority, claim.copy(storageId = id(9801)), commandId), "COMMAND_WIDGET_REPLICA_CHANGED")
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_status"))
        val valid = input(authority, claim, commandId); val store = CountCommandStore(db, tokens, sessions)
        val registered = store.register(valid); store.releaseUnadmitted(requireNotNull(registered.ticket))
        assertEquals(1, count("count_command_intents")); assertEquals(valid.text(), store.resolve(registered.reference).historical.intent.text())
        assertEquals(0, count("completions")); assertEquals(0, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun oldAccountSessionOrDeviceClaimIsRejectedBeforeAnyT0Contents() = runBlocking<Unit> {
        val (authority, claim) = widget()
        for (changed in listOf(claim.copy(accountId = id(9802)), claim.copy(generation = id(9803)), claim.copy(deviceId = id(9804)))) {
            refuses(input(authority, changed), "COMMAND_WIDGET_CAPTURE_CHANGED")
        }
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_status")); assertEquals(0, count("count_command_results"))
    }
    @Test(timeout = 150_000) fun realClearNewDatasetCannotRebindOldWidgetPayloadEvenBeforeT1() = runBlocking<Unit> {
        val (authority, oldClaim) = widget()
        db.clearAllData(); storage.reopen()
        val changed = input(authority, oldClaim)
        assertNotEquals(oldClaim.storageId, changed.namespace.storage)
        refuses(changed, "COMMAND_WIDGET_REPLICA_CHANGED")
        assertEquals(0, count("count_command_intents")); assertEquals(0, count("count_command_status")); assertEquals(0, count("count_command_results"))
        assertEquals(0, count("completions")); assertEquals(0, count("habits"))
    }
}
