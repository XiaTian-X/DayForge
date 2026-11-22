package com.dayforge.widget.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.dayforge.R
import com.dayforge.data.local.DatabaseRuntimeHost
import com.dayforge.data.model.CheckInResult
import com.dayforge.data.repository.CountCommandReceipt
import com.dayforge.data.repository.LegacyWidgetFactClaim
import com.dayforge.data.repository.LegacyWidgetRestartClaim
import com.dayforge.data.repository.WidgetCountCommandRecovery
import com.dayforge.data.repository.WidgetFactClaim
import com.dayforge.data.repository.WidgetFactSnapshot
import com.dayforge.data.local.entity.HabitEntity
import com.dayforge.domain.service.CountActionGate
import com.dayforge.widget.base.WidgetActivityTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Dedicated per-tap task. Raw mode performs only the system identity handoff. No durable
 * acceptance is claimed until T0; no saved result is claimed until actual T1 END/recovery.
 * This candidate remains blocked on the complete platform and primary continuity gates.
 */
@AndroidEntryPoint
class CountWidgetActionActivity : ComponentActivity() {
    @Inject internal lateinit var host: DatabaseRuntimeHost
    @Inject lateinit var countGate: CountActionGate
    private class Display(val name: String, val receipt: CountCommandReceipt) {
        val delivered = kotlinx.coroutines.CompletableDeferred<Unit>()
    }
    private data class Current(val habit: HabitEntity, val result: CheckInResult.Success,
        val source: WidgetFactSnapshot?, val legacy: LegacyWidgetRestartClaim?)
    private val display = kotlinx.coroutines.flow.MutableStateFlow<com.dayforge.data.local.DatabaseEpochValue<Display>?>(null)
    private fun publishDisplay(frame: com.dayforge.data.local.DatabaseEpochValue<Display>?, assignment: () -> Unit): Boolean {
        val accepted = if (frame == null || display.value !== frame) false else frame.publication.withSourceFence {
            if (display.value !== frame) false else frame.publication.publish(assignment)
        }
        // Coroutine continuations run only after the source/account/Host publication locks.
        if (frame != null) {
            if (accepted) frame.value.delivered.complete(Unit)
            else frame.value.delivered.completeExceptionally(IllegalStateException("DATABASE_EPOCH_SELECTION_CHANGED"))
        }
        return accepted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = try { WidgetCountTaskEntry.enter(this) }
        catch (error: Exception) { report(error); finish(); return }
        if (entry == WidgetCountTaskEntry.Result.HandedOff) return
        val input: WidgetCountTaskEntry.Input
        val commandId: String
        val fresh: Boolean
        when (entry) {
            is WidgetCountTaskEntry.Result.Fresh -> { input = entry.input; commandId = entry.commandId; fresh = true }
            is WidgetCountTaskEntry.Result.Restore -> { input = entry.input; commandId = entry.commandId; fresh = false }
            WidgetCountTaskEntry.Result.HandedOff -> error("Already returned")
        }
        val frames = com.dayforge.ui.producerEpochFrameState(lifecycleScope, host,
            kotlinx.coroutines.flow.SharingStarted.Eagerly, display, ::publishDisplay)
        setContent { WidgetActivityTheme(this) {
            val frame by frames.collectAsState()
            frame?.value?.let { accepted -> Text(getString(if (accepted.receipt.recorded)
                R.string.widget_count_locally_saved else R.string.widget_count_no_change, accepted.name)) }
                ?: Text(getString(R.string.widget_count_processing))
        } }
        // Fresh handoff was consumed before this first Gate. A rejected known record can
        // only resolve on recreation, never become another new command after busy ends.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lease: CountActionGate.Lease? = null
            try {
                // Known-ID recovery may execute an authenticated PREPARED T1. Its per-ID
                // Store lease does not replace the original shared same-habit admission.
                lease = when (input) {
                    is WidgetCountTaskEntry.Input.Typed -> countGate.tryAcquire(input.claim.session(), input.claim.habitUuid, input.claim.habitId)
                    is WidgetCountTaskEntry.Input.Legacy -> countGate.tryAcquire(input.claim.identity.session(), input.claim.identity.habitUuid, input.claim.identity.habitId)
                }
                if (lease == null) {
                    Toast.makeText(this@CountWidgetActionActivity, if (fresh) R.string.widget_count_busy_not_recorded
                        else R.string.widget_count_restore_busy, Toast.LENGTH_LONG).show()
                    return@launch
                }
                host.withGraph { graph ->
                    val value = if (fresh) when (input) {
                        is WidgetCountTaskEntry.Input.Typed -> graph.checkInService.widgetCountActionWithReceipt(
                            this@CountWidgetActionActivity, input.claim, input.action, commandId).let { result ->
                            Current(requireNotNull(result.current.followUp).habit, result.current, result.completionSource, null)
                        }
                        is WidgetCountTaskEntry.Input.Legacy -> graph.legacyWidgetFactWorkflow.performCountWithReceipt(
                            this@CountWidgetActionActivity, input.claim, commandId).let { Current(it.habit, it.result, null, it.identity) }
                    } else {
                        val recovered = when (input) {
                            is WidgetCountTaskEntry.Input.Typed -> graph.checkInService.resumeWidgetCountCommand(
                                this@CountWidgetActionActivity, input.claim, input.action, commandId)
                            is WidgetCountTaskEntry.Input.Legacy -> graph.legacyWidgetFactWorkflow.resumeCountCommand(
                                this@CountWidgetActionActivity, input.claim, commandId)
                        }
                        val receipt = when (recovered) {
                            is WidgetCountCommandRecovery.Committed -> recovered.receipt
                            is WidgetCountCommandRecovery.Unresolved -> {
                                Toast.makeText(this@CountWidgetActionActivity, R.string.widget_count_result_unresolved, Toast.LENGTH_LONG).show()
                                return@withGraph
                            }
                        }
                        // A retained known-ID Intent can outlive T2. Current authenticated
                        // delivery controls follow-up; immutable Outcome alone cannot reopen it.
                        if (!graph.countCommandPendingRepository.isPending(receipt.reference)) return@withGraph
                        when (input) {
                            is WidgetCountTaskEntry.Input.Typed -> graph.widgetFactReader.afterCountCommand(input.claim,
                                receipt.factUuid.takeIf { input.action == "increment" && receipt.recorded }).let { read ->
                                val current = read.current
                                Current(current.habit, CheckInResult.Success(current.completed, current.targetProgress,
                                    input.action == "increment" && current.habit.targetCycles?.let { current.targetProgress >= it } == true,
                                    recorded = receipt.recorded, followUp = current, command = receipt), read.completionSource, null)
                            }
                            is WidgetCountTaskEntry.Input.Legacy -> graph.legacyWidgetFactWorkflow.afterResolvedCount(
                                input.claim.identity, input.action, receipt).let { Current(it.habit, it.result, null, it.identity) }
                        }
                    }
                    val receipt = requireNotNull(value.result.command)
                    val publication = host.currentPublication()
                    val model = Display(value.habit.name, receipt)
                    val frame = com.dayforge.data.local.DatabaseEpochValue(model, publication)
                    var accepted = false
                    if (value.legacy != null) graph.legacyWidgetFactWorkflow.publish(value.legacy) {
                        accepted = publication.publish { display.value = frame }
                    } else graph.widgetFactReader.publish(requireNotNull(value.result.followUp).claim) {
                        accepted = publication.publish { display.value = frame }
                    }
                    check(accepted) { "DATABASE_EPOCH_SELECTION_CHANGED" }
                    model.delivered.await()
                    // This is an actual model consumer, not a claim that Compose drew pixels.
                    // T2 is separate from reliable save and happens outside the source lock.
                    if (!receipt.requiresMetricFollowUp) com.dayforge.domain.service.afterCommittedAction {
                        graph.habitRepository.acknowledgeCountResultDelivery(receipt)
                    }
                    if (value.result.goalReached) {
                        startActivity(GoalCompletionActivity.createIntent(this@CountWidgetActionActivity,
                            value.habit.id, value.habit.name, value.result.progress, requireNotNull(value.habit.targetCycles),
                            claim = value.result.followUp?.claim, legacyIdentity = value.legacy,
                            countCommand = receipt.reference, countCompletionSource = value.source?.claim, restoreCount = !fresh))
                    } else if (receipt.requiresMetricFollowUp) {
                        startActivity(MetricPromptActivity.createIntent(this@CountWidgetActionActivity,
                            value.habit.id, value.habit.name, factClaim = value.source?.claim,
                            legacyIdentity = value.legacy, countCommand = receipt.reference, restoreCount = !fresh))
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                report(error)
            } finally {
                lease?.let(countGate::release)
                com.dayforge.widget.WidgetRefreshScheduler.request(this@CountWidgetActionActivity)
                finish()
            }
        }
    }
    private fun report(error: Exception) {
        Log.w("CountWidgetAction", "COUNT entry unavailable", error)
        Toast.makeText(this, com.dayforge.ui.actionFailureMessage(this, error), Toast.LENGTH_LONG).show()
    }
    companion object {
        internal fun typedIntent(context: Context, claim: WidgetFactClaim, action: String): Intent =
            WidgetCountTaskEntry.rawIntent(context, CountWidgetActionActivity::class.java, WidgetCountTaskEntry.Input.Typed(claim, action))
        internal fun legacyIntent(context: Context, claim: LegacyWidgetFactClaim): Intent =
            WidgetCountTaskEntry.rawIntent(context, CountWidgetActionActivity::class.java, WidgetCountTaskEntry.Input.Legacy(claim))
    }
}
