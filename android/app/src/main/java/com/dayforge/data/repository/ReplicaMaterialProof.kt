package com.dayforge.data.repository

import com.dayforge.data.appearance.*
import com.dayforge.data.local.LocalIconAccess
import com.dayforge.data.local.ReplicaSwitchStage
import com.dayforge.domain.model.*
import com.dayforge.domain.service.IconMaterialOutcome
import kotlinx.serialization.json.*

internal sealed interface ReplicaMaterialVerification {
    data class Waiting(val outcome: IconMaterialOutcome) : ReplicaMaterialVerification {
        init { require(outcome != IconMaterialOutcome.COMPLETE) }
    }
    data class Verified(val proof: ReplicaMaterialProof) : ReplicaMaterialVerification
}

/** Complete fresh material evidence, NOT activation/consent or a cached resume authorization.
 * Actual stage/SQL/archive/auth/material must be rebuilt again before consumer gates can open.
 */
internal class ReplicaMaterialProof private constructor(val recoveryId: String, val access: LocalIconAccess,
    val selection: AccountIconSelection, val hash: String, val unresolvedRoles: Set<String>,
    val stageGeneration: Long, val projectionHash: String, val archiveHash: String) {
    val activationAuthorized: Boolean get() = false

    companion object {
        internal fun checked(stage: ReplicaSwitchStage, context: AccountIconContext, uses: List<ConfigIconUse>,
            database: IconRecoveryDatabaseProof, files: AccountIconRecoverySnapshot): ReplicaMaterialProof {
            requireNotNull(context.recovery).requireStage(stage)
            val intent = requireNotNull(context.recovery).intent
            check(database.original == context.access && files.namespace == context.namespace &&
                database.metadata == files.metadata) { "ICON_RECOVERY_SCOPE_CHANGED" }
            intent.requireRestored(database.metadata.selection)
            val catalog = database.metadata
            intent.requirePack(catalog.selection.pack?.let { chosen -> catalog.packs.singleOrNull {
                it.packId == chosen.packId && it.revision == chosen.revision
            } })
            val remote = database.remote
            check(remote.state == null && remote.entries.isEmpty() || remote.state?.let { it.cursor == it.through } == true) {
                "ICON_RECOVERY_CATALOG_INCOMPLETE"
            }
            check(remote.transfers.all { it.kind == "download" && it.state == "complete" } &&
                catalog.installations.all { it.validationProfile != null }) { "ICON_RECOVERY_CONTENT_PENDING" }
            val assets = catalog.assets.associateBy { it.assetId }
            val declared = remote.entries.filter { it.kind == "asset" }.map { it.targetId }.toSet()
            val packs = remote.entries.filter { it.kind == "pack" }.map { it.targetId to it.revision }.toSet()
            check(declared == assets.keys && packs == catalog.packs.map { it.packId to it.revision }.toSet()) {
                "ICON_RECOVERY_REMOTE_PROOF_MISSING"
            }
            val blobs = catalog.installations.associateBy { it.reservation.blob.sha256 }
            check(files.files.none { it.temporary } && files.files.map { it.blobHash }.toSet() == blobs.keys &&
                files.files.all { it.contentHash == it.blobHash && it.byteLength == blobs.getValue(it.blobHash).reservation.blob.byteLength }) {
                "ICON_RECOVERY_FILE_PROOF_MISSING"
            }
            val unresolved = sortedSetOf<String>()
            for (use in uses) {
                val resolved = when (val icon = use.reference) {
                    is IconReference.Asset -> requireNotNull(assets[icon.assetId]) { "ICON_RECOVERY_REFERENCE_MISSING" }
                    is IconReference.Role -> intent.pack?.roles?.get(icon.role)?.let { requireNotNull(assets[it]) }
                        .also { if (it == null) unresolved.add(icon.role) }
                }
                check(iconAllowed(use.reference, use.oneTime,
                    if (use.reference is IconReference.Asset) resolved else null) &&
                    (resolved == null || (resolved.purpose == "task") == use.oneTime)) { "ICON_RECOVERY_PURPOSE_INVALID" }
            }
            val witness = buildJsonObject {
                put("recovery_id", stage.recoveryId); put("projection_hash", stage.publishedSnapshot.target.projectionHash)
                put("archive_hash", requireNotNull(stage.archive).contentHash); put("material_database_hash", database.hash)
                put("files", buildJsonArray { for (file in files.files) add(buildJsonObject {
                    put("name", file.name); put("hash", file.contentHash); put("length", file.byteLength); put("operation_id", file.operationId)
                }) })
                put("uses", buildJsonArray { for (use in uses) add(buildJsonObject {
                    put("reference", Json.encodeToJsonElement(use.reference)); put("one_time", use.oneTime)
                }) })
            }
            return ReplicaMaterialProof(stage.recoveryId, context.access, catalog.selection,
                syncPayloadHash(canonicalReplicaJson(witness).toString()), java.util.Collections.unmodifiableSet(unresolved),
                stage.generation, stage.publishedSnapshot.target.projectionHash, requireNotNull(stage.archive).contentHash)
        }
    }
}
