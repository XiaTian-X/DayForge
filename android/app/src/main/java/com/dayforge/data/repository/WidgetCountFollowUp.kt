package com.dayforge.data.repository

import android.content.Intent

/** Ordinary process-restorable locators. Both the receipt and source are freshly validated
 * by the receiving graph; extras never carry an owner lease, authorization or Tx context.
 */
internal data class WidgetCountFollowUp(val command: CountCommandReference, val completionSource: WidgetFactClaim? = null) {
    fun attach(intent: Intent) {
        intent.putExtra(REFERENCE, command.encode())
        completionSource?.let { intent.putExtra(SOURCE, it.encode()) }
    }
    companion object {
        private const val REFERENCE = "com.dayforge.widget.count.command"
        private const val SOURCE = "com.dayforge.widget.count.completion_source"
        fun read(intent: Intent): WidgetCountFollowUp? {
            val reference = intent.getStringExtra(REFERENCE)
            val source = intent.getStringExtra(SOURCE)
            require(reference != null || source == null) { "COMMAND_FOLLOWUP_REFERENCE_REQUIRED" }
            return reference?.let { WidgetCountFollowUp(CountCommandReference.decode(it), source?.let(WidgetFactClaim::decode)) }
        }
    }
}
