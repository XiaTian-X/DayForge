package com.dayforge.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complete native/current-state and owner boundary matrix. Selective marker restoration
 * is an explicit hostile fixture: it does not model an admitted writer or extend trust.
 * Each negative case preserves the invalid image and proves no attempted repair/commit.
 */
@RunWith(AndroidJUnit4::class)
class G0CountRootWriteBoundaryTest : G0CountRootWriteFixture() {
    private fun corruptPrimaryKeepingOldSeal(edit: (SQLiteDatabase) -> Unit) {
        val previous = head()
        editRootFile { raw ->
            raw.execSQL("UPDATE sync_control SET suppressOutbox=1 WHERE id=1")
            edit(raw)
            raw.execSQL("UPDATE sync_control SET suppressOutbox=0 WHERE id=1")
            restoreMarkerForFault(raw, previous.marker)
        }
        val after = head()
        assertEquals(previous.root, after.root); assertEquals(previous.marker, after.marker)
        assertArrayEquals(previous.packet.authenticationBytes(), after.packet.authenticationBytes())
    }

    @Test fun aValidReadOnlyRootWithoutAuthenticatedCurrentNeverGrantsWritePermission() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        openIssuer(http) // Same HMAC/schema, but intentionally the old B3a issuer without C.
        val reader = openRootReader().reader
        assertEquals(0L, rootDay(reader, seed.habits[0].uuid, seed.today).day.quantity)
        val before = pageNativeState(); var attempted: G0CountRootStore? = null
        rejectedUnchangedRoot { openWriter { attempted = it } }
        assertTrue(runCatching { requireNotNull(attempted).increase(seed.habits[0].uuid) }.isFailure)
        assertEquals(before, pageNativeState()); assertEquals(0, pageRowCount("completions")); head()
    }

    @Test fun completeHabitFieldsRemainDependenciesEvenWhenHeaderAndMarkerMatch() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val clean = rootImage()
        val changes = listOf<(SQLiteDatabase) -> Unit>(
            { it.execSQL("UPDATE habits SET description=description || ' altered' WHERE id=?", arrayOf(seed.habits[0].id)) },
            { it.execSQL("UPDATE habits SET activityRate=activityRate-1 WHERE id=?", arrayOf(seed.habits[1].id)) }
        )
        for (change in changes) {
            restoreRootImage(clean); corruptPrimaryKeepingOldSeal(change)
            rejectedUnchangedRoot { openWriter() }; closeFailedRootOwner()
        }
    }

    @Test fun sqliteSequenceAdvanceAndDuplicateRowsCannotBorrowTheAuthenticatedAllocator() = runBlocking<Unit> {
        seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val clean = rootImage()
        val mutations = listOf<(SQLiteDatabase) -> Unit>(
            { it.execSQL("UPDATE sqlite_sequence SET seq=seq+1 WHERE name='completions'") },
            { it.execSQL("INSERT INTO sqlite_sequence(name,seq) SELECT name,seq FROM sqlite_sequence WHERE name='sync_outbox'") }
        )
        for (mutation in mutations) {
            restoreRootImage(clean); val previous = head(); editRootFile(mutation)
            assertEquals(previous.root, head().root) // sequence is not covered by dirty triggers.
            rejectedUnchangedRoot { openWriter() }; closeFailedRootOwner()
        }
    }

    @Test fun aPastActivityWindowFactMustMatchNativeContentBeforeFirstIncrease() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2, daysAgo = listOf(1, 2)); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http)
        corruptPrimaryKeepingOldSeal { it.execSQL("UPDATE completions SET value=value+1 WHERE id=?", arrayOf(seed.factIds[0])) }
        val invalid = pageNativeState()
        // Cold current checks alone legitimately do not read this old fact. The first +1
        // must include it because the original activity-rate window depends on this day.
        val store = openWriter().store
        rejectedUnchangedRoot { store.increase(seed.habits[0].uuid) }
        assertEquals(invalid, pageNativeState()); assertEquals(2, pageRowCount("completions"))
        assertEquals(0, pageRowCount("sync_outbox")); closeFailedRootOwner(); reopenReference()
        rejectedUnchangedRoot { CountHistoryReader(db, tokens, sessions).read(
            requireNotNull(db.habitDao().getHabitById(seed.habits[0].id)), seed.today) }
    }

    @Test fun aDuplicateUuidInAnAffectedDateRangeCannotHideBehindPointLookupOrOldSequence() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(2); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val allocator = nativeRoot { raw ->
            raw.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='completions'", null).use {
                check(it.moveToFirst()); it.getLong(0).also { _ -> check(!it.moveToNext()) }
            }
        }
        corruptPrimaryKeepingOldSeal { raw ->
            val columns = G0DomainSqlCatalog.columns.getValue("completions")
            val projection = columns.joinToString(",") { if (it == "id") "id+100" else "`$it`" }
            raw.execSQL("INSERT INTO completions (${columns.joinToString(",") { "`$it`" }}) SELECT $projection FROM completions WHERE id=?", arrayOf(seed.factIds[0]))
            raw.execSQL("UPDATE sqlite_sequence SET seq=? WHERE name='completions'", arrayOf(allocator))
        }
        val invalid = pageNativeState(); val store = openWriter().store
        rejectedUnchangedRoot { store.increase(seed.habits[0].uuid) }
        assertEquals(invalid, pageNativeState()); assertEquals(3, pageRowCount("completions"))
        assertEquals(0, pageRowCount("sync_outbox"))
    }

    @Test fun missingCurrentDayWithRetainedOriginCannotBeRecapturedAsANewBirth() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val writer = openWriter().store
        val saved = writer.increase(seed.habits[0].uuid)
        // Keep the source and pending command but remove the native projection. The Q day
        // and source remain signed, so this specifically tests the operation's native closure.
        corruptPrimaryKeepingOldSeal { raw -> raw.execSQL("DELETE FROM count_days WHERE habitId=? AND localDate=?",
            arrayOf<Any?>(seed.habits[0].id, seed.today.toString())) }
        val invalid = pageNativeState(); closeFailedRootOwner()
        var wrote = false
        val failure = runCatching {
            val reopened = openWriter().store
            reopened.increase(seed.habits[0].uuid); wrote = true
        }.exceptionOrNull()
        assertNotNull("cold admission or first use must reject the missing native day", failure)
        assertFalse(wrote)
        assertEquals(invalid, pageNativeState()); assertEquals(1, pageRowCount("completions"))
        assertEquals(1, pageRowCount("next_request_origins")); assertEquals(0, pageRowCount("count_days"))
        assertEquals(1, pageRowCount("sync_outbox"))
        val retained = nativeRoot { raw -> raw.rawQuery(
            "SELECT requestId FROM next_request_origins WHERE kind=? AND requestId=?", arrayOf(NEXT_OPERATION, saved.requestId)).use {
            check(it.moveToFirst()); it.getString(0).also { _ -> check(!it.moveToNext()) }
        } }
        assertEquals(saved.requestId, retained)
    }

    @Test fun aReceiptFollowedByReinsertedOutboxCannotPassCurrentMembershipChecks() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val writer = openWriter().store; val saved = writer.increase(seed.habits[0].uuid)
        writerAck(writer, http, saved)
        val nextId = nativeRoot { raw -> raw.rawQuery("SELECT seq FROM sqlite_sequence WHERE name='sync_outbox'", null).use {
            check(it.moveToFirst()); it.getLong(0)
        } }
        corruptPrimaryKeepingOldSeal { raw ->
            raw.execSQL("INSERT INTO sync_outbox (id,operationId,recordType,entityUuid,wireEntityUuid,action,referenceUuid,payloadJson,baseRevision,basePayloadJson,attemptedAt,attemptCount,lastError,errorCode,deadLetteredAt,createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(nextId, saved.requestId, "completion", saved.factUuid, saved.factUuid, "upsert", saved.habitUuid,
                    null, null, null, null, 0L, null, null, null, 1L))
        }
        rejectedUnchangedRoot { openWriter() }
        assertEquals(1, pageRowCount("next_acceptances")); assertEquals(1, pageRowCount("sync_outbox"))
    }

    @Test fun extraHeaderAndSameValueRootEffectsAreRejectedByTheCompleteActualJournal() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val clean = rootImage()
        for (extraHeader in listOf(true, false)) {
            restoreRootImage(clean); val store = openWriter().store
            store.beforeFinalForTest = {
                val sql = db.openHelper.writableDatabase
                if (extraHeader) {
                    val current = sql.query(G0CountRootSql.readSeal).use { requireNotNull(G0CountRootSql.captureSeal(it)) }
                    sql.execSQL(G0CountRootSql.updateHeader, G0CountRootSql.updateValues(current, current).toTypedArray())
                } else {
                    val root = sql.query(G0CountRootSql.readBoundedRoot).use { requireNotNull(G0CountRootSql.captureRootRow(it)) }
                    sql.execSQL(G0CountBlockSql.updateRoot,
                        (root.values().drop(1) + listOf(root.binding, root.root.digest, root.revision)).toTypedArray())
                    val marker = sql.query(G0CountRootSql.readMarker).use { requireNotNull(G0CountRootSql.captureMarker(it)) }
                    sql.execSQL(G0CountRootSql.clearMarker, arrayOf(marker.generation))
                }
            }
            rejectedUnchangedRoot { store.increase(seed.habits[0].uuid) }
            assertEquals(0, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox")); head()
        }
    }

    @Test fun cancelledPreparationAndFinalTransactionCannotProducePartialPOrQ() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val clean = rootImage()
        for (finalBoundary in listOf(false, true)) {
            restoreRootImage(clean); val store = openWriter().store; val before = pageNativeState()
            val entered = CompletableDeferred<Unit>()
            val wait: suspend () -> Unit = { entered.complete(Unit); awaitCancellation() }
            if (finalBoundary) store.beforeFinalForTest = wait else store.afterPrepareForTest = wait
            val task = launch(Dispatchers.IO) { store.increase(seed.habits[0].uuid) }
            withTimeout(15_000) { entered.await() }; task.cancelAndJoin()
            assertTrue(task.isCancelled); assertEquals(before, pageNativeState())
            assertEquals(0, pageRowCount("completions")); assertEquals(0, pageRowCount("sync_outbox")); head()
        }
    }

    @Test fun authorityAbaBetweenPreparationAndCommitRejectsTheOldCommand() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store; val before = pageNativeState()
        store.afterPrepareForTest = { authorityAba() }
        try { rejectedUnchangedRoot { store.increase(seed.habits[0].uuid) } }
        finally { store.afterPrepareForTest = {} }
        assertEquals(before, pageNativeState()); assertEquals(0, pageRowCount("completions")); head()
    }

    @Test fun aSameValueExternalCommitAfterPreparationStillInvalidatesTheConnection() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val store = openWriter().store; val signed = head()
        var afterFault: Map<String, List<String>>? = null
        store.afterPrepareForTest = {
            editRootFile { raw ->
                raw.execSQL("UPDATE ${G0CountBlockSql.root} SET revision=revision WHERE slot=1")
                // The same-value Q write changes SQLite data_version, even after the
                // old marker is restored and every compared root/header byte matches.
                restoreMarkerForFault(raw, signed.marker)
            }
            val identical = head()
            assertEquals(signed.root, identical.root); assertEquals(signed.marker, identical.marker)
            assertArrayEquals(signed.packet.authenticationBytes(), identical.packet.authenticationBytes())
            afterFault = pageNativeState()
        }
        val failure = runCatching { store.increase(seed.habits[0].uuid) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(hasCause(requireNotNull(failure), "G0_DOMAIN_EXTERNAL_CHANGE"))
        assertNotNull(afterFault); assertEquals(afterFault, pageNativeState())
        assertEquals(0, pageRowCount("completions"))
    }

    @Test fun unknownColdEndCannotGrantWriteQualificationOrReuseItsRetiredOwner() = runBlocking<Unit> {
        val seed = seedHouseholdAccepted(0); val (http, _) = channel { successReply(it) }
        issueWriteRoot(http); val before = pageNativeState(); var attempted: G0CountRootStore? = null
        val trace = G0PhaseTrace(driverTiming = false)
        val failure = rejectedUnchangedRoot { trace.run { openWriter {
            attempted = it; it.beforeColdFinalForTest = { endFaults.failAfterNextEndIn(trace) }
        } } }
        assertTrue(hasCause(failure, "G0_COMMIT_RESULT_LOST"))
        assertTrue(runCatching { requireNotNull(attempted).increase(seed.habits[0].uuid) }.isFailure)
        assertEquals(before, pageNativeState()); closeFailedRootOwner()
        val recovered = openWriter().store; writerAck(recovered, http, recovered.increase(seed.habits[0].uuid))
        assertEquals(1, pageRowCount("completions")); assertWriterMatchesOriginal(recovered, seed.today)
    }
}
