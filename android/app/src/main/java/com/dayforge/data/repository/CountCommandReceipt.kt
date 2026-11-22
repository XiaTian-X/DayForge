package com.dayforge.data.repository

/** Immutable historical receipt issued only after the original native T1 END was observed.
 * It is not a current display claim, account permission, executable ticket or automatic
 * delivery acknowledgement. Recovery locates the same ordinary identity in the primary
 * authenticated catalog and establishes current permissions separately.
 */
class CountCommandReceipt internal constructor(internal val committed: CountCommandStore.DurableCommit) {
    val reference: CountCommandReference get() = CountCommandReference.from(committed.reference)
    val commandId: String get() = committed.reference.commandId
    val inputHash: String get() = committed.reference.inputHash
    val namespaceHash: String get() = committed.reference.namespaceHash
    val datasetId: String get() = committed.reference.datasetId
    val factUuid: String? get() = committed.outcome.primaryFactUuid
    val recorded: Boolean get() = committed.outcome.kind == CountCommandOutcome.Kind.APPLIED
    internal val requiresMetricFollowUp: Boolean get() = committed.outcome.followUp == CountCommandOutcome.FollowUp.RECHECK_ORIGINAL_RULES
    internal val sourceHabitUuid: String? get() {
        require(committed.outcome.action != CountCommandRecords.Action.METRIC_APPEND) { "COMMAND_METRIC_IS_NOT_HABIT" }
        return committed.outcome.effects.singleOrNull()?.objectUuid
    }

}

/** Value from the original business function, with optional actual-END evidence. No current
 * count or prompt permission is inferred from this wrapper.
 */
internal data class CountMutationResult<T>(val value: T, val command: CountCommandReceipt?)

/** Historical local result or an explicitly unresolved command. Neither variant is a
 * substitute for a current card/home/goal/required-prompt read and publication check.
 */
sealed interface CountCommandRecovery {
    /** The old operation committed locally. This grants no current fact/queue/display
     * eligibility and says nothing about server acceptance or pending follow-up delivery. */
    data class HistoricalCommitted(val receipt: CountCommandReceipt) : CountCommandRecovery
    data class Unresolved(val reference: CountCommandReference, val reason: String) : CountCommandRecovery
}
