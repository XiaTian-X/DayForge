package com.dayforge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.repository.ObjectAppearanceAuthority
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import kotlinx.coroutines.Dispatchers

/** Replaces only the existing icon dialog, not the approved phone/tablet page layout. */
@Composable
internal fun ObjectAppearancePicker(
    appearance: ObjectAppearance, oneTime: Boolean, authority: ObjectAppearanceAuthority?,
    onSelected: (ObjectAppearance) -> Unit, onDismiss: () -> Unit,
    viewModel: ObjectAppearancePickerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState(context = Dispatchers.Main)
    DisposableEffect(viewModel, appearance, oneTime, authority) {
        viewModel.open(appearance, oneTime, authority)
        onDispose { viewModel.close() }
    }
    ObjectAppearancePickerContent(state, oneTime, viewModel::choose, viewModel::tint,
        onConfirm = { viewModel.confirm(onSelected) }, onDismiss = onDismiss)
}

@Composable
internal fun ObjectAppearancePickerContent(
    state: ObjectAppearancePickerState, oneTime: Boolean,
    onChoose: (IconReference) -> Unit, onTint: (String) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.object_icon_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.object_icon_picker_hint), style = MaterialTheme.typography.bodySmall)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(stringResource(R.string.object_icon_picker_expired), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("object-icon-picker-error"))
                }
                state.appearance?.let { appearance ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(appearance.iconTint == "theme", onClick = { onTint("theme") },
                            modifier = Modifier.weight(1f), enabled = !state.loading,
                            label = { Text(stringResource(R.string.object_icon_tint_theme)) })
                        FilterChip(appearance.iconTint == "object", onClick = { onTint("object") },
                            modifier = Modifier.weight(1f), enabled = !state.loading,
                            label = { Text(stringResource(R.string.object_icon_tint_object)) })
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("object-icon-picker-list")) {
                        items(state.choices, key = { it.reference.toString() }) { choice ->
                            val selected = choice.reference == appearance.icon
                            TextButton(onClick = { onChoose(choice.reference) }, enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    RadioButton(selected, onClick = null)
                                    ObjectIcon(choice.reference.toString(), appearance.copy(icon = choice.reference),
                                        oneTime, 0, Color.Unspecified, 28.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(choice.label)
                                        Text(stringResource(if (choice.reference is IconReference.Role)
                                            R.string.object_icon_role else R.string.object_icon_fixed),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.loading && state.error == null && state.appearance != null,
                modifier = Modifier.testTag("object-icon-picker-confirm")) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
