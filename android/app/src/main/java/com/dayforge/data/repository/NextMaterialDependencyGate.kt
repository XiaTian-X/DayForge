package com.dayforge.data.repository

import com.dayforge.data.api.dto.SyncV2Operation
import com.dayforge.data.appearance.IconDependencyState
import com.dayforge.data.local.LocalSyncAccess
import com.dayforge.domain.model.IconReference
import com.dayforge.domain.model.ObjectAppearance
import com.dayforge.domain.service.AccountIconController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

internal class NextMaterialPending(val session: com.dayforge.data.local.LocalDataSession? = null,
    val completedAfter: Long? = null) :
    IllegalStateException("SYNC_MATERIAL_DEPENDENCY_PENDING")
internal class NextMaterialAttention : IllegalStateException("SYNC_MATERIAL_DEPENDENCY_BLOCKED")

internal fun fixedMaterialAsset(operation: SyncV2Operation): String? {
    if (operation.action != "upsert" || operation.entityType !in setOf("plan_node", "metric")) return null
    val appearance = operation.payload["appearance"] ?: return null
    return (Json.decodeFromJsonElement<ObjectAppearance>(appearance).icon as? IconReference.Asset)?.assetId
}

/** No business/account transaction may span the independent material database read. */
internal interface NextMaterialDependencyGate {
    suspend fun requireReady(access: LocalSyncAccess, declarations: Set<String>, content: Set<String>)
}

/** Primitive test senders without material storage fail closed for fixed references. */
internal object UnavailableMaterialDependencies : NextMaterialDependencyGate {
    override suspend fun requireReady(access: LocalSyncAccess, declarations: Set<String>, content: Set<String>) {
        if (declarations.isNotEmpty() || content.isNotEmpty()) throw NextMaterialAttention()
    }
}

@Singleton
internal class AccountMaterialDependencyGate @Inject constructor(private val icons: AccountIconController) : NextMaterialDependencyGate {
    override suspend fun requireReady(access: LocalSyncAccess, declarations: Set<String>, content: Set<String>) {
        if (declarations.isEmpty() && content.isEmpty()) return
        val context = icons.capture()
        check(context.access.session == access.session && context.access.deviceId == access.deviceId &&
            context.access.capabilityRevision == access.capabilityRevision &&
            context.access.canDeclare == ("structure.write" in access.capabilities)) { "SYNC_ACCOUNT_CHANGED" }
        when (icons.dependencies(context, declarations, content)) {
            IconDependencyState.READY -> Unit
            IconDependencyState.PENDING -> { icons.requestMaterialSync(); throw NextMaterialPending(access.session) }
            IconDependencyState.ATTENTION -> throw NextMaterialAttention()
        }
    }
}
