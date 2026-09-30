package com.dayforge.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.appearance.ThemeCatalogContent
import com.dayforge.data.appearance.ThemeInstallPhase
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.ui.screens.settings.ThemeChoiceSummary
import com.dayforge.ui.screens.settings.themeDisplayName

@Composable
internal fun ThemeRecoveryScreen(onBack: () -> Unit, viewModel: ThemeRecoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    // Activity-scoped ViewModels survive leaving this surface. Refresh on every new entry
    // so a later failure does not reuse the previous recovery's catalog or draft choices.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    ThemeRecoveryContent(state, viewModel::choose, viewModel::apply, viewModel::delete, viewModel::refresh, onBack)
}

/** A neutral repair surface, not a silently substituted application theme. */
@Composable
internal fun ThemeRecoveryContent(
    state: ThemeRecoveryState,
    onChoose: (ThemeVersionRef, Boolean) -> Unit,
    onApply: () -> Unit,
    onDelete: (ThemeVersionRef, Long) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    var deleting by remember { mutableStateOf<ThemeChoiceSummary?>(null) }
    BackHandler(onBack = onBack)
    MaterialTheme(typography = Typography) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(20.dp).testTag("theme-recovery-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.theme_recovery_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.theme_recovery_hint))
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                        if (state.busy) CircularProgressIndicator()
                        state.error?.let { Text(themeErrorMessage(it), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.action_retry)) }
                    }
                    val library = state.library
                    if (library != null) {
                        items(library.items, key = { "${it.slot.ref.themeId}:${it.slot.ref.revision}" }) { item ->
                            val summary = ThemeChoiceSummary(item, library.state.revision)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(themeDisplayName(summary), style = MaterialTheme.typography.titleMedium)
                                Text("${summary.ref.themeId} · v${summary.ref.revision}", style = MaterialTheme.typography.bodySmall)
                                if (item.content is ThemeCatalogContent.Available) {
                                    if (summary.suitableForLight) OutlinedButton(
                                        onClick = { onChoose(summary.ref, false) }, enabled = !state.busy,
                                        modifier = Modifier.testTag("theme-recovery-light-${summary.id}")
                                    ) { Text(stringResource(if (state.light == summary.ref) R.string.theme_recovery_light_selected else R.string.settings_light_theme)) }
                                    if (summary.suitableForDark) OutlinedButton(
                                        onClick = { onChoose(summary.ref, true) }, enabled = !state.busy,
                                        modifier = Modifier.testTag("theme-recovery-dark-${summary.id}")
                                    ) { Text(stringResource(if (state.dark == summary.ref) R.string.theme_recovery_dark_selected else R.string.settings_dark_theme)) }
                                } else Text(stringResource(R.string.theme_unavailable))
                                val selected = library.selected?.selection
                                if (summary.isCustom && summary.ref != selected?.light && summary.ref != selected?.dark &&
                                    (item.content !is ThemeCatalogContent.Available || item.slot.phase != ThemeInstallPhase.ACTIVE)) {
                                    TextButton(onClick = { deleting = summary }, enabled = !state.busy) {
                                        Text(stringResource(R.string.theme_delete))
                                    }
                                }
                            }
                        }
                    }
                    item { Button(onClick = onApply, enabled = state.canApply) { Text(stringResource(R.string.theme_recovery_apply)) } }
                }
            }
        }
        deleting?.let { target ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text(stringResource(R.string.theme_delete)) },
                text = { Text(stringResource(R.string.theme_recovery_delete_hint, target.ref.themeId, target.ref.revision)) },
                confirmButton = { TextButton(onClick = {
                    deleting = null
                    onDelete(target.ref, target.catalogRevision)
                }, enabled = !state.busy) { Text(stringResource(R.string.action_delete)) } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }
}
