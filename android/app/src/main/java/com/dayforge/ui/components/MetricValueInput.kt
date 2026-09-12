package com.dayforge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.util.NumericInputUtils
import java.util.Locale

/**
 * MetricValueInput dialog for recording metric values with optional notes.
 *
 * Per D-12 to D-14:
 * - Dialog for inputting value + optional note
 * - Uses a compact numeric-entry dialog
 * - Save to MetricLogEntity via onConfirm callback
 *
 * @param metricName Display name of the metric
 * @param unit Unit of measurement (e.g., "kg", "steps")
 * @param decimalPlaces Number of decimal places allowed (0 for integers)
 * @param currentValue Latest recorded value, null if none
 * @param targetValue Optional target value for context display
 * @param targetDirection Optional direction ("increase", "decrease", "range") for display
 * @param onConfirm Callback with (value, note) when user confirms
 * @param onDismiss Callback when user dismisses dialog
 */
@Composable
fun MetricValueInput(
    metricName: String,
    unit: String,
    decimalPlaces: Int,
    currentValue: Double?,
    targetValue: Double?,
    targetDirection: String?,
    onConfirm: (value: Double, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var inputValue by rememberSaveable { mutableStateOf("") }
    var noteValue by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Formats and validates the numeric input.
     * Returns the parsed Double if valid, null otherwise.
     */
    fun parseAndValidate(input: String): Double? {
        if (input.isEmpty()) {
            validationError = null
            return null
        }

        val trimmed = input.trim()

        // Check for valid number format
        val doubleValue = NumericInputUtils.parseFiniteDouble(trimmed)
        if (doubleValue == null) {
            validationError = context.getString(R.string.metric_input_error_invalid_number)
            return null
        }

        validationError = null
        return doubleValue
    }

    // Build context text showing current/target info
    val contextText = buildString {
        currentValue?.let { current ->
            append(context.getString(R.string.metric_input_current, formatMetricValue(current, decimalPlaces), unit))
        }
        targetValue?.let { target ->
            if (isNotEmpty()) append(" | ")
            val directionLabel = when (targetDirection) {
                "increase" -> context.getString(R.string.metric_input_target_format, formatMetricValue(target, decimalPlaces), unit)
                "decrease" -> context.getString(R.string.metric_input_target_format, formatMetricValue(target, decimalPlaces), unit)
                "range" -> context.getString(R.string.metric_input_target_range_format, formatMetricValue(target, decimalPlaces), unit)
                else -> context.getString(R.string.metric_input_target_format, formatMetricValue(target, decimalPlaces), unit)
            }
            append(directionLabel)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.metric_input_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                // Metric name
                Text(
                    text = metricName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Context info (current/target)
                if (contextText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = contextText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Value input
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = { newValue ->
                        inputValue = NumericInputUtils.filterNumericInput(newValue, decimalPlaces)
                        parseAndValidate(inputValue)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.metric_input_enter_value)) },
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it) } },
                    suffix = { Text(unit) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Note input (optional)
                OutlinedTextField(
                    value = noteValue,
                    onValueChange = { noteValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.metric_input_note_optional)) },
                    supportingText = { Text(stringResource(R.string.metric_input_note_hint)) }
                )
            }
        },
        confirmButton = {
            val parsedValue = parseAndValidate(inputValue)
            Button(
                onClick = {
                    parsedValue?.let { value ->
                        onConfirm(value, noteValue.trim())
                    }
                },
                enabled = parsedValue != null
            ) {
                Text(text = stringResource(R.string.metric_record_value))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Formats a metric value according to its decimal places setting.
 */
private fun formatMetricValue(value: Double, decimalPlaces: Int): String {
    return if (decimalPlaces == 0) {
        value.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
    }
}
