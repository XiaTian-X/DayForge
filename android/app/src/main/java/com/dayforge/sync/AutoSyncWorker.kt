package com.dayforge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dayforge.data.local.TokenManager
import com.dayforge.data.api.NextSyncHttpFailure
import com.dayforge.data.api.NextSyncReplyInvalid
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.NextSyncAttention
import com.dayforge.data.repository.NextSyncRetryRequired
import com.dayforge.data.repository.ServerIdentityMismatchException
import com.dayforge.data.repository.SyncEpochChangedException
import com.dayforge.data.repository.SyncProtocolException
import com.dayforge.data.repository.SyncRequiresAttentionException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/** A small WorkManager adapter; the durable outbox remains the source of truth. */
class AutoSyncWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val loadDependencies: () -> AutoSyncWorkerEntryPoint
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, {
        EntryPointAccessors.fromApplication(
            appContext.applicationContext,
            AutoSyncWorkerEntryPoint::class.java
        )
    })

    override suspend fun doWork(): Result {
        // Dependency initialization is not a network failure or a completed sync.
        val dependencies = loadDependencies()
        val tokens = dependencies.tokenManager()
        val userId = tokens.userId.first() ?: return Result.success()
        if (tokens.accessToken.first() == null || tokens.syncAccountId.first() != userId) {
            return Result.success()
        }

        return try {
            dependencies.syncRepository().sync()
            Result.success()
        } catch (cancelled: CancellationException) {
            // REPLACE and WorkManager stoppages cancel the structured owner. Never
            // convert that signal into a completed attempt or a retry decision.
            throw cancelled
        } catch (_: ServerIdentityMismatchException) {
            Result.failure()
        } catch (_: SyncEpochChangedException) {
            Result.failure()
        } catch (_: SyncProtocolException) {
            Result.failure()
        } catch (_: SyncRequiresAttentionException) {
            // Rejected rows require a user decision, not background retry churn.
            Result.failure()
        } catch (_: NextSyncAttention) {
            Result.failure()
        } catch (_: NextSyncRetryRequired) {
            Result.retry()
        } catch (_: NextSyncReplyInvalid) {
            // A received but invalid reply is not evidence of transient transport
            // loss. Keep the original queue for diagnosis/explicit recovery.
            Result.failure()
        } catch (error: NextSyncHttpFailure) {
            // The next HTTP adapter uses IOException for both transport and HTTP
            // errors. Inspect its typed status BEFORE the generic I/O fallback.
            if (error.code in NEXT_CONTEXT_FAILURES) Result.failure()
            else if (error.status == 409) {
                if (error.code in NEXT_TRANSIENT_CONFLICTS) Result.retry() else Result.failure()
            } else httpResult(error.status, tokens)
        } catch (error: HttpException) {
            httpResult(error.code(), tokens)
        } catch (error: Exception) {
            if (error.hasIOExceptionCause()) Result.retry() else Result.failure()
        }
    }

    private suspend fun httpResult(status: Int, tokens: TokenManager): Result =
        if (status in RETRYABLE_HTTP_CODES ||
            (status == 401 && tokens.accessToken.first() != null && tokens.refreshToken.first() != null)
        ) Result.retry() else Result.failure()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoSyncWorkerEntryPoint {
        fun syncRepository(): IncrementalSyncRepository
        fun tokenManager(): TokenManager
    }

    private companion object {
        // 409 is used for the short-lived OPERATION_IN_PROGRESS response when
        // two automatic/manual attempts overlap; the durable queues make a
        // delayed retry safe and idempotent.
        val RETRYABLE_HTTP_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504)
        val NEXT_TRANSIENT_CONFLICTS = setOf("MISSING_PREDECESSOR", "OPERATION_IN_PROGRESS")
        val NEXT_CONTEXT_FAILURES = setOf("SERVER_IDENTITY_MISMATCH", "SYNC_EPOCH_MISMATCH")
    }

    private fun Throwable.hasIOExceptionCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is IOException) return true
            current = current.cause
        }
        return false
    }
}
