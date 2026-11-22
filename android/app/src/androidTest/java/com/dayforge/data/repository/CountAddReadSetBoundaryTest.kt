package com.dayforge.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.data.local.CountTrustRuntime
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountAddReadSetBoundaryTest : CountTrustFormalProfileFixture() {
    private suspend fun failure(block: suspend () -> Unit): IllegalStateException {
        try { block() } catch (error: IllegalStateException) { return error }
        throw AssertionError("Expected closed preparation lifetime rejection")
    }

    @Test fun childJobCannotBorrowBuilderAndSealCannotBeRepeated() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val access = requireNotNull(tokens.localCoreWriteAccess())
        db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
                val current = requireNotNull(context)
                val builder = CountAddReadSet.Builder.create(owner, current)
                val child = coroutineScope { async { failure { builder.leaf("format") } }.await() }
                assertEquals("COUNT_ADD_FREEZE_EXPIRED", child.message)
                builder.rows(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", mapOf("uuid" to habit.uuid))
                val sealed = builder.seal()
                assertEquals("COUNT_ADD_FREEZE_EXPIRED", failure { builder.seal() }.message)
                assertTrue(sealed.matchesCurrent(owner, current))
            }
        }
    }

    @Test fun builderDoesNotSurviveActualEndButDeepValuesCanRevalidateInNewTransaction() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val access = requireNotNull(tokens.localCoreWriteAccess())
        lateinit var builder: CountAddReadSet.Builder
        val sealed = db.withTransaction {
            CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
                builder = CountAddReadSet.Builder.create(owner, requireNotNull(context))
                builder.rows(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", mapOf("uuid" to habit.uuid))
                builder.seal()
            }
        }
        assertEquals("COUNT_ADD_FREEZE_EXPIRED", failure { builder.leaf("format") }.message)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { current ->
            assertTrue(sealed.matchesCurrent(owner, requireNotNull(current)))
        } }
    }

    @Test fun reopeningSameFileCreatesNewIncarnationAndRejectsOldPreparedValues() = runBlocking<Unit> {
        val habit = openCountAddPlanInputs().first()
        val owner = requireNotNull(CountTrustRuntime.forDatabase(db))
        val access = requireNotNull(tokens.localCoreWriteAccess())
        val sealed = db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { context ->
            val builder = CountAddReadSet.Builder.create(owner, requireNotNull(context))
            builder.rows(CountTrustMembership.key("habit.uuid", habit.uuid), "habits", mapOf("uuid" to habit.uuid))
            builder.seal()
        } }
        storage.reopen()
        val reopened = requireNotNull(CountTrustRuntime.forDatabase(db))
        assertNotSame(owner, reopened)
        db.withTransaction { CountTrustKernel(db, tokens, sessions).withOpen(access) { current ->
            assertEquals("COUNT_ADD_PREPARATION_EXPIRED", failure {
                sealed.matchesCurrent(reopened, requireNotNull(current))
            }.message)
        } }
    }
}
