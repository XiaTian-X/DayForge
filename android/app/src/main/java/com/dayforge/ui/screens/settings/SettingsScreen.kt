package com.dayforge.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.dayforge.R
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.data.model.SyncProgress
import com.dayforge.data.local.entity.SyncOutboxEntity
import com.dayforge.data.local.entity.SyncConflictEntity
import com.dayforge.data.local.entity.TimerCommandEntity
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings screen with sync status, manual sync trigger, and account management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onNavigateToPermission: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {},
    onNavigateToToken: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val syncProgress by viewModel.syncProgress.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val activeServerUrl by viewModel.activeServerUrl.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val canEditStructure by viewModel.canEditStructure.collectAsState()
    val isPrimaryEditor by viewModel.isPrimaryEditor.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val showSyncSuccess by viewModel.showSyncSuccess.collectAsState()
    val showSyncError by viewModel.showSyncError.collectAsState()
    val syncErrorMessage by viewModel.syncErrorMessage.collectAsState()
    val isLoggingOut by viewModel.isLoggingOut.collectAsState()
    val showActiveTimerDialog by viewModel.showActiveTimerDialog.collectAsState()
    val activeTimerHabitName by viewModel.activeTimerHabitName.collectAsState()
    val activeTimerDuration by viewModel.activeTimerDuration.collectAsState()
    val rejectedChanges by viewModel.rejectedChanges.collectAsState()
    val syncConflicts by viewModel.syncConflicts.collectAsState()
    val rejectedTimerCommands by viewModel.rejectedTimerCommands.collectAsState()
    var showRejectedChanges by remember { mutableStateOf(false) }
    var rejectedChangeToDiscard by remember { mutableStateOf<SyncOutboxEntity?>(null) }
    var rejectedTimerToCancel by remember { mutableStateOf<TimerCommandEntity?>(null) }

    // Export/Import state
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val importConfirmData by viewModel.importConfirmData.collectAsState()
    val importResult by viewModel.importResult.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }

    // Get context for file operations
    val context = LocalContext.current

    // Coroutine scope for file operations
    val coroutineScope = rememberCoroutineScope()

    // Language selection state
    val languageCode by viewModel.languageCode.collectAsState()
    var showLanguageDialog by remember { mutableStateOf(false) }

    // Theme selection state
    val themeMode by viewModel.themeMode.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }

    // Global color theme state
    val lightColorThemeId by viewModel.lightColorThemeId.collectAsState()
    val darkColorThemeId by viewModel.darkColorThemeId.collectAsState()
    var showLightThemeDialog by remember { mutableStateOf(false) }
    var showDarkThemeDialog by remember { mutableStateOf(false) }

    // Card color style state
    val cardColorStyle by viewModel.cardColorStyle.collectAsState()
    var showCardColorStyleDialog by remember { mutableStateOf(false) }

    // Global notifications enabled state (NOTIFY-04)
    val globalNotificationsEnabled by viewModel.globalNotificationsEnabled.collectAsState()

    // Theme import/export state
    val themeImportProgress by viewModel.themeImportProgress.collectAsState()
    val themeImportResult by viewModel.themeImportResult.collectAsState()
    val themeExportProgress by viewModel.themeExportProgress.collectAsState()
    val themeExportResult by viewModel.themeExportResult.collectAsState()
    val themeDeleteResult by viewModel.themeDeleteResult.collectAsState()

    // Theme lists (observed from ThemeManager StateFlow)
    val allLightThemes by viewModel.allLightThemes.collectAsState()
    val allDarkThemes by viewModel.allDarkThemes.collectAsState()

    // Theme delete confirmation state
    var themeToDelete by remember { mutableStateOf<com.dayforge.domain.model.GlobalColorTheme?>(null) }

    // Theme export option state
    var themeExportOptions by remember { mutableStateOf<com.dayforge.domain.model.GlobalColorTheme?>(null) }

    // Determine current effective mode
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val effectiveDarkMode = when (themeMode) {
        "light" -> false
        "dark" -> true
        null -> isSystemInDarkTheme  // System default
        else -> isSystemInDarkTheme
    }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.prepareImport(uri)
        }
    }

    // File saver for config export
    val configExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val jsonContent = viewModel.exportConfigToJson()
                if (jsonContent != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(jsonContent.toByteArray())
                    }
                }
            }
        }
    }

    // File picker for theme import
    val themeImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importTheme(uri)
        }
    }

    // File saver for theme export
    var themeToExport by remember { mutableStateOf<String?>(null) }
    val themeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            // Capture themeId immediately to avoid race condition
            val capturedThemeId = themeToExport
            themeToExport = null  // Clear state immediately after capturing

            if (capturedThemeId != null) {
                // Check if this is template export (ends with "-template")
                val isTemplateExport = capturedThemeId.endsWith("-template")
                val actualThemeId = if (isTemplateExport) {
                    capturedThemeId.removeSuffix("-template")
                } else {
                    capturedThemeId
                }

                coroutineScope.launch {
                    val jsonContent = viewModel.exportTheme(actualThemeId, isTemplateExport)
                    if (jsonContent != null) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(jsonContent.toByteArray())
                        }
                    }
                    // Error handling is done via themeExportResult dialog
                }
            }
        }
    }

    // Sync progress dialog
    val showSyncProgressDialog = syncProgress.let { progress ->
        progress.isRunning()
    }

    LaunchedEffect(
        showRejectedChanges,
        rejectedChanges.isEmpty(),
        syncConflicts.isEmpty(),
        rejectedTimerCommands.isEmpty()
    ) {
        if (showRejectedChanges && rejectedChanges.isEmpty() &&
            syncConflicts.isEmpty() && rejectedTimerCommands.isEmpty()
        ) {
            showRejectedChanges = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Sync Status Section
            item {
                Text(
                    text = stringResource(R.string.settings_sync_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SyncStatusCard(
                    isOnline = isOnline,
                    isLoggedIn = isLoggedIn,
                    lastSyncTime = viewModel.formatLastSyncTime(lastSyncTime),
                    activeServerUrl = activeServerUrl,
                    syncProgress = syncProgress,
                    canEditStructure = canEditStructure,
                    isPrimaryEditor = isPrimaryEditor,
                    rejectedCount = rejectedChanges.size + syncConflicts.size + rejectedTimerCommands.size,
                    onRejectedClick = { showRejectedChanges = true },
                    onSyncClick = { viewModel.sync() },
                    onMakePrimaryClick = { viewModel.makeCurrentDevicePrimary() }
                )
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Config Management Section
            item {
                Text(
                    text = stringResource(R.string.settings_config_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                ConfigManagementCard(
                    exportProgress = exportProgress,
                    importProgress = importProgress,
                    onExportClick = {
                        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        configExportLauncher.launch("habits-config-${dateFormat.format(java.util.Date())}.json")
                    },
                    onImportClick = { importLauncher.launch(arrayOf("application/json")) }
                )
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Language Section
            item {
                Text(
                    text = stringResource(R.string.settings_language_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                LanguageSectionCard(
                    currentLanguage = languageCode,
                    onLanguageClick = { showLanguageDialog = true }
                )
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Theme Section
            item {
                Text(
                    text = stringResource(R.string.settings_theme_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                ThemeSectionCard(
                    currentTheme = themeMode,
                    onThemeClick = { showThemeDialog = true }
                )
            }

            // Only show light theme selector when in light mode
            if (!effectiveDarkMode) {
                item {
                    LightThemeSelectorCard(
                        currentThemeId = lightColorThemeId,
                        allThemes = allLightThemes,
                        onThemeClick = { showLightThemeDialog = true }
                    )
                }
            }

            // Only show dark theme selector when in dark mode
            if (effectiveDarkMode) {
                item {
                    DarkThemeSelectorCard(
                        currentThemeId = darkColorThemeId,
                        allThemes = allDarkThemes,
                        onThemeClick = { showDarkThemeDialog = true }
                    )
                }
            }

            // Card Color Style Section - placed after theme selectors in Theme Section
            item {
                CardColorStyleSectionCard(
                    currentStyle = cardColorStyle,
                    onStyleClick = { showCardColorStyleDialog = true }
                )
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Permission Section
            item {
                Text(
                    text = stringResource(R.string.settings_permission_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                PermissionSectionCard(
                    onPermissionClick = onNavigateToPermission
                )
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Notification Settings Section (NOTIFY-04)
            item {
                Text(
                    text = stringResource(R.string.settings_notification_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                // Global notification toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_global_notifications_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.settings_global_notifications_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = globalNotificationsEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setGlobalNotificationsEnabled(enabled)
                            }
                        )
                    }
                }
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // API Token Section
            item {
                Text(
                    text = stringResource(R.string.token_screen_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToToken() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.token_screen_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.token_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Divider
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Account Section
            item {
                Text(
                    text = stringResource(R.string.settings_account_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                AccountSection(
                    isLoggedIn = isLoggedIn,
                    isAdmin = isAdmin,
                    userEmail = userEmail,
                    onLogoutClick = { showLogoutDialog = true },
                    onLoginClick = onNavigateToLogin,
                    onAdminClick = onNavigateToAdmin
                )
            }
        }
    }

    // Sync Progress Dialog
    if (showSyncProgressDialog) {
        SyncProgressDialog(progress = syncProgress)
    }

    // Sync Error Dialog
    if (showSyncError) {
        SyncErrorDialog(
            message = syncErrorMessage,
            onRetry = { viewModel.retrySync() },
            onDismiss = { viewModel.dismissSyncError() }
        )
    }

    // Logout Sync Confirmation Dialog
    if (showLogoutDialog) {
        LogoutSyncConfirmationDialog(
            isLoggedIn = isLoggedIn,
            isOnline = isOnline,
            isLoggingOut = isLoggingOut,
            onSyncAndLogout = {
                showLogoutDialog = false
                viewModel.syncAndLogout {
                    onNavigateToLogin()
                }
            },
            onDirectLogout = {
                showLogoutDialog = false
                viewModel.directLogout {
                    onNavigateToLogin()
                }
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    // Active Timer Dialog
    if (showActiveTimerDialog) {
        ActiveTimerDialog(
            habitName = activeTimerHabitName,
            durationSeconds = activeTimerDuration,
            onDismiss = { viewModel.dismissActiveTimerDialog() }
        )
    }

    // Export Progress Dialog
    if (exportProgress) {
        ExportProgressDialog()
    }

    // Export Success Dialog
    exportResult?.let { result ->
        if (result.isSuccess) {
            ExportSuccessDialog(
                filePath = result.getOrNull() ?: "",
                onDismiss = { viewModel.dismissExportResult() }
            )
        } else {
            ExportErrorDialog(
                message = result.exceptionOrNull()?.message ?: stringResource(R.string.export_error),
                onDismiss = { viewModel.dismissExportResult() }
            )
        }
    }

    // Import Progress Dialog
    if (importProgress) {
        ImportProgressDialog()
    }

    // Import Confirmation Dialog
    importConfirmData?.let { data ->
        ImportConfirmDialog(
            data = data,
            onConfirm = { viewModel.confirmImport() },
            onCancel = { viewModel.cancelImport() }
        )
    }

    // Import Result Dialog
    importResult?.let { result ->
        if (result.isSuccess) {
            ImportSuccessDialog(
                onDismiss = { viewModel.dismissImportResult() }
            )
        } else {
            ImportErrorDialog(
                message = result.exceptionOrNull()?.message ?: stringResource(R.string.import_error),
                onDismiss = { viewModel.dismissImportResult() }
            )
        }
    }

    // Language Selection Dialog
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = languageCode,
            onLanguageSelected = { code ->
                viewModel.changeLanguage(code)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = themeMode,
            onThemeSelected = { mode ->
                viewModel.changeTheme(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    // Light Theme Selection Dialog
    if (showLightThemeDialog) {
        GlobalColorThemeSelectionDialog(
            title = stringResource(R.string.settings_light_theme),
            themes = allLightThemes,
            currentThemeId = lightColorThemeId,
            onThemeSelected = { themeId ->
                viewModel.changeLightColorTheme(themeId)
                showLightThemeDialog = false
            },
            onDeleteTheme = { themeId ->
                // Find the theme and show confirmation dialog
                val theme = allLightThemes.find { it.id == themeId }
                if (theme != null) {
                    themeToDelete = theme
                }
            },
            onShowExportOptions = { theme ->
                themeExportOptions = theme
            },
            onImportTheme = { themeImportLauncher.launch(arrayOf("application/json")) },
            onDismiss = { showLightThemeDialog = false }
        )
    }

    // Dark Theme Selection Dialog
    if (showDarkThemeDialog) {
        GlobalColorThemeSelectionDialog(
            title = stringResource(R.string.settings_dark_theme),
            themes = allDarkThemes,
            currentThemeId = darkColorThemeId,
            onThemeSelected = { themeId ->
                viewModel.changeDarkColorTheme(themeId)
                showDarkThemeDialog = false
            },
            onDeleteTheme = { themeId ->
                // Find the theme and show confirmation dialog
                val theme = allDarkThemes.find { it.id == themeId }
                if (theme != null) {
                    themeToDelete = theme
                }
            },
            onShowExportOptions = { theme ->
                themeExportOptions = theme
            },
            onImportTheme = { themeImportLauncher.launch(arrayOf("application/json")) },
            onDismiss = { showDarkThemeDialog = false }
        )
    }

    // Card Color Style Selection Dialog
    if (showCardColorStyleDialog) {
        CardColorStyleSelectionDialog(
            currentStyle = cardColorStyle,
            onStyleSelected = { style ->
                viewModel.changeCardColorStyle(style)
                showCardColorStyleDialog = false
            },
            onDismiss = { showCardColorStyleDialog = false }
        )
    }

    // Theme Import Progress Dialog
    if (themeImportProgress) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Downloading,
                    contentDescription = null
                )
            },
            title = { Text(stringResource(R.string.theme_importing)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = { }
        )
    }

    // Theme Export Progress Dialog
    if (themeExportProgress) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.IosShare,
                    contentDescription = null
                )
            },
            title = { Text(stringResource(R.string.theme_exporting)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = { }
        )
    }

    // Theme Export Result Dialog
    themeExportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissThemeExportResult() },
            icon = {
                Icon(
                    imageVector = if (result.isSuccess) Icons.Rounded.Check else Icons.Rounded.Error,
                    contentDescription = null,
                    tint = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(if (result.isSuccess) stringResource(R.string.theme_export_success) else stringResource(R.string.theme_export_error))
            },
            text = {
                if (!result.isSuccess) {
                    Text(result.exceptionOrNull()?.message ?: stringResource(R.string.theme_export_error))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissThemeExportResult() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    // Theme Import Result Dialog
    themeImportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissThemeImportResult() },
            icon = {
                Icon(
                    imageVector = if (result.isSuccess) Icons.Rounded.Check else Icons.Rounded.Error,
                    contentDescription = null,
                    tint = if (result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(if (result.isSuccess) stringResource(R.string.theme_import_success) else stringResource(R.string.theme_import_error))
            },
            text = {
                if (result.isSuccess) {
                    val theme = result.getOrNull()
                    Text(stringResource(R.string.theme_import_success_message, theme?.name ?: ""))
                } else {
                    Text(result.exceptionOrNull()?.message ?: stringResource(R.string.import_error))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissThemeImportResult() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    // Theme Delete Result Dialog
    themeDeleteResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissThemeDeleteResult() },
            icon = {
                Icon(
                    imageVector = if (result.isSuccess) Icons.Rounded.Check else Icons.Rounded.Error,
                    contentDescription = null
                )
            },
            title = {
                Text(if (result.isSuccess) stringResource(R.string.theme_delete_success) else stringResource(R.string.theme_delete_error))
            },
            text = {
                if (!result.isSuccess) {
                    Text(result.exceptionOrNull()?.message ?: stringResource(R.string.theme_delete_error))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissThemeDeleteResult() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    // Theme Delete Confirmation Dialog
    themeToDelete?.let { theme ->
        AlertDialog(
            onDismissRequest = { themeToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(stringResource(R.string.theme_delete))
            },
            text = {
                Text(stringResource(R.string.color_theme_delete_confirm, theme.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomTheme(theme.id)
                        themeToDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { themeToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Theme Export Options Dialog
    themeExportOptions?.let { theme ->
        AlertDialog(
            onDismissRequest = { themeExportOptions = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.IosShare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(stringResource(R.string.theme_export))
            },
            text = {
                Column {
                    Text(stringResource(R.string.theme_export_options_hint))
                    Spacer(modifier = Modifier.height(12.dp))
                    // Option 1: Export original (minimal)
                    OutlinedButton(
                        onClick = {
                            themeToExport = theme.id
                            themeExportOptions = null
                            themeExportLauncher.launch("theme-${theme.id}.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(stringResource(R.string.theme_export_original))
                            Text(
                                text = stringResource(R.string.theme_export_original_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Option 2: Export complete template (25 fields)
                    Button(
                        onClick = {
                            themeToExport = "${theme.id}-template"
                            themeExportOptions = null
                            themeExportLauncher.launch("theme-${theme.id}-template.json")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(stringResource(R.string.theme_export_template))
                            Text(
                                text = stringResource(R.string.theme_export_template_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { themeExportOptions = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = { }
        )
    }

    if (showRejectedChanges) {
        RejectedChangesDialog(
            changes = rejectedChanges,
            conflicts = syncConflicts,
            timerCommands = rejectedTimerCommands,
            onDismiss = { showRejectedChanges = false },
            onRetry = viewModel::retryRejectedChange,
            onRetryTimerCommand = viewModel::retryRejectedTimerCommand,
            onCancelTimerAndUseServer = {
                showRejectedChanges = false
                rejectedTimerToCancel = it
            },
            onUseServer = viewModel::resolveConflictUseServer,
            onUseLocal = viewModel::resolveConflictUseLocal,
            onDiscard = {
                showRejectedChanges = false
                rejectedChangeToDiscard = it
            }
        )
    }
    rejectedChangeToDiscard?.let { change ->
        AlertDialog(
            onDismissRequest = { rejectedChangeToDiscard = null },
            title = { Text(stringResource(R.string.sync_rejected_discard_title)) },
            text = { Text(stringResource(R.string.sync_rejected_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.discardRejectedChange(change.id)
                    rejectedChangeToDiscard = null
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectedChangeToDiscard = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    rejectedTimerToCancel?.let { command ->
        AlertDialog(
            onDismissRequest = { rejectedTimerToCancel = null },
            title = { Text(stringResource(R.string.sync_rejected_timer_cancel_title)) },
            text = { Text(stringResource(R.string.sync_rejected_timer_cancel_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelRejectedTimerCommandAndUseServer(command.id)
                    rejectedTimerToCancel = null
                }) { Text(stringResource(R.string.sync_rejected_timer_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectedTimerToCancel = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Card displaying sync status with last sync time and sync button.
 */
@Composable
private fun SyncStatusCard(
    isOnline: Boolean,
    isLoggedIn: Boolean,
    lastSyncTime: String,
    activeServerUrl: String?,
    syncProgress: SyncProgress,
    canEditStructure: Boolean,
    isPrimaryEditor: Boolean,
    rejectedCount: Int,
    onRejectedClick: () -> Unit,
    onSyncClick: () -> Unit,
    onMakePrimaryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Sync status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                val (icon, iconColor) = when {
                    !isOnline -> Icons.Rounded.CloudOff to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    syncProgress is SyncProgress.Success -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
                    syncProgress.isRunning() ->
                        Icons.Rounded.CloudSync to MaterialTheme.colorScheme.tertiary
                    else -> Icons.Rounded.CloudDone to MaterialTheme.colorScheme.primary
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Last sync time
                Text(
                    text = stringResource(R.string.sync_last_time, lastSyncTime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!activeServerUrl.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.sync_current_endpoint, activeServerUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (canEditStructure) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                isPrimaryEditor -> R.string.sync_device_role_primary
                                canEditStructure -> R.string.sync_device_role_editor
                                else -> R.string.sync_device_role_facts_only
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (!canEditStructure) {
                        TextButton(onClick = onMakePrimaryClick) {
                            Text(stringResource(R.string.sync_device_make_primary))
                        }
                    }
                }
            }

            // Sync button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSyncClick,
                    enabled = isLoggedIn && !syncProgress.isRunning(),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_now_button))
                }

                when {
                    !isLoggedIn -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sync_need_login),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !isOnline -> {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.sync_need_network),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (rejectedCount > 0) {
                TextButton(onClick = onRejectedClick) {
                    Icon(Icons.Rounded.Warning, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_rejected_count, rejectedCount))
                }
            }
        }
    }
}

private fun SyncProgress.isRunning(): Boolean = when (this) {
    is SyncProgress.UploadingChanges,
    SyncProgress.Downloading,
    SyncProgress.Recovering -> true
    else -> false
}

@Composable
private fun RejectedChangesDialog(
    changes: List<SyncOutboxEntity>,
    conflicts: List<SyncConflictEntity>,
    timerCommands: List<TimerCommandEntity>,
    onDismiss: () -> Unit,
    onRetry: (Long) -> Unit,
    onRetryTimerCommand: (Long) -> Unit,
    onCancelTimerAndUseServer: (TimerCommandEntity) -> Unit,
    onUseServer: (Long) -> Unit,
    onUseLocal: (Long) -> Unit,
    onDiscard: (SyncOutboxEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_rejected_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(conflicts, key = { "conflict-${it.id}" }) { conflict ->
                    Column {
                        Text(
                            stringResource(
                                R.string.sync_conflict_item,
                                conflict.recordType,
                                conflict.localEntityUuid.take(8)
                            )
                        )
                        Text(
                            conflict.message ?: stringResource(R.string.sync_conflict_default_message),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (conflict.conflictingFieldsJson != "[]") {
                            Text(
                                stringResource(
                                    R.string.sync_conflict_fields,
                                    conflict.conflictingFieldsJson
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            TextButton(onClick = { onUseServer(conflict.id) }) {
                                Text(stringResource(R.string.sync_conflict_use_server))
                            }
                            if (conflict.conflictKind != "deleted_conflict") {
                                TextButton(onClick = { onUseLocal(conflict.id) }) {
                                    Text(stringResource(R.string.sync_conflict_use_local))
                                }
                            }
                        }
                    }
                }
                items(changes, key = { it.id }) { change ->
                    Column {
                        Text("${change.recordType} · ${change.entityUuid.take(8)}")
                        Text(
                            change.lastError ?: stringResource(R.string.sync_rejected_unknown_error),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { onRetry(change.id) }) {
                                Text(stringResource(R.string.sync_rejected_retry))
                            }
                            TextButton(onClick = { onDiscard(change) }) {
                                Text(stringResource(R.string.sync_rejected_discard))
                            }
                        }
                    }
                }
                items(timerCommands, key = { "timer-${it.id}" }) { command ->
                    Column {
                        Text(
                            stringResource(
                                R.string.sync_rejected_timer_command,
                                command.commandType,
                                command.sessionUuid.take(8)
                            )
                        )
                        Text(
                            command.lastError
                                ?: stringResource(R.string.sync_rejected_unknown_error),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { onRetryTimerCommand(command.id) }) {
                                Text(stringResource(R.string.sync_rejected_retry))
                            }
                            TextButton(onClick = { onCancelTimerAndUseServer(command) }) {
                                Text(stringResource(R.string.sync_rejected_timer_use_server))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/**
 * Account section showing login status and logout/login button.
 * Supports logged-in and logged-out states.
 */
@Composable
private fun AccountSection(
    isLoggedIn: Boolean,
    isAdmin: Boolean,
    userEmail: String?,
    onLogoutClick: () -> Unit,
    onLoginClick: () -> Unit,
    onAdminClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Login status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isLoggedIn) Icons.Rounded.Person else Icons.Rounded.PersonOutline,
                    contentDescription = null,
                    tint = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isLoggedIn) userEmail ?: stringResource(R.string.account_logged_in) else stringResource(R.string.account_not_logged),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // The backend is authoritative for the admin role.
            if (isLoggedIn && isAdmin) {
                OutlinedButton(
                    onClick = onAdminClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_admin_dashboard))
                }
            }

            // Login/Logout button
            if (isLoggedIn) {
                OutlinedButton(
                    onClick = onLogoutClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_logout))
                }
            } else {
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Login,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_login))
                }
            }
        }
    }
}

/**
 * Blocking progress dialog shown during sync operation.
 */
@Composable
private fun SyncProgressDialog(progress: SyncProgress) {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.sync_in_progress_status)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )

                val statusText = when (progress) {
                    is SyncProgress.UploadingChanges -> stringResource(R.string.sync_uploading_changes, progress.current, progress.total)
                    is SyncProgress.Downloading -> stringResource(R.string.sync_downloading)
                    is SyncProgress.Recovering -> stringResource(R.string.sync_recovering)
                    else -> stringResource(R.string.sync_in_progress_status)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Error dialog shown when sync fails.
 */
@Composable
private fun SyncErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.sync_error)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Confirmation dialog shown when user tries to logout.
 * Offers sync-before-logout option for logged-in users.
 */
@Composable
private fun LogoutSyncConfirmationDialog(
    isLoggedIn: Boolean,
    isOnline: Boolean,
    isLoggingOut: Boolean,
    onSyncAndLogout: () -> Unit,
    onDirectLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.logout_confirm_title)) },
        text = {
            if (isLoggingOut) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_in_progress_status))
                }
            } else if (isLoggedIn && isOnline) {
                Text(stringResource(R.string.logout_confirm_sync_message))
            } else {
                Text(stringResource(R.string.logout_confirm_direct_message))
            }
        },
        confirmButton = {
            if (isLoggingOut) {
                // No buttons during logout
            } else if (isLoggedIn && isOnline) {
                // Primary action: Sync then logout
                Button(onClick = onSyncAndLogout) {
                    Text(stringResource(R.string.logout_sync_then_logout))
                }
            } else {
                // Not logged in or offline: just logout
                TextButton(
                    onClick = onDirectLogout,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        },
        dismissButton = {
            if (isLoggingOut) {
                // No dismiss during logout
            } else if (isLoggedIn && isOnline) {
                // Two options: Direct logout and Cancel
                Row {
                    TextButton(
                        onClick = onDirectLogout,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.logout_direct))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

/**
 * Blocks account-destructive actions and configuration import while a timer is active.
 * Ordinary manual and automatic synchronization remains available during timing.
 */
@Composable
private fun ActiveTimerDialog(
    habitName: String,
    durationSeconds: Int,
    onDismiss: () -> Unit
) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val durationText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.timer_active_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.timer_active_dialog_habit_running, habitName))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.timer_active_dialog_elapsed, durationText),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.timer_active_dialog_complete_first),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Card with export and import buttons.
 */
@Composable
private fun ConfigManagementCard(
    exportProgress: Boolean,
    importProgress: Boolean,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Export button
            Button(
                onClick = onExportClick,
                enabled = !exportProgress && !importProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (exportProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.export_progress_message))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_config))
                }
            }

            // Import button
            OutlinedButton(
                onClick = onImportClick,
                enabled = !exportProgress && !importProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (importProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.import_progress_message))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_config))
                }
            }

            // Hint text
            Text(
                text = stringResource(R.string.config_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Progress dialog shown during export.
 */
@Composable
private fun ExportProgressDialog() {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.export_progress_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.export_progress_message))
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Success dialog shown after export completes.
 */
@Composable
private fun ExportSuccessDialog(
    filePath: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.export_success_title)) },
        text = {
            Column {
                Text(stringResource(R.string.export_success_saved_to))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Error dialog shown when export fails.
 */
@Composable
private fun ExportErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.export_error)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Progress dialog shown during import.
 */
@Composable
private fun ImportProgressDialog() {
    AlertDialog(
        onDismissRequest = { /* Not dismissible */ },
        title = { Text(stringResource(R.string.import_progress_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.import_progress_message))
            }
        },
        confirmButton = { /* No buttons - blocking */ }
    )
}

/**
 * Confirmation dialog before import.
 * Shows counts of data that will be replaced.
 */
@Composable
private fun ImportConfirmDialog(
    data: SettingsViewModel.ImportConfirmData,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.import_confirm_title)) },
        text = {
            Column {
                // Deletion section
                Text(
                    text = stringResource(R.string.import_confirm_delete_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("• ${stringResource(R.string.import_item_habits, data.deleteHabitCount)}")
                Text("• ${stringResource(R.string.import_item_metrics, data.deleteMetricCount)}")
                if (data.deleteCompletionCount > 0) {
                    Text("• ${stringResource(R.string.import_item_completions, data.deleteCompletionCount)}")
                }
                if (data.deleteTimeLogCount > 0) {
                    Text("• ${stringResource(R.string.import_item_timelogs, data.deleteTimeLogCount)}")
                }
                if (data.deleteMetricLogCount > 0) {
                    Text("• ${stringResource(R.string.import_item_metriclogs, data.deleteMetricLogCount)}")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Import section
                Text(
                    text = stringResource(R.string.import_confirm_import_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("• ${stringResource(R.string.import_item_habits, data.importHabitCount)}")
                Text("• ${stringResource(R.string.import_item_metrics, data.importMetricCount)}")
                Text("• ${stringResource(R.string.import_item_links, data.importLinkCount)}")

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.import_confirm_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.import_confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Success dialog shown after import completes.
 */
@Composable
private fun ImportSuccessDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.import_success_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_success_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_success_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Error dialog shown when import fails.
 */
@Composable
private fun ImportErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.import_error)) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_error_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/**
 * Card displaying current language with click to change.
 */
@Composable
private fun LanguageSectionCard(
    currentLanguage: String?,
    onLanguageClick: () -> Unit
) {
    val displayLanguage = when (currentLanguage) {
        "zh" -> stringResource(R.string.settings_language_chinese)
        "en" -> stringResource(R.string.settings_language_english)
        null -> stringResource(R.string.settings_language_system)
        else -> stringResource(R.string.settings_language_system)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable { onLanguageClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = displayLanguage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dialog for selecting app language.
 * Three options: System Default, Chinese, English.
 */
@Composable
private fun LanguageSelectionDialog(
    currentLanguage: String?,
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                LanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    isSelected = currentLanguage == null,
                    onClick = { onLanguageSelected(null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageOption(
                    label = stringResource(R.string.settings_language_chinese),
                    isSelected = currentLanguage == "zh",
                    onClick = { onLanguageSelected("zh") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageOption(
                    label = stringResource(R.string.settings_language_english),
                    isSelected = currentLanguage == "en",
                    onClick = { onLanguageSelected("en") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Single language option row in the selection dialog.
 */
@Composable
private fun LanguageOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Card displaying current theme mode with click to change.
 */
@Composable
private fun ThemeSectionCard(
    currentTheme: String?,
    onThemeClick: () -> Unit
) {
    val displayTheme = when (currentTheme) {
        "light" -> stringResource(R.string.settings_theme_light)
        "dark" -> stringResource(R.string.settings_theme_dark)
        null -> stringResource(R.string.settings_theme_system)
        else -> stringResource(R.string.settings_theme_system)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable { onThemeClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.BrightnessMedium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = displayTheme,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dialog for selecting app theme mode.
 * Three options: System Default, Light, Dark.
 */
@Composable
private fun ThemeSelectionDialog(
    currentTheme: String?,
    onThemeSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.BrightnessMedium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column {
                ThemeOption(
                    label = stringResource(R.string.settings_theme_system),
                    isSelected = currentTheme == null,
                    onClick = { onThemeSelected(null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ThemeOption(
                    label = stringResource(R.string.settings_theme_light),
                    isSelected = currentTheme == "light",
                    onClick = { onThemeSelected("light") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ThemeOption(
                    label = stringResource(R.string.settings_theme_dark),
                    isSelected = currentTheme == "dark",
                    onClick = { onThemeSelected("dark") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Single theme option row in the selection dialog.
 */
@Composable
private fun ThemeOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Card displaying permission management entry.
 */
@Composable
private fun PermissionSectionCard(
    onPermissionClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable { onPermissionClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_permission),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
// ========== Global Color Theme UI Components ==========

/**
 * Card displaying current light mode theme with color swatch.
 * Only shown when app is in light mode.
 */
@Composable
private fun LightThemeSelectorCard(
    currentThemeId: String,
    allThemes: List<com.dayforge.domain.model.GlobalColorTheme>,
    onThemeClick: () -> Unit
) {
    // Find theme from actual list (includes custom themes)
    val theme = allThemes.find { it.id == currentThemeId }
        ?: com.dayforge.domain.model.DefaultGlobalColorThemes.getById(currentThemeId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onThemeClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color swatch
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.seedColor)),
                        shape = MaterialTheme.shapes.extraSmall
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_light_theme),
                    style = MaterialTheme.typography.bodyMedium
                )
                // Use theme.name for custom themes, otherwise use localized resource
                val displayName = if (theme.isCustom) {
                    theme.name
                } else {
                    val themeNameRes = when (theme.id) {
                        "ocean" -> R.string.theme_name_ocean
                        "nature" -> R.string.theme_name_nature
                        "vibrant" -> R.string.theme_name_vibrant
                        else -> R.string.theme_name_ocean
                    }
                    stringResource(themeNameRes)
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card displaying current dark mode theme with color swatch.
 * Only shown when app is in dark mode.
 */
@Composable
private fun DarkThemeSelectorCard(
    currentThemeId: String,
    allThemes: List<com.dayforge.domain.model.GlobalColorTheme>,
    onThemeClick: () -> Unit
) {
    // Find theme from actual list (includes custom themes)
    val theme = allThemes.find { it.id == currentThemeId }
        ?: com.dayforge.domain.model.DefaultGlobalColorThemes.getById(currentThemeId)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onThemeClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color swatch
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.seedColor)),
                        shape = MaterialTheme.shapes.extraSmall
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_dark_theme),
                    style = MaterialTheme.typography.bodyMedium
                )
                // Use theme.name for custom themes, otherwise use localized resource
                val displayName = if (theme.isCustom) {
                    theme.name
                } else {
                    val themeNameRes = when (theme.id) {
                        "dusk" -> R.string.theme_name_dusk
                        "forest" -> R.string.theme_name_forest
                        "coral" -> R.string.theme_name_coral
                        "oled" -> R.string.theme_name_oled
                        else -> R.string.theme_name_dusk
                    }
                    stringResource(themeNameRes)
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dialog for selecting global color theme.
 * Uses LazyColumn with theme items for extensibility.
 * Supports import/export/delete for custom themes.
 */
@Composable
private fun GlobalColorThemeSelectionDialog(
    title: String,
    themes: List<com.dayforge.domain.model.GlobalColorTheme>,
    currentThemeId: String,
    onThemeSelected: (String) -> Unit,
    onDeleteTheme: (String) -> Unit = {},
    onShowExportOptions: (com.dayforge.domain.model.GlobalColorTheme) -> Unit = {},
    onImportTheme: () -> Unit = {},
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(themes.size) { index ->
                    val theme = themes[index]
                    GlobalColorThemeOption(
                        theme = theme,
                        isSelected = theme.id == currentThemeId,
                        onClick = { onThemeSelected(theme.id) },
                        onDelete = if (theme.isCustom) { { onDeleteTheme(theme.id) } } else null,
                        onExport = { onShowExportOptions(theme) }
                    )
                }
                // Import button at bottom
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImportTheme() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.theme_import),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

/**
 * Single global color theme option row with color swatch.
 * Supports delete and export actions for custom themes.
 */
@Composable
private fun GlobalColorThemeOption(
    theme: com.dayforge.domain.model.GlobalColorTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onExport: () -> Unit = {}
) {
    val themeNameRes = when (theme.id) {
        "ocean" -> R.string.theme_name_ocean
        "nature" -> R.string.theme_name_nature
        "vibrant" -> R.string.theme_name_vibrant
        "dusk" -> R.string.theme_name_dusk
        "forest" -> R.string.theme_name_forest
        "coral" -> R.string.theme_name_coral
        "oled" -> R.string.theme_name_oled
        else -> R.string.theme_name_ocean
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }

        // Color swatch
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.seedColor)),
                    shape = MaterialTheme.shapes.extraSmall
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (theme.isCustom) theme.name else stringResource(themeNameRes),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (theme.isCustom) stringResource(R.string.theme_custom) else stringResource(R.string.theme_preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Export button
            IconButton(
                onClick = onExport,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.IosShare,
                    contentDescription = stringResource(R.string.theme_export),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Delete button (only for custom themes)
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.theme_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ========== Card Color Style UI Components ==========

/**
 * Card displaying current card color style with click to change.
 * Per CARD-01: User can toggle between "跟随主题" and "个性化配色" modes.
 * Note: Labels hardcoded in Chinese for Phase 72, i18n deferred to Phase 75.
 */
@Composable
private fun CardColorStyleSectionCard(
    currentStyle: String,
    onStyleClick: () -> Unit
) {
    val displayStyle = when (currentStyle) {
        "follow_theme" -> stringResource(R.string.card_color_style_follow_theme)
        "personalized" -> stringResource(R.string.card_color_style_personalized)
        else -> stringResource(R.string.card_color_style_follow_theme)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable { onStyleClick() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.card_color_style_title),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = displayStyle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dialog for selecting card color style.
 * Two options: "跟随主题" (Follow Theme) and "个性化配色" (Personalized).
 * Note: Labels hardcoded in Chinese for Phase 72, i18n deferred to Phase 75.
 */
@Composable
private fun CardColorStyleSelectionDialog(
    currentStyle: String,
    onStyleSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.card_color_style_title)) },
        text = {
            Column {
                CardColorStyleOption(
                    label = stringResource(R.string.card_color_style_follow_theme),
                    isSelected = currentStyle == "follow_theme",
                    onClick = { onStyleSelected("follow_theme") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                CardColorStyleOption(
                    label = stringResource(R.string.card_color_style_personalized),
                    isSelected = currentStyle == "personalized",
                    onClick = { onStyleSelected("personalized") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Single card color style option row in the selection dialog.
 */
@Composable
private fun CardColorStyleOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(28.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
