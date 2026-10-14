package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.MaterialSocketServer
import com.dayforge.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Explicit socket fixture, not a claim of real FastAPI/file verification. */
internal fun recoveryMaterialReply(response: ReplicaRecoveryPreviewResponse, input: MaterialSocketServer.Input,
    packs: List<IconPack>, assets: List<IconAsset>): ReplicaRecoveryPreviewResponse {
    val url = ("http://localhost" + input.target).toHttpUrl()
    if (url.queryParameter("include_materials") == null) return response.copy(materialPreview = null)
    check(url.queryParameterValues("include_materials") == listOf("1"))
    val requested = url.queryParameter("pack_id")?.let {
        RecoveryPackVersion(it, requireNotNull(url.queryParameter("pack_revision")).toInt())
    }
    val pack = packs.singleOrNull { requested == RecoveryPackVersion(it.packId, it.revision) }
    val ids = recoveryMaterialIds(response.snapshot, pack)
    val found = assets.filter { it.assetId in ids }.sortedBy { it.assetId }
    val context = AssetSyncContext(response.serverInstanceId, response.syncEpoch, response.device.deviceId)
    return response.copy(materialPreview = ReplicaRecoveryMaterialPreview(1, requested, pack,
        found.map { AssetRecord(context, it, if (it.dark == null) listOf("light") else listOf("light", "dark")) },
        (ids - found.map { it.assetId }.toSet()).sorted()))
}
