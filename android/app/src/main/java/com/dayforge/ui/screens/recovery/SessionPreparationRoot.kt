package com.dayforge.ui.screens.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dayforge.R
import com.dayforge.data.local.SessionPreparationAttempt
import com.dayforge.data.local.SessionPreparationState
import com.dayforge.ui.screens.login.LoginScreen

/** Outside the disposable business graph: original login and replica recovery remain reachable. */
@Composable
internal fun SessionPreparationRoot(model: SessionPreparationViewModel, openRecovery: () -> Unit,
    content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val initializationFailed by model.initializationFailed.collectAsStateWithLifecycle()
    var login by remember { mutableStateOf(false) }
    when {
        login -> {
            BackHandler { login = false; model.inspect() }
            MaterialTheme { LoginScreen(onLoginSuccess = { login = false; model.inspect() }) }
        }
        state is SessionPreparationState.Satisfied -> content()
        else -> SessionPreparationContent(state, initializationFailed, model::retry,
            { login = true }, openRecovery)
    }
}

@Composable
internal fun SessionPreparationContent(state: SessionPreparationState, initializationFailed: Boolean,
    retry: (SessionPreparationAttempt) -> Unit, login: () -> Unit, recovery: () -> Unit) {
    val checking = state is SessionPreparationState.Checking && !initializationFailed
    val attempt = state.attempt
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)
                .testTag("session-preparation"), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.session_preparation_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (checking) CircularProgressIndicator(Modifier.testTag("session-preparation-busy"))
                Text(stringResource(when {
                    initializationFailed -> R.string.session_preparation_storage
                    state is SessionPreparationState.AuthenticationUnavailable -> R.string.session_preparation_authentication
                    state is SessionPreparationState.DeferredFence -> R.string.session_preparation_recovery
                    checking -> R.string.session_preparation_checking
                    else -> R.string.session_preparation_failed
                }))
                Spacer(Modifier.height(12.dp))
                if (!checking && attempt != null) {
                    Button(onClick = { retry(attempt) }, modifier = Modifier.testTag("session-preparation-retry")) {
                        Text(stringResource(R.string.session_preparation_retry))
                    }
                }
                if (!checking) {
                    TextButton(login, modifier = Modifier.testTag("session-preparation-login")) {
                        Text(stringResource(R.string.login_title))
                    }
                    TextButton(recovery, modifier = Modifier.testTag("session-preparation-recovery")) {
                        Text(stringResource(R.string.replica_recovery_title))
                    }
                }
            }
        }
    }
}
