package com.dayforge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dayforge.R
import com.dayforge.data.appearance.ValidatedTheme
import com.dayforge.domain.appearance.ThemeColorField
import com.dayforge.domain.appearance.ThemeColorGroup
import com.dayforge.domain.appearance.parseAppearanceColor
import com.dayforge.domain.model.isRgbColor
import com.dayforge.ui.theme.themeErrorMessage

/** Full-height, bounded-width editor: controls scroll on small/landscape windows and large fonts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeEditorDialog(
    state: ThemeEditorState,
    onRename: (String) -> Unit,
    onColor: (ThemeColorField, String) -> Unit,
    onReset: (ThemeColorField) -> Unit,
    onPrepare: () -> Unit,
    onConfirm: (ValidatedTheme) -> Unit,
    onBackToEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val draft = state.draft
    if (draft == null) {
        AlertDialog(onDismissRequest = { if (!state.busy) onCancel() },
            title = { Text(stringResource(R.string.color_theme_edit)) },
            text = { if (state.busy) CircularProgressIndicator() else Text(themeErrorMessage(state.error)) },
            confirmButton = { TextButton(onClick = onCancel, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } })
        return
    }
    var discard by rememberSaveable(draft.target.choiceKey()) { mutableStateOf(false) }
    var dark by rememberSaveable(draft.target.choiceKey()) { mutableStateOf(false) }
    var groupKey by rememberSaveable(draft.target.choiceKey()) { mutableStateOf(ThemeColorGroup.MATERIAL.key) }
    val group = ThemeColorGroup.entries.single { it.key == groupKey }
    val fields = ThemeColorField.all.filter { it.dark == dark && it.group == group }
    val requestClose = {
        if (!state.busy) { if (draft.changed || state.locked) discard = true else onCancel() }
    }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Scaffold(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().imePadding(),
                topBar = { TopAppBar(title = { Text(stringResource(if (state.preview == null) R.string.color_theme_edit else R.string.theme_editor_preview)) },
                    navigationIcon = { IconButton(onClick = requestClose, enabled = !state.busy, modifier = Modifier.testTag("theme-edit-close")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                    } }) },
                bottomBar = { EditorBottomBar(state, onPrepare, onConfirm, onBackToEdit) }
            ) { padding ->
                EditorFields(state, dark, { dark = it }, group, { groupKey = it.key }, fields,
                    onRename, onColor, onReset, Modifier.padding(padding))
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.theme_editor_discard)) },
        text = { Text(stringResource(if (state.locked) R.string.theme_editor_confirmed_hint else R.string.theme_editor_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; onCancel() }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun EditorFields(state: ThemeEditorState, dark: Boolean, onDark: (Boolean) -> Unit,
    group: ThemeColorGroup, onGroup: (ThemeColorGroup) -> Unit, fields: List<ThemeColorField>,
    onRename: (String) -> Unit, onColor: (ThemeColorField, String) -> Unit,
    onReset: (ThemeColorField) -> Unit, modifier: Modifier) {
    val draft = checkNotNull(state.draft)
    val preview = state.preview
    LazyColumn(modifier.fillMaxSize().testTag("theme-edit-fields"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.theme_editor_source, draft.source.name, draft.source.revision),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (draft.source.themeId == draft.target.themeId) R.string.theme_editor_target
                else R.string.theme_editor_fork, draft.target.revision), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.theme_editor_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (state.error != null) item { Text(themeErrorMessage(state.error), color = MaterialTheme.colorScheme.error) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(value = preview?.definition?.name ?: draft.name, onValueChange = onRename,
                label = { Text(stringResource(R.string.color_theme_name_label)) }, singleLine = true,
                readOnly = preview != null, enabled = !state.busy, isError = !draft.validName,
                supportingText = { if (!draft.validName) Text(stringResource(R.string.theme_editor_name_error)) },
                modifier = Modifier.fillMaxWidth().testTag("theme-edit-name"))
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in listOf(false, true)) FilterChip(selected = dark == option,
                    onClick = { onDark(option) }, label = { Text(stringResource(if (option) R.string.settings_dark_theme else R.string.settings_light_theme)) },
                    modifier = Modifier.testTag("theme-edit-side-${if (option) "dark" else "light"}"))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in ThemeColorGroup.entries) FilterChip(selected = group == option,
                    onClick = { onGroup(option) }, label = { Text(stringResource(when (option) {
                        ThemeColorGroup.MATERIAL -> R.string.theme_editor_material
                        ThemeColorGroup.STATUS -> R.string.theme_editor_status
                        ThemeColorGroup.CHART -> R.string.theme_editor_chart
                    })) }, modifier = Modifier.testTag("theme-edit-group-${option.key}"))
            }
        }
        items(fields, key = { it.key }) { field ->
            val palette = preview?.definition?.let { if (field.dark) it.dark else it.light }
            val value = if (palette == null) draft.color(field) else when (field.group) {
                ThemeColorGroup.MATERIAL -> palette.material
                ThemeColorGroup.STATUS -> palette.status
                ThemeColorGroup.CHART -> palette.chart
            }.getValue(field.role)
            EditorColorField(field, value, draft.originalColor(field), !state.busy,
                readOnly = preview != null, changed = field.key in draft.edits,
                onChange = { onColor(field, it) }, onReset = { onReset(field) })
        }
    }
}

@Composable
private fun EditorColorField(field: ThemeColorField, value: String, original: String,
    enabled: Boolean, readOnly: Boolean, changed: Boolean, onChange: (String) -> Unit, onReset: () -> Unit) {
    val valid = isRgbColor(value)
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(field.role) }, enabled = enabled, readOnly = readOnly, isError = !valid,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        supportingText = { Text(if (valid) stringResource(R.string.theme_editor_original, original)
            else stringResource(R.string.theme_editor_rgb)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(if (valid) Color(parseAppearanceColor(value))
                    else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall))
                if (!readOnly) IconButton(onClick = onReset, enabled = enabled && changed,
                    modifier = Modifier.testTag("theme-edit-reset-${field.key}")) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.theme_editor_reset, field.role))
                } else Spacer(Modifier.width(12.dp))
            }
        }, modifier = Modifier.fillMaxWidth().testTag("theme-edit-color-${field.key}"))
}

@Composable
private fun EditorBottomBar(state: ThemeEditorState, onPrepare: () -> Unit,
    onConfirm: (ValidatedTheme) -> Unit, onBackToEdit: () -> Unit) {
    Surface {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val preview = state.preview
            if (preview != null && !state.locked) IconButton(onClick = onBackToEdit, enabled = !state.busy,
                modifier = Modifier.testTag("theme-edit-back")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.theme_editor_back))
            }
            Button(onClick = { if (preview == null) onPrepare() else onConfirm(preview) },
                enabled = !state.busy && state.draft?.valid == true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme-edit-save")) {
                Text(stringResource(if (preview == null) R.string.theme_editor_preview else R.string.theme_editor_save))
            }
        }
    }
}
