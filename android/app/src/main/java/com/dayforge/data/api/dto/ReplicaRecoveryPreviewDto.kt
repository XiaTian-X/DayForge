@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class)

package com.dayforge.data.api.dto

import com.dayforge.domain.model.ContractBooleanSerializer
import com.dayforge.domain.model.ContractIntegerSerializer
import com.dayforge.domain.model.isContractUuid
import com.dayforge.domain.model.AssetRecord
import com.dayforge.domain.model.AssetSyncContext
import com.dayforge.domain.model.IconPack
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class RecoveryPackVersion(@SerialName("pack_id") val packId: String,
    @Serializable(with = ContractIntegerSerializer::class) val revision: Int) {
    init { require(isContractUuid(packId) && revision > 0) }
}

/** Read-only metadata diagnostics; ready_variants does not prove the native files. */
@Serializable
internal data class ReplicaRecoveryMaterialPreview(
    @Serializable(with = ContractIntegerSerializer::class)
    @SerialName("material_contract") val materialContract: Int,
    @SerialName("requested_pack") val requestedPack: RecoveryPackVersion?,
    val pack: IconPack?, val assets: List<AssetRecord>,
    @SerialName("missing_assets") val missingAssets: List<String>
) {
    init {
        require(materialContract == 1 && assets.size <= 50_000 && missingAssets.size <= 50_000)
        require(pack == null || requestedPack == RecoveryPackVersion(pack.packId, pack.revision))
        val ids = assets.map { it.asset.assetId }
        require(ids == ids.distinct().sorted() && missingAssets == missingAssets.distinct().sorted())
        require(missingAssets.all(::isContractUuid) && ids.toSet().intersect(missingAssets.toSet()).isEmpty())
        val present = assets.associate { it.asset.assetId to it.asset }
        require(pack?.assets.orEmpty().all { present[it.assetId]?.let { known -> known == it } ?: true })
    }
}

internal fun recoveryMaterialIds(snapshot: RoundSyncBootstrapResponse, pack: IconPack?): Set<String> = buildSet {
    addAll(pack?.assets.orEmpty().map { it.assetId })
    for (change in snapshot.changes) if (change.entityType in setOf("plan_node", "metric")) {
        val icon = Json.decodeFromJsonElement(ObjectAppearance.serializer(), change.payload.getValue("appearance")).icon
        if (icon is IconReference.Asset) add(icon.assetId)
    }
}

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
    @SerialName("active_timer") val activeTimer: TimerSessionResponse?,
    @SerialName("material_preview") val materialPreview: ReplicaRecoveryMaterialPreview? = null
) {
    init {
        require(recoveryContract == 1 && protocolVersion == 5)
        require(listOf(accountId, serverInstanceId, syncEpoch).all(::isContractUuid))
        materialPreview?.let { material ->
            require(material.assets.all { it.context == AssetSyncContext(serverInstanceId, syncEpoch, device.deviceId) })
            require(material.assets.map { it.asset.assetId }.toSet() + material.missingAssets == recoveryMaterialIds(snapshot, material.pack))
        }
        activeTimer?.let { timer ->
            require(timer.state in setOf("running", "paused") && timer.endedAt == null && timer.completedEventId == null)
            snapshot.metadata().requireBirth("timer_session", timer.sessionId, timer.activityUuid)
            require(snapshot.changes.any { it.entityType == "plan_node" && it.operation == "upsert" && it.entityUuid == timer.activityUuid })
        }
    }
}
