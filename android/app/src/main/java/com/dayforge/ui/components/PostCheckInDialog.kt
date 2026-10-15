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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    onRecord: suspend (values: List<MetricValueInput>, neverAskAgain: Boolean) -> Unit,
    onSkip: suspend (neverAskAgain: Boolean) -> Unit,
    onDismiss: suspend () -> Unit,
    initialInputs: Map<Long, Pair<String, String>> = emptyMap(),
    promptIdentity: String? = null,
    onDraftChange: suspend (List<MetricInputState>) -> Unit = {},
    onRefreshMetadata: (suspend () -> Unit)? = null,
    missingTargets: List<String> = emptyList()
) {
    var neverAskAgain by remember(promptIdentity) { mutableStateOf(false) }
    var busy by remember(promptIdentity) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var draftJob by remember(promptIdentity) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun submit(block: suspend () -> Unit) {
        if (busy) return
        busy = true // Set before launching: even two callbacks before recomposition cannot submit twice.
        scope.launch {
            try {
                draftJob?.cancel(); draftJob?.join()
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error),
                    android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    // State for each metric's input
    val inputStates = remember(promptIdentity, linkedMetrics) {
        linkedMetrics.map { info ->
            mutableStateOf(
                MetricInputState(
                    metricId = info.metricId,
                    name = info.metricName,
                    unit = info.unit,
                    decimalPlaces = info.decimalPlaces,
                    inputValue = initialInputs[info.metricId]?.first.orEmpty(),
                    note = initialInputs[info.metricId]?.second.orEmpty()
                )
            )
        }
    }

    AlertDialog(
        onDismissRequest = { submit {
            onDraftChange(inputStates.map { it.value })
            onDismiss()
        } },
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
                if (missingTargets.isNotEmpty()) {
                    Text(stringResource(R.string.once_metrics_missing, missingTargets.joinToString()),
                        color = MaterialTheme.colorScheme.error)
                }

                // Inline input for each metric (D-17)
                linkedMetrics.forEachIndexed { index, info ->
                    val state = inputStates[index]

                    OutlinedTextField(
                        enabled = !busy,
                        value = state.value.inputValue,
                        onValueChange = input@ { newValue ->
                            if (busy) return@input
                            state.value = state.value.copy(
                                inputValue = NumericInputUtils.filterNumericInput(newValue, state.value.decimalPlaces)
                            )
                            val draft = inputStates.map { it.value }
                            val previous = draftJob
                            previous?.cancel()
                            draftJob = scope.launch {
                                previous?.join()
                                try {
                                    onDraftChange(draft)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    android.widget.Toast.makeText(context, context.getString(R.string.metric_error_record_failed,
                                        error.message.orEmpty()), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
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

                onRefreshMetadata?.let { refresh ->
                    TextButton(onClick = { submit {
                        onDraftChange(inputStates.map { it.value })
                        refresh()
                    } }, enabled = !busy) { Text(stringResource(R.string.once_refresh_metrics)) }
                }

                // Never ask again checkbox (D-18)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        enabled = !busy,
                        checked = neverAskAgain,
                        onCheckedChange = { neverAskAgain = it }
                    )
                    Text(stringResource(R.string.post_checkin_dont_ask_again))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && inputStates.any { NumericInputUtils.parseFiniteDouble(it.value.inputValue) != null },
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
                    submit {
                        // Flush exact visible text before submission; cancelled superseded drafts
                        // cannot trail the final write. Durable identities/time remain repository-owned.
                        onDraftChange(inputStates.map { it.value })
                        onRecord(values, neverAskAgain)
                    }
                }
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.post_checkin_record))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    submit { onSkip(neverAskAgain) }
                }
            ) {
                Text(stringResource(R.string.post_checkin_skip))  // D-19
            }
        }
    )
}
