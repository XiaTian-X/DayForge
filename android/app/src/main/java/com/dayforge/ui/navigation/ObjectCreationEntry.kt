package com.dayforge.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dayforge.R
import com.dayforge.data.repository.NextObjectCreator
import com.dayforge.data.repository.ObjectCreationAuthority
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ObjectCreationEntryState {
    data object Loading : ObjectCreationEntryState
    data class Ready(val authority: ObjectCreationAuthority?) : ObjectCreationEntryState
    data class Error(val message: String) : ObjectCreationEntryState
}

/** Per navigation entry, memory only: neither authority nor credentials enter SavedStateHandle. */
@HiltViewModel
class ObjectCreationEntryViewModel @Inject constructor(private val creator: NextObjectCreator) : ViewModel() {
    private val mutable = MutableStateFlow<ObjectCreationEntryState>(ObjectCreationEntryState.Loading)
    val state = mutable.asStateFlow()
    init { load() }

    fun retry() { if (mutable.value is ObjectCreationEntryState.Error) load() }

    private fun load() {
        mutable.value = ObjectCreationEntryState.Loading
        viewModelScope.launch {
            try { mutable.value = ObjectCreationEntryState.Ready(creator.captureForNavigation()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = ObjectCreationEntryState.Error(error.message ?: "OBJECT_CREATE_ACCESS_DENIED") }
        }
    }
}

/** Do not render a legacy form during asynchronous v5 admission. Existing form layouts stay intact. */
@Composable
internal fun ObjectCreationEntry(onBack: () -> Unit,
    viewModel: ObjectCreationEntryViewModel = hiltViewModel(),
    content: @Composable (ObjectCreationAuthority?) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is ObjectCreationEntryState.Ready -> content(current.authority)
        else -> ObjectCreationPending((current as? ObjectCreationEntryState.Error)?.message, onBack,
            if (current is ObjectCreationEntryState.Error) viewModel::retry else null)
    }
}

@Composable
internal fun ObjectCreationPending(message: String?, onBack: () -> Unit, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (message != null) {
                Text(stringResource(R.string.toast_save_failed, message))
                if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.action_retry)) }
            } else CircularProgressIndicator()
            TextButton(onClick = onBack) { Text(stringResource(R.string.content_description_back)) }
        }
}
