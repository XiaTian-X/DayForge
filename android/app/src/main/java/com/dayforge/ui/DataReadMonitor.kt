package com.dayforge.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Read errors remain recoverable; no stale account content is kept as a fallback. */
internal class DataReadMonitor(private val tag: String) {
    private val failed = MutableStateFlow(false)
    val error = failed.asStateFlow()
    val retries = MutableStateFlow(0L)
    fun retry() { retries.update { it + 1 } }
    /** Includes exceptions thrown by DAO/DataStore sources, not just projection calculations. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> recover(fallback: T, source: () -> Flow<T>): Flow<T> = retries.flatMapLatest {
        flow { emitAll(source()) }.catch { error ->
            if (error is CancellationException) throw error
            Log.e(tag, "Read source failed; explicit retry restarts the subscription", error)
            failed.value = true
            emit(fallback)
        }
    }
    suspend fun <T> read(fallback: T, block: suspend () -> T): T = try {
        block().also { failed.value = false }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.e(tag, "Cannot read personal data; retry remains available", error)
        failed.value = true
        fallback
    }
}
