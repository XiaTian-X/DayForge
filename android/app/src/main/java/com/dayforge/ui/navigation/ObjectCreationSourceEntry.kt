package com.dayforge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dayforge.data.local.DatabaseCreationInput
import com.dayforge.data.local.DatabaseObjectSelection
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.repository.ObjectCreationAuthority
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed interface CreationSourceState {
    data object Loading : CreationSourceState
    data class Ready(val input: DatabaseCreationInput) : CreationSourceState
    data class Error(val message: String) : CreationSourceState
}

/** Production form admission is bound to the original database as well as the original
 * protocol authority. A parent selection is transferred from the rendered parent page. */
@HiltViewModel
internal class ObjectCreationSourceViewModel @Inject constructor(private val host: DatabaseRuntimeHost) : ViewModel() {
    private val mutable = MutableStateFlow<CreationSourceState>(CreationSourceState.Loading)
    val state = mutable.asStateFlow()
    private var started = false
    private var parent: DatabaseObjectSelection? = null
    private val registration = host.observeMemory(object : DatabaseRuntimeHost.MemoryListener {
        override fun transition(blocked: Boolean) {
            val before = mutable.value
            val input = (before as? CreationSourceState.Ready)?.input ?: return
            if (blocked || !input.publication.isCurrentInMemory()) mutable.compareAndSet(before,
                CreationSourceState.Error("DATABASE_EPOCH_INPUT_EXPIRED"))
        }
    })
    fun begin(originalParent: DatabaseObjectSelection?, requireParent: Boolean) {
        if (started) return
        started = true; parent = originalParent
        if (requireParent && originalParent == null) {
            mutable.value = CreationSourceState.Error("DATABASE_EPOCH_INPUT_REQUIRED"); return
        }
        viewModelScope.launch {
            try {
                suspend fun prepare(graph: com.dayforge.data.local.DatabaseEpochGraph) {
                    originalParent?.let { selection ->
                        selection.requireHabit(requireNotNull(graph.habitTargetReadRepository.habit(selection.id)) {
                            "DATABASE_EPOCH_SELECTION_CHANGED"
                        })
                    }
                    val publication = host.currentPublication()
                    val authority = graph.nextObjectCreator.captureForNavigation()
                    check(publication.publish { mutable.value = CreationSourceState.Ready(DatabaseCreationInput(publication, authority, originalParent)) }) {
                        "DATABASE_EPOCH_INPUT_EXPIRED"
                    }
                }
                if (originalParent == null) host.withGraph { prepare(it) }
                else host.withSourceGraph(originalParent.publication) { prepare(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = CreationSourceState.Error(error.message ?: "OBJECT_CREATE_ACCESS_DENIED") }
        }
    }
}

@Composable
internal fun ObjectCreationSourceEntry(onBack: () -> Unit,
    parentSelection: DatabaseObjectSelection? = null, requireParent: Boolean = false,
    viewModel: ObjectCreationSourceViewModel = hiltViewModel(),
    content: @Composable (ObjectCreationAuthority?, DatabaseCreationInput) -> Unit
) {
    LaunchedEffect(viewModel, parentSelection) { viewModel.begin(parentSelection, requireParent) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val value = state) {
        is CreationSourceState.Ready -> content(value.input.authority, value.input)
        else -> ObjectCreationPending((state as? CreationSourceState.Error)?.message, onBack)
    }
}
