package com.dayforge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dayforge.R
import com.dayforge.util.NumericInputUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

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

/** Current-operation display only. DELIVERED means the original T2 returned, not ACK or a rendered frame. */
enum class MetricSubmissionPhase { SAVING, SAVED_DELIVERING, SAVED_PENDING, DELIVERED }

/** Optional presentation-only suspension. Callers retain ownership of validation and
 * cancellation; this value never authorizes personal content or a business operation. */
data class PostCheckInDialogPresentation(
    val showContent: Boolean,
    val waitingText: String,
    val onWaitingDismiss: () -> Unit
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
    modifier: Modifier = Modifier,
    initialInputs: Map<Long, Pair<String, String>> = emptyMap(),
    promptIdentity: String? = null,
    onDraftChange: suspend (List<MetricInputState>) -> Unit = {},
    onRefreshMetadata: (suspend () -> Unit)? = null,
    missingTargets: List<String> = emptyList(),
    presentation: PostCheckInDialogPresentation? = null,
    submissionProgress: StateFlow<MetricSubmissionPhase?>? = null,
    savedSubmission: (() -> com.dayforge.ui.metrics.SavedMetricSubmission?)? = null,
    onContinue: (suspend () -> Unit)? = null
) {
    val showContent = presentation?.showContent != false
    val submissionPhase = submissionProgress?.let { progress ->
        key(progress) { progress.collectAsState().value }
    }
    fun savedNow() = submissionProgress?.value in setOf(MetricSubmissionPhase.SAVED_DELIVERING,
        MetricSubmissionPhase.SAVED_PENDING, MetricSubmissionPhase.DELIVERED)
    val savedPhase = submissionPhase in setOf(MetricSubmissionPhase.SAVED_DELIVERING,
        MetricSubmissionPhase.SAVED_PENDING, MetricSubmissionPhase.DELIVERED)
    val saved = if (savedPhase) savedSubmission?.invoke() else null
    var neverAskAgain by remember(promptIdentity, showContent) { mutableStateOf(false) }
    var busy by remember(promptIdentity, showContent) { mutableStateOf(false) }
    fun busyNow() = busy || submissionProgress?.value in setOf(MetricSubmissionPhase.SAVING, MetricSubmissionPhase.SAVED_DELIVERING)
    val processing = busy || submissionPhase in setOf(MetricSubmissionPhase.SAVING, MetricSubmissionPhase.SAVED_DELIVERING)
    // Ordinary callers retain the original scope. The optional presentation path
    // retires old draft/submission jobs without replacing the AlertDialog window.
    val scope = if (presentation == null) rememberCoroutineScope()
        else key(promptIdentity, showContent) { rememberCoroutineScope() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var draftJob by remember(promptIdentity, showContent) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun submit(block: suspend () -> Unit) {
        if (busyNow()) return
        busy = true // Set before launching: even two callbacks before recomposition cannot submit twice.
        scope.launch {
            try {
                draftJob?.cancel(); draftJob?.join()
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    android.widget.Toast.makeText(context, com.dayforge.ui.actionFailureMessage(context, error),
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } finally { busy = false }
        }
    }

    // State for each metric's input
    val inputStates = remember(promptIdentity, linkedMetrics, showContent) {
        if (!showContent) emptyList() else linkedMetrics.map { info ->
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
        modifier = modifier,
        onDismissRequest = {
            if (!showContent) requireNotNull(presentation).onWaitingDismiss()
            else submit {
                if (!savedNow()) onDraftChange(inputStates.map { it.value })
                onDismiss()
            }
        },
        title = {
            if (showContent) Text(
                text = stringResource(R.string.post_checkin_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            if (!showContent) {
                Text(requireNotNull(presentation).waitingText)
            } else Column(
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

                // A missing display copy is not permission to edit or infer saved values.
                if (savedPhase && saved == null) Text(stringResource(R.string.metric_submission_saved_input_missing),
                    color = MaterialTheme.colorScheme.error)
                // Inline input for each metric (D-17)
                if (!savedPhase || saved != null) linkedMetrics.forEachIndexed { index, info ->
                    val state = inputStates[index]

                    OutlinedTextField(
                        enabled = !processing && !savedPhase,
                        value = if (savedPhase) saved?.values?.singleOrNull { it.metricId == info.metricId }?.value?.toString().orEmpty()
                            else state.value.inputValue,
                        onValueChange = input@ { newValue ->
                            if (busyNow() || savedNow()) return@input
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
                    } }, enabled = !processing && !savedPhase) { Text(stringResource(R.string.once_refresh_metrics)) }
                }

                if (submissionProgress != null) {
                    val status = when (submissionPhase) {
                        MetricSubmissionPhase.SAVING -> stringResource(R.string.metric_submission_saving)
                        MetricSubmissionPhase.SAVED_DELIVERING -> stringResource(R.string.metric_submission_saved_delivering)
                        MetricSubmissionPhase.SAVED_PENDING -> stringResource(R.string.metric_submission_saved_pending)
                        MetricSubmissionPhase.DELIVERED -> stringResource(R.string.metric_submission_delivered)
                        null -> if (processing) stringResource(R.string.metric_submission_processing) else " "
                    }
                    // Reserve the status area for this formal prompt; keep its window and inputs.
                    Text(status, style = MaterialTheme.typography.bodySmall, minLines = 2,
                        modifier = Modifier.fillMaxWidth())
                }

                // Never ask again checkbox (D-18)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        enabled = !processing && !savedPhase,
                        checked = saved?.neverAskAgain ?: neverAskAgain,
                        onCheckedChange = { if (!busyNow() && !savedNow()) neverAskAgain = it }
                    )
                    Text(stringResource(R.string.post_checkin_dont_ask_again))
                }
            }
        },
        confirmButton = {
            if (showContent) Button(
                enabled = !processing && if (savedPhase) saved != null && onContinue != null
                    else inputStates.any { NumericInputUtils.parseFiniteDouble(it.value.inputValue) != null },
                onClick = click@ {
                    if (savedNow()) {
                        val continuation = onContinue ?: return@click
                        submit { continuation() }
                        return@click
                    }
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
                val recordLabel = stringResource(R.string.post_checkin_record)
                val continueLabel = stringResource(R.string.metric_submission_continue)
                val processingLabel = stringResource(R.string.metric_submission_processing)
                Box(contentAlignment = Alignment.Center) {
                    // Reserve the entire action area, including progress, before the first click.
                    listOf(recordLabel, continueLabel, processingLabel).forEach { label ->
                        Row(Modifier.alpha(0f).clearAndSetSemantics {}) {
                            Spacer(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (processing) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (processing) processingLabel else if (savedPhase) continueLabel else recordLabel)
                    }
                }
            }
        },
        dismissButton = {
            if (!showContent) {
                TextButton(onClick = { requireNotNull(presentation).onWaitingDismiss() }) {
                    Text(stringResource(android.R.string.cancel))
                }
            } else TextButton(
                enabled = !processing,
                onClick = {
                    if (savedNow()) submit { onDismiss() }
                    else submit { onSkip(neverAskAgain) }
                }
            ) {
                Text(stringResource(if (savedPhase) R.string.metric_submission_later else R.string.post_checkin_skip))
            }
        }
    )
}
