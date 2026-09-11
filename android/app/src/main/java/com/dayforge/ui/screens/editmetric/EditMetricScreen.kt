package com.dayforge.ui.screens.editmetric

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.ui.components.ColorPicker
import com.dayforge.ui.components.IconPicker
import com.dayforge.ui.components.UnitPicker

/**
 * Screen for editing an existing metric.
 *
 * Single scrollable form with all editable fields:
 * - Name, description
 * - Unit, decimal places
 * - Target direction and values
 * - Icon and color
 * - Active status (for archiving)
 *
 * Per UI-SPEC: Edit screen allows modifying all metric properties
 *
 * @param metricId The ID of the metric to edit (from navigation)
 * @param onNavigateBack Callback when user navigates back
 * @param onMetricDeleted Callback when metric is deleted (unused here, handled by detail screen)
 * @param viewModel ViewModel for state management
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMetricScreen(
    metricId: Long,
    viewModel: EditMetricViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onMetricDeleted: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate back after save
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_metric_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                    }
                }
            )
        }
    ) { padding ->
        if (!uiState.isLoaded) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.errorMessage == stringResource(R.string.toast_metric_not_found)) {
            // Error state - metric not found
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.toast_metric_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            // Content - single scrollable form
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Name field (required)
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::updateName,
                    label = { Text(stringResource(R.string.edit_metric_name_required)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = uiState.errorMessage != null && uiState.errorMessage != stringResource(R.string.toast_metric_not_found)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description field (optional)
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text(stringResource(R.string.edit_metric_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Unit selector
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleUnitPicker() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.edit_metric_unit_required),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (uiState.unit.isNotEmpty()) {
                                Text(
                                    text = uiState.unit,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.edit_metric_select_unit))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Decimal places selection
                Text(
                    text = stringResource(R.string.edit_metric_decimal_places_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 1, 2).forEach { places ->
                        FilterChip(
                            selected = uiState.decimalPlaces == places,
                            onClick = { viewModel.updateDecimalPlaces(places) },
                            label = { Text(places.toString()) },
                            leadingIcon = if (uiState.decimalPlaces == places) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Aggregation type selection
                Text(
                    text = stringResource(R.string.edit_metric_aggregation_type_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.aggregationType == "average",
                        onClick = { viewModel.updateAggregationType("average") },
                        label = { Text(stringResource(R.string.edit_metric_aggregation_average)) },
                        leadingIcon = if (uiState.aggregationType == "average") {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = uiState.aggregationType == "sum",
                        onClick = { viewModel.updateAggregationType("sum") },
                        label = { Text(stringResource(R.string.edit_metric_aggregation_sum)) },
                        leadingIcon = if (uiState.aggregationType == "sum") {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                    FilterChip(
                        selected = uiState.aggregationType == "by_time",
                        onClick = { viewModel.updateAggregationType("by_time") },
                        label = { Text(stringResource(R.string.edit_metric_aggregation_by_time)) },
                        leadingIcon = if (uiState.aggregationType == "by_time") {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target direction selection
                Text(
                    text = stringResource(R.string.edit_metric_target_direction_label),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.targetDirection == null,
                        onClick = { viewModel.updateTargetDirection(null) },
                        label = { Text(stringResource(R.string.edit_metric_target_none)) }
                    )
                    FilterChip(
                        selected = uiState.targetDirection == "increase",
                        onClick = { viewModel.updateTargetDirection("increase") },
                        label = { Text(stringResource(R.string.edit_metric_target_increase)) }
                    )
                    FilterChip(
                        selected = uiState.targetDirection == "decrease",
                        onClick = { viewModel.updateTargetDirection("decrease") },
                        label = { Text(stringResource(R.string.edit_metric_target_decrease)) }
                    )
                    FilterChip(
                        selected = uiState.targetDirection == "range",
                        onClick = { viewModel.updateTargetDirection("range") },
                        label = { Text(stringResource(R.string.edit_metric_target_range)) }
                    )
                }

                // Target value inputs (conditional)
                if (uiState.targetDirection != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Determine keyboard type based on decimalPlaces
                    val keyboardType = if (uiState.decimalPlaces > 0) {
                        KeyboardType.Decimal
                    } else {
                        KeyboardType.Number
                    }

                    if (uiState.targetDirection == "range") {
                        // Range: show two inputs for lower and upper bound
                        OutlinedTextField(
                            value = uiState.targetValueInput,
                            onValueChange = { viewModel.updateTargetValueInput(it) },
                            label = { Text(stringResource(R.string.edit_metric_lower_bound)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.targetValueUpperInput,
                            onValueChange = { viewModel.updateTargetValueUpperInput(it) },
                            label = { Text(stringResource(R.string.edit_metric_upper_bound)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
                        )
                    } else {
                        // Increase/Decrease: show single target value
                        OutlinedTextField(
                            value = uiState.targetValueInput,
                            onValueChange = { viewModel.updateTargetValueInput(it) },
                            label = { Text(stringResource(R.string.edit_metric_target_value)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Icon selector
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleIconPicker() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.edit_metric_icon_label),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.edit_metric_select_icon))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Color selector
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleColorPicker() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.edit_metric_color_label),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        color = Color(android.graphics.Color.parseColor(uiState.colorHex)),
                                        shape = MaterialTheme.shapes.small
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.edit_metric_select_color))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Active status switch (for archiving)
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.edit_metric_active_label),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.edit_metric_active_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.isActive,
                            onCheckedChange = { viewModel.toggleActive() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save button
                Button(
                    onClick = { viewModel.saveMetric() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isValid && !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.edit_metric_save_changes))
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.showUnitPicker) {
        UnitPicker(
            selectedUnit = uiState.unit,
            onUnitSelected = viewModel::updateUnit,
            onDismiss = viewModel::toggleUnitPicker
        )
    }

    if (uiState.showIconPicker) {
        IconPicker(
            selectedIconId = uiState.iconResId,
            onIconSelected = viewModel::updateIcon,
            onDismiss = viewModel::toggleIconPicker
        )
    }

    if (uiState.showColorPicker) {
        ColorPicker(
            selectedColor = uiState.colorHex,
            onColorSelected = viewModel::updateColor,
            onDismiss = viewModel::toggleColorPicker
        )
    }

    // Duplicate name error dialog
    if (uiState.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicateDialog() },
            title = { Text(stringResource(R.string.dialog_cannot_save_title)) },
            text = { Text(stringResource(R.string.dialog_duplicate_metric_name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}