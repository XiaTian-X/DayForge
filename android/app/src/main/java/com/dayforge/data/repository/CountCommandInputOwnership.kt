package com.dayforge.data.repository

import com.dayforge.data.local.LocalCoreWriteAccess
import kotlinx.serialization.json.*

/** Pure ownership check at the actual T0 entry, before binding/root/Intent/Status writes.
 * The private Store then authenticates this exact namespace against its current native root
 * in the same transaction. No current storage/session is copied into an old display claim.
 * This is not source membership, business eligibility or an execution permission: all original
 * T1 claim/source/target/permission/complete-effect checks remain mandatory.
 */
internal object CountCommandInputOwnership {
    fun requireForRegistration(intent: CountCommandRecords.Intent, access: LocalCoreWriteAccess) {
        val namespace = intent.namespace
        require(namespace.account == access.session.authentication.userId && namespace.server == access.session.serverInstanceId &&
            namespace.epoch == access.session.syncEpoch && namespace.device == access.capturedDeviceId &&
            CountCommandRecords.text(intent.body(), "original_auth_generation") == access.session.authentication.generation) {
            "COMMAND_CAPTURE_AUTHORITY_EXPIRED"
        }
        val input = intent.body().getValue("input").jsonObject
        if (CountCommandLegacyWidgetInput.matches(intent)) {
            val claim = CountCommandLegacyWidgetInput.claim(input).identity
            require(claim.session() == access.session && claim.deviceId == access.capturedDeviceId &&
                claim.habitUuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                claim.habitId == CountCommandRecords.integer(input, "habit_id")) { "COMMAND_WIDGET_CAPTURE_CHANGED" }
            require(claim.storageId != null && claim.storageId == namespace.storage) { "COMMAND_WIDGET_REPLICA_CHANGED" }
        } else if (CountCommandLegacyInput.isLegacy(intent)) {
            // Current legacy repository COUNT inputs have no Widget claim. A legacy metric
            // child may retain the original common Widget identity and must never re-sign it.
            input["widget_claim"]?.takeUnless { it == JsonNull }?.let { value ->
                val claim = LegacyWidgetRestartClaim.decode(CountCommandRecords.canonical(value))
                require(claim.session() == access.session && claim.deviceId == access.capturedDeviceId &&
                    claim.habitUuid == CountCommandRecords.uuid(input, "habit_uuid") &&
                    claim.habitId == CountCommandRecords.integer(input, "habit_id")) { "COMMAND_WIDGET_CAPTURE_CHANGED" }
                require(claim.storageId != null && claim.storageId == namespace.storage) { "COMMAND_WIDGET_REPLICA_CHANGED" }
            }
        } else {
            val key = if (intent.action == CountCommandRecords.Action.LINKED_METRICS) "source_claim" else "widget_claim"
            input[key]?.takeUnless { it == JsonNull }?.let { value ->
                val claim = WidgetFactClaim.decode(CountCommandRecords.canonical(value))
                require(claim.session() == access.session && claim.deviceId == access.capturedDeviceId &&
                    claim.habitUuid == CountCommandRecords.uuid(input, "habit_uuid")) { "COMMAND_WIDGET_CAPTURE_CHANGED" }
                input["habit_id"]?.let { require(claim.habitId == CountCommandRecords.integer(input, "habit_id")) { "COMMAND_WIDGET_CAPTURE_CHANGED" } }
                require(claim.storageId != null && claim.storageId == namespace.storage) { "COMMAND_WIDGET_REPLICA_CHANGED" }
                if (intent.action == CountCommandRecords.Action.LINKED_METRICS)
                    require(claim.completionUuid == CountCommandRecords.uuid(input, "source_fact_uuid")) { "COMMAND_CHILD_SOURCE_CHANGED" }
            }
        }
    }
}
