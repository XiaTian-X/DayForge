package com.dayforge.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.dayforge.R
import com.dayforge.data.appearance.ValidatedTheme

@Composable
internal fun ThemeImportPreviewDialog(preview: ValidatedTheme, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.theme_import_preview)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${preview.definition.name} · v${preview.definition.revision}")
                for ((label, palette) in listOf(R.string.settings_light_theme to preview.definition.light,
                    R.string.settings_dark_theme to preview.definition.dark)) {
                    Text(stringResource(label))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (role in listOf("primary", "secondary", "tertiary", "surface", "background")) {
                            Box(Modifier.size(28.dp).background(Color(palette.material.getValue(role).toColorInt()),
                                MaterialTheme.shapes.extraSmall))
                        }
                    }
                }
                Text(stringResource(R.string.theme_import_preview_hint))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } }
    )
}
