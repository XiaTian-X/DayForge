package com.dayforge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.util.NumericInputUtils

/**
 * State for a single metric input in the post-check-in dialog.
 *
 * @param metricId The ID of the metric
 * @param name The display name of the metric
 * @param unit The unit of measurement
 * @param decimalPlaces Number of decimal places allowed
 * @param inputValue Current input value as string
 * @param note Optional note for this record
 */
data class MetricInputState(
    val metricId: Long,
    val name: String,
    val unit: String,
    val decimalPlaces: Int,
    val inputValue: String = "",
    val note: String = ""
)

/** A validated metric value submitted by the dialog. */
data class MetricValueInput(
    val metricId: Long,
    val value: Double,
    val note: String = ""
)

/**
 * Dialog for recording linked metrics after habit check-in.
 *
 * Per METRIC-09: Prompts user to record linked metrics after checking in.
 * Per D-15 to D-19:
 * - D-15: Dialog appears after check-in for habits with linked metrics
 * - D-16: Dialog shows all linked metrics with promptOnComplete=true
 * - D-17: Inline input for recording values
 * - D-18: "不再询问" checkbox per habit
 * - D-19: "记录" and "跳过" buttons
 *
 * @param habitName The name of the habit that was checked in
 * @param linkedMetrics List of linked metrics to display
 * @param onRecord Callback that submits all valid values as one operation
 * @param onSkip Callback when user skips recording
 * @param onDismiss Callback to dismiss the dialog
 */
@Composable
fun PostCheckInDialog(
    habitName: String,
    linkedMetrics: List<LinkedMetricInfo>,
    onRecord: (values: List<MetricValueInput>, neverAskAgain: Boolean) -> Unit,
    onSkip: (neverAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var neverAskAgain by remember { mutableStateOf(false) }

    // State for each metric's input
    val inputStates = remember(linkedMetrics) {
        linkedMetrics.map { info ->
            mutableStateOf(
                MetricInputState(
                    metricId = info.metricId,
                    name = info.metricName,
                    unit = info.unit,
                    decimalPlaces = info.decimalPlaces
                )
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.post_checkin_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Dialog message
                Text(
                    text = stringResource(R.string.post_checkin_message, habitName),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Inline input for each metric (D-17)
                linkedMetrics.forEachIndexed { index, info ->
                    val state = inputStates[index]

                    OutlinedTextField(
                        value = state.value.inputValue,
                        onValueChange = { newValue ->
                            state.value = state.value.copy(
                                inputValue = NumericInputUtils.filterNumericInput(newValue, state.value.decimalPlaces)
                            )
                        },
                        label = { Text("${info.metricName} (${info.unit})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Never ask again checkbox (D-18)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = neverAskAgain,
                        onCheckedChange = { neverAskAgain = it }
                    )
                    Text(stringResource(R.string.post_checkin_dont_ask_again))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = inputStates.any { NumericInputUtils.parseFiniteDouble(it.value.inputValue) != null },
                onClick = {
                    val values = inputStates.mapNotNull { state ->
                        NumericInputUtils.parseFiniteDouble(state.value.inputValue)?.let { value ->
                            MetricValueInput(
                                metricId = state.value.metricId,
                                value = value,
                                note = state.value.note
                            )
                        }
                    }
                    onRecord(values, neverAskAgain)
                }
            ) {
                Text(stringResource(R.string.post_checkin_record))  // D-19
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onSkip(neverAskAgain)
                }
            ) {
                Text(stringResource(R.string.post_checkin_skip))  // D-19
            }
        }
    )
}
