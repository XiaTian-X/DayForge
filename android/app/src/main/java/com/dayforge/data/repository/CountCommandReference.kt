package com.dayforge.data.repository

import kotlinx.serialization.json.*

/** Stable ordinary locator only. It contains no account credential or execution permission.
 * Constructing or restoring it never grants access to an Outcome or the right to retry T1.
 */
data class CountCommandReference(val commandId: String, val inputHash: String,
    val namespaceHash: String, val datasetId: String) {
    init { native() }
    internal fun native() = CountCommandStore.Reference(commandId, inputHash, namespaceHash, datasetId)
    fun encode(): String = CountCommandRecords.canonical(buildJsonObject {
        put("format", 1); put("command_id", commandId); put("input_hash", inputHash)
        put("namespace_hash", namespaceHash); put("dataset_id", datasetId)
    })
    companion object {
        fun decode(text: String): CountCommandReference {
            // Fixed identity fields, not a limit on business input/payload size.
            val b = CountCommandRecords.parse(text.toByteArray(Charsets.UTF_8), 1024, {})
            CountCommandRecords.fields(b, "format", "command_id", "input_hash", "namespace_hash", "dataset_id")
            require(CountCommandRecords.integer(b, "format") == 1L)
            return CountCommandReference(CountCommandRecords.uuid(b, "command_id"), CountCommandRecords.digest(b, "input_hash"),
                CountCommandRecords.digest(b, "namespace_hash"), CountCommandRecords.uuid(b, "dataset_id"))
        }
        internal fun from(value: CountCommandStore.Reference) = CountCommandReference(value.commandId, value.inputHash, value.namespaceHash, value.datasetId)
    }
}
