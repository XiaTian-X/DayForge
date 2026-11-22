package com.dayforge.data.repository

/** Known OS entry identity, not a claim that an absent result never committed. Both variants
 * require the original current-display checks before any card, goal or prompt is shown.
 */
internal sealed interface WidgetCountCommandRecovery {
    data class Committed(val receipt: CountCommandReceipt) : WidgetCountCommandRecovery
    data class Unresolved(val commandId: String, val reason: String) : WidgetCountCommandRecovery
}
