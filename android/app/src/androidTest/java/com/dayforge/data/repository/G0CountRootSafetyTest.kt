package com.dayforge.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.history.HistoryBlockCodec.Code
import com.dayforge.data.history.HistoryBlockCodec.Rejected
import java.lang.reflect.Modifier
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** B3a only: original full-validated issuer maintains P/Q/H, while a reopened owner can read
 * authenticated Q on demand. These tests do NOT grant cold writes, eliminate integrity_check,
 * establish P-currentness under selective replay, measure UI latency or simulate process death. */
@RunWith(AndroidJUnit4::class)
class G0CountRootSafetyTest : G0CountRootFixture() {
    private suspend fun aba() {
        val old = dataStore.data.first()
        dataStore.edit { it[stringPreferencesKey("sync_device_capability_revision")] = "2" }
        dataStore.updateData { old }
        assertEquals(old, dataStore.data.first())
    }
    private fun causedBy(error: Throwable, text: String): Boolean =
        generateSequence(error) { it.cause }.any { it.message?.contains(text) == true }

    @Test fun originalIssuerMaintainsEveryRealStageAndReopenedReaderMatchesOriginalDayResults() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        val store = openIssuer(http); val initial = head()
        assertEquals(1L, initial.root.revision)
        val saved = store.increase(seed.habits[0].uuid); val written = head()
        assertEquals(initial.root.revision + 1, written.root.revision)
        assertTrue(written.marker.generation > initial.marker.generation)
        val delivery = requireNotNull(store.send(http, saved.requestId)); val sent = head()
        assertEquals(written.root.revision + 1, sent.root.revision)
        assertEquals(NextOperationAcceptance.COMMITTED, store.acknowledge(delivery)); val accepted = head()
        assertEquals(sent.root.revision + 1, accepted.root.revision)
        val durable = pageNativeState()
        assertEquals(NextOperationAcceptance.REPLAYED, store.acknowledge(delivery))
        assertEquals(durable, pageNativeState()) // replay must not re-sign or advance the marker.
        val complete = householdView(store)
        reopenReference(); sameHouseholdAsOriginal(complete)
        assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
        assertEquals(durable, pageNativeState())
        val beforeAuth = decryptCount.get()
        val coldLedger = G0ColdWorkLedger(); val coldTrace = G0PhaseTrace(driverTiming = false)
        val opened = coldTrace.run { coldLedger.run { openRootReader() } }
        assertEquals(0, coldLedger.blockNodeQueries)
        assertTrue(coldLedger.wholeTableQueries.isEmpty())
        for (phase in listOf("count_complete_history", "count_block_snapshot", "count_block_build", "count_block_node_decode"))
            assertEquals("cold phase $phase", 0, coldTrace.count(phase))
        assertEquals(G0RoomColdOwnerFactory.Phase.READY, opened.owner.phase)
        assertEquals(1, opened.owner.actualEnds)
        for (row in seed.habits) for (date in listOf(seed.today, seed.today.minusDays(1))) {
            val result = rootDay(opened.reader, row.uuid, date)
            assertTrue(result.valid()); assertEquals(accepted.root.root.digest, result.rootDigest)
            assertRootDay(complete.cards.getValue(row.uuid).history, result.day)
            assertTrue(requireNotNull(opened.reader.lastSourceStats).queries > 0)
        }
        assertTrue(decryptCount.get() > beforeAuth)
        assertEquals(durable, pageNativeState()); assertEquals(3, pageRowCount("completions"))
        assertEquals(0, pageRowCount("sync_outbox")); assertEquals(3, pageRowCount("next_acceptances"))
        assertEquals(1, store.incrementalFullBuilds); assertEquals(3, store.incrementalUpdates)
    }

    @Test fun unmaintainedPrimaryWriteDurablyDirtiesTheRootAndColdReaderCannotRepairIt() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val previous = head()
        editRootFile { it.execSQL("UPDATE habits SET name=name WHERE id=?", arrayOf(seed.habits[0].id)) }
        val marker = nativeRoot { raw -> raw.rawQuery(G0CountRootSql.readMarker, null).use { requireNotNull(G0CountRootSql.captureMarker(it)) } }
        assertTrue(marker.dirty); assertTrue(marker.generation > previous.marker.generation)
        var attempted: G0CountRootReader? = null
        rejectedUnchangedRoot { openRootReader { attempted = it } }
        var published = false
        rejectedUnchangedRoot { requireNotNull(attempted).readDay(seed.habits[0].uuid, seed.today) { published = true } }
        assertFalse(published)
    }

    @Test fun missingMalformedHeaderRootAndKeyNeverCreateAColdPermissionOrNewKey() = runBlocking<Unit> {
        seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val marker = head().marker; val clean = rootImage()
        val mutations = listOf<(android.database.sqlite.SQLiteDatabase) -> Unit>(
            { it.execSQL("DELETE FROM ${G0CountRootSql.sealTable}") },
            { it.execSQL("UPDATE ${G0CountRootSql.sealTable} SET mac=? WHERE slot=1", arrayOf(ByteArray(32))) },
            { it.execSQL("UPDATE ${G0CountRootSql.sealTable} SET format=format+1 WHERE slot=1") },
            { raw -> raw.execSQL("UPDATE ${G0CountBlockSql.root} SET revision=revision+1 WHERE slot=1"); restoreMarkerForFault(raw, marker) }
        )
        for (mutation in mutations) {
            restoreRootImage(clean); editRootFile(mutation)
            rejectedUnchangedRoot { openRootReader() }; closeFailedRootOwner()
        }
        restoreRootImage(clean)
        val signed = head().packet
        deleteRootKey(); assertFalse(rootSeal.match(signed, signed.binding))
        rejectedUnchangedRoot { openRootReader() }
        assertFalse(rootSeal.match(signed, signed.binding)) // matching must not enroll a replacement key.
    }

    @Test fun untouchedOldMonthDamageIsDeferredButAccessingItsExactPathMustReject() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2, daysAgo = listOf(40, 400)); val (http, _) = channel { successReply(it) }
        openIssuer(http)
        val row = seed.habits[0]; val oldDate = seed.datesByObject.getValue(row.uuid).single()
        val selected = storedUnit("F", row.uuid, YearMonth.from(oldDate)); val previous = head()
        assertEquals(0, selected.height) // precise one-fact leaf, not an accidentally shared directory node.
        val broken = rootNodeBytes(selected.digest).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        editRootFile { raw ->
            raw.execSQL("UPDATE ${G0CountBlockSql.nodes} SET body=? WHERE digest=?", arrayOf<Any?>(broken, selected.digest))
            restoreMarkerForFault(raw, previous.marker)
        }
        // Explicit untracked-byte-corruption model: SQL dirty was restored only by this hostile
        // fixture. Normal controlled Q writes are covered by the separate dirty rejection.
        val damaged = pageNativeState(); val ledger = G0ColdWorkLedger()
        val opened = ledger.run { openRootReader() }
        assertEquals(0, ledger.blockNodeQueries); assertTrue(ledger.wholeTableQueries.isEmpty())
        val current = rootDay(opened.reader, row.uuid, seed.today)
        assertRootDay(seed.references.getValue(row.uuid), current.day); assertTrue(current.valid())
        var published = false
        val error = rejectedUnchangedRoot { opened.reader.readDay(row.uuid, oldDate) { published = true } }
        assertTrue(generateSequence(error) { it.cause }.any { it is Rejected && it.code == Code.HASH_MISMATCH })
        assertFalse(published); assertEquals(damaged, pageNativeState())
    }

    @Test fun matchingRootStillRequiresFreshColdAndFinalPublicationAuthentication() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val durable = pageNativeState()
        var coldReached = false
        try {
            rejectedUnchangedRoot { openRootReader { reader -> reader.beforeColdFinalForTest = {
                coldReached = true; failDecrypt = true
            } } }
        } finally { failDecrypt = false }
        assertTrue(coldReached); closeFailedRootOwner()
        val reader = openRootReader().reader
        val old = rootDay(reader, seed.habits[0].uuid, seed.today)
        val decrypts = decryptCount.get(); var published = false; var finalReached = false
        reader.beforePublishForTest = { finalReached = true; failDecrypt = true }
        try { rejectedUnchangedRoot { reader.readDay(seed.habits[0].uuid, seed.today) { published = true } } }
        finally { failDecrypt = false; reader.beforePublishForTest = {} }
        assertTrue(finalReached); assertTrue(decryptCount.get() > decrypts)
        assertFalse(published); assertFalse(old.valid()); assertEquals(durable, pageNativeState())
    }

    @Test fun permissionAbaAndExpiredRealReadLeaseCannotAuthorizeAnotherPublication() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val reader = openRootReader().reader
        val old = rootDay(reader, seed.habits[0].uuid, seed.today)
        reader.beforePublishForTest = { aba() }; var published = false
        try { rejectedUnchangedRoot { reader.readDay(seed.habits[0].uuid, seed.today) { published = true } } }
        finally { reader.beforePublishForTest = {} }
        assertFalse(published); assertFalse(old.valid())
        closeFailedRootOwner()
        openRootReader()
        lateinit var expiredBoundary: () -> Unit
        domainGate.transaction(db, G0DomainSqlGate.Phase.READ) {
            expiredBoundary = domainGate.captureBlockReadBoundary()
            expiredBoundary()
        }
        // A different real Gate READ is active on the same Room connection. A mere
        // inTransaction test would pass, but the old private lease must remain unusable.
        val failure = rejectedUnchangedRoot {
            domainGate.transaction(db, G0DomainSqlGate.Phase.READ) { expiredBoundary() }
        }
        assertTrue(generateSequence(failure) { it.cause }.any { it is G0DomainSqlGate.Denied })
    }

    @Test fun cancellationDuringColdAndPublicationCannotInstallOrPublishPartialResults() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val durable = pageNativeState()
        val coldEntered = CompletableDeferred<Unit>(); var attempted: G0CountRootReader? = null
        val cold = launch(Dispatchers.IO) { openRootReader { reader ->
            attempted = reader; reader.beforeColdFinalForTest = { coldEntered.complete(Unit); awaitCancellation() }
        } }
        withTimeout(15_000) { coldEntered.await() }; cold.cancelAndJoin()
        assertTrue(cold.isCancelled); assertEquals(durable, pageNativeState())
        var published = false
        rejectedUnchangedRoot { requireNotNull(attempted).readDay(seed.habits[0].uuid, seed.today) { published = true } }
        assertFalse(published); closeFailedRootOwner()
        val reader = openRootReader().reader; val entered = CompletableDeferred<Unit>()
        reader.beforePublishForTest = { entered.complete(Unit); awaitCancellation() }
        val read = launch(Dispatchers.IO) { reader.readDay(seed.habits[0].uuid, seed.today) { published = true } }
        withTimeout(15_000) { entered.await() }; read.cancelAndJoin(); reader.beforePublishForTest = {}
        assertTrue(read.isCancelled); assertFalse(published); assertEquals(durable, pageNativeState())
    }

    @Test fun unknownColdEndCannotIssueAReadPermissionAndRequiresAnActuallyClosedOwner() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val durable = pageNativeState(); var attempted: G0CountRootReader? = null
        val trace = G0PhaseTrace(driverTiming = false); endFaults.failAfterNextEndIn(trace)
        val failure = rejectedUnchangedRoot { trace.run { openRootReader { attempted = it } } }
        assertTrue(causedBy(failure, "G0_COMMIT_RESULT_LOST"))
        var published = false
        rejectedUnchangedRoot { requireNotNull(attempted).readDay(seed.habits[0].uuid, seed.today) { published = true } }
        assertFalse(published)
        val retired = domainGate
        assertTrue(runCatching { freshGateForColdOpen() }.isFailure); assertSame(retired, domainGate)
        closeFailedRootOwner()
        val recovered = openRootReader().reader
        assertTrue(rootDay(recovered, seed.habits[0].uuid, seed.today).valid())
        assertEquals(durable, pageNativeState())
    }

    @Test fun externalCommitAfterColdEndOrReadDecodeBlocksTheFirstPersonalPublication() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http); val clean = rootImage()
        var afterFault: Map<String, List<String>>? = null
        val cold = runCatching { openRootReader { reader -> reader.afterColdEndForTest = {
            editRootFile { it.execSQL("UPDATE ${G0CountBlockSql.root} SET revision=revision+1 WHERE slot=1") }
            afterFault = pageNativeState()
        } } }
        var published = false
        // Implementations may detect the data_version change while handing off or on the first
        // controlled READ, but neither point is allowed to publish the old Q result.
        cold.getOrNull()?.let { opened ->
            assertTrue(runCatching { opened.reader.readDay(seed.habits[0].uuid, seed.today) { published = true } }.isFailure)
        }
        assertFalse(published); assertNotNull(afterFault); assertEquals(afterFault, pageNativeState())
        restoreRootImage(clean)
        val reader = openRootReader().reader
        val old = rootDay(reader, seed.habits[0].uuid, seed.today)
        val previousHead = head(); var memoryOnlyStillCurrent = false
        reader.beforePublishForTest = {
            editRootFile { raw ->
                raw.execSQL("UPDATE ${G0CountBlockSql.root} SET revision=revision WHERE slot=1")
                restoreMarkerForFault(raw, previousHead.marker)
            }
            val same = head()
            assertEquals(previousHead.root, same.root); assertEquals(previousHead.marker, same.marker)
            assertArrayEquals(previousHead.packet.bodyBytes(), same.packet.bodyBytes())
            assertArrayEquals(previousHead.packet.authenticationBytes(), same.packet.authenticationBytes())
            // H/root/marker comparisons and the memory epoch all still match. Only the real
            // connection continuity check can reject this externally committed snapshot.
            memoryOnlyStillCurrent = old.valid()
            afterFault = pageNativeState()
        }
        val error = try { runCatching { reader.readDay(seed.habits[0].uuid, seed.today) { published = true } }.exceptionOrNull() }
            finally { reader.beforePublishForTest = {} }
        assertTrue(memoryOnlyStillCurrent); assertNotNull(error)
        assertTrue(causedBy(requireNotNull(error), "G0_DOMAIN_EXTERNAL_CHANGE"))
        assertFalse(published); assertFalse(old.valid()); assertEquals(afterFault, pageNativeState())
    }

    @Test fun unexpectedActualHeaderOrMarkerWriteRollsBackTheWholeBusinessCommit() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        val base = rootImage()
        for (extraHeader in listOf(true, false)) {
            restoreRootImage(base); val store = openIssuer(http); val durable = pageNativeState()
            store.beforeFinalForTest = {
                val sql = db.openHelper.writableDatabase
                if (extraHeader) {
                    val current = sql.query(G0CountRootSql.readSeal).use { requireNotNull(G0CountRootSql.captureSeal(it)) }
                    sql.execSQL(G0CountRootSql.updateHeader, G0CountRootSql.updateValues(current, current).toTypedArray())
                } else {
                    // A permitted same-value Q CAS causes a REAL dirty-trigger increment after
                    // the legitimate clear. Clear it again so final bytes look plausible;
                    // the complete actual journal must still reject the extra Q+marker effects.
                    val root = sql.query(G0CountRootSql.readBoundedRoot).use { requireNotNull(G0CountRootSql.captureRootRow(it)) }
                    sql.execSQL(G0CountBlockSql.updateRoot,
                        (root.values().drop(1) + listOf(root.binding, root.root.digest, root.revision)).toTypedArray())
                    val marker = sql.query(G0CountRootSql.readMarker).use { requireNotNull(G0CountRootSql.captureMarker(it)) }
                    assertTrue(marker.dirty)
                    sql.execSQL(G0CountRootSql.clearMarker, arrayOf(marker.generation))
                }
            }
            rejectedUnchangedRoot { store.increase(seed.habits[0].uuid) }
            assertEquals(durable, pageNativeState()); assertEquals(0, pageRowCount("completions"))
            assertEquals(0, pageRowCount("sync_outbox")); head()
        }
    }

    @Test fun unknownBusinessEndsKeepFactsAndSignedRootAtomicWithoutGivingColdWriteAuthority() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        val base = rootImage()
        for (ack in listOf(false, true)) {
            restoreRootImage(base); val store = openIssuer(http)
            val command = store.newCommand(seed.habits[0].uuid)
            val delivery = if (ack) {
                val saved = store.increase(command); requireNotNull(store.send(http, saved.requestId))
            } else null
            val before = head(); val trace = G0PhaseTrace(driverTiming = false)
            endFaults.failAfterNextEndIn(trace)
            val failure = runCatching { trace.run {
                if (delivery == null) store.increase(command) else store.acknowledge(delivery)
            } }.exceptionOrNull()
            assertNotNull(failure); assertTrue(causedBy(requireNotNull(failure), "G0_COMMIT_RESULT_LOST"))
            val committed = head(); assertEquals(before.root.revision + 1, committed.root.revision)
            assertEquals(1, pageRowCount("completions")); assertEquals(if (ack) 0 else 1, pageRowCount("sync_outbox"))
            assertTrue(runCatching { store.increase(command) }.isFailure)
            val durable = pageNativeState()
            val reader = openRootReader().reader
            val value = rootDay(reader, command.habitUuid, seed.today)
            assertEquals(1L, value.day.quantity); assertEquals(listOf(command.factUuid), value.day.facts.map { it.uuid })
            assertEquals(setOf(command.requestId), value.day.sourceRequestIds)
            assertEquals(committed.root.root.digest, value.rootDigest); assertEquals(durable, pageNativeState())
            reopenReference()
            if (delivery != null) assertEquals(NextOperationAcceptance.REPLAYED, sender(http).acceptOperation(delivery))
            val row = requireNotNull(db.habitDao().getHabitById(seed.habits[0].id))
            assertRootDay(CountHistoryReader(db, tokens, sessions).read(row, seed.today), value.day)
            assertEquals(durable, pageNativeState())
        }
    }

    @Test fun selectivePrimaryAndMarkerReplayIsAnExplicitReadOnlyBoundaryNotAWriteProof() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        openIssuer(http); val signed = head(); val selectedId = seed.factIds.first()
        editRootFile { raw ->
            raw.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            raw.execSQL("UPDATE completions SET value=value+1 WHERE id=?", arrayOf(selectedId))
            raw.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            restoreMarkerForFault(raw, signed.marker)
        }
        val changed = pageNativeState(); val reader = openRootReader().reader
        val row = seed.habits[0]; val date = seed.datesByObject.getValue(row.uuid).single()
        val view = rootDay(reader, row.uuid, date)
        assertTrue(view.valid()); assertRootDay(seed.references.getValue(row.uuid), view.day)
        // H authenticates the original Q bytes; this malicious selective replay deliberately
        // changed P without changing Q. No assertion says native P is current or safe to write.
        assertEquals(1L, view.day.quantity)
        assertTrue(reader.javaClass.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
            .none { method -> listOf("increase", "send", "acknowledge", "recover").any { method.name.startsWith(it) } })
        assertEquals(changed, pageNativeState())
        reopenReference()
        val current = requireNotNull(db.habitDao().getHabitById(row.id))
        assertTrue(runCatching { CountHistoryReader(db, tokens, sessions).read(current, seed.today) }.isFailure)
        assertEquals(changed, pageNativeState())
    }
}
