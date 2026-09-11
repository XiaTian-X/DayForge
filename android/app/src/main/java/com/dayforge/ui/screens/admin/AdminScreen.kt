package com.dayforge.ui.screens.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.api.dto.AdminUserResponse
import com.dayforge.data.api.dto.ApiTokenListResponse
import com.dayforge.data.api.dto.ApiTokenResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val users by viewModel.users.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val resetUser by viewModel.resetPasswordDialog.collectAsState()
    val resetPasswordInput by viewModel.resetPasswordInput.collectAsState()
    val selectedUser by viewModel.selectedUser.collectAsState()
    val userTokens by viewModel.userTokens.collectAsState()
    val tokensLoading by viewModel.tokensLoading.collectAsState()
    val createdToken by viewModel.createdToken.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showCreateTokenDialog by remember { mutableStateOf(false) }
    var showCreateUserDialog by remember { mutableStateOf(false) }
    var showDeleteTokenDialog by remember { mutableStateOf<ApiTokenListResponse?>(null) }
    var showCreatedTokenDialog by remember { mutableStateOf<ApiTokenResponse?>(null) }

    LaunchedEffect(Unit) {
        viewModel.event.collect { event ->
            when (event) {
                is AdminViewModel.AdminEvent.PasswordResetSuccess -> {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.admin_password_reset_success, event.username)
                    )
                }
                is AdminViewModel.AdminEvent.UserCreated -> {
                    showCreateUserDialog = false
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.admin_user_created_success, event.username)
                    )
                }
            }
        }
    }

    LaunchedEffect(createdToken) {
        createdToken?.let { token ->
            showCreatedTokenDialog = token
            viewModel.clearCreatedToken()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.takeIf { users.isNotEmpty() }?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateUserDialog = true }, enabled = !isLoading) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.admin_create_user)
                        )
                    }
                    IconButton(onClick = { viewModel.loadUsers() }, enabled = !isLoading) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.admin_refresh)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && users.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null && users.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.loadUsers() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users, key = { "user_${it.id}" }) { user ->
                            UserCard(
                                user = user,
                                isSelected = selectedUser?.id == user.id,
                                onToggleStatus = { viewModel.toggleUserStatus(user) },
                                onResetPassword = { viewModel.showResetPasswordDialog(user) },
                                onSelect = { viewModel.selectUser(user) }
                            )
                        }

                        // Token management section
                        if (selectedUser != null) {
                            item(key = "token_header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                TokenManagementHeader(
                                    username = selectedUser!!.username,
                                    onDismiss = { viewModel.dismissSelectedUser() },
                                    onCreateToken = { showCreateTokenDialog = true },
                                    onRefresh = { viewModel.loadUserTokens(selectedUser!!.id) }
                                )
                            }

                            if (tokensLoading) {
                                item(key = "token_loading") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            } else if (userTokens.isEmpty()) {
                                item(key = "token_empty") {
                                    Text(
                                        text = stringResource(R.string.token_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                items(userTokens, key = { "token_${it.id}" }) { token ->
                                    AdminTokenCard(
                                        token = token,
                                        onDelete = { showDeleteTokenDialog = token }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (resetUser != null) {
        ResetPasswordDialog(
            username = resetUser!!.username,
            passwordInput = resetPasswordInput,
            onPasswordChange = { viewModel.onResetPasswordInputChange(it) },
            onConfirm = { viewModel.confirmResetPassword() },
            onDismiss = { viewModel.dismissResetPasswordDialog() }
        )
    }

    if (showCreateUserDialog) {
        CreateUserDialog(
            onDismiss = { showCreateUserDialog = false },
            onCreate = viewModel::createUser
        )
    }

    // Token dialogs
    if (showCreateTokenDialog) {
        AdminCreateTokenDialog(
            onDismiss = { showCreateTokenDialog = false },
            onCreate = { name, expiresInDays ->
                viewModel.createUserToken(name, expiresInDays)
                showCreateTokenDialog = false
            }
        )
    }

    showDeleteTokenDialog?.let { token ->
        AlertDialog(
            onDismissRequest = { showDeleteTokenDialog = null },
            title = { Text(stringResource(R.string.token_delete_title)) },
            text = { Text(stringResource(R.string.token_delete_message, token.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUserToken(token.id)
                    showDeleteTokenDialog = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTokenDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    showCreatedTokenDialog?.let { token ->
        AdminCreatedTokenDialog(
            token = token,
            onDismiss = { showCreatedTokenDialog = null }
        )
    }
}

@Composable
private fun CreateUserDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_create_user)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAdmin, onCheckedChange = { isAdmin = it })
                    Text(stringResource(R.string.admin_grant_admin))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(username, password, isAdmin) }) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun UserCard(
    user: AdminUserResponse,
    isSelected: Boolean,
    onToggleStatus: () -> Unit,
    onResetPassword: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        colors = if (isSelected) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.username,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!user.email.isNullOrBlank()) {
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (user.isAdmin) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.admin_badge_admin)) }
                            )
                        }
                        if (!user.isActive) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(R.string.admin_badge_disabled)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            )
                        }
                    }
                }
                Switch(
                    checked = user.isActive,
                    onCheckedChange = { onToggleStatus() }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResetPassword) {
                    Text(stringResource(R.string.admin_reset_password))
                }
            }
        }
    }
}

@Composable
private fun TokenManagementHeader(
    username: String,
    onDismiss: () -> Unit,
    onCreateToken: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.admin_user_tokens, username),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCreateToken) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.token_create))
                }
                OutlinedButton(onClick = onRefresh) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.admin_refresh))
                }
            }
        }
    }
}

@Composable
private fun AdminTokenCard(
    token: ApiTokenListResponse,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = token.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = token.prefix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.token_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.token_created, token.created_at.take(10)),
                    style = MaterialTheme.typography.bodySmall
                )
                token.last_used_at?.let {
                    Text(
                        text = stringResource(R.string.token_last_used, it.take(10)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminCreateTokenDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var expiresInDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.token_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = expiresInDays,
                    onValueChange = { expiresInDays = it },
                    label = { Text(stringResource(R.string.token_expires_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.token_expires_placeholder)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val days = expiresInDays.toIntOrNull()
                        onCreate(name, days)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AdminCreatedTokenDialog(
    token: ApiTokenResponse,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_created_title)) },
        text = {
            Column {
                Text(stringResource(R.string.token_created_message))
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = token.token ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.token_created_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clip = android.content.ClipData.newPlainText("API Token", token.token ?: "")
                clipboard?.setPrimaryClip(clip)
                onDismiss()
            }) {
                Text(stringResource(R.string.token_copy))
            }
        }
    )
}

@Composable
private fun ResetPasswordDialog(
    username: String,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_reset_password_title, username)) },
        text = {
            OutlinedTextField(
                value = passwordInput,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.admin_new_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.admin_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
