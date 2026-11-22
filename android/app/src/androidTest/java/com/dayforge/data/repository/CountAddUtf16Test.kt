package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountAddUtf16Test : CountTrustFormalProfileFixture() {
    private val spy = SourceWindowNativeSpy(utf16 = true)
    override fun sourceWindowNativeSpy() = spy

    @Test fun closedFirstAndExistingDayEffectsUseActualUtf16NativeEncoding() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        db.withTransaction { db.openHelper.writableDatabase.query("PRAGMA main.encoding").use {
            assertTrue(it.moveToFirst()); assertEquals("UTF-16le", it.getString(0)); assertFalse(it.moveToNext())
        } }
        repeat(2) {
            val row = requireNotNull(db.habitDao().getHabitById(habit.id))
            val result = editor.addCountPrepared(row, requireNotNull(editor.habit(row.id).authority), null,
                Instant.now(), ZoneId.systemDefault())
            assertTrue(result is CountAddExecutor.Result.Committed && result.id > 0)
        }
        val originals = RecurringCompletionEvidenceReader(db, tokens, sessions).read(requireNotNull(db.habitDao().getHabitById(habit.id)))
        assertEquals(2, originals.completions.size)
        assertEquals(2L, CountHistoryReader(db, tokens, sessions).readSummary(requireNotNull(db.habitDao().getHabitById(habit.id))).totalQuantity)
        for (source in db.syncOutboxDao().getAll()) {
            val origin = requireNotNull(db.nextRequestDao().origin(NEXT_OPERATION, source.operationId))
            assertEquals(NextRequestSql.sourceHash(source), origin.sourceHash)
            assertEquals(CountAddOriginHash.hash(origin), NextRequestSql.rowHash(db, "next_request_origins",
                "kind=? AND requestId=?", arrayOf(NEXT_OPERATION, source.operationId)))
        }
    }
}
