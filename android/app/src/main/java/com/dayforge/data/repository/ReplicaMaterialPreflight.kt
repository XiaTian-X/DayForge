package com.dayforge.data.repository

import com.dayforge.data.api.dto.*
import com.dayforge.data.appearance.AccountIconRecoveryMetadata
import com.dayforge.data.appearance.AccountIconSelection
import com.dayforge.domain.model.*
import java.util.Collections
import kotlinx.serialization.json.*

/** Fresh metadata diagnostics only: no file proof, selection, consent or activation permit. */
internal class ReplicaMaterialPreflight private constructor(val selection: AccountIconSelection,
    val missingPack: Boolean, val changedPack: Boolean, missingAssets: Set<String>,
    pendingVariants: Map<String, Set<String>>, unresolvedRoles: Set<String>) {
    val missingAssets: Set<String> = Collections.unmodifiableSet(missingAssets.toSortedSet())
    val pendingVariants: Map<String, Set<String>> = Collections.unmodifiableMap(pendingVariants.toSortedMap()
        .mapValues { Collections.unmodifiableSet(it.value.toSortedSet()) })
    val unresolvedRoles: Set<String> = Collections.unmodifiableSet(unresolvedRoles.toSortedSet())
    val activationAuthorized: Boolean get() = false
    val nativeFilesVerified: Boolean get() = false
    fun requireAvailable() {
        check(!missingPack) { "ICON_RECOVERY_PACK_MISSING" }
        check(!changedPack) { "ICON_RECOVERY_PACK_CHANGED" }
        check(missingAssets.isEmpty()) { "ICON_RECOVERY_REFERENCE_MISSING" }
        check(pendingVariants.isEmpty()) { "ICON_RECOVERY_CONTENT_PENDING" }
    }

    companion object {
        internal fun checked(response: ReplicaRecoveryPreviewResponse,
            original: AccountIconRecoveryMetadata): ReplicaMaterialPreflight {
            val material = requireNotNull(response.materialPreview) { "ICON_RECOVERY_PREFLIGHT_MISSING" }
            val selected = original.selection.pack
            check(material.requestedPack == selected?.let { RecoveryPackVersion(it.packId, it.revision) }) {
                "ICON_RECOVERY_SELECTION_CHANGED"
            }
            val oldPack = selected?.let { version -> requireNotNull(original.packs.singleOrNull {
                it.packId == version.packId && it.revision == version.revision
            }) { "ICON_RECOVERY_SOURCE_PACK_MISSING" } }
            val missingPack = selected != null && material.pack == null
            // Exact full immutable metadata, not UUID/revision alone or a display fallback.
            val changedPack = !missingPack && material.pack != oldPack
            val assets = material.assets.associateBy { it.asset.assetId }
            val pending = assets.mapValues { (_, record) ->
                (if (record.asset.dark == null) setOf("light") else setOf("light", "dark")) - record.readyVariants.toSet()
            }.filterValues { it.isNotEmpty() }
            val unresolved = sortedSetOf<String>()
            for (change in response.snapshot.changes) if (change.entityType in setOf("plan_node", "metric")) {
                val icon = Json.decodeFromJsonElement<ObjectAppearance>(change.payload.getValue("appearance")).icon
                val once = change.entityType == "plan_node" &&
                    (change.payload["activity"] as? JsonObject)?.get("completion_policy") == JsonPrimitive("one_and_done")
                val resolved = when (icon) {
                    is IconReference.Asset -> assets[icon.assetId]?.asset
                    is IconReference.Role -> material.pack?.roles?.get(icon.role)?.let { assets[it]?.asset }
                        .also { if (it == null) unresolved.add(icon.role) }
                }
                check((icon !is IconReference.Role || iconAllowed(icon, once)) &&
                    (resolved == null || (resolved.purpose == "task") == once)) { "ICON_RECOVERY_PURPOSE_INVALID" }
            }
            return ReplicaMaterialPreflight(original.selection, missingPack, changedPack,
                material.missingAssets.toSet(), pending, unresolved)
        }
    }
}
