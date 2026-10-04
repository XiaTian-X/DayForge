package com.dayforge.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.appearance.IconPackVersion
import com.dayforge.data.appearance.IconRasterSize
import com.dayforge.data.appearance.BuiltInTheme
import com.dayforge.domain.appearance.ThemeVersionRef
import com.dayforge.domain.model.IconAsset
import com.dayforge.domain.service.AccountIconController
import com.dayforge.domain.service.IconImageState
import com.dayforge.domain.service.IconPackSource
import com.dayforge.ui.theme.LocalResolvedTheme
import com.dayforge.ui.theme.toComposeImage
import kotlinx.coroutines.Dispatchers

/** Local library only: online v4 business references remain unchanged until coordinated v5. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconLibraryScreen(onNavigateBack: () -> Unit, viewModel: IconLibraryViewModel = hiltViewModel()) {
    // Guarded publication runs on IO. Dispatch collection instead of allowing an
    // eager composition continuation to mutate snapshots on the publisher thread.
    val state by viewModel.state.collectAsState(context = Dispatchers.Main)
    DisposableEffect(viewModel) {
        viewModel.openPage()
        onDispose { viewModel.closePage() }
    }
    // Unconditional registration; restored results without a live ViewModel ticket fail closed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::pickerResult)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.icon_library_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary), navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.content_description_back))
            }
        })
    }) { padding ->
        IconLibraryContent(state, viewModel.icons, onImport = {
            if (viewModel.beginPicker()) try { picker.launch(arrayOf("*/*")) }
            catch (_: ActivityNotFoundException) { viewModel.pickerUnavailable() }
            catch (_: SecurityException) { viewModel.pickerUnavailable() }
        }, onInspect = viewModel::inspect, onInstall = viewModel::install,
            onSelect = viewModel::select, onDismissPreview = viewModel::dismissPreview,
            onRefresh = viewModel::refresh, modifier = Modifier.padding(padding))
    }
}

@Composable
internal fun IconLibraryContent(
    state: IconLibraryState, icons: AccountIconController, onImport: () -> Unit,
    onInspect: (com.dayforge.domain.model.IconPack) -> Unit, onInstall: () -> Unit,
    onSelect: (IconPackVersion?) -> Unit, onDismissPreview: () -> Unit, onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = !state.busy && !state.loading && !state.picking
    val source = state.source
    var dark by remember(source) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().testTag("icon-library-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.icon_library_local_only), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, enabled = enabled && state.context != null,
                    modifier = Modifier.testTag("icon-library-import")) { Text(stringResource(R.string.icon_library_import)) }
                TextButton(onClick = onRefresh, enabled = enabled && !state.picking) { Text(stringResource(R.string.icon_library_refresh)) }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.picking) Text(stringResource(R.string.icon_library_picking))
            state.error?.let { error ->
                Text(stringResource(if (error == "ICON_ACCESS_DENIED") R.string.icon_library_auth_required
                    else if (error.startsWith("ICON_PICKER_")) R.string.icon_library_picker_expired
                    else R.string.icon_library_failed, error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("icon-library-error"))
            }
            if (state.installed) Text(stringResource(R.string.icon_library_installed), Modifier.testTag("icon-library-installed"))
            if (state.context?.access?.canDeclare == false) Text(stringResource(R.string.icon_library_read_only))
        }
        if (source != null) {
            item {
                Text(source.pack.name, style = MaterialTheme.typography.titleMedium)
                Text("${source.pack.packId} · ${source.pack.revision}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.icon_library_dark), Modifier.weight(1f))
                    Switch(dark, onCheckedChange = { dark = it }, modifier = Modifier.testTag("icon-library-dark"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source.preview != null && !state.installed) {
                        Button(onClick = onInstall, enabled = enabled && state.context?.access?.canDeclare == true,
                            modifier = Modifier.testTag("icon-library-install")) { Text(stringResource(R.string.icon_library_install)) }
                    }
                    TextButton(onClick = onDismissPreview, enabled = enabled) { Text(stringResource(R.string.icon_library_close_preview)) }
                }
            }
            items(source.pack.assets, key = { "asset:${it.assetId}" }) { asset ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconLibraryImage(icons, source, asset, dark)
                    Column(Modifier.weight(1f)) {
                        Text(asset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(if (asset.purpose == "task") R.string.icon_library_task else R.string.icon_library_general) +
                            " · " + stringResource(if (asset.colorMode == "template") R.string.icon_library_template else R.string.icon_library_original),
                            style = MaterialTheme.typography.bodySmall)
                        val roles = source.pack.roles.filterValues { it == asset.assetId }.keys.sorted().joinToString(", ")
                        if (roles.isNotEmpty()) Text(roles, style = MaterialTheme.typography.bodySmall)
                        Text(asset.assetId, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.icon_library_packages), style = MaterialTheme.typography.titleMedium)
            if (state.catalog?.packs?.isEmpty() == true) Text(stringResource(R.string.icon_library_empty))
            if (state.catalog?.selection?.pack != null) TextButton(onClick = { onSelect(null) }, enabled = enabled,
                modifier = Modifier.testTag("icon-library-clear")) { Text(stringResource(R.string.icon_library_clear)) }
        }
        items(state.catalog?.packs.orEmpty(), key = { "pack:${it.packId}:${it.revision}" }) { pack ->
            val version = IconPackVersion(pack.packId, pack.revision)
            val selected = state.catalog?.selection?.pack == version
            val ready = version in state.catalog?.readyVersions.orEmpty()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall)
                    Text("${pack.packId} · ${pack.revision}", style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (selected) R.string.icon_library_selected else if (ready)
                        R.string.icon_library_ready else R.string.icon_library_incomplete), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onInspect(pack) }, enabled = enabled,
                            modifier = Modifier.testTag("icon-library-inspect:${pack.packId}:${pack.revision}")) { Text(stringResource(R.string.icon_library_preview)) }
                        TextButton(onClick = { onSelect(version) }, enabled = enabled && ready && !selected,
                            modifier = Modifier.testTag("icon-library-select:${pack.packId}:${pack.revision}")) { Text(stringResource(R.string.icon_library_select)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun IconLibraryImage(icons: AccountIconController, source: IconPackSource, asset: IconAsset, dark: Boolean) {
    val resolved = LocalResolvedTheme.current
    val theme = ThemeVersionRef(resolved?.themeId ?: BuiltInTheme.OCEAN.themeId, resolved?.revision ?: 1)
    val size = with(LocalDensity.current) { 48.dp.roundToPx().coerceIn(1, 1024) }.let { IconRasterSize(it, it) }
    val tint = MaterialTheme.colorScheme.primary.toArgb()
    val handle = remember(icons, source, asset.assetId, theme, dark, size, tint) { icons.image() }
    DisposableEffect(handle) { onDispose { handle.close() } }
    LaunchedEffect(handle) { icons.load(handle, source, asset.assetId, theme, dark, size, tint) }
    val image by handle.state.collectAsState(context = Dispatchers.Main)
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant)
        .testTag("icon-library-image:${asset.assetId}"), contentAlignment = Alignment.Center) {
        when (val current = image) {
            is IconImageState.Ready -> Image(current.raster.toComposeImage(), contentDescription = asset.name, modifier = Modifier.fillMaxSize())
            is IconImageState.Failed -> Text(stringResource(R.string.icon_library_image_failed), style = MaterialTheme.typography.labelSmall)
            IconImageState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconImageState.Empty -> Text("—")
        }
    }
}
