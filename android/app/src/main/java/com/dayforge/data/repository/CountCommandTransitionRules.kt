package com.dayforge.data.repository

import com.dayforge.data.repository.CountCommandRecords.Delivery
import com.dayforge.data.repository.CountCommandRecords.Execution
import com.dayforge.data.repository.CountCommandRecords.Status
import kotlinx.serialization.json.jsonObject

/** Pure finite-state rules. Inputs MUST already have exact authenticated catalog/native
 * evidence; returned values grant neither SQL access nor signing authority. Expected effects
 * come from a private pre-write business plan, never by interpreting the actual journal.
 */
internal object CountCommandTransitionRules {
    fun prepared(intent: CountCommandRecords.Intent, businessGeneration: Long, parent: String? = null): Status {
        require((intent.action == CountCommandRecords.Action.LINKED_METRICS) == (parent != null))
        if (parent != null) require(CountCommandRecords.uuid(intent.body().getValue("input").jsonObject, "parent_command_id") == parent)
        return Status(intent.commandId, intent.inputHash, 1, Execution.PREPARED, Delivery.PENDING_RESOLUTION,
            parent, null, null, null, null, businessGeneration, intent.format)
    }

    fun execution(old: Status, intent: CountCommandRecords.Intent, result: CountCommandOutcome): Status {
        requireSame(old, intent)
        require(old.execution == Execution.PREPARED && old.delivery == Delivery.PENDING_RESOLUTION && old.child == null)
        result.requireIntent(intent)
        val applied = result.kind == CountCommandOutcome.Kind.APPLIED
        return old.copy(revision = next(old), execution = if (applied) Execution.APPLIED else Execution.NO_EFFECT,
            delivery = if (result.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES)
                Delivery.FOLLOW_UP_PENDING else Delivery.RESULT_PENDING)
    }

    /** A failed/expired prepared request can be terminal, but neither cancellation nor
     * invalidation may overwrite an existing immutable Outcome. Caller proves its absence.
     */
    fun abandon(old: Status, intent: CountCommandRecords.Intent, cancelled: Boolean, reason: String): Status {
        requireSame(old, intent)
        require(old.execution == Execution.PREPARED && old.child == null && reason.isNotBlank())
        return old.copy(revision = next(old), execution = if (cancelled) Execution.CANCELLED else Execution.INVALIDATED,
            delivery = Delivery.RESULT_PENDING, reason = reason)
    }

    /** Closing a human-visible result is distinct from committing business. This cannot
     * consume a pending linked-metric form; that requires the original close contract or child T1.
     * Pending-list removal subsequently clears links in this SAME root transaction.
     */
    fun delivered(old: Status): Status {
        require(old.execution != Execution.PREPARED && old.delivery in setOf(Delivery.RESULT_PENDING, Delivery.METRICS_COMMITTED))
        return old.copy(revision = next(old), delivery = Delivery.DELIVERED, previous = null, next = null)
    }

    /** Only a private caller holding the original prompt close permission or a complete
     * current no-required-prompt decision may call this transition. A receipt alone is neither.
     */
    fun closeFollowUp(old: Status): Status {
        require(old.execution == Execution.APPLIED && old.delivery == Delivery.FOLLOW_UP_PENDING && old.child == null)
        return old.copy(revision = next(old), delivery = Delivery.CLOSED, previous = null, next = null)
    }

    const val OBSOLETE_FOLLOWUP_CHILD_REASON = "EXPLICIT_OBSOLETE_FOLLOWUP_AFTER_COMPLETE_NO_EFFECTS"

    /** Private C issuer only, after complete original-source successor and whole child
     * NoEffects. No immutable Outcome is created and the historical parent edge is retained. */
    fun endObsoleteUncommittedChild(old: Status, intent: CountCommandRecords.Intent): Status {
        requireSame(old, intent)
        require(intent.action == CountCommandRecords.Action.LINKED_METRICS && old.parent != null && old.child == null &&
            old.execution == Execution.PREPARED && old.delivery == Delivery.PENDING_RESOLUTION)
        return old.copy(revision = next(old), execution = Execution.INVALIDATED, delivery = Delivery.CLOSED,
            previous = null, next = null, reason = OBSOLETE_FOLLOWUP_CHILD_REASON)
    }
    fun closeObsoleteFollowUp(old: Status, terminalChild: Status?): Status {
        require(old.execution == Execution.APPLIED && old.delivery == Delivery.FOLLOW_UP_PENDING)
        if (terminalChild == null) require(old.child == null) else require(old.child == terminalChild.commandId &&
            terminalChild.parent == old.commandId && terminalChild.execution == Execution.INVALIDATED &&
            terminalChild.delivery == Delivery.CLOSED && terminalChild.reason == OBSOLETE_FOLLOWUP_CHILD_REASON &&
            terminalChild.child == null && !terminalChild.pending)
        return old.copy(revision = next(old), delivery = Delivery.CLOSED, previous = null, next = null)
    }

