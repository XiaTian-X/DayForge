package com.dayforge.ui.recovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dayforge.R
import com.dayforge.data.repository.CountCommandPendingRepository
import com.dayforge.data.repository.CountCommandRecords
import com.dayforge.data.repository.CountCommandStore
import com.dayforge.widget.checkin.GoalCompletionActivity
import com.dayforge.widget.checkin.MetricPromptActivity

/** Explicit, non-modal doorway. Opening or paging never retries a write or acknowledges it.
 * Closing this view leaves all unfinished results in the authenticated persistent directory. */
@Composable
internal fun CountCommandRecoveryPanel(viewModel: CountCommandRecoveryViewModel = hiltViewModel()) {
    var open by remember { mutableStateOf(false) }
    val frame by viewModel.frames.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val context = LocalContext.current
    TextButton(onClick = { open = true; viewModel.open() }) { Text(stringResource(R.string.count_recovery_open)) }
    val original = frame
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    LaunchedEffect(open, original?.value?.followUp) {
        if (open && original != null) viewModel.consumeFollowUp(original) { followUp ->
            val goal = followUp.goalProgress
            val intent = if (goal != null) GoalCompletionActivity.createIntent(context, followUp.habit.id,
                followUp.habit.name, goal, requireNotNull(followUp.habit.targetCycles),
                claim = followUp.current?.claim, legacyIdentity = followUp.legacyIdentity,
                countCommand = followUp.reference, countCompletionSource = followUp.completion?.claim, restoreCount = true)
            else MetricPromptActivity.createIntent(context, followUp.habit.id, followUp.habit.name,
                factClaim = followUp.completion?.claim, legacyIdentity = followUp.legacyIdentity,
                countCommand = followUp.reference, restoreCount = true)
            context.startActivity(intent)
        }
    }
    if (open) AlertDialog(onDismissRequest = { viewModel.close(); open = false },
        title = { Text(stringResource(R.string.count_recovery_title)) },
        text = { Column {
            Text(stringResource(R.string.count_recovery_explanation))
            if (failed) Text(stringResource(R.string.count_recovery_refresh_required))
            if (busy) Text(stringResource(R.string.widget_count_processing))
            original?.let { source ->
                when (val result = source.value.result) {
                    is CountCommandPendingRepository.Result.Saved -> Text(stringResource(if (result.receipt.recorded)
                        R.string.count_recovery_saved else R.string.count_recovery_no_effect))
                    is CountCommandPendingRepository.Result.IndependentEndAvailable -> {
                        Text(stringResource(R.string.independent_metric_unexecuted))
                        TextButton(enabled = !busy, onClick = { viewModel.endIndependent(source) }) {
                            Text(stringResource(R.string.independent_metric_end_action))
                        }
                    }
                    is CountCommandPendingRepository.Result.IndependentEnded -> Text(stringResource(R.string.independent_metric_ended))
                    is CountCommandPendingRepository.Result.Unresolved -> Text(stringResource(R.string.count_recovery_unresolved))
                    is CountCommandPendingRepository.Result.MetricParent -> Text(stringResource(R.string.count_recovery_metric_pending))
                    is CountCommandPendingRepository.Result.Invalidated -> Text(stringResource(when (result.reason) {
                        com.dayforge.data.repository.CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_PLAN_CHANGED -> R.string.count_recovery_invalidated_plan
                        com.dayforge.data.repository.CountCommandInvalidationDomain.Reason.ORIGINAL_COUNT_ROUND_CHANGED -> R.string.count_recovery_invalidated_round
                    }))
                    is CountCommandPendingRepository.Result.EndAvailable -> {
                        Text(stringResource(when (result.reason) {
                            com.dayforge.data.repository.CountCommandObsoleteFollowUp.Reason.FORMAL_ACCEPTED_ROUND_SUCCESSOR -> R.string.count_recovery_followup_obsolete_round
                            com.dayforge.data.repository.CountCommandObsoleteFollowUp.Reason.FORMAL_ORIGINAL_COUNT_REVERTED -> R.string.count_recovery_followup_obsolete_reverted
                            com.dayforge.data.repository.CountCommandObsoleteFollowUp.Reason.FORMAL_ACTIVITY_DELETE_ACCEPTED -> R.string.count_recovery_followup_obsolete_deleted
                        }))
                        if (!result.preferenceReady) Text(stringResource(R.string.count_recovery_metrics_hint_unresolved))
                        TextButton(enabled = !busy && result.preferenceReady, onClick = { viewModel.endFollowUp(source) }) {
                            Text(stringResource(R.string.count_recovery_end_followup))
                        }
                    }
                    is CountCommandPendingRepository.Result.FollowUpEnded -> Text(stringResource(R.string.count_recovery_followup_ended))
                    is CountCommandPendingRepository.Result.StandaloneMetricSaved ->
                        Text(pluralStringResource(R.plurals.count_recovery_metrics_saved, 1, 1))
                    is CountCommandPendingRepository.Result.MetricSaved -> {
                        Text(pluralStringResource(R.plurals.count_recovery_metrics_saved, result.count, result.count))
                        if (result.pending && !result.preferenceReady)
                            Text(stringResource(R.string.count_recovery_metrics_hint_unresolved))
                    }
                    null -> Unit
                }
                val retainedCount = source.value.page.value.retainedCount
                if (retainedCount > 0L)
                    Text(pluralStringResource(R.plurals.count_recovery_retained,
                        retainedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), retainedCount))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(source.value.page.value.entries, key = { it.reference.commandId }) { entry ->
                        Column {
                            val label = when (entry.action) {
                                CountCommandRecords.Action.COUNT_ADD -> R.string.count_recovery_add
                                CountCommandRecords.Action.COUNT_UNDO -> R.string.count_recovery_undo
                                CountCommandRecords.Action.LINKED_METRICS, CountCommandRecords.Action.METRIC_APPEND -> R.string.count_recovery_metrics
                            }
                            Text(stringResource(label, entry.reference.commandId.takeLast(8)))
                            if (entry.scope != CountCommandStore.LocationScope.CURRENT)
                                Text(stringResource(R.string.count_recovery_scope_required))
                            val historicalMetric = entry.action == CountCommandRecords.Action.METRIC_APPEND &&
                                entry.scope == CountCommandStore.LocationScope.INITIAL_REGISTRATION_REVALIDATION_REQUIRED &&
                                entry.execution in setOf(CountCommandRecords.Execution.APPLIED, CountCommandRecords.Execution.CANCELLED)
                            TextButton(enabled = !busy && (entry.scope == CountCommandStore.LocationScope.CURRENT || historicalMetric),
                                onClick = { viewModel.resume(source, entry.reference) }) {
                                Text(stringResource(if (historicalMetric) R.string.count_recovery_view_result else R.string.count_recovery_continue))
                            }
                        }
                    }
                }
                if (!source.value.page.value.complete) TextButton(enabled = !busy,
                    onClick = { viewModel.next(source) }) { Text(stringResource(R.string.count_recovery_next)) }
                else Text(stringResource(R.string.count_recovery_end))
            }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = viewModel::refresh) { Text(stringResource(R.string.count_recovery_refresh)) } },
        dismissButton = { TextButton(onClick = { viewModel.close(); open = false }) { Text(stringResource(R.string.count_recovery_close)) } })
}
