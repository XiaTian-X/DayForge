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