    fun attachChild(old: Status, child: CountCommandRecords.Intent): Status {
        require(old.execution == Execution.APPLIED && old.delivery == Delivery.FOLLOW_UP_PENDING && old.child == null)
        require(child.action == CountCommandRecords.Action.LINKED_METRICS)
        require(CountCommandRecords.uuid(child.body().getValue("input").jsonObject, "parent_command_id") == old.commandId)
        return old.copy(revision = next(old), child = child.commandId)
    }

    /** Called in the metric business T1 together with its whole group Outcome and sources.
     * Parent result is immutable. Child ID/input is a CAS, not an in-memory submitted flag.
     */
    fun childApplied(parent: Status, childBefore: Status, childAfter: Status, result: CountCommandOutcome): Status {
        require(parent.execution == Execution.APPLIED && parent.delivery == Delivery.FOLLOW_UP_PENDING &&
            parent.child == childBefore.commandId && childBefore.parent == parent.commandId)
        require(childBefore.execution == Execution.PREPARED && childAfter.execution == Execution.APPLIED &&
            childBefore.commandId == childAfter.commandId && childBefore.inputHash == childAfter.inputHash &&
            childAfter.revision == next(childBefore) && result.action == CountCommandRecords.Action.LINKED_METRICS &&
            result.commandId == childAfter.commandId && result.inputHash == childAfter.inputHash)
        return parent.copy(revision = next(parent), delivery = Delivery.METRICS_COMMITTED)
    }

    /** A new explicit submission may supersede a PREPARED child only after the store proves
     * complete same-dataset NoEffects and excludes a still-running execution. The old ID and
     * immutable input stay catalogued; no Outcome is fabricated and no failed click is queued.
     */
    fun replaceProvenUncommittedChild(old: Status, intent: CountCommandRecords.Intent): Status {
        requireSame(old, intent)
        require(intent.action == CountCommandRecords.Action.LINKED_METRICS && old.parent != null &&
            old.execution == Execution.PREPARED && old.delivery == Delivery.PENDING_RESOLUTION && old.child == null)
        return old.copy(revision = next(old), execution = Execution.INVALIDATED, delivery = Delivery.CLOSED,
            previous = null, next = null, reason = "EXPLICIT_RESUBMISSION_AFTER_COMPLETE_NO_EFFECTS")
    }

    /** Original prompt keeps submitted=null after a genuinely uncommitted failed write, so
     * corrected human input may be attempted again. Only a proven terminal uncommitted child
     * may be detached; APPLIED or unresolved PREPARED can NEVER be detached/replaced. Its old
     * Intent/Status/full catalog member remains permanently addressable for same-ID resolution.
     */
    fun detachUncommittedChild(parent: Status, terminalChild: Status): Status {
        require(parent.execution == Execution.APPLIED && parent.delivery == Delivery.FOLLOW_UP_PENDING &&
            parent.child == terminalChild.commandId && terminalChild.parent == parent.commandId)
        require(terminalChild.execution in setOf(Execution.CANCELLED, Execution.INVALIDATED))
        return parent.copy(revision = next(parent), child = null)
    }

    fun requireAggregate(intent: CountCommandRecords.Intent, status: Status, outcome: CountCommandOutcome?) =
        requireAggregateParts(intent, status, outcome, null)

    /** The previous Aggregate has fully checked this immutable pair. All current Status
     * relations below still run; no caller-supplied verification flag or SQL proof is accepted. */
    internal fun requireStatusWithSameResult(previous: CountCommandRowPlan.Aggregate, status: Status) =
        requireAggregateParts(previous.intent, status, previous.outcome, previous)

    private fun requireAggregateParts(intent: CountCommandRecords.Intent, status: Status,
        outcome: CountCommandOutcome?, previous: CountCommandRowPlan.Aggregate?) {
        requireSame(status, intent)
        if (intent.action == CountCommandRecords.Action.METRIC_APPEND) IndependentMetricInput.requireStatus(status, outcome)
        if (status.execution in setOf(Execution.APPLIED, Execution.NO_EFFECT)) {
            requireNotNull(outcome)
            if (previous == null) outcome.requireIntent(intent)
            else require(previous.intent === intent && previous.outcome === outcome)
            require((status.execution == Execution.APPLIED) == (outcome.kind == CountCommandOutcome.Kind.APPLIED))
        } else require(outcome == null)
        require((intent.action == CountCommandRecords.Action.LINKED_METRICS) == (status.parent != null))
        if (status.reason == OBSOLETE_FOLLOWUP_CHILD_REASON) require(intent.action == CountCommandRecords.Action.LINKED_METRICS &&
            status.execution == Execution.INVALIDATED && status.delivery == Delivery.CLOSED && status.child == null && !status.pending)
    }

    private fun requireSame(status: Status, intent: CountCommandRecords.Intent) {
        require(status.commandId == intent.commandId && status.inputHash == intent.inputHash && status.format == intent.format)
    }
    private fun next(status: Status) = Math.addExact(status.revision, 1L)
}
