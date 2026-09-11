package com.dayforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dayforge.R

/**
 * Data class representing a category of units.
 *
 * @param name Category name (e.g., "Weight", "Money")
 * @param units List of unit strings in this category
 */
data class UnitCategory(
    val name: String,
    val units: List<String>
)

/**
 * Predefined metric units organized by category.
 * From REQUIREMENTS.md METRIC-05 preset unit list.
 * Note: Category names use raw strings here; localization happens in getLocalizedCategories().
 */
object MetricUnits {
    // Raw category keys for lookup - units are symbols/symbols that need localization
    val categories = listOf(
        UnitCategory("weight", listOf("kg", "斤", "g")),
        UnitCategory("money", listOf("¥", "$", "万")),
        UnitCategory("distance", listOf("km", "m")),
        UnitCategory("count", listOf("步", "页", "次")),
        UnitCategory("time", listOf("h", "min")),
        UnitCategory("other", listOf("L", "ml", "%", "℃"))
    )

    /**
     * Get all preset units as a flat list.
     */
    fun allUnits(): List<String> = categories.flatMap { it.units }

    /**
     * Check if a unit is a preset unit.
     */
    fun isPreset(unit: String): Boolean = allUnits().contains(unit)
}

/**
 * Get localized category names and localized unit labels for display.
 * Called within Composable context to access stringResource.
 */
@Composable
private fun getLocalizedCategories(): List<UnitCategory> {
    return listOf(
        UnitCategory(stringResource(R.string.unit_cat_weight), listOf("kg", stringResource(R.string.unit_jin), "g")),
        UnitCategory(stringResource(R.string.unit_cat_money), listOf("¥", "$", stringResource(R.string.unit_wan))),
        UnitCategory(stringResource(R.string.unit_picker_distance), listOf("km", "m")),  // Distance category
        UnitCategory(stringResource(R.string.unit_cat_count), listOf(stringResource(R.string.unit_steps), stringResource(R.string.unit_pages), stringResource(R.string.unit_times))),
        UnitCategory(stringResource(R.string.unit_cat_time), listOf("h", "min")),
        UnitCategory(stringResource(R.string.unit_picker_other), listOf("L", "ml", "%", "℃"))  // Other category
    )
}

/**
 * Dialog for selecting a metric unit.
 *
 * Per D-05: Dialog for unit selection with presets and custom input
 * Per UI-SPEC: Shows category-grouped units as FilterChips with custom option
 *
 * @param selectedUnit Currently selected unit
 * @param onUnitSelected Callback when a unit is selected
 * @param onDismiss Callback to dismiss the dialog
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitPicker(
    selectedUnit: String,
    onUnitSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var customUnit by remember { mutableStateOf("") }
    var isCustomMode by remember { mutableStateOf(false) }

    // If selected unit is not a preset, show it in custom field
    val effectiveCustomUnit = if (!MetricUnits.isPreset(selectedUnit) && selectedUnit.isNotEmpty()) {
        selectedUnit
    } else {
        customUnit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_metric_select_unit)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Preset categories (localized)
                getLocalizedCategories().forEach { category ->
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        category.units.forEach { unit ->
                            FilterChip(
                                selected = selectedUnit == unit && !isCustomMode,
                                onClick = {
                                    isCustomMode = false
                                    onUnitSelected(unit)
                                },
                                label = { Text(unit) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Custom unit option
                Text(
                    text = stringResource(R.string.unit_picker_custom),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                FilterChip(
                    selected = isCustomMode || (!MetricUnits.isPreset(selectedUnit) && selectedUnit.isNotEmpty()),
                    onClick = {
                        isCustomMode = true
                        if (effectiveCustomUnit.isNotEmpty()) {
                            onUnitSelected(effectiveCustomUnit)
                        }
                    },
                    label = { Text(stringResource(R.string.unit_picker_custom_unit)) }
                )

                // Show text field when custom is selected
                if (isCustomMode || (!MetricUnits.isPreset(selectedUnit) && selectedUnit.isNotEmpty())) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = effectiveCustomUnit,
                        onValueChange = { newValue ->
                            customUnit = newValue
                            if (newValue.isNotEmpty()) {
                                onUnitSelected(newValue)
                            }
                        },
                        label = { Text(stringResource(R.string.unit_picker_enter_custom)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}