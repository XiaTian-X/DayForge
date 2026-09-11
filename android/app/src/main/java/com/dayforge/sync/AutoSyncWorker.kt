package com.dayforge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dayforge.data.local.TokenManager
import com.dayforge.data.repository.IncrementalSyncRepository
import com.dayforge.data.repository.ServerIdentityMismatchException
import com.dayforge.data.repository.SyncEpochChangedException
import com.dayforge.data.repository.SyncProtocolException
import com.dayforge.data.repository.SyncRequiresAttentionException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/** A small WorkManager adapter; the durable outbox remains the source of truth. */
class AutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            AutoSyncWorkerEntryPoint::class.java
        )
        val tokens = dependencies.tokenManager()
        val userId = tokens.userId.first() ?: return Result.success()
        if (tokens.accessToken.first() == null || tokens.syncAccountId.first() != userId) {
            return Result.success()
        }

        return try {
            dependencies.syncRepository().sync()
            Result.success()
        } catch (_: ServerIdentityMismatchException) {
            Result.failure()
        } catch (_: SyncEpochChangedException) {
            Result.failure()
        } catch (_: SyncProtocolException) {
            Result.failure()
        } catch (_: SyncRequiresAttentionException) {
            // Rejected rows require a user decision, not background retry churn.
            Result.failure()
        } catch (error: HttpException) {
            val credentialsWereRetained = tokens.accessToken.first() != null &&
                tokens.refreshToken.first() != null
            if (error.code() in RETRYABLE_HTTP_CODES ||
                (error.code() == 401 && credentialsWereRetained)
            ) Result.retry() else Result.failure()
        } catch (error: Exception) {
            if (error.hasIOExceptionCause()) Result.retry() else Result.failure()
        }
    }

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
