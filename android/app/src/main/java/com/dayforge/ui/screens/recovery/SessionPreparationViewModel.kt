package com.dayforge.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.local.SessionPreparationAttempt
import com.dayforge.data.local.SessionPreparationRetry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity control state only. No command, navigation input or business callback is retained. */
@HiltViewModel
internal class SessionPreparationViewModel @Inject constructor(private val host: DatabaseRuntimeHost) : ViewModel() {
    val state = host.sessionPreparation
    private val physicalFailure = MutableStateFlow(false)
    val initializationFailed = physicalFailure.asStateFlow()
    private var work: Job? = null

    init { inspect() }

    /** A fresh control capture after login observes the new account; it never retries an old action. */
    fun inspect() = launch { host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { } }
    fun retry(expected: SessionPreparationAttempt) = launch {
        if (host.retrySessionPreparation(expected) === SessionPreparationRetry.Stale)
            host.withGraph(DatabaseRuntimeHost.Work.CONTROL) { }
    }

    private fun launch(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            physicalFailure.value = false
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { physicalFailure.value = true }
        }
    }
}
