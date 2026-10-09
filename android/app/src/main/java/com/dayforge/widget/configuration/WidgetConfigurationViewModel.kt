package com.dayforge.widget.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.data.repository.WidgetConfigurationExpired
import com.dayforge.data.repository.WidgetConfigurationSnapshot
import com.dayforge.domain.service.WidgetConfigurationKind
import com.dayforge.domain.service.WidgetConfigurationWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class WidgetConfigurationState(val habits: List<HabitEntity> = emptyList(),
    val loading: Boolean = true, val saving: Boolean = false, val error: Exception? = null,
    val finished: Boolean = false, val expired: Boolean = false, val selectedName: String? = null)

internal class WidgetConfigurationViewModel(private val workflow: WidgetConfigurationWorkflow,
    private val kind: WidgetConfigurationKind, private val widgetId: Int) : ViewModel() {
    private val mutable = MutableStateFlow(WidgetConfigurationState())
    val state = mutable.asStateFlow()
    private var snapshot: WidgetConfigurationSnapshot? = null
    private var request: Job? = null

    init {
        load()
        viewModelScope.launch {
            workflow.changes.collect {
                val captured = snapshot
                if (captured != null && !workflow.current(captured)) {
                    mutable.value = WidgetConfigurationState(loading = false, expired = true)
                    request?.cancel()
                }
            }
        }
    }

    fun load() {
        if (mutable.value.saving || mutable.value.finished || mutable.value.expired || request?.isActive == true) return
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                snapshot?.let { if (!workflow.current(it)) throw WidgetConfigurationExpired() }
                val captured = workflow.load(kind, widgetId)
                currentCoroutineContext().ensureActive()
                snapshot = captured
                mutable.value = WidgetConfigurationState(habits = captured.habits, loading = false)
            } catch (error: Exception) { fail(error) }
        }
    }

    fun choose(habit: HabitEntity) {
        if (mutable.value.loading || mutable.value.saving || mutable.value.finished || mutable.value.expired) return
        val captured = snapshot ?: return
        if (captured.habits.none { it.id == habit.id && it.uuid == habit.uuid }) return
        mutable.value = mutable.value.copy(saving = true, error = null, selectedName = habit.name)
        request = viewModelScope.launch {
            try {
                workflow.configure(captured, habit)
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(saving = false, finished = true)
            } catch (error: Exception) { fail(error) }
        }
    }

    // Re-read choices after rollback; deleted/replaced objects must not trap a frozen retry.
    fun retry() = load()

    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        mutable.value = if (error is WidgetConfigurationExpired || mutable.value.expired)
            WidgetConfigurationState(loading = false, expired = true)
        else mutable.value.copy(loading = false, saving = false, error = error)
    }

    internal class Factory(private val workflow: WidgetConfigurationWorkflow,
        private val kind: WidgetConfigurationKind, private val widgetId: Int) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WidgetConfigurationViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return WidgetConfigurationViewModel(workflow, kind, widgetId) as T
        }
    }
}
