package com.dayforge.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dayforge.R
import com.dayforge.domain.service.*

/** Wraps the formal app, including theme repair/settings and reminder navigation. */
@Composable
internal fun ReplicaPresentationGate(controller: ReplicaPresentationController,
    content: @Composable (ReplicaDisplayPermit) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    ReplicaPresentationGate(state, controller::retry, content)
}

@Composable
internal fun ReplicaPresentationGate(state: ReplicaPresentationState, retry: () -> Unit,
    content: @Composable (ReplicaDisplayPermit) -> Unit) {
    when (state) {
        is ReplicaPresentationState.Open -> key(state.permit.lifetime) {
            Box(Modifier.fillMaxSize().drawWithContent { if (state.permit.canRender()) drawContent() }) {
                content(state.permit)
            }
        }
        else -> MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(R.string.replica_display_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    if (state == ReplicaPresentationState.Checking) CircularProgressIndicator()
                    else {
                        Text(stringResource(R.string.replica_display_pending))
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = retry) { Text(stringResource(R.string.replica_display_retry)) }
                    }
                }
            }
        }
    }
}

/** Each recovery lifetime owns all navigation stores. Ordinary rotations keep the same owner;
 * removing a frozen lifetime clears graph/entry VMs, including their old Room collectors.
 */
@Composable
internal fun ReplicaNavigationLifetime(permit: ReplicaDisplayPermit, content: @Composable () -> Unit) {
    val registry: ReplicaNavigationStores = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = viewModelFactory { initializer { ReplicaNavigationStores() } })
    val owner = remember(registry, permit.lifetime) { registry.acquire(permit.lifetime) }
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(registry, permit, activity) {
        onDispose {
            // Keep only a genuine rotation; ordinary theme/navigation removal must not leave
            // orphaned collectors in the Activity store. A revoked permit always wins.
            if (!permit.canRender() || activity?.isChangingConfigurations != true) registry.release(permit.lifetime)
        }
    }
    CompositionLocalProvider(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides owner, content = content)
}

internal class ReplicaNavigationStores : androidx.lifecycle.ViewModel() {
    private var lifetime: Long? = null
    private var owner: androidx.lifecycle.ViewModelStoreOwner? = null
    fun acquire(value: Long): androidx.lifecycle.ViewModelStoreOwner {
        if (lifetime != value) {
            owner?.viewModelStore?.clear()
            lifetime = value
            owner = object : androidx.lifecycle.ViewModelStoreOwner {
                override val viewModelStore = androidx.lifecycle.ViewModelStore()
            }
        }
        return requireNotNull(owner)
    }
    fun release(value: Long) {
        if (lifetime == value) { owner?.viewModelStore?.clear(); owner = null; lifetime = null }
    }
    override fun onCleared() { owner?.viewModelStore?.clear(); owner = null; lifetime = null }
}
