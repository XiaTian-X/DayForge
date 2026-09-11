package com.dayforge.ui.screens.createmetric

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
 * Two-step metric creation screen.
 *
 * Per UI-SPEC:
 * - Step 1: Basic info (name, description, unit, decimals, target)
 * - Step 2: Appearance (icon, color)
 *
 * @param viewModel ViewModel for state management
 * @param onNavigateBack Callback when user navigates back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMetricScreen(
    viewModel: CreateMetricViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.currentStep == 1) stringResource(R.string.create_metric_screen_title) else stringResource(R.string.create_metric_appearance_step)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.currentStep == 2) {
                            viewModel.goToStep(1)
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (uiState.currentStep) {
                1 -> MetricBasicInfoStep(
                    uiState = uiState,
                    onNameChange = viewModel::updateName,
                    onDescriptionChange = viewModel::updateDescription,
                    onUnitClick = viewModel::toggleUnitPicker,
                    onDecimalPlacesChange = viewModel::updateDecimalPlaces,
                    onAggregationTypeChange = viewModel::updateAggregationType,
                    onTargetDirectionChange = viewModel::updateTargetDirection,
                    onTargetValueChange = viewModel::updateTargetValueInput,
                    onTargetValueUpperChange = viewModel::updateTargetValueUpperInput
                )
                2 -> MetricAppearanceStep(
                    uiState = uiState,
                    onIconClick = viewModel::toggleIconPicker,
                    onColorClick = viewModel::toggleColorPicker,
                    onHabitToggle = viewModel::toggleHabitSelection
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Bottom button
            Button(
                onClick = {
                    if (uiState.currentStep == 1) {
                        viewModel.goToStep(2)
                    } else {
                        viewModel.saveMetric()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isStepValid && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (uiState.currentStep == 1) stringResource(R.string.create_metric_next) else stringResource(R.string.create_metric_screen_title))
                }
            }
        }

        // Navigate back after metric is saved
        LaunchedEffect(uiState.savedMetricId) {
            if (uiState.savedMetricId != null) {
                onNavigateBack()
                viewModel.clearSavedMetric()
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
                title = { Text(stringResource(R.string.dialog_cannot_create)) },
                text = { Text(stringResource(R.string.dialog_duplicate_metric_name)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDuplicateDialog() }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            )
        }
    }
}

/**
 * Step 1: Basic information form.
 */
@Composable
private fun MetricBasicInfoStep(
    uiState: CreateMetricUiState,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onUnitClick: () -> Unit,
    onDecimalPlacesChange: (Int) -> Unit,
    onAggregationTypeChange: (String) -> Unit,
    onTargetDirectionChange: (String?) -> Unit,
    onTargetValueChange: (String) -> Unit,
    onTargetValueUpperChange: (String) -> Unit
) {
    // Name field (required)
    OutlinedTextField(
        value = uiState.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.edit_metric_name_required)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Description field (optional)
    OutlinedTextField(
        value = uiState.description,
        onValueChange = onDescriptionChange,
        label = { Text(stringResource(R.string.edit_metric_description_label)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Unit selector
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUnitClick() },
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
                onClick = { onDecimalPlacesChange(places) },
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
            onClick = { onAggregationTypeChange("average") },
            label = { Text(stringResource(R.string.edit_metric_aggregation_average)) },
            leadingIcon = if (uiState.aggregationType == "average") {
                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
            } else null
        )
        FilterChip(
            selected = uiState.aggregationType == "sum",
            onClick = { onAggregationTypeChange("sum") },
            label = { Text(stringResource(R.string.edit_metric_aggregation_sum)) },
            leadingIcon = if (uiState.aggregationType == "sum") {
                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
            } else null
        )
        FilterChip(
            selected = uiState.aggregationType == "by_time",
            onClick = { onAggregationTypeChange("by_time") },
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
            onClick = { onTargetDirectionChange(null) },
            label = { Text(stringResource(R.string.edit_metric_target_none)) }
        )
        FilterChip(
            selected = uiState.targetDirection == "increase",
            onClick = { onTargetDirectionChange("increase") },
            label = { Text(stringResource(R.string.edit_metric_target_increase)) }
        )
        FilterChip(
            selected = uiState.targetDirection == "decrease",
            onClick = { onTargetDirectionChange("decrease") },
            label = { Text(stringResource(R.string.edit_metric_target_decrease)) }
        )
        FilterChip(
            selected = uiState.targetDirection == "range",
            onClick = { onTargetDirectionChange("range") },
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
                onValueChange = onTargetValueChange,
                label = { Text(stringResource(R.string.edit_metric_lower_bound)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.targetValueUpperInput,
                onValueChange = onTargetValueUpperChange,
                label = { Text(stringResource(R.string.edit_metric_upper_bound)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
        } else {
            // Increase/Decrease: show single target value
            OutlinedTextField(
                value = uiState.targetValueInput,
                onValueChange = onTargetValueChange,
                label = { Text(stringResource(R.string.edit_metric_target_value)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
        }
    }
}

/**
 * Step 2: Appearance selection and habit linking.
 */
@Composable
private fun MetricAppearanceStep(
    uiState: CreateMetricUiState,
    onIconClick: () -> Unit,
    onColorClick: () -> Unit,
    onHabitToggle: (Long) -> Unit
) {
    // Icon selector
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onIconClick() },
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
            .clickable { onColorClick() },
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

    // Habit Links Section (D-08)
    if (uiState.availableHabits.isNotEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.create_metric_link_habits_optional),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.create_metric_link_habits_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        uiState.availableHabits.forEach { habit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHabitToggle(habit.id) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = habit.id in uiState.selectedHabitIds,
                    onCheckedChange = { onHabitToggle(habit.id) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = habit.name,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}