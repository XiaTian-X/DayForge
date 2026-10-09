package com.dayforge.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.dayforge.data.api.NextSyncHttpFailure
import com.dayforge.data.api.NextSyncReplyInvalid
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.data.repository.*
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import retrofit2.Response

/** Actual CoroutineWorker adapter, physical authenticated DataStore/Room and real HTTP.
 * The legacy dependency is explicitly bridged to the staged runtime in socket cases;
 * this does not enable next-protocol dispatch in the production worker.
 */
@RunWith(AndroidJUnit4::class)
class AutoSyncWorkerTest : NextCoreRequestFixture() {
    private fun worker(synchronize: suspend () -> Unit): AutoSyncWorker {
        val repository = mockk<IncrementalSyncRepository>()
        coEvery { repository.sync(any()) } coAnswers { synchronize() }
        val dependencies = mockk<AutoSyncWorker.AutoSyncWorkerEntryPoint>()
        every { dependencies.tokenManager() } returns tokens
        every { dependencies.syncRepository() } returns repository
        return AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)) { dependencies }
    }

    private fun legacyHttp(status: Int) = HttpException(Response.error<Unit>(status,
        "{}".toResponseBody()))

    @Test fun authenticatedAttemptCallsTheRepositoryExactlyOnceAndReturnsSuccess() = runBlocking<Unit> {
        var calls = 0
        assertEquals(Result.success(), worker { calls++ }.doWork())
        assertEquals(1, calls)
    }

    @Test fun signedOutAndUnboundAccountsNeverStartAnAttempt() = runBlocking<Unit> {
        var calls = 0
        // Retain login credentials but explicitly remove the replica binding.
        tokens.clearSyncState()
        assertEquals(Result.success(), worker { calls++ }.doWork())
        tokens.clearTokens()
        assertEquals(Result.success(), worker { calls++ }.doWork())
        assertEquals(0, calls)
    }

    @Test fun directCancellationEscapesWithItsOriginalIdentityAndIsNotAWorkResult() = runBlocking<Unit> {
        val original = CancellationException("owned stop")
        try { worker { throw original }.doWork(); fail("Must cancel") }
        catch (error: CancellationException) { assertSame(original, error) }
    }

    @Test fun cancelledRunningOwnerCannotContinueOrReportACompletedAttempt() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        var continued = false
        val attempt = async {
            worker { entered.complete(Unit); CompletableDeferred<Unit>().await(); continued = true }.doWork()
        }
        withTimeout(5_000) { entered.await() }
        attempt.cancelAndJoin()
        assertTrue(attempt.isCancelled)
        assertFalse(continued)
    }

    @Test fun dependencyInitializationFailureIsNotInventedAsSuccessOrNetworkRetry() = runBlocking<Unit> {
        val original = IllegalStateException("dependency unavailable")
        try {
            AutoSyncWorker(app, mockk<WorkerParameters>(relaxed = true)) { throw original }.doWork()
            fail("Must expose initialization failure")
        } catch (error: IllegalStateException) { assertSame(original, error) }
    }

    @Test fun legacyProtocolReplicaAndExplicitAttentionFailuresNeverRequestBackgroundRetry() = runBlocking<Unit> {
        for (failure in listOf(ServerIdentityMismatchException("changed"), SyncEpochChangedException("changed"),
            SyncProtocolException("upgrade"), SyncRequiresAttentionException("decision"),
            ProtocolNextDataRequiresUpgradeException(), IllegalStateException("local validation"))) {
            assertEquals(failure.javaClass.name, Result.failure(), worker { throw failure }.doWork())
        }
    }

    @Test fun actualTransportAndWrappedIoFailuresStillRequestBackoff() = runBlocking<Unit> {
        for (failure in listOf(IOException("offline"), IllegalStateException("wrapped", IOException("lost"))))
            assertEquals(Result.retry(), worker { throw failure }.doWork())
    }

    @Test fun legacyHttpRetryAndPermanentCodesRetainTheirExistingPolicy() = runBlocking<Unit> {
        for (status in listOf(408, 409, 425, 429, 500, 502, 503, 504))
            assertEquals("HTTP $status", Result.retry(), worker { throw legacyHttp(status) }.doWork())
        for (status in listOf(400, 403, 404, 410, 422))
            assertEquals("HTTP $status", Result.failure(), worker { throw legacyHttp(status) }.doWork())
    }

    @Test fun unauthorizedResponseRetriesOnlyWhileBothCredentialsWereRetained() = runBlocking<Unit> {
        for (next in listOf(false, true)) {
            assertEquals(Result.retry(), worker {
                if (next) throw NextSyncHttpFailure(401, null) else throw legacyHttp(401)
            }.doWork())
        }
        assertEquals(Result.failure(), worker { tokens.clearTokens(); throw legacyHttp(401) }.doWork())
        tokens.saveLoginSession("synthetic-first", "synthetic-refresh", "member", id(1), false)
        register()
        assertEquals(Result.failure(), worker { tokens.clearTokens(); throw NextSyncHttpFailure(401, null) }.doWork())
    }

    @Test fun nextPermanentHttpAndInvalidReceivedReplyAreNotMisclassifiedAsTransportLoss() = runBlocking<Unit> {
        for (failure in listOf(NextSyncHttpFailure(403, "FORBIDDEN"), NextSyncHttpFailure(422, "INVALID_PAYLOAD"),
            NextSyncHttpFailure(409, "SERVER_IDENTITY_MISMATCH"), NextSyncHttpFailure(409, "SYNC_EPOCH_MISMATCH"),
            NextSyncHttpFailure(409, "TASK_STATE_CONFLICT"), NextSyncHttpFailure(409, null),
            NextSyncReplyInvalid(), NextSyncAttention(setOf(id(50)))))
            assertEquals(failure.message, Result.failure(), worker { throw failure }.doWork())
    }

    @Test fun nextExplicitTransientConflictsAndUnfinishedDurableWorkRequestBackoff() = runBlocking<Unit> {
        for (failure in listOf(NextSyncHttpFailure(409, "OPERATION_IN_PROGRESS"),
            NextSyncHttpFailure(409, "MISSING_PREDECESSOR"), NextSyncHttpFailure(503, null),
            NextSyncHttpFailure(429, null), NextSyncRetryRequired()))
            assertEquals(failure.message, Result.retry(), worker { throw failure }.doWork())
    }

    @Test fun realNextHttpPermanentFailureKeepsOriginalQueueBytesAndCannotAdvanceSuccessMetadata() = runBlocking<Unit> {
        register()
        val row = editMetric("Permanent HTTP failure")
        val origin = originalIntent(row)
        val (http, server) = channel { input ->
            if (input.path.endsWith("/identity")) reply(input)
            else MaterialSocketServer.Reply("{\"detail\":{\"code\":\"FORBIDDEN\"}}".toByteArray(), status = 403)
        }
        assertEquals(Result.failure(), worker { NextSyncRuntime(db, tokens, sessions, http, preferences).sync() }.doWork())
        val firstBytes = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        storage.reopen()
        assertEquals(Result.failure(), worker { NextSyncRuntime(db, tokens, sessions, http, preferences).sync() }.doWork())
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertEquals(origin, originalIntent(row))
        assertArrayEquals(firstBytes, transmission(NEXT_OPERATION, row.operationId).wireBytes)
        assertEquals(2, server.requests.count { it.path.endsWith("/push") })
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertTrue(db.nextSyncStateDao().rows().isEmpty())
        assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun realNextPeerDisconnectRetriesTheExactOriginalRequestAfterColdReopen() = runBlocking<Unit> {
        register()
        val row = editMetric("Dropped response")
        val origin = originalIntent(row)
        val (http, server) = channel { input -> if (input.path.endsWith("/identity")) reply(input) else null }
        assertEquals(Result.retry(), worker { NextSyncRuntime(db, tokens, sessions, http, preferences).sync() }.doWork())
        val firstBytes = transmission(NEXT_OPERATION, row.operationId).wireBytes.copyOf()
        storage.reopen()
        assertEquals(Result.retry(), worker { NextSyncRuntime(db, tokens, sessions, http, preferences).sync() }.doWork())
        val uploads = server.requests.filter { it.path.endsWith("/push") }
        assertEquals(2, uploads.size)
        assertArrayEquals(uploads[0].body, uploads[1].body)
        assertArrayEquals(firstBytes, uploads[1].body)
        assertEquals(origin, originalIntent(row))
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertNull(preferences.lastSyncTimestamp.first())
    }

    @Test fun realNextInvalidReplyIsFiniteFailureWithoutAnAckOrAutomaticRekey() = runBlocking<Unit> {
        register()
        val row = editMetric("Malformed response")
        val origin = originalIntent(row)
        val (http, _) = channel { input -> if (input.path.endsWith("/identity")) reply(input)
            else MaterialSocketServer.Reply("{\"results\":[]}".toByteArray()) }
        assertEquals(Result.failure(), worker { NextSyncRuntime(db, tokens, sessions, http, preferences).sync() }.doWork())
        storage.reopen()
        assertEquals(row, db.syncOutboxDao().getById(row.id))
        assertEquals(origin, originalIntent(row))
        assertNotNull(db.nextRequestDao().transmission(NEXT_OPERATION, row.operationId))
        assertNull(db.nextRequestDao().acceptance(NEXT_OPERATION, row.operationId))
        assertTrue(db.nextSyncStateDao().rows().isEmpty())
        assertNull(preferences.lastSyncTimestamp.first())
    }
}
