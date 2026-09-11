package com.dayforge.ui.screens.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dayforge.R
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Login screen with username/password inputs and post-login sync prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showSyncPrompt by viewModel.showSyncPrompt.collectAsState()
    val syncErrorMessage by viewModel.syncErrorMessage.collectAsState()
    val localDataPrompt by viewModel.localDataPrompt.collectAsState()
    val serverProtocol by viewModel.serverProtocol.collectAsState()
    val serverHostPort by viewModel.serverHostPort.collectAsState()
    val remoteServerHostPort by viewModel.remoteServerHostPort.collectAsState()
    val showServerSettings by viewModel.showServerSettings.collectAsState()

    // Handle login events
    LaunchedEffect(Unit) {
        viewModel.loginEvent.collect { event ->
            when (event) {
                is LoginViewModel.LoginEvent.Success -> onLoginSuccess()
            }
        }
    }

    // Password visibility state
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App title/logo area
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(R.string.login_app_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.login_admin_managed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Username input
            OutlinedTextField(
                value = username,
                onValueChange = { viewModel.onUsernameChange(it) },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            // Password input
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = { Text(stringResource(R.string.login_password_label)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            viewModel.onLoginClick()
                        }
                    }
                ),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (passwordVisible) stringResource(R.string.login_content_description_hide_password) else stringResource(R.string.login_content_description_show_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            // Error message
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Login button
            Button(
                onClick = { viewModel.onLoginClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.login_button))
                }
            }

            // Server settings expandable section
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header row - clickable to expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.login_server_settings),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { viewModel.toggleServerSettings() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (showServerSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showServerSettings) stringResource(R.string.login_content_description_collapse) else stringResource(R.string.login_content_description_expand),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Expandable content
                    if (showServerSettings) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.login_server_address_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (serverProtocol == "http") {
                                Text(
                                    text = stringResource(R.string.login_server_http_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            // Protocol selector row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = serverProtocol == "http",
                                        onClick = { viewModel.onServerProtocolChange("http") },
                                        enabled = !isLoading
                                    )
                                    Text(
                                        text = "HTTP",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = serverProtocol == "https",
                                        onClick = { viewModel.onServerProtocolChange("https") },
                                        enabled = !isLoading
                                    )
                                    Text(
                                        text = "HTTPS",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            // Host:port input
                            OutlinedTextField(
                                value = serverHostPort,
                                onValueChange = { viewModel.onServerHostPortChange(it) },
                                label = { Text(stringResource(R.string.login_server_address_label)) },
                                placeholder = { Text("192.168.1.1:8080") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { viewModel.saveServerUrl() }
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            )
                            OutlinedTextField(
                                value = remoteServerHostPort,
                                onValueChange = viewModel::onRemoteServerHostPortChange,
                                label = { Text(stringResource(R.string.login_remote_server_address_label)) },
                                placeholder = { Text("dayforge.example.com") },
                                supportingText = {
                                    Text(stringResource(R.string.login_remote_server_address_hint))
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { viewModel.saveServerUrl() }
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = { viewModel.resetServerUrl() },
                                    enabled = !isLoading
                                ) {
                                    Text(stringResource(R.string.login_reset_default))
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Button(
                                    onClick = { viewModel.saveServerUrl() },
                                    enabled = !isLoading
                                ) {
                                    Text(stringResource(R.string.login_save))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Sync prompt dialog - shown after successful login
    if (showSyncPrompt) {
        SyncPromptDialog(
            errorMessage = syncErrorMessage,
            onSync = { viewModel.onSyncConfirm() },
            onSkip = { viewModel.onSyncSkip() }
        )
    }

    localDataPrompt?.let { prompt ->
        LocalDataDecisionDialog(
            prompt = prompt,
            enabled = !isLoading,
            onMerge = viewModel::onMergeLocalData,
            onDiscard = viewModel::onDiscardLocalData,
            onCancel = viewModel::onCancelLocalDataDecision
        )
    }
}

/**
 * Dialog prompting user to sync data after login.
 */
@Composable
private fun SyncPromptDialog(
    errorMessage: String?,
    onSync: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onSkip() },
        icon = {
            Icon(
                imageVector = Icons.Rounded.CloudUpload,
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.login_sync_data_title)) },
        text = {
            Column {
                Text(stringResource(R.string.login_sync_data_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.login_sync_data_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSync) {
                Text(stringResource(R.string.login_sync_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.login_skip))
            }
        }
    )
}

@Composable
private fun LocalDataDecisionDialog(
    prompt: LoginViewModel.LocalDataPrompt,
    enabled: Boolean,
    onMerge: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.login_local_data_title)) },
        text = {
            Text(
                if (prompt.canMerge) {
                    stringResource(R.string.login_local_data_unowned_message, prompt.accountName)
                } else {
                    stringResource(R.string.login_local_data_other_account_message)
                }
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (prompt.canMerge) {
                    Button(
                        onClick = onMerge,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled
                    ) {
                        Text(stringResource(R.string.login_local_data_merge))
                    }
                }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.login_local_data_discard))
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}
