package com.dayforge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.toColorInt
import androidx.compose.ui.unit.dp
import com.dayforge.R

/**
 * Card displaying current language with click to change.
 */
@Composable
internal fun LanguageSectionCard(
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
internal fun LanguageSelectionDialog(
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
internal fun LanguageOption(
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
internal fun ThemeSectionCard(
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
internal fun ThemeSelectionDialog(
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
internal fun ThemeOption(
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
internal fun PermissionSectionCard(
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
internal fun LightThemeSelectorCard(
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
                        color = androidx.compose.ui.graphics.Color(theme.seedColor.toColorInt()),
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
internal fun DarkThemeSelectorCard(
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
                        color = androidx.compose.ui.graphics.Color(theme.seedColor.toColorInt()),
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
internal fun GlobalColorThemeSelectionDialog(
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
internal fun GlobalColorThemeOption(
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
                    color = androidx.compose.ui.graphics.Color(theme.seedColor.toColorInt()),
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
internal fun CardColorStyleSectionCard(
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
internal fun CardColorStyleSelectionDialog(
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
internal fun CardColorStyleOption(
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
