package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The new finite participant inherits original actual transaction, Job and auth gates. */
@RunWith(AndroidJUnit4::class)
class CountDisplayScopeTest : CountTrustFormalProfileFixture() {
    @Test fun finiteReadCannotReuseAScopeAfterItsActualReadTransactionEnds() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val counts = CountHistoryReader(db, tokens, sessions)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        lateinit var escaped: RecurringDisplayReadScope
        val value = RecurringDisplayReadScope.withSnapshot(db, tokens, sessions) { scope ->
            escaped = scope; counts.readProjectionInTransaction(habit, today, zone, scope)
        }
        assertEquals(0L, value.todayQuantity)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        val failure = try { db.withTransaction { counts.readProjectionInTransaction(habit, today, zone, escaped) }; null }
            catch (error: IllegalStateException) { error }
        assertEquals("DISPLAY_READ_SCOPE_CLOSED", failure?.message)
        images.assertUnchanged(before)
    }
    @Test fun childJobCannotBorrowTheFiniteParticipantEvenOnTheSameReadSnapshot() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val counts = CountHistoryReader(db, tokens, sessions)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        RecurringDisplayReadScope.withSnapshot(db, tokens, sessions) { scope ->
            val error = try { withContext(Dispatchers.Default) { counts.readProjectionInTransaction(habit, today, zone, scope) }; null }
                catch (failure: IllegalStateException) { failure }
            assertEquals("DISPLAY_READ_SCOPE_CLOSED", error?.message)
            assertEquals(0L, counts.readProjectionInTransaction(habit, today, zone, scope).todayQuantity)
        }
        images.assertUnchanged(before)
    }
    @Test fun lostAuthorityAfterFiniteCalculationRejectsTheWholeOriginalReadExit() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val counts = CountHistoryReader(db, tokens, sessions)
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val images = CountConsumerReadFixtureAssertions(db, tokens, sessions); val before = images.capture()
        var computed = false
        val failure = try {
            RecurringDisplayReadScope.withSnapshot(db, tokens, sessions) { scope ->
                assertEquals(0L, counts.readProjectionInTransaction(habit, today, zone, scope).todayQuantity)
                computed = true
                tokens.clearTokens()
            }
            null
        } catch (error: IllegalStateException) { error }
        assertTrue(computed); assertEquals("RECURRING_READ_SESSION_CHANGED", failure?.message)
        images.assertUnchanged(before)
        assertNotNull(CountTrustRuntime.forDatabase(db))
    }
}
