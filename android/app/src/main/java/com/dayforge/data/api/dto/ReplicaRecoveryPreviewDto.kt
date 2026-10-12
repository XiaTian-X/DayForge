@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.api.dto

import com.dayforge.domain.model.ContractBooleanSerializer
import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.isContractUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Unlike the legacy response, no missing permission fields receive defaults. */
@Serializable
internal data class RecoveryDeviceProof(
    @SerialName("device_id") val deviceId: String,
    @SerialName("installation_id") val installationId: String,
    val platform: String,
    @SerialName("device_class") val deviceClass: String,
    @SerialName("app_version") val appVersion: String?,
    @SerialName("display_name") val displayName: String?,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("is_primary_editor") val isPrimaryEditor: Boolean,
    @Serializable(with = ContractBooleanSerializer::class)
    @SerialName("structural_edit_enabled") val structuralEditEnabled: Boolean,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("capability_revision") val capabilityRevision: Int,
    val capabilities: List<String>,
    @SerialName("last_seen_at") val lastSeenAt: String?
)

/** Evidence only: contains neither credentials nor an activation/confirmation ticket. */
@Serializable
internal data class ReplicaRecoveryPreviewResponse(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("recovery_contract") val recoveryContract: Int,
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("account_id") val accountId: String,
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("sync_epoch") val syncEpoch: String,
    val device: RecoveryDeviceProof,
    val snapshot: RoundSyncBootstrapResponse,
    @SerialName("active_timer") val activeTimer: TimerSessionResponse?
) {
    init {
        require(recoveryContract == 1 && protocolVersion == 5)
        require(listOf(accountId, serverInstanceId, syncEpoch).all(::isContractUuid))
        activeTimer?.let { timer ->
            require(timer.state in setOf("running", "paused") && timer.endedAt == null && timer.completedEventId == null)
            snapshot.metadata().requireBirth("timer_session", timer.sessionId, timer.activityUuid)
            require(snapshot.changes.any { it.entityType == "plan_node" && it.operation == "upsert" && it.entityUuid == timer.activityUuid })
        }
    }
}
